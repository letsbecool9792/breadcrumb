import assert from "node:assert/strict";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, test } from "node:test";
import type { Db } from "mongodb";
import { createApp } from "./app.ts";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import type { Embedder } from "./embeddings.ts";
import type { Extraction, Extractor } from "./gemini.ts";
import { parseMemory } from "./ingest.ts";
import { ensureIndexes, getMemory, memories } from "./memories.ts";

/** A link as the phone would send it, in epoch milliseconds. */
const sent = {
  id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  type: "LINK",
  hasLink: true,
  capturedAt: Date.UTC(2026, 3, 18, 10, 12, 33),
  updatedAt: Date.UTC(2026, 3, 18, 10, 12, 33),
  sourceApp: "com.android.chrome",
  sourceAppLabel: "Chrome",
  title: "Qualcomm Software Engineering Intern",
  rawText: "https://example.com/jobs/qualcomm-swe-intern",
  extractedText: null,
};

describe("parseMemory", () => {
  test("accepts what the phone sends and converts its timestamps", () => {
    const parsed = parseMemory(sent);

    assert.ok(parsed.ok, `expected ok, got ${JSON.stringify(parsed)}`);
    assert.equal(parsed.memory.id, sent.id);
    assert.equal(parsed.memory.type, "LINK");
    assert.deepEqual(parsed.memory.capturedAt, new Date(sent.capturedAt));
    assert.equal(parsed.memory.contentCreatedAt, null);
  });

  test("refuses a body that would store nothing findable", () => {
    for (const body of [null, "a string", [], {}, { id: "x" }, { id: "x", type: "NOPE", capturedAt: 1 }]) {
      const parsed = parseMemory(body);
      assert.ok(!parsed.ok, `${JSON.stringify(body)} should not parse`);
      assert.ok(parsed.errors.length > 0, "a refusal should say why");
    }
  });

  test("names every problem at once, not just the first", () => {
    const parsed = parseMemory({ id: "", type: "VIDEO", capturedAt: "yesterday" });

    assert.ok(!parsed.ok);
    assert.equal(parsed.errors.length, 3);
  });

  test("blank text is stored as absent, not as an empty string", () => {
    const parsed = parseMemory({ ...sent, title: "   ", rawText: "" });

    assert.ok(parsed.ok);
    assert.equal(parsed.memory.title, null);
    assert.equal(parsed.memory.rawText, null);
  });

  test("takes the person's note, and a blank one as none", () => {
    const noted = parseMemory({ ...sent, note: "  from Priya, for the Pune trip " });
    const blank = parseMemory({ ...sent, note: "   " });
    const absent = parseMemory(sent);

    assert.ok(noted.ok && blank.ok && absent.ok);
    assert.equal(noted.memory.note, "from Priya, for the Pune trip");
    assert.equal(blank.memory.note, null);
    assert.equal(absent.memory.note, null);
  });

  test("a note that is not text is refused", () => {
    const parsed = parseMemory({ ...sent, note: 42 });

    assert.ok(!parsed.ok);
    assert.deepEqual(parsed.errors, ["note must be a string or null"]);
  });

  test("fields the client made up are dropped rather than stored", () => {
    const parsed = parseMemory({ ...sent, localUri: "file:///secret.jpg", syncState: "SYNCED" });

    assert.ok(parsed.ok);
    assert.ok(!("localUri" in parsed.memory), "device-only fields must not reach the document");
    assert.ok(!("syncState" in parsed.memory));
  });
});

/**
 * The endpoint against the real database, with a stubbed model: what matters
 * here is what gets stored and what comes back, not Gemini's wording. The
 * live model has its own test in gemini.test.ts.
 */
describe(
  "POST /memories",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let server: Server;
    let base: string;
    let extraction: Extraction | Error;
    let asked: number;
    let embedding: number[] | Error;
    let embedded: string[];
    let embeddedCalls: number;

    /** Counts calls, not items: the point of batching is fewer calls. */
    const extract: Extractor = async (inputs) => {
      asked += 1;
      if (extraction instanceof Error) throw extraction;
      return new Map(inputs.map((input) => [input.index, extraction as Extraction]));
    };

    const embed: Embedder = async (texts) => {
      embedded.push(...texts);
      embeddedCalls += 1;
      if (embedding instanceof Error) throw embedding;
      return texts.map(() => embedding as number[]);
    };

    /** These tests never post a picture or search; both have their own suites. */
    const noImages = async () => {
      throw new Error("not used by these tests");
    };

    const post = (body: unknown) =>
      fetch(`${base}/memories`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });

    before(async () => {
      await connectMongo();
      // its own database: test files run as parallel processes, and sharing one
      // means each suite's cleanup wipes the other's documents mid-test
      database = db(`${databaseName()}_test_ingest`);
      await ensureIndexes(database);
      server = createApp({ log: false, extract, embed, extractImages: noImages, parseQuery: noImages, database }).listen(
        0,
        "127.0.0.1",
      );
      await once(server, "listening");
      base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    after(async () => {
      server.closeAllConnections();
      server.close();
      try {
        await memories(database).drop();
      } catch {
        // already gone
      } finally {
        await closeMongo();
      }
    });

    beforeEach(async () => {
      await memories(database).deleteMany({});
      asked = 0;
      embedded = [];
      embeddedCalls = 0;
      embedding = [0.6, 0.8];
      extraction = {
        summary: "Qualcomm software engineering internship, applications close April 30",
        kind: "job posting",
        entities: ["Qualcomm"],
        dates: ["2026-04-30"],
      };
    });

    test("stores a link with what Gemini made of it", async () => {
      const response = await post(sent);

      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), { id: sent.id, enriched: true, embedded: true });

      const stored = await getMemory(database, sent.id);
      assert.deepEqual(stored?.embedding, [0.6, 0.8]);
      assert.equal(stored?.embeddedWith?.dimensions, 768);
      assert.equal(stored?.title, sent.title);
      assert.equal(stored?.sourceAppLabel, "Chrome");
      // where the link goes, for "that link from <site>" however it arrived
      assert.deepEqual(stored?.linkSites, ["example.com"]);
      assert.equal(stored?.enrichment?.kind, "job posting");
      assert.deepEqual(stored?.enrichment?.entities, ["Qualcomm"]);
      assert.deepEqual(stored?.enrichment?.dates, ["2026-04-30"]);
      assert.ok(stored?.enrichment?.at instanceof Date);
    });

    test("a picture is dated by when it was taken, anything else by when it was saved", async () => {
      const taken = Date.UTC(2026, 3, 2, 9, 0, 0);
      await post([
        { ...sent, id: "link" },
        { ...sent, id: "photo", type: "IMAGE", hasLink: false, contentCreatedAt: taken },
      ]);

      // what "from April" filters on (4.3): a photo saved in September was still taken in April
      assert.deepEqual((await getMemory(database, "link"))?.datedAt, new Date(sent.capturedAt));
      assert.deepEqual((await getMemory(database, "photo"))?.datedAt, new Date(taken));
    });

    test("the original never leaves the phone", async () => {
      await post({ ...sent, localUri: "file:///data/originals/shot.jpg" });

      const stored = await getMemory(database, sent.id);
      assert.ok(stored && !("localUri" in stored), "a stored memory must hold no path to the original");
    });

    test("re-sending the same memory replaces it rather than duplicating", async () => {
      await post(sent);
      await post({ ...sent, title: "Qualcomm SWE Intern (reposted)" });

      assert.equal(await memories(database).countDocuments(), 1);
      assert.equal((await getMemory(database, sent.id))?.title, "Qualcomm SWE Intern (reposted)");
      assert.equal(asked, 2);
    });

    test("what gets embedded is the summary first, then the memory's own text", async () => {
      await post(sent);

      assert.equal(embedded.length, 1);
      assert.ok(embedded[0]?.startsWith("Qualcomm software engineering internship"));
      assert.ok(embedded[0]?.includes(sent.rawText), "the memory's own text belongs in the vector too");
    });

    test("a rate-limited memory is stored, and the phone is told to send it again", async () => {
      // the free tier's daily cap, mid-save
      extraction = new Error('{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}');

      const response = await post(sent);

      // 503, not 200: a success would mark it synced on the phone and leave it
      // unenriched for good, since nothing else ever enriches it
      assert.equal(response.status, 503);
      const body = (await response.json()) as { retryable: boolean; enriched: boolean };
      assert.equal(body.retryable, true);
      assert.equal(body.enriched, false);
      assert.ok(await getMemory(database, sent.id), "the memory itself is still stored");
    });

    test("re-sending unchanged text costs no model calls", async () => {
      await post(sent);
      assert.equal(asked, 1);
      assert.equal(embedded.length, 1);

      await post(sent);

      // what the model read has not changed, so neither would its answer
      assert.equal(asked, 1, "Gemini should not be asked twice about the same words");
      assert.equal(embedded.length, 1, "nor should the same text be embedded twice");
      const stored = await getMemory(database, sent.id);
      assert.equal(stored?.enrichment?.kind, "job posting");
      assert.deepEqual(stored?.embedding, [0.6, 0.8]);
    });

    test("a memory enriched before fingerprints existed is not paid for twice", async () => {
      await post(sent);
      // as documents looked before this step
      await memories(database).updateOne(
        { _id: sent.id },
        { $unset: { enrichedFrom: "", embeddedFrom: "" } },
      );

      await post(sent);

      assert.equal(asked, 1, "the stored enrichment should be recognised as current");
      assert.equal(embedded.length, 1);
    });

    test("text added after the first send is enriched and embedded again", async () => {
      await post(sent);

      // OCR finished on the phone and the memory came back with more to read
      await post({ ...sent, extractedText: "Qualcomm | Software Engineering Intern | Bengaluru" });

      assert.equal(asked, 2);
      assert.equal(embedded.length, 2);
    });

    test("a note is stored and embedded", async () => {
      await post({ ...sent, note: "Priya said apply before the long weekend" });

      assert.equal((await getMemory(database, sent.id))?.note, "Priya said apply before the long weekend");
      assert.ok(embedded[0]?.includes("Priya said apply before the long weekend"));
    });

    test("writing a note later costs an embedding, never a model call", async () => {
      await post(sent);
      assert.equal(asked, 1);

      await post({ ...sent, note: "Priya said apply before the long weekend" });

      // the model describes the thing, and the thing has not changed
      assert.equal(asked, 1, "a note must not send the memory back to the model");
      assert.equal(embedded.length, 2, "but the note is part of what is embedded");
      const stored = await getMemory(database, sent.id);
      assert.equal(stored?.enrichment?.kind, "job posting", "the enrichment is kept");
    });

    test("a picture with nothing in it but a note is embedded by the note, without a model call", async () => {
      const response = await post({
        ...sent,
        id: "noted-photo",
        type: "IMAGE",
        hasLink: false,
        title: null,
        rawText: null,
        extractedText: "",
        note: "the balcony view from Priya's new flat",
      });

      assert.deepEqual(await response.json(), { id: "noted-photo", enriched: false, embedded: true });
      assert.equal(asked, 0, "the picture itself is read by /memories/images, not from its note");
      assert.deepEqual(embedded, ["the balcony view from Priya's new flat"]);
    });

    test("a memory is stored even when the model fails for good, and says why", async () => {
      extraction = new Error("400 invalid argument");

      const response = await post(sent);

      assert.equal(response.status, 200, "a permanent failure is not worth retrying");
      assert.deepEqual(await response.json(), {
        id: sent.id,
        enriched: false,
        embedded: true,
        reason: "400 invalid argument",
      });
      const stored = await getMemory(database, sent.id);
      assert.equal(stored?.rawText, sent.rawText, "the memory itself must survive a failed model call");
      assert.equal(stored?.enrichmentError, "400 invalid argument");
      assert.equal(stored?.enrichment, undefined);
      assert.ok(stored?.embedding, "an unenriched memory is still worth embedding");
    });

    test("a memory is stored even when embedding fails, and says why", async () => {
      embedding = new Error("429 rate limited");

      const response = await post(sent);

      assert.equal(response.status, 503, "rate limits pass; the phone should try again");
      assert.deepEqual(await response.json(), {
        id: sent.id,
        enriched: true,
        embedded: false,
        reason: "429 rate limited",
        retryable: true,
      });
      const stored = await getMemory(database, sent.id);
      assert.equal(stored?.embeddingError, "429 rate limited");
      assert.equal(stored?.embedding, undefined);
      assert.ok(stored?.enrichment, "a failed embedding must not cost us the enrichment");
    });

    test("an image with no text yet is stored without asking the model", async () => {
      const response = await post({
        ...sent,
        id: "image-without-text",
        type: "IMAGE",
        hasLink: false,
        title: null,
        rawText: null,
        extractedText: null,
      });

      assert.deepEqual(await response.json(), {
        id: "image-without-text",
        enriched: false,
        embedded: false,
        reason: "nothing to read yet",
      });
      assert.equal(asked, 0, "nothing to read means nothing to ask about");
      assert.equal(embedded.length, 0, "and nothing to embed");
      assert.ok(await getMemory(database, "image-without-text"));
    });

    test("a batch of memories costs one model call, not one each", async () => {
      // the whole point on a free tier, which counts requests rather than items
      const batch = [0, 1, 2, 3, 4].map((n) => ({ ...sent, id: `batch-${n}`, title: `Item ${n}` }));

      const response = await post(batch);

      assert.equal(response.status, 200);
      const body = (await response.json()) as { results: { id: string; enriched: boolean }[] };
      assert.equal(body.results.length, 5);
      assert.ok(body.results.every((result) => result.enriched));
      assert.equal(asked, 1, "one Gemini call for the batch");
      assert.equal(embeddedCalls, 1, "one embedding call for the batch");
      assert.equal(await memories(database).countDocuments(), 5);
    });

    test("in a batch, only what is new is sent to the model", async () => {
      await post([{ ...sent, id: "already" }]);
      asked = 0;
      embedded = [];

      await post([{ ...sent, id: "already" }, { ...sent, id: "fresh" }]);

      assert.equal(asked, 1);
      assert.equal(embedded.length, 1, "only the new memory needed reading");
      assert.ok(await getMemory(database, "already"));
      assert.ok(await getMemory(database, "fresh"));
    });

    test("a memory the model skips is stored, and says so", async () => {
      extraction = {
        summary: "answered for one item only",
        kind: "note",
        entities: [],
        dates: [],
      };
      // an extractor that answers for the first item and ignores the rest
      const partial: Extractor = async (inputs) => {
        asked += 1;
        const first = inputs[0];
        return first ? new Map([[first.index, extraction as Extraction]]) : new Map();
      };
      const app = createApp({ log: false, extract: partial, embed, extractImages: noImages, parseQuery: noImages, database });
      const server2 = app.listen(0, "127.0.0.1");
      await once(server2, "listening");
      const base2 = `http://127.0.0.1:${(server2.address() as AddressInfo).port}`;

      try {
        const response = await fetch(`${base2}/memories`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify([{ ...sent, id: "answered" }, { ...sent, id: "skipped" }]),
        });
        const body = (await response.json()) as { results: { id: string; enriched: boolean; reason?: string }[] };

        assert.deepEqual(body.results.map((result) => result.enriched), [true, false]);
        assert.match(body.results[1]?.reason ?? "", /returned nothing/);
        // stored regardless, and still embedded from its own text
        const stored = await getMemory(database, "skipped");
        assert.ok(stored?.embedding, "an unenriched memory is still worth embedding");
      } finally {
        server2.closeAllConnections();
        server2.close();
      }
    });

    test("too many memories at once is refused", async () => {
      const response = await post(Array.from({ length: 51 }, (_, n) => ({ ...sent, id: `over-${n}` })));

      assert.equal(response.status, 400);
      assert.equal(await memories(database).countDocuments(), 0);
    });

    test("an invalid body is refused with reasons and stores nothing", async () => {
      const response = await post({ id: "bad", type: "VIDEO" });

      assert.equal(response.status, 400);
      const body = (await response.json()) as { error: string; invalid: { index: number; errors: string[] }[] };
      assert.equal(body.error, "invalid memory");
      // which item, and what is wrong with it: a batch must say both
      assert.deepEqual(body.invalid.map((item) => item.index), [0]);
      assert.ok((body.invalid[0]?.errors.length ?? 0) > 0);
      assert.equal(await memories(database).countDocuments(), 0);
      assert.equal(asked, 0);
    });
  },
);

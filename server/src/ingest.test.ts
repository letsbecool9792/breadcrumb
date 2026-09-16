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

    const extract: Extractor = async () => {
      asked += 1;
      if (extraction instanceof Error) throw extraction;
      return extraction;
    };

    const embed: Embedder = async (text) => {
      embedded.push(text);
      if (embedding instanceof Error) throw embedding;
      return embedding;
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
      server = createApp({ log: false, extract, embed, database }).listen(0, "127.0.0.1");
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
      assert.equal(stored?.enrichment?.kind, "job posting");
      assert.deepEqual(stored?.enrichment?.entities, ["Qualcomm"]);
      assert.deepEqual(stored?.enrichment?.dates, ["2026-04-30"]);
      assert.ok(stored?.enrichment?.at instanceof Date);
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

    test("a memory is stored even when the model fails, and says why", async () => {
      extraction = new Error("503 model overloaded");

      const response = await post(sent);

      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), {
        id: sent.id,
        enriched: false,
        embedded: true,
        reason: "503 model overloaded",
      });
      const stored = await getMemory(database, sent.id);
      assert.equal(stored?.rawText, sent.rawText, "the memory itself must survive a failed model call");
      assert.equal(stored?.enrichmentError, "503 model overloaded");
      assert.equal(stored?.enrichment, undefined);
      assert.ok(stored?.embedding, "an unenriched memory is still worth embedding");
    });

    test("a memory is stored even when embedding fails, and says why", async () => {
      embedding = new Error("429 rate limited");

      const response = await post(sent);

      assert.deepEqual(await response.json(), {
        id: sent.id,
        enriched: true,
        embedded: false,
        reason: "429 rate limited",
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

    test("an invalid body is refused with reasons and stores nothing", async () => {
      const response = await post({ id: "bad", type: "VIDEO" });

      assert.equal(response.status, 400);
      const body = (await response.json()) as { error: string; details: string[] };
      assert.equal(body.error, "invalid memory");
      assert.ok(body.details.length > 0);
      assert.equal(await memories(database).countDocuments(), 0);
      assert.equal(asked, 0);
    });
  },
);

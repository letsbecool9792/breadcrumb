import assert from "node:assert/strict";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, test, type TestContext } from "node:test";
import type { Db } from "mongodb";
import { createApp } from "./app.ts";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import type { Embedder, EmbeddingPurpose } from "./embeddings.ts";
import { ensureVectorIndex, type MemoryDoc, memories, VECTOR_INDEX, vectorIndexDefinition } from "./memories.ts";
import { DEFAULT_LIMIT, MAX_LIMIT, MAX_QUERY_CHARS, parseSearch, vectorSearchPipeline } from "./search.ts";

const unused = async () => {
  throw new Error("not used by these tests");
};

/** Serves the app on a free port; returns its base URL and a way to stop it. */
async function serve(options: { embed: Embedder; database: Db }) {
  const server: Server = createApp({ log: false, extract: unused, extractImages: unused, ...options }).listen(
    0,
    "127.0.0.1",
  );
  await once(server, "listening");
  return {
    base: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    stop: () => {
      server.closeAllConnections();
      server.close();
    },
  };
}

describe("parseSearch", () => {
  test("takes the phrase, tidied, with the default limit", () => {
    const parsed = parseSearch({ q: "  that   internship\nscreenshot " });

    assert.deepEqual(parsed, { ok: true, request: { query: "that internship screenshot", limit: DEFAULT_LIMIT } });
  });

  test("takes a limit up to the ceiling", () => {
    assert.deepEqual(parseSearch({ q: "x", limit: "5" }), { ok: true, request: { query: "x", limit: 5 } });
    assert.deepEqual(parseSearch({ q: "x", limit: String(MAX_LIMIT) }), {
      ok: true,
      request: { query: "x", limit: MAX_LIMIT },
    });
  });

  test("refuses a search with nothing to search for", () => {
    for (const params of [{}, { q: "" }, { q: "   " }, { q: ["a", "b"] }]) {
      const parsed = parseSearch(params);
      assert.ok(!parsed.ok, `${JSON.stringify(params)} should not parse`);
      assert.match(parsed.error, /q is required/);
    }
  });

  test("refuses a document pasted in as a search", () => {
    const parsed = parseSearch({ q: "x".repeat(MAX_QUERY_CHARS + 1) });

    assert.ok(!parsed.ok);
    assert.match(parsed.error, /longer than/);
  });

  test("refuses a limit that is not a whole number in range", () => {
    for (const limit of ["0", "-1", "2.5", "ten", "", String(MAX_LIMIT + 1)]) {
      assert.ok(!parseSearch({ q: "x", limit }).ok, `limit ${JSON.stringify(limit)} should be refused`);
    }
  });
});

describe("vectorSearchPipeline", () => {
  test("names the fields that leave, and the vector is not one of them", () => {
    const project = (vectorSearchPipeline([1, 0], 5)[1] as { $project: Record<string, unknown> }).$project;

    // an inclusion list: a field added to the document later stays in the cloud
    assert.ok(Object.values(project).every((value) => value === 1 || typeof value === "object"));
    for (const field of ["embedding", "embeddedFrom", "enrichedFrom", "sourceApp"]) {
      assert.ok(!(field in project), `${field} must not be sent back`);
    }
  });
});

/**
 * The route's refusals and its handling of a failing model. None of these may
 * reach the database, and the stand-in below fails the test if one does.
 */
describe("GET /search, before any searching", () => {
  const noDatabase = new Proxy({} as Db, {
    get() {
      throw new Error("this request should never have reached the database");
    },
  });

  let embedFailure: Error;
  let app: Awaited<ReturnType<typeof serve>>;

  before(async () => {
    app = await serve({
      embed: async () => {
        throw embedFailure;
      },
      database: noDatabase,
    });
  });

  after(() => app.stop());

  test("a search with no phrase is refused, with the reason", async () => {
    const response = await fetch(`${app.base}/search`);

    assert.equal(response.status, 400);
    assert.match(((await response.json()) as { error: string }).error, /q is required/);
  });

  test("a bad limit is refused", async () => {
    const response = await fetch(`${app.base}/search?q=internship&limit=500`);

    assert.equal(response.status, 400);
  });

  test("a busy model is a 503 the phone may retry", async () => {
    embedFailure = new Error('{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}');

    const response = await fetch(`${app.base}/search?q=internship`);

    assert.equal(response.status, 503);
    const body = (await response.json()) as { retryable?: boolean; reason: string };
    assert.equal(body.retryable, true);
    assert.match(body.reason, /RESOURCE_EXHAUSTED/);
  });

  test("a model that refuses outright is a 502, not worth retrying", async () => {
    embedFailure = new Error("400 API key not valid");

    const response = await fetch(`${app.base}/search?q=internship`);

    assert.equal(response.status, 502);
    assert.equal(((await response.json()) as { retryable?: boolean }).retryable, undefined);
  });
});

/** Four dimensions, so which memory is nearest is obvious by reading the numbers. */
const DIMENSIONS = 4;

const base = {
  hasLink: false,
  capturedAt: new Date("2026-04-18T10:12:33.000Z"),
  updatedAt: new Date("2026-04-18T10:12:33.000Z"),
  syncedAt: new Date("2026-04-18T10:12:40.000Z"),
  sourceApp: null,
  sourceAppLabel: null,
  title: null,
  rawText: null,
  extractedText: null,
};

const seeded: MemoryDoc[] = [
  {
    ...base,
    _id: "internship",
    type: "LINK",
    hasLink: true,
    sourceApp: "com.android.chrome",
    sourceAppLabel: "Chrome",
    title: "Qualcomm Software Engineering Intern",
    rawText: "https://example.com/jobs/qualcomm-swe-intern",
    enrichment: {
      summary: "Qualcomm software internship, applications close April 30",
      kind: "job posting",
      entities: ["Qualcomm"],
      dates: ["2026-04-30"],
      at: new Date(),
    },
    embedding: [1, 0, 0, 0],
  },
  {
    ...base,
    _id: "notes",
    type: "TEXT",
    rawText: "intern interview prep: arrays, graphs, one systems question",
    embedding: [0.8, 0.6, 0, 0],
  },
  {
    ...base,
    _id: "whiteboard",
    type: "IMAGE",
    extractedText: "boxes and arrows",
    enrichment: {
      summary: "Whiteboard from the architecture session",
      kind: "photo",
      entities: [],
      dates: [],
      readText: "A whiteboard covered in boxes and arrows",
      at: new Date(),
    },
    embedding: [0, 0.6, 0.8, 0],
  },
  {
    ...base,
    _id: "dinner",
    type: "TEXT",
    rawText: "Naru's in Indiranagar does omakase, book two weeks ahead",
    embedding: [0, 0, 0, 1],
  },
  // an image OCR has not read yet: stored, but nothing to embed
  { ...base, _id: "unread", type: "IMAGE" },
];

const embeddedCount = seeded.filter((doc) => doc.embedding).length;

/** What each test phrase embeds to. A phrase not listed here is a test bug. */
const phrases: Record<string, number[]> = {
  "that internship": [1, 0, 0, 0],
  "restaurant someone recommended": [0, 0, 0, 1],
};

/**
 * Against a real Atlas vector index, since what is worth checking -- that the
 * pipeline is one Atlas accepts, and ranks as it should -- is Atlas's
 * behaviour. The model is stubbed: the live embedding test in
 * embeddings.test.ts covers what the words mean.
 *
 * The index lives on this suite's own collection and goes when the collection
 * is dropped. An M0 cluster takes about half a minute to make a new one
 * queryable, which is most of this suite's running time.
 */
describe(
  "GET /search against Atlas",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let app: Awaited<ReturnType<typeof serve>>;
    let purposes: EmbeddingPurpose[];
    /** Set when Atlas would not give us an index to search; every test then skips. */
    let unavailable: string | undefined;

    const embed: Embedder = async (texts, purpose) => {
      purposes.push(purpose);
      return texts.map((text) => {
        const vector = phrases[text];
        if (!vector) throw new Error(`no test vector for ${JSON.stringify(text)}`);
        return vector;
      });
    };

    const search = async (t: TestContext, query: string, limit?: number) => {
      if (unavailable) {
        t.skip(unavailable);
        return undefined;
      }
      const params = new URLSearchParams({ q: query, ...(limit ? { limit: String(limit) } : {}) });
      const response = await fetch(`${app.base}/search?${params}`);
      assert.equal(response.status, 200);
      return (await response.json()) as { query: string; results: (Record<string, unknown> & { id: string; score: number })[] };
    };

    before(async () => {
      await connectMongo();
      database = db(`${databaseName()}_test_search`);
      purposes = [];

      // deleteMany, not drop: a run that died before its cleanup leaves the
      // collection and its index behind, and reusing that index saves the wait
      await memories(database).deleteMany({});
      await memories(database).insertMany(seeded);

      const state = await ensureVectorIndex(database, DIMENSIONS);
      if (state === "refused") {
        unavailable = `this database user cannot create search indexes; see ${VECTOR_INDEX} in index.ts`;
      } else {
        unavailable = await waitUntilSearchable(database);
      }

      app = await serve({ embed, database });
    });

    after(async () => {
      app?.stop();
      try {
        // dropping the collection takes its search index with it, returning the
        // slot: M0 allows three search indexes across the whole cluster
        await memories(database).drop();
      } catch {
        // already gone
      } finally {
        await closeMongo();
      }
    });

    test("the nearest memory comes first, and scores only fall from there", async (t) => {
      const body = await search(t, "that internship");
      if (!body) return;

      assert.deepEqual(body.results.slice(0, 2).map((hit) => hit.id), ["internship", "notes"]);
      assert.equal(body.results[0]?.score, 1, "an identical vector scores 1");
      const scores = body.results.map((hit) => hit.score);
      assert.deepEqual(scores, [...scores].sort((a, b) => b - a), "results must be best first");
    });

    test("a different phrase finds a different memory", async (t) => {
      const body = await search(t, "restaurant someone recommended");
      if (!body) return;

      assert.equal(body.results[0]?.id, "dinner");
    });

    test("the phrase is embedded as a query, not as a document", async (t) => {
      purposes = [];
      const body = await search(t, "that internship");
      if (!body) return;

      // the two task types land in different places; mixing them costs quality
      assert.deepEqual(purposes, ["query"]);
    });

    test("a result says what it is, what it says and why it matched, and nothing more", async (t) => {
      const body = await search(t, "that internship");
      if (!body) return;

      assert.equal(body.query, "that internship");
      assert.deepEqual(body.results[0], {
        id: "internship",
        score: 1,
        type: "LINK",
        hasLink: true,
        capturedAt: "2026-04-18T10:12:33.000Z",
        sourceAppLabel: "Chrome",
        title: "Qualcomm Software Engineering Intern",
        rawText: "https://example.com/jobs/qualcomm-swe-intern",
        extractedText: null,
        readText: null,
        summary: "Qualcomm software internship, applications close April 30",
        kind: "job posting",
      });
      // the vector stays in the cloud: no client has a use for it
      for (const hit of body.results) {
        for (const field of ["embedding", "enrichment", "_id"]) {
          assert.ok(!(field in hit), `${field} must not be in a result`);
        }
      }
    });

    test("a picture's result carries what OCR read and what the model saw", async (t) => {
      const body = await search(t, "that internship", MAX_LIMIT);
      if (!body) return;

      const whiteboard = body.results.find((hit) => hit.id === "whiteboard");
      assert.equal(whiteboard?.extractedText, "boxes and arrows");
      assert.equal(whiteboard?.readText, "A whiteboard covered in boxes and arrows");
    });

    test("an unenriched memory has no summary, rather than an empty one", async (t) => {
      const body = await search(t, "that internship");
      if (!body) return;

      const notes = body.results.find((hit) => hit.id === "notes");
      assert.equal(notes?.summary, null);
      assert.equal(notes?.kind, null);
    });

    test("a memory with no embedding yet is never a result", async (t) => {
      const body = await search(t, "that internship", MAX_LIMIT);
      if (!body) return;

      assert.equal(body.results.length, embeddedCount);
      assert.ok(!body.results.some((hit) => hit.id === "unread"));
    });

    test("the limit caps the list", async (t) => {
      const body = await search(t, "that internship", 2);
      if (!body) return;

      assert.equal(body.results.length, 2);
    });
  },
);

/**
 * Waits for the new index to be built and to hold every seeded memory.
 * Returns a reason to skip if Atlas is slow, and throws if the index failed,
 * since a definition Atlas rejects is our bug rather than its capacity.
 */
async function waitUntilSearchable(database: Db, timeoutMs = 180_000): Promise<string | undefined> {
  const deadline = Date.now() + timeoutMs;
  const probe = vectorSearchPipeline([1, 0, 0, 0], MAX_LIMIT);

  while (Date.now() < deadline) {
    const [index] = (await memories(database).listSearchIndexes(VECTOR_INDEX).toArray()) as {
      status?: string;
      queryable?: boolean;
    }[];
    if (index?.status === "FAILED") {
      throw new Error(`the ${VECTOR_INDEX} index failed to build: ${JSON.stringify(index)}`);
    }
    if (index?.queryable) {
      const found = await memories(database).aggregate(probe).toArray();
      if (found.length === embeddedCount) return undefined;
    }
    await new Promise((resume) => setTimeout(resume, 1_000));
  }
  return `the test index was not searchable after ${timeoutMs / 1000}s (definition: ${JSON.stringify(
    vectorIndexDefinition(DIMENSIONS),
  )})`;
}

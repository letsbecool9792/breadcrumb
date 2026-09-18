import assert from "node:assert/strict";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, describe, test, type TestContext } from "node:test";
import type { Db } from "mongodb";
import { createApp } from "./app.ts";
import { closeMongo, connectMongo, db } from "./db.ts";
import { EMBEDDING_DIMENSIONS, type Embedder, type EmbeddingPurpose, normalize } from "./embeddings.ts";
import {
  ensureTextIndex,
  ensureVectorIndex,
  type MemoryDoc,
  memories,
  TEXT_INDEX,
  VECTOR_INDEX,
} from "./memories.ts";
import {
  DEFAULT_LIMIT,
  MAX_LIMIT,
  MAX_QUERY_CHARS,
  parseSearch,
  type SearchHit,
  searchMemories,
  searchPipeline,
} from "./search.ts";

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

describe("searchPipeline", () => {
  type Stage = Record<string, Record<string, unknown>>;
  const stage = (pipeline: object[], name: string) =>
    (pipeline as Stage[]).find((candidate) => name in candidate)?.[name] as Record<string, unknown>;

  test("names the fields that leave, and the vector is not one of them", () => {
    const project = stage(searchPipeline("x", [1, 0], 5), "$project");

    // an inclusion list: a field added to the document later stays in the cloud
    assert.ok(Object.values(project).every((value) => value === 1 || typeof value === "object"));
    for (const field of ["embedding", "embeddedFrom", "enrichedFrom", "sourceApp"]) {
      assert.ok(!(field in project), `${field} must not be sent back`);
    }
  });

  test("a filter narrows both halves, not just one", () => {
    // narrowing one half only would let the other bring back what was filtered out
    const fusion = stage(searchPipeline("x", [1, 0], 5, { sourceAppLabel: "WhatsApp" }), "$rankFusion");
    const halves = (fusion["input"] as { pipelines: Record<string, Stage[]> }).pipelines;

    assert.deepEqual(halves["vector"]?.[0]?.["$vectorSearch"]?.["filter"], { sourceAppLabel: { $eq: "WhatsApp" } });
    const compound = halves["text"]?.[0]?.["$search"]?.["compound"] as { filter?: unknown };
    assert.deepEqual(compound.filter, [{ equals: { path: "sourceAppLabel", value: "WhatsApp" } }]);
  });

  test("with no filter, neither half is narrowed", () => {
    const fusion = stage(searchPipeline("x", [1, 0], 5), "$rankFusion");
    const halves = (fusion["input"] as { pipelines: Record<string, Stage[]> }).pipelines;

    assert.ok(!("filter" in (halves["vector"]?.[0]?.["$vectorSearch"] ?? {})));
    assert.ok(!("filter" in (halves["text"]?.[0]?.["$search"]?.["compound"] as object)));
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

/**
 * Marks every document these tests write into the real collection, and is the
 * filter every search here runs under. No real memory carries it: a source
 * label is the name of an installed app.
 */
const TEST_LABEL = "breadcrumb-test";

/** A unit vector at the stored size, leaning along the given axes. */
function direction(...weights: [axis: number, weight: number][]): number[] {
  const vector = new Array<number>(EMBEDDING_DIMENSIONS).fill(0);
  for (const [axis, weight] of weights) vector[axis] = weight;
  return normalize(vector);
}

const base = {
  hasLink: false,
  capturedAt: new Date("2026-04-18T10:12:33.000Z"),
  updatedAt: new Date("2026-04-18T10:12:33.000Z"),
  syncedAt: new Date("2026-04-18T10:12:40.000Z"),
  sourceApp: null,
  sourceAppLabel: TEST_LABEL,
  title: null,
  rawText: null,
  extractedText: null,
};

/**
 * Which memory is nearest to which phrase is set by hand, one axis per idea,
 * so every ranking below can be worked out by reading the numbers.
 */
const seeded: MemoryDoc[] = [
  // a one-word memory, nearest in meaning to "government" but without the word
  { ...base, _id: "breadcrumb-test-goes", type: "TEXT", rawText: "goes", embedding: direction([0, 1]) },
  // the word appears once, deep in a screenshot about something else entirely
  {
    ...base,
    _id: "breadcrumb-test-reddit",
    type: "IMAGE",
    extractedText: "This happens when the government is trying to get you. Your days are numbered.",
    enrichment: {
      summary: "Screenshot of comments joking about a parallel dimension",
      kind: "screenshot",
      entities: [],
      dates: [],
      at: new Date(),
    },
    embedding: direction([1, 1]),
  },
  {
    ...base,
    _id: "breadcrumb-test-internship",
    type: "LINK",
    hasLink: true,
    title: "Qualcomm Software Engineering Intern",
    rawText: "https://example.com/jobs/swe-intern-2026",
    enrichment: {
      summary: "Qualcomm software internship, applications close April 30",
      kind: "job posting",
      entities: ["Qualcomm"],
      dates: ["2026-04-30"],
      at: new Date(),
    },
    embedding: direction([3, 0.6], [4, 0.8]),
  },
  // nearest in meaning to "Qualcomm", and never names it
  {
    ...base,
    _id: "breadcrumb-test-interview",
    type: "TEXT",
    rawText: "intern interview prep: arrays, graphs, one systems question",
    embedding: direction([3, 1]),
  },
  {
    ...base,
    _id: "breadcrumb-test-kyoto",
    type: "TEXT",
    rawText: "Kyoto in November for the maple leaves",
    embedding: direction([2, 1]),
  },
  // stored, but its embedding call failed: only its words can find it
  {
    ...base,
    _id: "breadcrumb-test-dinner",
    type: "TEXT",
    rawText: "Naru's in Indiranagar does omakase, book two weeks ahead",
  },
  // an image OCR has not read yet: nothing to match in either half
  { ...base, _id: "breadcrumb-test-unread", type: "IMAGE" },
];

const seededIds = seeded.map((doc) => doc._id);
const embeddedCount = seeded.filter((doc) => doc.embedding).length;

/** What each test phrase embeds to. A phrase not listed here is a test bug. */
const phrases: Record<string, number[]> = {
  government: direction([0, 1]),
  governments: direction([0, 1]),
  Qualcomm: direction([3, 1]),
  "trip to japan in autumn": direction([2, 1]),
  omakase: direction([5, 1]),
};

/**
 * Hybrid search against the real indexes on the real collection.
 *
 * M0 holds three search indexes across the whole cluster. The real collection
 * needs two, so a test collection with its own pair would make four -- which
 * is why these tests borrow the real indexes (decided 2026-09-18, until tests
 * get a cluster of their own). The rules that make that safe:
 *
 * - every document written here carries TEST_LABEL and a fixed id;
 * - every search here filters on TEST_LABEL, in both halves;
 * - cleanup deletes only those ids, and only while they carry the label;
 * - nothing here drops, empties or updates anything else.
 *
 * The model is stubbed with vectors set by hand: the live embedding test in
 * embeddings.test.ts covers what words mean.
 */
describe(
  "hybrid search against the real indexes",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let purposes: EmbeddingPurpose[];
    /** Set when Atlas has no index to search; every test then skips. */
    let unavailable: string | undefined;

    const embed: Embedder = async (texts, purpose) => {
      purposes.push(purpose);
      return texts.map((text) => {
        const vector = phrases[text];
        if (!vector) throw new Error(`no test vector for ${JSON.stringify(text)}`);
        return vector;
      });
    };

    /** Only ever deletes what these tests wrote. */
    const removeSeeded = () =>
      memories(database).deleteMany({ _id: { $in: seededIds }, sourceAppLabel: TEST_LABEL });

    const search = async (t: TestContext, query: string, limit = MAX_LIMIT): Promise<SearchHit[] | undefined> => {
      if (unavailable) {
        t.skip(unavailable);
        return undefined;
      }
      const outcome = await searchMemories(database, embed, {
        query,
        limit,
        filter: { sourceAppLabel: TEST_LABEL },
      });
      assert.ok(outcome.ok, `search failed: ${JSON.stringify(outcome)}`);
      return outcome.results;
    };

    const ids = (hits: SearchHit[]) => hits.map((hit) => hit.id.replace("breadcrumb-test-", ""));

    before(async () => {
      await connectMongo();
      database = db();
      purposes = [];

      // the same indexes startup creates; normally both exist already
      const states = [await ensureVectorIndex(database, EMBEDDING_DIMENSIONS), await ensureTextIndex(database)];
      if (states.some((state) => state === "refused" || state === "full")) {
        unavailable = `the real collection lacks a search index (${states.join(", ")}); start the server to see why`;
        return;
      }

      // a run that died before its cleanup leaves these behind
      await removeSeeded();
      await memories(database).insertMany(seeded);
      unavailable = await waitUntilSearchable(database);
    });

    after(async () => {
      try {
        if (database) await removeSeeded();
      } finally {
        await closeMongo();
      }
    });

    test("a word only one memory holds beats the nearest meaning", async (t) => {
      const hits = await search(t, "government");
      if (!hits) return;

      // meaning alone ranks the one-word memory first...
      const goes = hits.find((hit) => hit.id.endsWith("-goes"));
      assert.equal(goes?.ranks.vector, 1);
      // ...but the only memory with the word in it wins once words count
      assert.equal(ids(hits)[0], "reddit");
      assert.equal(hits[0]?.ranks.text, 1);
      assert.ok((hits[0]?.ranks.vector ?? 0) > 1, "it should not have been first by meaning alone");
    });

    test("a proper noun beats the pure-vector baseline", async (t) => {
      const hits = await search(t, "Qualcomm");
      if (!hits) return;

      // the step's own test: by meaning alone, interview prep is nearest
      const interview = hits.find((hit) => hit.id.endsWith("-interview"));
      assert.equal(interview?.ranks.vector, 1);
      assert.equal(interview?.ranks.text, null, "it never names Qualcomm");
      // the memory that names Qualcomm comes first once the halves are fused
      assert.equal(ids(hits)[0], "internship");
      assert.deepEqual(hits[0]?.ranks, { vector: 2, text: 1 });
    });

    test("stemming finds a word in another form", async (t) => {
      const hits = await search(t, "governments");
      if (!hits) return;

      assert.equal(ids(hits)[0], "reddit");
      assert.equal(hits[0]?.ranks.text, 1);
    });

    test("meaning alone still wins when no memory shares the words", async (t) => {
      const hits = await search(t, "trip to japan in autumn");
      if (!hits) return;

      assert.equal(ids(hits)[0], "kyoto");
      assert.deepEqual(hits[0]?.ranks, { vector: 1, text: null });
    });

    test("a memory with no embedding is still found by its words", async (t) => {
      const hits = await search(t, "omakase");
      if (!hits) return;

      const dinner = hits.find((hit) => hit.id.endsWith("-dinner"));
      assert.ok(dinner, "the text half alone should find it");
      assert.deepEqual(dinner.ranks, { vector: null, text: 1 });
    });

    test("a memory with nothing to read or embed is never a result", async (t) => {
      for (const phrase of Object.keys(phrases)) {
        const hits = await search(t, phrase);
        if (!hits) return;
        assert.ok(!ids(hits).includes("unread"), `"${phrase}" found a memory with nothing in it`);
      }
    });

    test("the filter keeps every other memory out of both halves", async (t) => {
      // the real collection holds its own "government" screenshot; were either
      // half unfiltered, it or some other real memory would appear here
      const hits = await search(t, "government");
      if (!hits) return;

      assert.ok(hits.length > 0);
      assert.ok(hits.every((hit) => hit.sourceAppLabel === TEST_LABEL), `leaked: ${JSON.stringify(ids(hits))}`);
      assert.equal(hits.length, embeddedCount, "every embedded test memory, and nothing else");
    });

    test("the phrase is embedded as a query, not as a document", async (t) => {
      purposes = [];
      const hits = await search(t, "Qualcomm");
      if (!hits) return;

      // the two task types land in different places; mixing them costs quality
      assert.deepEqual(purposes, ["query"]);
    });

    test("a result says what it is, what it says and why it matched, and nothing more", async (t) => {
      const hits = await search(t, "Qualcomm");
      if (!hits) return;

      const { score, ...rest } = hits[0] as SearchHit;
      assert.ok(Math.abs(score - (1 / 61 + 1 / 62)) < 1e-9, `reciprocal rank fusion of 1st and 2nd, got ${score}`);
      assert.deepEqual(rest, {
        id: "breadcrumb-test-internship",
        ranks: { vector: 2, text: 1 },
        type: "LINK",
        hasLink: true,
        capturedAt: new Date("2026-04-18T10:12:33.000Z"),
        sourceAppLabel: TEST_LABEL,
        title: "Qualcomm Software Engineering Intern",
        rawText: "https://example.com/jobs/swe-intern-2026",
        extractedText: null,
        readText: null,
        summary: "Qualcomm software internship, applications close April 30",
        kind: "job posting",
      });
      for (const hit of hits) {
        for (const field of ["embedding", "enrichment", "scoreDetails", "_id"]) {
          assert.ok(!(field in hit), `${field} must not be in a result`);
        }
      }
    });

    test("an unenriched memory has no summary, rather than an empty one", async (t) => {
      const hits = await search(t, "Qualcomm");
      if (!hits) return;

      const interview = hits.find((hit) => hit.id.endsWith("-interview"));
      assert.equal(interview?.summary, null);
      assert.equal(interview?.kind, null);
    });

    test("a picture's result carries what OCR read", async (t) => {
      const hits = await search(t, "government");
      if (!hits) return;

      assert.match(hits[0]?.extractedText ?? "", /government is trying to get you/);
    });

    test("the limit caps the list", async (t) => {
      const hits = await search(t, "Qualcomm", 2);
      if (!hits) return;

      assert.equal(hits.length, 2);
    });
  },
);

/**
 * Waits until both indexes are built and both have caught up with the seeded
 * memories -- Atlas indexes a write a moment after it lands. Returns a reason
 * to skip if Atlas is slow, and throws if an index failed to build, since a
 * definition Atlas rejects is our bug rather than its capacity.
 */
async function waitUntilSearchable(database: Db, timeoutMs = 180_000): Promise<string | undefined> {
  const deadline = Date.now() + timeoutMs;
  const collection = memories(database);
  const count = async (pipeline: object[]) => (await collection.aggregate(pipeline).toArray()).length;

  while (Date.now() < deadline) {
    const indexes = (await collection.listSearchIndexes().toArray()) as {
      name: string;
      status?: string;
      queryable?: boolean;
    }[];
    const failed = indexes.find((index) => index.status === "FAILED");
    if (failed) throw new Error(`the ${failed.name} index failed to build: ${JSON.stringify(failed)}`);

    const queryable = [VECTOR_INDEX, TEXT_INDEX].every(
      (name) => indexes.find((index) => index.name === name)?.queryable,
    );
    if (queryable) {
      const vectors = await count([
        {
          $vectorSearch: {
            index: VECTOR_INDEX,
            path: "embedding",
            queryVector: direction([0, 1]),
            exact: true,
            limit: MAX_LIMIT,
            filter: { sourceAppLabel: { $eq: TEST_LABEL } },
          },
        },
      ]);
      const texts = await count([
        { $search: { index: TEXT_INDEX, equals: { path: "sourceAppLabel", value: TEST_LABEL } } },
      ]);
      if (vectors === embeddedCount && texts === seeded.length) return undefined;
    }
    await new Promise((resume) => setTimeout(resume, 1_000));
  }
  return `the search indexes had not caught up after ${timeoutMs / 1000}s`;
}

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
  datedAt,
  ensureTextIndex,
  ensureVectorIndex,
  type MemoryDoc,
  memories,
  TEXT_INDEX,
  VECTOR_INDEX,
} from "./memories.ts";
import { type Interpretation, type QueryParser, understanding } from "./query.ts";
import {
  answerSearch,
  DEFAULT_LIMIT,
  filterFor,
  MAX_LIMIT,
  MAX_QUERY_CHARS,
  mqlFilter,
  parseSearch,
  type SearchAnswer,
  searchFilterClauses,
  type SearchHit,
  searchMemories,
  searchPipeline,
} from "./search.ts";

const unused = async () => {
  throw new Error("not used by these tests");
};

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

describe("the filter, in each half's language", () => {
  test("asking for links also asks for anything carrying one", () => {
    // a captioned photo from WhatsApp is an IMAGE with a link; filtering on type
    // alone would miss it
    assert.deepEqual(mqlFilter({ types: ["LINK"] }), { $or: [{ type: { $in: ["LINK"] } }, { hasLink: true }] });
    assert.deepEqual(searchFilterClauses({ types: ["LINK"] }), [
      {
        compound: {
          should: [{ in: { path: "type", value: ["LINK"] } }, { equals: { path: "hasLink", value: true } }],
          minimumShouldMatch: 1,
        },
      },
    ]);
  });

  test("any other type is just the type", () => {
    assert.deepEqual(mqlFilter({ types: ["IMAGE", "PDF"] }), { type: { $in: ["IMAGE", "PDF"] } });
  });

  test("every condition must hold", () => {
    const from = new Date(2026, 3, 1);
    const to = new Date(2026, 4, 1);

    assert.deepEqual(mqlFilter({ types: ["IMAGE"], from, to, sourceAppLabel: "WhatsApp" }), {
      $and: [
        { type: { $in: ["IMAGE"] } },
        { datedAt: { $gte: from, $lt: to } },
        { sourceAppLabel: { $eq: "WhatsApp" } },
      ],
    });
    assert.equal(searchFilterClauses({ types: ["IMAGE"], from, to, sourceAppLabel: "WhatsApp" }).length, 3);
  });

  test("a range may be open at either end", () => {
    assert.deepEqual(mqlFilter({ from: new Date(2026, 3, 1) }), { datedAt: { $gte: new Date(2026, 3, 1) } });
  });

  test("no filter is no condition at all", () => {
    assert.deepEqual(mqlFilter({}), {});
    assert.deepEqual(searchFilterClauses({}), []);
  });

  test("April, inclusive, becomes a range that ends where May begins", () => {
    const filter = filterFor({ query: "", types: [], from: "2026-04-01", to: "2026-04-30", sourceApp: null });

    assert.deepEqual(filter, { from: new Date(2026, 3, 1), to: new Date(2026, 4, 1) });
  });
});

/**
 * The route's refusals and its handling of a failing model. None of these
 * searches: the stand-in database below answers only the parser's question
 * about which apps exist, and a search would fail on it.
 */
describe("GET /search, when it cannot search", () => {
  const labelsOnly = { collection: () => ({ distinct: async () => [] }) } as unknown as Db;

  let embedFailure: Error;
  let server: Server;
  let base: string;

  before(async () => {
    server = createApp({
      log: false,
      extract: unused,
      extractImages: unused,
      parseQuery: async () => ({ query: "internship", types: [], from: null, to: null, sourceApp: null }),
      embed: async () => {
        throw embedFailure;
      },
      database: labelsOnly,
    }).listen(0, "127.0.0.1");
    await once(server, "listening");
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  after(() => {
    server.closeAllConnections();
    server.close();
  });

  test("a search with no phrase is refused, with the reason", async () => {
    const response = await fetch(`${base}/search`);

    assert.equal(response.status, 400);
    assert.match(((await response.json()) as { error: string }).error, /q is required/);
  });

  test("a bad limit is refused", async () => {
    const response = await fetch(`${base}/search?q=internship&limit=500`);

    assert.equal(response.status, 400);
  });

  test("a busy model is a 503 the phone may retry", async () => {
    embedFailure = new Error('{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}');

    const response = await fetch(`${base}/search?q=internship`);

    assert.equal(response.status, 503);
    const body = (await response.json()) as { retryable?: boolean; reason: string };
    assert.equal(body.retryable, true);
    assert.match(body.reason, /RESOURCE_EXHAUSTED/);
  });

  test("a model that refuses outright is a 502, not worth retrying", async () => {
    embedFailure = new Error("400 API key not valid");

    const response = await fetch(`${base}/search?q=internship`);

    assert.equal(response.status, 502);
    assert.equal(((await response.json()) as { retryable?: boolean }).retryable, undefined);
  });
});

/** A unit vector at the stored size, leaning along the given axes. */
function direction(...weights: [axis: number, weight: number][]): number[] {
  const vector = new Array<number>(EMBEDDING_DIMENSIONS).fill(0);
  for (const [axis, weight] of weights) vector[axis] = weight;
  return normalize(vector);
}

/** When the test memories were saved -- a September, like the real ones. */
const SAVED = new Date("2026-09-10T10:00:00.000Z");

const base = {
  hasLink: false,
  capturedAt: SAVED,
  updatedAt: SAVED,
  syncedAt: SAVED,
  sourceApp: null,
  sourceAppLabel: null,
  title: null,
  rawText: null,
  extractedText: null,
};

/**
 * Which memory is nearest to which phrase is set by hand, one axis per idea,
 * so every ranking below can be worked out by reading the numbers. Every id
 * starts "breadcrumb-test-", which no real memory's UUID can.
 */
const seeded: MemoryDoc[] = (
  [
    // a one-word memory, nearest in meaning to "government" but without the word
    { ...base, _id: "goes", type: "TEXT", sourceAppLabel: "Chrome", rawText: "goes", embedding: direction([0, 1]) },
    // the word appears once, deep in a screenshot about something else entirely
    {
      ...base,
      _id: "reddit",
      type: "IMAGE",
      extractedText: "This happens when the government is trying to get you. Your days are numbered.",
      enrichment: {
        summary: "Screenshot of comments joking about a parallel dimension",
        kind: "screenshot",
        entities: [],
        dates: [],
        at: SAVED,
      },
      embedding: direction([1, 1]),
    },
    {
      ...base,
      _id: "internship",
      type: "LINK",
      hasLink: true,
      sourceApp: "com.android.chrome",
      sourceAppLabel: "Chrome",
      title: "Qualcomm Software Engineering Intern",
      rawText: "https://example.com/jobs/swe-intern-2026",
      enrichment: {
        summary: "Qualcomm software internship, applications close April 30",
        kind: "job posting",
        entities: ["Qualcomm"],
        dates: ["2026-04-30"],
        at: SAVED,
      },
      embedding: direction([3, 0.6], [4, 0.8]),
    },
    // nearest in meaning to "Qualcomm", and never names it
    {
      ...base,
      _id: "interview",
      type: "TEXT",
      rawText: "intern interview prep: arrays, graphs, one systems question",
      embedding: direction([3, 1]),
    },
    { ...base, _id: "kyoto", type: "TEXT", rawText: "Kyoto in November for the maple leaves", embedding: direction([2, 1]) },
    // stored, but its embedding call failed: only its words can find it
    { ...base, _id: "dinner", type: "TEXT", rawText: "Naru's in Indiranagar does omakase, book two weeks ahead" },
    // an image OCR has not read yet: nothing to match in either half
    { ...base, _id: "unread", type: "IMAGE" },

    // for 4.3's filters: two screenshots saved in September, taken in April and May
    {
      ...base,
      _id: "april-shot",
      type: "IMAGE",
      contentCreatedAt: new Date("2026-04-10T06:00:00.000Z"),
      extractedText: "Samsung R&D internship, applications open till May",
      embedding: direction([6, 1]),
    },
    {
      ...base,
      _id: "may-shot",
      type: "IMAGE",
      contentCreatedAt: new Date("2026-05-03T06:00:00.000Z"),
      extractedText: "Samsung R&D internship interview schedule",
      embedding: direction([6, 0.9], [7, 0.44]),
    },
    // and three links, two sent on WhatsApp -- one of them as a captioned photo
    {
      ...base,
      _id: "whatsapp-link",
      type: "LINK",
      hasLink: true,
      sourceAppLabel: "WhatsApp",
      capturedAt: new Date("2026-09-11T10:00:00.000Z"),
      rawText: "check this https://example.com/reel/123",
      embedding: direction([8, 1]),
    },
    {
      ...base,
      _id: "whatsapp-photo",
      type: "IMAGE",
      hasLink: true,
      sourceAppLabel: "WhatsApp",
      capturedAt: new Date("2026-09-12T10:00:00.000Z"),
      rawText: "check this https://example.com/menu",
      embedding: direction([9, 1]),
    },
    {
      ...base,
      _id: "chrome-link",
      type: "LINK",
      hasLink: true,
      sourceAppLabel: "Chrome",
      rawText: "check this https://example.com/article",
      embedding: direction([10, 1]),
    },
  ] as MemoryDoc[]
).map((doc) => ({ ...doc, _id: `breadcrumb-test-${doc._id}`, datedAt: datedAt(doc) }));

const seededIds = seeded.map((doc) => doc._id);
const embeddedCount = seeded.filter((doc) => doc.embedding).length;

/** What each phrase embeds to. A phrase not listed here is a test bug. */
const phrases: Record<string, number[]> = {
  government: direction([0, 1]),
  governments: direction([0, 1]),
  Qualcomm: direction([3, 1]),
  "trip to japan in autumn": direction([2, 1]),
  omakase: direction([5, 1]),
  internship: direction([6, 1]),
  "check this": direction([8, 0.5], [9, 0.5], [10, 0.5]),
  stuff: direction([11, 1]),
};

/** What the stub parser makes of each test phrase: as the live one should, by 4.3's own tests. */
const none = { types: [], from: null, to: null, sourceApp: null };
const parses: Record<string, Interpretation | Error> = {
  "that internship screenshot from April": {
    ...none,
    query: "internship",
    types: ["IMAGE"],
    from: "2026-04-01",
    to: "2026-04-30",
  },
  "internship saved in September": { ...none, query: "internship", from: "2026-09-01", to: "2026-09-30" },
  "that link from WhatsApp": { ...none, query: "", types: ["LINK"], sourceApp: "WhatsApp" },
  "the link from WhatsApp saying check this": { ...none, query: "check this", types: ["LINK"], sourceApp: "WhatsApp" },
  stuff: { ...none, query: "" },
  Qualcomm: new Error("503 UNAVAILABLE: the model is overloaded"),
};

/**
 * Search against the real indexes on the real collection.
 *
 * M0 holds three search indexes across the whole cluster. The real collection
 * needs two, so a test collection with its own pair would make four -- which
 * is why these tests borrow the real indexes (decided 2026-09-18, until tests
 * get a cluster of their own). The rules that make that safe:
 *
 * - every document written here has a fixed id starting "breadcrumb-test-";
 * - every search here is narrowed to those ids, in both halves;
 * - cleanup deletes those ids and nothing else;
 * - nothing here drops, empties or updates anything else.
 *
 * The models are stubbed with answers set by hand: the live embedding and
 * parsing tests cover what words mean.
 */
describe(
  "search against the real indexes",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let purposes: EmbeddingPurpose[];
    let embeddedPhrases: string[];
    /** Set when Atlas has no index to search; every test then skips. */
    let unavailable: string | undefined;

    const embed: Embedder = async (texts, purpose) => {
      purposes.push(purpose);
      embeddedPhrases.push(...texts);
      return texts.map((text) => {
        const vector = phrases[text];
        if (!vector) throw new Error(`no test vector for ${JSON.stringify(text)}`);
        return vector;
      });
    };

    const parseQuery: QueryParser = async (query) => {
      const parsed = parses[query];
      if (!parsed) throw new Error(`no test parse for ${JSON.stringify(query)}`);
      if (parsed instanceof Error) throw parsed;
      return parsed;
    };

    /** Only ever deletes what these tests wrote. */
    const removeSeeded = () => memories(database).deleteMany({ _id: { $in: seededIds } });

    /** The hybrid core on its own, narrowed to the test memories. */
    const search = async (t: TestContext, query: string, limit = MAX_LIMIT): Promise<SearchHit[] | undefined> => {
      if (unavailable) {
        t.skip(unavailable);
        return undefined;
      }
      const outcome = await searchMemories(database, embed, { query, limit, filter: { ids: seededIds } });
      assert.ok(outcome.ok, `search failed: ${JSON.stringify(outcome)}`);
      return outcome.results;
    };

    /** A whole search, parse included, narrowed to the test memories. */
    const answer = async (t: TestContext, query: string): Promise<SearchAnswer | undefined> => {
      if (unavailable) {
        t.skip(unavailable);
        return undefined;
      }
      const outcome = await answerSearch(database, embed, understanding(parseQuery), {
        query,
        limit: MAX_LIMIT,
        filter: { ids: seededIds },
      });
      assert.ok(outcome.ok, `search failed: ${JSON.stringify(outcome)}`);
      return outcome.answer;
    };

    const ids = (hits: SearchHit[]) => hits.map((hit) => hit.id.replace("breadcrumb-test-", ""));

    before(async () => {
      await connectMongo();
      database = db();
      purposes = [];
      embeddedPhrases = [];

      // the same indexes startup creates -- or updates, when one lacks a field
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

    describe("hybrid ranking", () => {
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

      test("narrowing to given memories keeps every other one out of both halves", async (t) => {
        // the real collection holds its own "government" screenshot; were either
        // half not narrowed, it or some other real memory would appear here
        const hits = await search(t, "government");
        if (!hits) return;

        assert.ok(hits.every((hit) => seededIds.includes(hit.id)), `leaked: ${JSON.stringify(ids(hits))}`);
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
        assert.ok(
          Math.abs((score ?? 0) - (1 / 61 + 1 / 62)) < 1e-9,
          `reciprocal rank fusion of 1st and 2nd, got ${score}`,
        );
        assert.deepEqual(rest, {
          id: "breadcrumb-test-internship",
          ranks: { vector: 2, text: 1 },
          type: "LINK",
          hasLink: true,
          capturedAt: SAVED,
          datedAt: SAVED,
          sourceAppLabel: "Chrome",
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

      test("the limit caps the list", async (t) => {
        const hits = await search(t, "Qualcomm", 2);
        if (!hits) return;

        assert.equal(hits.length, 2);
      });
    });

    describe("filters from the parsed phrase (4.3)", () => {
      test("'that internship screenshot from April' filters by both type and month", async (t) => {
        const found = await answer(t, "that internship screenshot from April");
        if (!found) return;

        // the May screenshot has the same words; the internship link is not a
        // picture; only one memory is both
        assert.deepEqual(ids(found.results), ["april-shot"]);
        assert.deepEqual(found.interpretation?.types, ["IMAGE"]);
      });

      test("a date filter matches when a picture was taken, not when it was saved", async (t) => {
        const found = await answer(t, "internship saved in September");
        if (!found) return;

        // both screenshots were saved in September, and taken months before
        assert.ok(found.results.length > 0);
        assert.ok(!ids(found.results).includes("april-shot"));
        assert.ok(!ids(found.results).includes("may-shot"));
      });

      test("'that link from WhatsApp' also finds a photo whose caption carried a link", async (t) => {
        embeddedPhrases = [];
        const found = await answer(t, "that link from WhatsApp");
        if (!found) return;

        // the step's own test: type = LINK OR hasLink, and only from WhatsApp
        assert.deepEqual(ids(found.results), ["whatsapp-photo", "whatsapp-link"], "newest first");
        // all filter and no words: listed, not ranked, and no embedding paid for
        assert.ok(found.results.every((hit) => hit.score === null));
        assert.deepEqual(embeddedPhrases, []);
      });

      test("the same filters narrow a search with words in it, through both halves", async (t) => {
        const found = await answer(t, "the link from WhatsApp saying check this");
        if (!found) return;

        // "check this" is in all three links; the Chrome one is filtered out of both halves
        assert.deepEqual(ids(found.results).sort(), ["whatsapp-link", "whatsapp-photo"]);
      });

      test("only the words the parser left are searched", async (t) => {
        embeddedPhrases = [];
        const found = await answer(t, "that internship screenshot from April");
        if (!found) return;

        // "from April" in the embedding would only be noise
        assert.deepEqual(embeddedPhrases, ["internship"]);
        assert.equal(found.query, "that internship screenshot from April", "the phrase is reported as typed");
      });

      test("a phrase the parser took everything from, and filtered nothing, is searched as typed", async (t) => {
        embeddedPhrases = [];
        const found = await answer(t, "stuff");
        if (!found) return;

        // an empty search is never what was meant
        assert.deepEqual(embeddedPhrases, ["stuff"]);
      });

      test("a parse that fails still searches, unfiltered, and says why", async (t) => {
        const found = await answer(t, "Qualcomm");
        if (!found) return;

        assert.equal(found.interpretation, null);
        assert.match(found.interpretationError ?? "", /UNAVAILABLE/);
        assert.equal(ids(found.results)[0], "internship", "the raw phrase is still searched");
      });
    });
  },
);

/**
 * Waits until both indexes are built -- including a rebuild after startup
 * added a field -- and both hold the seeded memories: Atlas indexes a write a
 * moment after it lands. Returns a reason to skip if Atlas is slow, and throws
 * if an index failed to build, since a definition Atlas rejects is our bug
 * rather than its capacity.
 */
async function waitUntilSearchable(database: Db, timeoutMs = 180_000): Promise<string | undefined> {
  const deadline = Date.now() + timeoutMs;
  const collection = memories(database);
  const count = async (pipeline: object[]) => (await collection.aggregate(pipeline).toArray()).length;
  let lastError = "";

  while (Date.now() < deadline) {
    const indexes = (await collection.listSearchIndexes().toArray()) as {
      name: string;
      status?: string;
      queryable?: boolean;
    }[];
    const failed = indexes.find((index) => index.status === "FAILED");
    if (failed) throw new Error(`the ${failed.name} index failed to build: ${JSON.stringify(failed)}`);

    const ready = [VECTOR_INDEX, TEXT_INDEX].every((name) => {
      const index = indexes.find((candidate) => candidate.name === name);
      return index?.queryable && index.status === "READY";
    });
    if (ready) {
      try {
        const vectors = await count([
          {
            $vectorSearch: {
              index: VECTOR_INDEX,
              path: "embedding",
              queryVector: direction([0, 1]),
              exact: true,
              limit: MAX_LIMIT,
              filter: { _id: { $in: seededIds } },
            },
          },
        ]);
        const texts = await count([{ $search: { index: TEXT_INDEX, in: { path: "_id", value: seededIds } } }]);
        if (vectors === embeddedCount && texts === seeded.length) return undefined;
        lastError = `${vectors}/${embeddedCount} vectors and ${texts}/${seeded.length} texts indexed`;
      } catch (error) {
        // a field the serving version does not have yet
        lastError = error instanceof Error ? error.message : String(error);
      }
    }
    await new Promise((resume) => setTimeout(resume, 1_000));
  }
  return `the search indexes had not caught up after ${timeoutMs / 1000}s (${lastError || "not ready"})`;
}

import assert from "node:assert/strict";
import { describe, test, type TestContext } from "node:test";
import type { Db } from "mongodb";
import { isTransient } from "./gemini.ts";
import {
  dayAfter,
  formatDay,
  geminiQueryParser,
  hasFilters,
  type Interpretation,
  localDay,
  parseInterpretation,
  QUERY_MODEL,
  type QueryContext,
  type QueryParser,
  understanding,
} from "./query.ts";

const context: QueryContext = { today: "2026-09-18", sourceApps: ["Chrome", "Instagram", "WhatsApp"] };

const reply = (value: Record<string, unknown>) =>
  JSON.stringify({ query: "", types: [], from: "", to: "", sourceApp: "", ...value });

describe("parseInterpretation", () => {
  test("reads a well-formed reply", () => {
    const parsed = parseInterpretation(
      reply({ query: "internship", types: ["IMAGE"], from: "2026-04-01", to: "2026-04-30", sourceApp: "WhatsApp" }),
      context,
    );

    assert.deepEqual(parsed, {
      query: "internship",
      types: ["IMAGE"],
      from: "2026-04-01",
      to: "2026-04-30",
      sourceApp: "WhatsApp",
    });
  });

  test("empty fields mean no filter", () => {
    const parsed = parseInterpretation(reply({ query: "restaurant recommendation" }), context);

    assert.deepEqual(parsed, { query: "restaurant recommendation", types: [], from: null, to: null, sourceApp: null });
    assert.equal(hasFilters(parsed), false);
  });

  test("a filter the model made up is dropped rather than trusted", () => {
    // each of these would hide the very memory being looked for
    const parsed = parseInterpretation(
      reply({ types: ["IMAGE", "VIDEO", "IMAGE", 7], from: "April", to: "2026-02-30", sourceApp: "Telegram" }),
      context,
    );

    assert.deepEqual(parsed.types, ["IMAGE"], "unknown and repeated types go");
    assert.equal(parsed.from, null, "a word is not a date");
    assert.equal(parsed.to, null, "nor is a day that does not exist");
    assert.equal(parsed.sourceApp, null, "no memory came from Telegram");
  });

  test("a range given backwards is turned around", () => {
    const parsed = parseInterpretation(reply({ from: "2026-04-30", to: "2026-04-01" }), context);

    assert.deepEqual([parsed.from, parsed.to], ["2026-04-01", "2026-04-30"]);
  });

  test("a reply that is not an object throws, so the search runs unfiltered", () => {
    assert.throws(() => parseInterpretation("[]", context));
    assert.throws(() => parseInterpretation("not json", context));
  });
});

describe("dates", () => {
  test("a day runs from its local midnight to the next", () => {
    assert.deepEqual(localDay("2026-04-01"), new Date(2026, 3, 1));
    assert.deepEqual(dayAfter("2026-04-30"), new Date(2026, 4, 1));
    assert.deepEqual(dayAfter("2026-12-31"), new Date(2027, 0, 1), "across a year end");
  });

  test("formats a day back as it was written", () => {
    assert.equal(formatDay(localDay("2026-09-08")), "2026-09-08");
  });
});

describe("understanding", () => {
  /** Answers the labels question and nothing else. */
  const labelsDb = (labels: string[]) =>
    ({ collection: () => ({ distinct: async () => labels }) }) as unknown as Db;

  const interpretation: Interpretation = { query: "internship", types: ["IMAGE"], from: null, to: null, sourceApp: null };

  test("the same phrase on the same day is parsed once", async () => {
    let calls = 0;
    const understand = understanding(async () => {
      calls += 1;
      return interpretation;
    });

    await understand(labelsDb([]), "internship screenshot");
    const second = await understand(labelsDb([]), "internship screenshot");

    assert.equal(calls, 1, "the second search should cost nothing");
    assert.deepEqual(second.interpretation, interpretation);
  });

  test("a new day parses again, since 'last week' has moved", async () => {
    let calls = 0;
    let now = new Date(2026, 8, 18, 23, 0);
    const understand = understanding(async () => {
      calls += 1;
      return interpretation;
    }, () => now);

    await understand(labelsDb([]), "from last week");
    now = new Date(2026, 8, 19, 9, 0);
    await understand(labelsDb([]), "from last week");

    assert.equal(calls, 2);
  });

  test("the parser is told today's date and the apps memories came from", async () => {
    let seen: QueryContext | undefined;
    const understand = understanding(async (_query, given) => {
      seen = given;
      return interpretation;
    }, () => new Date(2026, 8, 18, 12, 0));

    await understand(labelsDb(["WhatsApp", "Chrome", " "]), "x");

    assert.deepEqual(seen, { today: "2026-09-18", sourceApps: ["Chrome", "WhatsApp"] });
  });

  test("a failed parse says why, and is not remembered", async () => {
    let calls = 0;
    const failing: QueryParser = async () => {
      calls += 1;
      throw new Error("503 UNAVAILABLE");
    };
    const understand = understanding(failing);

    const first = await understand(labelsDb([]), "x");
    await understand(labelsDb([]), "x");

    assert.equal(first.interpretation, null);
    assert.match(first.error ?? "", /UNAVAILABLE/);
    assert.equal(calls, 2, "the next search should ask again");
  });
});

/**
 * The step's own tests, against the live model: what the parser makes of the
 * phrases rule 6 was written for. Today is pinned, so the dates are knowable.
 */
describe(
  `${QUERY_MODEL} live`,
  { skip: process.env.GEMINI_API_KEY ? false : "GEMINI_API_KEY is not set (see .env.example)" },
  () => {
    const parse = geminiQueryParser(process.env.GEMINI_API_KEY as string);
    /** Google's capacity is not ours to assert on. */
    const parsed = async (t: TestContext, query: string) => {
      try {
        return await parse(query, context);
      } catch (error) {
        if (isTransient(error)) {
          t.skip(`${QUERY_MODEL} is busy: ${(error as Error).message}`);
          return undefined;
        }
        throw error;
      }
    };

    test("'that internship screenshot from April' is a type, a month and a word", async (t) => {
      const result = await parsed(t, "that internship screenshot from April");
      if (!result) return;

      assert.deepEqual(result.types, ["IMAGE"]);
      assert.deepEqual([result.from, result.to], ["2026-04-01", "2026-04-30"]);
      assert.match(result.query, /internship/i);
      assert.doesNotMatch(result.query, /april|screenshot/i, "filter words are noise in the embedding");
      assert.equal(result.sourceApp, null);
    });

    test("'that link from whatsapp' is a type and an app", async (t) => {
      const result = await parsed(t, "that link from whatsapp");
      if (!result) return;

      assert.deepEqual(result.types, ["LINK"]);
      assert.equal(result.sourceApp, "WhatsApp");
      assert.equal(result.from, null);
      assert.doesNotMatch(result.query, /whatsapp|link/i);
    });

    test("a phrase with no filter in it is left alone", async (t) => {
      const result = await parsed(t, "restaurant someone recommended in Indiranagar");
      if (!result) return;

      assert.equal(hasFilters(result), false, `no filter was asked for: ${JSON.stringify(result)}`);
      assert.match(result.query, /restaurant/i);
      assert.match(result.query, /Indiranagar/, "names are kept as written");
    });
  },
);

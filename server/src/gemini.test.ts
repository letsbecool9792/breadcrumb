import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  type Extraction,
  geminiExtractor,
  INGEST_MODEL,
  isTransient,
  parseExtractions,
  pinnedModel,
  retryTransient,
} from "./gemini.ts";

describe("parseExtractions", () => {
  test("reads a reply about several items, keyed by their numbers", () => {
    const extractions = parseExtractions(
      `{"items":[
        {"index":0,"summary":"Qualcomm internship","kind":"job posting","entities":["Qualcomm"],"dates":["2026-04-30"]},
        {"index":1,"summary":"Omakase in Indiranagar","kind":"restaurant recommendation","entities":["Naru's"],"dates":[]}
      ]}`,
    );

    assert.equal(extractions.size, 2);
    assert.deepEqual(extractions.get(0), {
      summary: "Qualcomm internship",
      kind: "job posting",
      entities: ["Qualcomm"],
      dates: ["2026-04-30"],
    });
    assert.equal(extractions.get(1)?.kind, "restaurant recommendation");
  });

  test("an item the model skipped is simply absent", () => {
    const extractions = parseExtractions(`{"items":[{"index":2,"summary":"only this one","kind":"note","entities":[],"dates":[]}]}`);

    assert.deepEqual([...extractions.keys()], [2]);
  });

  test("a reply that ignores the schema still cannot store rubbish", () => {
    // the schema makes the shape likely, not certain
    const extractions = parseExtractions(
      `{"items":[{"index":0,"summary":42,"entities":"Qualcomm","dates":[null," ","2026-04-30"]},{"summary":"no number"}]}`,
    );

    assert.equal(extractions.size, 1, "an answer with no number belongs to no item");
    assert.deepEqual(extractions.get(0), { summary: "", kind: "", entities: [], dates: ["2026-04-30"] });
  });

  test("a reply that is not JSON at all throws", () => {
    assert.throws(() => parseExtractions("I'm sorry, I can't help with that."));
    assert.throws(() => parseExtractions("[1,2,3]"), /non-object/);
    assert.throws(() => parseExtractions(`{"summary":"unbatched"}`), /no items/);
  });
});

describe("retryTransient", () => {
  const busy = () => new Error('{"error":{"code":503,"message":"high demand","status":"UNAVAILABLE"}}');

  test("asks again when the model is merely busy", async () => {
    let attempts = 0;
    const answer = await retryTransient(async () => {
      attempts += 1;
      if (attempts < 3) throw busy();
      return "read";
    }, [0, 0]);

    assert.equal(answer, "read");
    assert.equal(attempts, 3);
  });

  test("gives up on a wrong request rather than hammering it", async () => {
    let attempts = 0;
    await assert.rejects(
      retryTransient(async () => {
        attempts += 1;
        throw new Error('{"error":{"code":400,"message":"invalid argument"}}');
      }, [0, 0]),
      /invalid argument/,
    );

    assert.equal(attempts, 1);
  });

  test("gives up once the tries run out, keeping the last failure", async () => {
    await assert.rejects(
      retryTransient(async () => {
        throw busy();
      }, [0, 0]),
      /high demand/,
    );
  });
});

/**
 * One real call, so the pinned model, the schema and the SDK are known to
 * work together rather than assumed to. Skipped without a key, since it costs
 * tokens and needs the network.
 */
describe(
  `${INGEST_MODEL} live`,
  { skip: process.env.GEMINI_API_KEY ? false : "GEMINI_API_KEY is not set (see .env.example)" },
  () => {
    test("reads two saved items in one call, without mixing them up", async (t) => {
      const extract = geminiExtractor(process.env.GEMINI_API_KEY as string);

      let extractions: Map<number, Extraction>;
      try {
        extractions = await extract([
          {
            index: 0,
            type: "LINK",
            title: "Qualcomm Software Engineering Intern, Bengaluru",
            text: "https://example.com/jobs/qualcomm-swe-intern\nApplications close April 30.",
            sourceAppLabel: "Chrome",
            capturedAt: new Date("2026-04-18T10:12:33.000Z"),
          },
          {
            index: 1,
            type: "TEXT",
            text: "Naru's in Indiranagar does omakase, book two weeks ahead",
            sourceAppLabel: "WhatsApp",
            capturedAt: new Date("2026-04-18T10:12:33.000Z"),
          },
        ]);
      } catch (error) {
        // Google's capacity is not ours to assert on; the retries are already spent.
        if (isTransient(error)) return t.skip(`${INGEST_MODEL} is busy: ${(error as Error).message}`);
        throw error;
      }

      const job = extractions.get(0);
      const dinner = extractions.get(1);
      assert.ok(job && dinner, `expected an answer for each item, got ${[...extractions.keys()]}`);

      assert.ok(job.summary.length > 0, "a summary is the one thing we always want");
      assert.ok(job.summary.length < 400, `summary should be one line, got: ${job.summary}`);
      assert.ok(
        job.entities.some((entity) => /qualcomm/i.test(entity)),
        `expected Qualcomm among entities, got ${JSON.stringify(job.entities)}`,
      );
      assert.ok(
        job.dates.includes("2026-04-30"),
        `expected the deadline resolved against the save date, got ${JSON.stringify(job.dates)}`,
      );

      // the two items are unrelated; neither answer may describe the other
      assert.ok(!/qualcomm|intern/i.test(dinner.summary), `item 1 bled into item 0: ${dinner.summary}`);
      assert.ok(
        /naru|omakase|restaurant|indiranagar/i.test(`${dinner.summary} ${dinner.kind}`),
        `expected the restaurant for item 1, got: ${dinner.summary}`,
      );
    });
  },
);

describe("pinnedModel", () => {
  test("falls back to the pinned default when nothing is configured", () => {
    assert.equal(pinnedModel(undefined, "gemini-3.8-flash"), "gemini-3.8-flash");
    assert.equal(pinnedModel("  ", "gemini-3.8-flash"), "gemini-3.8-flash");
  });

  test("takes a configured model that names a version", () => {
    assert.equal(pinnedModel("gemini-2.5-flash-lite", "gemini-3.8-flash"), "gemini-2.5-flash-lite");
  });

  test("refuses an alias, whose behaviour would shift between runs", () => {
    assert.throws(() => pinnedModel("gemini-flash-latest", "gemini-3.8-flash"), /alias/);
  });
});

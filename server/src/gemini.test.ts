import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { geminiExtractor, INGEST_MODEL, parseExtraction } from "./gemini.ts";

describe("parseExtraction", () => {
  test("reads a well-formed reply", () => {
    const extraction = parseExtraction(
      `{"summary":"Qualcomm internship","kind":"job posting","entities":["Qualcomm"],"dates":["2026-04-30"]}`,
    );

    assert.deepEqual(extraction, {
      summary: "Qualcomm internship",
      kind: "job posting",
      entities: ["Qualcomm"],
      dates: ["2026-04-30"],
    });
  });

  test("a reply that ignores the schema still cannot store rubbish", () => {
    // the schema makes the shape likely, not certain
    const extraction = parseExtraction(`{"summary":42,"entities":"Qualcomm","dates":[null," ","2026-04-30"]}`);

    assert.deepEqual(extraction, { summary: "", kind: "", entities: [], dates: ["2026-04-30"] });
  });

  test("a reply that is not JSON at all throws", () => {
    assert.throws(() => parseExtraction("I'm sorry, I can't help with that."));
    assert.throws(() => parseExtraction("[1,2,3]"), /non-object/);
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
    test("reads a saved link into the shape we store", async () => {
      const extract = geminiExtractor(process.env.GEMINI_API_KEY as string);

      const extraction = await extract({
        type: "LINK",
        title: "Qualcomm Software Engineering Intern, Bengaluru",
        text: "https://example.com/jobs/qualcomm-swe-intern\nApplications close April 30.",
        sourceAppLabel: "Chrome",
        capturedAt: new Date("2026-04-18T10:12:33.000Z"),
      });

      assert.ok(extraction.summary.length > 0, "a summary is the one thing we always want");
      assert.ok(extraction.summary.length < 400, `summary should be one line, got: ${extraction.summary}`);
      assert.ok(
        extraction.entities.some((entity) => /qualcomm/i.test(entity)),
        `expected Qualcomm among entities, got ${JSON.stringify(extraction.entities)}`,
      );
      assert.ok(
        extraction.dates.includes("2026-04-30"),
        `expected the deadline resolved against the save date, got ${JSON.stringify(extraction.dates)}`,
      );
    });
  },
);

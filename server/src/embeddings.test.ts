import assert from "node:assert/strict";
import { describe, test, type TestContext } from "node:test";
import { isTransient } from "./gemini.ts";
import {
  cosine,
  EMBEDDING_DIMENSIONS,
  EMBEDDING_MODEL,
  embeddingText,
  geminiEmbedder,
  normalize,
} from "./embeddings.ts";

describe("normalize and cosine", () => {
  test("a normalized vector has unit length", () => {
    const unit = normalize([3, 4]);

    assert.deepEqual(unit, [0.6, 0.8]);
    assert.ok(Math.abs(cosine(unit, unit) - 1) < 1e-12);
  });

  test("a zero vector is refused rather than turned into NaNs", () => {
    assert.throws(() => normalize([0, 0]), /zero vector/);
  });

  test("cosine is 1 for the same direction, 0 across, -1 opposite", () => {
    assert.equal(cosine([1, 0], [1, 0]), 1);
    assert.equal(cosine([1, 0], [0, 1]), 0);
    assert.equal(cosine([1, 0], [-1, 0]), -1);
  });
});

describe("embeddingText", () => {
  const memory = {
    title: "Qualcomm Software Engineering Intern",
    rawText: "https://example.com/jobs/qualcomm-swe-intern",
    extractedText: null,
    enrichment: {
      summary: "Qualcomm internship, applications close April 30",
      kind: "job posting",
      entities: ["Qualcomm", "Bengaluru"],
      dates: ["2026-04-30"],
      at: new Date(),
    },
  };

  test("leads with the summary, so a cut tail costs the least", () => {
    const text = embeddingText(memory);

    assert.ok(text.startsWith("Qualcomm internship, applications close April 30"));
    assert.ok(text.includes("Bengaluru"));
    assert.ok(text.includes("https://example.com/jobs/qualcomm-swe-intern"));
  });

  test("the person's note is embedded near the front, after what the thing is called", () => {
    const text = embeddingText({ ...memory, note: "Priya's pick for the summer" });

    const lines = text.split("\n");
    assert.equal(lines.indexOf("Priya's pick for the summer"), lines.indexOf(memory.title) + 1);
  });

  test("an unenriched memory still has its own text to embed", () => {
    const text = embeddingText({ title: null, rawText: "Naru's in Indiranagar", extractedText: null });

    assert.equal(text, "Naru's in Indiranagar");
  });

  test("blank parts leave no empty lines behind", () => {
    const text = embeddingText({ title: "  ", rawText: "kept", extractedText: null });

    assert.equal(text, "kept");
  });

  test("the source app is never part of what is embedded", () => {
    // provenance is a filter (4.3); embedding it would drag every WhatsApp save together
    const text = embeddingText({ ...memory, title: "Photo from Priya" });

    assert.ok(!/whatsapp/i.test(text));
  });

  test("a very long memory is capped", () => {
    const text = embeddingText({ title: null, rawText: "x".repeat(20_000), extractedText: null });

    assert.equal(text.length, 6_000);
  });
});

/**
 * The step's own test, against the live model: related texts must land closer
 * together than unrelated ones, or retrieval has nothing to stand on.
 */
describe(
  `${EMBEDDING_MODEL} live`,
  { skip: process.env.GEMINI_API_KEY ? false : "GEMINI_API_KEY is not set (see .env.example)" },
  () => {
    const embedder = geminiEmbedder(process.env.GEMINI_API_KEY as string);
    /** These tests read one vector at a time; the batch shape is exercised below. */
    const embed = async (text: string, purpose: "document" | "query") =>
      (await embedder([text], purpose))[0] as number[];

    const internship = "Qualcomm software engineering internship in Bengaluru, applications close April 30";
    const sameThing = "SWE intern role at Qualcomm, apply by the end of April";
    const restaurant = "Naru's in Indiranagar does omakase, book two weeks ahead";

    /** Google's capacity is not ours to assert on; the retries are already spent. */
    const unlessBusy = async (t: TestContext, body: () => Promise<void>) => {
      try {
        await body();
      } catch (error) {
        if (isTransient(error)) return t.skip(`${EMBEDDING_MODEL} is busy: ${(error as Error).message}`);
        throw error;
      }
    };

    test("comes back at the size and length we store", async (t) =>
      unlessBusy(t, async () => {
        const vector = await embed(internship, "document");

        assert.equal(vector.length, EMBEDDING_DIMENSIONS);
        // truncated dimensions are not unit length until we normalize them
        assert.ok(Math.abs(cosine(vector, vector) - 1) < 1e-6, "vectors must be normalized before storage");
      }));

    test("two related texts score closer than two unrelated ones", async (t) =>
      unlessBusy(t, async () => {
        // one request for all three: a free tier counts requests, not texts
        const [a, b, c] = await embedder([internship, sameThing, restaurant], "document");
        assert.ok(a && b && c, "a batch must come back in the order it was sent");

        const related = cosine(a, b);
        const unrelated = cosine(a, c);

        assert.ok(related > unrelated, `related ${related} should beat unrelated ${unrelated}`);
        assert.ok(related - unrelated > 0.1, `too close to call: ${related} vs ${unrelated}`);
      }));

    test("a remembered phrase finds the memory it describes", async (t) =>
      unlessBusy(t, async () => {
        // what someone would actually type, against what we store
        const query = await embed("that internship thing I saved", "query");
        const [job, dinner] = await embedder([internship, restaurant], "document");
        assert.ok(job && dinner);

        assert.ok(
          cosine(query, job) > cosine(query, dinner),
          `internship ${cosine(query, job)} should beat restaurant ${cosine(query, dinner)}`,
        );
      }));
  },
);

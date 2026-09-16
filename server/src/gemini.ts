import { GoogleGenAI } from "@google/genai";

/**
 * Pinned, never an alias: `gemini-flash-latest` would change behaviour under
 * us between runs. Flash, not Flash-Lite -- this is the ingest pass, and
 * Flash-Lite is for query parsing at 4.3.
 */
export const INGEST_MODEL = "gemini-3.8-flash";

/** What one Gemini call gets us about a saved thing (architecture rule 3). */
export interface Extraction {
  /** One line: what this is, in the words someone would use looking for it. */
  summary: string;
  /** What kind of thing it is -- "job posting", "restaurant recommendation". */
  kind: string;
  /** Proper nouns worth searching by: people, organisations, places, products. */
  entities: string[];
  /** Dates the content refers to, resolved to YYYY-MM-DD. */
  dates: string[];
}

/** What the extractor is given. Text only for now; images arrive at 3.6. */
export interface ExtractionInput {
  type: string;
  title?: string | null;
  text?: string | null;
  sourceAppLabel?: string | null;
  /** When it was saved, so "next Friday" can be resolved. */
  capturedAt: Date;
}

export type Extractor = (input: ExtractionInput) => Promise<Extraction>;

const SCHEMA = {
  type: "object",
  properties: {
    summary: { type: "string", description: "One line, at most 20 words. No preamble." },
    kind: { type: "string", description: "A short noun phrase: article, job posting, restaurant recommendation, receipt." },
    entities: {
      type: "array",
      items: { type: "string" },
      description: "Proper nouns worth searching by. Empty if there are none.",
    },
    dates: {
      type: "array",
      items: { type: "string", description: "YYYY-MM-DD" },
      description: "Dates the content refers to, resolved against the save date. Empty if none.",
    },
  },
  required: ["summary", "kind", "entities", "dates"],
  additionalProperties: false,
};

const INSTRUCTION = `You index single items a person saved to a personal memory app, so they can find
them again months later by describing them from memory.

Describe only what the item actually contains. Never invent a fact, a name or a date: if the item
is thin, say so plainly in the summary and leave the lists empty. Write the summary the way the
person would describe the thing to themselves, not as a caption or an advertisement.`;

/**
 * One multimodal call, not a pipeline of them (architecture rule 3). Whatever
 * this returns is stored beside the memory; nothing is chained off it.
 */
export function geminiExtractor(apiKey: string): Extractor {
  const ai = new GoogleGenAI({ apiKey });

  return async (input) =>
    retryTransient(async () => {
      const response = await ai.models.generateContent({
        model: INGEST_MODEL,
        contents: describe(input),
        config: {
          systemInstruction: INSTRUCTION,
          // Indexing, not writing: the same item should give the same answer.
          temperature: 0,
          responseMimeType: "application/json",
          responseJsonSchema: SCHEMA,
        },
      });

      const text = response.text;
      if (!text) throw new Error("Gemini returned no text");
      return parseExtraction(text);
    });
}

/** Busy or rate-limited, rather than wrong: worth asking again shortly. */
const TRANSIENT = /\b(429|500|503|UNAVAILABLE|RESOURCE_EXHAUSTED)\b|overloaded|high demand/i;

/**
 * A blip should not leave a memory unenriched. Two more tries, seconds apart --
 * the phone is not waiting on this (rule 2), but the request is.
 */
async function retryTransient<T>(call: () => Promise<T>, delaysMs = [1_000, 3_000]): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= delaysMs.length; attempt += 1) {
    try {
      return await call();
    } catch (error) {
      lastError = error;
      const message = error instanceof Error ? error.message : String(error);
      const delay = delaysMs[attempt];
      if (delay === undefined || !TRANSIENT.test(message)) break;
      await new Promise((resume) => setTimeout(resume, delay));
    }
  }
  throw lastError;
}

/** The saved item as the model sees it, metadata included -- it is context, not noise. */
function describe(input: ExtractionInput): string {
  return [
    `Saved on: ${input.capturedAt.toISOString().slice(0, 10)}`,
    `Saved as: ${input.type}`,
    input.sourceAppLabel ? `Shared from: ${input.sourceAppLabel}` : null,
    input.title ? `Title: ${input.title}` : null,
    "Content:",
    input.text?.trim() || "(none)",
  ]
    .filter((line) => line !== null)
    .join("\n");
}

/**
 * The schema makes the shape very likely, not certain, and a bad reply must
 * not reach the database.
 */
export function parseExtraction(text: string): Extraction {
  const raw: unknown = JSON.parse(text);
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new Error("Gemini returned a non-object");
  }
  const value = raw as Record<string, unknown>;

  return {
    summary: asString(value["summary"]),
    kind: asString(value["kind"]),
    entities: asStrings(value["entities"]),
    dates: asStrings(value["dates"]),
  };
}

function asString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asStrings(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map(asString).filter((item) => item.length > 0);
}

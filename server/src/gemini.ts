import { GoogleGenAI } from "@google/genai";

/**
 * Pinned, never an alias: `gemini-flash-latest` would change behaviour under
 * us between runs. Flash, not Flash-Lite -- this is the ingest pass, and
 * Flash-Lite is for query parsing at 4.3.
 */
/**
 * Flash Lite, not Flash. On the free tier every full Flash model allows 20
 * requests a day, which cannot carry ordinary use, let alone the 500-item seed
 * phase 5 calls non-optional; Flash Lite allows 500. Summarising one saved
 * item, naming what it mentions and resolving its dates is not work that needs
 * the larger model -- see the live test, which holds it to that.
 */
export const DEFAULT_INGEST_MODEL = "gemini-3.5-flash-lite";

/**
 * Overridable because free-tier daily allowances differ enormously by model --
 * gemini-3.8-flash allows 20 ingest calls a day, older and lighter models far
 * more -- and this task (summarise, name entities, resolve dates) does not
 * need the newest model.
 */
export const INGEST_MODEL = pinnedModel(process.env.GEMINI_INGEST_MODEL, DEFAULT_INGEST_MODEL);

/**
 * An alias such as `gemini-flash-latest` shifts behaviour under us between
 * runs, so a configured model must name a version.
 */
export function pinnedModel(configured: string | undefined, fallback: string): string {
  const model = configured?.trim();
  if (!model) return fallback;
  if (/latest|preview-latest/.test(model)) {
    throw new Error(`${model} is an alias; name an explicit model version instead`);
  }
  return model;
}

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
  /** Identifies the answer in the reply. Never the memory's id -- see [describe]. */
  index: number;
  type: string;
  title?: string | null;
  text?: string | null;
  sourceAppLabel?: string | null;
  /** When it was saved, so "next Friday" can be resolved. */
  capturedAt: Date;
}

/**
 * Reads several saved items in one call, answering by [ExtractionInput.index].
 *
 * Batched because of what a free tier allows: 500 requests a day, whether each
 * carries one memory or ten. An item the model skips is simply absent from the
 * result.
 */
export type Extractor = (inputs: ExtractionInput[]) => Promise<Map<number, Extraction>>;

const ITEM_SCHEMA = {
  type: "object",
  properties: {
    index: { type: "integer", description: "The number of the item this describes." },
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
  required: ["index", "summary", "kind", "entities", "dates"],
  additionalProperties: false,
};

const SCHEMA = {
  type: "object",
  properties: { items: { type: "array", items: ITEM_SCHEMA } },
  required: ["items"],
  additionalProperties: false,
};

const INSTRUCTION = `You index items a person saved to a personal memory app, so they can find them
again months later by describing them from memory.

Each request holds one or more numbered items. Return exactly one result per item, carrying that
item's number. The items are unrelated to each other: never let one describe another.

Describe only what an item actually contains. Never invent a fact, a name or a date: if an item is
thin, say so plainly in the summary and leave the lists empty. Write the summary the way the person
would describe the thing to themselves, not as a caption or an advertisement.`;

/** Long enough for a screenshot's text, short enough that one item cannot crowd out a batch. */
const MAX_ITEM_CHARS = 4_000;

/**
 * One multimodal call, not a pipeline of them (architecture rule 3). Whatever
 * this returns is stored beside the memory; nothing is chained off it.
 */
export function geminiExtractor(apiKey: string): Extractor {
  const ai = new GoogleGenAI({ apiKey });

  return async (inputs) => {
    if (inputs.length === 0) return new Map();
    return retryTransient(async () => {
      const response = await ai.models.generateContent({
        model: INGEST_MODEL,
        contents: inputs.map(describe).join("\n\n"),
        config: {
          systemInstruction: INSTRUCTION,
          // Indexing, not writing: the same item should give the same answer.
          temperature: 0,
          // No thinkingConfig here: gemini-3.5-flash-lite refuses it outright
          // (400 INVALID_ARGUMENT), and it needs no help with extraction.
          responseMimeType: "application/json",
          responseJsonSchema: SCHEMA,
        },
      });

      const text = response.text;
      if (!text) throw new Error("Gemini returned no text");
      return parseExtractions(text);
    });
  };
}

/** Busy or rate-limited, rather than wrong: worth asking again shortly. */
export const TRANSIENT = /\b(429|500|503|UNAVAILABLE|RESOURCE_EXHAUSTED)\b|overloaded|high demand/i;

export function isTransient(error: unknown): boolean {
  return TRANSIENT.test(error instanceof Error ? error.message : String(error));
}

/**
 * A blip should not leave a memory unenriched. Two more tries, seconds apart --
 * the phone is not waiting on this (rule 2), but the request is.
 */
export async function retryTransient<T>(call: () => Promise<T>, delaysMs = [1_000, 3_000]): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= delaysMs.length; attempt += 1) {
    try {
      return await call();
    } catch (error) {
      lastError = error;
      const delay = delaysMs[attempt];
      if (delay === undefined || !isTransient(error)) break;
      await new Promise((resume) => setTimeout(resume, delay));
    }
  }
  throw lastError;
}

/**
 * One saved item as the model sees it, metadata included -- it is context, not
 * noise. Numbered rather than keyed by the memory's id, because a model copies
 * a small integer back reliably and a UUID often not.
 */
function describe(input: ExtractionInput): string {
  return [
    `--- Item ${input.index}`,
    `Saved on: ${input.capturedAt.toISOString().slice(0, 10)}`,
    `Saved as: ${input.type}`,
    input.sourceAppLabel ? `Shared from: ${input.sourceAppLabel}` : null,
    input.title ? `Title: ${input.title}` : null,
    "Content:",
    input.text?.trim().slice(0, MAX_ITEM_CHARS) || "(none)",
  ]
    .filter((line) => line !== null)
    .join("\n");
}

/**
 * The schema makes the shape very likely, not certain, and a bad reply must
 * not reach the database. An item the model skipped, or answered without a
 * number, is simply missing from the result.
 */
export function parseExtractions(text: string): Map<number, Extraction> {
  const raw: unknown = JSON.parse(text);
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new Error("Gemini returned a non-object");
  }
  const items = (raw as Record<string, unknown>)["items"];
  if (!Array.isArray(items)) throw new Error("Gemini returned no items");

  const extractions = new Map<number, Extraction>();
  for (const item of items) {
    if (typeof item !== "object" || item === null) continue;
    const value = item as Record<string, unknown>;
    const index = value["index"];
    if (typeof index !== "number" || !Number.isInteger(index)) continue;

    extractions.set(index, {
      summary: asString(value["summary"]),
      kind: asString(value["kind"]),
      entities: asStrings(value["entities"]),
      dates: asStrings(value["dates"]),
    });
  }
  return extractions;
}

function asString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asStrings(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map(asString).filter((item) => item.length > 0);
}

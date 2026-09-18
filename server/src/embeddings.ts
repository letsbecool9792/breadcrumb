import { GoogleGenAI } from "@google/genai";
import { pinnedModel, retryTransient } from "./gemini.ts";
import type { MemoryDoc } from "./memories.ts";

/**
 * Pinned, as with the ingest model. `gemini-embedding-2` is now stable and
 * multimodal, which would let a screenshot be embedded as a picture rather
 * than only as its text -- a real gain for images with little text, and a
 * deliberate change to make, not a drive-by one (see Post-V1).
 */
export const DEFAULT_EMBEDDING_MODEL = "gemini-embedding-001";

export const EMBEDDING_MODEL = pinnedModel(process.env.GEMINI_EMBEDDING_MODEL, DEFAULT_EMBEDDING_MODEL);

/**
 * 768, per architecture rule 8: MTEB 67.99 against 68.17 at 1536 and 3072,
 * for a quarter of the storage on a 512MB M0.
 */
export const EMBEDDING_DIMENSIONS = 768;

/**
 * The model reads about 2048 tokens. Anything past this cap is dropped, so
 * [embeddingText] puts the summary and title first and the body last: what
 * gets cut is the tail of a long screenshot, not the description of it.
 */
const MAX_CHARS = 6_000;

/**
 * A document is embedded for storage, a search phrase for lookup. The same
 * text embedded under the two task types does not land in the same place, and
 * using one for both is the quiet way to make retrieval mediocre.
 */
export type EmbeddingPurpose = "document" | "query";

/**
 * Embeds several texts in one call, in the order given. Batched for the same
 * reason as extraction: a free tier counts requests, not items.
 */
export type Embedder = (texts: string[], purpose: EmbeddingPurpose) => Promise<number[][]>;

export function geminiEmbedder(apiKey: string): Embedder {
  const ai = new GoogleGenAI({ apiKey });

  return async (texts, purpose) => {
    if (texts.length === 0) return [];
    return retryTransient(async () => {
      const response = await ai.models.embedContent({
        model: EMBEDDING_MODEL,
        contents: texts.map((text) => text.slice(0, MAX_CHARS)),
        config: {
          outputDimensionality: EMBEDDING_DIMENSIONS,
          taskType: purpose === "query" ? "RETRIEVAL_QUERY" : "RETRIEVAL_DOCUMENT",
        },
      });

      const embeddings = response.embeddings ?? [];
      if (embeddings.length !== texts.length) {
        throw new Error(`asked for ${texts.length} embeddings, got ${embeddings.length}`);
      }
      return embeddings.map((embedding) => {
        const values = embedding.values;
        if (!values?.length) throw new Error("Gemini returned an empty embedding");
        if (values.length !== EMBEDDING_DIMENSIONS) {
          throw new Error(`expected ${EMBEDDING_DIMENSIONS} dimensions, got ${values.length}`);
        }
        return normalize(values);
      });
    });
  };
}

/**
 * gemini-embedding-001 only returns unit-length vectors at its full 3072
 * dimensions; truncated ones must be normalised by hand. Skip this and cosine
 * similarity quietly measures length as much as meaning.
 */
export function normalize(values: number[]): number[] {
  const length = Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
  if (length === 0) throw new Error("cannot normalize a zero vector");
  return values.map((value) => value / length);
}

/** Cosine similarity. For unit vectors this is just the dot product. */
export function cosine(a: number[], b: number[]): number {
  if (a.length !== b.length) throw new Error("vectors of different lengths");
  return a.reduce((sum, value, i) => sum + value * (b[i] ?? 0), 0);
}

/**
 * What gets embedded, and in what order.
 *
 * Gemini's summary and the entities come first: they say what the thing is in
 * the words someone would search with, and they survive the cap. The source
 * app is deliberately absent -- provenance is a filter (step 4.3), and mixing
 * "WhatsApp" into the meaning of every message would pull unrelated saves
 * together.
 */
export function embeddingText(
  memory: Pick<MemoryDoc, "title" | "rawText" | "extractedText" | "enrichment">,
): string {
  const enrichment = memory.enrichment;
  return [
    enrichment?.summary,
    enrichment?.kind,
    memory.title,
    enrichment?.entities?.join(", "),
    // what the model saw in the picture, when it was read as one (3.6)
    enrichment?.readText,
    memory.rawText,
    memory.extractedText,
  ]
    .map((part) => part?.trim())
    .filter((part): part is string => !!part)
    .join("\n")
    .slice(0, MAX_CHARS);
}

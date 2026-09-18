import type { Db } from "mongodb";
import type { Embedder } from "./embeddings.ts";
import { isTransient } from "./gemini.ts";
import { type MemoryDoc, type MemoryType, memories, VECTOR_INDEX } from "./memories.ts";

/** A phrase someone remembers, not a document. Past this it is not a search. */
export const MAX_QUERY_CHARS = 500;

export const DEFAULT_LIMIT = 20;
export const MAX_LIMIT = 50;

export interface SearchRequest {
  query: string;
  limit: number;
}

export type SearchParseResult = { ok: true; request: SearchRequest } | { ok: false; error: string };

/**
 * Reads `q` and `limit` from a query string. A repeated parameter arrives as
 * an array and is refused rather than guessed at.
 */
export function parseSearch(params: Record<string, unknown>): SearchParseResult {
  const q = params["q"];
  if (typeof q !== "string") return { ok: false, error: "q is required: what to search for" };
  const query = q.trim().replace(/\s+/g, " ");
  if (!query) return { ok: false, error: "q is required: what to search for" };
  if (query.length > MAX_QUERY_CHARS) {
    return { ok: false, error: `q is longer than ${MAX_QUERY_CHARS} characters` };
  }

  const rawLimit = params["limit"];
  if (rawLimit === undefined) return { ok: true, request: { query, limit: DEFAULT_LIMIT } };
  const limit = typeof rawLimit === "string" && /^\d+$/.test(rawLimit) ? Number(rawLimit) : Number.NaN;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    return { ok: false, error: `limit must be a whole number from 1 to ${MAX_LIMIT}` };
  }
  return { ok: true, request: { query, limit } };
}

/**
 * One result: what it is, what it says, why it matched, and the id the phone
 * looks its own row up by. The text comes back whole, so a result can be read
 * without the phone -- the server can hold memories the phone has since
 * deleted, since deletes do not sync. The vector never leaves.
 */
export interface SearchHit {
  id: string;
  /** Atlas's vectorSearchScore: (1 + cosine) / 2, so 0.5 is unrelated and 1 identical. */
  score: number;
  type: MemoryType;
  hasLink: boolean;
  capturedAt: Date;
  sourceAppLabel: string | null;
  title: string | null;
  /** What was shared: the link, the text, a photo's caption. */
  rawText: string | null;
  /** What on-device OCR read in a picture. */
  extractedText: string | null;
  /** What the model saw in a picture OCR could barely read (step 3.6). */
  readText: string | null;
  /** Gemini's one line about it -- the most a result says about why it matched. */
  summary: string | null;
  kind: string | null;
}

/**
 * Exact nearest neighbours rather than approximate. Atlas recommends exact
 * search under about 10,000 documents: it needs no `numCandidates` tuning,
 * cannot miss a close match, and stays exact once 4.3 adds pre-filters, where
 * approximate search is weakest. A personal corpus sits well under that; past
 * it, switch to `numCandidates` at 10-20x the limit.
 *
 * The projection names what leaves, rather than excluding what must not: a
 * field added to the document later -- and the 768-number vector, which no
 * client needs -- stays here unless it is listed.
 */
export function vectorSearchPipeline(queryVector: number[], limit: number) {
  return [
    {
      $vectorSearch: {
        index: VECTOR_INDEX,
        path: "embedding",
        queryVector,
        exact: true,
        limit,
      },
    },
    {
      $project: {
        type: 1,
        hasLink: 1,
        capturedAt: 1,
        sourceAppLabel: 1,
        title: 1,
        rawText: 1,
        extractedText: 1,
        "enrichment.readText": 1,
        "enrichment.summary": 1,
        "enrichment.kind": 1,
        score: { $meta: "vectorSearchScore" },
      },
    },
  ];
}

type Projected = Pick<
  MemoryDoc,
  "_id" | "type" | "hasLink" | "capturedAt" | "sourceAppLabel" | "title" | "rawText" | "extractedText"
> & {
  enrichment?: { summary?: string; kind?: string; readText?: string };
  score: number;
};

export type SearchOutcome =
  | { ok: true; results: SearchHit[] }
  | { ok: false; reason: string; retryable: boolean };

/**
 * Embeds the phrase and returns the nearest memories, best first.
 *
 * Only a failed embedding comes back as an outcome, since that is the model's
 * trouble and may pass; a database failure is thrown, like everywhere else.
 * A memory with no embedding yet -- an image waiting on OCR -- is not in the
 * vector index, so it cannot be a result.
 *
 * Beware: a missing vector index is not an error. Atlas answers an empty list,
 * so an index that was never created looks exactly like a search that found
 * nothing. Startup checks for it (see `ensureVectorIndex`).
 */
export async function searchMemories(database: Db, embed: Embedder, request: SearchRequest): Promise<SearchOutcome> {
  let queryVector: number[];
  try {
    const [vector] = await embed([request.query], "query");
    if (!vector) throw new Error("the embedding model returned nothing for the query");
    queryVector = vector;
  } catch (error) {
    return {
      ok: false,
      reason: error instanceof Error ? error.message : String(error),
      retryable: isTransient(error),
    };
  }

  const found = await memories(database)
    .aggregate<Projected>(vectorSearchPipeline(queryVector, request.limit))
    .toArray();

  return {
    ok: true,
    results: found.map((doc) => ({
      id: doc._id,
      score: doc.score,
      type: doc.type,
      hasLink: doc.hasLink,
      capturedAt: doc.capturedAt,
      sourceAppLabel: doc.sourceAppLabel ?? null,
      title: doc.title ?? null,
      rawText: doc.rawText ?? null,
      extractedText: doc.extractedText ?? null,
      readText: doc.enrichment?.readText || null,
      summary: doc.enrichment?.summary || null,
      kind: doc.enrichment?.kind || null,
    })),
  };
}

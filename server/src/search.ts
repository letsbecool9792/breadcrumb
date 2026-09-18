import type { Db } from "mongodb";
import type { Embedder } from "./embeddings.ts";
import { isTransient } from "./gemini.ts";
import { type MemoryDoc, type MemoryType, memories, TEXT_INDEX, TEXT_PATHS, VECTOR_INDEX } from "./memories.ts";
import { dayAfter, hasFilters, type Interpretation, localDay, type Understand } from "./query.ts";

/** A phrase someone remembers, not a document. Past this it is not a search. */
export const MAX_QUERY_CHARS = 500;

export const DEFAULT_LIMIT = 20;
export const MAX_LIMIT = 50;

/**
 * Narrows both halves of the search before either ranks. The parsed query
 * fills it in (4.3); `ids` is how the tests keep to their own documents.
 * Every condition given must hold.
 */
export interface SearchFilter {
  /** Any of these. LINK also matches any memory carrying a link -- a captioned photo, a PDF. */
  types?: MemoryType[];
  /** On datedAt: from inclusive, to exclusive. */
  from?: Date;
  to?: Date;
  sourceAppLabel?: string;
  ids?: string[];
}

export interface SearchRequest {
  query: string;
  limit: number;
  filter?: SearchFilter;
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

/** Where a result placed in each half of the search; null where it did not place at all. */
export interface Ranks {
  /** By meaning: nearness of its embedding to the phrase's. */
  vector: number | null;
  /** By words: the phrase's words in its text, scored by BM25. */
  text: number | null;
}

/**
 * One result: what it is, what it says, why it matched, and the id the phone
 * looks its own row up by. The text comes back whole, so a result can be read
 * without the phone -- the server can hold memories the phone has since
 * deleted, since deletes do not sync. The vector never leaves.
 */
export interface SearchHit {
  id: string;
  /**
   * The fused score: 1 / (60 + rank) summed over the halves it placed in. It
   * says only which result is ahead -- about 0.016 is first place in one half,
   * about 0.033 first in both -- and means nothing across searches. Null when
   * the search was all filter, and results are simply newest first.
   */
  score: number | null;
  ranks: Ranks;
  type: MemoryType;
  hasLink: boolean;
  capturedAt: Date;
  /** When a picture was taken, else when it was saved: what a date filter matches. */
  datedAt: Date;
  sourceAppLabel: string | null;
  title: string | null;
  /** What was shared: the link, the text, a photo's caption. */
  rawText: string | null;
  /** What the phone read out of it: a picture's words, a PDF's text, a link's page. */
  extractedText: string | null;
  /** The person's own words about it. */
  note: string | null;
  /** What the model saw in a picture OCR could barely read (step 3.6). */
  readText: string | null;
  /** Gemini's one line about it -- the most a result says about why it matched. */
  summary: string | null;
  kind: string | null;
}

/**
 * How many results each half offers the fusion. More than the final list, so
 * a memory ranked modestly by both halves can still rise above one ranked
 * first by only one.
 */
function perHalf(limit: number): number {
  return Math.min(Math.max(limit * 2, 20), 100);
}

/**
 * Hybrid search, fused by reciprocal rank in Atlas's own `$rankFusion`
 * (architecture rule 5). Each half ranks the corpus its own way, and a memory
 * scores 1 / (60 + rank) for each half it places in:
 *
 * - vector: nearest in meaning. Blurs exact words -- one "government" in forty
 *   lines barely moves an embedding.
 * - text: the phrase's words, stemmed, stop words dropped. Blind to meaning,
 *   but a proper noun or a rare word lands exactly.
 *
 * So a memory both halves agree on beats one that only one half likes, and a
 * memory the embedding missed can still be found by its words -- including one
 * that has no embedding at all, because its embedding call failed.
 *
 * The halves weigh the same. Change that with evidence from a real corpus
 * (phase 5), not before.
 *
 * Vector search is exact rather than approximate: Atlas recommends it under
 * about 10,000 documents, it needs no `numCandidates` tuning, and it stays
 * exact under 4.3's pre-filters, where approximate search is weakest. Past
 * that size, switch to `numCandidates` at 10-20x the limit.
 *
 * The projection names what leaves, rather than excluding what must not: a
 * field added to the document later -- and the 768-number vector, which no
 * client needs -- stays here unless it is listed.
 */
export function searchPipeline(query: string, queryVector: number[], limit: number, filter: SearchFilter = {}) {
  const candidates = perHalf(limit);
  return [
    {
      $rankFusion: {
        input: {
          pipelines: {
            vector: [
              {
                $vectorSearch: {
                  index: VECTOR_INDEX,
                  path: "embedding",
                  queryVector,
                  exact: true,
                  limit: candidates,
                  ...vectorFilter(filter),
                },
              },
            ],
            text: [
              {
                $search: {
                  index: TEXT_INDEX,
                  compound: {
                    must: [{ text: { query, path: TEXT_PATHS } }],
                    ...textFilter(filter),
                  },
                },
              },
              { $limit: candidates },
            ],
          },
        },
        combination: { weights: { vector: 1, text: 1 } },
        scoreDetails: true,
      },
    },
    { $limit: limit },
    { $project: { ...RESULT_FIELDS, score: { $meta: "score" }, scoreDetails: { $meta: "scoreDetails" } } },
  ];
}

/** What leaves the server about a memory. An allow-list: see [searchPipeline]. */
const RESULT_FIELDS = {
  type: 1,
  hasLink: 1,
  capturedAt: 1,
  datedAt: 1,
  sourceAppLabel: 1,
  title: 1,
  rawText: 1,
  extractedText: 1,
  note: 1,
  "enrichment.readText": 1,
  "enrichment.summary": 1,
  "enrichment.kind": 1,
} as const;

/**
 * The filter in MQL, for `$vectorSearch` and for a plain listing. A link
 * filter matches `type = LINK OR hasLink`: filtering on type alone would miss
 * every captioned photo and PDF that carries one.
 */
export function mqlFilter(filter: SearchFilter): Record<string, unknown> {
  const clauses: Record<string, unknown>[] = [];
  if (filter.types?.length) {
    const byType = { type: { $in: filter.types } };
    clauses.push(filter.types.includes("LINK") ? { $or: [byType, { hasLink: true }] } : byType);
  }
  if (filter.from || filter.to) {
    clauses.push({ datedAt: { ...(filter.from ? { $gte: filter.from } : {}), ...(filter.to ? { $lt: filter.to } : {}) } });
  }
  if (filter.sourceAppLabel !== undefined) clauses.push({ sourceAppLabel: { $eq: filter.sourceAppLabel } });
  if (filter.ids) clauses.push({ _id: { $in: filter.ids } });

  if (clauses.length === 0) return {};
  return clauses.length === 1 ? (clauses[0] as Record<string, unknown>) : { $and: clauses };
}

/** The same filter in Atlas Search operators, for the text half. */
export function searchFilterClauses(filter: SearchFilter): object[] {
  const clauses: object[] = [];
  if (filter.types?.length) {
    const byType = { in: { path: "type", value: filter.types } };
    clauses.push(
      filter.types.includes("LINK")
        ? { compound: { should: [byType, { equals: { path: "hasLink", value: true } }], minimumShouldMatch: 1 } }
        : byType,
    );
  }
  if (filter.from || filter.to) {
    clauses.push({
      range: { path: "datedAt", ...(filter.from ? { gte: filter.from } : {}), ...(filter.to ? { lt: filter.to } : {}) },
    });
  }
  if (filter.sourceAppLabel !== undefined) {
    clauses.push({ equals: { path: "sourceAppLabel", value: filter.sourceAppLabel } });
  }
  if (filter.ids) clauses.push({ in: { path: "_id", value: filter.ids } });
  return clauses;
}

function vectorFilter(filter: SearchFilter) {
  const mql = mqlFilter(filter);
  return Object.keys(mql).length === 0 ? {} : { filter: mql };
}

function textFilter(filter: SearchFilter) {
  const clauses = searchFilterClauses(filter);
  return clauses.length === 0 ? {} : { filter: clauses };
}

type Projected = Pick<
  MemoryDoc,
  | "_id"
  | "type"
  | "hasLink"
  | "capturedAt"
  | "datedAt"
  | "sourceAppLabel"
  | "title"
  | "rawText"
  | "extractedText"
  | "note"
> & {
  enrichment?: { summary?: string; kind?: string; readText?: string };
  score?: number;
  scoreDetails?: { details?: { inputPipelineName?: string; rank?: number }[] };
};

/** Atlas reports rank 0 for a half a result did not place in. */
function ranksFrom(details: Projected["scoreDetails"]): Ranks {
  const rankIn = (half: keyof Ranks) => {
    const rank = details?.details?.find((detail) => detail.inputPipelineName === half)?.rank;
    return rank && rank > 0 ? rank : null;
  };
  return { vector: rankIn("vector"), text: rankIn("text") };
}

export type SearchOutcome =
  | { ok: true; results: SearchHit[] }
  | { ok: false; reason: string; retryable: boolean };

/**
 * Embeds the phrase and returns the best memories by both meaning and words.
 *
 * Only a failed embedding comes back as an outcome, since that is the model's
 * trouble and may pass; a database failure is thrown, like everywhere else.
 * A memory with neither words nor an embedding -- an image waiting on OCR --
 * is in neither half, so it cannot be a result.
 *
 * Beware: a missing index is not an error. Atlas answers that half with an
 * empty list, so a search missing one half looks like a search that simply
 * found less. Startup checks for both (see `reportIndex`).
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
    .aggregate<Projected>(searchPipeline(request.query, queryVector, request.limit, request.filter))
    .toArray();

  return { ok: true, results: found.map(toHit) };
}

/**
 * A search that was all filter -- "screenshots from April" -- has nothing to
 * rank by, so its matches come back newest first. No model call at all.
 */
export async function listMemories(database: Db, filter: SearchFilter, limit: number): Promise<SearchHit[]> {
  const found = await memories(database)
    .find(mqlFilter(filter), { projection: RESULT_FIELDS })
    .sort({ datedAt: -1 })
    .limit(limit)
    .toArray();
  return (found as Projected[]).map(toHit);
}

function toHit(doc: Projected): SearchHit {
  return {
    id: doc._id,
    score: doc.score ?? null,
    ranks: ranksFrom(doc.scoreDetails),
    type: doc.type,
    hasLink: doc.hasLink,
    capturedAt: doc.capturedAt,
    datedAt: doc.datedAt,
    sourceAppLabel: doc.sourceAppLabel ?? null,
    title: doc.title ?? null,
    rawText: doc.rawText ?? null,
    extractedText: doc.extractedText ?? null,
    note: doc.note ?? null,
    readText: doc.enrichment?.readText || null,
    summary: doc.enrichment?.summary || null,
    kind: doc.enrichment?.kind || null,
  };
}

/** Turns what the parser read into the filter both halves run under. */
export function filterFor(interpretation: Interpretation): SearchFilter {
  return {
    ...(interpretation.types.length ? { types: interpretation.types } : {}),
    ...(interpretation.from ? { from: localDay(interpretation.from) } : {}),
    ...(interpretation.to ? { to: dayAfter(interpretation.to) } : {}),
    ...(interpretation.sourceApp ? { sourceAppLabel: interpretation.sourceApp } : {}),
  };
}

export interface SearchAnswer {
  /** The phrase as typed. */
  query: string;
  /** How it was taken apart, so a searcher can see why the list is what it is. */
  interpretation: Interpretation | null;
  /** Why it could not be taken apart; the search then ran on the raw phrase, unfiltered. */
  interpretationError?: string;
  results: SearchHit[];
}

export type AnswerOutcome = { ok: true; answer: SearchAnswer } | { ok: false; reason: string; retryable: boolean };

/**
 * The whole of a search (rule 6): take the phrase apart, then search what is
 * left of it under the filters it asked for.
 *
 * A parse that fails never fails the search. It costs the filters, and the
 * raw phrase is searched as it was typed -- worse, but still a search.
 *
 * `request.filter` is laid over whatever the phrase asked for; the tests use
 * it to keep to their own documents.
 */
export async function answerSearch(
  database: Db,
  embed: Embedder,
  understand: Understand,
  request: SearchRequest,
): Promise<AnswerOutcome> {
  const { interpretation, error } = await understand(database, request.query);
  const filter: SearchFilter = { ...(interpretation ? filterFor(interpretation) : {}), ...request.filter };

  // Words the parser left, or the phrase itself when it took everything and
  // filtered nothing ("stuff") -- an empty search is never what was meant.
  const words = interpretation && (interpretation.query || hasFilters(interpretation)) ? interpretation.query : request.query;

  const base = {
    query: request.query,
    interpretation,
    ...(error === undefined ? {} : { interpretationError: error }),
  };

  if (!words) {
    return { ok: true, answer: { ...base, results: await listMemories(database, filter, request.limit) } };
  }

  const outcome = await searchMemories(database, embed, { query: words, limit: request.limit, filter });
  return outcome.ok ? { ok: true, answer: { ...base, results: outcome.results } } : outcome;
}

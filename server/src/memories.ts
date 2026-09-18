import type { Collection, Db } from "mongodb";

export type MemoryType = "TEXT" | "LINK" | "IMAGE" | "PDF" | "AUDIO";

/**
 * A memory as the cloud holds it: text, metadata and later an embedding --
 * never the original file (architecture rule 1). The phone keeps those.
 *
 * Fields mirror the Room entity, hand-kept in step with it rather than
 * generated: one client, one server. Missing here on purpose: `localUri`,
 * which means nothing off the device, and `syncState`, which is the phone's
 * own bookkeeping. `summary` and entities arrive with Gemini at 3.3, the
 * embedding at 3.4.
 */
export interface MemoryDoc {
  /** The id the phone generated, so a retried upload replaces rather than duplicates. */
  _id: string;
  type: MemoryType;
  hasLink: boolean;
  capturedAt: Date;
  contentCreatedAt?: Date | null;
  /**
   * The date a person places it by: when a picture was taken, else when it was
   * saved. What "from April" filters on (step 4.3) -- a screenshot imported in
   * September was still taken in April. Derived here, never sent by the phone.
   */
  datedAt: Date;
  sourceApp?: string | null;
  sourceAppLabel?: string | null;
  title?: string | null;
  rawText?: string | null;
  /** What on-device OCR read (step 2.1). */
  extractedText?: string | null;
  /** The phone's last change to the row. */
  updatedAt: Date;
  /** Server clock, set on every write here. */
  syncedAt: Date;

  /** What one Gemini call made of it (step 3.3). Absent until that succeeds. */
  enrichment?: Enrichment;
  /**
   * Fingerprint of the text [enrichment] was made from. A re-send whose text
   * has not changed reuses it rather than spending another model call -- which
   * is what keeps retries free on a rate-limited free tier.
   */
  enrichedFrom?: string;

  /** Unit-length, [EMBEDDING_DIMENSIONS] long (step 3.4). */
  embedding?: number[];
  /** Which model and size produced [embedding], so a re-embed can tell what is stale. */
  embeddedWith?: { model: string; dimensions: number; at: Date };
  /** Fingerprint of the text [embedding] was made from, as with [enrichedFrom]. */
  embeddedFrom?: string;

  /** Fingerprint of the picture that was read (step 3.6), so the same one is never read twice. */
  imageReadFrom?: string;
  imageReadAt?: Date;
  /** Why the last embedding attempt failed, when it did. */
  embeddingError?: string;
  /**
   * Why the last enrichment failed. The memory is stored either way, so this
   * marks the ones worth another pass.
   */
  enrichmentError?: string;
}

export interface Enrichment {
  summary: string;
  kind: string;
  entities: string[];
  dates: string[];
  /** What the model saw in the picture, when the memory was read as one (step 3.6). */
  readText?: string;
  at: Date;
}

export function memories(database: Db): Collection<MemoryDoc> {
  return database.collection<MemoryDoc>("memories");
}

/**
 * capturedAt, and datedAt for listing a filter's matches newest first when a
 * search is all filter ("screenshots from April"). Filtering within a search
 * runs inside the search indexes themselves.
 */
export async function ensureIndexes(database: Db): Promise<void> {
  await memories(database).createIndex({ capturedAt: -1 }, { name: "capturedAt_desc" });
  await memories(database).createIndex({ datedAt: -1 }, { name: "datedAt_desc" });
}

/** When a memory is dated: when its picture was taken, else when it was saved. */
export function datedAt(memory: { capturedAt: Date; contentCreatedAt?: Date | null }): Date {
  return memory.contentCreatedAt ?? memory.capturedAt;
}

/**
 * Gives documents stored before datedAt existed their date. Idempotent and
 * cheap, so it runs at every startup; once every document has one it matches
 * nothing.
 */
export async function backfillDatedAt(database: Db): Promise<number> {
  const result = await memories(database).updateMany({ datedAt: { $exists: false } }, [
    { $set: { datedAt: { $ifNull: ["$contentCreatedAt", "$capturedAt"] } } },
  ]);
  return result.modifiedCount;
}

/**
 * Idempotent on the phone's id: re-sending the same memory replaces it.
 *
 * A replace, so a memory that loses a field on the phone loses it here too --
 * which also means a re-send with no enrichment drops the enrichment, and the
 * caller passes it back in.
 */
export async function putMemory(database: Db, memory: Omit<MemoryDoc, "syncedAt">): Promise<void> {
  await memories(database).replaceOne(
    { _id: memory._id },
    { ...memory, syncedAt: new Date() },
    { upsert: true },
  );
}

export const VECTOR_INDEX = "memories_vector";

/**
 * The Atlas Vector Search index. Declared filter fields are the ones 4.3
 * filters on before searching; a field not declared here cannot be used as a
 * pre-filter, and adding one later means rebuilding the index.
 *
 * M0 allows three search indexes across the whole cluster, so this plus
 * [TEXT_INDEX] leaves one spare.
 */
export function vectorIndexDefinition(dimensions: number) {
  return {
    fields: [
      { type: "vector", path: "embedding", numDimensions: dimensions, similarity: "cosine" },
      { type: "filter", path: "type" },
      { type: "filter", path: "hasLink" },
      { type: "filter", path: "capturedAt" },
      { type: "filter", path: "sourceAppLabel" },
      // 4.3's date filter
      { type: "filter", path: "datedAt" },
      // narrowing to given memories; the search tests use it to see only their own
      { type: "filter", path: "_id" },
    ],
  };
}

export const TEXT_INDEX = "memories_text";

/** Every field a memory's words live in. Searched together, one score across all. */
export const TEXT_PATHS = [
  "title",
  "rawText",
  "extractedText",
  "enrichment.summary",
  "enrichment.kind",
  "enrichment.entities",
  "enrichment.readText",
];

/**
 * The Atlas Search index behind the keyword half of hybrid search (rule 5):
 * the exact words and proper nouns an embedding blurs.
 *
 * lucene.english rather than lucene.standard: it drops stop words, so "that
 * link from April" is not matched on "that" and "from" across the whole
 * corpus, and it stems, so "governments" finds "government".
 *
 * The source app is a token, never text: provenance is a filter (4.3), and
 * "WhatsApp" in a search must not match the label of every WhatsApp save.
 * Like the vector index, it declares 4.3's filter fields up front, since
 * adding one later means rebuilding.
 */
export function textIndexDefinition() {
  return {
    analyzer: "lucene.english",
    searchAnalyzer: "lucene.english",
    mappings: {
      dynamic: false,
      fields: {
        title: { type: "string" },
        rawText: { type: "string" },
        extractedText: { type: "string" },
        enrichment: {
          type: "document",
          fields: {
            summary: { type: "string" },
            kind: { type: "string" },
            entities: { type: "string" },
            readText: { type: "string" },
          },
        },
        type: { type: "token" },
        hasLink: { type: "boolean" },
        capturedAt: { type: "date" },
        sourceAppLabel: { type: "token" },
        datedAt: { type: "date" },
        _id: { type: "token" },
      },
    },
  };
}

/**
 * Every field path a search index definition declares, of either kind: the
 * vector index's `fields` list, or the text index's nested mappings.
 */
export function declaredPaths(definition: unknown): string[] {
  const value = definition as {
    fields?: { path?: string }[];
    mappings?: { fields?: Record<string, unknown> };
  };
  if (Array.isArray(value?.fields)) {
    return value.fields.flatMap((field) => (typeof field.path === "string" ? [field.path] : []));
  }
  const walk = (fields: Record<string, unknown> | undefined, prefix: string): string[] =>
    Object.entries(fields ?? {}).flatMap(([name, field]) => {
      const nested = (field as { type?: string; fields?: Record<string, unknown> }) ?? {};
      return nested.type === "document" && nested.fields
        ? walk(nested.fields, `${prefix}${name}.`)
        : [`${prefix}${name}`];
    });
  return walk(value?.mappings?.fields, "");
}

/**
 * "updated": it existed without a field declared here, and is rebuilding with it.
 * "full": the cluster already holds as many search indexes as its tier allows.
 */
export type SearchIndexState = "created" | "exists" | "updated" | "refused" | "full";

/**
 * Creates a search index if the collection does not have one by that name,
 * and updates one that lacks a field its definition now declares -- the
 * common change, as when 4.3 added `datedAt`.
 *
 * Only a missing field triggers an update, never any other difference: Atlas
 * reports definitions back with its own defaults filled in, so comparing them
 * whole would rebuild the index at every start. A changed analyzer or type is
 * applied deliberately, in the Atlas UI or with updateSearchIndex. While an
 * update builds, the old version keeps answering.
 *
 * Returns "refused" rather than throwing when the database user may not manage
 * search indexes: readWriteAnyDatabase is enough for everything else this
 * server does, and widening it permanently for one index would be the wrong
 * trade -- create it in the Atlas UI from its definition instead.
 */
export async function ensureSearchIndex(
  database: Db,
  name: string,
  type: "search" | "vectorSearch",
  definition: object,
): Promise<SearchIndexState> {
  const collection = memories(database);
  try {
    const indexes = (await collection.listSearchIndexes().toArray()) as { name: string; latestDefinition?: unknown }[];
    const existing = indexes.find((index) => index.name === name);
    if (existing) {
      const has = new Set(declaredPaths(existing.latestDefinition));
      if (declaredPaths(definition).every((path) => has.has(path))) return "exists";
      await collection.updateSearchIndex(name, definition);
      return "updated";
    }

    await collection.createSearchIndex({ name, type, definition });
    return "created";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (/maximum number of FTS indexes/i.test(message)) return "full";
    if (/not allowed|unauthorized|requires authentication|Atlas Search/i.test(message)) return "refused";
    throw error;
  }
}

export function ensureVectorIndex(database: Db, dimensions: number): Promise<SearchIndexState> {
  return ensureSearchIndex(database, VECTOR_INDEX, "vectorSearch", vectorIndexDefinition(dimensions));
}

export function ensureTextIndex(database: Db): Promise<SearchIndexState> {
  return ensureSearchIndex(database, TEXT_INDEX, "search", textIndexDefinition());
}

/** The same, for a batch: one round trip rather than one per memory. */
export async function putMemories(database: Db, docs: Omit<MemoryDoc, "syncedAt">[]): Promise<void> {
  if (docs.length === 0) return;
  const syncedAt = new Date();
  await memories(database).bulkWrite(
    docs.map((doc) => ({
      replaceOne: { filter: { _id: doc._id }, replacement: { ...doc, syncedAt }, upsert: true },
    })),
  );
}

export async function getMemory(database: Db, id: string): Promise<MemoryDoc | null> {
  return memories(database).findOne({ _id: id });
}

export async function recentMemories(database: Db, limit = 20): Promise<MemoryDoc[]> {
  return memories(database).find().sort({ capturedAt: -1 }).limit(limit).toArray();
}

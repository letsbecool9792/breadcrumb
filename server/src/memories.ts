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
 * capturedAt only. Type and date filtering at 4.3 runs inside Atlas Search's
 * own index, so a b-tree index for it would sit unused.
 */
export async function ensureIndexes(database: Db): Promise<void> {
  await memories(database).createIndex({ capturedAt: -1 }, { name: "capturedAt_desc" });
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
 * M0 allows three search indexes in total, so this plus the text index at 4.4
 * still leaves one spare.
 */
export function vectorIndexDefinition(dimensions: number) {
  return {
    fields: [
      { type: "vector", path: "embedding", numDimensions: dimensions, similarity: "cosine" },
      { type: "filter", path: "type" },
      { type: "filter", path: "hasLink" },
      { type: "filter", path: "capturedAt" },
      { type: "filter", path: "sourceAppLabel" },
    ],
  };
}

export type VectorIndexState = "created" | "exists" | "refused";

/**
 * Creates the vector index if the cluster does not have it.
 *
 * Returns "refused" rather than throwing when the database user may not manage
 * search indexes: readWriteAnyDatabase is enough for everything else this
 * server does, and widening it permanently for one index would be the wrong
 * trade -- create it in the Atlas UI from [vectorIndexDefinition] instead.
 */
export async function ensureVectorIndex(database: Db, dimensions: number): Promise<VectorIndexState> {
  const collection = memories(database);
  try {
    const existing = await collection.listSearchIndexes().toArray();
    if (existing.some((index) => index.name === VECTOR_INDEX)) return "exists";

    await collection.createSearchIndex({
      name: VECTOR_INDEX,
      type: "vectorSearch",
      definition: vectorIndexDefinition(dimensions),
    });
    return "created";
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (/not allowed|unauthorized|requires authentication|Atlas Search/i.test(message)) return "refused";
    throw error;
  }
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

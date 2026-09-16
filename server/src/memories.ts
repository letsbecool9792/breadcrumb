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

export async function getMemory(database: Db, id: string): Promise<MemoryDoc | null> {
  return memories(database).findOne({ _id: id });
}

export async function recentMemories(database: Db, limit = 20): Promise<MemoryDoc[]> {
  return memories(database).find().sort({ capturedAt: -1 }).limit(limit).toArray();
}

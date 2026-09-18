import type { Db } from "mongodb";
import { memories } from "./memories.ts";

/** Ids per request. The phone asks in batches of 50; this is the ceiling. */
export const MAX_IDS = 100;

export type IdsParseResult = { ok: true; ids: string[] } | { ok: false; error: string };

/** `{ "ids": [...] }`, each a memory's id as the phone knows it. Repeats are asked about once. */
export function parseIds(body: unknown): IdsParseResult {
  const ids = typeof body === "object" && body !== null ? (body as Record<string, unknown>)["ids"] : undefined;
  if (!Array.isArray(ids) || ids.length === 0 || ids.length > MAX_IDS) {
    return { ok: false, error: `ids must be a list of 1 to ${MAX_IDS} memory ids` };
  }
  if (!ids.every((id) => typeof id === "string" && id.trim().length > 0 && id.length <= 200)) {
    return { ok: false, error: "every id must be a non-empty string" };
  }
  return { ok: true, ids: [...new Set(ids.map((id: string) => id.trim()))] };
}

/** What the model made of one memory, for the phone to keep beside its own row. */
export interface EnrichmentReply {
  id: string;
  summary: string | null;
  kind: string | null;
  readText: string | null;
}

/**
 * What the server made of each memory it holds (step 4.6), so the phone can
 * show a memory's summary and what the model saw in it however it was opened,
 * and find it by those words offline. No model call: this reads what ingest
 * already stored.
 *
 * An id the server does not hold is absent from the answer. A memory held but
 * not enriched -- nothing to read yet, or a model call still owed -- comes back
 * with nulls, which is itself an answer: the phone asks again only after it
 * next sends the memory.
 */
export async function enrichmentFor(database: Db, ids: string[]): Promise<EnrichmentReply[]> {
  const docs = await memories(database)
    .find(
      { _id: { $in: ids } },
      { projection: { "enrichment.summary": 1, "enrichment.kind": 1, "enrichment.readText": 1 } },
    )
    .toArray();
  return docs.map((doc) => ({
    id: doc._id,
    summary: doc.enrichment?.summary || null,
    kind: doc.enrichment?.kind || null,
    readText: doc.enrichment?.readText || null,
  }));
}

/**
 * Deletes memories deleted on the phone (step 4.6): the text, the enrichment
 * and the vector all go, and the search indexes follow within a moment.
 * Idempotent -- an id already gone, or never sent, is simply not counted -- so
 * the phone can send the same delete again after a failure.
 */
export async function deleteMemories(database: Db, ids: string[]): Promise<number> {
  const result = await memories(database).deleteMany({ _id: { $in: ids } });
  return result.deletedCount;
}

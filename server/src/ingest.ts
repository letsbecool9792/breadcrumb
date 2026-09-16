import type { Db } from "mongodb";
import { EMBEDDING_DIMENSIONS, EMBEDDING_MODEL, type Embedder, embeddingText } from "./embeddings.ts";
import type { Extractor } from "./gemini.ts";
import { type MemoryDoc, type MemoryType, putMemory } from "./memories.ts";

const TYPES: MemoryType[] = ["TEXT", "LINK", "IMAGE", "PDF", "AUDIO"];

/** A memory as the phone sends it: the Room row, minus what is device-only. */
export interface IncomingMemory {
  id: string;
  type: MemoryType;
  hasLink: boolean;
  capturedAt: Date;
  contentCreatedAt: Date | null;
  sourceApp: string | null;
  sourceAppLabel: string | null;
  title: string | null;
  rawText: string | null;
  extractedText: string | null;
  updatedAt: Date;
}

export type ParseResult =
  | { ok: true; memory: IncomingMemory }
  | { ok: false; errors: string[] };

/**
 * Hand-written rather than a schema library: one client sends this, the shape
 * is ten fields, and the errors say more this way. Anything unknown in the
 * body is dropped rather than stored -- the client does not get to decide what
 * a memory document contains.
 */
export function parseMemory(body: unknown): ParseResult {
  const errors: string[] = [];
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, errors: ["body must be a JSON object"] };
  }
  const value = body as Record<string, unknown>;

  const id = typeof value["id"] === "string" ? value["id"].trim() : "";
  if (!id) errors.push("id is required and must be a non-empty string");
  if (id.length > 200) errors.push("id is too long");

  const type = value["type"];
  if (typeof type !== "string" || !TYPES.includes(type as MemoryType)) {
    errors.push(`type must be one of ${TYPES.join(", ")}`);
  }

  const capturedAt = asDate(value["capturedAt"], "capturedAt", errors, true);
  const updatedAt = asDate(value["updatedAt"], "updatedAt", errors, false) ?? capturedAt;
  const contentCreatedAt = asDate(value["contentCreatedAt"], "contentCreatedAt", errors, false);

  const hasLink = value["hasLink"];
  if (hasLink !== undefined && typeof hasLink !== "boolean") errors.push("hasLink must be a boolean");

  const text = {
    sourceApp: asText(value["sourceApp"], "sourceApp", errors),
    sourceAppLabel: asText(value["sourceAppLabel"], "sourceAppLabel", errors),
    title: asText(value["title"], "title", errors),
    rawText: asText(value["rawText"], "rawText", errors),
    extractedText: asText(value["extractedText"], "extractedText", errors),
  };

  if (errors.length > 0 || !capturedAt) return { ok: false, errors };

  return {
    ok: true,
    memory: {
      id,
      type: type as MemoryType,
      hasLink: hasLink === true,
      capturedAt,
      contentCreatedAt,
      updatedAt: updatedAt ?? capturedAt,
      ...text,
    },
  };
}

/** Epoch milliseconds, as the phone stores them. */
function asDate(value: unknown, field: string, errors: string[], required: boolean): Date | null {
  if (value === undefined || value === null) {
    if (required) errors.push(`${field} is required (epoch milliseconds)`);
    return null;
  }
  if (typeof value !== "number" || !Number.isFinite(value)) {
    errors.push(`${field} must be a number of epoch milliseconds`);
    return null;
  }
  return new Date(value);
}

function asText(value: unknown, field: string, errors: string[]): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string") {
    errors.push(`${field} must be a string or null`);
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export interface IngestResult {
  id: string;
  enriched: boolean;
  embedded: boolean;
  /** Why enrichment did not happen, when it did not. */
  reason?: string;
}

/**
 * Stores the memory, enriched when there is text to enrich.
 *
 * The memory is stored whether or not Gemini answers. Losing the text because
 * a model call failed would be the wrong trade: the phone has already told the
 * user it was saved (rule 2), and an unenriched document can be enriched
 * later, while a missing one is a memory that quietly did not sync.
 */
export async function ingest(
  database: Db,
  extract: Extractor,
  embed: Embedder,
  incoming: IncomingMemory,
): Promise<IngestResult> {
  const text = [incoming.rawText, incoming.extractedText].filter(Boolean).join("\n").trim();
  const enrichable = text.length > 0 || (incoming.title?.length ?? 0) > 0;

  const doc: Omit<MemoryDoc, "syncedAt"> = {
    _id: incoming.id,
    type: incoming.type,
    hasLink: incoming.hasLink,
    capturedAt: incoming.capturedAt,
    contentCreatedAt: incoming.contentCreatedAt,
    sourceApp: incoming.sourceApp,
    sourceAppLabel: incoming.sourceAppLabel,
    title: incoming.title,
    rawText: incoming.rawText,
    extractedText: incoming.extractedText,
    updatedAt: incoming.updatedAt,
  };

  if (!enrichable) {
    // An image whose OCR found nothing, say: 3.6 sends the picture itself.
    // Nothing to embed either -- a vector of nothing would only be noise.
    await putMemory(database, doc);
    return { id: incoming.id, enriched: false, embedded: false, reason: "nothing to read yet" };
  }

  let enriched: Omit<MemoryDoc, "syncedAt"> = doc;
  let reason: string | undefined;

  try {
    const extraction = await extract({
      type: incoming.type,
      title: incoming.title,
      text,
      sourceAppLabel: incoming.sourceAppLabel,
      capturedAt: incoming.capturedAt,
    });
    enriched = { ...doc, enrichment: { ...extraction, at: new Date() } };
  } catch (error) {
    reason = error instanceof Error ? error.message : String(error);
    enriched = { ...doc, enrichmentError: reason };
  }

  // Embedded after enrichment, so the summary and entities are part of what is
  // embedded. An unenriched memory is still worth embedding -- its own text is
  // what the phone already shows -- so a failure above does not skip this.
  let stored = enriched;
  try {
    stored = {
      ...enriched,
      embedding: await embed(embeddingText(enriched), "document"),
      embeddedWith: { model: EMBEDDING_MODEL, dimensions: EMBEDDING_DIMENSIONS, at: new Date() },
    };
  } catch (error) {
    const embeddingError = error instanceof Error ? error.message : String(error);
    stored = { ...enriched, embeddingError };
    reason ??= embeddingError;
  }

  await putMemory(database, stored);
  return {
    id: incoming.id,
    enriched: !!stored.enrichment,
    embedded: !!stored.embedding,
    ...(reason === undefined ? {} : { reason }),
  };
}

import { createHash } from "node:crypto";
import type { Db } from "mongodb";
import { EMBEDDING_DIMENSIONS, EMBEDDING_MODEL, type Embedder, embeddingText } from "./embeddings.ts";
import { type Extractor, isTransient } from "./gemini.ts";
import { type MemoryDoc, type MemoryType, memories, putMemories } from "./memories.ts";

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
  /** Why the model work did not finish, when it did not. */
  reason?: string;
  /**
   * The memory is stored, but a model call failed for a reason that may pass --
   * a rate limit, an overloaded model. The phone should send it again later,
   * and that re-send is what enriches it.
   */
  retryable?: boolean;
}

/** Enough to tell whether the model would be reading the same thing again. */
function fingerprint(text: string): string {
  return createHash("sha256").update(text).digest("hex").slice(0, 32);
}

/** Exactly what the extractor is given to read, from either side of a send. */
function enrichmentSource(parts: Pick<MemoryDoc, "title" | "rawText" | "extractedText">): string {
  const text = [parts.rawText, parts.extractedText].filter(Boolean).join("\n").trim();
  return [parts.title, text].filter(Boolean).join("\n");
}

/**
 * What a stored memory was enriched from.
 *
 * Documents written before fingerprints existed carry an enrichment and no
 * fingerprint; recomputing it from what they hold spares a re-send from paying
 * for an answer we already have.
 */
function enrichedFingerprint(existing: MemoryDoc | null): string | undefined {
  if (!existing?.enrichment) return undefined;
  return existing.enrichedFrom ?? fingerprint(enrichmentSource(existing));
}

/**
 * Stores the memory, enriched and embedded when there is text to work with.
 *
 * The memory is stored whether or not the model answers. Losing the text
 * because a model call failed would be the wrong trade: the phone has already
 * told the user it was saved (rule 2), and an unenriched document can be
 * enriched on a later send, while a missing one is a memory that quietly did
 * not sync.
 *
 * Model calls are skipped when the text they would read has not changed since
 * last time. On a free tier that is what makes retries, and the re-send after
 * OCR, cost nothing.
 */
export async function ingest(
  database: Db,
  extract: Extractor,
  embed: Embedder,
  incoming: IncomingMemory[],
): Promise<IngestResult[]> {
  if (incoming.length === 0) return [];

  const existing = new Map(
    (await memories(database)
      .find({ _id: { $in: incoming.map((memory) => memory.id) } })
      .toArray()).map((doc) => [doc._id, doc]),
  );

  // One pass to work out what each memory needs, so the model calls that
  // follow carry everything that is actually missing and nothing that is not.
  const pending = incoming.map((memory) => {
    const text = [memory.rawText, memory.extractedText].filter(Boolean).join("\n").trim();
    const held = existing.get(memory.id) ?? null;
    const enrichFrom = fingerprint(enrichmentSource(memory));
    const reusable = held?.enrichment && enrichedFingerprint(held) === enrichFrom ? held.enrichment : null;

    const doc: Omit<MemoryDoc, "syncedAt"> = {
      _id: memory.id,
      type: memory.type,
      hasLink: memory.hasLink,
      capturedAt: memory.capturedAt,
      contentCreatedAt: memory.contentCreatedAt,
      sourceApp: memory.sourceApp,
      sourceAppLabel: memory.sourceAppLabel,
      title: memory.title,
      rawText: memory.rawText,
      extractedText: memory.extractedText,
      updatedAt: memory.updatedAt,
      // Same words as last time: the model would only tell us what we have.
      ...(reusable ? { enrichment: reusable, enrichedFrom: enrichFrom } : {}),
    };

    return {
      memory,
      held,
      text,
      enrichFrom,
      // An image whose OCR found nothing, say: 3.6 sends the picture itself.
      enrichable: text.length > 0 || (memory.title?.length ?? 0) > 0,
      needsExtraction: !reusable,
      doc,
      reason: undefined as string | undefined,
      retryable: false,
    };
  });

  const toExtract = pending.filter((item) => item.enrichable && item.needsExtraction);
  if (toExtract.length > 0) {
    try {
      const extractions = await extract(
        toExtract.map((item, index) => ({
          index,
          type: item.memory.type,
          title: item.memory.title,
          text: item.text,
          sourceAppLabel: item.memory.sourceAppLabel,
          capturedAt: item.memory.capturedAt,
        })),
      );

      toExtract.forEach((item, index) => {
        const extraction = extractions.get(index);
        if (extraction) {
          item.doc = { ...item.doc, enrichment: { ...extraction, at: new Date() }, enrichedFrom: item.enrichFrom };
        } else {
          // Answered for the others but not this one. Storing it unenriched
          // and saying so beats asking again and again for the same silence.
          item.reason = "the model returned nothing for this memory";
          item.doc = { ...item.doc, enrichmentError: item.reason };
        }
      });
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const retryable = isTransient(error);
      for (const item of toExtract) {
        item.reason = reason;
        item.retryable = retryable;
        item.doc = { ...item.doc, enrichmentError: reason };
      }
    }
  }

  // Embedded after enrichment, so the summary and entities are part of what is
  // embedded. An unenriched memory is still worth embedding -- its own text is
  // what the phone already shows -- so a failure above does not skip this.
  const toEmbed = pending
    .filter((item) => item.enrichable)
    .map((item) => ({ item, text: embeddingText(item.doc), from: "" }))
    .filter((candidate) => {
      candidate.from = fingerprint(candidate.text);
      const held = candidate.item.held;
      const sameVector =
        held?.embedding &&
        // a vector from before fingerprints is recognised by re-deriving it
        (held.embeddedFrom ?? fingerprint(embeddingText(held))) === candidate.from &&
        held.embeddedWith?.model === EMBEDDING_MODEL &&
        held.embeddedWith.dimensions === EMBEDDING_DIMENSIONS;

      if (sameVector) {
        candidate.item.doc = {
          ...candidate.item.doc,
          embedding: held.embedding,
          embeddedWith: held.embeddedWith,
          embeddedFrom: candidate.from,
        };
        return false;
      }
      return true;
    });

  if (toEmbed.length > 0) {
    try {
      const vectors = await embed(toEmbed.map((candidate) => candidate.text), "document");
      toEmbed.forEach((candidate, index) => {
        candidate.item.doc = {
          ...candidate.item.doc,
          embedding: vectors[index],
          embeddedWith: { model: EMBEDDING_MODEL, dimensions: EMBEDDING_DIMENSIONS, at: new Date() },
          embeddedFrom: candidate.from,
        };
      });
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      const retryable = isTransient(error);
      for (const candidate of toEmbed) {
        candidate.item.doc = { ...candidate.item.doc, embeddingError: reason };
        candidate.item.reason ??= reason;
        candidate.item.retryable ||= retryable;
      }
    }
  }

  await putMemories(database, pending.map((item) => item.doc));

  return pending.map((item) => ({
    id: item.memory.id,
    enriched: !!item.doc.enrichment,
    embedded: !!item.doc.embedding,
    ...(item.enrichable ? {} : { reason: "nothing to read yet" }),
    ...(item.reason === undefined ? {} : { reason: item.reason }),
    ...(item.retryable ? { retryable: true } : {}),
  }));
}

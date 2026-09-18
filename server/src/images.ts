import { createHash } from "node:crypto";
import type { Db } from "mongodb";
import { EMBEDDING_DIMENSIONS, EMBEDDING_MODEL, type Embedder, embeddingText } from "./embeddings.ts";
import { type ImageExtractor, isTransient } from "./gemini.ts";
import type { IngestResult } from "./ingest.ts";
import { type MemoryDoc, memories, putMemories } from "./memories.ts";

const ACCEPTED_TYPES = ["image/jpeg", "image/png", "image/webp"];

/** Room for a downscaled screenshot, and no room for anything that is not one. */
const MAX_BYTES = 4 * 1024 * 1024;

/** One saved picture, as the phone sends it: base64, never a path or a file. */
export interface IncomingImage {
  id: string;
  mimeType: string;
  data: string;
}

export type ImageParseResult =
  | { ok: true; images: IncomingImage[] }
  | { ok: false; errors: string[] };

export function parseImages(body: unknown): ImageParseResult {
  if (!Array.isArray(body)) return { ok: false, errors: ["body must be an array of images"] };

  const errors: string[] = [];
  const images: IncomingImage[] = [];

  body.forEach((entry, index) => {
    if (typeof entry !== "object" || entry === null) {
      errors.push(`image ${index} must be an object`);
      return;
    }
    const value = entry as Record<string, unknown>;
    const id = typeof value["id"] === "string" ? value["id"].trim() : "";
    const mimeType = typeof value["mimeType"] === "string" ? value["mimeType"] : "";
    const data = typeof value["data"] === "string" ? value["data"] : "";

    if (!id) errors.push(`image ${index} needs the id of the memory it belongs to`);
    if (!ACCEPTED_TYPES.includes(mimeType)) {
      errors.push(`image ${index} must be one of ${ACCEPTED_TYPES.join(", ")}`);
    }
    if (!data) errors.push(`image ${index} has no data`);
    // base64 runs about 4 characters per 3 bytes
    if (data.length * 0.75 > MAX_BYTES) errors.push(`image ${index} is larger than ${MAX_BYTES} bytes`);

    if (errors.length === 0) images.push({ id, mimeType, data });
  });

  return errors.length > 0 ? { ok: false, errors } : { ok: true, images };
}

/**
 * Reads saved pictures and keeps what the model saw (step 3.6).
 *
 * The bytes live in memory for the length of the request and are never written
 * anywhere: the cloud holds the text, the entities and the vector, and the
 * original stays on the phone (architecture rule 1).
 *
 * Only memories already stored here are read. The phone sends metadata first,
 * so an image arriving for an unknown id means the two got out of order, and
 * inventing a document from a picture alone would lose everything the phone
 * knows about it.
 */
export async function ingestImages(
  database: Db,
  extractImages: ImageExtractor,
  embed: Embedder,
  images: IncomingImage[],
): Promise<IngestResult[]> {
  if (images.length === 0) return [];

  const held = new Map(
    (await memories(database)
      .find({ _id: { $in: images.map((image) => image.id) } })
      .toArray()).map((doc) => [doc._id, doc]),
  );

  const results: IngestResult[] = [];
  const pending: { image: IncomingImage; doc: MemoryDoc; readFrom: string }[] = [];

  for (const image of images) {
    const doc = held.get(image.id);
    if (!doc) {
      results.push({ id: image.id, enriched: false, embedded: false, reason: "no such memory" });
      continue;
    }

    const readFrom = fingerprintBytes(image.data);
    if (doc.imageReadFrom === readFrom && doc.enrichment) {
      // The same picture, read before. Nothing the model could add.
      results.push({ id: image.id, enriched: true, embedded: !!doc.embedding });
      continue;
    }
    pending.push({ image, doc, readFrom });
  }

  if (pending.length === 0) return results;

  const read = new Map<number, { doc: Omit<MemoryDoc, "syncedAt">; enriched: boolean }>();
  let reason: string | undefined;
  let retryable = false;

  try {
    const extractions = await extractImages(
      pending.map(({ image, doc }, index) => ({
        index,
        mimeType: image.mimeType,
        data: image.data,
        capturedAt: doc.capturedAt,
        sourceAppLabel: doc.sourceAppLabel,
        text: [doc.rawText, doc.extractedText].filter(Boolean).join("\n"),
      })),
    );

    pending.forEach(({ doc, readFrom }, index) => {
      const extraction = extractions.get(index);
      read.set(index, {
        doc: extraction
          ? { ...doc, enrichment: { ...extraction, at: new Date() }, imageReadFrom: readFrom, imageReadAt: new Date() }
          : { ...doc, enrichmentError: "the model returned nothing for this picture" },
        enriched: !!extraction,
      });
    });
  } catch (error) {
    reason = error instanceof Error ? error.message : String(error);
    retryable = isTransient(error);
    pending.forEach(({ doc }, index) => {
      read.set(index, { doc: { ...doc, enrichmentError: reason as string }, enriched: false });
    });
  }

  // What the model saw changes what should be embedded, so anything it read is
  // embedded again -- in one call, as everywhere else.
  const toEmbed = [...read.entries()].filter(([, entry]) => entry.enriched);
  if (toEmbed.length > 0) {
    try {
      const vectors = await embed(toEmbed.map(([, entry]) => embeddingText(entry.doc)), "document");
      toEmbed.forEach(([index, entry], position) => {
        read.set(index, {
          ...entry,
          doc: {
            ...entry.doc,
            embedding: vectors[position],
            embeddedWith: { model: EMBEDDING_MODEL, dimensions: EMBEDDING_DIMENSIONS, at: new Date() },
            embeddedFrom: fingerprint(embeddingText(entry.doc)),
          },
        });
      });
    } catch (error) {
      const embeddingError = error instanceof Error ? error.message : String(error);
      reason ??= embeddingError;
      retryable ||= isTransient(error);
      toEmbed.forEach(([index, entry]) => {
        read.set(index, { ...entry, doc: { ...entry.doc, embeddingError } });
      });
    }
  }

  await putMemories(database, [...read.values()].map((entry) => entry.doc));

  pending.forEach(({ image }, index) => {
    const entry = read.get(index);
    results.push({
      id: image.id,
      enriched: entry?.enriched ?? false,
      embedded: !!entry?.doc.embedding,
      ...(reason === undefined ? {} : { reason }),
      ...(retryable ? { retryable: true } : {}),
    });
  });

  return results;
}

function fingerprint(text: string): string {
  return createHash("sha256").update(text).digest("hex").slice(0, 32);
}

function fingerprintBytes(base64: string): string {
  return createHash("sha256").update(base64, "base64").digest("hex").slice(0, 32);
}

import assert from "node:assert/strict";
import { deflateSync } from "node:zlib";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, test } from "node:test";
import type { Db } from "mongodb";
import { createApp } from "./app.ts";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import type { Embedder } from "./embeddings.ts";
import { type Extraction, geminiImageExtractor, type ImageExtractor, isTransient } from "./gemini.ts";
import { parseImages } from "./images.ts";
import { getMemory, memories, putMemory } from "./memories.ts";

/** A solid-colour PNG, encoded by hand so the tests need no image library. */
export function solidPng(red: number, green: number, blue: number, size = 64): Buffer {
  const raw = Buffer.alloc(size * (size * 3 + 1));
  for (let y = 0; y < size; y += 1) {
    const row = y * (size * 3 + 1);
    raw[row] = 0; // filter: none
    for (let x = 0; x < size; x += 1) {
      raw.set([red, green, blue], row + 1 + x * 3);
    }
  }

  const chunk = (type: string, body: Buffer) => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(body.length);
    const typed = Buffer.concat([Buffer.from(type, "ascii"), body]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(typed));
    return Buffer.concat([length, typed, crc]);
  };

  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 2; // truecolour
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

const png = solidPng(20, 40, 200).toString("base64");

describe("parseImages", () => {
  test("takes a picture belonging to a memory", () => {
    const parsed = parseImages([{ id: "m1", mimeType: "image/png", data: png }]);

    assert.ok(parsed.ok);
    assert.equal(parsed.images.length, 1);
  });

  test("refuses anything that is not a picture we can read", () => {
    for (const body of [
      {},
      [{ id: "m1", mimeType: "application/pdf", data: png }],
      [{ id: "", mimeType: "image/png", data: png }],
      [{ id: "m1", mimeType: "image/png", data: "" }],
    ]) {
      assert.ok(!parseImages(body).ok, `${JSON.stringify(body).slice(0, 40)} should not parse`);
    }
  });

  test("refuses one too large to be a downscaled screenshot", () => {
    const parsed = parseImages([{ id: "m1", mimeType: "image/png", data: "A".repeat(6 * 1024 * 1024) }]);

    assert.ok(!parsed.ok);
    assert.match(parsed.errors.join(" "), /larger than/);
  });
});

/**
 * One real call, so the pinned model, the image parts and the schema are known
 * to work together. A plain coloured square is enough: what is being proved is
 * that the picture reaches the model and comes back described.
 */
describe(
  "reading a picture with the live model",
  { skip: process.env.GEMINI_API_KEY ? false : "GEMINI_API_KEY is not set (see .env.example)" },
  () => {
    test("describes what is in it", async (t) => {
      const extractImages = geminiImageExtractor(process.env.GEMINI_API_KEY as string);

      let read: Map<number, Extraction>;
      try {
        read = await extractImages([
          {
            index: 0,
            mimeType: "image/png",
            data: solidPng(30, 60, 220, 256).toString("base64"),
            capturedAt: new Date("2026-04-18T10:12:33.000Z"),
            sourceAppLabel: "Screenshot",
            text: null,
          },
        ]);
      } catch (error) {
        if (isTransient(error)) return t.skip(`the model is busy: ${(error as Error).message}`);
        throw error;
      }

      const seen = read.get(0);
      assert.ok(seen, "expected an answer for the picture");
      assert.ok(seen.summary.length > 0, "a picture with nothing in it still gets a summary");
      assert.match(
        `${seen.summary} ${seen.readText ?? ""}`,
        /blue|colour|color|square|solid/i,
        `expected the colour to be described, got: ${seen.summary} / ${seen.readText}`,
      );
    });
  },
);

describe(
  "POST /memories/images",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let server: Server;
    let base: string;
    let seen: number;
    let extraction: Extraction | Error;

    const extractImages: ImageExtractor = async (inputs) => {
      seen += 1;
      if (extraction instanceof Error) throw extraction;
      return new Map(inputs.map((input) => [input.index, extraction as Extraction]));
    };

    const embed: Embedder = async (texts) => texts.map(() => [0.6, 0.8]);
    const unused = async () => {
      throw new Error("the text extractor is not used by these tests");
    };

    const post = (body: unknown) =>
      fetch(`${base}/memories/images`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });

    const savedImage = async (id: string) =>
      putMemory(database, {
        _id: id,
        type: "IMAGE",
        hasLink: false,
        capturedAt: new Date("2026-04-18T10:12:33.000Z"),
        updatedAt: new Date("2026-04-18T10:12:33.000Z"),
        sourceAppLabel: "WhatsApp",
        extractedText: null,
        rawText: null,
      });

    before(async () => {
      await connectMongo();
      database = db(`${databaseName()}_test_images`);
      server = createApp({ log: false, extract: unused, extractImages, embed, database }).listen(0, "127.0.0.1");
      await once(server, "listening");
      base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    });

    after(async () => {
      server.closeAllConnections();
      server.close();
      try {
        await memories(database).drop();
      } catch {
        // already gone
      } finally {
        await closeMongo();
      }
    });

    beforeEach(async () => {
      await memories(database).deleteMany({});
      seen = 0;
      extraction = {
        summary: "Whiteboard from the architecture session",
        kind: "photo",
        entities: ["Qualcomm"],
        dates: [],
        readText: "A whiteboard covered in boxes and arrows",
      };
    });

    test("keeps what the model saw, and the picture itself nowhere", async () => {
      await savedImage("shot");

      const response = await post([{ id: "shot", mimeType: "image/png", data: png }]);

      assert.equal(response.status, 200);
      const stored = await getMemory(database, "shot");
      assert.equal(stored?.enrichment?.readText, "A whiteboard covered in boxes and arrows");
      assert.deepEqual(stored?.enrichment?.entities, ["Qualcomm"]);
      assert.ok(stored?.imageReadFrom, "the picture should be fingerprinted, so it is never read twice");
      assert.ok(stored?.embedding, "what the model saw changes what should be embedded");
      // the bytes are held for the length of the request and stored nowhere
      assert.ok(!JSON.stringify(stored).includes(png.slice(0, 64)), "the picture must not be stored");
    });

    test("several pictures cost one model call", async () => {
      for (const id of ["a", "b", "c"]) await savedImage(id);

      await post(["a", "b", "c"].map((id) => ({ id, mimeType: "image/png", data: png })));

      assert.equal(seen, 1);
    });

    test("the same picture is never read twice", async () => {
      await savedImage("shot");
      await post([{ id: "shot", mimeType: "image/png", data: png }]);

      await post([{ id: "shot", mimeType: "image/png", data: png }]);

      assert.equal(seen, 1, "a re-send of the same bytes should cost nothing");
    });

    test("a changed picture is read again", async () => {
      await savedImage("shot");
      await post([{ id: "shot", mimeType: "image/png", data: png }]);

      await post([{ id: "shot", mimeType: "image/png", data: solidPng(200, 20, 20).toString("base64") }]);

      assert.equal(seen, 2);
    });

    test("a picture for a memory we do not hold is refused, not invented", async () => {
      const response = await post([{ id: "never-sent", mimeType: "image/png", data: png }]);

      const body = (await response.json()) as { results: { id: string; reason?: string }[] };
      assert.match(body.results[0]?.reason ?? "", /no such memory/);
      assert.equal(await memories(database).countDocuments(), 0);
    });

    test("a rate-limited read asks the phone to send it again", async () => {
      await savedImage("shot");
      extraction = new Error('{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}');

      const response = await post([{ id: "shot", mimeType: "image/png", data: png }]);

      assert.equal(response.status, 503);
      const stored = await getMemory(database, "shot");
      assert.ok(stored, "the memory itself is untouched");
      assert.equal(stored?.imageReadFrom, undefined, "an unread picture must not look read");
    });

    test("too many pictures at once is refused", async () => {
      const response = await post(
        Array.from({ length: 9 }, (_, n) => ({ id: `m${n}`, mimeType: "image/png", data: png })),
      );

      assert.equal(response.status, 400);
    });
  },
);

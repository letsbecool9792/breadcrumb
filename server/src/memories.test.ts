import assert from "node:assert/strict";
import { after, before, beforeEach, describe, test } from "node:test";
import type { Db } from "mongodb";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import { ensureIndexes, getMemory, memories, putMemory, recentMemories } from "./memories.ts";

/**
 * Runs against the real Atlas cluster, because what is worth checking here --
 * that an upsert is idempotent, that dates survive the round trip -- is the
 * database's behaviour, not ours.
 *
 * It writes to "<MONGODB_DB>_test", a database of its own, and drops it
 * afterwards, so saved memories are never touched.
 */
describe(
  "memories collection",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;

    const sample = {
      _id: "11111111-2222-3333-4444-555555555555",
      type: "IMAGE" as const,
      hasLink: true,
      capturedAt: new Date("2026-04-18T10:12:33.000Z"),
      contentCreatedAt: new Date("2026-04-17T08:00:00.000Z"),
      sourceApp: "com.whatsapp",
      sourceAppLabel: "WhatsApp",
      title: "Internship posting",
      rawText: "have a look https://example.com/jobs",
      extractedText: "Qualcomm | Software Engineering Intern",
      updatedAt: new Date("2026-04-18T10:12:34.000Z"),
    };

    before(async () => {
      await connectMongo();
      database = db(`${databaseName()}_test`);
      await ensureIndexes(database);
    });

    after(async () => {
      try {
        // The collection, not the database: readWriteAnyDatabase, which is all
        // this user needs, cannot dropDatabase. A database with no collections
        // left in it is gone anyway.
        await memories(database).drop();
      } catch {
        // already gone, or never created
      } finally {
        // Always: a client left open holds the process alive, and the run hangs
        // rather than reporting the failure that got us here.
        await closeMongo();
      }
    });

    beforeEach(async () => {
      await memories(database).deleteMany({});
    });

    test("writes a memory and reads it back whole", async () => {
      await putMemory(database, sample);

      const stored = await getMemory(database, sample._id);

      assert.ok(stored, "nothing came back");
      const { syncedAt, ...rest } = stored;
      assert.deepEqual(rest, sample);
      assert.ok(syncedAt instanceof Date, "the server should stamp syncedAt");
    });

    test("re-sending the same memory replaces it rather than duplicating", async () => {
      await putMemory(database, sample);
      await putMemory(database, { ...sample, title: "Internship posting (edited)" });

      assert.equal(await memories(database).countDocuments(), 1);
      assert.equal((await getMemory(database, sample._id))?.title, "Internship posting (edited)");
    });

    test("an id we do not hold reads back as null", async () => {
      assert.equal(await getMemory(database, "no-such-id"), null);
    });

    test("recent memories come back newest first", async () => {
      for (const [id, day] of [["old", "01"], ["new", "03"], ["mid", "02"]] as const) {
        await putMemory(database, { ...sample, _id: id, capturedAt: new Date(`2026-04-${day}T00:00:00.000Z`) });
      }

      const ids = (await recentMemories(database)).map((memory) => memory._id);

      assert.deepEqual(ids, ["new", "mid", "old"]);
    });
  },
);

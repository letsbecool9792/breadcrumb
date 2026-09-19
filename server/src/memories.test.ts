import assert from "node:assert/strict";
import { after, before, beforeEach, describe, test } from "node:test";
import type { Db } from "mongodb";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import {
  backfillDatedAt,
  backfillLinkSites,
  datedAt,
  declaredPaths,
  ensureIndexes,
  getMemory,
  memories,
  putMemory,
  recentMemories,
  textIndexDefinition,
  vectorIndexDefinition,
} from "./memories.ts";

/**
 * Runs against the real Atlas cluster, because what is worth checking here --
 * that an upsert is idempotent, that dates survive the round trip -- is the
 * database's behaviour, not ours.
 *
 * It writes to a database of its own -- `node --test` runs each file in its
 * own process, in parallel, so two suites sharing one database would delete
 * each other's documents -- and drops its collection afterwards. Saved
 * memories are never touched.
 */
describe("vectorIndexDefinition", () => {
  test("declares the vector field at the size we store", () => {
    const field = vectorIndexDefinition(768).fields[0];

    assert.deepEqual(field, { type: "vector", path: "embedding", numDimensions: 768, similarity: "cosine" });
  });

  test("declares every field a search filters on", () => {
    // a field not declared here cannot be a pre-filter, and adding one later
    // means rebuilding the index
    const filters = vectorIndexDefinition(768)
      .fields.filter((field) => field.type === "filter")
      .map((field) => field.path);

    assert.deepEqual(filters, ["type", "hasLink", "capturedAt", "sourceAppLabel", "datedAt", "linkSites", "_id"]);
  });

  test("the text index can filter on the same fields, so both halves narrow alike", () => {
    const vectorFilters = vectorIndexDefinition(768)
      .fields.filter((field) => field.type === "filter")
      .map((field) => field.path);

    const textPaths = declaredPaths(textIndexDefinition());
    for (const path of vectorFilters) assert.ok(textPaths.includes(path), `${path} is missing from the text index`);
  });
});

describe("declaredPaths", () => {
  test("reads a vector index's fields", () => {
    assert.deepEqual(declaredPaths({ fields: [{ type: "vector", path: "embedding" }, { type: "filter", path: "type" }] }), [
      "embedding",
      "type",
    ]);
  });

  test("reads a text index's mappings, nested documents included", () => {
    const paths = declaredPaths({
      mappings: { fields: { title: { type: "string" }, enrichment: { type: "document", fields: { kind: { type: "string" } } } } },
    });

    assert.deepEqual(paths, ["title", "enrichment.kind"]);
  });

  test("an index definition Atlas reports with its own defaults still reads the same", () => {
    // what listSearchIndexes hands back: the same fields, plus settings we never set
    const reported = { ...textIndexDefinition(), storedSource: false, numPartitions: 1 };

    assert.deepEqual(declaredPaths(reported), declaredPaths(textIndexDefinition()));
  });

  test("nothing declared reads as nothing, not as an error", () => {
    assert.deepEqual(declaredPaths(undefined), []);
    assert.deepEqual(declaredPaths({}), []);
  });
});

describe("datedAt", () => {
  test("a picture is dated by when it was taken, anything else by when it was saved", () => {
    const capturedAt = new Date("2026-09-10T10:00:00.000Z");
    const taken = new Date("2026-04-02T09:00:00.000Z");

    assert.equal(datedAt({ capturedAt, contentCreatedAt: taken }), taken);
    assert.equal(datedAt({ capturedAt, contentCreatedAt: null }), capturedAt);
    assert.equal(datedAt({ capturedAt }), capturedAt);
  });
});

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
      datedAt: new Date("2026-04-17T08:00:00.000Z"),
      sourceApp: "com.whatsapp",
      sourceAppLabel: "WhatsApp",
      title: "Internship posting",
      rawText: "have a look https://example.com/jobs",
      extractedText: "Qualcomm | Software Engineering Intern",
      updatedAt: new Date("2026-04-18T10:12:34.000Z"),
    };

    before(async () => {
      await connectMongo();
      database = db(`${databaseName()}_test_memories`);
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

    test("memories stored before datedAt existed are given one, once", async () => {
      const { datedAt: _, ...withoutDate } = sample;
      await memories(database).insertMany([
        { ...withoutDate, _id: "photo" },
        { ...withoutDate, _id: "note", contentCreatedAt: null },
      ] as never[]);

      assert.equal(await backfillDatedAt(database), 2);
      assert.deepEqual((await getMemory(database, "photo"))?.datedAt, sample.contentCreatedAt);
      assert.deepEqual((await getMemory(database, "note"))?.datedAt, sample.capturedAt);
      assert.equal(await backfillDatedAt(database), 0, "a second run has nothing left to do");
    });

    test("memories stored before linkSites existed are given theirs, once", async () => {
      await memories(database).insertMany([
        { ...sample, _id: "reel", rawText: "lol https://www.instagram.com/reel/abc" },
        { ...sample, _id: "plain", rawText: "no link in this one" },
      ] as never[]);

      assert.equal(await backfillLinkSites(database), 2);
      assert.deepEqual((await getMemory(database, "reel"))?.linkSites, ["instagram.com"]);
      // an empty list, not a missing field: the next run must not look at it again
      assert.deepEqual((await getMemory(database, "plain"))?.linkSites, []);
      assert.equal(await backfillLinkSites(database), 0, "a second run has nothing left to do");
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

import assert from "node:assert/strict";
import { once } from "node:events";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, beforeEach, describe, test } from "node:test";
import type { Db } from "mongodb";
import { createApp } from "./app.ts";
import { closeMongo, connectMongo, databaseName, db } from "./db.ts";
import { type MemoryDoc, memories, putMemories } from "./memories.ts";
import { MAX_IDS, parseIds } from "./sync.ts";

describe("parseIds", () => {
  test("takes a list of ids, each asked about once", () => {
    assert.deepEqual(parseIds({ ids: ["a", " b ", "a"] }), { ok: true, ids: ["a", "b"] });
  });

  test("refuses anything that is not a short list of ids", () => {
    for (const body of [
      null,
      {},
      { ids: [] },
      { ids: "a" },
      { ids: ["a", ""] },
      { ids: ["a", 7] },
      { ids: Array.from({ length: MAX_IDS + 1 }, (_, n) => `m${n}`) },
    ]) {
      assert.ok(!parseIds(body).ok, `${JSON.stringify(body).slice(0, 60)} should be refused`);
    }
  });
});

/** How the phone keeps in step with what the server holds (step 4.6). */
describe(
  "keeping the phone in step",
  { skip: process.env.MONGODB_URI ? false : "MONGODB_URI is not set (copy .env.example to .env)" },
  () => {
    let database: Db;
    let server: Server;
    let base: string;

    const unused = async () => {
      throw new Error("no model is called here");
    };

    const post = async (path: string, body: unknown) => {
      const response = await fetch(`${base}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      return { status: response.status, body: (await response.json()) as Record<string, unknown> };
    };

    const doc = (id: string, extra: Partial<MemoryDoc> = {}): Omit<MemoryDoc, "syncedAt"> => ({
      _id: id,
      type: "IMAGE",
      hasLink: false,
      capturedAt: new Date("2026-09-10T10:00:00.000Z"),
      datedAt: new Date("2026-09-10T10:00:00.000Z"),
      updatedAt: new Date("2026-09-10T10:00:00.000Z"),
      ...extra,
    });

    before(async () => {
      await connectMongo();
      database = db(`${databaseName()}_test_sync`);
      server = createApp({
        log: false,
        extract: unused,
        extractImages: unused,
        embed: unused,
        parseQuery: unused,
        database,
      }).listen(0, "127.0.0.1");
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
    });

    test("the phone gets back what the model made of each memory", async () => {
      await putMemories(database, [
        doc("read", {
          enrichment: {
            summary: "Comments joking about a parallel dimension",
            kind: "screenshot",
            entities: ["TVA"],
            dates: [],
            readText: "A Reddit thread",
            at: new Date(),
          },
          embedding: [0.6, 0.8],
        }),
        doc("unread"),
      ]);

      const { status, body } = await post("/memories/enrichment", { ids: ["read", "unread", "never-sent"] });

      assert.equal(status, 200);
      const results = body["results"] as Record<string, unknown>[];
      assert.deepEqual(
        results.find((r) => r["id"] === "read"),
        { id: "read", summary: "Comments joking about a parallel dimension", kind: "screenshot", readText: "A Reddit thread" },
      );
      // held but not enriched: an answer of nulls, not a silence
      assert.deepEqual(results.find((r) => r["id"] === "unread"), { id: "unread", summary: null, kind: null, readText: null });
      // not held at all: absent
      assert.equal(results.find((r) => r["id"] === "never-sent"), undefined);
      // only what the phone lacks travels back
      assert.ok(!JSON.stringify(body).includes("0.6"), "the vector must not be sent back");
    });

    test("a bad list is refused", async () => {
      const { status } = await post("/memories/enrichment", { ids: [] });

      assert.equal(status, 400);
    });
  },
);

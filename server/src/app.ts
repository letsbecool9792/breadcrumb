import express, { type NextFunction, type Request, type Response } from "express";
import type { Db } from "mongodb";
import { db } from "./db.ts";
import type { Embedder } from "./embeddings.ts";
import type { Extractor } from "./gemini.ts";
import { type IncomingMemory, ingest, parseMemory } from "./ingest.ts";

/** One request's worth of memories. The phone sends ten; this is the ceiling. */
const MAX_BATCH = 50;

export interface AppOptions {
  /** One line per request. Off in tests, where it only buries the results. */
  log?: boolean;
  /** The Gemini pass. Injected so tests can run the endpoint without a model. */
  extract: Extractor;
  /** Turns a memory into a vector. Injected for the same reason. */
  embed: Embedder;
  /** Defaults to the process-wide connection; tests pass their own database. */
  database?: Db;
}

/**
 * The HTTP surface, built without listening so tests can serve it on any
 * port. Stays thin: health, ingest, and search at 4.1.
 */
export function createApp({ log = true, extract, embed, database }: AppOptions) {
  const app = express();
  app.disable("x-powered-by");

  if (log) app.use(logRequest);

  // Unauthenticated on purpose: it reveals nothing, and it is what the app
  // pings to tell "server down" from "server up". `service` lets the app tell
  // this server from anything else that happens to be holding the port.
  app.get("/health", (_req, res) => {
    res.json({ service: "breadcrumb", status: "ok" });
  });

  // A whole memory per request, and the phone's own id as the key, so a retried
  // upload replaces rather than duplicates. Still unauthenticated: the device
  // token is a V1 item of its own, and this only ever listens on localhost.
  app.post("/memories", express.json({ limit: "4mb" }), async (req, res) => {
    // An array is the shape the phone sends, since a batch costs one model
    // call rather than one each. A single memory is accepted too, which is
    // what curl and a one-off test want.
    const batch = Array.isArray(req.body) ? req.body : [req.body];
    if (batch.length === 0 || batch.length > MAX_BATCH) {
      res.status(400).json({ error: `send between 1 and ${MAX_BATCH} memories` });
      return;
    }

    const parsed = batch.map(parseMemory);
    const invalid = parsed.flatMap((result, index) => (result.ok ? [] : [{ index, errors: result.errors }]));
    if (invalid.length > 0) {
      res.status(400).json({ error: "invalid memory", invalid });
      return;
    }

    const results = await ingest(
      database ?? db(),
      extract,
      embed,
      parsed.map((result) => (result as { ok: true; memory: IncomingMemory }).memory),
    );

    // 503 when a model call failed for a reason that may pass: the memories are
    // stored, and the phone sending them again later is what enriches them. A
    // 200 would mark them synced on the phone and leave them unenriched for good.
    const status = results.some((result) => result.retryable) ? 503 : 200;
    res.status(status).json(Array.isArray(req.body) ? { results } : results[0]);
  });

  // JSON, never Express's HTML pages: the only client parses JSON.
  app.use((_req: Request, res: Response) => {
    res.status(404).json({ error: "not found" });
  });
  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    console.error(err);
    res.status(500).json({ error: "internal" });
  });

  return app;
}

function logRequest(req: Request, res: Response, next: NextFunction) {
  const started = performance.now();
  res.on("finish", () => {
    const ms = Math.round(performance.now() - started);
    console.log(`${req.method} ${req.originalUrl} ${res.statusCode} ${ms}ms`);
  });
  next();
}

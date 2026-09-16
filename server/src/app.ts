import express, { type NextFunction, type Request, type Response } from "express";
import type { Db } from "mongodb";
import { db } from "./db.ts";
import type { Extractor } from "./gemini.ts";
import { ingest, parseMemory } from "./ingest.ts";

export interface AppOptions {
  /** One line per request. Off in tests, where it only buries the results. */
  log?: boolean;
  /** The Gemini pass. Injected so tests can run the endpoint without a model. */
  extract: Extractor;
  /** Defaults to the process-wide connection; tests pass their own database. */
  database?: Db;
}

/**
 * The HTTP surface, built without listening so tests can serve it on any
 * port. Stays thin: health, ingest, and search at 4.1.
 */
export function createApp({ log = true, extract, database }: AppOptions) {
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
  app.post("/memories", express.json({ limit: "1mb" }), async (req, res) => {
    const parsed = parseMemory(req.body);
    if (!parsed.ok) {
      res.status(400).json({ error: "invalid memory", details: parsed.errors });
      return;
    }

    const result = await ingest(database ?? db(), extract, parsed.memory);
    res.status(200).json(result);
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

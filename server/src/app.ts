import express, { type NextFunction, type Request, type Response } from "express";

export interface AppOptions {
  /** One line per request. Off in tests, where it only buries the results. */
  log?: boolean;
}

/**
 * The HTTP surface, built without listening so tests can serve it on any
 * port. Stays thin: health now, then one ingest and one search endpoint.
 */
export function createApp({ log = true }: AppOptions = {}) {
  const app = express();
  app.disable("x-powered-by");

  if (log) app.use(logRequest);

  // Unauthenticated on purpose: it reveals nothing, and it is what the app
  // pings to tell "server down" from "server up". `service` lets the app tell
  // this server from anything else that happens to be holding the port.
  app.get("/health", (_req, res) => {
    res.json({ service: "breadcrumb", status: "ok" });
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

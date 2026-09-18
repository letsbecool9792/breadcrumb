import { createApp } from "./app.ts";
import { connectMongo, databaseName, db, describeMongoError } from "./db.ts";
import { EMBEDDING_DIMENSIONS, EMBEDDING_MODEL, geminiEmbedder } from "./embeddings.ts";
import { geminiExtractor, geminiImageExtractor, INGEST_MODEL } from "./gemini.ts";
import { ensureIndexes, ensureVectorIndex, VECTOR_INDEX, vectorIndexDefinition } from "./memories.ts";

const geminiKey = process.env.GEMINI_API_KEY;
if (!geminiKey) {
  console.error("GEMINI_API_KEY is not set -- see server/.env.example");
  process.exit(1);
}

// 127.0.0.1, not 0.0.0.0. In development the phone arrives through adb reverse,
// which connects from this machine, so nothing on the local network needs to
// reach the server -- and before long it holds personal data. A host that
// must listen publicly sets HOST.
const host = process.env.HOST ?? "127.0.0.1";
const port = Number(process.env.PORT ?? 3000);

// Before listening: a bad URI or a blocked IP should say so at startup, not on
// the first save. The client reconnects by itself after this.
try {
  await connectMongo();
  await ensureIndexes(db());
  console.log(`mongodb connected, database "${databaseName()}"`);

  const index = await ensureVectorIndex(db(), EMBEDDING_DIMENSIONS);
  if (index === "refused") {
    console.warn(
      `cannot create the "${VECTOR_INDEX}" search index with this database user.\n` +
        "Create it in Atlas (cluster -> Atlas Search -> Create Search Index -> JSON editor,\n" +
        `on ${databaseName()}.memories, named ${VECTOR_INDEX}) with:\n` +
        JSON.stringify(vectorIndexDefinition(EMBEDDING_DIMENSIONS), null, 2),
    );
  } else {
    // a new index takes about half a minute on M0, and until then every search
    // comes back empty rather than failing
    console.log(`vector index "${VECTOR_INDEX}" ${index}${index === "created" ? ", searchable in about 30s" : ""}`);
  }
} catch (error) {
  console.error(`could not reach MongoDB: ${describeMongoError(error)}`);
  process.exit(1);
}

console.log(`gemini models: ${INGEST_MODEL} for ingest, ${EMBEDDING_MODEL} at ${EMBEDDING_DIMENSIONS} dims`);

createApp({
  extract: geminiExtractor(geminiKey),
  extractImages: geminiImageExtractor(geminiKey),
  embed: geminiEmbedder(geminiKey),
}).listen(port, host, (error) => {
  if (error) {
    console.error(
      (error as NodeJS.ErrnoException).code === "EADDRINUSE"
        ? `port ${port} is already in use -- another dev server? Stop it, or set PORT (and adb reverse to match).`
        : error,
    );
    process.exit(1);
  }
  console.log(`breadcrumb server on http://${host}:${port}`);
});

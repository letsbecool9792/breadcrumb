import { createApp } from "./app.ts";
import { connectMongo, databaseName, db, describeMongoError } from "./db.ts";
import { EMBEDDING_DIMENSIONS, EMBEDDING_MODEL, geminiEmbedder } from "./embeddings.ts";
import { geminiExtractor, geminiImageExtractor, INGEST_MODEL } from "./gemini.ts";
import { geminiQueryParser, QUERY_MODEL } from "./query.ts";
import {
  backfillDatedAt,
  ensureIndexes,
  ensureTextIndex,
  ensureVectorIndex,
  type SearchIndexState,
  TEXT_INDEX,
  textIndexDefinition,
  VECTOR_INDEX,
  vectorIndexDefinition,
} from "./memories.ts";

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
  const dated = await backfillDatedAt(db());
  if (dated > 0) console.log(`dated ${dated} memories stored before datedAt existed`);

  // both halves of hybrid search (rule 5)
  const vector = await ensureVectorIndex(db(), EMBEDDING_DIMENSIONS);
  reportIndex(VECTOR_INDEX, vectorIndexDefinition(EMBEDDING_DIMENSIONS), vector);
  reportIndex(TEXT_INDEX, textIndexDefinition(), await ensureTextIndex(db()));
} catch (error) {
  console.error(`could not reach MongoDB: ${describeMongoError(error)}`);
  process.exit(1);
}

console.log(
  `gemini models: ${INGEST_MODEL} for ingest, ${QUERY_MODEL} for search, ` +
    `${EMBEDDING_MODEL} at ${EMBEDDING_DIMENSIONS} dims`,
);

createApp({
  extract: geminiExtractor(geminiKey),
  extractImages: geminiImageExtractor(geminiKey),
  embed: geminiEmbedder(geminiKey),
  parseQuery: geminiQueryParser(geminiKey),
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

/**
 * A missing search index does not fail a search -- Atlas answers that half
 * with an empty list -- so startup is where one has to be said out loud.
 */
function reportIndex(name: string, definition: object, state: SearchIndexState) {
  if (state === "exists") {
    console.log(`search index "${name}" exists`);
  } else if (state === "created") {
    console.log(`search index "${name}" created, searchable in about 30s`);
  } else if (state === "updated") {
    console.log(`search index "${name}" lacked a field and is rebuilding; new fields searchable in about 30s`);
  } else {
    console.warn(
      (state === "full"
        ? `cannot create the "${name}" search index: the cluster already holds as many as its tier allows\n` +
          "(three on M0). Delete one you do not need in Atlas, then restart.\n"
        : `cannot create the "${name}" search index with this database user.\n`) +
        "To create it by hand: cluster -> Atlas Search -> Create Search Index -> JSON editor,\n" +
        `on ${databaseName()}.memories, named ${name}, with:\n` +
        JSON.stringify(definition, null, 2),
    );
  }
}

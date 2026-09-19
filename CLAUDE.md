# Breadcrumb — Working Context

Personal memory retrieval. Save anything in one tap; find it later by describing it in natural language.

This file is the source of truth for **what we are building, what we decided, and what we are deliberately not building yet.** Read the V1 boundary before proposing work.

https://claude.ai/code/artifact/a280905e-4c93-4666-822f-b423bc2484e1

---

## Product definition

The core problem is **information fragmentation**. People save useful things all day — screenshots, links, PDFs, photos, voice notes, recommendations — and they scatter across apps. Later the user remembers *what* they saved and the context around it, but not *where*.

The whole product is two flows:

> **Save → Forget about it → Search → Find**

### Reference memory, not action memory

This is the central distinction and the thing that keeps the product from drifting.

- **Action memory** = "I need to *do* something later." Pay a bill, submit an assignment, attend a meeting. Calendars, reminders and task managers already solve this well. **Breadcrumb does not compete here.**
- **Reference memory** = "I need to *remember* something later." A restaurant recommendation, a useful article, an internship screenshot, a PDF from a professor, a person met at an event. Nothing needs to happen with these. The user just wants to find them again.

Breadcrumb is **only** the second one.

### Non-goals (reject features that pull toward these)

- Not a notes app. Not Notion, not Obsidian.
- Not a task manager or reminder app.
- Not a "second brain" / knowledge graph / PKM tool.
- **Not a chatbot.** Search returns a ranked list of memories that open the original artifact. When retrieval starts working there will be real pull to make it conversational — resist it. Conversational answers make it worse at its actual job, which is getting the user back to the original thing. At most, one line of "why this matched."
- **No manual organization, ever.** No folders, no tags, no titles, no required input at save time. If a feature asks the user to organize something, it is the wrong feature.

The product should feel like a personal search engine, not a digital notebook.

---

## Stack

| Layer | Choice |
|---|---|
| Mobile | **Native Android** — Kotlin, Jetpack Compose, MVVM, Room, WorkManager |
| Backend | Node + Express + TypeScript |
| Database | MongoDB Atlas (M0 free tier) + Atlas Vector Search |
| Ingest AI | **Gemini 3.5 Flash Lite** (multimodal — text, image, audio in one call) |
| Query AI | Gemini Flash-Lite (query parsing, cheap and fast) |
| Embeddings | `gemini-embedding-001` |
| On-device OCR | ML Kit Text Recognition v2 |

Pin explicit Gemini model versions in code. The `gemini-flash-latest` alias exists but must not be used in source — behavior shifts under you. `pinnedModel` refuses one outright.

### The free tier is the design constraint

**No billing.** Decided 2026-09-17, after a day's testing hit the wall. What the free tier allows, **per model per project** — each model has its own allowance:

| Model | RPM | RPD |
|---|---|---|
| Every full Flash (2.5, 3, 3.5, 3.6, 3.7, 3.8) | 5 | **20** |
| **Gemini 3.5 / 3.1 Flash Lite** | 15 | **500** |
| Gemini Embedding 1 and 2 | 100 | 1000 |
| Gemma 4 26B / 31B | 30 | 14,400 |

Confirmed 2026-09-18 from the Cloud Console quota page (3.5 and 3.1 Flash Lite 500/day each, embedding 1,000/day and 100/min), and 3.8 Flash's 20 from a real 429. **Where to check:** AI Studio's rate-limit page now shows only per-minute limits; the daily ones are at `console.cloud.google.com/apis/api/generativelanguage.googleapis.com/quotas` (the key's project), filtered on "free tier". Daily limits reset at midnight Pacific.

**Ingest runs on 3.5 Flash Lite and query parsing on 3.1 Flash Lite**, so the two never share one 500. Everything is built to spend requests, not memories:

- **Batch.** One call reads ten memories; embeddings take an array too. 500 seed items is ~50 requests.
- **Never pay twice.** Fingerprints of what the model read mean a retry or a re-send costs nothing.
- **Ask only when there is something new to read.** An image waits for OCR; a memory with no text is stored without a model call at all.
- **Send a picture only when OCR could not read it** (under 80 characters). Images cost ~1,000 prompt tokens each even when tiny, roughly ten times the text beside them, so a text-heavy screenshot is left to its words. Pictures go downscaled, four to a request, once each.

- **A search phrase is parsed once a day.** The same phrase against the same apps on the same day reuses its parse; a parse that fails costs the search only its filters.

Gemma's 14,400/day is the escape hatch if 500 ever binds, though it likely has no structured output.

### Rejected: React Native

The original plan was React Native. Rejected because:

- Every capture surface is an Android platform surface (share sheet, `TileService`, clipboard). You write Kotlin for all of it either way, then pay a bridge tax on top.
- **RN puts the slowest path on the most-used flow.** Both capture entry points are cold-start paths — every save is a fresh process launch. Booting a JS bundle + Hermes + bridge from a QS tile tap is hundreds of ms to seconds; a native transparent activity is ~50ms. The product promise is that saving feels free.
- WorkManager is the right tool for the ingest queue and is native.
- No iOS device to test on, so RN's only real dividend goes unclaimed.
- Developer is already fluent in Kotlin + Compose + MVVM.

Revisit only if iOS becomes a real target.

---

## Architecture rules

These are decisions, not suggestions. Changing one is a deliberate call, not an implementation detail.

**1. Originals stay on the device.**
Do not upload blobs to MongoDB. M0 is 512MB total and Mongo caps documents at 16MB. Originals live in app-private storage or as a MediaStore reference. Files are uploaded to the backend only for processing, then dropped. The cloud stores **extracted text + embedding + metadata only.** This is cheaper, faster, more private, and it is a defensible product stance: your screenshots never permanently leave your phone.

**2. Save never blocks on the network.**
Share sheet / tile → write to Room → return immediately → WorkManager handles upload, processing and retry. The user sees "Saved" before anything touches a server. This is also why backend cold starts are largely invisible on the ingest path.

**3. One Gemini call for ingest, not a four-stage pipeline.**
The original design had OCR → transcription → entity extraction → embedding as separate stages. Instead: a single multimodal Flash call takes the artifact and returns structured JSON — extracted text, a one-line description, entities, inferred type, date references. Audio goes to the same call (Flash ingests audio natively; no separate transcription service). This is *better* than raw OCR, not just simpler, because it understands context: "LinkedIn post, Qualcomm SWE internship, deadline April 30" rather than a bag of words.

**4. On-device OCR as a fast path.**
Run ML Kit text recognition locally at save time. Free, instant, offline. The item becomes searchable in the local index before the network round-trip completes. The Gemini pass enriches it afterward.

**5. Hybrid search, never pure vector.**
Pure semantic search is weakest at exactly what the target queries contain — proper nouns. In *"that guy from the hackathon who worked at Qualcomm"*, "Qualcomm" wants keyword matching and the rest wants semantics. Use Atlas's native `$rankFusion` stage to fuse `$vectorSearch` and `$search` via reciprocal rank fusion in one pipeline. Not extra infrastructure.

**6. Parse the query before searching it.**
*"that internship screenshot from April"* decomposes into a semantic part (internship), a type filter (screenshot), and a date filter (April). Vector search handles none of the filtering — "from April" just becomes noise in the embedding. A cheap Flash-Lite call turns the raw query into `{semantic_query, type_filter, date_range, entity_hints}`, then runs **filtered** vector search using Atlas pre-filtering. Without this layer the headline demo queries do not actually work.

**7. Capture provenance — it is a first-class retrieval signal.**
On `ACTION_SEND`, record the calling package via `Activity.getReferrer()`. That makes *"that link someone sent me on WhatsApp"* answerable. Also store capture timestamp and, for existing files, the original creation date. Cheap to store, and it matches how people actually remember things.

**8. Embeddings at 768 or 1536 dims, not 3072.**
`gemini-embedding-001` defaults to 3072 but is Matryoshka-trained: 1536 scores identically on MTEB (68.17) and 768 is barely behind (67.99). On a 512MB M0 the storage difference matters. Also note M0 allows **3 search indexes maximum** — one vector index plus one text index fits with room to spare.

---

## Android specifics / known gotchas

- **minSdk 29** (Android 10), target latest. Rationale: the clipboard restriction lands at 29, so a 29 floor means one code path instead of two; scoped storage is mandatory from 29 anyway; device coverage is a non-issue in 2026.
- **Clipboard cannot be read from a `TileService`.** Android 10+ blocks clipboard access unless the app has window focus or is the default IME, and a tile service has neither. The tile launches `ClipboardCaptureActivity` instead — but **focus arrives after `onCreate`, so reading there also returns null.** Read in `onWindowFocusChanged(true)`. That in turn rules out `windowNoDisplay` (a window that never shows never gets focus), so it uses the transparent capture theme, with a 2s timeout so a window that never gains focus cannot sit invisibly on top eating touches.
- **`TileService.startActivityAndCollapse(Intent)` throws `UnsupportedOperationException`** for apps targeting API 34+, not merely deprecated. Use the `PendingIntent` overload on 34+.
- **Clips marked sensitive are never saved.** Password managers and OTP autofill set `android.content.extra.IS_SENSITIVE` on what they copy; a personal search engine is the last place a password belongs. The key is read on every API level, since copying apps set it regardless of the constant's API 33 introduction.
- Android 12+ shows its own "Breadcrumb pasted from your clipboard" notice whenever the tile reads the clipboard. Expected, and not something to suppress.
- **WhatsApp offers no Share on text messages or on link-preview messages** — with or without accompanying text. Only Copy and Forward. No manifest change reaches either; copy → clipboard tile (1.6) is the path. (An earlier note here claimed link previews arrive as `image/jpeg`. That was a misread: the message tested contained a real photo, not a preview card.)
- **A WhatsApp photo shared with a caption arrives as `image/jpeg` with the caption in `EXTRA_TEXT`.** Saved as an IMAGE with the caption as `rawText`, plus `hasLink` when the caption carries a URL — see the chip model below.
- **Chip model: one primary `type`, plus a `hasLink` flag.** A file decides the primary type (IMAGE, PDF); otherwise LINK if the text contains a URL; otherwise TEXT. `hasLink` is set whenever shared text contains a URL, so a captioned photo shows IMAGE + LINK chips. LINK is the only kind that combines with another, which is why a single flag is enough rather than a set of types. **Any "link" filter must match `type = LINK OR hasLink`** — filtering on type alone misses every captioned photo and PDF. `hasLink` comes from shared text only, never OCR: a screenshot with a URL bar in it is not a link someone sent.
- **Schema changes go through Room `AutoMigration` against the checked-in schema JSON** (`app/schemas/`). Never `fallbackToDestructiveMigration` — it would silently wipe saved memories on upgrade. v1 → v2 added `hasLink` with a backfill, and was verified by installing over a real v1 database on the phone.
- **`EXTRA_SUBJECT` is not always a title.** WhatsApp sets it to `"Photo from <sender name>"` on image shares. Putting that in `Memory.title` is noise; filter obviously-generic subjects at 1.4.
- **Clipboard-originated shares report `referrer = com.android.systemui`**, not the app the text came from, and carry `nt:source = clipboard`. Provenance (rule 7) is unavailable on that path — do not expect the tile at 1.6 to recover it. `SourceApps.normalize` records these as no source rather than as "System UI".
- **Package visibility decides whether a source app's name can be read.** Without a `<queries>` declaration, Android 11+ hid most packages from Breadcrumb: WhatsApp was visible only because it had granted access to a shared file, and Chrome, sharing plain text, was not visible at all. The launcher-intent `<queries>` in the manifest fixes this without the policy-restricted `QUERY_ALL_PACKAGES`. Verified with `adb shell dumpsys package queries` (Chrome, WhatsApp, Gmail visible afterwards). **This cannot be covered by a portable test** — the obvious candidate, Settings, is force-queryable and visible regardless. Re-run that dumpsys check if the manifest's `<queries>` ever changes.
- **Store the source app's name at capture, not just its package.** It is resolved while the app is installed and visible; resolving it later loses it for anything uninstalled since.
- **Declare `text/html` alongside `text/plain` on share filters.** Some apps send rich text for links; cheap to accept, and `EXTRA_TEXT` still carries the plain fallback.
- **`EXTRA_TEXT` and `EXTRA_PROCESS_TEXT` are `CharSequence`, not `String`.** `getStringExtra` returns null when the sender supplies styled text, turning a real share into "nothing to save" with no error. Always use `getCharSequenceExtra(...)?.toString()`.
- **A shared `content://` URI is readable only while the receiving activity is alive.** Finish first and copy later and the copy fails with a SecurityException. So the capture sheet **cannot be dismissed while it shows "Saving…"** — swipe, back and tapping outside are all ignored until the copy completes, because closing the activity would revoke the grant mid-copy. The same constraint means **provider metadata — notably `DATE_TAKEN` — must be read at capture time**; it cannot be backfilled later.
- **Closing a capture activity is a cross-task transition, and apps cannot customise those.** Sharing apps launch targets with `NEW_TASK` / `NEW_DOCUMENT` and the tile must use `NEW_TASK`, so every capture activity runs in its own task. On finish the system slides the window down; `overrideActivityTransition`, `overridePendingTransition` and `windowAnimationStyle` are all ignored for cross-task transitions. Whatever the window still holds slides with it — on-device, the dim slid away a beat after the sheet. The fix (`CaptureActivity.finishInvisibly`): make the decor view `INVISIBLE`, wait two frames for the window manager to hide the surface, then finish. Diagnosed from logcat: SurfaceFlinger layer names at close showed a `Transition Root` for the underlying task and no system dim or backdrop layer, which placed the tint in our own window. **Any future transparent activity that must close cleanly needs the same treatment.**
- **OCR uses ML Kit's bundled model** (`com.google.mlkit:text-recognition`), not `play-services-mlkit-text-recognition`, which downloads its model on first use and so is not offline from the first save. Gradle fetches the model from Google's Maven at build time and packs it into the APK; nothing is checked in. The cost is APK size: its native library is ~11 MB per ABI, ~41 MB across all four, of which x86/x86_64 serve only emulators. The arm64 library is 16 KB page-aligned (checked from its ELF headers).
- **`extractedText` is what the phone read out of the thing** — OCR of a picture, a PDF's text, a link's page description. **Null means not read yet, empty means read and holding no text.** `OcrQueue` (pictures, PDFs) and the upload worker's `LinkReading` (links) find their work by that null, so never write empty to mean anything else, and resetting a row to null queues it to be read again.
- **Nothing calls OCR.** `OcrQueue` starts in `BreadcrumbApp.onCreate` — every process, capture included — and watches Room for unread images and PDFs. Any code that inserts IMAGE or PDF rows, the 5.1 importer included, gets them read without doing anything. Links are read the same way, by the upload worker's first step.
- **A PDF is read by its text layer on Android 15+** (`PdfRenderer.Page.getTextContents`, API 35), and by OCR of its first six pages when there is none — a scan — or on older Android. Up to 20,000 characters are kept (`PdfRules`); the server's model reads the first 4,000 and embeds 6,000, the word indexes take it all. A PDF behind a password reads as empty.
- **A link's page is read from the phone, never the server** (`PageReader`, `LinkReading`): the person's own connection, as when they open it, and the server never fetches arbitrary URLs. Only the head, up to `</head>` or 512 KB; plain http goes as https; never localhost or a bare IP. Every answer is final — an error page, a login wall's title, a dead link while online — except losing the network, which leaves the link for the next pass. The page title becomes `title` only when the memory has none (a Chrome share brings its own).
- **The "+" sheet's draft lives on disk** (`DraftStore`, SharedPreferences), so it survives the app closing. A picked file is only a URI, so each is given a persistable read grant when picked and let go once kept or removed; a file whose grant did not last is dropped on load. Android caps an app's persisted grants (512), which is why they are released rather than kept.
- **A capture sheet holds its memories back from upload until it goes** (`BreadcrumbApp.uploadHolds`, passed over by `pendingUploads`), so a note written in it costs no second send. Released on dismiss, on leaving for another app, and on destroy — a hold that is never released strands a memory until the process dies.
- **ML Kit reports usage to Google — accepted, 2026-09-15.** Its logging queue shows up as `databases/com.google.android.datatransport.events` in app storage. What it sends is SDK usage and performance data, not images or recognized text, so rule 1's stance holds: originals and their text stay on the device. Accepted rather than stripped, since removing the transport service by manifest merge is unsupported and could break on an ML Kit update. Revisit if the privacy stance tightens.
- **A Text given a style takes nothing from the theme**, so every style names its face — use the helpers in `ui/home/HomeType.kt` (`serif`, `sans`, `monoStyle`), never a bare `TextStyle(fontSize = …)`, which falls back to the platform font. Text given only loose parameters (as the capture sheet's are) inherits Material's type scale, which is set in Instrument Sans.
- **Serif or sans is decided by where a title sits, never by the kind of memory.** Among others on the page — tiles, result rows, the capture sheet — a memory's title is `sans`; opened in its detail it is `serif`. The serif is otherwise only the app's voice (the wordmark, "Saved", empty states).
- **The detail sheet is the app's own `SheetLayer`, not Material's `ModalBottomSheet`.** A shared-element transition — the picture travelling from its tile — only works within one composition, and Material's sheet lives in a window of its own. `SheetLayer` gives back what Material's gave: a height limit, a scrim that closes it, Back (predictive: the sheet follows the swipe, via `PredictiveBackHandler` and `enableOnBackInvokedCallback`), standing on the keyboard, and drag-to-dismiss handed off from the content's scroll through a nested-scroll connection. The "+" sheet is a `SheetLayer` too.
- **The launcher shortcut names the applicationId** (`res/xml/shortcuts.xml`, `targetPackage`). Adding an `applicationIdSuffix` to a build type breaks it for that build.
- **Animations that loop or follow a gesture run only while shown, and are read while drawing** (`graphicsLayer`, `drawBehind`, `Canvas`), not in composition: an infinite transition left running at rest redraws every frame, and reading one in composition recomposes every frame.
- **Haptics follow the phone's Touch feedback setting.** They go through `performHapticFeedback`, which Android drops when that setting is off — it was off on the dev phone at first (`adb shell settings get system haptic_feedback_enabled` read 0). Felt nothing? Check that before the code.
- **Upsert with `@Upsert`, never `@Insert(onConflict = REPLACE)`.** `memories_fts` is an external-content index kept in step by triggers, and REPLACE deletes the old row without firing delete triggers — the old text would stay searchable. `MemorySearchTest` covers it.
- **Search input always goes through `FtsQuery.matchExpression`**, never straight into MATCH: raw input containing `"`, `-`, `OR` or `column:` is a syntax error or means something else. FTS4 (Room supports no FTS5) has no ranking function, so local results are newest first.
- **An AutoMigration that adds or changes the FTS table does not index existing rows.** Room recreates the sync triggers after migrating, but the triggers only see later writes. v3 → v4 rebuilds the index in its spec (`BuildSearchIndex`); any future change to the FTS columns needs the same.
- **Back up the phone's database before installing a schema bump.** `adb exec-out run-as com.lbc.breadcrumb cat databases/breadcrumb.db > breadcrumb.db`, and the same for `-wal` and `-shm` — the WAL holds recent writes. A failed migration rolls back rather than wiping, but real saved memories are not worth the bet. Run it from Git Bash: PowerShell 5.1's `>` re-encodes binary output and corrupts the copy.
- **Running the instrumented tests migrates the real database.** The test APK runs in the app's process, and `BreadcrumbApp.onCreate` opens the real database for the OCR queue — so installing a schema bump and running `am instrument` performs the migration, before the app is ever opened. Back up first.
- **Cleartext HTTP is blocked by default** since Android 9. Local dev against `adb reverse` needs a network security config permitting cleartext to `localhost` — scoped to the **debug** build type only, never release.
- Android Studio should open **`android/`** as the project root, not the repo root. Opening the repo root confuses Gradle sync.
- **compileSdk is 36.1 and only android-30/34/35/36/36.1 are installed.** Some libraries now require compileSdk 37 (lifecycle 2.11.0 does, 2.10.0 does not; OkHttp 5.5.0 does, 5.4.0 does not — read `minCompileSdk` from the AAR's `aar-metadata.properties` to check). Prefer pinning the library back over pulling down another SDK platform unless the newer version is actually needed — disk on this machine is tight.
- **AGP 9 compiles Kotlin itself** (built-in Kotlin), which is why there is no `org.jetbrains.kotlin.android` plugin here. Consequence: KSP must be **2.3.1 or newer** — the older `<kotlin>-<ksp>` versions register generated sources through the `kotlin.sourceSets` DSL and AGP 9 rejects that at configuration time. Do **not** fix it with `android.disallowKotlinSourceSets=false`; Google explicitly advises against that flag. Bump KSP instead.

---

## Dev workflow

**Do not deploy the backend during development.** Run Express locally and bridge the device to it:

```
adb reverse tcp:3000 tcp:3000
```

The device's `localhost:3000` then forwards to port 3000 on the dev machine, over USB or wireless debugging. Same URL works on emulator and physical device, so there is no environment switching. (`10.0.2.2` also reaches the host from an emulator, but `adb reverse` is preferred precisely because it is uniform.)

**The forward is lost whenever the phone reconnects** — re-run it after replugging or restarting adb. The debug list's server line (tap to recheck) says which half is missing: *connection refused* means no `adb reverse`; *connection closed without a reply* means the forward is set but the server is not running.

### Server

```
cd server
npm install
npm run dev         # node --watch; restarts on save
npm run reverse     # adb reverse tcp:3000 tcp:3000
npm test
npm run typecheck
```

- **No build step.** Node 22.18+ runs `.ts` files itself by stripping types; `tsc` only type-checks. So `erasableSyntaxOnly` is on — no enums, namespaces or parameter properties — and relative imports carry the `.ts` extension.
- Listens on **127.0.0.1** unless `HOST` is set; `PORT` defaults to 3000. `adb reverse` connects from the dev machine, so nothing on the LAN needs to reach it.
- **`/health` names the service** (`{"service":"breadcrumb","status":"ok"}`) and the app checks the name, so another dev server holding port 3000 is not mistaken for this one. `ServerClientTest` holds the app's side of that contract; change both together.
- **Secrets live in `server/.env`**, copied from `.env.example` and loaded by Node's own `--env-file-if-exists` — no dotenv. Every `=` line needs its `KEY=` prefix; a bare connection string pasted in makes Node read everything up to the first `=` as the variable name, and the value simply goes missing.
- **The Atlas user is `readWriteAnyDatabase`, not `atlasAdmin`.** Enough to read, write and create indexes, and deliberately not enough to drop a database — so tests drop their own *collection* instead. If 3.4's search-index creation is refused for the same reason, create that index in the Atlas UI rather than widening the role for good.
- **Server tests run against the real cluster** in `<MONGODB_DB>_test` (the search tests are the one exception — see below), and skip themselves when `MONGODB_URI` is unset. **Close the Mongo client in a `finally`** in any hook: a teardown that throws with the client still open hangs the whole run instead of reporting the failure that caused it.
- A free M0 cluster **pauses itself after 60 days idle** and has to be resumed from the Atlas UI.
- **`node --test` runs each test file in its own process, in parallel.** Two suites sharing one database wipe each other's documents mid-test, and the failures look like impossible counts. Give every test file its own database: `<MONGODB_DB>_test_<suite>`.
- **A memory is stored even when the model call fails**, with the reason in `enrichmentError`. The phone has already said "Saved" (rule 2), so a document that is merely unenriched can be fixed later, while a missing one is a memory that quietly never synced. Anything re-enriching later looks for that field.
- **The ingest request is parsed field by field and unknown fields are dropped**, so `localUri` cannot reach the cloud whatever the client sends (rule 1). A test is named for it.
- **`gemini-embedding-001` only returns unit-length vectors at its full 3072 dims.** At 768 they must be L2-normalised before storage, or cosine similarity measures length as much as meaning. Anything that produces an embedding — ingest, and query embedding at 4.1 — normalises.
- **Documents and search phrases are embedded under different task types** (`RETRIEVAL_DOCUMENT`, `RETRIEVAL_QUERY`). Using one for both quietly costs retrieval quality.
- **A vector index's filter fields must be declared up front.** `memories_vector` declares `type`, `hasLink`, `capturedAt`, `sourceAppLabel`, `datedAt`, `linkSites` and `_id`; `memories_text` declares the same. Adding one means rebuilding the index — startup does it when a field is missing (see below), and a filter on the new field errors until the rebuild is done.
- **A source is an app or a site** (`sources.ts`). "That link from Instagram" matches a memory shared from the Instagram app *or* one whose link points to instagram.com, however it arrived. `linkSites` holds the registrable domains of a memory's shared links, derived at ingest and backfilled at startup; sites are named as people say them (instagram.com and instagr.am are "Instagram", youtu.be "YouTube", twitter.com "X"), anything unlisted by its first label. The parser is offered app labels plus the 40 most linked-to site names, one name when an app and its site share it.
- **`FAILED` means the server refused a memory**, and is not retried automatically: the same bytes would be refused again. Transient failures go back to `PENDING` instead, and the debug Sync button queues refused ones again.
- **Every delete goes through `MemoryDao.deleteEverywhere`**, never `delete` alone: it removes the row and records a `pending_deletes` entry in one transaction, and the upload queue sends those first. A delete that stays on the phone leaves the memory's text and vector in Atlas. The debug list's **Clear** deletes everything on the server too.
- **`Memory.enrichedAt` null means "ask the server"** for its reading. `markSynced` and `markImageSent` clear it — every send may have been read again — and the queue's last step copies summary, kind and readText back. Every id asked about is marked, answer or not, so the pass cannot loop.
- **A memory's note is never given to the model to describe** (`enrichmentSource` in ingest.ts). It is embedded, near the front, and word-indexed (`memories_text` gained a `note` field, which startup adds to the live index). So writing or editing a note costs one embedding and never a Flash Lite call, and never replaces what the model saw in a picture with a reading of the note alone. A picture sent to be read does get the note as context.
- **A model call that may succeed later must answer 503, never 200.** A 200 marks the memory synced on the phone, and nothing re-enriches it afterwards — the phone re-sending it *is* the re-enrichment path.
- **Pictures reach the server as base64 JSON and are never written anywhere**, not even a temp file (rule 1). `/memories/images` only reads pictures for memories it already holds; the phone sends memories first and pictures after, in the same worker run.
- **OCR must ask for an upload pass after *every* read, including one that found nothing.** Images are held back until OCR has looked at them, and an empty read is the very case whose picture gets sent — skip the signal and those photos wait for the next save or launch.
- **A missing search index is not an error.** `$vectorSearch` or `$search` against an index that does not exist answers an empty list, so hybrid search quietly becomes one-half search. Startup creates both indexes and says so; if results look like only meaning or only words, check the indexes before the query.
- **M0 holds three search indexes per *cluster*** (confirmed 2026-09-18: a third was refused with "maximum number of FTS indexes"). A new one takes ~30s to become queryable. The real collection uses two.
- **The search tests borrow the real indexes** — decided 2026-09-18, because a test pair plus the real pair would make four. The rules that keep it safe: every test document has a fixed id starting `breadcrumb-test-` (no UUID can); every search in the tests is narrowed to those ids through the `_id` filter field, **in both halves**; cleanup deletes those ids and nothing else; nothing drops, empties or updates anything else. Move them to a second free cluster when deploying.
- **Every test memory is searched by every test phrase.** A word slipped into a new seeded memory — even in a URL, as `instagram.com/p/lake-trip` did — is found by an older test's phrase ("trip to japan") and ties its ranking. Give seeded URLs id-like paths.
- **Startup updates a search index only when it lacks a declared field** (`ensureSearchIndex`, via `declaredPaths`). Atlas reports definitions back with its own defaults, so comparing whole definitions would rebuild at every start. A changed analyzer or field type is applied by hand. While an update builds, the old version answers, and a filter on the new field errors until it is done.
- **Dates filter on `datedAt`** — when a picture was taken, else when it was saved — derived at ingest and backfilled at startup. Days are placed on the server's own calendar: right in development, where the server runs on the phone owner's machine; a deployed server needs the phone's time zone.
- **A search filter must narrow both halves** (`searchPipeline`): MQL in `$vectorSearch.filter`, a compound `filter` in `$search`. Narrow only one and the other brings back what was filtered out.
- **Search results are an allow-list projection** (`searchPipeline`). The text goes back whole; the vector never leaves. A field added to the document stays in the cloud until it is listed.
- **PowerShell 5.1 strips double quotes from arguments to native programs**, here-strings included, so a `git commit -m` message containing `"` splits into pathspecs. Commit with `git commit -F <file>`.
- **Live model tests skip themselves when Gemini is overloaded** (503/429 after retries). A third party's capacity is not something to fail a build over — but a skip is not a pass, so read the run's skip lines.

### Editors

Testing is on a **physical device over USB / wireless debugging**. No emulator is installed or wanted.

Android Studio is required for the `android/` module — Live Edit and `@Preview` are IDE features and do not work from VS Code or the CLI. `server/` is ordinary TypeScript and belongs in VS Code. Running both IDEs at once is the RAM problem; running Android Studio *only while working on Android* is the answer.

CLI build/install, when the IDE is not open:

```
cd android && ./gradlew installDebug
adb shell am start -n com.lbc.breadcrumb/.MainActivity
```

Instrumented tests, **without wiping saved memories**. Gradle's `connectedDebugAndroidTest` removes the APKs when the run finishes, and uninstalling the app deletes its Room database and stored originals. Install and instrument directly instead:

```
cd android && ./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.lbc.breadcrumb.test/androidx.test.runner.AndroidJUnitRunner
```

**If a build fails with "The paging file is too small" (or a JVM `hs_err_pid` crash log appears in `android/app/`):** the machine is out of memory — idle Gradle daemons hold ~1 GB each. `./gradlew --stop` frees them; leave any other Java process alone, as VS Code's Java extension runs its own. Delete the crash log; it is not for committing.

**If a build fails with `jlink executable ... redhat.java ... does not exist`:** VS Code's Java extension imported `android/` and left an idle Gradle daemon running on its bundled JRE, which has no `jlink`. `gradlew` reuses any idle Java 21 daemon, so it inherits the broken JVM. Run `./gradlew --stop` and rebuild. To stop it recurring, set `"java.import.gradle.enabled": false` in VS Code for this workspace — that extension cannot build Android projects anyway, and the daemon it keeps alive is pure RAM cost.

### Backend deployment — deferred, not decided

Not a V1 concern. When it comes up, the notes are:

- Render's free tier spins down after 15 minutes idle and cold-starts in 30–60s. The cron-ping keep-alive hack technically works but eats essentially the entire 750 instance-hour monthly allowance with no headroom — fine as a demo trick, fragile as a strategy.
- **Google Cloud Run is the better free option**: scales to zero, ~2s cold start, 2M requests/month always-free. Costs a Dockerfile and a billing account on file.
- Fly.io wakes in 200–500ms but has no free tier since 2024 — cheap, not free.
- Because of architecture rule #2, a cold start on the *ingest* path is invisible. Only **search** is latency-sensitive, and any save also wakes the server.

---

## Repo layout

```
breadcrumb/
  android/      Gradle project — open THIS in Android Studio
  server/       Express + TypeScript, own package.json
  tools/        generate_icons.py — regenerates all launcher/splash assets
  CLAUDE.md
  README.md
```

Icons and the splash asset are **generated, not hand-drawn**. To change the mark, edit the palette/geometry constants at the top of `tools/generate_icons.py` and re-run it — do not edit the PNGs directly, they will be overwritten. The one exception is `res/drawable/ic_tile_crumb.xml`, the Quick Settings tile icon: a hand-built vector, because the system tints tile icons and needs a single-colour shape. Keep it roughly in step with the mark if the mark changes.

Polyglot monorepo — separate toolchains, no shared build system. **Do not add Nx or Turborepo**; there is nothing meaningful to share between Gradle and npm.

- One `.gitignore` per subfolder, not a merged root one.
- No npm workspaces until `/extension` exists and wants to share types with `/server`.
- No type codegen between server and Android. With one backend and one client, hand-written Kotlin data classes matching the API are faster and clearer. Revisit if the API starts churning.

---

# ══════════ V1 SCOPE ══════════

Everything down to the V1 boundary below is in scope. Everything under "Post-V1" is explicitly out.

## How we work

One step at a time. Each step below is small enough to finish, test on a real device, and commit on its own.

1. Build the step
2. **Test it on the phone** — not in an emulator, not "it compiles"
3. Commit
4. Tick the box here, then move on

Do not start the next step until the current one is ticked. Do not batch several steps into one commit. **This checklist is the only progress tracker** — no separate TODO files, no issues.

`[x]` done and verified on device · `[~]` in progress · `[ ]` not started

### Phase 0 — Foundation

- [x] **0.1** Android project scaffold — Compose, minSdk 29, `com.lbc.breadcrumb`
- [x] **0.2** Brand icon + splash screen (generated by `tools/generate_icons.py`)
- [x] **0.3** Initial commit, `.gitignore` sanity check, `server/` placeholder

### Phase 1 — Capture (local only; no AI, no backend)

- [x] **1.1** Room: `Memory` entity, DAO, database
      · *test:* `./gradlew connectedDebugAndroidTest` — 7 DAO tests on the device
- [x] **1.2** Debug list screen showing all saved memories — temporary, replaced at 4.2
      · includes a "+" that inserts a sample memory, since no capture surface exists yet
      · *test:* launch app, tap + a few times, rows render and survive a restart
- [x] **1.3** Share sheet target for text and links (`ACTION_SEND`, `ACTION_PROCESS_TEXT`)
      · *test:* `./gradlew testDebugUnitTest` — 13 JVM tests on the classification rules
      · *test:* share a URL from Chrome → LINK row with the page title as its title
      · *test:* select text anywhere → Breadcrumb in the selection toolbar → TEXT row
      · confirms with a toast for now; the designed capture sheet lands at 1.7
- [x] **1.4** Share sheet for images and PDFs; copy into app-private storage
      · *test:* `./gradlew testDebugUnitTest` — media classification, EXIF dates, URL detection,
        and the chip table
      · *test:* on-device `OriginalStoreTest` — byte-exact copy, delete never escapes the store
      · *test:* share a screenshot from the gallery → thumbnail, file size and taken date in the row
      · *test:* share a WhatsApp photo with a caption → IMAGE row, caption as its text,
        plus a LINK chip when the caption has a URL (chip table in `MemoryChipsTest`)
      · *test:* share several images at once → one row each, "Saved N"
      · *test:* share a PDF → PDF row titled by its filename
      · *test:* delete a row / Clear → its stored file is removed too
- [x] **1.5** Source-app provenance via `Activity.getReferrer()`
      · package recording already landed with 1.3/1.4; this step makes it usable:
        app name stored at capture, system surfaces dropped, `<queries>` for visibility
      · *test:* `./gradlew testDebugUnitTest` — `SourceAppsTest`; on-device `SourceAppResolverTest`
      · verified: Chrome share → "from Chrome"; WhatsApp → "from WhatsApp"; clipboard → no source
      · verified: text selection toolbar reports the host app ("from Chrome"), so provenance
        works on that path too
      · verified: a Gmail PDF opens in Drive, so sharing it records "from Drive" — accepted as
        correct, since Drive is where it was shared from
- [x] **1.6** QS tile → transparent activity → save clipboard
      · *test:* `./gradlew testDebugUnitTest` — `ClipboardRulesTest`
      · verified: copied text → TEXT row, no source; WhatsApp message → saved; text with a
        link → LINK; Chrome "copy image" → IMAGE row; lock screen → unlock first, then saves
      · **not verified on device:** the sensitive-clip skip. No password manager to test with,
        so it rests on `ClipboardRulesTest` alone. Worth a real check if one is ever installed.
      · tapping twice saves the clipboard twice. Accepted — no duplicate detection wanted
- [x] **1.7** Replace the capture toast with the designed capture sheet
      · board 4 of the design canvas: sheet over the host app, crumb drop, undo
      · all capture activities now extend `CaptureActivity` (a `ComponentActivity`) and share
        one translucent `Theme.Breadcrumb.Capture`; `windowNoDisplay` is gone
      · *test:* `./gradlew testDebugUnitTest` — `CaptureSummaryTest` (wording and preview shape)
      · the sheet **stays until dismissed** (Done, swipe down, tap outside, back); only undo
        closes it on its own, after showing "Removed"
      · scrim and sheet share one transition, and Android's own open/close window animations
        are switched off — the dim must leave with the sheet, never a beat after it
      · *test:* dismiss any way → dim fades out together with the sheet, nothing slides after
      · *test:* one photo → shown large, following its shape (screenshots cropped from the top)
      · *test:* several images → row of square tiles, "+N" on the third
      · *test:* a Chrome page → link card: site, page title, URL
      · *test:* shared text → the text itself; a PDF → document card with title, type, size
      · *test:* Undo → "Removed", closes, row and stored file gone
      · *test:* a failure (empty clipboard) → reason and a Close button
      · verified on device, all paths: previews, undo, failures, and a clean close with no
        dim sliding away afterwards
      · the system monospace stood in for IBM Plex Mono here; the canvas's faces were bundled
        at 4.6

### Phase 2 — On-device intelligence (still no backend)

- [x] **2.1** ML Kit OCR on image saves → store extracted text
      · *test:* `./gradlew testDebugUnitTest` — `OcrRulesTest` (downsampling, EXIF rotation, cleanup)
      · *test:* on-device `OcrQueueTest` — fake reader: newest first, failures skipped rather than
        retried, a memory undone mid-read stays deleted; `MlKitOcrReaderTest` — the real model
        against images the test draws
      · *test:* share a text-heavy screenshot → "ocr · N lines" in the debug list, full text on tap
      · verified on device: images saved in phase 1 were read on the next launch; a text-heavy
        screenshot shows its text; a photo without text shows "no text found"; the capture sheet
        animates as before
      · open: Latin script only. (A PDF's contents went unread, found by filename alone, until 4.9.)
- [x] **2.2** Room FTS index + local keyword search
      · *test:* `./gradlew testDebugUnitTest` — `FtsQueryTest` (words become quoted prefix terms)
      · *test:* on-device `MemorySearchTest` — OCR-only words, prefixes, every word must match,
        case and accent folding on the device's SQLite, FTS syntax in the input, and the index
        following insert, update, upsert, delete and clear
      · *test:* search a word that appears only inside a screenshot
      · verified on device: installed over the real v3 database — all 32 memories indexed, all
        four sync triggers present (checked from a pulled copy)
      · search field on the debug list, results newest first; ranking waits for 4.4

### Phase 3 — Backend + ingest

- [x] **3.1** Express skeleton + `/health`; `adb reverse` wired; app pings on launch
      · *test:* `npm test` in `server/` — `/health` shape, JSON 404, no `x-powered-by`
      · *test:* `./gradlew testDebugUnitTest` — `ServerClientTest`: reachable, another server on
        the port, Breadcrumb failing, refused, accepted-then-closed, timeout
      · *test:* app reports server reachable
      · verified on device: the status line under the title read reachable, and tapping it walked
        all four states — reachable; no `adb reverse` → "connection refused -- run adb reverse";
        forward set but server stopped → "connection closed without a reply -- is the server
        running?"; server restarted → reachable
- [x] **3.2** MongoDB Atlas connection + memory collection
      · *test:* `npm test` in `server/` — writes a memory and reads it back whole, a re-send
        replaces rather than duplicates, an unknown id is null, newest first
      · verified against the real M0 cluster; the suite skips itself when `MONGODB_URI` is unset
      · `/health` deliberately still touches no database — it is a liveness check
- [x] **3.3** Gemini Flash structured extraction for text memories (one call, per rule #3)
      · *test:* `npm test` in `server/` — the endpoint against the real database with a stubbed
        model (stored shape, idempotent re-send, `localUri` never stored, a failed call still
        stores the memory, no text means no call, a bad body is refused), the reply parser, and
        one live call proving the pinned model, the schema and the SDK agree
      · *test:* POST a link → structured JSON stored
      · verified by the user against the running server and Atlas
      · model pinned to `gemini-3.8-flash`; enrichment is summary, kind, entities, dates
      · **open:** the Gemini free tier may use what is sent to improve Google's products, and
        what is sent is the text of everything saved. Enabling billing on the key's project
        moves it to the paid tier, where it is not. Undecided.
- [x] **3.4** Embeddings + Atlas vector index at 768 dims
      · *test:* `npm test` in `server/` — against the live model: 768 unit-length dims, a query
        phrase landing nearer the memory it describes, and **two related texts scoring closer
        than two unrelated ones** (by >0.1); plus what gets embedded, and the index definition
      · verified: `vector index "memories_vector" exists` at startup, and a posted link came
        back `enriched: True, embedded: True` with its vector in Atlas
      · **ingest took ~13s per memory** on 3.8 Flash. Resolved at 3.5: Flash Lite answers in
        ~1.4s per *batch*. (`thinkingBudget: 0` was tried — Flash Lite refuses `thinkingConfig`.)
      · **open:** `gemini-embedding-2` is now stable and multimodal, which is what Post-V1
        wanted for screenshots with little text. Staying on `gemini-embedding-001` for V1.
- [x] **3.5** WorkManager upload queue with retry
      · *test:* `npm test` in `server/` — batching (one model call per batch), reuse of unchanged
        text, a skipped item, 503 on a rate limit, per-item validation errors
      · *test:* `./gradlew testDebugUnitTest` — `ServerClientTest`: the payload carries no
        `localUri`, per-memory answers, refusal vs unavailable, an unmentioned memory
      · *test:* on-device `MemoryUploaderTest` — oldest first, one request per batch, nothing
        sent twice, offline stays queued, refusals stop, stale UPLOADING re-queued, an image
        waiting on OCR held back, text read mid-upload keeping it queued
      · *test:* save in airplane mode → reconnect → syncs without duplicating
      · verified on device with the server running: the queue drained on launch, a save in
        airplane mode stayed unsent, and reconnecting drained it with nothing duplicated
- [x] **3.6** Image ingest — upload for processing, discard server-side after
      · *test:* `npm test` in `server/` — pictures stored nowhere, one model call per batch, the
        same picture never read twice, an unknown memory refused, 503 on a rate limit; plus a
        live read of a hand-encoded PNG proving Flash Lite takes images
      · *test:* `./gradlew testDebugUnitTest` — `ImageRulesTest` (the downscale arithmetic)
      · *test:* on-device `ImageForUploadTest` (3000×2000 PNG leaves as 1500×1000 JPEG) and
        `MemoryUploaderTest`: only images OCR could barely read, once each, after their memory
        is on the server, four to a request
      · *test:* share a screenshot → entities returned, original still only on device
      · verified on device: a photo with no text came back with `readText`, entities and an
        embedding, and no image data or `localUri` in Atlas; a text-heavy screenshot sent no
        picture; nothing was sent twice
      · schema v5 (`imageSentAt`) verified by installing over the real v4 database

### Phase 4 — Retrieval

**Order changed 2026-09-18:** the server steps first — 4.1, 4.4, 4.3, all tested from the
terminal — then the UI steps 4.2 and 4.5 back to back (committed apart), then 4.6. A keyword query
("government") against real data showed vector-only search ranking noise, and a UI tested on
that would feel broken for exactly what people type. 4.3 adds its filters to both halves.

- [x] **4.1** `/search`: embed query → vector search → ranked results
      · *test:* `npm test` in `server/` — `parseSearch`; the route's refusals and model failures
        (400, 503 retryable, 502) proved never to reach the database; and against real Atlas,
        on a throwaway 4-dim index (replaced at 4.4 by the real indexes): nearest first, the
        phrase embedded as a *query*, the result shape, an unembedded memory never a result
      · *test:* curl a natural-language query, get sensible hits
      · `GET /search?q=&limit=` — exact nearest neighbours, not approximate (Atlas's advice
        under ~10k documents; no `numCandidates` to tune, and exact under 4.3's pre-filters)
      · results carry the text in full (`rawText`, `extractedText`, `readText`) plus summary and
        kind, so a result is readable without the phone; the vector never leaves
      · verified by the user with curl against the real collection: results readable. A keyword
        query ("government") ranked the only memory containing the word fourth, the top five
        within 0.003 of each other — rule 5's weakness, exactly as predicted, left to 4.4
- [x] **4.4** Hybrid search via `$rankFusion` (rule #5) — *moved ahead of 4.2, see above*
      · *test:* `npm test` in `server/` — against the real indexes, under the test label: a word
        only one memory holds beats the nearest meaning; **a proper noun ("Qualcomm") beats the
        pure-vector baseline**; stemming; meaning still wins when no words are shared; a memory
        with no embedding found by its words; the filter keeping real memories out of *both*
        halves; the fused score and result shape
      · *test:* a proper-noun query ("Qualcomm") beats the pure-vector baseline
      · *test:* "government" puts the one screenshot containing it first
      · text index `memories_text` under `lucene.english` (stop words dropped, stemmed); the
        source app is a token, never text; 4.3's filter fields declared up front
      · halves weigh the same; each result carries `ranks: {vector, text}`, the "why this
        matched" for now. Tune the weights with phase 5's corpus, not before
      · checked by the user with curl against the real collection
- [x] **4.3** Flash-Lite query parsing → type and date filters (rule #6)
      · *test:* `npm test` in `server/` — the parser's guards (made-up types, impossible dates,
        unknown apps dropped), the filter in both halves' languages, the day-long parse cache;
        three live parses on `gemini-3.1-flash-lite`; and against the real indexes: type and
        month together, dates by when a picture was taken, all-filter listing with no embedding,
        only the leftover words embedded, a failed parse still searching
      · *test:* *"screenshot from April"* filters by both type and month
      · *test:* *"that link from WhatsApp"* also finds photos whose caption carried a link
        (filter on `type = LINK OR hasLink`)
      · parsing must also extract a **source app** filter, matched against
        `sourceAppLabel` — provenance is a filter, never mixed into embedded text
      · the source app is an enum of the labels memories carry, so the model cannot invent one
      · rule 6's `entity_hints` left out: nothing would use them, since the text half already
        matches names exactly (4.4)
      · the answer reports its `interpretation`, so a searcher can see why the list is what it is
      · **a new phrase takes ~2–3s**: the parse (1.3–2.3s) runs before the embedding. At 4.2,
        embed the raw phrase in parallel and reuse it when the parse leaves it unchanged
      · tested by the user with curl against the real collection
- [x] **4.2** Real search UI, replacing the debug list
      · *test:* type a query on device, see ranked results
      · deletes did not sync then, so the server held memories the phone had deleted (16 there
        against 6 on the phone, 2026-09-18); results drop ids the phone does not hold. Deletes
        sync since 4.6, but the join stays — memories deleted before that are still there
      · *test:* `./gradlew testDebugUnitTest` — `ResultTextTest` (fragments, titles, ages, the
        counter), `SearchJoinTest` (server order kept, deleted ids dropped), `ServerClientTest`
        (the search contract); on-device `MemoryDaoTest` (`getByIds`, `searchOnce`)
      · design boards 1–2: the mosaic at rest, a ranked list while typing. The phone's word
        index answers at once; the server's ranking replaces it after a 450ms pause, and the
        word matches stand when the server is out of reach ("offline · words only")
      · the debug list survives behind a long press on the wordmark, debug builds only
      · the app is dark whatever the phone's theme, as the canvas decides
      · verified on device: mosaic, ranked results with the lit match, filter searches,
        deleted memories absent, clearing and Back
- [x] **4.5** Tap a result → open the original artifact
      · *test:* tap a saved screenshot → opens in a viewer
      · *test:* tap a saved PDF → opens in a PDF viewer (originals are app-private, so
        this needs a FileProvider to hand another app read access)
      · *test:* `./gradlew testDebugUnitTest` — `OriginalsTest` (the action per kind),
        `UrlTextTest`; on-device `OriginalsProviderTest` — a stored original read back through
        its content URI, a read-only one-off grant, the database and cache unreachable
      · design board 3, as a sheet: the artifact, provenance, the text found, one action —
        open original / open link / copy text
      · verified on device: screenshot → gallery, PDF → PDF viewer, link → browser, note copied
      · built and tested together with 4.2 at the user's request, committed apart
- [x] **4.6** Search screens, second pass — from trying 4.2 and 4.5 on the phone
      · **done:** the detail sheet stops at 84% of the window, so the mosaic it came from
        stays in sight; its picture is cropped at 42%
      · **done:** a memory opened from the mosaic shows what a search shows. The server's
        reading (summary, kind, what it saw) is copied back into Room by the upload queue
        (`POST /memories/enrichment`), so it also captions picture tiles and is found by local
        word search offline. Schema v6, verified by installing over the real v5 database
      · **done:** delete from the detail, with five seconds of Undo in place of a confirmation;
        the delete reaches the server (`POST /memories/delete`) through a `pending_deletes`
        queue in Room, as do the capture sheet's Undo and the debug list's delete and Clear
      · *test:* `npm test` in `server/` — `sync.test.ts`; `./gradlew testDebugUnitTest` —
        `ServerClientTest` (both calls); on-device `MemoryDaoTest` (delete, restore, clear,
        a copied summary found by local search) and `MemoryUploaderTest` (deletes sent once,
        kept when unconfirmed, never sent when undone; readings copied, asked once per send)
      · **the design pass**, agreed 2026-09-18, built on the `design-pass` branch one commit
        per piece, and merged by the user once verified on the phone:
        - three faces bundled (`res/font`, OFL licences in `assets/licenses`): Young Serif
          for the serif, Instrument Sans, IBM Plex Mono for chrome; Material's type scale set
          in them too. The serif is the user's pick: Instrument Serif was dropped as too thin
          on the ink, Fraunces tried, and Young Serif chosen — ordinary weight, sturdy, a face
          not often seen
        - **a memory's title is sans on the page and serif when opened — for every kind**
          (the user's rule, after a first version that went by kind)
        - "breadcrumb" large in the serif at the head of the mosaic, handing over to a slim
          strip as it scrolls away
        - picture tiles all picture, captioned over a shade; notes under a small amber mark
        - the crumb trail walking in the search field while a search is out (no "searching…"),
          and an amber light behind the field, breathing while it searches
        - results rising in, a stagger apart; rows gliding to their ranked places when the
          ranking replaces the word matches; the matched word warming to amber
        - the mosaic rising in on launch; a new save or an Undo dropping in with the crumb's
          bounce; a deleted tile fading while its neighbours close the gap
        - a picture or PDF page travelling from its tile or row up into the detail and back
        - haptics: results landing, a delete, the debug long press, a sheet pulled past its
          dismiss point
      · *test:* `./gradlew testDebugUnitTest` — `MastheadTest`, `CrumbTrailTest`,
        `MosaicEntrancesTest`; the motion itself is checked on the phone
      · verified on device: faces, masthead, tiles, the walking crumb and the breathing
        light, results and mosaic motion, the travelling picture, and all four haptics

**Before seeding, 2026-09-18:** the user asked for two ways in (4.7, 4.8), for the two gaps
phase 5 would expose — a bare link carries only its URL, a PDF only its filename — to be
closed (4.9), and for seven small fixes (4.10). Built on the `pre-seeding` branch, one commit
per piece, checked on the phone by the user and merged; the on-device suite (123 tests) and the
server's (134) ran against the real phone and Atlas first, and caught a short PDF's text layer
being mistaken for a scan (fixed). Follow-ups from trying them are 4.11, committed on main.

- [x] **4.7** A note on a save
      · the capture sheet's quiet "add a note" opens a field (closed by default: saving stays
        one tap); the note goes on every memory the share saved, when the sheet goes
      · `Memory.note`, schema v7 (the word index gains it and is rebuilt); kept apart from
        `rawText`, which is what was shared
      · the memories wait out the open sheet (`uploadHolds`), so a note costs no second send
      · server: embedded and word-indexed, never read by the model (see the gotcha), and in
        search results
      · *test:* `npm test` in `server/` — the note parsed, stored, embedded; a note written
        later costing an embedding and no model call; a picture with only a note embedded by
        it; a memory found by its note's words. `./gradlew testDebugUnitTest` —
        `ServerClientTest` (the payload carries it); on-device `MemoryDaoTest` (found by local
        search, re-queues, unchanged is no change) and `MemoryUploaderTest` (held while the
        sheet is open)
- [x] **4.8** The "+": keeping something from inside the app
      · an amber "+" beside the search field (hidden while searching) opens a `SheetLayer`:
        one box to write in, a photo (system photo picker, no permission) or PDF to go with
        it. No title, no formatting — not a notes app
      · text alone is the memory (a note, or a link by the share rules); with files, each file
        is a memory and the text is their note. No source app. A draft survives the sheet
        being swiped away
      · a launcher shortcut, "Keep something", opens it straight from the app icon
      · *test:* on-device `WrittenCaptureTest`; the picking and saving of files on the phone
- [x] **4.9** Reading what was saved: PDFs' text and links' pages
      · a PDF is read on the phone: its text layer on Android 15+, OCR of its first pages
        otherwise; every PDF already saved is read on the next launch and sent again
      · a link's page is read on the phone by the upload worker's first step: its title (when
        the memory has none) and description, so a link copied bare from a chat is found by
        what it was about. Links already saved are read on the next pass
      · both ride the ordinary batched upload: no request of their own
      · a thin text layer is read by OCR too, and kept unless OCR found clearly more: a
        one-line PDF was taken for a scan and misread ("ACME VWidgets") until then
      · verified on device: the PDF and both bare links already saved were read on the first
        run after installing, and synced
      · *test:* `./gradlew testDebugUnitTest` — `PdfRulesTest`, `PageMetaTest`,
        `PageReaderTest` (against a local server); on-device `PdfReaderTest` (a PDF with a text
        layer, and a scan, both drawn by the test), `OcrQueueTest`, `LinkReadingTest`,
        `MemoryUploaderTest` (fresh PDFs and links held while unread)
- [x] **4.10** Seven small fixes
      · a drag on the results or the mosaic puts the keyboard away
      · tapping the slim "breadcrumb" strip goes back to the top of the mosaic
      · share a memory onward from its detail: the file, the URL, or a note's words, with the
        person's note (since 4.11); Breadcrumb itself left out of the share sheet
      · the note shown in the detail and editable there — only the note, never the memory
      · the launcher shortcut (4.8)
      · sheets follow the predictive back gesture
      · a "status" line in the detail while there is one: still reading, page not read,
        not sent yet (so found by words only), refused
      · *test:* `OriginalsTest` and on-device `OriginalsProviderTest` (sharing), `ResultTextTest`
        (the status line)
      · 4.7-4.10 verified on device by the user, 2026-09-19
- [x] **4.11** From trying 4.7-4.10 — committed on main
      · "ig link" / "link from instagram" found only links shared *from the Instagram app*; it
        now also finds an Instagram link sent on WhatsApp or copied (a source is an app or a
        site, see the gotcha). The indexes gain `linkSites`; startup adds it and backfills
      · the "+" draft survives the app being closed, picked files included; a dot on the "+"
        says one is waiting
      · sharing onward carries the note: a photo's caption, the line before a link, after a
        thought's words
      · *test:* `npm test` in `server/` — `sources.test.ts` (sites and their names), the source
        filter in both halves' languages, the parser offered site names, and against the real
        indexes an Instagram link sent on Telegram found beside one shared from Instagram; a
        live parse of "ig link". `./gradlew testDebugUnitTest` — `DraftCodecTest`,
        `OriginalsTest` (the note with each kind); on-device `DraftStoreTest`,
        `OriginalsProviderTest` (the caption on the share)
      · verified on device by the user, 2026-09-19: an Instagram link sent on WhatsApp found
        by "ig link", the draft back after the app was closed, the note going with a share

**Phase 4 closed 2026-09-19.** Every step verified on the phone. Phase 5 is next and not
started.

### Phase 5 — Seeding

- [ ] **5.1** Bulk importer: screenshots folder
- [ ] **5.2** Bulk importer: Chrome bookmarks export, downloaded PDFs
      · the importer only inserts rows: 4.9 reads every unread link's page and PDF's text
        on its own, four pages at a time, from the phone, at no Gemini cost
      · *test:* 500+ real items indexed, then honestly assess retrieval quality

**Why phase 5 is not optional:** a personal memory search engine is worthless until it holds a few hundred items. The likely failure mode for this project is building it, using it a week, having 14 items, finding search unimpressive, and losing motivation. This kills most apps in the category. Seed 500+ real items so retrieval is tuned against a corpus that actually exercises it. Pull it earlier than phase 5 if motivation dips.

## Also in V1, outside the numbered steps

- **Auth: no user accounts.** One device, a long-lived token in the Android keystore, backend validates it. Google Sign-In is an afternoon's work whenever it is actually needed — do not spend V1 on user management for a one-user app.
- Express stays thin: ingest (`/memories`, `/memories/images`), search (`/search`), and keeping the phone in step (`/memories/enrichment`, `/memories/delete`). Each is a few lines over a function that is tested on its own.
- Search UI is search-bar-first. No dashboard, no feed, no folder tree.

## V1 explicitly excludes

Voice notes. They are in the product vision and the ingest call handles audio in principle (rule #3), but recording UI, playback and audio storage are their own slice of work. Add them once text and images are solid.

# ══════════ END OF V1 ══════════

---

## Post-V1 — DO NOT BUILD IN V1

Listed so the ideas are not lost, not so they get pulled forward. Do not start any of these without an explicit decision to move past V1.

**V2**
- **Voice notes** — recording, playback, audio storage; the Gemini ingest call already accepts audio natively
- **Browser extension** (Plasmo) — cut from V1 deliberately: it is a second frontend, a second auth flow and a second capture pipeline for maybe 10% of saves. Ship the phone app, live on it for a month, then decide.
- **Real auth** — Google Sign-In, proper user accounts
- **Cross-device sync** — requires object storage for originals (R2 or B2), which reverses architecture rule #1. Deliberate call, not a drive-by change.
- **Screenshot auto-detect** — a `ContentObserver` on MediaStore can notice new screenshots and offer to save via notification. High-leverage for the single biggest content type, but costs `READ_MEDIA_IMAGES` and real battery care.

**Later / speculative**
- `gemini-embedding-2-preview` is multimodal — embedding the image itself rather than only its extracted text would meaningfully help screenshots with little text. Worth watching.
- Reranking pass over the top ~20 hits before display
- iOS (only if a test device exists)
- Desktop capture

---

## The name

From leaving breadcrumbs while walking through a forest. You don't know when you'll want to return somewhere; you just leave a trail as you go. Users drop small pieces of information as they move through their digital lives, then follow the trail back when they need something.

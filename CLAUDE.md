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
| Ingest AI | Gemini Flash (multimodal — text, image, audio in one call) |
| Query AI | Gemini Flash-Lite (query parsing, cheap and fast) |
| Embeddings | `gemini-embedding-001` |
| On-device OCR | ML Kit Text Recognition v2 |

Pin explicit Gemini model versions in code. The `gemini-flash-latest` alias exists but must not be used in source — behavior shifts under you.

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
- **Cleartext HTTP is blocked by default** since Android 9. Local dev against `adb reverse` needs a network security config permitting cleartext to `localhost` — scoped to the **debug** build type only, never release.
- Android Studio should open **`android/`** as the project root, not the repo root. Opening the repo root confuses Gradle sync.
- **compileSdk is 36.1 and only android-30/34/35/36/36.1 are installed.** Some androidx libraries now require compileSdk 37 (lifecycle 2.11.0 does; 2.10.0 does not). Prefer pinning the library back over pulling down another SDK platform unless the newer version is actually needed — disk on this machine is tight.
- **AGP 9 compiles Kotlin itself** (built-in Kotlin), which is why there is no `org.jetbrains.kotlin.android` plugin here. Consequence: KSP must be **2.3.1 or newer** — the older `<kotlin>-<ksp>` versions register generated sources through the `kotlin.sourceSets` DSL and AGP 9 rejects that at configuration time. Do **not** fix it with `android.disallowKotlinSourceSets=false`; Google explicitly advises against that flag. Bump KSP instead.

---

## Dev workflow

**Do not deploy the backend during development.** Run Express locally and bridge the device to it:

```
adb reverse tcp:3000 tcp:3000
```

The device's `localhost:3000` then forwards to port 3000 on the dev machine, over USB or wireless debugging. Same URL works on emulator and physical device, so there is no environment switching. (`10.0.2.2` also reaches the host from an emulator, but `adb reverse` is preferred precisely because it is uniform.)

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
      · open: mono text uses the system monospace as a stand-in for IBM Plex Mono; bundling
        the design's fonts is a separate decision

### Phase 2 — On-device intelligence (still no backend)

- [ ] **2.1** ML Kit OCR on image saves → store extracted text
      · *test:* share a text-heavy screenshot → OCR text visible in debug list
- [ ] **2.2** Room FTS index + local keyword search
      · *test:* search a word that appears only inside a screenshot

### Phase 3 — Backend + ingest

- [ ] **3.1** Express skeleton + `/health`; `adb reverse` wired; app pings on launch
      · *test:* app reports server reachable
- [ ] **3.2** MongoDB Atlas connection + memory collection
      · *test:* server writes and reads back a doc
- [ ] **3.3** Gemini Flash structured extraction for text memories (one call, per rule #3)
      · *test:* POST a link → structured JSON stored
- [ ] **3.4** Embeddings + Atlas vector index at 768 dims
      · *test:* two related texts score closer than two unrelated ones
- [ ] **3.5** WorkManager upload queue with retry
      · *test:* save in airplane mode → reconnect → syncs without duplicating
- [ ] **3.6** Image ingest — upload for processing, discard server-side after
      · *test:* share a screenshot → entities returned, original still only on device

### Phase 4 — Retrieval

- [ ] **4.1** `/search`: embed query → vector search → ranked results
      · *test:* curl a natural-language query, get sensible hits
- [ ] **4.2** Real search UI, replacing the debug list
      · *test:* type a query on device, see ranked results
- [ ] **4.3** Flash-Lite query parsing → type and date filters (rule #6)
      · *test:* *"screenshot from April"* filters by both type and month
      · *test:* *"that link from WhatsApp"* also finds photos whose caption carried a link
        (filter on `type = LINK OR hasLink`)
      · parsing must also extract a **source app** filter, matched against
        `sourceAppLabel` — provenance is a filter, never mixed into embedded text
- [ ] **4.4** Hybrid search via `$rankFusion` (rule #5)
      · *test:* a proper-noun query ("Qualcomm") beats the pure-vector baseline
- [ ] **4.5** Tap a result → open the original artifact
      · *test:* tap a saved screenshot → opens in a viewer
      · *test:* tap a saved PDF → opens in a PDF viewer (originals are app-private, so
        this needs a FileProvider to hand another app read access)

### Phase 5 — Seeding

- [ ] **5.1** Bulk importer: screenshots folder
- [ ] **5.2** Bulk importer: Chrome bookmarks export, downloaded PDFs
      · *test:* 500+ real items indexed, then honestly assess retrieval quality

**Why phase 5 is not optional:** a personal memory search engine is worthless until it holds a few hundred items. The likely failure mode for this project is building it, using it a week, having 14 items, finding search unimpressive, and losing motivation. This kills most apps in the category. Seed 500+ real items so retrieval is tuned against a corpus that actually exercises it. Pull it earlier than phase 5 if motivation dips.

## Also in V1, outside the numbered steps

- **Auth: no user accounts.** One device, a long-lived token in the Android keystore, backend validates it. Google Sign-In is an afternoon's work whenever it is actually needed — do not spend V1 on user management for a one-user app.
- Express stays thin: one ingest endpoint, one search endpoint.
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

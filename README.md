# Breadcrumb

**A search engine for your own memory.**

Save anything in one tap. Find it later by describing it the way you actually remember it.

> 🚧 Early development. Nothing is usable yet.

---

## The problem

We save information everywhere and find it nowhere.

Screenshots, links, PDFs, photos, voice notes, recommendations — they end up scattered across a dozen apps. Later you remember *what* you saved and roughly the context around it, but not *where* you put it. So you search your gallery, then your bookmarks, then WhatsApp, and eventually give up.

Research on re-finding information on the web bears this out: people fall back on bookmarking (~80%), searching again from scratch (~54%), re-typing URLs (~51%), even emailing things to themselves (~36%) — and no single method covers everything they need. Existing tools each solve a slice. None unify personal information retrieval.

## The idea

Breadcrumb is two flows and nothing else:

> **Save → Forget about it → Search → Find**

**Saving is frictionless.** Share sheet or a Quick Settings tile. No folders, no tags, no titles, no organizing. You are never asked to do work at save time.

**Retrieval is the product.** One search bar. You type what you remember:

- *"that internship screenshot from April"*
- *"the restaurant somebody recommended"*
- *"that guy from the hackathon who worked at Qualcomm"*
- *"the PDF my professor sent"*

It finds the thing even when your words don't match the original content — because it understands meaning and context, not just keywords.

## Reference memory, not action memory

The distinction the whole product rests on:

- **Action memory** — *"I need to **do** something later."* Pay a bill, submit an assignment, attend a meeting. Calendars, reminders and task managers already do this well. Breadcrumb doesn't compete here.
- **Reference memory** — *"I need to **remember** something later."* A restaurant rec, a useful article, an internship posting, a person you met at an event. Nothing needs to happen with these. You just want to find them again.

Breadcrumb is only the second one.

It is **not** a notes app, a bookmark manager, a task manager, or a second brain. It should feel like a personal search engine, not a digital notebook.

## How it works

**Saving**

```
share / tile  →  saved locally, instantly  →  background processing
                                                      ↓
                                     on-device OCR + one Gemini Flash pass
                                     (text, description, entities, dates)
                                                      ↓
                                          embedding → Atlas Vector Search
```

Saving never waits on the network. The original file never permanently leaves your phone — only extracted text and embeddings go to the cloud.

**Retrieving**

```
"that internship screenshot from April"
              ↓
    query parsing → { semantic query, type: image, date: April }
              ↓
    hybrid search (vector + keyword, fused by RRF, date-filtered)
              ↓
    ranked memories → tap to open the original
```

Hybrid rather than pure semantic search, because the queries people actually type are full of proper nouns — "Qualcomm" needs keyword matching while the rest of the sentence needs meaning.

## Stack

| | |
|---|---|
| **Mobile** | Native Android — Kotlin, Jetpack Compose, Room, WorkManager |
| **Backend** | Node + Express + TypeScript |
| **Database** | MongoDB Atlas + Atlas Vector Search (`$rankFusion` hybrid) |
| **AI** | Gemini Flash (ingest), Flash-Lite (query parsing), `gemini-embedding-001` |
| **On-device** | ML Kit Text Recognition |

Native Android rather than React Native: every capture surface is an Android platform surface, and every save is a cold start — so a native capture path is the difference between saving feeling free and saving feeling like a wait.

## Layout

```
breadcrumb/
  android/    Android app — open this folder in Android Studio
  server/     Express API
```

## Status

| | |
|---|---|
| **V1** | Share sheet + QS tile capture · OCR & Gemini ingest · hybrid search · bulk importer |
| **V2** | Voice notes · browser extension · real auth · cross-device sync |

See [CLAUDE.md](CLAUDE.md) for architecture decisions, rationale, and the full V1 boundary.

## Why "Breadcrumb"

From leaving breadcrumbs while walking through a forest. You don't know when you'll want to return somewhere — you just leave a trail as you go. You drop small pieces of information as you move through your digital life, then follow the trail back when you need something.

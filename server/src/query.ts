import { GoogleGenAI } from "@google/genai";
import type { Db } from "mongodb";
import { pinnedModel } from "./gemini.ts";
import { type MemoryType, memories } from "./memories.ts";

/**
 * Flash Lite, and a different one from ingest's on purpose: the free tier
 * counts requests per model per day, so parsing searches on 3.1 while ingest
 * runs on 3.5 gives each its own 500. Turning a short phrase into a few
 * filters needs nothing larger.
 */
export const DEFAULT_QUERY_MODEL = "gemini-3.1-flash-lite";

export const QUERY_MODEL = pinnedModel(process.env.GEMINI_QUERY_MODEL, DEFAULT_QUERY_MODEL);

/** The kinds a search can name. AUDIO waits for voice notes (post-V1). */
export const SEARCHABLE_TYPES: MemoryType[] = ["TEXT", "LINK", "IMAGE", "PDF"];

/**
 * What a search phrase asks for, once taken apart (architecture rule 6).
 * "that internship screenshot from April" is the words "internship", the
 * type IMAGE, and April -- and "from April" searched as words would only be
 * noise in the embedding.
 */
export interface Interpretation {
  /** What is left to search by meaning and words. Empty when the phrase was all filter. */
  query: string;
  /** Any of these; empty means any kind. LINK also matches anything carrying a link. */
  types: MemoryType[];
  /** Inclusive, YYYY-MM-DD, on the searcher's calendar. Either may be open. */
  from: string | null;
  to: string | null;
  /** One of the source labels memories actually carry. */
  sourceApp: string | null;
}

export interface QueryContext {
  /** YYYY-MM-DD, so "April" and "last week" can be placed. */
  today: string;
  /** The source labels memories actually carry; a parsed app must be one of them. */
  sourceApps: string[];
}

export type QueryParser = (query: string, context: QueryContext) => Promise<Interpretation>;

const INSTRUCTION = `You read what someone typed into the search box of a personal memory app, and split it
into filters and the words left to search with. They saved screenshots, photos, links, PDFs and
notes, and are describing one of them from memory.

Return:
- query: the words that describe the thing itself -- its subject, names, what it says. Remove
  only the words you turned into a filter below, and filler such as "that", "the one", "I saved".
  Keep every other word as written, names included. Empty if nothing is left.
- types: only when they name a kind of item.
  IMAGE: screenshot, photo, picture, pic, image.
  LINK: link, URL, website, web page, article, video, reel, post.
  PDF: pdf, document.
  TEXT: note, text snippet.
  A word that names no one kind -- "message", "thing", "stuff" -- sets nothing.
- from, to: only when they say when, as inclusive dates, YYYY-MM-DD. A month or season with no
  year means the most recent one that has begun by today. "last week" is the previous Monday to
  Sunday. Vague words -- "recently", "a while ago", "old" -- set nothing.
- sourceApp: only when they say which app it came from, and then only a name from the list you
  are given, matched loosely ("insta" is Instagram). Empty if they name none, or one not listed.

Never add a filter the words do not ask for. Leave a field empty rather than guess.`;

function schemaFor(context: QueryContext) {
  return {
    type: "object",
    properties: {
      query: { type: "string", description: "The words left to search with. May be empty." },
      types: { type: "array", items: { type: "string", enum: SEARCHABLE_TYPES } },
      from: { type: "string", description: "YYYY-MM-DD, or empty" },
      to: { type: "string", description: "YYYY-MM-DD, or empty" },
      sourceApp: { type: "string", enum: [...context.sourceApps, ""] },
    },
    required: ["query", "types", "from", "to", "sourceApp"],
    additionalProperties: false,
  };
}

const WEEKDAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

function describe(query: string, context: QueryContext): string {
  const weekday = WEEKDAYS[localDay(context.today).getDay()];
  return [
    `Today: ${weekday}, ${context.today}`,
    `Apps things were saved from: ${context.sourceApps.join(", ") || "(none recorded)"}`,
    `Search: ${query}`,
  ].join("\n");
}

/**
 * One call, and no retries: someone is waiting on a search, and a parse that
 * fails costs only its filters -- the search runs on the raw phrase instead
 * (see [understanding]).
 */
export function geminiQueryParser(apiKey: string): QueryParser {
  const ai = new GoogleGenAI({ apiKey });

  return async (query, context) => {
    const response = await ai.models.generateContent({
      model: QUERY_MODEL,
      contents: describe(query, context),
      config: {
        systemInstruction: INSTRUCTION,
        temperature: 0,
        responseMimeType: "application/json",
        responseJsonSchema: schemaFor(context),
      },
    });
    const text = response.text;
    if (!text) throw new Error("Gemini returned no text");
    return parseInterpretation(text, context);
  };
}

/**
 * The schema makes the shape likely, not certain, and a filter the searcher
 * never asked for hides the very thing they are looking for. So anything
 * malformed is dropped rather than trusted: an unknown type, a date that is
 * not a date, an app no memory came from.
 */
export function parseInterpretation(text: string, context: QueryContext): Interpretation {
  const raw: unknown = JSON.parse(text);
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new Error("Gemini returned a non-object");
  const value = raw as Record<string, unknown>;

  const query = typeof value["query"] === "string" ? value["query"].trim().replace(/\s+/g, " ") : "";
  const types = Array.isArray(value["types"])
    ? [...new Set(value["types"].filter((type): type is MemoryType => SEARCHABLE_TYPES.includes(type as MemoryType)))]
    : [];

  let from = asDay(value["from"]);
  let to = asDay(value["to"]);
  if (from && to && from > to) [from, to] = [to, from];

  const app = value["sourceApp"];
  const sourceApp = typeof app === "string" && context.sourceApps.includes(app) ? app : null;

  return { query, types, from, to, sourceApp };
}

/** A real calendar day as YYYY-MM-DD, or null. "2026-02-30" is not one. */
function asDay(value: unknown): string | null {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const day = localDay(value);
  return formatDay(day) === value ? value : null;
}

/** Whether the interpretation filters anything at all. */
export function hasFilters(interpretation: Interpretation): boolean {
  return (
    interpretation.types.length > 0 ||
    interpretation.from !== null ||
    interpretation.to !== null ||
    interpretation.sourceApp !== null
  );
}

/**
 * Local midnight of a YYYY-MM-DD day. Dates are placed on the server's own
 * calendar, which in development is the phone owner's. A deployed server
 * would need the phone's time zone instead.
 */
export function localDay(day: string): Date {
  const [year, month, date] = day.split("-").map(Number) as [number, number, number];
  return new Date(year, month - 1, date);
}

/** The local midnight after a YYYY-MM-DD day: the exclusive end of a range that includes it. */
export function dayAfter(day: string): Date {
  const start = localDay(day);
  return new Date(start.getFullYear(), start.getMonth(), start.getDate() + 1);
}

export function formatDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Every source label memories carry, for the parser to choose from. */
export async function knownSourceApps(database: Db): Promise<string[]> {
  const labels = await memories(database).distinct("sourceAppLabel", { sourceAppLabel: { $type: "string" } });
  return (labels as string[]).filter((label) => label.trim().length > 0).sort();
}

export interface Understanding {
  /** null when the parse failed; the search then runs on the raw phrase, unfiltered. */
  interpretation: Interpretation | null;
  error?: string;
}

/** Enough for a day of searching; the oldest go first. */
const MAX_REMEMBERED = 500;

/** New apps appear rarely, and a search should not pay a query for them each time. */
const LABELS_FOR_MS = 5 * 60_000;

/**
 * The parser, with a memory: the same phrase on the same day, against the same
 * apps, is parsed once. A search box asks the same thing again and again --
 * returning to a screen, fixing a typo and undoing it -- and on a free tier
 * the second answer should cost nothing. Failures are not remembered, so the
 * next try asks again.
 */
export function understanding(parse: QueryParser, now: () => Date = () => new Date()) {
  const remembered = new Map<string, Interpretation>();
  let labels: { at: number; value: string[] } | undefined;

  return async (database: Db, query: string): Promise<Understanding> => {
    const at = now();
    if (!labels || at.getTime() - labels.at > LABELS_FOR_MS) {
      labels = { at: at.getTime(), value: await knownSourceApps(database) };
    }
    const context: QueryContext = { today: formatDay(at), sourceApps: labels.value };
    const key = JSON.stringify([context.today, context.sourceApps, query]);

    const known = remembered.get(key);
    if (known) return { interpretation: known };

    try {
      const interpretation = await parse(query, context);
      remembered.set(key, interpretation);
      if (remembered.size > MAX_REMEMBERED) remembered.delete(remembered.keys().next().value as string);
      return { interpretation };
    } catch (error) {
      return { interpretation: null, error: error instanceof Error ? error.message : String(error) };
    }
  };
}

export type Understand = ReturnType<typeof understanding>;

/**
 * Where a memory came from, in the two senses a person means by it. "That
 * link from Instagram" is a link shared from the Instagram app -- or an
 * Instagram link that arrived any other way: sent on WhatsApp, copied, typed
 * in. So a source names an app, a site, or both, and a source filter matches
 * either (see `searchFilter` in search.ts).
 *
 * Sites are kept as registrable domains ("instagram.com", not
 * "www.instagram.com"), and named as the app or service a person would say.
 */

/** Short and alternative domains that belong to a service, and the name people use for it. */
const NAMED: Record<string, string> = {
  "instagram.com": "Instagram",
  "instagr.am": "Instagram",
  "youtube.com": "YouTube",
  "youtu.be": "YouTube",
  "x.com": "X",
  "twitter.com": "X",
  "t.co": "X",
  "reddit.com": "Reddit",
  "redd.it": "Reddit",
  "linkedin.com": "LinkedIn",
  "lnkd.in": "LinkedIn",
  "facebook.com": "Facebook",
  "fb.com": "Facebook",
  "fb.watch": "Facebook",
  "whatsapp.com": "WhatsApp",
  "wa.me": "WhatsApp",
  "spotify.com": "Spotify",
  "spotify.link": "Spotify",
  "github.com": "GitHub",
  "amzn.to": "Amazon",
  "amzn.in": "Amazon",
  "threads.net": "Threads",
  "tiktok.com": "TikTok",
  "pinterest.com": "Pinterest",
  "pin.it": "Pinterest",
  "t.me": "Telegram",
  "telegram.me": "Telegram",
  "discord.gg": "Discord",
  "discord.com": "Discord",
  "substack.com": "Substack",
  "medium.com": "Medium",
};

/** Second-level labels under a country code that are not themselves a name: "amazon.co.in". */
const GENERIC_SECOND_LEVEL = new Set(["co", "com", "org", "net", "ac", "gov", "edu", "or", "ne"]);

const URL_PATTERN = /\b(?:https?:\/\/|www\.)[^\s<>"']+/gi;

/** The registrable domain of a URL, lowercased: "https://m.youtube.com/x" -> "youtube.com". */
export function siteOf(url: string): string | null {
  const withScheme = /^https?:\/\//i.test(url) ? url : `https://${url}`;
  let host: string;
  try {
    host = new URL(withScheme).hostname.toLowerCase().replace(/\.$/, "");
  } catch {
    return null;
  }
  if (!host.includes(".") || /^[\d.]+$/.test(host) || host.startsWith("[")) return null;

  const labels = host.split(".");
  const last = labels.at(-1) ?? "";
  const second = labels.at(-2) ?? "";
  const keep = labels.length >= 3 && last.length === 2 && GENERIC_SECOND_LEVEL.has(second) ? 3 : 2;
  return labels.slice(-keep).join(".");
}

/**
 * Every site the links in a memory's shared text point to. Shared text only,
 * as for `hasLink`: a URL read off a screenshot is not a link someone sent.
 */
export function linkSites(text: string | null | undefined): string[] {
  if (!text) return [];
  const sites = [...text.matchAll(URL_PATTERN)].map((match) => siteOf(match[0]));
  return [...new Set(sites.filter((site): site is string => site !== null))];
}

/** What a person calls a site: "instagram.com" -> "Instagram", "nytimes.com" -> "Nytimes". */
export function siteName(site: string): string {
  const named = NAMED[site];
  if (named) return named;
  const first = site.split(".")[0] ?? site;
  return first.charAt(0).toUpperCase() + first.slice(1);
}

/** The sites among [sites] that go by [name], matched without regard to case. */
export function sitesNamed(name: string, sites: string[]): string[] {
  const wanted = name.toLowerCase();
  return sites.filter((site) => siteName(site).toLowerCase() === wanted);
}

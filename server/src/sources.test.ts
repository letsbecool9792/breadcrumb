import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { linkSites, siteName, siteOf, sitesNamed } from "./sources.ts";

describe("siteOf", () => {
  test("a URL's site is its registrable domain", () => {
    assert.equal(siteOf("https://www.instagram.com/reel/Cx1/?igsh=abc"), "instagram.com");
    assert.equal(siteOf("https://m.youtube.com/watch?v=1"), "youtube.com");
    assert.equal(siteOf("https://open.spotify.com/track/2"), "spotify.com");
    assert.equal(siteOf("www.reddit.com/r/bangalore"), "reddit.com");
  });

  test("a country's generic second level stays with the name", () => {
    assert.equal(siteOf("https://www.amazon.co.in/dp/B0"), "amazon.co.in");
    assert.equal(siteOf("https://bbc.co.uk/news"), "bbc.co.uk");
  });

  test("addresses that are not sites have none", () => {
    assert.equal(siteOf("http://192.168.1.1/admin"), null);
    assert.equal(siteOf("http://localhost:3000/health"), null);
    assert.equal(siteOf("https://"), null);
  });
});

describe("linkSites", () => {
  test("every site the shared text links to, once each", () => {
    const text = "reel https://www.instagram.com/reel/1 and https://instagram.com/p/2 plus youtu.be? https://youtu.be/x";

    assert.deepEqual(linkSites(text), ["instagram.com", "youtu.be"]);
  });

  test("no link, no sites", () => {
    assert.deepEqual(linkSites("just words"), []);
    assert.deepEqual(linkSites(null), []);
  });
});

describe("naming sites", () => {
  test("a service's short domains go by its own name", () => {
    assert.equal(siteName("instagram.com"), "Instagram");
    assert.equal(siteName("instagr.am"), "Instagram");
    assert.equal(siteName("youtu.be"), "YouTube");
    assert.equal(siteName("twitter.com"), "X");
  });

  test("any other site goes by its first label", () => {
    assert.equal(siteName("nytimes.com"), "Nytimes");
    assert.equal(siteName("amazon.co.in"), "Amazon");
  });

  test("the sites that go by a name, whatever its case", () => {
    const sites = ["instagram.com", "youtube.com", "youtu.be", "example.com"];

    assert.deepEqual(sitesNamed("YouTube", sites), ["youtube.com", "youtu.be"]);
    assert.deepEqual(sitesNamed("instagram", sites), ["instagram.com"]);
    assert.deepEqual(sitesNamed("Chrome", sites), []);
  });
});

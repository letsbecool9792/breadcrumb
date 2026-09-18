package com.lbc.breadcrumb.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetaTest {

    @Test
    fun readsWhatAPageGivesLinkPreviews() {
        val meta = PageMeta.parse(
            """
            <html><head>
              <title>Ignored when og:title is there</title>
              <meta property="og:title" content="The best biryani in Bengaluru">
              <meta property="og:description" content="Twelve places, ranked by a very hungry critic.">
              <meta property="og:site_name" content="Condé Nast Traveller">
            </head><body>…</body></html>
            """.trimIndent()
        )

        assertEquals("The best biryani in Bengaluru", meta.title)
        assertEquals("Twelve places, ranked by a very hungry critic.", meta.description)
        assertEquals("Condé Nast Traveller", meta.siteName)
    }

    @Test
    fun attributesInAnyOrderAndAnyQuotes() {
        val meta = PageMeta.parse(
            """<meta content='A talk on B-trees' property=og:title><META NAME="Description" CONTENT="From Strange Loop 2019">"""
        )

        assertEquals("A talk on B-trees", meta.title)
        assertEquals("From Strange Loop 2019", meta.description)
    }

    @Test
    fun fallsBackToTwitterTagsThenTheTitleAndPlainDescription() {
        val twitter = PageMeta.parse("""<meta name="twitter:title" content="Thread on rent control"><title>x</title>""")
        val plain = PageMeta.parse("<title>\n  Rust  1.80   released\n</title><meta name=\"description\" content=\"LazyLock is stable\">")

        assertEquals("Thread on rent control", twitter.title)
        assertEquals("Rust 1.80 released", plain.title)
        assertEquals("LazyLock is stable", plain.description)
    }

    @Test
    fun theFirstOfARepeatedTagWins() {
        val meta = PageMeta.parse("""<meta property="og:title" content="First"><meta property="og:title" content="Second">""")

        assertEquals("First", meta.title)
    }

    @Test
    fun entitiesAreDecoded() {
        val meta = PageMeta.parse(
            """<meta property="og:title" content="Tom &amp; Jerry&#39;s &#x201C;best&#x201D; &hellip; &eacute;">"""
        )

        // a named entity it does not know is left as written
        assertEquals("Tom & Jerry's “best” … &eacute;", meta.title)
    }

    @Test
    fun aBotCheckOrLoginWallIsNotThePage() {
        assertTrue(PageMeta.parse("<title>Just a moment...</title>").isEmpty)
        assertTrue(PageMeta.parse("<title>Log in | Instagram</title><meta name=\"description\" content=\"Welcome back\">").isEmpty)
        // a real title that merely contains such words stands
        assertEquals("How to sign in faster with passkeys", PageMeta.parse("<title>How to sign in faster with passkeys</title>").title)
    }

    @Test
    fun aPageWithNothingInItsHeadIsEmpty() {
        assertTrue(PageMeta.parse("<html><body><p>hello</p></body></html>").isEmpty)
        assertTrue(PageMeta.parse("<title>   </title>").isEmpty)
    }

    @Test
    fun aVeryLongDescriptionIsCutAtAWord() {
        val meta = PageMeta.parse("""<meta name="description" content="${"word ".repeat(400)}">""")

        assertTrue(meta.description!!.length <= 1_001)
        assertTrue(meta.description!!.endsWith("word…"))
    }

    @Test
    fun theReadTextIsTheDescriptionAndASiteNameNotAlreadySaid() {
        val named = PageMeta("Chicken biryani", "Slow-cooked dum biryani.", "NYT Cooking")
        val repeated = PageMeta("Chicken biryani | NYT Cooking", "Slow-cooked dum biryani.", "NYT Cooking")

        assertEquals("Slow-cooked dum biryani.\nNYT Cooking", named.readText())
        assertEquals("Slow-cooked dum biryani.", repeated.readText())
        assertEquals("", PageMeta("Title only", null, null).readText())
    }

    @Test
    fun onlyPagesOnTheOpenInternetAreRead() {
        assertTrue(PageRules.worthReading("https://www.example.com/article?id=1"))
        assertTrue(PageRules.worthReading("http://blog.example.co.in"))
        assertFalse(PageRules.worthReading("http://localhost:3000/health"))
        assertFalse(PageRules.worthReading("http://192.168.1.1/admin"))
        assertFalse(PageRules.worthReading("https://printer.local/status"))
        assertFalse(PageRules.worthReading("ftp://example.com/file"))
        assertFalse(PageRules.worthReading("https://intranet/wiki"))
    }

    @Test
    fun plainHttpGoesAsHttps() {
        assertEquals("https://example.com/a", PageRules.secure("http://example.com/a"))
        assertEquals("https://example.com/a", PageRules.secure("https://example.com/a"))
    }

    @Test
    fun cleanReturnsNullForNothing() {
        assertNull(PageMeta.clean(null, 10))
        assertNull(PageMeta.clean(" &nbsp; ", 10))
    }
}

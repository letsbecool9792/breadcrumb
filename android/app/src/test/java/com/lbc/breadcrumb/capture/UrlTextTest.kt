package com.lbc.breadcrumb.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTextTest {

    @Test
    fun `urls with a scheme are found anywhere in the text`() {
        assertTrue(UrlText.containsUrl("https://github.com/square/okhttp"))
        assertTrue(UrlText.containsUrl("look at http://example.com later"))
        assertTrue(UrlText.containsUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun `bare www addresses are found`() {
        assertTrue(UrlText.containsUrl("it's on www.example.com somewhere"))
    }

    @Test
    fun `www inside a word is not a link`() {
        assertFalse(UrlText.containsUrl("awww.gif"))
        assertFalse(UrlText.containsUrl("that was awww. so cute"))
    }

    @Test
    fun `the host of the first url names the site`() {
        assertEquals("github.com", UrlText.firstHost("https://github.com/square/okhttp"))
        assertEquals("github.com", UrlText.firstHost("https://www.github.com/square"))
        assertEquals("developer.android.com", UrlText.firstHost("see https://developer.android.com/x?y=1#z"))
        assertEquals("example.com", UrlText.firstHost("it's on www.example.com."))
        assertEquals("localhost.dev", UrlText.firstHost("http://localhost.dev:8080/path"))
        assertEquals("first.com", UrlText.firstHost("https://first.com and https://second.com"))
    }

    @Test
    fun `the first url comes out ready to open`() {
        assertEquals("https://github.com/square/okhttp", UrlText.firstUrl("see https://github.com/square/okhttp."))
        assertEquals("https://first.com", UrlText.firstUrl("https://first.com and https://second.com"))
        assertEquals("https://www.example.com/menu", UrlText.firstUrl("(www.example.com/menu)"))
        assertEquals("http://example.com/a?b=1", UrlText.firstUrl("http://example.com/a?b=1"))
        assertNull(UrlText.firstUrl("no links here"))
        assertNull(UrlText.firstUrl(null))
    }

    @Test
    fun `text without a url has no host`() {
        assertNull(UrlText.firstHost("no links here"))
        assertNull(UrlText.firstHost(null))
    }

    @Test
    fun `plain text and nothing are not links`() {
        assertFalse(UrlText.containsUrl("Naru's in Indiranagar, book two weeks ahead"))
        assertFalse(UrlText.containsUrl("http is a protocol"))
        assertFalse(UrlText.containsUrl(""))
        assertFalse(UrlText.containsUrl(null))
    }
}

package com.lbc.breadcrumb.capture

import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests -- ShareParser has no Android dependencies, so these run in
 * milliseconds without a device: `./gradlew testDebugUnitTest`.
 */
class ShareParserTest {

    @Test
    fun `a bare url is a LINK`() {
        val memory = ShareParser.parse("https://github.com/square/okhttp")!!

        assertEquals(MemoryType.LINK, memory.type)
        assertTrue(memory.hasLink)
        assertEquals("https://github.com/square/okhttp", memory.rawText)
    }

    @Test
    fun `a www url with no scheme is still a LINK`() {
        assertEquals(MemoryType.LINK, ShareParser.parse("www.example.com/thing")!!.type)
    }

    @Test
    fun `prose containing a url is a LINK and keeps the whole message`() {
        val shared = "have a look at https://developer.android.com when you get a sec"
        val memory = ShareParser.parse(shared)!!

        assertEquals(MemoryType.LINK, memory.type)
        assertTrue(memory.hasLink)
        // calling it a link loses nothing the sender wrote around it
        assertEquals(shared, memory.rawText)
    }

    @Test
    fun `text with several urls is a LINK`() {
        val shared = "https://a.example.com https://b.example.com"

        assertEquals(MemoryType.LINK, ShareParser.parse(shared)!!.type)
    }

    @Test
    fun `plain text is TEXT with no link`() {
        val memory = ShareParser.parse("Naru's in Indiranagar, book two weeks ahead")!!

        assertEquals(MemoryType.TEXT, memory.type)
        assertFalse(memory.hasLink)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val memory = ShareParser.parse("\n  https://example.com  \n")!!

        assertEquals(MemoryType.LINK, memory.type)
        assertEquals("https://example.com", memory.rawText)
    }

    @Test
    fun `empty and null bodies yield nothing`() {
        assertNull(ShareParser.parse(null))
        assertNull(ShareParser.parse(""))
        assertNull(ShareParser.parse("   \n  "))
    }

    @Test
    fun `a page title arrives as the subject and becomes the title`() {
        // How Chrome shares: subject is the page title, text is the URL.
        val memory = ShareParser.parse(
            text = "https://developer.android.com/develop/ui/compose/performance",
            subject = "Compose performance  |  Android Developers",
        )!!

        assertEquals("Compose performance  |  Android Developers", memory.title)
        assertEquals(MemoryType.LINK, memory.type)
    }

    @Test
    fun `a subject that merely echoes the body is dropped`() {
        // Several messaging apps copy the text into the subject; keeping it
        // would show the same string twice in the list.
        val memory = ShareParser.parse(text = "same thing", subject = "same thing")!!

        assertNull(memory.title)
    }

    @Test
    fun `a blank subject is dropped`() {
        assertNull(ShareParser.parse(text = "something", subject = "   ")!!.title)
    }

    @Test
    fun `the source app and its name are recorded`() {
        val memory = ShareParser.parse(
            text = "hello",
            sourceApp = "com.whatsapp",
            sourceAppLabel = "WhatsApp",
        )!!

        assertEquals("com.whatsapp", memory.sourceApp)
        assertEquals("WhatsApp", memory.sourceAppLabel)
    }

    @Test
    fun `a share starts life pending and unsynced`() {
        val memory = ShareParser.parse("hello")!!

        assertEquals(SyncState.PENDING, memory.syncState)
        assertNull(memory.remoteId)
    }

    @Test
    fun `capturedAt and updatedAt use the supplied clock`() {
        val memory = ShareParser.parse("hello", now = 1_700_000_000_000)!!

        assertEquals(1_700_000_000_000, memory.capturedAt)
        assertEquals(1_700_000_000_000, memory.updatedAt)
    }
}

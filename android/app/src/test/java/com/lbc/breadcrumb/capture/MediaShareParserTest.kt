package com.lbc.breadcrumb.capture

import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaShareParserTest {

    private fun plan(
        mime: String?,
        text: String? = null,
        subject: String? = null,
        displayName: String? = null,
        createdAt: Long? = null,
        isOnlyItem: Boolean = true,
    ) = MediaShareParser.plan(
        item = IncomingMedia(mime = mime, displayName = displayName, createdAt = createdAt),
        sharedText = text,
        subject = subject,
        isOnlyItem = isOnlyItem,
        newId = { "fixed-id" },
    )

    // --- type ---------------------------------------------------------------

    @Test
    fun `images and pdfs are accepted, everything else is not`() {
        assertEquals(MemoryType.IMAGE, MediaShareParser.typeFor("image/jpeg"))
        assertEquals(MemoryType.IMAGE, MediaShareParser.typeFor("image/png"))
        assertEquals(MemoryType.IMAGE, MediaShareParser.typeFor("IMAGE/WEBP"))
        assertEquals(MemoryType.PDF, MediaShareParser.typeFor("application/pdf"))
        assertNull(MediaShareParser.typeFor("video/mp4"))
        assertNull(MediaShareParser.typeFor("application/zip"))
        assertNull(MediaShareParser.typeFor(null))
    }

    @Test
    fun `an unsupported file yields no draft`() {
        assertNull(plan(mime = "video/mp4"))
    }

    @Test
    fun `extensions follow the mime type`() {
        assertEquals("jpg", MediaShareParser.extensionFor("image/jpeg"))
        assertEquals("png", MediaShareParser.extensionFor("image/png"))
        assertEquals("heic", MediaShareParser.extensionFor("image/heic"))
        assertEquals("pdf", MediaShareParser.extensionFor("application/pdf"))
        assertEquals("bin", MediaShareParser.extensionFor("image/x-something"))
        assertEquals("bin", MediaShareParser.extensionFor(null))
    }

    @Test
    fun `only content uris are acceptable sources`() {
        assertTrue(MediaShareParser.isAcceptableScheme("content"))
        assertFalse(MediaShareParser.isAcceptableScheme("file"))
        assertFalse(MediaShareParser.isAcceptableScheme("http"))
        assertFalse(MediaShareParser.isAcceptableScheme(null))
    }

    // --- captions -----------------------------------------------------------

    @Test
    fun `a photo whose caption carries a url is still an IMAGE`() {
        // Verified on-device: a WhatsApp photo + text + link share. The file is
        // the memory; the caption, URL included, stays searchable as rawText.
        val draft = plan(
            mime = "image/jpeg",
            text = "this is the one I meant https://github.com/square/okhttp",
            subject = "Photo from Suparno Saha",
        )!!

        assertEquals(MemoryType.IMAGE, draft.type)
        assertTrue("the link must still be recorded", draft.hasLink)
        assertEquals("this is the one I meant https://github.com/square/okhttp", draft.rawText)
        assertEquals("jpg", draft.extension)
        assertNull(draft.title)
    }

    @Test
    fun `an image with a plain caption stays an IMAGE, keeps the caption, has no link`() {
        val draft = plan(mime = "image/jpeg", text = "the menu board at Naru")!!

        assertEquals(MemoryType.IMAGE, draft.type)
        assertFalse(draft.hasLink)
        assertEquals("the menu board at Naru", draft.rawText)
    }

    @Test
    fun `an image with no text is an IMAGE with no text`() {
        val draft = plan(mime = "image/jpeg")!!

        assertEquals(MemoryType.IMAGE, draft.type)
        assertFalse(draft.hasLink)
        assertNull(draft.rawText)
    }

    @Test
    fun `a pdf carrying a url stays a PDF and records the link`() {
        val draft = plan(mime = "application/pdf", text = "see https://x.com")!!

        assertEquals(MemoryType.PDF, draft.type)
        assertTrue(draft.hasLink)
    }

    @Test
    fun `across a multi-file share, text is attached to none of them`() {
        val draft = plan(mime = "image/jpeg", text = "https://example.com", isOnlyItem = false)!!

        assertNull(draft.rawText)
        assertFalse("a link the file was not shared with is not its link", draft.hasLink)
        assertEquals(MemoryType.IMAGE, draft.type)
    }

    // --- titles ------------------------------------------------------------

    @Test
    fun `whatsapp envelope subjects are not titles`() {
        assertNull(MediaShareParser.usefulSubject("Photo from Suparno Saha"))
        assertNull(MediaShareParser.usefulSubject("Video from Mom"))
        assertNull(MediaShareParser.usefulSubject("Document from Prof. Rao"))
        assertNull(MediaShareParser.usefulSubject("photos from the trip group"))
    }

    @Test
    fun `a real subject is kept`() {
        assertEquals("Q3 planning deck", MediaShareParser.usefulSubject("  Q3 planning deck "))
        // "from" alone is not enough to look like an envelope
        assertEquals("Notes from the talk", MediaShareParser.usefulSubject("Notes from the talk"))
    }

    @Test
    fun `a subject that just repeats the filename is dropped`() {
        assertNull(MediaShareParser.usefulSubject("scan.pdf", displayName = "scan.pdf"))
        assertNull(MediaShareParser.usefulSubject(" ", displayName = null))
    }

    @Test
    fun `a pdf with no useful subject is titled by its filename`() {
        val draft = plan(mime = "application/pdf", displayName = "ds-week6-notes.pdf")!!

        assertEquals("ds-week6-notes", draft.title)
    }

    @Test
    fun `a pdf prefers a useful subject over its filename`() {
        val draft = plan(
            mime = "application/pdf",
            subject = "Distributed Systems, week 6",
            displayName = "ds-week6-notes.pdf",
        )!!

        assertEquals("Distributed Systems, week 6", draft.title)
    }

    @Test
    fun `an image is never titled by its filename`() {
        assertNull(plan(mime = "image/jpeg", displayName = "IMG_20260418_101233.jpg")!!.title)
    }

    // --- memory ------------------------------------------------------------

    @Test
    fun `the draft becomes a pending memory pointing at its stored file`() {
        val memory = plan(mime = "image/png", text = "https://x.com", createdAt = 1_690_000_000_000)!!
            .toMemory(localUri = "file:///x/fixed-id.png", sourceApp = "com.whatsapp", now = 1_700_000_000_000)

        assertEquals("fixed-id", memory.id)
        assertTrue(memory.hasLink)
        assertEquals("file:///x/fixed-id.png", memory.localUri)
        assertEquals("com.whatsapp", memory.sourceApp)
        assertEquals(1_700_000_000_000, memory.capturedAt)
        assertEquals(1_690_000_000_000, memory.contentCreatedAt)
        assertEquals(SyncState.PENDING, memory.syncState)
    }
}

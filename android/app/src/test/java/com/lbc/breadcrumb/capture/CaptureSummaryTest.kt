package com.lbc.breadcrumb.capture

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSummaryTest {

    private fun memory(
        type: MemoryType,
        hasLink: Boolean = false,
        title: String? = null,
        rawText: String? = null,
        label: String? = null,
        pkg: String? = null,
    ) = Memory(type = type, hasLink = hasLink, title = title, rawText = rawText, sourceAppLabel = label, sourceApp = pkg)

    // --- subtitle ----------------------------------------------------------

    @Test
    fun `a single save reads like the design -- kind, then source`() {
        assertEquals(
            "link · from chrome",
            CaptureSummary.subtitle(listOf(memory(MemoryType.LINK, hasLink = true, label = "Chrome"))),
        )
    }

    @Test
    fun `every chip a memory carries is named`() {
        assertEquals(
            "image + link · from whatsapp",
            CaptureSummary.subtitle(listOf(memory(MemoryType.IMAGE, hasLink = true, label = "WhatsApp"))),
        )
    }

    @Test
    fun `no source means no from`() {
        // clipboard saves record no source app
        assertEquals("text", CaptureSummary.subtitle(listOf(memory(MemoryType.TEXT))))
    }

    @Test
    fun `the package stands in when no name was resolved`() {
        assertEquals(
            "text · from com.example.app",
            CaptureSummary.subtitle(listOf(memory(MemoryType.TEXT, pkg = "com.example.app"))),
        )
    }

    @Test
    fun `several files of one kind are counted by kind`() {
        val shots = List(3) { memory(MemoryType.IMAGE, label = "Photos") }

        assertEquals("3 images · from photos", CaptureSummary.subtitle(shots))
    }

    @Test
    fun `mixed kinds are items, and disagreeing sources name none`() {
        val mixed = listOf(memory(MemoryType.IMAGE, label = "Files"), memory(MemoryType.PDF, label = "Drive"))

        assertEquals("2 items", CaptureSummary.subtitle(mixed))
    }

    @Test
    fun `nothing saved has no subtitle or preview`() {
        assertNull(CaptureSummary.subtitle(emptyList()))
        assertNull(CaptureSummary.previewFor(emptyList()))
    }

    // --- preview shape -------------------------------------------------------

    @Test
    fun `one photo is shown large, with its caption`() {
        val photo = memory(MemoryType.IMAGE, rawText = "  the menu board at Naru  ")
        val preview = CaptureSummary.previewFor(listOf(photo))

        assertEquals(CapturePreview.Photo(photo, caption = "the menu board at Naru"), preview)
    }

    @Test
    fun `a photo with no caption has none`() {
        val preview = CaptureSummary.previewFor(listOf(memory(MemoryType.IMAGE))) as CapturePreview.Photo

        assertNull(preview.caption)
    }

    @Test
    fun `several files become tiles, three shown and the rest counted`() {
        val five = List(5) { memory(MemoryType.IMAGE) }
        val preview = CaptureSummary.previewFor(five) as CapturePreview.Files

        assertEquals(3, preview.visible.size)
        assertEquals(2, preview.overflow)
    }

    @Test
    fun `three or fewer files have no overflow`() {
        val two = CaptureSummary.previewFor(List(2) { memory(MemoryType.IMAGE) }) as CapturePreview.Files
        val three = CaptureSummary.previewFor(List(3) { memory(MemoryType.PDF) }) as CapturePreview.Files

        assertEquals(0, two.overflow)
        assertEquals(0, three.overflow)
    }

    @Test
    fun `a pdf is a document titled by its title`() {
        val pdf = memory(MemoryType.PDF, title = "ds-week6-notes")
        val preview = CaptureSummary.previewFor(listOf(pdf)) as CapturePreview.Document

        assertEquals("ds-week6-notes", preview.title)
    }

    @Test
    fun `a page with a title shows the title, and the url moves to the detail line`() {
        val page = memory(
            MemoryType.LINK,
            hasLink = true,
            title = "Compose performance",
            rawText = "https://developer.android.com/develop/ui/compose/performance",
        )

        assertEquals(
            CapturePreview.Link(
                host = "developer.android.com",
                headline = "Compose performance",
                detail = "https://developer.android.com/develop/ui/compose/performance",
            ),
            CaptureSummary.previewFor(listOf(page)),
        )
    }

    @Test
    fun `a link with no title leads with its text and has no detail line`() {
        val shared = memory(MemoryType.LINK, hasLink = true, rawText = "have a look https://github.com/square/okhttp")
        val preview = CaptureSummary.previewFor(listOf(shared)) as CapturePreview.Link

        assertEquals("github.com", preview.host)
        assertEquals("have a look https://github.com/square/okhttp", preview.headline)
        assertNull(preview.detail)
    }

    @Test
    fun `text is shown as itself`() {
        val note = memory(MemoryType.TEXT, rawText = "Naru's in Indiranagar\nbook two weeks ahead")

        assertEquals(
            CapturePreview.Note("Naru's in Indiranagar\nbook two weeks ahead"),
            CaptureSummary.previewFor(listOf(note)),
        )
    }

    @Test
    fun `a single saved file of any kind never becomes tiles`() {
        assertTrue(CaptureSummary.previewFor(listOf(memory(MemoryType.PDF))) is CapturePreview.Document)
        assertTrue(CaptureSummary.previewFor(listOf(memory(MemoryType.IMAGE))) is CapturePreview.Photo)
    }
}

package com.lbc.breadcrumb.data

import com.lbc.breadcrumb.capture.MediaShareParser
import com.lbc.breadcrumb.capture.IncomingMedia
import com.lbc.breadcrumb.capture.ShareParser
import com.lbc.breadcrumb.data.MemoryType.IMAGE
import com.lbc.breadcrumb.data.MemoryType.LINK
import com.lbc.breadcrumb.data.MemoryType.PDF
import com.lbc.breadcrumb.data.MemoryType.TEXT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chip table, end to end: from what was shared, through the capture
 * rules, to the chips a memory shows.
 */
class MemoryChipsTest {

    private fun photo(caption: String?) = MediaShareParser.plan(
        item = IncomingMedia(mime = "image/jpeg"),
        sharedText = caption,
        subject = null,
        isOnlyItem = true,
    )!!.toMemory(localUri = "file:///x.jpg", sourceApp = null, now = 0)

    @Test
    fun `photo plus text plus link shows IMAGE and LINK`() {
        assertEquals(listOf(IMAGE, LINK), photo("this one https://github.com/square/okhttp").chips)
    }

    @Test
    fun `photo plus link shows IMAGE and LINK`() {
        assertEquals(listOf(IMAGE, LINK), photo("https://github.com/square/okhttp").chips)
    }

    @Test
    fun `photo plus text shows IMAGE`() {
        assertEquals(listOf(IMAGE), photo("the menu board at Naru").chips)
    }

    @Test
    fun `photo alone shows IMAGE`() {
        assertEquals(listOf(IMAGE), photo(null).chips)
    }

    @Test
    fun `text plus link shows LINK`() {
        assertEquals(listOf(LINK), ShareParser.parse("have a look https://example.com")!!.chips)
    }

    @Test
    fun `link alone shows LINK`() {
        assertEquals(listOf(LINK), ShareParser.parse("https://example.com")!!.chips)
    }

    @Test
    fun `text alone shows TEXT`() {
        assertEquals(listOf(TEXT), ShareParser.parse("book two weeks ahead")!!.chips)
    }

    @Test
    fun `pdf with a link in its caption shows PDF and LINK`() {
        val pdf = MediaShareParser.plan(
            item = IncomingMedia(mime = "application/pdf"),
            sharedText = "slides are also at https://example.com",
            subject = null,
            isOnlyItem = true,
        )!!.toMemory(localUri = "file:///x.pdf", sourceApp = null, now = 0)

        assertEquals(listOf(PDF, LINK), pdf.chips)
    }

    @Test
    fun `a LINK never shows LINK twice`() {
        assertEquals(listOf(LINK), Memory(type = LINK, hasLink = true).chips)
    }
}

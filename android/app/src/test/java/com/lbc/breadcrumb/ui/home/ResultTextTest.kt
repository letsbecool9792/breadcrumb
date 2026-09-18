package com.lbc.breadcrumb.ui.home

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.Interpretation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ResultTextTest {

    private val today = LocalDate.of(2026, 9, 18)
    private val now = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour

    // --- where a memory has got to ---------------------------------------------

    @Test
    fun `a memory read and on the server says nothing`() {
        val done = Memory(type = MemoryType.IMAGE, localUri = "file:///o/a.png", extractedText = "", syncState = SyncState.SYNCED)

        assertNull(ResultText.status(done))
    }

    @Test
    fun `one not sent yet is found by its words only`() {
        assertEquals(
            "not sent yet · words only",
            ResultText.status(Memory(type = MemoryType.TEXT, rawText = "x", syncState = SyncState.PENDING)),
        )
        assertEquals(
            "not sent yet · words only",
            ResultText.status(Memory(type = MemoryType.TEXT, rawText = "x", syncState = SyncState.UPLOADING)),
        )
    }

    @Test
    fun `a refused memory says so`() {
        assertEquals("the server refused it", ResultText.status(Memory(type = MemoryType.TEXT, syncState = SyncState.FAILED)))
    }

    @Test
    fun `what the phone is still reading comes first`() {
        val photo = Memory(type = MemoryType.IMAGE, localUri = "file:///o/a.png", syncState = SyncState.PENDING)
        val link = Memory(type = MemoryType.LINK, rawText = "https://example.com", syncState = SyncState.SYNCED)

        assertEquals("still reading it · not sent yet · words only", ResultText.status(photo))
        assertEquals("page not read yet", ResultText.status(link))
    }

    // --- a long text's opening ---------------------------------------------------

    @Test
    fun `a long text shows its opening, cut at a word`() {
        assertEquals("Data Structures …", ResultText.opening("Data Structures week six", max = 18))
    }

    @Test
    fun `a short text is shown whole`() {
        assertEquals("Data Structures", ResultText.opening("Data Structures", max = 18))
    }

    // --- why this matched -------------------------------------------------------

    @Test
    fun `the matching word is lit with the text around it`() {
        val fragment = ResultText.fragment(
            listOf("Qualcomm | Software Engineering Intern | Bengaluru"),
            ResultText.words("qualcomm intern"),
        )

        assertEquals(Fragment("", "Qualcomm", " | Software Engineering Intern | Bengaluru"), fragment)
    }

    @Test
    fun `a word lights the whole of a longer word it begins`() {
        // roughly what the server's stemming did to find it
        val fragment = ResultText.fragment(listOf("summer internship at Samsung"), listOf("intern"))

        assertEquals("internship", fragment?.hit)
        assertEquals("summer ", fragment?.before)
    }

    @Test
    fun `a word only matches where a word begins`() {
        // "tern" is inside "pattern", but no word here starts with it
        assertNull(ResultText.fragment(listOf("the pattern repeats"), listOf("tern")))
    }

    @Test
    fun `case does not matter`() {
        assertEquals("GOVERNMENT", ResultText.fragment(listOf("THE GOVERNMENT IS WATCHING"), listOf("government"))?.hit)
    }

    @Test
    fun `texts are searched in the order given`() {
        val fragment = ResultText.fragment(listOf(null, "", "no match here", "the omakase at Naru's"), listOf("omakase"))

        assertEquals("omakase", fragment?.hit)
    }

    @Test
    fun `long text is cut at words either side, with ellipses`() {
        val text = "x ".repeat(40) + "one two three four five six seven Qualcomm eight nine ten " + "y ".repeat(60)

        val fragment = ResultText.fragment(listOf(text), listOf("qualcomm"), lead = 20, tail = 20)!!

        assertTrue(fragment.before, fragment.before.startsWith("…"))
        assertTrue(fragment.after, fragment.after.endsWith("…"))
        // never half a word at the cut
        assertTrue(fragment.before, fragment.before.removePrefix("…").split(" ").all { it.isEmpty() || it in text.split(" ") })
    }

    @Test
    fun `line breaks in OCR text read as spaces`() {
        val fragment = ResultText.fragment(listOf("This happens when the\ngovernment is\n\ntrying"), listOf("government"))

        assertEquals("This happens when the ", fragment?.before)
        assertEquals(" is trying", fragment?.after)
    }

    @Test
    fun `filler words light nothing up`() {
        assertEquals(listOf("internship", "screenshot"), ResultText.words("that internship screenshot I saved"))
        assertNull(ResultText.fragment(listOf("that thing from the"), ResultText.words("that thing from the")))
    }

    @Test
    fun `words that regex would read as syntax are matched as themselves`() {
        assertEquals("c++", ResultText.fragment(listOf("learn c++ fast"), listOf("c++"))?.hit)
        // and a stray bracket cannot break the search
        assertNull(ResultText.fragment(listOf("plain"), listOf("(")))
    }

    // --- titles -------------------------------------------------------------------

    @Test
    fun `each kind is named by what it holds`() {
        assertEquals(
            "square/okhttp",
            ResultText.title(Memory(type = MemoryType.LINK, title = "square/okhttp", rawText = "https://github.com/square/okhttp")),
        )
        assertEquals("https://example.com/x", ResultText.title(Memory(type = MemoryType.LINK, rawText = "https://example.com/x")))
        assertEquals("Naru's in Indiranagar", ResultText.title(Memory(type = MemoryType.TEXT, rawText = "\n  Naru's in Indiranagar\nbook ahead")))
        assertEquals("INCEPTIA 2K26", ResultText.title(Memory(type = MemoryType.PDF, title = "INCEPTIA 2K26")))
        assertEquals("Document", ResultText.title(Memory(type = MemoryType.PDF)))
    }

    @Test
    fun `a picture with no title or caption takes the summary, then its kind`() {
        val picture = Memory(type = MemoryType.IMAGE, extractedText = "ignored")

        assertEquals("Comments joking about the TVA", ResultText.title(picture, summary = "Comments joking about the TVA"))
        assertEquals("Image", ResultText.title(picture))
        assertEquals("look at this", ResultText.title(picture.copy(rawText = "look at this"), summary = "x"))
    }

    @Test
    fun `a long first line is cut at a word`() {
        val line = ResultText.firstLine("word ".repeat(40), max = 22)!!

        assertTrue(line, line.endsWith("…"))
        assertTrue(line, line.length <= 23)
    }

    // --- chrome -------------------------------------------------------------------

    @Test
    fun `ages take as few characters as will do`() {
        assertEquals("now", ResultText.age(now - 20_000, now))
        assertEquals("5m", ResultText.age(now - 5 * 60_000, now))
        assertEquals("3h", ResultText.age(now - 3 * hour, now))
        assertEquals("2d", ResultText.age(now - 2 * day, now))
        assertEquals("3w", ResultText.age(now - 21 * day, now))
        assertEquals("4mo", ResultText.age(now - 125 * day, now))
        assertEquals("1y", ResultText.age(now - 400 * day, now))
        // a clock that moved backwards is not the future
        assertEquals("now", ResultText.age(now + day, now))
    }

    @Test
    fun `a tile says when and where, and a row also says what`() {
        val photo = Memory(type = MemoryType.IMAGE, hasLink = true, capturedAt = now - 2 * day, sourceAppLabel = "WhatsApp")

        assertEquals("2d · whatsapp", ResultText.tileMeta(photo, now))
        assertEquals("2d · whatsapp · image + link", ResultText.rowMeta(photo, now))
        assertEquals("2d · text", ResultText.rowMeta(Memory(type = MemoryType.TEXT, capturedAt = now - 2 * day), now))
    }

    @Test
    fun `the resting count mentions unsent saves only when there are some`() {
        assertEquals("17 kept", ResultText.kept(17, 0))
        assertEquals("17 kept · 2 unsent", ResultText.kept(17, 2))
    }

    @Test
    fun `the counter shows the phrase as typed until the server has read it`() {
        assertEquals("3 of 17 · qualcomm intern · searching…", ResultText.counter(3, 17, "qualcomm intern", null, today, "searching…"))
    }

    @Test
    fun `the counter shows what the server searched for, filters included`() {
        val read = Interpretation(query = "internship", types = listOf("IMAGE"), from = "2026-04-01", to = "2026-04-30")

        assertEquals("1 of 17 · internship · images · apr", ResultText.counter(1, 17, "that internship screenshot from april", read, today))
    }

    @Test
    fun `an all-filter search shows only its filters`() {
        val read = Interpretation(query = "", types = listOf("LINK"), sourceApp = "WhatsApp")

        assertEquals("2 of 17 · links · from whatsapp", ResultText.counter(2, 17, "links from whatsapp", read, today))
    }

    @Test
    fun `date ranges read as briefly as they can`() {
        assertEquals("apr", ResultText.dates("2026-04-01", "2026-04-30", today))
        assertEquals("apr 2025", ResultText.dates("2025-04-01", "2025-04-30", today))
        assertEquals("17 sep", ResultText.dates("2026-09-17", "2026-09-17", today))
        assertEquals("7 sep – 13 sep", ResultText.dates("2026-09-07", "2026-09-13", today))
        assertEquals("since 1 sep", ResultText.dates("2026-09-01", null, today))
        assertNull(ResultText.dates(null, null, today))
        assertNull(ResultText.dates("not a date", null, today))
    }

    @Test
    fun `the detail says when it was saved, and how long ago`() {
        val zone = ZoneOffset.UTC

        assertEquals("18 sep · today", ResultText.saved(now - hour, now, zone))
        assertEquals("17 sep · yesterday", ResultText.saved(now - day, now, zone))
        assertEquals("12 sep · 6 days ago", ResultText.saved(now - 6 * day, now, zone))
        assertEquals("21 aug · 4 weeks ago", ResultText.saved(now - 28 * day, now, zone))
        assertEquals("18 apr", ResultText.taken(now - 153 * day, now, zone))
    }
}

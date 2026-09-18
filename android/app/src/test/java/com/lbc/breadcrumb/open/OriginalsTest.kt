package com.lbc.breadcrumb.open

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** What "Open original" does for each kind of memory (step 4.5). The intent itself is tested on the device. */
class OriginalsTest {

    private val stored = File("/data/originals/m1.jpg")
    private val storedPdf = File("/data/originals/m2.pdf")

    /** Stands in for OriginalStore.fileFor: the file when it is held, null otherwise. */
    private fun holding(vararg files: File): (Memory) -> File? = { memory -> files.firstOrNull { it.nameWithoutExtension == memory.id } }

    @Test
    fun `a picture opens as its file, with its own type`() {
        val action = Originals.actionFor(Memory(id = "m1", type = MemoryType.IMAGE), holding(stored))

        assertEquals(OriginalAction.ViewFile(stored, "image/jpeg"), action)
    }

    @Test
    fun `a pdf opens as a pdf`() {
        val action = Originals.actionFor(Memory(id = "m2", type = MemoryType.PDF), holding(storedPdf))

        assertEquals(OriginalAction.ViewFile(storedPdf, "application/pdf"), action)
    }

    @Test
    fun `a picture whose file is gone has nothing to open`() {
        // a debug sample's made-up path, or a file deleted behind the app's back
        assertEquals(OriginalAction.Missing, Originals.actionFor(Memory(id = "m1", type = MemoryType.IMAGE), holding()))
    }

    @Test
    fun `a link opens its url, even with words around it`() {
        val link = Memory(type = MemoryType.LINK, rawText = "check this out https://github.com/square/okhttp.")

        assertEquals(OriginalAction.ViewUrl("https://github.com/square/okhttp"), Originals.actionFor(link, holding()))
    }

    @Test
    fun `a bare www address is given a scheme so a browser takes it`() {
        val link = Memory(type = MemoryType.LINK, rawText = "www.example.com/menu")

        assertEquals(OriginalAction.ViewUrl("https://www.example.com/menu"), Originals.actionFor(link, holding()))
    }

    @Test
    fun `a note is handed back by copying it`() {
        val note = Memory(type = MemoryType.TEXT, rawText = "Naru's in Indiranagar, book two weeks ahead")

        assertEquals(OriginalAction.CopyText("Naru's in Indiranagar, book two weeks ahead"), Originals.actionFor(note, holding()))
    }

    @Test
    fun `a note with nothing in it has nothing to hand back`() {
        assertEquals(OriginalAction.Missing, Originals.actionFor(Memory(type = MemoryType.TEXT, rawText = "  "), holding()))
    }

    @Test
    fun `file types come from the extension, with a safe fallback`() {
        assertEquals("image/png", Originals.mimeType(MemoryType.IMAGE, "PNG"))
        assertEquals("image/webp", Originals.mimeType(MemoryType.IMAGE, "webp"))
        assertEquals("image/*", Originals.mimeType(MemoryType.IMAGE, "bin"))
        assertEquals("application/pdf", Originals.mimeType(MemoryType.PDF, "whatever"))
    }
}

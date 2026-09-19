package com.lbc.breadcrumb.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCodecTest {

    @Test
    fun aDraftComesBackAsItWasWritten() {
        val draft = Draft(
            text = "Priya's flat, \"the one with the balcony\"\nsecond line",
            attachments = listOf(
                DraftFile("content://media/picker/0/com.android.providers.media.photopicker/media/1000", isPdf = false),
                DraftFile("content://com.android.providers.downloads.documents/document/42", "notes.pdf", isPdf = true),
            ),
        )

        assertEquals(draft, DraftCodec.decode(DraftCodec.encode(draft)))
    }

    @Test
    fun nothingStoredIsAnEmptyDraft() {
        assertEquals(Draft(), DraftCodec.decode(null))
    }

    @Test
    fun somethingUnreadableIsAnEmptyDraftNotACrash() {
        assertEquals(Draft(), DraftCodec.decode("{not json"))
        assertEquals(Draft(), DraftCodec.decode("[1, 2, 3]"))
    }

    @Test
    fun aDraftWrittenByANewerVersionStillReads() {
        assertEquals(Draft(text = "hi"), DraftCodec.decode("""{"text":"hi","someFieldFromLater":true}"""))
    }

    @Test
    fun onlyWhitespaceIsNoDraft() {
        assertTrue(Draft(text = "  \n ").isEmpty)
        assertFalse(Draft(attachments = listOf(DraftFile("content://x"))).isEmpty)
    }
}

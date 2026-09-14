package com.lbc.breadcrumb.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardRulesTest {

    @Test
    fun `plain copied text is saved as text`() {
        assertEquals(
            ClipKind.Text("Naru's in Indiranagar"),
            ClipboardRules.decide(isSensitive = false, texts = listOf("Naru's in Indiranagar"), fileCount = 0),
        )
    }

    @Test
    fun `a sensitive clip is never saved, whatever it holds`() {
        // what a password manager or OTP autofill copies
        assertEquals(ClipKind.Sensitive, ClipboardRules.decide(true, listOf("hunter2"), 0))
        assertEquals(ClipKind.Sensitive, ClipboardRules.decide(true, emptyList(), 2))
        assertEquals(ClipKind.Sensitive, ClipboardRules.decide(true, emptyList(), 0))
    }

    @Test
    fun `text is trimmed and multiple items are joined by line`() {
        assertEquals(
            ClipKind.Text("first\nsecond"),
            ClipboardRules.decide(false, listOf("  first ", null, "", "second\n"), 0),
        )
    }

    @Test
    fun `text wins over a file in the same clip`() {
        assertEquals(ClipKind.Text("caption"), ClipboardRules.decide(false, listOf("caption"), 1))
    }

    @Test
    fun `a copied image with no text is a file`() {
        assertEquals(ClipKind.Files, ClipboardRules.decide(false, listOf(null, "  "), 1))
    }

    @Test
    fun `an empty clipboard is empty`() {
        assertEquals(ClipKind.Empty, ClipboardRules.decide(false, emptyList(), 0))
        assertEquals(ClipKind.Empty, ClipboardRules.decide(false, listOf("   "), 0))
    }
}

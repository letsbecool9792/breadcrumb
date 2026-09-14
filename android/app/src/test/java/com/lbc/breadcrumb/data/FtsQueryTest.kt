package com.lbc.breadcrumb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {

    @Test
    fun `each word becomes a quoted prefix term`() {
        assertEquals("\"qualcomm*\" \"intern*\"", FtsQuery.matchExpression("qualcomm intern"))
    }

    @Test
    fun `case is left to the tokenizer`() {
        assertEquals("\"Qualcomm*\"", FtsQuery.matchExpression("Qualcomm"))
    }

    @Test
    fun `extra spaces and punctuation only separate words`() {
        assertEquals("\"april*\" \"30*\"", FtsQuery.matchExpression("  april, 30! "))
    }

    @Test
    fun `an apostrophe splits a word the way the index does`() {
        assertEquals("\"Naru*\" \"s*\"", FtsQuery.matchExpression("Naru's"))
    }

    @Test
    fun `FTS syntax is searched for as words, never parsed`() {
        assertEquals("\"cats*\" \"OR*\" \"dogs*\"", FtsQuery.matchExpression("cats OR dogs"))
        assertEquals("\"NEAR*\" \"x*\"", FtsQuery.matchExpression("NEAR(x)"))
        assertEquals("\"ok*\"", FtsQuery.matchExpression("-\"ok*"))
        assertEquals("\"title*\" \"foo*\"", FtsQuery.matchExpression("title:foo"))
    }

    @Test
    fun `letters beyond ASCII are kept whole`() {
        assertEquals("\"café*\" \"straße*\"", FtsQuery.matchExpression("café straße"))
    }

    @Test
    fun `nothing to search for is null`() {
        assertNull(FtsQuery.matchExpression(""))
        assertNull(FtsQuery.matchExpression("   "))
        assertNull(FtsQuery.matchExpression("\"*-()"))
    }
}

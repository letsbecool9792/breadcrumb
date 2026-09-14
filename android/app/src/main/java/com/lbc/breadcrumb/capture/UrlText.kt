package com.lbc.breadcrumb.capture

object UrlText {

    /**
     * The word boundary keeps "awww.gif" from counting as a link. Deliberately
     * loose otherwise: a false LINK chip costs little, a missed one hides a
     * memory from a "that link" search.
     */
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    fun containsUrl(text: String?): Boolean = text != null && URL.containsMatchIn(text)
}

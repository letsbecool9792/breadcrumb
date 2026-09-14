package com.lbc.breadcrumb.capture

object UrlText {

    /**
     * The word boundary keeps "awww.gif" from counting as a link. Deliberately
     * loose otherwise: a false LINK chip costs little, a missed one hides a
     * memory from a "that link" search.
     */
    private val URL = Regex("""\b(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    fun containsUrl(text: String?): Boolean = text != null && URL.containsMatchIn(text)

    /**
     * The host of the first URL in [text], as a person would name the site:
     * "https://www.github.com/square/okhttp" -> "github.com".
     */
    fun firstHost(text: String?): String? {
        val url = text?.let { URL.find(it)?.value } ?: return null
        return url
            .replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trimEnd('.', ',', ')', ']', '!', '?', ';')
            .removePrefix("www.")
            .removePrefix("WWW.")
            .lowercase()
            .takeIf { it.contains('.') }
    }
}

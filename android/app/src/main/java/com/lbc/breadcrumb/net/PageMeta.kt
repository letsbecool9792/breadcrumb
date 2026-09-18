package com.lbc.breadcrumb.net

import java.util.Locale

/**
 * What a web page says about itself in its head: the title and description it
 * gives link previews, and its site's name. This is what turns a bare URL --
 * copied from WhatsApp, saved from the tile -- into something findable by
 * what it was about.
 *
 * Pure and Android-free, so the parsing is covered by JVM tests. Regexes over
 * the head rather than an HTML parser: only a handful of tags matter, and a
 * page that defeats this simply reads as nothing, as it would have before.
 */
data class PageMeta(val title: String?, val description: String?, val siteName: String?) {

    val isEmpty: Boolean get() = title == null && description == null

    /**
     * What is kept as the link's read text: the description, and the site's
     * name when neither it nor the title already says it -- "NYT Cooking" is
     * how someone remembers a recipe, and a URL does not always spell it.
     */
    fun readText(): String {
        val site = siteName?.takeUnless { name ->
            listOfNotNull(title, description).any { it.contains(name, ignoreCase = true) }
        }
        return listOfNotNull(description, site).joinToString("\n")
    }

    companion object {
        private const val MAX_TITLE = 300
        private const val MAX_DESCRIPTION = 1_000

        private val META = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val ATTRIBUTE = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
        private val TITLE = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SPACE = Regex("""\s+""")
        private val ENTITY = Regex("""&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);""")

        /**
         * Titles of pages that stand between a reader and the page -- a bot
         * check, a login wall. Taking one for the page's own would name every
         * such link "Just a moment...".
         */
        private val BLOCKED = listOf(
            "just a moment", "attention required", "access denied", "403 forbidden", "forbidden",
            "are you a robot", "are you a human", "security check", "log in", "login", "sign in",
            "page not found", "404 not found", "not found",
        )

        fun parse(html: String): PageMeta {
            // first of each wins: a page that repeats a tag means the first one
            val metas = mutableMapOf<String, String>()
            for (tag in META.findAll(html)) {
                val attributes = ATTRIBUTE.findAll(tag.value).associate { match ->
                    val (name, doubled, single, bare) = match.destructured
                    name.lowercase(Locale.ROOT) to doubled.ifEmpty { single.ifEmpty { bare } }
                }
                val key = attributes["property"] ?: attributes["name"] ?: continue
                val content = attributes["content"] ?: continue
                metas.putIfAbsent(key.lowercase(Locale.ROOT), content)
            }

            val title = listOf(metas["og:title"], metas["twitter:title"], TITLE.find(html)?.groupValues?.get(1))
                .firstNotNullOfOrNull { clean(it, MAX_TITLE) }
            val description = listOf(metas["og:description"], metas["twitter:description"], metas["description"])
                .firstNotNullOfOrNull { clean(it, MAX_DESCRIPTION) }
            val site = clean(metas["og:site_name"], MAX_TITLE)

            if (title != null && blocked(title)) return PageMeta(null, null, null)
            return PageMeta(title, description, site)
        }

        private fun blocked(title: String): Boolean {
            val lower = title.lowercase(Locale.ROOT).trimEnd('.', '…', '!', ' ')
            return BLOCKED.any { lower == it || lower.startsWith("$it |") || lower.startsWith("$it -") }
        }

        /** Entities decoded, whitespace collapsed, cut at a word; null when nothing is left. */
        internal fun clean(raw: String?, max: Int): String? {
            val text = decode(raw ?: return null).replace(SPACE, " ").trim()
            if (text.isEmpty()) return null
            if (text.length <= max) return text
            val cut = text.substring(0, max)
            return cut.substringBeforeLast(' ', cut).trimEnd() + "…"
        }

        private val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
            "ldquo" to "“", "rdquo" to "”", "middot" to "·", "bull" to "•", "copy" to "©",
            "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»",
        )

        internal fun decode(text: String): String = ENTITY.replace(text) { match ->
            val name = match.groupValues[1]
            val codePoint = when {
                name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)
                name.startsWith("#") -> name.substring(1).toIntOrNull()
                else -> null
            }
            when {
                codePoint != null && Character.isValidCodePoint(codePoint) -> String(Character.toChars(codePoint))
                else -> NAMED[name] ?: match.value
            }
        }
    }
}

/** Which links are worth fetching, and how. Pure, like [PageMeta]. */
object PageRules {

    /**
     * Only web pages on the open internet. Not the phone itself, and not an
     * address given as a bare IP -- a router, a dev server on the LAN: those
     * are not pages anyone saved to find again by their title, and fetching
     * them from the background is not something to do unasked.
     */
    fun worthReading(url: String): Boolean {
        val match = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(url) ?: return false
        val host = match.groupValues[1].lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        if (host.startsWith("[")) return false
        return host.contains('.')
    }

    /**
     * Plain http goes as https: Android refuses cleartext outside debugging,
     * and nearly every site answers both.
     */
    fun secure(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else url
}

package com.lbc.breadcrumb.ui.home

import com.lbc.breadcrumb.capture.UrlText
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.net.Interpretation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A result's "why this matched": a stretch of its own text with the matching
 * word lit, as design board 2 draws it.
 */
data class Fragment(val before: String, val hit: String, val after: String)

/**
 * Everything the search screens say about a memory, in words. Pure, so it is
 * tested on the JVM; the composables only lay it out. Lowercase mono chrome
 * throughout, as the capture sheet's -- chrome recedes.
 */
object ResultText {

    /** Words that describe no memory: lighting them up would light up everything. */
    private val FILLER = setOf(
        "a", "an", "the", "that", "this", "these", "those", "it", "its", "i", "me", "my", "we", "our",
        "from", "of", "in", "on", "at", "to", "for", "with", "and", "or", "about", "by", "is", "was",
        "one", "thing", "stuff", "saved", "save", "some", "someone", "sent", "where", "what", "which",
    )

    private val SEPARATORS = Regex("""[^\p{L}\p{Nd}]+""")
    private val SPACE = Regex("""\s+""")

    /** The words worth lighting up in a result: what the parser left of the phrase, or the phrase itself. */
    fun words(phrase: String): List<String> =
        phrase.lowercase().split(SEPARATORS).filter { it.length >= 2 && it !in FILLER }.distinct()

    /**
     * The first place any of [words] begins a word in [texts], searched in the
     * order given, with some of the text either side.
     *
     * A word matches the start of a longer one -- "intern" lights all of
     * "internship" -- which is roughly what the server's stemming did to find
     * it. Null when no word appears: a match by meaning alone, which the
     * summary explains instead.
     */
    fun fragment(texts: List<String?>, words: List<String>, lead: Int = 28, tail: Int = 80): Fragment? {
        if (words.isEmpty()) return null
        val alternatives = words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("""(?<![\p{L}\p{Nd}])(?:$alternatives)""", RegexOption.IGNORE_CASE)

        for (raw in texts) {
            val text = raw?.replace(SPACE, " ")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val match = pattern.find(text) ?: continue

            val start = match.range.first
            var end = match.range.last + 1
            while (end < text.length && text[end].isLetterOrDigit()) end++

            return Fragment(
                before = leadIn(text.substring(0, start), lead),
                hit = text.substring(start, end),
                after = tailOff(text.substring(end), tail),
            )
        }
        return null
    }

    /** The last [max] characters before the hit, cut at a word so no half word is shown. */
    private fun leadIn(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(text.length - max)
        return "…" + cut.substringAfter(' ', cut)
    }

    private fun tailOff(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        return cut.substringBeforeLast(' ', cut).trimEnd() + "…"
    }

    /**
     * What to call a memory, by kind. Only a few have a real title -- a page's,
     * a PDF's filename -- so most are named by what they hold. A picture with
     * neither title nor caption takes the server's one-line summary when a
     * search brought one.
     */
    fun title(memory: Memory, summary: String? = null): String = when (memory.type) {
        MemoryType.LINK -> memory.title ?: firstLine(memory.rawText) ?: "Link"
        MemoryType.TEXT, MemoryType.AUDIO -> firstLine(memory.rawText) ?: memory.title ?: "Note"
        MemoryType.IMAGE -> memory.title ?: firstLine(memory.rawText) ?: summary ?: "Image"
        MemoryType.PDF -> memory.title ?: "Document"
    }

    /** The first line with anything in it, cut to one line's worth. */
    fun firstLine(text: String?, max: Int = 90): String? {
        val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length <= max) line else line.substring(0, max).substringBeforeLast(' ').trimEnd() + "…"
    }

    /** The start of a long text, cut at a word, with an ellipsis; a short one whole. */
    fun opening(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        return cut.substringBeforeLast(' ', cut).trimEnd() + " …"
    }

    /** Where a link goes, as a person names the site. */
    fun host(memory: Memory): String? = UrlText.firstHost(memory.rawText)

    /** "2d", "3w", "1mo": how long ago it was saved, in as few characters as will do. */
    fun age(then: Long, now: Long): String {
        val minutes = (now - then).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24 * 7)}w"
            minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)}mo"
            else -> "${minutes / (60 * 24 * 365)}y"
        }
    }

    private fun source(memory: Memory): String? = memory.sourceAppLabel?.lowercase()

    /** Under a mosaic tile: when and where. The tile's shape already says what. */
    fun tileMeta(memory: Memory, now: Long): String =
        listOfNotNull(age(memory.capturedAt, now), source(memory)).joinToString(" · ")

    /** Under a result: when, where, and what -- a list row has no shape to say it. */
    fun rowMeta(memory: Memory, now: Long): String =
        listOfNotNull(
            age(memory.capturedAt, now),
            source(memory),
            memory.chips.joinToString(" + ") { it.name.lowercase() },
        ).joinToString(" · ")

    /** The resting count, e.g. "17 kept", or "17 kept · 2 unsent" while uploads are owed. */
    fun kept(total: Int, unsent: Int): String =
        if (unsent > 0) "$total kept · $unsent unsent" else "$total kept"

    /**
     * The line over the results, e.g. "1 of 17 · internship · images · apr".
     * It shows what was searched for as the server read it, so a filter it
     * applied is never a surprise.
     */
    fun counter(
        shown: Int,
        total: Int,
        typed: String,
        interpretation: Interpretation?,
        today: LocalDate,
        status: String? = null,
    ): String {
        val parts = mutableListOf("$shown of $total")
        if (interpretation == null) {
            parts += typed
        } else {
            interpretation.query.takeIf { it.isNotBlank() }?.let { parts += it }
            types(interpretation.types)?.let { parts += it }
            dates(interpretation.from, interpretation.to, today)?.let { parts += it }
            interpretation.sourceApp?.let { parts += "from ${it.lowercase()}" }
        }
        status?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun types(types: List<String>): String? {
        val names = types.mapNotNull {
            when (it) {
                "IMAGE" -> "images"
                "LINK" -> "links"
                "PDF" -> "pdfs"
                "TEXT" -> "notes"
                else -> null
            }
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString(" or ")
    }

    /** "apr" for all of April this year, "18 sep" for one day, "1 apr – 15 may" otherwise. */
    fun dates(from: String?, to: String?, today: LocalDate): String? {
        val start = from?.let(::parseDay)
        val end = to?.let(::parseDay)
        return when {
            start != null && end != null && start.dayOfMonth == 1 && end == start.withDayOfMonth(start.lengthOfMonth()) ->
                month(start) + if (start.year != today.year) " ${start.year}" else ""
            start != null && start == end -> day(start, today)
            start != null && end != null -> "${day(start, today)} – ${day(end, today)}"
            start != null -> "since ${day(start, today)}"
            end != null -> "until ${day(end, today)}"
            else -> null
        }
    }

    private fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

    private fun month(date: LocalDate): String = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()

    /** "18 sep", with the year only when it is not this one. */
    fun day(date: LocalDate, today: LocalDate): String =
        "${date.dayOfMonth} ${month(date)}" + if (date.year != today.year) " ${date.year}" else ""

    /** "12 sep · 2 days ago": when it was saved, for the detail's provenance. */
    fun saved(then: Long, now: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        val ago = when {
            days <= 0 -> "today"
            days == 1L -> "yesterday"
            days < 14 -> "$days days ago"
            days < 60 -> "${days / 7} weeks ago"
            days < 730 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
        return "${day(date, today)} · $ago"
    }

    /** A day on its own, e.g. "18 apr", for when a picture was taken. */
    fun taken(then: Long, now: Long, zone: ZoneId): String =
        day(Instant.ofEpochMilli(then).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())

    /** The month a mosaic tile was saved in, for the date that rides the grid's edge. */
    fun monthOf(then: Long, zone: ZoneId): String = month(Instant.ofEpochMilli(then).atZone(zone).toLocalDate())
}

package com.lbc.breadcrumb.capture

sealed interface ClipKind {
    /** Nothing usable on the clipboard. */
    data object Empty : ClipKind

    /** Flagged sensitive by the app that copied it -- a password, an OTP. */
    data object Sensitive : ClipKind

    data class Text(val text: String) : ClipKind

    /** A copied image or file, carried as content URIs. */
    data object Files : ClipKind
}

/**
 * What to do with whatever is on the clipboard. Pure, so the decision is
 * testable on the JVM; reading ClipData lives in the activity.
 */
object ClipboardRules {

    /**
     * Sensitive wins over everything. Password managers and OTP autofill mark
     * their copies sensitive precisely so they are not persisted, and a search
     * engine for personal memory is the last place a password should end up.
     *
     * Text wins over files when a clip carries both: the text is what the
     * person meant to copy; a URI alongside it is usually incidental.
     */
    fun decide(isSensitive: Boolean, texts: List<String?>, fileCount: Int): ClipKind {
        if (isSensitive) return ClipKind.Sensitive

        val text = texts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isNotEmpty()) return ClipKind.Text(text)

        if (fileCount > 0) return ClipKind.Files
        return ClipKind.Empty
    }
}

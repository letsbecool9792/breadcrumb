package com.lbc.breadcrumb.capture

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType

/**
 * Turns the extras of an `ACTION_SEND` into a [Memory].
 *
 * Deliberately pure and Android-free so it can be tested on the JVM: the
 * classification rules are where the bugs live, not in the Intent plumbing.
 */
object ShareParser {

    /**
     * Matches only when the *entire* shared body is one URL. Prose that merely
     * contains a link stays TEXT -- "have a look at https://..." is a message,
     * and throwing away the message to keep the URL loses the part that made it
     * worth saving.
     */
    private val SINGLE_URL = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

    fun parse(
        text: String?,
        subject: String? = null,
        sourceApp: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Memory? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null

        // Chrome sends the page title as the subject and the URL as the text.
        // Messaging apps usually send no subject, or echo the body into it.
        val shareTitle = subject?.trim()?.takeIf { it.isNotEmpty() && it != body }

        return Memory(
            type = if (SINGLE_URL.matches(body)) MemoryType.LINK else MemoryType.TEXT,
            capturedAt = now,
            updatedAt = now,
            sourceApp = sourceApp,
            rawText = body,
            title = shareTitle,
        )
    }
}

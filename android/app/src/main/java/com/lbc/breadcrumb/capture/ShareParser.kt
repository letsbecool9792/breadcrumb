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

    fun parse(
        text: String?,
        subject: String? = null,
        sourceApp: String? = null,
        sourceAppLabel: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Memory? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null

        // Text with a URL anywhere in it is a LINK. The whole message is kept
        // as rawText regardless, so nothing the sender wrote around the link
        // is lost by calling it one.
        val hasLink = UrlText.containsUrl(body)

        // Chrome sends the page title as the subject and the URL as the text.
        // Messaging apps usually send no subject, or echo the body into it.
        val shareTitle = subject?.trim()?.takeIf { it.isNotEmpty() && it != body }

        return Memory(
            type = if (hasLink) MemoryType.LINK else MemoryType.TEXT,
            hasLink = hasLink,
            capturedAt = now,
            updatedAt = now,
            sourceApp = sourceApp,
            sourceAppLabel = sourceAppLabel,
            rawText = body,
            title = shareTitle,
        )
    }
}

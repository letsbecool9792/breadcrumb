package com.lbc.breadcrumb.capture

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import java.util.Locale
import java.util.UUID

/** What we could learn about one shared file before copying it. */
data class IncomingMedia(
    val mime: String?,
    val displayName: String? = null,
    /** Creation time reported by the provider, if it reports one. */
    val createdAt: Long? = null,
)

/**
 * A memory that is ready to be written once its file has been copied in.
 * The id is fixed up front because it also names the copied file.
 */
data class MediaDraft(
    val id: String,
    val type: MemoryType,
    val hasLink: Boolean,
    val extension: String,
    val title: String?,
    val rawText: String?,
    val contentCreatedAt: Long?,
) {
    fun toMemory(localUri: String, sourceApp: String?, now: Long): Memory = Memory(
        id = id,
        type = type,
        hasLink = hasLink,
        capturedAt = now,
        contentCreatedAt = contentCreatedAt,
        sourceApp = sourceApp,
        rawText = rawText,
        localUri = localUri,
        title = title,
        updatedAt = now,
    )
}

/**
 * Classification rules for shared images and PDFs. Pure and Android-free, like
 * [ShareParser], so the judgment calls are covered by fast JVM tests.
 */
object MediaShareParser {

    /**
     * WhatsApp sets the subject to "Photo from <sender>" on image shares (and
     * the equivalent for other media). That describes the envelope, not the
     * content, so it must not become the title. English only -- matches the
     * device locale this is developed against.
     */
    private val GENERIC_SUBJECT = Regex(
        """^(photo|image|picture|video|document|file|audio|gif|sticker|voice message)s?\s+from\s+.+$""",
        RegexOption.IGNORE_CASE,
    )

    fun typeFor(mime: String?): MemoryType? {
        val m = mime?.lowercase(Locale.ROOT) ?: return null
        return when {
            m.startsWith("image/") -> MemoryType.IMAGE
            m == "application/pdf" -> MemoryType.PDF
            else -> null
        }
    }

    fun extensionFor(mime: String?): String = when (mime?.lowercase(Locale.ROOT)) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        "image/bmp" -> "bmp"
        "application/pdf" -> "pdf"
        else -> "bin"
    }

    /**
     * Only content:// is accepted. file:// URIs from another app are either a
     * pre-Nougat leftover or an attempt to make us read a path the sender
     * chose, and nothing legitimate in 2026 shares that way.
     */
    fun isAcceptableScheme(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

    fun usefulSubject(subject: String?, displayName: String? = null): String? {
        val s = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (GENERIC_SUBJECT.matches(s)) return null
        if (displayName != null && s.equals(displayName.trim(), ignoreCase = true)) return null
        return s
    }

    /**
     * The file decides the primary type: a photo shared with a link in its
     * caption is still a photo. The link is recorded as [MediaDraft.hasLink],
     * so the memory carries both an IMAGE and a LINK chip and turns up under
     * either search.
     *
     * @param isOnlyItem shared text is attached only when a single file came
     *   with it. Across a multi-file share there is no telling which file the
     *   text belongs to, so attaching it to all of them would be wrong every
     *   time but once.
     */
    fun plan(
        item: IncomingMedia,
        sharedText: String?,
        subject: String?,
        isOnlyItem: Boolean,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): MediaDraft? {
        val type = typeFor(item.mime) ?: return null
        val text = sharedText?.trim()?.takeIf { isOnlyItem && it.isNotEmpty() }

        val title = usefulSubject(subject, item.displayName)
            ?: if (type == MemoryType.PDF) pdfTitle(item.displayName) else null

        return MediaDraft(
            id = newId(),
            type = type,
            hasLink = UrlText.containsUrl(text),
            extension = extensionFor(item.mime),
            title = title,
            rawText = text,
            contentCreatedAt = item.createdAt,
        )
    }

    /**
     * A PDF's filename is usually a real title ("ds-week6-notes.pdf"). An
     * image's filename almost never is ("IMG_20260418_101233.jpg"), which is
     * why only PDFs fall back to it.
     */
    private fun pdfTitle(displayName: String?): String? =
        displayName?.trim()
            ?.removeSuffix(".pdf")?.removeSuffix(".PDF")
            ?.takeIf { it.isNotEmpty() }
}

package com.lbc.breadcrumb.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the "+" sheet holds before it is kept: words, and files picked. */
@Serializable
data class Draft(val text: String = "", val attachments: List<DraftFile> = emptyList()) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/** A picked file, as a draft remembers it: its URI as a string, and what its tile shows. */
@Serializable
data class DraftFile(val uri: String, val name: String? = null, val isPdf: Boolean = false)

/** How a draft is written down. Pure, so it is tested on the JVM. */
object DraftCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(draft: Draft): String = json.encodeToString(draft)

    /** Whatever was stored, or an empty draft when nothing usable was -- never a crash on launch. */
    fun decode(stored: String?): Draft =
        stored?.let { runCatching { json.decodeFromString<Draft>(it) }.getOrNull() } ?: Draft()
}

/**
 * Keeps the "+" sheet's draft on disk, so a thought half written survives the
 * app being closed, not just the sheet being swiped away.
 *
 * A picked file is only a URI, readable while its grant lasts. So each is
 * given a persistable read grant when picked -- the photo picker's and the
 * document picker's both allow one -- and let go once the draft is kept or
 * the file taken out of it. A file whose grant did not survive is dropped
 * from the draft when it is next loaded, rather than failing at Keep.
 */
class DraftStore(
    private val context: Context,
    /** The preferences file; the tests use their own, never the real draft's. */
    name: String = "draft",
) {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun load(): Draft {
        val stored = DraftCodec.decode(prefs.getString(KEY, null))
        val readable = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        return stored.copy(attachments = stored.attachments.filter { it.uri in readable })
    }

    fun save(draft: Draft) {
        prefs.edit().apply {
            if (draft.isEmpty) remove(KEY) else putString(KEY, DraftCodec.encode(draft))
        }.apply()
    }

    /** Holds on to reading [uri] past this run of the app. Some providers refuse; the file then lasts the run. */
    fun keepReadable(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.i(TAG, "no lasting grant for $uri; it stays for this run only", e)
        }
    }

    /** Lets go of [uri] once the draft no longer needs it. */
    fun letGo(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // never held one
        }
    }

    private companion object {
        const val KEY = "draft"
        const val TAG = "DraftStore"
    }
}

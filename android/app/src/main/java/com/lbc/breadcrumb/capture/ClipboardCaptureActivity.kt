package com.lbc.breadcrumb.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lbc.breadcrumb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves whatever is on the clipboard. Launched by [CaptureTileService].
 *
 * Android 10+ only lets the app with window focus read the clipboard, and focus
 * arrives after onCreate -- reading there returns null every time. So capture
 * waits for [onWindowFocusChanged]. The capture sheet is already on screen by
 * then, showing "Saving…".
 *
 * Provenance is unavailable on this path; the clipboard does not record which
 * app copied something.
 */
class ClipboardCaptureActivity : CaptureActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false

    /**
     * If focus never arrives -- another window holding it, an OEM quirk --
     * report it rather than sitting on "Saving…" indefinitely.
     */
    private val focusTimeout = Runnable {
        if (!handled) {
            handled = true
            showFailed(R.string.capture_clipboard_unreadable)
        }
    }

    override fun onCapture() {
        handler.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        handler.removeCallbacks(focusTimeout)
        capture()
    }

    override fun onDestroy() {
        handler.removeCallbacks(focusTimeout)
        super.onDestroy()
    }

    private fun capture() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val items = clip?.let { c -> (0 until c.itemCount).map(c::getItemAt) }.orEmpty()

        // coerceToText also covers clips that carry only HTML or an intent.
        // Items that are URIs are handled as files, not coerced into a URI string.
        val texts = items.filter { it.uri == null }.map { it.coerceToText(this)?.toString() }
        val uris = items.mapNotNull(ClipData.Item::getUri)

        when (val kind = ClipboardRules.decide(isSensitive(clip), texts, uris.size)) {
            ClipKind.Empty -> showFailed(R.string.capture_clipboard_empty)
            ClipKind.Sensitive -> showFailed(R.string.capture_clipboard_sensitive)
            is ClipKind.Text -> saveText(kind.text)
            // the provider's own type is preferred later; this is only a fallback
            ClipKind.Files -> saveFiles(
                uris,
                clip?.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0),
            )
        }
    }

    private fun saveText(text: String) {
        val memory = ShareParser.parse(text) ?: return showFailed(R.string.capture_clipboard_empty)
        val write = app.applicationScope.launch { dao.upsert(memory) }
        showSaved(listOf(memory), attempted = 1, write = write)
    }

    /**
     * A copied image. The clipboard grants read access to the app that reads
     * the clip, so the copy runs while this activity is still alive, exactly as
     * for a shared file.
     */
    private fun saveFiles(uris: List<Uri>, mime: String?) {
        val capture = MediaCapture(applicationContext, dao, store)
        app.applicationScope.launch {
            val result = capture.save(uris, mime, sharedText = null, subject = null, source = null)
            withContext(Dispatchers.Main) {
                if (result.saved == 0) {
                    showFailed(R.string.capture_failed)
                } else {
                    showSaved(result.memories, result.attempted)
                }
            }
        }
    }

    /**
     * ClipDescription.EXTRA_IS_SENSITIVE is an API 33 constant, but the key is
     * a plain string that copying apps set regardless, so read it on every
     * version.
     */
    private fun isSensitive(clip: ClipData?): Boolean =
        clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true

    private companion object {
        const val FOCUS_TIMEOUT_MS = 2_000L
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

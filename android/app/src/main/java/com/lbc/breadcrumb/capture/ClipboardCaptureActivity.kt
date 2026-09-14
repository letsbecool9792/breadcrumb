package com.lbc.breadcrumb.capture

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.OriginalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves whatever is on the clipboard. Launched by [CaptureTileService].
 *
 * Android 10+ only lets the app with window focus read the clipboard, and focus
 * arrives after onCreate -- reading there returns null every time. So this
 * activity waits for [onWindowFocusChanged], which also rules out
 * windowNoDisplay: a window that never shows never gets focus. It uses the
 * fully transparent capture theme instead.
 *
 * Provenance is unavailable on this path; the clipboard does not record which
 * app copied something.
 */
class ClipboardCaptureActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false

    /**
     * If focus never arrives -- another window holding it, an OEM quirk --
     * give up visibly rather than leaving an invisible activity sitting on top
     * of everything and eating touches.
     */
    private val focusTimeout = Runnable {
        if (!handled) {
            handled = true
            done(R.string.capture_clipboard_unreadable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            ClipKind.Empty -> done(R.string.capture_clipboard_empty)
            ClipKind.Sensitive -> done(R.string.capture_clipboard_sensitive)
            is ClipKind.Text -> saveText(kind.text)
            // the provider's own type is preferred later; this is only a fallback
            ClipKind.Files -> saveFiles(
                uris,
                clip?.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0),
            )
        }
    }

    private fun saveText(text: String) {
        val memory = ShareParser.parse(text) ?: return done(R.string.capture_clipboard_empty)
        val dao = BreadcrumbDatabase.get(this).memoryDao()
        (application as BreadcrumbApp).applicationScope.launch { dao.upsert(memory) }
        done(R.string.capture_saved)
    }

    /**
     * A copied image. The clipboard grants read access to the app that reads
     * the clip, so the copy runs while this activity is still alive, exactly as
     * for a shared file.
     */
    private fun saveFiles(uris: List<Uri>, mime: String?) {
        val capture = MediaCapture(
            context = applicationContext,
            dao = BreadcrumbDatabase.get(this).memoryDao(),
            store = OriginalStore(applicationContext),
        )
        (application as BreadcrumbApp).applicationScope.launch {
            val result = capture.save(uris, mime, sharedText = null, subject = null, source = null)
            withContext(Dispatchers.Main) {
                done(if (result.saved > 0) R.string.capture_saved else R.string.capture_failed)
            }
        }
    }

    private fun done(@StringRes message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        if (!isFinishing && !isDestroyed) finish()
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

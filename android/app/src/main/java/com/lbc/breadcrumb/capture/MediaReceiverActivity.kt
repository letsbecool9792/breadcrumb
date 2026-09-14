package com.lbc.breadcrumb.capture

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.lbc.breadcrumb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share sheet entry point for images and PDFs.
 *
 * The read grant on a shared content:// URI lasts only while the receiving
 * activity is alive, so this one must stay up until every file has been copied
 * in. The capture sheet makes that visible -- "Saving…" -- and refuses to be
 * dismissed until the copy is done.
 */
class MediaReceiverActivity : CaptureActivity() {

    override fun onCapture() {
        val uris = sharedUris(intent)
        if (uris.isEmpty()) return showFailed(R.string.capture_nothing)

        val capture = MediaCapture(applicationContext, dao, store)
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val intentType = intent.type
        // android-app://com.whatsapp -> com.whatsapp -> "WhatsApp"
        val source = SourceAppResolver(this).resolve(referrer?.authority)

        app.applicationScope.launch {
            val result = capture.save(uris, intentType, sharedText, subject, source)

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
     * EXTRA_STREAM first, since that is the documented contract. ClipData as a
     * fallback: the system mirrors the stream into ClipData so read grants can
     * travel with it, and some senders populate only that.
     */
    private fun sharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()

        val fromExtras = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            else ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        if (fromExtras.isNotEmpty()) return fromExtras.distinct()

        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
    }
}

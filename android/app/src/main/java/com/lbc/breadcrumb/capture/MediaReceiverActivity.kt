package com.lbc.breadcrumb.capture

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.OriginalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share sheet entry point for images and PDFs.
 *
 * Separate from [ShareReceiverActivity] because it cannot finish instantly.
 * The read grant on a shared content:// URI lasts only while the receiving
 * activity is alive, so this one stays up -- invisibly, via a translucent
 * theme rather than windowNoDisplay, which would force a finish inside
 * onCreate -- until every file has been copied in.
 */
class MediaReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A recreation (rotation mid-copy) must not save everything twice. The
        // original job is still running and will report on its own.
        if (savedInstanceState != null) {
            finish()
            return
        }

        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.capture_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val capture = MediaCapture(
            context = applicationContext,
            dao = BreadcrumbDatabase.get(this).memoryDao(),
            store = OriginalStore(applicationContext),
        )
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val intentType = intent.type
        // android-app://com.whatsapp -> com.whatsapp
        val sourceApp = referrer?.authority

        (application as BreadcrumbApp).applicationScope.launch {
            val result = capture.save(uris, intentType, sharedText, subject, sourceApp)

            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message(result), Toast.LENGTH_SHORT).show()
                if (!isFinishing && !isDestroyed) finish()
            }
        }
    }

    private fun message(result: CaptureResult): String = when {
        result.saved == 0 -> getString(R.string.capture_failed)
        result.attempted == 1 -> getString(R.string.capture_saved)
        result.saved == result.attempted -> getString(R.string.capture_saved_count, result.saved)
        else -> getString(R.string.capture_saved_partial, result.saved, result.attempted)
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

package com.lbc.breadcrumb.capture

import android.content.Intent
import com.lbc.breadcrumb.R
import kotlinx.coroutines.launch

/**
 * Capture entry point for text. Handles both the share sheet (`ACTION_SEND`)
 * and the text selection toolbar (`ACTION_PROCESS_TEXT`).
 */
class ShareReceiverActivity : CaptureActivity() {

    override fun onCapture() {
        // android-app://com.android.chrome -> com.android.chrome -> "Chrome"
        val source = SourceAppResolver(this).resolve(referrer?.authority)

        val memory = ShareParser.parse(
            text = sharedText(intent),
            subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            sourceApp = source?.packageName,
            sourceAppLabel = source?.label,
        ) ?: return showFailed(R.string.capture_nothing)

        // Write first; the sheet reports a save already under way.
        val write = app.applicationScope.launch { dao.upsert(memory) }
        showSaved(listOf(memory), attempted = 1, write = write)
    }

    /**
     * Both extras are declared as CharSequence, not String. Most apps put a
     * plain String in and `getStringExtra` works, but anything sharing styled
     * text puts a Spanned in instead -- and `getStringExtra` returns null for
     * that, silently turning a real share into "Nothing to save".
     */
    private fun sharedText(intent: Intent?): String? {
        if (intent == null) return null

        val extra = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
            else -> Intent.EXTRA_TEXT
        }
        return intent.getCharSequenceExtra(extra)?.toString()
    }
}

package com.lbc.breadcrumb.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import kotlinx.coroutines.launch

/**
 * Capture entry point for text. Handles both the share sheet (`ACTION_SEND`)
 * and the text selection toolbar (`ACTION_PROCESS_TEXT`).
 *
 * Plain [Activity], not ComponentActivity: this is a cold-start path that runs
 * on every single save, and it renders nothing. There is no reason to pay for
 * the lifecycle and saved-state machinery to show a toast and finish.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // android-app://com.android.chrome -> com.android.chrome -> "Chrome"
        val source = SourceAppResolver(this).resolve(referrer?.authority)

        val memory = ShareParser.parse(
            text = sharedText(intent),
            subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            sourceApp = source?.packageName,
            sourceAppLabel = source?.label,
        )

        if (memory == null) {
            Toast.makeText(this, R.string.capture_nothing, Toast.LENGTH_SHORT).show()
        } else {
            val dao = BreadcrumbDatabase.get(this).memoryDao()
            (application as BreadcrumbApp).applicationScope.launch { dao.upsert(memory) }

            // Confirmed before the write lands, on purpose -- saving never
            // blocks (architecture rule 2), and a local Room insert has no
            // realistic failure this would be hiding.
            Toast.makeText(this, R.string.capture_saved, Toast.LENGTH_SHORT).show()
        }

        // The window is never shown (windowNoDisplay), so this must finish here.
        finish()
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

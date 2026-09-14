package com.lbc.breadcrumb.capture

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Debug-only diagnostic. Accepts every mime type, so it appears in EVERY share
 * sheet, and reports exactly what the sending app handed over.
 *
 * This exists because the system log only records the CHOOSER wrapper intent,
 * never the inner target intent, so there is no way to learn a sharing app's
 * real mime type from the outside.
 *
 * Lives in src/debug, so it is never part of a release build.
 *
 *     adb logcat -s BreadcrumbProbe:I
 */
class ShareProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = intent
        val out = StringBuilder("\n=== share probe ===\n")
        out.appendLine("action    = ${i?.action}")
        out.appendLine("type      = ${i?.type}")
        out.appendLine("referrer  = ${referrer?.authority}")

        i?.clipData?.let { clip ->
            val desc = clip.description
            val mimes = (0 until desc.mimeTypeCount).joinToString(", ") { desc.getMimeType(it) }
            out.appendLine("clip.mime = [$mimes]  items=${clip.itemCount}")
            for (n in 0 until clip.itemCount) {
                val item = clip.getItemAt(n)
                out.appendLine("  [$n] uri=${item.uri} text=${item.text?.take(90)}")
            }
        } ?: out.appendLine("clip      = (none)")

        val extras = i?.extras
        if (extras == null) {
            out.appendLine("extras    = (none)")
        } else {
            for (key in extras.keySet()) {
                @Suppress("DEPRECATION")
                val value = extras.get(key)
                out.appendLine("extra ${key} = ${value?.javaClass?.simpleName}: ${value.toString().take(180)}")
            }
        }

        Log.i("BreadcrumbProbe", out.toString())
        Toast.makeText(this, "probe: ${i?.type}", Toast.LENGTH_LONG).show()

        finish()
    }
}

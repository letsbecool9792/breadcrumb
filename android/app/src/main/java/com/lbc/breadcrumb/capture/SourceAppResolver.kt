package com.lbc.breadcrumb.capture

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** Where a capture came from: the stable package, and the name a person would say. */
data class Provenance(val packageName: String, val label: String?)

class SourceAppResolver(private val context: Context) {

    /**
     * Resolves a referrer package to [Provenance], or null when the referrer is
     * missing or is a system surface rather than a real source.
     *
     * The label is looked up now, at capture, and stored. Resolving it later
     * would lose it for any app uninstalled in the meantime, and "that thing
     * from WhatsApp" should keep working regardless.
     */
    fun resolve(packageName: String?): Provenance? {
        val pkg = SourceApps.normalize(packageName, context.packageName) ?: return null
        return Provenance(packageName = pkg, label = labelFor(pkg))
    }

    /**
     * Needs the source app to be visible to us. On Android 11+ that is only
     * guaranteed by the launcher-intent `<queries>` declaration in the manifest;
     * without it, apps that share plain text (Chrome, for one) cannot be seen
     * and this silently returns null.
     */
    private fun labelFor(pkg: String): String? = try {
        val pm = context.packageManager
        val info: ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString().trim().takeIf { it.isNotEmpty() && it != pkg }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

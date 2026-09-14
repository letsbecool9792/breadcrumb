package com.lbc.breadcrumb.capture

object SourceApps {

    /**
     * Packages that show up as the referrer without being where the content
     * came from. Each is here on evidence, not guesswork:
     *
     * - `com.android.systemui` -- clipboard shares report it (observed on-device),
     *   as do shares from the screenshot preview. "From System UI" says nothing.
     * - `com.android.intentresolver` -- the share chooser itself.
     * - `android` -- the framework, when no better caller is known.
     * - `com.android.shell` -- adb, i.e. development only.
     */
    private val NOT_A_SOURCE = setOf(
        "com.android.systemui",
        "com.android.intentresolver",
        "android",
        "com.android.shell",
    )

    /** The referrer package if it names a real origin, else null. */
    fun normalize(packageName: String?, ownPackage: String): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (pkg == ownPackage || pkg in NOT_A_SOURCE) return null
        return pkg
    }
}

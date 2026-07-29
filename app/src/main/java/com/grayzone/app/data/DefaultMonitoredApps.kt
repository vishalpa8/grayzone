package com.grayzone.app.data

/**
 * One-shot seeding of high-friction apps into the monitored set.
 * After [DEFAULT_APPS_SEEDED] is set, the user may remove them permanently.
 */
object DefaultMonitoredApps {

    val DEFAULT_PACKAGES = listOf(
        "com.instagram.android",
        "com.google.android.youtube"
    )

    /**
     * Merge installed default packages into [existing] when seeding has not run yet.
     *
     * @return null when no prefs write is needed (already seeded); otherwise the new set
     *         (may equal [existing] if none of the defaults are installed).
     */
    fun seedIfNeeded(
        alreadySeeded: Boolean,
        existing: Set<String>,
        installedPackages: Set<String>
    ): Set<String>? {
        if (alreadySeeded) return null
        val toAdd = DEFAULT_PACKAGES.filter { it in installedPackages }.toSet()
        return existing + toAdd
    }
}

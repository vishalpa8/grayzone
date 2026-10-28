package com.grayzone.app.service

/**
 * Turns the system's activity usage events into "which app is the user in" and "which apps are
 * on screen". Pure (event types are plain ints) so every sequence is unit-tested.
 *
 * - A resumed activity is in front; a paused-but-not-stopped one is still visible (behind a
 *   translucent screen, in split-screen or picture-in-picture, or mid-switch-animation).
 * - The user is in the most recently resumed launchable app ("user app": has a launcher icon or
 *   is the home screen). Notification shade, keyboard and Grayzone's own pause screens are not
 *   activities, so they never register as leaving the app.
 * - When only a non-launchable activity is resumed (in-call screen, permission dialog, share
 *   sheet), the user has left their app: [OTHER].
 */
class UsageForegroundTracker(private val isUserApp: (String) -> Boolean) {

    private data class Entry(val pkg: String, val sinceMs: Long)

    // Keyed by "package/activityClass"; insertion order doubles as recency.
    private val resumed = LinkedHashMap<String, Entry>()
    private val paused = LinkedHashMap<String, Entry>()

    fun onEvent(type: Int, pkg: String, className: String?, timeMs: Long) {
        val key = "$pkg/${className ?: ""}"
        when (type) {
            ACTIVITY_RESUMED -> {
                paused.remove(key)
                resumed.remove(key)
                resumed[key] = Entry(pkg, timeMs)
            }
            ACTIVITY_PAUSED -> {
                resumed.remove(key)
                paused.remove(key)
                paused[key] = Entry(pkg, timeMs)
            }
            ACTIVITY_STOPPED -> {
                resumed.remove(key)
                paused.remove(key)
            }
            DEVICE_SHUTDOWN, DEVICE_STARTUP -> {
                resumed.clear()
                paused.clear()
            }
        }
        // A lost STOPPED event must not grow these forever.
        trim(resumed)
        trim(paused)
    }

    /** Package the user is in, [OTHER] for a non-launchable full screen, or null when unknown. */
    fun foreground(): String? {
        val top = resumed.values.maxByOrNull { it.sinceMs }
        if (top != null) {
            if (isUserApp(top.pkg)) return top.pkg
            // A non-launchable screen is on top. Another resumed user app (multi-window) keeps the
            // user "in" that app; otherwise they left it.
            return resumed.values.filter { isUserApp(it.pkg) }.maxByOrNull { it.sinceMs }?.pkg ?: OTHER
        }
        // Nothing resumed (between a pause and the next resume): the last app still showing.
        return paused.values.filter { isUserApp(it.pkg) }.maxByOrNull { it.sinceMs }?.pkg
    }

    /** User-app packages with an activity on screen (resumed or paused-but-visible), newest first. */
    fun visiblePackages(): List<String> =
        (resumed.values + paused.values)
            .filter { isUserApp(it.pkg) }
            .sortedByDescending { it.sinceMs }
            .map { it.pkg }
            .distinct()

    private fun trim(map: LinkedHashMap<String, Entry>) {
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
    }

    companion object {
        /** Foreground is a non-launchable full-screen activity (e.g. a phone call). */
        const val OTHER = "__other__"

        // android.app.usage.UsageEvents.Event constants (stable public API values).
        const val ACTIVITY_RESUMED = 1
        const val ACTIVITY_PAUSED = 2
        const val ACTIVITY_STOPPED = 23
        const val DEVICE_SHUTDOWN = 26
        const val DEVICE_STARTUP = 27

        private const val MAX_ENTRIES = 64
    }
}

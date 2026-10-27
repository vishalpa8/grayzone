package com.grayzone.app.service

/**
 * Decides, from the accessibility window list, which app the user is in and which apps are on
 * screen. Pure so every window combination is unit-tested.
 *
 * - Grayzone's own pause / lock overlays are ignored entirely.
 * - Picture-in-picture windows of user apps count as "on screen" (the app keeps using its
 *   session) but are never "the app the user is in".
 * - Windows smaller than [minArea] (toasts, edge sidebars, bubbles) are ignored, except a focused
 *   user app (an app in pop-up / freeform mode).
 * - A full-size active window from a package without a launcher entry (in-call screen, control
 *   centre) is a real switch away from the current app: reported as [OTHER].
 * - With no active window (focus on the shade, keyboard or a Grayzone overlay), the app the user
 *   was already in is kept while it is still on screen; otherwise the largest app window wins.
 */
object ForegroundResolver {

    /** Foreground is a non-launchable full-screen window (e.g. a phone call). */
    const val OTHER = "__other__"

    data class AppWindow(
        val pkg: String,
        val area: Long,
        val isActive: Boolean,
        val isPip: Boolean,
        val isUserApp: Boolean,
        val isOwnOverlay: Boolean,
    )

    data class Result(val packages: List<String>, val foreground: String?)

    fun resolve(windows: List<AppWindow>, minArea: Long, lastForeground: String?): Result {
        val visible = LinkedHashSet<String>()
        val fullSize = LinkedHashSet<String>()
        var activeUser: String? = null
        var otherActive = false
        var largest: String? = null
        var largestArea = 0L
        for (w in windows) {
            if (w.isOwnOverlay) continue
            if (w.isPip) {
                if (w.isUserApp) visible += w.pkg
                continue
            }
            // An app shrunk into a small focused pop-up / freeform window is still the app in use;
            // small focused system panels (edge sidebars) are not a switch away.
            if (w.area < minArea && !(w.isActive && w.isUserApp)) continue
            if (!w.isUserApp) {
                if (w.isActive) otherActive = true
                continue
            }
            visible += w.pkg
            fullSize += w.pkg
            if (activeUser == null && w.isActive) activeUser = w.pkg
            if (w.area > largestArea) {
                largestArea = w.area
                largest = w.pkg
            }
        }
        val kept = lastForeground?.takeIf { it in fullSize }
        val foreground = activeUser ?: (if (otherActive) OTHER else null) ?: kept ?: largest
        return Result(visible.toList(), foreground)
    }
}

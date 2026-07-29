package com.grayzone.app

/**
 * Pure parsing for [android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES].
 *
 * The setting is a colon-separated list of flattened [android.content.ComponentName]s.
 * OEMs may store either the full class name (`pkg/com.example.Service`) or the relative
 * short form (`pkg/.Service`). Substring [String.contains] matching is unsafe and can
 * both false-negative (missing a short-form entry) and false-positive (partial matches).
 */
object AccessibilityServiceIds {

    /**
     * @param enabledServicesSetting raw Secure setting value (may be null/blank)
     * @param packageName app package, e.g. `com.grayzone.app`
     * @param serviceClassName fully-qualified service class name
     */
    fun isServiceEnabled(
        enabledServicesSetting: String?,
        packageName: String,
        serviceClassName: String
    ): Boolean {
        if (enabledServicesSetting.isNullOrBlank()) return false
        val expectedRelative = relativeClassName(packageName, serviceClassName) ?: return false

        // TextUtils.SimpleStringSplitter semantics: ':' delimiter, empty segments ignored.
        return enabledServicesSetting.split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0 || slash >= entry.length - 1) return@any false
            val pkg = entry.substring(0, slash)
            val cls = entry.substring(slash + 1)
            pkg == packageName && (cls == serviceClassName || cls == expectedRelative)
        }
    }

    /** `com.grayzone.app` + `com.grayzone.app.service.Foo` → `.service.Foo` */
    fun relativeClassName(packageName: String, serviceClassName: String): String? {
        if (!serviceClassName.startsWith("$packageName.")) return null
        return "." + serviceClassName.substring(packageName.length + 1)
    }
}

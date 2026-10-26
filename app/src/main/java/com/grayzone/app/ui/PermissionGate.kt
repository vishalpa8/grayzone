package com.grayzone.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets any feature say "I only work with the core permissions" without knowing how they are asked.
 * [requireSetup] runs the action straight away when Accessibility and Display over other apps are
 * granted; otherwise it shows the permission popup and runs the action once both are granted
 * (or drops it if the user declines).
 */
class PermissionGate(val requireSetup: (action: () -> Unit) -> Unit)

val LocalPermissionGate = staticCompositionLocalOf { PermissionGate { action -> action() } }

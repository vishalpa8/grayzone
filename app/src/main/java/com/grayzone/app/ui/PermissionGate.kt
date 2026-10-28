package com.grayzone.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets any feature say "I need Grayzone's permissions" without knowing how they are asked.
 * [requireSetup] runs the action straight away when every setup permission (notifications,
 * usage access, display over other apps, battery) is granted; otherwise it walks the user through
 * the missing ones and runs the action only if all of them end up granted.
 */
class PermissionGate(val requireSetup: (action: () -> Unit) -> Unit)

val LocalPermissionGate = staticCompositionLocalOf { PermissionGate { action -> action() } }

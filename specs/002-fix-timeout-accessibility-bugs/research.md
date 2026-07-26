# Phase 0 Research: Fix Session Timeout Lock & Accessibility Permission Stability

This document resolves the unknowns behind the two defects and records the design decisions that the
Phase 1 artifacts build on. Evidence is drawn from the current implementation in
`app/src/main/java/com/grayzone/app/service/`.

---

## R1. Why the session countdown goes negative

**Decision**: Stop the ongoing session countdown from ever rendering negative by driving a reliable
expiry event that cancels/replaces the countdown notification exactly at `activeUntil` (clamped to
`00:00`), instead of relying on the lock overlay to incidentally clear it.

**Evidence / root cause**:
- The visible per-session countdown is the notification built in
  `OverlayService.startCountdownNotification()`:

```555:560:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        val baseElapsed = SystemClock.elapsedRealtime() + remainingMs
        val body = RemoteViews(applicationContext.packageName, R.layout.notification_countdown).apply {
            setTextViewText(R.id.notif_title, appName)
            setChronometerCountDown(R.id.notif_timer, true)
            setChronometer(R.id.notif_timer, baseElapsed, null, true)
        }
```

- An Android `Chronometer` in count-down mode is **self-rendering off `SystemClock.elapsedRealtime()`
  and does not stop at its base** — once the base time passes it displays negative durations
  (e.g. `-01:23`). Nothing in the code updates or cancels the notification when `activeUntil` is
  reached, so the timer keeps counting into the negative until something else cancels it.
- The only thing that clears it today is showing a lock-type overlay, which calls
  `cancelCountdownNotification()` (lock presentations have `showsProgress == false`). When the lock
  fails to appear (see R2), the negative counter persists.

**Alternatives considered**:
- *Clamp inside the Chronometer*: there is no reliable framework hook to freeze a countdown
  Chronometer at zero across OEM skins → rejected.
- *A UI coroutine that cancels the notification*: same reliability problem as the lock (frozen in
  Doze) → rejected as the primary mechanism; the reliable expiry event (R2) is the single source.

---

## R2. Why the app does not lock at session end (and how to make it reliable)

**Decision**: Enforce session expiry through a **Doze-safe `AlarmManager`** callback keyed to
`activeUntil` per monitored package, **re-armed on service (re)start and on boot**, with the existing
**enforce-on-next-foreground** policy path retained as a backstop. On fire, a pure
`SessionExpiryDecision` decides whether to clamp the countdown notification and/or show the lock
overlay (only if the monitored app is still foreground).

**Evidence / root cause**:
- The lock is scheduled only in memory via a coroutine `delay()`:

```512:523:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    private fun scheduleLockoutCheck(packageName: String, delayMs: Long) {
        lockoutJobs[packageName]?.cancel()
        lockoutJobs[packageName] = serviceScope.launch {
            delay(delayMs)
            val currentPkg = lastForegroundPackage
            if (currentPkg == packageName) {
                val prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + packageName, 0L)
                launch(Dispatchers.Main) { showOverlay(packageName, getAppName(packageName), OverlayMode.LOCK, lockedUntil) }
            }
        }
    }
```

  - `delay()` runs on `serviceScope` (`Dispatchers.IO`); coroutine timers do **not wake the device**
    and are deferred while the CPU is suspended in **Doze/screen-off**, so a session that elapses
    while the screen is off fires late or not at all — while the elapsed-realtime notification keeps
    ticking (now negative).
  - `lockoutJobs` is **in-memory only**; `OverlayService` is `START_STICKY`, so after process death
    the service restarts but the scheduled lock is gone and is never re-armed for an in-flight
    session (`onCreate`/`onStartCommand` do not reschedule).
  - The lock also only shows when `lastForegroundPackage == packageName` at fire time; that guard is
    correct, but combined with the frozen timer it means "counting negative, never locks".
- The policy engine already locks correctly **on the next foreground transition** (so re-opening a
  monitored app after expiry does lock), which is why the failure is most visible when the user stays
  in the app with the screen going on/off:

```61:65:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
                // Currently locked out? (Penalty box)
                if (state.isLockedOut(now)) {
                    commands.add(SessionCommand.ShowLockoutScreen(state.packageName, appName, state.lockedUntil))
                    return commands
                }
```

**AlarmManager API choice (Android 12+ exact-alarm policy)**:
- **Chosen**: `AlarmManager.setAndAllowWhileIdle()` (Doze-safe, delivered in Doze maintenance
  windows) as the default because it requires **no new user-facing permission**. Session locking
  tolerates the small worst-case Doze delay, and the enforce-on-next-foreground backstop covers the
  gap when the user actually interacts.
- **Optional upgrade**: if `AlarmManager.canScheduleExactAlarms()` is already true (or the device is
  pre-Android-12), use `setExactAndAllowWhileIdle()` for tighter timing. Do **not** add a runtime
  request for `SCHEDULE_EXACT_ALARM`; do not declare `USE_EXACT_ALARM` (policy-restricted to
  clock/alarm apps) to keep scope and Play-policy risk low.
- Existing manifest already holds `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE*`, and
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; no dangerous permission is added.

**Alternatives considered**:
- *Keep `delay()` but add a `WakeLock`*: holding wake locks for a full 10–60 min session is
  battery-hostile and fights Doze → rejected.
- *`WorkManager` one-shot*: minimum latency and Doze batching make it unsuitable for a precise
  session boundary → rejected in favor of `AlarmManager`.
- *Poll on a timer*: contradicts the project's event-driven, ~95%-battery-savings design → rejected.

---

## R3. Behavior that MUST be preserved (regression guard)

**Decision**: The fix changes only expiry/lock timing reliability and the countdown display at zero.
The following are explicitly preserved and covered by existing tests:
- Friction wait-screen countdown (seconds) already stops at `0` (`while (secondsRemaining > 0)`);
  unchanged.
- Session pause/resume on background/foreground (`SessionPolicyEngine` `UpdateState` /
  `sessionRemainingMs`), budget lock, schedule lock, and midnight reset flows.
- The `OverlayPresentation` strategy and lock/friction/budget/schedule screens.

**Rationale**: Spec FR-008 and Constitution Principles V/VI require all other behavior to remain
identical and verified.

---

## R4. Why the Accessibility permission is auto-revoked / re-requested repeatedly

**Decision**: Treat this as two separable problems and fix both: (a) ensure Grayzone is **not the
cause** of revocation (defensive hardening so the service cannot crash-loop or be an obvious battery
offender), and (b) fix the **app-side re-prompt loop** so the user is asked at most once per genuine
loss.

**Evidence / analysis**:
- The service itself is minimal and does not call `disableSelf()` anywhere (confirmed by search), so
  the app is not deliberately revoking the permission.
- Real-world causes of the OS disabling an accessibility service are: aggressive OEM battery
  management killing the process, the process crashing (Android disables services that misbehave),
  or being swiped/force-stopped. The app already surfaces a battery-optimization exemption in
  onboarding, which mitigates the battery-kill case.
- The **loop the user experiences** is app-driven: the gate and every `ON_RESUME` re-check flip to
  onboarding and re-open Accessibility settings whenever the service reads as disabled:

```59:63:app/src/main/java/com/grayzone/app/MainActivity.kt
    var currentScreen by remember {
        mutableStateOf(
            if (isAccessibilityServiceEnabled(context) && Settings.canDrawOverlays(context))
                Screen.MAIN else Screen.ONBOARDING
        )
    }
```

```40:45:app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageAccess = isAccessibilityServiceEnabled(context)
                hasOverlay = Settings.canDrawOverlays(context)
                hasBatteryOpt = pm.isIgnoringBatteryOptimizations(context.packageName)
                if (hasUsageAccess && hasOverlay && hasBatteryOpt) onContinue()
            }
```

**Fixes**:
1. **Prompt-once-per-loss** (`AccessibilityPromptState`, pure + unit-tested): record that the user
   has been prompted for the *current* loss; suppress further automatic prompts until the permission
   is observed granted again (which resets the flag). This converts the loop into a single prompt per
   genuine loss (spec FR-006/FR-007).
2. **Defensive hardening** of `GrayzoneAccessibilityService`: guard `onAccessibilityEvent`/
   `onServiceConnected`/`sendBroadcast` so no unhandled exception can crash the service (a crash is a
   revocation trigger). Keep the config minimal; drop the unused
   `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`/`flagRetrieveInteractiveWindows` if window retrieval is not
   actually used (it is not — only `event.packageName` is read), reducing overhead and the chance the
   OS treats the service as heavyweight. This is a behavior-preserving reduction of scope for the
   foreground-detection path (still emits the same `ACTION_APP_CHANGED` broadcasts).
3. **Encourage** (not force) the battery-optimization exemption already present in onboarding to
   reduce OS-driven kills; no new permission required.

**Alternatives considered**:
- *Auto-relaunch settings on every resume*: this **is** the current loop → rejected.
- *Silently ignore loss*: would disable enforcement without telling the user → rejected; a single
  clear prompt per loss is the balance.
- *A foreground watchdog that re-enables accessibility*: an app cannot re-grant its own accessibility
  permission programmatically → not possible.

**Residual investigation note**: The *exact* OEM revocation trigger is device-specific and can only
be confirmed on the affected hardware (logcat around the disable event). The design above removes the
app-side loop and the app-side revocation triggers regardless of the specific OEM cause; on-device
logcat confirmation is captured as a validation step in `quickstart.md`.

---

## Summary of Decisions

| # | Decision | Primary Mechanism |
|---|----------|-------------------|
| R1 | Countdown never shows negative | Reliable expiry event cancels/clamps the notification at `00:00` |
| R2 | Session reliably locks at expiry | Doze-safe `AlarmManager` (`setAndAllowWhileIdle`, exact if already permitted), re-armed on start/boot; foreground policy backstop |
| R3 | Preserve all other behavior | No changes to pause/resume, budget/schedule/midnight, friction countdown, presentation strategy |
| R4 | No accessibility re-prompt loop; app is not the revoker | `AccessibilityPromptState` (prompt-once-per-loss) + defensive service hardening + minimal config |

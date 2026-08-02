# Findings â€” RU-01: permission and setup state (T022)

**Feature**: `004-android-regression-audit` | **Task**: T022 | **Created**: 2026-07-31
**Reserved ID block**: F-080 â€¦ F-094 | **Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md)
**Register**: [`findings.md`](./findings.md) | **Format model**: [`findings-ru01.md`](./findings-ru01.md)

**Scope**: `SetupRequirements.kt`, `AccessibilityPromptState.kt`, and every surface they touch
(`AppUtils.isAccessibilityServiceEnabled`/`settingsIntentFor`/`isBatteryOptimized`, `MainActivity`,
`ui/HomeScreen.kt`, `ui/OnboardingScreen.kt`, `ui/VpnScreen.kt`, `service/OverlayService.kt`,
`data/ProtectionStatus.kt`, `data/ProtectionHealthRepository.kt`, `data/StateBackupManager.kt`,
`AndroidManifest.xml`) analysed against permission **denial**, **"don't ask again" / permanent
denial**, and **revocation mid-session**.

**Question this task answers**: for each protection-critical permission, is loss *detected* and
*surfaced to the user*, or does it fail silently?

## Already-owned findings cited here, never re-filed

F-209 (all-files prompt flag set before the prompt), F-210 (auto VPN re-consent dialog can fail to
open unrecorded), F-211 (eleven unguarded `startActivity`/service-start/`VpnService.prepare` calls on
UI paths), F-212 (unguarded `VpnService.prepare()` in `BootReceiver`), F-061 (`OemBatteryHelper`
never wired), F-073 (`SetupRequirements.actionLabel()` test-only while `HomeScreen` re-derives the
CTA inline), C1 (`OverlayService.kt:989â€“994` `addView` failure returns with no overlay), F-025
(`onRevoke()` not overridden in `AdBlockVpnService`).

---

### F-080 â€” Accessibility revocation is undetectable while the app is backgrounded, and the ongoing notification keeps asserting "Grayzone is active"  [Critical]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.buildNotification()` (lines 1189â€“1196) and `OverlayService.onCreate()` (lines
  140â€“176); the only detection sites are
  `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainAppContent.evaluateScreen()` (line 190)
  and `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()` (lines 42, 52), both
  bound to `Lifecycle.Event.ON_RESUME`.
- **Issue**: `isAccessibilityServiceEnabled()` is called from exactly three places
  (`MainActivity.kt:190`, `HomeScreen.kt:42,52`, `OnboardingScreen.kt:36,44`), and every one of them
  runs only when an Activity resumes. **No background component ever reads the permission.**
  `OverlayService` â€” the component that actually applies friction â€” never calls it, and its
  foreground notification is built once from two hardcoded strings, `"Grayzone is active"` /
  `"Monitoring app usage to help you stay focused."` (`:1191â€“1192`), which are never re-evaluated
  against permission state. When the accessibility service is revoked (user toggles it off, an OS
  update disables it, the OS disables it after a service crash â€” the exact scenario prior-fix P8
  guards against), `GrayzoneAccessibilityService` stops broadcasting `ACTION_APP_CHANGED`, so
  `OverlayService.handleForegroundAppChanged()` is never invoked and **no friction, lock, session
  expiry or budget enforcement fires again**. `OverlayService` keeps running, keeps its notification
  posted, and keeps saying protection is active. The only trace of the loss is
  `GrayzoneAccessibilityService.onUnbind()` (`:124â€“130`), a `GrayzoneLogger.w()` visible in logcat
  only â€” and it fires in the `:a11y` process, which no user surface reads. This is the app's worst
  failure mode as defined by the register's Grayzone calibration: protection stops, nothing crashes,
  and the persistent notification actively asserts the opposite.
- **Trigger condition**:
  1. Grant Accessibility + overlay, add a monitored app with a 1-minute limit, confirm friction
     appears when the app is opened.
  2. Press Home so Grayzone's Activity is not resumed (or `adb shell am force-stop` is **not**
     needed â€” the point is the Activity is simply backgrounded).
  3. Revoke the service: Settings â†’ Accessibility â†’ Grayzone â†’ off, or
     `adb shell settings put secure enabled_accessibility_services ""`.
  4. Pull down the shade: the notification still reads "Grayzone is active â€” Monitoring app usage to
     help you stay focused."
  5. Open the monitored app. No friction overlay, no lock, no countdown. No notification changes, no
     toast, no crash.
  6. The loss becomes visible only after step 7: return to Grayzone's Activity, at which point
     `evaluateScreen()`/`HomeScreen` finally re-read and surface it.
- **Blast radius**:
  - *Callers*: readers of `isAccessibilityServiceEnabled()` are `MainActivity.evaluateScreen()`
    (`:190`), `HomeScreen()` (`:42`, `:52`), `OnboardingScreen()` (`:36`, `:44`) â€” verified by direct
    `Grep` over `app/src`, no other call sites in main or test source. Consumers of the resulting
    boolean: `SetupRequirements.missing()`, `AccessibilityPromptPolicy.onObserved()`.
    `buildNotification()` callers: `OverlayService.onCreate():144` and
    `cancelCountdownNotification():755`.
  - *Process death*: makes it worse. After the default process dies and the `:a11y` watchdog restarts
    `OverlayService`, `onCreate()` re-posts the same unconditional "Grayzone is active" notification
    (`:142â€“144`) with no permission check, so a restart re-asserts health it never verified. Note the
    watchdog itself is driven by accessibility events, so once accessibility is revoked the watchdog
    is also dead â€” nothing restarts anything.
  - *Config change*: none directly; a rotation of `MainActivity` re-runs `evaluateScreen()` only
    because it is in the composition, which is the same foreground-only path.
  - *Foreground/background*: this **is** the defect axis. Detection exists only in the foreground;
    enforcement runs only in the background. The two never overlap.
  - *Cross-process*: yes, and it is load-bearing. The uncommitted `android:process=":a11y"`
    (`AndroidManifest.xml:82`) puts the only component that observes the revocation
    (`onUnbind`, `:124â€“130`) in a separate process from the component that posts the notification
    (`OverlayService`, default process). Any fix must cross that boundary with real IPC, not a shared
    singleton â€” see [`evidence/cross-process-inventory.md`](./evidence/cross-process-inventory.md).
  - *Persistence*: a fix that persists a "last known accessibility state" writes SharedPreferences,
    which **must** stay in the default process (`GrayzoneApplication.kt:52â€“54` deliberately skips all
    prefs work in `:a11y`) and will be captured by `StateBackupManager.writeBackup`'s `prefs.all`
    snapshot (`StateBackupManager.kt:167`).
  - *Release-only*: no â€” but note the notification text is the *only* field-observable signal, since
    `BuildConfig.DEBUG` is `false` in both variants, so `GrayzoneLogger.d()`/`.i()` emit nothing (see
    F-064). A fix that logs instead of changing the notification produces no user-visible change at
    all.
- **Fix** (minimal): make the ongoing notification honest and re-evaluate it on a tick the app
  already has. In `OverlayService`, (a) change `buildNotification()` to read
  `isAccessibilityServiceEnabled(this)` and `Settings.canDrawOverlays(this)` and return a
  `GZ_AMBER`-coloured "Protection is OFF â€” Accessibility is disabled" notification with a
  `PendingIntent` to `settingsIntentFor()` when either is false; (b) re-post it via
  `nm.notify(NOTIF_ID, buildNotification())` from `handleForegroundAppChanged()` and from
  `onStartCommand`'s `ACTION_APP_CHANGED_WATCHDOG` branch (`:673â€“675`), which already runs on every
  app switch. No new alarm, receiver, or thread is required. Because accessibility revocation also
  stops those broadcasts, add the same re-post to the existing midnight-reset loop
  (`scheduleMidnightReset()`, `:243â€“255`) so a fully-silent device still corrects the claim within a
  day; a `ScreenOn` addition to the existing `screenReceiver` (`:204â€“227`) is the cheaper alternative.
- **Rewrite justification**: n/a â€” the minimal patch above is sufficient; no component is replaced.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/ProtectionNotificationStateTest.kt`, over a new pure function
  `ProtectionNotificationState.resolve(accessibilityEnabled: Boolean, overlayEnabled: Boolean)`
  extracted for testability, asserting `resolve(false, true).isWarning == true` and
  `resolve(false, true).title == "Protection is OFF"` while `resolve(true, true).title ==
  "Grayzone is active"`. Fails before the fix (the function does not exist and the notification text
  is unconditional), passes after.
- **Existing test conflict**: none. `SetupRequirementsTest` and `AccessibilityPromptStateTest` assert
  only pure mappings and are unaffected. No test asserts notification content anywhere in
  `app/src/test` or `app/src/androidTest`.
- **Verification**: `confirmed` (evidence tier T1). Established from source alone: the three
  `isAccessibilityServiceEnabled` call sites are all `ON_RESUME`-bound (direct `Grep`, no other
  matches under `app/src`); `buildNotification()` (`:1189â€“1196`) takes no arguments and reads no
  permission state; `OverlayService` contains no `isAccessibilityServiceEnabled` reference.
  - *Missing*: n/a for the mechanism. Only the *user-perceived* timing (how long the stale
    notification persists on a specific OEM) would need a device.
  - *Procedure*: steps 1â€“6 above on any API 29+ device; no root or adb required beyond the optional
    `settings put` shortcut.
  - *Severity effect*: Critical stands regardless of device outcome â€” the absence of any background
    reader is a static property of the code, not a runtime question.
- **Build scope**: everyday-build
- **Constitution**: III (a protection-critical state transition crosses a system boundary with no
  failure handling and no user-visible signal), IV (the observing component and the reporting
  component live in different processes with no channel between them)
- **Affected surfaces**: the same foreground-only detection shape applies to overlay
  (`Settings.canDrawOverlays` at `MainActivity.kt:191`, `HomeScreen.kt:43,53`,
  `OnboardingScreen.kt:37,45`) and battery exemption (`isBatteryOptimized` at `HomeScreen.kt:44,54`;
  `pm.isIgnoringBatteryOptimizations` at `OnboardingScreen.kt:38,46`) â€” see F-087. The overlay leg has
  one partial in-service check at `OverlayService.kt:790`, which F-081 and F-082 show is broken.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AndroidManifest.xml,
  GrayzoneAccessibilityService.kt, OverlayService.kt, GrayzoneApplication.kt, build.gradle.kts)`
- **Disposition**: `fix-pending-device-verification`

---

### F-081 â€” Overlay-revocation recovery deep-links to the *monitored app's* settings page, not Grayzone's, because the parameter shadows `Context.getPackageName()`  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.showOverlay()` (line 809, inside the revocation branch at lines 786â€“823; function
  signature at line 787)
- **Issue**: The revoked-overlay recovery path builds its settings intent as
  `android.net.Uri.parse("package:$packageName")`. Inside `showOverlay(packageName: String, appName:
  String, mode: Int, lockedUntil: Long)`, `packageName` is the **function parameter** â€” the monitored
  app whose friction screen was about to be shown â€” and it shadows the inherited
  `Context.getPackageName()` that would have returned `com.grayzone.app`. Every one of the eight call
  sites passes a third-party package (`:282` `currentPkg`, `:493`/`:498`/`:503`/`:508`
  `cmd.packageName`, `:612` `pkg`, `:653` `packageName` from the expiry path, `:1028` `packageName`),
  so the intent is e.g. `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:com.instagram.android`. The
  user, having just been told by the toast that *Grayzone* needs "Display over other apps", is
  dropped on **Instagram's** overlay-permission screen â€” which on most devices already reads
  "Allowed". They will conclude the permission is fine and back out; Grayzone still cannot draw, and
  every subsequent monitored-app launch repeats the same wrong-page loop. Contrast
  `AppUtils.settingsIntentFor()` (`AppUtils.kt:135`), which correctly uses
  `context.packageName` â€” the two code paths for the same recovery disagree.
- **Trigger condition**:
  1. Grant Accessibility + overlay; add `com.instagram.android` (or any installed app) as monitored.
  2. Revoke only the overlay permission:
     `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`.
  3. Open the monitored app so `handleForegroundAppChanged` â†’ `showOverlay()` runs.
  4. Observe the toast naming Grayzone, then observe the settings screen that opens (if it opens at
     all â€” see F-082): its header names the monitored app, not Grayzone. `adb shell dumpsys activity
     activities | findstr mCurrentFocus` confirms the package in the launched settings screen.
- **Blast radius**:
  - *Callers*: all eight `showOverlay(...)` call sites listed above, enumerated by direct `Grep` for
    `showOverlay` over `app/src` (no other matches, main or test). Every one reaches this branch when
    `canDrawOverlays` is false.
  - *Process death*: none â€” the intent is constructed per-invocation from a parameter; no state
    survives or is lost.
  - *Config change*: none â€” no Activity or composition state involved.
  - *Foreground/background*: the branch always runs from a background service, which is why the wrong
    target is never noticed by a developer testing from the foreground; it also compounds F-082.
  - *Cross-process*: reached only via the `ACTION_APP_CHANGED` broadcast from `:a11y`
    (`GrayzoneAccessibilityService.kt:86â€“89`) and the watchdog `startForegroundService`
    (`:93â€“99`); the defect itself is entirely in the default process.
  - *Persistence*: none â€” nothing is written.
  - *Release-only*: no. R8 does not affect it, and the toast/`w()` log emit identically in release
    (`w()` is one of the two levels that survive, per F-064).
- **Fix** (minimal): replace the interpolated parameter with the app's own package and reuse the
  existing helper so the two recovery paths cannot diverge again â€” at `OverlayService.kt:806â€“813`,
  call `com.grayzone.app.settingsIntentFor(this, SetupRequirement.OVERLAY).addFlags(
  Intent.FLAG_ACTIVITY_NEW_TASK)` instead of hand-building the intent. As a defence against
  recurrence, rename the parameter to `targetPackage` so `packageName` unambiguously resolves to
  `Context.getPackageName()` throughout the function.
- **Rewrite justification**: n/a â€” a one-line substitution plus a parameter rename.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/SettingsIntentTargetTest.kt`, asserting that the URI produced
  for `SetupRequirement.OVERLAY` is `package:com.grayzone.app` for a context stubbed with
  `packageName = "com.grayzone.app"`, and â€” after the fix routes `OverlayService` through the same
  helper â€” that no overlay-settings intent in the codebase interpolates a monitored package. The
  behavioural half is only meaningful once `showOverlay` delegates, so the test locks in the
  delegation. Fails before the fix (`OverlayService` builds its own intent from the parameter),
  passes after.
- **Existing test conflict**: none. `SetupRequirementsTest` covers only string mapping;
  `settingsIntentFor` has no existing test.
- **Verification**: `confirmed` (evidence tier T1). Read directly: the parameter name at
  `OverlayService.kt:787` and the interpolation at `:809` are in the same scope, and Kotlin resolves
  `$packageName` to the nearest declaration â€” the parameter. All eight call sites were enumerated and
  every one passes a monitored app's package.
  - *Missing*: n/a for the mechanism. Only the OEM-specific rendering of an
    `ACTION_MANAGE_OVERLAY_PERMISSION` intent aimed at a foreign package would need a device (some
    OEMs fall back to the global list rather than the named app's page).
  - *Procedure*: steps 1â€“4 above on any API 29+ device.
  - *Severity effect*: `High` if the wrong app's page opens (the confirmed static reading) or if the
    OEM silently falls back to the global overlay list, since in both cases the user is misdirected
    on a protection-critical recovery. Drops to `Medium` only if a device is found where the foreign
    package URI is ignored and Grayzone's own page opens, which no documented behaviour supports.
- **Build scope**: everyday-build
- **Constitution**: II (the same recovery is expressed twice, and the copies disagree), III (the
  failure branch's remediation is itself unverified)
- **Affected surfaces**: `AppUtils.settingsIntentFor()` (`AppUtils.kt:129â€“137`) is the correct
  implementation this site should be using; `HomeScreen.kt:187` and `OnboardingScreen.kt:156` are the
  two UI paths that already target the right package.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt)` â€” the surrounding
  block is inside the largest uncommitted diff, but `git diff` shows the revocation branch itself is
  unchanged, so this is a pre-existing defect, not a regression from the working tree.
- **Disposition**: `verified`

---

### F-082 â€” The overlay-revocation recovery `startActivity` is a background activity start that is blocked without throwing, so the "open Settings" step silently never happens  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.showOverlay()` (lines 806â€“820)
- **Issue**: The recovery calls `startActivity(intent)` from a `Service`, with the app in the
  background. Since Android 10 (API 29 â€” this project's `minSdk`), background activity starts are
  blocked unless the app qualifies for an exemption, and a running foreground service is **not** one
  of them. The one exemption that would have applied is holding `SYSTEM_ALERT_WINDOW` â€” which is
  precisely the permission whose absence put us in this branch. Critically, a blocked background
  activity start **does not throw**: the framework logs `Background activity start â€¦ blocked` and
  returns normally. So the `catch (e: Exception)` at `:814â€“820` never runs, `GrayzoneLogger.e` never
  emits, and the code's own "Try to open settings for user to re-grant" comment (`:805`) describes
  something that does not occur. The user is left with a toast telling them to go to Settings and no
  route there, on every single monitored-app launch, while the ongoing notification continues to say
  protection is active (F-080). This is a distinct root cause from F-081: fixing the package URI does
  not make the activity start succeed, and fixing the activity start does not correct the target.
- **Trigger condition**:
  1. Grant Accessibility + overlay; add a monitored app.
  2. `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`.
  3. Press Home so Grayzone has no visible window, then open the monitored app.
  4. Observe the toast appears but no Settings screen opens.
  5. `adb logcat | findstr /i "Background activity start"` shows the block; `adb logcat -s
     GrayzoneOverlay` shows the `w()` "Overlay permission revoked" line but **no** `e()` "Failed to
     open overlay settings" line, proving the catch did not fire.
- **Blast radius**:
  - *Callers*: same eight `showOverlay(...)` call sites as F-081, all of which reach this branch when
    the overlay permission is missing.
  - *Process death*: none â€” nothing is queued or persisted, so a restart simply repeats the blocked
    start on the next app switch.
  - *Config change*: none.
  - *Foreground/background*: this **is** the defect axis. The identical call succeeds when Grayzone
    has a visible window, which is exactly the condition under which a developer would test it, and
    never holds in production where the branch actually fires.
  - *Cross-process*: reached from `:a11y` via `ACTION_APP_CHANGED`
    (`GrayzoneAccessibilityService.kt:86â€“89`); the start itself is issued by the default process.
  - *Persistence*: none today. The fix introduces one â€” a full-screen-intent or content-intent
    notification is the correct replacement and requires no prefs, but see F-086: if
    `POST_NOTIFICATIONS` is denied, the notification-based fix is itself invisible, so the fix must
    also address that.
  - *Release-only*: no. Note that only `w()`/`e()` emit at all (F-064), so a fix that merely adds
    logging is field-observable but not user-observable.
- **Fix** (minimal): replace the background `startActivity` with a notification the user can tap.
  Post a high-importance notification on a distinct id (not `NOTIF_ID`, which the countdown reuses)
  with `setContentIntent(PendingIntent.getActivity(this, 0, settingsIntentFor(this,
  SetupRequirement.OVERLAY).addFlags(FLAG_ACTIVITY_NEW_TASK), FLAG_IMMUTABLE or
  FLAG_UPDATE_CURRENT))` and `setAutoCancel(true)`. This also lands the F-080 fix for the overlay leg.
  Keep the toast. If the `startActivity` is retained as a best-effort first attempt, it must be
  followed by the notification unconditionally, because its failure cannot be detected from the
  return value or an exception.
- **Rewrite justification**: n/a â€” the branch is replaced call-for-call; `showOverlay` and its
  callers are untouched.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/OverlayPermissionRecoveryTest.kt`, over a small
  extracted `OverlayRecoveryPlan.forRevokedOverlay()` returning the actions to take, asserting the
  plan contains `Action.PostSettingsNotification` and does **not** rely solely on
  `Action.StartSettingsActivity`. Fails before the fix (no plan type exists and the only action is
  the activity start), passes after.
- **Existing test conflict**: none â€” `OverlayService` has no unit tests; `OverlayPresentationTest`
  covers only presentation mapping.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device confirmation that the start is blocked rather than allowed. The static facts
    (call issued from a `Service` with no visible window; `SYSTEM_ALERT_WINDOW` denied by
    construction in this branch; no other exemption held) are confirmed from source, but Android's
    background-activity-start allowlist has OEM-specific additions.
  - *Procedure*: steps 1â€“5 above on an API 29+ device, ideally on both a Pixel/AOSP build and one
    OEM build. The decisive artifact is the absence of the `e()` log line in step 5 alongside the
    framework's `Background activity start` line.
  - *Severity effect*: `High` if confirmed blocked â€” a protection-critical recovery affordance that
    silently does nothing and cannot be diagnosed. Drops to `Low` (dead defensive code) if the start
    is in fact permitted on all target devices, in which case only F-081's wrong target remains.
- **Build scope**: everyday-build
- **Constitution**: III (a system-boundary call whose failure mode is neither an exception nor a
  return value, and is therefore handled by a `catch` that can never fire)
- **Affected surfaces**: `MainActivity.requestAllFilesAccessOnce()` (`MainActivity.kt:93â€“109`) has the
  same shape â€” two nested `startActivity` attempts in `catch (_: Exception)` â€” but is called from
  `MainActivity.onResume` and `MainScreen`'s `LaunchedEffect`, both foreground, so it is not subject
  to this block. Its own defect is the pre-set flag, already owned as F-209. The unguarded UI-path
  `startActivity` calls are owned as F-211.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt)` â€” branch itself
  unchanged by the working-tree diff.
- **Disposition**: `fix-pending-device-verification`

---

### F-083 â€” `ProtectionStatus.overlayActive` reports service liveness while its name and its use in `isFullyProtected` claim permission health, so the health model has no permission input at all  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.onCreate()` (line 142) and `OverlayService.onDestroy()` (line 760), against
  `app/src/main/java/com/grayzone/app/data/ProtectionStatus.kt` â†’ `ProtectionStatus.isFullyProtected`
  / `isDegraded` (lines 10â€“14)
- **Issue**: `updateOverlayStatus(true)` is called unconditionally in `onCreate` and
  `updateOverlayStatus(false)` in `onDestroy`. The flag therefore means "the `OverlayService` object
  exists", not "Grayzone can draw an overlay" â€” no call site anywhere consults
  `Settings.canDrawOverlays()` before setting it (verified by `Grep`: the only `canDrawOverlays`
  reference inside any service is the `showOverlay` guard at `:790`, which does not touch the
  repository). Revoke `SYSTEM_ALERT_WINDOW` and `overlayActive` stays `true` for as long as the
  service runs, which is indefinitely. `ProtectionStatus` then computes `isFullyProtected =
  vpnActive && overlayActive && accessibilityActive` over a field that cannot express permission
  loss. Combined with `accessibilityActive`, which has **no production writer at all** (the two
  `updateAccessibilityStatus` calls were deleted from `GrayzoneAccessibilityService` by the
  uncommitted diff â€” already recorded as a Phase-1 lead in
  [`evidence/cross-process-inventory.md`](./evidence/cross-process-inventory.md) and
  [`evidence/prior-fix-watchlist.md`](./evidence/prior-fix-watchlist.md), owner T020/T029, not
  re-filed here), **zero of the app's seven protection-critical permissions is an input to the app's
  own protection-health model.** Two of its three fields are structurally incapable of reporting
  permission loss. The user-visible consequence is latent today because no screen reads
  `isFullyProtected`/`isDegraded` â€” confirmed by direct `Grep`, whose only matches outside the
  declaration are in `ProtectionStatusTest.kt:19â€“47` â€” but that is exactly the trap: the field's name
  invites the F-080 fix to be built on it, and doing so would render a green "Fully protected" badge
  while the overlay permission is revoked.
- **Trigger condition**:
  1. Start Grayzone so `OverlayService` runs; confirm friction works.
  2. `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`.
  3. Attach a debugger, or temporarily add `Text(ProtectionHealthRepository.status.value.toString())`
     to `HomeScreen`, and read the flow: `overlayActive` is still `true`.
  4. Equivalently and without a device: `updateOverlayStatus` has exactly two call sites
     (`OverlayService.kt:142`, `:760`), neither of which reads a permission, so no revocation can
     ever change the value.
- **Blast radius**:
  - *Callers*: writers of `overlayActive` â€” `OverlayService.onCreate():142`,
    `OverlayService.onDestroy():760`, plus `ProtectionStatusTest.kt:13,40`. Readers of the derived
    predicates â€” **none in `app/src/main`**; only `ProtectionStatusTest.kt:19,22,28â€“33,44,47`.
    Readers of sibling fields: `VpnScreen.kt:52â€“53` (`vpnActive`, `vpnReconnectPending`),
    `MainActivity.kt:137` (`vpnActive`). Re-verified by direct `Grep` over `app/src` rather than
    trusting `evidence/reference-index.md`, per the index's accuracy warning â€” the type and both
    predicates are **live declarations and must not be deleted**; the defect is a wrong input, not
    dead code.
  - *Process death*: total loss. `ProtectionHealthRepository` is an in-memory `object` with a
    `MutableStateFlow` and no persistence, so after the default process dies every field resets to
    `false` and `isDegraded` becomes `false` â€” i.e. the model reports "nothing is wrong" and "nothing
    is protected" identically. A fix must not treat the default state as authoritative.
  - *Config change*: none â€” the repository is process-scoped, not Activity-scoped, so rotation is
    transparent.
  - *Foreground/background*: `overlayActive` is written from a background service and read from
    foreground composables; the write happens exactly once per service lifetime, so a background
    revocation is never reflected.
  - *Cross-process*: yes, and it is why the accessibility leg is broken. The repository is a plain
    Kotlin `object`, so the `:a11y` process would get its **own** instance
    (`AndroidManifest.xml:82`, uncommitted). Any fix that restores an accessibility writer must not
    call the repository from `GrayzoneAccessibilityService` â€” that would update a copy nothing reads.
    The correct source for the accessibility leg is `isAccessibilityServiceEnabled()` read in the
    default process.
  - *Persistence*: none today, and the fix should keep it that way â€” deriving the flags from live
    permission reads avoids adding keys that `StateBackupManager.writeBackup`'s `prefs.all` snapshot
    (`StateBackupManager.kt:167`) would then capture and restore stale.
  - *Release-only*: no â€” but R8 currently removes nothing here only because the test source keeps the
    predicates referenced; if `isFullyProtected`/`isDegraded` remain unread by main source they are
    candidates for shrinking, which would mask the gap further. See `evidence/r8.md`.
- **Fix** (minimal): make the two lifecycle fields report the permission rather than the object.
  Replace `updateOverlayStatus(true)` at `OverlayService.kt:142` with
  `updateOverlayStatus(Settings.canDrawOverlays(this))`, and re-post the same read from
  `handleForegroundAppChanged()` and the `ACTION_APP_CHANGED_WATCHDOG` branch (`:673â€“675`) â€” the same
  tick the F-080 fix uses, so both land in one change. For the accessibility leg, write
  `updateAccessibilityStatus(isAccessibilityServiceEnabled(this))` from the **default** process on
  the same tick, never from `:a11y`. Rename nothing; the field names become accurate once the inputs
  are correct.
- **Rewrite justification**: n/a â€” two call-site edits plus one added read.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` (extend the existing class),
  adding `overlay permission loss clears overlayActive`: seed
  `ProtectionHealthRepository.updateOverlayStatus(true)`, then `updateOverlayStatus(false)`
  simulating the permission read, and assert `status.value.isFullyProtected == false` and
  `isDegraded == true`. Because the current code never produces the second call while the service
  lives, the meaningful assertion is on the extracted read â€” add
  `ProtectionInputs.resolveOverlay(canDrawOverlays: Boolean, serviceRunning: Boolean)` and assert
  `resolveOverlay(canDrawOverlays = false, serviceRunning = true) == false`. Fails before the fix
  (the function does not exist; liveness alone determines the flag), passes after.
- **Existing test conflict**: **yes** â€” `ProtectionStatusTest.kt:36â€“47`
  (`repository updates propagate`) drives `updateOverlayStatus(true)` directly and asserts
  `isFullyProtected` becomes true after `updateAccessibilityStatus(true)`. It will still pass, but it
  is the test that concealed this defect (and the deleted-writer defect) by exercising the repository
  instead of its producers. It must be extended, not merely kept: add the assertion that the
  producers read a permission, per the test above.
- **Verification**: `confirmed` (evidence tier T1). Both writers read directly; neither consults a
  permission API. Zero production readers of `isFullyProtected`/`isDegraded` confirmed by direct
  `Grep` over `app/src`.
  - *Missing*: n/a.
  - *Procedure*: step 4 above is a source-only check; steps 1â€“3 are optional device corroboration.
  - *Severity effect*: `Medium` as shipped â€” the value is wrong but unread, so there is no current
    user-visible consequence, and the rubric reserves High for degraded protection. Rises to `High`
    the moment any screen or notification renders `isFullyProtected`/`isDegraded`, because it would
    then assert full protection while the overlay permission is revoked. The F-080 fix is exactly
    such a wiring, so **F-083 must be fixed in the same change as F-080 or not at all.**
- **Build scope**: everyday-build
- **Constitution**: II (a field named for a permission is fed by a lifecycle signal, so the same
  concept has two divergent meanings), IV (a process-scoped `object` used as if it were global state)
- **Affected surfaces**: `ProtectionStatus.accessibilityActive` (no production writer â€” Phase-1 lead,
  owner T020/T029) and `vpnReconnectPending` (`AdBlockVpnService.kt:319,336`), the only field with a
  genuine failure-driven writer, which is the model this fix should follow.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt,
  GrayzoneAccessibilityService.kt, AndroidManifest.xml)` â€” the missing accessibility writer
  originates in the uncommitted `GrayzoneAccessibilityService.kt` diff (`git diff` removes both
  `updateAccessibilityStatus` calls); the `overlayActive` defect predates it.
- **Disposition**: `verified`

---

### F-084 â€” `PACKAGE_USAGE_STATS` is declared with a lint suppression but is never requested, checked, or used, so a protected permission ships for a capability the app does not have  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/AndroidManifest.xml` (line 24) â€” `<uses-permission
  android:name="android.permission.PACKAGE_USAGE_STATS" â€¦ tools:ignore="ProtectedPermissions" />`
- **Issue**: The permission is declared and its lint error is explicitly suppressed, but nothing in
  the app uses it. Direct `Grep` over `app/src` for `UsageStatsManager`, `queryUsageStats`,
  `usagestats`, `AppOpsManager`, and `checkOpNoThrow` returns **zero** matches in main and test
  source; the only other occurrences of the words "UsageStatsManager" are two comments
  (`GrayzoneAccessibilityService.kt:20`, `AndroidManifest.xml:76`) explaining that the accessibility
  service *replaced* usage-stats polling. So the answer to "is loss of `PACKAGE_USAGE_STATS`
  detected and surfaced?" is that there is nothing to detect: the app never held the appop (it is a
  `signature|appop` permission granted only via Settings â†’ Special app access â†’ Usage access, and
  the app never sends the user there â€” `ACTION_USAGE_ACCESS_SETTINGS` has zero matches). The
  consequences are real even though no runtime path depends on it: `PACKAGE_USAGE_STATS` is one of
  the permissions Play's sensitive-permissions review and on-device scanners flag, and the manifest's
  own `<queries>` comment (`:4â€“9`) shows the project deliberately avoids exactly this class of
  declaration for `QUERY_ALL_PACKAGES` â€” the two decisions contradict each other. The suppression
  comment is also absent, so a future reader cannot tell whether the declaration is vestigial or
  load-bearing.
- **Trigger condition**:
  1. Build and install the APK.
  2. `adb shell dumpsys package com.grayzone.app | findstr /i "USAGE_STATS"` â€” the permission is
     requested and not granted.
  3. Settings â†’ Special app access â†’ Usage access â†’ Grayzone reads "Not allowed".
  4. Use the app normally: every feature works, because no code path reads usage stats.
  5. Source-only equivalent: `Grep` `app/src` for `UsageStatsManager|queryUsageStats|AppOpsManager`
     â†’ zero matches.
- **Blast radius**:
  - *Callers*: **none.** Enumerated by direct `Grep` over `app/src` (main and test) for
    `PACKAGE_USAGE_STATS`, `UsageStatsManager`, `queryUsageStats`, `AppOpsManager`, `checkOpNoThrow`
    â€” the only match is the manifest declaration itself. Re-verified by direct search rather than
    from `evidence/reference-index.md`, per the index's accuracy warning. Note this is a **manifest
    declaration**, not a Kotlin declaration, so the "never delete without proof" rule about live code
    does not apply in the same way â€” but the removal is still gated on the maintainer confirming no
    planned feature needs it.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none â€” the declaration is app-wide and both processes inherit it equally.
  - *Persistence*: none.
  - *Release-only*: partially. `lint { checkReleaseBuilds = false }` in the uncommitted
    `app/build.gradle.kts:102â€“108` removes the release lint gate, so any future lint rule about
    unused or sensitive permissions would no longer fail the release build. That disabled gate is
    owned by RU-08; it is cited here only because it is why this declaration would not be caught
    later.
- **Fix** (minimal): delete line 24 of `app/src/main/AndroidManifest.xml`, including the
  `tools:ignore="ProtectedPermissions"` suppression it carries. If the maintainer intends to add a
  usage-statistics feature, keep the line but add a comment stating that and open a task â€” an
  undocumented suppressed protected permission is the actual defect.
- **Rewrite justification**: n/a â€” one line removed.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/ManifestPermissionsTest.kt`, reading
  `app/src/main/AndroidManifest.xml` from the test source set and asserting the set of declared
  `uses-permission` names equals an explicit expected set that excludes `PACKAGE_USAGE_STATS`. Fails
  before the fix, passes after, and pins every future permission addition to a deliberate test
  change. (A plain file-parsing JVM test; it needs no Android framework stubs, so it is unaffected by
  the missing `testOptions { unitTests.isReturnDefaultValues = true }` noted for T025.)
- **Existing test conflict**: none â€” no test reads the manifest today.
- **Verification**: `confirmed` (evidence tier T1). Zero code references established by direct
  `Grep` over `app/src`.
  - *Missing*: n/a for the "unused" claim. Only the Play-policy consequence is external to this
    repository.
  - *Procedure*: steps 1â€“5 above; step 5 alone is decisive for the finding as written.
  - *Severity effect*: `Medium` as filed, because the app ships a suppressed protected-permission
    request it cannot justify and the project's own `<queries>` rationale argues against it. Drops to
    `Low` if the maintainer confirms the Play listing already declares this permission and no removal
    is wanted â€” it is then pure manifest cleanliness.
- **Build scope**: everyday-build
- **Constitution**: I (a declaration with no implementation behind it), V (the suppression asserts a
  justification that is nowhere stated)
- **Affected surfaces**: `ui/OnboardingScreen.kt`'s `hasUsageAccess` variable and its
  `// Permission 1: Usage Access` comment perpetuate the impression that usage access is checked â€”
  see F-085. `AndroidManifest.xml:76` and `GrayzoneAccessibilityService.kt:20` both document that
  usage-stats polling was replaced, which is the evidence that this declaration is vestigial.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AndroidManifest.xml)` â€” `git diff`
  shows the only uncommitted manifest change is `android:process=":a11y"` at line 82, so line 24 is
  pre-existing.
- **Disposition**: `verified`

---

### F-085 â€” `OnboardingScreen`'s permission-1 card is named and commented for Usage Access but is wired to the Accessibility service, so the setup UI describes a permission it never checks  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` â†’ `OnboardingScreen()`
  (lines 36, 44, 47, 53, 66, 71, 74, 81, 83, 101, 227, 242) â€” the `hasUsageAccess` state variable and
  the `// Permission 1: Usage Access` section comment at line 66
- **Issue**: `var hasUsageAccess by remember { mutableStateOf(isAccessibilityServiceEnabled(context))
  }` holds accessibility state under a name that means a different, unrelated permission
  (`PACKAGE_USAGE_STATS`, which the app declares but never uses â€” F-084). The rendered card is
  correctly titled "Accessibility Service", so the *user* sees the right thing; the harm is entirely
  to maintenance, and it is concrete: the variable feeds the `hasUsageAccess && hasOverlay` gate at
  `:53` and `:227`, and it feeds `SetupRequirements.missing(hasUsageAccess, hasOverlay)` at `:242`,
  whose first parameter is `accessibilityEnabled`. A maintainer adding a genuine usage-access check
  would reasonably attach it to the existing `hasUsageAccess`, silently changing what gates entry to
  the app. The identical state in `HomeScreen.kt:42` is correctly named `accessibilityOn`, so the two
  screens already disagree on the name for the same value.
- **Trigger condition**:
  1. Open `ui/OnboardingScreen.kt` and read line 36 against line 66 and line 89.
  2. Compare with `ui/HomeScreen.kt:42`, which names the identical expression `accessibilityOn`.
  3. Confirm no usage-access API is called anywhere: `Grep` `app/src` for `UsageStatsManager` â†’
     zero matches.
  4. There is no runtime observation; the defect is a naming/documentation divergence.
- **Blast radius**:
  - *Callers*: the variable is file-local to `OnboardingScreen()`; read at `:47`, `:53`, `:71`,
    `:74`, `:81`, `:83`, `:101`, `:227`, `:242`, written at `:36` and `:44`. No other file references
    it (direct `Grep`). `SetupRequirements.missing()` is the only cross-file consumer of its value.
  - *Process death*: none â€” composition-local state, re-derived from a live permission read on every
    entry and every `ON_RESUME`.
  - *Config change*: `remember` without `rememberSaveable`, so rotation re-initialises from a fresh
    permission read at `:36` â€” correct behaviour, no defect.
  - *Foreground/background*: refreshed on `ON_RESUME` only, which is the general limitation recorded
    in F-080; not specific to the naming.
  - *Cross-process*: none â€” `OnboardingScreen` runs only in the default process.
  - *Persistence*: indirect only. The `ON_RESUME` branch at `:47â€“52` writes
    `PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS` gated on `hasUsageAccess`, so a future
    misinterpretation of the name would corrupt the prompt-once flag that prior-fix P6 depends on.
  - *Release-only*: no.
- **Fix** (minimal): rename `hasUsageAccess` â†’ `accessibilityOn` throughout
  `OnboardingScreen.kt` (12 occurrences), matching `HomeScreen.kt:42`, and change the section comment
  at `:66` from `// Permission 1: Usage Access` to `// Permission 1: Accessibility Service`.
- **Rewrite justification**: n/a â€” a rename and a comment.
- **Test to add**: `jvm-unit` â€” none is appropriate; a rename is not behaviour and a test asserting a
  variable name would be noise. The check that has value is the manifest test already specified in
  F-084 (`app/src/test/java/com/grayzone/app/ManifestPermissionsTest.kt`), which fails while
  `PACKAGE_USAGE_STATS` is declared and thereby removes the only thing that makes the name plausible.
  Recording this explicitly rather than inventing a placeholder test, per the contract's prohibition
  on test placeholders.
- **Existing test conflict**: none â€” `OnboardingScreen` has no tests (it is a composable with no
  extracted logic).
- **Verification**: `confirmed` (evidence tier T1). Read directly; all 12 occurrences enumerated.
  - *Missing*: n/a.
  - *Procedure*: steps 1â€“3 above, source-only.
  - *Severity effect*: `Low` in all outcomes â€” no user-visible consequence exists today.
- **Build scope**: everyday-build
- **Constitution**: II (one concept, two names across two screens), V (an identifier and a comment
  that assert a permission the code does not read)
- **Affected surfaces**: `SetupRequirements.missing(accessibilityEnabled, overlayEnabled)`
  (`SetupRequirements.kt:16`) is the correctly-named boundary the value crosses;
  `HomeScreen.kt:42,52` is the correctly-named sibling. F-084 removes the manifest declaration that
  gives the wrong name its apparent legitimacy.
- **Observed against**: `557a963` â€” `OnboardingScreen.kt` carries no uncommitted modifications.
- **Disposition**: `verified`

---

### F-086 â€” `POST_NOTIFICATIONS` denial is requested once, its result discarded, and never re-checked, so on Android 13+ every background protection signal disappears with no in-app trace  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainScreen()` â€” the
  `permissionLauncher` result callback (lines 233â€“235) and the request in `LaunchedEffect(Unit)`
  (lines 247â€“255)
- **Issue**: Three defects compound at one site. (1) The result callback is literally `{ _ -> }`: the
  grant/deny outcome is discarded, so no code in the app ever learns whether notifications are
  permitted. (2) The request lives in `LaunchedEffect(Unit)`, which runs once per entry into
  `MainScreen` and never again â€” `MainAppContent` keeps `MainScreen` in the composition across every
  `ON_RESUME` (`MainActivity.kt:211â€“225`), so backgrounding and returning does not re-check. (3)
  There is no permanent-denial handling anywhere: `shouldShowRequestPermissionRationale` has **zero**
  matches in `app/src`, so after Android's two-dismissal auto-deny the `launch()` call returns DENIED
  instantly with no system dialog, and the discarding callback swallows that too. At `targetSdk 34`
  on an API 33+ device, denying `POST_NOTIFICATIONS` means the user sees **no** foreground-service
  notification in the shade â€” the "Grayzone is active" notification, the session countdown
  chronometer (`OverlayService.kt:728`), and any future permission-loss notification are all
  suppressed while the services keep running. Every background channel the app has is gone, and the
  app neither knows nor says so. `SetupRequirements` cannot express it either (F-091), so `HomeScreen`
  continues to render "Protection ready". This also caps the F-080 and F-082 fixes: both are
  notification-based, and both are invisible on a device where this permission was denied.
- **Trigger condition**:
  1. Fresh install on an API 33+ device; open Grayzone and complete onboarding.
  2. When the notification permission dialog appears, tap **Don't allow**.
  3. Pull down the shade: no Grayzone notification, despite `OverlayService` running
     (`adb shell dumpsys activity services com.grayzone.app` shows it alive; the Android 13+ Task
     Manager also lists it).
  4. Open a monitored app and start a session: the countdown notification never appears.
  5. Return to Grayzone's Home tab: it reads "Protection ready" with a green **Active** pill and says
     nothing about notifications.
  6. Background and re-open the app any number of times: the permission is never re-requested and
     never mentioned.
- **Blast radius**:
  - *Callers*: `permissionLauncher` is declared and used only inside `MainScreen()`
    (`MainActivity.kt:233`, launched at `:253`) â€” direct `Grep`, no other references. Consumers of
    the permission's effect: `OverlayService.buildNotification()` (`:1189`) via
    `startForeground(NOTIF_ID, â€¦)` (`:144`), `OverlayService.startCountdownNotification()`
    (`:728`), `cancelCountdownNotification()` (`:755`), `AdBlockVpnService`'s
    `startForeground` (`:189`), and `createNotificationChannel()` (`:1186`).
  - *Process death*: makes it worse in a non-obvious way. The permission request is bound to
    `MainScreen`'s first composition; after process death the user typically returns to a restored
    Activity, which re-enters `MainScreen` and *does* re-run `LaunchedEffect(Unit)` â€” but by then
    Android has usually recorded the permission as permanently denied, so the re-run is a silent
    no-op. Process death therefore produces retries that look like handling but achieve nothing.
  - *Config change*: `LaunchedEffect(Unit)` survives rotation (same composition, same key), so
    rotation neither re-requests nor re-checks.
  - *Foreground/background*: the request can only happen in the foreground, while the entire
    consequence is background-only. The user is asked at the one moment the cost of saying no is
    invisible to them.
  - *Cross-process*: none for the request. Relevant to the fix: notifications posted from `:a11y`
    would be governed by the same app-level permission, so no fix can route around it.
  - *Persistence*: none today. A fix that records "notifications denied" in SharedPreferences would
    be captured by `StateBackupManager.writeBackup`'s `prefs.all` snapshot
    (`StateBackupManager.kt:167`) and restored stale after a reinstall â€” so the fix should read
    `NotificationManagerCompat.areNotificationsEnabled()` live instead of persisting a flag. Note
    `NotificationManagerCompat` has **zero** matches in `app/src` today.
  - *Release-only*: no. Worth stating explicitly: with `BuildConfig.DEBUG` false in both variants
    (F-064), `d()`/`i()` emit nothing, so once notifications are also suppressed the app has **no**
    observable output in the field except `w()`/`e()` in logcat.
- **Fix** (minimal): (a) use the callback â€” replace `{ _ -> }` with `{ granted -> if (!granted)
  notificationsDenied = true }` and surface that state; (b) re-check on resume rather than once, by
  moving the check into the existing `ON_RESUME` observer in `MainAppContent`
  (`MainActivity.kt:211â€“217`) and testing
  `NotificationManagerCompat.from(context).areNotificationsEnabled()`, which correctly covers both a
  denied runtime permission and a user-disabled channel; (c) when it is false, add a
  `SetupRequirement.NOTIFICATIONS` entry (see F-091) so `HomeScreen` renders the same
  one-button-per-missing-permission affordance it already has, routing to
  `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. Deep-linking to app notification settings is the
  correct action once the permission is permanently denied, since `launch()` can no longer show a
  dialog.
- **Rewrite justification**: n/a â€” one callback body, one moved check, one enum entry.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` (extend the existing class), adding
  `notifications off is reported as a missing requirement`: assert
  `SetupRequirements.missing(accessibilityEnabled = true, overlayEnabled = true,
  notificationsEnabled = false) == listOf(SetupRequirement.NOTIFICATIONS)` and that
  `headline(...) == "Notifications are off"`. Fails before the fix (the parameter and enum entry do
  not exist), passes after.
- **Existing test conflict**: **yes** â€” `SetupRequirementsTest.kt:11,16,25,33,42` all call the
  two-argument `SetupRequirements.missing(...)`. Adding a third parameter requires either a default
  value (`notificationsEnabled: Boolean = true`, which keeps all five existing tests compiling and
  passing unchanged) or updating all five call sites. The default-value form is preferred so the
  existing assertions continue to lock in the two-permission behaviour.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device confirmation that a denied `POST_NOTIFICATIONS` suppresses the
    foreground-service notification at `targetSdk 34`. The code-side facts â€” discarded result,
    once-only request, no rationale handling, no `areNotificationsEnabled` call anywhere â€” are all
    `confirmed` by direct `Grep` and need no device.
  - *Procedure*: steps 1â€“6 above on an API 33+ device. To reach the permanent-denial state
    deterministically: `adb shell pm revoke com.grayzone.app android.permission.POST_NOTIFICATIONS`
    then relaunch.
  - *Severity effect*: `High` if confirmed â€” protection continues but every user-facing signal that
    it is (or is not) working disappears, with no in-app indication, which is the app's stated worst
    outcome one step removed. It does not reach `Critical` because friction and lockouts themselves
    still function; only the reporting dies. Drops to `Medium` if a device shows the
    foreground-service notification surviving denial, in which case the remaining defect is the
    discarded result and the missing re-check.
- **Build scope**: everyday-build
- **Constitution**: III (a permission-request result is discarded by construction â€” the strongest
  possible form of an unhandled boundary outcome)
- **Affected surfaces**: `AdBlockVpnService.kt:189` (`startForeground`) is subject to the same
  suppression; `OverlayService.kt:144,728,755` are the three notification posts that go dark.
  `INSTALL.md:58` tells the recipient to grant "Notifications (foreground service + countdowns)",
  which is correct guidance the app itself does not enforce or re-check.
- **Observed against**: `working-tree @ 557a963 (uncommitted: build.gradle.kts)` â€” `MainActivity.kt`
  is unmodified in the working tree; `build.gradle.kts` is cited only for `targetSdk 34` (line 29),
  which is unchanged by the diff.
- **Disposition**: `verified`

---

### F-087 â€” The battery-optimization warning is suppressed whenever any setup permission is missing, and the green "Active" pill ignores it entirely  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()` â€” the status
  `when` chains (lines 119â€“141), the Fix button guard (line 167), and `ActivePill(isActive =
  isActive)` (line 112, `isActive` defined at line 68), against
  `app/src/main/java/com/grayzone/app/SharedComponents.kt` â†’ `ActivePill()` (lines 76â€“98)
- **Issue**: `isBatteryOptimized()` **is** re-read on every `ON_RESUME` (`:44`, `:54`), so loss of the
  exemption is detected â€” but the surfacing is conditional in two ways that hide it exactly when it
  matters. First, all four status `when` chains put `needsSetup ->` before `batteryIssue ->`
  (`:119â€“141`) and the "Fix" button is guarded by `if (!needsSetup && batteryIssue)` (`:167`), so
  while *any* of accessibility/overlay is off the battery warning is not merely deprioritised, it is
  **not rendered at all** â€” no headline, no body, no button. A user in the worst state (two
  protections off *and* the service liable to be killed) is told about two of the three problems and
  fixes them one at a time, never seeing the third until the first two are resolved. Second,
  `ActivePill(isActive = !needsSetup)` renders a green dot and the word **"Active"** whenever
  accessibility and overlay are on, regardless of `batteryIssue`, so the top-of-screen summary
  contradicts the amber card immediately beneath it. Loss of the exemption is not user-initiated in
  the common case â€” an OS update, an OEM battery manager, or Android's unused-app hibernation can
  re-optimize the app â€” so this is a state the user arrives in without knowing. Compounding it, the
  only remediation offered is the generic `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
  (`:171â€“175`), which `OemBatteryHelper`'s own doc comment says is insufficient on the five OEMs that
  matter â€” that gap is already owned as **F-061** and is not re-filed here.
- **Trigger condition**:
  1. Grant accessibility + overlay + battery exemption; open Home and confirm the green "Protection
     ready" card.
  2. Revoke the exemption: `adb shell dumpsys deviceidle whitelist -com.grayzone.app` (or Settings â†’
     Apps â†’ Grayzone â†’ Battery â†’ Optimized).
  3. Background and re-open Grayzone â†’ Home now shows the amber "Battery may interrupt protection"
     card **and**, at the top of the same screen, a green "Active" pill. Two contradictory
     statements, visible simultaneously.
  4. Now also revoke overlay: `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`.
  5. Re-open Home: the battery warning has **disappeared** entirely; only the overlay card is shown.
     Re-grant overlay and it reappears.
- **Blast radius**:
  - *Callers*: `isBatteryOptimized()` is called only from `HomeScreen()` (`AppUtils.kt:139`, read at
    `HomeScreen.kt:44,54`) â€” direct `Grep` over `app/src`, no other call sites; the sibling raw call
    `pm.isIgnoringBatteryOptimizations` appears at `OnboardingScreen.kt:38,46`. `ActivePill` callers:
    `HomeScreen.kt:112` only.
  - *Process death*: none for detection â€” every value is re-derived from a live system read on entry
    and on each `ON_RESUME`, so nothing stale survives. Process death is the *consequence* the
    exemption exists to prevent, which is why the suppressed warning matters.
  - *Config change*: `remember` without `rememberSaveable`, so rotation re-reads from the system â€”
    correct.
  - *Foreground/background*: detection is `ON_RESUME`-only, so a revocation while backgrounded is
    invisible until the user opens the Home tab specifically. That general gap is owned by F-080;
    what is specific here is that even in the foreground the warning can be suppressed.
  - *Cross-process*: none â€” `HomeScreen` runs only in the default process.
  - *Persistence*: none â€” nothing is written; the state is read live each time.
  - *Release-only*: no. Note `@SuppressLint("BatteryLife")` at `:170` is applied to a local `val`
    rather than the composable, so the `BatteryLife` lint warning is still reported
    (`evidence/lint.md` Â§E5, owned by T025) â€” cited, not re-filed.
- **Fix** (minimal): make the two conditions additive instead of exclusive. Render the setup card and
  the battery card as two independent `item {}` blocks in the `LazyColumn` rather than one card with
  a precedence `when`, so a missing permission never hides the battery warning; and change
  `ActivePill(isActive = isActive)` at `:112` to `ActivePill(isActive = isActive && !batteryIssue)`
  so the summary cannot contradict the detail beneath it. Both are local to `HomeScreen`.
- **Rewrite justification**: n/a â€” a split of one composable block and one argument change.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/HomeStatusTest.kt`, over a small extracted
  `HomeStatus.resolve(missing: List<SetupRequirement>, batteryIssue: Boolean)` returning the cards to
  show and the pill state, asserting
  `resolve(listOf(SetupRequirement.OVERLAY), batteryIssue = true).cards` contains **both**
  `Card.Setup` and `Card.Battery`, and `resolve(emptyList(), batteryIssue = true).pillActive ==
  false`. Fails before the fix (battery is suppressed when setup is missing; the pill ignores
  battery), passes after.
- **Existing test conflict**: none â€” `HomeScreen` has no tests, and `SetupRequirementsTest` covers
  only the pure string mapping, which this fix does not change.
- **Verification**: `confirmed` (evidence tier T1). The precedence is read directly from the four
  `when` chains at `:119â€“141`, the button guard at `:167`, and the pill argument at `:112` /
  definition at `SharedComponents.kt:76â€“98`. No device is needed to establish that the battery branch
  is unreachable while `needsSetup` is true.
  - *Missing*: n/a for the suppression. Only how frequently a real device silently re-optimizes the
    app would need field observation, and that governs likelihood, not the defect.
  - *Procedure*: steps 1â€“5 above on any API 29+ device.
  - *Severity effect*: `Medium` in all outcomes â€” the rubric's "incorrect behavior in narrow
    conditions" fits precisely: the warning is correct and visible in the common case and wrong only
    while another permission is also missing. It does not reach High because the state is recoverable
    and becomes visible as soon as the other permissions are fixed.
- **Build scope**: everyday-build
- **Constitution**: II (the Home status is expressed twice â€” pill and card â€” and the two can
  disagree)
- **Affected surfaces**: `OnboardingScreen.kt:171â€“223` shows the battery card unconditionally and
  correctly does **not** gate "Continue" on it (`:225â€“227`, with a stated rationale) â€” that surface
  is a positive example and needs no change. **F-061** owns the missing OEM-specific remediation.
- **Observed against**: `557a963` â€” `HomeScreen.kt` and `SharedComponents.kt` carry no uncommitted
  modifications.
- **Disposition**: `verified`

---

### F-088 â€” All-files access lost after the first prompt is never re-detected: the one-shot flag has no reset-on-grant, so wipe resistance disappears with zero trace  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’
  `requestAllFilesAccessOnce()` (lines 87â€“110, specifically the early return at line 91) and
  `MainActivity.onResume()` (lines 42â€“53)
- **Issue**: `onResume` correctly re-reads the live permission (`StateBackupManager.hasAllFilesAccess`
  â†’ `Environment.isExternalStorageManager()`), so **revocation is detected** â€” and then the detection
  is thrown away. The `else` branch calls `requestAllFilesAccessOnce`, which returns at `:91` because
  `ALL_FILES_PROMPTED` was set to `true` on the very first launch. Direct `Grep` over `app/src`
  confirms the key appears at exactly two lines â€” the read at `MainActivity.kt:91` and the write at
  `:92` â€” with **no reset anywhere**. Contrast `ACCESSIBILITY_PROMPTED_FOR_LOSS`, which is reset to
  `false` whenever the permission is observed granted (`OnboardingScreen.kt:51`,
  `AccessibilityPromptPolicy.onObserved`'s `isEnabled` branch): the accessibility flag is a
  once-per-*loss* suppressor, the all-files flag is a once-per-*install* suppressor. So the
  prompt-once design was applied correctly to one permission and incorrectly to the other. The
  consequence is the app's anti-tamper property: without all-files access,
  `StateBackupManager.writeBackup` returns at `:161â€“163` and `readBackup` at `:187`, so the hidden
  `/sdcard/.grayzone/` snapshot stops being maintained and stops being readable â€” the backup no
  longer survives a data/cache wipe, which is its entire purpose. The only trace is
  `GrayzoneLogger.i(LogComponent.BOOT, "State backup skipped â€” all-files access not granted")` at
  `:162`, and `i()` is a **no-op in every variant this project builds** (F-064). There is no toast,
  no notification, no UI element anywhere: `hasAllFilesAccess` has exactly one caller outside
  `StateBackupManager` (`MainActivity.kt:47`) and it renders nothing. This is distinct from **F-209**,
  which is that the flag is written before the prompt succeeds; fixing F-209 makes the *first* prompt
  reliable but leaves every subsequent revocation just as silent, because the flag still never resets.
- **Trigger condition**:
  1. Fresh install; on first launch accept the all-files-access prompt and grant it. Confirm
     `/sdcard/.grayzone/` exists: `adb shell ls -a /sdcard/.grayzone`.
  2. Revoke it: Settings â†’ Apps â†’ Special app access â†’ All files access â†’ Grayzone â†’ off (or
     `adb shell appops set com.grayzone.app MANAGE_EXTERNAL_STORAGE deny`).
  3. Re-open Grayzone. No prompt, no toast, no notification, no UI change anywhere.
  4. Change a lock setting so the backup would be rewritten, then
     `adb shell ls -l /sdcard/.grayzone/` â€” the file's timestamp is unchanged; the snapshot is now
     stale.
  5. Clear app data (Settings â†’ Storage â†’ Clear data) and relaunch: the restore reads nothing new;
     any locks created after step 2 are gone. The user was never told the anti-tamper backup had
     stopped.
- **Blast radius**:
  - *Callers*: `requestAllFilesAccessOnce` is called from `MainActivity.onResume():50` and
    `MainScreen`'s `LaunchedEffect(Unit)` (`:256`) â€” direct `Grep`, both in `MainActivity.kt`.
    `hasAllFilesAccess` callers: `MainActivity.kt:47`, `StateBackupManager.kt:152` (`backupExists`),
    `:161` (`writeBackup`), `:187` (`readBackup`). `ALL_FILES_PROMPTED` occurrences: `PrefsKeys.kt:27`
    (declaration), `MainActivity.kt:91,92` â€” nothing else in `app/src`, re-verified by direct search
    rather than from `evidence/reference-index.md`.
  - *Process death*: no effect on the flag (it is persisted), which is the problem â€” the suppression
    outlives every process, every launch, and every permission change.
  - *Config change*: none â€” `requestAllFilesAccessOnce` is a plain function called from `onResume`
    and a `LaunchedEffect`, not composition state.
  - *Foreground/background*: the check is foreground-only by design (it must launch a settings
    Activity), which is acceptable; the defect is that the foreground check reaches a dead branch.
  - *Cross-process*: relevant. `GrayzoneApplication.kt:52â€“54` skips all prefs and backup work in the
    `:a11y` process, so the flag is only ever read and written by the default process â€” the fix must
    not move this check into a service that could run in `:a11y`.
  - *Persistence*: yes, and it self-reinforces. `StateBackupManager.writeBackup` snapshots
    `prefs.all` (`:167`) with no allowlist, so `ALL_FILES_PROMPTED = true` is itself written into the
    external backup and restored by `applyInto` (`:229â€“251`) after a wipe â€” see F-090. The
    suppression therefore survives clear-data, which is the one recovery a user might attempt.
  - *Release-only*: no, but the diagnosability is release-shaped: the sole log line uses `i()`, dead
    in both variants (F-064).
- **Fix** (minimal): give the flag the same reset-on-grant that the accessibility flag has. In
  `MainActivity.onResume()` (`:47â€“51`), change the granted branch to also clear the suppressor:
  `if (StateBackupManager.hasAllFilesAccess(this)) { prefs.edit().putBoolean(
  PrefsKeys.ALL_FILES_PROMPTED, false).apply(); StateBackupManager.restoreIfWiped(this) } else {
  requestAllFilesAccessOnce(this) }`. That makes `ALL_FILES_PROMPTED` mean "already prompted for the
  *current* loss", matching `ACCESSIBILITY_PROMPTED_FOR_LOSS`, so exactly one prompt is issued per
  contiguous revocation and no loop is possible. Reuse `AccessibilityPromptPolicy.onObserved` for the
  decision â€” it is already the tested, pure implementation of this policy and is not
  accessibility-specific in anything but its name. This composes with, and does not conflict with,
  the F-209 fix (set the flag only after a successful `startActivity`).
- **Rewrite justification**: n/a â€” one branch gains one `putBoolean`, reusing an existing policy
  object.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt` (extend the existing class) or
  a sibling `PromptOncePolicyTest.kt`, adding `all-files regrant resets so a future loss prompts
  once more`: drive `onObserved(previouslyPromptedForLoss = true, isEnabled = true)` and assert
  `promptedForLoss == false`, then `onObserved(previouslyPromptedForLoss = false, isEnabled = false)`
  and assert `shouldPrompt == true`. The policy assertions already exist for accessibility
  (`:48â€“56`); what must be added is a test at the *call site* level asserting that the all-files path
  consults the policy at all â€” add
  `app/src/test/java/com/grayzone/app/AllFilesPromptDecisionTest.kt` over an extracted
  `AllFilesPrompt.decide(previouslyPrompted: Boolean, hasAccess: Boolean)` delegating to
  `AccessibilityPromptPolicy.onObserved`, asserting `decide(previouslyPrompted = true, hasAccess =
  true).promptedForLoss == false`. Fails before the fix (no such function; the flag is write-once),
  passes after.
- **Existing test conflict**: none. `AccessibilityPromptStateTest`'s five tests assert the pure
  policy and continue to pass unchanged; the fix reuses that policy rather than altering it.
- **Verification**: `confirmed` (evidence tier T1). The write-once behaviour is established by direct
  `Grep`: `ALL_FILES_PROMPTED` has exactly one read (`:91`) and one write (`:92`) in `app/src`, and
  the write sets `true` unconditionally. The absence of any user-facing surface for
  `hasAllFilesAccess` is established the same way.
  - *Missing*: n/a for the mechanism. Only the OEM behaviour in step 2 (whether a given device
    exposes the revocation toggle) varies.
  - *Procedure*: steps 1â€“5 above on an API 30+ device (the `MANAGE_EXTERNAL_STORAGE` path; on API 29
    the legacy `WRITE_EXTERNAL_STORAGE` branch of `hasAllFilesAccess` applies and the same flag logic
    governs).
  - *Severity effect*: `High` confirmed. It matches F-209's rating for the same property â€” silent
    loss of wipe resistance â€” and stays below Critical because friction, locks and sessions all keep
    working; only the anti-tamper backup degrades, and only for a user who takes the extra step of
    wiping data. It would rise to `Critical` if the app ever depended on the backup for correctness
    at runtime rather than for recovery.
- **Build scope**: everyday-build
- **Constitution**: II (two permissions implement "prompt once per loss" and only one implements it
  correctly), III (a detected permission loss produces no handling and no observable output, since
  the sole log call is a no-op level)
- **Affected surfaces**: **F-209** owns the pre-set flag on the first prompt and its fix must land in
  the same function; **F-211** owns the unguarded `startActivity` calls at `:94` and `:102`.
  `StateBackupManager.writeBackup():161â€“163` and `readBackup():187` are the two silent early returns
  this permission loss triggers. F-090 covers the flag's restoration from the external backup.
- **Observed against**: `557a963` â€” `MainActivity.kt` and `StateBackupManager.kt` carry no
  uncommitted modifications.
- **Disposition**: `verified`

---

### F-089 â€” VPN consent revocation is never re-checked after startup, leaving the VPN screen claiming "Protection interrupted â€” reconnectingâ€¦" forever  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `reconcileVpnState()` (lines
  121â€“154), reached only from `MainScreen()`'s `LaunchedEffect(Unit)` (line 258); consequence
  rendered at `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt` â†’ `VpnScreen()` (lines 153â€“182)
- **Issue**: `VpnService.prepare()` is the app's only test for VPN consent, and it is called from
  exactly three places (`MainActivity.kt:139` inside `reconcileVpnState`, `VpnScreen.kt:98` inside
  the user-tapped `beginEnableVpn`, `BootReceiver.kt:61`). None of them runs on resume:
  `reconcileVpnState` sits in `LaunchedEffect(Unit)`, whose key never changes while `MainScreen`
  stays in the composition â€” and it does stay, because `MainAppContent`'s `ON_RESUME` observer
  (`:211â€“217`) only reassigns `currentScreen`, which remains `Screen.MAIN`. So the consent check runs
  **once per entry into the main UI** and never again for the life of that composition. Android
  revokes a VPN's consent whenever another VPN app is prepared, which needs no adversarial intent â€”
  installing any VPN, or an employer's always-on profile activating, is sufficient. When that
  happens the framework tears down `AdBlockVpnService`; `onDestroy` (`:327â€“340`) sees
  `VPN_ENABLED == true` and calls `markVpnReconnectPending(true)`, so `VpnScreen` renders the amber
  banner **"Protection interrupted â€” reconnectingâ€¦"** (`:156`, `:177`). Nothing will reconnect:
  `restartVpnAfterDelay` is not called on this path, `onRevoke()` is not overridden at all (**F-025**,
  RU-06 â€” re-verified here by direct `Grep`: zero matches for `onRevoke` in `app/src/main`), and no
  resume-time path re-runs `prepare()`. The banner is a false statement of an in-progress recovery
  that does not exist, and it is the *more* honest of the two states â€” after the process next dies,
  `ProtectionHealthRepository`'s in-memory flow resets to defaults and the screen reverts to "Off â€”
  ads not blocked" while `VPN_ENABLED` stays `true` in prefs and the 24-hour hold keeps counting down
  (`VpnHoldLockStore`), so the user sees a hold timer for a tunnel that is not running.
- **Trigger condition**:
  1. Enable the AdBlock VPN in Grayzone; confirm "Filtering DNS traffic" and an active 24h hold.
  2. Without closing Grayzone, install and connect any other VPN app (or
     `adb shell settings put secure always_on_vpn_app <other.pkg>`). Android revokes Grayzone's
     consent and stops its service.
  3. Return to Grayzone â†’ VPN tab: the banner reads "Protection interrupted â€” reconnectingâ€¦"
     indefinitely. `adb shell dumpsys activity services com.grayzone.app` shows no
     `AdBlockVpnService`.
  4. Background and re-open Grayzone repeatedly: no consent dialog is ever offered, because
     `LaunchedEffect(Unit)` does not re-run.
  5. `adb shell am kill com.grayzone.app`, relaunch, open the VPN tab: it now reads "Off â€” ads not
     blocked" while the hold countdown still displays, and `adb shell run-as` / the app's own state
     still has `vpn_enabled = true`.
- **Blast radius**:
  - *Callers*: `reconcileVpnState` â€” one caller, `MainScreen`'s `LaunchedEffect(Unit)`
    (`MainActivity.kt:258`). `VpnService.prepare` call sites â€” `MainActivity.kt:139`,
    `VpnScreen.kt:98`, `BootReceiver.kt:61` (direct `Grep`, no others).
    `markVpnReconnectPending` writers â€” `AdBlockVpnService.kt:319` (`restartVpnAfterDelay`) and
    `:336` (`onDestroy`); reader â€” `VpnScreen.kt:53`, rendered at `:156` and `:170â€“182`.
  - *Process death*: changes the symptom, not the defect. `ProtectionHealthRepository` is an
    in-memory `object` with no persistence, so `vpnReconnectPending` is lost on process death and the
    screen silently downgrades from a false "reconnecting" to a bare "Off" â€” while the persisted
    `VPN_ENABLED` and the hold timestamps still assert the tunnel should be up. A fix must derive the
    banner from persisted intent plus a live `prepare()` check, not from a volatile flag.
  - *Config change*: none â€” `LaunchedEffect(Unit)` survives rotation with the same key, so rotation
    does not re-trigger the check either.
  - *Foreground/background*: `prepare()` must be called from the foreground to be actionable (its
    consent dialog is an Activity), so the correct hook is `ON_RESUME` â€” which is precisely the hook
    the current code does not use.
  - *Cross-process*: none. `AdBlockVpnService` is declared with no `android:process`
    (`AndroidManifest.xml:121â€“132`), so it and the UI share the default process and
    `ProtectionHealthRepository` is genuinely shared between them
    (`evidence/cross-process-inventory.md`).
  - *Persistence*: yes. `PrefsKeys.VPN_ENABLED` and the `VpnHoldLockStore` keys persist and are
    deliberately **not** cleared on an OS-initiated stop (`AdBlockVpnService.kt:329â€“332`, with a
    stated rationale). That decision is correct; the defect is that nothing reconciles the persisted
    intent against live consent after startup.
  - *Release-only*: no.
- **Fix** (minimal): move the reconciliation onto the resume hook the app already has. Add
  `reconcileVpnState(context, onConsentNeeded)` to `MainAppContent`'s existing `ON_RESUME`
  observer (`MainActivity.kt:211â€“217`), or add a `DisposableEffect`/`ON_RESUME` observer inside
  `MainScreen` that calls it, so a consent revocation is detected the next time the user opens the
  app. `reconcileVpnState` is already idempotent and already early-returns when `VPN_ENABLED` is
  false (`:123`) or the hold has elapsed (`:129â€“135`), so it is safe to call repeatedly. Separately,
  make the banner honest: in `VpnScreen`, render "Protection interrupted â€” reconnectingâ€¦" only when a
  reconnect is actually scheduled, and render "VPN permission was revoked â€” tap to re-enable" with
  the existing `vpnPermLauncher` when `VpnService.prepare(context) != null`. The underlying
  missing `onRevoke()` hook is **F-025** and is fixed in RU-06; this finding is the UI-side detection
  gap, which persists even after `onRevoke()` is added.
- **Rewrite justification**: n/a â€” an existing idempotent function is attached to an existing
  lifecycle observer, plus one banner condition.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/VpnConsentBannerTest.kt`, over an extracted
  `VpnBannerState.resolve(vpnActive: Boolean, wantsVpn: Boolean, consentPresent: Boolean,
  reconnectScheduled: Boolean)`, asserting
  `resolve(vpnActive = false, wantsVpn = true, consentPresent = false, reconnectScheduled = false)
  == VpnBannerState.ConsentRevoked` and that the same inputs with `reconnectScheduled = true` give
  `VpnBannerState.Reconnecting`. Fails before the fix (the type does not exist and the screen maps
  `reconnectPending` straight to "reconnecting"), passes after.
- **Existing test conflict**: none â€” `VpnScreen` and `reconcileVpnState` have no tests. The VPN hold
  tests (`VpnHoldLock*`) cover timing only and are untouched.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device confirmation of step 2 â€” that preparing another VPN revokes Grayzone's consent
    and tears down its service on the target OS build. The code-side facts are `confirmed`:
    `reconcileVpnState` has exactly one caller and it is `LaunchedEffect(Unit)`; `MainScreen` is not
    removed from the composition on resume; `prepare()` has three call sites and none is
    resume-bound; `onRevoke` has zero matches in `app/src/main`.
  - *Procedure*: steps 1â€“5 above on any API 29+ device with a second VPN app installed.
  - *Severity effect*: `High` if confirmed â€” protection (DNS filtering) is off, the UI states the
    opposite, and the 24-hour hold the user committed to keeps counting against a dead tunnel, which
    for a self-control app is a bypass the user can trigger by installing an unrelated app. Drops to
    `Medium` if a device shows the tunnel surviving another VPN's activation (contrary to the
    documented single-VPN model), leaving only the once-per-composition reconciliation as the defect.
- **Build scope**: everyday-build
- **Constitution**: III (a revocable system grant is checked once and never re-validated), II (the
  banner's "reconnecting" state is derived from a flag that does not mean a reconnect is scheduled)
- **Affected surfaces**: **F-025** (no `onRevoke()` override, RU-06) is the service-side half of the
  same gap; **F-210** owns the auto re-consent dialog failing to open unrecorded; **F-212** owns the
  unguarded `prepare()` in `BootReceiver`. `BootReceiver.kt:58â€“61` is the one path that *does*
  re-check consent outside the UI and correctly declines to start without it â€” the model this fix
  should follow.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)` â€” the
  `onDestroy`/`markVpnReconnectPending` path is unchanged by the diff; `MainActivity.kt` and
  `VpnScreen.kt` are unmodified.
- **Disposition**: `verified`

---

### F-090 â€” The anti-tamper restore brings back the prompt-suppression flags, so a reinstalled app drops the user into the main screen with the accessibility service disabled and never re-prompts  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’
  `StateBackupManager.writeBackup()` (line 167, `prefs.all`) and `applyInto()` (lines 229â€“251),
  interacting with `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’
  `MainAppContent.evaluateScreen()` (lines 189â€“207)
- **Issue**: `writeBackup` snapshots **every** preference (`serialize(context, prefs.all)`, `:167`)
  and `applyInto` restores every key with no allowlist and no schema gate. Among those keys are the
  three that suppress setup UI: `ONBOARDING_COMPLETED`, `ACCESSIBILITY_PROMPTED_FOR_LOSS`, and
  `ALL_FILES_PROMPTED`. The backup exists specifically so locks survive a wipe or a
  reinstall â€” and it restores the *suppressors* along with the locks. Trace the app's own
  anti-tamper scenario: after an uninstall/reinstall the accessibility service is removed from
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, so `isAccessibilityServiceEnabled()` is `false`.
  The user grants all-files access (fresh install, so F-088's flag is clear), `MainActivity.onResume`
  runs `restoreIfWiped` **before** `super.onResume()` â€” deliberately, per the comment at `:43â€“46`, so
  the UI's re-evaluation sees the restored values â€” and `evaluateScreen()` then reads
  `ACCESSIBILITY_PROMPTED_FOR_LOSS = true` (restored) and `ONBOARDING_COMPLETED = true` (restored).
  `AccessibilityPromptPolicy.onObserved(previouslyPromptedForLoss = true, isEnabled = false)` returns
  `shouldPrompt = false`, the `!ONBOARDING_COMPLETED` guard at `:205` does not fire, and `:206`
  returns `Screen.MAIN`. **The user lands on the main screen with the app's core protection
  permission disabled, and is never routed through onboarding.** The prompt-once-per-loss design
  (prior-fix P6) is correct in isolation; what defeats it is that the flag meaning "we already asked
  about this loss" is treated as durable user state and carried across a reinstall, where by
  definition no one has been asked. The only remaining signal is `HomeScreen`'s amber setup card,
  which is on the default tab but is not a prompt â€” and F-091 shows the same screen's pill logic and
  headline are computed from that same two-permission model.
- **Trigger condition**:
  1. Install, complete setup (accessibility + overlay + all-files access), set a lock so the backup
     is written. Confirm `adb shell ls -a /sdcard/.grayzone`.
  2. Uninstall Grayzone. Confirm the hidden backup survives (`adb shell ls -a /sdcard/.grayzone`).
  3. Reinstall the same signed APK and launch it. Confirm accessibility is off: Settings â†’
     Accessibility â†’ Grayzone reads off.
  4. Grant all-files access when prompted, then return to the app.
  5. Observe the app renders the **main** screen, not onboarding. The locks are back (restored), the
     accessibility service is off, and nothing prompts for it.
  6. Confirm the cause without a device: `adb shell` is not needed â€” `writeBackup` serialises
     `prefs.all` (`:167`), `applyInto` has no key filter (`:233â€“246`), and `evaluateScreen()`'s only
     escape hatch for a disabled service is `decision.shouldPrompt`, which the restored `true`
     suppresses.
- **Blast radius**:
  - *Callers*: `writeBackup` and `restoreIfWiped` callers â€” `GrayzoneApplication.onCreate():64`,
    `MainActivity.onResume():48`, plus the backup-flush launch sites enumerated in F-206.
    `evaluateScreen()` callers â€” `MainActivity.kt:209` (composition) and `:213` (`ON_RESUME`).
    `ACCESSIBILITY_PROMPTED_FOR_LOSS` sites â€” `PrefsKeys.kt:17`, `MainActivity.kt:193,196`,
    `OnboardingScreen.kt:51`; `ONBOARDING_COMPLETED` sites â€” `PrefsKeys.kt:24`,
    `MainActivity.kt:199,205,221`, `StateBackupManager.kt:95,107`; `ALL_FILES_PROMPTED` sites â€”
    `PrefsKeys.kt:27`, `MainActivity.kt:91,92`. All enumerated by direct `Grep` over `app/src`.
  - *Process death*: irrelevant to the flags (persisted) but central to the ordering: the restore
    happens in `Application.onCreate`, so every process start re-applies whatever the backup holds
    before any UI runs.
  - *Config change*: none â€” the decision is re-derived on each `ON_RESUME` from persisted values.
  - *Foreground/background*: the restore that plants the suppressor runs from
    `MainActivity.onResume` (foreground) and `Application.onCreate` (either), while the suppressed
    prompt is foreground-only. Both halves are foreground, so this defect is fully reproducible
    without any background timing.
  - *Cross-process*: `GrayzoneApplication.kt:52â€“54` skips the restore entirely in `:a11y`
    (uncommitted, and correct), so only the default process is involved. A fix must not add a
    second writer.
  - *Persistence*: this **is** the persistence defect. `applyInto` uses `commit()` deliberately
    (`:248â€“250`) so restored values are durably visible before anything reads them, which means the
    suppressors are in place before `evaluateScreen()` ever runs. The related "no schema gate, no key
    allowlist" property is already a Phase-1 lead owned by T020 and is not re-filed; what is filed
    here is the specific consequence for setup prompts.
  - *Release-only*: no. Note the restore's only trace is `GrayzoneLogger.i` (`:119â€“122`), a no-op in
    both variants (F-064), so nothing records that the suppressors were reinstated.
- **Fix** (minimal): exclude the prompt-suppression flags from the backup payload. In
  `StateBackupManager.serialize()` (`:200â€“227`), skip a small deny-set before writing each entry â€”
  `PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS` and `PrefsKeys.ALL_FILES_PROMPTED` â€” so a restored
  install re-derives them from a live permission read instead of inheriting a stale "already asked".
  `ONBOARDING_COMPLETED` must **stay** in the payload: `restoreIfWiped` uses it as the wipe sentinel
  (`:95â€“107`), so removing it would break restore entirely. Instead, tighten `evaluateScreen()`'s
  `:205` guard so completion is not sufficient on its own: return `Screen.ONBOARDING` when
  `!accessibilityOn && !overlayOn`-style hard gaps exist and the restore just ran. The one-line form
  is the deny-set; the `evaluateScreen` change is the belt-and-braces half and should ship with it.
- **Rewrite justification**: n/a â€” a two-key skip inside an existing loop.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupKeyFilterTest.kt`, over an extracted
  `StateBackupManager.isBackedUp(key: String)` predicate, asserting
  `isBackedUp(PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS) == false`,
  `isBackedUp(PrefsKeys.ALL_FILES_PROMPTED) == false`, and
  `isBackedUp(PrefsKeys.ONBOARDING_COMPLETED) == true` (required by the wipe sentinel). Fails before
  the fix (no predicate exists; `prefs.all` is serialised wholesale), passes after. Pair it with
  `app/src/test/java/com/grayzone/app/SetupGateTest.kt` over an extracted
  `SetupGate.resolve(accessibilityOn, overlayOn, onboardingCompleted, promptedForLoss)`, asserting
  `resolve(false, true, onboardingCompleted = true, promptedForLoss = true) == Screen.ONBOARDING`.
- **Existing test conflict**: none. `AccessibilityPromptStateTest`'s five tests assert the pure
  policy, which is unchanged â€” the fix corrects the *input* the policy is given, not the policy.
  No test covers `StateBackupManager.serialize`.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device confirmation of step 3 â€” that an uninstall/reinstall leaves the accessibility
    service disabled while the `/sdcard/.grayzone/` backup remains readable once all-files access is
    re-granted (the encryption key derives from `ANDROID_ID`, `StateBackupManager.kt:253â€“258`, which
    is stable across a reinstall with the same signing key but must be observed). Everything else is
    `confirmed` from source: `prefs.all` at `:167`, no allowlist at `:233â€“246`, and
    `evaluateScreen()`'s `:192â€“206` decision chain.
  - *Procedure*: steps 1â€“5 above on any API 30+ device, using the same release-signed APK for both
    installs (INSTALL.md's signing note applies â€” a different key changes `ANDROID_ID` and the
    restore would fail closed instead).
  - *Severity effect*: `High` if confirmed â€” the user completes a reinstall believing their locks are
    intact (they are, and are displayed) while the permission that enforces them is off and the app
    never asks for it, which is silent protection loss dressed as a successful restore. Drops to
    `Medium` if a device shows `ANDROID_ID` changing across reinstall so the backup cannot decrypt,
    since the restore would then fail closed and onboarding would run normally â€” but that outcome
    also means the anti-tamper backup does not survive reinstall at all, which is a separate and
    larger problem to report.
- **Build scope**: everyday-build
- **Constitution**: II (a UI-suppression flag and durable user state are stored in the same
  undifferentiated namespace and treated identically), III (the restore's outcome is logged only at a
  level that never emits)
- **Affected surfaces**: **F-088** (the all-files flag never resets) is compounded by the same
  restore path; **F-209** owns the flag being pre-set on first prompt. The "no schema gate / no key
  allowlist in `applyInto`" property is a Phase-1 lead owned by **T020** and the deny-set fix here
  should be designed to coexist with whatever allowlist that task specifies.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)` â€” the
  `:a11y` early return at `GrayzoneApplication.kt:52â€“54` is uncommitted and is what keeps the restore
  single-writer; `StateBackupManager.kt` and `MainActivity.kt` are unmodified.
- **Disposition**: `verified`

---

### F-091 â€” `SetupRequirement` models 2 of the app's 7 protection permissions, so `headline(emptyList())` renders the absolute claim "Protection ready" while five grants may be missing  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/SetupRequirements.kt` â†’ `SetupRequirement` (line
  4) and `SetupRequirements.headline()` (lines 27â€“31); rendered at
  `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()` lines 66â€“68, 133, 135, 112
- **Issue**: `enum class SetupRequirement { ACCESSIBILITY, OVERLAY }` is closed at two entries, and
  `missing()` takes exactly two booleans. Everything downstream inherits that closure:
  `missingSetup.isNotEmpty()` becomes `needsSetup`, `isActive = !needsSetup` drives
  `ActivePill(isActive)` â€” a green dot and the word **"Active"** (`SharedComponents.kt:76â€“98`) â€” and
  `headline(emptyList())` returns the unqualified string **"Protection ready"**, which
  `HomeScreen.kt:133,135` renders as the app's top-level statement about itself. Five
  protection-relevant grants are outside the model entirely: `POST_NOTIFICATIONS` (F-086, and its
  denial removes every background signal the app has), all-files access (F-088, wipe resistance),
  battery-optimization exemption (present in `HomeScreen` but as a *separate* branch that F-087 shows
  is suppressed whenever the two modelled ones are missing), VPN consent (F-089), and
  `PACKAGE_USAGE_STATS` (declared but unused, F-084). So "Protection ready" can be displayed with
  notifications denied, the anti-tamper backup dead, the VPN revoked, and the app battery-optimized.
  This is the structural reason the rest of this task's findings have nowhere to surface: the app has
  a well-built, tested, pure model for "what is still missing" â€” `SetupRequirements` with per-entry
  `label`/`headline`/`body`/`actionLabel` and a one-button-per-requirement renderer at
  `HomeScreen.kt:184â€“196` â€” and only two of seven permissions were ever connected to it. The fix for
  most of the findings above is to extend this enum rather than to build anything new.
- **Trigger condition**:
  1. Grant accessibility and overlay only. Deny the notification permission (API 33+), leave
     all-files access ungranted, leave the VPN off.
  2. Open Grayzone â†’ Home.
  3. Observe the green **Active** pill and the green card reading "Protection ready" with body
     "N apps monitored".
  4. Confirm nothing else is consulted: `SetupRequirements.missing` takes only
     `accessibilityEnabled` and `overlayEnabled` (`SetupRequirements.kt:16`), and `needsSetup` is
     derived solely from its result (`HomeScreen.kt:66â€“68`).
- **Blast radius**:
  - *Callers*: `SetupRequirements.missing` â€” `HomeScreen.kt:66`, `OnboardingScreen.kt:242`,
    `SetupRequirementsTest.kt:11,16,25,33,42`. `headline` â€” `HomeScreen.kt:133`,
    `SetupRequirementsTest.kt:19,28,36`. `body` â€” `HomeScreen.kt:138`,
    `SetupRequirementsTest.kt:20,44`. `label` â€” `SetupRequirements.kt:29,41`, `HomeScreen.kt:194`,
    `OnboardingScreen.kt:243`. `actionLabel` â€” `SetupRequirementsTest.kt:37,45` only (**test-only**,
    already owned as **F-073**; re-verified here by direct `Grep` over `app/src` per the
    reference-index accuracy warning â€” the function is live in test source and **must not be
    deleted**, it must be adopted by `HomeScreen.kt:194`). `settingsIntentFor` switches on the enum
    (`AppUtils.kt:129â€“137`) and must gain a branch for every entry added.
  - *Process death*: none â€” the model is pure and re-derived from live reads on every entry.
  - *Config change*: none â€” pure functions over booleans held in `remember`, re-derived on
    recomposition.
  - *Foreground/background*: the model is only ever evaluated in the foreground. Extending the enum
    is also the cheapest way to give F-080's notification something structured to render, so the fix
    should keep `SetupRequirements` free of `Context` so it stays usable from a service.
  - *Cross-process*: none today. Keeping the object pure (it currently imports nothing) preserves the
    option of evaluating it in either process.
  - *Persistence*: none â€” `SetupRequirements` reads and writes nothing.
  - *Release-only*: no. `SetupRequirement` is an enum referenced by a `when` in `settingsIntentFor`;
    adding entries requires that `when` to stay exhaustive, which the compiler enforces.
  - *Callers (extended note)*: `ActivePill` â€” `HomeScreen.kt:112` only.
- **Fix** (minimal): extend the existing model rather than adding a parallel one. Add
  `NOTIFICATIONS`, `ALL_FILES`, and `BATTERY` to `SetupRequirement`; change `missing()` to
  `missing(accessibilityEnabled: Boolean, overlayEnabled: Boolean, notificationsEnabled: Boolean =
  true, allFilesGranted: Boolean = true, batteryExempt: Boolean = true)` â€” defaults keep every
  existing call site and test compiling â€” and add the corresponding `label`/`body` branches and
  `settingsIntentFor` branches (`ACTION_APP_NOTIFICATION_SETTINGS`,
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
  Keep the ordering contract stated at `:15` ("the first entry is the one to fix next") by placing
  the two blocking permissions first and the three degrading ones after, and split `headline` so a
  set containing only degrading entries reads "Protection ready â€” 2 items need attention" rather than
  either extreme. VPN consent stays outside the enum: it is opt-in, not required, so it belongs in
  `VpnScreen`'s own banner (F-089).
- **Rewrite justification**: n/a â€” three enum entries and three `when` branches, with defaulted
  parameters so no existing caller changes. This is the case *for* patching: the component is
  well-shaped and pure; only its inputs are incomplete.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` (extend the existing class), adding
  `degrading permissions are reported without claiming protection ready`: assert
  `SetupRequirements.missing(accessibilityEnabled = true, overlayEnabled = true, notificationsEnabled
  = false, allFilesGranted = false, batteryExempt = true) == listOf(SetupRequirement.NOTIFICATIONS,
  SetupRequirement.ALL_FILES)` and `SetupRequirements.headline(that) != "Protection ready"`. Fails
  before the fix (the parameters do not exist and the two-argument call returns an empty list, whose
  headline is exactly "Protection ready"), passes after.
- **Existing test conflict**: **yes, but resolvable without edits.**
  `SetupRequirementsTest.kt:11,16,25,33,42` call the two-argument `missing(...)`, and `:11`/`:42`
  assert that granting both yields an empty list and an empty `body`/`actionLabel`. Defaulted
  parameters keep all five passing unchanged. If the maintainer instead makes the new parameters
  required, all five call sites must be updated â€” the defaulted form is preferred precisely to avoid
  weakening the existing two-permission assertions.
- **Verification**: `confirmed` (evidence tier T1). Read directly: the enum has two entries
  (`SetupRequirements.kt:4`), `missing()` takes two booleans (`:16`), `headline(emptyList()) ==
  "Protection ready"` (`:28`), and `HomeScreen.kt:66â€“68,112,133` derive the pill and the headline
  from nothing else. All call sites enumerated by direct `Grep`.
  - *Missing*: n/a.
  - *Procedure*: steps 1â€“4 above; step 4 alone is decisive.
  - *Severity effect*: `High` confirmed. The rubric's High is "degrades protection, or user-visible
    but recoverable", and this is the surface that makes the degradations in F-086/F-088/F-089
    unreportable â€” an app that states "Protection ready" while four grants are missing is asserting
    something false at its most prominent point. It stops short of Critical because each individual
    gap is separately filed and none of the two *modelled* permissions is mis-reported.
- **Build scope**: everyday-build
- **Constitution**: II (the permission set is enumerated in one place and silently re-derived
  elsewhere â€” `MainActivity.kt:197` recomputes `ready = accessibilityOn && overlayOn` inline instead
  of calling `missing()`), V (an absolute claim rendered from a partial model)
- **Affected surfaces**: `MainActivity.evaluateScreen():197` duplicates the readiness predicate
  inline; `OnboardingScreen.kt:227,242` duplicates it a third time (`hasUsageAccess && hasOverlay`).
  All three should route through `SetupRequirements.missing(...).isEmpty()`. **F-073** owns the
  related duplication of `actionLabel` at `HomeScreen.kt:194` and its fix belongs in this same pass.
- **Observed against**: `557a963` â€” `SetupRequirements.kt`, `HomeScreen.kt`, `SharedComponents.kt`
  and `MainActivity.kt` carry no uncommitted modifications.
- **Disposition**: `verified`

---

### F-092 â€” On Android 13+ the accessibility CTA lands on a toggle the OS has greyed out for sideloaded installs, and the app offers no guidance for the "Restricted setting" flow  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’ `settingsIntentFor()` (lines
  129â€“131, the `ACCESSIBILITY` branch) and
  `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` â†’ the "Grant Accessibility" button
  (line 104); same intent re-derived at `app/src/main/java/com/grayzone/app/service/OverlayService.kt`
  is not involved
- **Issue**: Both accessibility CTAs launch the bare `Settings.ACTION_ACCESSIBILITY_SETTINGS` â€” the
  global list, with no way to indicate which service the user should enable. Since Android 13,
  accessibility is a **restricted setting**: for an app installed by a non-session installer (an APK
  opened from a browser, Files, or a messaging app), the per-service toggle in that list is disabled
  and tapping it shows "Restricted setting" with no path forward from that screen. The user must
  instead go to App info â†’ â‹® overflow â†’ "Allow restricted settings", then return. `INSTALL.md`
  documents that sideloading **is** this app's distribution model ("Send this file to the person who
  wants to install it (WhatsApp, Drive, email, USB)"), and `app/build.gradle.kts:29` sets
  `targetSdk = 34`, so the modal install path for real users hits the restricted-settings gate. The
  app has no handling for it: `Grep` over `app/src` finds zero references to
  `ACTION_APPLICATION_DETAILS_SETTINGS`, and no string anywhere mentions restricted settings. The
  observable outcome is an onboarding dead end â€” the user taps "Grant Accessibility", cannot enable
  the toggle, returns, and `OnboardingScreen`'s `ON_RESUME` re-reads `false` and shows the same
  button again. `INSTALL.md` itself covers the install-time Play Protect block in detail but says
  nothing about this post-install gate, so neither the app nor its documentation prepares the user.
- **Trigger condition**:
  1. Build the release APK (`./gradlew assembleRelease "-Dorg.gradle.java.home=C:\Program
     Files\jdk-21.0.11"`).
  2. Transfer it to an Android 13+ device by a non-`adb` route â€” send via WhatsApp/Drive and open the
     file from the Files app or the browser (this is the distinguishing step; `adb install` uses a
     session installer and is **exempt**).
  3. Install and open Grayzone; on the onboarding screen tap **Grant Accessibility**.
  4. In the Accessibility list, tap Grayzone: the toggle is greyed out and a "Restricted setting"
     dialog appears with no action.
  5. Return to Grayzone: the same "Grant Accessibility" button is shown, with no explanation and no
     route to App info â†’ â‹® â†’ Allow restricted settings.
- **Blast radius**:
  - *Callers*: `settingsIntentFor` â€” `HomeScreen.kt:187` only (direct `Grep`; the
    `SetupRequirement.ACCESSIBILITY` branch is reached from there). The duplicate raw intent â€”
    `OnboardingScreen.kt:104`. Both are the app's only accessibility CTAs, so the dead end has no
    alternative route.
  - *Process death*: none â€” the CTA is stateless.
  - *Config change*: none.
  - *Foreground/background*: both CTAs are user-tapped from a resumed Activity (prior-fix P6
    deliberately removed all auto-launching), so the background-activity-start block in F-082 does
    not apply here.
  - *Cross-process*: none.
  - *Persistence*: indirect. Each failed round trip runs `OnboardingScreen`'s `ON_RESUME` handler,
    which leaves `ACCESSIBILITY_PROMPTED_FOR_LOSS` untouched when the permission is still off
    (`:47â€“52` only writes on success) â€” so no flag is burned by the loop, which is correct behaviour
    and worth crediting.
  - *Release-only*: **yes, in effect.** The restriction keys off the installing package, and the
    everyday development loop uses `adb install` / `installDebug`, which are session installs and
    exempt. The defect is therefore invisible to the developer and appears only on the distribution
    path real users take. The uncommitted `debug { isDebuggable = false }` +
    release-key-signed-debug block (`app/build.gradle.kts:69â€“80`) narrows the debug/release gap for
    signature purposes but does nothing about the installer, so it does not help here.
  - *Callers (fix scope)*: `AppUtils.settingsIntentFor` is the single place a fix should land, so both
    CTAs inherit it once `OnboardingScreen.kt:104` is routed through it.
- **Fix** (minimal): pair the accessibility CTA with the escape hatch. In `OnboardingScreen` and
  `HomeScreen`, alongside the "Turn on Accessibility" button, add a secondary text button â€” "Toggle
  greyed out? Open app info" â€” launching
  `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))`
  with a one-line explanation ("On Android 13+, apps installed from a file must first allow
  restricted settings: â‹® â†’ Allow restricted settings"). Route `OnboardingScreen.kt:104` through
  `settingsIntentFor(context, SetupRequirement.ACCESSIBILITY)` at the same time so the two CTAs
  cannot diverge. Gate the hint on `Build.VERSION.SDK_INT >= 33` so it is not shown where it is
  meaningless. `INSTALL.md` should gain the same note.
- **Rewrite justification**: n/a â€” one secondary button plus a string, reusing the existing intent
  helper.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/RestrictedSettingsHintTest.kt`, over an extracted
  `SetupRequirements.needsRestrictedSettingsHint(sdkInt: Int, requirement: SetupRequirement):
  Boolean`, asserting `needsRestrictedSettingsHint(33, SetupRequirement.ACCESSIBILITY) == true`,
  `needsRestrictedSettingsHint(32, SetupRequirement.ACCESSIBILITY) == false`, and
  `needsRestrictedSettingsHint(34, SetupRequirement.OVERLAY) == false`. Keeping the predicate in the
  pure `SetupRequirements` object matches how the rest of this model is tested. Fails before the fix
  (the function does not exist), passes after.
- **Existing test conflict**: none â€” the five existing `SetupRequirementsTest` cases assert
  `missing`/`headline`/`body`/`actionLabel` and are unaffected by a new pure predicate.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device confirmation that the restricted-settings gate applies to this APK on a
    target device, and confirmation of which installer sources trigger it on the OEM builds the
    maintainer ships to. The code-side facts are `confirmed`: both CTAs launch the bare global
    accessibility intent, and `ACTION_APPLICATION_DETAILS_SETTINGS` has zero matches in `app/src`.
  - *Procedure*: steps 1â€“5 above on an Android 13, 14 and 15 device. The comparison that isolates the
    cause is running the same steps twice â€” once with `adb install -r` (expected: toggle enabled) and
    once with a file-manager install (expected: toggle greyed out).
  - *Severity effect*: `High` if confirmed on the sideload path â€” the app's single most important
    permission cannot be granted through the route the app itself offers, on the distribution channel
    its own `INSTALL.md` prescribes, with no in-app explanation. Drops to `Medium` if the maintainer
    moves to the Play Internal Testing track that `INSTALL.md:62â€“71` already describes, since Play
    installs are session installs and exempt â€” in that case the remaining defect is only the missing
    hint for users who sideloaded earlier builds.
- **Build scope**: everyday-build (the *defect* ships in the everyday build; it is only *observable*
  on non-`adb` installs, which is why it is also listed under the release-only note above)
- **Constitution**: II (the accessibility CTA is built twice, once via `settingsIntentFor` and once
  raw in `OnboardingScreen`), V (the setup flow asserts an action the platform may refuse, with no
  stated handling)
- **Affected surfaces**: `OnboardingScreen.kt:104` (raw duplicate intent), `HomeScreen.kt:187` (via
  `settingsIntentFor`), `INSTALL.md:52â€“58` (the post-install permission list that omits this step â€”
  docs accuracy is owned by **T025b**, so the `INSTALL.md` edit should be routed there rather than
  filed separately; note `INSTALL.md:56` also lists "Usage access" as a permission to grant, which
  F-084 shows the app never requests or uses).
- **Observed against**: `working-tree @ 557a963 (uncommitted: build.gradle.kts)` â€” `targetSdk = 34`
  at `build.gradle.kts:29` is unchanged by the diff; `AppUtils.kt` and `OnboardingScreen.kt` are
  unmodified. `INSTALL.md` is untracked (new in this working tree).
- **Disposition**: `fix-pending-device-verification`

---

### F-093 â€” `evaluateScreen()` writes three preferences as a side effect of Compose composition, so the one allowed accessibility prompt is consumed before it is rendered  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’
  `MainAppContent.evaluateScreen()` (lines 189â€“207; the writes at `:196`, `:199`, and via `:221`),
  invoked from the composition-time initializer at line 209
- **Issue**: `evaluateScreen()` is not a query â€” it persists `ACCESSIBILITY_PROMPTED_FOR_LOSS` at
  `:196` and `ONBOARDING_COMPLETED` at `:199` â€” and it is called from
  `var currentScreen by remember { mutableStateOf(evaluateScreen()) }` at `:209`, i.e. **inside
  composition**. Deciding whether to prompt and consuming the one allowed prompt are therefore the
  same call, and that call happens in a context Compose does not guarantee to be side-effect-free:
  a composition may be started and abandoned, and `remember`'s calculation runs before the frame is
  ever presented. The flag is written `true` at `:196` and the `ONBOARDING` screen is returned at
  `:206`, so any interruption between the `apply()` and the first rendered frame â€” process death, or
  an abandoned composition â€” leaves `promptedForLoss = true` with nothing shown, and the next
  evaluation takes the `shouldPrompt = false` branch to `MAIN`. This is the same shape as **F-209**
  (flag set before the action succeeds) at a different site and on a different flag, so it is filed
  separately rather than merged. It is also the structural risk recorded as "Fragile fixes #5" in
  [`evidence/prior-fix-watchlist.md`](./evidence/prior-fix-watchlist.md), which notes the read-decide-
  persist trio is split across `MainActivity.kt:196` and `OnboardingScreen.kt:50â€“51` with no single
  owner. `AccessibilityPromptPolicy` itself is clean â€” it is pure, returns the value to persist, and
  is fully tested; the defect is entirely in how the caller sequences the persist against the render.
- **Trigger condition**:
  1. Grant accessibility + overlay, complete onboarding so `ONBOARDING_COMPLETED = true`.
  2. Revoke accessibility: `adb shell settings put secure enabled_accessibility_services ""`.
  3. Launch Grayzone and immediately kill it before the first frame renders:
     `adb shell am start -n com.grayzone.app/.MainActivity && adb shell am kill com.grayzone.app`
     (repeat a few times to win the race).
  4. Relaunch normally: the app opens on **MAIN**, not onboarding, because the flag was persisted on
     the aborted launch. The only remaining indication is `HomeScreen`'s amber setup card.
  5. Source-only confirmation that the ordering is wrong regardless of the race: `:196` writes before
     `:206` returns, and neither is conditional on the screen being displayed.
- **Blast radius**:
  - *Callers*: `evaluateScreen()` â€” `MainActivity.kt:209` (composition initializer) and `:213`
    (`ON_RESUME` observer). Writers of `ACCESSIBILITY_PROMPTED_FOR_LOSS` â€” `MainActivity.kt:196` and
    `OnboardingScreen.kt:51`; reader â€” `MainActivity.kt:193`. Writers of `ONBOARDING_COMPLETED` â€”
    `MainActivity.kt:199`, `:221`; readers â€” `MainActivity.kt:205`, `StateBackupManager.kt:107`.
    All by direct `Grep` over `app/src`.
  - *Process death*: this **is** the trigger axis. The window between `apply()` (asynchronous by
    definition) and the rendered frame is exactly where the prompt is lost. Note `apply()` is
    correct here â€” a `commit()` would make the loss *more* certain, not less â€” so the fix must move
    the write, not change its durability.
  - *Config change*: a rotation re-runs the composition. `remember` survives configuration change
    within the same `ComponentActivity` composition, so `:209` does not re-run on rotation â€” but
    `:213` fires on the subsequent `ON_RESUME` and re-evaluates, so a rotation while the accessibility
    permission is off performs another prefs write. Harmless in value (idempotent `true`) but it is a
    disk write per rotation.
  - *Foreground/background*: `evaluateScreen()` runs only in the foreground; the persisted decision
    it makes governs a foreground-only prompt. No background interaction.
  - *Cross-process*: none â€” `MainActivity` is default-process only, and
    `GrayzoneApplication.kt:52â€“54` keeps `:a11y` out of preferences entirely, so there is no second
    writer to race.
  - *Persistence*: yes â€” two SharedPreferences writes per evaluation, both captured by
    `StateBackupManager.writeBackup`'s `prefs.all` snapshot (`:167`) and restored verbatim, which is
    the compounding path in F-090.
  - *Release-only*: no.
- **Fix** (minimal): separate deciding from consuming. Make `evaluateScreen()` pure â€” return both the
  target `Screen` and the `Decision` without writing anything â€” and perform the two `putBoolean`
  calls from a `LaunchedEffect(currentScreen)` that runs only once the `ONBOARDING` screen is
  actually composed, and from the `ON_RESUME` observer at `:213` (which is a real lifecycle callback,
  not composition). `AccessibilityPromptPolicy.onObserved` already returns `promptedForLoss`
  precisely so the caller can persist it at the right moment; the fix is to honour that contract.
  This mirrors the F-209 fix (persist only after the action succeeds) and the two should be reviewed
  together for consistency.
- **Rewrite justification**: n/a â€” the function's body is unchanged apart from moving two `apply()`
  calls out of it; the policy object and the screen routing are untouched.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/SetupGateTest.kt`, over the extracted pure
  `SetupGate.resolve(accessibilityOn: Boolean, overlayOn: Boolean, onboardingCompleted: Boolean,
  promptedForLoss: Boolean): GateResult`, asserting that `resolve(...)` returns
  `GateResult(screen = Screen.ONBOARDING, persist = Decision(shouldPrompt = true, promptedForLoss =
  true))` **as a value** and â€” the point of the test â€” that the function performs no I/O, verified by
  constructing it with no `Context` parameter at all so a write is not expressible. Fails before the
  fix (`evaluateScreen` closes over `prefs` and cannot be called without writing), passes after.
  This is the same file F-090's second test uses; both assertions belong in it.
- **Existing test conflict**: none. `AccessibilityPromptStateTest`'s five cases assert the pure
  policy and are unaffected â€” indeed the fix makes the caller match what those tests already assume.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device execution of the race in step 3. The *ordering* defect â€” write at `:196`,
    return at `:206`, neither conditional on display â€” is `confirmed` from source and is the part the
    fix addresses; only the practical hit rate of losing a prompt to it needs a device.
  - *Procedure*: steps 1â€“4 above on any API 29+ device, repeated ~20 times to sample the race; step 5
    is the source-only check that stands on its own.
  - *Severity effect*: `Medium` as filed â€” the rubric's "incorrect behavior in narrow conditions" is
    exact, because the window is small and the user retains the `HomeScreen` setup card as a fallback
    signal. It would rise to `High` if the fallback card were removed or if F-091's model change made
    the card conditional. It does **not** reach the severity of F-209 despite the identical shape,
    because F-209's flag suppresses a prompt permanently while this one resets as soon as the
    permission is observed enabled.
- **Build scope**: everyday-build
- **Constitution**: III (a persisted decision is committed before the action it authorises has
  occurred, with no path to undo it if the action does not happen)
- **Affected surfaces**: **F-209** (`MainActivity.requestAllFilesAccessOnce():91â€“92`) is the same
  write-before-act shape on `ALL_FILES_PROMPTED`; **F-088** shows that flag additionally never
  resets. `OnboardingScreen.kt:50â€“51` is the second, independent writer of the same accessibility
  flag that a single-owner fix should absorb.
- **Observed against**: `557a963` â€” `MainActivity.kt` carries no uncommitted modifications.
- **Disposition**: `verified`

---

### F-094 â€” `isAccessibilityServiceEnabled()` reports a failed `Settings.Secure` read as "revoked", so an unreadable setting is indistinguishable from a real loss  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’ `isAccessibilityServiceEnabled()`
  (lines 108â€“126, specifically the `catch` at lines 119â€“125)
- **Issue**: The function returns `Boolean`, and its failure path returns `false` after a
  `GrayzoneLogger.w`. Failing closed is the **right** direction for a protection app and this is
  credited as such â€” prior-fix P7 specified exactly this behaviour and the contract is met. The
  defect is that the single `Boolean` erases the distinction between "the OS says the service is
  off" and "we could not ask". Callers treat them identically: `evaluateScreen()` feeds the value
  straight into `AccessibilityPromptPolicy.onObserved(isEnabled = â€¦)`, so a transient read failure
  persists `promptedForLoss = true` (`MainActivity.kt:196`) and bounces the user to onboarding for a
  loss that did not occur; `HomeScreen` renders "Accessibility is off" with a CTA that will lead to a
  toggle already switched on. The state self-heals â€” the next successful read returns `true` and the
  policy resets the flag (`AccessibilityPromptState.kt:26â€“28`) â€” so the user-visible cost is one
  spurious onboarding bounce, and the `w()` log does emit in release (it is one of the two surviving
  levels, per F-064), so it is diagnosable. That combination is why this is `Low` and not higher.
- **Trigger condition**:
  1. There is no supported way to make `Settings.Secure.getString` throw on a normal device; the
     realistic sources are a `SecurityException` from a hardened/managed-profile `ContentResolver` or
     a `DeadObjectException` when the settings provider is unavailable during early boot.
  2. Source-only observation: `AppUtils.kt:119â€“125` returns `false` for any `Exception`, and no
     caller inspects the log or distinguishes the case â€” `evaluateScreen()` (`MainActivity.kt:190`),
     `HomeScreen` (`:42,52`), and `OnboardingScreen` (`:36,44`) all consume a bare `Boolean`.
  3. To observe the downstream effect deterministically, temporarily replace the body with `throw
     RuntimeException()` in a local build and launch: the app bounces to onboarding and
     `accessibility_prompted_for_loss` is set to `true`.
- **Blast radius**:
  - *Callers*: `MainActivity.evaluateScreen():190`, `HomeScreen():42,52`, `OnboardingScreen():36,44`
    â€” direct `Grep` over `app/src`, no other call sites in main or test source. The function is
    **live and must not be removed**; this finding proposes only a widened return shape.
  - *Process death*: none â€” the value is re-derived from a live read on every evaluation, so no
    incorrect result is cached.
  - *Config change*: none.
  - *Foreground/background*: only called from foreground paths today. If the F-080 fix adds a
    service-side reader, that reader would also need to distinguish the two cases before deciding to
    change the ongoing notification â€” an unreadable setting must not be rendered as "Protection is
    OFF". That makes this finding a prerequisite for F-080's fix quality, not merely hygiene.
  - *Cross-process*: none today; the same caution as above applies if a `:a11y`-side reader is ever
    added, which it should not be.
  - *Persistence*: indirect â€” a false `false` causes one `putBoolean(ACCESSIBILITY_PROMPTED_FOR_LOSS,
    true)` write at `MainActivity.kt:196`, which is then captured by the backup (F-090).
  - *Release-only*: no. The `w()` at `:120â€“123` does emit in release, unlike `d()`/`i()` (F-064), so
    this is one of the few paths in the app that is actually diagnosable in the field â€” worth
    preserving in the fix.
- **Fix** (minimal): return a three-state result instead of a `Boolean` â€”
  `sealed interface AccessibilityState { object Enabled; object Disabled; object Unknown }` â€” and
  have callers map `Unknown` to "keep the previous decision" rather than to `Disabled`. Concretely,
  in `evaluateScreen()` skip the `AccessibilityPromptPolicy.onObserved` call and the resulting write
  when the state is `Unknown`, leaving `promptedForLoss` untouched; in `HomeScreen`/`OnboardingScreen`
  keep the last rendered state. Everything still fails closed for *protection* purposes â€” nothing
  grants access on `Unknown` â€” but no prompt is consumed for a loss that was never observed. If the
  maintainer judges the three-state type too invasive for the benefit, the acceptable smaller change
  is to keep the `Boolean` and add an `onReadFailure: () -> Unit` callback that `evaluateScreen()`
  uses to skip its write.
- **Rewrite justification**: n/a â€” the function body is unchanged; only its return type widens, and
  the three call sites map the new case.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt` (extend the existing class),
  adding `unknown state does not consume the prompt`: assert that the new caller-side mapper
  `SetupGate.resolve(...)` (from F-093) leaves `promptedForLoss` at its prior value when the
  accessibility state is `Unknown`, i.e. `resolve(state = Unknown, promptedForLoss = false).persist
  == null`. Fails before the fix (`Unknown` is not representable and the write is unconditional),
  passes after. `AccessibilityServiceIdsTest`'s seven parsing cases already cover the *matcher*; this
  covers the *read-failure* case they cannot reach.
- **Existing test conflict**: none. `AccessibilityServiceIdsTest.kt:13â€“56` tests
  `AccessibilityServiceIds.isServiceEnabled` on strings and is unaffected by the wrapper's return
  type; `AccessibilityPromptStateTest`'s five cases assert the pure policy, which is unchanged.
- **Verification**: `confirmed` (evidence tier T1) for the mechanism â€” the `catch` returns `false`
  and no caller can distinguish it, read directly at `AppUtils.kt:119â€“125` with all three call sites
  enumerated. The *frequency* of a real read failure is unknown and is why the severity is Low rather
  than Medium.
  - *Missing*: no realistic reproduction exists without modifying the source (step 3 above) or a
    managed/hardened device.
  - *Procedure*: step 3 above in a local build, confirming the onboarding bounce and the
    `accessibility_prompted_for_loss` write.
  - *Severity effect*: `Low` in all outcomes as shipped â€” the state self-heals on the next successful
    read and the failure is logged at an emitting level. It rises to `Medium` if the F-080 fix wires
    this value into the ongoing notification without handling `Unknown`, because the app would then
    announce "Protection is OFF" on a transient read error.
- **Build scope**: everyday-build
- **Constitution**: III (a boundary failure is folded into a legitimate return value, so callers
  cannot handle it â€” the "returns-default-silently" pattern the sweep counts eight of, though this
  instance does log)
- **Affected surfaces**: `AppUtils.isPrivateDnsActive()` (`:154â€“165`) has the identical shape with
  `catch (_: Exception) { false }` and **no** log at all, which is strictly worse â€” but it is dead
  code (definition only, confirmed in the lead-auditor's RU-01 verification table) and is owned there.
  `StateBackupManager.androidId()` (`:253â€“258`) is a third instance.
- **Observed against**: `557a963` â€” `AppUtils.kt` carries no uncommitted modifications.
- **Disposition**: `verified`

---

## Summary

### Findings filed (F-080 â€¦ F-094 â€” block fully consumed, 15 findings)

| ID | Title | Severity |
|---|---|---|
| F-080 | Accessibility revocation undetectable while backgrounded; ongoing notification keeps asserting "Grayzone is active" | **Critical** |
| F-081 | Overlay-revocation recovery deep-links to the monitored app's settings page (parameter shadowing) | High |
| F-082 | Overlay-recovery `startActivity` is a background activity start, blocked without throwing | High |
| F-083 | `ProtectionStatus.overlayActive` reports service liveness, not permission health; model has no permission input | Medium |
| F-084 | `PACKAGE_USAGE_STATS` declared with a lint suppression, never requested or used | Medium |
| F-085 | `OnboardingScreen.hasUsageAccess` names a permission the code never checks | Low |
| F-086 | `POST_NOTIFICATIONS` requested once, result discarded, never re-checked | High |
| F-087 | Battery-optimization warning suppressed when any setup permission is missing; "Active" pill ignores it | Medium |
| F-088 | All-files access loss never re-detected â€” one-shot flag has no reset-on-grant | High |
| F-089 | VPN consent revocation never re-checked; screen claims "reconnecting" forever | High |
| F-090 | Anti-tamper restore reinstates prompt-suppression flags; reinstall lands in MAIN with accessibility off | High |
| F-091 | `SetupRequirement` models 2 of 7 permissions; "Protection ready" rendered from a partial model | High |
| F-092 | Android 13+ restricted-settings gate makes the accessibility CTA a dead end on the sideload path | High |
| F-093 | `evaluateScreen()` persists the prompt decision during composition, before the prompt renders | Medium |
| F-094 | `isAccessibilityServiceEnabled()` reports a failed read as "revoked" | Low |

**Counts**: 1 Critical, 8 High, 4 Medium, 2 Low.
**Verification**: 9 `confirmed` (T1), 6 `needs-verification` (T4) â€” F-082, F-086, F-089, F-090,
F-092, F-093.

### Per-permission verdict â€” is loss detected, and is it surfaced?

| Permission | Loss detected? | Surfaced in foreground? | Surfaced in background? | Verdict | Findings |
|---|---|---|---|---|---|
| Accessibility service | Foreground only (`ON_RESUME` Ã—3) | Yes â€” routes to onboarding once per loss, plus the Home setup card | **No** â€” notification still says "Grayzone is active" | **Silent while backgrounded** | F-080, F-090, F-093, F-094 |
| Overlay (`SYSTEM_ALERT_WINDOW`) | Foreground on `ON_RESUME`; also at point of use in `showOverlay()` | Yes â€” Home setup card and CTA | Partial â€” a toast fires, but the recovery deep-link targets the wrong app and the settings launch is silently blocked | **Detected, mis-surfaced** | F-081, F-082, F-083, F-080 |
| `PACKAGE_USAGE_STATS` | n/a â€” never requested, checked, or used | n/a | n/a | **Not applicable â€” declared but unused** | F-084, F-085 |
| `POST_NOTIFICATIONS` | **No** â€” requested once, result discarded, never re-read | No â€” not in `SetupRequirements`; Home still says "Protection ready" | **No** â€” denial is what removes the background surface | **Fully silent** | F-086, F-091 |
| `MANAGE_EXTERNAL_STORAGE` | Yes, on every `onResume` â€” then discarded at the one-shot flag | **No** â€” no UI anywhere reflects it | **No** â€” sole log call is `i()`, a no-op in every variant | **Detected then silently dropped** | F-088, F-090 |
| Battery-optimization exemption | Foreground only (`ON_RESUME`, Home tab) | Partial â€” amber card, but **suppressed** while any setup permission is missing; pill still reads "Active" | **No** | **Conditionally surfaced** | F-087, F-091, F-061 |
| VPN consent | Once per entry to the main UI; never on resume | Misleading â€” permanent "Protection interrupted â€” reconnectingâ€¦" that will never reconnect; reverts to a bare "Off" after process death | **No** | **Detected once, then falsely surfaced** | F-089, F-025 |

**The single sentence this table adds**: every detection site in the app is bound to an Activity
resuming, and every enforcement site runs in a service â€” so the app can only notice a lost permission
at the one moment it is not being used, and its one persistent background surface (the ongoing
notification) is a hardcoded string that never re-reads anything.

### Not resolvable statically

- Whether a denied `POST_NOTIFICATIONS` suppresses the foreground-service notification on the target
  devices (F-086) â€” governs whether the app has *any* background surface.
- Whether the background activity start in `showOverlay` is blocked on the target OEMs (F-082) â€” the
  difference between High and Low for that finding.
- Whether Android 13+ restricted settings gate the accessibility toggle for this APK's distribution
  path (F-092) â€” the difference between an onboarding dead end and a non-issue.
- Whether preparing another VPN revokes Grayzone's consent and tears down its service (F-089).
- Whether `ANDROID_ID` (and therefore backup decryptability) survives uninstall/reinstall with the
  same signing key (F-090) â€” determines which of two failure modes the reinstall path produces, and
  either way is worth reporting.
- The practical hit rate of the composition-time write race (F-093); the ordering defect itself is
  confirmed.

### Duplicates and contradictions against already-filed findings

- **No duplicates filed.** F-209, F-210, F-211, F-212, F-061, F-073, F-025 and C1 are cited in the
  findings above and never re-filed. F-088 is deliberately distinct from F-209: F-209 is
  *write-before-the-prompt-succeeds*, F-088 is *no reset on re-grant*, and fixing either leaves the
  other. F-093 is the same write-before-act shape as F-209 on a different flag in a different
  function, filed separately per the contract's prohibition on merged root causes.
- **One overlap to manage, not a contradiction.** F-083 touches
  `ProtectionStatus.accessibilityActive`, which is an unassigned Phase-1 lead (owner T020/T029, from
  `evidence/cross-process-inventory.md`). F-083 files only the `overlayActive` half and cites the
  accessibility half as context. T034 should treat them as one fix.
- **One contradiction with an evidence file, in the audit's favour.**
  `evidence/prior-fix-watchlist.md` states that the uncommitted `isDebuggable = false` leaves
  `BuildConfig.DEBUG` `true` so "`GrayzoneLogger` verbosity is unaffected". The lead-auditor note in
  `findings.md` supersedes this from generated output: `DEBUG` is `false` in both variants. This task
  used the corrected fact throughout â€” it is what makes F-088's `i()`-only trace a *zero*-trace
  failure rather than a logged one.
- **One item propagated rather than filed**: `INSTALL.md:56` lists "Usage access" among the
  permissions the recipient must grant, which F-084 shows the app never requests. `INSTALL.md` is
  untracked and new in this working tree, and docs accuracy is amendment 2's scope, so this belongs
  to **T025b** (block F-265 â€¦ F-269), not here.
- **Dead-code discipline**: every "unused" claim above was re-verified by direct `Grep` over
  `app/src` rather than read from `evidence/reference-index.md`, per its accuracy warning. Two
  symbols were specifically checked and found **live** â€” `AppUtils.isAccessibilityServiceEnabled`
  (three callers) and `ProtectionStatus`/`isFullyProtected` (constructed and asserted in
  `ProtectionStatusTest`) â€” and **no deletion is proposed for either**. The only deletion proposed in
  this file is one manifest line (F-084), which is a declaration, not code, and is still gated on
  maintainer confirmation.

# Findings â€” RU-04 `service` (T018)

**Review unit**: RU-04 = `app/src/main/java/com/grayzone/app/service/` (top level only; `overlay/` is
RU-05 / T023 and `vpn/` is RU-06 / T019)
**Files in scope (5, confirmed by directory listing)**: `OverlayService.kt`,
`GrayzoneAccessibilityService.kt`, `BootReceiver.kt`, `SessionExpiryReceiver.kt`,
`SessionExpiryScheduler.kt`
**Not in scope**: `AccessibilityServiceIds.kt` â€” checked; it lives in the root package
(`app/src/main/java/com/grayzone/app/AccessibilityServiceIds.kt`), not in `service/`, so it belongs to
RU-01 (T021/T022). RU-04 contains no sixth file.

**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md), field template and
FR-006 rubric per [`findings.md`](./findings.md).
**ID block**: F-001 â€¦ F-019. **Used**: F-001 â€¦ F-016.
**Ranking**: Critical first, then High, Medium, Low.

**Evidence base** (not re-derived here): [`findings.md`](./findings.md) verified-Criticals table (C1),
[`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md),
[`evidence/concurrency-inventory.md`](./evidence/concurrency-inventory.md),
[`evidence/reference-index.md`](./evidence/reference-index.md) (sole source for `Callers`),
[`evidence/cross-process-inventory.md`](./evidence/cross-process-inventory.md),
[`evidence/lint.md`](./evidence/lint.md).

**Excluded by instruction** (recorded so the omission is not silent): lint `MissingPermission` on
`setExactAndAllowWhileIdle` (`SessionExpiryScheduler.kt:46â€“69` is correct and is credited as a
positive example below); R8 stripping of manifest components; cross-process false sharing of in-memory
state (T010 proved zero instances); and the `commit()` calls at `OverlayService.kt:531`/`:600` as a
threading problem (both on `Dispatchers.IO`, Low/style only per `lint.md` Â§E4 as corrected).

**No device or emulator was available.** Every runtime claim is marked
`Verification: needs-verification` with an exact device procedure and the severity effect of each
outcome. Nothing below asserts observed runtime behavior.

---

## Critical

### F-001 â€” `WindowManager.addView` failure leaves NO overlay during a lockout, logged only as a message  [Critical]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.showOverlay()` (lines 989â€“994; the catch body is 992â€“994)
- **Issue**: `windowManager?.addView(root, wlp)` is wrapped in
  `catch (e: Exception) { Log.e(TAG, "addView failed: ${e.message}"); return }`. On failure **no
  overlay window exists at all** and the function returns, so a FRICTION pause screen, a LOCK, a
  BUDGET_LOCK or a SCHEDULE_LOCK simply never appears and the monitored app stays fully usable
  *during* a lockout. `overlayView` is assigned only on the success path (`:991`), so no downstream
  code can detect the absence, and the `return` at `:993` also skips the countdown job created at
  `:1003â€“1031` â€” meaning `markAppUnlocked` never runs, so for the FRICTION path the session
  bookkeeping silently diverges from what the user is shown. Nothing is surfaced to the user. The log
  line carries neither the throwable, nor the package, nor the overlay mode, so the incident cannot be
  attributed afterwards. Classified `logged-bare` / **VIOLATION** as row 45 of
  `evidence/error-handling-inventory.md`, and verified Critical candidate **C1** in `findings.md`.
- **Trigger condition**:
  1. Add a monitored app and let a session run to `activeUntil` so a LOCK is due (or exhaust a daily
     budget so a BUDGET_LOCK is due).
  2. Revoke *Display over other apps* for Grayzone while that app is foreground
     (`adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`), or use any OEM build that
     restricts `TYPE_APPLICATION_OVERLAY` for non-foreground apps.
  3. `addView` throws `BadTokenException` / `SecurityException` /
     `WindowManager$InvalidDisplayException`; the monitored app remains usable with no lock and no
     message.
- **Blast radius**:
  - *Callers*: `showOverlay` is private; every call site is inside `OverlayService.kt` and each one is
    an enforcement path â€” `performMidnightReset()` (`:282`, re-entry friction at midnight),
    `executeCommand()` for `ShowWaitScreen` (`:493`), `ShowLockoutScreen` (`:498`),
    `ShowScheduleLockScreen` (`:503`), `ShowBudgetLockScreen` (`:508`),
    `logUsageAndCheckBudget()` (`:612`, budget lock), `handleSessionExpired()` (`:653`, the alarm-driven
    lock), and `LockPresentation.onCountdownFinished` â†’ `CountdownFinishContext.showFriction`
    (`:1028`). The enclosing service is reached cross-process and cross-restart from
    `AndroidManifest.xml`, `MainActivity.kt` (2 sites), `GrayzoneAccessibilityService.kt` (5 sites),
    `BootReceiver.kt` (4 sites) and `SessionExpiryReceiver.kt` (3 sites) per
    `evidence/reference-index.md` â†’ Â§`OverlayService.kt`.
  - *Process death*: no change to the defect. The prefs written by `markAppUnlocked`/`executeCommand`
    survive, so after a restart the policy engine re-evaluates on the next foreground transition and
    tries to show the same overlay again â€” and fails the same way while the cause persists.
  - *Config change*: no effect. The overlay is a `TYPE_APPLICATION_OVERLAY` window built
    programmatically, not an Activity; there is no configuration-driven recreation path.
  - *Foreground/background*: this is the foreground-critical path. The failure only matters while the
    monitored app is foreground, which is exactly when it happens.
  - *Cross-process*: reachable from `:a11y`. `GrayzoneAccessibilityService.onAccessibilityEvent`
    (`:86â€“89` broadcast, `:93â€“99` watchdog `startForegroundService`) is what drives
    `handleForegroundAppChanged` â†’ `executeCommand` â†’ `showOverlay`. The failure is therefore
    triggered by the second process but is invisible to it â€” `:a11y` gets no result back from either
    channel.
  - *Persistence*: yes, indirectly. On the FRICTION path the skipped countdown job means
    `markAppUnlocked` (`:1047â€“1100`) never writes `ACTIVE_UNTIL`/`LOCKED_UNTIL` and never removes
    `REMAINING_MILLIS`, so prefs keep the pre-session state while the user is inside the app; no Room
    row is written either (`logUsageAndCheckBudget` is only reached via the background transition).
  - *Release-only*: no. `Log.e` and `addView` are unaffected by R8; `proguard-rules.pro` keeps
    `OverlayService` and AGP generates the manifest-component keep rules (`evidence/r8.md` Â§F2).
- **Fix** (minimal): replace the catch body with a logged-with-context failure plus the
  user-visible signal the same function already implements 190 lines earlier for a revoked overlay
  permission (`:791â€“803`, warn + Toast):
  `GrayzoneLogger.e(LogComponent.OVERLAY, "addView failed for $packageName mode=$mode", e)`, then
  `ProtectionHealthRepository.updateOverlayStatus(false)` (existing API, `data/ProtectionHealthRepository.kt:30`,
  already published into `ProtectionStatus.overlayActive`), then the existing Toast block, then
  `return`. No new API, no restructuring.
- **Rewrite justification**: n/a â€” minimal patch to one catch body plus reuse of two existing calls.
- **Test to add**: `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/OverlayAddViewFailureTest.kt`,
  asserting that with `SYSTEM_ALERT_WINDOW` denied (`appops â€¦ deny`) a lock-due transition leaves
  `ProtectionHealthRepository.status.value.overlayActive == false` and emits a log record naming the
  package and the mode. Before the fix `overlayActive` stays `true` and the only record is
  `addView failed: <msg>`. A jvm-unit test is not possible today: `showOverlay` has no seam that can be
  driven without a real `WindowManager`, and the project has no Robolectric dependency
  (`app/build.gradle.kts:150â€“152` â€” JUnit + Mockito only). That absent seam is itself part of why the
  defect survived.
- **Existing test conflict**: none. `OverlayPresentationTest.kt` exercises the presentation strategies,
  never `showOverlay`; the reference index records `OverlayService` as having **no** unit tests.
- **Verification**: `confirmed` (evidence tier `T1` â€” source read directly, and independently re-read
  by the lead auditor as C1). Only the *specific throwable* a given device raises is unverified; that
  the failure produces no overlay and no user signal is a static fact.
  - *Missing*: nothing for the finding itself. For the throwable identity: a device with the overlay
    permission revocable at runtime.
  - *Procedure*: `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`, foreground a
    monitored app with an expired session, `adb logcat -s GrayzoneOverlay Grayzone` and confirm the
    lock never renders.
  - *Severity effect*: stays **Critical** under every outcome. If a device instead throws before
    `addView` (inside `WindowManager.LayoutParams`), the finding still holds because the same catch
    swallows it; if `canDrawOverlays` already returns `false` the earlier branch at `:790â€“822` handles
    it correctly, which narrows the *trigger* but not the severity of the unhandled residue.
- **Build scope**: `everyday-build`
- **Constitution**: III (Robust Error Handling â€” a catch that logs too thinly to diagnose, on an I/O
  boundary, masking a protection failure); also the Code Quality Standard "I/O and service-boundary
  code MUST log failures with actionable context (what failed, on which resource)".
- **Affected surfaces**: the same file uses raw `android.util.Log` instead of `GrayzoneLogger` at
  `:993` and `:1129` â€” see F-016 for that root cause. The sibling `removeView` catch at `:1039â€“1042`
  is compliant and shows the intended house style. T023 owns the presentation-side half of the same
  user-visible outcome.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `fix-pending-device-verification`

### F-002 â€” `startForeground` in `OverlayService.onCreate` is unguarded, so protection never starts and the main process crashes on every retry  [Critical]

> **Disposition**: **withdrawn â€” consolidated into F-205** (`findings-principle3.md`), which covers
> the same `OverlayService.onCreate:144` site **and** `AdBlockVpnService.startVpn:189`. One root cause,
> one fix. T033.

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.onCreate()` (line 144; the unguarded prologue is 141â€“148)
- **Issue**: `startForeground(NOTIF_ID, buildNotification())` has no failure handling
  (`evidence/error-handling-inventory.md` Â§2.10 row 157, **UNPROTECTED**, named there as the
  highest-value unprotected boundary in the app). A throw propagates out of `Service.onCreate` on the
  main thread of the **default** process, which kills `MainActivity`, `AdBlockVpnService` and every
  scope in the concurrency inventory's main-process column. Because the accessibility service lives in
  `:a11y` (`AndroidManifest.xml:82`) it survives the crash and keeps firing its watchdog
  `startForegroundService` on every window change (`GrayzoneAccessibilityService.kt:93â€“99`), so the
  same crash repeats on every app switch â€” the identical "every launch retries the same crash" shape
  the inventory documents for `AdBlockVpnService.kt:189`. Protection is absent throughout, and the
  user's only visible symptom is an app that will not stay open.
  Two aggravating details, both static: (a) `ProtectionHealthRepository.updateOverlayStatus(true)`
  runs at `:142` *before* `startForeground`, so the health model claims the overlay is active for the
  window between the two calls, and `onDestroy` (`:758â€“767`, which would set it back to `false`) is
  **not** invoked when `onCreate` throws; (b) the throw pre-empts everything the rest of `onCreate`
  does â€” the `WindowManager` handle (`:145`), `ScheduleManager` (`:146`), the session-expiry re-arm
  loop (`:157â€“162`), both receiver registrations (`:167â€“168`) and the midnight-reset loop (`:175`).
  Which start paths are exposed is derivable: `MainActivity.kt:257` starts it from a visible activity
  (exempt), `BootReceiver.kt:40` under the `BOOT_COMPLETED` exemption, `SessionExpiryReceiver.kt:26`
  under the AlarmManager while-idle temporary allowlist â€” the one path with no independent exemption is
  the `:a11y` watchdog, which relies on the app holding `SYSTEM_ALERT_WINDOW`. That makes the trigger
  the *same* condition as F-001: overlay permission revoked mid-session.
- **Trigger condition**:
  1. Install on API 31+ and grant accessibility but revoke *Display over other apps*
     (`adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`).
  2. Force-stop the app so `OverlayService` is not already running.
  3. Switch between two apps to make `:a11y` fire the watchdog `startForegroundService`.
  4. `startForeground` raises `ForegroundServiceStartNotAllowedException`; the app process dies and
     the next window change repeats it.
- **Blast radius**:
  - *Callers*: `onCreate` is a framework callback; the start paths that reach it are
    `MainActivity.kt` (2 sites), `service/GrayzoneAccessibilityService.kt` (5 sites),
    `service/BootReceiver.kt` (4 sites) and `service/SessionExpiryReceiver.kt` (3 sites), per
    `evidence/reference-index.md` â†’ Â§`OverlayService.kt`, plus the `AndroidManifest.xml` declaration
    and the self-restart alarm in `onTaskRemoved` (F-004).
  - *Process death*: this finding *is* a process-death finding. Nothing in prefs is corrupted, but
    every in-memory singleton in the default process is lost (`ProtectionHealthRepository`,
    `DnsTrafficBus`, `BlocklistManager`, `AppMetadataCache`, `DateUtils` caches â€” all `main-only` in
    `evidence/cross-process-inventory.md`), and `START_STICKY` (`:677`) plus the `:a11y` watchdog both
    drive an immediate retry into the same failure.
  - *Config change*: none â€” `Service`, no configuration-dependent state.
  - *Foreground/background*: decisive. The exposure exists only on the background-start paths; the
    `MainActivity` path runs while an activity is visible and is exempt.
  - *Cross-process*: yes. The `:a11y` process is the component that repeatedly triggers the crash and
    it receives no failure signal â€” `startForegroundService` succeeds from its side; the throw happens
    later, in the other process. `:a11y` is unaffected by the crash and so cannot back off.
  - *Persistence*: no direct writes. Indirectly severe: the re-arm loop at `:157â€“162` never runs, so
    in-flight sessions recorded in `ACTIVE_UNTIL` keep no expiry alarm and will never lock.
  - *Release-only*: no. `evidence/r8.md` Â§F2 confirms all manifest components survive R8.
- **Fix** (minimal): wrap the foreground-start prologue so a failure is recorded and the health model
  tells the truth instead of crashing the process â€” move `updateOverlayStatus(true)` to *after* a
  successful `startForeground`, and wrap `:143â€“144` in
  `try { â€¦ } catch (e: Exception) { GrayzoneLogger.e(LogComponent.OVERLAY, "startForeground failed; overlay protection not started", e); ProtectionHealthRepository.updateOverlayStatus(false); stopSelf(); return }`.
  This mirrors the pattern already credited in `AdBlockVpnService.kt:246â€“250`, which explicitly tears
  down foreground state on failure rather than leaving a mismatched lifecycle.
- **Rewrite justification**: n/a â€” one try/catch plus reordering two existing statements.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/OverlayForegroundStartTest.kt`, starting
  `OverlayService` from a background context with `SYSTEM_ALERT_WINDOW` denied and asserting the
  process survives and `ProtectionHealthRepository.status.value.overlayActive == false`. Before the
  fix the test process dies; after it, the assertion holds.
- **Existing test conflict**: none â€” `OverlayService` has no test coverage at all
  (`evidence/reference-index.md`).
- **Verification**: `needs-verification` (evidence tier `T2` â€” the unguarded boundary and the
  `updateOverlayStatus`-before-`startForeground` ordering are `T1` static facts; whether
  `startForeground` actually throws on the watchdog path is the runtime part).
  - *Missing*: an API 31+ device on which `SYSTEM_ALERT_WINDOW` can be denied while accessibility
    stays enabled, plus confirmation that Android's FGS background-start exemption for
    `SYSTEM_ALERT_WINDOW` holders is what currently protects this path.
  - *Procedure*: (1) `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW allow`, force-stop,
    switch apps, confirm `OverlayService started` appears in logcat â€” establishes the exempt baseline.
    (2) `adb shell appops set com.grayzone.app SYSTEM_ALERT_WINDOW deny`, `adb shell am force-stop
    com.grayzone.app`, switch apps twice, and watch `adb logcat -b crash` for
    `ForegroundServiceStartNotAllowedException` originating in `OverlayService.onCreate`.
    (3) Repeat with the battery-optimisation exemption both granted and revoked.
  - *Severity effect*: **Critical** if step 2 produces a crash (crash + protection absent + repeating
    retry, all three rubric triggers). **High** if no device can be made to throw: the boundary is
    still unprotected and the `updateOverlayStatus(true)`-before-`startForeground` ordering is still
    wrong, but the consequence would be latent rather than reachable. It cannot drop below High while
    the ordering defect stands.
- **Build scope**: `everyday-build`
- **Constitution**: III (an I/O / service-callback boundary with no failure handling).
- **Affected surfaces**: `AdBlockVpnService.kt:189` is the same defect in RU-06 (owner T019) and is
  already recorded in the inventory; `OverlayService.kt:1181â€“1187` (`createNotificationChannel`, called
  at `:143`) and the `nm.notify` sites at `:728`/`:755` are unguarded on the same entry path. The
  manifest half is correct and should be credited, not touched: `foregroundServiceType="specialUse"`
  plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (`AndroidManifest.xml:65â€“72`) and the
  `FOREGROUND_SERVICE_SPECIAL_USE` permission (`:20`) satisfy the API 34 typed-FGS contract, and
  `startForeground` is called synchronously inside `onCreate`, so the 5-second `startForegroundService`
  window is met on every entry path.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt, app/src/main/AndroidManifest.xml)`
- **Disposition**: **withdrawn â€” consolidated into F-205** (`findings-principle3.md`).

---

## High

### F-003 â€” Inexact alarms are the DEFAULT on API 31+, so a session lock arrives minutes late with no user signal while the notification counts into negative time  [High]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/SessionExpiryScheduler.kt` â†’
  `SessionExpiryScheduler.arm()` (lines 41â€“70, decision at `:46`, inexact path at `:50`);
  consequence at `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `startCountdownNotification()` (lines 706â€“751, chronometer at `:721â€“726`) and
  `handleSessionExpired()` (lines 630â€“657)
- **Issue**: Neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM` is declared anywhere in `app/`
  (verified by search in `evidence/lint.md` Â§E1 and re-confirmed by a working-tree grep: the only
  occurrences of `canScheduleExactAlarms` are the two guard sites). Therefore `canScheduleExactAlarms()`
  returns `false` on API 31+ **unless** the user granted the battery-optimisation exemption, and
  `am.setAndAllowWhileIdle(...)` at `:50` is the *normal* path on modern devices, not the exceptional
  one. `setAndAllowWhileIdle` is throttled in Doze to roughly one delivery per app per ~9 minutes, and
  further by App Standby buckets, so the alarm that is supposed to end a session at `activeUntil` can
  fire well after it.
  **What happens during the deferral, derived statically:**
  1. **Enforcement gap.** The only thing that ends a session while the user stays inside the monitored
     app is this alarm. `GrayzoneAccessibilityService` emits only
     `TYPE_WINDOW_STATE_CHANGED` (`:44`), and `processForegroundAppChanged` returns immediately when
     the package has not changed (`OverlayService.kt:330`), so navigating within the app never
     re-evaluates policy. The leak ends only when the user switches away or the screen turns off:
     `SessionPolicyEngine.evaluate` then takes the `isLockedOut(now)` branch
     (`policy/SessionPolicyEngine.kt:62â€“65`) on the next foreground. So the over-run is bounded by
     "user leaves the app", not by any timer.
  2. **The lock screen itself does reconcile.** `LockPresentation.initialSeconds` is
     `((lockedUntil - now) / 1000)` (`service/overlay/LockPresentation.kt:16â€“17`) and
     `SessionExpiryDecision.decide` re-reads `now` against `activeUntil`/`lockedUntil`
     (`policy/SessionExpiryDecision.kt:43â€“49`), so a late lock shows the correct *remaining* lockout.
     That half is right and should be credited.
  3. **The notification countdown does NOT reconcile â€” it drifts.** `startCountdownNotification` posts a
     `RemoteViews` `Chronometer` with `setChronometerCountDown(true)` and
     `base = elapsedRealtime() + remainingMs` (`:721â€“726`). Nothing recomputes it; it is only replaced
     when `cancelCountdownNotification()` runs, which on this path happens **only** when the alarm
     finally arrives (`handleSessionExpired` â†’ `ClampCountdown` at `:647â€“648`, or the
     `!presentation.showsProgress` branch at `:831â€“833`). A `Chronometer` past its base renders
     negative values, so for the whole deferral the ongoing notification displays a negative countdown
     â€” precisely the regression feature 002 was written to eliminate ("left the countdown notification
     ticking into negative values", `SessionExpiryScheduler.kt:14â€“16`), reintroduced through Doze
     rather than through an in-memory `delay()`.
  4. **No user signal, and the docs assert the opposite.** Nothing tells the user timing is
     approximate (grep for user-facing copy found none). Three doc sites call the fallback
     "Doze-safe": `SessionExpiryScheduler.kt:12` and `:38â€“39`, and
     `AndroidManifest.xml:93â€“95` ("reliably across Doze/screen-off/process death"), as does
     `OverlayService.kt:625â€“628`. `setAndAllowWhileIdle` is Doze-*permitted*, not Doze-*punctual*.
  Recorded as the High candidate in `evidence/lint.md` Â§E2 and in `findings.md`'s confirmed-leads
  table; this finding answers the two questions Â§E2 left open (user signalling: none; wall-clock
  reconciliation: yes for the lock screen, no for the notification).
- **Trigger condition**:
  1. API 31+ device, battery-optimisation exemption **not** granted (the default after install).
  2. Set a 10-minute session on a monitored app and open it; a session is granted and
     `SessionExpiryScheduler.arm` takes the `:50` inexact path.
  3. Leave the screen on and stay inside the app (or let the device idle into Doze with the app
     foreground).
  4. At `activeUntil` no lock appears; the ongoing notification's countdown passes 00:00 and goes
     negative until the throttled alarm is delivered.
- **Blast radius**:
  - *Callers*: `SessionExpiryScheduler.arm()` is called from `service/OverlayService.kt` â€” verified
    sites `onCreate()` re-arm loop (`:160`), `executeCommand()` / `UpdateState` (`:529`) and
    `markAppUnlocked()` (`:1098`). `cancel()` is called from `performMidnightReset()` (`:267`) and
    `executeCommand()` / `clearActiveSession` (`:523`). The object is additionally referenced from
    `service/SessionExpiryReceiver.kt` (KDoc). `evidence/reference-index.md` records "4 sites" for
    `arm()`; a direct re-grep of the working tree finds the three above â€” the count differs, the set
    does not change this finding. `arm()` name-collides with `VpnHoldExpiryScheduler.arm()` per the
    index.
  - *Process death*: none â€” that is the point of using AlarmManager. The alarm survives process death
    and reboot is covered by the `onCreate` re-arm loop (`:157â€“162`). The deferral is orthogonal.
  - *Config change*: none.
  - *Foreground/background*: the defect is worst with the monitored app in the **foreground** and the
    device idle, because that is when no transition re-evaluates policy.
  - *Cross-process*: n/a for the alarm itself (`SessionExpiryScheduler` is `main-only` in
    `evidence/cross-process-inventory.md`). Relevant indirectly: because `:a11y` only reports package
    *changes*, there is no second enforcement channel while the user stays put.
  - *Persistence*: no writes here. `ACTIVE_UNTIL`/`LOCKED_UNTIL` remain correct throughout, which is
    why the late lock still computes a correct remaining lockout.
  - *Release-only*: no.
- **Fix** (minimal, two parts): (1) make the deferral visible instead of silent â€” in the inexact branch
  at `:50`, log via `GrayzoneLogger.i(LogComponent.OVERLAY, "Exact alarms unavailable; session lock for $packageName may be deferred under Doze")`
  and return a Boolean the caller can surface, and correct the three doc claims that call this path
  "Doze-safe". (2) stop the notification drifting: clamp it at the boundary by scheduling the
  chronometer's own end â€” simplest correct form is to pass
  `setChronometer(R.id.notif_timer, baseElapsed, null, remainingMs > 0)` together with a second,
  already-armed inexact alarm is *not* needed; instead reuse the existing pure helper
  `SessionExpiryDecision.clampRemainingMs(now, activeUntil)`
  (`policy/SessionExpiryDecision.kt:53â€“54`, currently **TEST-ONLY** per the reference index) when the
  notification is rebuilt, and re-post the plain notification from `onStartCommand`'s
  `ACTION_START_COUNTDOWN` path when the clamped value is `0`. That converts an existing test-only
  helper into a live one rather than adding new logic.
- **Rewrite justification**: n/a for part (1). Part (2) reuses an existing helper instead of adding a
  mechanism; declaring `USE_EXACT_ALARM` is deliberately **not** proposed â€” it is a Play Console
  policy-restricted permission and `evidence/lint.md` Â§E5 already records the battery-exemption
  policy tension.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionExpiryLateAlarmTest.kt`, asserting via
  `SessionExpiryDecision.decide` and `LockPresentation.initialSeconds` that an alarm delivered
  `activeUntil + 15 min` still yields `ShowLock` with a lockout remainder of
  `lockedUntil - now` (not `lockoutMins`), and that `clampRemainingMs(now, activeUntil)` returns `0`
  rather than a negative value for the notification. This locks in the reconciliation that exists and
  fails today for the notification path once `clampRemainingMs` is wired in.
- **Existing test conflict**: none. `SessionExpiryDecisionTest.kt` already covers `decide()` and
  `clampRemainingMs()`; the new test extends rather than contradicts it. `SessionExpiryScheduler`
  itself has no test coverage (reference index).
- **Verification**: `needs-verification` (evidence tier `T2`). Static and `T1`: no exact-alarm
  permission is declared; the guard chooses the inexact path when `canScheduleExactAlarms()` is
  `false`; the chronometer is never recomputed. Runtime: the actual deferral magnitude.
  - *Missing*: an API 31+ device that can be forced into Doze.
  - *Procedure*: install; confirm `adb shell dumpsys package com.grayzone.app | grep -i exact` shows no
    exact-alarm permission and `adb shell cmd alarm get-config` / `dumpsys alarm` shows the app without
    the exact-alarm allowlist. Grant a 2-minute session, then
    `adb shell dumpsys deviceidle force-idle`, and record the wall-clock delta between `activeUntil`
    and the `SessionExpiryReceiver` delivery in `adb shell dumpsys alarm | grep grayzone`. Repeat with
    `adb shell dumpsys deviceidle unforce` and with the battery exemption granted (which should flip
    `canScheduleExactAlarms()` to `true` and make the delay disappear). Also photograph the ongoing
    notification during the deferral to confirm the negative countdown.
  - *Severity effect*: **High** if the measured deferral exceeds ~1 minute in Doze (protection lapses
    silently, which the FR-006 Grayzone calibration ranks at or above a visible error). **Critical** if
    the deferral is unbounded in the `rare`/`restricted` App Standby bucket such that a session never
    ends until the user leaves the app *and* the notification shows negative time for hours.
    **Medium** if delivery proves punctual on the tested devices, in which case only the drifting
    notification and the inaccurate "Doze-safe" documentation remain.
- **Build scope**: `everyday-build`
- **Constitution**: none directly (no swallowed exception; the guard is correct). The inaccurate
  "Doze-safe" claims engage Principle IV's rationale that comments must not decay against the code
  they describe â€” see F-008 for the same class of defect.
- **Affected surfaces**: `service/vpn/VpnHoldExpiryScheduler.kt:37â€“52` uses the identical guard shape
  for the 24-hour VPN hold auto-off and inherits the identical deferral (owner T019; `evidence/lint.md`
  Â§E2 assigns it there). `OverlayService.kt:706â€“751` and `:753â€“756` are the notification half.
  T025 owns the `BatteryLife` policy warning (`evidence/lint.md` Â§E5) that this interacts with, since
  the exact-alarm capability is currently a side-effect of the battery exemption.
- **Observed against**: `557a963` for `SessionExpiryScheduler.kt`;
  `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt, app/src/main/AndroidManifest.xml)`
  for the notification and documentation halves.
- **Disposition**: `fix-pending-device-verification`

### F-004 â€” The swipe-away self-restart alarm is unguarded, cannot wake the device, and starts the service in a way Android 8+ forbids from the background  [High]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.onTaskRemoved()` (lines 680â€“704; `PendingIntent` at 691â€“696, alarm at 698â€“703)
- **Issue**: three independent defects in the one recovery path that is supposed to bring protection
  back after the user swipes Grayzone out of Recents.
  1. **Unguarded** (`evidence/error-handling-inventory.md` Â§2.8 rows 150 and 151, both
     **UNPROTECTED**). Neither `PendingIntent.getService` nor `AlarmManager.set` is wrapped, and
     `onTaskRemoved` is a main-thread lifecycle callback, so a throw takes the process down *and* the
     restart is never scheduled. The `GrayzoneLogger.w` at `:683â€“686` has already claimed
     "scheduling restartâ€¦" by then, so the log actively misleads.
  2. **`ELAPSED_REALTIME`, not `ELAPSED_REALTIME_WAKEUP`** (`:700`). By documented AlarmManager
     semantics a non-wakeup alarm does not wake a sleeping device; if the screen goes off within the
     2-second window the restart is deferred until the device next wakes for some other reason. For a
     recovery path this is the wrong alarm type.
  3. **`PendingIntent.getService`, not `getForegroundService`** (`:691`). When the alarm fires the
     system performs a plain `startService` on the app's behalf. `OverlayService` immediately calls
     `startForeground` in `onCreate` (`:144`) and is declared `foregroundServiceType="specialUse"`, so
     `startForegroundService` is the required entry point â€” and `PendingIntent.getForegroundService`
     has existed since API 26, below this app's `minSdk = 29` (`app/build.gradle.kts:28`). Every other
     start site in the codebase uses `startForegroundService` (`BootReceiver.kt:40`,
     `SessionExpiryReceiver.kt:26`, `GrayzoneAccessibilityService.kt:93`, `MainActivity.kt:257`); this
     is the sole outlier. Compounding it, the alarm is a plain `set()` with no
     `*AndAllowWhileIdle`, so it earns no temporary power-allowlist window either â€” unlike
     `SessionExpiryScheduler`, whose while-idle alarm is what makes `SessionExpiryReceiver`'s
     foreground-service start legal (documented at `SessionExpiryReceiver.kt:12â€“14`).
  If the start is rejected, protection stays off until the user opens the app or `:a11y` fires the
  watchdog â€” with nothing logged, because the failure happens inside the system's delivery, not in the
  app.
- **Trigger condition**:
  1. Grayzone running with protection active.
  2. Swipe the Grayzone task out of Recents; `onTaskRemoved` schedules the 2-second alarm.
  3. Turn the screen off immediately (exercises defect 2) or leave the device idle (exercises
     defect 3).
  4. Observe whether `OverlayService started â€¦` reappears in logcat within ~2 seconds.
- **Blast radius**:
  - *Callers*: `onTaskRemoved` is a framework callback with no in-app caller. The `PendingIntent` it
    builds re-enters `OverlayService.onStartCommand` with **no action**, so it falls through every
    `else if` at `:660â€“676` and returns `START_STICKY` â€” intentional, since the work is all in
    `onCreate`. The class itself is reached from `AndroidManifest.xml`, `MainActivity.kt` (2 sites),
    `service/GrayzoneAccessibilityService.kt` (5 sites), `service/BootReceiver.kt` (4 sites) and
    `service/SessionExpiryReceiver.kt` (3 sites) per `evidence/reference-index.md`.
  - *Process death*: this path exists *because of* a user-initiated teardown. If the process is killed
    before the alarm fires, the alarm survives (AlarmManager is durable) but the delivery then targets
    a cold process, which is exactly when defect 3 bites hardest.
  - *Config change*: none.
  - *Foreground/background*: by construction the app is in the background when the alarm fires â€”
    the condition under which defect 3 applies.
  - *Cross-process*: the `:a11y` watchdog (`GrayzoneAccessibilityService.kt:91â€“99`, whose comment
    explicitly names "restarts it if swipe-away/crash killed it") is the redundant recovery channel and
    partially masks this defect. That redundancy is real and should be credited â€” but it only fires on
    the next app switch, and F-009 shows the coupling that keeps it working is a bare string literal.
  - *Persistence*: none. No prefs or Room writes on this path.
  - *Release-only*: no.
- **Fix** (minimal): `PendingIntent.getForegroundService(...)` instead of `getService(...)` at `:691`;
  `AlarmManager.ELAPSED_REALTIME_WAKEUP` instead of `ELAPSED_REALTIME` at `:700`; and wrap `:691â€“703`
  in `try { â€¦ } catch (e: Exception) { GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to schedule OverlayService restart after swipe-away", e) }`.
  Three one-line changes; no restructuring, no new permission.
- **Rewrite justification**: n/a â€” three in-place substitutions plus one try/catch.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/OverlayTaskRemovedRestartTest.kt`, driving
  `onTaskRemoved` and asserting that a pending alarm exists with a `getForegroundService`
  `PendingIntent` and `ELAPSED_REALTIME_WAKEUP`, and that no exception escapes when the alarm service
  is unavailable. A jvm-unit test cannot express this (no Robolectric; `AlarmManager` and
  `PendingIntent` are unavailable off-device).
- **Existing test conflict**: none â€” no test touches `OverlayService`.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: the boundary is
  unguarded; the alarm type is non-wakeup; the `PendingIntent` is `getService` while every other start
  site uses `startForegroundService`. Runtime: whether the system rejects the resulting
  `startService`, which depends on the app's exemptions at delivery time.
  - *Missing*: an API 31+ device, and confirmation of whether the app's `SYSTEM_ALERT_WINDOW` grant
    exempts the delivery.
  - *Procedure*: with the overlay permission granted, swipe the task away and check
    `adb shell dumpsys alarm | grep -A3 grayzone` for the pending alarm, then confirm from logcat
    whether `OverlayService started - listening for AccessibilityService events` (`:170â€“173`) reappears
    ~2 s later. Repeat with `appops set â€¦ SYSTEM_ALERT_WINDOW deny`, and repeat with the screen turned
    off inside the 2-second window. Look for `BackgroundServiceStartNotAllowedException` /
    `Not allowed to start service Intent` in `adb logcat -b crash,main`.
  - *Severity effect*: **High** if the restart fails or is deferred in any of the three scenarios
    (protection off with no signal, exactly what the FR-006 calibration ranks highest). **Medium** if
    every scenario restarts successfully â€” the unguarded boundary, the non-wakeup alarm type and the
    misleading "scheduling restartâ€¦" log all remain, and the `:a11y` watchdog is only a partial
    backstop. It cannot be dismissed as Low while a lifecycle callback can crash the process.
- **Build scope**: `everyday-build`
- **Constitution**: III (an IPC / AlarmManager boundary with no failure handling, on a recovery path).
- **Affected surfaces**: the `:a11y` watchdog start (`GrayzoneAccessibilityService.kt:93â€“99`) is the
  redundant path and is correctly guarded and logged â€” credit it. `MainActivity.kt:257` is the third
  restart path and is unguarded per the inventory (owner T021).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `fix-pending-device-verification`

### F-005 â€” Leaving a monitored app never removes its lock overlay: `DismissOverlay` is a no-op, so BUDGET_LOCK / SCHEDULE_LOCK / LOCK screens stay on top of unrelated apps  [High]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.executeCommand()` (lines 488â€“490, the `SessionCommand.DismissOverlay` branch)
- **Issue**: the policy engine emits `SessionCommand.DismissOverlay` whenever overlays should be
  cleared â€” for a non-monitored app (`policy/SessionPolicyEngine.kt:38`), a live session (`:57`), the
  global kill switch (`:22`) and an active daily break (`:30`). `OverlayService` handles it with an
  empty body and the comment "Intentional no-op: policy still emits this; leave/lock paths dismiss
  overlays." That claim does not hold. `dismissOverlay()` is reachable only from
  `performMidnightReset()` (`:273`), `onStartCommand`/`ACTION_STOP` (`:661`), the start of
  `showOverlay()` (`:825`), the skip-button click listener (`:952`), `onDestroy()` (`:766`), and the
  presentations' `CountdownFinishContext.dismissOverlay` (`:1026`). **None of those is an app switch.**
  Consequences differ by mode, and the two worst are unbounded:
  - `BudgetLockPresentation` and `ScheduleLockPresentation` both return `initialSeconds = 0`
    (`service/overlay/BudgetLockPresentation.kt:13`, `ScheduleLockPresentation.kt:13`), so the
    countdown loop at `OverlayService.kt:1005` is skipped, `onCountdownFinished` is a documented no-op,
    and both files state the intended contract in a comment: *"No countdown; the screen stays up until
    the user leaves the app."* The service never implements "until the user leaves the app". The
    overlay is a `MATCH_PARENT` / `MATCH_PARENT` `TYPE_APPLICATION_OVERLAY` window with an opaque
    background (`:867`, `:981â€“987`), so it stays covering the screen indefinitely â€” over the launcher,
    over other apps â€” until the user happens to tap the "â† Leave App" button, another overlay replaces
    it, midnight resets (`:273`), or the service is destroyed.
  - `LockPresentation` bounds itself only by the remaining lockout, `((lockedUntil - now) / 1000)`
    (`LockPresentation.kt:16â€“17`) â€” up to the 24-hour ceiling `markAppUnlocked` clamps to (`:1065`).
  - `FrictionPresentation` self-dismisses (`FrictionPresentation.kt:15â€“18`), so its exposure is only
    the wait duration (3â€“30 s) â€” but during it, `markAppUnlocked` still grants a full session for an
    app the user already left, which is the same window F-007 exploits.
- **Trigger condition**:
  1. Set a daily budget for a monitored app and exhaust it (or configure a schedule rule that is
     currently active).
  2. Open that app â€” the BUDGET_LOCK / SCHEDULE_LOCK overlay appears. Correct so far.
  3. Press Home (or switch to any non-monitored app) **instead of** tapping "â† Leave App".
  4. `:a11y` reports the new package â†’ `processForegroundAppChanged` â†’ `buildSessionState` says not
     monitored â†’ `DismissOverlay` â†’ no-op. The lock screen remains on top of the launcher with no
     countdown and no automatic exit.
- **Blast radius**:
  - *Callers*: `executeCommand` is private and `suspend`; its only callers are
    `processForegroundAppChanged()` (`:373`) and `backgroundApp()` (`:406`), which are in turn reached
    from `drainForegroundTransitions()` (`:323`) and `handleScreenOff()` (`:421`) â€” i.e. every
    app-switch and screen-off transition. The `SessionCommand.DismissOverlay` object itself is
    referenced from `policy/SessionPolicyEngine.kt` and `service/OverlayService.kt` per
    `evidence/reference-index.md` â†’ Â§`SessionModels.kt`, and is covered by the engine test suites.
  - *Process death*: the overlay window dies with the process, so a process kill is currently the
    most reliable way out â€” which is the wrong recovery mechanism to depend on.
  - *Config change*: rotating the device does not recreate a `WindowManager` overlay, so the stale
    window persists across rotation with layout params computed for the old configuration.
  - *Foreground/background*: this is precisely a background-transition defect: the overlay belongs to
    app A while app B (or the launcher) is foreground.
  - *Cross-process*: yes. The transition that should dismiss the overlay originates in `:a11y`
    (`GrayzoneAccessibilityService.kt:86â€“89`), so the trigger crosses the process boundary while the
    dismissal decision is dropped on the default-process side.
  - *Persistence*: no writes on the no-op branch. Indirectly, for the FRICTION case, the still-running
    countdown reaches `markAppUnlocked` and writes `ACTIVE_UNTIL`/`LOCKED_UNTIL` for an app the user
    has already left.
  - *Release-only*: no.
- **Fix** (minimal): implement the branch the presentations already document â€”
  `is SessionCommand.DismissOverlay -> withContext(Dispatchers.Main) { dismissOverlay() }` â€” and
  delete the inaccurate comment. `dismissOverlay()` (`:1034â€“1045`) is already idempotent (null-checks
  `overlayView`, catches and logs `removeView` failures) and already cancels `countdownJobUI`, so no
  guard is needed. `executeCommand` is a `suspend fun` on `Dispatchers.IO`
  (`evidence/concurrency-inventory.md`, "the lint correction"), hence the `Main` hop, matching the four
  existing `serviceScope.launch(Dispatchers.Main)` overlay calls at `:492`, `:497`, `:502`, `:507`.
- **Rewrite justification**: n/a â€” replaces an empty branch body with a call that already exists; no
  new API and no change to the policy engine.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/DismissOverlayContractTest.kt`, asserting that
  `SessionPolicyEngine.evaluate(AppForegrounded(nonMonitoredPkg), â€¦)` yields
  `SessionCommand.DismissOverlay` (locks in the contract that the service must honour), **plus**
  `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/OverlayDismissOnSwitchTest.kt`
  asserting that after a BUDGET_LOCK is shown and a non-monitored package is broadcast via
  `GrayzoneAccessibilityService.ACTION_APP_CHANGED`, no overlay view remains attached. The
  instrumented half fails before the fix and passes after; the jvm half documents the contract.
- **Existing test conflict**: `SessionPolicyEngineForegroundTest.kt` and `SessionPolicyEngineTest.kt`
  already assert that `DismissOverlay` is emitted; they need **no** change, because the fix is entirely
  service-side. `OverlayPresentationTest.kt` asserts the presentations' `onCountdownFinished`
  behaviour and is likewise unaffected.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: the branch body is
  empty; no app-switch path calls `dismissOverlay()`; `initialSeconds` is `0` for both budget and
  schedule locks so the countdown loop never runs. The runtime part is how obstructive the residual
  window is â€” `FLAG_NOT_FOCUSABLE` is set (`:985`) but `FLAG_NOT_TOUCHABLE` is not, so the full-screen
  window should still consume touches within its bounds.
  - *Missing*: a device to observe whether the stale overlay blocks interaction with the app beneath it
    and whether the system nav/gesture bar remains usable.
  - *Procedure*: exhaust a daily budget, open the app to raise the BUDGET_LOCK, press Home, then
    (1) try to tap a launcher icon, (2) run `adb shell dumpsys window windows | grep -i grayzone` to
    confirm the overlay window is still added, and (3) wait 5 minutes and re-check. Repeat for a
    SCHEDULE_LOCK and for a LOCK with a 60-minute lockout.
  - *Severity effect*: **High** as written â€” user-visible and recoverable (the "â† Leave App" button
    remains on screen and works), which is the rubric's High definition. It is **not** Critical: it
    over-blocks rather than silently disabling protection, and no data is lost. Escalate to
    **Critical** only if step (1) shows the device is effectively unusable *and* the overlay survives a
    reboot or cannot be dismissed without force-stopping the app. Drop to **Medium** if a device shows
    the framework removing the window on app switch by itself.
- **Build scope**: `everyday-build`
- **Constitution**: II (Consistent Strategy-Pattern Implementation â€” the `OverlayPresentation`
  strategies own the "screen stays up until the user leaves the app" behaviour, but the single call
  site drops the dismissal decision instead of delegating it); III is not engaged (no exception is
  involved).
- **Affected surfaces**: `service/overlay/BudgetLockPresentation.kt:15â€“17` and
  `ScheduleLockPresentation.kt:15â€“17` carry the contract comment that the service violates â€” RU-05,
  owner T023. `policy/SessionPolicyEngine.kt:22`, `:30`, `:38`, `:57` are the four emitting branches â€”
  RU-03, owner T024. `handleScreenOff()` (`:415â€“436`) is the second transition that should dismiss and
  does not.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-006 â€” `OverlayService`'s synchronous entry paths read SharedPreferences with no failure containment, contradicting the invariant its own comment states  [High]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.onCreate()` (lines 150â€“162) and `OverlayService.handleSessionExpired()`
  (lines 630â€“643)
- **Issue**: `evidence/error-handling-inventory.md` Â§2.2 rows 30 and 37 record both as
  **UNPROTECTED**. `onCreate` reads `MONITORED_APPS` (`:151`) and then a per-package
  `ACTIVE_UNTIL` (`:158`) inside the re-arm loop with no guard; `handleSessionExpired`, reached
  synchronously from `onStartCommand:672` on the main thread, reads `ACTIVE_UNTIL`, `LOCKED_UNTIL`
  and `GRAYZONE_ENABLED` (`:633â€“641`) with no guard. The realistic failure mode is a
  `ClassCastException` after a pref's stored type drifts across an upgrade or after an unvalidated
  backup restore writes a key with the wrong type â€” the E3 upgrade-path risk the inventory documents
  at length, and the concrete vector is `StateBackupManager.applyInto`, which writes arbitrary keys
  from a JSON snapshot with no schema gate and no allowlist (`findings.md` confirmed-leads table,
  owner T020). Consequences: in `onCreate` the service dies before the re-arm loop completes, so every
  in-flight session loses its expiry alarm and **never locks**; in `handleSessionExpired` the alarm
  fires, the service crashes, and the lockout the alarm existed to apply is not applied.
  What makes this a finding rather than a generic hardening note is that the file **states the
  opposite invariant three lines later**: "Register broadcast receivers. Must never throw out of
  onCreate" (`:164â€“166`). The two receiver registrations are the *only* guarded work in `onCreate`
  (`:187â€“201`, `:212â€“226`); the entire prologue that runs before them â€” `startForeground` (F-002),
  the `WindowManager` cast (`:145`, inventory Â§2.9 row 152), `ScheduleManager` construction (`:146`),
  `clearStaleSystemGrayscale` (`:148`) and these prefs reads â€” is unguarded. The invariant is asserted
  and then not implemented.
- **Trigger condition**:
  1. Produce a type-drifted pref: restore a `/sdcard/.grayzone/backup.dat` snapshot written by a build
     in which one of `MONITORED_APPS`, `ACTIVE_UNTIL + pkg`, `LOCKED_UNTIL + pkg` or
     `GRAYZONE_ENABLED` had a different type (`StateBackupManager.applyInto` restores by *recorded*
     type with no schema check), or write the wrong type directly with
     `adb shell run-as com.grayzone.app` on a debuggable build.
  2. Launch the app, or wait for a session-expiry alarm.
  3. `OverlayService` throws out of `onCreate` / `handleSessionExpired`; no lock is applied and no
     expiry alarm is re-armed.
- **Blast radius**:
  - *Callers*: `onCreate` and `onStartCommand` are framework callbacks. The start paths are
    `MainActivity.kt` (2 sites), `service/GrayzoneAccessibilityService.kt` (5 sites),
    `service/BootReceiver.kt` (4 sites) and `service/SessionExpiryReceiver.kt` (3 sites)
    (`evidence/reference-index.md` â†’ Â§`OverlayService.kt`). The prefs keys involved are widely shared:
    `MONITORED_APPS` is read by `GrayzoneApplication.kt`, `data/RuntimeSessionReset.kt`,
    `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/HomeScreen.kt`,
    `data/StreakEvaluator.kt`, `ui/LimitsScreen.kt`; `ACTIVE_UNTIL`/`LOCKED_UNTIL`/`REMAINING_MILLIS`
    by `Extensions.kt`, `data/RuntimeSessionReset.kt`, `ui/AppsScreen.kt`, `service/OverlayService.kt`,
    `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` (index â†’ Â§`PrefsKeys.kt`). So a single drifted
    key takes down several screens as well as this service.
  - *Process death*: the failure *causes* process death and is deterministic on restart while the bad
    value persists â€” a crash loop, since `START_STICKY` (`:677`), `BootReceiver` and the `:a11y`
    watchdog all retry.
  - *Config change*: none.
  - *Foreground/background*: `handleSessionExpired` runs from a background alarm delivery, so the
    crash there is invisible to the user; `onCreate` can fail on any start path.
  - *Cross-process*: `:a11y` never reads prefs (T010 verified), so prefs coherence is not at issue â€”
    but `:a11y` is one of the components that repeatedly re-triggers the failing start.
  - *Persistence*: read-only on these paths; the damage is that the re-arm write side
    (`SessionExpiryScheduler.arm`) never runs.
  - *Release-only*: no.
- **Fix** (minimal): wrap the `onCreate` prefs prologue and re-arm loop (`:150â€“162`) and the
  `handleSessionExpired` prefs block (`:631â€“641`) in `try`/`catch (e: Exception)` that logs with
  context via `GrayzoneLogger.e(LogComponent.OVERLAY, â€¦)` and continues rather than aborting â€” for
  `onCreate`, continue to receiver registration so foreground detection still works; for
  `handleSessionExpired`, treat unreadable state as "no session" and fall through to
  `cancelCountdownNotification()`. Both are additive; no read is changed.
- **Rewrite justification**: n/a â€” two try/catch blocks.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/SessionExpiryStateReadTest.kt`, using the existing
  `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt` to store `ACTIVE_UNTIL + pkg` as a
  `String` and asserting the extracted read helper degrades to "no session" instead of propagating
  `ClassCastException`. This requires lifting the four reads in `handleSessionExpired` into a small
  internal function that takes `SharedPreferences` â€” the same shape as the already-pure
  `SessionExpiryDecision.decide` it feeds, so it matches house style. Without that lift the only
  option is `instrumented`.
- **Existing test conflict**: none. `SessionExpiryDecisionTest.kt` tests `decide()` with primitives and
  is unaffected; `RuntimeSessionResetTest.kt` and `LockGuardLogicTest.kt` already use
  `FakeSharedPreferences` with correctly-typed values.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: both boundaries are
  unguarded (independently catalogued by T011); the stated `onCreate` invariant is not implemented.
  Runtime: that a drifted type actually throws where predicted, and that the restore path can produce
  one.
  - *Missing*: a device plus a crafted backup snapshot, or a debuggable build to write prefs directly.
  - *Procedure*: with a debuggable build, replace `active_until_<pkg>` with a `String` in
    `/data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml`, then
    `adb shell am start-foreground-service -a com.grayzone.app.ACTION_SESSION_EXPIRED --es package_name <pkg> com.grayzone.app/.service.OverlayService`
    and capture `adb logcat -b crash`. Repeat for `MONITORED_APPS` and a cold start.
  - *Severity effect*: **High** if either read throws (silent loss of the lock plus a crash loop).
    **Medium** if `SharedPreferences` proves to coerce rather than throw for the specific drifts
    reachable through `StateBackupManager` â€” the boundaries are still unprotected and the stated
    invariant is still false, both of which stand on their own.
- **Build scope**: `everyday-build`
- **Constitution**: III (every I/O boundary must have explicit failure handling); the false invariant
  comment additionally engages Principle IV's rationale (see F-008).
- **Affected surfaces**: same class of unguarded prologue at `OverlayService.kt:145` (unchecked
  `WindowManager` cast), `:146`, `:148`; `GrayzoneApplication.kt:176â€“215` (`validateConfiguration`,
  owner T021) is the canonical instance of a repair function that can be killed by the corruption it
  exists to fix; `service/BootReceiver.kt:34â€“54` is the same shape in this review unit (see F-011).
  The upstream cause â€” `StateBackupManager.applyInto` with no schema gate and no key allowlist â€” is
  T020's.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

---

## Medium

### F-007 â€” `sessionStartTime` is read-modify-written across IO and Main, so a whole session's usage can be dropped from the daily budget  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  field declaration (line 122), read-modify-write in `backgroundApp()` (read `:399`, write `:407`),
  competing write in `markAppUnlocked()` (line 1099)
- **Issue**: `@Volatile private var sessionStartTime: Long = 0L` gives visibility, not atomicity, and
  the comment above it (`:119â€“120`) names all three writer contexts and then concludes `@Volatile` is
  sufficient â€” the incorrect inference, as recorded in `evidence/concurrency-inventory.md` Â§R4.
  `backgroundApp` reads it at `:399` to compute `bgDuration`, then unconditionally writes `0L` at
  `:407`. Between those two lines sits `bgCommands.forEach { executeCommand(it, prefs) }` (`:406`),
  which is a **suspending** call chain: `executeCommand` â†’ `logUsageAndCheckBudget` â†’
  `withContext(Dispatchers.IO)` (`:563`) â†’ a Room `insertEvent` (`:574`) and a synchronous
  `commit()` (`:600`). Every one of those is a suspension point, so the window between read and write
  is wide â€” milliseconds to tens of milliseconds, not nanoseconds.
  Meanwhile `markAppUnlocked` writes `sessionStartTime = now` at `:1099` on `Dispatchers.Main`
  (reached from the countdown job `countdownJobUI`, launched `Dispatchers.Main` at `:1004`). If that
  write lands inside the window, `:407`'s `0L` **overwrites the freshly granted session's start
  time**. The next `backgroundApp` then computes `bgDuration = 0`, `RecordUsage` is not emitted at all
  (`policy/SessionPolicyEngine.kt:111` requires `sessionDurationMs > 0`), and the entire session is
  missing from both `DAILY_USED_MILLIS` and the Room `UsageEvent` table. The user silently gets more
  time than their daily budget allows, and the streak/stats derived from Room under-report.
  The window is reachable rather than theoretical, because backgrounding a monitored app does **not**
  cancel the countdown job â€” `DismissOverlay` is a no-op (F-005), so the friction countdown keeps
  running on `Main` and can complete into `markAppUnlocked` while the IO transition coroutine is
  mid-`backgroundApp`.
- **Trigger condition**:
  1. Set a daily budget on a monitored app so the accounting is observable.
  2. Open the app; the friction wait screen appears with its countdown.
  3. Switch away at the moment the countdown reaches zero â€” the IO drain coroutine runs
     `backgroundApp` while the Main countdown job runs `markAppUnlocked`.
  4. Use the app for a measurable period, background it, and compare `DAILY_USED_MILLIS` against the
     wall-clock time spent.
- **Blast radius**:
  - *Callers*: `backgroundApp` is private and called from `processForegroundAppChanged()` (`:348`) and
    `handleScreenOff()` (`:421`). `markAppUnlocked` is called only through
    `CountdownFinishContext.markAppUnlocked` (`:1025`), whose consumers per
    `evidence/reference-index.md` â†’ Â§`OverlayPresentation.kt` are
    `service/overlay/FrictionPresentation.kt` and `service/OverlayService.kt` (3 sites). The corrupted
    value flows into `SessionCommand.RecordUsage` (`policy/SessionModels.kt:59`) â†’
    `logUsageAndCheckBudget()` (`:512`) â†’ `DailyBudget.accumulateUsedMs`
    (`data/DailyBudget.kt:26`, called only from `service/OverlayService.kt`) and into
    `UsageDao.insertEvent()` (called from `service/OverlayService.kt`, 2 sites).
  - *Process death*: `sessionStartTime` is in-memory only, so process death loses the current
    session's start entirely â€” an independent, larger under-count that this finding's fix does not
    address and that should be recorded as a separate hazard.
  - *Config change*: none.
  - *Foreground/background*: the race exists exactly at the foregroundâ†”background boundary.
  - *Cross-process*: `main-only` (`evidence/cross-process-inventory.md`, `OverlayService.sessionStartTime`
    row). `:a11y` triggers the transition via broadcast but holds no copy of the field.
  - *Persistence*: yes â€” the dropped duration means `DAILY_USED_MILLIS + pkg` and
    `DAILY_RESET_DATE + pkg` are not updated (`:597â€“600`) and no `UsageEvent` row is written
    (`:574â€“584`). Both are durable under-counts; nothing later reconciles them.
  - *Release-only*: no.
- **Fix** (minimal): make the read-and-clear atomic instead of two accesses. Change the field to
  `private val sessionStartTime = java.util.concurrent.atomic.AtomicLong(0L)` and in `backgroundApp`
  take the value with `getAndSet(0L)` **before** `policyEngine.evaluate`, deleting the separate write
  at `:407`. `markAppUnlocked`'s `set(now)` then either happens before the `getAndSet` (and is
  correctly consumed) or after it (and survives). The other two writes (`:281`, `:379`) become
  `set(...)`. Update the comment at `:119â€“120`, which currently asserts the wrong conclusion.
- **Rewrite justification**: n/a â€” one field type change and moving one existing assignment; no
  restructuring of the transition pipeline. A `Mutex` around the whole transition would be the
  alternative and is *not* proposed: the codebase deliberately has zero `Mutex` uses
  (`evidence/concurrency-inventory.md`, "Absent primitives"), and an atomic is the smaller change.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/DailyBudgetAccountingTest.kt`, asserting that a
  `getAndSet`-style accessor returns the pending start time exactly once and `0L` thereafter under
  interleaved `set`/`getAndSet` calls from two threads, and that
  `DailyBudget.accumulateUsedMs` therefore receives the full duration. Requires lifting the
  read-and-clear into a tiny internal holder so it is testable off-device; the existing
  `DailyBudgetTest.kt` shows the pure-math half is already tested this way.
- **Existing test conflict**: none. `DailyBudgetTest.kt`, `SessionPolicyEngineBackgroundTest.kt` and
  `SessionStateLogicTest.kt` all pass durations in as parameters and never touch the field.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: the field is
  read at `:399` and written at `:407` with suspension points between; a fourth writer runs on
  `Dispatchers.Main`; `@Volatile` does not make that atomic. Runtime: how often the interleaving
  actually occurs.
  - *Missing*: a device and either a stress harness or added instrumentation.
  - *Procedure*: add a temporary `GrayzoneLogger.d` at `:399`, `:407` and `:1099` logging the value and
    thread name; then repeatedly switch away from a monitored app at the instant its friction
    countdown expires (a scripted `adb shell input` loop makes this repeatable) and look for a
    `:1099` write between a `:399` read and a `:407` write. Cross-check by comparing
    `DAILY_USED_MILLIS` against measured wall-clock usage over 20 sessions.
  - *Severity effect*: **Medium** as filed â€” incorrect behaviour in a narrow timing window, matching
    the rubric. **High** if the instrumentation shows the interleaving occurs routinely, because a
    daily budget that under-counts is protection silently weakened rather than a cosmetic
    mis-accounting. Never Low: the consequence is a lost durable write, not a display artefact.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI directly. The incorrect reasoning recorded in the comment at
  `:119â€“120` engages Principle IV's rationale about comments decaying against the code.
- **Affected surfaces**: `lastForegroundPackage` (`:121`) is correctly served by `@Volatile` â€” it is
  only ever assigned whole references (`:351`, `:422`) and read whole; do **not** "fix" it.
  `cachedHomeLauncherPkg`/`homeLauncherCacheTime` (`:123â€“124`) are unsynchronized by declaration but
  bounded to a stale launcher package for â‰¤30 s (Low, `evidence/concurrency-inventory.md`).
  The independent process-death under-count noted above shares this field.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-008 â€” Comment asserts `OverlayService` shares a process with the accessibility service; the manifest says the opposite  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.onCreate()` (lines 164â€“166)
- **Issue**: the comment reads "Register broadcast receivers. Must never throw out of onCreate:
  OverlayService shares a process with GrayzoneAccessibilityService, and an uncaught crash here makes
  Android disable (revoke) the accessibility service." Both halves of the causal claim are false in
  this build. `AndroidManifest.xml:82` declares `android:process=":a11y"` on
  `.service.GrayzoneAccessibilityService`, and it is the **only** `android:process` declaration in the
  tree (T009 and T010 both verified this); `OverlayService` is declared at `AndroidManifest.xml:65â€“72`
  with no `android:process`, so it runs in the default process. A crash in `OverlayService` therefore
  cannot revoke the accessibility service â€” the two are in different processes, which is exactly why
  F-002's crash loop can repeat (the surviving `:a11y` process keeps re-triggering it).
  Flagged independently by T008 and T012 and carried in `findings.md` as a confirmed lead
  (`evidence/prefs-inventory.md`, `evidence/prior-fix-watchlist.md`), and repeated as a standing note
  at `evidence/error-handling-inventory.md:15â€“19`.
  The defensive guards the comment justifies (`:187â€“201`, `:212â€“226`) are still correct and valuable â€”
  a throw out of `onCreate` kills the service and with it all friction, locks and countdowns. Only the
  stated *reason* is wrong. That matters beyond tidiness: this is the invariant a future change would
  reason from, and believing the two components share a process is what would make someone
  reintroduce SharedPreferences or Room access into `:a11y`, which
  `evidence/cross-process-inventory.md` rates **Critical if introduced** (prefs) and **High if
  introduced** (Room) because `MODE_PRIVATE` prefs are not multi-process coherent and
  `UsageDatabase` is built without `enableMultiInstanceInvalidation()`.
- **Trigger condition**: no runtime trigger. The defect materialises at review time:
  1. A contributor reads `:164â€“166` and concludes the two services share a process.
  2. They add a prefs read or a Room call to `GrayzoneAccessibilityService`, or narrow its deliberately
     broad catches (`:57`, `:106`) on the belief that the crash consequence lives elsewhere.
  3. The `:a11y` process then holds a prefs snapshot frozen at its first load, or a second Room
     instance over one WAL file.
- **Blast radius**:
  - *Callers*: comments have no callers; the affected reasoning surface is the `:a11y` boundary, whose
    full extent per `evidence/reference-index.md` Â§`:a11y`-reachable declarations is 11 entries â€”
    `GrayzoneAccessibilityService` (class), its `ACTION_APP_CHANGED` and `EXTRA_PACKAGE_NAME`,
    `GrayzoneLogger` (object) with `d()`, `w()`, `e()`, `LogComponent` (enum) with
    `LogComponent.ACCESSIBILITY`, the `OverlayService` class literal, and the hardcoded watchdog action
    string (F-009). The consumers of `OverlayService`'s cross-process channels are
    `service/GrayzoneAccessibilityService.kt` (5 sites) and `service/OverlayService.kt` itself
    (`:181â€“184`, `:674`).
  - *Process death*: n/a.
  - *Config change*: n/a.
  - *Foreground/background*: n/a.
  - *Cross-process*: this is entirely a cross-process finding â€” the comment misstates the process
    topology that T010 established.
  - *Persistence*: n/a today. The latent consequence is prefs incoherence and duplicate Room
    instances, both persistence defects.
  - *Release-only*: no.
- **Fix** (minimal): replace the false clause with the true one â€” keep "Must never throw out of
  onCreate" and restate the reason: a throw kills `OverlayService`, and with it friction, lockouts and
  the countdown, while `GrayzoneAccessibilityService` runs in `:a11y`
  (`AndroidManifest.xml:82`) and keeps re-triggering the failing start. Add the invariant explicitly:
  `:a11y` must never touch SharedPreferences or Room (the reason is already written down at
  `GrayzoneApplication.kt:52â€“54`).
- **Rewrite justification**: n/a â€” comment text only; no code change, so behaviour is preserved by
  construction (Principle V is satisfied trivially).
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/ProcessTopologyContractTest.kt`, parsing
  `app/src/main/AndroidManifest.xml` from the test resources and asserting that `.service.OverlayService`
  has no `android:process` while `.service.GrayzoneAccessibilityService` has `:a11y`. That converts the
  invariant from a comment into a check, so the next change to the topology fails a test instead of
  silently invalidating documentation. Manifest parsing in a jvm-unit test needs the file on the test
  classpath; if that is unacceptable, the alternative is `instrumented` with
  `PackageManager.getServiceInfo(...).processName`.
- **Existing test conflict**: none. `AccessibilityServiceIdsTest.kt` asserts only class-name parsing.
- **Verification**: `confirmed` (evidence tier `T1`). The manifest was read directly
  (`AndroidManifest.xml:65â€“72`, `:79â€“90`), and T009/T010 independently confirmed that `:82` is the only
  `android:process` declaration in the tree.
  - *Missing*: nothing.
  - *Procedure*: not required. Optional confirmation on device:
    `adb shell dumpsys activity processes | grep grayzone` should list both `com.grayzone.app` and
    `com.grayzone.app:a11y`.
  - *Severity effect*: **Medium** regardless of any device outcome â€” the comment is provably false
    against the manifest in the same working tree. It stays Medium rather than Low because it is the
    specific misstatement that would license a Critical-if-introduced change; it does not rise to High
    because nothing is currently broken by it.
- **Build scope**: `everyday-build`
- **Constitution**: IV. Its letter targets commented-out code, but its rationale is the governing one
  here â€” "Commented-out code is untested, unverified, and misleading; it masquerades as context while
  decaying against the live code around it" â€” and its principle statement reserves comments for
  "explaining non-obvious intent, trade-offs, or constraints". This comment states a constraint that
  does not exist.
- **Affected surfaces**: the same stale claim is reproduced in the audit's own evidence as a warning
  (`evidence/error-handling-inventory.md:15â€“19`) and must be dropped there once fixed. Related
  inaccurate documentation in this review unit: the "Doze-safe" claims at
  `SessionExpiryScheduler.kt:12`, `:38â€“39`, `OverlayService.kt:625â€“628` and `AndroidManifest.xml:93â€“95`
  (F-003), and the "leave/lock paths dismiss overlays" claim at `OverlayService.kt:489` (F-005).
  Docs-accuracy drift generally is T025b (`findings-docs.md`).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

### F-009 â€” The `:a11y` watchdog contract is a bare string literal, so renaming the constant silently disables the only recovery path for a killed `OverlayService`  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` â†’
  `GrayzoneAccessibilityService.onAccessibilityEvent()` (line 96), against
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’ companion (line 71) and
  `onStartCommand()` (line 673)
- **Issue**: the watchdog Intent's action is written as the literal
  `"com.grayzone.app.ACTION_APP_CHANGED_WATCHDOG"` instead of referencing
  `OverlayService.ACTION_APP_CHANGED_WATCHDOG`, which declares the identical string at
  `OverlayService.kt:71` and is the value `onStartCommand` compares against at `:673`. A working-tree
  grep confirms exactly three occurrences of the token and that the literal at
  `GrayzoneAccessibilityService.kt:96` is the only one not going through the constant;
  `evidence/reference-index.md` records `ACTION_APP_CHANGED_WATCHDOG` as referenced from "`(self)`
  (3 sites)" with the note "`:a11y` sends the same literal **without** referencing this constant", and
  `evidence/cross-process-inventory.md` classifies it `main-only` for the same reason.
  The two are therefore coupled **by convention only**, across a process boundary, on the single path
  that revives `OverlayService` after a swipe-away or a kill (the comment at `:91` says so: "Watchdog:
  Ensure OverlayService is running (restarts it if swipe-away/crash killed it)"). Rename or re-namespace
  the constant and the compiler stays silent, the `startForegroundService` still succeeds, the service
  still starts â€” but `onStartCommand` matches no branch, `handleForegroundAppChanged` is never called
  from the watchdog path, and the recovery channel degrades to "the service is alive but ignores the
  package it was told about". Nothing logs it. Note the same function already imports the sibling
  constant properly two lines earlier â€” `EXTRA_PACKAGE_NAME` at `:97` and `:87` â€” and holds a
  reference to the class itself at `:95`, so there is no visibility obstacle.
  There is a plausible unstated reason for the literal: `evidence/cross-process-inventory.md` records
  as an open question whether touching a static member of `OverlayService` would run its companion
  `<clinit>` in `:a11y` and allocate the `Gson` at `OverlayService.kt:98` there. If that is the intent,
  it is undocumented, which is what makes this a defect rather than a deliberate trade-off.
- **Trigger condition**: no runtime trigger in the current tree; the action strings match today.
  1. A contributor renames `OverlayService.ACTION_APP_CHANGED_WATCHDOG` or changes its value (e.g.
     during an `applicationId` change or a constant tidy-up).
  2. The build succeeds and every test passes â€” no test references either the constant or the literal.
  3. The user swipes Grayzone from Recents, or the OS kills `OverlayService`.
  4. The a11y watchdog starts the service but its package payload is ignored; friction and lock
     screens do not appear for the app the user just opened, with no log line anywhere.
- **Blast radius**:
  - *Callers*: sender `service/GrayzoneAccessibilityService.kt` â†’ `onAccessibilityEvent()` (`:93â€“99`),
    the only writer of this action. Receiver `service/OverlayService.kt` â†’ `onStartCommand()` (`:673`)
    â†’ `handleForegroundAppChanged()` (`:675`) â†’ `drainForegroundTransitions()` â†’
    `processForegroundAppChanged()`, i.e. the whole enforcement pipeline. Per
    `evidence/reference-index.md`, `GrayzoneAccessibilityService`'s other cross-process constants are
    referenced correctly: `ACTION_APP_CHANGED` from `service/OverlayService.kt` (receiver registration,
    `:191`) and `EXTRA_PACKAGE_NAME` from `service/OverlayService.kt` (2 sites),
    `service/SessionExpiryReceiver.kt` (3 sites) and `service/SessionExpiryScheduler.kt`. The watchdog
    action is the single exception.
  - *Process death*: this *is* the post-process-death recovery path, so the blast radius is exactly the
    scenario the coupling protects.
  - *Config change*: none.
  - *Foreground/background*: the watchdog fires while the app is in the background â€” the case with no
    other recovery channel except F-004's alarm.
  - *Cross-process*: yes, and this is the core of the finding. `:a11y` has its own class loader
    (`evidence/reference-index.md`, Â§Cross-process consequence), so the constant would be inlined into
    each process's dex independently anyway; using the literal removes even the compile-time link that
    would make a rename a build error.
  - *Persistence*: none.
  - *Release-only*: no. `evidence/r8.md` Â§F2 confirms manifest components survive R8, and a `const val`
    String is inlined before R8 runs, so shrinking cannot change the outcome either way.
  - *Cross-reference*: `evidence/reference-index.md` Â§Process topology states this coupling explicitly:
    "the watchdog action is a **hardcoded string literal**, not a reference to
    `OverlayService.ACTION_APP_CHANGED_WATCHDOG` â€” the two constants are coupled by convention only."
- **Fix** (minimal): add a jvm-unit test asserting the literal equals the constant, and a one-line
  comment at `GrayzoneAccessibilityService.kt:96` stating why the literal is used (avoiding a static
  touch of `OverlayService` in `:a11y`) with a pointer to that test. This keeps the current dex
  behaviour byte-for-byte while making a rename a build/test failure. If the `<clinit>` concern is
  disproved by the procedure below, the smaller fix is to replace the literal with
  `OverlayService.ACTION_APP_CHANGED_WATCHDOG`.
- **Rewrite justification**: n/a â€” a test plus a comment, or a one-token substitution.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/WatchdogActionContractTest.kt`, asserting
  `assertEquals("com.grayzone.app.ACTION_APP_CHANGED_WATCHDOG", OverlayService.ACTION_APP_CHANGED_WATCHDOG)`.
  Because it is a `const val`, the reference is inlined at compile time and the `OverlayService` class
  is never loaded, so this runs in a plain JVM test with no Robolectric â€” the same technique
  `OverlayModeTest.kt` already uses for `PrefsKeys.OverlayMode` constants. It fails the moment the
  constant's value is changed without updating the sender.
- **Existing test conflict**: none. `AccessibilityServiceIdsTest.kt` touches only class-name parsing;
  no existing test references either the constant or the literal.
- **Verification**: `confirmed` (evidence tier `T1`) for the coupling itself â€” grep shows the literal
  at `:96`, the declaration at `:71` and the single consumer at `:673`, with no reference linking them.
  `needs-verification` only for the `<clinit>` rationale.
  - *Missing*: a device, to settle whether referencing the constant would load `OverlayService`'s
    companion in `:a11y`.
  - *Procedure*: compare `adb shell dumpsys meminfo com.grayzone.app` with
    `adb shell dumpsys meminfo com.grayzone.app:a11y` before and after switching the literal to the
    constant, or run with `-verbose:class` and check whether `OverlayService`'s static initialiser
    executes in `:a11y`. `evidence/cross-process-inventory.md` records the same open question and notes
    it affects a few kilobytes and never behaviour.
  - *Severity effect*: **Medium** either way â€” the silent-rename hazard on the only recovery channel is
    independent of the `<clinit>` answer. If the device shows no companion initialisation in `:a11y`,
    the fix simplifies to the direct constant reference (still Medium). It is not Low because the
    failure mode is silent loss of protection recovery, not a style preference; it is not High because
    nothing is broken in the current tree.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI squarely. The Code Quality Standard "Strategy abstractions MUST
  expose a single decision point; call sites depend on the abstraction, not on concrete variant
  identity" is the nearest analogue: the receiving service owns the action contract and the sender
  should depend on it, not restate it.
- **Affected surfaces**: `evidence/reference-index.md` records related duplicate-literal couplings in
  this review unit â€” `OverlayService.ACTION_SESSION_EXPIRED` (`:70`) duplicates
  `SessionExpiryReceiver.ACTION_SESSION_EXPIRED` (`:37`), and
  `GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME` (`:33`) duplicates
  `SessionExpiryReceiver.EXTRA_PACKAGE_NAME` (`:38`). Those two pairs are **not** the same defect: both
  sides go through *some* named constant and `SessionExpiryReceiver.kt:23` correctly references
  `OverlayService.ACTION_SESSION_EXPIRED` when building the outgoing Intent, so a rename produces a
  compile error on at least one side. They are recorded here as adjacent, lower-risk duplication rather
  than as findings. `OverlayService.ACTION_STOP` colliding by name with `AdBlockVpnService.ACTION_STOP`
  is likewise naming-only.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt, app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-010 â€” `BootReceiver` restores two of the three protection legs and has no signal for the third  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` â†’
  `BootReceiver.onReceive()` (lines 28â€“78)
- **Issue**: after a reboot the receiver restarts `OverlayService` unconditionally (`:39â€“44`) and
  `AdBlockVpnService` conditionally on persisted user intent (`:48â€“77`). The third leg â€”
  `GrayzoneAccessibilityService`, which is the *only* source of foreground-app detection and therefore
  the trigger for every friction screen and lock â€” is not addressed, and correctly cannot be: an
  accessibility service is bound by the system from
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, not started by the app. That part is not the
  defect. The defect is that **nothing verifies it**, so a reboot after which the OS does not re-bind
  the service (an OEM cleanup, an OS update that resets accessibility grants, or an earlier revocation)
  leaves an app whose overlay service is running, whose notification says "Grayzone is active"
  (`:1191`), and which detects nothing at all. Three facts combine to make this unobservable:
  - the app's only accessibility-loss signal is `GrayzoneAccessibilityService.onUnbind` (`:124â€“130`),
    which cannot fire if the service was never bound after boot;
  - `ProtectionStatus.accessibilityActive` has **no production writer** â€”
    `ProtectionHealthRepository.updateAccessibilityStatus` is **TEST-ONLY**
    (`evidence/reference-index.md`; `evidence/cross-process-inventory.md` Â§Latent traps item 1), so
    `isFullyProtected` is unsatisfiable in production;
  - `BootReceiver` writes nothing to the health model, while `OverlayService.onCreate:142` writes
    `updateOverlayStatus(true)` â€” so the health model after a boot with accessibility missing reports
    the overlay leg healthy and says nothing about the leg that matters.
  The gap closes only when the user next opens the app: `MainActivity.kt:190` calls
  `isAccessibilityServiceEnabled` (`AppUtils.kt:108â€“126`) and `AccessibilityPromptPolicy` decides
  whether to prompt. Until then, protection is silently absent. `BootReceiver` has **no test
  coverage** (`evidence/reference-index.md` â†’ Â§`BootReceiver.kt`).
- **Trigger condition**:
  1. Set up Grayzone fully (accessibility + overlay granted), confirm friction screens work.
  2. Disable Grayzone's accessibility service in Settings (simulating a reboot-time reset), then
     reboot â€” or reboot an OEM device that clears accessibility grants on update.
  3. `BootReceiver` restarts `OverlayService`; the ongoing notification claims Grayzone is active.
  4. Open a monitored app: no friction screen, no lock, no log line, nothing in the UI â€” until the
     user opens Grayzone itself.
- **Blast radius**:
  - *Callers*: `BootReceiver` is registered in `AndroidManifest.xml:139â€“149` for
    `BOOT_COMPLETED`, `QUICKBOOT_POWERON` and `com.htc.intent.action.QUICKBOOT_POWERON`, and per
    `evidence/reference-index.md` is additionally referenced from `MainActivity.kt` and
    `service/vpn/AdBlockVpnService.kt`. `onReceive` reaches `OverlayService` (4 sites),
    `AdBlockVpnService` (6 sites), `VpnHoldExpiryScheduler.rearmFromPrefs` and `VpnHoldLockStore`
    (3 sites), `VpnHoldLock` (3 sites), and `PrefsKeys`.
  - *Process death*: this path exists because of a full device restart, so all in-memory state
    (including `ProtectionHealthRepository`) starts at its defaults â€” which is why the missing
    accessibility signal cannot be inferred from anything left over.
  - *Config change*: none.
  - *Foreground/background*: the receiver runs with no UI, so any user-facing signal it wanted to raise
    would have to be a notification or persisted state.
  - *Cross-process*: relevant. Any fix must not write `updateAccessibilityStatus` from `:a11y` â€”
    `evidence/cross-process-inventory.md` Â§Latent traps item 1 shows that writes the `:a11y` copy of the
    `MutableStateFlow` and the UI would read `false` forever. The correct source in the default process
    is `AppUtils.isAccessibilityServiceEnabled`, which already exists and is what `MainActivity` uses.
  - *Persistence*: the receiver writes `VPN_ENABLED = false` (`:53`) and clears the hold
    (`VpnHoldLockStore.clear`, `:54`) on the hold-expired path; both are durable and correct.
  - *Release-only*: no.
- **Fix** (minimal): after restarting `OverlayService`, publish the accessibility state from the
  default process â€” call `AppUtils.isAccessibilityServiceEnabled(context)` and pass the result to
  `ProtectionHealthRepository.updateAccessibilityStatus(...)`, and log it via
  `GrayzoneLogger.i(LogComponent.BOOT, â€¦)`. That wires the existing TEST-ONLY writer from the correct
  process using the existing checker, with no new API and no `:a11y` involvement. Whether the health
  state should additionally drive a user-visible notification is a product decision and deliberately
  out of scope here.
- **Rewrite justification**: n/a â€” two existing functions joined by one call.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt`, asserting over
  `FakeSharedPreferences` that the boot decision publishes `accessibilityActive` matching the injected
  accessibility state, and that it is published even when the VPN branch is skipped. This requires
  lifting `onReceive`'s decision logic into a pure function that takes `SharedPreferences`, a clock and
  an `isAccessibilityEnabled: Boolean` â€” the pattern the codebase already uses for
  `SessionExpiryDecision`, `DailyBudget`, `PerAppLimits` and `RuntimeSessionReset`, all of which are
  jvm-tested this way. Without the lift the only option is `instrumented`.
- **Existing test conflict**: none â€” `BootReceiver` has no tests. `ProtectionStatusTest.kt:14,46` are
  the current sole callers of `updateAccessibilityStatus` and would keep passing; the fix gives that
  writer its first production caller, so `ProtectionStatus.isFullyProtected` becomes satisfiable â€”
  which is a behaviour change T020/T029 must be told about, since `evidence/cross-process-inventory.md`
  rates a naive wiring **High**.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: `BootReceiver`
  contains no accessibility handling; `updateAccessibilityStatus` has no production caller; `onUnbind`
  is the only loss signal. Runtime: whether real devices actually lose the accessibility binding across
  a reboot, and how quickly the OS re-binds it when they do not.
  - *Missing*: at least two devices from different OEMs, one with an OS update available.
  - *Procedure*: grant everything, `adb reboot`, then immediately and repeatedly run
    `adb shell settings get secure enabled_accessibility_services` and
    `adb shell dumpsys activity services com.grayzone.app` to time the re-bind relative to
    `BootReceiver`'s `Boot detected` log. Then disable accessibility, reboot, and confirm that opening a
    monitored app produces no friction screen while the ongoing notification still says "Grayzone is
    active".
  - *Severity effect*: **Medium** as filed â€” incorrect behaviour in a specific condition, self-healing
    on the next app open. **High** if the devices show the accessibility binding is routinely absent or
    materially delayed after boot, because the app would then be claiming active protection during a
    real gap on every reboot. **Low** if a device shows the OS re-binds before `BootReceiver` even runs
    *and* some other component already surfaces the loss â€” neither of which any static evidence
    supports.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI directly. Adjacent: Principle I, since the fix converts a TEST-ONLY
  declaration (`ProtectionHealthRepository.updateAccessibilityStatus`) into live code rather than
  deleting it.
- **Affected surfaces**: `data/ProtectionHealthRepository.kt:34` and `data/ProtectionStatus.kt:6,11,14`
  (owners T020, T029 â€” the protection-health model is 3/4 wired and this is the missing leg);
  `GrayzoneAccessibilityService.kt:124â€“130` (the only existing loss signal);
  `MainActivity.kt:183â€“221` (`evaluateScreen`, the current recovery path, owner T021).
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-011 â€” `VpnService.prepare` is unguarded inside `BootReceiver.onReceive`, so one throw aborts boot restoration entirely  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` â†’
  `BootReceiver.onReceive()` (line 61; the surrounding unguarded block is 48â€“64)
- **Issue**: `android.net.VpnService.prepare(context)` at `:61` sits **outside** the `try` that begins
  at `:65`, and so do the prefs read at `:48`, `VpnHoldLockStore.read` at `:49`,
  `VpnHoldLock.isHoldActive` at `:52` and the `prefs.edit().apply()` at `:53`
  (`evidence/error-handling-inventory.md` Â§2.13 row 193 records `prepare` as **UNPROTECTED**, and Â§2.2
  row 54 records the boot prefs reads/writes the same way). `onReceive` runs on the receiver's main
  thread with no `goAsync()`, so a throw there propagates out of the receiver and takes the process
  down mid-boot. The file's own KDoc (`:14â€“20`) promises the opposite: "If restart fails, the persisted
  user intent is left unchanged so a later app launch or recovery path can retry instead of silently
  forgetting that protection should be active." That promise is kept for the two failures it wraps
  (`:42`, `:74`) and broken for everything else on the path.
  Consequences, in order: the VPN is not restored; `VpnHoldExpiryScheduler.rearmFromPrefs` at `:72` is
  never reached (see F-012); and the crash is attributed to boot, where nobody looks. Note the
  `OverlayService` restart at `:39â€“44` has already completed by then, so the overlay leg survives â€” the
  loss is scoped to the VPN leg plus the hold alarm.
- **Trigger condition**:
  1. Enable the AdBlock VPN so `VPN_ENABLED` is `true`.
  2. Reboot on a device or profile where `VpnService.prepare` throws rather than returning an Intent â€”
     e.g. a `SecurityException` under a device-owner/always-on-VPN policy, or a `NullPointerException`
     from a missing `IConnectivityManager` binder very early in boot.
  3. `onReceive` throws; the VPN is never restored and no hold alarm is re-armed.
- **Blast radius**:
  - *Callers*: framework-invoked from `AndroidManifest.xml:139â€“149`. Downstream it reaches
    `AdBlockVpnService` (6 sites), `VpnHoldExpiryScheduler.rearmFromPrefs`, `VpnHoldLockStore.read` /
    `clear` (3 sites) and `VpnHoldLock.isHoldActive` (3 sites), per `evidence/reference-index.md` â†’
    Â§`BootReceiver.kt`. `VpnService.prepare` is also called unguarded at `MainActivity.kt:139` and
    `ui/VpnScreen.kt:98` (owners T021, T026) â€” same root cause, different review units.
  - *Process death*: the failure *is* a process death, at the worst moment: the receiver is the only
    component that reconstructs protection after a reboot.
  - *Config change*: none.
  - *Foreground/background*: no UI exists at boot, so nothing can surface the failure to the user.
  - *Cross-process*: none â€” `BootReceiver` runs in the default process
    (`evidence/cross-process-inventory.md` process-topology table).
  - *Persistence*: yes. If the throw lands between `:53` (`VPN_ENABLED = false`) and `:54`
    (`VpnHoldLockStore.clear`), the persisted state is left half-updated: intent cleared but the hold
    timestamps still present. `VpnHoldLock.State.isTracked` then reports a tracked hold with no intent
    behind it.
  - *Release-only*: no.
- **Fix** (minimal): widen the existing `try` so it starts before the prefs read at `:48` and
  encloses `prepare` â€” or add a second `try`/`catch (e: Exception)` around `:48â€“64` that logs via
  `GrayzoneLogger.e(LogComponent.BOOT, "VPN boot restoration failed; intent preserved", e)` and
  returns. Either keeps the KDoc's promise and preserves the already-completed `OverlayService`
  restart. Additionally, swap the order of `:53` and `:54` so the hold is cleared before the intent, or
  perform both in one `edit()`, so no interleaving can leave a tracked hold with no intent.
- **Rewrite justification**: n/a â€” widening one `try` and reordering two adjacent writes.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` (shared with F-010),
  asserting that when the injected consent probe throws, the decision function returns
  "skip VPN, preserve intent" and leaves `VPN_ENABLED` untouched, rather than propagating. Requires the
  same lift of `onReceive`'s logic into a pure function described in F-010; a single lift serves
  F-010, F-011 and F-012.
- **Existing test conflict**: none â€” `BootReceiver` has no tests.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: `prepare` and the
  four prefs operations are outside every `try`; `onReceive` has no `goAsync()`; the KDoc promises
  retry-preserving behaviour the code does not implement on this path. Runtime: whether `prepare` ever
  actually throws at boot on a real device.
  - *Missing*: a device with an always-on-VPN or device-owner policy, and a slow-boot device to test
    the early-boot binder window.
  - *Procedure*: set another app as always-on VPN with "Block connections without VPN", enable
    Grayzone's VPN, `adb reboot`, and watch `adb logcat -b crash,main | grep -i -e grayzone -e boot`
    for an exception originating in `BootReceiver.onReceive`. Repeat under a device-owner profile.
    Confirm afterwards whether `VPN_ENABLED` and the hold keys are consistent via
    `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml`.
  - *Severity effect*: **Medium** as filed. **High** if a reachable configuration makes `prepare`
    throw, since a boot-time crash there permanently loses the VPN leg and the hold alarm with no
    signal. **Low** only if `prepare` is proven non-throwing on every supported configuration â€” which
    cannot be established statically, and the half-updated-prefs ordering issue would still stand.
- **Build scope**: `everyday-build`
- **Constitution**: III (an IPC / VPN-consent boundary and four SharedPreferences operations with no
  failure handling, on the boot recovery path).
- **Affected surfaces**: `MainActivity.kt:139` and `ui/VpnScreen.kt:98` are the other two unguarded
  `VpnService.prepare` call sites (owners T021, T026). `service/vpn/AdBlockVpnService.kt` and
  `VpnHoldLockStore.kt` own the VPN-side half (T019).
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-012 â€” The 24-hour VPN hold auto-off alarm is re-armed on only one of four boot outcomes, so the hold can outlive its own deadline  [Medium]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` â†’
  `BootReceiver.onReceive()` (lines 48â€“77; the re-arm is at 71â€“73)
- **Issue**: `AlarmManager` alarms do not survive a reboot, so the hold-expiry alarm that auto-disables
  the VPN after 24 hours must be re-armed at boot. `BootReceiver` calls
  `VpnHoldExpiryScheduler.rearmFromPrefs(context)` only at `:72` â€” inside the `try`, **after** a
  successful `startForegroundService`, and only when `VpnHoldLock.isHoldActive(...)` is true. Four boot
  outcomes exist and three of them skip it:
  1. hold tracked but already expired across the reboot â†’ `:52â€“57` clears the intent and returns; no
     re-arm needed. **Correct.**
  2. VPN consent missing â†’ `:61â€“64` returns early; hold still active, **no re-arm**.
  3. `startForegroundService` throws â†’ caught at `:74`; hold still active, **no re-arm**.
  4. success â†’ re-armed. **Correct.**
  In cases 2 and 3 `VPN_ENABLED` stays `true` (deliberately, per the KDoc) and the hold timestamps stay
  in prefs, but no alarm exists to release the hold. `VpnHoldLock.remainingMs` is computed from stored
  timestamps (`evidence/cross-process-inventory.md` credits it for preferring `elapsedRealtime` with a
  wall-clock reboot fallback), so the UI countdown still reads correctly and `canUserDisable` still
  flips to `true` at the deadline â€” the user is not trapped. What is lost is the *automatic* release:
  nothing disables the VPN at the deadline until something else calls `rearmFromPrefs`, which per
  `evidence/reference-index.md` is `MainActivity.kt` (2 sites),
  `service/vpn/AdBlockVpnService.kt` and `service/BootReceiver.kt`. So the self-heal requires the user
  to open the app or the VPN service to start â€” the two things cases 2 and 3 establish did not happen.
- **Trigger condition**:
  1. Enable the AdBlock VPN, which starts a tracked 24-hour hold.
  2. Reinstall the app or otherwise revoke VPN consent so `VpnService.prepare` returns non-null
     (case 2), or arrange for `startForegroundService` to fail at boot (case 3).
  3. Reboot within the 24-hour window.
  4. Wait past the deadline without opening Grayzone: `VPN_ENABLED` is still `true`, the hold record is
     still present, and no alarm will fire to clear either.
- **Blast radius**:
  - *Callers*: `VpnHoldExpiryScheduler.rearmFromPrefs()` is called from `MainActivity.kt` (2 sites),
    `service/vpn/AdBlockVpnService.kt` and `service/BootReceiver.kt`; `VpnHoldExpiryScheduler` itself is
    referenced from `MainActivity.kt` (3 sites), `service/vpn/AdBlockVpnService.kt` (4 sites) and
    `service/BootReceiver.kt` (2 sites), and has **no test coverage**
    (`evidence/reference-index.md` â†’ Â§`VpnHoldExpiryScheduler.kt`). The state it reads,
    `VpnHoldLockStore`, is touched by `MainActivity.kt` (3 sites), `ui/VpnScreen.kt` (7 sites),
    `service/vpn/AdBlockVpnService.kt` (9 sites), `VpnHoldExpiryScheduler` and `BootReceiver` (3 sites).
  - *Process death*: reboot is the premise. The alarm is the only piece of this mechanism that does not
    survive it, which is exactly why the re-arm placement matters.
  - *Config change*: none.
  - *Foreground/background*: the self-heal path requires the app to be *opened*, so the defect persists
    for as long as the user stays away.
  - *Cross-process*: none â€” all participants are default-process
    (`evidence/cross-process-inventory.md`).
  - *Persistence*: yes. `VPN_ENABLED` (`PrefsKeys.kt`, read by `MainActivity.kt`, `BootReceiver.kt`,
    `AdBlockVpnService.kt`) and `VPN_HOLD_STARTED_WALL` / `VPN_HOLD_STARTED_ELAPSED` (read/written only
    by `VpnHoldLockStore.kt`) are all left asserting an active hold with no mechanism to end it.
  - *Release-only*: no.
- **Fix** (minimal): move the re-arm so it runs for every outcome in which a hold is still active â€”
  call `VpnHoldExpiryScheduler.rearmFromPrefs(context)` once, immediately after the
  `isHoldActive(holdState, nowWall, nowElapsed)` check at `:52`, before the consent probe and before
  the service start, and delete the conditional copy at `:71â€“73`. `rearmFromPrefs` already re-reads
  prefs and no-ops when there is nothing to arm (it is the same function `MainActivity` calls
  unconditionally), so hoisting it is safe and removes a branch rather than adding one.
- **Rewrite justification**: n/a â€” moving one existing call earlier and deleting its conditional
  duplicate.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` (shared with F-010 and
  F-011), asserting over `FakeSharedPreferences` that the boot decision requests a hold re-arm in all
  three "hold still active" outcomes â€” consent missing, service-start failure, and success â€” and does
  **not** request one in the hold-expired outcome. Depends on the same lift into a pure decision
  function; `VpnHoldLockTest.kt` already tests the hold math this way.
- **Existing test conflict**: none. `VpnHoldLockTest.kt` covers `VpnHoldLock.remainingMs`,
  `isHoldActive`, `canUserDisable` and `expiresAtWallMs` with primitives and is unaffected.
- **Verification**: `needs-verification` (evidence tier `T2`). `T1` static facts: the re-arm is
  inside the success branch and guarded by `isHoldActive`; both early returns bypass it; alarms do not
  survive reboot. Runtime: whether any other component happens to re-arm before the deadline in
  practice.
  - *Missing*: a device and a 24-hour observation window (or the ability to shorten `VpnHoldLock.HOLD_MS`
    in a test build).
  - *Procedure*: enable the VPN, then revoke consent (reinstall, or
    `adb shell cmd netpolicy` / Settings â†’ VPN â†’ forget), reboot, and check
    `adb shell dumpsys alarm | grep -i grayzone` for a pending `ACTION_HOLD_EXPIRED` alarm â€” the fix's
    assertion is that one exists. Without opening the app, wait past the deadline and confirm whether
    `VPN_ENABLED` is ever cleared. Repeat for the `startForegroundService`-failure branch.
  - *Severity effect*: **Medium** as filed â€” narrow conditions, user-recoverable, no data loss.
    **High** if the device shows the stale `VPN_ENABLED` flag also causes a repeated failing
    foreground-service start on every subsequent boot or app launch. **Low** if `rearmFromPrefs` proves
    to be reached by another component before any realistic deadline â€” the placement is still wrong,
    but the consequence would be theoretical.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI directly.
- **Affected surfaces**: `service/vpn/VpnHoldExpiryScheduler.kt` (no test coverage) and
  `service/vpn/AdBlockVpnService.kt:152`, `:175`, `:304` are the other arm/cancel sites â€” owner T019.
  `MainActivity.kt`'s two `rearmFromPrefs` calls are the current self-heal path â€” owner T021.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Low

### F-013 â€” Session-expiry alarms are keyed on `packageName.hashCode()`, so two monitored packages whose hashes collide share one alarm  [Low]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/SessionExpiryScheduler.kt` â†’
  `SessionExpiryScheduler.pendingIntent()` (lines 23â€“34; the request code is `:30`)
- **Issue**: the per-package `PendingIntent` uses `packageName.hashCode()` as its request code, and the
  package name travels only as an Intent **extra** (`:26`). `PendingIntent` equality ignores extras â€”
  it matches on request code plus `Intent.filterEquals` (action, data, type, component, categories),
  all of which are identical across packages here. So two monitored packages whose `String.hashCode()`
  values collide resolve to the *same* `PendingIntent`. With `FLAG_UPDATE_CURRENT` (`:32`), arming the
  second package rewrites the shared object's extras and re-targets the single alarm, so the first
  package's session **never locks**; symmetrically, `cancel()` (`:73â€“82`) for either package cancels the
  other's alarm. The failure is silent â€” no log, and the notification countdown for the losing package
  keeps running until it drifts negative (F-003).
  Severity is Low because the probability is tiny â€” a `String.hashCode` collision among the handful of
  package names a user monitors â€” but it is deterministic per device once it occurs, and it is
  avoidable at no cost.
- **Trigger condition**:
  1. Monitor two apps whose package names have equal `String.hashCode()` values (constructible for a
     test; vanishingly rare in the wild).
  2. Start a session in app A, then start one in app B; arming B replaces A's alarm.
  3. Stay in app A past its `activeUntil`: no lock ever arrives.
- **Blast radius**:
  - *Callers*: `pendingIntent()` is private, used by `arm()` (`:44`) and `cancel()` (`:75`).
    `SessionExpiryScheduler.arm()` is called from `service/OverlayService.kt` at `onCreate()` `:160`,
    `executeCommand()` `:529` and `markAppUnlocked()` `:1098`; `cancel()` from
    `performMidnightReset()` `:267` and `executeCommand()` `:523`. The object is referenced from
    `service/OverlayService.kt` (5 sites) and `service/SessionExpiryReceiver.kt` per
    `evidence/reference-index.md`, and has no test coverage.
  - *Process death*: none â€” alarms and the request-code scheme are both durable, so a collision
    survives restarts and reboots identically.
  - *Config change*: none.
  - *Foreground/background*: the losing package's lock is missed exactly when the user is inside it.
  - *Cross-process*: none. `SessionExpiryScheduler` is `main-only`
    (`evidence/cross-process-inventory.md`), and the alarm is delivered to a manifest-registered
    receiver in the same process.
  - *Persistence*: none directly; the consequence is that `LOCKED_UNTIL + pkg` is never enforced even
    though it is stored correctly.
  - *Release-only*: no.
- **Fix** (minimal): make the package part of the `PendingIntent`'s identity rather than of its extras
  â€” set `intent.data = Uri.fromParts("package", packageName, null)` (or
  `intent.action = "$ACTION_SESSION_EXPIRED:$packageName"`) so `filterEquals` distinguishes packages,
  and keep the existing extra for the receiver to read. `Intent.data` is part of `filterEquals`, so
  collisions become impossible regardless of the request code. Three lines, no behaviour change for
  non-colliding packages.
- **Rewrite justification**: n/a â€” one added `data` assignment.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/SessionExpiryRequestKeyTest.kt`, asserting that the key
  derivation is injective for two package names with equal `hashCode()` (a colliding pair can be
  generated from `String.hashCode`'s definition). Requires exposing the key/`Uri` derivation as an
  internal pure function; `AccessibilityServiceIdsTest.kt` shows the same "pure string derivation
  tested off-device" shape already in use.
- **Existing test conflict**: none â€” `SessionExpiryScheduler` has no tests, and
  `SessionExpiryDecisionTest.kt` tests only the pure decision.
- **Verification**: `confirmed` (evidence tier `T1`) â€” derived from the documented `PendingIntent`
  matching rules (request code + `filterEquals`, extras excluded) applied to the code as written. No
  device is needed to establish the mechanism.
  - *Missing*: nothing for the mechanism. For an end-to-end demonstration: two installed apps with
    colliding package-name hashes.
  - *Procedure*: on a debuggable build, monitor two packages with a constructed hash collision, arm
    both, and inspect `adb shell dumpsys alarm | grep -A5 grayzone` to confirm one alarm rather than
    two.
  - *Severity effect*: **Low** â€” the consequence is severe (a session that never locks) but the trigger
    probability is negligible. Raise to **Medium** only if a realistic colliding pair is found among
    commonly monitored packages; the finding would then describe silent loss of protection for a real
    user configuration.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI.
- **Affected surfaces**: `service/vpn/VpnHoldExpiryScheduler.kt:24â€“29` builds a single global
  `PendingIntent` with no per-entity key, so it has no equivalent exposure â€” noted so T019 does not
  "fix" it by symmetry.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-014 â€” Comment-only `catch (_: SecurityException)` when clearing the stale system grayscale setting  [Low]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.clearStaleSystemGrayscale()` (lines 774â€“784; the catch is 781â€“783)
- **Issue**: `Settings.Secure.putString(contentResolver, "accessibility_display_daltonizer_enabled", "0")`
  is wrapped in `catch (_: SecurityException) { // No grant â€” nothing to clear. }` â€” a comment-only
  body, which Principle III prohibits in its own words ("an empty or comment-only catch is
  prohibited"). Recorded as `SILENT` / **VIOLATION (Low)** at row 41 and Â§2.7 row 134 of
  `evidence/error-handling-inventory.md`, and ranked there as **L2**. The mitigating facts are real:
  the operation is cosmetic (clearing a monochrome display setting left behind by older builds), the
  KDoc at `:773` documents the intent and the expected no-op ("Best-effort â€¦ No-op without grant"), and
  the caught type is narrow. Consequence of the swallow: on a device where the write *should* have
  succeeded but did not, the user's display stays monochrome from a previous Grayzone version with no
  record of why. It is called unguarded from `onCreate:148`, i.e. on the service's critical startup
  path, so the narrow catch is also what keeps a `SecurityException` there from becoming F-002's crash.
- **Trigger condition**:
  1. Run a build old enough to have set `accessibility_display_daltonizer_enabled`.
  2. Upgrade to the current build on a device where `WRITE_SECURE_SETTINGS` is not granted (the normal
     case).
  3. `OverlayService.onCreate` attempts the clear, the `SecurityException` is swallowed, and the
     display stays as it was with nothing logged.
- **Blast radius**:
  - *Callers*: private; the sole caller is `OverlayService.onCreate()` (`:148`).
  - *Process death*: none â€” re-attempted on every service start, so a transient failure self-heals.
  - *Config change*: none.
  - *Foreground/background*: none; it runs before any UI exists.
  - *Cross-process*: none. `OverlayService` is default-process
    (`evidence/cross-process-inventory.md`), and `Settings.Secure` writes go through the settings
    provider, not shared memory.
  - *Persistence*: it writes to `Settings.Secure`, i.e. system state outside the app sandbox â€” the
    reason to keep the catch narrow rather than widen it.
  - *Release-only*: no.
- **Fix** (minimal): add one line to the catch body â€”
  `GrayzoneLogger.d(LogComponent.OVERLAY, "No WRITE_SECURE_SETTINGS grant; stale grayscale not cleared")`.
  Keep the narrow `SecurityException` type and keep the behaviour identical.
- **Rewrite justification**: n/a â€” a single log statement inside an existing catch.
- **Test to add**: `jvm-unit` â€” none is meaningful; the behaviour is a framework write plus a log, with
  no seam and no return value. Recorded honestly as a **verification gap** for T045 rather than
  papered over with a test that asserts nothing: the compliant form is verifiable only by code review
  or by an `instrumented` test that asserts a log record, which is a disproportionate harness for a
  one-line diagnostic.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`) â€” the catch body is read directly and
  independently catalogued by T011 as row 41.
  - *Missing*: nothing.
  - *Procedure*: not required. Optional: `adb shell settings get secure accessibility_display_daltonizer_enabled`
    before and after a service start on a device without `WRITE_SECURE_SETTINGS`.
  - *Severity effect*: **Low** under every outcome â€” cosmetic display state, documented intent, narrow
    catch type. It cannot rise to Medium because no protection behaviour depends on it.
- **Build scope**: `everyday-build`
- **Constitution**: III (comment-only catch body, explicitly prohibited).
- **Affected surfaces**: `evidence/lint.md` Â§E3 also flags the `ObsoleteSdkInt` dead branch at
  `OverlayService.kt:789` (`SDK_INT >= M`, always true at `minSdk = 29`) â€” assigned to T060, not
  reported here, and recorded in the coverage summary below so the omission is explicit. T030's
  Principle III sweep may also carry this catch; it is the same site, not a second defect.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-015 â€” `BootReceiver` acts on two unprotected broadcast actions that any installed app can send  [Low]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` â†’ `BOOT_ACTIONS`
  (lines 83â€“87) and `onReceive()` (lines 29â€“30), against `AndroidManifest.xml:139â€“149`
- **Issue**: the receiver is `android:exported="true"` (required to receive the system's
  `BOOT_COMPLETED`) and accepts three actions. `android.intent.action.BOOT_COMPLETED` is protected â€”
  only the system can broadcast it. `android.intent.action.QUICKBOOT_POWERON` and
  `com.htc.intent.action.QUICKBOOT_POWERON` are **not** protected: they are OEM conventions any
  installed app can send with `sendBroadcast`. `onReceive` validates only that the action is in
  `BOOT_ACTIONS` (`:30`); it does not check the sender, so a third-party app can drive the whole boot
  path on demand. What that buys an attacker is genuinely limited â€” it starts `OverlayService` (which
  the app wants running anyway) and re-runs the VPN restore decision â€” but it also lets an external
  caller repeatedly trigger `startForegroundService` and, on the hold-expired branch, force the
  `VPN_ENABLED = false` / `VpnHoldLockStore.clear` writes at `:53â€“54` at a time of the caller's
  choosing. For an app whose threat model explicitly includes the user working around their own limits
  (README's "Anti-Bypass Protection"), an externally triggerable path that clears VPN intent deserves a
  record.
- **Trigger condition**:
  1. Any installed app runs
     `adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -n com.grayzone.app/.service.BootReceiver`
     (or the equivalent `sendBroadcast`).
  2. `onReceive` executes the full boot restoration path outside of any boot.
  3. If a tracked VPN hold has already expired, `VPN_ENABLED` is cleared and the hold record removed on
     the caller's schedule.
- **Blast radius**:
  - *Callers*: the manifest registration (`AndroidManifest.xml:139â€“149`) plus any external caller.
    Internally the class is referenced from `MainActivity.kt` and
    `service/vpn/AdBlockVpnService.kt` (`evidence/reference-index.md` â†’ Â§`BootReceiver.kt`), and
    `onReceive` reaches `OverlayService` (4 sites), `AdBlockVpnService` (6 sites),
    `VpnHoldExpiryScheduler`, `VpnHoldLock` (3 sites) and `VpnHoldLockStore` (3 sites).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: an external broadcast can arrive while the app is in the background, when
    `startForegroundService` is most restricted â€” so the practical outcome may be a logged failure
    rather than a start.
  - *Cross-process*: yes in the general sense (an external process drives the receiver), but not across
    the `:a11y` boundary. No shared in-memory state is involved.
  - *Persistence*: yes â€” `VPN_ENABLED` (`:53`) and the two `VpnHoldLockStore` keys (`:54`) are durable
    writes reachable by an external trigger.
  - *Release-only*: no.
- **Fix** (minimal): treat the two unprotected actions as untrusted â€” require
  `Intent.ACTION_BOOT_COMPLETED` for anything that writes prefs, and for the `QUICKBOOT_POWERON`
  variants restrict the work to the idempotent `OverlayService` restart, logging the action that was
  received. A one-line `val trusted = receivedAction == "android.intent.action.BOOT_COMPLETED"` gate
  around `:48â€“77` is sufficient and preserves the OEM fast-boot behaviour the actions exist for.
- **Rewrite justification**: n/a â€” one boolean and one `if`.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` (shared with F-010, F-011,
  F-012), asserting that the decision function performs no prefs write when the action is
  `QUICKBOOT_POWERON` and does when it is `BOOT_COMPLETED`. Same pure-function lift.
- **Existing test conflict**: none â€” `BootReceiver` has no tests.
- **Verification**: `confirmed` (evidence tier `T1`) for the mechanism â€” the manifest exports the
  receiver, `BOOT_ACTIONS` includes two non-system-protected actions, and `onReceive` performs no
  sender validation. `needs-verification` for the practical reach.
  - *Missing*: a device, to establish what actually happens when the broadcast arrives outside boot.
  - *Procedure*: with a tracked-but-expired VPN hold in prefs, send
    `adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -n com.grayzone.app/.service.BootReceiver`
    from a foreground shell and from a background context, then diff
    `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml` before and after.
  - *Severity effect*: **Low** as filed â€” no privilege escalation, and every effect is something the
    app would do at boot anyway. Raise to **Medium** if the procedure shows an external caller can
    clear `VPN_ENABLED` while a hold is *still active*, which would be a genuine anti-bypass hole; the
    static read says the guard at `:52` prevents that.
- **Build scope**: `everyday-build`
- **Constitution**: none of Iâ€“VI. The manifest attribute half (`android:exported="true"`) belongs to
  RU-08 / T025; only the receiver's own action handling is claimed here.
- **Affected surfaces**: `AndroidManifest.xml:139â€“149` (owner T025). The two receivers that are
  correctly `exported="false"` â€” `SessionExpiryReceiver` (`:97â€“100`) and `VpnHoldExpiryReceiver`
  (`:106â€“109`) â€” are the right pattern and should be credited.
- **Observed against**: `557a963` for `BootReceiver.kt`;
  `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)` for the export attribute.
- **Disposition**: `verified`

### F-016 â€” RU-04's recovery-path failures are logged with raw `android.util.Log`, bypassing the component tagging and crash-reporting hook  [Low]

- **Review unit**: RU-04
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` (lines 43 and 75, plus
  the `Log.d` calls at 32, 41, 55, 62, 70) and
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` (lines 993 and 1129)
- **Issue**: `GrayzoneLogger` is the codebase's single logging entry point, referenced from 14 main
  files (`evidence/reference-index.md` â†’ Â§`GrayzoneLogger.kt`). It attaches a `LogComponent` tag,
  gates DEBUG/INFO to debug builds via `shouldLog` (`GrayzoneLogger.kt:37â€“40`), and is documented as
  the production crash-reporting integration point (`:69â€“71`, with the wiring sketched at `:77â€“80`).
  Four failure sites in this review unit bypass it:
  - `BootReceiver.kt:43` â€” `Log.e(TAG, "Failed to start OverlayService: ${e.message}")`: the single most
    consequential failure in the app (protection not restored after a reboot) is logged under the tag
    `GrayzoneBootReceiver`, without the throwable and without `LogComponent.BOOT` â€” even though the
    `BOOT` component exists for exactly this (`GrayzoneLogger.kt:22`).
  - `BootReceiver.kt:75` â€” same shape for the VPN restart failure.
  - `OverlayService.kt:993` â€” the C1 catch (see F-001), where the missing context is part of that
    finding.
  - `OverlayService.kt:1129` â€” `Log.e(TAG, "Failed to log blocked session: ${e.message}")`, no
    throwable, on the friction-skip Room insert.
  A logcat filter built for this app (`Grayzone` tag, `[COMPONENT]` prefix) therefore misses the boot
  failures entirely, and none of the four would reach crash reporting once the hook at
  `GrayzoneLogger.kt:77â€“80` is wired. Secondary effect: the six `Log.d` calls in `BootReceiver`
  (`:32`, `:41`, `:55`, `:62`, `:70`) and the three in `GrayzoneAccessibilityService` (`:56`, `:116`,
  `:121`) print in **release** builds, where `GrayzoneLogger.d` would be suppressed by `shouldLog` â€”
  and two of them (`:55`, `:62`) describe the user's VPN hold state, which the Code Quality Standards
  ask to keep out of logs ("without leaking user data").
- **Trigger condition**:
  1. Ship a release build and enable crash reporting at `GrayzoneLogger.kt:77â€“80`.
  2. A reboot fails to restart `OverlayService`.
  3. The failure is invisible to both the `Grayzone`/`LogComponent` logcat filter and to crash
     reporting; only a raw `GrayzoneBootReceiver` line exists, without the throwable.
- **Blast radius**:
  - *Callers*: `GrayzoneLogger.e()` is already called from `data/CleanupWorker.kt`, `AppUtils.kt`,
    `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`,
    `service/GrayzoneAccessibilityService.kt`, `service/SessionExpiryReceiver.kt` and others per
    `evidence/reference-index.md`; `service/SessionExpiryScheduler.kt` uses it correctly at `:57` and
    `:64`. The four sites above are the outliers within RU-04, so this is a consistency defect against a
    convention the same review unit otherwise follows.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: the affected sites are all background/boot paths, which is precisely where
    logcat is the only diagnostic available.
  - *Cross-process*: `GrayzoneLogger` is one of only two declarations reachable from `:a11y`, and it
    holds no mutable state, so the two class copies cannot diverge
    (`evidence/cross-process-inventory.md` marks it `correctly-shared`). Routing `:a11y` logs through it
    is therefore safe.
  - *Persistence*: none.
  - *Release-only*: partly â€” the `Log.d` suppression difference and the crash-reporting hook are
    release-build concerns, so the observable half of this finding is `release-only` while the missing
    throwable and component tag affect both builds.
  - *Cross-reference*: `evidence/error-handling-inventory.md` rows 45 and 47 record the two
    `OverlayService` sites (`logged-bare`, and "raw Log, no throwable"), and rows 23â€“24 record the two
    `BootReceiver` catches as `logged-with-context` â€” compliant on content, but via the wrong logger.
- **Fix** (minimal): replace the four `Log.e` calls with
  `GrayzoneLogger.e(LogComponent.BOOT | LogComponent.OVERLAY | LogComponent.DATABASE, "<same message without ${e.message}>", e)`,
  and the nine `Log.d` calls with `GrayzoneLogger.d(LogComponent.BOOT | LogComponent.ACCESSIBILITY, â€¦)`.
  The `OverlayService.kt:993` replacement is already specified by F-001's fix; do not apply it twice.
- **Rewrite justification**: n/a â€” call-site substitution onto an existing API.
- **Test to add**: `jvm-unit` â€” extend
  `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` (which already covers
  `GrayzoneLogger.shouldLog`) with an assertion that `shouldLog(LogLevel.DEBUG)` is `false` for a
  non-debug build, documenting the release-build suppression the raw `Log.d` calls bypass. The
  call-site substitution itself is verified by review, not by a test â€” recorded as a verification gap
  for T045 rather than covered by a hollow assertion.
- **Existing test conflict**: none. `CleanupAndLoggingTest.kt` is extended, not modified.
- **Verification**: `confirmed` (evidence tier `T1`) â€” the call sites and `GrayzoneLogger`'s gating and
  crash-reporting comment were all read directly.
  - *Missing*: nothing for the finding. To demonstrate the release-build half: a release APK.
  - *Procedure*: build release, reboot the device with the app installed, and filter
    `adb logcat -s Grayzone` â€” the boot failure lines will be absent while
    `adb logcat -s GrayzoneBootReceiver` shows them.
  - *Severity effect*: **Low** in all cases â€” observability and consistency, no behaviour change.
    Raise to **Medium** only if crash reporting is actually wired at `GrayzoneLogger.kt:77â€“80` and boot
    failures are consequently the one class of failure that never reaches it.
- **Build scope**: `release-only` for the `Log.d`-suppression and crash-reporting half;
  `everyday-build` for the missing throwable and component tag. Recorded as `release-only` so T044
  picks it up, with the everyday-build half noted here.
- **Constitution**: Code Quality Standards â€” "I/O and service-boundary code MUST log failures with
  actionable context (what failed, on which resource) without leaking user data." Not Principle III:
  every one of these sites does log.
- **Affected surfaces**: `GrayzoneLogger.kt:77â€“80` itself contains commented-out code (a Principle IV
  violation) but lives in the root package, so it belongs to RU-01 / T021 and is **not** reported here.
  `OverlayService.kt:993` overlaps F-001 by design.
- **Observed against**: `557a963` for `BootReceiver.kt`;
  `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt, app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt)`
- **Disposition**: `verified`

---

## Positive examples â€” credit these, do not "fix" them

Recorded so later fixes do not regress correct code.

1. **`SessionExpiryScheduler.arm()` (`:41â€“70`) is the reference error-handling implementation** in the
   whole codebase, and the lint `MissingPermission` warning against it is a confirmed **non-finding**
   (`evidence/lint.md` Â§E1). It guards with `SDK_INT < S || canScheduleExactAlarms()` (`:46`), falls
   back to the inexact Doze-permitted API (`:50`), catches the narrow `SecurityException` for the
   check-then-call race with a comment saying exactly that (`:52â€“53`), takes a documented recovery
   action (`:55`), and logs the *fallback's own* failure with the package name (`:56â€“62`). F-003 is
   about the platform consequence of the fallback being the default path â€” **not** about this guard,
   which must not be changed. Its `activeUntil <= now` early return (`:42`) is also correct: all three
   call sites pass a future timestamp.
2. **The whole alarm architecture replaces an in-memory `delay()`** and says so with a spec pointer
   (`SessionExpiryScheduler.kt:11â€“17`). Prefs are the durable source of truth, the receiver is
   manifest-registered so delivery survives process death, and `OverlayService.onCreate:154â€“162`
   re-arms in-flight sessions after a restart or reboot. `evidence/cross-process-inventory.md` credits
   this as a correct IPC channel. Keep all three parts.
3. **`SessionExpiryReceiver` (`:16â€“40`) is small and complete**: it validates the action (`:19`),
   null-checks the extra with an early return (`:20`), wraps the `startForegroundService` (`:22â€“26`),
   and logs failure with the package name and the throwable (`:27â€“33`). Its KDoc explains why a
   manifest receiver plus a while-idle alarm makes the foreground-service start legal (`:9â€“15`).
4. **Both `OverlayService` receiver registrations are defensive and correct** (`:187â€“201`,
   `:212â€“226`): `ContextCompat.registerReceiver` with `RECEIVER_NOT_EXPORTED` (`:192`, `:217`), the
   field assigned only after success, and failures logged with the throwable. `safeUnregister`
   (`:229â€“236`) and `onDestroy` (`:758â€“767`) null both fields, so there is no receiver leak.
5. **`OverlayService`'s coroutine scope ownership is correct â€” established, not re-derived.**
   `serviceScope` (`:115â€“117`) carries a `SupervisorJob` **and** a `CoroutineExceptionHandler`
   (`:107â€“113`) and **is** cancelled in `onDestroy` at **`:765`** (`evidence/concurrency-inventory.md`
   rows C2 and 221, quoted there in full). There is no `onUnbind` to worry about because `onBind`
   returns `null` (`:769`). Every launched job in the file (J1, J2, J3, J5â€“J10) is therefore
   transitively cancelled, and `dismissOverlay()` at `:766` runs after cancellation to remove the
   window. `GrayzoneApplication.appScope` is the one scope with no handler and no cancel path â€” RU-01,
   owner T021.
6. **The revoked-overlay-permission branch (`:790â€“822`) is the model C1's fix should copy**: it warns
   with context, tells the user with a Toast, tries to open the settings screen, wraps that
   `startActivity`, and returns without pretending the overlay exists.
7. **The custom-prompts Gson site (`:850â€“861`)** logs what failed and what it is doing about it, then
   degrades to the default prompt pool â€” the model JSON handler, in contrast to
   `data/ScheduleManager.kt:38` and `ui/CustomPromptsSheet.kt:44`.
8. **`GrayzoneAccessibilityService`'s deliberately broad catches (`:57`, `:106`) are correct for
   `:a11y` and the reason is written down** (`:39â€“40`, `:67â€“68`): an uncaught exception is one of the
   triggers that makes Android revoke an accessibility service. `onUnbind` (`:124â€“130`) additionally
   logs a possible revocation. **Do not narrow these** â€” and note the file's minimality is what makes
   the `:a11y` boundary clean (T010: zero falsely-shared state), so do not add state to it either.
9. **The `:a11y` â†’ default channels are real IPC, correctly secured**: the broadcast at `:86â€“89` sets
   an explicit `setPackage(...)`, and the watchdog uses `startForegroundService` with the package name
   in an extra (`:93â€“99`), guarded and logged (`:100â€“105`). F-009 is about the action *string*, not
   about the channel design.
10. **`BootReceiver` preserves user intent on failure** rather than clearing it (KDoc `:14â€“20`, plus
    the deliberate non-clearing at `:74â€“76`), restarts `OverlayService` unconditionally because it is
    the core monitoring service (`:36â€“38`), and skips a doomed VPN start when consent is missing
    instead of churning a failing foreground service (`:58â€“64`). The hold-expired-across-reboot branch
    (`:52â€“57`) correctly clears both the intent and the hold record.
11. **`OverlayService`'s foreground-service declaration satisfies the API 34 typed-FGS contract**:
    `foregroundServiceType="specialUse"` plus `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
    (`AndroidManifest.xml:65â€“72`) and the matching `FOREGROUND_SERVICE_SPECIAL_USE` permission
    (`:20`), with `startForeground` called synchronously inside `onCreate` so the 5-second window is
    met on every entry path. F-002 is about the missing guard, not about the declaration.
12. **The lock screen reconciles against wall-clock time.** `LockPresentation.initialSeconds`
    (`service/overlay/LockPresentation.kt:16â€“17`) and `SessionExpiryDecision.decide`
    (`policy/SessionExpiryDecision.kt:43â€“49`) both recompute from `now`, so a late alarm still shows the
    correct remaining lockout. Only the notification chronometer drifts (F-003).
13. **The transition queue is correctly synchronized.** `queuedForegroundPackage` and `transitionJob`
    are guarded by `synchronized(transitionLock)` on both the write and the read path (`:305â€“309`,
    `:314â€“322`), and the countdown job re-checks `isActive` before granting a session (`:1020`) â€”
    `evidence/concurrency-inventory.md` lists both under correctly synchronized shared state.
    `lastForegroundPackage`'s `@Volatile` (`:121`) **is** sufficient; F-007 concerns only
    `sessionStartTime`.

---

## Coverage summary

| File | Outcome | Findings | What was examined |
|---|---|---|---|
| `OverlayService.kt` | `findings-reported` | F-001, F-002, F-003 (notification half), F-004, F-005, F-006, F-007, F-008, F-009 (receiver half), F-014, F-016 | Entry paths `onCreate` (`:140â€“176`) and `onStartCommand` (`:659â€“678`) including all five action branches; FGS notification lifecycle (`:144`, `:706â€“751`, `:753â€“756`, `:1181â€“1200`); `onTaskRemoved` restart (`:680â€“704`); `onDestroy` (`:758â€“767`) and the absence of `onUnbind` (`:769`); all 15 catch blocks against `evidence/error-handling-inventory.md` rows 33â€“47; every SharedPreferences read/write (rows 30â€“41); the receiver registration/unregistration pair; the midnight-reset loop (`:243â€“293`); the transition queue and `drainForegroundTransitions` (`:304â€“325`); `buildSessionState` (`:438â€“483`) and `executeCommand` (`:485â€“537`); `logUsageAndCheckBudget` (`:559â€“623`); `handleSessionExpired` (`:630â€“657`); `showOverlay` (`:787â€“1032`) including permission check, prompt parsing, view construction, `addView` and the countdown job; `dismissOverlay` (`:1034â€“1045`); `markAppUnlocked` (`:1047â€“1100`); `recordFrictionSkip` (`:1102â€“1132`); all `@Volatile` and plain mutable fields against `evidence/concurrency-inventory.md` Â§R4 and the confined-state table. **Examined and deliberately not reported**: the `commit()` calls at `:531` and `:600` (confirmed non-finding â€” both on `Dispatchers.IO`; Low/style only per `findings.md` and `lint.md` Â§E4 as corrected); the `ObsoleteSdkInt` dead branch at `:789` (Low, assigned to T060 by `findings.md`); the `logged-bare` `unregisterReceiver` catch at `:233` (recorded compliant-with-note by T011 â€” the outcome is logged, only the receiver identity is missing); `secondsRemaining` / `overlayView` / `windowManager` / `countdownJobUI` as thread-safety issues (verified main-thread-confined by T009). |
| `GrayzoneAccessibilityService.kt` | `findings-reported` | F-009 (sender half), F-016 (`Log.d` half) | The full 131-line file. `onServiceConnected` (`:36â€“64`) including the `AccessibilityServiceInfo` configuration and its guard; `onAccessibilityEvent` (`:66â€“113`) including the event-type filter, the null/empty package guards, the package-targeted broadcast (`:86â€“89`), the watchdog `startForegroundService` (`:92â€“105`) and both catch layers; `onInterrupt` (`:115â€“117`); `onDestroy` (`:119â€“122`); `onUnbind` (`:124â€“130`). Cross-checked against `evidence/cross-process-inventory.md`'s enumeration of all seven declarations `:a11y` touches and against `evidence/reference-index.md`'s 11-entry `:a11y` surface. **Examined and deliberately not reported**: cross-process false sharing (confirmed non-finding â€” T010 proved zero instances and this file is the sole class in `:a11y`); the two broad `catch (e: Exception)` blocks (deliberate, documented, and credited); the missing `ProtectionHealthRepository.updateAccessibilityStatus` call (correctly absent here â€” writing it from `:a11y` is the documented latent trap; the default-process fix is F-010). |
| `BootReceiver.kt` | `findings-reported` | F-010, F-011, F-012, F-015, F-016 | The full 89-line file. Action validation (`:29â€“30`, `:83â€“87`); the `OverlayService` restart and its guard (`:39â€“44`); the VPN branch end to end â€” `VPN_ENABLED` read (`:48`), `VpnHoldLockStore.read` (`:49`), the two clocks (`:50â€“51`), the hold-expired branch and its two writes (`:52â€“57`), the consent probe (`:61â€“64`), the guarded service start (`:65â€“70`), the conditional hold re-arm (`:71â€“73`) and the failure catch (`:74â€“76`); both early returns and what each skips; all logging calls. Checked against `evidence/error-handling-inventory.md` rows 23â€“24, Â§2.2 row 54 and Â§2.13 rows 192â€“194, and against the manifest registration (`AndroidManifest.xml:139â€“149`). Confirmed it has **no** test coverage. |
| `SessionExpiryScheduler.kt` | `findings-reported` | F-003 (the alarm-exactness root cause), F-013 | The full 83-line file. `alarmManager()` (`:20â€“21`); `pendingIntent()` (`:23â€“34`) including the request-code derivation, the flags and what `filterEquals` does and does not compare; `arm()` (`:41â€“70`) including the early return, the exactness guard, both API calls, the `SecurityException` fallback and both logging catches; `cancel()` (`:73â€“82`). Verified by grep that no exact-alarm permission is declared anywhere in `app/`. Cross-checked against `evidence/error-handling-inventory.md` rows 26â€“29 and Â§2.8 rows 140â€“145, and against `evidence/lint.md` Â§E1 (non-finding) and Â§E2 (this finding). Confirmed it has **no** test coverage. |
| `SessionExpiryReceiver.kt` | `no-defect-found` | â€” | The full 40-line file, examined line by line, and no defect found. Specifically checked: the action guard at `:19` (`intent.action != ACTION_SESSION_EXPIRED` â†’ return) â€” correct, and required because the receiver is `exported="false"` but still reachable internally; the extra read at `:20` with an early return on null â€” correct; the outgoing Intent at `:22â€“25`, which sets `action = OverlayService.ACTION_SESSION_EXPIRED` **through the constant** rather than a literal (contrast F-009) and forwards the package under `EXTRA_PACKAGE_NAME` = `"package_name"`, which matches the raw key `OverlayService.onStartCommand:671` reads â€” verified by reading both sides, so there is no key mismatch; `startForegroundService` at `:26` wrapped in a `try` whose catch logs the package name **and** the throwable (`:27â€“33`) â€” compliant, T011 row 25; the FGS-start legality question, answered by the KDoc at `:9â€“15` (a manifest receiver plus AlarmManager's while-idle temporary allowlist), consistent with `evidence/cross-process-inventory.md`'s credited IPC channel; the absence of `goAsync()` â€” correct, since the body performs no blocking work; the duplicate literals `ACTION_SESSION_EXPIRED` (`:37`) and `EXTRA_PACKAGE_NAME` (`:38`) versus `OverlayService.kt:70` and `GrayzoneAccessibilityService.kt:33` â€” examined and judged **not** a defect, because both sides go through named constants and `:23` references `OverlayService`'s constant, so a rename breaks the build on at least one side (recorded under F-009's affected surfaces as adjacent, lower-risk duplication); the manifest declaration (`AndroidManifest.xml:97â€“100`, `exported="false"`, `enabled="true"`) â€” correct. Noted for T029, not reported as a defect here: the file has **no** test coverage (`evidence/reference-index.md`). |
| `AccessibilityServiceIds.kt` | n/a â€” not in RU-04 | â€” | Checked for, per the task's "if it lives here". It does not: the file is `app/src/main/java/com/grayzone/app/AccessibilityServiceIds.kt` in the root package (confirmed by directory listing and by `evidence/reference-index.md`, which indexes it under `com.grayzone.app`). It belongs to RU-01 (T021/T022) and was not analysed here. For completeness: `evidence/error-handling-inventory.md` records it as having zero catch blocks and its parsing as null/blank-safe (Â§2.7 row 138). |

**Register update required**: `findings.md`'s ID-allocation table row T018 should record `Used` =
F-001 â€¦ F-016.

# Findings â€” RU-03 `policy` (T024)

**Feature**: `004-android-regression-audit` | **Task**: T024 | **Created**: 2026-07-31
**Subject**: `app/src/main/java/com/grayzone/app/policy/` â€” `SessionPolicyEngine.kt` (143 lines),
`SessionModels.kt` (73 lines), `SessionExpiryDecision.kt` (55 lines).
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md) Â·
**Register**: [`findings.md`](./findings.md) Â· **Callers**: [`evidence/reference-index.md`](./evidence/reference-index.md)
**ID block**: F-110 â€¦ F-124. **Used**: F-110 â€¦ F-119.
**Observed against**: `557a963` â€” verified clean: `git status --porcelain -- app/src/main/java/com/grayzone/app/policy/`
returns no output at `557a963cc059098b0ff5c2f62bd8d45593031370`. No file in this unit is in the
uncommitted working-tree set.

---

## Verdict summary

| Finding | Severity |
|---|---|
| [F-110](#f-110--moving-the-system-clock-forward-ends-an-active-lockout-instantly-sessionstateislockedout--critical) â€” Moving the system clock forward ends an active lockout instantly | **Critical** |
| [F-111](#f-111--moving-the-system-clock-backward-makes-an-already-expired-session-test-as-active-again-sessionstatehasactivesession--critical) â€” Moving the system clock backward makes an expired session test as active again | **Critical** |
| [F-112](#f-112--showwaitscreenwaitseconds-is-decided-by-the-engine-and-discarded-by-its-only-consumer-which-re-derives-the-wait-from-prefs-with-no-validation--medium) â€” `ShowWaitScreen.waitSeconds` is decided centrally, discarded, and re-derived unvalidated | Medium |
| [F-113](#f-113--clampremainingms-is-dead-and-its-three-tests-assert-the-feature-002-negative-countdown-contract-against-a-function-production-never-calls--medium) â€” `clampRemainingMs` is dead; its tests guard nothing production runs | Medium |
| [F-114](#f-114--sessioncommanddismissoverlay-is-a-declared-command-that-its-only-executor-ignores-so-the-engine-cannot-clear-an-overlay--medium) â€” `SessionCommand.DismissOverlay` is a command its only executor ignores | Medium |
| [F-115](#f-115--sessionstatedefaultsessionmins-is-never-read-by-the-engine-the-session-grant-decision-lives-outside-the-decision-core--low) â€” `SessionState.defaultSessionMins` is never read by the engine | Low |
| [F-116](#f-116--sessionstate-can-represent-an-out-of-range-defaultlockoutmins-and-the-engines-int-only-arithmetic-overflows-on-it--low) â€” `SessionState` can represent an out-of-range `defaultLockoutMins`; Int-only arithmetic overflows | Low |
| [F-117](#f-117--appeventtimertick-has-no-production-producer-and-an-empty-handler-branch--low) â€” `AppEvent.TimerTick` has no production producer | Low |
| [F-118](#f-118--unreachable-else-branch-in-the-appbackgrounded-pause-path--low) â€” Unreachable `else` branch in the `AppBackgrounded` pause path | Low |
| [F-119](#f-119--executecommand-re-samples-the-clock-so-commands-execute-against-a-different-instant-than-they-were-decided-against--low) â€” `executeCommand` re-samples the clock, breaking the decided-`now` contract | Low |

**Clock-manipulation verdicts** (extended condition E7, all statically derived â€” see
[Clock and time analysis](#clock-and-time-analysis-e7) for the full reasoning and quoted code):

| Scenario | Engine affected? | Verdict |
|---|---|---|
| Device timezone change mid-session | **No** | All three files are timezone-free. Session/lockout arithmetic is pure epoch-millis. Credited as a positive. |
| DST spring-forward gap inside a session or lockout | **No** | Epoch millis is monotone across the gap; the unit never converts to local wall time. Credited. |
| DST autumn fold (repeated local hour) inside a session or lockout | **No** | Same reason. No local-time conversion exists to be ambiguous. Credited. |
| System clock moved **forward** | **Yes** | **F-110 â€” trivial protection bypass.** A lockout ends the moment the clock passes `lockedUntil`. |
| System clock moved **backward** | **Yes** | **F-111 â€” protection bypass plus a user trap.** Expired sessions test active again; lockouts outlive their configured duration. |
| `System.currentTimeMillis()` vs a monotonic clock | **Yes** | Every deadline in this unit is wall-clock only. `VpnHoldLock` already implements the correct dual-clock pattern in this same repo; sessions do not use it. Root cause of both F-110 and F-111. |

**`clampRemainingMs` bypassed in production?** **Yes â€” confirmed.** `SessionExpiryDecision.clampRemainingMs()`
has zero main-source callers. Production clamps inline at three separate sites instead. Unlike
`PerAppLimits.waitSeconds()`, the inline paths here are *equivalent or stronger*, so the shipped
behavior is correct â€” the defect is that the three tests asserting the feature-002 negative-countdown
contract exercise only the unused helper. See [F-113](#f-113--clampremainingms-is-dead-and-its-three-tests-assert-the-feature-002-negative-countdown-contract-against-a-function-production-never-calls--medium).

**Unresolvable statically**: one item only â€” whether an already-attached overlay window visibly
persists over the next app after the engine emits an ignored `DismissOverlay`
([F-114](#f-114--sessioncommanddismissoverlay-is-a-declared-command-that-its-only-executor-ignores-so-the-engine-cannot-clear-an-overlay--medium)).
The *mechanism* (the command is a no-op) is confirmed from source; only the on-screen outcome needs a
device.

---

## Clock and time analysis (E7)

This section states the derivation once so the findings below can reference it instead of repeating
it. It is entirely static.

**Where the clock enters.** Neither `SessionPolicyEngine.kt`, `SessionModels.kt`, nor
`SessionExpiryDecision.kt` contains a single `import` statement. There is no `java.util.Calendar`, no
`TimeZone`, no `SimpleDateFormat`, no `System.currentTimeMillis()`, and no `SystemClock`. Every
time value arrives as a `Long` parameter:

```17:17:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
    fun evaluate(event: AppEvent, state: SessionState, now: Long, appName: String): List<SessionCommand> {
```

```30:32:app/src/main/java/com/grayzone/app/policy/SessionModels.kt
    fun hasActiveSession(now: Long): Boolean = now < activeUntil
    fun isLockedOut(now: Long): Boolean = now < lockedUntil
    fun isBudgetExhausted(): Boolean = budgetRemainingMs <= 0
```

**Timezone and DST: not affected, and that is a design property, not luck.** The five production
call sites all supply `System.currentTimeMillis()` (`OverlayService.kt:334`, `:420`, `:486`, `:632`,
`:1049`), which is UTC epoch millis. `activeUntil` and `lockedUntil` are written from the same
source (`OverlayService.kt:1088-1089`) and persisted as `Long` (`:1093-1094`). Epoch millis does not
change when the device timezone changes and does not repeat or skip across a DST transition, so
`now < activeUntil` and `now < lockedUntil` are invariant under both. The alarm that drives expiry
is `AlarmManager.RTC_WAKEUP` set at the same epoch value (`SessionExpiryScheduler.kt:48`, `:50`),
which the platform re-maps itself on a timezone change. **A session or lockout spanning either DST
boundary lasts exactly its configured duration.** This is the correct answer and the unit earns
credit for it.

The timezone coupling the app *does* have is not in this unit: `budgetRemainingMs` is computed
before construction from `DateUtils.getCurrentDateKey()` (`OverlayService.kt:462`), a **local**
calendar date. Crossing a timezone eastward can therefore roll the daily budget over early, granting
a fresh daily allowance. That is `DateUtils`/`DailyBudget` behavior owned by **T020 (RU-02)**; the
policy unit receives an already-computed `Long` and is correct with respect to it. Recorded here as
a cross-reference so it is not lost, not claimed as an RU-03 finding.

**Forward and backward wall-clock changes: affected.** Both predicates compare a caller-supplied
wall clock against a stored wall-clock deadline with no second, monotonic corroborating value. The
repo already contains the correct pattern for exactly this threat, applied to the VPN hold:

```25:34:app/src/main/java/com/grayzone/app/service/vpn/VpnHoldLock.kt
    fun remainingMs(state: State, nowWallMs: Long, nowElapsedMs: Long): Long {
        if (!state.isTracked) return 0L
        val started = state.startedElapsedMs
        val rebooted = started > 0L && nowElapsedMs < started
        return if (!rebooted && started > 0L) {
            (started + HOLD_MS - nowElapsedMs).coerceAtLeast(0L)
        } else {
            (state.startedWallMs + HOLD_MS - nowWallMs).coerceAtLeast(0L)
        }
    }
```

Its own doc comment states the intent: *"Remaining time prefers `State.startedElapsedMs` so
wall-clock changes cannot skip the wait; after reboot (elapsed clock resets), falls back to wall
`State.startedWallMs`."* (`VpnHoldLock.kt:9-10`). The persisted companion keys already exist
(`PrefsKeys.VPN_HOLD_STARTED_WALL`, `PrefsKeys.VPN_HOLD_STARTED_ELAPSED`). **The session and lockout
deadlines â€” the app's primary protection â€” do not use this pattern.** The VPN hold, a secondary
feature, is hardened against clock tampering while the friction/lockout core is not.

**No time-change signal is handled anywhere.** Exhaustive search across `app/`: zero occurrences of
`ACTION_TIME_CHANGED` and zero of `ACTION_TIMEZONE_CHANGED`. `BootReceiver` registers only
`BOOT_COMPLETED` and package-replaced. So nothing re-evaluates state or re-arms alarms when the user
changes the clock; the next evaluation happens opportunistically on the next app switch.

---

## Findings

### F-110 â€” Moving the system clock forward ends an active lockout instantly (`SessionState.isLockedOut`)  [Critical]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionState.isLockedOut()` (line 31); consumed at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (line 62) and `SessionExpiryDecision.decide()` (`SessionExpiryDecision.kt:45`).
- **Issue**: `isLockedOut(now)` is `now < lockedUntil`, comparing a caller-supplied wall clock
  against a wall-clock deadline persisted in `SharedPreferences`
  (`PrefsKeys.LOCKED_UNTIL + pkg`, written at `OverlayService.kt:1094`). Nothing corroborates it
  against a monotonic clock. Advancing the device clock past `lockedUntil` therefore makes
  `isLockedOut` return `false` immediately, the engine falls through the lockout gate at
  `SessionPolicyEngine.kt:62` to the wait screen at `:101`, and the user gets a fresh session after
  the configured friction delay. The penalty box â€” the app's central deterrent â€” is defeated by two
  taps in Settings, with **no crash, no log, and no user-facing signal that protection lapsed**. For
  a self-control app whose README advertises "Anti-Bypass Protection", the user is inside the threat
  model, which is what makes this a protection failure rather than an edge case.
  The lockout *screen* fails the same way: `LockPresentation.initialSeconds` computes
  `((lockedUntil - now) / 1000).coerceAtLeast(0)` (`LockPresentation.kt:17`), so a forward jump
  renders the countdown at `0m 0s` and `onCountdownFinished` (`:19-26`) immediately dismisses and
  drops the user onto the friction screen.
- **Trigger condition**:
  1. Install, grant accessibility + overlay permissions, add a monitored app with a 60-minute
     lockout (the default).
  2. Open the monitored app, sit through the friction countdown, then let the session expire â€” or
     force it: `adb shell am broadcast -a com.grayzone.app.SESSION_EXPIRED --es package_name <pkg>`.
  3. Re-open the monitored app and confirm the lock screen shows a lockout countdown.
  4. Disable automatic time: `adb shell settings put global auto_time 0`, then advance the clock
     past the lockout: `adb shell date $(date -d '+2 hours' +%m%d%H%M%Y.%S)` (or Settings â†’ System â†’
     Date & time â†’ set time forward two hours).
  5. Re-open the monitored app. **Observed**: the friction wait screen appears instead of the lock
     screen, and after the wait a full new session is granted.
- **Blast radius**:
  - *Callers*: `SessionState.isLockedOut()` is called from `SessionPolicyEngine.evaluate()`
    (2 sites) only â€” per `evidence/reference-index.md` Â§`SessionModels.kt`. `evaluate()` is called
    from `OverlayService.processForegroundAppChanged()` (`OverlayService.kt:355`) and
    `OverlayService.backgroundApp()` (`:400`). The equivalent wall-clock comparison
    `now < lockedUntil` also appears in `SessionExpiryDecision.decide()` (`:45`, called from
    `OverlayService.handleSessionExpired()` at `:636`), in `isAnyAppLocked()`
    (`Extensions.kt:56`, called from `OverlayService.kt`, `ui/AppsScreen.kt`, `ui/SettingsScreen.kt`)
    and in `LockPresentation.initialSeconds()` (`LockPresentation.kt:17`, reached via
    `OverlayPresentation.resolve()` from `OverlayService.showOverlay`). A fix confined to the policy
    predicates leaves the other three reading the same tampered value.
  - *Process death*: no change â€” the defect is in persisted wall-clock state, which survives process
    death by design. A fix must persist the monotonic companion value too, not hold it in memory.
  - *Config change*: none. No Activity or Compose state participates.
  - *Foreground/background*: the bypass is realised on the next foreground transition of the
    monitored app; it does not require the app to be running at the moment the clock changes. While
    backgrounded, nothing re-evaluates, so the state simply stays wrong until the next transition.
  - *Cross-process*: not reachable from `:a11y`. `GrayzoneAccessibilityService` sends only a package
    name (`evidence/reference-index.md`, process-topology note); it holds no session state. The fix
    stays entirely in the main process.
  - *Persistence*: yes â€” `LOCKED_UNTIL + pkg` (`PrefsKeys.kt`, written `OverlayService.kt:1094`,
    `:517`; removed `:521`) and, for the fix, one new companion key per package. `StateBackupManager`
    snapshots `prefs.all` (`StateBackupManager.kt:167`), so any new key is carried by backup/restore
    automatically â€” which is itself a hazard given the unvalidated `applyInto` already recorded in
    `findings.md`.
  - *Release-only*: not applicable. Pure Kotlin arithmetic in a class R8 keeps (reachable from
    `OverlayService`, a manifest component); no shrinking or optimization can alter the comparison.
- **Fix** (minimal): persist an `elapsedRealtime()` companion alongside each wall deadline and prefer
  it, mirroring `VpnHoldLock.remainingMs` exactly. Concretely: add `lockedUntilElapsed` and
  `nowElapsed` to `SessionState`, and change `isLockedOut` to
  `if (lockedUntilElapsed > 0L && nowElapsed >= 0L && nowElapsed < lockedUntilElapsed) true else now < lockedUntil`,
  with the wall value as the post-reboot fallback (detected exactly as `VpnHoldLock.kt:28` does it:
  `nowElapsed < startedElapsed` means the device rebooted). Write the companion where the wall value
  is written (`OverlayService.kt:1094`, `executeCommand` `:517`) and read it in `buildSessionState`
  (`:443`). This is a field addition plus one changed expression â€” no restructuring of the engine,
  no change to gate ordering, and it reuses a pattern already proven in this codebase.
- **Rewrite justification**: not a rewrite. `evaluate()`'s structure, gate ordering and command
  vocabulary are unchanged; only the predicate's inputs and body change.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/policy/SessionClockTamperTest.kt`,
  asserting that a state whose lockout has 30 minutes of *elapsed*-clock remaining still reports
  `isLockedOut == true` when `now` is advanced past `lockedUntil`, and that
  `evaluate(AppForegrounded, â€¦)` emits `ShowLockoutScreen` rather than `ShowWaitScreen`. Fails before
  the fix (`isLockedOut` returns `false`, engine emits `ShowWaitScreen`); passes after. Add the
  reboot-fallback case as well: `nowElapsed < lockedUntilElapsed - HOLD` must fall back to the wall
  comparison, mirroring `VpnHoldLockTest`'s existing reboot case.
- **Existing test conflict**: `SessionStatePredicateTest` (4 `isLockedOut` cases, lines 40â€“58) and
  `SessionPolicyEngineForegroundTest.lockout boundary - now equals lockedUntil is no longer locked`
  (line 128) construct `SessionState` positionally through the `state()` fixture
  (`PolicyTestFixtures.kt:14-37`). Adding constructor parameters with defaults of `0L` keeps all of
  them compiling and passing unchanged, because `0L` selects the existing wall-clock path. No
  assertion needs to change.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing required for the mechanism. The predicate, both call sites, the persistence
    of `LOCKED_UNTIL` and the absence of any monotonic companion or `ACTION_TIME_CHANGED` receiver
    were each read directly.
  - *Procedure*: the trigger steps above serve as an optional on-device confirmation of the
    user-visible outcome; they are not needed to establish the defect.
  - *Severity effect*: none â€” severity does not depend on any pending observation.
- **Build scope**: everyday-build
- **Constitution**: II (a protection decision derived from a single untrustworthy input rather than
  centralised on the hardened pattern the codebase already owns)
- **Affected surfaces**: `SessionExpiryDecision.decide()` (`SessionExpiryDecision.kt:45`),
  `isAnyAppLocked()` (`Extensions.kt:56`), `LockPresentation.initialSeconds()`
  (`LockPresentation.kt:17`), `ui/AppsScreen.kt` and `ui/LimitsScreen.kt` lock displays via
  `AppLockState`. `ScheduleManager`'s focus-mode and break deadlines (`FOCUS_MODE_UNTIL`,
  `BREAK_UNTIL`) share the shape and are **T020**'s. Contrast: `VpnHoldLock` is already correct.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-111 â€” Moving the system clock backward makes an already-expired session test as active again (`SessionState.hasActiveSession`)  [Critical]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionState.hasActiveSession()` (line 30); consumed at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (lines 56, 113, 118).
- **Issue**: `hasActiveSession(now)` is `now < activeUntil` against a persisted wall-clock deadline.
  Moving the device clock **backward** by *N* makes every session that expired within the last *N*
  test as active again. The engine's gate ordering places the active-session check *before* both the
  lockout and budget gates â€” deliberately, and correctly for its intended invariant
  (`SessionPolicyEngine.kt:50-55`) â€” so a resurrected session short-circuits straight to
  `DismissOverlay` at `:57`:

  ```56:59:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
                if (state.hasActiveSession(now)) {
                    commands.add(SessionCommand.DismissOverlay)
                    return commands
                }
  ```

  **Every monitored app whose session ended within the rewound window becomes freely usable at once**,
  with no friction screen, no lock screen, no log and no notification. The session also never
  expires while the clock stays rewound: `SessionExpiryScheduler.arm` is an `RTC_WAKEUP` alarm at
  `activeUntil` (`SessionExpiryScheduler.kt:48`) which the platform pushes further out, and
  `handleSessionExpired` would return `NoOp` anyway because `decide` re-checks `now < activeUntil`
  (`SessionExpiryDecision.kt:43`). Nothing re-arms or re-evaluates on a clock change â€” there is no
  `ACTION_TIME_CHANGED` receiver anywhere in `app/`.

  The same rewind has a second, opposite consequence that harms an *honest* user: `lockedUntil` also
  moves further into the future, so a lockout outlives its configured duration by the full rewind.
  A device whose clock was fast and is then corrected (manually or by an NTP sync after
  `auto_time` is re-enabled) leaves the user locked out of an app for hours longer than the app
  promised, with the lock screen counting down a duration it never agreed to and no way to clear it.
  Rewinding past `LOCKED_UNTIL`'s original write time makes the lockout effectively permanent until
  real time catches up.

  A third, visible symptom confirms the two clocks have diverged: the countdown notification anchors
  to the **monotonic** clock â€” `baseElapsed = SystemClock.elapsedRealtime() + remainingMs`
  (`OverlayService.kt:721`) â€” so the notification chronometer reaches zero at the true moment while
  the policy still believes the session is live. The user sees a finished timer over an unlocked app.
- **Trigger condition**:
  1. Install, grant accessibility + overlay permissions, add a monitored app with default limits.
  2. Open the monitored app, sit through the friction countdown, and let the session run to
     completion so `ACTIVE_UNTIL + pkg` is a timestamp a few minutes in the past.
  3. Confirm protection is live: re-open the app and observe the lock screen.
  4. `adb shell settings put global auto_time 0`, then rewind:
     `adb shell date $(date -d '-2 hours' +%m%d%H%M%Y.%S)`.
  5. Re-open the monitored app. **Observed (bypass)**: no overlay at all â€” the app opens directly.
  6. For the trap half: while a lockout is running, rewind the clock two hours and re-open the app.
     **Observed**: the lock screen counts down roughly two hours more than the configured lockout.
- **Blast radius**:
  - *Callers*: `SessionState.hasActiveSession()` is called from `SessionPolicyEngine.evaluate()`
    (3 sites: `:56`, `:113`, `:118`) and from `OverlayService.kt` (4 sites) â€” per
    `evidence/reference-index.md` Â§`SessionModels.kt`. In `OverlayService` the reachable call is
    `processForegroundAppChanged()` at `:365` (`var hasActiveSession = newState.hasActiveSession(now)`),
    which gates whether `sessionStartTime` is set at `:379` â€” so a resurrected session also causes
    usage to be timed and charged against the daily budget. `evaluate()` itself is called from
    `OverlayService.processForegroundAppChanged()` (`:355`) and `OverlayService.backgroundApp()`
    (`:400`). The same `now < activeUntil` comparison is duplicated in
    `SessionExpiryDecision.decide()` (`:43`) and `isAnyAppLocked()` (`Extensions.kt:56`).
  - *Process death*: no change; the state is persisted prefs, and `OverlayService.onCreate:158-160`
    re-arms the alarm from the same tampered `ACTIVE_UNTIL`, so a restart re-establishes the wrong
    deadline rather than correcting it.
  - *Config change*: none.
  - *Foreground/background*: the bypass materialises on the next foreground transition. Note
    `processForegroundAppChanged` returns early when `currentPkg == lastForegroundPackage`
    (`OverlayService.kt:330`), so the user must switch away and back â€” which the trigger steps do.
  - *Cross-process*: not reachable from `:a11y`; that process carries no session state
    (`evidence/reference-index.md`, process topology; `findings.md` confirmed NON-finding).
  - *Persistence*: yes â€” `ACTIVE_UNTIL + pkg` and `LOCKED_UNTIL + pkg` (`OverlayService.kt:1093-1094`,
    `:516-517`, removed at `:520-521`), plus one new companion key per package for the fix. Carried
    by `StateBackupManager` (`prefs.all`, `:167`).
  - *Release-only*: not applicable â€” pure arithmetic in an R8-kept class.
- **Fix** (minimal): the same companion-value fix as F-110, applied to `activeUntil`. Persist
  `elapsedRealtime()` at grant time next to `ACTIVE_UNTIL`, add `activeUntilElapsed`/`nowElapsed` to
  `SessionState`, and make `hasActiveSession` prefer the monotonic comparison with the wall value as
  the post-reboot fallback (`nowElapsed < storedElapsed` â‡’ rebooted â‡’ use wall, exactly
  `VpnHoldLock.kt:28`). Both F-110 and F-111 are fixed by one field pair per deadline; they are
  recorded separately because they are independent defects with opposite user consequences and
  either can be shipped without the other.
  Additionally register a `BroadcastReceiver` for `android.intent.action.TIME_SET` that calls the
  existing `RuntimeSessionReset.resetIfNeeded` plus a re-arm loop over monitored packages â€” the
  re-arm loop already exists verbatim at `OverlayService.kt:157-161` and can be extracted rather
  than rewritten.
- **Rewrite justification**: not a rewrite; see F-110.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionClockTamperTest.kt` (same new file as F-110),
  asserting that a session which has expired on the monotonic clock reports
  `hasActiveSession == false` even when `now` is rewound behind `activeUntil`, and that
  `evaluate(AppForegrounded, â€¦)` emits `ShowLockoutScreen`. Fails before the fix (returns `true`,
  engine emits `DismissOverlay`); passes after. A second case asserts the trap half: a lockout whose
  monotonic deadline has passed reports `isLockedOut == false` despite a rewound wall clock.
- **Existing test conflict**: `SessionStatePredicateTest` lines 18â€“36 (4 `hasActiveSession` cases),
  `SessionPolicyEngineForegroundTest` lines 86â€“114, and `SessionPolicyEngineBackgroundTest`
  lines 61â€“105 all build state through the `state()` fixture. Defaulting the new parameters to `0L`
  (â‡’ wall-clock path) keeps every one compiling and passing with no assertion change.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing. The predicate, the gate ordering, the alarm's `RTC_WAKEUP` clock base, the
    `decide()` re-check, the absence of any time-change receiver, and the monotonic notification
    anchor were each read directly.
  - *Procedure*: the trigger steps above optionally confirm the user-visible outcome; the defect
    does not depend on them.
  - *Severity effect*: none.
- **Build scope**: everyday-build
- **Constitution**: II (protection decision derived from a single untrustworthy clock while the
  hardened dual-clock pattern already exists in-repo)
- **Affected surfaces**: `SessionExpiryDecision.decide()` (`SessionExpiryDecision.kt:43`),
  `isAnyAppLocked()` (`Extensions.kt:56`), `OverlayService.processForegroundAppChanged()` (`:365`),
  `OverlayService.handleSessionExpired()` (`:630-657`), `SessionExpiryScheduler.arm()`
  (`SessionExpiryScheduler.kt:41-70`, `RTC_WAKEUP`). `ScheduleManager`'s `FOCUS_MODE_UNTIL` and
  `BREAK_UNTIL` share the shape (**T020**). Contrast: `VpnHoldLock` is correct.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-112 â€” `ShowWaitScreen.waitSeconds` is decided by the engine and discarded by its only consumer, which re-derives the wait from prefs with no validation  [Medium]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionCommand.ShowWaitScreen` (lines 41â€“45, `waitSeconds: Int` at line 44); emitted at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (line 101); ignored at `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.executeCommand()` (lines 491â€“495) and re-derived at `OverlayService.showOverlay()`
  (lines 835â€“843).
- **Issue**: The engine decides the friction duration and hands it over as a command payload:

  ```101:101:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
                commands.add(SessionCommand.ShowWaitScreen(state.packageName, appName, DEFAULT_WAIT_SECONDS))
  ```

  Its only consumer drops the payload on the floor:

  ```491:495:app/src/main/java/com/grayzone/app/service/OverlayService.kt
            is com.grayzone.app.policy.SessionCommand.ShowWaitScreen -> {
                serviceScope.launch(Dispatchers.Main) {
                    showOverlay(cmd.packageName, cmd.appName, com.grayzone.app.OverlayMode.FRICTION, 0L)
                }
            }
  ```

  `cmd.waitSeconds` is never read. `showOverlay` then resolves the wait itself, straight from prefs:

  ```835:845:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val customWait = prefs.getInt(PrefsKeys.PER_APP_WAIT_SECONDS + packageName, DEFAULT_WAIT)
        val globalWait = prefs.getInt(PrefsKeys.WAIT_SECONDS, DEFAULT_WAIT)

        secondsRemaining = presentation.initialSeconds(
            SecondsInputs(
                now = System.currentTimeMillis(),
                lockedUntil = lockedUntil,
                waitSeconds = if (hasCustom) customWait else globalWait
            )
        )
  ```

  **Can the two paths disagree about the same session? Yes, always** â€” whenever the user has
  configured any wait other than 5 seconds, or set a per-app override. The engine says 5
  unconditionally; the overlay shows the configured value. **What the user sees today is the
  configured value, i.e. the correct one** â€” so there is no user-visible wrong behavior at present.
  Two consequences make it a defect anyway:

  1. **The inline path drops validation the codebase already wrote for it.**
     `PerAppLimits.waitSeconds()` (`PerAppLimits.kt:15-16`) exists precisely to resolve
     per-app-vs-global with a `coerceIn(1, 60)` guard, and is **TEST-ONLY** per
     `evidence/reference-index.md`. `ConfigValidator.WAIT_SECONDS_RANGE = 3..30`
     (`ConfigValidator.kt:15`) is enforced only on the *global* key, at startup
     (`GrayzoneApplication.kt:181-185`). Nothing validates `per_app_wait_<pkg>`, and
     `OverlayService.kt:836` reads it raw. `PerAppSettingsSheet.kt:98-105` bounds the slider
     `3f..30f`, so the normal UI is safe; the realistic vector is the unvalidated backup restore
     already recorded in `findings.md` (`applyInto` writes arbitrary keys with no schema gate). A
     restored `per_app_wait_<pkg>` of `0` makes friction instantaneous â€” protection silently
     defeated â€” and a huge value traps the user behind a countdown they cannot dismiss except by
     leaving the app. This discharges the confirmed lead assigned to T020/T024/T026 in
     `findings.md`.
  2. **It is a loaded trap for the next maintainer.** `SessionCommand.ShowWaitScreen` declares
     `waitSeconds` and `SessionPolicyEngineForegroundTest:207-213` asserts it equals
     `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` â€” a green test guarding a value nothing consumes.
     Anyone who "fixes" the executor to honour the command payload would silently cap every user's
     friction at 5 seconds, weakening protection for everyone who configured more, with a passing
     test suite.
- **Trigger condition**:
  1. Install and add a monitored app; in Settings set the global pause to 30 seconds.
  2. Open the monitored app and observe a **30**-second friction countdown â€” i.e. the engine's
     `waitSeconds = 5` had no effect. Confirms the payload is dead.
  3. For the validation half: with the app stopped, write an out-of-range per-app value directly, as
     a restored backup would â€”
     `adb shell 'run-as com.grayzone.app sed -i "s#<int name=\"per_app_wait_<pkg>\" value=\"[0-9]*\"#<int name=\"per_app_wait_<pkg>\" value=\"0\"#" /data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml'`
     (or restore a hand-edited `/sdcard/.grayzone/` snapshot, the supported vector).
  4. Re-open the monitored app. **Observed**: the friction screen elapses immediately, granting a
     session with no pause at all. `PerAppLimits.waitSeconds()`'s `coerceIn(1, 60)` would have
     prevented it had it been called.
- **Blast radius**:
  - *Callers*: `SessionCommand.ShowWaitScreen` is referenced from `policy/SessionPolicyEngine.kt`
    and `service/OverlayService.kt` (2 sites) â€” per `evidence/reference-index.md`
    Â§`SessionModels.kt`. The two `OverlayService` sites are the `when` branch at `:491` and the
    `didShowWaitScreen` flag check at `:367`; **neither reads `waitSeconds`**. Tests
    `SessionPolicyEngineForegroundTest.kt` and `SessionPolicyEngineTest.kt` are the only readers of
    the field. `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` is referenced only from `(self)`
    `evaluate()` plus `ConfigValidatorTest.kt` and `SessionPolicyEngineForegroundTest.kt` â€” zero
    main-source consumers outside the engine.
  - *Process death*: none â€” the command is transient and re-derived on the next transition.
  - *Config change*: none.
  - *Foreground/background*: the wait screen is shown from `Dispatchers.Main` while the monitored app
    is foreground; a fix that threads the value through must not re-read prefs on Main (it already
    does, at `:826`, which is B8 in `evidence/concurrency-inventory.md` â€” out of scope here but do
    not make it worse).
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: reads `PrefsKeys.PER_APP_WAIT_SECONDS + pkg` and `PrefsKeys.WAIT_SECONDS`
    (`OverlayService.kt:836-837`). No write. The fix adds no key.
  - *Release-only*: not applicable.
- **Fix** (minimal): resolve the wait **once**, in `buildSessionState`, through the helper that
  already exists â€” add `waitSeconds = PerAppLimits.waitSeconds(hasCustom, perApp = prefs.getInt(PER_APP_WAIT_SECONDS + pkg, 5), global = prefs.getInt(WAIT_SECONDS, 5))`
  to `SessionState` (`OverlayService.kt:470-482`), emit `state.waitSeconds` instead of
  `DEFAULT_WAIT_SECONDS` at `SessionPolicyEngine.kt:101`, pass `cmd.waitSeconds` through
  `executeCommand` (`:491-495`) into `showOverlay`, and delete the inline resolution at
  `OverlayService.kt:835-843`. This is a parameter thread-through: it makes `PerAppLimits.waitSeconds()`
  live (removing it from the TEST-ONLY list), makes the engine the single source of truth
  (Principle II), and restores the `coerceIn(1, 60)` guard on the same edit. `showOverlay` is called
  for lock modes too, where `waitSeconds` is unused â€” pass the value only on the FRICTION path and
  leave the other three call sites untouched.
- **Rewrite justification**: not a rewrite; one new field, one changed argument, one deleted block.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` (extend the
  existing file), asserting `evaluate(AppForegrounded, state(waitSeconds = 30), â€¦)` emits
  `ShowWaitScreen(waitSeconds = 30)` and that `state(waitSeconds = 0)` is clamped to the
  `PerAppLimits` floor rather than emitted as `0`. Fails before the fix (the engine hard-codes 5, and
  `SessionState` has no `waitSeconds` field to set); passes after.
- **Existing test conflict**: `SessionPolicyEngineForegroundTest.no session shows friction wait
  screen with default wait seconds` (lines 207â€“213) asserts
  `cmd.waitSeconds == SessionPolicyEngine.DEFAULT_WAIT_SECONDS`. It must be changed to assert the
  value carried by the state fixture; leaving it as-is would pin the defect. `PolicyTestFixtures.state()`
  gains a `waitSeconds: Int = 5` parameter with a default, so no other test changes.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing. Both resolution paths, the executor branch that drops the payload, the
    TEST-ONLY status of `PerAppLimits.waitSeconds()`, the `WAIT_SECONDS_RANGE` location and the
    global-only repair were each read directly.
  - *Procedure*: the trigger steps optionally confirm on device; not required.
  - *Severity effect*: none.
- **Build scope**: everyday-build
- **Constitution**: II (strategy selection re-derived at the consumer instead of centralised in the
  decision core)
- **Affected surfaces**: `PerAppLimits.waitSeconds()` (`PerAppLimits.kt:15`, TEST-ONLY â€” this is what
  keeps it dead); `ConfigValidator.WAIT_SECONDS_RANGE` (`ConfigValidator.kt:15`, global-only
  enforcement at `GrayzoneApplication.kt:181-185`); the same constant `5` declared three times as
  `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` (`:10`), `ConfigValidator.DEFAULT_WAIT_SECONDS`
  (`ConfigValidator.kt:11`) and `OverlayService.DEFAULT_WAIT` (`OverlayService.kt:72`) â€” the
  duplication `evidence/reference-index.md` flags; `StateBackupManager.applyInto` (the unvalidated
  write vector, **T020**); `ui/PerAppSettingsSheet.kt:98-105` (the bounded UI path, **T026**).
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-113 â€” `clampRemainingMs` is dead, and its three tests assert the feature-002 negative-countdown contract against a function production never calls  [Medium]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionExpiryDecision.kt` â†’
  `SessionExpiryDecision.clampRemainingMs()` (lines 52â€“54); tests at
  `app/src/test/java/com/grayzone/app/policy/SessionExpiryDecisionTest.kt` (lines 34â€“47).
- **Issue**: `clampRemainingMs(now, activeUntil) = (activeUntil - now).coerceAtLeast(0L)` is
  documented as the fix for the negative-countdown defect
  (`SessionExpiryDecision.kt:52`, "fixes the negative counter", contract
  `specs/002-fix-timeout-accessibility-bugs/contracts/session-expiry.md`). It has **zero main-source
  callers** â€” confirmed by `evidence/reference-index.md`, which lists it under TEST-ONLY, and by
  reading every consumer of the file. Production never clamps through it; it clamps inline, three
  different ways:

  | Site | Inline mechanism |
  |---|---|
  | `OverlayService.startCountdownNotification()` (`:710-714`) | `val remainingMs = activeUntil - System.currentTimeMillis(); if (remainingMs <= 0) { cancelCountdownNotification(); return }` |
  | `OverlayService.handleSessionExpired()` (`:645-648`) | `ClampCountdown` â†’ `cancelCountdownNotification()` â€” cancels outright, so there is no value left to clamp |
  | `LockPresentation.initialSeconds()` (`LockPresentation.kt:17`) | `((inputs.lockedUntil - inputs.now) / 1000).coerceAtLeast(0)` |

  **The shipped behavior is correct**, and stronger than the helper in the notification case: it
  refuses to post the notification at all rather than posting a zero. This is materially unlike
  `PerAppLimits.waitSeconds()` (see F-112), where the inline substitute was *weaker*; the audit
  should not report it as the same defect. What *is* defective is the test relationship:
  `SessionExpiryDecisionTest`'s three `clamp*` tests (lines 34â€“47) are the only automated evidence
  for a shipped bug fix, and they exercise a function no production path can reach. **Deleting all
  three inline clamps would leave the suite green.** The user-visible consequence is therefore
  latent rather than current: the negative countdown that feature 002 fixed can regress at any of
  the three real sites without a single test failing.

  This matters more than usual here because `evidence/prior-fix-watchlist.md` records that **6 of 10
  fixes from feature `002-fix-timeout-accessibility-bugs` have no automated test** and this package
  owns the session-expiry decision that feature fixed. The `decide()` half of the contract genuinely
  *is* locked in â€” `handleSessionExpired` calls it at `OverlayService.kt:636` and the eight
  `decide()` tests (lines 51â€“105) cover every branch. The clamp half is not.
- **Trigger condition**:
  1. Confirm the helper is unreachable: `rg -n "clampRemainingMs" app/src/main` returns **only** its
     own declaration at `SessionExpiryDecision.kt:53`.
  2. Delete the guard at `OverlayService.kt:711-714`
     (`if (remainingMs <= 0) { cancelCountdownNotification(); return }`).
  3. Run `./gradlew testDebugUnitTest`. **Observed**: the whole suite, including all three
     `clamp*` tests, still passes â€” the regression guard does not guard the code.
  4. On device, grant a session, then force expiry while the app is backgrounded
     (`adb shell am broadcast -a com.grayzone.app.SESSION_EXPIRED --es package_name <pkg>`) and watch
     the countdown notification render a negative chronometer â€” the exact defect feature 002 closed.
- **Blast radius**:
  - *Callers*: **none in main source.** `evidence/reference-index.md` Â§`SessionExpiryDecision.kt`
    records `clampRemainingMs()` as `**TEST-ONLY** â€” SessionExpiryDecisionTest.kt; OverlayService
    clamps inline`, and independent search across `app/src/main` returns only the declaration. The
    sibling `SessionExpiryDecision.decide()` **is** live, called from
    `OverlayService.handleSessionExpired()` (`OverlayService.kt:636`), itself reached from
    `OverlayService.onStartCommand()` (`:670-672`) via `SessionExpiryReceiver.onReceive()` and the
    `SessionExpiryScheduler` alarm. The three real clamp sites listed above are the surfaces that
    need the coverage.
  - *Process death*: none â€” the helper holds no state. The behavior it protects (the countdown
    notification) is re-derived from prefs after a restart at `OverlayService.kt:158-160`.
  - *Config change*: none.
  - *Foreground/background*: the regression window is specifically the **backgrounded** case â€”
    `ClampCountdown` is the outcome when `isForegroundPackage == false`
    (`SessionExpiryDecision.kt:45-49`), which is when a stale notification is most likely to be the
    only thing the user sees.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none â€” the helper reads and writes nothing. The values it would clamp come from
    `PrefsKeys.ACTIVE_UNTIL + pkg` (`OverlayService.kt:633`).
  - *Release-only*: worth noting, not blocking. `evidence/r8.md` shows R8 removes unreferenced
    members, so `clampRemainingMs` is stripped from release builds while the tests that "cover" it
    run against the debug/JVM classpath. The coverage illusion is therefore strictly a debug-side
    artifact, which is exactly why it is invisible.
- **Fix** (minimal): make the helper live rather than delete it â€” one call site each. Replace
  `OverlayService.kt:710` with
  `val remainingMs = SessionExpiryDecision.clampRemainingMs(System.currentTimeMillis(), activeUntil)`
  (the existing `if (remainingMs <= 0)` guard then reads `== 0L` and is otherwise unchanged), and
  replace `LockPresentation.kt:17`'s body with
  `(SessionExpiryDecision.clampRemainingMs(inputs.now, inputs.lockedUntil) / 1000).toInt()`. Two
  one-line edits; the existing three tests immediately become real regression guards for both sites.
  Deleting the helper instead is the alternative, but it discards a documented contract artifact and
  leaves the three inline clamps untested â€” this fix costs the same and yields coverage.
- **Rewrite justification**: not a rewrite; two expressions substituted.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` (extend the
  existing file), asserting `LockPresentation.initialSeconds(SecondsInputs(now = t, lockedUntil = t - 120_000, waitSeconds = 5)) == 0`.
  This is the one production clamp reachable from a JVM unit test today (`LockPresentation` is pure).
  It fails if the `coerceAtLeast` is removed and passes with it â€” the guard that is currently
  missing. The `OverlayService.startCountdownNotification` clamp is not JVM-testable (it builds a
  `RemoteViews`); its coverage arrives only via the fix above, which routes it through the helper the
  existing tests already exercise. That routing *is* the coverage argument for this finding.
- **Existing test conflict**: none. All three `clamp*` tests
  (`SessionExpiryDecisionTest.kt:34-47`) pass unchanged and become meaningful.
- **Verification**: `confirmed` (evidence tier T1 â€” source read plus reference index).
  - *Missing*: nothing. Zero main-source callers is corroborated twice (reference index +
    independent search), and all three inline clamp sites were read directly.
  - *Procedure*: trigger steps 1â€“3 are a local, non-device confirmation; step 4 optionally
    demonstrates the latent regression on hardware.
  - *Severity effect*: none.
- **Build scope**: everyday-build
- **Constitution**: I (dead code in main source) and II (the single-source-of-truth helper for a
  documented contract is bypassed by three divergent inline implementations)
- **Affected surfaces**: `OverlayService.startCountdownNotification()` (`:710-714`),
  `OverlayService.handleSessionExpired()` (`:645-648`), `LockPresentation.initialSeconds()`
  (`LockPresentation.kt:17`), `OverlayService.kt:944` (a fourth `coerceAtLeast(0)` on the friction
  waited-ms path). Process-gap cross-reference: `evidence/prior-fix-watchlist.md` (6/10 feature-002
  fixes untested) â€” owner **T029** for the test-suite-quality view.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-114 â€” `SessionCommand.DismissOverlay` is a declared command that its only executor ignores, so the engine cannot clear an overlay  [Medium]

> **Disposition**: **withdrawn â€” consolidated into F-005** (`findings-ru04.md`), which owns the same
> `DismissOverlay` no-op at High. Severity pair resolved: High wins (protection overlay stuck). T031.

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionCommand.DismissOverlay` (lines 39â€“40); emitted at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (lines 22, 30, 38, 57, 87); executed at
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’ `OverlayService.executeCommand()`
  (lines 488â€“490).
- **Issue**: `DismissOverlay` is the engine's most-emitted command â€” five of the ten emission sites
  in `evaluate()` produce it, including every "let the user through" decision (kill switch off, on
  break, unmonitored, active session, resumed session). Its declaration concedes that it does
  nothing:

  ```39:40:app/src/main/java/com/grayzone/app/policy/SessionModels.kt
    /** Policy signal that overlays should be clear; OverlayService treats as intentional no-op. */
    object DismissOverlay : SessionCommand()
  ```

  ```488:490:app/src/main/java/com/grayzone/app/service/OverlayService.kt
            is com.grayzone.app.policy.SessionCommand.DismissOverlay -> {
                // Intentional no-op: policy still emits this; leave/lock paths dismiss overlays.
            }
  ```

  The comment's premise â€” "leave/lock paths dismiss overlays" â€” is only partly true. The four real
  callers of `dismissOverlay()` are: the top of `showOverlay()` (`:825`, i.e. showing a *new* overlay
  removes the old one), the skip-button click listener (`:952`), `CountdownFinishContext.dismissOverlay`
  (`:1026`, i.e. a countdown that runs to completion), and `onDestroy()` (`:765`). **None of them is
  reachable from a policy decision that the overlay should now be clear.** So the engine can decide
  "this app should not be blocked" and have no way to act on it: an overlay attached during an
  earlier decision stays attached until the user interacts with it, its countdown finishes, or a
  different overlay replaces it.

  The mechanism is confirmed from source. The user-visible consequence â€” a full-screen
  `TYPE_APPLICATION_OVERLAY` window (`MATCH_PARENT`, `OverlayService.kt:981-987`) remaining over the
  next app â€” is device-observable only, and is scoped in the verification field below. The most
  plausible path: a LOCK overlay is showing for app A; the user switches to an unmonitored app B via
  Recents; `processForegroundAppChanged(B)` runs, the engine returns `DismissOverlay` for B
  (`SessionPolicyEngine.kt:38`, the `!isMonitored` gate), and nothing removes A's window.
  A break started mid-lockout has the same shape via the `isOnBreak` gate (`:30`).
- **Trigger condition**:
  1. Install, grant accessibility + overlay permissions, add exactly one monitored app A with a
     60-minute lockout; leave a second app B unmonitored.
  2. Drive A into lockout (open A, complete a session, let it expire, re-open A) so the "Locked."
     screen is showing with a long countdown.
  3. Without pressing the "â† Leave App" button, open Recents and switch to unmonitored app B.
  4. **Observe** whether the "Locked." overlay is still drawn over B. Capture
     `adb shell dumpsys window windows | grep -i grayzone` to confirm whether the overlay window is
     still attached, and `adb logcat -s Grayzone:*` to confirm `processForegroundAppChanged` ran for
     B (the "Foreground app changed" log at `OverlayService.kt:340-344`).
  5. Repeat with a daily break started from the Grayzone app instead of switching to B, to exercise
     the `isOnBreak` gate.
- **Blast radius**:
  - *Callers*: `SessionCommand.DismissOverlay` is referenced from `policy/SessionPolicyEngine.kt`
    and `service/OverlayService.kt` â€” per `evidence/reference-index.md` Â§`SessionModels.kt`. The
    `OverlayService` reference is the no-op `when` branch at `:488`. `evaluate()`, which emits it,
    is called from `OverlayService.processForegroundAppChanged()` (`:355`) and
    `OverlayService.backgroundApp()` (`:400`); `backgroundApp` is in turn reached from
    `processForegroundAppChanged()` (`:348`) and `handleScreenOff()` (`:421`). The private
    `dismissOverlay()` that would need to be invoked is called from `showOverlay()` (`:825`), the
    skip listener (`:952`), `CountdownFinishContext` (`:1026`) and `onDestroy()` (`:765`).
  - *Process death*: relevant to the fix. `overlayView` is a plain in-memory field
    (`OverlayService.kt:102`, main-thread-confined per `evidence/concurrency-inventory.md`), so a
    process kill drops the reference while the window token may outlive it. A fix must call
    `dismissOverlay()` (which is null-safe and already catches, `:1036-1043`) rather than track
    additional state.
  - *Config change*: rotation re-lays-out the overlay window; a fix that removes the view must be
    idempotent, which `dismissOverlay()` already is (`overlayView?.let { â€¦ }; overlayView = null`).
  - *Foreground/background*: the command is executed on `Dispatchers.IO` (`executeCommand` is a
    `suspend fun` whose callers are on C2/IO â€” traced in `evidence/concurrency-inventory.md` Â§"the
    lint correction"), while `WindowManager.removeView` must run on Main. A fix must hop, exactly as
    the four `showOverlay` branches already do (`:492`, `:497`, `:502`, `:507`).
  - *Cross-process*: not reachable from `:a11y`. The a11y service triggers the transition via
    broadcast/`startForegroundService` but holds no overlay state
    (`evidence/reference-index.md` process topology).
  - *Persistence*: none â€” no prefs, Room or file writes on this path.
  - *Release-only*: not applicable.
- **Fix** (minimal): give the command an implementation:

  ```kotlin
  is com.grayzone.app.policy.SessionCommand.DismissOverlay -> {
      serviceScope.launch(Dispatchers.Main) { dismissOverlay() }
  }
  ```

  This mirrors the four sibling branches exactly (same scope, same dispatcher hop) and is safe
  when no overlay is attached, because `dismissOverlay()` is already null-guarded and
  exception-guarded (`:1034-1044`). Do **not** instead delete `DismissOverlay` from the sealed class:
  the engine needs a way to express "clear the screen", and deleting it would make the five
  `let-them-pass` decisions indistinguishable from "no decision".
  One ordering caveat the fix must respect: `showOverlay()` already calls `dismissOverlay()` at
  `:825`, so a command list containing both `DismissOverlay` and a `Show*` command must not dismiss
  *after* showing. The engine never emits both â€” every `DismissOverlay` site returns immediately
  (`:23`, `:31`, `:39`, `:58`, `:88`) â€” so list ordering is already safe; add a test that pins it.
- **Rewrite justification**: not a rewrite; one empty branch body filled in.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` (extend the
  existing file), asserting that no command list from `evaluate()` ever contains both
  `DismissOverlay` and any `Show*` command, across the full gate matrix. This is the invariant the
  executor fix depends on; it fails if a future edit adds a fall-through emission and passes today.
  The window-removal behavior itself is not JVM-testable (`OverlayService` has no unit tests per
  `evidence/reference-index.md`) and is covered by the device procedure below.
- **Existing test conflict**: none. Every current assertion checks that `DismissOverlay` is
  *emitted* (`SessionPolicyEngineForegroundTest` lines 38, 54, 64, 91, 99, 183;
  `SessionPolicyEngineTest` lines 33, 42, 56, 106, 124, 204); none asserts anything about its
  execution.
- **Verification**: `needs-verification` (evidence tier T4) â€” split deliberately.
  **Confirmed without a device**: the command is emitted from five sites, its executor branch is
  empty, and none of the four `dismissOverlay()` call sites is reachable from a policy decision.
  Those are source facts, read directly.
  - *Missing*: on-device observation of whether an already-attached overlay window visibly persists
    over the next app after an ignored `DismissOverlay`. The Android window compositor's behavior for
    a `TYPE_APPLICATION_OVERLAY` window whose owning service is still alive but whose target app has
    changed cannot be settled from source.
  - *Procedure*: trigger steps 1â€“5 above on any API 29+ device, with the `dumpsys window windows`
    and `logcat` captures named in step 4. Run both the app-switch variant (step 3) and the
    daily-break variant (step 5).
  - *Severity effect*: if the overlay visibly persists over an unrelated app â†’ **High** (user-visible,
    protection state misrepresented, recovery requires the skip button or waiting out a countdown
    that no longer applies). If the platform or an unexamined path removes it â†’ **Low** (an
    unimplemented command in the policy contract; maintainability only). Ranked **Medium** pending
    the observation, which is the midpoint and does not pre-judge either outcome.
- **Build scope**: everyday-build
- **Constitution**: II (the decision core emits a command the executor does not implement, so the
  policy contract and the runtime behavior disagree by declaration)
- **Affected surfaces**: `OverlayService.executeCommand()` (`:485-537`, **T018**),
  `OverlayService.dismissOverlay()` (`:1034-1044`, **T018**), the four `showOverlay` command branches
  (`:491-510`), `service/overlay/*Presentation.onCountdownFinished` (**T023**). Related but distinct:
  `findings.md` C1 (`OverlayService.kt:989-994`, `addView` failure leaves no overlay at all) is the
  mirror-image defect on the *attach* side.
- **Observed against**: `557a963`
- **Disposition**: **withdrawn â€” consolidated into F-005** (`findings-ru04.md`). High wins over Medium.

---

### F-115 â€” `SessionState.defaultSessionMins` is never read by the engine; the session-grant decision lives outside the decision core  [Low]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionState.defaultSessionMins` (line 23); populated at
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’ `OverlayService.buildSessionState()`
  (lines 449â€“453); the decision it exists for is made at `OverlayService.markAppUnlocked()`
  (lines 1047â€“1100).
- **Issue**: `SessionState` carries `defaultSessionMins` and `defaultLockoutMins` as a pair.
  `SessionPolicyEngine.evaluate()` reads `defaultLockoutMins` once (`:80`, computing the resumed
  session's `lockedUntil`) and **never reads `defaultSessionMins` at all** â€” search across
  `SessionPolicyEngine.kt` returns zero occurrences. The field is nonetheless computed on every
  evaluation through the validated helper:

  ```449:453:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        val defaultSessionMins = com.grayzone.app.data.PerAppLimits.sessionMinutes(
            hasCustom = hasCustom,
            perApp = prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + pkg, 10),
            global = prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        )
  ```

  The decision it would drive â€” how long a session actually lasts â€” is made instead in
  `markAppUnlocked()`, reached from the friction countdown's completion callback
  (`FrictionPresentation.onCountdownFinished` â†’ `CountdownFinishContext.markAppUnlocked`,
  `OverlayService.kt:1025`), entirely outside the engine. That function re-derives the value inline:

  ```1051:1056:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        val hasCustom = prefs.getBoolean(PrefsKeys.PER_APP_HAS_CUSTOM + packageName, false)
        val sessionMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_SESSION_MINUTES + packageName, 10) else prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
        val lockoutMins = if (hasCustom) prefs.getInt(PrefsKeys.PER_APP_LOCKOUT_MINUTES + packageName, 60) else prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)

        // Validate session duration: must be between 1 minute and 24 hours
        val validSessionMins = sessionMins.coerceIn(1, 24 * 60)
  ```

  **Important scoping â€” the two derivations agree.** `PerAppLimits.sessionMinutes()` applies
  `coerceIn(1, 24 * 60)` (`PerAppLimits.kt:10`) and `markAppUnlocked` applies the identical
  `coerceIn(1, 24 * 60)` at `:1056`; likewise `lockoutMinutes()`'s `coerceIn(15, 24 * 60)`
  (`PerAppLimits.kt:13`) matches `:1065`. So unlike F-112, **there is no value divergence and no
  user-visible wrong behavior** â€” this is duplication, not disagreement, and reporting it as a
  bypass would overstate it. The defect is structural: the app's single most consequential write
  (`ACTIVE_UNTIL` and `LOCKED_UNTIL`, `:1093-1094`) is made by the presentation layer's countdown
  callback rather than by the decision core, and the state object the core receives carries a field
  for it that nothing reads. A maintainer changing session-length policy must find and change two
  places, and the compiler will not point at the second one.
- **Trigger condition**:
  1. `rg -n "defaultSessionMins" app/src/main` â€” returns exactly two hits: the declaration
     (`SessionModels.kt:23`) and the population site (`OverlayService.kt:449`, `:478`). Zero reads in
     `SessionPolicyEngine.kt`.
  2. Change `PerAppLimits.sessionMinutes()`'s clamp floor from 1 to 5 and rebuild. **Observe** that
     granted session lengths are unchanged on device, because the grant path never consults it â€”
     the change silently has no effect.
  3. Confirm by opening a monitored app, completing the friction countdown, and checking the granted
     `ACTIVE_UNTIL`:
     `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml | grep active_until`.
- **Blast radius**:
  - *Callers*: `SessionState.{â€¦, defaultSessionMins, defaultLockoutMins, â€¦}` is indexed as a grouped
    constructor-property row referenced from `policy/SessionPolicyEngine.kt` (`evaluate()`) and
    `service/OverlayService.kt` â€” per `evidence/reference-index.md` Â§`SessionModels.kt`. Narrowed by
    direct search: `defaultSessionMins` has **zero** reads in `SessionPolicyEngine.kt`;
    `defaultLockoutMins` has exactly one (`:80`). `PerAppLimits.sessionMinutes()` is called only from
    `OverlayService.buildSessionState()` (`:449`) â€” per `evidence/reference-index.md`
    Â§`PerAppLimits.kt`. `markAppUnlocked()` is private, invoked only through
    `CountdownFinishContext.markAppUnlocked` (`OverlayService.kt:1025`), consumed by
    `FrictionPresentation.onCountdownFinished` (`FrictionPresentation.kt:16`).
  - *Process death*: none â€” both derivations read the same prefs and are recomputed on every use.
  - *Config change*: none.
  - *Foreground/background*: `markAppUnlocked` runs on `Dispatchers.Main` (from the countdown job J2,
    `evidence/concurrency-inventory.md`), while `buildSessionState` runs on IO. A fix that routes the
    grant through the engine must not move prefs writes onto Main that are currently on IO, nor the
    reverse â€” `markAppUnlocked`'s `prefs.edit()â€¦apply()` (`:1091-1095`) is already on Main, which is
    B9 in `evidence/concurrency-inventory.md` and is **T018**'s to address, not this finding's.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: `markAppUnlocked` writes `ACTIVE_UNTIL + pkg`, `LOCKED_UNTIL + pkg` and removes
    `REMAINING_MILLIS + pkg` (`:1091-1095`); `buildSessionState` only reads. A fix must not duplicate
    the write.
  - *Release-only*: not applicable â€” `defaultSessionMins` is a constructor property of a class R8
    keeps, so it is not stripped; there is no release-vs-debug divergence.
- **Fix** (minimal, two options â€” the second is preferred but larger, so the first is what this
  finding proposes): **delete `defaultSessionMins` from `SessionState`** and its population at
  `OverlayService.kt:449-453` and `:478`. It carries no information any consumer uses, and removing
  it makes the engine's actual inputs honest. Four lines deleted, one constructor argument removed,
  one default added to `PolicyTestFixtures.state()` removed. This does not fix the structural split
  â€” it just stops the model from lying about it â€” and that is the correct minimal scope for a Low.
  The larger option, routing session granting through the engine as a `GrantSession` command, is
  recorded as an improvement for the punch list rather than proposed here, because it moves a
  main-thread prefs write and touches `FrictionPresentation`, `CountdownFinishContext` and
  `markAppUnlocked` â€” a change that must be sequenced after **T018**'s threading findings.
- **Rewrite justification**: the proposed fix is a deletion, not a rewrite. The larger routing
  option *would* be a restructure, which is exactly why it is not proposed as the minimal fix.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/data/PerAppLimitsTest.kt`
  (extend the existing file), asserting that `PerAppLimits.sessionMinutes()` and the clamp applied at
  `OverlayService.markAppUnlocked` agree at both boundaries: `sessionMinutes(false, 0, 0) == 1` and
  `sessionMinutes(false, 0, 100_000) == 1440`. This pins the equivalence that makes this finding Low
  rather than Medium, so a future divergence in either clamp is caught. Fails if either clamp is
  changed in isolation; passes today.
- **Existing test conflict**: `PolicyTestFixtures.state()` declares `defaultSessionMins: Int = 10`
  (`PolicyTestFixtures.kt:21`) and `SessionPolicyEngineTest.defaultState` passes it positionally
  (`SessionPolicyEngineTest.kt:22`). Deleting the field requires removing both, plus the
  `defaultSessionMins = 10` line in `SessionPolicyEngineForegroundTest`'s fixture usage â€” mechanical,
  no assertion changes, because no test asserts anything about the field's effect.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing. Zero engine reads, both clamp expressions, and the callback chain into
    `markAppUnlocked` were each read directly.
  - *Procedure*: trigger step 1 is a one-command local confirmation.
  - *Severity effect*: none.
- **Build scope**: everyday-build
- **Constitution**: I (unused field in main source) and II (the session-grant decision is not
  centralised in the decision core)
- **Affected surfaces**: `OverlayService.markAppUnlocked()` (`:1047-1100`, **T018**),
  `OverlayService.recordFrictionSkip()` (`:1102-1109`, which re-derives `lockoutMins` inline a
  *third* time and, unlike the other two, applies **no clamp** before passing it to
  `StreakManager.recordBlockedSession` â€” a stats-only path owned by **T018**/**T020**),
  `FrictionPresentation.onCountdownFinished()` (`FrictionPresentation.kt:15-18`, **T023**),
  `PerAppLimits.sessionMinutes()` (`PerAppLimits.kt:9`).
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-116 â€” `SessionState` can represent an out-of-range `defaultLockoutMins`, and the engine's Int-only arithmetic overflows on it  [Low]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `SessionState.defaultLockoutMins` (line 24, declared as a bare `Int`); consumed at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (line 80).
- **Issue**: The paused-session resume path computes the new lockout deadline as:

  ```80:80:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
                        val newLockedUntil = newActiveUntil + (state.defaultLockoutMins * 60 * 1000L)
  ```

  Kotlin evaluates this left-associatively: `(defaultLockoutMins * 60)` is `Int * Int`, so the
  multiplication happens in **32-bit arithmetic** and only the final `* 1000L` widens to `Long`. For
  `defaultLockoutMins > 35_791_394` the inner product overflows and can go negative, producing a
  `newLockedUntil` **earlier** than `newActiveUntil` â€” a session that resumes with its lockout
  already expired, so the penalty box silently does not exist for that app. The consequence would be
  a protection loss with no crash and no user signal, which is the Grayzone-calibrated Critical
  shape.

  **It is not reachable in production today, and that is why this is Low.** The sole production
  constructor of `SessionState` is `OverlayService.buildSessionState()` (`:470-482`), which sources
  the field through `PerAppLimits.lockoutMinutes()`:

  ```12:13:app/src/main/java/com/grayzone/app/data/PerAppLimits.kt
    fun lockoutMinutes(hasCustom: Boolean, perApp: Int, global: Int, fallback: Int = 60): Int =
        (if (hasCustom) perApp else global).coerceIn(15, 24 * 60).takeIf { it > 0 } ?: fallback
  ```

  `coerceIn(15, 1440)` bounds the value at 1440, and `1440 * 60 = 86_400` is nowhere near
  `Int.MAX_VALUE`. **`PerAppLimits.lockoutMinutes()` is what makes the overflow unreachable, and it
  deserves explicit credit** â€” it is the live counterpart of the TEST-ONLY `waitSeconds()` that
  F-112 reports. The defect that remains is a defence-in-depth gap: the *model* accepts a value the
  *engine* cannot safely multiply, so correctness depends entirely on a caller's discipline, with
  nothing in `policy/` expressing the constraint. Any second construction site â€” a new caller, a
  test-derived production path, or a future `SessionState` built from the backup-restore payload â€”
  reintroduces the overflow for free. The related range constants (`ConfigValidator.LOCKOUT_MINUTES_RANGE = 15..300`,
  `ConfigValidator.kt:17`) live in a different package and are enforced only on the global key at
  startup (`GrayzoneApplication.kt:181-185`); per-app values are not validated at all, which
  `findings.md` already records as a confirmed High lead. The same shape applies to
  `SessionCommand.ShowWaitScreen.waitSeconds: Int` (`SessionModels.kt:44`), where
  `ConfigValidator.WAIT_SECONDS_RANGE = 3..30` exists but is not enforced at construction â€” inert
  today only because nothing consumes the field (F-112).
- **Trigger condition**:
  1. Confirm the arithmetic in isolation â€” in a JVM scratch test:
     `assertTrue(Int.MAX_VALUE * 60 * 1000L < 0L)` passes, demonstrating the widening order.
  2. Confirm the production guard holds: `PerAppLimits.lockoutMinutes(hasCustom = true, perApp = Int.MAX_VALUE, global = 60)`
     returns `1440`, so `buildSessionState` cannot produce the overflowing value.
  3. Demonstrate the latent path: construct
     `SessionState(â€¦, defaultLockoutMins = Int.MAX_VALUE, sessionRemainingMs = 5_000L, â€¦)` and call
     `evaluate(AppForegrounded, â€¦)`. **Observe** the emitted
     `UpdateState.newLockedUntil < UpdateState.newActiveUntil` â€” a lockout that has already elapsed
     at the instant it is written.
- **Blast radius**:
  - *Callers*: `SessionState.defaultLockoutMins` is read from exactly one place in main source â€”
    `SessionPolicyEngine.evaluate()` (`:80`). Per `evidence/reference-index.md` Â§`SessionModels.kt`,
    `SessionState`'s constructor properties are referenced from `policy/SessionPolicyEngine.kt` and
    `service/OverlayService.kt`; the only production construction site is
    `OverlayService.buildSessionState()` (`:470`), which feeds it from
    `PerAppLimits.lockoutMinutes()` (`:454-458`). Test constructions are
    `PolicyTestFixtures.state()` (`:22`, default 60) and `SessionPolicyEngineTest.defaultState`
    (`:23`, 60). The `UpdateState.newLockedUntil` this computes is consumed by
    `OverlayService.executeCommand()` (`:517`), which persists it to `PrefsKeys.LOCKED_UNTIL + pkg`.
  - *Process death*: the overflowed value would be **persisted** (`:517`, `editor.commit()` at
    `:531`), so it survives process death and is re-read by every later `buildSessionState`,
    `isAnyAppLocked` and `LockPresentation.initialSeconds`. A fix that clamps only in memory would
    not repair an already-written value.
  - *Config change*: none.
  - *Foreground/background*: the path is the `AppForegrounded` paused-resume branch
    (`SessionPolicyEngine.kt:76-97`), so it runs on an app switch into a monitored app that has a
    saved remainder. It is not reachable from the `AppBackgrounded` branch.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: yes â€” `PrefsKeys.LOCKED_UNTIL + pkg` (`OverlayService.kt:517`). Also snapshotted
    by `StateBackupManager` (`prefs.all`, `:167`) and restored without validation
    (`applyInto`, `:229-251`), so a bad value would round-trip through backup.
  - *Release-only*: not applicable â€” arithmetic semantics are identical under R8.
- **Fix** (minimal): widen the multiplication so it cannot overflow regardless of input â€” change
  `SessionPolicyEngine.kt:80` to `(state.defaultLockoutMins.toLong() * 60_000L)`. One expression,
  no behavior change for any in-range value, and it removes the dependency on caller discipline.
  Optionally reinforce it at the model boundary with an `init` block on `SessionState`
  (`require(defaultLockoutMins in 1..(24 * 60))`) â€” but that turns a data-corruption bug into a
  crash on a startup path, which for this app is a worse failure than a clamped value, so the
  `toLong()` widening plus the existing `PerAppLimits` clamp is the right minimal answer.
- **Rewrite justification**: not a rewrite; one expression widened.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` (extend the
  existing file), asserting that `evaluate(AppForegrounded, state(sessionRemainingMs = 5_000L, defaultLockoutMins = Int.MAX_VALUE), now, APP)`
  emits an `UpdateState` whose `newLockedUntil > newActiveUntil`. Fails before the fix
  (`newLockedUntil` is less than `newActiveUntil` because of the 32-bit wrap); passes after. This
  sits directly beside the existing `resume with overflowing remainder is treated as clock skew`
  test (line 195), which already covers the *other* overflow on this same code path â€” the pair makes
  the resume branch's arithmetic fully pinned.
- **Existing test conflict**: none. `SessionPolicyEngineForegroundTest.paused resume respects a
  custom lockout duration` (lines 186â€“192) uses `defaultLockoutMins = 15` and is unaffected by the
  widening; `paused session resumes with new active window, lockout and cleared remainder`
  (lines 174â€“184) uses 60 and computes its expectation as `now + remaining + lockoutMs` with
  `lockoutMs = 60 * 60 * 1000L` â€” an identical result under `toLong()` arithmetic.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing. The widening order, the sole production construction site, and the
    `PerAppLimits` clamp that bounds it were each read directly. Unreachability today is a positive
    claim, established by enumerating construction sites from the reference index and confirming
    there is exactly one in main source.
  - *Procedure*: trigger steps 1â€“3 are local JVM checks; no device needed.
  - *Severity effect*: none. **Low is the correct rank precisely because the caller's clamp makes it
    unreachable** â€” reporting an unreachable overflow as Critical would be severity inflation. It
    would rise to High if a second `SessionState` construction site is ever added without the clamp.
- **Build scope**: everyday-build
- **Constitution**: II (a safety constraint that exists elsewhere in the codebase is not expressed in
  the type or the decision core, leaving correctness dependent on one caller)
- **Affected surfaces**: `SessionCommand.ShowWaitScreen.waitSeconds: Int` (`SessionModels.kt:44`,
  same unconstrained-`Int` shape, inert per F-112); `ConfigValidator.LOCKOUT_MINUTES_RANGE` and
  `WAIT_SECONDS_RANGE` (`ConfigValidator.kt:15-17`, global-key enforcement only);
  `PerAppLimits.lockoutMinutes()` (`PerAppLimits.kt:12`, the guard that makes this Low â€” a credited
  positive); `DailyBudget.remainingMs` (`DailyBudget.kt:19`) and `DailyBudget.shouldShowBudgetLock`
  (`:64`) contain the same `budgetMins * 60 * 1000L` Int-first shape and are **T020**'s to assess;
  `OverlayService.kt:1089` uses the same expression but on an already-`coerceIn`ed value.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-117 â€” `AppEvent.TimerTick` has no production producer and an empty handler branch  [Low]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionModels.kt` â†’
  `AppEvent.TimerTick` (line 9); handled at
  `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’ `SessionPolicyEngine.evaluate()`
  (lines 135â€“138).
- **Issue**: The sealed event hierarchy declares a third variant that nothing in main source ever
  constructs, and the engine's handler for it is an empty branch carrying a "not yet" comment:

  ```135:138:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
            is AppEvent.TimerTick -> {
                // Timer events (e.g. countdown finished) handled elsewhere for now, 
                // but engine could process them.
            }
  ```

  `evidence/reference-index.md` Â§`SessionModels.kt` records it explicitly: *"test:
  `...BackgroundTest.kt`; **no main-source producer** â€” `OverlayService` never emits `TimerTick`"*.
  Independent search confirms the only construction is `SessionPolicyEngineBackgroundTest.kt:123`.
  The user-visible consequence is nil today; the cost is that the decision core advertises a
  timer-driven decision path it does not have, and the comment's "handled elsewhere" is the
  load-bearing claim â€” the work it refers to (the countdown job J2 at `OverlayService.kt:1004-1031`
  and the `SessionExpiryScheduler` alarm) is real, but a reader inspecting the engine to understand
  timer behavior finds an empty branch and no pointer to either. The `when` is also exhaustive over
  the sealed class, so removing the variant is a compile-checked, zero-risk deletion.
- **Trigger condition**:
  1. `rg -n "TimerTick" app/src/main` â€” returns exactly two hits: the declaration
     (`SessionModels.kt:9`) and the empty handler (`SessionPolicyEngine.kt:135`). Zero constructions.
  2. `rg -n "TimerTick" app/src/test` â€” returns `SessionPolicyEngineBackgroundTest.kt:123`, the one
     test keeping it alive.
  3. Delete the variant and its branch; the project compiles (the `when` remains exhaustive over the
     two remaining variants) after removing the single test.
- **Blast radius**:
  - *Callers*: `AppEvent.TimerTick` is referenced from `policy/SessionPolicyEngine.kt` only in main
    source â€” per `evidence/reference-index.md` Â§`SessionModels.kt`, which annotates it **"no
    main-source producer"**. Its parent `AppEvent` is referenced from
    `policy/SessionPolicyEngine.kt` (5 sites) and `service/OverlayService.kt` (2 sites: `:356`
    `AppForegrounded`, `:401` `AppBackgrounded`) â€” neither constructs `TimerTick`. Test caller:
    `SessionPolicyEngineBackgroundTest.timer tick produces no commands` (line 121).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none.
  - *Release-only*: worth a note. `evidence/r8.md` confirms R8 removes unreferenced classes, so
    `AppEvent$TimerTick` is stripped from release builds while remaining in debug â€” a divergence
    with no behavioral effect (nothing constructs it in either), recorded so it is not misread as an
    R8 finding.
- **Fix** (minimal): delete `AppEvent.TimerTick` (`SessionModels.kt:9`) and its empty branch
  (`SessionPolicyEngine.kt:135-138`). If the intent to route timer events through the engine is to
  be preserved, replace the comment with a pointer to where the behavior actually lives â€” the
  countdown job (`OverlayService.kt:1004-1031`) and `SessionExpiryScheduler` â€” rather than leaving a
  variant that implies it is already plumbed. Deletion is the smaller change and is compile-checked.
- **Rewrite justification**: not a rewrite; a deletion of nine lines across two files.
- **Test to add**: none required â€” this finding *removes* a test rather than adding one. If the
  variant is kept instead of deleted, no test is warranted either, because there is no behavior to
  assert.
- **Existing test conflict**: `SessionPolicyEngineBackgroundTest.timer tick produces no commands`
  (lines 121â€“125) must be deleted alongside the variant. It is the only test affected, and it
  asserts an empty command list from an event production never emits â€” it has no regression value.
- **Verification**: `confirmed` (evidence tier T1 â€” source read plus reference index).
  - *Missing*: nothing. Zero main-source producers is corroborated twice (reference index +
    independent search).
  - *Procedure*: trigger steps 1â€“3 are local.
  - *Severity effect*: none. Low is correct per the contract's rule that dead code is Low unless
    actively reachable and wrong; this is neither reachable nor wrong.
- **Build scope**: everyday-build
- **Constitution**: I (dead declaration in main source)
- **Affected surfaces**: none beyond the two files. Grouped for the dead-code inventory (**T064**)
  alongside the 23 UNREFERENCED and 17 TEST-ONLY declarations in
  `evidence/reference-index.md`; note this one is a *sealed-class variant*, which that inventory's
  declaration-level rows do not currently capture.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-118 â€” Unreachable `else` branch in the `AppBackgrounded` pause path  [Low]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’
  `SessionPolicyEngine.evaluate()`, `AppEvent.AppBackgrounded` branch (lines 118â€“132).
- **Issue**: The pause path guards on `hasActiveSession(now)` and then re-tests the same condition
  in a different form:

  ```118:132:app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt
                if (state.hasActiveSession(now)) {
                    val remainingMs = state.activeUntil - now
                    if (remainingMs > 0) {
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            newSessionRemainingMs = remainingMs,
                            clearActiveSession = true
                        ))
                    } else {
                        commands.add(SessionCommand.UpdateState(
                            packageName = state.packageName,
                            clearActiveSession = true
                        ))
                    }
                }
  ```

  `hasActiveSession(now)` is `now < activeUntil` (`SessionModels.kt:30`), which is exactly
  `activeUntil - now > 0` for any non-overflowing pair. The outer guard therefore implies the inner
  condition, and the `else` at `:126-131` **cannot execute**. (The one arithmetic escape â€”
  `activeUntil - now` overflowing when `activeUntil` is near `Long.MAX_VALUE` and `now` is negative â€”
  is not producible: `activeUntil` is written from `System.currentTimeMillis() + grantedSessionMs`
  where `grantedSessionMs â‰¤ 24 h` in ms, `OverlayService.kt:1075-1088`.) The user-visible
  consequence is nil; the cost is that the branch reads as a real defensive case, so a maintainer
  could "fix" a bug by editing a body that never runs and conclude the fix did not work. It is also
  untestable by construction, which is why the otherwise-thorough
  `SessionPolicyEngineBackgroundTest` has no case for it.
- **Trigger condition**:
  1. Attempt to reach the branch from a unit test: any `SessionState` with
     `hasActiveSession(now) == true` necessarily has `activeUntil - now > 0`, so no fixture value
     selects the `else`. This impossibility *is* the observation.
  2. Confirm by instrumenting: insert `error("unreachable")` in the `else` body at `:126` and run
     `./gradlew testDebugUnitTest`. **Observe** the full suite passes â€” including the seven
     `AppBackgrounded` tests in `SessionPolicyEngineBackgroundTest` and the three in
     `SessionPolicyEngineTest` â€” proving no path enters it.
- **Blast radius**:
  - *Callers*: reached only through `SessionPolicyEngine.evaluate()`, whose main-source callers are
    `OverlayService.processForegroundAppChanged()` (`OverlayService.kt:355`) and
    `OverlayService.backgroundApp()` (`:400`) â€” per `evidence/reference-index.md`
    Â§`SessionPolicyEngine.kt`. `backgroundApp()` is itself reached from
    `processForegroundAppChanged()` (`:348`) and `handleScreenOff()` (`:421`). The `UpdateState`
    command the dead branch would emit is consumed by `OverlayService.executeCommand()` (`:514-532`).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: the enclosing branch is the app-backgrounding path, exercised on every
    app switch away from a monitored app and on every screen-off. The *live* sibling branch
    (`:120-125`) is what actually pauses the session; only the `else` is dead.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: the live sibling writes `PrefsKeys.REMAINING_MILLIS + pkg` and removes
    `ACTIVE_UNTIL`/`LOCKED_UNTIL` (`OverlayService.kt:518-521`); the dead branch would write only
    the removals. No persistence change from the fix.
  - *Release-only*: not applicable â€” R8 may fold the branch, but the observable behavior is
    identical in both build types because the branch never executes in either.
- **Fix** (minimal): collapse the inner conditional. Replace lines 119â€“131 with the single live
  case, keeping the local for readability:

  ```kotlin
  val remainingMs = state.activeUntil - now
  commands.add(SessionCommand.UpdateState(
      packageName = state.packageName,
      newSessionRemainingMs = remainingMs,
      clearActiveSession = true
  ))
  ```

  Thirteen lines become six, behavior is bit-identical, and the remaining code states the invariant
  it actually relies on.
- **Rewrite justification**: not a rewrite; a dead branch is removed and the live branch is left
  unchanged.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineBackgroundTest.kt` (extend the
  existing file), asserting that backgrounding with `activeUntil = now + 1` emits an `UpdateState`
  with `newSessionRemainingMs == 1L` and `clearActiveSession == true`. This pins the boundary the
  collapsed code now relies on (the smallest active session still produces a positive remainder), so
  the simplification cannot silently change behavior at the edge. It passes both before and after
  the fix, which is correct for a refactor-safety test.
- **Existing test conflict**: none. `SessionPolicyEngineBackgroundTest.active session with zero usage
  duration still pauses the remainder` (lines 87â€“96) and `usage inside an active session is recorded
  as not blocked and pauses remainder` (lines 61â€“73) both exercise the live branch and are
  unaffected. `backgrounding after the session expired does not pause anything` (lines 98â€“105)
  confirms the outer guard's `false` case.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing. The implication `now < activeUntil â‡’ activeUntil - now > 0` is arithmetic,
    and the overflow escape was excluded by reading the write site (`OverlayService.kt:1075-1088`).
  - *Procedure*: trigger step 2 is a local, non-device confirmation.
  - *Severity effect*: none.
- **Build scope**: everyday-build
- **Constitution**: I (unreachable code in main source)
- **Affected surfaces**: none. The dead branch is self-contained; no other file changes.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-119 â€” `executeCommand` re-samples the clock, so commands execute against a different instant than they were decided against  [Low]

- **Review unit**: RU-03 (`policy`)
- **Location**: `app/src/main/java/com/grayzone/app/policy/SessionPolicyEngine.kt` â†’
  `SessionPolicyEngine.evaluate()` (line 17, the `now: Long` parameter that defines the contract);
  broken at `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.executeCommand()` (lines 486, 512).
- **Issue**: The engine's central design property is that it is a pure function of an
  explicitly-supplied instant â€” `evaluate(event, state, now, appName)` â€” which is what makes it
  fully unit-testable off-device and immune to timezone and locale coupling. Its only consumer
  honours that for the decision and then discards it for the execution. `processForegroundAppChanged`
  samples the clock once at `:334` and passes it to `evaluate` at `:358`, but `executeCommand`
  samples it **again**:

  ```485:486:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    private suspend fun executeCommand(cmd: com.grayzone.app.policy.SessionCommand, prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
  ```

  and uses the second sample to reconstruct the session's start time:

  ```511:513:app/src/main/java/com/grayzone/app/service/OverlayService.kt
            is com.grayzone.app.policy.SessionCommand.RecordUsage -> {
                logUsageAndCheckBudget(cmd.packageName, now - cmd.durationMs, cmd.wasBlocked)
            }
  ```

  `cmd.durationMs` was computed against the *first* `now` (`backgroundApp` at `:399`,
  `now - sessionStartTime`), so `now - cmd.durationMs` recovers `sessionStartTime` only if the two
  samples are equal. They are not: the commands are executed in a `forEach` loop (`:366-374`,
  `:406`), each iteration runs on `Dispatchers.IO`, and the `UpdateState` branch performs a
  synchronous `editor.commit()` (`:531`) â€” an fsync-bearing write â€” before later commands run. The
  drift is the accumulated wall time of the preceding commands, which pushes the recorded session
  start later and shrinks the logged usage duration correspondingly. The magnitude is small
  (milliseconds to tens of milliseconds per transition) and there is no crash and no lock-state
  error, but it accumulates into `PrefsKeys.DAILY_USED_MILLIS + pkg` on every single app switch,
  systematically under-counting usage against the daily budget in the direction that favours *more*
  screen time.

  The structural cost is larger than the numeric one: the policy contract's whole point is that
  `now` is an input, and re-sampling inside the executor means the engine's testable determinism
  does not extend to the behavior the user actually gets.
- **Trigger condition**:
  1. Add a temporary log at `OverlayService.kt:486` emitting
     `now - <the now passed to evaluate>` for each command in the loop.
  2. Configure a monitored app with a daily budget (Per-app settings â†’ Daily budget â†’ 30 min) so the
     `RecordUsage` path is exercised.
  3. Switch away from the monitored app after a timed session so `backgroundApp` runs with
     `sessionStartTime > 0`, and read the drift from `adb logcat -s Grayzone:*`. **Observe** a
     non-zero delta, largest when an `UpdateState` command precedes `RecordUsage` in the same list
     (the `commit()` at `:531` runs first).
  4. Compare `daily_used_millis_<pkg>` before and after against wall-clock stopwatch time:
     `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml | grep daily_used`.
- **Blast radius**:
  - *Callers*: `SessionPolicyEngine.evaluate()` is called from
    `OverlayService.processForegroundAppChanged()` (`OverlayService.kt:355`) and
    `OverlayService.backgroundApp()` (`:400`) â€” per `evidence/reference-index.md`
    Â§`SessionPolicyEngine.kt`; those are the only two main-source callers, so the fix has exactly two
    call sites to update. `executeCommand()` is private with the same two callers (`:373`, `:406`).
    `SessionCommand.RecordUsage`, the only command that consumes the re-sampled value, is referenced
    from `policy/SessionPolicyEngine.kt` and `service/OverlayService.kt` â€” per the reference index.
    Downstream: `logUsageAndCheckBudget()` (`:559-623`) â†’ `DailyBudget.accumulateUsedMs`
    (`DailyBudget.kt:26`) and `UsageDao.insertEvent` (`:574`).
  - *Process death*: none â€” no state is held across the gap; the drift is per-transition.
  - *Config change*: none.
  - *Foreground/background*: the drift is largest on the app-switch path, where `backgroundApp` and
    `processForegroundAppChanged` run back-to-back and each executes its own command list. The
    screen-off path (`handleScreenOff` â†’ `backgroundApp`, `:421`) has the same shape with a shorter
    list.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: yes, indirectly â€” the under-counted duration reaches
    `PrefsKeys.DAILY_USED_MILLIS + pkg` via `logUsageAndCheckBudget`'s `commit()`
    (`OverlayService.kt:600`) and a `UsageEvent` row via `UsageDao.insertEvent` (`:574`), so the
    error is durable, not display-only.
  - *Release-only*: not applicable.
- **Fix** (minimal): thread the decided instant through instead of re-sampling. Change
  `executeCommand`'s signature to
  `private suspend fun executeCommand(cmd: SessionCommand, prefs: SharedPreferences, now: Long)`,
  delete the `val now = System.currentTimeMillis()` at `:486`, and pass the existing local `now`
  from `processForegroundAppChanged` (`:334`, already in scope at the `:373` call) and from
  `backgroundApp` (its `now` parameter, already in scope at `:406`). Three lines changed, no new
  state, no threading change â€” and it makes the decision and its execution share one instant, which
  is what the engine's `now` parameter was designed to guarantee.
- **Rewrite justification**: not a rewrite; one parameter added and one local removed.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/DailyBudgetTest.kt` (extend the existing file), asserting
  `DailyBudget.accumulateUsedMs(dateKey, dateKey, previousUsedMs = 0L, durationMs = 5_000L) == 5_000L`
  for the exact duration the engine emits, i.e. that no rounding or re-derivation is expected between
  `RecordUsage.durationMs` and the accumulated total. `OverlayService` itself has no unit tests
  (`evidence/reference-index.md`), so the thread-through cannot be asserted directly here; the device
  procedure above covers it. This test fails if a future change reintroduces a clock read in the
  accumulation path and passes today.
- **Existing test conflict**: none. No test constructs `executeCommand` or asserts anything about the
  execution instant; `SessionPolicyEngineBackgroundTest` asserts only the emitted
  `RecordUsage.durationMs` (lines 56, 66, 80), which the fix does not change.
- **Verification**: `confirmed` (evidence tier T1 â€” source read).
  - *Missing*: nothing for the mechanism. The two independent `System.currentTimeMillis()` samples,
    the `forEach` execution loop, the intervening `commit()`, and the `now - cmd.durationMs`
    reconstruction were each read directly. The *magnitude* of the drift is not claimed as a number â€”
    the trigger procedure exists to measure it, and severity does not depend on it, because the
    finding's rank is set by the structural contract violation rather than by the size of the error.
  - *Procedure*: trigger steps 1â€“4 above, on any API 29+ device.
  - *Severity effect*: none. Low regardless of the measured drift; if measurement ever showed a
    drift large enough to change a budget-lock decision (seconds, not milliseconds), it would be
    re-raised as Medium â€” but that would require the `commit()` at `:531` to block for seconds, which
    `evidence/concurrency-inventory.md` does not support.
- **Build scope**: everyday-build
- **Constitution**: II (the decision core's explicit-time contract is not honoured by its only
  consumer, so the centralised decision and the executed effect are computed from different inputs)
- **Affected surfaces**: `OverlayService.executeCommand()` (`:485-537`, **T018**),
  `OverlayService.logUsageAndCheckBudget()` (`:559-623`, **T018**),
  `DailyBudget.accumulateUsedMs()` (`DailyBudget.kt:26`, **T020**). Related but distinct:
  `evidence/concurrency-inventory.md` R4 (`sessionStartTime` read-modify-write across IO and Main,
  `OverlayService.kt:122`) is a second, independent source of error in the *same* duration value and
  is **T018**'s â€” a fix for one does not fix the other.

- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Credited positive examples

The audit must be able to say what works. This unit is the strongest code read so far, and these
are specific, verifiable properties rather than general praise.

1. **The engine is genuinely pure, and it is the reason E7 has a static answer at all.**
   `SessionPolicyEngine.kt`, `SessionModels.kt` and `SessionExpiryDecision.kt` contain **zero
   `import` statements between them**. No `android.*`, no `Context`, no `SharedPreferences`, no
   `java.util.Calendar`, no `SimpleDateFormat`, no `System.currentTimeMillis()`. Time enters only as
   the `now: Long` parameter of `evaluate()` (`:17`), `decide()` (`:34`) and the three
   `SessionState` predicates (`SessionModels.kt:30-32`). This is exactly the "takes time as a
   parameter" shape the audit asked for, and it is what makes the timezone and DST verdicts
   derivable without a device. It is also why the package carries 6 JVM test files while
   `OverlayService` â€” its only consumer â€” carries none.
2. **Gate ordering is correct, documented with its reasoning, and regression-pinned.** The
   active-session check deliberately precedes the lockout check, and the comment at
   `SessionPolicyEngine.kt:50-55` explains *why* in terms of the concrete bug it prevents
   (`lockedUntil = activeUntil + lockout` makes `isLockedOut` always true mid-session, so checking
   lockout first would show a "1h 3m" countdown while time remained). `SessionPolicyEngineTest.re-foregrounding
   mid-session does not lock out (1h3m regression)` (line 110) pins it by name. This is the standard
   the rest of the codebase's comments should be held to â€” contrast the stale comment at
   `OverlayService.kt:164-166` that asserts the opposite of the manifest.
3. **Boundary semantics are consistent and exhaustively tested.** Both `hasActiveSession` and
   `isLockedOut` use strict `<`, so `now == deadline` means "over" in both cases â€” no off-by-one
   asymmetry between the two. `SessionStatePredicateTest` covers all three predicates at
   one-millisecond-before, exactly-at, after and never-set (12 cases, lines 18â€“80), and
   `SessionPolicyEngineForegroundTest` re-tests the same boundaries through the full engine
   (lines 102â€“140). Expiry exactly at `now`, negative remaining, and `activeUntil` in the past are
   all covered.
4. **The `Long.MAX_VALUE` overflow on the resume path is anticipated, handled, and tested.**
   `SessionPolicyEngine.kt:79` guards the resume with `if (newActiveUntil > now)` and its `else`
   branch clears the invalid remainder rather than trusting it (`:92-95`);
   `SessionPolicyEngineForegroundTest.resume with overflowing remainder is treated as clock skew and
   clears remainder` (line 195) drives it with `sessionRemainingMs = Long.MAX_VALUE`. Very few
   codebases test their own arithmetic overflow.
5. **`PerAppLimits.lockoutMinutes()` and `sessionMinutes()` are live and are what keep F-116
   unreachable.** `OverlayService.buildSessionState:449-458` routes both values through the validated
   helper. This is the correct pattern, and it is precisely what `waitSeconds()` does *not* get
   (F-112) â€” the contrast is the clearest evidence in the audit that the helper approach works when
   it is actually used.
6. **`SessionExpiryDecision.decide()` is a real, live, fully-covered decision function.** It is
   called from `OverlayService.handleSessionExpired()` (`:636`), and
   `SessionExpiryDecisionTest`'s eight `decide` cases (lines 51â€“105) cover every branch:
   `ShowLock` (foreground + within lockout), `ClampCountdown` (both the not-foreground and the
   lockout-elapsed routes), and `NoOp` (not yet expired, no session, disabled, on break). The
   feature-002 lock-at-expiry contract genuinely is locked in â€” it is only the *clamp* half that is
   not (F-113).
7. **The accessibility service re-derives no policy whatsoever.** `GrayzoneAccessibilityService`
   holds no session state, never reads prefs, and communicates only a package name across the
   `:a11y` boundary (`evidence/reference-index.md` process topology; `findings.md` confirmed
   NON-finding, independently re-verified). For the Principle II question "does anything re-derive
   policy inline", the a11y service is a clean pass â€” every re-derivation found (F-112, F-115) is in
   `OverlayService`, in the main process.
8. **The daily-break gate is scoped correctly, with the reasoning written down.**
   `SessionPolicyEngine.kt:26-32` short-circuits only `AppForegrounded`, so usage is still recorded
   on backgrounding and daily budgets stay accurate during a break. Both halves are tested
   (`SessionPolicyEngineBackgroundTest.break does not suppress usage recording on background`,
   line 109; `SessionPolicyEngineForegroundTest.break dismisses over schedule, lockout, budget and
   active session`, line 43).

## Examined and found clean (no defect)

Named specifically, because "we looked and it was fine" is only credible with the behavior named.

| Question examined | Evidence | Outcome |
|---|---|---|
| Zero-length session representable? | `DailyBudget.grantedSessionMs` (`DailyBudget.kt:51-52`) returns `sessionMs` when `budgetRemainingMs <= 0` and `minOf(...)` otherwise, so the grant at `OverlayService.kt:1083-1088` is always â‰¥ 1 ms; the engine's resume path is gated on `sessionRemainingMs > 0` (`:76`). | `no-defect-found` |
| Expiry exactly at `now` | `hasActiveSession` strict `<` (`SessionModels.kt:30`); `SessionStatePredicateTest:24`; `SessionPolicyEngineForegroundTest:103` (falls through to lockout, as intended). | `no-defect-found` |
| Lockout release exactly at `lockedUntil` | `isLockedOut` strict `<` (`:31`); `SessionStatePredicateTest:46`; `SessionPolicyEngineForegroundTest:128` (falls through to a fresh wait screen). | `no-defect-found` |
| Negative `sessionRemainingMs` reaching the resume path | `getNormalizedRemainingMillis` (`Extensions.kt:33-37`) zeroes negatives and â‰¥ 24 h values at `OverlayService.kt:446`; the engine additionally guards `sessionRemainingMs > 0` (`:76`). Two independent guards. | `no-defect-found` |
| `activeUntil` in the past at decision time | Falls through the active-session gate to the lockout gate (`:56` â†’ `:62`). Tested at `SessionPolicyEngineTest:128` and `SessionPolicyEngineForegroundTest:110`. | `no-defect-found` |
| `Long` overflow on `now + sessionRemainingMs` (`:77`) | Guarded by `if (newActiveUntil > now)` (`:79`) with an explicit skew fallback; tested with `Long.MAX_VALUE` (`SessionPolicyEngineForegroundTest:195`). | `no-defect-found` |
| Timezone change mid-session | Unit is timezone-free (zero imports; epoch-millis arithmetic only). Deadlines and the `RTC_WAKEUP` alarm all use epoch millis. | `no-defect-found` |
| DST spring-forward gap / autumn fold inside a session or lockout | Same reason â€” epoch millis is monotone and unambiguous across both; no local-time conversion exists in the unit. | `no-defect-found` |
| Engine branch coverage gaps | All 13 decision branches reachable from production are covered by the 6 test files. The only uncovered branch is the one that **cannot** be reached (F-118); the only covered branch with no production producer is `TimerTick` (F-117). | `no-defect-found` for coverage; the two structural issues are filed separately |
| `SessionState` invariant `lockedUntil >= activeUntil` | Representable-but-violable in the type. In practice the engine always writes both together (`:80-86`) and `markAppUnlocked` writes both together (`:1088-1094`); `clearActiveSession` removes both (`:520-521`). No production path writes one without the other. Recorded as a hazard for future edits, not a defect. | `no-defect-found` (latent hazard noted) |
| `budgetRemainingMs` sentinel overloading | `Long.MAX_VALUE` means "unlimited" (`DailyBudget.kt:10, 17`) and is compared only via `<= 0` in `isBudgetExhausted` (`SessionModels.kt:32`), so the sentinel and a real duration are never confused. Tested at `SessionStatePredicateTest:78`. | `no-defect-found` |
| Principle III (error handling) | `evidence/error-handling-inventory.md:114` lists all three `policy/` files among the 46 files containing **zero** catch blocks and zero I/O boundaries. Pure functions with nothing to fail. | `no-defect-found` |
| Concurrency | `evidence/concurrency-inventory.md` records no scope, thread, executor, handler, alarm or shared mutable state in `policy/`. `SessionPolicyEngine` holds no fields; `SessionState` is an immutable `data class`; `SessionExpiryDecision` is a stateless `object`. `evaluate()` is safe to call from any dispatcher. | `no-defect-found` |
| Cross-process (`:a11y`) exposure | No `policy/` declaration appears in the 11-item `:a11y`-reachable surface (`evidence/reference-index.md`). The package is main-process only. | `no-defect-found` |

## Coverage summary

| File | Lines | What was examined | Findings | Outcome |
|---|---|---|---|---|
| `policy/SessionPolicyEngine.kt` | 143 | All 13 decision branches and their ordering; the `now`-parameter purity contract; the paused-resume arithmetic including both overflow escapes; the clock-skew fallback; `DEFAULT_WAIT_SECONDS` emission and its (non-)consumption; the `Int`-first multiplication at `:80`; the empty `TimerTick` branch; the unreachable `else` at `:126-131`; reachability of every branch from the 6 test files; timezone/DST/forward-clock/backward-clock behavior of every comparison. | F-112, F-116, F-117, F-118, F-119 (F-110/F-111 consumed here) | Findings + credited positives 1, 2, 4, 8 |
| `policy/SessionModels.kt` | 73 | Totality of `SessionState` (all 11 properties, which invalid states are representable, the `lockedUntil >= activeUntil` invariant, the `budgetRemainingMs` sentinel); the three predicates' boundary semantics and clock dependence; totality of `SessionCommand` (all 8 variants, which are consumed and which are ignored); totality of `AppEvent` (all 3 variants, which have production producers); unconstrained `Int` fields against the ranges declared in `ConfigValidator`. | **F-110**, **F-111**, F-112, F-114, F-115, F-116, F-117 | Findings + credited positives 1, 3 |
| `policy/SessionExpiryDecision.kt` | 55 | Every branch of `decide()` against its feature-002 contract (`SE-1/2/3/6`); whether `handleSessionExpired` actually calls it; whether `clampRemainingMs` has any production caller; what production does instead at all three real clamp sites; whether the 11 `SessionExpiryDecisionTest` cases lock in the shipped fix; clock dependence of the two comparisons at `:43` and `:45`. | F-113 (F-110/F-111 also consumed here) | One finding; `decide()` itself is `no-defect-found` â€” credited positive 6 |

**Totals**: 3 files, 271 lines, 10 findings â€” 2 Critical, 3 Medium, 5 Low. 8 credited positive
examples; 13 examined-and-clean behaviors named above. One item is `needs-verification` (F-114's
on-screen outcome only; its mechanism is confirmed). No item was deferred to `needs-verification`
to avoid analysis â€” every clock and DST question is answered from source, as required.

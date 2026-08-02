# Findings — RU-05 `service.overlay` (T023)

**Feature**: `004-android-regression-audit` · **Review unit**: RU-05
(`app/src/main/java/com/grayzone/app/service/overlay/`)
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md) ·
**ID authority**: [`findings.md`](./findings.md) · **Reserved block**: F-095 … F-109
**Baseline**: `557a963`. The five files in this package carry **no uncommitted modifications**
(`git status --porcelain -- app/src/main/java/com/grayzone/app/service/overlay/` is empty); they were
last changed in `0a41a1a` ("Ship premium UI redesign…"). Findings whose evidence also cites
`OverlayService.kt` are recorded against the working tree, because that file *is* modified.

## Scope examined

All five files, in full — 170 lines total:

| File | Lines | Examined |
|---|---|---|
| `OverlayPresentation.kt` | 70 | `SecondsInputs`, `CountdownFinishContext`, `SkipContext`, the `OverlayPresentation` interface (8 members), and `resolve()` — every member, plus every KDoc claim checked against behaviour |
| `FrictionPresentation.kt` | 23 | all 8 overrides |
| `LockPresentation.kt` | 31 | all 8 overrides |
| `BudgetLockPresentation.kt` | 22 | all 8 overrides |
| `ScheduleLockPresentation.kt` | 22 | all 8 overrides |

Consumer side read for reachability and lifecycle (owned by RU-04, cited not re-filed):
`OverlayService.showOverlay()` (`:787–1032`), `dismissOverlay()` (`:1034–1045`),
`markAppUnlocked()` (`:1047–1100`), `executeCommand()` (`:485–537`), `handleScreenOff()` (`:415–436`),
`backgroundApp()` (`:397–408`), `performMidnightReset()` (`:257–293`), `handleSessionExpired()`
(`:630–657`), `onStartCommand()` (`:659–678`), `onDestroy()` (`:758–767`).
Policy side read to establish reachability: `policy/SessionPolicyEngine.evaluate()` (all branches).
Existing test read in full: `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`
(137 lines, 8 tests).

## Boundary findings cited, not re-filed

- **F-001 / C1** (Critical, RU-04) — `windowManager?.addView` at `OverlayService.kt:989–994` swallows
  failure and returns, so no overlay is created at all during a lockout.
- **F-005** (High, RU-04) — `SessionCommand.DismissOverlay` is an intentional no-op, so no app switch
  removes an overlay. Absorbed the withdrawn F-114.
- **F-007** (Medium, RU-04) — `sessionStartTime` read/written across IO and Main.
- **F-002 / F-205** (Critical) — unguarded `startForeground` in both service entry points.
- **C5 / F-110**, **C6 / F-111** (Critical, RU-03) — wall-clock comparisons in `SessionModels.kt`.
  F-102 below is the overlay package's own instance of the same clock-discipline theme, from the
  opposite direction: the lock countdown ignores the wall clock entirely once it has started.

## Findings

### F-095 — `FrictionPresentation` grants a full session without checking whether the user is still in the app, so a screen-off during the wait silently burns the session, the daily budget and the lockout  [High]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/FrictionPresentation.kt` →
  `FrictionPresentation.onCountdownFinished()` (lines 15–18)
- **Issue**: `onCountdownFinished` unconditionally calls `ctx.markAppUnlocked()` then
  `ctx.dismissOverlay()`. It never consults `ctx.isStillForeground()`, even though the context hands it
  that callback and its sibling `LockPresentation.onCountdownFinished()` (`LockPresentation.kt:19–26`)
  does use it — with a comment stating the exact principle being broken here: *"Don't grant silent,
  untracked access."* `FrictionPresentation` is the **only** presentation whose completion grants a
  session, and the only one that skips the check. The countdown job
  (`OverlayService.kt:1004–1031`) is not tied to the app remaining foreground, so when the user leaves
  during the 3–30 s wait the timer keeps running and `markAppUnlocked()` (`:1047–1100`) writes
  `ACTIVE_UNTIL` and `LOCKED_UNTIL`, arms the expiry alarm (`:1098`), posts the countdown notification
  (`:1097`) and sets `sessionStartTime = now` (`:1099`) — for an app the user is not looking at.
  User-visible consequence: the session and the app's daily budget drain while the app is unused, and
  the lockout clock starts. The user returns to a session that is partly or fully spent and is locked
  out of an app they never got to use. The friction pause is also defeated in the other direction: the
  wait can be served with the screen off, so re-opening the app grants immediate access with no pause.
- **Trigger condition**:
  1. Grant overlay + accessibility permissions; monitor an app; leave the wait at its default
     (`WAIT_SECONDS`, 3–30 s) and the session at 10 minutes.
  2. Open the monitored app. The friction screen appears and counts down.
  3. Press the **power button** to turn the screen off before the countdown reaches zero. (Or switch
     to any non-monitored app / the launcher — F-005 shows no app switch removes the overlay either.)
  4. `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep -E 'active_until|locked_until'"`
     — both keys are now set for that package even though the app was never used.
  5. Wait out the session length with the screen still off, then unlock and open the app: the LOCK
     screen appears immediately. `adb shell dumpsys alarm | grep -i grayzone` shows the expiry alarm
     was armed at step 3.
- **Blast radius**:
  - *Callers*: `FrictionPresentation` is reached in main source **only** through
    `OverlayPresentation.resolve()` (`OverlayPresentation.kt:63–68`), whose sole main-source caller is
    `OverlayService.showOverlay()` (`:828`) — confirmed by direct search over `app/src` and by
    `evidence/reference-index.md:588`. `onCountdownFinished` is invoked from exactly one site, the
    countdown job at `OverlayService.kt:1021`. `showOverlay` reaches FRICTION from four call sites:
    `performMidnightReset()` (`:282`), `executeCommand(ShowWaitScreen)` (`:493`),
    `LockPresentation`'s `showFriction` callback (`:1028`), and — for other modes —
    `executeCommand` (`:498`, `:503`, `:508`), `logUsageAndCheckBudget()` (`:612`),
    `handleSessionExpired()` (`:653`).
  - *Process death*: the overlay window and `countdownJobUI` die with the process, so a process kill
    **prevents** the bad grant. That is the only thing limiting it today, and it is not a mechanism the
    app controls.
  - *Config change*: none. `showOverlay` is not activity-bound and the countdown job is unaffected by
    rotation, so the grant proceeds across a configuration change.
  - *Foreground/background*: this is exactly a background-transition defect. The grant happens while
    the monitored app is backgrounded or the screen is off. `handleScreenOff()` (`:415–436`) already
    knows the device is idle — it calls `backgroundApp()` and sets `lastForegroundPackage = null` — but
    it does not dismiss the overlay and nothing tells the countdown job to stand down.
  - *Cross-process*: partially. The app-switch variant of the trigger originates in `:a11y`
    (`GrayzoneAccessibilityService.kt:86–89` broadcast, `:93–99` watchdog), so the signal that should
    stop the grant crosses the process boundary. The screen-off variant is entirely default-process
    (`ACTION_SCREEN_OFF` receiver, `OverlayService.kt:204–227`).
  - *Persistence*: yes — `markAppUnlocked()` writes `ACTIVE_UNTIL + pkg`, `LOCKED_UNTIL + pkg` and
    removes `REMAINING_MILLIS + pkg` via `prefs.edit().apply()` (`:1091–1095`). These are the keys
    `StateBackupManager` snapshots, so a wrongly-granted session also propagates into the external
    anti-tamper backup.
  - *Release-only*: no. The only logging in the path is `GrayzoneLogger.w` for clamped durations
    (`:1058`, `:1067`), which emits in every variant.
- **Fix** (minimal): mirror `LockPresentation`'s shape — three lines in `FrictionPresentation.kt`:

  ```kotlin
  override fun onCountdownFinished(ctx: CountdownFinishContext) {
      // A wait served while the app is backgrounded or the screen is off must not buy a session.
      if (!ctx.isStillForeground()) { ctx.dismissOverlay(); return }
      ctx.markAppUnlocked()
      ctx.dismissOverlay()
  }
  ```

  No service change is required: `isStillForeground` is already supplied at `OverlayService.kt:1027`
  and already returns `false` for both triggers (`lastForegroundPackage` is another package after an
  app switch, and `null` after screen-off, `:422`).
- **Rewrite justification**: n/a — one added guard inside the existing override.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, new test
  `friction countdown finish does not unlock when the app is no longer foreground`, constructing
  `CountdownFinishContext(… isStillForeground = { false } …)` with
  `markAppUnlocked = { throw AssertionError() }` and asserting `dismissOverlay` still ran. Fails before
  the fix (the `markAppUnlocked` lambda is invoked), passes after.
- **Existing test conflict**: `OverlayPresentationTest.kt:92–108`
  (`friction countdown finish unlocks and dismisses`) already passes `isStillForeground = { true }`, so
  it keeps passing unchanged — it just stops being the only case covered. No other test touches this
  path.
- **Verification**: `confirmed` (evidence tier `T1`) for the mechanism and the reachability. Three
  static facts establish it without a device: (1) `onCountdownFinished` contains no call to
  `isStillForeground` (`FrictionPresentation.kt:15–18`); (2) `SessionPolicyEngine.evaluate` on
  `AppEvent.AppBackgrounded` emits only `CancelCountdownNotification`, `RecordUsage` and `UpdateState`
  — **never** `DismissOverlay` (`SessionPolicyEngine.kt:104–133`), so this defect survives F-005's fix
  and is not a duplicate of it; (3) `handleScreenOff()` never calls `dismissOverlay()`
  (`OverlayService.kt:415–436`).
  - *Missing*: only the magnitude on a real device — specifically whether the main-looper `delay(1000)`
    ticks continue long enough after screen-off to reach zero on a given OEM (`delay` on
    `Dispatchers.Main` is `Handler.postDelayed`, i.e. `uptimeMillis`, which stops in deep sleep; for a
    3–30 s wait the device will normally not have suspended yet).
  - *Procedure*: steps 1–5 above on any API 29+ device, once with screen-off at 1 s remaining and once
    at 25 s remaining on a 30 s wait, to bracket the suspend window.
  - *Severity effect*: **High** as filed — protection is degraded and the user is punished for a
    session they never had, but it is recoverable (the lockout expires). Escalate to **Critical** if a
    device shows the grant happening with the screen off *and* the session expiring unused, since the
    resulting lockout is then imposed with no user action and no notification at all. Drop to
    **Medium** only if a device shows the countdown reliably frozen by suspend at every screen-off
    point, which would leave the app-switch variant as the sole trigger.
- **Build scope**: `everyday-build`
- **Constitution**: II (Consistent Strategy-Pattern Implementation — two implementations of one
  interface method disagree about whether the foreground check is part of the contract, and the
  interface does not say which is right); also I (the `isStillForeground` capability is supplied to
  every presentation and honoured by one).
- **Affected surfaces**: `OverlayService.kt:1017–1020` carries a comment claiming this is already
  handled — *"If this job was cancelled while the loop was exiting (e.g. an app change dismissed the
  overlay), don't grant a session"* — whose premise F-005 disproves, since no app change dismisses the
  overlay. That comment is why the gap survived review, and the fix should delete or correct it (RU-04,
  T018). `handleScreenOff()` (`OverlayService.kt:415–436`) is the second surface: it is the natural
  place for a service-side `dismissOverlay()` and would give defence in depth (RU-04, T018).
  A third variant reaches the same grant: if `SYSTEM_ALERT_WINDOW` is revoked while a friction overlay
  is displayed, the next `showOverlay` returns at `:821` **before** the `dismissOverlay()` at `:825`,
  so the old countdown job keeps running to a grant — worth noting to T018 because RU-04's
  lead-auditor notes credit that branch (`:790–822`) as the model for C1's fix without observing the
  ordering.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)` — the defect itself is in `FrictionPresentation.kt`, which is unmodified at `557a963`
- **Disposition**: `fix-pending-device-verification`

### F-096 — `isStillForeground` is a single-package equality, so `LockPresentation`'s "no silent untracked access" guard fails in split-screen and whenever the user moves to an app with no launcher intent  [High]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `CountdownFinishContext.isStillForeground` (line 22), consumed by
  `app/src/main/java/com/grayzone/app/service/overlay/LockPresentation.kt` →
  `LockPresentation.onCountdownFinished()` (lines 19–26). Supplied by
  `OverlayService.kt:1027` as `{ lastForegroundPackage == packageName }`.
- **Issue**: the contract the presentation is written against is "is the monitored app still the one in
  front of the user", but the implementation is equality against a single `@Volatile` field
  (`OverlayService.kt:121`) that holds the *last* package the accessibility service reported and that
  passed two filters. That model is wrong in both directions:
  - **False negative (silent under-blocking, the harmful one).** In split-screen / multi-window two
    packages are simultaneously visible and resumed, but `lastForegroundPackage` can only name one. If
    a monitored app A is in one pane and app B in the other, and the last `TYPE_WINDOW_STATE_CHANGED`
    came from B, `isStillForeground()` returns `false`. `LockPresentation.onCountdownFinished` then runs
    `ctx.dismissOverlay()` and **skips** `ctx.showFriction()` — so at the instant a lockout expires, the
    lock screen is removed and app A is left visible and fully usable with no friction screen, no
    session record, no `ACTIVE_UNTIL`, no expiry alarm and no notification. That is precisely the
    outcome the code's own comment at `LockPresentation.kt:20–21` exists to prevent: *"Don't grant
    silent, untracked access."* Recovery depends on the next `ACTION_APP_CHANGED` event for A, so a user
    who simply keeps scrolling or watching in A — no window-state change — keeps untracked access.
  - **False positive (over-blocking, and it chains).** `processForegroundAppChanged` returns early
    before assigning `lastForegroundPackage` when the new package has no launcher intent
    (`OverlayService.kt:329`, `hasLauncherIntent()` at `:556–557`). Any foreground surface belonging to
    a package with no `MAIN`/`LAUNCHER` activity therefore leaves `lastForegroundPackage` pointing at
    the previous app. `isStillForeground()` then returns `true` for app A while the user is in fact
    looking at B, so a lockout expiry re-shows the **friction screen for A on top of B**, and by F-095
    that friction countdown proceeds to grant A a session the user never asked for.
- **Trigger condition**:
  1. Monitor app A with a short session (1 min) and the minimum lockout (15 min).
  2. Open A, sit through friction, let the session expire so the LOCK screen appears.
  3. Enter split-screen with A in one pane and any non-monitored app B in the other
     (`adb shell input keyevent KEYCODE_APP_SWITCH`, then drag A to the top).
  4. Tap in B so the last window-state change belongs to B, then wait out the remaining lockout while
     leaving A visible.
  5. At lockout expiry, observe that A becomes usable with no friction screen.
     `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep active_until"`
     shows no session for A, and `adb shell dumpsys alarm | grep -i grayzone` shows no expiry alarm —
     i.e. the usage is untracked.
  6. For the false-positive direction: from A, open a surface belonging to a package with no launcher
     activity (`adb shell cmd package resolve-activity --brief <pkg>` returning no launcher activity),
     confirm with `adb shell dumpsys activity activities | grep mResumedActivity`, and observe the
     friction screen for A appearing over it at lockout expiry.
- **Blast radius**:
  - *Callers*: `CountdownFinishContext.isStillForeground` has exactly one main-source consumer,
    `LockPresentation.onCountdownFinished()`, and one main-source producer,
    `OverlayService.kt:1027` — confirmed by direct search over `app/src` and consistent with
    `evidence/reference-index.md:566`. `lastForegroundPackage` itself is read at `OverlayService.kt:263`
    (`performMidnightReset`), `:330`, `:332`, `:416` (`handleScreenOff`), `:567`
    (`logUsageAndCheckBudget`'s budget-lock foreground test), `:640` (`handleSessionExpired` →
    `SessionExpiryDecision.isForegroundPackage`) and `:1027`; written at `:351` and `:422`. Every one of
    those seven readers inherits the same single-package assumption, which is why the fix belongs in one
    place.
  - *Process death*: `lastForegroundPackage` is in-memory only, so after a process restart it is `null`
    until the next accessibility event. `isStillForeground()` then returns `false` for every package,
    which biases the defect toward the harmful false-negative branch immediately after every restart —
    including the `onTaskRemoved` self-restart at `:680–704`.
  - *Config change*: entering or leaving split-screen is a configuration change for the *apps*, not for
    the overlay window, so nothing re-evaluates the predicate; the stale answer is used as-is.
  - *Foreground/background*: the whole defect is about a mistaken foreground model. Multi-window means
    "foreground" is a set, not a value.
  - *Cross-process*: yes. `lastForegroundPackage` is fed exclusively by broadcasts and watchdog intents
    from `:a11y` (`GrayzoneAccessibilityService.kt:86–89`, `:93–105`), and only for events that survive
    the `:329` filter, so the default process's view of "what is in front of the user" is a filtered
    subset of what `:a11y` observed.
  - *Persistence*: no direct writes on the false-negative branch — that is the defect: nothing is
    persisted, so the usage is invisible to the daily budget and to `UsageDatabase`. On the
    false-positive branch, F-095's `markAppUnlocked` writes do occur.
  - *Release-only*: no.
- **Fix** (minimal): keep the presentation contract and correct the predicate at its single producer —
  `OverlayService.kt:1027` becomes a call to a small helper that answers "is `packageName` currently
  visible" from a *set*, e.g. maintain `visibleMonitoredPackages: MutableSet<String>` alongside
  `lastForegroundPackage`, adding on `AppForegrounded` and removing in `backgroundApp()`/
  `handleScreenOff()`, and implement `isStillForeground = { packageName in visibleMonitoredPackages }`.
  In the presentation package the minimal change is to make the contract explicit so the next
  implementer cannot mistake it: rename the property to `isMonitoredAppVisible` and document that it
  must be true whenever the package occupies *any* visible window. Do **not** "fix" it by removing the
  guard: `LockPresentation`'s fallback to the friction screen is the only thing preventing untracked
  access at lockout expiry.
- **Rewrite justification**: n/a — one predicate replaced at one producer plus a rename; no change to
  `resolve()`, the interface's other members, or the policy engine.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/LockPresentationForegroundTest.kt`, asserting
  that `LockPresentation.onCountdownFinished` with `isStillForeground = { true }` calls `showFriction`
  exactly once and with `{ false }` calls it zero times *and* that both paths dismiss — which pins the
  contract so a fix to the producer cannot silently invert it. The multi-window behaviour itself needs
  `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/OverlaySplitScreenTest.kt`, driving
  `ACTION_APP_CHANGED` for B after A and asserting the visibility helper still reports A visible.
  **The instrumented half cannot be executed here** (no device or emulator available) and the project
  currently has no instrumentation coverage of any service (`evidence/prior-fix-watchlist.md`, "Test
  coverage of the prior fixes").
- **Existing test conflict**: `OverlayPresentationTest.kt:110–122`
  (`lock countdown finish dismisses and re-shows friction only when still foreground`) asserts the
  current behaviour of the *presentation*, which the fix preserves — so no change is needed. If the
  property is renamed, that test's constructor call at `:115`/`:118` uses positional arguments and
  compiles unchanged; the named-argument call at `:97–104` must be updated.
- **Verification**: `needs-verification` (evidence tier `T2`). Confirmed statically: the predicate is a
  single-package equality (`OverlayService.kt:1027`); `lastForegroundPackage` is written only at `:351`
  and `:422`; the `:329` early return skips the assignment for packages with no launcher intent; and
  the consumer's own comment states the invariant that fails. What needs a device is the *ordering* of
  accessibility events in multi-window and how long the untracked window lasts.
  - *Missing*: a device to (a) confirm that `TYPE_WINDOW_STATE_CHANGED` in split-screen leaves
    `lastForegroundPackage` naming the non-monitored pane, and (b) measure how long A stays usable
    before the next event re-runs the policy engine.
  - *Procedure*: steps 1–6 above, with `adb logcat -s GrayzoneOverlay GrayzoneAccessibility` to record
    the event order, on API 29+ (split-screen) and additionally on an API 33+ device where per-app
    freeform/multi-window is more common.
  - *Severity effect*: **High** as filed — the mechanism is a confirmed static fact and the outcome is
    silent, unlogged loss of protection for one app, which the FR-006 Grayzone calibration ranks at or
    above a visible error. Escalate to **Critical** if a device shows the untracked window persisting
    for minutes of continuous in-app use (no self-heal), since protection is then simply off with no
    signal. Drop to **Medium** only if a device shows an `ACTION_APP_CHANGED` for A arriving within a
    second of the lock dismissal in every multi-window arrangement.
- **Build scope**: `everyday-build`
- **Constitution**: II (the presentations share one interface but only one implementation consults this
  capability, and the capability's meaning is not stated anywhere); IV (the property name says
  "foreground", the value means "last reported package that had a launcher intent" — a name that
  documents the opposite of the guarantee its consumer relies on).
- **Affected surfaces**: the same single-package assumption is relied on by
  `logUsageAndCheckBudget()`'s `isStillForeground = currentFgPkg == pkg` (`OverlayService.kt:567`,
  `:607`) which gates whether the BUDGET_LOCK screen is raised at all, and by
  `handleSessionExpired()`'s `isForegroundPackage = lastForegroundPackage == packageName`
  (`:640`) which gates the entire `ShowLock` outcome in `SessionExpiryDecision` — so in split-screen a
  session can expire with no lock shown. Both are RU-04 (T018) / RU-03 (T024) surfaces and should be
  fixed in the same pass as the helper above; neither is covered by an existing finding.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `fix-pending-device-verification`

### F-097 — In landscape or at a raised system font scale the lock screens' only exit control is clipped off the bottom of an unscrollable card, leaving a full-screen overlay with no way out  [High]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `OverlayPresentation.countdownTextSizeSp` (line 47), with the values that make it bite at
  `BudgetLockPresentation.kt:8` and `ScheduleLockPresentation.kt:8` (`72f` for a screen whose
  `countdownText()` returns the literal `"—"`) and `FrictionPresentation.kt:8` (`72f`). Rendered by
  `OverlayService.showOverlay()` (`:869–979`, the card built at `:869` and attached at `:979`).
- **Issue**: the interface mandates a **fixed** countdown text size in `sp` with no input for screen
  size, orientation or font scale, and the card that hosts it has no scroll container. `textSize =
  presentation.countdownTextSizeSp` (`OverlayService.kt:909`) is `TextView.setTextSize(Float)`, i.e.
  `COMPLEX_UNIT_SP`, so it multiplies by the user's system font scale. Summing the card's children at
  font scale 1.0 — 32dp top padding, 11sp app name, 34sp title, 33dp divider block, a 17sp two-line
  prompt, 72sp countdown with a 24dp top margin, 12sp sub-text with 28dp of margins, the button with
  24dp of padding, 32dp bottom padding — gives roughly **410dp** of content for a lock screen. Landscape
  height on current phones is roughly 360–450dp, so the lock screens are already at or over the limit in
  landscape at the default font scale, and about 60% of that height is `sp`-scaled, so a system font
  scale of ~1.15 or more overflows it outright (and Android 14 allows up to 2.0). When it overflows,
  `FrameLayout` measures the `WRAP_CONTENT` card against `AT_MOST(parentHeight)`, `LinearLayout` clamps
  its own measured height to that via `resolveSizeAndState` while still laying its children out
  sequentially from the top, and the **last** children — the sub-text and the `skipBtn` — are positioned
  below the clip boundary and never drawn. The button is the *only* exit control on a BUDGET_LOCK or
  SCHEDULE_LOCK screen: `initialSeconds` is `0` (`BudgetLockPresentation.kt:13`,
  `ScheduleLockPresentation.kt:13`), `onCountdownFinished` is an empty body (`:15–17` in both), and by
  F-005 no app switch removes the window. So the user faces a full-screen, opaque
  `TYPE_APPLICATION_OVERLAY` window (`OverlayService.kt:867`, `:981–987`) with no visible control,
  nothing that self-dismisses, and — because the window is not activity-bound — nothing that rotation or
  Back will clear. Recovery is force-stopping the app or rebooting, i.e. the user must defeat their own
  screen-time app to escape it. The waste is gratuitous: the two static lock screens spend ~110dp of
  vertical space rendering an em dash at 72sp.
- **Trigger condition**:
  1. Monitor an app and set a daily budget of 1 minute; use the app for a minute so the budget is
     exhausted.
  2. `adb shell settings put system font_scale 1.3` (or, on the device, Settings → Display → Font size
     → largest).
  3. Rotate the device to landscape and make sure auto-rotate is on.
  4. Open the monitored app. The BUDGET_LOCK overlay appears.
  5. Observe whether the "← Leave App" button is visible. `adb shell uiautomator dump` and grep the XML
     for `Leave App`: if the node's `bounds` extend past the display height, the control is unreachable.
  6. Confirm the window itself is still attached: `adb shell dumpsys window windows | grep -i grayzone`.
     Repeat with `font_scale 2.0` in portrait on a small-screen device.
- **Blast radius**:
  - *Callers*: `countdownTextSizeSp` has one main-source reader, `OverlayService.kt:909`, and four
    implementations (`evidence/reference-index.md:575`); re-verified by direct search over `app/src`.
    Every path that shows any overlay is affected because all four presentations go through the same
    card: `performMidnightReset()` (`:282`), `executeCommand()` (`:493`, `:498`, `:503`, `:508`),
    `logUsageAndCheckBudget()` (`:612`), `handleSessionExpired()` (`:653`), and the `showFriction`
    callback (`:1028`).
  - *Process death*: the window dies with the process, so — as with F-005 — a process kill is currently
    the most reliable escape, which is the wrong recovery mechanism to depend on.
  - *Config change*: central to the defect. The overlay is a `WindowManager` window, not an Activity
    view, so a rotation resizes the window but never rebuilds the card and never re-measures against a
    different layout; the app has no `onConfigurationChanged` anywhere (zero matches in
    `app/src/main`) and there is no `ScrollView` anywhere in the codebase (zero matches). A font-scale
    change likewise does not rebuild an already-attached overlay, so an overlay raised before the change
    keeps the old metrics until it is replaced.
  - *Foreground/background*: no difference — the overlay is raised while the monitored app is
    foreground and stays regardless.
  - *Cross-process*: no. The card is built and attached entirely in the default process; `:a11y` only
    triggers the transition.
  - *Persistence*: no writes on this path.
  - *Release-only*: no. Note however that R8 does not shrink or alter this code path in a way that
    changes measurement, and no resource qualifiers are involved because the layout is built
    imperatively in Kotlin — so there is nothing to configure per-orientation even if someone tried.
- **Fix** (minimal), two parts, both small:
  1. Service side (`OverlayService.showOverlay`, `:977–979`): wrap the card in a `ScrollView` before
     adding it to `root`, so the exit control is always reachable:
     `val scroller = ScrollView(ctx).apply { isFillViewport = false; addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }` and attach `scroller` with the existing
     `cardParams`. This is the change that removes the "no way out" outcome and it is orientation- and
     font-scale-agnostic.
  2. Presentation side (this review unit): drop `countdownTextSizeSp` to a small value for the two
     static lock screens, which render only `"—"` — `BudgetLockPresentation.kt:8` and
     `ScheduleLockPresentation.kt:8` from `72f` to `24f`. That reclaims ~70dp for the screens that have
     the least reason to spend it and are the two that never self-dismiss.
- **Rewrite justification**: n/a — one wrapper view and two constant changes. A responsive
  `countdownTextSizeSp(config)` signature was considered and rejected as larger than needed: with a
  scroll container the fixed sizes are no longer harmful.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, extend the two
  static-lock tests to assert the reduced `countdownTextSizeSp`, which pins part 2 of the fix. The
  clipping itself needs `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/OverlayLandscapeReachabilityTest.kt`, raising a
  BUDGET_LOCK with `font_scale = 1.3` in landscape and asserting via `UiDevice` that a node with text
  containing "Leave App" exists and its bounds lie inside the display. Fails before the fix, passes
  after. **It cannot be executed here** — no device or emulator is available, and the project has no
  instrumentation coverage of any service.
- **Existing test conflict**: `OverlayPresentationTest.kt:72` and `:85` assert
  `assertEquals(72f, p.countdownTextSizeSp)` for the budget and schedule locks; both must be updated to
  the new value as part of the fix. `:44` (friction, `72f`) and `:57` (lock, `42f`) are unchanged by this
  fix.
- **Verification**: `needs-verification` (evidence tier `T2`). Confirmed statically: the sizes are fixed
  constants in `sp`; `showOverlay` builds the card with no scroll container and the codebase contains no
  `ScrollView` and no `onConfigurationChanged` (both zero matches in `app/src/main`); the card is
  `WRAP_CONTENT` inside a `MATCH_PARENT` `FrameLayout` with `Gravity.CENTER`; the button is the last
  child added (`OverlayService.kt:975`); and the two static lock screens have no self-dismiss path. What
  is unverified is the arithmetic on real metrics — whether the content actually exceeds the available
  height on a given device, orientation and font scale.
  - *Missing*: a device (or emulator) to measure the card against the display, at font scales 1.0 / 1.3
    / 2.0, in portrait and landscape, on both a compact (≤360dp-wide) and a tall modern display.
  - *Procedure*: steps 1–6 above; the decisive artifact is the `uiautomator dump` node bounds for the
    "← Leave App" button compared with the display height from `adb shell wm size`.
  - *Severity effect*: **High** as filed, following the audit's restraint precedent for a plausible but
    unmeasured chain. Escalate to **Critical** if a device shows the button unreachable on a BUDGET_LOCK
    or SCHEDULE_LOCK at the *default* font scale in landscape, because combined with F-005 the user then
    has no recovery short of force-stop or reboot and no signal explaining it. Drop to **Medium** if the
    clipping only appears at font scales ≥1.5 (still a real defect — that is an accessibility setting,
    not an exotic one — but a narrower condition), and to **Low** only if measurement shows the content
    fits at every scale up to 2.0.
- **Build scope**: `everyday-build`
- **Constitution**: II (the presentations own presentation-sizing decisions but express them as fixed
  constants the single render site cannot adapt, so every screen inherits one screen's assumptions);
  also the Code Quality expectation that a lock screen's exit affordance is part of its contract rather
  than a by-product of measurement.
- **Affected surfaces**: `OverlayService.kt:869–979` is the unscrollable card (RU-04, T018) and part 1
  of the fix lands there — RU-04's coverage of `showOverlay` recorded "view construction" as examined
  but filed nothing on measurement, and F-005's *Config change* sub-field notes only that the window
  survives rotation, not that its content can be clipped by it. `FrictionPresentation.kt:8` shares the
  `72f` value but is materially safer, since the friction screen self-dismisses after 3–30 s so a
  clipped skip button costs the user a wait rather than trapping them.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `fix-pending-device-verification`

### F-098 — `dismissOverlay()` forgets the overlay view even when `removeView` throws, so a window that is still attached becomes untracked and can never be removed  [Medium]

- **Review unit**: RU-05 (removal paths; the code lives in RU-04's file — see *Affected surfaces* for
  the ownership note)
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` →
  `OverlayService.dismissOverlay()` (lines 1034–1045), the single removal path for every overlay every
  presentation raises
- **Issue**: the assignment `overlayView = null` at `:1044` sits **outside** the `overlayView?.let { … }`
  block, so it runs on the exception path as well as the success path:

  ```1036:1044:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        overlayView?.let {
            try {
                it.clearAnimation()
                windowManager?.removeView(it)
            } catch (e: Exception) {
                GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to remove overlay: ${e.message}")
            }
        }
        overlayView = null
  ```

  If `removeView` throws while the view is still attached, the service drops its only reference to an
  attached `TYPE_APPLICATION_OVERLAY` window. Nothing else in the app holds it — a direct search over
  `app/src` shows `overlayView` is read or written only at `:102`, `:991`, `:1036` and `:1044` — so no
  later `dismissOverlay()`, no `onDestroy()` (`:766`) and no `ACTION_STOP` (`:661`) can ever remove it.
  The user is left with a full-screen opaque window that the app no longer knows exists, and the next
  `showOverlay()` calls `addView` believing nothing is attached (`:989–991`), stacking a second window
  on top of the orphan and repeating the leak on each subsequent failure. The warning that is logged
  carries neither the package, nor the overlay mode, nor the throwable — only `e.message` — so the
  orphaned window leaves no diagnosable trace. Note also `windowManager?.removeView(it)`: if
  `windowManager` were ever null the removal would be skipped with **no log at all** and the reference
  still nulled. That specific path is not currently reachable (`windowManager` is assigned in `onCreate`
  at `:145` and never cleared, and `addView` uses the same safe call so `overlayView` could not have
  been set), but it is the same defect one edit away.
- **Trigger condition**:
  1. Grant overlay + accessibility permissions and raise any overlay (e.g. exhaust a daily budget and
     open the monitored app).
  2. Force a `removeView` failure while the view is attached. The reproducible route is to make the
     dismissal run off the main thread — apply a one-line patch that calls `dismissOverlay()` from
     `serviceScope.launch(Dispatchers.IO)` — which raises `CalledFromWrongThreadException` (a
     `RuntimeException`, so the catch swallows it). On stock code the same state is reached whenever the
     platform throws from `removeView` for a still-attached view.
  3. Trigger a second overlay (switch to another monitored app).
  4. `adb shell dumpsys window windows | grep -i grayzone` shows **two** Grayzone overlay windows.
  5. Force-stop the app and re-check: the orphan is removed only by process death.
- **Blast radius**:
  - *Callers*: `dismissOverlay()` is private with six call sites — `performMidnightReset()` (`:273`),
    `onStartCommand`/`ACTION_STOP` (`:661`), `onDestroy()` (`:766`), the top of `showOverlay()` (`:825`),
    the skip-button listener (`:952`), and `CountdownFinishContext.dismissOverlay` (`:1026`), which is
    what `FrictionPresentation.kt:17` and `LockPresentation.kt:22` invoke. All six inherit the defect;
    all six currently run on the main thread, which is what keeps the common `removeView` throw rare.
  - *Process death*: process death removes the window, so this leak is bounded by the process lifetime —
    but `OverlayService` is `START_STICKY` (`:677`) and self-restarts after a Recents swipe (`:680–704`),
    so the process is designed to be long-lived.
  - *Config change*: a rotation does not rebuild or re-attach the window (there is no
    `onConfigurationChanged` anywhere in `app/src/main`), so an orphaned window survives rotation
    unchanged.
  - *Foreground/background*: irrelevant to the leak; the orphan covers whatever is in front.
  - *Cross-process*: no. `overlayView` and `windowManager` live only in the default process; `:a11y`
    holds no view state (`evidence/cross-process-inventory.md` records zero falsely-shared
    declarations).
  - *Persistence*: no writes.
  - *Release-only*: no — the log line is `GrayzoneLogger.w`, which does emit in every variant (unlike
    `d()`/`i()`, dead everywhere per F-064). Its usefulness is still limited because
    `-keepattributes SourceFile,LineNumberTable` is absent from `app/proguard-rules.pro`, so a release
    trace would carry no line numbers even if the throwable were logged.
- **Fix** (minimal): move the reset inside the success path and log the throwable with context —

  ```kotlin
  overlayView?.let {
      try {
          it.clearAnimation()
          windowManager?.removeView(it)
          overlayView = null
      } catch (e: Exception) {
          GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to remove overlay; view left attached", e)
          // Keep the reference so a later dismiss can retry rather than orphaning the window.
      }
  } ?: run { overlayView = null }
  ```

  Retaining the reference on failure is the point: it makes the next `dismissOverlay()` — from
  `onDestroy`, `ACTION_STOP` or the next `showOverlay` — a retry instead of a leak. The one throw for
  which nulling *is* correct, `IllegalArgumentException("View … not attached to window manager")`,
  should be caught separately and treated as success.
- **Rewrite justification**: n/a — the statement moves inside the `try` and the catch gains the
  throwable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/OverlayViewRegistryTest.kt`, extracting the three-line
  attach/detach bookkeeping into a small pure holder (`fun detach(remove: (View) -> Unit)`) and
  asserting that when `remove` throws the holder still reports the view attached, and that it reports
  detached after a successful call. Fails before the fix (the holder clears unconditionally), passes
  after. This is the same seam F-001 notes is missing; one extraction serves both findings.
- **Existing test conflict**: none. `OverlayService` has no unit tests at all
  (`evidence/reference-index.md:524`), and `OverlayPresentationTest.kt` stubs `dismissOverlay` with a
  counter lambda, so it is unaffected.
- **Verification**: `confirmed` (evidence tier `T1`) for the state desynchronisation — the assignment is
  demonstrably outside the `let`, and `overlayView` demonstrably has no other holder.
  `needs-verification` (tier `T4`) for the user-visible outcome, which additionally requires
  `removeView` to throw *while the view is attached*.
  - *Missing*: a device on which a real `removeView` failure can be observed, and confirmation of which
    throwables the platform raises for an attached view.
  - *Procedure*: steps 1–5 above on any API 29+ device.
  - *Severity effect*: **Medium** as filed, deliberately not higher: the throw `removeView` raises most
    often — "View not attached to window manager" — means the window is *already* gone, and for that
    case nulling is the correct behaviour, so the harmful variant is plausible but unproven. Escalate to
    **High** if a device shows a residual attached window after a thrown `removeView` (an undismissable
    full-screen artifact — the F-005 outcome without even a "Leave App" button), and to **Critical**
    only if it also survives a service restart.
- **Build scope**: `everyday-build`
- **Constitution**: III (an exception on a service boundary is caught, logged without the throwable, and
  then followed by state mutation that assumes it succeeded — the catch turns the failure from
  reportable into invisible).
- **Affected surfaces**: **ownership note for T034** — the code is in `OverlayService.kt`, which RU-04
  owns; T023 files it because the audit brief assigns RU-05 "window/view removal on every exit path,
  including exception paths", and because RU-04 examined this catch and credited it: F-001's *Affected
  surfaces* states *"The sibling `removeView` catch at `:1039–1042` is compliant and shows the intended
  house style."* That assessment is right about the catch and misses the unconditional reset two lines
  below it — see the contradiction note at the end of this file. The fix is one hunk in
  `OverlayService.dismissOverlay()` and must not be double-counted against RU-04. The `addView`
  counterpart at `:989–994` is F-001; the two are opposite halves of one attach/detach bookkeeping
  problem and should be fixed together.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-099 — `OverlayPresentation.resolve()` fails **open**: an unmapped mode renders the session-granting friction screen instead of a lock, and an existing test pins that behaviour  [Medium]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `OverlayPresentation.Companion.resolve()` (lines 57–68)
- **Issue**: the mapping is a `when` with three explicit lock cases and `else -> FrictionPresentation`.
  `FrictionPresentation` is the one presentation whose `onCountdownFinished` calls `markAppUnlocked()`
  and hands the user a full session (`FrictionPresentation.kt:15–18`), so the default branch of the
  factory in a self-control app is *"grant access"*. Any mode value that is not `LOCK`, `BUDGET_LOCK` or
  `SCHEDULE_LOCK` — including `OverlayMode.FRICTION` itself, which is deliberately unlisted, including
  any future lock mode whose author forgets a `resolve` entry, and including a corrupted or
  out-of-range integer — produces a countdown that ends in a granted session. There is no logging on the
  default branch, so the substitution is silent. The KDoc compounds it in two ways: it presents the
  fail-open as the *defensive* choice (*"every other value (FRICTION, and defensively anything else)
  maps to the friction screen"*), and its final sentence — *"Non-screen modes never reach this path"* —
  describes a category that does not exist, since `OverlayMode` declares exactly four constants and all
  four are screens (`PrefsKeys.kt:90–95`). The KDoc's stated design rule ("Adding a new overlay screen
  means adding an implementation and a `resolve` mapping entry") is exactly the rule the `else` branch
  makes unenforceable: omitting the entry compiles, passes every test, and silently unlocks the app.
- **Trigger condition**:
  1. Add a new lock mode to `OverlayMode` (e.g. `const val FOCUS_LOCK = 7`) and a
     `showOverlay(pkg, name, OverlayMode.FOCUS_LOCK, 0L)` call site, but omit the `resolve` entry —
     the change every future lock feature will make.
  2. Build and run the full unit-test suite: it passes.
     `./gradlew testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  3. Trigger the new lock on a device: the "Pause." friction screen appears instead of a lock, counts
     down the wait, and grants a session.
  4. `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep active_until"`
     confirms a session was granted for a mode that was supposed to block.
- **Blast radius**:
  - *Callers*: `resolve()` has exactly one main-source caller, `OverlayService.showOverlay()` (`:828`),
    and one test caller, `OverlayPresentationTest.kt:25–35` — confirmed by direct search over `app/src`
    and consistent with `evidence/reference-index.md:582` ("sole factory mapping `OverlayMode` →
    presentation"). Selection is genuinely centralised; the defect is what the centre does when it does
    not recognise the input. All eight `showOverlay` call sites pass `OverlayMode` constants today
    (`OverlayService.kt:282`, `:493`, `:498`, `:503`, `:508`, `:612`, `:653`, `:1028`), so no untrusted
    integer reaches `resolve()` **in the current tree** — verified by reading every call site.
  - *Process death*: none — `resolve()` is pure and the four objects are stateless singletons.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not today, but the risk is one step away and documented in-tree: `PrefsKeys.kt:89`
    says the modes are *"sent via broadcast intent extra `overlay_mode`"*. There are **zero** occurrences
    of `overlay_mode` anywhere in `app/src` (direct search), so that comment is stale — the mode is now
    an in-process Kotlin parameter. If anyone restores the documented intent-extra transport,
    `intent.getIntExtra("overlay_mode", 0)` defaults to `0`, an unmapped value, which `resolve()` maps to
    the session-granting screen.
  - *Persistence*: indirectly — the friction path writes `ACTIVE_UNTIL`/`LOCKED_UNTIL` via
    `markAppUnlocked()` (`OverlayService.kt:1091–1095`).
  - *Release-only*: no.
- **Fix** (minimal): make the mapping total over the declared modes and fail closed on anything else —

  ```kotlin
  fun resolve(mode: Int): OverlayPresentation = when (mode) {
      OverlayMode.FRICTION -> FrictionPresentation
      OverlayMode.LOCK -> LockPresentation
      OverlayMode.BUDGET_LOCK -> BudgetLockPresentation
      OverlayMode.SCHEDULE_LOCK -> ScheduleLockPresentation
      else -> {
          GrayzoneLogger.w(LogComponent.OVERLAY, "Unmapped overlay mode $mode; failing closed to a lock screen")
          BudgetLockPresentation
      }
  }
  ```

  Listing `FRICTION` explicitly is what turns "add a `resolve` entry" from a convention into a visible
  requirement, and failing closed means a forgotten entry over-blocks — recoverable and immediately
  noticed — instead of silently unlocking. `GrayzoneLogger.w` is used deliberately: `w()`/`e()` are the
  only levels that emit, since `BuildConfig.DEBUG` is `false` in every variant this project builds
  (F-064).
- **Rewrite justification**: n/a — one added branch and one changed default in an existing `when`.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, replacing
  `resolve defaults unknown modes to friction` with `resolve fails closed for unknown modes`, asserting
  `assertSame(BudgetLockPresentation, OverlayPresentation.resolve(9999))` and that
  `resolve(OverlayMode.FRICTION)` still returns `FrictionPresentation`. Fails before the fix, passes
  after.
- **Existing test conflict**: **yes, and it is the notable part of this finding.**
  `OverlayPresentationTest.kt:31–36` (`resolve defaults unknown modes to friction`) asserts
  `assertSame(FrictionPresentation, OverlayPresentation.resolve(3))`, `resolve(4)` and `resolve(9999)`.
  The fail-open behaviour is not merely present, it is **locked in by a passing test**, so a developer
  who fixes it will see a red suite and is likely to revert. That test must be replaced, not amended.
  (`3` and `4` are not `OverlayMode` values at all — the object declares 1, 2, 5, 6 — so the test pins
  behaviour for integers that have no meaning.)
- **Verification**: `confirmed` (evidence tier `T1`). Every element is a static fact: the `else` branch,
  the four constants at `PrefsKeys.kt:90–95`, the single call site, the zero matches for `overlay_mode`,
  and the test that pins the behaviour. No device is required; what is latent is the trigger, not the
  mechanism.
- **Build scope**: `everyday-build`
- **Constitution**: II (the strategy factory is the one place that must enumerate the modes, and its
  default silently substitutes a strategy with the opposite effect); IV (the KDoc states a rule the code
  cannot enforce and asserts a "non-screen modes" category that does not exist).
- **Affected surfaces**: `PrefsKeys.kt:89`'s stale "sent via broadcast intent extra `overlay_mode`"
  comment is the other half of the drift and belongs to RU-01 (T021's block F-060…F-079 is fully
  consumed, so route it to T059/T025b as a documentation correction rather than opening a new finding).
  `OverlayModeTest.kt:13–23` asserts the four constants and their values and needs no change.
- **Observed against**: `557a963` (the overlay package has no uncommitted modifications)
- **Disposition**: `verified`

### F-100 — `FrictionPresentation.initialSeconds` returns the wait duration unvalidated, so a wait of `0` makes the friction screen grant a session instantly — while its sibling `LockPresentation` does clamp  [Medium]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/FrictionPresentation.kt` →
  `FrictionPresentation.initialSeconds()` (line 13), fed by `OverlayService.showOverlay()` (`:835–845`)
- **Issue**: the override is `= inputs.waitSeconds`, a bare pass-through of a value that reaches it
  straight from SharedPreferences with no validation anywhere on the path:
  `OverlayService.kt:836–837` reads `per_app_wait_<pkg>` and `wait_seconds` with `getInt(…, DEFAULT_WAIT)`
  and `:843` selects between them inline. When the value is `0` or negative, `secondsRemaining` is
  non-positive, the countdown loop at `OverlayService.kt:1005` (`while (secondsRemaining > 0)`) never
  executes, and `onCountdownFinished` runs immediately — which for this presentation means
  `markAppUnlocked()` plus `dismissOverlay()` (F-095). The friction screen flashes and the app is
  unlocked with a full session: the app's core deterrent silently becomes a no-op, with no crash and no
  log. `ProgressBar` is also configured with `max = totalSeconds` (`:916–917`), i.e. `max = 0` or
  negative. The contrast inside the same interface is the evidence that this is an oversight rather than
  a decision: `LockPresentation.initialSeconds` (`LockPresentation.kt:16–17`) applies
  `.coerceAtLeast(0)`, and `BudgetLockPresentation`/`ScheduleLockPresentation` return a literal `0`, so
  three of the four implementations reason about their bounds and the one that gates protection does
  not. A validated helper for exactly this value exists and is unused in main source:
  `PerAppLimits.waitSeconds(hasCustom, perApp, global, fallback)` (`data/PerAppLimits.kt:15–16`) — **re-verified
  by direct search over `app/src` per the reference-index accuracy warning: its only references are its
  own declaration and `PerAppLimitsTest.kt:12,19,38,39`, so it is test-only.** `buildSessionState` uses
  its two siblings `PerAppLimits.sessionMinutes` and `.lockoutMinutes` (`OverlayService.kt:449`, `:454`),
  which makes skipping the third an inconsistency within one file.
- **Trigger condition**:
  1. The normal UI cannot produce the value: both sliders are bounded (`SettingsScreen.kt:161–166`,
     `PerAppSettingsSheet.kt:99–104`). The realistic route is the unvalidated backup restore —
     `StateBackupManager.applyInto` writes arbitrary keys and types from the JSON with no allowlist and
     no schema gate, and the file lives in `/sdcard/.grayzone/`.
  2. Plant the value directly to reproduce:
     `adb shell "run-as com.grayzone.app sh -c 'sed -i s/\"wait_seconds\" value=\"5\"/\"wait_seconds\" value=\"0\"/ /data/data/com.grayzone.app/shared_prefs/grayzone_prefs.xml'"`,
     or set `per_app_wait_<pkg>` to `0` the same way, then force-stop so prefs are re-read.
  3. Open the monitored app.
  4. Observe the "Pause." screen appear and vanish within one frame, and the app open immediately.
     `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep active_until"`
     shows a full session was granted.
  5. Note that the global key is repaired at next launch but the per-app key never is: `validateConfiguration()`
     (`GrayzoneApplication.kt:181–185`) repairs only `WAIT_SECONDS`, so `per_app_wait_<pkg> = 0` persists
     indefinitely.
- **Blast radius**:
  - *Callers*: `initialSeconds` is called from one main-source site, `OverlayService.kt:839`, for
    whichever presentation `resolve()` returned; `SecondsInputs.waitSeconds` is read only by
    `FrictionPresentation` (direct search; `evidence/reference-index.md:561` lists all four
    presentations as consumers of `SecondsInputs`' properties, which is true of the class but not of this
    property).
  - *Process death*: no effect — the value is re-read from prefs on every `showOverlay`, so the defect is
    reproduced on every friction screen until the pref is repaired.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: no. Prefs are read only in the default process; `:a11y` reads no preferences
    (`evidence/cross-process-inventory.md`).
  - *Persistence*: yes, on two counts — the poisoned value lives in SharedPreferences and is snapshotted
    into the external backup by `StateBackupManager` (`prefs.all`), so it survives a data wipe; and the
    resulting instant grant writes `ACTIVE_UNTIL`/`LOCKED_UNTIL`.
  - *Release-only*: no.
- **Fix** (minimal): clamp in the presentation, which is the last point before the value becomes
  behaviour —
  `override fun initialSeconds(inputs: SecondsInputs) = inputs.waitSeconds.coerceIn(ConfigValidator.WAIT_SECONDS_RANGE)`
  (`data/ConfigValidator.kt:15`, `3..30`). The complete fix also routes `OverlayService.kt:835–843`
  through `PerAppLimits.waitSeconds(...)` so the per-app key gets the same treatment as the session and
  lockout keys, and reconciles the two ranges that currently disagree —
  `ConfigValidator.WAIT_SECONDS_RANGE` is `3..30` while `PerAppLimits.waitSeconds` coerces to `1..60`
  (`PerAppLimits.kt:16`), so today the two "validated" paths would accept different values.
- **Rewrite justification**: n/a — one `coerceIn` in the presentation; the service-side change is a
  substitution of an existing helper for an inline expression.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, new test
  `friction wait duration is clamped into the supported range`, asserting
  `initialSeconds(SecondsInputs(0, 0, 0)) == ConfigValidator.DEFAULT_WAIT_SECONDS`-equivalent lower
  bound `3`, `initialSeconds(… waitSeconds = -5) == 3`, and `initialSeconds(… waitSeconds = 999) == 30`.
  Fails before the fix (returns `0`, `-5`, `999`), passes after.
- **Existing test conflict**: none. `OverlayPresentationTest.kt:48` asserts
  `initialSeconds(… waitSeconds = 8) == 8`, and `8` is inside `3..30`, so it keeps passing.
- **Verification**: `confirmed` (evidence tier `T1`) for the mechanism — the override is a bare
  pass-through, the loop guard is `> 0`, `onCountdownFinished` grants, and nothing on the path validates.
  The *trigger* is inherited from the backup-restore finding rather than proven here.
- **Build scope**: `everyday-build`
- **Constitution**: II (four implementations of one method, three of which bound their result and the
  one that gates protection does not, plus a validated helper that exists and is bypassed); V (the fix
  is guarded by a test rather than by inspection).
- **Affected surfaces**: **not new fix work in the punch list beyond the one-line clamp.**
  `findings.md`'s confirmed-leads table already owns the primary validation gap ("Per-app config has no
  validation, and the validated helper that exists for it is test-only", owners T020, T024, T026, rated
  High because it chains with the unvalidated backup restore, citing `OverlayService.kt:836`,
  `PerAppLimits.kt:15`, `ConfigValidator.kt:11–29`, `GrayzoneApplication.kt:181–185`). This record adds
  only the RU-05 half — the presentation-side missing clamp and the intra-interface inconsistency — and
  is filed at Medium so T034 does not double-count the same user outcome that lead already rates High.
  A second surface belongs to RU-03/RU-04 and is unfiled: `SessionCommand.ShowWaitScreen` carries a
  `waitSeconds` field (`policy/SessionModels.kt:44`) populated with the hardcoded
  `SessionPolicyEngine.DEFAULT_WAIT_SECONDS = 5` (`SessionPolicyEngine.kt:101`) which ignores both the
  global and per-app preferences, and `executeCommand`'s `ShowWaitScreen` branch
  (`OverlayService.kt:491–495`) **discards** it — so the wait duration is derived twice, the two
  derivations have diverged, and only one is used. `SessionPolicyEngineForegroundTest.kt:212` asserts the
  discarded value. See the cross-unit notes at the end of this file.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-101 — `showsProgress` is used as three different predicates at three call sites, so a new lock screen that wants a progress bar would also leak a free-running countdown notification  [Medium]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `OverlayPresentation.showsProgress` (line 46); consumed at `OverlayService.showOverlay()` `:831`,
  `:923` and `:1011`
- **Issue**: the interface exposes exactly one boolean about a screen's countdown behaviour, named after
  a styling detail, and the single render site uses it to answer three unrelated questions:
  1. `:831` — *does this screen own the session countdown notification?*
     `if (!presentation.showsProgress) cancelCountdownNotification()`, with the comment "Lock-type
     screens (no progress bar) clear any running session countdown notification". The parenthetical is
     the tell: the code wants "is this a lock type" and settles for "has no progress bar".
  2. `:923` — *should the progress bar be visible?* `if (!presentation.showsProgress) progress.visibility = View.GONE`. This is the only use that matches the property's name.
  3. `:1011` — *is `subText()` a function of the remaining seconds?* Inside the tick loop,
     `subView.text = presentation.subText(remaining)` is applied only when `showsProgress` is true, even
     though `countdownView.text = presentation.countdownText(remaining)` on the line above is applied
     unconditionally. The three lock screens happen to return constant sub-text, so today the skip is
     invisible — it is correct by coincidence, not by contract.
  Because one flag answers three questions, the questions can no longer disagree, and the app has
  already been told this is a hazard: `evidence/prior-fix-watchlist.md` "Fragile fixes" #3 records that
  a future lock-type presentation returning `showsProgress = true` "would show a lock with a
  free-running negative chronometer still in the shade", i.e. prior defect **P1** (the countdown running
  past `00:00` into negative values, fixed in feature `002`) is re-openable by adding a presentation and
  editing nothing else. That watchlist item is assigned to no task, so this record owns it. The
  user-visible consequence when it happens: a lock screen with the shade showing a session timer
  counting into negative time, contradicting the lock on screen — the exact symptom the previous feature
  was written to eliminate.
- **Trigger condition**:
  1. Add a fifth presentation — the KDoc at `OverlayPresentation.kt:40–41` invites exactly this ("Adding
     a new overlay screen means adding an implementation and a `resolve` mapping entry") — that is a lock
     type but sets `showsProgress = true` to render a bar (e.g. "3 of 5 focus blocks done").
  2. Start a session on a monitored app so the countdown notification is posted
     (`OverlayService.kt:1097`), then trigger the new lock while the session is still live.
  3. Pull down the shade: the `Chronometer` in `notification_countdown.xml` keeps counting down and then
     up in negative time, because `cancelCountdownNotification()` at `:831` was skipped.
  4. `adb shell dumpsys notification --noredact | grep -A5 grayzone` shows the chronometer notification
     still posted behind the lock screen.
- **Blast radius**:
  - *Callers*: three main-source reads, all in `showOverlay` (`:831`, `:923`, `:1011`), and four
    implementations — `FrictionPresentation.kt:7` (`true`), `LockPresentation.kt:9`,
    `BudgetLockPresentation.kt:7`, `ScheduleLockPresentation.kt:7` (all `false`); confirmed by direct
    search and consistent with `evidence/reference-index.md:574`. Also asserted by
    `OverlayPresentationTest.kt:43`, `:56`, `:71`, `:84`.
  - *Process death*: none — the property is a constant on a stateless object.
  - *Config change*: none.
  - *Foreground/background*: the countdown notification is the app's only background-visible surface, so
    the mis-answered question at `:831` is precisely the one whose consequence is visible while the app
    is backgrounded.
  - *Cross-process*: no.
  - *Persistence*: no. Note that `cancelCountdownNotification()` (`:753–756`) only replaces the
    notification; it does not clear `ACTIVE_UNTIL` or cancel the expiry alarm, so notification state and
    session state are independent — which is why "who owns the notification" needs to be an explicit
    contract rather than a side effect of styling.
  - *Release-only*: no.
- **Fix** (minimal): split the one property into the questions actually being asked, defaulting the two
  new ones so the existing four implementations need no edits beyond the lock screens' honest values —

  ```kotlin
  interface OverlayPresentation {
      val showsProgress: Boolean                                 // styling only, as named
      val clearsCountdownNotification: Boolean get() = !showsProgress   // temporary shim
      val subTextTracksCountdown: Boolean get() = showsProgress         // temporary shim
  }
  ```

  then set `clearsCountdownNotification = true` explicitly on `LockPresentation`,
  `BudgetLockPresentation` and `ScheduleLockPresentation`, `false` on `FrictionPresentation`, and change
  `OverlayService.kt:831` to `if (presentation.clearsCountdownNotification)` and `:1011` to
  `if (presentation.subTextTracksCountdown)`. The defaults keep behaviour identical today while making
  each decision a declaration a new implementation must consider. The watchlist's alternative — invert
  the rule to "cancel unless the mode is FRICTION" — is worse: it reintroduces a mode conditional at the
  call site, which is the duplication `resolve()` exists to remove.
- **Rewrite justification**: n/a — two added interface properties with defaults and two changed
  conditionals.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, new test
  `every lock-type presentation clears the countdown notification`, iterating
  `listOf(LockPresentation, BudgetLockPresentation, ScheduleLockPresentation)` and asserting
  `clearsCountdownNotification` is `true` for each and `false` for `FrictionPresentation`. Written as an
  iteration over a list so a fifth presentation added to the list is covered automatically; it fails
  before the fix (the property does not exist) and passes after.
- **Existing test conflict**: none — the four `assertFalse/assertTrue(p.showsProgress)` assertions
  (`OverlayPresentationTest.kt:43`, `:56`, `:71`, `:84`) stay true, since `showsProgress` keeps its
  styling meaning and its current values.
- **Verification**: `confirmed` (evidence tier `T1`) that one property drives three decisions and that
  the `:831` comment describes a different predicate from the property's name — all four lines were read
  directly. The consequence is latent by construction (it needs a fifth presentation), which is why this
  is Medium and not higher: there is no current user-visible defect, only a re-openable one.
- **Build scope**: `everyday-build`
- **Constitution**: II (Consistent Strategy-Pattern Implementation — the strategy interface under-models
  its own contract, so the call site infers three behaviours from one flag); IV (the property name
  documents one of the three meanings, and the `:831` comment silently redefines it).
- **Affected surfaces**: `evidence/prior-fix-watchlist.md` "Fragile fixes" #3 is this item and had no
  owning task; this finding closes it. The countdown-notification half of prior defect P1 lives at
  `OverlayService.kt:706–751` and `:753–756` (RU-04, F-003's notification half), and
  `res/layout/notification_countdown.xml` holds the `Chronometer` (RU-09, T028). Watchlist "Fragile
  fixes" #4 is adjacent but distinct — the never-negative guard exists in only one function — and is
  RU-04's.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-102 — The lock countdown is a tick counter, not a deadline: it is snapshotted once and decremented by a timer that stops in deep sleep, so a long lockout screen shows a wrong remaining time and expires late  [Medium]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `OverlayPresentation.initialSeconds()` / `countdownText()` / `subText()` (lines 50–52), with
  `app/src/main/java/com/grayzone/app/service/overlay/LockPresentation.kt` →
  `LockPresentation.initialSeconds()` (lines 16–17) as the only implementation for which it matters;
  driven by `OverlayService.kt:1004–1016`
- **Issue**: the interface's countdown contract is *"you are told the deadline once, and thereafter you
  are told a tick count"*. `initialSeconds(SecondsInputs)` receives `now` and `lockedUntil` exactly
  once, at construction; `countdownText(secondsRemaining: Int)` and `subText(secondsRemaining: Int)` then
  receive only an integer that the service produces by decrementing a field once per `delay(1000)`.
  There is no way for a presentation to re-derive remaining time from the wall clock, because after the
  first call it is never given the clock again. Two consequences follow, both invisible for a 3–30 s
  friction wait and both real for a lock screen that can legitimately be displayed for up to 24 hours:
  - **The timer does not track real time.** `delay` on `Dispatchers.Main` is `Handler.postDelayed`, i.e.
    `SystemClock.uptimeMillis`, which does not advance while the device is suspended. Nothing dismisses
    an overlay on screen-off (`handleScreenOff()`, `OverlayService.kt:415–436`, pauses the session and
    nulls `lastForegroundPackage` but leaves the window and the job), so a lock screen displayed when the
    screen goes off simply freezes its countdown. On wake, "Remaining lockout time" reads high by the
    whole suspended interval, and `onCountdownFinished` — which for `LockPresentation` is what re-shows
    the friction screen so re-entry is tracked (`LockPresentation.kt:19–26`) — fires that much later than
    `lockedUntil`. The user is shown, and held to, a lockout longer than the one the app promised.
  - **It drifts even awake.** Each iteration is `delay(1000)` *plus* the cost of the loop body, so the
    period is always slightly over a second; over thousands of ticks the displayed value diverges from
    `lockedUntil` in the same direction. There is no resynchronisation anywhere in the loop.
  The app already knows this is the wrong pattern and has the right one in-tree twice over: the countdown
  *notification* anchors a `Chronometer` to `SystemClock.elapsedRealtime()` (`OverlayService.kt:721`) so
  it renders real time without ticks, and the whole VPN subsystem uses `elapsedRealtime` deltas for the
  same reason (`VpnHoldLock.kt:9` documents it: *"Remaining time prefers `State.startedElapsedMs` so
  wall-clock changes cannot skip the wait"*). This is the third instance of the clock-discipline theme
  `findings.md` records at C5/C6/F-021, from the opposite direction: those findings are about trusting
  the wall clock too much, this one is about a screen that stops consulting any clock at all.
- **Trigger condition**:
  1. Monitor an app; set the session to 1 minute and the lockout to 60 minutes.
  2. Open the app, sit through friction, let the session expire so the LOCK screen appears showing
     "59m 5xs".
  3. Press the power button and leave the device untouched for 15 minutes.
  4. Wake the device. The LOCK screen still shows roughly the value it had at step 3, not 15 minutes
     less. Compare with the truth:
     `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep locked_until"`
     against `adb shell date +%s%3N`.
  5. Leave it displayed until it reaches `0m 0s` and note the wall-clock time: the friction screen is
     re-shown roughly 15 minutes after `locked_until` had already passed.
- **Blast radius**:
  - *Callers*: `initialSeconds` is called at `OverlayService.kt:839`; `countdownText` at `:908` (initial
    render) and `:1010` (every tick); `subText` at `:928` and `:1012`. `secondsRemaining` — the tick
    counter — is the service field at `:104`, written at `:839` and `:1007` and read at `:846`, `:908`,
    `:928`, `:944` and `:1005–1013`. All four presentations implement the three methods; only
    `LockPresentation` returns a value large enough for the drift to matter, since
    `FrictionPresentation` returns 3–30 and the two static locks return `0`.
  - *Process death*: the job and the window die together, and `onCreate` does not re-raise a lock screen
    (it only re-arms expiry alarms, `:157–162`), so process death resets the drift — the policy engine
    re-derives the lock from `lockedUntil` on the next app change. That is the only resynchronisation
    that exists.
  - *Config change*: none. Rotation does not rebuild the view or restart the job, so the tick count
    carries over unchanged.
  - *Foreground/background*: the drift accumulates fastest exactly when the app is backgrounded or the
    screen is off, because that is when the device suspends.
  - *Cross-process*: no.
  - *Persistence*: no writes from the loop. `lockedUntil` in prefs remains the truth, and the screen
    diverges from it — so the UI and the policy engine can disagree about whether the user is locked out,
    with the policy engine correct.
  - *Release-only*: no.
- **Fix** (minimal): give the presentations the clock on every tick instead of a tick count. Change the
  two rendering methods to take the same `SecondsInputs` the initialiser gets —
  `fun countdownText(inputs: SecondsInputs): String` and `fun subText(inputs: SecondsInputs): String` —
  and have `OverlayService`'s loop rebuild `SecondsInputs(now = System.currentTimeMillis(), lockedUntil, waitSeconds)`
  each second, exiting when `initialSeconds(inputs) <= 0` rather than when a counter reaches zero. That
  keeps the loop's cadence approximate (it only drives repaints) while making the *value* and the
  *completion* derive from the deadline. `FrictionPresentation` keeps working unchanged in spirit
  (`waitSeconds` becomes a deadline captured once at `showOverlay`); the three lock screens gain
  correctness for free.
- **Rewrite justification**: the two method signatures change, which is a contract change rather than a
  patch, and it is required: the current signatures make the defect unfixable inside the presentations,
  because a presentation is never handed a clock after construction. The alternative patch — clamping the
  displayed value against `lockedUntil` inside `LockPresentation` — is impossible for the same reason,
  and duplicating the deadline into a presentation field would make the stateless singletons stateful and
  break the shared-`object` model the package is built on. Scope is four implementations plus one loop.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/LockCountdownDeadlineTest.kt`, asserting that
  `LockPresentation.countdownText(SecondsInputs(now = t0 + 900_000, lockedUntil = t0 + 3_600_000, …))`
  equals `formatDuration(2700)` regardless of how many ticks elapsed — i.e. that the rendered value is a
  function of the clock and not of a counter. Fails before the fix (the method cannot see the clock),
  passes after.
- **Existing test conflict**: `OverlayPresentationTest.kt:46`, `:47`, `:59`, `:60`, `:74`, `:75`, `:87`,
  `:88` all call `countdownText(Int)` / `subText(Int)` and must be updated to the new signatures. This is
  the largest test change any fix in this unit requires, and it is mechanical.
- **Verification**: `confirmed` (evidence tier `T1`) for the design fact — the presentations receive the
  clock once and a counter thereafter, the loop decrements a field, and nothing resynchronises.
  `needs-verification` (tier `T4`) for the magnitude, which depends on how aggressively a given device
  suspends while a foreground service holds no wakelock.
  - *Missing*: a device to measure the divergence between the displayed value and `locked_until` across a
    real suspend interval, and to confirm that the main-looper timer is in fact frozen rather than merely
    delayed.
  - *Procedure*: steps 1–5 above, with `adb shell dumpsys deviceidle` before and after step 3 to confirm
    the device actually entered a doze/suspend state.
  - *Severity effect*: **Medium** as filed — incorrect displayed time and a late self-dismiss under a
    narrow condition (a lock screen left visible across a suspend). Escalate to **High** if a device shows
    the lock screen holding the user past `locked_until` for a *user-noticeable* interval with no way to
    dismiss it other than the "Leave App" button, since the app would then be enforcing a longer
    punishment than it promised. It does not reach Critical: the policy engine still holds the correct
    deadline and re-derives it on the next app change, so protection is not disabled — only the screen is
    wrong.
- **Build scope**: `everyday-build`
- **Constitution**: II (the same "compute remaining time from a monotonic anchor" logic exists in
  `OverlayService.startCountdownNotification` and across the VPN hold subsystem, and the overlay
  countdown reimplements it as a tick counter instead); IV (`subText` for the lock screens is documented
  as "Remaining lockout time", which the value stops being once the timer drifts).
- **Affected surfaces**: `OverlayService.kt:1004–1016` is the loop (RU-04, T018) and must change with the
  signatures. `findings.md`'s clock-tampering theme (C5/F-110, C6/F-111, F-021) is the same discipline
  applied elsewhere; T034 should group this record with them so one design decision — "remaining time is
  always derived from a deadline, never counted" — is applied across the overlay, policy and VPN
  subsystems in one pass.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/java/com/grayzone/app/service/OverlayService.kt)`
- **Disposition**: `verified`

### F-103 — `LockPresentation.initialSeconds` truncates a `Long` to `Int` *after* clamping, so an out-of-range `lockedUntil` wraps negative and the lockout screen is skipped entirely  [Medium]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/LockPresentation.kt` →
  `LockPresentation.initialSeconds()` (lines 16–17)
- **Issue**: the expression is
  `((inputs.lockedUntil - inputs.now) / 1000).coerceAtLeast(0).toInt()`. The `coerceAtLeast(0)` is
  applied to a `Long` and is then discarded by `.toInt()`, which in Kotlin keeps the **low 32 bits**
  rather than saturating. Any remaining lockout longer than `Int.MAX_VALUE` seconds (≈68 years, i.e.
  `lockedUntil - now > 2.147 × 10¹² ms`) therefore wraps, and frequently wraps **negative**: for
  `lockedUntil - now = 4 × 10¹² ms` the result is `4_000_000_000.toInt()` = **-294,967,296**. A negative
  `secondsRemaining` fails the loop guard `while (secondsRemaining > 0)` (`OverlayService.kt:1005`) on the
  first evaluation, so `onCountdownFinished` runs immediately — and for `LockPresentation` that means
  `dismissOverlay()` followed by `showFriction()` (`LockPresentation.kt:22–24`). The user-visible outcome
  inverts the intent: a `lockedUntil` so far in the future that the app should be locked essentially
  forever instead **skips the lock screen entirely**, shows the friction wait, and (via
  `markAppUnlocked`) grants a session. The guard the author wrote to keep the value sane is defeated by
  the conversion on the same line, which is what makes this worth filing rather than dismissing as
  theoretical: the intent is explicit and the implementation does not achieve it.
- **Trigger condition**:
  1. In-app writes cannot produce the value: `markAppUnlocked` clamps the lockout to ≤24 h
     (`OverlayService.kt:1065`, `:1089`) and `executeCommand(UpdateState)` derives it from
     `PerAppLimits.lockoutMinutes` (`:454`, coerced to `15..1440`). The reachable route is the external
     backup, whose `applyInto` writes arbitrary keys and types with no allowlist and no schema gate.
  2. Plant it directly to reproduce: force-stop the app, then
     `adb shell "run-as com.grayzone.app sh -c 'sed -i s/locked_until_com.example.app\" value=\"[0-9]*\"/locked_until_com.example.app\" value=\"5780000000000\"/ /data/data/com.grayzone.app/shared_prefs/grayzone_prefs.xml'"`
     (any value ≳ `now + 2.15 × 10¹²`).
  3. Open the monitored app.
  4. Observe the "Pause." friction screen instead of "Locked.", and a granted session afterwards:
     `adb shell "run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | grep active_until"`.
- **Blast radius**:
  - *Callers*: `initialSeconds` is called once per overlay at `OverlayService.kt:839`;
    `SecondsInputs.lockedUntil` is read only by `LockPresentation` (direct search over `app/src`). The
    `lockedUntil` argument reaches `showOverlay` from `executeCommand(ShowLockoutScreen)` (`:498`, from
    `SessionCommand.ShowLockoutScreen.lockedUntil`) and `handleSessionExpired()` (`:653`, read straight
    from prefs at `:634`) — the second path performs no range check of any kind.
  - *Process death*: none — the value is re-read from prefs on every overlay, so the defect recurs on
    every attempt until the pref is corrected.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: no. Prefs are read only in the default process.
  - *Persistence*: yes — the poisoned `locked_until_<pkg>` lives in SharedPreferences, is snapshotted
    into the external backup (`StateBackupManager` writes `prefs.all`), and nothing repairs it:
    `validateConfiguration()` (`GrayzoneApplication.kt:176–215`) validates wait/session/lockout
    *settings*, not the per-package deadline keys.
  - *Release-only*: no.
- **Fix** (minimal): saturate instead of truncating —
  `((inputs.lockedUntil - inputs.now) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()`. That
  preserves the author's intent for every in-range value and turns the pathological case into "locked for
  as long as we can represent" — fail-closed — instead of "not locked at all". A complete fix also gives
  the per-package deadline keys the same treatment the settings keys already get in
  `validateConfiguration()`, which belongs to RU-01/RU-02.
- **Rewrite justification**: n/a — one `coerceAtLeast` becomes one `coerceIn` on the same line.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, new test
  `lock presentation saturates an out-of-range lockout instead of wrapping`, asserting
  `assertEquals(Int.MAX_VALUE, LockPresentation.initialSeconds(SecondsInputs(now = 0L, lockedUntil = 4_000_000_000_000L, waitSeconds = 8)))`.
  Before the fix it returns `-294967296`; after, `Int.MAX_VALUE`.
- **Existing test conflict**: none. `OverlayPresentationTest.kt:62` (`60`) and `:63` (`0`, the
  already-expired case) both remain correct under `coerceIn`.
- **Verification**: `confirmed` (evidence tier `T1`) for the arithmetic and the resulting control flow —
  Kotlin's `Long.toInt()` truncation is specified, the loop guard is `> 0`, and
  `LockPresentation.onCountdownFinished` dismisses and re-shows friction. The *trigger* is inherited from
  the unvalidated backup restore rather than proven here, which is why this is Medium.
  - *Missing*: nothing for the mechanism. For the end-to-end path, a device on which a poisoned backup is
    restored rather than a pref edited by hand.
  - *Procedure*: steps 1–4 above; optionally repeat by planting the value in `/sdcard/.grayzone/` and
    clearing app data, to exercise the realistic delivery route.
  - *Severity effect*: **Medium** as filed — narrow condition, and the immediate outcome (friction instead
    of a lock) is at least *some* friction rather than none. Escalate to **High** if the backup-restore
    route is demonstrated end-to-end, since a value planted in shared storage would then defeat the
    lockout for every affected package with no user signal. It stays below Critical because the wrapped
    value is as likely to be a large positive as a negative, and the positive case over-blocks rather
    than under-blocks.
- **Build scope**: `everyday-build`
- **Constitution**: III is not engaged (no exception); II (the clamp expresses an intent the sibling
  implementations do not share and the conversion silently discards); V (the fix is provable by a pure
  unit test, and none exists for out-of-range input).
- **Affected surfaces**: `OverlayService.kt:916–917` sets `ProgressBar.max`/`progress` from the same
  value, so a negative or wrapped `totalSeconds` is also handed to the widget (harmless today only
  because the lock screens set it `GONE`), and `:944`'s `waitedMs = (totalSeconds - secondsRemaining)`
  inherits it. `handleSessionExpired()` (`:630–657`) passes `lockedUntil` from prefs with no validation
  and is the second entry point. The backup-restore root cause is the confirmed lead "Backup restore has
  no schema gate and no key allowlist" in `findings.md` (owner T020).
- **Observed against**: `557a963` (the overlay package has no uncommitted modifications)
- **Disposition**: `verified`

### F-104 — `BudgetLockPresentation` and `ScheduleLockPresentation` are structurally identical copies, and the three lock screens triplicate the same empty `onSkip` body  [Low]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/BudgetLockPresentation.kt` (whole
  file, lines 4–22) and `app/src/main/java/com/grayzone/app/service/overlay/ScheduleLockPresentation.kt`
  (whole file, lines 4–22); the triplicated `onSkip` also at
  `app/src/main/java/com/grayzone/app/service/overlay/LockPresentation.kt` → `LockPresentation.onSkip()`
  (lines 28–30)
- **Issue**: the two static lock screens are the same 19 lines with two string literals swapped — same
  `title` ("Locked."), same `skipButtonLabel`, same `showsProgress = false`, same
  `countdownTextSizeSp = 72f`, same `countdownText` returning `"—"`, same `initialSeconds = 0`, same
  empty `onCountdownFinished` with the same comment ("No countdown; the screen stays up until the user
  leaves the app."), same empty `onSkip` with the same comment ("Leaving a lock screen records no
  friction skip.") — which `LockPresentation.kt:28–30` repeats a third time. Constitution II forbids
  duplicated logic, and the duplication is not inert: five of the findings in this file
  (F-095, F-097, F-099, F-101, F-102) require touching two or three of these files to make one change,
  and every future lock screen starts by copying one of them, which is how `countdownTextSizeSp = 72f`
  came to be applied to a screen that renders an em dash (F-097). There is no user-visible consequence
  today, which is why this is Low.
- **Trigger condition**:
  1. Make any behavioural change that applies to "lock screens" — for example the
     `clearsCountdownNotification` property in F-101's fix, or the reduced `countdownTextSizeSp` in
     F-097's.
  2. Observe that it must be written identically into three files, and that nothing fails if one is
     missed: `./gradlew testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` passes
     because each presentation is asserted independently (`OverlayPresentationTest.kt:66–90`), never as a
     family.
- **Blast radius**:
  - *Callers*: both objects are referenced from exactly one main-source site,
    `OverlayPresentation.resolve()` (`OverlayPresentation.kt:65–66`) — re-verified by direct search over
    `app/src`, and consistent with `evidence/reference-index.md:600`, `:606`. Both are also constructed
    in `OverlayPresentationTest.kt:68`, `:82`, `:128–129`.
  - *Process death*: none — stateless `object` singletons.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: no. Neither object is loaded in `:a11y`, which holds only
    `GrayzoneAccessibilityService` (`evidence/cross-process-inventory.md`).
  - *Persistence*: none — neither writes.
  - *Release-only*: no. R8 keeps both: each is reachable from `resolve()`, which is reachable from
    `OverlayService.showOverlay`, and `OverlayService` is a manifest component with a generated keep rule
    (`evidence/r8.md` §F2). Neither is dead code and **neither may be deleted**.
  - **Callers re-verification note**: per the standing warning that `evidence/reference-index.md`
    contains confirmed errors (it listed `AdBlockVpnService.onRevoke()` as an existing override with zero
    matches, and marked `DnsTrafficBus.clear()` test-only with a live caller), both objects were
    re-checked by direct search before this record proposed anything. This finding proposes **no
    deletion** — only extraction of a shared base.
- **Fix** (minimal): introduce one small abstract base in the package and have both objects extend it —

  ```kotlin
  /** Static lock screens: no countdown, no self-dismiss, no friction-skip accounting. */
  abstract class StaticLockPresentation(
      private val body: String,
      private val sub: String
  ) : OverlayPresentation {
      final override val title = "Locked."
      final override val skipButtonLabel = "\u2190 Leave App"
      final override val showsProgress = false
      final override fun bodyText(prompt: String) = body
      final override fun countdownText(secondsRemaining: Int) = "\u2014"
      final override fun subText(secondsRemaining: Int) = sub
      final override fun initialSeconds(inputs: SecondsInputs) = 0
      final override fun onCountdownFinished(ctx: CountdownFinishContext) = Unit
      final override fun onSkip(ctx: SkipContext) = Unit
  }

  object BudgetLockPresentation : StaticLockPresentation("Daily usage limit reached.", "Resets tomorrow")
  object ScheduleLockPresentation : StaticLockPresentation("Focus mode / schedule is active.", "Try again later")
  ```

  `countdownTextSizeSp` stays per-object so F-097's fix can differ per screen if wanted. Do this **after**
  F-097 and F-101 land, so the shared members are extracted in their corrected form rather than
  needing a second pass.
- **Rewrite justification**: n/a — the two objects keep their names, identity and public behaviour;
  only their shared members move up.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, new test
  `all static lock screens share the lock contract`, iterating
  `listOf(BudgetLockPresentation, ScheduleLockPresentation)` and asserting `title`, `skipButtonLabel`,
  `showsProgress`, `countdownText(anyInt)` and `initialSeconds(anyInputs)` as a family. Currently these
  are asserted per-object, so a family-level regression is invisible; after the extraction the iteration
  covers any future subclass automatically.
- **Existing test conflict**: none — `OverlayPresentationTest.kt:66–90` asserts values the extraction
  preserves exactly, and `:128–129` asserts the `onSkip` no-ops, which `final override fun onSkip(…) = Unit`
  keeps.
- **Verification**: `confirmed` (evidence tier `T1`) — both files were read in full and diffed by
  inspection; they differ only in the KDoc line, `bodyText`'s literal and `subText`'s literal.
- **Build scope**: `everyday-build`
- **Constitution**: II (duplicated logic across strategy implementations), I (the duplication is what
  invites copy-paste growth in a package designed for extension)
- **Affected surfaces**: `LockPresentation.kt:28–30` holds the third copy of the empty `onSkip` and can
  adopt the base for that member only if it keeps its own `countdownText`, `initialSeconds` and
  `onCountdownFinished`; that is a judgement call for the implementer and this record does not require
  it.
- **Observed against**: `557a963` (the overlay package has no uncommitted modifications)
- **Disposition**: `verified`

### F-105 — `CountdownFinishContext.packageName` and `.appName` are read by no implementation, and `SkipContext` hands its callback values the callback's own context already owns  [Low]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` →
  `CountdownFinishContext` (lines 17–24, specifically the `packageName` and `appName` properties at
  `:18–19`) and `SkipContext` (lines 29–33, specifically `recordFrictionSkip: (String, Long) -> Unit` at
  `:32`)
- **Issue**: two unrelated-looking symptoms of one root cause — the context objects were shaped
  speculatively rather than from what the implementations need.
  - `CountdownFinishContext.packageName` and `.appName` are read by **none** of the four
    implementations. `FrictionPresentation.onCountdownFinished` uses `markAppUnlocked` and
    `dismissOverlay`; `LockPresentation` uses `dismissOverlay`, `isStillForeground` and `showFriction`;
    `BudgetLockPresentation` and `ScheduleLockPresentation` use nothing. Both properties are supplied at
    `OverlayService.kt:1023–1024` and are already captured by the lambdas beside them
    (`markAppUnlocked = { markAppUnlocked(packageName) }`, `:1025`), so they are pure redundancy that
    makes the context look like it grants capabilities it does not.
  - `SkipContext.recordFrictionSkip` takes `(String, Long)` and its only implementation calls it as
    `ctx.recordFrictionSkip(ctx.packageName, ctx.waitedMs)` (`FrictionPresentation.kt:21`) — the context
    passing its own fields back into its own callback. A presentation that passed a *different* package
    name would compile and would silently record a friction skip against the wrong app, corrupting the
    usage statistics that drive the Home screen and streaks. A zero-argument callback makes that
    unrepresentable.
  No user-visible consequence exists today, which is why this is Low.
- **Trigger condition**:
  1. Read the four implementations and observe that `ctx.packageName` / `ctx.appName` appear in none of
     them: `rg "ctx\.(packageName|appName)" app/src/main/java/com/grayzone/app/service/overlay/` returns
     exactly one line, `FrictionPresentation.kt:21`, and that one is `SkipContext`'s property, not
     `CountdownFinishContext`'s.
  2. For the latent half: change `FrictionPresentation.kt:21` to
     `ctx.recordFrictionSkip("com.other.app", ctx.waitedMs)` — it compiles, every test passes, and the
     skip is recorded against a package the user never opened.
- **Blast radius**:
  - *Callers*: `CountdownFinishContext` is constructed at `OverlayService.kt:1022` and at
    `OverlayPresentationTest.kt:97`, `:115`, `:118`. `SkipContext` is constructed at
    `OverlayService.kt:946` and `OverlayPresentationTest.kt:127–132`. `recordFrictionSkip`'s single
    supplier is the lambda at `OverlayService.kt:946–950`, which forwards to
    `OverlayService.recordFrictionSkip(pkg, ms)` (`:1102`) on `Dispatchers.IO`.
  - *Process death*: none — the contexts are per-invocation value holders.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: no.
  - *Persistence*: indirectly — `recordFrictionSkip` is the write path for friction-skip statistics, so
    the mis-attribution risk described above lands in `UsageDatabase`.
  - *Release-only*: no. R8 removes unread constructor properties from a non-`data` class only if it can
    prove them unused; both classes are plain `class` declarations (not `data class`), so no `equals`/
    `hashCode`/`toString` keeps them alive, but nothing in `evidence/r8.md` reports them removed and this
    record does not rely on that either way.
  - **Reference-index correction**: `evidence/reference-index.md:563` lists
    `CountdownFinishContext.{packageName, appName}` as referenced by "presentations'
    `onCountdownFinished()`". **That row is wrong** — direct search over `app/src` finds no such
    reference. This is a third confirmed error in the index, after `AdBlockVpnService.onRevoke()` and
    `DnsTrafficBus.clear()`; it is recorded here so T059–T064 treat the index's overlay-package rows with
    the same caution as the rest.
- **Fix** (minimal): delete the two unread properties from `CountdownFinishContext` and drop
  `recordFrictionSkip`'s parameters —
  `val recordFrictionSkip: () -> Unit`, with `OverlayService.kt:946` supplying
  `{ serviceScope.launch(Dispatchers.IO) { recordFrictionSkip(packageName, waitedMs) } }` from the
  enclosing scope it already has, and `FrictionPresentation.kt:21` becoming `ctx.recordFrictionSkip()`.
  `SkipContext.packageName` and `.waitedMs` then become unread too and go with them, leaving `SkipContext`
  as a single callback — at which point the parameter should simply be that callback and the class
  deleted. **Deletion is proposed only for the members proven unread by direct search over `app/src`
  (main and test), not on the strength of the reference index.**
- **Rewrite justification**: n/a — property and parameter removals; no behaviour changes.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`, update
  `lock-type screens ignore skip while friction records skip` (`:124–134`) to the zero-argument callback.
  The assertion that survives — friction records exactly one skip, the three lock screens record none —
  is unchanged, and it becomes impossible for it to pass while recording against the wrong package.
- **Existing test conflict**: yes, mechanical. `OverlayPresentationTest.kt:97–104` uses named arguments
  `packageName = "pkg", appName = "App"` and `:115`/`:118` use positional
  `CountdownFinishContext("pkg", "App", …)`; all three must drop the two leading arguments. `:127–132`
  must drop the lambda parameters.
- **Verification**: `confirmed` (evidence tier `T1`) — established by direct search over `app/src`
  (both `main` and `test`) rather than from the reference index, which is wrong on this exact row.
- **Build scope**: `everyday-build`
- **Constitution**: I (unused declarations in a public-ish contract); II (a callback signature that
  permits a caller to contradict its own context)
- **Affected surfaces**: `evidence/reference-index.md:563` needs correcting (T064's dead-code inventory
  should not inherit the wrong row). `OverlayService.kt:946` and `:1022–1029` are the two construction
  sites the fix edits (RU-04, T018).
- **Observed against**: `557a963` (the overlay package has no uncommitted modifications)
- **Disposition**: `verified`

### F-106 — `ScheduleLockPresentation`'s KDoc promises the screen "ends when the schedule window ends"; nothing in the app ever ends it  [Low]

- **Review unit**: RU-05
- **Location**: `app/src/main/java/com/grayzone/app/service/overlay/ScheduleLockPresentation.kt` → the
  class KDoc (line 3) together with `ScheduleLockPresentation.initialSeconds()` (line 13) and
  `ScheduleLockPresentation.onCountdownFinished()` (lines 15–17)
- **Issue**: the file documents a lifecycle it does not implement. The KDoc reads *"Schedule / focus-mode
  lock screen (no countdown; ends when the schedule window ends)"*, and `subText` tells the user *"Try
  again later"*. But `initialSeconds` returns `0`, `onCountdownFinished` is empty, and the schedule
  predicate `ScheduleManager.isCurrentlyScheduled()` is evaluated at exactly **one** main-source site —
  `OverlayService.buildSessionState()` (`:480`) — which runs only on an app-change or screen-off
  transition (`processForegroundAppChanged` `:354`, `backgroundApp` `:398`). Verified by direct search
  over `app/src/main`: `isCurrentlyScheduled` has that one caller. There is no alarm, no timer and no
  periodic re-evaluation tied to a schedule rule's end time, and no receiver for `ACTION_TIME_TICK`.
  So the screen's exit condition is not "the schedule window ended" but "the user tapped ← Leave App, or
  some *other* overlay replaced this one, or midnight arrived, or the service died". The same applies to
  a Quick Focus session started from the UI (`QuickFocusCard.kt:102` →
  `ScheduleManager.startFocusMode`), which `isCurrentlyScheduled` also covers: when the focus timer
  elapses, the lock screen in front of the user does not react. By contrast
  `BudgetLockPresentation`'s claim ("resets at midnight") **is** backed — `performMidnightReset()` calls
  `dismissOverlay()` at `OverlayService.kt:273` — so this is one unbacked claim, not a package-wide
  pattern, and the asymmetry is what makes the schedule claim misleading rather than merely aspirational.
  Consequence: a comment that a future maintainer will trust when deciding whether a schedule-end
  dismissal already exists. The user-facing outcome (the screen persisting) is F-005's, so this record
  claims only the documentation defect and the missing mechanism it points at.
- **Trigger condition**:
  1. Create a schedule rule ending five minutes from now, or start a five-minute Quick Focus session.
  2. Open a monitored app so the SCHEDULE_LOCK screen appears.
  3. Do nothing and wait past the end time, leaving the screen displayed.
  4. Observe the "Locked. / Focus mode / schedule is active. / Try again later" screen still displayed
     after the window has ended. `adb shell dumpsys window windows | grep -i grayzone` confirms the
     window is still attached; `adb shell dumpsys alarm | grep -i grayzone` shows no alarm scheduled for
     the schedule's end (only session-expiry alarms, if any).
- **Blast radius**:
  - *Callers*: `ScheduleLockPresentation` is referenced from one main-source site,
    `OverlayPresentation.resolve()` (`:66`), re-verified by direct search. The absent mechanism would
    belong to `ScheduleManager` (`data/ScheduleManager.kt:66`) and `OverlayService.buildSessionState()`
    (`:480`).
  - *Process death*: none for the documentation defect. For the missing mechanism: any schedule-end
    alarm added as the real fix must be re-armed after process death and boot, the way
    `SessionExpiryScheduler` is (`OverlayService.onCreate` `:157–162`, `BootReceiver.kt:36–44`).
  - *Config change*: none.
  - *Foreground/background*: the gap is visible precisely when the user leaves the screen alone, i.e.
    when no transition event occurs to re-evaluate the schedule.
  - *Cross-process*: no — but note the practical dependency: the only thing that re-evaluates the
    schedule today is an app-change event originating in `:a11y`
    (`GrayzoneAccessibilityService.kt:86–89`), so if that process is killed the schedule is never
    re-evaluated at all.
  - *Persistence*: no writes. `SCHEDULE_RULES_JSON` is read through `ScheduleManager.getScheduleRules()`,
    whose silent `catch (_: Exception) { emptyList() }` is C3/F-110's territory, not this record's.
  - *Release-only*: no.
- **Fix** (minimal): correct the comment to describe what happens —
  *"Schedule / focus-mode lock screen. No countdown: the screen is replaced on the next app-change
  transition once the schedule window has ended (see F-005 — nothing dismisses it on an app switch
  today)."* The behavioural fix, which is larger and belongs with F-005's, is to arm an alarm at each
  active rule's end time (and at a focus session's end) that re-runs the policy evaluation for
  `lastForegroundPackage`, reusing the `SessionExpiryScheduler` pattern rather than inventing a second
  one. This record requires only the comment; the mechanism is recorded so it is not lost.
- **Rewrite justification**: n/a — a comment change.
- **Test to add**: `jvm-unit` — none is meaningful for a comment. For the mechanism, if it is built:
  `app/src/test/java/com/grayzone/app/data/ScheduleEndDecisionTest.kt`, asserting the pure "next rule
  end after `now`" computation returns the earliest end among enabled rules and `null` when none is
  active. Recorded as a follow-on, not as a requirement of this finding's minimal fix.
- **Existing test conflict**: none. No test asserts anything about a schedule-lock lifecycle;
  `OverlayPresentationTest.kt:79–90` covers only the strings and `initialSeconds = 0`.
- **Verification**: `confirmed` (evidence tier `T1`) — the KDoc text, `initialSeconds = 0`, the empty
  `onCountdownFinished`, and the single `isCurrentlyScheduled()` call site were each read directly, and
  the absence of any schedule-end alarm was checked by searching `app/src` for alarm scheduling
  (`SessionExpiryScheduler` and `VpnHoldExpiryScheduler` are the only two schedulers, and neither takes a
  schedule rule).
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: `everyday-build`
- **Constitution**: IV (a comment asserting behaviour the code does not implement — the same class of
  drift as `OverlayService.kt:164–166`'s stale shared-process claim, F-008)
- **Affected surfaces**: `BudgetLockPresentation.kt:3`'s "resets at midnight" is the backed counterpart
  and needs no change. The user-visible persistence of both screens is F-005 (RU-04), whose fix makes the
  corrected comment true for the app-switch case but still not for the "user sits on the screen" case.
  `data/ScheduleManager.kt:66` is where a schedule-end trigger would live (RU-02, T020).
- **Observed against**: `557a963` (the overlay package has no uncommitted modifications)
- **Disposition**: `verified`

## Summary

| ID | Title (short) | Severity | Verification |
|---|---|---|---|
| F-095 | Friction grants a session without checking the app is still in front | High | confirmed (T1) |
| F-096 | `isStillForeground` is unsound in multi-window and for launcher-less packages | High | needs-verification (T2) |
| F-097 | Lock screens' only exit control can be clipped off-screen | High | needs-verification (T2) |
| F-098 | `dismissOverlay()` orphans the view when `removeView` throws | Medium | confirmed (T1) mechanism / needs-verification (T4) outcome |
| F-099 | `resolve()` fails open to the session-granting screen, pinned by a test | Medium | confirmed (T1) |
| F-100 | Friction wait duration is unclamped, so `0` defeats friction instantly | Medium | confirmed (T1) |
| F-101 | `showsProgress` answers three different questions | Medium | confirmed (T1) |
| F-102 | Lock countdown is a tick counter, not a deadline | Medium | confirmed (T1) design / needs-verification (T4) magnitude |
| F-103 | `.toInt()` truncation defeats `coerceAtLeast(0)` in `LockPresentation` | Medium | confirmed (T1) |
| F-104 | Budget and schedule lock presentations are identical copies | Low | confirmed (T1) |
| F-105 | Unread context properties and a self-referential callback signature | Low | confirmed (T1) |
| F-106 | Schedule-lock KDoc promises an end mechanism that does not exist | Low | confirmed (T1) |

**Counts**: 12 findings — **0 Critical, 3 High, 6 Medium, 3 Low**. Block F-095…F-109 used through
F-106; **F-107, F-108 and F-109 are unused** and remain available.

No Critical is filed by this unit. The Critical-severity outcomes that touch these files —
no overlay at all on `addView` failure, and lock screens that never leave the screen — are F-001/C1 and
F-005, both owned by RU-04, and this unit's job was to establish what the presentations themselves
contribute. The nearest this unit comes is F-097, held at High under the audit's restraint precedent
because the measurement that would make it Critical needs a device.

## Verdict on the three required coverage areas

**1. Window/view removal on every exit path, including exception paths — one defect found (F-098), and
the removal path is otherwise sound but has exactly one owner.**
Every overlay any presentation raises is removed by the single function `dismissOverlay()`
(`OverlayService.kt:1034–1045`), reached from six sites: `performMidnightReset()` (`:273`),
`ACTION_STOP` (`:661`), `onDestroy()` (`:766`), the top of `showOverlay()` (`:825`), the skip-button
listener (`:952`), and the presentations' `CountdownFinishContext.dismissOverlay` (`:1026`). All six run
on the main thread — traced individually — so the most common cross-thread `removeView` failure cannot
occur today. The function is idempotent, null-checks its field, and cancels `countdownJobUI` first, so
re-entrancy from inside the countdown job (`FrictionPresentation.kt:17`, `LockPresentation.kt:22` both
call it from within the job they thereby cancel) is safe: the cancellation takes effect only at the next
suspension point, so `LockPresentation`'s subsequent `showFriction()` still runs, and the new job it
creates is parented to `serviceScope` rather than to the cancelled job, so it is not transitively
cancelled. That is correct but undocumented and fragile — worth a comment in any fix that touches it.
The one hole is the exception path: `overlayView = null` runs whether or not `removeView` succeeded
(F-098), so a still-attached window becomes permanently untracked. Two boundary paths are cited rather
than re-filed: no *app switch* reaches `dismissOverlay()` at all (F-005), and `handleScreenOff()`
(`:415–436`) does not dismiss either. Beyond exception paths, `showOverlay`'s revoked-permission early
return at `:821` precedes the `dismissOverlay()` at `:825`, so a stale overlay and its running countdown
survive that path — recorded in F-095's *Affected surfaces* for T018 rather than filed here, since RU-04
owns that branch and currently credits it as a model.

**2. Duplicated presentation-selection conditionals — presentation *selection* is genuinely
centralised; the duplication has moved into the *properties* and into a second derivation of the wait
duration.**
Direct search over `app/src` for `OverlayMode.` finds no `when`/`if` on a mode value anywhere except
`OverlayPresentation.resolve()` (`:63–68`); `showOverlay` contains no `isLocked` / `isBudgetLock` /
`isScheduleLock` branch, which is what the interface's KDoc claims it replaced (`:36–38`) — that claim is
accurate and the refactor should be credited. Four qualifications:

- The centralised mapping fails **open** on an unrecognised mode, and a passing test pins it (F-099).
- Selection was centralised but *behaviour* was not: three different questions are re-derived from the
  single `showsProgress` flag at `:831`, `:923` and `:1011` (F-101). This is the closest thing to
  "diverged copies" in the package — the copies have not yet diverged in value, but they have already
  diverged in *meaning*, and the `:831` comment ("Lock-type screens (no progress bar) …") records the
  divergence in prose.
- The two static lock presentations are structurally identical files (F-104), so any change to the "lock
  screen" concept is a three-file edit with no test that would catch a missed one.
- One genuine duplicated derivation was found outside this unit and is **unfiled** by anyone: the
  friction wait duration is computed twice. `SessionPolicyEngine` emits
  `ShowWaitScreen(pkg, appName, DEFAULT_WAIT_SECONDS)` with a hardcoded `5` that ignores both the global
  and per-app preferences (`SessionPolicyEngine.kt:101`, field at `policy/SessionModels.kt:44`), and
  `executeCommand`'s branch discards it (`OverlayService.kt:491–495`) in favour of re-reading the prefs
  inline at `:836–843`. The two derivations have diverged and only the second is used;
  `SessionPolicyEngineForegroundTest.kt:212` asserts the discarded one. Recorded in F-100's *Affected
  surfaces* and flagged to T024/T018 below.

**3. Overlay behaviour on rotation and split-screen entry — two defects found (F-096, F-097), both
device-dependent, and one structural fact that limits how bad rotation can be.**
The overlay is a `WindowManager` window, not an Activity view, so rotation neither destroys nor recreates
it: there is no `onConfigurationChanged` anywhere in `app/src/main` (zero matches) and no
`updateViewLayout` call, so the same view tree is simply re-laid-out at the new size. That rules out the
double-add / recreate-loss class of rotation bug entirely — worth stating, because it is the class one
would look for first. What remains is measurement: the card is `WRAP_CONTENT` inside a `MATCH_PARENT`
`FrameLayout` with no scroll container anywhere in the codebase (zero `ScrollView` matches), and its
height is dominated by `sp`-scaled text the presentations mandate, so in landscape or at a raised font
scale the last child — the only exit control — can be clipped away (F-097). For split-screen, the
package's foreground predicate is a single-package equality that cannot represent two simultaneously
visible apps, which breaks `LockPresentation`'s explicit anti-"silent untracked access" guard in the
unsafe direction (F-096). Both are `needs-verification` with device procedures and severity-per-outcome,
because **no device or emulator is available in this environment**. A third, benign observation: the
overlay's `MATCH_PARENT × MATCH_PARENT` params mean a lock raised for app A in one split-screen pane
covers app B in the other — that is F-005's "stays on top of unrelated apps" outcome and is cited, not
re-filed.

## Not resolvable statically

| Question | Why | Where it is recorded |
|---|---|---|
| Does the card actually overflow the display, and at which orientation and font scale? | Needs real display metrics and a rendered view hierarchy (`uiautomator dump` vs `wm size`) | F-097 *Verification* |
| In split-screen, which package does `lastForegroundPackage` end up holding, and for how long does the untracked window last? | Needs the real ordering of `TYPE_WINDOW_STATE_CHANGED` events from a multi-window session | F-096 *Verification* |
| Does the main-looper countdown actually freeze across a device suspend, and by how much does the lock screen diverge from `locked_until`? | Needs a real doze/suspend cycle; `Handler`-based `delay` behaviour under suspend is device- and OEM-dependent | F-102 *Verification* |
| Does a real `removeView` failure ever leave the view attached, or does the platform only throw once it is already detached? | Needs a device on which the throw can be provoked and the window list inspected | F-098 *Verification* |
| Do the friction ticks survive long enough after screen-off to reach zero on a given OEM? | Same suspend-timing dependency; determines whether F-095's screen-off trigger fires every time or only sometimes | F-095 *Verification* |
| Whether a poisoned `/sdcard/.grayzone/` backup can actually deliver `wait_seconds = 0` or an absurd `locked_until` end to end | The restore path is owned by T020 and the chain was not re-executed here | F-100, F-103 *Verification* |

Gradle was not run for this unit: nothing in it requires compilation evidence, all twelve findings rest
on source read directly, and the two commands a maintainer may want are given inline
(`./gradlew testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`).

## Duplicates, contradictions and corrections against findings already filed

1. **No duplicate of F-005.** The overlap was checked deliberately, because F-005 mentions
   `FrictionPresentation` in passing (*"during it, `markAppUnlocked` still grants a full session for an
   app the user already left"*). F-095 is a distinct root cause and survives F-005's fix: F-005's fix
   implements `executeCommand`'s `DismissOverlay` branch, but `SessionPolicyEngine.evaluate` **never
   emits `DismissOverlay` for `AppEvent.AppBackgrounded`** (`SessionPolicyEngine.kt:104–133`), and
   `handleScreenOff()` does not dismiss, so the screen-off trigger is untouched by it. F-005 correctly
   identifies the symptom from the executor's side; F-095 fixes the presentation that grants the session.
   Both should ship; neither is sufficient alone.
2. **Correction to F-001's *Affected surfaces*.** F-001 states *"The sibling `removeView` catch at
   `:1039–1042` is compliant and shows the intended house style."* The catch is compliant; the two lines
   around it are not — `overlayView = null` at `:1044` executes on the exception path (F-098), and the
   catch logs `e.message` without the throwable, the package or the mode, which is the same
   thin-logging pattern F-001 itself objects to at `:993`. The sentence should be amended when F-001 is
   actioned so the "house style" endorsement does not survive into the punch list.
3. **Correction to `evidence/reference-index.md:563`.** It lists
   `CountdownFinishContext.{packageName, appName}` as referenced by "presentations'
   `onCountdownFinished()`". Direct search over `app/src` finds zero such references. This is a third
   confirmed index error alongside `AdBlockVpnService.onRevoke()` and `DnsTrafficBus.clear()`, and it
   errs in the *safe* direction (claiming live, not claiming dead) — but it means the index's
   overlay-package rows were not derived from a reliable pass. Every callers list in this file was built
   by direct search, and the one deletion proposed (F-105) was verified against `app/src` main **and**
   test before being proposed.
4. **`evidence/prior-fix-watchlist.md` "Fragile fixes" #3 now has an owner** — it is F-101. The watchlist
   had no task assigned to it, so it would otherwise have been dropped. Its diagnosis is confirmed and
   its suggested remedy is partly rejected: "invert the rule to cancel unless the mode is FRICTION"
   reintroduces a mode conditional at the call site, which is the duplication `resolve()` exists to
   eliminate; the property split in F-101's fix achieves the same result without it.
5. **No contradiction with the C5/C6 clock findings, but a third direction.** C5 and C6 concern policy
   decisions trusting the wall clock. F-102 is the overlay's inverse: once a lock screen starts, it
   consults no clock at all. A fix for either that is designed in isolation will not cover the other;
   T034 should group F-102 with C5/C6/F-021 under one "remaining time is always derived from a monotonic
   anchor" decision.
6. **Two surfaces observed in this unit that no finding owns**, forwarded rather than filed, because both
   live in units already audited:
   - `SessionCommand.ShowWaitScreen.waitSeconds` is populated with a hardcoded `5` that ignores the
     user's configured wait, and is then discarded by the executor (`SessionPolicyEngine.kt:101`,
     `policy/SessionModels.kt:44`, `OverlayService.kt:491–495`, asserted by
     `SessionPolicyEngineForegroundTest.kt:212`). → **T024 / T018**.
   - `showOverlay`'s revoked-permission branch returns at `:821` **before** the `dismissOverlay()` at
     `:825`, so a stale overlay and its running countdown survive a mid-session permission revocation —
     the same scenario F-001/C1 and T022 are built around. → **T018**.
7. **Nothing in this unit originates in uncommitted work.** `git status --porcelain` for
   `app/src/main/java/com/grayzone/app/service/overlay/` is empty and the package was last touched in
   `0a41a1a`; the uncommitted `OverlayService.kt` diff (77 changed lines, all defensive per the
   watchlist) neither introduces nor conceals any of these twelve. One uncommitted change is adjacent and
   is credited: `clearAnimation()` before `removeView` at `:1038` is part of the diff and is correct —
   F-098's defect is the two lines around it, which predate it.

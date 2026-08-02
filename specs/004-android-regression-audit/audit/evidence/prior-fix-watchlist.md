# Prior-Fix Regression Watchlist (T012)

**Audit**: `004-android-regression-audit` · **Baseline commit**: `557a963` ("updated new changes") with
uncommitted working-tree modifications · **Prior feature under watch**:
`specs/002-fix-timeout-accessibility-bugs/` (implemented in commit `d71e60f`).

**Source artifacts read**: `spec.md`, `plan.md`, `research.md`, `data-model.md`, `quickstart.md`,
`tasks.md`, `contracts/session-expiry.md`, `contracts/accessibility-permission.md`,
`checklists/requirements.md`. All present and non-empty.

**Intervening feature**: `specs/003-premium-ui-redesign/` (commit `0a41a1a`) **did** touch six files
that `002` had modified — `MainActivity.kt`, `PrefsKeys.kt`, `BootReceiver.kt`, `OverlayService.kt`,
`HomeScreen.kt`, `OnboardingScreen.kt` — which is why those files are re-verified line-by-line below.
Its UX decisions are out of scope; only its effect on the `002` fixes was checked.

**Prior defects found: 10.**

---

## Watchlist

| # | Prior defect | Fix that was applied | Files/functions touched | Still present in current code? | Evidence (file:line) |
|---|---|---|---|---|---|
| P1 | Session countdown notification ran past `00:00` into negative values — the count-down `Chronometer` in the ongoing notification is self-rendering off `SystemClock.elapsedRealtime()` and nothing cancelled it at `activeUntil` (research.md R1; FR-001) | Expiry event cancels/replaces the countdown notification; `startCountdownNotification` refuses to post a chronometer when remaining time is already `<= 0`; pure `clampRemainingMs` gives the never-negative display value | `OverlayService.startCountdownNotification()`, `cancelCountdownNotification()`, `handleSessionExpired()`; `SessionExpiryDecision.clampRemainingMs()` | intact | `app/src/main/java/com/grayzone/app/service/OverlayService.kt:710-714` (guard), `:645-648` (`ClampCountdown` → cancel), `:753-756` (cancel impl); `app/src/main/java/com/grayzone/app/policy/SessionExpiryDecision.kt:52-54` |
| P2 | Monitored app never locked at session end — the lock was scheduled **only** by an in-memory coroutine `delay()` on `Dispatchers.IO`, which is deferred in Doze/screen-off and lost on process death (research.md R2; FR-002) | Replaced with a Doze-safe per-package `AlarmManager` alarm at `activeUntil`, delivered to a manifest-registered receiver, which hands off to `OverlayService` and applies a pure decision | `SessionExpiryScheduler.kt` (new), `SessionExpiryReceiver.kt` (new), `SessionExpiryDecision.kt` (new), `OverlayService.handleSessionExpired()` / `onStartCommand()`, `AndroidManifest.xml` | intact | `app/src/main/java/com/grayzone/app/service/SessionExpiryScheduler.kt:41-70`; `SessionExpiryReceiver.kt:18-34`; `OverlayService.kt:630-657`, `:670-672`; `app/src/main/AndroidManifest.xml:97-100` |
| P3 | Superseded in-memory lock path left behind as dead code (`lockoutJobs` map + `scheduleLockoutCheck()`); T020 / Constitution I required its removal | Deleted entirely | `OverlayService.kt` | intact | Zero occurrences of `lockoutJobs` or `scheduleLockoutCheck` anywhere under `app/src` (repo-wide grep); the delay-based lock path is absent from `OverlayService.kt:630-678` |
| P4 | Alarm was not armed at every point where `ACTIVE_UNTIL` is written, and a paused/cleared session could leave a stale alarm pending (contract SE-7) | `SessionExpiryScheduler.arm()` at both session-grant sites; `cancel()` when the policy engine clears the session and at midnight reset | `OverlayService.markAppUnlocked()`, `executeCommand(UpdateState)`, `performMidnightReset()` | intact | `OverlayService.kt:1097-1098` (grant), `:526-530` (`UpdateState`), `:519-524` (cancel on clear), `:266-267` (midnight cancel) |
| P5 | In-flight session lost across process death / reboot — nothing re-armed the schedule (contract SE-5) | Re-arm from persisted `ACTIVE_UNTIL` for every monitored app in `OverlayService.onCreate()`; `BootReceiver` restarts `OverlayService` so the same path runs after boot | `OverlayService.onCreate()`, `BootReceiver.onReceive()` | intact (re-arm half only — see Fragile fixes #2) | `OverlayService.kt:150-162`; `app/src/main/java/com/grayzone/app/service/BootReceiver.kt:36-44` |
| P6 | Accessibility permission re-prompt loop — the gate and every `ON_RESUME` re-check bounced the user to onboarding / Accessibility settings whenever the service read as disabled (research.md R4; FR-006/FR-007) | Pure prompt-once-per-loss policy plus a persisted flag: prompt at most once per contiguous loss, reset when the permission is observed enabled again | `AccessibilityPromptState.kt` (new, object `AccessibilityPromptPolicy`), `PrefsKeys.ACCESSIBILITY_PROMPTED_FOR_LOSS` (new), `MainActivity.evaluateScreen()`, `OnboardingScreen` `ON_RESUME`, `HomeScreen` | intact | `app/src/main/java/com/grayzone/app/AccessibilityPromptState.kt:11-33`; `PrefsKeys.kt:17`; `MainActivity.kt:189-217`; `ui/OnboardingScreen.kt:43-54`; `ui/HomeScreen.kt:49-59` and `:184-195` (permission buttons are user-tapped, no auto-launch) |
| P7 | Accessibility permission state was inferred loosely; FR-006 requires reading the **actual** current system state, exception-safely | `isAccessibilityServiceEnabled()` parses `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` through a pure, tested matcher and degrades to `false` with a logged warning | `AppUtils.isAccessibilityServiceEnabled()`, `AccessibilityServiceIds` | intact | `app/src/main/java/com/grayzone/app/AppUtils.kt:108-126` |
| P8 | An unhandled throwable in the accessibility service crashes it, and a crash is one of the triggers that makes Android disable (revoke) an accessibility service (contract AH-1) | `onServiceConnected` and the whole of `onAccessibilityEvent` wrapped with explicit, logged (`LogComponent.ACCESSIBILITY`) handling — no silent catch; `onUnbind` now logs suspected revocation | `GrayzoneAccessibilityService.onServiceConnected()`, `onAccessibilityEvent()`, `onUnbind()` | intact (and further hardened — see Elevated-attention) | `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt:41-63`, `:66-112`, `:124-130` |
| P9 | Service requested interactive-window retrieval it never used, making it look heavyweight to the OS (contract AH-2; tasks T017/T018) | Capability dropped in both the runtime `AccessibilityServiceInfo` and the XML config; `ACTION_APP_CHANGED` payload preserved | `GrayzoneAccessibilityService.onServiceConnected()`, `res/xml/accessibility_service_config.xml` | intact | `GrayzoneAccessibilityService.kt:42-53` (no flags requested; comment at `:49-51` records the intent); `app/src/main/res/xml/accessibility_service_config.xml:1-8`; zero occurrences of `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` / `flagRetrieveInteractiveWindows` under `app/src` |
| P10 | The app must never be the revoker: no `disableSelf()`, and `OverlayService.onCreate()` (then sharing a process with the accessibility service) must not throw (contract AH-3, FR-005) | Confirmed no `disableSelf()`; receiver registration in `onCreate` wrapped in logged try/catch | `GrayzoneAccessibilityService`, `OverlayService.registerAppChangeReceiver()` / `registerScreenOffReceiver()` | intact | Zero occurrences of `disableSelf` under `app/src`; `OverlayService.kt:164-168` (rationale + calls), `:178-227` (both registrations wrapped) |

---

## REGRESSED or PARTIALLY REVERTED (candidate findings)

**None.** All ten `002` defects verified above are still fixed in the current working tree. Both
mechanisms that carry the fixes — the Doze-safe `SessionExpiryScheduler`/`SessionExpiryReceiver`/
`SessionExpiryDecision` triad and the `AccessibilityPromptPolicy` prompt-once flag — are present,
wired at every call site the contracts name, and covered by the two JVM test classes the feature
added. The intervening `003-premium-ui-redesign` commit rewrote large parts of `MainActivity.kt`,
`OnboardingScreen.kt`, `HomeScreen.kt`, and `OverlayService.kt`, but it **strengthened** rather than
weakened the accessibility gate: it stopped the overlay permission from driving the prompt flag, so
granting only Accessibility no longer resets/re-triggers the prompt
(`MainActivity.kt:190-196`, versus the pre-`003` version where `isEnabled = ready` conflated both
permissions).

One near-miss worth recording so it is not mistaken for a regression later: `OverlayService.onCreate()`
implements only the *arming* half of contract SE-5 and silently skips a session whose `activeUntil`
already passed while the process was dead (`OverlayService.kt:157-162`). Git history shows this was
never implemented rather than removed — `git log -S "if (activeUntil > now)"` and
`git log -S "handleSessionExpired"` on `OverlayService.kt` both return only `d71e60f`, the `002`
implementation commit itself. It is therefore an original gap (detailed below), not a reversion.

## Fragile fixes

1. **Alarm arming is duplicated across three call sites instead of being owned by one writer.**
   `ACTIVE_UNTIL` is written in `markAppUnlocked()` (`OverlayService.kt:1091-1098`) and in
   `executeCommand(UpdateState)` (`OverlayService.kt:514-531`), and each site must separately remember
   to call `SessionExpiryScheduler.arm()`. Any future path that persists `ACTIVE_UNTIL` without arming
   silently restores the original "counts down, never locks" defect, with no test failure.
   *Robust form*: funnel every `ACTIVE_UNTIL` write through a single helper (or a small session-store
   type) that arms/cancels as part of the write, so the invariant is enforced by construction rather
   than by reviewer memory.

2. **Contract SE-5's "if already past, apply the expiry decision immediately" is not implemented.**
   `OverlayService.onCreate()` arms only when `activeUntil > now` and does nothing otherwise
   (`OverlayService.kt:157-162`). Today the negative timer is cleared incidentally, because
   `startForeground(NOTIF_ID, buildNotification())` at `OverlayService.kt:144` overwrites the same
   notification id with the plain "Grayzone is active" notification, and the lock is applied by the
   policy-engine foreground backstop. Both are accidental, not designed: a future change that moves
   `startForeground` off `NOTIF_ID`, or that posts the countdown before the re-arm loop, would resurface
   a negative counter after process death.
   *Robust form*: add the `else` branch that calls `handleSessionExpired(pkg)` for already-expired
   sessions, as SE-5 specifies.

3. **"No negative countdown behind a lock" depends on a convention, not a type.**
   The countdown is cancelled in `showOverlay()` only when `presentation.showsProgress == false`
   (`OverlayService.kt:830-833`). A new lock-type `OverlayPresentation` that returns
   `showsProgress = true` would show a lock with a free-running negative chronometer still in the
   shade. The property name describes a progress bar, not countdown ownership.
   *Robust form*: give `OverlayPresentation` an explicit `clearsCountdown` (or `isLockType`) property,
   or invert the rule to "cancel unless the mode is FRICTION".

4. **The never-negative invariant is enforced by one guard inside one function.**
   `startCountdownNotification()` is the only place that checks `remainingMs <= 0`
   (`OverlayService.kt:710-714`). It is reachable from three callers — `markAppUnlocked()`,
   `executeCommand(UpdateState)`, and the `ACTION_START_COUNTDOWN` intent handler
   (`OverlayService.kt:662-667`), the last of which accepts `active_until` from an intent extra. Any new
   code path that builds a count-down `Chronometer` `RemoteViews` directly bypasses the guard.
   Note also that `cancelCountdownNotification()` replaces the notification with the generic one rather
   than rendering a `00:00` frame — the shipped invariant is "never shows negative", which is weaker
   than the spec's "stops at `00:00`".

5. **The prompt-once flag is a bare `Boolean` that three independent sites must keep consistent.**
   `AccessibilityPromptPolicy.onObserved()` is pure and returns the value to persist, but persisting it
   is the caller's duty: `MainActivity.kt:196` writes it, `OnboardingScreen.kt:50-51` independently
   resets it to `false` on re-grant. A future caller that consults the policy and forgets to persist
   `promptedForLoss` re-opens the prompt loop. Separately, `evaluateScreen()` performs a
   `SharedPreferences` write as a side effect of being *evaluated* (`MainActivity.kt:189-207`, invoked
   inside the state initializer at `:209`), so "deciding" and "consuming the one allowed prompt" are the
   same call — a structurally risky shape in a Composable.
   *Robust form*: move the read-decide-persist trio into one function that owns the key, and keep
   composition-time evaluation side-effect-free.

6. **Crash-safety is per-override hand-written try/catch.** `GrayzoneAccessibilityService` is protected
   because `onServiceConnected` (`:41-63`) and `onAccessibilityEvent` (`:69-112`) each wrap their whole
   body. `onInterrupt` (`:115-117`) and `onDestroy` (`:119-122`) are unguarded (currently trivial), and
   any new override added later inherits no protection. Nothing structural prevents the
   crash → OS-revocation path from reopening.

7. **A stale rationale now documents the opposite of reality.** `OverlayService.kt:164-166` still states
   that `OverlayService` "shares a process with `GrayzoneAccessibilityService`, and an uncaught crash
   here makes Android disable (revoke) the accessibility service." The uncommitted manifest change moved
   the accessibility service into its own `:a11y` process (`AndroidManifest.xml:82`), so that comment no
   longer explains why the guard exists — the kind of drift that invites a future reviewer to delete the
   guard as unnecessary.

## Test coverage of the prior fixes

Only two test classes lock in `002` behavior, and both cover **pure decision logic only**. Neither
`app/src/test/java/com/grayzone/app/` nor `app/src/androidTest/java/com/grayzone/app/` contains a
single test that exercises the Android glue the two defects actually lived in. `androidTest` holds only
three Room tests (`data/MigrationTest.kt`, `data/PeakHourAggregationTest.kt`,
`data/UsageDaoStatsTest.kt`), so there is **zero instrumentation coverage of either prior fix**.

| Prior defect | Locked in by a test? | Test file → method |
|---|---|---|
| P1 (never-negative countdown) | Partly — the clamp *rule* only | `app/src/test/java/com/grayzone/app/policy/SessionExpiryDecisionTest.kt` → `clamp returns remaining while session active`, `clamp returns zero exactly at expiry`, `clamp never returns negative after expiry` (`:34-47`). **Gap**: nothing covers `startCountdownNotification`'s `remainingMs <= 0` guard or `cancelCountdownNotification`, which is where the visible defect was. |
| P2 (lock reliably applied at expiry) | Partly — the decision only | `SessionExpiryDecisionTest` → `expired in foreground within lockout shows lock`, `expired one ms ago in foreground shows lock`, `expired but not foreground clamps only`, `expired foreground but lockout window over clamps only`, `not yet expired is noop`, `no session is noop`, `disabled is noop even when expired`, `on break is noop even when expired` (`:51-105`). **Gap**: `SessionExpiryScheduler` and `SessionExpiryReceiver` have no tests at all — the exact-vs-inexact alarm selection, the `SecurityException` fallback, and the receiver's `startForegroundService` hand-off are entirely unverified. |
| P3 (dead delay-based path removed) | **No test** — enforced only by grep/review. A reintroduced `delay()`-based lock would not fail any test. |  |
| P4 (arm at every `ACTIVE_UNTIL` write, cancel on pause) | **No test.** This is the highest-value gap: it is exactly the invariant described in Fragile fixes #1, and contract SE-7 (pause cancels the pending alarm) is listed in the contract as on-device-only verification. |  |
| P5 (re-arm after process death / boot) | **No test.** Contract SE-5 was assigned to manual on-device validation (`quickstart.md` §2 Scenarios C/D), and T021 — the only task that would have run it — is the one task still unchecked in `tasks.md:110`. So the re-arm path has never been verified by anything, automated or manual, in this repo. |  |
| P6 (prompt-once-per-loss) | Yes — the state machine only | `app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt` → `granted does not prompt and clears flag`, `granted clears a previously set flag`, `first loss prompts once and sets flag`, `subsequent loss does not prompt again`, `regrant resets so a future loss prompts once more` (`:14-56`). **Gap**: no test asserts the *wiring* — that `MainActivity.evaluateScreen()` persists `promptedForLoss`, or that `HomeScreen`/`OnboardingScreen` never auto-launch Accessibility settings. The loop the user experienced lived in the wiring, not the state machine. |
| P7 (read actual system state) | Yes, indirectly | `app/src/test/java/com/grayzone/app/AccessibilityServiceIdsTest.kt` → `null or blank setting is disabled`, `full component name matches`, `relative short component name matches`, `colon-separated list finds our service among others`, `substring lookalike does not false-positive`, `different package with same class suffix does not match`, `malformed entries are ignored` (`:13-56`) |
| P8 (service cannot crash) | **No test.** Exception-safety of `onAccessibilityEvent` / `onServiceConnected` is asserted nowhere; contract AH-1 was assigned to on-device validation, which was never run (T021). |  |
| P9 (no interactive-window retrieval) | **No test.** No assertion over `accessibility_service_config.xml` or the runtime `AccessibilityServiceInfo`. A future edit re-adding the flag would pass CI silently. |  |
| P10 (no `disableSelf`, `onCreate` cannot throw) | **No test.** Grep-enforced only. |  |

Supporting (not `002`-specific) coverage that touches the same area:
`app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt:40-84` asserts
`showsProgress` per presentation (`false` for all three lock types), which is the property the
countdown-cancel rule in Fragile fixes #3 piggybacks on — though the test asserts it as styling, not as
a countdown-clearing contract.

**Net**: 4 of 10 prior defects have some automated coverage; 6 have none. Every fix that lives in
Android glue rather than pure Kotlin is unprotected, which is precisely why regression here is possible.

## Elevated-attention files

Nine files carry uncommitted modifications against `HEAD` (`557a963`): 138 insertions, 35 deletions.
Direction of every diff read below is working tree **relative to** `557a963`.

| File | What the uncommitted change does | Flag |
|---|---|---|
| `app/build.gradle.kts` | Adds a `debug { }` block that sets `isDebuggable = false` and signs debug builds with the **release** key when `keystore.properties` exists (`:69-80`), so `installDebug` produces an APK signature-compatible with the shipped release. Adds `lint { checkReleaseBuilds = false }` (`:102-108`) to stop the release-only `lintVital` gate from trying to download `lint-gradle` from `dl.google.com`. | ⚠️ **Removed guard.** `checkReleaseBuilds = false` disables the only lint gate that blocks a release build on fatal issues — a network workaround permanently weakening a quality gate. `isDebuggable = false` on debug also silently disables debugger attach and `adb`-level debug tooling for the everyday build; `BuildConfig.DEBUG` stays `true`, so `GrayzoneLogger` verbosity is unaffected (`GrayzoneLogger.kt:37-40`). |
| `app/src/main/AndroidManifest.xml` | Single line: `android:process=":a11y"` on `GrayzoneAccessibilityService` (`:82`), isolating it from the main app process. | ⚠️ **Highest-risk change in the set.** It is a genuine hardening for prior defect P8/P10 (a main-process crash can no longer take the accessibility service down), but it invalidates every shared-process assumption in the `002` design, silently changes which process in-memory singletons live in, and is the root cause of the two flags below. Nothing in the test suite exercises a multi-process configuration. |
| `app/src/main/res/xml/accessibility_service_config.xml` | Removes `android:packageNames="@null"` (`:8`). | Behaviour-preserving: an absent `packageNames` and `@null` both mean "all packages". Contract AH-2 unaffected — no `accessibilityFlags` in either version. |
| `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` | Adds an early return in `onCreate()` when `Application.getProcessName() != packageName` (`:52-54`), so the `:a11y` process performs no prefs read, no `StateBackupManager.restoreIfWiped`, no WorkManager scheduling. Rewrites the comment at `:66-69` to describe the new process topology. | Correct and necessary for the split (`SharedPreferences` is not multi-process safe, and two writers would corrupt the anti-tamper backup). `getProcessName()` is API 28+, safe at `minSdk = 29` (`app/build.gradle.kts:28`). The `:a11y` process only uses the stateless `GrayzoneLogger`, so nothing it needs is skipped. |
| `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` | Three changes: (a) **deletes** both `ProtectionHealthRepository.updateAccessibilityStatus(true/false)` calls from `onServiceConnected`/`onDestroy`; (b) **adds** a watchdog that calls `startForegroundService(OverlayService, ACTION_APP_CHANGED_WATCHDOG)` on every window-state-change event (`:91-105`); (c) adds `onUnbind` revocation logging (`:124-130`). | ⚠️ **Two flags.** (1) The deletion leaves `ProtectionHealthRepository.updateAccessibilityStatus` (`data/ProtectionHealthRepository.kt:34-36`) with **no production caller** — dead code (Constitution I) — and pins `ProtectionStatus.accessibilityActive` to `false` forever, so `isFullyProtected` can never become `true` (`data/ProtectionStatus.kt:6-14`). `ProtectionStatusTest` still passes because it drives the repository directly, so the suite cannot catch this. Impact is currently latent: no screen reads `isFullyProtected`/`isDegraded` (`ui/VpnScreen.kt:51-53` reads only `vpnActive`), and the real accessibility gate reads `Settings.Secure` directly (`AppUtils.kt:108-126`), so prior defect P6/P7 is **not** regressed. (2) The watchdog is a background→foreground-service start issued from the `:a11y` process on every app switch; on Android 12+ it is exactly the call most likely to be refused *when it is needed* (i.e. when `OverlayService` is already dead and the app has no foreground component). It is wrapped in try/catch with a warning, so AH-1 holds, but it is best-effort recovery that will fail silently in the case it exists to handle. |
| `app/src/main/java/com/grayzone/app/service/OverlayService.kt` | The largest diff (77 changed lines), all defensive: wraps `performMidnightReset()` in logged try/catch (`:248-252`); moves `getAppName()` off the main thread in the midnight and `ShowLock` paths (`:269`, `:649-655`); adds the `ACTION_APP_CHANGED_WATCHDOG` handler (`:673-675`); adds `onTaskRemoved()` re-start alarm 2 s after a Recents swipe (`:680-704`); hoists `Gson` into a shared instance (`:98`, `:853`); `promptsList.random()` → `randomOrNull() ?: "Take a moment to reflect."` (`:863`); moves friction-skip logging onto an IO coroutine (`:943-951`); wraps the home-intent `startActivity` (`:957-965`); sets `overlayView` only after `addView` succeeds (`:989-994`); adds `clearAnimation()` before `removeView` (`:1038`). | No prior-fix impact: the P1/P2/P4/P5 mechanisms are untouched by this diff. Every change removes a crash or leak path. One cosmetic nit: `ON_ACCENT_DARK` was demoted from `const val` to `val` (`:96`) with no reason — inconsistent with the sibling constants. |
| `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` | Adds `@Volatile` to `isRunning`, `vpnInterface`, `vpnThread`, `resolverPool` (`:33-34`, `:86-89`); moves stream construction inside the `try` and installs a thread `UncaughtExceptionHandler` that restarts the tunnel after 3 s (`:347-358`); broadens the inner packet-loop catch from `IOException` to `Exception` (`:379`). | ⚠️ **Broadened catch.** `catch (e: Exception)` now swallows programming errors (NPE, index errors) into the same "VPN loop error, back off and retry" path (`:379-407`), so a deterministic bug becomes an invisible retry loop until the 5-strike restart at `:394-402`. It is logged, so not silent, but the error class is no longer distinguishable. The `@Volatile` additions and the uncaught handler are straightforwardly correct. |
| `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` | Widens the bloom-filter load catch from `Exception` to `Throwable` (`:151`). | ⚠️ **Broadened catch.** Now also swallows `Error` — notably `OutOfMemoryError` while loading the bloom filters — and resets `loadStarted` so the next call retries the same allocation. It does log (`:157`) and resets state coherently, so it is defensible, but catching `Throwable` deserves an explicit justification comment it currently lacks. |
| `app/src/main/java/com/grayzone/app/service/vpn/DnsTrafficBus.kt` | Adds `@Synchronized` to `clear()` (`:67`). | Complete and correct: `emit()` is already `@Synchronized` on the same object monitor (`:39`), so `clear()` now participates in the existing mutual exclusion rather than racing the VPN writer thread. |

Nothing in the nine diffs is commented-out logic, a hardcoded test value, a shortened timeout, or a
removed null check. The two items worth escalating as findings in their own right are
`checkReleaseBuilds = false` (a permanently disabled release quality gate) and the orphaned
`updateAccessibilityStatus` (dead code plus a permanently-false protection-health field).

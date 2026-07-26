# Implementation Plan: Fix Session Timeout Lock & Accessibility Permission Stability

**Branch**: `002-fix-timeout-accessibility-bugs` | **Date**: 2026-07-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-fix-timeout-accessibility-bugs/spec.md`

## Summary

Fix two behavioral defects in the foreground session-enforcement service module:

1. **Session timeout never locks and the countdown goes negative.** When a session (e.g. 10 min)
   reaches its end, the ongoing countdown **notification** (a countdown `Chronometer` built in
   `OverlayService.startCountdownNotification()`) keeps ticking past `00:00` into negative values,
   and the lock overlay often never appears. The lock is scheduled only via an in-memory coroutine
   `delay()` on `serviceScope` (`scheduleLockoutCheck()`), which is not Doze/screen-off safe and is
   lost on process death, so expiry enforcement is unreliable while the elapsed-realtime-driven
   notification keeps counting negative.

2. **Accessibility permission is repeatedly revoked and re-requested.** When the OS disables the
   accessibility service (aggressive OEM battery management / process death / crash), the app bounces
   the user back to onboarding and re-prompts on every `ON_RESUME`, producing a re-grant loop.

The fix introduces a single **reliable session-expiry mechanism** (Doze-safe `AlarmManager`
scheduling keyed to `activeUntil`, re-armed on service (re)start and boot, with the existing
enforce-on-next-foreground policy path as a backstop) that (a) clamps/cancels the countdown
notification at zero so it never shows negative, and (b) applies the lock at expiry. It also hardens
`GrayzoneAccessibilityService` against self-inflicted revocation and replaces the aggressive
re-prompt behavior with a **prompt-once-per-loss** state machine. All other observable behavior is
preserved and every pure-logic change is verified by the existing + new JVM unit-test suite.

## Technical Context

**Language/Version**: Kotlin (JVM 17 toolchain; Android Gradle Plugin project)

**Primary Dependencies**: Android SDK (`Service`, `AccessibilityService`, `AlarmManager`,
`NotificationManager`/`Chronometer` via `RemoteViews`, `WindowManager`, `BroadcastReceiver`),
`kotlinx.coroutines`; existing in-module collaborators via their current contracts
(`SessionPolicyEngine`, `PrefsKeys`, `ScheduleManager`, `DailyBudget`, `ProtectionHealthRepository`,
`OverlayMode`, `isAccessibilityServiceEnabled`).

**Storage**: `SharedPreferences` (`GrayzonePrefs`) — existing per-package keys `ACTIVE_UNTIL`,
`LOCKED_UNTIL`, `REMAINING_MILLIS`, `MONITORED_APPS`, `GRAYZONE_ENABLED`; **new** keys for the
accessibility prompt-once state. No Room schema changes.

**Testing**: JUnit4 JVM unit tests under `app/src/test` (behavior-preservation oracle + new tests for
the pure expiry/clamp/prompt-state logic). Command: `./gradlew :app:testDebugUnitTest`.

**Target Platform**: Android 10 (API 29)+ (includes Android 12+ exact-alarm policy considerations).

**Project Type**: Single Android application module (`app/`).

**Performance Goals**: Remain event-driven. Session expiry fires at most one alarm per active
session (no polling); no added work on the per-foreground-transition hot path.

**Constraints**:
- Prefer **no new user-facing dangerous permission**. Session-expiry alarms use a Doze-safe
  `AlarmManager` API that requires no runtime prompt (see research.md), with an exact-alarm upgrade
  only if already permitted.
- Doze / screen-off / process-death safe: enforcement must survive these and never depend solely on
  in-memory coroutine `delay()`.
- Behavior-preserving for all flows except the two corrected defects (spec FR-008).
- Scope bounded to the `service/` module plus the minimal UI touch points that drive the
  accessibility re-prompt loop (`MainActivity`, `OnboardingScreen`, `HomeScreen`); do not spill into
  unrelated modules (`service/vpn/`, `policy/` internals unchanged except additive helpers).

**Scale/Scope**: Edits to `OverlayService.kt` (expiry scheduling + notification clamp),
`GrayzoneAccessibilityService.kt` (defensive hardening), and the accessibility gate/prompt sites
(`MainActivity.kt`, `OnboardingScreen.kt`, `HomeScreen.kt`); new small classes under `service/` for
the expiry scheduler and the accessibility prompt-state logic; new `PrefsKeys` entries; new unit
tests. On Android 12+ an exact-alarm capability may be declared; no Room/API-signature changes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against `.specify/memory/constitution.md` v1.0.0:

| Principle | Gate | Status |
|-----------|------|--------|
| I. Zero Dead & Unused Code | The unreliable `delay()`-based lock path is replaced, not left dangling; any superseded code is removed. No new unused symbols. | ✅ PASS |
| II. Consistent Strategy-Pattern Implementation | Expiry decision (`clamp countdown` + `apply lock`) is centralized in one scheduler/decision point rather than duplicated at call sites; presentation strategy (`OverlayPresentation`) is unchanged. | ✅ PASS |
| III. Robust Error Handling | All new I/O boundaries (AlarmManager set/cancel, notification updates, settings intents, accessibility state reads) have explicit, actionable failure handling — no silent catches. | ✅ PASS |
| IV. No Commented-Out Code | Superseded logic is deleted, not commented. | ✅ PASS |
| V. Behavior-Preserving Removals | These are **intentional behavior changes** authorized by this feature (spec FR-001/002/005–007) and explicitly called out; all *other* behavior is preserved (FR-008) and verified by existing tests. | ✅ PASS (intentional, justified) |
| VI. Test-Verified Refactors | Pure logic (expiry/clamp decision, prompt-once state machine) is extracted and unit-tested; the full suite must pass before completion. | ✅ PASS |

**Initial gate: PASS.** No violations requiring Complexity Tracking.

**Post-Design re-check (after Phase 1)**: The design adds small, single-responsibility, independently
testable units (expiry scheduler + prompt-state logic) and one Doze-safe scheduling boundary; it
removes the unreliable in-memory-only lock path net of additions and introduces no public API changes.
Re-evaluated: **PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/002-fix-timeout-accessibility-bugs/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output (root-cause + decisions)
├── data-model.md        # Phase 1 output (state/keys/entities)
├── quickstart.md        # Phase 1 output (manual + automated validation)
├── contracts/
│   ├── session-expiry.md            # Internal contract: reliable expiry + notification clamp
│   └── accessibility-permission.md  # Internal contract: prompt-once-per-loss state machine
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
app/src/main/java/com/grayzone/app/
├── service/
│   ├── OverlayService.kt                 # EDIT: schedule reliable expiry; clamp/cancel countdown
│   │                                     #       notification at 00:00; re-arm on (re)start
│   ├── SessionExpiryScheduler.kt         # NEW: Doze-safe AlarmManager scheduling per package
│   ├── SessionExpiryReceiver.kt          # NEW: alarm callback → clamp notification + apply lock
│   ├── GrayzoneAccessibilityService.kt   # EDIT: defensive hardening (no crash-driven revocation)
│   └── BootReceiver.kt                   # EDIT (minimal): re-arm expiry alarms after boot
├── policy/
│   └── SessionExpiryDecision.kt          # NEW (pure): given now/activeUntil/lockedUntil/foreground
│                                         #       → {ClampCountdown, ShowLock, NoOp} (unit-tested)
├── AccessibilityPromptState.kt           # NEW (pure): prompt-once-per-loss state machine (unit-tested)
├── AppUtils.kt                           # (read) isAccessibilityServiceEnabled — unchanged
├── PrefsKeys.kt                          # EDIT: add accessibility prompt-state keys
├── MainActivity.kt                       # EDIT: use prompt-once state; stop forced re-prompt loop
└── ui/
    ├── OnboardingScreen.kt               # EDIT: prompt-once-per-loss on ON_RESUME
    └── HomeScreen.kt                     # EDIT: surface loss without auto-looping to settings

app/src/test/java/com/grayzone/app/
├── policy/SessionExpiryDecisionTest.kt   # NEW: clamp-at-zero + lock-at-expiry decision coverage
├── AccessibilityPromptStateTest.kt       # NEW: single-prompt-per-loss, reset-on-regrant coverage
└── (existing session/policy tests)       # MUST remain green (behavior preservation)
```

**Structure Decision**: Single Android application module (`app/`). Reliability-critical, hard-to-test
Android glue (AlarmManager, notification, receiver) is kept thin and delegates all decisions to two
**pure Kotlin** units — `SessionExpiryDecision` and `AccessibilityPromptState` — so the corrected
behavior is verified by JVM unit tests without a device. New service glue lives under `service/` to
stay inside the target module boundary; the accessibility gate edits touch the minimal UI sites that
own the re-prompt loop.

## Complexity Tracking

> No Constitution Check violations. This section is intentionally empty.

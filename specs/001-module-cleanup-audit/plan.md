# Implementation Plan: Module Cleanup & Strategy Consolidation Audit

**Branch**: `001-module-cleanup-audit` | **Date**: 2026-07-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-module-cleanup-audit/spec.md`

## Summary

Audit and clean up the **foreground session-enforcement service module** (`service/` package,
excluding `service/vpn/`) — `OverlayService`, `GrayzoneAccessibilityService`, `BootReceiver`, and the
`OverlayMode` display-mode constants. Remove provably dead/unreachable code and unused symbols,
delete any commented-out code, and consolidate the repeated `OverlayMode` type/state branching inside
`OverlayService.showOverlay()` (and its countdown loop) into a single strategy abstraction that owns
per-mode presentation and countdown-completion behavior. All currently-tested behavior is preserved;
no public API signature changes (the safe-to-change list is empty). Every change is verified green
against the existing JVM unit-test suite before being marked complete.

## Technical Context

**Language/Version**: Kotlin (JVM 17 toolchain; Android Gradle Plugin project)

**Primary Dependencies**: Android SDK (`Service`, `AccessibilityService`, `WindowManager`,
`BroadcastReceiver`, `RemoteViews`), Jetpack (`androidx.core`), Gson (custom-prompt parsing);
in-module collaborators only via existing public/internal contracts (`SessionPolicyEngine`,
`GrayscaleManager`, `ScheduleManager`, `DailyBudget`, `Prompts`, `UsageDatabase`, `PrefsKeys`,
`OverlayMode`).

**Storage**: `SharedPreferences` (`GrayzonePrefs`) and Room (`UsageDatabase`) — accessed through
existing keys/DAOs; no schema or key changes in scope.

**Testing**: JUnit4 (`app/src/test`, JVM/Robolectric-free logic tests) plus a small `androidTest`
Room suite. The pre-existing green suite is the behavior-preservation oracle. Command:
`./gradlew :app:testDebugUnitTest`.

**Target Platform**: Android 10 (API 29)+.

**Project Type**: Single Android application module (`app/`).

**Performance Goals**: Behavior- and performance-neutral. The event-driven foreground-transition
path and countdown cadence (1s tick) MUST remain unchanged; no added allocation on the hot
per-transition path.

**Constraints**:
- No public API signature changes (safe-to-change list is empty).
- Preserve all observable behavior verified by existing tests; preserve evaluation order and side
  effects in `showOverlay()` and the countdown-completion `when`.
- Indirect entry points (manifest-registered services/receiver, `BroadcastReceiver` callbacks) count
  as referenced and MUST NOT be treated as dead.
- Scope bounded to `service/` excluding `service/vpn/`; do not edit other modules except to delete a
  symbol the target module exclusively owns (none currently anticipated).

**Scale/Scope**: 3 source files (~1,232 LOC total: `OverlayService.kt` ~1,077, `BootReceiver.kt` ~64,
`GrayzoneAccessibilityService.kt` ~90) plus the shared `OverlayMode` constants in `PrefsKeys.kt`. New
strategy types added under `service/` (or a `service/overlay/` sub-package).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against `.specify/memory/constitution.md` v1.0.0:

| Principle | Gate | Status |
|-----------|------|--------|
| I. Zero Dead & Unused Code | Feature's core goal; removals only when provably unreachable/unreferenced. Known targets: `OverlayService.DEFAULT_LOCKOUT`. | ✅ PASS |
| II. Consistent Strategy-Pattern Implementation | Consolidate `OverlayMode` branching in `showOverlay()` into one strategy owning the decision; call site depends on the abstraction. | ✅ PASS (this is the design) |
| III. Robust Error Handling | No `catch` blocks are removed or weakened; all existing I/O-boundary handling (WindowManager add/remove, DB writes, settings intents) is preserved. No new silent catches. | ✅ PASS |
| IV. No Commented-Out Code | Any commented-out block found is deleted (none currently identified; mojibake comments are non-code text and out of scope). | ✅ PASS |
| V. Behavior-Preserving Removals | Test-only symbols (`OverlayMode.TINT`/`REMOVE_TINT`) are flagged, not removed, since removal would change tested behavior without an approved decision. | ✅ PASS |
| VI. Test-Verified Refactors | Every step gated on `:app:testDebugUnitTest` passing before completion. | ✅ PASS |

**Initial gate: PASS.** No violations requiring Complexity Tracking.

**Post-Design re-check (after Phase 1)**: The strategy abstraction introduces new small types but
removes duplicated conditionals net-of-additions and adds no new public API. Re-evaluated: **PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/001-module-cleanup-audit/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── overlay-presentation-strategy.md   # Internal contract for the strategy
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
app/src/main/java/com/grayzone/app/
├── service/
│   ├── OverlayService.kt                 # TARGET: dead-code removal + strategy consolidation
│   ├── GrayzoneAccessibilityService.kt   # TARGET: audit for unused symbols/imports
│   ├── BootReceiver.kt                   # TARGET: audit for unused symbols/imports
│   ├── overlay/                          # NEW (proposed): strategy implementations
│   │   └── OverlayPresentation.kt        # NEW: per-OverlayMode presentation strategy
│   └── vpn/                              # OUT OF SCOPE (separate module)
└── PrefsKeys.kt                          # OverlayMode constants (shared; TINT/REMOVE_TINT flagged)

app/src/test/java/com/grayzone/app/
├── OverlayModeTest.kt                    # Existing — pins OverlayMode constants
├── SessionStateLogicTest.kt             # Existing — session gating behavior
├── LockGuardLogicTest.kt                # Existing — lock-guard behavior
├── PromptResolutionTest.kt              # Existing — prompt pool resolution
└── policy/                              # Existing — SessionPolicyEngine coverage
```

**Structure Decision**: Single Android application module (`app/`). New strategy types are introduced
in a `service/overlay/` sub-package to keep the presentation strategy cohesive and independently
testable while staying inside the target module boundary. No files outside `service/` are modified
(the `OverlayMode` constants in `PrefsKeys.kt` are read, not changed).

## Complexity Tracking

> No Constitution Check violations. This section is intentionally empty.

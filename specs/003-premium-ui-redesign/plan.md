# Implementation Plan: Premium Lightweight UI Redesign

**Branch**: `003-premium-ui-redesign` | **Date**: 2026-07-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-premium-ui-redesign/spec.md`

## Summary

Replace Grayzone’s dark-dominant, visually dense Compose UI (and matching overlay surfaces) with a
**light-first, minimal, premium visual system** that feels calm, fast, and intentional — without
changing protection, session, VPN, or commitment-lock **behavior**.

Approach: redefine the shared theme tokens (`Color` / `Theme` / `Type`) as the single source of truth
for color roles and typography; rebuild primary destinations and dialogs against that system with
decluttered information hierarchy; sync classic-View overlay colors in `OverlayService` to the same
roles; remove superseded dark/decorative UI code (Constitution I/IV). Perceived lightness comes from
simpler layouts and fewer expensive decorations, not from rewriting protection engines.

## Technical Context

**Language/Version**: Kotlin (Android Gradle Plugin; JVM target 1.8 / compileSdk 34)

**Primary Dependencies**: Jetpack Compose (BOM `2024.01.00`), Material 3, Activity Compose,
existing app services (`OverlayService` classic Views for overlays, VPN, prefs, Room usage DB —
behavior unchanged)

**Storage**: N/A for redesign (no new persistence schema). Existing `SharedPreferences` / Room usage
unchanged.

**Testing**: JUnit4 JVM unit tests under `app/src/test` (behavior-preservation oracle for overlay
presentation strategies, commitment lock, etc.). Manual/on-device visual + perceived-performance
validation per [quickstart.md](./quickstart.md). Command: `./gradlew :app:testDebugUnitTest`.

**Target Platform**: Android 10 (API 29)+.

**Project Type**: Single Android application module (`app/`).

**Performance Goals** (perceived, from spec):
- Cold start → first useful Home content under ~2s on a typical mid-range device
- Warm tab switches feel immediate (&lt; ~300 ms perceived wait for chrome + primary structure)
- Smooth scrolling on Apps / Stats lists without sustained jank from decorative UI

**Constraints**:
- **Behavior-preserving** for all protection outcomes (spec FR-006 / FR-015; Constitution V) —
  appearance and layout may change; timers, locks, VPN filtering, commitment lock must not.
- **Light-first only** for this feature; full alternate dark theme out of scope.
- Keep all six primary destinations reachable (Home, Apps, VPN, Limits, Stats, Settings).
- Overlay presentation **strategies** (`OverlayPresentation`) stay the decision point for copy /
  countdown / skip behavior; only visual chrome (colors, spacing, weight) changes.
- Remove dead/old visual tokens and unused decorative composables once replaced (Constitution I/IV).
- Avoid purple-glow / near-black “gamer dark” aesthetic; calm wellbeing neutrals + one restrained
  accent (see [research.md](./research.md)).

**Scale/Scope**: Theme package + `MainActivity` shell + all screens under `ui/` (including sheets /
dialogs / onboarding) + overlay View styling constants in `OverlayService` (+ optional light
notification polish). No VPN engine, policy, or Room schema changes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against `.specify/memory/constitution.md` v1.0.0:

| Principle | Gate | Status |
|-----------|------|--------|
| I. Zero Dead & Unused Code | Old dark-only tokens, unused gradient helpers, and superseded decorative composables are deleted after migration — not left parallel. | ✅ PASS |
| II. Consistent Strategy-Pattern Implementation | Overlay behavior stays in `OverlayPresentation` strategies; redesign does not reintroduce mode `when`/`if` at call sites. Theme roles are centralized in `ui/theme`. | ✅ PASS |
| III. Robust Error Handling | No new I/O boundaries required for pure visual work; existing error/logging paths at overlay/VPN/prefs boundaries remain. | ✅ PASS |
| IV. No Commented-Out Code | Old UI is deleted or replaced, not commented out. | ✅ PASS |
| V. Behavior-Preserving Removals | Visual-only change set; protection behavior unchanged (spec FR-006). Removals limited to unused visual assets/code after replacement. Any accidental behavior change is a defect. | ✅ PASS |
| VI. Test-Verified Refactors | Existing unit tests must pass after redesign; overlay presentation tests remain the behavior oracle for copy/countdown/skip. | ✅ PASS |

**Initial gate: PASS.** No violations requiring Complexity Tracking.

**Post-Design re-check (after Phase 1)**: Design centralizes visual roles in theme + documented UI
contracts; overlay strategies unchanged for behavior; no new storage or public API surface. Re-evaluated:
**PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/003-premium-ui-redesign/
├── plan.md              # This file
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/           # Phase 1
│   ├── visual-system.md
│   ├── primary-destinations.md
│   └── overlay-surfaces.md
└── tasks.md             # Phase 2 (/speckit-tasks — not created here)
```

### Source Code (repository root)

```text
app/src/main/java/com/grayzone/app/
├── MainActivity.kt                 # Scaffold, bottom nav, theme host
├── ui/
│   ├── theme/
│   │   ├── Color.kt                # Color role tokens (light-first redesign)
│   │   ├── Theme.kt                # Material3 lightColorScheme + window chrome
│   │   └── Type.kt                 # Typography scale
│   ├── HomeScreen.kt
│   ├── AppsScreen.kt
│   ├── VpnScreen.kt
│   ├── LimitsScreen.kt
│   ├── StatsScreen.kt
│   ├── SettingsScreen.kt
│   ├── OnboardingScreen.kt
│   ├── CommitmentPinDialog.kt
│   ├── StreakCard.kt / DailyBreakCard.kt / QuickFocusCard.kt
│   ├── ScheduleSheet.kt / PerAppSettingsSheet.kt / CustomPromptsSheet.kt
│   └── …
├── service/
│   ├── OverlayService.kt           # Classic View overlay chrome colors/spacing
│   └── overlay/                    # Presentation strategies (behavior unchanged)
└── …

app/src/main/res/
├── layout/notification_countdown.xml   # Optional light polish
└── drawable/…

app/src/test/java/com/grayzone/app/
└── service/overlay/OverlayPresentationTest.kt  # Behavior oracle
```

**Structure Decision**: Stay in the existing single-module Android app. Redesign is confined to
`ui/theme`, Compose screens/sheets/dialogs, `MainActivity` chrome, and overlay View styling in
`OverlayService`. No new modules.

## Complexity Tracking

> No constitution violations requiring justification.

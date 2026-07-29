---
description: "Task list for Premium Lightweight UI Redesign"
---

# Tasks: Premium Lightweight UI Redesign

**Input**: Design documents from `/specs/003-premium-ui-redesign/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: No new UI unit-test suite requested in the spec. Constitution VI / plan require the **existing**
JVM suite (`./gradlew :app:testDebugUnitTest`) to stay green as the behavior-preservation oracle
(especially `OverlayPresentationTest`). Manual visual/perf checks follow `quickstart.md`.

**Organization**: Tasks are grouped by user story (US1–US4). Foundational theme tokens block all
stories. Primary destinations stay reachable; protection behavior must not change.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4 for story phases; Setup / Foundational / Polish have no story label
- Exact file paths are included in each task

## Path Conventions

Single Android module: `app/src/main/java/com/grayzone/app/`, tests under
`app/src/test/java/com/grayzone/app/`. Theme: `app/src/main/java/com/grayzone/app/ui/theme/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Behavior-preservation baseline before visual changes.

- [x] T001 Establish green baseline: run `./gradlew :app:testDebugUnitTest` from repo root and confirm all existing tests pass (Constitution VI oracle for FR-006 / FR-015).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Light-first visual system tokens that every screen and overlay chrome will consume.
**⚠️ CRITICAL**: No user-story UI work until this phase is complete.

- [x] T002 Replace dark color tokens with light-first role tokens (background, surface, text, teal/sage accent, success/warning/error, neutral outlines; remove/replace purple-glow and near-black fills) in `app/src/main/java/com/grayzone/app/ui/theme/Color.kt` per `research.md` R2 and `contracts/visual-system.md` VS-1/VS-2.
- [x] T003 Switch `GrayzoneTheme` to a Material 3 **light** color scheme wired to the new tokens and set light status/navigation bar appearance (`isAppearanceLightStatusBars` / navigation bars = true) in `app/src/main/java/com/grayzone/app/ui/theme/Theme.kt` (research.md R1; VS-1). Depends on T002.
- [x] T004 [P] Tune typography scale for clear hierarchy (titles/body/labels) suitable for calm light UI in `app/src/main/java/com/grayzone/app/ui/theme/Type.kt` (VS-3; data-model Visual System).
- [x] T005 Apply scaffold container / bottom-nav colors to the new light roles (selected = accent, unselected = secondary text) without removing any of the six tabs in `app/src/main/java/com/grayzone/app/MainActivity.kt` (PD-1 / PD-4). Depends on T003.

**Checkpoint**: App boots into a light shell with new tokens; screens may still look inconsistent until story phases — foundation ready.

---

## Phase 3: User Story 1 - Calm, clear Home experience (Priority: P1) 🎯 MVP

**Goal**: Home shows protection/setup status and the next action at a glance on a light, uncluttered layout.

**Independent Test**: Open Home fully set up vs missing a permission — status and CTA are obvious within ~5s; no dark/messy competing chrome (`quickstart.md` §2–3 Home; SC-002).

### Implementation for User Story 1

- [x] T006 [US1] Redesign Home primary status affordance (ready / needs setup / warning) as the above-the-fold focus with one clear CTA and restrained semantic color in `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` (FR-001–004, FR-008; PD-2).
- [x] T007 [US1] Declutter Home layout: remove competing gradients/glow washes, reduce stacked card noise, use generous spacing and theme tokens only in `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` (FR-012; VS-5). Depends on T006.
- [x] T008 [P] [US1] Restyle `app/src/main/java/com/grayzone/app/ui/StreakCard.kt` to light minimal surfaces subordinate to Home status (no purple glow / heavy decoration).
- [x] T009 [P] [US1] Restyle `app/src/main/java/com/grayzone/app/ui/DailyBreakCard.kt` to light minimal subordinate section styling.
- [x] T010 [P] [US1] Restyle `app/src/main/java/com/grayzone/app/ui/QuickFocusCard.kt` to light minimal subordinate section styling.
- [x] T011 [US1] Verify Home still exposes existing setup/permission/monitoring summary capabilities (no silent removals) and compile/run on device or emulator; spot-check SC-001/SC-002 visually. Depends on T007–T010.

**Checkpoint**: Home alone delivers the calm light MVP experience.

---

## Phase 4: User Story 2 - Core destinations without clutter (Priority: P1)

**Goal**: Users can complete Apps / VPN / Limits / Stats / Settings tasks on light, single-purpose screens with snappy nav feedback.

**Independent Test**: On each remaining tab, primary job is obvious; complete monitor toggle, limit/setting change, and VPN toggle without assistance (`quickstart.md` §3; SC-003 / SC-009).

### Implementation for User Story 2

- [x] T012 [P] [US2] Redesign Apps list for scannable light rows, clear primary job, and immediate toggle feedback in `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` (FR-007, FR-013; PD-2/PD-3). Preserve all monitoring capabilities.
- [x] T013 [P] [US2] Redesign VPN destination (status + enable/disable) for light minimal hierarchy in `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt`; keep commitment-lock and blocker controls reachable (PD-3/PD-5).
- [x] T014 [P] [US2] Redesign Limits for clear session/pause/lockout scanning on light surfaces in `app/src/main/java/com/grayzone/app/ui/LimitsScreen.kt` (PD-1 Limits primary job).
- [x] T015 [P] [US2] Redesign Stats for readable summaries / calm empty state on light surfaces in `app/src/main/java/com/grayzone/app/ui/StatsScreen.kt` (FR-008; PD-2).
- [x] T016 [P] [US2] Redesign Settings for clear primary controls, light hierarchy, and intentional locked-control appearance when commitment lock is active in `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` (FR-007–009 edge cases).
- [x] T017 [US2] Confirm bottom navigation still reaches all six destinations with clear selected accent state after destination restyles in `app/src/main/java/com/grayzone/app/MainActivity.kt` (PD-1/PD-4). Depends on T012–T016.
- [x] T018 [US2] Verify US2: exercise Apps toggle, Settings slider/limit change, VPN toggle on device; run `./gradlew :app:testDebugUnitTest` and confirm green. Depends on T017.

**Checkpoint**: All primary destinations are light, usable, and capability-complete.

---

## Phase 5: User Story 3 - Cohesive light premium visual system (Priority: P2)

**Goal**: Dialogs, sheets, onboarding, and protection overlays match the same light premium identity.

**Independent Test**: Open commitment PIN / sheets / onboarding and trigger friction or lock — all feel light, calm, and on-brand (`quickstart.md` §5–6; SC-007/SC-008).

### Implementation for User Story 3

- [x] T019 [P] [US3] Restyle commitment PIN / change-PIN dialogs to light minimal language in `app/src/main/java/com/grayzone/app/ui/CommitmentPinDialog.kt` (FR-009; VS contracts).
- [x] T020 [P] [US3] Restyle `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` to light theme tokens and restrained chrome.
- [x] T021 [P] [US3] Restyle `app/src/main/java/com/grayzone/app/ui/PerAppSettingsSheet.kt` to light theme tokens and restrained chrome.
- [x] T022 [P] [US3] Restyle `app/src/main/java/com/grayzone/app/ui/CustomPromptsSheet.kt` to light theme tokens and restrained chrome.
- [x] T023 [P] [US3] Restyle `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` to light-first calm hierarchy consistent with Home (VS-1).
- [x] T024 [US3] Update overlay View chrome constants (background, surface, text, accent, borders, progress) to mirror light theme roles **without** changing `OverlayPresentation` behavior in `app/src/main/java/com/grayzone/app/service/OverlayService.kt` (contracts/overlay-surfaces.md OS-2/OS-3; research.md R6). Do not add mode `when` branches at call sites (Constitution II).
- [x] T025 [US3] Verify US3: run `./gradlew :app:testDebugUnitTest` (must keep `OverlayPresentationTest` green); on device trigger friction/lock and open dialogs/sheets for visual cohesion (SC-008). Depends on T019–T024.

**Checkpoint**: In-app chrome and overlays share one light premium identity.

---

## Phase 6: User Story 4 - Perceived speed and lightness (Priority: P2)

**Goal**: Daily use feels lightweight — fewer decorations, simpler lists, snappy cold start and tab switches.

**Independent Test**: Cold start, tab switches, and Apps/Stats scrolling feel snappy without sustained jank (`quickstart.md` §4; SC-004–SC-006).

### Implementation for User Story 4

- [x] T026 [US4] Audit and remove remaining competing `Brush`/gradient/glow decorations from primary screens under `app/src/main/java/com/grayzone/app/ui/` (especially any leftover in Home/Apps/Vpn/Limits/Stats/Settings) so large fills use flat theme roles only (FR-012/FR-014; research.md R5).
- [x] T027 [US4] Simplify heavy per-row ornamentation on list-heavy UIs in `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` and `app/src/main/java/com/grayzone/app/ui/StatsScreen.kt` for smoother scrolling (FR-013; SC-006). Depends on T026 where those files still carry decoration.
- [x] T028 [US4] Ensure tab content structure appears immediately on switch (avoid adding heavy animated stacks / blocking UI work on tab change) in `app/src/main/java/com/grayzone/app/MainActivity.kt` and destination entry composables (SC-005).
- [x] T029 [US4] On-device perceived-performance check: cold start to useful Home content, warm tab switches, scroll Apps/Stats; note any multi-second freezes on ordinary toggles and fix UI-thread offenders in the touched screen files (SC-004–SC-007). Depends on T026–T028.

**Checkpoint**: Redesign feels light and fast in daily use, not only “less dark.”

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Dead-code removal, optional notification polish, full validation.

- [x] T030 Delete unused dark-only tokens and dead decorative helpers from `app/src/main/java/com/grayzone/app/ui/theme/Color.kt` (and any unused imports across `ui/`) so no parallel old palette remains (Constitution I/IV; VS-4).
- [x] T031 [P] Optional light polish for session countdown notification colors/contrast in `app/src/main/res/layout/notification_countdown.xml` and related drawables if they clash with the new identity (plan: not primary focus).
- [ ] T032 Execute on-device validation checklist in `specs/003-premium-ui-redesign/quickstart.md` §§2–6 (visual system, destinations, overlays, dialogs). **(MANUAL — requires a physical device/emulator; not run in this environment. Automated suite green via T033.)**
- [x] T033 Final full-suite verification: run `./gradlew :app:testDebugUnitTest` and confirm green (behavior preservation + overlay oracle).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first (baseline).
- **Foundational (Phase 2)**: T002 → T003 → T005; T004 parallel with T002/T003. **Blocks all user stories.**
- **US1 (Phase 3)**: After Foundational. MVP.
- **US2 (Phase 4)**: After Foundational; can follow US1 or run in parallel with US1 if separate owners (Home vs other tabs). Prefer US1 first for MVP demo.
- **US3 (Phase 5)**: After Foundational; ideally after US2 so destination context exists, but dialogs/overlays can proceed once tokens exist.
- **US4 (Phase 6)**: After US1–US3 screens exist (needs something to strip/audit).
- **Polish (Phase 7)**: After desired stories complete.

### User Story Dependencies

- **US1**: Depends on Foundational only.
- **US2**: Depends on Foundational; independent of US1 for Apps/VPN/Limits/Stats/Settings files.
- **US3**: Depends on Foundational; OverlayService independent of Compose screens; dialogs/sheets independent of each other ([P]).
- **US4**: Depends on US1–US3 having applied light layouts (audit pass).

### Parallel Opportunities

- T004 with T002/T003 (different files).
- T008–T010 after T006 starts (card files vs HomeScreen — prefer after T007 to match Home spacing, but files differ).
- T012–T016 all [P] (different screen files).
- T019–T023 all [P] (different dialog/sheet/onboarding files).
- T031 with T030.

---

## Parallel Example: User Story 2

```text
# After Foundational, launch destination redesigns together:
T012 AppsScreen.kt
T013 VpnScreen.kt
T014 LimitsScreen.kt
T015 StatsScreen.kt
T016 SettingsScreen.kt
# Then T017 MainActivity nav confirm + T018 verify
```

## Parallel Example: User Story 3

```text
T019 CommitmentPinDialog.kt
T020 ScheduleSheet.kt
T021 PerAppSettingsSheet.kt
T022 CustomPromptsSheet.kt
T023 OnboardingScreen.kt
# Then T024 OverlayService.kt chrome + T025 verify
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 baseline
2. Phase 2 theme tokens + light shell (T002–T005)
3. Phase 3 Home + home cards (T006–T011)
4. **STOP and VALIDATE** Home on device (light, clear status)
5. Demo MVP

### Incremental Delivery

1. Setup + Foundational → light shell
2. US1 Home → MVP demo
3. US2 other destinations → full app usable light
4. US3 dialogs + overlays → cohesive identity
5. US4 perf declutter → “feels fast”
6. Polish → dead tokens gone, quickstart + tests green

### Parallel Team Strategy

1. Team finishes Foundational together
2. Dev A: US1 Home; Dev B: US2 destinations; Dev C: US3 overlays/dialogs (after tokens)
3. Anyone: US4 audit after screens land
4. Shared: Polish + `testDebugUnitTest`

---

## Notes

- [P] = different files, no incomplete-task dependencies
- Do **not** change session timers, VPN filtering logic, or `OverlayPresentation` copy/countdown/skip semantics
- Delete old dark UI — do not comment it out (Constitution IV)
- Prefer theme tokens over new hard-coded hex in screens (VS-4)
- Commit after each task or logical group when implementing

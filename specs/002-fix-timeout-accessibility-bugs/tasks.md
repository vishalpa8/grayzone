---
description: "Task list for Fix Session Timeout Lock & Accessibility Permission Stability"
---

# Tasks: Fix Session Timeout Lock & Accessibility Permission Stability

**Input**: Design documents from `/specs/002-fix-timeout-accessibility-bugs/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The plan and Constitution Principle VI (Test-Verified Refactors) require the
corrected pure logic to be unit-tested, with the existing suite as the behavior-preservation oracle.

**Organization**: Tasks are grouped by user story (US1 = P1 session lock, US2 = P2 accessibility
stability). The two stories touch disjoint files and are independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1 or US2 (Setup/Polish tasks have no story label)
- Exact file paths are included in each task

## Path Conventions

Single Android application module: main code under `app/src/main/java/com/grayzone/app/`, JVM unit
tests under `app/src/test/java/com/grayzone/app/`. Test command: `./gradlew :app:testDebugUnitTest`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the behavior-preservation baseline before any change.

- [x] T001 Establish green baseline: run `./gradlew :app:testDebugUnitTest` from repo root and confirm all existing tests pass; this is the behavior-preservation oracle for FR-008 / Constitution VI.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Cross-cutting prerequisites for the user stories.

**Note**: US1 and US2 are independent and touch disjoint files, so there is no shared production code
that blocks both. The only foundational requirement is the green baseline (T001). No additional
foundational tasks are needed — proceed to the user stories.

**Checkpoint**: Baseline green → user story implementation can begin (US1 and US2 may proceed in parallel).

---

## Phase 3: User Story 1 - Session locks when the time limit is reached (Priority: P1) 🎯 MVP

**Goal**: The session countdown stops at `00:00` (never negative) and the monitored app is locked at
expiry, reliably across screen-off/Doze/process-death/reboot.

**Independent Test**: Set a 1-minute limit; on expiry the countdown notification stops at `00:00` and
the lock overlay appears (foreground), including when expiry happens with the screen off — per
`quickstart.md` §2 Scenarios A–D.

### Tests for User Story 1 ⚠️ (write first, ensure they fail before implementation)

- [x] T002 [P] [US1] Create `SessionExpiryDecisionTest` in app/src/test/java/com/grayzone/app/policy/SessionExpiryDecisionTest.kt covering: clamp invariant `max(0, activeUntil-now)`, `ShowLock` when foreground & `now < lockedUntil`, `ClampCountdown` when expired & not foreground, and `NoOp` when disabled / on break / not yet expired (contracts SE-1/2/3/6).

### Implementation for User Story 1

- [x] T003 [P] [US1] Create pure `SessionExpiryDecision` in app/src/main/java/com/grayzone/app/policy/SessionExpiryDecision.kt with inputs/outputs per data-model.md §2 (`NoOp` / `ClampCountdown` / `ShowLock`) and the never-negative clamp rule; no Android imports.
- [x] T004 [P] [US1] Create `SessionExpiryScheduler` in app/src/main/java/com/grayzone/app/service/SessionExpiryScheduler.kt to arm/cancel a per-package Doze-safe alarm at `activeUntil`: default `AlarmManager.setAndAllowWhileIdle()`, upgrade to `setExactAndAllowWhileIdle()` only when `canScheduleExactAlarms()` is already true; explicit logged failure handling (component `OVERLAY`), no new dangerous permission (research.md R2).
- [x] T005 [US1] Create `SessionExpiryReceiver` in app/src/main/java/com/grayzone/app/service/SessionExpiryReceiver.kt: on alarm fire read `ACTIVE_UNTIL`/`LOCKED_UNTIL` (PrefsKeys) + current foreground package, call `SessionExpiryDecision`, and apply the result via `OverlayService` (clamp/cancel notification; show `OverlayMode.LOCK` when `ShowLock`). Depends on T003, T004.
- [x] T006 [US1] Wire session start to arm the alarm in app/src/main/java/com/grayzone/app/service/OverlayService.kt: arm via `SessionExpiryScheduler` wherever `activeUntil` is set (`markAppUnlocked()` and `executeCommand(UpdateState)`), and remove/replace the unreliable in-memory `scheduleLockoutCheck()` `delay()` lock path. Depends on T004, T005.
- [x] T007 [US1] Make the countdown notification never render negative in app/src/main/java/com/grayzone/app/service/OverlayService.kt: ensure the countdown `Chronometer` notification is cancelled/replaced at `activeUntil` by the expiry path so it clamps to `00:00` (research.md R1; contract display invariant). Depends on T005.
- [x] T008 [US1] Re-arm alarms on service (re)start in app/src/main/java/com/grayzone/app/service/OverlayService.kt (`onCreate()`/`onStartCommand()`): for each monitored app with a persisted active session, re-arm; if `now >= activeUntil`, apply the expiry decision immediately (contract SE-5). Depends on T004, T005.
- [x] T009 [US1] Re-arm alarms after boot in app/src/main/java/com/grayzone/app/service/BootReceiver.kt (or via the OverlayService start path it already triggers) so sessions in-flight across reboot still clamp/lock (contract SE-5). Depends on T008.
- [x] T010 [US1] Verify US1: run `./gradlew :app:testDebugUnitTest`; confirm `SessionExpiryDecisionTest` passes and all pre-existing tests remain green (no regression).

**Checkpoint**: Session expiry reliably clamps the countdown and locks the app — US1 is independently testable (MVP).

---

## Phase 4: User Story 2 - Accessibility permission stays granted (Priority: P2)

**Goal**: No re-prompt loop while the permission is granted; at most one prompt per genuine loss; the
app is not the cause of revocation.

**Independent Test**: With the permission granted, resuming repeatedly shows no prompt; disabling it
prompts exactly once and not again until re-granted — per `quickstart.md` §3 Scenarios E–G.

### Tests for User Story 2 ⚠️ (write first, ensure they fail before implementation)

- [x] T011 [P] [US2] Create `AccessibilityPromptStateTest` in app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt covering AP-1..AP-4: no prompt while granted, exactly one prompt on first observed loss, no prompt on subsequent losses, and reset-on-regrant (data-model.md §3).

### Implementation for User Story 2

- [x] T012 [P] [US2] Add the prompt-once persisted key `ACCESSIBILITY_PROMPTED_FOR_LOSS` to app/src/main/java/com/grayzone/app/PrefsKeys.kt (data-model.md §3).
- [x] T013 [P] [US2] Create pure `AccessibilityPromptState` in app/src/main/java/com/grayzone/app/AccessibilityPromptState.kt implementing `next(state, event)` for the prompt-once-per-loss state machine; no Android imports.
- [x] T014 [US2] Update the accessibility gate in app/src/main/java/com/grayzone/app/MainActivity.kt to drive routing/prompting through `AccessibilityPromptState` (read/write the persisted flag) instead of forcing onboarding/re-prompt on every evaluation (research.md R4). Depends on T012, T013.
- [x] T015 [US2] Update app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt `ON_RESUME` handling to prompt at most once per loss and reset on re-grant (contract AP-2/AP-3/AP-4). Depends on T012, T013.
- [x] T016 [US2] Update app/src/main/java/com/grayzone/app/ui/HomeScreen.kt to surface permission loss without auto-looping to Accessibility settings (contract AP-1). Depends on T012, T013.
- [x] T017 [P] [US2] Harden app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt: make `onServiceConnected`/`onAccessibilityEvent`/broadcast send exception-safe with actionable logging (component `ACCESSIBILITY`, no silent catch), and drop the unused `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` while preserving the `ACTION_APP_CHANGED` broadcast payload/behavior (contract AH-1/AH-2).
- [x] T018 [P] [US2] Drop `android:accessibilityFlags="flagRetrieveInteractiveWindows"` in app/src/main/res/xml/accessibility_service_config.xml to match T017 (keep event type/feedback/timeout unchanged).
- [x] T019 [US2] Verify US2: run `./gradlew :app:testDebugUnitTest`; confirm `AccessibilityPromptStateTest` passes and all pre-existing tests remain green.

**Checkpoint**: No re-prompt loop and a hardened service — US2 is independently testable.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Cleanup, validation, and final verification across both stories.

- [x] T020 Remove now-dead superseded code (e.g. the obsolete in-memory `lockoutJobs`/`delay()` lock path if fully replaced) in app/src/main/java/com/grayzone/app/service/OverlayService.kt and confirm zero unused imports/symbols (Constitution I).
- [ ] T021 [P] Execute the on-device validation in specs/002-fix-timeout-accessibility-bugs/quickstart.md §2 (Scenarios A–D) and §3 (Scenarios E–G), including logcat capture around any OEM revocation to confirm the app is not the trigger. **(MANUAL — requires a physical device/emulator; not run in this environment.)**
- [x] T022 Final full-suite verification: run `./gradlew :app:testDebugUnitTest` and confirm green (behavior preservation + new tests).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 first (baseline).
- **Foundational (Phase 2)**: none beyond T001.
- **User Stories (Phase 3, 4)**: both depend only on T001; US1 and US2 are independent and may run in parallel.
- **Polish (Phase 5)**: after the desired stories are complete.

### User Story Dependencies

- **US1 (P1)**: independent — the MVP. Delivers the session-lock/negative-counter fix on its own.
- **US2 (P2)**: independent — delivers the accessibility-stability fix on its own; touches disjoint files from US1.

### Within User Story 1

- T002 (test) → T003 (decision) → T005 (receiver); T004 (scheduler) ∥ T003.
- T005 → T006, T007, T008; T008 → T009; then T010 (verify).

### Within User Story 2

- T011 (test) → T013 (state); T012 (key) ∥ T013.
- T013/T012 → T014, T015, T016; T017 ∥ T018 (independent hardening); then T019 (verify).

### Parallel Opportunities

- Across stories: entire US1 (Phase 3) and US2 (Phase 4) can proceed in parallel after T001.
- US1: T002, T003, T004 are [P] (distinct files).
- US2: T011, T012, T013 are [P]; T017 and T018 are [P].

---

## Parallel Example: after baseline (T001)

```bash
# US1 kickoff (parallel, distinct files):
Task: "T002 SessionExpiryDecisionTest in app/src/test/.../policy/SessionExpiryDecisionTest.kt"
Task: "T003 SessionExpiryDecision in app/src/main/.../policy/SessionExpiryDecision.kt"
Task: "T004 SessionExpiryScheduler in app/src/main/.../service/SessionExpiryScheduler.kt"

# US2 kickoff (parallel, distinct files):
Task: "T011 AccessibilityPromptStateTest in app/src/test/.../AccessibilityPromptStateTest.kt"
Task: "T012 Add ACCESSIBILITY_PROMPTED_FOR_LOSS to app/src/main/.../PrefsKeys.kt"
Task: "T013 AccessibilityPromptState in app/src/main/.../AccessibilityPromptState.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1 (T001 baseline).
2. Complete Phase 3 (US1): reliable expiry + never-negative countdown + lock.
3. **STOP and VALIDATE**: quickstart.md §2 Scenarios A–D + `SessionExpiryDecisionTest`.
4. Ship the session-lock fix independently.

### Incremental Delivery

1. Baseline (T001) → US1 (session lock) → validate/ship.
2. Add US2 (accessibility stability) → validate/ship.
3. Polish (T020–T022) → final green suite + on-device sign-off.

---

## Notes

- [P] = different files, no dependencies on incomplete tasks.
- Each verify task (T010, T019, T022) enforces Constitution VI before its checkpoint.
- New Android glue (scheduler/receiver) stays thin; all decisions live in the two pure, unit-tested
  classes so correctness is provable off-device.
- Commit after each task or logical group; do not weaken/skip existing tests to make the suite pass.

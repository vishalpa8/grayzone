---
description: "Task list for Module Cleanup & Strategy Consolidation Audit"
---

# Tasks: Module Cleanup & Strategy Consolidation Audit

**Input**: Design documents from `/specs/001-module-cleanup-audit/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md,
contracts/overlay-presentation-strategy.md, quickstart.md

**Scope (module boundary)**: `app/src/main/java/com/grayzone/app/service/` **excluding**
`service/vpn/` — i.e. `OverlayService.kt`, `GrayzoneAccessibilityService.kt`, `BootReceiver.kt`, and
the shared `OverlayMode` constants in `PrefsKeys.kt` (read-only). No public API signatures change
(safe-to-change list is empty). Existing behavior must be preserved and verified by the existing
test suite.

**Tests**: The feature does NOT request TDD. New test authoring is optional (one additive strategy
test is included and marked OPTIONAL). Behavior preservation is verified by running the **existing**
JVM unit suite (`./gradlew :app:testDebugUnitTest`) — these verification runs are required tasks.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- All paths are repository-relative

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare the audit-tracking artifact used by all stories.

- [X] T001 Create the audit report artifact `specs/001-module-cleanup-audit/audit-report.md` with the `FlaggedItem` and `AuditReport` structure from `data-model.md` (columns: id, category, location, evidence, verdict, behaviorPreserved, verifyingTests; plus testCommand / preRunResult / postRunResult).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish the green behavior baseline (the preservation oracle) and confirm the target
file set. No removal or refactor work may begin until this phase is complete.

**⚠️ CRITICAL**: The baseline test run gates every subsequent change (Constitution Principle VI; spec
FR-012, SC-002).

- [X] T002 Establish green baseline: run `./gradlew :app:testDebugUnitTest`, confirm BUILD SUCCESSFUL, and record `AuditReport.preRunResult = PASS` (with the passing test names) in `specs/001-module-cleanup-audit/audit-report.md`. If not green, STOP and report.
- [X] T003 Confirm the target file set exists and record the module boundary in `audit-report.md`: `app/src/main/java/com/grayzone/app/service/OverlayService.kt`, `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt`, `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` (exclude `service/vpn/`).

**Checkpoint**: Baseline green and scope fixed — user stories can begin.

---

## Phase 3: User Story 1 - Remove dead, unreachable, and unreferenced code (Priority: P1) 🎯 MVP

**Goal**: Remove all provably dead/unreachable code, unused imports/variables/symbols, orphaned
files, and commented-out code from the target module, preserving all currently-tested behavior.

**Independent Test**: Run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`
after removals — zero unused-import/unreachable-code warnings for the target files (SC-001) and the
same tests pass with identical outcomes (SC-002).

- [X] T004 [US1] Gather removal evidence and record `FlaggedItem`s in `audit-report.md`: run repository-wide searches for `DEFAULT_LOCKOUT\b` and `OverlayMode\.(TINT|REMOVE_TINT)`; capture that `OverlayService.DEFAULT_LOCKOUT` (`OverlayService.kt:69`) is unreferenced (verdict REMOVE) and that `OverlayMode.TINT`/`REMOVE_TINT` are referenced only by `OverlayModeTest` (verdict FLAG_ONLY).
- [X] T005 [US1] Remove the unused private constant `DEFAULT_LOCKOUT` from the companion object in `app/src/main/java/com/grayzone/app/service/OverlayService.kt` (provably unused — FR-004, SC-006).
- [X] T006 [P] [US1] Audit `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` for unused imports, variables, parameters, and unreachable branches; remove any provably-dead items and record them in `audit-report.md`. Guardrail (FR-005): treat overridden lifecycle/callback methods and the manifest-registered service class as referenced — do NOT flag them as dead.
- [X] T007 [P] [US1] Audit `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` for unused imports, variables, parameters, and unreachable branches; remove any provably-dead items and record them in `audit-report.md`. Guardrail (FR-005): treat `onReceive` and the manifest-registered receiver class as referenced — do NOT flag them as dead.
- [X] T008 [US1] Audit `app/src/main/java/com/grayzone/app/service/OverlayService.kt` for unused imports, unused private members, unreachable branches, and commented-out code (excluding the strategy-consolidation branching handled in US2); remove only provably-dead items and record each in `audit-report.md`. Guardrail (FR-005): treat `Service` lifecycle overrides (`onCreate`/`onStartCommand`/`onDestroy`/`onBind`) and the `appChangeReceiver` broadcast callback as referenced. Do NOT alter any `try/catch`/error-handling control flow (Principle III; the C1 logging fix is handled by T025).
- [X] T009 [US1] Record `OverlayMode.TINT` and `OverlayMode.REMOVE_TINT` (`app/src/main/java/com/grayzone/app/PrefsKeys.kt:69-70`) as `FLAG_ONLY` in `audit-report.md` and leave them unchanged (test-only usage — Principle V; do not edit `PrefsKeys.kt`).
- [X] T025 [US1] (C1 remediation — FR-014) Add actionable logging to the silent catch in `showOverlay()` at `app/src/main/java/com/grayzone/app/service/OverlayService.kt` (`catch (e: Exception) { emptyList() }` in the custom-prompts parse block): log the swallowed exception via `GrayzoneLogger.w(LogComponent.OVERLAY, …)` while STILL returning `emptyList()` so control flow and outcomes are unchanged (behavior-preserving). Record as a `FlaggedItem` in `audit-report.md`.
- [X] T010 [US1] Verify US1: run `./gradlew :app:compileDebugKotlin` (zero unused/unreachable warnings for target files — SC-001) and `./gradlew :app:testDebugUnitTest` (all previously-passing tests still pass — SC-002); record results in `audit-report.md`.

**Checkpoint**: Module contains only live code; behavior verified. MVP deliverable is complete.

---

## Phase 4: User Story 2 - Consolidate duplicated conditionals into a strategy pattern (Priority: P2)

**Goal**: Replace the repeated `OverlayMode` type/state branching in `OverlayService.showOverlay()`
(and its countdown loop) with a single `OverlayPresentation` strategy that owns per-mode presentation
and countdown-completion behavior, per `contracts/overlay-presentation-strategy.md`.

**Independent Test**: After the refactor, no call site re-derives `isLocked/isBudgetLock/isScheduleLock`
for presentation (SC-004), each mode renders identically to the contract's equivalence table, and
`./gradlew :app:testDebugUnitTest` stays green.

- [X] T011 [US2] Create `app/src/main/java/com/grayzone/app/service/overlay/OverlayPresentation.kt` defining the `OverlayPresentation` interface, the input value types (`SecondsInputs`, `CountdownFinishContext`), and a `resolve(mode: Int): OverlayPresentation` mapping (FRICTION/LOCK/BUDGET_LOCK/SCHEDULE_LOCK), per the contract. Reject TINT/REMOVE_TINT as invalid inputs.
- [X] T012 [P] [US2] Implement `FrictionPresentation` in `app/src/main/java/com/grayzone/app/service/overlay/FrictionPresentation.kt` matching the contract equivalence table (title `Pause.`, quoted prompt body, `"$seconds"`, `Opening in $seconds seconds…`, `← I'll Skip This`, progress shown, onFinished = markAppUnlocked + dismiss).
- [X] T013 [P] [US2] Implement `LockPresentation` in `app/src/main/java/com/grayzone/app/service/overlay/LockPresentation.kt` matching the contract (title `Locked.`, session-limit body, `formatDuration(seconds)`, `Remaining lockout time`, `← Leave App`, progress hidden, initialSeconds from `lockedUntil`, onFinished = dismiss + re-show FRICTION if still foreground).
- [X] T014 [P] [US2] Implement `BudgetLockPresentation` in `app/src/main/java/com/grayzone/app/service/overlay/BudgetLockPresentation.kt` matching the contract (title `Locked.`, `Daily usage limit reached.`, `—`, `Resets tomorrow`, `← Leave App`, progress hidden, initialSeconds 0, no countdown action).
- [X] T015 [P] [US2] Implement `ScheduleLockPresentation` in `app/src/main/java/com/grayzone/app/service/overlay/ScheduleLockPresentation.kt` matching the contract (title `Locked.`, `Focus mode / schedule is active.`, `—`, `Try again later`, `← Leave App`, progress hidden, initialSeconds 0, no countdown action).
- [X] T016 [US2] Refactor `showOverlay()` in `app/src/main/java/com/grayzone/app/service/OverlayService.kt` to resolve exactly one `OverlayPresentation` from `mode` and source title/body/countdown/sub-text/skip-label/progress-visibility/initial-seconds from it, removing the duplicated `isLocked/isBudgetLock/isScheduleLock/isAnyLock` presentation conditionals (FR-007, SC-004). Keep all side effects in `OverlayService` (permission check, `dismissOverlay()`, `cancelCountdownNotification()` on lock modes, tint, `recordFrictionSkip()`, window add/remove) and preserve evaluation order.
- [X] T017 [US2] Refactor the countdown loop and the terminal `when` in `showOverlay()` (`app/src/main/java/com/grayzone/app/service/OverlayService.kt`) to delegate per-tick text and completion to `OverlayPresentation.countdownText()` / `onCountdownFinished()`, preserving the `isActive` cancellation guard and existing behavior.
- [X] T018 [P] [US2] (REQUIRED per U1 remediation — additive) Add `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` asserting `resolve(mode)` returns the correct strategy and each strategy's text/flags match the contract equivalence table (locks FR-008 extensibility: `resolve()` is the single sanctioned decision point). Must not modify or weaken any existing test.
- [X] T019 [US2] Verify US2: run `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`; confirm all existing tests still pass with identical outcomes and record results in `audit-report.md`.

**Checkpoint**: `OverlayMode` branching owned by a single strategy; behavior verified.

---

## Phase 5: User Story 3 - Produce a reviewable audit report (Priority: P3)

**Goal**: Finalize a reviewable report itemizing every removal and consolidation with evidence and
the tests that confirm behavior preservation.

**Independent Test**: Review `audit-report.md` — every flagged item has category, location, evidence,
verdict, behavior-preserved flag, and verifying tests; public-API-affecting items outside the empty
safe-to-change list are marked FLAG_ONLY (FR-009, FR-010).

- [X] T020 [US3] Finalize all `FlaggedItem` entries in `specs/001-module-cleanup-audit/audit-report.md`: ensure each REMOVE/CONSOLIDATE item records evidence of unreachability/non-reference and the verifying tests, and each FLAG_ONLY item states why it is preserved (test-only / would change tested behavior).
- [X] T021 [US3] Record the final `AuditReport` results in `audit-report.md`: `testCommand`, `preRunResult` (from T002), `postRunResult` (from T019), and the acceptance-gate checklist SC-001…SC-006 with pass/fail.

**Checkpoint**: Cleanup is fully auditable and gated.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final sweep and end-to-end validation.

- [X] T022 [P] Final static sweep: run `./gradlew :app:compileDebugKotlin` and confirm zero unused-import / unused-symbol / unreachable-code warnings across all three target files (SC-001, SC-003).
- [X] T023 Run the end-to-end validation in `quickstart.md` (Steps 1–5) and confirm every acceptance gate passes; note any deviation in `audit-report.md`.
- [X] T024 [P] Update `specs/001-module-cleanup-audit/checklists/requirements.md` notes to reflect completion (baseline green, behavior preserved, zero public API changes).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup. **Blocks all user stories** (baseline oracle).
- **User Story 1 (Phase 3)**: Depends on Foundational. No dependency on other stories.
- **User Story 2 (Phase 4)**: Depends on Foundational; SHOULD follow US1 (know what is live before
  refactoring — research.md Decision 1/4). Independently testable.
- **User Story 3 (Phase 5)**: Depends on US1 and US2 completing (it documents their outcomes).
- **Polish (Phase 6)**: Depends on all user stories.

### User Story Dependencies

- US1 (P1): independent — the MVP.
- US2 (P2): sequenced after US1 for safety; does not reuse US1 code paths.
- US3 (P3): reporting layer over US1 + US2.

### Within Each User Story

- Evidence/flagging before removal (US1).
- Interface + implementations before `showOverlay()` refactor (US2): T011 → T012–T015 → T016 → T017.
- Verification task runs last in each story.

### Parallel Opportunities

- US1: T006 and T007 are `[P]` (different files: `GrayzoneAccessibilityService.kt`, `BootReceiver.kt`).
- US2: T012–T015 are `[P]` (separate strategy files); T018 `[P]` (new test file).
- Polish: T022 and T024 are `[P]`.

---

## Parallel Example: User Story 2 strategy implementations

```bash
# After T011 (interface + resolver) is in place, implement the four strategies in parallel:
Task: "Implement FrictionPresentation in service/overlay/FrictionPresentation.kt"
Task: "Implement LockPresentation in service/overlay/LockPresentation.kt"
Task: "Implement BudgetLockPresentation in service/overlay/BudgetLockPresentation.kt"
Task: "Implement ScheduleLockPresentation in service/overlay/ScheduleLockPresentation.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1 (Setup) + Phase 2 (Foundational green baseline).
2. Complete Phase 3 (US1): remove provably-dead code, verify green.
3. **STOP and VALIDATE**: existing tests pass, zero dead-code warnings. This is a shippable increment.

### Incremental Delivery

1. Setup + Foundational → baseline established.
2. US1 → dead-code removed, verified (MVP).
3. US2 → strategy consolidation, verified.
4. US3 → audit report finalized.
5. Polish → final sweep + quickstart validation.

---

## Notes

- [P] = different files, no dependencies on incomplete tasks.
- Every user story ends with a verification task running the existing suite — do not mark a refactor
  complete until it is green (Constitution Principle VI).
- No public API signatures change; `OverlayMode` integer constants and `PrefsKeys` are read-only.
- Removals require recorded evidence; unprovable cases are FLAG_ONLY, never removed on assumption.

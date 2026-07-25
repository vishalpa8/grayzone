# Quickstart & Validation: Module Cleanup & Strategy Consolidation Audit

**Feature**: `001-module-cleanup-audit`
**Scope**: `service/` excluding `service/vpn/` (`OverlayService`, `GrayzoneAccessibilityService`,
`BootReceiver`, `OverlayMode` constants).

This guide describes how to establish the behavior baseline, perform the audit safely, and validate
that behavior is preserved. It is a run/validation guide — implementation details live in the plan,
data-model, contract, and the eventual `tasks.md`.

## Prerequisites

- JDK 17 and the Android SDK configured for the project (Android Studio or CLI Gradle).
- A clean working tree on branch `001-module-cleanup-audit`.

## Step 1 — Establish the behavior baseline (oracle)

Run the existing unit-test suite and confirm it is green **before** any change:

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Record this as `AuditReport.preRunResult = PASS`. If it is not green,
stop — the baseline must be green to serve as the preservation oracle (FR-012, SC-002).

## Step 2 — Run the dead-code / unused-symbol audit

Confirm the Phase 0 findings still hold (evidence for each removal — FR-004, SC-006):

```bash
# Genuinely unused private constant → REMOVE
rg -n "DEFAULT_LOCKOUT\b" app/src/main/java/com/grayzone/app/service/OverlayService.kt

# Test-only mode constants → FLAG ONLY (do NOT remove)
rg -n "OverlayMode\.(TINT|REMOVE_TINT)" app/src

# Unused imports / unreachable code surfaced by the compiler
./gradlew :app:compileDebugKotlin
```

Expected outcomes:
- `DEFAULT_LOCKOUT` appears only at its declaration in `OverlayService.kt` → remove it.
- `TINT`/`REMOVE_TINT` appear only in `OverlayModeTest` → leave them; record as `FLAG_ONLY`.
- Zero unused-import / unreachable-code warnings remain for the target files after cleanup (SC-001).

## Step 3 — Consolidate the OverlayMode branching into a strategy

Follow `contracts/overlay-presentation-strategy.md`:

- Introduce `OverlayPresentation` + the four implementations under `service/overlay/`.
- Replace the repeated `isLocked/isBudgetLock/isScheduleLock/isAnyLock` derivations and the per-mode
  `if/when` text blocks in `showOverlay()` and its countdown loop with a single
  `resolve(mode)` + delegation.
- Keep all side effects (permission check, `dismissOverlay()`, notification cancel on lock, tint,
  `recordFrictionSkip`, window add/remove) in `OverlayService`.

Behavior-equivalence check: compare each mode's rendered text/flags against the table in the contract.

## Step 4 — Verify behavior is preserved (required before "done")

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with the **same** tests passing as in Step 1 — none skipped, weakened, or
deleted. Record `AuditReport.postRunResult = PASS` (FR-012, SC-002, Principle VI).

Then confirm the module still compiles cleanly:

```bash
./gradlew :app:compileDebugKotlin
```

## Step 5 — Produce the audit report

Summarize each change as a `FlaggedItem` (see `data-model.md`) with: category, location, evidence,
verdict, behavior-preserved flag, and the verifying tests (FR-010). Confirm the acceptance gates:

- [ ] SC-001: zero unused imports/symbols/unreachable-code warnings in the target module
- [ ] SC-002: 100% of previously-passing tests still pass, identical outcomes
- [ ] SC-003: zero commented-out blocks / orphaned files remain in the module
- [ ] SC-004: each duplicated `OverlayMode` conditional now owned by exactly one strategy
- [ ] SC-005: zero public API signature changes (safe-to-change list empty)
- [ ] SC-006: every removal carries recorded unreachability/non-reference evidence

## Rollback

All changes are behavior-neutral and confined to the target module; revert the branch commits if any
gate fails. Removed dead code and (if any) commented-out code remain recoverable via git history.

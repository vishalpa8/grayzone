# Phase 0 Research: Module Cleanup & Strategy Consolidation Audit

**Feature**: `001-module-cleanup-audit`
**Scope**: `service/` package excluding `service/vpn/` (`OverlayService`,
`GrayzoneAccessibilityService`, `BootReceiver`, and the shared `OverlayMode` constants).

This document resolves the unknowns needed to plan the audit and records the evidence gathered from
the current codebase.

## Decision 1: Target module selection

- **Decision**: Audit the foreground session-enforcement service module (`service/` minus
  `service/vpn/`).
- **Rationale**: `OverlayService.showOverlay()` branches on the same `OverlayMode` discriminator in
  at least seven places (title, prompt, initial countdown text, sub-text, skip-button label, progress
  visibility, and the countdown-completion `when`) — the strongest strategy-consolidation opportunity
  in the codebase. It also contains a genuinely unused constant. By contrast, `policy/`
  (`SessionPolicyEngine`) and `service/vpn/` (`DnsQueryClassifier`, `DomainZoneMatcher`) are already
  cleanly abstracted and heavily unit-tested, offering little cleanup value.
- **Alternatives considered**:
  - `policy/` — already a clean decision engine with strong tests; low cleanup ROI.
  - `service/vpn/` — already uses small, testable strategy-like objects; low ROI.
  - `data/` — large, cross-cutting; better split into multiple features than one audit.

## Decision 2: Behavior-preservation oracle

- **Decision**: The existing JVM unit-test suite (`./gradlew :app:testDebugUnitTest`), run green
  before and after each change, is the acceptance oracle. The `androidTest` Room suite is not
  required for this module but must remain compilable.
- **Rationale**: Constitution Principle VI requires test-verified refactors; the module's observable
  behavior for the refactored paths is encoded by `OverlayModeTest`, `SessionStateLogicTest`,
  `LockGuardLogicTest`, `PromptResolutionTest`, and the `policy/` tests that drive the command flow
  `OverlayService` consumes.
- **Alternatives considered**: Adding new characterization tests before refactor — deferred; the
  existing suite already pins the mode constants and the policy→command contract. New tests may be
  added for the extracted strategy but are not a precondition to preserve behavior.

## Decision 3: Dead / unused symbol findings (evidence)

- **`OverlayService.DEFAULT_LOCKOUT = 60`** (companion, `OverlayService.kt:69`): referenced nowhere
  in the module (repository-wide search finds only its declaration; `ConfigValidator.DEFAULT_LOCKOUT_MINUTES`
  is a distinct symbol). **Verdict: provably unused → remove.**
- **`OverlayMode.TINT` (3) and `OverlayMode.REMOVE_TINT` (4)** (`PrefsKeys.kt:69-70`): not referenced
  by any production path; tint is applied/removed via `showTint()`/`dismissTint()` directly. However,
  they ARE referenced by `OverlayModeTest`. **Verdict: test-only → FLAG for review, do NOT remove**
  (removal would change tested behavior without a separately-approved decision; Principle V + spec
  edge case for test-only code).
- **Commented-out code**: none found in the three target files. (Box-drawing "mojibake" characters in
  comments are corrupted non-code text, not commented-out code, and are out of scope as behavior-
  neutral.)
- **Orphaned files**: none — all three files are live (`OverlayService` and
  `GrayzoneAccessibilityService` are manifest-registered; `BootReceiver` is a manifest-registered
  receiver).

## Decision 4: Strategy-consolidation approach for `OverlayMode`

- **Decision**: Introduce an internal `OverlayPresentation` strategy (one implementation per relevant
  mode: FRICTION, LOCK, BUDGET_LOCK, SCHEDULE_LOCK) that owns: title text, prompt/body text, countdown
  text formatting, sub-text, skip-button label, whether the progress bar is shown, the initial
  `secondsRemaining` derivation inputs, and the countdown-completion action. `showOverlay()` resolves
  exactly one strategy from the incoming `mode` and delegates, instead of re-deriving
  `isLocked/isBudgetLock/isScheduleLock/isAnyLock` at each call site.
- **Rationale**: Principle II — behavior variation on the `OverlayMode` type belongs in one place;
  adding a future mode becomes "add a strategy," not "edit scattered conditionals."
- **Constraints preserved**:
  - Evaluation order and side effects (permission checks, `dismissOverlay()`, notification cancel on
    lock modes, tint handling) stay in `OverlayService`; only presentation/decision text moves into
    strategies.
  - Existing integer `OverlayMode` constants and their meanings are unchanged (pinned by
    `OverlayModeTest`).
  - No public API/signature changes; strategies are internal to the module.
- **Alternatives considered**:
  - Kotlin `enum class` with abstract members replacing the `object OverlayMode` — rejected: would
    change the public `OverlayMode` Int contract that `OverlayModeTest` and broadcast extras pin.
  - `when`-based helper function — rejected: still a single conditional but does not satisfy the
    "strategy owns the decision / add-a-variant-without-editing-conditionals" requirement (FR-008).

## Decision 5: Indirect-reachability guardrails

- **Decision**: Treat manifest-registered components and framework callbacks as referenced:
  `OverlayService` (started via `startForegroundService`), `GrayzoneAccessibilityService`
  (system-bound), `BootReceiver` (boot broadcasts), and the `appChangeReceiver` inner
  `BroadcastReceiver`. Their overridden lifecycle methods are entry points, not dead code.
- **Rationale**: Principle V and spec FR-005 — reachability via framework/manifest is real
  reachability; static "no direct caller" is insufficient evidence of deadness.

## Open questions

- None blocking. The safe-to-change public API list is empty (per spec Assumptions), so no signature
  changes are considered.

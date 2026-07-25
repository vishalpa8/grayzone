# Feature Specification: Module Cleanup & Strategy Consolidation Audit

**Feature Branch**: `001-module-cleanup-audit`

**Created**: 2026-07-25

**Status**: Draft

**Input**: User description: "Audit and clean up the [module/service name] to remove unnecessary logic, dead code, and stale branches that are no longer reachable or referenced. Consolidate duplicated conditional logic into a proper strategy pattern where multiple code paths currently branch on type/state. Identify and flag unused functions, unused exports, orphaned files, and commented-out code for removal. Preserve all currently-tested behavior. Do not change public API signatures unless explicitly listed as safe to change."

## Clarifications

### Session 2026-07-25

- Q: Which module/service is the target of this cleanup audit? → A: The foreground session-enforcement service module — the `service/` package **excluding** the `service/vpn/` sub-package (which is a separate module). Concretely: `OverlayService`, `GrayzoneAccessibilityService`, `BootReceiver`, and the `OverlayMode` display-mode constants they depend on. This module was chosen because its overlay-rendering path branches repeatedly on the same `OverlayMode` type/state discriminator (a prime strategy-consolidation target) and it contains identifiable dead constants, whereas the `policy/` and `service/vpn/` modules are already cleanly abstracted and heavily unit-tested.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Remove dead, unreachable, and unreferenced code (Priority: P1)

As a maintainer of the target module, I want all dead code, unreachable branches, unused
functions/exports, orphaned files, and commented-out code identified and removed, so that the
module reflects only its live, exercised behavior and is safe to reason about.

**Why this priority**: This is the core value of the audit and the foundation the other stories
build on. Removing dead code shrinks the surface area that all subsequent refactoring must consider,
and directly satisfies the project's zero-dead-code principle. It delivers standalone value even if
no strategy consolidation happens.

**Independent Test**: Run the full existing test suite before and after the removals; confirm the
same tests pass with identical outcomes, and confirm static analysis reports zero unused
imports/symbols and zero unreachable code in the target module.

**Acceptance Scenarios**:

1. **Given** the target module contains a function that no live code path references, **When** the
   audit runs, **Then** that function is flagged as unused and removed, and the build plus existing
   tests still pass unchanged.
2. **Given** a conditional branch in the target module can never evaluate true given current inputs
   and callers, **When** the audit runs, **Then** the branch is proven unreachable and removed
   without altering any observable behavior.
3. **Given** a source file in the target module is not referenced by any live code or build target,
   **When** the audit runs, **Then** the file is flagged as orphaned and removed.
4. **Given** a block of commented-out code exists in the target module, **When** the audit runs,
   **Then** the commented-out block is removed (history remains in version control).

### User Story 2 - Consolidate duplicated conditionals into a strategy pattern (Priority: P2)

As a maintainer, I want duplicated conditional logic that branches on the same type or state — and
that a strategy abstraction should own — consolidated into a single strategy pattern, so that
behavior variation lives in one place and adding a new variant no longer requires editing scattered
conditionals.

**Why this priority**: Consolidation prevents future divergence bugs and satisfies the strategy
consistency principle, but it depends on first knowing which code is live (US1) and delivers less
immediate risk reduction than removing dead code.

**Independent Test**: Identify at least one set of call sites that branch on the same type/state,
route them through a single strategy decision point, and confirm the existing test suite passes with
identical outcomes and no duplicated branch logic remains for that decision.

**Acceptance Scenarios**:

1. **Given** two or more code paths that branch on the same type/state discriminator, **When**
   consolidation is applied, **Then** the branching decision is owned by a single strategy
   abstraction and call sites depend on that abstraction rather than re-deriving the discriminator.
2. **Given** a new variant needs to be supported after consolidation, **When** a maintainer adds it,
   **Then** it is added as a new strategy implementation without editing existing call-site
   conditionals.
3. **Given** the consolidation is applied, **When** the existing test suite runs, **Then** all
   previously passing tests still pass with identical outcomes.

### User Story 3 - Produce a reviewable audit report of flagged removals (Priority: P3)

As a reviewer, I want every proposed removal and consolidation itemized with evidence (why it is
unreachable/unused and which tests confirm behavior is preserved), so that I can approve the cleanup
with confidence and reject anything that changes behavior.

**Why this priority**: The report makes the cleanup auditable and enforces the constitution's
behavior-preservation and test-verification gates, but it is a supporting artifact rather than the
cleanup itself.

**Independent Test**: Review the generated report; confirm each flagged item includes its category
(dead code / unused / orphaned / commented-out / consolidation), the evidence of unreachability or
non-reference, and the tests that verify behavior preservation.

**Acceptance Scenarios**:

1. **Given** an item is flagged for removal, **When** it appears in the report, **Then** it records
   the evidence that the item is unreachable or unreferenced.
2. **Given** a public API signature is affected, **When** it is not on the explicitly-approved
   safe-to-change list, **Then** the report marks it as out of scope and it is left unchanged.

### Edge Cases

- What happens when code is referenced **only** by tests but not by production paths? It MUST be
  treated as currently-tested behavior and preserved (not flagged as dead), unless the test itself
  is also flagged as obsolete under an explicit, separately-approved decision.
- What happens when code is reachable only via reflection, dependency injection, framework callbacks,
  or manifest/resource registration (e.g., Android services, receivers, Compose entry points)? Such
  entry points MUST NOT be treated as unreferenced solely because no direct call site exists.
- What happens when a removal would change a public API signature that is **not** on the approved
  safe-to-change list? The removal MUST be deferred and the item flagged, leaving the signature
  unchanged.
- What happens when a branch's reachability cannot be proven? It MUST be preserved (behavior-
  preserving default) and flagged for manual review rather than removed on assumption.
- What happens when consolidating conditionals would alter evaluation order or side effects observed
  by existing tests? The consolidation MUST preserve the observable behavior the tests encode, or be
  deferred.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The audit MUST identify, within the target module, all unused functions, unused
  exports/visible symbols, unused variables and parameters, unused imports, orphaned (unreferenced)
  files, and commented-out code blocks.
- **FR-002**: The audit MUST identify unreachable/stale conditional branches that can never execute
  given current callers and inputs.
- **FR-003**: Each removal MUST preserve all currently-tested behavior; the existing test suite MUST
  pass with identical outcomes after removals.
- **FR-004**: A removal MUST only be performed when the removed code is provably unreachable or
  unreferenced; when reachability/reference cannot be proven, the item MUST be flagged for review
  and left in place.
- **FR-005**: Code reachable only through indirect entry points (reflection, DI, framework/lifecycle
  callbacks, manifest/resource registration) MUST NOT be classified as dead or orphaned.
- **FR-006**: Code exercised only by existing tests MUST be preserved as currently-tested behavior.
- **FR-007**: The audit MUST consolidate duplicated conditional logic that branches on the same
  type/state into a single strategy abstraction that owns the decision, with call sites depending on
  the abstraction rather than re-deriving the discriminator.
- **FR-008**: After consolidation, adding a new variant MUST NOT require editing existing call-site
  conditionals.
- **FR-009**: The audit MUST NOT change any public API signature unless that specific signature is on
  an explicitly-provided safe-to-change list; affected-but-unlisted signatures MUST be flagged and
  left unchanged.
- **FR-010**: The audit MUST produce a reviewable report itemizing each flagged item with its
  category, the evidence for removal (unreachable/unreferenced), and the tests that confirm behavior
  is preserved.
- **FR-011**: Commented-out code identified by the audit MUST be removed rather than annotated or
  retained.
- **FR-012**: The refactor/cleanup MUST NOT be marked complete until the existing test suite has been
  run and confirmed to pass against the changed code.
- **FR-013**: The scope of the audit MUST be bounded to the single target module/service identified
  for this feature; changes MUST NOT spill into unrelated modules except where a removal within the
  target module requires deleting a now-unreferenced symbol it exclusively owns.
- **FR-014**: Silent catches within the target module (empty or comment-only `catch` blocks, or
  catches that discard the exception without logging or rethrowing) MUST be given actionable logging
  of the swallowed exception. Such logging MUST be behavior-preserving — it MUST NOT change control
  flow, return values, or any outcome observed by the existing tests. (Added per Constitution
  Principle III; identified during `/speckit-analyze`.)

### Key Entities *(include if feature involves data)*

- **Target Module**: The foreground session-enforcement service module — the `service/` package
  excluding `service/vpn/` (`OverlayService`, `GrayzoneAccessibilityService`, `BootReceiver`, and the
  `OverlayMode` constants). This is the unit of scope for all removals and consolidations.
- **Flagged Item**: A candidate for removal or consolidation, with attributes: category (dead code /
  unreachable branch / unused function / unused export / unused variable/import / orphaned file /
  commented-out code / strategy consolidation), location, evidence of unreachability/non-reference,
  behavior-preservation verdict, and the tests that confirm it.
- **Safe-to-Change List**: The explicit, approved set of public API signatures permitted to change;
  empty by default.
- **Audit Report**: The reviewable collection of Flagged Items and the record of tests run and their
  outcomes.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After the cleanup, static analysis reports zero unused imports, zero unused symbols,
  and zero unreachable-code warnings within the target module.
- **SC-002**: 100% of the tests that passed before the cleanup still pass after it, with identical
  outcomes (no test is weakened, skipped, or deleted to make the suite pass).
- **SC-003**: Zero commented-out code blocks and zero orphaned files remain in the target module.
- **SC-004**: For every duplicated type/state conditional identified, the branching decision resides
  in exactly one strategy abstraction, with zero duplicated discriminator branches remaining at the
  former call sites.
- **SC-005**: Zero public API signatures outside the approved safe-to-change list are changed.
- **SC-006**: Every removal in the audit report carries recorded evidence of unreachability or
  non-reference; zero removals are made on unproven assumptions.

## Assumptions

- The "currently-tested behavior" baseline is the set of tests that pass on the target module at the
  start of the audit; this suite is the acceptance oracle for behavior preservation.
- The safe-to-change list for public API signatures is **empty** unless the requester explicitly
  provides one; therefore no public API signatures change by default.
- Version control retains full history, so removing commented-out code and dead code loses no
  recoverable information.
- The audit is scoped to exactly one module/service per this feature; a broader multi-module cleanup
  would be handled as separate features.
- Indirect entry points (Android services/receivers registered in the manifest, DI/reflection,
  Compose/framework callbacks) count as "referenced" for reachability purposes.

<!--
SYNC IMPACT REPORT
==================
Version change: (unratified template) → 1.0.0
Rationale: Initial ratification of the project constitution (MAJOR baseline).

Modified principles: N/A (initial adoption)

Added sections:
  - Core Principles (I–VI)
  - Code Quality Standards
  - Development Workflow & Quality Gates
  - Governance

Removed sections: None (template placeholders replaced)

Principles defined:
  - I. Zero Dead & Unused Code
  - II. Consistent Strategy-Pattern Implementation
  - III. Robust Error Handling
  - IV. No Commented-Out Code
  - V. Behavior-Preserving Removals
  - VI. Test-Verified Refactors

Templates & artifacts reviewed for alignment:
  - .specify/templates/plan-template.md ......... ✅ aligned (generic "Constitution Check"
      gate adapts to these principles; no hardcoded rules to change)
  - .specify/templates/spec-template.md ......... ✅ aligned (no new mandatory spec sections
      introduced by these principles)
  - .specify/templates/tasks-template.md ........ ✅ aligned (existing Polish/refactor phase
      already accommodates cleanup & test-verification tasks)
  - .specify/templates/checklist-template.md .... ✅ aligned (no change required)
  - README.md ................................... ✅ aligned (no principle references to update)
  - Spec Kit command/skill files ................ ✅ reviewed; no outdated agent-specific
      references requiring correction

Follow-up TODOs: None
-->

# Grayzone Constitution

## Core Principles

### I. Zero Dead & Unused Code

The codebase MUST contain zero dead or unreachable code, and no unused imports, variables,
parameters, functions, classes, resources, or files. Every symbol and file that exists MUST be
reachable and referenced by at least one live execution path or build artifact. Contributors MUST
remove — not comment out or annotate around — any code that becomes unreachable or unreferenced.

**Rationale**: Dead code inflates cognitive load, hides real behavior, and rots into silent bugs.
Keeping only live code makes the true behavior of the app auditable and keeps reviews honest.

### II. Consistent Strategy-Pattern Implementation

Where a strategy pattern is used, behavior variation MUST live inside the strategy implementations,
not in duplicated conditional logic scattered across call sites. There MUST NOT be `if`/`when`/`switch`
branches that re-derive which strategy applies once a strategy abstraction exists to own that
decision. Adding a new variant MUST require adding a strategy, not editing conditionals elsewhere.

**Rationale**: Duplicated conditional logic that a strategy should own is the primary source of
inconsistent behavior and missed-branch bugs. Centralizing variation in strategy classes keeps the
open/closed boundary intact and makes each behavior independently testable.

### III. Robust Error Handling

Error handling MUST be explicit and robust. There MUST be no silent catches and no swallowed
exceptions: every `catch` block MUST either handle the error meaningfully, rethrow, or log with
enough context to diagnose it — an empty or comment-only catch is prohibited. Every I/O boundary
(disk, network, IPC, database, VPN/socket, system settings, Android service callbacks) MUST have
explicit failure handling. Failures MUST NOT be masked by returning defaults without a recorded,
intentional reason.

**Rationale**: Grayzone enforces behavior across accessibility, overlay, VPN, and persistence
boundaries where a swallowed exception can silently disable protection. Explicit handling at every
I/O boundary is what makes the app's guarantees dependable.

### IV. No Commented-Out Code

Commented-out code blocks MUST NOT be committed or retained. Version control is the record of
historical code; comments are reserved for explaining non-obvious intent, trade-offs, or
constraints. Contributors MUST delete disabled code rather than leaving it commented.

**Rationale**: Commented-out code is untested, unverified, and misleading; it masquerades as
context while decaying against the live code around it.

### V. Behavior-Preserving Removals

Every removal or cleanup MUST preserve existing observable behavior UNLESS the removed behavior is
provably unreachable. "Provably unreachable" means demonstrated by evidence (call-graph/reference
analysis, coverage, or a build/type check that the path cannot execute) — not by assumption. Any
removal that intentionally changes behavior MUST be called out explicitly and justified, and MUST
NOT be disguised as cleanup.

**Rationale**: Cleanup work is only safe when it is behavior-neutral by default. Requiring proof of
unreachability before removal prevents "refactors" from quietly deleting active functionality.

### VI. Test-Verified Refactors

A refactor MUST NOT be marked complete until it is verified by the existing test suite. Contributors
MUST run the relevant existing tests and confirm they pass against the refactored code before
declaring the work done. If a refactor cannot be verified by existing tests, that gap MUST be stated
explicitly so it can be addressed rather than silently accepted.

**Rationale**: Refactors claim to preserve behavior; the only trustworthy evidence of preservation
is the tests that encode that behavior. Verification-before-completion turns that claim into a
checkable fact.

## Code Quality Standards

These standards apply to all code in the repository (Kotlin/Android application code, build scripts,
and tooling scripts) and are enforced in addition to the Core Principles:

- Static analysis and the compiler MUST NOT report unused imports, unused symbols, or unreachable
  code as warnings; such warnings are treated as defects to fix, not to suppress.
- Suppression annotations (e.g. `@Suppress`) that hide dead-code, unused, or unchecked-exception
  warnings MUST NOT be used to satisfy the Core Principles; they are permitted only with a documented
  technical justification.
- Strategy abstractions MUST expose a single decision point; call sites depend on the abstraction,
  not on concrete variant identity.
- I/O and service-boundary code MUST log failures with actionable context (what failed, on which
  resource) without leaking user data.

## Development Workflow & Quality Gates

- Reviews MUST verify compliance with every Core Principle before approval. A change that adds dead
  code, duplicated strategy conditionals, silent catches, or commented-out code MUST NOT be merged.
- Cleanup and removal changes MUST include, in their description, the evidence that the removed code
  is unreachable (Principle V) or a statement that behavior is preserved.
- Refactor changes MUST report which existing tests were run and that they passed (Principle VI).
- When Core Principles conflict with expedience, the principle wins; exceptions MUST be justified in
  writing in the change description and are subject to reviewer rejection.

## Governance

This constitution supersedes ad-hoc practices for the concerns it covers. All pull requests and
reviews MUST verify compliance with the Core Principles and Quality Gates above.

- **Amendments**: Changes to this constitution MUST be proposed in writing, MUST document the
  rationale and the impact on dependent Spec Kit templates, and MUST be approved before taking effect.
- **Versioning policy**: This constitution is versioned using semantic versioning.
  - MAJOR: Backward-incompatible governance changes — removing or redefining a principle.
  - MINOR: Adding a new principle/section or materially expanding guidance.
  - PATCH: Clarifications, wording, and non-semantic refinements.
- **Compliance review**: Reviewers act as the compliance gate on every change. Justified exceptions
  MUST be recorded in the change description; unjustified violations MUST block merge.

**Version**: 1.0.0 | **Ratified**: 2026-07-25 | **Last Amended**: 2026-07-25

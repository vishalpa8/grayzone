# Phase 1 Data Model: Module Cleanup & Strategy Consolidation Audit

**Feature**: `001-module-cleanup-audit`

This feature is a refactor/cleanup, so the "data model" captures (a) the audit-tracking entities used
to make the work reviewable and (b) the new strategy abstraction introduced during consolidation. No
persisted storage schema, `SharedPreferences` keys, or Room entities change.

## Audit-tracking entities (review artifacts)

### FlaggedItem

Represents a single candidate for removal or consolidation.

| Field | Type | Notes |
|-------|------|-------|
| `id` | string | Stable identifier for cross-referencing in the report |
| `category` | enum | `DEAD_CODE` \| `UNREACHABLE_BRANCH` \| `UNUSED_FUNCTION` \| `UNUSED_SYMBOL` \| `UNUSED_IMPORT` \| `ORPHANED_FILE` \| `COMMENTED_OUT_CODE` \| `STRATEGY_CONSOLIDATION` |
| `location` | string | File + line/range (e.g. `OverlayService.kt:69`) |
| `evidence` | string | Why it is unreachable/unreferenced (search results, call-graph, framework-entry reasoning) |
| `verdict` | enum | `REMOVE` \| `CONSOLIDATE` \| `FLAG_ONLY` (preserve; needs separate decision) |
| `behaviorPreserved` | bool | Whether the change preserves observable behavior |
| `verifyingTests` | string[] | Tests confirming behavior preservation |

**Validation rules**:
- `verdict = REMOVE` requires `category ∈ {DEAD_CODE, UNREACHABLE_BRANCH, UNUSED_*, ORPHANED_FILE, COMMENTED_OUT_CODE}` **and** `evidence` non-empty (FR-004, SC-006).
- Any item whose removal would change test-verified behavior MUST be `FLAG_ONLY` (Principle V; spec edge case for test-only code).
- Public-API-affecting items not on the (empty) safe-to-change list MUST be `FLAG_ONLY` (FR-009).

**Known instances (from Phase 0)**:
- `OverlayService.DEFAULT_LOCKOUT` → `category=DEAD_CODE`, `verdict=REMOVE`.
- `OverlayMode.TINT`, `OverlayMode.REMOVE_TINT` → `category=UNUSED_SYMBOL`, `verdict=FLAG_ONLY` (test-only).
- `OverlayService.showOverlay` mode branching → `category=STRATEGY_CONSOLIDATION`, `verdict=CONSOLIDATE`.

### AuditReport

Collection of `FlaggedItem`s plus the record of test runs.

| Field | Type | Notes |
|-------|------|-------|
| `items` | FlaggedItem[] | All flagged items |
| `testCommand` | string | e.g. `./gradlew :app:testDebugUnitTest` |
| `preRunResult` | enum | `PASS` \| `FAIL` (baseline) |
| `postRunResult` | enum | `PASS` \| `FAIL` (after changes) |

**Validation rules**:
- Completion requires `preRunResult = PASS` and `postRunResult = PASS` (FR-012, SC-002).

## New strategy abstraction (introduced by consolidation)

### OverlayPresentation (strategy interface)

Owns all per-`OverlayMode` presentation and completion decisions currently duplicated across
`showOverlay()`.

| Member | Type | Replaces (current duplicated branch) |
|--------|------|--------------------------------------|
| `title` | string | `if (isAnyLock) "Locked." else "Pause."` |
| `bodyText(prompt)` | string | the `when` for `promptView.text` |
| `countdownText(seconds)` | string | `if (isLocked) formatDuration(..) else if (isBudgetLock\|\|isScheduleLock) "—" else "$seconds"` |
| `subText(seconds)` | string | the `when` for `subView.text` |
| `skipButtonLabel` | string | `if (isAnyLock) "← Leave App" else "← I'll Skip This"` |
| `showsProgress` | bool | `if (isAnyLock) progress.visibility = GONE` |
| `initialSeconds(inputs)` | int | the `secondsRemaining` derivation block |
| `onCountdownFinished(ctx)` | action | the terminal `when { !isAnyLock -> ...; isLocked -> ... }` |

**Implementations (one per mode)**: `FrictionPresentation`, `LockPresentation`,
`BudgetLockPresentation`, `ScheduleLockPresentation`.

**Resolution rule**: exactly one implementation is selected from the incoming `mode: Int`
(`OverlayMode`) at the top of `showOverlay()`; all downstream code depends on the resolved
`OverlayPresentation`, never re-deriving `isLocked/isBudgetLock/isScheduleLock` (FR-007, SC-004).

**Extensibility rule**: adding a new overlay mode = adding a new `OverlayPresentation` implementation
and its resolution mapping; no existing call-site conditional is edited (FR-008).

## Explicitly unchanged (no-touch)

- `OverlayMode` integer constants and their values (pinned by `OverlayModeTest`).
- `SharedPreferences` keys in `PrefsKeys` and Room `UsageDatabase` schema.
- Public method signatures of `OverlayService`, `GrayzoneAccessibilityService`, `BootReceiver`.
- All existing error-handling `catch` blocks and I/O-boundary guards.

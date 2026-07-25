# Audit Report: Module Cleanup & Strategy Consolidation

**Feature**: `001-module-cleanup-audit`
**Target module**: `app/src/main/java/com/grayzone/app/service/` excluding `service/vpn/`
(`OverlayService.kt`, `GrayzoneAccessibilityService.kt`, `BootReceiver.kt`; `OverlayMode` constants in
`PrefsKeys.kt` are read-only).

## AuditReport summary

| Field | Value |
|-------|-------|
| testCommand | `./gradlew :app:testDebugUnitTest` |
| preRunResult | **PASS** (baseline, 2026-07-25 — `BUILD SUCCESSFUL`) |
| postRunResult | **PASS** (post-refactor, 2026-07-25 — `BUILD SUCCESSFUL`, incl. new `OverlayPresentationTest`) |

## Module boundary (T003)

In scope (edited):
- `app/src/main/java/com/grayzone/app/service/OverlayService.kt`
- `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt`
- `app/src/main/java/com/grayzone/app/service/BootReceiver.kt`
- New: `app/src/main/java/com/grayzone/app/service/overlay/*` (strategy implementations)

Read-only / out of scope:
- `app/src/main/java/com/grayzone/app/PrefsKeys.kt` (`OverlayMode` constants — referenced, not changed)
- `app/src/main/java/com/grayzone/app/service/vpn/**` (separate module)

Indirect-reachability guardrail (FR-005): manifest-registered components (`OverlayService`,
`GrayzoneAccessibilityService`, `BootReceiver`) and their lifecycle/callback overrides are treated as
referenced and are never flagged as dead.

## Flagged Items

| id | category | location | evidence | verdict | behaviorPreserved | verifyingTests |
|----|----------|----------|----------|---------|-------------------|----------------|
| FI-01 | DEAD_CODE | `OverlayService.kt` companion `DEFAULT_LOCKOUT` | Repo-wide search: only the declaration exists; no reference. `ConfigValidator.DEFAULT_LOCKOUT_MINUTES` is a distinct symbol. | REMOVE | yes | full unit suite |
| FI-02 | UNUSED_SYMBOL | `PrefsKeys.kt` `OverlayMode.TINT`, `OverlayMode.REMOVE_TINT` | Referenced only by `OverlayModeTest`; no production use (tint handled by `showTint()`/`dismissTint()`). | FLAG_ONLY | n/a (unchanged) | `OverlayModeTest` |
| FI-03 | STRATEGY_CONSOLIDATION | `OverlayService.showOverlay()` + countdown loop | `OverlayMode` discriminator re-derived across title/body/countdown/sub-text/skip-label/progress/finish branches. | CONSOLIDATE | yes | full unit suite (+ new `OverlayPresentationTest`) |
| FI-04 | SILENT_CATCH (FR-014) | `OverlayService.showOverlay()` custom-prompts parse `catch (e: Exception) { emptyList() }` | Empty catch swallows Gson parse errors with no log (Constitution Principle III). | LOG (behavior-preserving) | yes | full unit suite |

_Additional unused-import findings from T006–T008 are appended below as discovered._

### Unused imports / members removed (T006–T008)

- `OverlayService.kt`: removed `DEFAULT_LOCKOUT` (FI-01). All remaining imports verified used
  (manual audit of every import against usage). No commented-out code, no orphaned files.
- `GrayzoneAccessibilityService.kt`: all 5 imports used; no dead code. No changes.
- `BootReceiver.kt`: all 6 imports used; no dead code. No changes.
- IDE linter (`ReadLints`) on all three target files: **no errors**.
- `./gradlew :app:compileDebugKotlin`: no warnings emitted for any of the three target files
  (all compiler warnings are in unrelated files: `LocalShareServer`, `WifiRepository`, `ui/*`).

### US1 verification (T010)

- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** after FI-01 removal + FI-04 logging fix.

## US2 — Strategy consolidation (FI-03)

Replaced the `isLocked` / `isBudgetLock` / `isScheduleLock` / `isAnyLock` branching that
`OverlayService.showOverlay()` re-derived across title, body, countdown text, countdown text-size,
progress visibility, sub-text, skip label, initial-seconds, per-tick countdown, and countdown-finish
with a single `OverlayPresentation` strategy resolved once via `OverlayPresentation.resolve(mode)`.

New files (in `app/src/main/java/com/grayzone/app/service/overlay/`):
- `OverlayPresentation.kt` — interface + `SecondsInputs` / `CountdownFinishContext` / `SkipContext`
  + `resolve(mode)` (the single sanctioned decision point; unknown/non-lock modes → friction,
  matching the prior `else` behavior).
- `FrictionPresentation.kt`, `LockPresentation.kt`, `BudgetLockPresentation.kt`,
  `ScheduleLockPresentation.kt` — one strategy per mode.

`OverlayService` retains all side effects (permission check, `dismissOverlay()`,
`cancelCountdownNotification()` on lock screens, window add/remove, `recordFrictionSkip()`,
`markAppUnlocked()`, coroutine `isActive` guard) and their original evaluation order. The unused
`import com.grayzone.app.formatDuration` (now owned by `LockPresentation`) was removed from
`OverlayService`.

Verification (T019): `./gradlew :app:compileDebugKotlin` — no warnings for target files;
`./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL** (all prior tests + new
`OverlayPresentationTest` green).

### Observation (out of scope — flagged, not changed)

The overlay's arrow (`←`), em-dash (`—`), and ellipsis (`…`) glyphs are stored in the source as
double-encoded (mojibake) byte sequences — e.g. the arrow compiles to `U+00E2 U+2020 U+0090`. The
strategy implementations reproduce these **byte-for-byte** to preserve current observable behavior
(FR-007). Correcting the source encoding would change rendered output and is therefore a separate,
non-behavior-preserving follow-up outside this audit's scope.

## Acceptance gates (SC-001…SC-006)

- [x] SC-001: zero unused-import/unused-symbol/unreachable-code warnings in the target files
      (`compileDebugKotlin` — all warnings are in unrelated files; `formatDuration` import removed).
- [x] SC-002 (baseline established): preRunResult = PASS
- [x] SC-002 (final): postRunResult = PASS, identical outcomes (no existing test modified/weakened).
- [x] SC-003: zero commented-out blocks / orphaned files remain in the target module.
- [x] SC-004: the `OverlayMode` presentation branching is owned by exactly one strategy per mode;
      `showOverlay` re-derives no `mode ==` / `isLocked` discriminator (verified by grep).
- [x] SC-005: zero public API signature changes — `showOverlay` and all `OverlayService` public
      members are unchanged; the new strategy types are internal additions (safe-to-change list empty).
- [x] SC-006: every removal (FI-01, `formatDuration` import) and consolidation (FI-03) carries
      recorded evidence and verifying tests above.

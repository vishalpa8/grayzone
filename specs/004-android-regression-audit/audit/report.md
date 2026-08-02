# Audit report — `004-android-regression-audit`

**Entry point** for the adversarial Android regression / code-quality audit.  
**Completed (fix phase)**: 2026-08-02 · **Gate**: T065 approved ("let's go")

## Severity rubric (FR-006)

| Severity | Meaning (Grayzone calibration) |
|---|---|
| **Critical** | Protection silently off or app unrecoverably bricked |
| **High** | Material bypass / wrong Settings / late lock / dishonest UI |
| **Medium** | Degraded reliability, race, or UX that can strand a user |
| **Low** | Cleanliness, docs drift, dead code, non-load-bearing polish |

## Artifacts

| Artifact | Path |
|---|---|
| Punch list | [`punch-list.md`](./punch-list.md) |
| Finding register | [`findings.md`](./findings.md) + per-unit `findings-*.md` |
| Coverage matrix | [`coverage-matrix.md`](./coverage-matrix.md) |
| Dead-code inventory | [`dead-code-inventory.md`](./dead-code-inventory.md) |
| Validation log | [`validation-log.md`](./validation-log.md) |
| Pending device | [`pending-device.md`](./pending-device.md) |
| Verification gaps | [`verification-gaps.md`](./verification-gaps.md) |
| Release-only | [`release-only.md`](./release-only.md) |
| Named tests index | [`findings.md`](./findings.md) § Named tests index |
| Constitution summary | [`constitution-summary.md`](./constitution-summary.md) |
| Warnings baseline / post-fix | [`evidence/warnings.md`](./evidence/warnings.md) · [`evidence/warnings-post-fix.md`](./evidence/warnings-post-fix.md) |
| Tasks | [`../tasks.md`](../tasks.md) |

## Fix-phase outcome

- **Tier-1 Critical**: code landed; JVM-verified or `fix-pending-device` as appropriate.
- **Tier-2 High**: punch-list Highs addressed (including setup model, VPN races, overlay honesty, dual-clock, JSON refuse-overwrite).
- **Tier-3 Medium / Tier-4 Low**: bulk fixes + dead-code cleanup; gates green.
- **Gates**: `testDebugUnitTest` BUILD SUCCESSFUL · `assembleRelease` BUILD SUCCESSFUL · `assembleDebugAndroidTest` compile-verified (FR-024).

## V1–V10 summary

| Scenario | Result |
|---|---|
| V1 Scope coverage | **pass** (as amended) |
| V2 Punch-list order | **pass** |
| V3–V5 Field completeness samples | **pass** (T046, 2026-08-02 — Phase 5 T036–T045 closed; sample F-001/F-040/F-110/F-148/F-205) |
| V6 Coverage matrix | **pass** |
| V7 Runtime labelling | **pass** — device claims use `needs-verification` / `fix-pending-device` + procedures |
| V8 No new test regressions | **pass** (all fix tiers) |
| V9 Release build green | **pass** |
| V10 Traceability | **pass** with baseline caveat (`audit/baseline.md` dirty tree at audit start) |

## Residual risk

On-device proof list: [`pending-device.md`](./pending-device.md). No finding that required a device was marked `verified`.

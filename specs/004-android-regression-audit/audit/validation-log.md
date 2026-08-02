# Validation log

The audit's own audit trail: which `quickstart.md` validation scenarios have been run, and which of the
two 🛑 approval gates have been passed. Every row is dated and names the evidence checked.

**Created retroactively during T054 follow-up.** T016 was marked complete when the scope gate was
presented and confirmed (correctly — the gate genuinely happened, and `spec.md` line 63 records the
maintainer's confirmation), but this file, which T016 also required, was never created. The Gate record
below is reconstructed from `spec.md`'s Scope of Review section and the confirmation exchange itself, not
invented. See `audit/artifact-drift.md`.

---

## Scenario record

`quickstart.md` V1–V10. A `fail` row is a legitimate and useful result — leaving a row blank because the
outcome was unwelcome is the one thing this table exists to prevent.

| Scenario | What it checks | Owning task | Date | Pass/fail | Evidence checked |
|---|---|---|---|---|---|
| V1 | Every package, resource dir, and build file sits in exactly one review unit or the out-of-scope list (SC-002) | T086 | 2026-08-02 | **pass** (as amended) | Re-confirmed after fix phase: Scope amendments 1–2 still cover root build/config + `README.md`/`INSTALL.md`; main-source denominator remains 68 files (coverage-matrix T015). No new unassigned packages under `app/src/main`. |
| V2 | Fix order decidable from the punch list alone; Critical before High; only Critical/High present (SC-003, FR-005) | T035 | 2026-08-02 | **pass** | `audit/punch-list.md`: Critical table precedes High; Medium/Low absent; every row states user-visible consequence; fix-order recommendation decidable without opening breakdown; release-only table separated; clusters named. |
| V3 | All seven mandatory fields present in any 5 sampled findings (SC-004) | T046 | 2026-08-02 | **pass** | Sweep log shows T032 + T036–T043 complete. Sampled F-001, F-040, F-110, F-148, F-205: each has Review unit, Location, Issue, Trigger, Blast radius, Fix, Test to add, Verification, Disposition. |
| V4 | Callers enumerated; process-death, config-change, and background/foreground risk each stated (SC-006) | T046 | 2026-08-02 | **pass** | Same sample: each Blast radius enumerates callers and states process-death / config-change / foreground-background (docs/build findings use explicit n/a). |
| V5 | Every sampled finding names a test that fails before and passes after (SC-007) | T046 | 2026-08-02 | **pass** | Same sample + Named tests index in `findings.md` (T042). F-148 now covered by `PromptResolutionTest` parseCustom cases. |
| V6 | Coverage matrix: 10 units + 6 areas + 8 conditions, all non-blank with evidence (SC-008) | T058 | 2026-08-02 | **pass** | Canonical 24 rows all non-blank with evidence. Expanded matrix has 31 rows ( +2 amendment units, +5 A3 subs) also all non-blank. A4 is `findings-reported` *(partial)* pending Phase 7 dead-code sweep — not blank. E7 T024 analysis preserved under T055 merge. Counts recorded under `coverage-matrix.md` §Row count check (T058). |
| V7 | Every runtime assertion is tool-backed or `needs-verification` with a device procedure (SC-009, FR-025) | T086 | 2026-08-02 | **pass** | No device/emulator. Device-dependent Critical/High use `fix-pending-device-verification` with procedures in finding records and the consolidated list in `pending-device.md`. No runtime claim marked `verified` without JVM/`assembleRelease` evidence. |
| V8 | Zero previously-passing tests fail after each fix tier (SC-010, SC-012) | T086 | 2026-08-02 | **pass** (Tiers 1–4) | `testDebugUnitTest` BUILD SUCCESSFUL after Critical, High, Medium, and Low tiers (JDK 21). New/renamed coverage includes `MonotonicDeadlineTest`, `ProtectionNotificationStateTest`, `VisibleButUnfocusedSessionTest`, `GrayzoneLoggerTest` (ex-`CleanupAndLoggingTest`). |
| V9 | `BUILD SUCCESSFUL` from Tier 2 after the Critical and Low tiers (SC-012) | T086 | 2026-08-02 | **pass** | `assembleRelease` BUILD SUCCESSFUL after Critical + High + Medium + Low (JDK 21). `assembleDebugAndroidTest` compile-verified only (FR-024). |
| V10 | Every changed file traces to an approved finding (SC-013) | T085 | 2026-08-02 | **pass** (with baseline caveat) | Fix-phase app changes map to approved punch-list / Medium-Low findings (T065 "let's go"). Pre-existing dirty tree of 9 files at audit start recorded in `baseline.md` — those files are not treated as untraceable drift from this feature's fix tier. |

---

## Gate record

The two 🛑 gates. Neither may be passed by the audit on its own initiative.

| Gate | Task | Date | What was presented | What was requested | Outcome |
|---|---|---|---|---|---|
| Scope confirmation | T016 / T017 | 2026-07-31 | The 10 review units (RU-01 … RU-10) from `spec.md`'s Scope of Review table, the Explicitly-out-of-scope list, and the 2 scope amendments T015 had found necessary — RU-08 extended to 7 repository-root build/config files, and `README.md`/`INSTALL.md` admitted for accuracy drift only | Confirmation of the review-unit inventory and the out-of-scope list, and approval of both amendments, before any analysis effort was spent | **Passed.** The maintainer confirmed the 10 review units, the out-of-scope list as written, and both amendments. Recorded at `spec.md` line 63. Analysis was authorized on this basis. |
| Findings approval | T065 | 2026-08-02 | Punch list at `audit/punch-list.md` (12 Critical, ~48 High, clusters named) + finding register `audit/findings.md`. Preconditions: V2 pass (T035); V6 pass (T058); T031–T033 Sweep log present. **Gaps disclosed:** V3–V5 (T046) and full Medium/Low field sweeps (T036–T043) not complete — Phase 5 still open. | Approve / decline each Critical & High (and optionally authorize Phase 5 completion before Medium/Low fixes). Deliverable mode already chose fix-all severities once approved. | **Approved 2026-08-02** — maintainer: "let's go". All Critical + High approved for fix; Medium/Low included per earlier deliverable-mode choice. Tier-1 Critical fixes authorized (T066+). |

---

## Notes for the tasks that write here

- T035 owns the V2 row; T046 owns V3, V4 and V5 as **three separate rows**, each naming its own evidence;
  T058 owns V6; T085 owns V10; T086 owns V1, V7, V8 and V9.
- Fill the existing row rather than appending a duplicate. If a scenario is re-run after a fix, add a
  second dated row and keep the first — the history of a scenario that once failed is worth more than a
  clean-looking table.

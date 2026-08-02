# Phase 1 Data Model: Android Regression & Code-Quality Audit

**Feature**: `004-android-regression-audit` | **Date**: 2026-07-31

The audit's "data" is its report. This document defines the entities from the spec as concrete
records with required fields, validation rules, and state transitions, so the report is machine-
checkable against the spec's requirements rather than judged by feel.

---

## Entity: Finding

The atomic unit of the audit. One Finding = one root cause (FR-009).

| Field | Required | Type / allowed values | Source requirement |
|---|---|---|---|
| `id` | yes | `F-###`, unique, assigned in discovery order | — |
| `review_unit` | yes | One of `RU-01`…`RU-10` | FR-002, FR-003 |
| `location` | yes | File path + class + function (+ line range where stable) | FR-007 |
| `issue` | yes | One-paragraph statement of what is wrong and the user-visible consequence | FR-007 |
| `severity` | yes | `Critical` \| `High` \| `Medium` \| `Low` | FR-006 |
| `severity_bound` | conditional | Range (e.g. `High–Critical`) — required when severity depends on unobservable device behavior | Edge case: unobservable severity |
| `trigger_condition` | yes | Ordered reproduction steps concrete enough to observe the defect unaided | FR-008 |
| `blast_radius` | yes | Blast Radius record (below) | FR-012, FR-013 |
| `fix` | yes | Minimal diff or precise change description; rewrite requires `rewrite_justification` | FR-014 |
| `rewrite_justification` | conditional | Required when `fix` replaces rather than patches a component | FR-014 |
| `test_to_add` | yes | Test record (below) | FR-015 |
| `existing_test_conflict` | conditional | Test name + required change, when an existing test asserts the defect | FR-016 |
| `verification_status` | yes | `confirmed` \| `needs-verification` | FR-010 |
| `evidence_tier` | yes | `T1`\|`T2`\|`T3`\|`T4` per research R2 | FR-024 |
| `device_procedure` | conditional | Required when `verification_status = needs-verification` | FR-025 |
| `build_scope` | yes | `everyday-build` \| `release-only` | FR-011, FR-026 |
| `constitution_principle` | optional | `I`–`VI` when the finding is also a constitution violation | FR-020 |
| `affected_surfaces` | conditional | Additional locations sharing this root cause | FR-009 |
| `observed_against` | yes | Commit SHA or `working-tree (uncommitted: <files>)` | FR-004 |
| `disposition` | yes | See state transitions below | FR-021, FR-023 |

### Validation rules

1. All seven core fields (`location`, `issue`, `severity`, `trigger_condition`, `blast_radius`,
   `fix`, `test_to_add`) MUST be non-empty — SC-004 requires 100% compliance.
2. `severity = Critical` MUST mean the defect silently disables protection, loses user data, or
   crashes the app (FR-006). A defect that is merely ugly or unidiomatic MUST NOT be Critical.
3. `verification_status = confirmed` is permitted only with `evidence_tier` of T1, T2, or T4-with-
   cited-code. Any runtime claim without device access MUST be `needs-verification` (FR-025).
4. `needs-verification` findings MUST carry a `device_procedure`; a bare "needs testing" is invalid.
5. `build_scope = release-only` findings MUST be reported in the release-only section, never mixed
   into the everyday-build section (FR-011, SC-011).
6. Two findings MUST NOT share a root cause; the second is folded into the first's
   `affected_surfaces` (FR-009).
7. Every Finding MUST be traceable to an applied change, and every applied change to a Finding
   (SC-013).

### State transitions (`disposition`)

```text
discovered ──> reported ──> approved ────> fixed ──> verified
                  │                          │          │
                  │                          │          └─> (JVM suite + assembleRelease green)
                  │                          └─> fix-pending-device-verification
                  ├─> declined ──> accepted-risk        (retained in report, FR-023)
                  └─> withdrawn                          (analysis error; reason recorded)
```

- `reported → approved` requires maintainer approval of the punch list (FR-021); no repository
  mutation may occur before this edge is crossed.
- `fixed → verified` requires the tier's JVM suite run to pass (FR-022, Constitution VI).
- `fixed → fix-pending-device-verification` is the terminal state for `needs-verification` findings;
  it MUST NOT be reported as `verified` (FR-026).
- `declined → accepted-risk` retains the record; deletion is prohibited (FR-023).

---

## Entity: Severity

Closed enumeration with the rubric from FR-006. Drives punch-list membership and fix ordering.

| Value | Definition | Punch list? | Fix tier |
|---|---|---|---|
| `Critical` | Silently disables protection, loses user data, or crashes the app | Yes | 1 |
| `High` | Degrades protection, or is user-visible but recoverable | Yes | 2 |
| `Medium` | Incorrect behavior in narrow conditions | No | 3 |
| `Low` | Maintainability, dead code, or cleanliness defect | No | 4 |

**Grayzone-specific calibration**: "silently" is load-bearing. A crash is Critical, but so is a
failure with *no* crash and *no* user notification — protection stopping without telling the user is
this app's worst outcome, and the audit must not rank it below a visible error.

---

## Entity: Blast Radius

Attached to every Finding whose fix touches shared code (FR-012, FR-013).

| Field | Required | Content |
|---|---|---|
| `callers` | yes | Every caller/consumer of the touched function, class, or resource, enumerated (not "several places") |
| `process_death_risk` | yes | What changes if the process dies before/during the touched path |
| `config_change_risk` | yes | What changes on rotation, dark-mode toggle, locale change, or font-scale change |
| `foreground_background_risk` | yes | What changes across app background/foreground and service kill/restart |
| `cross_process_risk` | conditional | Required when the touched code runs in, or shares state with, the `:a11y` process |
| `persistence_risk` | conditional | Required when the touched code writes Room, SharedPreferences, or the shared-storage backup — including upgrade-path effects |
| `release_only_risk` | conditional | Required when R8 or resource shrinking could alter the behavior of the fix |

**Validation**: the first four fields are mandatory for 100% of fixes (SC-006). `callers: none` is
valid only with the reference-search evidence that established it.

---

## Entity: Test Record (`test_to_add`)

| Field | Required | Content |
|---|---|---|
| `kind` | yes | `jvm-unit` \| `instrumented` |
| `target_path` | yes | `app/src/test/...` for `jvm-unit`, `app/src/androidTest/...` for `instrumented` |
| `assertion` | yes | The behavior asserted, stated so it fails before the fix and passes after |
| `executable_here` | yes | `true` for `jvm-unit`; `false` for `instrumented` (no device) |
| `compile_verified` | conditional | For `instrumented`: whether `assembleDebugAndroidTest` compiled it |

**Validation**: `kind = instrumented` MUST NOT be reported as passing (FR-024). Preference order is
`jvm-unit` whenever the defect's logic can be extracted to a pure function — this simultaneously
satisfies FR-015 and increases the share of findings that are verifiable in this environment.

---

## Entity: Coverage Outcome

Guarantees the brief is answered exhaustively (FR-003, FR-017, FR-018, SC-008).

| Field | Required | Allowed values |
|---|---|---|
| `subject` | yes | A review unit (`RU-01`…`RU-10`), a brief area (`A1`…`A6`), or an extended condition (`E1`…`E8`) |
| `outcome` | yes | `findings-reported` \| `no-defect-found` \| `not-applicable` \| `not-verifiable` \| `deferred` |
| `evidence` | yes | What was checked (files, functions, tool runs) — mandatory even for `no-defect-found` |
| `reason` | conditional | Required for `not-applicable`, `not-verifiable`, and `deferred` |
| `finding_ids` | conditional | Required for `findings-reported` |

**Validation**: every one of the 10 units, 6 areas, and 8 conditions MUST appear exactly once, with
zero blanks (SC-008). `no-defect-found` without `evidence` is invalid — the spec's adversarial stance
means "found nothing" is a claim requiring proof of what was examined.

---

## Entity: Verification Gap

| Field | Required | Content |
|---|---|---|
| `finding_id` | yes | The Finding it blocks |
| `missing` | yes | The artifact, device condition, or measurement required |
| `procedure` | yes | Exact steps a maintainer with a device would run |
| `severity_effect` | yes | How severity would change per outcome (resolves `severity_bound`) |

**Rationale**: Constitution VI requires unverifiable gaps to be stated rather than silently accepted;
this entity is the mechanism, and SC-009 audits it (zero unlabelled speculative assertions).

---

## Relationships

```text
Coverage Outcome (1) ──covers──> (0..n) Finding
Finding (1) ──has──> (1) Blast Radius
Finding (1) ──has──> (1) Test Record
Finding (1) ──has──> (0..1) Verification Gap        [required iff needs-verification]
Finding (n) ──rolls up to──> (1) Punch List entry   [iff severity in {Critical, High}]
Finding (1) ──may reference──> (0..n) affected_surfaces
```

## Derived views

- **Punch list** = all Findings where `severity ∈ {Critical, High}`, ordered Critical-first
  (FR-005, contract in `contracts/punch-list.md`).
- **Release-only section** = all Findings where `build_scope = release-only` (FR-011).
- **Pending-device section** = all Findings where `disposition = fix-pending-device-verification`
  (FR-026).
- **Dead-code inventory** = all Findings where `constitution_principle = I` (US5).
- **Coverage matrix** = all Coverage Outcomes (contract in `contracts/coverage-matrix.md`).

# Contract: Coverage Matrix

**Feature**: `004-android-regression-audit`

The coverage matrix is the audit's completeness proof. It exists so that "we didn't find anything
there" and "we never looked there" can never be confused (FR-003, FR-017, FR-018, SC-008). Every row
requires evidence, including the rows that report no defect.

## Part A — Review units (all 10 required)

| Unit | Outcome | Evidence (what was examined) | Findings |
|---|---|---|---|
| RU-01 `com.grayzone.app` root | | | |
| RU-02 `data` | | | |
| RU-03 `policy` | | | |
| RU-04 `service` | | | |
| RU-05 `service.overlay` | | | |
| RU-06 `service.vpn` | | | |
| RU-07 `ui` + `ui.theme` | | | |
| RU-08 build / manifest / config | | | |
| RU-09 resources | | | |
| RU-10 existing tests | | | |

## Part B — Brief areas (all 6 required)

| ID | Area | Outcome | Notes |
|---|---|---|---|
| A1 | Lifecycle correctness | | Fragment-specific items are re-scoped to Compose state + single-Activity navigation (no Fragments exist) |
| A2 | Threading & concurrency | | LiveData/RxJava items re-scoped to `StateFlow`/`SharedFlow` and `DnsTrafficBus` |
| A3 | Android-specific robustness | | Permissions, the three long-lived services, configuration, intents, low memory, offline |
| A4 | Dead / unnecessary code | | Constitution Principle I; includes the unused-parameter warning already observed in `Theme.kt` |
| A5 | Data layer integrity | | Room migration 1→2, DB threading, growth, indices; HTTP-client items are `not-applicable` |
| A6 | Security | | Prefs/log exposure, exported components, R8 stripping |

## Part C — Extended conditions (all 8 required)

| ID | Condition | Outcome | Verifiability here |
|---|---|---|---|
| E1 | Startup & process lifecycle (cold start after force-kill, `Application.onCreate` ANR risk, background-kill restore) | | Partly static (T4); runtime restore needs a device |
| E2 | Interruption handling (call/notification, screen lock mid-write, Doze/App Standby, home vs recents vs swipe-away) | | Static reasoning only; all runtime claims `needs-verification` |
| E3 | Upgrade path (populated-DB migration, renamed prefs/DataStore keys, version-to-version diff) | | Prefs-key analysis is static (T4); migration execution needs a device (T3 compile-only) |
| E4 | Foldables / multi-window (split-screen, service losing foreground focus) | | `needs-verification` — no device, no foldable |
| E5 | Accessibility conflicts (own service vs TalkBack, missing content descriptions) | | Content descriptions static (T4); TalkBack coexistence `needs-verification` |
| E6 | OEM background-kill resilience (Xiaomi/OnePlus/Samsung/Oppo; detect-and-guide vs fail silently) | | Detection/guidance code is static (T4); actual OEM behavior `needs-verification` |
| E7 | Time/locale edge cases (timezone change mid-session, DST boundary, daily reset) | | **Fully verifiable at T1** — extract to pure functions and unit-test |
| E8 | Debug vs release divergence (R8/shrinking, lowest minSdk) | | R8 verifiable at T2 (`assembleRelease` green); minSdk-29 runtime behavior `needs-verification` |

## Rules

1. **Exhaustive**: all 10 units + 6 areas + 8 conditions appear exactly once. Zero blank outcomes
   (SC-008).
2. **Allowed outcomes**: `findings-reported`, `no-defect-found`, `not-applicable`, `not-verifiable`,
   `deferred`.
3. **Evidence mandatory**: including for `no-defect-found`. "Checked, looks fine" is a contract
   violation; name the files and functions examined and the property checked.
4. **Reason mandatory** for `not-applicable`, `not-verifiable`, and `deferred`. For
   `not-applicable`, the reason must also state the re-scoped equivalent question that *was* asked
   (see research R8) — the brief's intent is not discarded just because a library is absent.
5. **Findings linked**: `findings-reported` rows list finding IDs, so every finding traces to a
   subject and every subject to its findings.
6. **E7 is a commitment**: time/locale logic is fully unit-testable in this environment, so
   `not-verifiable` is not an acceptable outcome for it.
7. **Deferred requires approval**: only the maintainer may defer a unit (US1 acceptance scenario 3);
   the audit may not defer on its own initiative.

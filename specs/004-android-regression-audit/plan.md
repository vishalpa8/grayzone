# Implementation Plan: Android Regression & Code-Quality Audit

**Branch**: `004-android-regression-audit` | **Date**: 2026-07-31 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-android-regression-audit/spec.md`

## Summary

Audit the Grayzone app adversarially across 10 review units, produce a Critical-first punch list,
then apply fixes for every approved finding in severity order. Because no device or emulator is
available, confirmation rests on three tools that were verified to work in this environment: source
analysis, the existing JVM unit suite (`testDebugUnitTest`), and a minified `assembleRelease`.
Findings that can only be proven on hardware are labelled needs-verification with an explicit device
procedure, and their fixes are reported separately from statically-confirmed ones.

The audit's leverage comes from targeting the app's actual failure mode: three long-lived components
(accessibility service in a separate `:a11y` process, foreground overlay service, VPN service) whose
breakage is silent, combined with a codebase that has no `ViewModel` layer and persists state through
SharedPreferences plus Room. Process death, cross-process coherence, and the SharedPreferences
upgrade path are therefore the highest-yield analysis axes, not incidental checklist items.

## Technical Context

**Language/Version**: Kotlin 1.9.22 (`jvmTarget` 1.8), Java source/target 1.8, KSP 1.9.22-1.0.17

**Build toolchain**: Gradle 8.6 (wrapper, distribution cached), Android Gradle Plugin 8.2.2. **JDK 21
is mandatory** — the only JDK on `PATH` is Temurin 25 and `JAVA_HOME` is unset, under which AGP 8.2.2
fails immediately with an uninterpretable `* What went wrong: 25.0.4`. Every Gradle invocation in
this feature must pass `-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11`.

**Primary Dependencies**: Compose BOM 2024.01.00 (`ui`, `material3`, `material-icons-extended`),
AndroidX Lifecycle 2.7.0 (`runtime-ktx`, `runtime-compose`), Activity Compose 1.8.2, Room 2.6.1
(runtime + ktx, compiler via KSP), WorkManager 2.9.0, Gson 2.10.1. No DI framework, no HTTP client,
no RxJava, no DataStore.

**Storage**: Room `UsageDatabase` with exported schemas at versions 1 and 2 (`app/schemas/`),
SharedPreferences (`PrefsKeys`, `AppLockState`), and an anti-tamper state backup on shared storage
(`/sdcard/.grayzone/`) reached via `MANAGE_EXTERNAL_STORAGE` (legacy `WRITE_EXTERNAL_STORAGE` on
API 29).

**Testing**: JUnit 4 + Mockito 5.7.0 + mockito-kotlin 5.1.0 on the JVM (~40 test files, runnable
here); `androidTest` with `room-testing` and Espresso (`MigrationTest`, `PeakHourAggregationTest`,
`UsageDaoStatsTest` — compilable but **not executable**, no device).

**Target Platform**: Android `minSdk` 29 → `targetSdk`/`compileSdk` 34. Components: 1 Activity,
1 accessibility service (separate `:a11y` process), 1 foreground overlay service, 1 VPN service,
3 broadcast receivers, 1 FileProvider, 1 WorkManager worker.

**Project Type**: Single-module Android mobile app (`:app`); this feature audits and repairs it
rather than adding product surface.

**Performance / correctness thresholds asserted by the audit**: no disk, database, or file I/O on the
main thread; `Application.onCreate` free of blocking work that risks ANR; VPN packet loop free of
main-thread and unbounded-allocation behavior; blocklist assets loaded without holding peak memory
proportional to raw file size; no coroutine outliving the component that launched it.

**Constraints**: No emulator or physical device — instrumented tests are authored and compiled only.
Release lint (`lintVital`) is disabled via `checkReleaseBuilds = false` because the lint-gradle
download fails on a TLS-inspecting network, so `lintDebug` availability must be probed rather than
assumed. The `debug` build type is non-minified but non-debuggable and release-signed, so R8 and
resource shrinking are the only debug-vs-release divergence axes.

**Scale/Scope**: 68 main-source Kotlin files across 8 packages, plus 42 JVM test files and 3
instrumented test files (113 `.kt` total, verified by T015). 10 review units, 3 long-lived services,
12 Compose screens/sheets/cards, 2 Room schema versions, 23 resource files across 11 resource
directories, 26 lines of ProGuard keep rules.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution v1.0.0 (ratified 2026-07-25). Each principle is both an audit criterion (per FR-020) and
a constraint on the audit's own fix work.

| Principle | Gate applied to this feature | Pre-Phase-0 status |
|---|---|---|
| I. Zero Dead & Unused Code | The audit must itself produce the dead-code inventory (US5) with reference evidence, and fixes must not add unused symbols, parameters, or suppressions. | **PASS** — US5 and FR-020 encode it; `contracts/finding-record.md` requires evidence for every removal claim. |
| II. Consistent Strategy-Pattern Implementation | `service.overlay` uses a presentation strategy; the audit must flag conditionals that re-derive which presentation applies, and fixes must not add such conditionals. | **PASS** — RU-05 carries this as a named risk theme. |
| III. Robust Error Handling | The audit must flag every silent catch and every I/O boundary without explicit failure handling; fixes must not introduce swallowed exceptions or default-returning masks. | **PASS** — a dedicated whole-codebase sweep covers this: an inventory task classifies every `catch` and I/O boundary across all 10 units, and a findings task reports the violations. Per-unit I/O themes (RU-01/02/04/06) supplement it rather than substitute for it. |
| IV. No Commented-Out Code | The audit must flag commented-out blocks; fixes must delete rather than comment. | **PASS** — FR-020. |
| V. Behavior-Preserving Removals | Every removal in the fix phase needs reference/call-graph proof of unreachability or an explicit, justified behavior change. | **PASS** — FR-012/FR-014 plus the Blast Radius entity make this mandatory per finding. |
| VI. Test-Verified Refactors | Fixes must be verified by the existing suite before being called done; unverifiable gaps must be stated, not silently accepted. | **CONDITIONAL PASS** — the JVM suite runs here, but instrumented tests cannot execute. Recorded in Complexity Tracking; FR-024/FR-025/FR-026 force the gap to be declared rather than hidden. |

**Gate result: PASS** with one declared deviation (Principle VI, instrumented-test execution),
justified in Complexity Tracking below. No unjustified violations, so Phase 0 proceeds.

### Post-design re-evaluation (after Phase 1)

Re-checked against the generated artifacts:

| Principle | Where the design enforces it | Status |
|---|---|---|
| I | `contracts/coverage-matrix.md` Part B row A4 makes the dead-code inventory a mandatory outcome; `contracts/finding-record.md` requires reference evidence for removal claims and caps dead code at Low severity so it cannot crowd out the punch list. Phase 0 already produced a concrete instance (`Theme.kt:19` unused parameter). | **PASS** |
| II | `research.md` R6 gives RU-05 an explicit question about presentation-selection conditionals; `data-model.md` ties such findings to `constitution_principle = II`. | **PASS** |
| III | `tasks.md` T011 inventories every `catch` block and I/O boundary across all 10 review units, and T030 sweeps them into findings tagged by owning unit, rating Critical where a swallowed exception can silently disable protection. R6's per-unit I/O questions and the Blast Radius `persistence_risk` field supplement this. | **PASS** |
| IV | Covered by the A4 coverage row and the finding-record contract. | **PASS** |
| V | `data-model.md` makes `blast_radius.callers` mandatory with `none` permitted only alongside the search that proved it; the disposition state machine forbids repository mutation before approval. | **PASS** |
| VI | `quickstart.md` Tier 1 is the gate run between severity tiers, with a recorded green baseline (`BUILD SUCCESSFUL in 47s`). The instrumented-test gap is confined to Tier 3 (compile-only) and is declared per finding via the Verification Gap entity, never implied as verified. | **CONDITIONAL PASS** — same declared deviation, now mechanically enforced rather than promised |

**Post-design gate result: PASS.** No new violations introduced by the design. The single deviation
is unchanged in scope, and Phase 1 tightened it from a stated intention into a contract requirement
(`verification_status`, `device_procedure`, and the `fix-pending-device-verification` terminal state).

### Phase 0/1 tooling baseline (verified this session)

| Probe | Command | Result |
|---|---|---|
| JDK 25 (default) | `.\gradlew.bat testDebugUnitTest` | **FAILED** — `* What went wrong: 25.0.4` (AGP 8.2.2 rejects JDK 25) |
| JDK 21 unit suite | `.\gradlew.bat testDebugUnitTest -Dorg.gradle.java.home=...jdk-21.0.11` | **BUILD SUCCESSFUL in 47s** — all ~40 JVM test files pass |
| JDK 21 release build | `.\gradlew.bat assembleRelease -Dorg.gradle.java.home=...jdk-21.0.11` | **BUILD SUCCESSFUL in 3m 38s** — R8, resource shrinking, and release signing all executed |
| Warning harvest | (side effect of the release compile) | `Theme.kt:19:22 Parameter 'context' is never used`, plus 3 obsolete `source`/`target` 8 warnings |

The audit therefore starts from a green baseline on both gates, which is what makes SC-010 ("zero
newly failing tests") a meaningful assertion rather than an unknown.

## Project Structure

### Documentation (this feature)

```text
specs/004-android-regression-audit/
├── plan.md              # This file (/speckit-plan command output)
├── spec.md              # Feature specification
├── research.md          # Phase 0 output — audit techniques, tooling baseline, per-unit attack plan
├── data-model.md        # Phase 1 output — Finding / Severity / Blast Radius / Coverage Outcome schemas
├── quickstart.md        # Phase 1 output — verification commands and how to reproduce the audit's evidence
├── contracts/
│   ├── finding-record.md    # Required shape of a single finding
│   ├── punch-list.md        # Required shape and ordering of the Critical-first list
│   └── coverage-matrix.md   # Required per-area / per-condition outcome record
├── checklists/
│   └── requirements.md  # Spec quality checklist (complete)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/
├── build.gradle.kts                     # RU-08
├── proguard-rules.pro                   # RU-08
├── schemas/com.grayzone.app.data.UsageDatabase/{1,2}.json   # RU-08
└── src/
    ├── main/
    │   ├── AndroidManifest.xml          # RU-08
    │   ├── java/com/grayzone/app/
    │   │   ├── *.kt                     # RU-01 (13 files: MainActivity, GrayzoneApplication,
    │   │   │                            #   AppLockState, PrefsKeys, SetupRequirements,
    │   │   │                            #   AccessibilityPromptState, AccessibilityServiceIds,
    │   │   │                            #   AppUtils, DateUtils, Extensions, GrayzoneLogger,
    │   │   │                            #   OemBatteryHelper, SharedComponents)
    │   │   ├── data/                    # RU-02 (15 files: Room stack, CleanupWorker,
    │   │   │                            #   StateBackupManager, ScheduleManager, StreakManager,
    │   │   │                            #   budgets/limits, ProtectionHealthRepository)
    │   │   ├── policy/                  # RU-03 (SessionPolicyEngine, SessionExpiryDecision,
    │   │   │                            #   SessionModels)
    │   │   ├── service/                 # RU-04 (GrayzoneAccessibilityService [:a11y process],
    │   │   │   │                        #   OverlayService, BootReceiver, SessionExpiryReceiver,
    │   │   │   │                        #   SessionExpiryScheduler)
    │   │   │   ├── overlay/             # RU-05 (OverlayPresentation + Friction/Lock/BudgetLock/
    │   │   │   │                        #   ScheduleLock presentations)
    │   │   │   └── vpn/                 # RU-06 (AdBlockVpnService, DnsPacketHelper,
    │   │   │                            #   DnsQueryClassifier, DnsResolverPool, DnsTrafficBus,
    │   │   │                            #   BlocklistManager, DomainZoneMatcher,
    │   │   │                            #   GrayzoneBloomFilter, AbusedTldList, VpnHoldLock,
    │   │   │                            #   VpnHoldLockStore, VpnHoldExpiryScheduler)
    │   │   └── ui/ + ui/theme/          # RU-07 (12 screens/sheets/cards + Color/Theme/Type)
    │   └── res/                         # RU-09 (drawable, mipmap-*, layout, raw, values, xml)
    ├── test/java/com/grayzone/app/      # RU-10 (~40 JVM test files) — runnable
    └── androidTest/java/com/grayzone/app/data/   # RU-10 (3 instrumented files) — compile-only
```

**Structure Decision**: Single-module Android layout retained as-is. The audit adds no source
directories and no modules; it produces documentation under `specs/004-android-regression-audit/`
and, in the fix phase, minimal patches inside the existing packages above plus new tests under
`app/src/test/` (runnable) and `app/src/androidTest/` (compile-only). Review units map one-to-one
onto existing package boundaries so that findings, fixes, and their blast radius stay traceable to a
single directory.

## Complexity Tracking

> Filled because the Constitution Check records one conditional gate.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Principle VI (test-verified refactors) cannot be fully satisfied for instrumented tests | No emulator or physical device exists in this environment (maintainer-confirmed), yet the highest-severity risks — process death, Doze, cross-process state, rotation, populated-DB upgrade — are only observable at runtime. Refusing to write those tests would leave the app's worst failure modes permanently uncovered. | Restricting the audit to JVM-verifiable findings only was rejected because it would exclude the accessibility/VPN/overlay lifecycle defects that motivate the audit. Instead: instrumented tests are authored and required to **compile** via `assembleDebugAndroidTest`, their fixes are reported separately (FR-026), and each carries the device procedure that would confirm it (FR-025). The gap is declared, never implied to be verified. |
| Fix phase mutates production code across 10 review units in one feature | The maintainer approved applying fixes for all approved findings at every severity (FR-021), and defects in this codebase cluster by cross-cutting cause (SharedPreferences access, coroutine scoping, service restart) rather than by package. | Splitting into one feature per review unit was rejected because shared root causes would be fixed inconsistently across features and the blast-radius analysis would be duplicated. Risk is contained instead by FR-022's severity-tier sequencing with the JVM suite run between tiers, and by FR-009's report-once-at-root-cause rule. |

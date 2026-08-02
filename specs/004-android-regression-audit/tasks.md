---

description: "Task list for the Grayzone Android regression & code-quality audit"
---

# Tasks: Android Regression & Code-Quality Audit

**Input**: Design documents from `/specs/004-android-regression-audit/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/`, `quickstart.md`

**Tests**: Test tasks ARE included — the specification mandates them. FR-015 requires every finding to
name a test that fails before its fix and passes after, and Constitution Principle VI forbids calling
a refactor complete until the existing suite verifies it.

**Organization**: Tasks are grouped by user story so each story is independently deliverable.

**⚠️ Before starting any task**, check [`audit/artifact-drift.md`](audit/artifact-drift.md) — five
recorded mismatches between what a task's text says it produces and what exists on disk.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — guaranteed to write a **different file** than every other `[P]` task
  in the same group
- **[Story]**: Which user story this task belongs to (US1–US5)
- Every task names the exact file path it reads or writes

### Two shared bookkeeping files

Tasks that edit many files in place, and tasks that validate rather than produce, would otherwise leave
nothing on disk to prove they ran. Two append-only files close that gap:

- **Sweep log** — a table in `specs/004-android-regression-audit/audit/findings.md`. Every glob-scoped
  in-place task (T031–T033, T036–T043, T065, T081) stamps one row as its final action: task ID, date,
  files touched, findings affected, and any file deliberately skipped with its reason. T034 and T046
  read it to detect a partial sweep instead of under-reporting silently.
- **Validation log** — `specs/004-android-regression-audit/audit/validation-log.md`, created by T016,
  with one Scenario record row per `quickstart.md` scenario V1–V10 and a Gate record row for each of the
  two 🛑 gates. T035, T046, T058, T065 and T086 append to it; T082 links it.

Appending one row to either file is the **single documented exception** to the `[P]` different-file
rule: append as the task's last action, and never rewrite another task's row.

## Path Conventions

- **Audit output**: `specs/004-android-regression-audit/audit/`
- **App source under review**: `app/src/main/java/com/grayzone/app/`
- **JVM tests**: `app/src/test/java/com/grayzone/app/` (runnable here)
- **Instrumented tests**: `app/src/androidTest/java/com/grayzone/app/` (compile-only, no device)
- **Gradle**: all invocations require `-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11`

---

## Phase 1: Setup (Audit Workspace & Evidence Capture)

**Purpose**: Create the report workspace and capture the raw evidence every later finding cites.

- [X] T001 Create audit output directories `specs/004-android-regression-audit/audit/`, `audit/evidence/`, and `audit/conditions/`
- [X] T002 Record the code state under review in `specs/004-android-regression-audit/audit/baseline.md`: `git rev-parse HEAD`, `git status --porcelain`, and the list of the 9 currently-modified files, so every finding's `observed_against` field (FR-004) resolves to a real state
- [X] T003 [P] Link the verified tooling baseline in `specs/004-android-regression-audit/audit/baseline.md` by reference to `plan.md`'s "Phase 0/1 tooling baseline" table rather than restating it, so the two copies cannot drift
- [X] T004 [P] Harvest the full compiler warning set into `specs/004-android-regression-audit/audit/evidence/warnings.md` by running `.\gradlew.bat compileDebugKotlin -Dorg.gradle.java.home=... --rerun-tasks --console=plain` and capturing every `^w:` and `warning:` line (research R4 — incremental builds hide these)
- [X] T005 [P] Capture R8 evidence into `specs/004-android-regression-audit/audit/evidence/r8.md` by summarizing `app/build/outputs/mapping/release/usage.txt`, `seeds.txt`, and `resources.txt` — specifically whether the `AppAccessibilityService` keep rule matched anything and whether `AdBlockVpnService`, `BootReceiver`, `SessionExpiryReceiver`, and `VpnHoldExpiryReceiver` survive minification
- [X] T006 [P] Attempt `.\gradlew.bat lintDebug -Dorg.gradle.java.home=...` and record either the report path or the resolution failure in `specs/004-android-regression-audit/audit/evidence/lint.md` (research R3 — failure is expected behind a TLS-inspecting proxy and is not a blocker)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared analysis substrates that every finding's Blast Radius and every coverage row
depends on. Building these once prevents re-deriving them per finding and prevents the vague
blast-radius anti-pattern that `contracts/finding-record.md` prohibits.

**⚠️ CRITICAL**: No review-unit analysis (Phase 4+) may begin until this phase is complete.

- [X] T007 Build a cross-reference index in `specs/004-android-regression-audit/audit/evidence/reference-index.md` mapping every public function, object, and companion in `app/src/main/java/com/grayzone/app/` to its call sites — this is the source of the mandatory enumerated `callers` field (FR-012)
- [X] T008 [P] Inventory every SharedPreferences key in `specs/004-android-regression-audit/audit/evidence/prefs-inventory.md` by reading `app/src/main/java/com/grayzone/app/PrefsKeys.kt` and `AppLockState.kt`, recording each key's writer, readers, type, and whether any legacy/renamed key is still read (E3 upgrade path)
- [X] T009 [P] Inventory every coroutine scope, thread, and executor in `specs/004-android-regression-audit/audit/evidence/concurrency-inventory.md` across `service/`, `service/vpn/`, and `data/`, recording each one's owner, cancellation site, and whether it can outlive its component (A2)
- [X] T010 [P] Inventory cross-process state in `specs/004-android-regression-audit/audit/evidence/cross-process-inventory.md`: every singleton, `object`, companion cache, and `StateFlow`/`SharedFlow` reachable from `service/GrayzoneAccessibilityService.kt` (which runs in `:a11y`), flagging each as main-process-only, `:a11y`-only, or falsely-assumed-shared (research R5 axis 2)
- [X] T011 [P] Inventory every `catch` block and every I/O boundary (disk, network, IPC, database, VPN/socket, system settings, service callbacks) across all of `app/src/main/java/com/grayzone/app/` in `specs/004-android-regression-audit/audit/evidence/error-handling-inventory.md`, classifying each as handled / rethrown / logged-with-context / **silent** — this is the substrate for the Constitution Principle III sweep (T030) and covers all 10 review units, not only the four with named I/O themes
- [X] T012 [P] Build the prior-fix watchlist in `specs/004-android-regression-audit/audit/evidence/prior-fix-watchlist.md` from `specs/002-fix-timeout-accessibility-bugs/spec.md` and its `tasks.md`: the defects that feature fixed, so the audit can check whether any has regressed, plus the 9 files carrying uncommitted modifications flagged as elevated-attention per `spec.md`'s Assumptions
- [X] T013 [P] Instantiate the coverage matrix at `specs/004-android-regression-audit/audit/coverage-matrix.md` from `contracts/coverage-matrix.md`, with all 10 review-unit rows, 6 area rows, and 8 condition rows present and outcomes blank
- [X] T014 [P] Create the finding register index `specs/004-android-regression-audit/audit/findings.md` that owns the `F-###` numbering sequence, links to every per-unit findings file, and carries the field template from `contracts/finding-record.md`

**Checkpoint**: Reference, prefs, concurrency, cross-process, error-handling, and prior-fix substrates
all exist — analysis can begin.

---

## Phase 3: User Story 1 - Scope agreement before analysis (Priority: P1) 🎯 MVP

**Goal**: The maintainer confirms or amends the review scope before any analysis effort is spent.

**Independent Test**: The maintainer reads the scope section alone and can approve it or name a
missing unit, with no other artifact needed.

- [X] T015 [US1] Verify scope completeness against the filesystem: confirm every directory under `app/src/main/java/com/grayzone/app/`, every `app/src/main/res/` subdirectory, and every build file appears in exactly one review unit in `specs/004-android-regression-audit/spec.md` (SC-002), recording the check in `specs/004-android-regression-audit/audit/coverage-matrix.md`
- [X] T016 [US1] Present the review-unit inventory and out-of-scope list from `specs/004-android-regression-audit/spec.md` to the maintainer and request confirmation (FR-001), and record the act as a dated **Gate record** row in `specs/004-android-regression-audit/audit/validation-log.md` — what was presented, the date, and what was requested — creating that file if absent with a **Scenario record** table (scenario, owning task, date, pass/fail, evidence checked) covering `quickstart.md` V1–V10 plus a **Gate record** table for the two 🛑 gates (T016/T017 scope, T065 approval). This is what makes T016 verifiable separately from T017's `spec.md` line
- [X] T017 [US1] Record the maintainer's response in `specs/004-android-regression-audit/spec.md`: append any scope amendment to the Scope of Review section, and mark any maintainer-deferred unit as `deferred` in `specs/004-android-regression-audit/audit/coverage-matrix.md` (US1 scenario 3 — the audit may not defer on its own initiative)

**Checkpoint**: Scope is confirmed in writing. Analysis is now authorized. 🛑 **Do not proceed past
this checkpoint without maintainer confirmation.**

---

## Phase 4: User Story 2 - Critical-first punch list (Priority: P1)

**Goal**: An ordered, self-contained list of protection-breaking, data-losing, and crashing defects
the maintainer can act on in under 10 minutes.

**Independent Test**: Read the punch list alone; for each entry, state the user-visible consequence
and the reproduction path without opening the detailed breakdown.

**Note**: T018–T030 each write their own findings file, so they are genuinely parallel. Every analysis
task consults `audit/evidence/prior-fix-watchlist.md` (T012) and treats the flagged files as
elevated-attention. Medium/Low findings discovered here are parked and completed in Phase 5.

- [X] T018 [P] [US2] Analyze RU-04 `app/src/main/java/com/grayzone/app/service/` per the R6 question set, writing findings to `specs/004-android-regression-audit/audit/findings-ru04.md`: foreground-notification compliance windows, recovery after system kill, coroutine scope ownership vs `onDestroy`/`onUnbind`, `BootReceiver` restoring all three components, `AlarmManager` exactness and reschedule-after-reboot, and `:a11y` reading main-process in-memory state (**already answered by T010 — zero falsely-shared declarations; do not re-derive, cite `audit/evidence/cross-process-inventory.md` and spend the effort on the alarm-exactness and `commit()` leads in `audit/findings.md` instead**)
- [X] T019 [P] [US2] Analyze RU-06 `app/src/main/java/com/grayzone/app/service/vpn/` writing to `specs/004-android-regression-audit/audit/findings-ru06.md`: descriptor/socket/thread closure on `onRevoke`, `onDestroy`, and exception paths; packet-loop threading and per-packet allocation; malformed/truncated DNS packets killing the tunnel; **offline and flaky-network behavior — upstream resolver failure in `DnsResolverPool.kt` and connectivity loss mid-resolution, with a no-crash guarantee**; blocklist peak memory from `res/raw`; 24h hold expiry across process death and Doze
- [X] T020 [P] [US2] Analyze RU-02 `app/src/main/java/com/grayzone/app/data/` writing to `specs/004-android-regression-audit/audit/findings-ru02.md`: whether the 1→2 migration in `app/schemas/` is destructive, main-thread DAO calls, unbounded `UsageEvent` growth and whether `CleanupWorker` survives Doze, missing indices, and `StateBackupManager` shared-storage failure handling
- [X] T021 [P] [US2] Analyze RU-01 writing to `specs/004-android-regression-audit/audit/findings-ru01.md`, covering **all 13 root-package files** — `GrayzoneApplication.kt` (blocking I/O in `onCreate` / ANR risk, and whether `onTrimMemory`/`onLowMemory` are handled at all), `MainActivity.kt` (process-death survival), `AppLockState.kt` and `PrefsKeys.kt` (legacy key reads), `GrayzoneLogger.kt` (package names or domains leaked in release), `OemBatteryHelper.kt` (guides the user or fails silently), `DateUtils.kt`, `AppUtils.kt`, `Extensions.kt`, `SharedComponents.kt`, `AccessibilityServiceIds.kt`
- [X] T022 [P] [US2] Analyze RU-01 permission and setup state writing to `specs/004-android-regression-audit/audit/findings-ru01-permissions.md`: `SetupRequirements.kt` and `AccessibilityPromptState.kt` against runtime permission **denial**, **"don't ask again"**, and **revocation mid-session** for every permission protection depends on — overlay (`SYSTEM_ALERT_WINDOW`), accessibility service enablement, `PACKAGE_USAGE_STATS`, `POST_NOTIFICATIONS`, `MANAGE_EXTERNAL_STORAGE`, battery-optimization exemption, and VPN consent — determining for each whether loss is detected and surfaced to the user or fails silently
- [X] T023 [P] [US2] Analyze RU-05 `app/src/main/java/com/grayzone/app/service/overlay/` writing to `specs/004-android-regression-audit/audit/findings-ru05.md`: window/view removal on every exit path including exceptions, duplicated presentation-selection conditionals (Principle II), and overlay behavior on rotation and split-screen entry
- [X] T024 [P] [US2] Analyze RU-03 `app/src/main/java/com/grayzone/app/policy/` writing to `specs/004-android-regression-audit/audit/findings-ru03.md`: decision correctness across clock changes and stale timestamps, centralized vs re-derived strategy selection, and boundary conditions (expiry exactly now, negative remaining)
- [X] T025 [P] [US2] Analyze RU-08 `app/build.gradle.kts`, `app/proguard-rules.pro`, and `app/src/main/AndroidManifest.xml` writing to `specs/004-android-regression-audit/audit/findings-ru08.md`: keep-rule staleness confirmed against T005's evidence, hostile/malformed intent and missing-extras handling for the exported `MainActivity`, `AdBlockVpnService`, and `BootReceiver`, declared-vs-used permissions, `allowBackup=false` vs `backup_rules.xml`, over-broad `accessibility_service_config.xml`, and minSdk-29 safety
- [X] T025a [P] [US2] Analyze the RU-08 **repository-root** configuration added by scope amendment 1, writing to `specs/004-android-regression-audit/audit/findings-ru08-root.md`: root `build.gradle.kts` (AGP 8.2.2 / Kotlin 1.9.22 / KSP pinning vs the JDK-21 constraint from research R1), `settings.gradle.kts` (`FAIL_ON_PROJECT_REPOS`, module includes), `gradle.properties` (JVM args, the `file.encoding=UTF-8` pins that `app/build.gradle.kts` comments depend on, `nonTransitiveRClass`), `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.6 pin, `validateDistributionUrl`), and `.gitignore` + `keystore.properties.example` (whether every secret-bearing path is actually excluded — verify `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, and confirm no secret-bearing file is currently tracked via `git ls-files`)
- [X] T025b [P] [US2] Check `README.md` and `INSTALL.md` for **accuracy drift only** per scope amendment 2, writing to `specs/004-android-regression-audit/audit/findings-docs.md`: whether stated JDK, Gradle, or build instructions contradict the verified reality in `plan.md`'s tooling baseline (JDK 21 mandatory, JDK 25 fails with a bare `25.0.4`), and whether `INSTALL.md` — untracked and new — duplicates or contradicts `README.md`. Report factual contradictions only; documentation content, structure, and style are out of scope
- [X] T026 [P] [US2] Analyze RU-07 state handling `app/src/main/java/com/grayzone/app/ui/` writing to `specs/004-android-regression-audit/audit/findings-ru07.md`: per-screen `remember` vs `rememberSaveable` vs persisted state across process death (no `ViewModel` exists — research R5 axis 1), long-running work launched from composition scope, and sheet/dialog leaks on configuration change
- [X] T027 [P] [US2] Analyze RU-07 configuration variants writing to `specs/004-android-regression-audit/audit/findings-ru07-config.md`: dark mode via `ui/theme/Theme.kt`, RTL and locale handling, density coverage across small/large screens, large-font/font-scale layout robustness, and large-list handling for the monitored-app picker in `AppsScreen.kt`
- [X] T028 [P] [US2] Analyze RU-09 resources writing to `specs/004-android-regression-audit/audit/findings-ru09.md`: hardcoded user-facing strings in Compose instead of `res/values/strings.xml` (blocks localization), `res/layout/notification_countdown.xml` robustness under long text and large fonts, mipmap/drawable density coverage, and the memory cost of the `res/raw` blocklist assets
- [X] T029 [P] [US2] Analyze RU-10 existing test suites writing to `specs/004-android-regression-audit/audit/findings-ru10.md`: coverage gaps against the app's stated guarantees, whether `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` exercises populated data, and tests asserting stale or defective behavior
- [X] T030 [P] [US2] Sweep Constitution Principle III violations across all 10 review units writing to `specs/004-android-regression-audit/audit/findings-principle3.md`, using `audit/evidence/error-handling-inventory.md` (T011): every silent or comment-only `catch`, every swallowed exception, every I/O boundary without explicit failure handling, and every failure masked by returning a default without a recorded reason — tagging each finding with its owning `review_unit`, and rating Critical where a swallowed exception can silently disable protection
- [X] T031 [US2] **Reconcile and normalize the severities the analysis tasks already assigned** across all `specs/004-android-regression-audit/audit/findings-*.md` files — T018–T030 each rate their findings inline as they file them, so this task does not assign severities from scratch and must not re-do that work. Check every existing rating against the FR-006 rubric and the Grayzone calibration in `data-model.md` — a silent protection failure with no crash and no user notification ranks Critical, not below a visible error — correcting outliers in place and naming the rubric clause behind each correction. **Resolve conflicts where two review units rated the same defect differently**: `F-060`/`F-207` and `F-062`/`F-206` have already been reconciled by hand and are the pattern — the pair keeps one severity, and the overridden record states the pair and the reason (folding the duplicate records themselves is T033's job, not this task's). Then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` — task ID, date, files touched, findings affected, and any file deliberately skipped with its reason — creating that table if absent, so T034 can prove the sweep reached every findings file instead of under-reporting Criticals silently
- [X] T032 [US2] Label each finding's `verification_status` and `evidence_tier` across all `specs/004-android-regression-audit/audit/findings-*.md` files, adding a `device_procedure` to every `needs-verification` entry (FR-010, FR-025) — no unlabelled runtime claims may survive this task — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` (task ID, date, files touched, findings affected, files skipped and why), which is how T046 establishes that T032 ran at all
- [X] T033 [US2] Fold duplicate root causes across all `specs/004-android-regression-audit/audit/findings-*.md` files into single findings with `affected_surfaces` lists (FR-009), so the punch list is not inflated by one defect appearing in several units — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` listing each pair folded and the surviving `F-###`, so a fold is distinguishable from a dropped finding
- [X] T034 [US2] Assemble `specs/004-android-regression-audit/audit/punch-list.md` per `contracts/punch-list.md`: Critical rows before High, Medium/Low excluded, release-only findings in their own table, plus the header facts (observed-against state from T002, verification environment) and the full Critical/High/Medium/Low totals line. **First read the Sweep log in `specs/004-android-regression-audit/audit/findings.md` and confirm T031, T032 and T033 each covered every `findings-*.md` file**; if any file is unswept, state that in the punch list header and name the gap rather than publishing totals that silently under-report — a missing Critical row is indistinguishable from a correct exclusion. **Also read the "The answer" section of `audit/conditions/E8.md`: F-064, F-074, F-125, F-126 and F-130 compound into one release-observability cluster and must be presented together, not as five unrelated rows — each is individually Medium/Low, but jointly they make the release build's worst failure mode undetectable by every diagnostic the project has. Likewise read `audit/conditions/E1.md`'s answer: present C7 (F-043→F-060) with F-044 attached as the ANR half of the same `onCreate:64` call, and F-066 as the warm-start counterpart that process-death testing misses — do not mint a Critical for force-stop leaving protection off. Read E7/C12: present F-240 with F-110/F-111/F-049 as one clock-tamper cluster preferring a shared monotonic helper**
- [X] T035 [US2] Validate the punch list against `quickstart.md` scenario V2 — confirm fix order is decidable from the list alone and that every row states a user-visible consequence rather than a code observation — recording the V2 outcome as a Scenario record row (scenario, owning task, date, pass/fail, evidence checked) in `specs/004-android-regression-audit/audit/validation-log.md`, creating that file per T016's structure if it does not yet exist. A failing row is a legitimate result and must be recorded as `fail` rather than left blank; T065 reads this row to establish that its input was validated

**Checkpoint**: The punch list is deliverable on its own. This is the audit's MVP decision artifact.

---

## Phase 5: User Story 3 - Per-issue breakdown with regression-safe fix and test (Priority: P2)

**Goal**: Every finding becomes a self-contained record a second engineer can act on without asking a
question.

**Independent Test**: Pick any finding, reproduce it from the trigger condition alone, apply the fix,
and write the named test — with no auditor contact.

- [X] T036 [US3] Complete the Medium and Low findings parked during Phase 4 in every `specs/004-android-regression-audit/audit/findings-*.md`, so the register covers all four severities — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming the files completed and the parked findings closed, so "no Mediums left" is distinguishable from "this file was never revisited" — **complete 2026-08-02**: field audit shows Medium/Low records already filed in Phase 4; no parked stubs remained
- [X] T037 [P] [US3] Write enumerated Blast Radius records for all RU-01, RU-01-permissions, RU-02, and RU-03 findings in `specs/004-android-regression-audit/audit/findings-ru01.md`, `findings-ru01-permissions.md`, `findings-ru02.md`, and `findings-ru03.md`, drawing callers from `audit/evidence/reference-index.md` and adding `persistence_risk` for every finding touching Room, SharedPreferences, or the shared-storage backup — then stamp one **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming these four files and the findings covered — confirmed present; docs/root N/A axes filled where thin
- [X] T038 [P] [US3] Write enumerated Blast Radius records for all RU-04, RU-05, and RU-06 findings in `specs/004-android-regression-audit/audit/findings-ru04.md`, `findings-ru05.md`, and `findings-ru06.md`, mandatorily including `cross_process_risk` for anything reachable from `:a11y` per `audit/evidence/cross-process-inventory.md` — then stamp one **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming these three files and the findings covered — confirmed present at filing
- [X] T039 [P] [US3] Write enumerated Blast Radius records for all RU-07, RU-07-config, RU-08, RU-09, RU-10, and Principle III findings in their respective `specs/004-android-regression-audit/audit/findings-*.md` files, including `release_only_risk` wherever R8 or resource shrinking could alter the fix's behavior — then stamp one **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming every file it covered and the findings in each — confirmed; procedure stubs added where Verification lacked them
- [X] T040 [US3] Write reproduction steps for every finding as numbered, observable steps (FR-008), including `adb` commands where they shorten the repro, across all `specs/004-android-regression-audit/audit/findings-*.md` — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` (task ID, date, files touched, findings affected, any finding left without a repro and why) — Trigger condition present on active findings
- [X] T041 [US3] Write the minimal fix for every finding **into that finding's own record in its owning `specs/004-android-regression-audit/audit/findings-*.md` file**, and for any proposal that replaces rather than patches a component, add the `rewrite_justification` explaining why patching is insufficient (FR-014) — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming the files touched and any finding left without a fix and why — Fix fields present; F-148 code fix applied 2026-08-02
- [X] T042 [P] [US3] Name the test for every finding per `data-model.md`'s Test Record **in that finding's own record in its owning `specs/004-android-regression-audit/audit/findings-*.md` file**, preferring `jvm-unit` wherever the defective logic can be extracted to a pure function — this maximizes what is actually verifiable without a device. Then mirror every named test into a single **Named tests index** table in `specs/004-android-regression-audit/audit/findings.md` (finding `F-###`, severity, test name, `jvm-unit` vs instrumented, target source file), which is the one place T066, T071 and T074 read from — plus a **Sweep log** row recording the files swept — index generated 2026-08-02
- [X] T043 [P] [US3] Cross-check `app/src/test/java/com/grayzone/app/` and `app/src/androidTest/java/com/grayzone/app/data/` against T029's findings for tests that assert defective behavior, recording each as an `existing_test_conflict` with its required change (FR-016) **in the owning finding's record in its `specs/004-android-regression-audit/audit/findings-*.md` file** — then stamp a **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md` naming every conflict found and the finding that owns it, so a conflict cannot be lost between files — 35 records already carry conflicts; Sweep log lists owners
- [X] T044 [US3] Split release-only findings into `specs/004-android-regression-audit/audit/release-only.md`, cross-referenced from the register, so they never mix with everyday-build findings (FR-011, SC-011)
- [X] T045 [US3] Consolidate every `needs-verification` entry into `specs/004-android-regression-audit/audit/verification-gaps.md` with its missing artifact, device procedure, and severity effect per the Verification Gap entity
- [X] T046 [US3] Validate the register against `quickstart.md` scenarios V3, V4, and V5 — all seven mandatory fields present, callers enumerated with the four risk axes stated, and every finding naming a before/after test — recording **one Scenario record row per scenario (V3, V4, V5)** in `specs/004-android-regression-audit/audit/validation-log.md`, each naming the evidence checked. Establish first, from the Sweep log in `specs/004-android-regression-audit/audit/findings.md`, that T032 and T036–T043 actually completed across every `findings-*.md` file; a V3 pass asserted over a partial sweep is worse than a recorded fail — V3/V4/V5 recorded 2026-08-02

**Checkpoint**: Every finding is independently actionable.

---

## Phase 6: User Story 4 - Extended platform-condition coverage (Priority: P2)

**Goal**: The conditions manual test plans miss each get a recorded outcome — no ambiguity.

**Independent Test**: Every one of the 8 extended conditions shows a finding, a no-defect outcome, or
a not-verifiable outcome with a reason.

**Note**: T047–T054 each write their own file under `audit/conditions/`; T055 merges them into the
matrix. This keeps the `[P]` markers honest — no two parallel tasks write the same file.

**⚠️ MERGE WARNING — read before starting any task in this phase.** The Part C rows of
`specs/004-android-regression-audit/audit/coverage-matrix.md` are **already populated** for E1, E3, E6,
E7 and E8 while `audit/conditions/` is empty — the reverse of the flow these tasks assume. The
authoritative instruction is the **MERGE WARNING block at the top of that file**: T047–T054 read their
existing row as *input* rather than starting from a blank page, and T055 fills blank cells and
reconciles rather than rewriting rows. E7's clock-tamper analysis is load-bearing for Criticals C5 and
C6, so a straight overwrite destroys confirmed evidence. See `audit/artifact-drift.md` case D1.

- [X] T047 [P] [US4] Assess E1 startup & process lifecycle against `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` and `MainActivity.kt`, writing to `specs/004-android-regression-audit/audit/conditions/E1.md`: cold start after force-kill, warm start from recents, first-launch vs n-th launch, `onCreate` ANR risk, and state restore after system-initiated background kill. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E1 as input and copy it into `E1.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T048 [P] [US4] Assess E2 interruption handling across `service/` and `data/` writing to `specs/004-android-regression-audit/audit/conditions/E2.md`: **incoming call or notification during an active session**, screen lock/unlock mid-DB-write, Doze and App Standby deferral of `CleanupWorker` and `AlarmManager` alarms, and the home vs task-switcher vs swipe-away lifecycle paths. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E2 as input and copy it into `E2.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T049 [P] [US4] Assess E3 upgrade path writing to `specs/004-android-regression-audit/audit/conditions/E3.md`: whether `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` exercises populated data rather than an empty DB, and whether any renamed or removed prefs key is still read per `audit/evidence/prefs-inventory.md`. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E3 as input and copy it into `E3.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T050 [P] [US4] Assess E4 foldables & multi-window against `service/OverlayService.kt` and `service/overlay/` writing to `specs/004-android-regression-audit/audit/conditions/E4.md`: split-screen behavior and posture change, including the VPN/overlay services losing foreground focus, recorded as `needs-verification` with device procedures. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E4 as input and copy it into `E4.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T051 [P] [US4] Assess E5 accessibility conflicts writing to `specs/004-android-regression-audit/audit/conditions/E5.md`: TalkBack and other concurrent accessibility services coexisting with `service/GrayzoneAccessibilityService.kt`, `res/xml/accessibility_service_config.xml` event filtering, and missing `contentDescription` on interactive elements across `app/src/main/java/com/grayzone/app/ui/`. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E5 as input and copy it into `E5.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T052 [P] [US4] Assess E6 OEM background-kill resilience against `app/src/main/java/com/grayzone/app/OemBatteryHelper.kt` and `data/ProtectionHealthRepository.kt` writing to `specs/004-android-regression-audit/audit/conditions/E6.md`: does the app detect a kill and guide the user, or fail silently? **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E6 as input and copy it into `E6.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis — F-061 is already recorded there and T052 starts from it, not from zero**
- [X] T053 [US4] Assess E7 time/locale edge cases against `DateUtils.kt`, `data/DailyBudget.kt`, `data/StreakEvaluator.kt`, `data/RuntimeSessionReset.kt`, and `policy/SessionExpiryDecision.kt` writing to `specs/004-android-regression-audit/audit/conditions/E7.md` — timezone change mid-session, DST boundaries, and date-based reset logic. Per `contracts/coverage-matrix.md` rule 6, `not-verifiable` is **not** an acceptable outcome here: this logic is fully unit-testable at Tier 1. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E7 as input and copy it into `E7.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis — row E7 already holds a complete analysis citing F-110 and F-111, so this task reconciles and extends it rather than re-deriving it**
- [X] T054 [P] [US4] Assess E8 debug-vs-release divergence using `audit/evidence/r8.md` and `app/build.gradle.kts` writing to `specs/004-android-regression-audit/audit/conditions/E8.md`, including whether `checkReleaseBuilds = false` hides real defects by removing the only automatic gate over the minified build, plus minSdk-29 API safety. **Per the MERGE WARNING at the top of `specs/004-android-regression-audit/audit/coverage-matrix.md`, read the existing Part C row E8 as input and copy it into `E8.md` under a *Pre-existing input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis**
- [X] T055 [US4] Merge the eight `specs/004-android-regression-audit/audit/conditions/E*.md` outcomes into the Part C rows of `specs/004-android-regression-audit/audit/coverage-matrix.md`. **Execute this as fill-blank-cells-and-reconcile, never as a row rewrite** — the MERGE WARNING at the top of that file is authoritative, rows E1, E3, E6, E7 and E8 already carry confirmed content, and overwriting E7 destroys the clock-tamper reasoning that Criticals C5 and C6 rest on. Where an `E<n>.md` conclusion contradicts the existing row, record both and state which prevails and why rather than silently replacing the row
- [X] T056 [US4] Complete all 6 brief-area rows (A1–A6) in `specs/004-android-regression-audit/audit/coverage-matrix.md`, giving `not-applicable` outcomes their re-scoped equivalent question per research R8 (no HTTP client, no Fragments, no LiveData/RxJava, no DI framework), and confirming A3's sub-areas are each answered — permissions (T022), configuration (T027), low memory (T021, T028), deep links/intents (T025), offline (T019)
- [X] T057 [US4] Complete all 10 review-unit rows in `specs/004-android-regression-audit/audit/coverage-matrix.md` with evidence for every row — including `no-defect-found` rows, where naming what was examined is mandatory
- [X] T058 [US4] Validate the matrix against `quickstart.md` scenario V6: 24 rows, zero blank outcomes, zero evidence-free `no-defect-found` entries — recording the V6 outcome as a Scenario record row in `specs/004-android-regression-audit/audit/validation-log.md` with the counts actually observed. **Perform all three checks and record the result there, not in the matrix**: the existing "Row count check (T058)" heading in `coverage-matrix.md` states the 24-row requirement rather than performing it and asserts nothing about blank outcomes. Note that rows of mixed provenance (Part C — see the MERGE WARNING) must be validated on their merged content

**Checkpoint**: The brief is answered exhaustively; nothing is silently unexamined.

---

## Phase 7: User Story 5 - Dead code and unused resource inventory (Priority: P3)

**Goal**: Constitution Principle I compliance, with reference evidence for every removal claim.

**Independent Test**: Search the repository for each entry's references; the "unreferenced" claim
either holds or does not.

**Note**: T059–T063 each write their own file; T064 consolidates. No two parallel tasks share a file.

- [X] T059 [P] [US5] Identify unreferenced Kotlin symbols across `app/src/main/java/com/grayzone/app/` using `audit/evidence/reference-index.md` and `audit/evidence/warnings.md`, writing to `specs/004-android-regression-audit/audit/dead-code-symbols.md` — including the already-confirmed `ui/theme/Theme.kt:19` unused `context` parameter. **Any "swept N files" completeness statement must cite 68 main-source Kotlin files**, the count established by the T015 scope check in `audit/coverage-matrix.md` and re-verified 2026-07-31, **not the superseded 73 stated in `warnings.md`** — see the dated correction note at the foot of that file. Read `warnings.md` as the pre-fix baseline only; T080's post-fix harvest lands in `audit/evidence/warnings-post-fix.md`
- [X] T060 [P] [US5] Identify unreferenced resources across `app/src/main/res/` (drawable, mipmap, layout, raw, values) writing to `specs/004-android-regression-audit/audit/dead-code-resources.md`, cross-checked against `app/build/outputs/mapping/release/resources.txt` from T005
- [X] T061 [P] [US5] Identify orphaned flow collectors and duplicated fetch paths — `StateFlow`/`SharedFlow` with no subscribers (notably `service/vpn/DnsTrafficBus.kt`) and repository methods re-fetching the same data — writing to `specs/004-android-regression-audit/audit/dead-code-flows.md`
- [X] T062 [P] [US5] Identify commented-out code blocks (Principle IV) and duplicated strategy conditionals (Principle II) across `app/src/main/java/com/grayzone/app/`, writing to `specs/004-android-regression-audit/audit/dead-code-comments.md`
- [X] T063 [P] [US5] Audit `app/src/test/java/com/grayzone/app/` for duplicate or overlapping tests (e.g. root-level `BlocklistManagerTest.kt` vs `service/vpn/BlocklistDecisionTest.kt`, `ScheduleLogicTest.kt` vs `data/ScheduleMatchingTest.kt`) writing to `specs/004-android-regression-audit/audit/dead-code-tests.md`
- [X] T064 [US5] Consolidate T059–T063 into `specs/004-android-regression-audit/audit/dead-code-inventory.md`, adding reference evidence to every entry, distinguishing stale configuration (a keep rule naming a nonexistent class) from live usage, and flagging any removal that would change observable behavior as a behavior change rather than cleanup (Principle V) — stating the **68 main-source / 42 JVM test / 3 instrumented** denominators from the T015 scope check (re-verified 2026-07-31) wherever the inventory makes a coverage or completeness claim, never the superseded 73

**Checkpoint**: Dead-code inventory complete with evidence, capped at Low severity.

---

## Phase 8: Approval Gate & Fix Application

**Purpose**: Apply fixes for approved findings in severity tiers, with the regression gate run between
tiers. Tasks in this phase carry no story label — they serve every story's findings.

**🛑 HARD GATE**: T065 must complete before any task in this phase touches `app/`. Per FR-021 and the
`data-model.md` disposition state machine, no repository mutation is permitted before the maintainer
approves the punch list.

- [X] T065 Present `specs/004-android-regression-audit/audit/punch-list.md` and the finding register to the maintainer for approval; record each finding's `disposition` (`approved` / `declined`) in its `specs/004-android-regression-audit/audit/findings-*.md` record, moving declined items to `accepted-risk` with the decision retained (FR-023), and marking any finding the audit itself retracts as `withdrawn` with the analysis error stated. **Before presenting, confirm both inputs were actually validated**: `specs/004-android-regression-audit/audit/validation-log.md` must show V2 (T035) and V3–V5 (T046) recorded as passed, and the Sweep log in `specs/004-android-regression-audit/audit/findings.md` must show T031–T033 and T036–T043 reaching every `findings-*.md` file — an approval gate over unvalidated inputs is not a gate, and any gap must be presented to the maintainer alongside the punch list. Record the approval as a dated **Gate record** row in `audit/validation-log.md` and the disposition pass as a **Sweep log** row in `audit/findings.md`

### Tier 1 — Critical

- [X] T066 Write the failing tests for every approved Critical finding in `app/src/test/java/com/grayzone/app/` (and `app/src/androidTest/java/com/grayzone/app/` where the behavior is only reachable on-device), taking the test names and targets from the **Named tests index** in `specs/004-android-regression-audit/audit/findings.md` (T042), and confirm each JVM test FAILS against current code
- [X] T067 Apply the minimal fixes for all approved Critical findings in `app/src/main/java/com/grayzone/app/`, grouped by root cause rather than by file (research R7)
- [X] T068 Run `.\gradlew.bat testDebugUnitTest -Dorg.gradle.java.home=...` and confirm `BUILD SUCCESSFUL` with zero previously-passing tests newly failing (SC-010, SC-012)
- [X] T069 Run `.\gradlew.bat assembleRelease -Dorg.gradle.java.home=...` and confirm `BUILD SUCCESSFUL`, comparing `app/build/outputs/mapping/release/usage.txt` against T005's capture to confirm no fix introduced new R8 stripping
- [X] T070 Run `.\gradlew.bat assembleDebugAndroidTest -Dorg.gradle.java.home=...` to confirm new instrumented tests compile, recording in `specs/004-android-regression-audit/audit/baseline.md` that they are compile-verified and NOT execution-verified (FR-024)

### Tier 2 — High

- [X] T071 Write the failing tests for every approved High finding in `app/src/test/java/com/grayzone/app/`, taking the test names and targets from the **Named tests index** in `specs/004-android-regression-audit/audit/findings.md` (T042), confirming each JVM test FAILS first
- [X] T072 Apply the minimal fixes for all approved High findings in `app/src/main/java/com/grayzone/app/`, grouped by root cause — **complete 2026-08-02**: batches 1–3 covered punch-list Highs including F-003/F-022/F-035/F-044/F-086/F-091/F-092/F-096/F-161/F-211/F-231/F-233 (+ earlier F-004…F-210). Device-dependent fixes landed with `fix-pending-device-verification`. Test-gap Highs F-200–F-202 addressed via new/updated JVM coverage where extractable; residual instrumented gaps listed in `pending-device.md`
- [X] T073 Run `.\gradlew.bat testDebugUnitTest -Dorg.gradle.java.home=...` and confirm zero newly failing tests before proceeding to Tier 3 (FR-022) — green after High batch 3 (JDK 21)

### Tier 3 — Medium

- [X] T074 Write the failing tests for every approved Medium finding in `app/src/test/java/com/grayzone/app/`, taking the test names and targets from the **Named tests index** in `specs/004-android-regression-audit/audit/findings.md` (T042) — presentation/friction/setup/VPN path coverage updated with fixes
- [X] T075 Apply the minimal fixes for all approved Medium findings in `app/src/main/java/com/grayzone/app/` — **complete 2026-08-02**: F-008/009/010/012/025–032/035/036/038/050/052/055/063/087/098–100/103/112/113/146–147/151/158/216/235/250/265 (+ earlier Mediums). Residual Mediums that are pure UX polish / localization / foldable geometry deferred only where device-proof required — see `pending-device.md`
- [X] T076 Run `.\gradlew.bat testDebugUnitTest -Dorg.gradle.java.home=...` and confirm zero newly failing tests before proceeding to Tier 4 — green (JDK 21)

### Tier 4 — Low (dead code & cleanliness)

- [X] T077 Apply the approved dead-code and unused-resource removals from `specs/004-android-regression-audit/audit/dead-code-inventory.md` across `app/src/main/java/com/grayzone/app/`, `app/src/main/res/`, and `app/proguard-rules.pro` — deleted unreferenced helpers; ProGuard keep points at `GrayzoneAccessibilityService`; Theme unused `context` removed
- [X] T078 Apply the approved test consolidations in `app/src/test/java/com/grayzone/app/`, preserving every distinct assertion (Principle V — removals must be behavior-preserving) — `CleanupAndLoggingTest` → `GrayzoneLoggerTest`; unsafe whole-class merges deferred with note in `dead-code-tests.md`
- [X] T079 Run `.\gradlew.bat testDebugUnitTest -Dorg.gradle.java.home=...` and `.\gradlew.bat assembleRelease -Dorg.gradle.java.home=...`, confirming both green after the removals (SC-012)
- [X] T080 Re-run the T004 warning harvest and write `specs/004-android-regression-audit/audit/evidence/warnings-post-fix.md` (baseline `warnings.md` left immutable)

---

## Phase 9: Polish & Final Report Assembly

**Purpose**: Close the loop between findings, fixes, and the spec's success criteria.

- [X] T081 Update every finding's final `disposition` across `specs/004-android-regression-audit/audit/findings-*.md` to `verified`, `fix-pending-device-verification`, `accepted-risk`, or `withdrawn`, never marking a device-dependent fix as verified (FR-026) — then stamp a closing **Sweep log** row in `specs/004-android-regression-audit/audit/findings.md`
- [X] T082 [P] Assemble `specs/004-android-regression-audit/audit/report.md` as the single entry point
- [X] T083 [P] Write `specs/004-android-regression-audit/audit/pending-device.md`
- [X] T084 [P] Record `specs/004-android-regression-audit/audit/constitution-summary.md`
- [X] T085 Verify traceability (SC-013) — recorded in validation-log V10; pre-existing dirty tree at baseline noted
- [X] T086 Run remaining quickstart scenarios V1/V7/V8/V9; complete V1–V10 table in report + validation-log
- [X] T087 Update `specs/004-android-regression-audit/checklists/requirements.md` completion notes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup (T001–T006)**: No dependencies — start immediately
- **Phase 2 Foundational (T007–T014)**: Depends on Phase 1 — **BLOCKS all analysis**
- **Phase 3 US1 (T015–T017)**: Depends on Phase 2 — **BLOCKS Phases 4–7** (scope must be confirmed first)
- **Phase 4 US2 (T018–T035)**: Depends on Phase 3 approval
- **Phase 5 US3 (T036–T046)**: Depends on Phase 4 (needs the finding register populated)
- **Phase 6 US4 (T047–T058)**: Depends on Phase 3; can run in parallel with Phases 4–5
- **Phase 7 US5 (T059–T064)**: Depends on Phase 2 (needs the reference index); can run in parallel with Phases 4–6
- **Phase 8 Fixes (T065–T080)**: Depends on Phases 4–7 complete AND T065 maintainer approval
- **Phase 9 Polish (T081–T087)**: Depends on Phase 8

### Critical path

```text
T001 → T007 → T011 → T015 → T016 → T017 (approval) → T018-T030 → T031 → T034 (punch list)
     → T065 (approval) → T066 → T067 → T068 → T071 → T072 → T073 → ... → T085
```

### Within Phase 8

Tiers are strictly sequential — Tier 1 must be green before Tier 2 begins (FR-022). Within a tier:
tests first (must fail), then fixes, then the gate run. This is what makes a regression attributable
to a narrow batch instead of to dozens of changes.

### Parallel Opportunities

Every `[P]` group below writes disjoint files, verified by path:

- T003–T006 (evidence capture) after T002 — `baseline.md`, `warnings.md`, `r8.md`, `lint.md`
- T008–T014 (inventories and scaffolding) after T007 — 7 distinct files
- **T018–T030 (13 analysis tasks) run fully in parallel** — 13 distinct `findings-*.md` files
- T037–T039 (blast-radius batches) — disjoint findings-file groups; T042–T043 — disjoint concerns
- T047–T052 and T054 (extended conditions) — one file each under `audit/conditions/`; T053 is
  sequential only because it also drives Tier-1 test extraction
- T059–T063 (dead-code sweeps) — one file each, consolidated by T064

## Parallel Example: Phase 4 analysis

```bash
# 13 analyses, 13 output files, zero write contention:
Task: "RU-04 service/            → audit/findings-ru04.md"
Task: "RU-06 service/vpn/        → audit/findings-ru06.md"
Task: "RU-02 data/               → audit/findings-ru02.md"
Task: "RU-01 root package        → audit/findings-ru01.md"
Task: "RU-01 permissions/setup   → audit/findings-ru01-permissions.md"
Task: "RU-05 service/overlay/    → audit/findings-ru05.md"
Task: "RU-03 policy/             → audit/findings-ru03.md"
Task: "RU-08 build/manifest      → audit/findings-ru08.md"
Task: "RU-07 state handling      → audit/findings-ru07.md"
Task: "RU-07 config variants     → audit/findings-ru07-config.md"
Task: "RU-09 resources           → audit/findings-ru09.md"
Task: "RU-10 existing tests      → audit/findings-ru10.md"
Task: "Principle III sweep       → audit/findings-principle3.md"
```

---

## Implementation Strategy

### MVP scope

**US1 + US2 (T001–T035)** is the MVP: confirmed scope plus a Critical-first punch list. This alone
answers the maintainer's real question — "what must I fix today?" — and is deliverable without any
repository change.

### Incremental delivery

1. Phases 1–2 → evidence and indexes exist
2. Phase 3 → scope confirmed 🛑 **gate**
3. Phase 4 → punch list delivered (**MVP** — stop and validate here)
4. Phase 5 → every finding independently actionable
5. Phase 6 → coverage proven exhaustive
6. Phase 7 → dead-code inventory
7. Phase 8 → fixes applied in severity tiers 🛑 **approval gate at T065**
8. Phase 9 → traceability and final report

### Notes

- Every Gradle command needs `-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11`; without it the
  build fails with a bare `25.0.4` that names neither cause nor fix
- Two 🛑 gates exist (T017 scope, T065 fix approval) — neither may be self-approved
- Instrumented tests are compile-verified only; never report them as passing
- Judge Gradle runs by their `BUILD SUCCESSFUL`/`BUILD FAILED` line, not by the PowerShell
  `NativeCommandError` noise the wrapper produces on stderr

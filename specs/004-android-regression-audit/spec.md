# Feature Specification: Android Regression & Code-Quality Audit

**Feature Branch**: `004-android-regression-audit`

**Created**: 2026-07-31

**Status**: Draft

**Input**: User description: "Senior Android engineer + QA lead performing a full, adversarial regression and code-quality audit of the Grayzone app. Scope spans lifecycle correctness, threading/concurrency, Android-specific robustness (permissions, foreground/VPN/accessibility services, configuration, deep links, low memory, offline), dead/unnecessary code, data-layer integrity (Room migrations, DB threading, growth, indices), security (unencrypted sensitive data, exported components, R8 stripping), plus startup/process lifecycle, interruption handling, upgrade path, foldables/multi-window, accessibility-service conflicts, OEM background-kill resilience, time/locale edge cases, and debug-vs-release divergence. Regression safety is non-negotiable: identify every caller/consumer, state what could break, prefer minimal patch over rewrite. Output per issue: location, issue + severity, trigger condition, blast radius, fix, test to add. Deliver a Critical-first punch list before the detailed breakdown, mark anything unverified as 'needs verification', and confirm the module/package review scope before starting."

## Overview

The Grayzone app enforces user-facing screen-time and content protection through long-lived
background components (an accessibility service in a separate process, a foreground overlay
service, and a VPN service) whose failure is *silent* — protection simply stops working and the
user is not told. This audit produces an evidence-backed defect inventory for that behavior,
prioritized so the most protection-breaking defects are addressed first, and written so that every
proposed fix carries its own regression-safety analysis and a test that would catch the defect
recurring.

The deliverable of this feature is the audit itself: a reviewed scope agreement, a Critical-first
punch list, and a per-issue breakdown. Fix implementation is governed by the mode chosen in
FR-021 and is sequenced after the punch list is accepted.

## Scope of Review *(mandatory — requires stakeholder confirmation before analysis begins)*

The repository is a single Gradle module (`:app`). Every package below is in scope. Nothing outside
this list will be reviewed without an explicit scope amendment.

### Review units

| # | Review unit | Contents | Primary risk themes |
|---|-------------|----------|---------------------|
| RU-01 | `com.grayzone.app` (root) | `MainActivity`, `GrayzoneApplication`, `AppLockState`, `PrefsKeys`, `SetupRequirements`, `AccessibilityPromptState`, `AccessibilityServiceIds`, `AppUtils`, `DateUtils`, `Extensions`, `GrayzoneLogger`, `OemBatteryHelper`, `SharedComponents` | Startup/ANR risk, process-death state loss, preference-key upgrade path, logging of sensitive data, OEM battery guidance |
| RU-02 | `com.grayzone.app.data` | `UsageDatabase`, `UsageDao`, `UsageEvent`, `CleanupWorker`, `ConfigValidator`, `DailyBudget`, `DefaultMonitoredApps`, `PerAppLimits`, `ProtectionHealthRepository`, `ProtectionStatus`, `RuntimeSessionReset`, `ScheduleManager`, `StateBackupManager`, `StreakEvaluator`, `StreakManager` | Room migration integrity, main-thread DB access, unbounded table growth, missing indices, external-storage backup I/O, daily-reset/timezone logic |
| RU-03 | `com.grayzone.app.policy` | `SessionPolicyEngine`, `SessionExpiryDecision`, `SessionModels` | Decision correctness across background/foreground transitions, clock changes, duplicated conditionals that a strategy should own |
| RU-04 | `com.grayzone.app.service` | `GrayzoneAccessibilityService` (separate `:a11y` process), `OverlayService`, `BootReceiver`, `SessionExpiryReceiver`, `SessionExpiryScheduler` | Foreground-service notification correctness, killed-by-system recovery, cross-process state divergence, alarm/Doze reliability, boot restart, coroutine scope leaks |
| RU-05 | `com.grayzone.app.service.overlay` | `OverlayPresentation` and the `Friction`, `Lock`, `BudgetLock`, `ScheduleLock` presentations | Window/view leaks, strategy-pattern consistency (Constitution II), multi-window and configuration handling |
| RU-06 | `com.grayzone.app.service.vpn` | `AdBlockVpnService`, `BlocklistManager`, `DnsPacketHelper`, `DnsQueryClassifier`, `DnsResolverPool`, `DnsTrafficBus`, `DomainZoneMatcher`, `GrayzoneBloomFilter`, `AbusedTldList`, `VpnHoldLock`, `VpnHoldLockStore`, `VpnHoldExpiryScheduler` (+ the manifest-declared `VpnHoldExpiryReceiver`) | Socket/descriptor lifetime, revoke/teardown paths, blocking I/O and thread ownership, malformed-packet robustness, memory footprint of blocklists, hold-expiry across process death, exported VPN service surface |
| RU-07 | `com.grayzone.app.ui` and `com.grayzone.app.ui.theme` | `HomeScreen`, `AppsScreen`, `LimitsScreen`, `SettingsScreen`, `VpnScreen`, `OnboardingScreen`, `CustomPromptsSheet`, `PerAppSettingsSheet`, `ScheduleSheet`, `DailyBreakCard`, `QuickFocusCard`, `StreakCard`, `Color`, `Theme`, `Type` | State survival across recomposition/rotation/process death, work launched from composition scope, dark mode, RTL/locale, density and large-font layout, missing content descriptions |
| RU-08 | Build, manifest, and configuration (app-level **and repository-root**) | `app/build.gradle.kts`, `app/proguard-rules.pro`, `AndroidManifest.xml`, `res/xml/accessibility_service_config.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/file_paths.xml`, `app/schemas/**`, **root `build.gradle.kts`**, **`settings.gradle.kts`**, **`gradle.properties`**, **`gradle/wrapper/gradle-wrapper.properties`**, **`gradlew` / `gradlew.bat`**, **`.gitignore`**, **`keystore.properties.example`**, **`.editorconfig`** | Exported-component exposure, permission declarations, R8/shrink-resource divergence in release, stale keep rules, disabled release lint, non-debuggable debug build, minSdk-29 compatibility, plugin/toolchain version pinning, dependency-resolution mode, secret-exclusion correctness in `.gitignore`, signing-config contract for fresh clones |
| RU-09 | Resources | `res/drawable`, `res/mipmap-*`, `res/layout/notification_countdown.xml`, `res/raw` blocklist assets, `res/values/strings.xml`, `res/values/themes.xml` | Unused/unreferenced resources, density coverage, hardcoded user-facing strings, large asset memory cost |
| RU-10 | Existing test suites | `app/src/test/**` (JVM, 42 files), `app/src/androidTest/**` (`MigrationTest`, `PeakHourAggregationTest`, `UsageDaoStatsTest`) | Coverage gaps against every defect found, migration testing against populated data, duplicate/overlapping tests, tests asserting stale behavior |

### Explicitly out of scope

- Product/UX redesign decisions already specified in `specs/003-premium-ui-redesign`.
- Feature additions, new capabilities, or dependency upgrades not required to fix an identified defect.
- Server-side or backend concerns (the app has no HTTP client and no remote API layer).
- Play Store listing, release process, and CI configuration.
- Documentation *content, structure, and style*. `README.md` and `INSTALL.md` are in scope for
  **accuracy drift only** — specifically whether their stated setup or build instructions contradict
  the verified build reality (notably the JDK-21 requirement from research R1). Nothing beyond factual
  contradiction is reviewed.

### Scope amendments (recorded per FR-001, US1 acceptance scenario 2)

| # | Date | Amendment | Rationale |
|---|---|---|---|
| 1 | 2026-07-31 | RU-08 extended from app-level build files to include 7 repository-root build/config files | T015 found they were assigned to no unit, failing SC-002. They carry the AGP/Kotlin version pins behind the JDK-21 constraint, the dependency-resolution mode, and the `.gitignore` secret exclusions. |
| 2 | 2026-07-31 | `README.md` / `INSTALL.md` added as in-scope for accuracy drift only | T015 found them unassigned. `INSTALL.md` is untracked and new, so it may already contradict the JDK-21 requirement the audit verified. |

Maintainer confirmed both amendments and the 10 review units at the T017 gate on 2026-07-31.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Scope agreement before analysis (Priority: P1)

The maintainer receives the complete inventory of review units (the table above) together with the
declared out-of-scope list, and confirms or amends it before any analysis effort is spent.

**Why this priority**: An audit that reviews the wrong surface wastes the entire effort. Scope
confirmation is the cheapest possible checkpoint and gates everything downstream.

**Independent Test**: The maintainer reads the Scope of Review section alone and can either approve
it or name a missing/unwanted unit; no other artifact is needed to make that decision.

**Acceptance Scenarios**:

1. **Given** the audit has not started, **When** the maintainer opens the scope section, **Then** every package and configuration file in the repository appears in exactly one review unit or in the out-of-scope list.
2. **Given** the maintainer names an additional area to include, **When** the scope is amended, **Then** the amendment is recorded in the scope section before analysis begins.
3. **Given** a review unit is deferred by the maintainer, **When** the audit proceeds, **Then** the final report lists that unit as deferred rather than silently omitting it.

---

### User Story 2 - Critical-first punch list (Priority: P1)

The maintainer receives a short, ordered list of the defects that can break protection, lose user
data, or crash the app — Critical items first, then High — each with enough information to decide
whether to act now, before reading any detailed analysis.

**Why this priority**: This is the decision-making artifact. The maintainer's first question is
"what must I fix today?", and it must be answerable in one screen.

**Independent Test**: The punch list is read in isolation; for each entry the maintainer can state
the user-visible consequence and the reproduction path without consulting the detailed breakdown.

**Acceptance Scenarios**:

1. **Given** the audit is complete, **When** the maintainer reads the punch list, **Then** entries are ordered Critical before High and each names its review unit, its user-visible consequence, and its trigger condition.
2. **Given** a defect makes protection silently stop working, **When** it is classified, **Then** it is rated Critical.
3. **Given** the punch list is presented, **When** the maintainer counts its entries, **Then** the list contains only Critical and High findings, with Medium and Low deferred to the detailed breakdown.

---

### User Story 3 - Per-issue breakdown with regression-safe fix and test (Priority: P2)

For every finding, the maintainer receives a single self-contained record: where it lives, what is
wrong and how severe, the exact conditions that trigger it, everything else that depends on the
affected code, the smallest fix that resolves it, and the test that would fail before the fix and
pass after.

**Why this priority**: The punch list decides *what* to fix; this is what makes fixing it safe.
Without the blast-radius and test fields, a fix in a background-service codebase is a coin flip.

**Independent Test**: A second engineer picks any one finding record, reproduces the defect from the
trigger condition alone, applies the fix, and writes the named test — without asking the auditor a
question.

**Acceptance Scenarios**:

1. **Given** any finding, **When** its record is read, **Then** it contains all of: location, issue statement, severity, trigger condition, blast radius, proposed fix, and test to add.
2. **Given** a proposed fix touches a shared function, **When** the blast radius is stated, **Then** every caller and consumer of that function is enumerated, and the behavior at risk across process death, configuration change, and background/foreground transition is stated explicitly.
3. **Given** a finding could be fixed either minimally or by rewriting a component, **When** a rewrite is proposed, **Then** the record justifies why the minimal patch is insufficient.
4. **Given** a suspected defect cannot be confirmed from the code alone, **When** it is reported, **Then** it is labelled "needs verification" with the specific artifact, device condition, or measurement required to confirm it, and it is not asserted as fact.

---

### User Story 4 - Extended platform-condition coverage (Priority: P2)

The maintainer receives findings for the conditions that manual test plans routinely miss: cold
start after force-kill, system-initiated background kill, incoming call or screen lock mid-write,
Doze and App Standby, upgrade over a populated database, renamed preference keys, split-screen and
foldable posture changes, TalkBack running alongside the app's own accessibility service, aggressive
OEM battery managers, timezone and DST boundaries, and minSdk-29 behavior.

**Why this priority**: These are exactly the conditions under which Grayzone's long-lived services
fail, and they are the conditions least likely to be covered by the existing suite.

**Independent Test**: Each extended condition is enumerated with either a finding or an explicit
"reviewed, no defect found" / "not verifiable in this environment" outcome, so no condition is left
ambiguous.

**Acceptance Scenarios**:

1. **Given** the extended condition list, **When** the report is reviewed, **Then** every condition has a recorded outcome: finding, no-defect, or not-verifiable-with-reason.
2. **Given** the app upgrades from a prior version, **When** migration behavior is assessed, **Then** the assessment covers a populated database and previously-written preference keys, not a fresh install.
3. **Given** a background component is killed by the system or an OEM battery manager, **When** the behavior is assessed, **Then** the report states whether the app recovers, informs the user, or fails silently.

---

### User Story 5 - Dead code and unused resource inventory (Priority: P3)

The maintainer receives a list of unreachable code, unused symbols, unreferenced resources,
duplicated data-fetch paths, and orphaned observers, each with the evidence that it is unreferenced
and a statement of whether removal preserves behavior.

**Why this priority**: Required by Constitution Principle I and cheap to act on, but it does not
break users today, so it ranks below correctness work.

**Independent Test**: Each entry is checked by searching the repository for references; the claim
"unreferenced" either holds or does not.

**Acceptance Scenarios**:

1. **Given** an entry claims code is dead, **When** the evidence is checked, **Then** reference or call-graph evidence is cited rather than assumed.
2. **Given** a removal candidate is only referenced from a configuration or keep rule, **When** it is reported, **Then** the report distinguishes stale configuration from live usage.
3. **Given** a removal would change observable behavior, **When** it is reported, **Then** it is called out as a behavior change and not presented as cleanup.

---

### Edge Cases

- A scope area from the audit brief does not apply to this codebase (there is no HTTP client, so retry storms, unhandled response codes, and certificate pinning have no subject) — the area is recorded as not-applicable with that reason instead of being answered with invented findings.
- A defect is only reachable in the minified release build (for example, a keep rule naming a class that no longer exists) — it is reported separately from defects reproducible in the everyday build, because the everyday build in this project is non-minified.
- Two findings share one root cause — they are reported once at the root with the affected surfaces listed, so the punch list does not inflate.
- A finding's severity depends on a device behavior that cannot be observed in the available environment (OEM battery manager, foldable posture) — severity is stated as a bounded range with the verification step that would resolve it.
- The code under review was changed while the audit was in progress — the report records the commit or working-tree state each finding was observed against.
- An existing test asserts the buggy behavior — the finding states that the test must change and why, rather than treating the test as proof of correctness.

## Requirements *(mandatory)*

### Functional Requirements

**Scope and process**

- **FR-001**: The audit MUST publish the full review-unit inventory and the out-of-scope list, and MUST obtain maintainer confirmation, before analysis findings are produced.
- **FR-002**: The audit MUST assign every source package, resource directory, and build/configuration file in the repository to exactly one review unit or to the out-of-scope list.
- **FR-003**: The audit MUST record, for each review unit, an outcome of findings-reported, no-defect-found, not-applicable, not-verifiable, or deferred; silent omission is prohibited.
- **FR-004**: The audit MUST record the working-tree or commit state that findings were observed against, including the presence of uncommitted modifications.

**Findings and prioritization**

- **FR-005**: The audit MUST deliver a Critical-first punch list containing only Critical and High findings, ordered by severity, before the detailed breakdown.
- **FR-006**: The audit MUST apply a stated severity rubric in which a defect that silently disables protection, loses user data, or crashes the app is Critical; a defect that degrades protection or is user-visible but recoverable is High; a defect that affects correctness in narrow conditions is Medium; and a maintainability or cleanliness defect is Low.
- **FR-007**: Each finding MUST include location (file, class, and function), issue statement, severity, trigger condition, blast radius, proposed fix, and test to add.
- **FR-008**: Each trigger condition MUST be expressed as reproduction steps concrete enough to observe the defect without further clarification.
- **FR-009**: Findings sharing a single root cause MUST be reported once at the root cause, listing every affected surface.
- **FR-010**: The audit MUST NOT assert behavior it has not observed in the code or at runtime; any inference MUST be labelled "needs verification" together with the artifact or device condition required to confirm it.
- **FR-011**: Findings reproducible only in the minified/shrunk release build MUST be reported in a section separate from findings reproducible in the everyday build.

**Regression safety**

- **FR-012**: Before any fix is proposed, the audit MUST enumerate every caller and consumer of the function, class, or resource the fix touches.
- **FR-013**: Each proposed fix MUST state explicitly what could break, covering at minimum process death, configuration change, and background/foreground transition, plus cross-process state sharing where the affected code runs in the separate accessibility-service process.
- **FR-014**: Each proposed fix MUST be the minimal change that resolves the finding; a rewrite MUST carry a written justification of why a minimal patch is insufficient.
- **FR-015**: Each finding MUST name a specific test to add, identified as either a JVM unit test or an instrumented test, that fails against the current code and passes after the fix.
- **FR-016**: Where an existing test asserts the defective behavior, the finding MUST identify that test and state the required change.

**Coverage obligations**

- **FR-017**: The audit MUST cover each brief area — lifecycle correctness, threading and concurrency, Android-specific robustness, dead and unnecessary code, data-layer integrity, and security — and MUST record a not-applicable outcome with justification for any area without a subject in this codebase.
- **FR-018**: The audit MUST cover each extended condition — startup and process lifecycle, interruption handling, upgrade path over populated data, foldables and multi-window, concurrent accessibility services, OEM background-kill resilience, time and locale edge cases, and debug-versus-release divergence — with a finding, a no-defect outcome, or a not-verifiable outcome with reason.
- **FR-019**: The audit MUST assess the app's three long-lived components — the accessibility service, the foreground overlay service, and the VPN service — for notification correctness, recovery after being killed, and whether failure is surfaced to the user or silent.
- **FR-020**: The audit MUST evaluate compliance with each Constitution principle (dead code, strategy-pattern consistency, error handling without silent catches, no commented-out code, behavior-preserving removals, test-verified refactors) and report violations as findings.

**Deliverable mode and verification environment**

- **FR-021**: The audit MUST deliver findings first and MUST NOT modify the repository until the maintainer approves the punch list; once approved, the audit MUST apply the fix for every approved finding at all severities (Critical through Low), together with the test named in FR-015 for each.
- **FR-022**: Fix application MUST be sequenced by severity — Critical, then High, then Medium, then Low — with the existing test suite run and passing before proceeding to the next severity tier, so that a regression is attributable to a known, narrow batch of changes.
- **FR-023**: Approved findings that the maintainer declines MUST remain in the report as accepted-risk entries rather than being deleted, so the decision is recorded.
- **FR-024**: Confirmation of findings MUST rely on source analysis, the existing JVM unit test suite, and Gradle build outputs (including a minified `assembleRelease`); no physical device or emulator is available, so instrumented tests MUST be written and compiled but MUST NOT be reported as passing.
- **FR-025**: Any finding whose confirmation depends on runtime behavior that cannot be exercised without a device — process death, Doze, OEM battery-manager kills, foldable posture changes, TalkBack coexistence, rotation, and populated-database upgrade — MUST be labelled "needs verification" and MUST state the exact device procedure that would confirm it.
- **FR-026**: Fixes for needs-verification findings MUST be reported separately from fixes for statically-confirmed findings, so the maintainer knows which changes are still awaiting on-device proof.

### Key Entities

- **Review Unit**: A named, confirmable slice of the codebase (package, configuration group, or resource group) with an owner-visible outcome and associated risk themes.
- **Finding**: One defect record — location, issue, severity, trigger condition, blast radius, fix, test to add, and verification status (observed or needs verification).
- **Severity**: The Critical/High/Medium/Low classification defined by the rubric in FR-006, driving punch-list membership and ordering.
- **Blast Radius**: The enumerated set of callers, consumers, and behavioral guarantees at risk from a finding's fix, including the state transitions under which the risk materializes.
- **Verification Gap**: A recorded inability to confirm a suspicion, naming the artifact, device condition, or measurement required, so it can be resolved rather than silently accepted.
- **Coverage Outcome**: The per-area and per-condition record of findings-reported, no-defect-found, not-applicable, or not-verifiable, ensuring the brief is answered exhaustively.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The maintainer can approve or amend the review scope from the scope section alone, in under 5 minutes, before any analysis is delivered.
- **SC-002**: 100% of the repository's source packages, resource directories, and build/configuration files are accounted for in either a review unit or the out-of-scope list.
- **SC-003**: The maintainer can decide the first day's fix order by reading only the punch list, in under 10 minutes, without opening the detailed breakdown.
- **SC-004**: 100% of findings contain all seven required fields (location, issue, severity, trigger condition, blast radius, fix, test to add).
- **SC-005**: Every finding's trigger condition is specific enough to attempt without further clarification — verified at review time by confirming that 100% of trigger conditions name the starting state, the numbered actions, and the observation that reveals the defect. (First-attempt reproduction rate by a second engineer is the ultimate measure but is not evaluable in this environment; it is deferred to the device-verification backlog in `audit/pending-device.md`.)
- **SC-006**: 100% of proposed fixes enumerate their callers and consumers and state the behavior at risk across process death, configuration change, and background/foreground transition.
- **SC-007**: 100% of findings name a test that fails before the fix and passes after it.
- **SC-008**: 100% of the 6 brief areas and 8 extended conditions carry an explicit recorded outcome, with zero areas left unanswered.
- **SC-009**: Every claim not directly observed is labelled as needing verification, with zero unlabelled speculative assertions in the report.
- **SC-010**: Applying the approved fixes leaves the existing test suite passing, with zero pre-existing passing tests newly failing.
- **SC-011**: Findings reproducible only in the minified release build are separately identified, with zero such findings mixed into the everyday-build section.
- **SC-012**: After each severity tier of fixes is applied, the project compiles and the minified release build assembles successfully, and the existing test suite passes with zero newly failing tests.
- **SC-013**: Every applied fix is traceable to an approved finding, with zero repository changes that no finding in the report accounts for.

## Assumptions

The audit brief's context section was left as a placeholder, so the following was derived from the
repository and is treated as the audit's baseline. Corrections here change the audit, not the spec.

- **Platform baseline**: Single Gradle module `:app`, `minSdk` 29, `targetSdk` 34, `compileSdk` 34, Kotlin with Jetpack Compose (Compose BOM 2024.01.00), Java/Kotlin target 1.8.
- **Libraries in use**: Room 2.6.1 with KSP and exported schemas (versions 1 and 2 present), WorkManager 2.9.0, Gson 2.10.1, AndroidX Lifecycle 2.7.0, Activity Compose 1.8.2. Tests use JUnit 4, Mockito, and Espresso.
- **Libraries absent**: No dependency-injection framework (no Hilt or Dagger), no Retrofit/OkHttp or any HTTP client, no RxJava, no DataStore. Network-layer audit items therefore apply only to the VPN service's own socket and DNS handling, and to blocklist asset loading.
- **Architecture**: No `ViewModel` classes exist in the codebase; screen state appears to be held in Compose state and persisted through SharedPreferences helpers (`AppLockState`, `PrefsKeys`) plus Room. This makes process-death and configuration-change state survival a primary risk theme rather than a formality. *(Needs verification during the audit.)*
- **Process topology**: `GrayzoneAccessibilityService` runs in a separate `:a11y` process, so any state shared with the main process cannot be assumed to be in-memory-coherent.
- **Build divergence**: The `debug` build type is non-minified but also non-debuggable and signed with the release key when a keystore is present; `release` is minified with resource shrinking, and release lint (`lintVital`) is disabled. Debug-versus-release divergence is therefore about R8 and resource shrinking only, not about debuggability.
- **Known problem areas**: Absent a stated list, the audit treats the following as elevated-attention areas — the components carrying uncommitted modifications in the working tree (`GrayzoneApplication`, `GrayzoneAccessibilityService`, `OverlayService`, `AdBlockVpnService`, `BlocklistManager`, `DnsTrafficBus`, `accessibility_service_config.xml`, `build.gradle.kts`, `AndroidManifest.xml`) and the subject matter of the prior fix feature `specs/002-fix-timeout-accessibility-bugs`.
- **Anti-tamper storage**: The app intentionally writes state to shared storage (`/sdcard/.grayzone/`) via all-files access to survive data clearing. This is treated as intended design; the audit assesses its failure handling and security exposure, not its existence.
- **Governance**: `.specify/memory/constitution.md` v1.0.0 is binding, so its six principles are audit criteria and not merely style preferences.
- **Verification environment** (confirmed by the maintainer): No emulator or physical device is available. Gradle is available, so compilation, the JVM unit suite, and a minified `assembleRelease` are the confirmation tools. Instrumented tests will be authored and compiled but cannot be executed, and every runtime-dependent claim carries a needs-verification label with its device procedure.
- **Fix authority** (confirmed by the maintainer): Fixes are applied for all approved findings at every severity, but only after the punch list is approved, and applied in severity order with the test suite run between tiers.
- **Adversarial stance**: The audit is expected to look for defects rather than confirm correctness; "no issues found" for a review unit requires stated evidence of what was checked.

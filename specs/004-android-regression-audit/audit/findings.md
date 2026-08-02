# Finding Register

**Feature**: `004-android-regression-audit` | **Created**: 2026-07-31 (T014)
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md)

Index and ID authority for the audit. Individual findings live in the per-unit files below; this file
owns the `F-###` sequence so parallel analysis tasks cannot collide.

## ID allocation

`F-###`, assigned in discovery order. To avoid collisions while T018–T030 run in parallel, each task
draws from a reserved block and records what it used:

| Task | Subject | Reserved block | Output file | Used |
|---|---|---|---|---|
| T018 | RU-04 `service` | F-001 … F-019 | [`findings-ru04.md`](./findings-ru04.md) | F-001 … F-016 |
| T019 | RU-06 `service.vpn` | F-020 … F-039 | [`findings-ru06.md`](./findings-ru06.md) | F-020 … F-038 |
| T020 | RU-02 `data` | F-040 … F-059 | [`findings-ru02.md`](./findings-ru02.md) | F-040 … F-055 = 2 Critical, 5 High, 5 Medium, 4 Low. F-056–F-059 unused — **but see the residual-coverage gap below; four files in `data/` were never examined** |
| T021 | RU-01 root package | F-060 … F-079 | [`findings-ru01.md`](./findings-ru01.md) | F-060 … F-079 (block fully consumed; **F-062 withdrawn** → F-206) |
| T022 | RU-01 permissions & setup | F-080 … F-094 | [`findings-ru01-permissions.md`](./findings-ru01-permissions.md) | F-080 … F-094 (block fully consumed) |
| T023 | RU-05 `service.overlay` | F-095 … F-109 | [`findings-ru05.md`](./findings-ru05.md) | F-095 … F-106 (F-107–F-109 unused) |
| T024 | RU-03 `policy` | F-110 … F-124 | [`findings-ru03.md`](./findings-ru03.md) | F-110 … F-119 |
| T025 | RU-08 build / manifest | F-125 … F-144 | [`findings-ru08.md`](./findings-ru08.md) | F-125 … F-142 (0 Critical, 2 High, 6 Medium, 10 Low) |
| T025a | RU-08 repository-root config (amendment 1) | F-250 … F-264 | [`findings-ru08-root.md`](./findings-ru08-root.md) | F-250, F-251, F-252 |
| T025b | Docs accuracy drift (amendment 2) | F-265 … F-269 | [`findings-docs.md`](./findings-docs.md) | F-265, F-266, F-267 |
| T026 | RU-07 UI state | F-145 … F-159 | [`findings-ru07.md`](./findings-ru07.md) | F-145 … F-159 (block fully consumed; 0 Critical, 3 High, 12 Medium, 0 Low. **F-145 duplicates F-232** — one must be withdrawn, see F-145's *Affected surfaces*) |
| T027 | RU-07 configuration variants | F-160 … F-174 | [`findings-ru07-config.md`](./findings-ru07-config.md) | F-160 … F-174 (block fully consumed; 0 Critical, 2 High, 8 Medium, 5 Low. F-174 narrowed at filing time to avoid overlapping F-231) |
| T028 | RU-09 resources | F-175 … F-189 | [`findings-ru09.md`](./findings-ru09.md) | F-175 … F-183 (F-184 … F-189 unused, returned to pool) |
| T029 | RU-10 existing tests | F-190 … F-204 | [`findings-ru10.md`](./findings-ru10.md) | F-190 … F-204 (block fully consumed) |
| T030 | Principle III sweep (all units) | F-205 … F-249 | [`findings-principle3.md`](./findings-principle3.md) | F-205 … F-220 (**F-207 withdrawn** → F-060; **F-214 withdrawn** → F-031) |
| T047 | Extended condition E1 (startup & process lifecycle) | *(consolidation — no new IDs)* | [`conditions/E1.md`](./conditions/E1.md) | Cites F-060/F-043/F-044/F-066/F-206/F-078/F-010; no new IDs |
| T048 | Extended condition E2 (interruption) | F-221 … F-229 *(carved from T030's unused tail)* | [`conditions/E2.md`](./conditions/E2.md) | F-221, F-222 |
| T049 | Extended condition E3 (upgrade path) | *(consolidation — no new IDs)* | [`conditions/E3.md`](./conditions/E3.md) | Cites F-068/F-060/F-043/F-041; MigrationTest confirmed populated |
| T050 | Extended condition E4 (foldables / multi-window) | F-230 … F-232 *(carved from T030's unused tail)* | [`conditions/E4.md`](./conditions/E4.md) | F-230, F-231, F-232 |
| T051 | Extended condition E5 (accessibility conflicts) | F-233 … F-239 *(carved from T030's unused tail)* | [`conditions/E5.md`](./conditions/E5.md) | F-233 … F-236 |
| T052 | Extended condition E6 (OEM background-kill) | *(consolidation — no new IDs)* | [`conditions/E6.md`](./conditions/E6.md) | Cites F-061/F-010; app fails silently |
| T053 | Extended condition E7 (time/locale) | F-240 *(from free block)* | [`conditions/E7.md`](./conditions/E7.md) | F-240 Critical (TZ date-key lockout clear); preserves T024 F-110/F-111 |
| T054 | Extended condition E8 (debug vs release) | *(consolidation — no new IDs)* | [`conditions/E8.md`](./conditions/E8.md) | Cites F-064/F-074/F-125/F-126/F-130 as one release-observability cluster |
| T031 | Severity reconcile (all units) | F-241 *(from free block — was orphaned C3)* | [`findings-ru02.md`](./findings-ru02.md) §F-241 | Filed missing C3 as F-241 Critical; see Sweep log |

> **Allocation note.** Condition tasks drew from T030's unused tail and the free block F-240 … F-249.
> **F-242 … F-249 remain free.** All eight condition files under `audit/conditions/` now exist.

## Sweep log

| Task | Date | Files touched | Findings affected | Skipped / notes |
|---|---|---|---|---|
| T031 | 2026-08-02 | All `findings-*.md`, `conditions/E4.md`, `conditions/E7.md`, this file | **Severity confirmed Critical** against FR-006: F-001, F-002, F-020, F-043, F-049, F-060, F-080, F-110, F-111, F-205, F-230 (pending device), F-240, **F-241 (new — was orphaned C3)**. **Pair severity resolves (fold is T033):** F-060/F-207→Critical (done); F-062/F-206→High (done); F-148/F-215→High (F-215 withdrawn); F-145/F-232→Medium (F-232 withdrawn); F-005/F-114→High (F-114 withdrawn); F-002/F-205 same Critical (both `startForeground` — F-205 broader, fold T033). **No Critical downgraded.** F-241 filed because C3 had no F-### and prior cross-refs wrongly named F-205. | Docs/ru08-root Lows not re-rated (no Critical/High conflict). A4 dead-code severities deferred to T059–T064. |
| T033 | 2026-08-02 | `findings-ru04.md`, `findings-ru03.md`, `findings-principle3.md`, `conditions/E4.md`, this file | **Folds applied:** F-002→F-205; F-114→F-005; F-215→F-148; F-232→F-145; (prior) F-207→F-060, F-062→F-206, F-214→F-031. Survivors: F-205, F-005, F-148, F-145, F-060, F-206, F-031. | C7+C10 kept as two Critical IDs with one-fix note (F-043+F-060). |
| T032 | 2026-08-02 | Punch-list Critical/High set | Every Critical/High row in `punch-list.md` carries `confirmed` or `needs-verification`. | Full Medium/Low relabel sweep **partial** — many records already have Verification fields from filing; remaining Medium/Low gaps deferred to T045 consolidation rather than blocking the punch list. |
| T036–T041 | 2026-08-02 | All `findings-*.md` | Mechanical field audit: 204 active-style records already carry Review unit / Location / Issue / Trigger / Blast / Fix / Test / Verification / Disposition from Phase 4 filing. Docs/root N/A blast axes + 8 missing `*Procedure*` stubs filled. No parked Medium/Low stubs found. | F-148 code refuse-overwrite landed in same close-out (was overclaimed earlier). |
| T037–T039 | 2026-08-02 | RU-01…RU-10 + principle3 + docs + ru08-root | Blast-radius callers + risk axes confirmed present (sampled + axis scan). Thin docs/build-config axes marked n/a. | — |
| T042 | 2026-08-02 | `findings.md` Named tests index + per-record Test to add | Index generated from every active finding record (~200 rows). | Kind column is heuristic from record text; see owning record for authoritative wording. |
| T043 | 2026-08-02 | All `findings-*.md` | **35 findings** already record a non-`none` `Existing test conflict` (incl. F-005, F-050, F-064, F-083, F-086, F-091, F-095–F-097, F-110–F-112, F-115, F-117, F-219, …). Conflicts live in owning records; not re-copied here line-by-line. | Grep `Existing test conflict` ≠ none for full list. |
| T044 | 2026-08-02 | [`release-only.md`](./release-only.md) | Release-only / release-observability cluster split out (11 IDs). | — |
| T045 | 2026-08-02 | [`verification-gaps.md`](./verification-gaps.md) | Consolidated needs-verification / fix-pending-device rows (~100). | Cross-ref [`pending-device.md`](./pending-device.md). |
| T046 | 2026-08-02 | `validation-log.md` V3–V5 | Sampled 5 findings for seven mandatory fields; blast axes; named tests — **pass**. Preconditions: T032 + T036–T043 Sweep log rows present. | — |
| T065–T070 | 2026-08-02 | Punch list, Critical findings, `app/` (Tier-1 fixes), tests | **Gate approved** ("let's go"). Tier-1 Critical fixes applied: F-043/F-060 schema+guard; `MonotonicDeadline` → F-110/F-111/F-049/F-240; F-241/F-148 JSON refuse-overwrite; F-205 `startForeground`; F-001 `addView`; F-020 `protect`; F-080 notification honesty + a11y health wire; F-230 UsageStats visibility guard. Dispositions: 7 `verified`, 5 `fix-pending-device-verification`. Gates: `testDebugUnitTest` 317/0, `assembleRelease`, `assembleDebugAndroidTest` green (JDK 21). | Phase 5 was open at gate; closed under T036–T046 rows above. |
| T071–T080 | 2026-08-02 | Punch list Highs, Mediums, Low/dead-code, `app/` + tests | Tier-2/3/4 fixes applied; JVM + `assembleRelease` gates green after each tier. Dead-code removals + `GrayzoneLoggerTest` rename; `warnings-post-fix.md` written (Theme unused `context` gone). | — |
| T081–T087 | 2026-08-02 | All `findings-*.md`, punch-list, validation-log, report artifacts | Final dispositions swept; Phase 9 artifacts assembled; V1/V7–V10 recorded. | On-device list remains intentionally open. |

## Named tests index (T042)

| Finding | Severity | Test name | Kind | Target source / test file |
|---|---|---|---|---|
| F-001 | Critical | `androidTest` | jvm-unit | `app/src/androidTest/java/com/grayzone/app/service/OverlayAddViewFailureTest.kt` |
| F-003 | High | `SessionExpiryLateAlarmTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionExpiryLateAlarmTest.kt` |
| F-004 | High | `androidTest` | jvm-unit | `app/src/androidTest/java/com/grayzone/app/service/OverlayTaskRemovedRestartTest.kt` |
| F-005 | High | `DismissOverlayContractTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/DismissOverlayContractTest.kt` |
| F-006 | High | `SessionExpiryStateReadTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/SessionExpiryStateReadTest.kt` |
| F-007 | Medium | `DailyBudgetAccountingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/DailyBudgetAccountingTest.kt` |
| F-008 | Medium | `ProcessTopologyContractTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/ProcessTopologyContractTest.kt` |
| F-009 | Medium | `WatchdogActionContractTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/WatchdogActionContractTest.kt` |
| F-010 | Medium | `BootRestoreDecisionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` |
| F-011 | Medium | `BootRestoreDecisionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` |
| F-012 | Medium | `BootRestoreDecisionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` |
| F-013 | Low | `SessionExpiryRequestKeyTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/SessionExpiryRequestKeyTest.kt` |
| F-014 | Low | `(see record)` | jvm-unit | `—` |
| F-015 | Low | `BootRestoreDecisionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/BootRestoreDecisionTest.kt` |
| F-016 | Low | `CleanupAndLoggingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` |
| F-020 | Critical | `DnsResolverPoolTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` |
| F-021 | High | `VpnHoldLockTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt` |
| F-022 | High | `VpnLifecycleGuardTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnLifecycleGuardTest.kt` |
| F-023 | High | `VpnLifecycleGuardTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnLifecycleGuardTest.kt` |
| F-024 | High | `VpnTeardownLoggingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnTeardownLoggingTest.kt` |
| F-025 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnRevokeTest.kt` |
| F-026 | Medium | `BlocklistManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` |
| F-027 | Medium | `BloomFilterTest` | jvm-unit | `app/src/test/java/com/grayzone/app/BloomFilterTest.kt` |
| F-028 | Medium | `VpnLoopErrorPolicyTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnLoopErrorPolicyTest.kt` |
| F-029 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnThreadTeardownTest.kt` |
| F-030 | Medium | `BlocklistManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` |
| F-031 | Medium | `DnsResolverPoolTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` |
| F-032 | Medium | `DnsTrafficBusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsTrafficBusTest.kt` |
| F-033 | Medium | `VpnBypassListTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnBypassListTest.kt` |
| F-034 | Medium | `DnsPacketTest` | jvm-unit | `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` |
| F-035 | Medium | `VpnHoldExpirySchedulerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldExpirySchedulerTest.kt` |
| F-036 | Medium | `VpnStartFailureMessageTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnStartFailureMessageTest.kt` |
| F-037 | Medium | `DnsPacketTest` | jvm-unit | `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` |
| F-038 | Low | `DnsDeduplicationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsDeduplicationTest.kt` |
| F-040 | High | `UsageDatabaseInstanceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/UsageDatabaseInstanceTest.kt` |
| F-041 | High | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/data/DowngradeTest.kt` |
| F-042 | Low | `SingleProcessRoomInvariantTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/SingleProcessRoomInvariantTest.kt` |
| F-043 | Critical | `StateBackupRestoreTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupRestoreTest.kt` |
| F-044 | High | `StateBackupPayloadBoundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupPayloadBoundTest.kt` |
| F-045 | High | `StateBackupRollbackTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupRollbackTest.kt` |
| F-046 | High | `StateBackupKeyDerivationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupKeyDerivationTest.kt` |
| F-047 | Medium | `StateBackupObservabilityTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupObservabilityTest.kt` |
| F-048 | Medium | `CleanupWorkerCutoffTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/CleanupWorkerCutoffTest.kt` |
| F-049 | Critical | `ScheduleManagerBreakClockTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ScheduleManagerBreakClockTest.kt` |
| F-050 | Medium | `PerAppLimitsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/PerAppLimitsTest.kt` |
| F-051 | Low | `DailyBudgetTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/DailyBudgetTest.kt` |
| F-052 | Medium | `(see record)` | jvm-unit | `—` |
| F-053 | Low | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/data/QueryPlanTest.kt` |
| F-054 | Low | `StatisticsSourceConsistencyTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StatisticsSourceConsistencyTest.kt` |
| F-055 | Medium | `ScheduleRuleDeserializationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ScheduleRuleDeserializationTest.kt` |
| F-061 | High | `OemBatteryHelperTest` | jvm-unit | `app/src/test/java/com/grayzone/app/OemBatteryHelperTest.kt` |
| F-063 | Medium | `BackupDirtyFlagTest` | jvm-unit | `app/src/test/java/com/grayzone/app/BackupDirtyFlagTest.kt` |
| F-064 | Medium | `CleanupAndLoggingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` |
| F-065 | Medium | `LoggerRedactionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/LoggerRedactionTest.kt` |
| F-066 | Medium | `VpnReconciliationIdempotencyTest` | jvm-unit | `app/src/test/java/com/grayzone/app/VpnReconciliationIdempotencyTest.kt` |
| F-067 | Medium | `AppMetadataCacheTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AppMetadataCacheTest.kt` |
| F-068 | Low | `PrefsMigrationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/PrefsMigrationTest.kt` |
| F-069 | Low | `DateUtilsTest` | none | `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` |
| F-070 | Low | `PrivateDnsDetectionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/PrivateDnsDetectionTest.kt` |
| F-071 | Low | `AppMetadataCacheTest` | other | `app/src/test/java/com/grayzone/app/AppMetadataCacheTest.kt` |
| F-072 | Low | `(none)` | none | `—` |
| F-073 | Low | `SetupRequirementsTest` | none | `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` |
| F-074 | Low | `(none)` | none | `—` |
| F-075 | Low | `(none)` | none | `—` |
| F-076 | Low | `androidTest` | jvm-unit | `app/src/androidTest/java/com/grayzone/app/MainScreenStateRestorationTest.kt` |
| F-077 | Low | `(none)` | none | `—` |
| F-078 | Low | `PrefsProcessGuardTest` | jvm-unit | `app/src/test/java/com/grayzone/app/PrefsProcessGuardTest.kt` |
| F-079 | Low | `AppLockStateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AppLockStateTest.kt` |
| F-080 | Critical | `ProtectionNotificationStateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ProtectionNotificationStateTest.kt` |
| F-081 | High | `SettingsIntentTargetTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SettingsIntentTargetTest.kt` |
| F-082 | High | `OverlayPermissionRecoveryTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/OverlayPermissionRecoveryTest.kt` |
| F-083 | Medium | `ProtectionStatusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` |
| F-084 | Medium | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ManifestPermissionsTest.kt` |
| F-085 | Low | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ManifestPermissionsTest.kt` |
| F-086 | High | `SetupRequirementsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` |
| F-087 | Medium | `HomeStatusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/HomeStatusTest.kt` |
| F-088 | High | `AccessibilityPromptStateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt` |
| F-089 | High | `VpnConsentBannerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/VpnConsentBannerTest.kt` |
| F-090 | High | `StateBackupKeyFilterTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupKeyFilterTest.kt` |
| F-091 | High | `SetupRequirementsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` |
| F-092 | High | `RestrictedSettingsHintTest` | jvm-unit | `app/src/test/java/com/grayzone/app/RestrictedSettingsHintTest.kt` |
| F-093 | Medium | `SetupGateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SetupGateTest.kt` |
| F-094 | Low | `AccessibilityPromptStateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AccessibilityPromptStateTest.kt` |
| F-095 | High | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-096 | High | `LockPresentationForegroundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/LockPresentationForegroundTest.kt` |
| F-097 | High | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-098 | Medium | `OverlayViewRegistryTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/OverlayViewRegistryTest.kt` |
| F-099 | Medium | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-100 | Medium | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-101 | Medium | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-102 | Medium | `LockCountdownDeadlineTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/LockCountdownDeadlineTest.kt` |
| F-103 | Medium | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-104 | Low | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-105 | Low | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-106 | Low | `ScheduleEndDecisionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ScheduleEndDecisionTest.kt` |
| F-110 | Critical | `SessionClockTamperTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionClockTamperTest.kt` |
| F-111 | Critical | `SessionClockTamperTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionClockTamperTest.kt` |
| F-112 | Medium | `SessionPolicyEngineForegroundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` |
| F-113 | Medium | `OverlayPresentationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt` |
| F-115 | Low | `PerAppLimitsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/PerAppLimitsTest.kt` |
| F-116 | Low | `SessionPolicyEngineForegroundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` |
| F-117 | Low | `(none)` | none | `—` |
| F-118 | Low | `SessionPolicyEngineBackgroundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineBackgroundTest.kt` |
| F-119 | Low | `DailyBudgetTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/DailyBudgetTest.kt` |
| F-125 | High | `ProguardRulesTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` |
| F-126 | Medium | `ProguardRulesTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` |
| F-127 | Low | `ProguardRulesTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` |
| F-128 | Low | `ProguardRulesTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` |
| F-129 | Medium | `DnsResolverPoolTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` |
| F-130 | Medium | `BuildConfigurationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/BuildConfigurationTest.kt` |
| F-131 | High | `StateBackupPermissionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupPermissionTest.kt` |
| F-132 | Low | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` |
| F-133 | Low | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` |
| F-134 | Low | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` |
| F-135 | Low | `BuildConfigurationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/BuildConfigurationTest.kt` |
| F-136 | Medium | `ManifestExportSurfaceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt` |
| F-137 | Low | `ManifestExportSurfaceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt` |
| F-138 | Low | `ManifestExportSurfaceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt` |
| F-139 | Low | `BackupRulesTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/BackupRulesTest.kt` |
| F-140 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/AccessibilityConfigTest.kt` |
| F-141 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/AccessibilityConfigTest.kt` |
| F-142 | Low | `ManifestPermissionsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` |
| F-145 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/StateRestorationTest.kt` |
| F-146 | Medium | `ConfigValidatorTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ConfigValidatorTest.kt` |
| F-147 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/SettingsResumeReReadTest.kt` |
| F-148 | High | `PromptResolutionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/PromptResolutionTest.kt` |
| F-149 | Medium | `StateBackupAllowlistTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupAllowlistTest.kt` |
| F-150 | High | `LockGuardLogicTest` | jvm-unit | `app/src/test/java/com/grayzone/app/LockGuardLogicTest.kt` |
| F-151 | Medium | `SessionPolicyEngineForegroundTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` |
| F-152 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/ScheduleFormRestorationTest.kt` |
| F-153 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/TimePickerLifecycleTest.kt` |
| F-154 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/PerAppSheetRestorationTest.kt` |
| F-155 | High | `ProtectionStatusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` |
| F-156 | Medium | `ScheduleManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ScheduleManagerTest.kt` |
| F-157 | Medium | `LockGuardLogicTest` | jvm-unit | `app/src/test/java/com/grayzone/app/LockGuardLogicTest.kt` |
| F-158 | Medium | `DnsTrafficBusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsTrafficBusTest.kt` |
| F-159 | Medium | `SessionBootstrapTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SessionBootstrapTest.kt` |
| F-160 | High | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/OnboardingReachabilityTest.kt` |
| F-161 | High | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/AppsListReachabilityTest.kt` |
| F-162 | Medium | `NightResourceParityTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/theme/NightResourceParityTest.kt` |
| F-163 | Low | `ResolveDarkThemeTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/theme/ResolveDarkThemeTest.kt` |
| F-164 | Medium | `PaletteSelectionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/theme/PaletteSelectionTest.kt` |
| F-165 | Low | `PluralFormattingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/PluralFormattingTest.kt` |
| F-166 | Low | `TimeRangeFormattingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/TimeRangeFormattingTest.kt` |
| F-167 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/NavBarFontScaleTest.kt` |
| F-168 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/FontScaleClippingTest.kt` |
| F-169 | Medium | `IconSizingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/IconSizingTest.kt` |
| F-170 | Medium | `AppListSortingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/AppListSortingTest.kt` |
| F-171 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/LandscapeReachabilityTest.kt` |
| F-172 | Low | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/HeaderInsetTest.kt` |
| F-173 | Medium | `TypographyScaleTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/theme/TypographyScaleTest.kt` |
| F-174 | Low | `OverlayChromeParityTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/theme/OverlayChromeParityTest.kt` |
| F-175 | Low | `StringResourceCoverageTest` | jvm-unit | `app/src/test/java/com/grayzone/app/resources/StringResourceCoverageTest.kt` |
| F-176 | Low | `NotificationLayoutContractTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/NotificationLayoutContractTest.kt` |
| F-177 | High | `androidTest` | jvm-unit | `app/src/androidTest/java/com/grayzone/app/service/CountdownNotificationLayoutTest.kt` |
| F-178 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/NotificationColorTest.kt` |
| F-179 | Medium | `GrayzoneBloomFilterMappedTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/GrayzoneBloomFilterMappedTest.kt` |
| F-180 | Low | `BlocklistAssetPackagingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/BlocklistAssetPackagingTest.kt` |
| F-181 | Low | `BloomFilterSizingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/BloomFilterSizingTest.kt` |
| F-182 | Low | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/ui/ThemeNightModeTest.kt` |
| F-183 | Low | `LauncherIconResourceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/resources/LauncherIconResourceTest.kt` |
| F-190 | High | `SessionStatePredicateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionStatePredicateTest.kt` |
| F-191 | High | `SessionPolicyEngineTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt` |
| F-192 | Medium | `SessionPolicyEngineTest` | jvm-unit | `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt` |
| F-193 | Low | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` |
| F-194 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` |
| F-195 | Low | `IntegrationTest` | jvm-unit | `app/src/test/java/com/grayzone/app/IntegrationTest.kt` |
| F-196 | Medium | `CleanupAndLoggingTest` | jvm-unit | `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` |
| F-197 | Medium | `ProtectionStatusTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` |
| F-198 | Medium | `DnsResolverPoolTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` |
| F-199 | Low | `SetupRequirementsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` |
| F-200 | High | `StateBackupManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StateBackupManagerTest.kt` |
| F-201 | High | `OverlayAttachmentStateTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/OverlayAttachmentStateTest.kt` |
| F-202 | High | `SessionAlarmInvariantTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/SessionAlarmInvariantTest.kt` |
| F-203 | Medium | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/vpn/BlocklistLoadTest.kt` |
| F-204 | Medium | `VpnHoldLockStoreTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockStoreTest.kt` |
| F-205 | Critical | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/ForegroundStartFailureTest.kt` |
| F-206 | High | `AppScopeHandlerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AppScopeHandlerTest.kt` |
| F-208 | High | `androidTest` | jvm-unit | `app/src/androidTest/java/com/grayzone/app/VpnRestoreFailureTest.kt` |
| F-209 | High | `AllFilesPromptOnceTest` | jvm-unit | `app/src/test/java/com/grayzone/app/AllFilesPromptOnceTest.kt` |
| F-210 | High | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/VpnConsentLaunchFailureTest.kt` |
| F-211 | High | `StartSettingsIntentSafelyTest` | jvm-unit | `app/src/test/java/com/grayzone/app/ui/StartSettingsIntentSafelyTest.kt` |
| F-212 | High | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/BootRestoreFailureTest.kt` |
| F-213 | High | `StreakEvaluatorGuardTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StreakEvaluatorGuardTest.kt` |
| F-215 | Medium | `PromptResolutionTest` | jvm-unit | `app/src/test/java/com/grayzone/app/PromptResolutionTest.kt` |
| F-216 | Medium | `DefaultMonitoredAppsTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/DefaultMonitoredAppsTest.kt` |
| F-217 | Medium | `BlocklistManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/service/vpn/BlocklistManagerTest.kt` |
| F-218 | Low | `androidTest` | instrumented | `app/src/androidTest/java/com/grayzone/app/service/vpn/BypassListTest.kt` |
| F-219 | Low | `StreakManagerTest` | jvm-unit | `app/src/test/java/com/grayzone/app/data/StreakManagerTest.kt` |
| F-220 | Low | `DnsPacketTest` | jvm-unit | `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` |
| F-241 | Critical | `ScheduleManagerTest` | jvm-unit | `—` |
| F-250 | Medium | `(none)` | none | `—` |
| F-251 | Low | `(none)` | none | `—` |
| F-252 | Low | `(none)` | none | `—` |
| F-265 | Medium | `(none)` | none | `—` |
| F-266 | Low | `(none)` | none | `—` |
| F-267 | Low | `(none)` | none | `—` |
| F-268 | Medium | `(see record)` | other | `—` |

_Generated 2026-08-02 from finding records. Active rows: 198. Withdrawns omitted._

## Derived views

| View | File | Populated by |
|---|---|---|
| Critical-first punch list | [`punch-list.md`](./punch-list.md) | T034 |
| Release-only findings | [`release-only.md`](./release-only.md) | T044 |
| Verification gaps | [`verification-gaps.md`](./verification-gaps.md) | T045 |
| Dead-code inventory | [`dead-code-inventory.md`](./dead-code-inventory.md) | T064 |
| Coverage matrix | [`coverage-matrix.md`](./coverage-matrix.md) | T013, T055–T057 |
| Pending device verification | [`pending-device.md`](./pending-device.md) | T083 |
| Constitution summary | [`constitution-summary.md`](./constitution-summary.md) | T084 |
| Final report | [`report.md`](./report.md) | T082 |

## Required field template

Every finding MUST carry all of these. Copy this block verbatim; a missing mandatory field is a
contract violation, not a stylistic choice (SC-004 requires 100% compliance).

```markdown
### F-### — <one-line issue>  [<Critical|High|Medium|Low>]

- **Review unit**: RU-##
- **Location**: `<file path>` → `<Class.function()>` (lines N–M)
- **Issue**: <mechanism AND user-visible consequence>
- **Trigger condition**:
  1. <numbered, observable steps>
- **Blast radius**:
  - *Callers*: <enumerated by name, from evidence/reference-index.md>
  - *Process death*: <what changes>
  - *Config change*: <what changes>
  - *Foreground/background*: <what changes>
  - *Cross-process*: <required if reachable from :a11y>
  - *Persistence*: <required if it writes Room / prefs / shared storage>
  - *Release-only*: <required if R8 or shrinking could alter the fix>
- **Fix** (minimal): <smallest change resolving the root cause>
- **Rewrite justification**: <only if the fix replaces rather than patches>
- **Test to add**: `<jvm-unit|instrumented>` — `<path>`, asserting <behavior that fails before / passes after>
- **Existing test conflict**: <test name + required change, or `none`>
- **Verification**: `<confirmed|needs-verification>` (evidence tier `<T1|T2|T4>`)
  - *Missing*: <required artifact or device condition>
  - *Procedure*: <exact device steps>
  - *Severity effect*: <how severity changes per outcome>
- **Build scope**: `<everyday-build|release-only>`
- **Constitution**: `<I–VI, or none>`
- **Affected surfaces**: <other locations sharing this root cause>
- **Observed against**: `working-tree @ 557a963 (uncommitted: <files>)` | `557a963`
- **Disposition**: `reported`
```

## Severity rubric (FR-006)

| Severity | Definition | Punch list | Fix tier |
|---|---|---|---|
| Critical | Silently disables protection, loses user data, or crashes the app | Yes | 1 |
| High | Degrades protection, or user-visible but recoverable | Yes | 2 |
| Medium | Incorrect behavior in narrow conditions | No | 3 |
| Low | Maintainability, dead code, or cleanliness | No | 4 |

**Grayzone calibration**: "silently" is load-bearing. A crash is Critical — but so is a failure with
no crash and no user notification. Protection stopping without telling the user is this app's worst
outcome and must not rank below a visible error.

## Verified Critical candidates — each re-read and confirmed by the lead auditor

Originally seeded by the Principle III sweep (T011); now the running register of every Critical
regardless of source task — C1–C4 from the sweep, C5–C6 from T024 (policy), C7 from T021 (root).

These drive the punch list, so every one was independently re-read rather than accepted. Severity
reflects the FR-006 rubric's Grayzone calibration: protection failing with **no crash and no user
notification** ranks at or above a visible error. **Ranking note for T034**: C7 outranks the rest — it
is the only one the user cannot recover from, and unlike the clock-tamper pair (C5/C6) it does not
require the user to be adversarial.

| # | Location | Verified mechanism | Severity | Owner |
|---|---|---|---|---|
| C1 | `OverlayService.kt:989–994` | `windowManager?.addView(root, wlp)` wrapped in `catch (e: Exception) { Log.e(TAG, "addView failed: ${e.message}"); return }`. On failure **no overlay is created at all** and the function returns — during a lockout the monitored app stays fully usable. `overlayView` is assigned only on the success path (`:991`), so nothing downstream knows. The log carries neither the throwable, nor the package, nor the overlay mode, so it cannot be diagnosed. `addView` realistically throws `BadTokenException`/`SecurityException` when `SYSTEM_ALERT_WINDOW` is revoked mid-session — the exact scenario T022 covers. | **Critical** | T018, T023 |
| C2 | `AdBlockVpnService.kt:119` | `try { protect(socket) } catch (e: Exception) { /* ignore */ }` — a comment-only catch, explicitly prohibited by Principle III. `protect()` excludes the resolver socket from our own tunnel; if it fails, the app's DNS queries route back through the tunnel it is serving, while the notification and VPN screen both still claim filtering is active. **Additional defect not in the sweep**: `protect()` also *returns `Boolean`* and the return value is discarded, so a non-throwing failure is invisible too. Both the exception path and the false-return path must be handled. | **Critical** | T019 |
| C3 | `ScheduleManager.kt:33–43` | `catch (_: Exception) { emptyList() }` — the `_` discards the exception by construction. Malformed `SCHEDULE_RULES_JSON` silently becomes an empty rule list, so bedtime and work-hours locks stop firing with no signal. **The data-loss half is worse**: `addRule` (`:45–47`) does `getScheduleRules().toMutableList()`, appends, then `saveScheduleRules`, so the next UI edit **persists the empty list over the user's real rules** — silent protection loss followed by irreversible data loss. **F-### was missing until T031** (cross-refs wrongly pointed at F-205, which is `startForeground`); now **F-241**. | **Critical** | T031 (F-241); discovered T020/T024 |
| **C5** | `policy/SessionModels.kt:31` — `fun isLockedOut(now: Long): Boolean = now < lockedUntil` | **Moving the system clock forward ends an active lockout instantly.** A pure wall-clock comparison against a persisted epoch value. Advance the device clock past `lockedUntil` and the lockout gate falls through to the wait screen, granting a fresh session — no crash, no log, no user signal. `LockPresentation.initialSeconds` renders `0m 0s` and dismisses. **No root or adb required**: Settings → Date & time → disable automatic → set time. For a self-control app this is the bypass, not an edge case. **Fix is blocked by a green test that asserts this exact outcome is correct** — `SessionPolicyEngineTest.kt:191` simulates a +30-day jump and requires the session to be granted and `lockedUntil` re-anchored to the tampered clock (F-191). The fix commit must invert that assertion, and must also delete or rewrite the false-coverage test at `:156` whose name claims clock-skew coverage while its body tests a fresh launch (F-192). | **Critical** | T024 (F-110), tests T029 |
| **C6** | `policy/SessionModels.kt:30` — `fun hasActiveSession(now: Long): Boolean = now < activeUntil` | **Moving the clock backward revives every expired session at once, and traps honest users.** Sessions that expired inside the rewound window test active again, and because the active-session gate deliberately precedes the lockout gate, the engine short-circuits to `DismissOverlay` — every affected app becomes freely usable simultaneously. The session also never expires while rewound, since the `RTC_WAKEUP` alarm moves with the clock. The inverse harm hits an honest user: `lockedUntil` also shifts, so an NTP correction of a fast clock extends a lockout for hours beyond what the app promised. | **Critical** | T024 (F-111) |
| **C7** | `GrayzoneApplication.kt:64` then `:70` — `restoreIfWiped()` then `validateConfiguration()` | **The only finding in this audit that is unrecoverable by the user, which makes it the most severe.** `onCreate` restores the external backup first — deliberately, per the comment at `:61–63`, so locks survive a data wipe — then reads those values with unguarded typed getters. A single preference whose *type* differs from what `getInt`/`getBoolean` expects throws `ClassCastException` **inside `Application.onCreate`**, so the app cannot launch. Clearing app data does not help: the backup lives in `/sdcard/.grayzone/` precisely to survive that, so the next launch restores the same poison. The app is bricked until someone deletes an external file they don't know exists. **The trigger needs no attacker** — `applyInto` writes arbitrary keys and types with no allowlist, and `SCHEMA_VERSION` is written but never read, so an ordinary version-to-version type change is sufficient. Chains F-060 + F-043 + F-068. Found twice independently (T021 as F-060 Critical, T030 as F-207 High), which is corroboration; F-207 is withdrawn into F-060 and contributed the second instance at `resetRuntimeStateIfNeeded()` → `RuntimeSessionReset.kt:29–43` that the fix must also cover. | **Critical** | T021 (F-060, absorbs F-207) |
| **C8** *(pending lead re-read — filed by T022, not yet independently confirmed)* | `OverlayService.kt:1189–1196` + the three `ON_RESUME`-bound permission reads | **Every permission check in the app is bound to an Activity resuming; every enforcement path runs in a service.** `isAccessibilityServiceEnabled()` has exactly three call sites (`MainActivity.kt:190`, `HomeScreen.kt:42,52`, `OnboardingScreen.kt:36,44`), all `ON_RESUME`. `OverlayService` never reads it, and `buildNotification()` returns two hardcoded strings — "Grayzone is active" / "Monitoring app usage to help you stay focused." — that are never re-evaluated. When accessibility is revoked while the app is backgrounded, `ACTION_APP_CHANGED` stops arriving, **no friction, lock, session expiry or budget fires again**, and the persistent notification keeps asserting protection is on. The only trace is `onUnbind`'s `w()` (`GrayzoneAccessibilityService.kt:124–130`) — logcat only, and in the `:a11y` process no user surface reads. | **Critical** | T022 (F-080) |
| **C9** *(pending lead confirmation)* | `OverlayService.kt:327–349` — `processForegroundAppChanged` | **Grayzone has no concept of two visible apps, and multi-window is fully reachable** (targetSdk 34, no `resizeableActivity`, no `configChanges`). A single `lastForegroundPackage`, fed only by `TYPE_WINDOW_STATE_CHANGED`, means focusing the *second* split-screen pane is read as "the monitored app left": `backgroundApp()` runs at `:348`, the session is paused, `active_until`/`locked_until` are cleared and the expiry alarm is cancelled — while the monitored app keeps rendering and accepting input in its own pane. No friction, lock or budget screen can fire for it again until it is re-focused, and the notification still claims protection is on. Statically confirmed as far as the code allows: `:330` returns only on an *identical* package, so any different launchable package triggers the background path. **Held pending** because whether the second pane fires the event while the first stays resumed decides Critical vs High, and that needs a device. Minimal fix uses the already-granted `PACKAGE_USAGE_STATS` to confirm the app actually paused, so the accessibility service's narrow scope need not widen. | **Critical** *(if confirmed)* | T050 (F-230) |
| **C10** | `data/StateBackupManager.kt` → `applyInto` (`:235–243`), `SCHEMA_VERSION` written at `:202` | **The upstream cause of C7, and Critical in its own right.** The restore writes **arbitrary preference keys with arbitrary file-supplied types, with no allowlist and no schema gate** — `SCHEMA_VERSION` is written but never read. That is what lets a poisoned or merely type-drifted backup reach `validateConfiguration()` and brick the app unrecoverably. Filed by T020 at Critical; C7 chains it. **One fix serves both** — gate the restore — so T034 must not schedule C7 and C10 as separate work, and T033 should record them as one root cause with two surfaces. | **Critical** | T020 (F-043) |
| **C11** | `data/` focus mode and daily break — pure wall-clock deadlines | **Clock-tamper surfaces for focus/break**, joining C5/F-110, C6/F-111, F-021 and now C12/F-240. Changing the device date grants unearned focus-mode and daily-break time exactly as it does sessions and lockouts. Filed Critical by T020. **Fix strategy**: at six independent surfaces a per-site patch is the wrong shape; `VpnHoldLock` is the model to copy. T041 should require one shared helper rather than six diffs. | **Critical** | T020 (F-049) |
| **C12** | `RuntimeSessionReset.resetIfNeeded` + `DateUtils.shouldResetDailyRuntimeState` | **Timezone jump that advances the local date string clears every lockout and refreshes every daily budget** without moving `currentTimeMillis`. `OverlayService.kt:338` calls the reset on every foreground-app change, so no process restart is required. The policy unit's "timezone unaffected" verdict (epoch-millis deadlines) still holds; this is the deferred date-key half T020 never filed. Filed by T053 as F-240. Same shared monotonic/dual-clock helper as C5/C6/C11. | **Critical** | T053 (F-240) |
| C4 | `AdBlockVpnService.kt:286–288` | `vpnInterface?.close()` in a comment-only `catch (e: Exception) { /* ignored */ }`. Confirmed Principle III violation. **Severity corrected from Critical to High**: the leaked-descriptor → failed-`establish()` → misleading "Another VPN may be active" chain is plausible but its trigger (a failing `close()`) is rare, and the misleading-message path is not yet confirmed. Raising it as Critical without that link would overstate it. | **High** (Critical if the `establish` path is confirmed) | T019 |

**The fix for C5 and C6 already exists in this repository.** `service/vpn/VpnHoldLock.kt:9` documents
the dual-clock defense verbatim — *"Remaining time prefers `State.startedElapsedMs` so wall-clock
changes cannot skip the wait"* — with a dedicated prefs key (`PrefsKeys.kt:85`, "elapsedRealtime when
the current VPN hold started") and a reboot fallback. Verified directly: `SystemClock.elapsedRealtime`
appears in `VpnHoldLock`, `VpnHoldLockStore`, `VpnHoldExpiryScheduler`, `AdBlockVpnService`,
`BootReceiver`, `VpnScreen`, and `MainActivity` — the entire VPN subsystem is clock-tamper hardened.
`policy/SessionModels.kt` uses wall-clock comparisons only.

So the app hardened its **secondary** feature (the 24-hour VPN hold) against clock tampering and left
the friction/lockout core — its entire reason for existing — exposed. One pattern, already written and
already documented in-tree, applied twice. Also verified: there is **no** `ACTION_TIME_CHANGED` or
`ACTION_TIMEZONE_CHANGED` receiver anywhere in `app/` (zero matches).

A third symptom confirms the divergence and is independently verifiable: the countdown notification
anchors to `SystemClock.elapsedRealtime()` (`OverlayService.kt:701,721`), so it reaches zero on real
time while the policy still believes the session is live.

Sweep totals for context: 75 catch blocks across 22 files (46 of 68 files contain none), 224 I/O
boundaries of which **84 have no failure handling**, and **21 Principle III violations** — 11 `SILENT`,
8 `returns-default-silently`, 2 `logged-bare` too thin to diagnose. Zero `rethrown`. Full detail and 14
credited positive examples in [`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md).

Highest-value unprotected boundaries: `startForeground` in **both** service entry points
(`OverlayService.kt:144`, `AdBlockVpnService.kt:189`) — a throw there means protection never starts, and
for the VPN every launch and boot retries the same crash; `GrayzoneApplication.kt:36`, the one scope
with no `CoroutineExceptionHandler`; and `validateConfiguration()` (`GrayzoneApplication.kt:176–215`),
where the function meant to repair corrupt config reads prefs unguarded on the startup critical path
and can be killed by the very corruption it exists to fix.

## RESIDUAL COVERAGE GAP — RU-02, four files never examined (open)

**T020 is marked complete because all five of its required questions are answered, but the unit is not
complete at file level and that must not be hidden by the checkmark.** The crashed run's numbering plan
reserved F-058 and F-059 for four files, and those records were never written:

| File | Status | Consequence |
|---|---|---|
| `StreakManager.kt` | **not examined** | — |
| `StreakEvaluator.kt` | **not examined** | see unowned defect 1 |
| `ProtectionHealthRepository.kt` | **not examined** | see unowned defect 2 |
| `ProtectionStatus.kt` | **not examined** | — |

The file's own `Scope examined` table and four in-text forward references still point at F-058/F-059, so
the document currently promises records that do not exist. **Two defects are consequently unowned:**

1. **`StreakEvaluator.run` is launched unguarded from `HomeScreen.kt:56–58`** — confirmed during the gap
   check: `rememberCoroutineScope()`, no `try`/`catch`, no handler. This is load-bearing, because
   **F-041's High rating leans on it**. It also overlaps F-213, which filed the same call site from the
   Principle III angle; T033 must decide whether they are one finding.
2. **`ProtectionHealthRepository` is the natural home for F-047's health signal**, and with the file
   unexamined that fix has nowhere to land.

**This gap belongs to T056** (the coverage-completion task) and must be closed before the audit can claim
SC-008 completeness. It is recorded here rather than left in a subagent report so it survives.

## Corrections to `findings-ru02.md` for T031/T032 (do not action now)

Three defects in the record itself, all found by the gap check and none affecting a severity:

- **F-041's heading says High** (per its revision note) **while its `Severity effect` field still reads
  "Medium as filed."** The heading is authoritative; the field is stale wording. T031 owns this.
- **F-054's heading says "eight of eleven"** `UsageDao` methods have no caller; **its own table lists
  nine**, and nine of eleven read queries is the accurate figure. It *understates*, so Low is unaffected.
- **The `Scope examined` table's `Outcome` column is off by one** from `UsageDao.kt` downward. The filed
  headings are authoritative.

## Lead-auditor verification notes on T020 (RU-02 data layer) — partial run

**The agent terminated on resource exhaustion, but the deliverable survived intact** because it was
appending incrementally: 164.9 KB, 1,963 lines, findings **F-040 … F-055 all complete**, with the last
record ending cleanly on its `Disposition` field rather than mid-write. Only the wrap-up was lost.
This is the second time incremental writing has saved a run, and it should be standard instruction for
every remaining analysis task.

**F-040 CONFIRMED — textbook broken double-checked locking.**

```34:47:app/src/main/java/com/grayzone/app/data/UsageDatabase.kt
        fun getInstance(context: Context): UsageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    ...
                .build()
                INSTANCE = instance
                instance
            }
        }
```

There is **no re-check of `INSTANCE` inside the `synchronized` block**. Two threads that both observe a
null `INSTANCE` both enter; the first builds and assigns, and the second — already past the outer check
and now holding the lock — builds a **second** Room instance over the same database file and overwrites
the field. Callers that captured the first instance keep using it, so the app can hold two `RoomDatabase`
objects with separate connection pools and separate invalidation trackers against one file. The correct
idiom needs `INSTANCE ?: build().also { INSTANCE = it }` inside the lock.

**F-049 makes clock tampering a five-surface theme, and that changes the fix's shape.** The cross-unit
note previously listed three surfaces (C5/F-110 forward, C6/F-111 backward, F-021 the VPN hold). F-049
adds **focus mode** and **the daily break**, both pure wall-clock deadlines. Five independent surfaces
means a per-site patch is the wrong strategy: the fix should be a shared monotonic-deadline helper, and
`VpnHoldLock` already implements exactly that pattern correctly and is the model to copy. T034 should
group all five and T041 should require the shared helper rather than five separate diffs.

**Coverage looks complete despite the crash**, which is why this is a gap-check rather than a rerun. All
five required areas appear answered: the migration and downgrade path (F-041), main-thread I/O on the
data path (F-044), `CleanupWorker` retention (F-048), indices (F-053), and shared-storage backup failure
handling (F-043, F-045, F-046, F-047). **F-043 honoured the pin**, so C7's cross-reference holds.

## Lead-auditor verification notes on T048 / T050 / T051 (conditions E2, E4, E5)

**F-222 CONFIRMED — a guard meant to filter the incoming package also suppresses handling of the
outgoing one.** `processForegroundAppChanged` opens with

```329:330:app/src/main/java/com/grayzone/app/service/OverlayService.kt
            if (currentPkg == packageName || !hasLauncherIntent(currentPkg)) return
            if (currentPkg == lastForegroundPackage) return
```

and the pause of the previous app is 19 lines later at `:348` (`backgroundApp(oldPackage, prefs, now)`).
So when the incoming package has no launcher intent — `com.android.server.telecom`, Samsung's
`incallui` — the function returns before the monitored app is ever paused. The session keeps burning
through the call, the daily budget with it, and if the session expires mid-call the monitored app is
still recorded as foreground, so the lockout is drawn over the live call. The filter is not wrong to
ignore non-app system UI; it is wrong to do so *before* processing the app that just lost the foreground.

**F-230's mechanism CONFIRMED to the limit static reading allows, and it is the same code.** `:330`
returns only when the package is *identical*, so any different **launchable** package — which the second
split-screen pane is — falls straight through to `backgroundApp()` at `:348`. The single
`lastForegroundPackage` field genuinely cannot represent two visible apps. What remains device-dependent
is only whether focusing pane two emits the event while pane one stays resumed, which is exactly the
question T050 left open and the reason C9 is recorded as pending rather than confirmed.

**These two findings share one root cause and must be fixed together.** F-222 and F-230 are both
`processForegroundAppChanged` treating "a different window got focus" as "the monitored app is gone".
T033 should fold them under one root cause with two affected surfaces rather than counting two fixes, and
whatever replaces the single-package model has to satisfy both.

**F-221 raises the value of an existing lead.** There is no `ACTION_SCREEN_ON`/`ACTION_USER_PRESENT`
counterpart to the screen-off pause, so resumption is delegated to an accessibility event the app never
confirms arrived. T048's narrowing of F-003 is the useful part: screen-off normally cancels the session
alarm before Doze can start, so **F-003's session-side deferral is reachable mainly *because* of F-221**.
Two findings previously read as independent are actually a chain, and fixing F-221 shrinks F-003.

**One likely duplicate flagged in advance.** F-232 (`rememberSaveable` appears zero times) overlaps
F-076 (`selectedTab` lost on rotation) and will very likely collide with whatever T026 files on the same
axis. T050 already offered it for withdrawal. **Hold F-232 for T033** rather than actioning it — the UI
unit is the natural owner and is still running.

**Credit worth keeping.** E5's coexistence axis came out genuinely clean, and one positive is notable
because it is the exact bug this kind of review usually finds: `AccessibilityServiceIds.isServiceEnabled()`
already parses the multi-service setting properly rather than by substring match. Recording that prevents
a future reviewer from "fixing" correct code — and it is the same function T029 proved live at
`AppUtils.kt:114` after the reference index wrongly marked it test-only.

## Lead-auditor verification notes on T025 (RU-08 build / manifest)

**F-125 CONFIRMED from R8's own output.** The keep block at `proguard-rules.pro:5–14` carries the
`TypeAdapter`, `TypeAdapterFactory`, `JsonSerializer` and `JsonDeserializer` rules and **omits
`-keep class * extends com.google.gson.reflect.TypeToken`** — while its own comment at `:6` states the
block exists to "Prevent stripping of generic type information needed by TypeToken reflection". The
intent was there; the one rule that implements it is not. `-keepattributes Signature` preserves generic
signatures but does not stop the anonymous subclass itself from being shrunk.

`usage.txt` — R8's record of what it **removed** from the release build — names four of the app's five
TypeToken anonymous subclasses explicitly:

```
com.grayzone.app.data.StreakManager$getAchievements$existing$type$1
com.grayzone.app.data.StreakManager$unlockAchievement$existing$type$1
com.grayzone.app.service.OverlayService$showOverlay$customPrompts$type$1
com.grayzone.app.ui.CustomPromptsSheetKt$CustomPromptsSheet$initialPrompts$1$type$1
```

The fifth, `ScheduleManager`'s, is absent from that list. **F-126 is confirmed the same way**: grepping
the *merged* `configuration.txt` for `SourceFile`, `LineNumberTable` and `TypeToken` returns only the two
comment lines, proving `proguard-android-optimize.txt` supplies none of them.

**Correction — the explanation for the survivor is wrong, and it invalidates a protective claim.** T025
reasoned that the survivor lived because its type argument has its own keep rule. That cannot be right:
`Achievement` has a keep rule at `proguard-rules.pro:18` exactly as `ScheduleRule` does at `:17`, and
**both** of `StreakManager`'s TypeTokens were removed anyway. Keeping the type argument does not keep the
anonymous `TypeToken` subclass. The consequence is that T025's instruction to spare the
`ScheduleRule`/`Achievement` rules from F-128's deletion **rests on a disproven mechanism**. Those rules
may still be justified for field retention, but not for this reason, and F-128 must re-derive it.

**Severity: the restraint is defensible, but the ceiling must be recorded.** T025 held F-125 at High
because the runtime link is unproven, following the C4 precedent, and that is the right call on today's
evidence — the classes are provably gone, but whether the call site then throws, yields an empty
collection, or was rewritten by R8 cannot be settled without running the release build. What must not be
lost is the outcome if it *is* read-fails-into-empty: `unlockAchievement` reads the existing achievements
and **writes back what it read**, so a failed read silently destroys every unlocked achievement — the
identical read-empty-then-overwrite shape that makes C3 Critical, except this one is **release-only**.
Under the rubric's "loses user data" clause that is Critical, not High.

**This is now the audit's highest-value device check.** It is release-only, silent, destroys user data if
confirmed, and one `./gradlew installRelease` plus an achievement round-trip settles it. T083 should rank
it first in the pending-device queue, and T044 should carry it in the release-only table.

## Lead-auditor verification notes on T028 (RU-09 resources)

**The load-bearing claim is CONFIRMED against the shipped release APK.** Both blocklists are packaged
**STORED**, so `Resources.openRawResourceFd()` — which fails only on *compressed* resources — will
succeed, and the zero-heap memory-mapped path is available today with no change to the assets, the
generator, or the build:

| APK entry | Uncompressed | In-APK | Method |
|---|---|---|---|
| `res/gY.bin` (adblock) | 6,291,476 | 6,291,476 | **STORED** |
| `res/Kb.bin` (adult) | 4,194,324 | 4,194,324 | **STORED** |

Total 10,485,800 bytes = **76.1% of the 13,778,794-byte release APK**, matching T028's figure exactly.
On-disk sizes reconcile too: 6,291,476 + 4,194,324 against 10,485,760 bytes of bit array plus two 20-byte
headers.

**One refinement to the stated mechanism, which strengthens rather than weakens F-180.** T028 attributed
the STORED packaging to deflate falling back because a well-populated Bloom filter is incompressible.
That is probably not the cause: **every** entry in this APK is STORED, including `classes.dex`,
`resources.arsc` and the PNGs, which points to packaging configuration rather than a per-file fallback.
The operative conclusion is unaffected — the entries are STORED, so mmap works. But the *reason* matters
for F-180, because I also confirmed there is **no `noCompress`, `aaptOptions` or `androidResources` block
anywhere in `app/build.gradle.kts`**. So the property the loader would depend on is not merely
undeclared, it is inherited from AGP defaults that a future upgrade or a packaging-option change could
silently flip. F-180's recommendation to declare it explicitly should be treated as a **precondition of
F-179's mmap fix**, not a separate cleanup — adopting mmap without pinning `noCompress` makes a silent
future breakage possible where today there is only silent waste.

**This materially narrows F-067, and T034 should know.** F-067's open question was whether the process is
genuinely reclaimed under memory pressure given both services hold foreground notifications. For these
10 MiB that question stops applying under the mmap fix: mapped file pages are clean and reclaimable by
the kernel regardless of the process's `oom_adj` bucket. The corollary is that there would be nothing
left for an `onTrimMemory` hook to release here — so F-179 is not just cheaper than F-067's implied fix,
it partly **removes the need for it**. F-027 is likewise unfixable by shrinking the assets, since the ads
filter measures a 46.18% fill against the ~50% optimum for `k = 10`; only the holding strategy can change.

**F-266 is resolved by measurement, and the resolution inverts.** Population-counting both filters gives
**≈4,669,553 entries**, so the README's "4.6 million" is accurate to within 1.5% and the
"~800k ads + ~160k adult" sentence is the wrong one, off by roughly 3.9× and 9.7×. F-266 was filed as a
bare discrepancy without saying which side was wrong; it can now be closed by correcting that second
sentence only. **Do not "fix" the 4.6M figure.**

**Two self-corrections T028 made are accepted**, and both were the right call: a test *does* read a
`res/raw` asset (`AbusedTldListTest.kt:86–99`, now cited as the precedent for the proposed packaging
test), and the relevant test file is `BloomFilterTest.kt`, not the `GrayzoneBloomFilterTest` it first
named — which is why F-179's fix must be additive rather than a substitution.

**One item referred to RU-06 (T019, closed) and therefore now unowned**: `BlocklistManager.kt:217`
justifies the abused-TLD ordering as "cheaper than ten SHA-256 probes", but `baseHashes()` computes
**one** digest and derives both hashes from it. The ordering decision remains correct; only its stated
reason is false. This joins F-107/F-108 in the queue of confirmed defects whose owning unit has closed.

## Lead-auditor verification notes on T023 (RU-05 overlay)

**Both forwarded orphans confirmed. They are now owned — see F-107 and F-108** — because their natural
owners (T018/RU-04, T024/RU-03) are already closed and unowned defects silently disappear.

**Orphan 1 — `showOverlay` returns before it dismisses (now F-107).** Confirmed exactly as reported:

```821:825:app/src/main/java/com/grayzone/app/service/OverlayService.kt
                return
            }
        }
        
        dismissOverlay()
```

The revoked-permission branch returns at `:821`; the only `dismissOverlay()` on this path is at `:825`
and is never reached. So when overlay permission is revoked mid-session, any **already-attached** overlay
stays on screen with its countdown still running. This compounds C1/F-001 rather than duplicating it —
C1 is about the new overlay never appearing, F-107 is about the old one never leaving.

**Orphan 2 — the wait duration is derived twice and the copies have already diverged (now F-108), and it
is a regression trap rather than a live bug.** The chain:

- `SessionPolicyEngine.kt:10` declares `const val DEFAULT_WAIT_SECONDS = 5`, a **second copy** of
  `ConfigValidator.kt:11`'s constant of the same name and value.
- `SessionPolicyEngine.kt:101` always emits `ShowWaitScreen(…, DEFAULT_WAIT_SECONDS)` — the user's
  configured wait is never consulted, so this field is wrong for every user who changed the setting.
- `OverlayService.kt:491–493` executes that command as
  `showOverlay(cmd.packageName, cmd.appName, FRICTION, 0L)` — **`cmd.waitSeconds` is discarded.**
- `OverlayService.kt:843` then independently re-derives the correct value:
  `waitSeconds = if (hasCustom) customWait else globalWait`.

Today the wrong value is harmless because it is thrown away. The danger is the shape: a command field
being silently discarded looks exactly like a bug, so the natural "fix" is to make the executor honour
`cmd.waitSeconds` — which would instantly pin every user to 5 seconds and break both the global and
per-app wait settings. **Any fix here must delete the engine-side value, not wire it up.** This is
precisely the class of trap the audit's regression-safety mandate exists to catch, and it must be flagged
for whoever touches `SessionCommand`.

**A correction to a Critical that must not reach the punch list.** F-001/C1 examined the `removeView`
catch and credited it as intended house style. F-098 shows `overlayView = null` sits **outside** the
`try`, so a throwing `removeView` leaves a window attached that nothing can ever remove. The endorsement
in F-001 is withdrawn; T034 must not carry it forward.

**Reference-index error count reconciled: four, not three.** T023 and T029 each reported "a third"
independently, and they found different ones. The confirmed list is now `onRevoke` (listed, does not
exist), `DnsTrafficBus.clear` (marked test-only, has a live caller), `AccessibilityServiceIds.isServiceEnabled`
(marked test-only, called from `AppUtils.kt:114`), and `CountdownFinishContext.{packageName, appName}`
(listed as read by the presentations, read by nothing). Note the fourth errs in the *safe* direction —
claiming live where dead — but four errors across two directions means the index is unreliable as a
class, not patchy in one spot.

**Credit where the unit earned it.** Presentation selection is genuinely centralised in `resolve()` with
zero mode conditionals outside it, and the KDoc's claim to have removed the `isLocked`/`isBudgetLock`/
`isScheduleLock` branching is accurate. The finding (F-101) is that the duplication *moved* rather than
vanished: `showsProgress` now answers three different questions at three sites. The copies have not
diverged in value yet, only in meaning — which is the moment to fix it, before they do.

## Lead-auditor verification notes on T029 (RU-10 tests)

**F-191 CONFIRMED, and it explains why C5 exists and survived review.** The test is real, green, and
asserts the Critical bypass is correct behaviour:

```191:202:app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt
    fun `resuming a paused session after the clock jumps forward grants full remaining`() {
        // Simulate the wall clock moving far into the future while a paused session
        // still had 5s remaining. The resume must anchor to the new `now`, not grant
        // a stale/negative window.
        val future = now + 30L * 24 * 60 * 60 * 1000L // +30 days
        val state = defaultState.copy(sessionRemainingMs = 5000L)
        val commands = engine.evaluate(AppEvent.AppForegrounded(pkg), state, future, appName)

        assertEquals(2, commands.size)
        val update = commands[0] as SessionCommand.UpdateState
        assertEquals(future + 5000L, update.newActiveUntil)
        assertEquals(future + 5000L + state.defaultLockoutMins * 60 * 1000L, update.newLockedUntil)
```

A +30-day wall-clock jump is asserted to grant the session **and** re-anchor `lockedUntil` to the
tampered clock. That is C5/F-110 exactly. The comment shows how it happened: the author was defending
against a *different* failure — a stale or negative window — and the fix for that hazard encodes the
tamper outcome as the expected result. This is not a careless test; it is a correct fix to the wrong
threat model, which is precisely why it passed review.

**F-192 CONFIRMED, and it is stronger than its title.** The test named
`when session paused but clock skew detected, clear it and show wait screen` (`:156`) contains no clock
skew and no paused session — it evaluates `defaultState` and asserts a fresh-launch wait screen. The
lines above it are the author reasoning in real time (`:157–167`), and they arrive at
*"So the clock skew block in engine isn't strictly needed…"* before concluding *"Let's test the default
behavior: fresh app launch."* Line `:160` asks the exact question this audit filed two Criticals for —
*"what if the user manually changes the clock to the future?"* — and then talks itself out of it.

**Taken together these two tests are the origin story for C5 and C6**, and T034 should say so. The
vulnerability is not an oversight: the clock-tamper axis was considered, reasoned about in a comment,
dismissed, and then a green test was written asserting the bypass is the intended outcome. That also
raises the cost of the fix beyond what F-110/F-111 estimate — the fix must invert an existing assertion
and delete a false-coverage test, not merely add new ones.

**Two premises I supplied were wrong, and T029 was right to correct them.** Both corrections stand:

1. **The `DnsResolverPoolTest` / F-031 mechanism in Reconciliation 2 was wrong.** Those two tests do not
   reach any of the three F-031 catch bodies — one returns a healthy socket without closing it, the other
   calls `shutdown()` with an empty `idleSockets`, so the loop never iterates. The *conclusion* (ship the
   `testOptions` change with F-031) survives, and the real mechanism is broader: `DnsResolverPool` already
   logs unconditionally at `:71` and `:96`, so any test driving a failure path dies on
   `Method w in android.util.Log not mocked`. T029 appended the correction under Reconciliation 2.
2. **`MigrationTest` is not run over an empty database** — line 31 inserts a populated `usage_events` row
   before migrating. The real gap is narrower and sharper: the row is never read back afterward, so a
   data-destroying migration would leave the test green.

**A third reference-index error, which strengthens Reconciliation 3.** T029 re-verified four TEST-ONLY
claims directly; three held, but `AccessibilityServiceIds.isServiceEnabled` is **live**, called from
`AppUtils.kt:114`. The index has now been caught wrong three times (`onRevoke`, `DnsTrafficBus.clear`,
and this), which converts the standing warning from a precaution into a rule: T059–T064 must treat every
index row as unverified until re-checked by direct search.

## Lead-auditor verification notes on T022 (RU-01 permissions)

**F-081 CONFIRMED — the sharpest single-line defect found so far, and the codebase convicts itself.**
`showOverlay` declares a parameter that shadows the inherited `Context` property:

```787:787:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    private fun showOverlay(packageName: String, appName: String, mode: Int, lockedUntil: Long) {
```

so the recovery deep link 22 lines later resolves `$packageName` to the **monitored app**, not to Grayzone:

```808:809:app/src/main/java/com/grayzone/app/service/OverlayService.kt
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
```

What makes this unarguable is that **every other site in the app writes it correctly** —
`AppUtils.kt:135` builds the identical `ACTION_MANAGE_OVERLAY_PERMISSION` intent as
`"package:${context.packageName}"`, and `HomeScreen.kt:173`, `MainActivity.kt:97` and
`OnboardingScreen.kt:156,210` all qualify it too. `OverlayService.kt:809` is the lone unqualified
occurrence in `app/src/main`, and it is unqualified only because a parameter happens to share the name.
The user who lost overlay permission is sent to Instagram's overlay settings page.

**One speculation of mine checked and dismissed, so nobody "fixes" working code.** `OemBatteryHelper.kt:156`
also interpolates a bare `$packageName` and looked like the same bug. It is not: `openBatterySettings`
binds `val packageName = context.packageName` at `:83`, so the reference is a correct local. The
shadowing defect is unique to `OverlayService`. This matters because `OemBatteryHelper` is the unwired
mitigation in F-061 — when it is wired up, this line needs no change.

**F-084 and F-085 CONFIRMED.** `PACKAGE_USAGE_STATS` appears exactly once in `app/src/main`, in
`AndroidManifest.xml:24` behind a `tools:ignore="ProtectedPermissions"` suppression. There is **no
`UsageStatsManager` or `AppOpsManager` reference anywhere in `app/src`** — the only other mentions are two
comments (`AndroidManifest.xml:76`, `GrayzoneAccessibilityService.kt:20`) stating that the accessibility
service *replaced* usage-stats polling. The permission is a leftover from the superseded design, and the
lint suppression is what let it survive. F-085 is if anything understated: `OnboardingScreen.kt:36`
initialises `hasUsageAccess` from `isAccessibilityServiceEnabled(context)`, and that misnamed variable then
drives **eleven** call sites through the whole onboarding flow, so the wrong name propagates across the
entire screen rather than sitting on one line.

**On F-080 (Critical) and the C8 row.** T022 added itself to the Critical table marked *pending lead
re-read*, which is the correct and honest thing to do rather than claiming confirmation it had not earned.
The structural claim — every permission check fires on Activity resume while every enforcement path runs
in a service, so the ongoing notification asserts "Grayzone is active" against state nothing re-reads —
is consistent with everything else the audit has established, including F-066 (the only reconciliation is
a startup `LaunchedEffect(Unit)` that never re-runs) and F-064 (the `i()` traces that would have recorded
these transitions are no-ops in every variant). **C8 must stay marked pending until the notification's
text path is re-read end to end**; it is a broad claim and breadth is exactly where over-claiming happens.

**A stale entry T022 caught in our own evidence.** `prior-fix-watchlist.md` asserts that
`BuildConfig.DEBUG` remains `true` so logging is unaffected. That is now known false from generated
output (F-064). T022 used the corrected fact, which is what upgrades F-088's failure from *logged only via
`i()`* to *no trace at all*. The watchlist entry should be corrected at T059 so no later task inherits it.

## Lead-auditor verification notes on T021 (RU-01)

**F-060 is the audit's most severe finding, and its chain is confirmed by the code's own comments.**
`GrayzoneApplication.onCreate` runs in this exact order:

```64:70:app/src/main/java/com/grayzone/app/GrayzoneApplication.kt
        StateBackupManager.restoreIfWiped(this)
        // ...
        validateConfiguration()
```

The comment at `:61–63` states the intent explicitly — *"MUST run first: if the user cleared data/cache
to escape their locks, pull the state back from the external backup BEFORE anything else reads
preferences (config validation below, …)"*. So a poisoned backup is restored first, then
`validateConfiguration()` reads those values with unguarded typed getters and throws in
`Application.onCreate`.

**Why this is worse than a crash: clearing app data cannot recover it.** The backup lives in
`/sdcard/.grayzone/` specifically to survive a data wipe — that is its entire purpose — so the next
launch restores the same poison and crashes again. The app is unlaunchable until someone manually
deletes an external file most users don't know exists.

**And the trigger is not adversarial.** It chains three findings previously treated as separate:
`applyInto` writes arbitrary keys with arbitrary types from the JSON (no allowlist), `SCHEMA_VERSION` is
written but never read (no schema gate), and `validateConfiguration` reads with typed getters. A single
preference whose *type* changed between app versions makes `prefs.getInt(key)` throw
`ClassCastException`. The prefs inventory already found a real rename of exactly this shape —
`grayscale_enabled` (Boolean) → `theme_mode` (String) — which escaped only because the *name* changed
too. An ordinary upgrade is sufficient to produce an unrecoverable boot loop. → T034 must rank this at
or above C5/C6 in the punch list.

**F-064 CONFIRMED from generated artifacts — upgraded from `needs-verification` to `confirmed` (T1).**
T021 could not run Gradle to check this. The generated files were already present from this session's
`--rerun-tasks` compile, and they settle it:

| File | `DEBUG` | `BUILD_TYPE` |
|---|---|---|
| `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java:7,9` | **`false`** | `"debug"` |
| `app/build/generated/source/buildConfig/release/com/grayzone/app/BuildConfig.java:7,9` | `false` | `"release"` |

AGP derives `BuildConfig.DEBUG` from the build type's `debuggable` flag, **not** from its name. The
uncommitted `debug { isDebuggable = false }` block therefore makes `DEBUG` false in *both* variants, so
**every `GrayzoneLogger.d()` and `.i()` call in the app is a no-op in every variant the project
builds** — including the only observability into the anti-tamper backup. This is a silent regression
from the uncommitted change, whose stated intent was signing and installability parity, not logging. No
device needed; the maintainer-check note in F-064 can be replaced with this evidence.

**RU-01's dead-code claims re-verified by direct search, per the reference-index accuracy warning.**
Because the index was already caught twice (`onRevoke`, `DnsTrafficBus.clear`) and a false dead-code
claim gets live code deleted, every RU-01 candidate was re-checked against `app/src` directly. All hold:

| Symbol | Direct-search result | Verdict |
|---|---|---|
| `AppUtils.isPrivateDnsActive` (`:154`) | definition only | dead ✓ |
| `AppUtils.refreshInstalledApps` (`:72`) | definition only | dead ✓ |
| `DateUtils.formatTimestamp` (`:93`) | definition only | dead ✓ |
| `DateUtils.getTodayStartMillis` (`:129`) | definition only | dead ✓ |
| `SetupRequirements.actionLabel` (`:45`) | `SetupRequirementsTest.kt:37,45` only | test-only ✓ |
| `Extensions.millisUntilMidnight` (`:76`) | definition only | dead ✓ |
| `DateUtils.millisUntilMidnight` (`:100`) | **live** — `OverlayService.kt:247` | keep ✓ |

The last two rows are why F-069 needed care and got it: the two functions share a name, and only the
`Extensions.kt` copy is unreferenced. A name-based sweep would have deleted the wrong one.

**One wording risk to fix before T034, or F-079 will be actioned as a deletion.** Its title says
`AppLockState` "has no reader," but the type is constructed and read at four live sites —
`AppsScreen.kt:190,201` and `LimitsScreen.kt:70,93`. The real claim is that it exposes no *accessor* for
"is this app locked," so three call sites re-derive that predicate inline and two have diverged on its
meaning. That is a Principle II duplication finding, not dead code. **`AppLockState` must not be
deleted**; the fix is to add the missing accessor and route the three sites through it.

**Combined effect worth stating in the report.** Three findings interact to gut field diagnosability:
`d()`/`i()` are dead everywhere (F-064), `w()`/`e()` are ungated and do leak monitored package names and
queried domains in release (F-065), and `-keepattributes SourceFile,LineNumberTable` is absent so
release traces carry no line numbers (propagated to T025). The app logs the sensitive material and
almost none of the diagnostic material, with no line numbers when it does.

## Lead-auditor verification notes on T030 (Principle III sweep)

T030 filed F-205 … F-220 (1 Critical, 8 High, 4 Medium, 3 Low) in
[`findings-principle3.md`](./findings-principle3.md). Two were re-read directly.

**F-205 confirmed, and the realistic trigger is stronger than filed.** `OverlayService.kt:144` calls
`startForeground(NOTIF_ID, buildNotification())` in `onCreate` with no guard, and
`AdBlockVpnService.kt:189` does the same. The VPN site carries a thoughtful comment explaining why it
must be called *first* (the ~5 s foreground-service contract, since `establish()` can throw) — the
ordering is deliberate and correct; only the missing guard is the defect.

What makes it Critical is a trigger the finding should name: at `targetSdk 34`, `startForeground`
throws `ForegroundServiceStartNotAllowedException` when a service is started from the background
without an exemption. `GrayzoneAccessibilityService.kt:93–99` starts `OverlayService` via
`startForegroundService` as a watchdog on every app change — frequently while the app is in the
background. The `:a11y` side already guards its own call (`:100–105`), but that guard cannot catch a
throw inside `OverlayService.onCreate`, which happens in the **default** process. Result: the overlay
service dies and protection stops, with the only trace in logcat. → T018 should cite this trigger.

**F-209 confirmed mechanically; its consequence needs restating.** The ordering is exactly as filed —
`MainActivity.kt:91–92` returns early if `ALL_FILES_PROMPTED` is set, then writes the flag `true`
**before** attempting `startActivity`, and both nested attempts end in `catch (_: Exception)`. So if
both fail, the flag is already persisted and the user is never prompted again.

But "disables the anti-tamper backup forever" overstates it. The doc comment at `:83–85` documents a
real fallback: the backup falls back to a visible location. What is silently lost is **wipe
resistance** — the hidden folder that survives a data/cache clear. For an app whose threat model
includes the user, that *is* the anti-tamper property, so High still stands; the finding should say
"silently loses wipe resistance" rather than "disables the backup". Fix is one line: set the flag only
after a successful `startActivity`.

**Reconciliation 1 — F-005 (RU-04) and F-114 (RU-03) are one root cause, filed from two sides.**
Verified directly. `OverlayService.kt:488–490` is:

```kotlin
is SessionCommand.DismissOverlay -> {
    // Intentional no-op: policy still emits this; leave/lock paths dismiss overlays.
}
```

and `SessionModels.kt:39` documents the same decision from the policy side — *"Policy signal that
overlays should be clear; OverlayService treats as intentional no-op."* So this is a **deliberate,
doubly-documented design decision**, not an oversight, which changes how the finding must be argued:
the audit is challenging a stated design choice, and the fix must explain why the stated rationale is
wrong rather than treating it as a missing branch.

The rationale is wrong because the comment's premise — "leave/lock paths dismiss overlays" — does not
hold. Every `dismissOverlay()` call site was enumerated: `LockPresentation.kt:22` (lockout expired),
`OverlayService.kt:273` (midnight reset), `:661` (`ACTION_STOP`), `:766` (`onDestroy`), `:825`, `:952`
(home pressed), `:1026` (handed to presentations), `FrictionPresentation.kt:17` (countdown finished).
**None is an app switch.** Meanwhile `SessionPolicyEngine` emits `DismissOverlay` on exactly the app
switches that matter — kill switch off (`:22`), daily break (`:30`), unmonitored app (`:38`), active
session (`:57`), and `:87`. Since `TYPE_APPLICATION_OVERLAY` sits above application windows by
definition, a `BUDGET_LOCK` or `SCHEDULE_LOCK` screen — both with `initialSeconds = 0`, so their
countdown never runs and never self-dismisses — stays on top of the launcher and unrelated apps until
the user happens to tap it.

**Resolution**: keep **F-005** as the primary finding (RU-04 owns the executor and the presentation
contract, and rates it High). Set **F-114**'s `Disposition: withdrawn` with a pointer to F-005, and
carry F-114's policy-side detail into F-005's `Affected surfaces`. One root cause, one fix, both
surfaces enumerated.

**Reconciliation 2 — F-214 withdrawn, RESOLVED.** T030 filed the three `DnsResolverPool.kt:107,112,123`
empty socket-close catches because `findings-ru06.md` did not yet exist. T019 has now filed the same
sites as **F-031**, and RU-06 is the owning unit. F-214 is marked `withdrawn` in
`findings-principle3.md` with a pointer to F-031, retained for traceability only. One caveat travels
with F-031: `DnsResolverPoolTest.kt`'s two existing tests will break when the logging fix lands unless
the `testOptions { unitTests.isReturnDefaultValues = true }` change ships alongside it.

**Correction to the caveat above, from T029 (RU-10) — see [`findings-ru10.md`](./findings-ru10.md)
F-198.** The conclusion holds but the mechanism does not, and T034 should not plan around the stated
one. Traced statement by statement, the two existing tests **do not break**: all three F-031 sites
(`DnsResolverPool.kt:107`, `:112`, `:123`) are `catch` bodies around `socket.close()`, and neither
test reaches any of them — test 1 returns a healthy socket to `idleSockets` without closing it, and
test 2 calls `shutdown()` while `idleSockets` is empty, so the `forEach` at `:122–124` has no
iterations. The real constraint is different and stronger: `DnsResolverPool` **already** calls
`GrayzoneLogger.w` at `:71` and `.e` at `:96`, both on the unconditional `WARN`/`ERROR` branch, so
any test that deliberately drives a failure path — which is exactly the test F-031's record requires
— dies on `RuntimeException: Method w in android.util.Log not mocked`. So the Gradle setting is a
prerequisite for **verifying** F-031, not for keeping the suite green. A secondary hazard found in
the same trace: both tests open real UDP sockets to `127.0.0.1:53` on the build machine, so a host
whose stack surfaces ICMP port-unreachable to an unconnected socket would push `receive()` into the
outer catch at `:67` and fail the tests with that same misleading mocking error.

**Reconciliation 3 — the reference index has confirmed errors, and the dead-code sweeps must account
for it.** T019 found six stale or wrong rows in `evidence/reference-index.md`. Two were re-verified
directly: `onRevoke()` was listed as an existing override when there are **zero** matches for it in
`app/src/main` (validating F-025), and `DnsTrafficBus.clear()` was marked TEST-ONLY when
`AdBlockVpnService.shutdownVpn` calls it at `:293`. An accuracy warning is now at the top of the index
requiring T059–T064 to re-verify every removal candidate with a direct search before proposing a
deletion, since Principle V demands proof and the index has demonstrably mislabelled a live
declaration. Multi-signal findings such as `OemBatteryHelper` are unaffected.

**Reconciliation 4 — F-207 consolidated into F-060, severity corrected to Critical.** T030 and T021
independently found the same defect: `validateConfiguration()` reads the preferences it exists to repair
with unguarded typed getters, six lines after `restoreIfWiped()` can plant a type mismatch. T030 rated it
High, T021 rated it Critical. **Critical is correct** — the rubric's Critical row is "crashes the app,"
and this crashes inside `Application.onCreate`, so there is no launchable app. Keep **F-060** canonical
and set **F-207**'s `Disposition: withdrawn` with a pointer to it, but F-060 must absorb two things F-207
found and it did not: the second instance of the identical construct at `resetRuntimeStateIfNeeded()`
(`GrayzoneApplication.kt:71` → `RuntimeSessionReset.kt:29–43`), which fails the same way one line later
and must be fixed in the same pass, and the four exact read sites (`:181`, `:190`, `:199`, `:208`).

Both findings also need one correction on recovery, which neither got quite right. F-207 says uninstalling
is the only recovery; F-060 says clear-data cannot recover it. Precisely: **clear-data does not recover**,
because `MANAGE_EXTERNAL_STORAGE` is an appop held by the system rather than app data, so it survives the
wipe and the restore reads the poisoned backup again. **Uninstall does recover**, but only because
uninstalling revokes the appop so `restoreIfWiped` can no longer read the file — which means the sole
recovery path is the one that also destroys every lock. For a self-control app the recovery *is* the
bypass, so this cannot be filed as "recoverable, therefore High."

**Reconciliation 5 — F-062 consolidated into F-206, severity corrected up to High.** Both describe
`appScope` being the one scope with no `CoroutineExceptionHandler` and the two unguarded backup launch
sites that can therefore kill the process. T021 rated it Medium, T030 rated it High. **High is correct**:
Medium is "incorrect behavior in narrow conditions," and process death is not incorrect behavior, it is a
crash. It stays below Critical for the reason C4 was held at High — the trigger requires a shared-storage
probe (`File.exists()`, `isExternalStorageManager()`) to actually throw, which is plausible but not
confirmed, and the audit has already declined to rate an unconfirmed chain Critical. Keep **F-206**
canonical, since it enumerates all five launch sites and establishes that the app's other two scopes both
install handlers, making this an inconsistency rather than house style. Set **F-062**'s `Disposition:
withdrawn` with a pointer to F-206.

**Net effect on the counts**: RU-01's effective yield is 18 findings, not 20 — 1 Critical (F-060,
strengthened), 12 Low, and the Medium/High mix reduced by the two withdrawals. T034 must not double-count
F-060/F-207 or F-062/F-206 as separate fix work; each pair is one root cause and one patch.

**Reconciliation 6 — F-215 consolidated into F-148, severity corrected up to High.** A third
independently-discovered duplicate, and this one the analysis agents did *not* flag — I found it by
comparing locations. Both records cite the identical site: `CustomPromptsSheet.kt`, the `initialPrompts`
initialiser at **lines 38–48** and `savePrompts()` at **lines 53–56**. T030 rated it Medium, T026 rated it
High. **High is correct.** Medium means "incorrect behaviour in narrow conditions", and this is neither
narrow nor recoverable — any malformed JSON triggers it, F-043's unvalidated restore is a route to
malformed JSON without user action, and once the empty list is written back the user's prompts are gone.
It stays below Critical because what is destroyed is user *content*, not protection state: the friction
screen still appears, with default prompts. That is the line between this and C3, which shares the exact
read-empty-then-overwrite shape but also stops schedule locks firing.

Keep **F-148** canonical — it additionally names the two trigger sites (the add button at `:125` and
delete at `:154–157`) that F-215 does not — and set **F-215**'s `Disposition: withdrawn` pointing to it.
This follows Reconciliation 2's precedent that the owning review unit keeps the finding and the
Principle III sweep's copy is withdrawn.

**Reconciliation 7 — F-232 withdrawn into F-145, as anticipated.** Both describe the app having no state
restoration mechanism. I pre-flagged this when E4 filed F-232 and deliberately held it rather than
actioning it; T026 has now filed the systematic per-screen inventory that F-232 explicitly lacked, which
settles which survives. **F-145 is canonical**, F-232 is withdrawn against it. T050 offered the withdrawal
and T026 recommended it, so there is no dispute — but it had to be *recorded*, because both were left
`reported` and each was waiting on the other's owner to act. Note the finding is stronger than either
title suggests: `rememberSaveable` appears **zero** times, and with no `ViewModel` and no
`onSaveInstanceState` either, the prefs-backed subset survives a configuration change and **nothing**
survives process death.

**Clock tampering is now a cross-unit theme, not an isolated defect.** Three findings share it:
C5/F-110 (forward clock ends a lockout), C6/F-111 (backward clock revives expired sessions), and
**F-021** (forward clock ends the 24-hour VPN hold, because the hold-expiry alarm stops the VPN without
re-validating elapsed time). The last is the sharpest evidence that the pattern is *inconsistent
application* rather than absent design: `VpnHoldLock` implements the monotonic-clock defense correctly
and F-021 is one path failing to use it. A fix for C5/C6 should be designed to cover F-021 in the same
pass — T034 should group all three in the punch list.

**Propagated to T025 (RU-08)** — two build-config prerequisites T030 surfaced that block or weaken
fixes elsewhere:

- **`-keepattributes SourceFile,LineNumberTable` is absent** from `app/proguard-rules.pro`. Without it,
  release stack traces carry no line numbers, which materially weakens *every* Principle III fix whose
  whole purpose is diagnosability in the field. This is a genuinely good catch: it makes the 21
  error-handling fixes worth less in release than they appear.
- **`testOptions { unitTests.isReturnDefaultValues = true }` is absent** from `app/build.gradle.kts`,
  which constrains what JVM unit tests can be written for the Android-framework boundaries these fixes
  touch.

T021 independently surfaced both of the above while working a different review unit, which is
corroboration rather than duplication — two analyses reaching the same two prerequisites from different
directions. T021 adds a third, and it is already owned so T025 must not re-file it:

- **`debug { isDebuggable = false }` in `app/build.gradle.kts:69–78` (uncommitted) is the root cause of
  F-064**, whose fix necessarily edits that block. The finding is owned by RU-01; RU-08 should cite
  F-064 for the build-side change rather than opening a parallel finding. Note the fix is *not* to
  revert the block — its signing and installability rationale is sound — but to decouple the log gate
  from `debuggable` via a dedicated `BuildConfig` field.

## T025 (RU-08) — dispositions of the two propagated prerequisites, both upgraded to `confirmed`

T025 filed F-125 … F-142 in [`findings-ru08.md`](./findings-ru08.md) (0 Critical, 2 High, 6 Medium,
10 Low). Both prerequisites propagated above were checked against
`app/build/outputs/mapping/release/configuration.txt` — the **merged** configuration R8 actually
applied — rather than against `proguard-rules.pro` alone, which settles a question the propagation
note left open:

- **`-keepattributes SourceFile,LineNumberTable` → F-126.** The merged configuration keeps only
  `AnnotationDefault`, `Signature` and `*Annotation*` (lines 25, 128, 129, 240). So
  `proguard-android-optimize.txt` does **not** supply them either, and `-renamesourcefileattribute` is
  absent as well. `confirmed` from tool output, no device needed. Rated **Medium**, not High: it does
  not itself fail a protection, it reduces what the Principle III tier is worth in the field. T034
  should sequence it *before* that tier, since it is two lines.
- **`testOptions { unitTests.isReturnDefaultValues = true }` → F-129.** Absent from the whole
  repository (`rg` over `*.kts` returns zero matches). Must ship in the **same commit** as F-031, not
  after it, or `DnsResolverPoolTest.kt`'s two tests fail on landing.

**A third release-only defect was found in the same file and is the most consequential RU-08
finding — F-125.** The Gson block omits `-keep class * extends com.google.gson.reflect.TypeToken`,
which Gson's R8 guidance requires and which Gson 2.10.1 does not ship as a consumer rule (also
confirmed from `configuration.txt`, which contains only the app's hand-written block). The last
release build removed **four of the five** `TypeToken` anonymous subclasses in the app —
`StreakManager$getAchievements$existing$type$1`, `StreakManager$unlockAchievement$existing$type$1`,
`OverlayService$showOverlay$customPrompts$type$1` and
`CustomPromptsSheetKt$CustomPromptsSheet$initialPrompts$1$type$1` — none of which appears in
`mapping.txt`, so they were shrunk away rather than merged. All four call sites are live by direct
search. **The lone survivor is the one whose type argument has its own `-keep` rule**
(`ScheduleManager$getScheduleRules$type$1`, type `List<ScheduleRule>`), which is the strongest
available evidence for the mechanism and also means the `ScheduleRule`/`Achievement` keep rules are
load-bearing for TypeToken resolution — a point `evidence/r8.md` §F3 did not make, and a reason those
two rules must **not** be swept up in F-128's redundant-rule deletion. Every affected read wraps
`fromJson` in a catch returning an empty collection, so if type resolution is degraded the failure is
silent by construction, and `unlockAchievement` writes back what it read — the same
read-empty-then-overwrite data-loss shape as C3. Held at **High** with a stated device procedure;
**Critical if confirmed**. → T069's post-fix `usage.txt` diff should treat this as its first check.

**Two RU-08 findings must be actioned as an ordered pair.** F-140 (the accessibility config declares
no `packageNames`, so the service sees window events from every app, while the file's own header
claims "MINIMAL scope") and F-141 (`GrayzoneAccessibilityService.onServiceConnected` assigns a
**default-constructed** `AccessibilityServiceInfo`, whose `packageNames` is `null`, over whatever the
XML declared). F-141 must land first, or the natural fix for F-140 will appear to work and will
silently not.

## Confirmed leads carried in from Phase 1

Evidence already gathered, awaiting assignment to findings by the tasks named:

| Lead | Evidence | Owner task | Preliminary severity |
|---|---|---|---|
| Inexact alarms are the default path on API 31+ (no exact-alarm permission declared, so Doze can defer session lock past `activeUntil` with no user signal) | [`evidence/lint.md`](./evidence/lint.md) §E2 | T018, T019 | High candidate |
| ~~Synchronous `commit()` on `OverlayService.kt:531` and `:600`~~ **DOWNGRADED to Low** — both are on `Dispatchers.IO` (independently traced by T008 and T009). Style only, no ANR risk. | `evidence/lint.md` §E4 (corrected) | T018 | Low |
| **Main-thread storage read + AES-GCM decrypt + Gson parse + `commit()` on every `MainActivity.onResume`** — `StateBackupManager.restoreIfWiped` → `applyInto` (`StateBackupManager.kt:250`), called from `GrayzoneApplication.onCreate` and `MainActivity.onResume`. Lint missed it because that call site uses `commit()`'s return value, which suppresses `ApplySharedPref`. The `commit()` itself is **deliberate and documented** (`:248–249`) — the defect is the threading of the whole chain, so a fix must not swap in `apply()`. Verified by reading the call site. | `evidence/prefs-inventory.md`, `evidence/concurrency-inventory.md`, `evidence/lint.md` §E4 | T020 | **High** |
| **`UsageDatabase.getInstance` double-checked locking is broken** — `UsageDatabase.kt:35` is `INSTANCE ?: synchronized(this) { … }` with **no second null check inside** the lock, so two threads that both observe null each build a separate Room instance over one WAL file, and the second overwrites `INSTANCE` while the first's connection pool stays live. Verified by reading the file; neither the compiler nor lint reports it. | `evidence/concurrency-inventory.md`; re-verified directly at `UsageDatabase.kt:34–47` | T020 | **High** |
| **`vpn_bypass_apps` is read but never written** — `AdBlockVpnService.kt:261` reads it, `PrefsKeys.kt:66` defines it, and those are the only two occurrences in `app/src`. `userBypass` therefore always resolves to `emptySet()`. Precise scope: `DEFAULT_BYPASS_APPS` **is** applied (`:264`), so the default bypass works; only the *user-extendable* half is unreachable, contradicting the doc comment at `:255` ("plus any user-chosen packages") and README's "the exclusion list is user-extendable". | Verified directly at `AdBlockVpnService.kt:259–276`, `PrefsKeys.kt:66` | T019, T059, T025b | Medium |
| **Backup restore has no schema gate and no key allowlist** — `serialize` writes `SCHEMA_VERSION` (`StateBackupManager.kt:202`) but `applyInto` never reads it (`:229–231` reads only `entries`), so a snapshot from any schema is applied key-for-key. `applyInto` also writes **arbitrary** keys straight from the JSON with no allowlist, and the file lives in `/sdcard/.grayzone/` reachable by any app holding all-files access — so the anti-tamper backup is itself a tamper vector. | Verified directly at `StateBackupManager.kt:200–251` | T020 | **High** (upgrade path E3 + security A6) |
| **`restartVpnAfterDelay` can resurrect a user-stopped tunnel** — `AdBlockVpnService.kt:314–325` re-reads no state after its delay, leaving a VPN running with no persisted intent | `evidence/concurrency-inventory.md` | T019 | High candidate |
| **`onRevoke` is not overridden at all** in `AdBlockVpnService` — teardown on revocation relies on the framework default, so the app gets no hook to log it or set reconnect-pending | `evidence/concurrency-inventory.md` | T019 | Medium–High candidate |
| **Two raw `Thread`s no `Job` can cancel** — the VPN packet thread (`AdBlockVpnService.kt:223`) and the blocklist loader (`BlocklistManager.kt:145`, started from an `object` with no handle, join, or interrupt). Whether closing the tun fd reliably unblocks the parked `FileInputStream.read` is `needs-verification` (`vpnThread` nulled at `:290` with no `join()`). | `evidence/concurrency-inventory.md` | T019 | Medium candidate |
| **`sessionStartTime` read-modify-written across IO and Main** (`OverlayService.kt:122`) — `@Volatile` gives visibility but not atomicity, so daily budgets can mis-account | `evidence/concurrency-inventory.md` | T018 | Medium candidate |
| **`shareApk` copies the entire APK inside a Compose click handler** (`SettingsScreen.kt:82`) — main-thread file copy | `evidence/concurrency-inventory.md` | T026 | Medium candidate |
| **`RuntimeSessionReset` builds `daily_reset_date_runtime`** from the per-package `DAILY_RESET_DATE` prefix plus a private `"runtime"` literal (`RuntimeSessionReset.kt:17,29,42`) — global state inside a per-app namespace, outside `PrefsKeys`' knowledge. Renaming the prefix returns `""`, which triggers a reset that wipes every active session and lockout once after upgrade. | `evidence/prefs-inventory.md` | T020, T021 | Medium |
| **Two orphaned prefs keys with no migration mechanism** — `grayscale_enabled` (replaced by `theme_mode` in `0a41a1a`) and `budget_locked_`; the app has no SharedPreferences migration at all, and `StateBackupManager.kt:167` snapshots `prefs.all`, so the orphans are re-restored on every wipe-recovery | `evidence/prefs-inventory.md` | T021 | Low–Medium |
| **Stale comment asserting the opposite of the manifest** — `OverlayService.kt:164–166` claims it shares a process with `GrayzoneAccessibilityService`, contradicted by `android:process=":a11y"`. Flagged independently by T008 and T012. This is the invariant that would lead a later change to reintroduce prefs into `:a11y`. | `evidence/prefs-inventory.md`, `evidence/prior-fix-watchlist.md` | T018 | Medium (Principle IV) |
| **Two catch clauses widened in the uncommitted diff** — `IOException` → `Exception` in the VPN packet loop, `Exception` → `Throwable` in the bloom-filter load — folding programming errors and `OutOfMemoryError` into ordinary retry paths | `evidence/prior-fix-watchlist.md` | T019, T030 | Medium |
| **6 of 10 prior fixes from feature 002 have no automated test**, and the manual on-device validation meant to cover the re-arm and crash-safety contracts is that feature's own task T021 — still unchecked, so those paths were never verified by anything | `evidence/prior-fix-watchlist.md` | T029, T066–T081 | High (process gap) |
| `ObsoleteSdkInt` dead branches: `OemBatteryHelper.kt:163`, `OverlayService.kt:789`, `res/mipmap-anydpi-v26` | `evidence/lint.md` §E3 | T059, T060, T062 | Low |
| `Theme.kt:19:22` unused `context` parameter | [`evidence/warnings.md`](./evidence/warnings.md) | T059 | Low |
| Stale `AppAccessibilityService` keep rule matches nothing; `OverlayService`/`MainActivity` keep rules redundant with AGP's generated ones | [`evidence/r8.md`](./evidence/r8.md) §F1, §F2 | T025 | Low |
| `DateTotalRow` / `StartTimeTotalRow` removed by R8 → DAO projections possibly exercised only by instrumented tests | `evidence/r8.md` §F4 | T020, T029 | Low, needs corroboration |
| `HardcodedText` in `notification_countdown.xml:37` | `evidence/lint.md` §E7 | T028 | Low |
| **The protection-health model is 3/4 wired, and the uncommitted work is why**: `accessibilityActive` has no main-source writer (`updateAccessibilityStatus` is called only from `ProtectionStatusTest.kt:14,46`), making `isFullyProtected` **unsatisfiable in production** and leaving `isDegraded` reporting `true` for a fully-healthy state. **Causation established by T012**: the `:a11y` process split in the working tree required deleting its only two callers, so this is a regression introduced by the uncommitted change, not an original gap. `ProtectionStatusTest` still passes because it drives the repository directly — a test that cannot detect the loss of its subject's only callers. | [`evidence/cross-process-inventory.md`](./evidence/cross-process-inventory.md), [`evidence/prior-fix-watchlist.md`](./evidence/prior-fix-watchlist.md); confirmed by search — `ProtectionHealthRepository` is genuinely live for VPN/overlay (`OverlayService.kt:142,760`, `AdBlockVpnService.kt:226,248,311,319,336`, read at `MainActivity.kt:137`, `VpnScreen.kt:51`), so only the accessibility leg is missing | T020, T029 | Low as shipped, **High if wired naively** — see latent trap below |
| **The app's entire OEM-kill mitigation feature is built and never wired** — `OemBatteryHelper.kt` is 185 lines covering 8 manufacturers (MIUI autostart, ColorOS startup manager, vivo iManager, OxygenOS chain-launch, EMUI protected apps, Samsung sleeping-apps) with `needsOemOptimization()`, `getOptimizationInstructions()`, `openBatterySettings()`, and `canVerifyOptimization()`. **Zero callers** — the only occurrence in `app/src` is its own declaration at `:17`. Triple-corroborated: R8 whole-class removal, reference index 0 references, direct search. Meanwhile the app does only the generic `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at `OnboardingScreen.kt:209` and `HomeScreen.kt:172` — which this file's own doc comment says is insufficient: *"Xiaomi, Oppo, Vivo, Realme, OnePlus aggressively kill background services even when battery optimization is disabled."* **This is the E6 answer.** Not "delete dead code" — it is a feature gap on the app's most important resilience axis, fixable by wiring the existing code. Its error handling is already Principle III compliant (returns `false`, catches, logs). Whether the hardcoded `ComponentName`s still resolve on current MIUI/ColorOS is `needs-verification`. | Verified directly: `OemBatteryHelper.kt:1–185`, search across `app/src`; `evidence/r8.md` §F4; `evidence/reference-index.md` | T021, T052, T059 | **High** as a feature gap / Low if deleted — maintainer's call |
| **Per-app config has no validation, and the validated helper that exists for it is test-only** — `ConfigValidator` defines `WAIT_SECONDS_RANGE = 3..30` with `isWaitSecondsValid`/`repairWaitSeconds`, and `GrayzoneApplication.kt:181–185` repairs the **global** `WAIT_SECONDS` at startup. Nothing validates or repairs `per_app_wait_<pkg>`. `OverlayService.kt:836` reads it raw. `PerAppLimits.waitSeconds()` (`PerAppLimits.kt:15`) exists precisely to resolve per-app-vs-global with a validated fallback and is **test-only** — `OverlayService.kt:836–843` re-derives it inline instead. The normal UI path is safe (`PerAppSettingsSheet.kt:101` slider is bounded `3f..30f`), so the realistic trigger is the unvalidated backup restore above: `applyInto` writes arbitrary keys with no schema gate, so a **stale** snapshot can set `per_app_wait_<pkg>` to 0 — friction becomes instant and protection is silently defeated — or to a huge value, trapping the user. For a self-control app whose own README advertises "Anti-Bypass Protection", the user is part of the threat model, which is what makes this more than hygiene. | Verified directly: `PerAppLimits.kt:15`, `OverlayService.kt:836–843`, `ConfigValidator.kt:11–29`, `GrayzoneApplication.kt:181–185`, `PerAppSettingsSheet.kt:98–105`; `evidence/reference-index.md` | T020, T024, T026 | **High** (chains with the backup finding) |
| Three latent cross-process traps: `UsageDatabase` lacks `enableMultiInstanceInvalidation()` (`UsageDatabase.kt:34–47`); SharedPreferences coherence holds only because `:a11y` never reads prefs; `ProtectionStatus.accessibilityActive` invites a writer in the wrong process | `evidence/cross-process-inventory.md` | T020, T021 | Low now, documented as hazards |
| `BatteryLife` policy warning at `OnboardingScreen.kt:209` | `evidence/lint.md` §E5 | T025 | Low |

**Confirmed NON-findings** — recorded so the audit does not report them:

| Claim | Why it is not a defect | Source |
|---|---|---|
| Lint `MissingPermission` on `setExactAndAllowWhileIdle` | `SessionExpiryScheduler.arm()` guards with `canScheduleExactAlarms()`, falls back to inexact, catches `SecurityException` with an explanatory comment, and logs the fallback failure with context. Compliant with Principle III and a positive example. | `evidence/lint.md` §E1 |
| Manifest components stripped by R8 | All 7 manifest-declared components appear in `seeds.txt`; AGP generates their keep rules automatically. | `evidence/r8.md` §F2 |
| **Cross-process state corruption via the `:a11y` process** — the audit's leading hypothesis | **Refuted by evidence.** `GrayzoneAccessibilityService.kt` is 131 lines and is the only class in `:a11y`. Independently verified by reading it in full: its imports are `AccessibilityService`, `AccessibilityServiceInfo`, `Intent`, `Log`, `AccessibilityEvent` — nothing else. It touches only its own three `const val`s, the stateless `GrayzoneLogger`, and framework calls. It never reads SharedPreferences, never opens Room, and never references any repository or policy object. Both boundary crossings use real IPC: a package-targeted broadcast (`:86–89`) and a `startForegroundService` Intent extra (`:93–99`). **Zero FALSELY-SHARED declarations exist.** | `evidence/cross-process-inventory.md`; re-verified directly |
| `DnsTrafficBus` StateFlow collectors receive nothing | `AdBlockVpnService` is declared at `AndroidManifest.xml:121–132` with **no** `android:process`, so publisher (`AdBlockVpnService.kt:445/451/457`) and collectors (`VpnScreen.kt:293,414`) are both default-process. The "in-process bus" doc comment is accurate. Must not be "fixed". | `evidence/cross-process-inventory.md` |
| `GrayzoneApplication.onCreate` double-initializes expensive state | The code **does** detect the process — `GrayzoneApplication.kt:54` (`getProcessName() != packageName`), safe at `minSdk = 29`. The ~10 MB of Bloom filters load once (single call site, `AdBlockVpnService.kt:110`); WorkManager is doubly protected by the guard and `ExistingPeriodicWorkPolicy.KEEP`; Room is single-instance; the two notification channels have distinct IDs. Only `super.onCreate()` and four harmless field initializers run twice. | `evidence/cross-process-inventory.md` |

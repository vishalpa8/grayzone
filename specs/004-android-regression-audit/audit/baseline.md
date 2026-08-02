# Audit Baseline

**Feature**: `004-android-regression-audit` | **Captured**: 2026-07-31

Tasks: T002 (code state), T003 (tooling baseline).

## Code state under review (T002)

Every finding's `observed_against` field resolves to this state.

- **HEAD commit**: `557a963cc059098b0ff5c2f62bd8d45593031370` — `557a963 updated new changes`
- **Working tree**: dirty. Findings must be recorded as
  `working-tree @ 557a963 (uncommitted: <file>)` when they touch a modified file, and
  `557a963` otherwise.

### Uncommitted modifications — 9 app files (elevated attention per spec Assumptions)

| File | Review unit |
|---|---|
| `app/build.gradle.kts` | RU-08 |
| `app/src/main/AndroidManifest.xml` | RU-08 |
| `app/src/main/res/xml/accessibility_service_config.xml` | RU-08 |
| `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` | RU-01 |
| `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` | RU-04 |
| `app/src/main/java/com/grayzone/app/service/OverlayService.kt` | RU-04 |
| `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` | RU-06 |
| `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` | RU-06 |
| `app/src/main/java/com/grayzone/app/service/vpn/DnsTrafficBus.kt` | RU-06 |

Non-app changes in the tree (not under review): `.specify/feature.json`, untracked `INSTALL.md`,
untracked `specs/004-android-regression-audit/`.

**Consequence for the audit**: all 9 modified files sit in RU-01, RU-04, RU-06, and RU-08 — the four
units that own the app's three long-lived services and its build configuration. The most
change-exposed code is also the highest-severity code, so these units carry elevated attention in
T018–T030.

## Tooling baseline (T003)

Verified this session; see the "Phase 0/1 tooling baseline" table in
[`../plan.md`](../plan.md) for the authoritative record — deliberately not restated here so the two
copies cannot drift.

Summary of what it establishes: JDK 21 is mandatory (JDK 25 fails with a bare `25.0.4`), the JVM
unit suite is green, and the minified `assembleRelease` is green with R8 and resource shrinking
genuinely executed.

## Instrumented-test verification status (T070 — 2026-08-02)

`.\gradlew.bat assembleDebugAndroidTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` →
**BUILD SUCCESSFUL**. New/existing instrumented tests are **compile-verified only** and
NOT execution-verified (FR-024). No device or emulator is available in this environment.

Tier-1 also: `testDebugUnitTest` 317 tests / 0 failures; `assembleRelease` BUILD SUCCESSFUL.

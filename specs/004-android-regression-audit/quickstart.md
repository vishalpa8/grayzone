# Quickstart: Verifying the Audit

**Feature**: `004-android-regression-audit` | **Date**: 2026-07-31

How to reproduce the audit's evidence and validate its fixes in this environment. Every command here
was executed during Phase 0 unless marked otherwise.

## Prerequisites

| Requirement | Value | Notes |
|---|---|---|
| JDK | **21** at `C:\Program Files\jdk-21.0.11` | Mandatory. `JAVA_HOME` is unset and `PATH` resolves to Temurin 25, under which AGP 8.2.2 fails with a bare `25.0.4`. See research R1. |
| Gradle | 8.6 via wrapper | Distribution already cached; no download needed. |
| Android SDK | `C:\Android\Sdk` (from `local.properties`) | Platforms, build-tools, and platform-tools present. |
| Shell | PowerShell | Quote the JDK flag — the path contains a space. |
| Device | none | Instrumented tests compile only; see the Not Verifiable section. |

Set the JDK flag once per shell to avoid repeating it:

```powershell
$jdk = '-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11'
```

## Tier 1 — JVM unit suite (primary regression gate)

Run after every severity tier of fixes (FR-022):

```powershell
.\gradlew.bat testDebugUnitTest $jdk --console=plain
```

**Baseline established 2026-07-31**: `BUILD SUCCESSFUL in 47s`, all ~40 JVM test files passing.
**Expected after fixes**: still `BUILD SUCCESSFUL`, with zero previously-passing tests failing
(SC-010, SC-012). New tests added for findings must fail before their fix and pass after.

HTML report for triage:

```powershell
Start-Process app\build\reports\tests\testDebugUnitTest\index.html
```

## Tier 2 — Minified release build (release-only gate)

Run after the Critical tier and again after the Low tier (research R7):

```powershell
.\gradlew.bat assembleRelease $jdk --console=plain
```

**Baseline established 2026-07-31**: `BUILD SUCCESSFUL in 3m 38s`, with `minifyReleaseWithR8`,
`shrinkReleaseRes`, `optimizeReleaseResources`, and `validateSigningRelease` all executing — so R8
and resource shrinking are genuinely exercised, not skipped.

This is the only gate over the minified build, because `checkReleaseBuilds = false` disables release
lint (research R3). Inspect what R8 removed and kept:

```powershell
Get-Content app\build\outputs\mapping\release\usage.txt      # what was stripped
Get-Content app\build\outputs\mapping\release\seeds.txt      # what keep rules retained
Get-Content app\build\outputs\mapping\release\resources.txt  # shrunk resources
```

`usage.txt` and `seeds.txt` are the evidence for RU-08 keep-rule findings — including whether the
rule naming `AppAccessibilityService` (a class that does not exist) matched anything, and whether
`AdBlockVpnService` and the receivers survive without explicit rules.

## Tier 3 — Instrumented test compilation (compile-only)

Instrumented tests cannot run without a device, but they must still compile (Complexity Tracking):

```powershell
.\gradlew.bat assembleDebugAndroidTest $jdk --console=plain
```

**Expected**: `BUILD SUCCESSFUL`. Success proves the test compiles and its APIs exist. It does
**not** prove the asserted behavior — never report these as passing (FR-024).

## Tier 4 — Warning harvest (constitution gate)

Incremental builds hide warnings: the Tier 1 run reported `compileDebugKotlin UP-TO-DATE` and
emitted nothing, while a from-scratch release compile surfaced
`Theme.kt:19:22 Parameter 'context' is never used`. Force a recompile to collect the true set:

```powershell
.\gradlew.bat compileDebugKotlin $jdk --rerun-tasks --console=plain 2>&1 |
  Select-String -Pattern '^w:|warning:'
```

**Expected**: every `w:` line is triaged into a finding or explicitly dismissed with a reason. Under
the constitution's Code Quality Standards, unused-symbol and unreachable-code warnings are defects,
and `@Suppress` is not an acceptable resolution without documented justification.

## Optional — Android Lint (best-effort)

```powershell
.\gradlew.bat lintDebug $jdk --console=plain
```

May fail if `lint-gradle` cannot be resolved from `dl.google.com` behind a TLS-inspecting proxy —
that exact failure is why release lint is disabled. Treat a failure as expected and proceed; the
unused-resource and accessibility findings are produced by reference analysis instead (research R3).

## Validation scenarios

Each maps to a spec success criterion and is checkable against the delivered report.

| # | Scenario | Pass condition | Criterion |
|---|---|---|---|
| V1 | Read `spec.md`'s Scope of Review alone | Every package, resource dir, and build file is in exactly one review unit or the out-of-scope list | SC-002 |
| V2 | Read the punch list alone | Fix order decidable without opening the breakdown; Critical rows precede High; only Critical/High present | SC-003, FR-005 |
| V3 | Sample any 5 findings | All seven mandatory fields present in each | SC-004 |
| V4 | Sample any 5 fixes | Callers enumerated; process-death, config-change, and background/foreground risk each stated | SC-006 |
| V5 | Sample any 5 findings | Each names a test that fails before and passes after | SC-007 |
| V6 | Read the coverage matrix | 10 units + 6 areas + 8 conditions all present with non-blank outcomes and evidence | SC-008 |
| V7 | Grep the report for unlabelled runtime claims | Every runtime assertion is either tool-backed or `needs-verification` with a device procedure | SC-009, FR-025 |
| V8 | Run Tier 1 after each fix tier | Zero previously-passing tests fail | SC-010, SC-012 |
| V9 | Run Tier 2 after the Critical and Low tiers | `BUILD SUCCESSFUL` | SC-012 |
| V10 | Diff the working tree against the report | Every changed file traces to an approved finding | SC-013 |

## Not verifiable here (device required)

These carry `needs-verification` with the procedures below, for a maintainer with a device:

| Condition | Procedure sketch |
|---|---|
| Process-death state restore | `adb shell am kill com.grayzone.app`, reopen from recents, confirm screen state and active session survive |
| Rotation mid-operation | Enable auto-rotate, rotate while a session countdown or DB write is in flight |
| Doze / App Standby | `adb shell dumpsys deviceidle force-idle`, wait past a session expiry, confirm the alarm still fires |
| Populated-DB upgrade | Install the prior version, generate usage data, install the new version over it, confirm no data loss and no crash |
| Split-screen / foldable | Enter split-screen while an overlay is showing; change posture on a foldable |
| TalkBack coexistence | Enable TalkBack alongside the app's accessibility service, confirm both receive events |
| OEM battery kill | Install on Xiaomi/Samsung/OnePlus, leave backgrounded overnight with battery optimization on, confirm the app detects and reports the kill |
| minSdk 29 behavior | Run on an API 29 image, exercising legacy external storage and the absence of `MANAGE_EXTERNAL_STORAGE` |

## Environment gotchas

- **The JDK flag is not optional.** Omitting it produces `* What went wrong: 25.0.4` with no further
  diagnosis — do not mistake this for a code defect.
- **`gradlew.bat` writes warnings to stderr** (`java.lang.System::load` native-access warnings under
  JDK 25's launcher). PowerShell renders these as `NativeCommandError`; they are harmless noise, not
  build failures. Judge the run by its `BUILD SUCCESSFUL`/`BUILD FAILED` line and exit code.
- **Nine files are currently modified in the working tree.** Record `observed_against` per finding
  (FR-004) so findings are not later attributed to the wrong code state.
- **Do not add `org.gradle.java.home` to `gradle.properties`.** It is version-controlled; a
  machine-specific absolute path would break other clones. Use the flag, or a user-level
  `~/.gradle/gradle.properties`.

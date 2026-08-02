# Phase 0 Research: Android Regression & Code-Quality Audit

**Feature**: `004-android-regression-audit` | **Date**: 2026-07-31

Purpose: resolve every unknown that the audit's method depends on, with evidence gathered in this
environment rather than assumed. Each section states the Decision, the Rationale, and the
Alternatives considered.

---

## R1. Build toolchain — which JDK the audit must use

**Decision**: Every Gradle invocation for this feature uses
`-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11`. No repository file is changed to encode this.

**Rationale**: The only JDK on `PATH` is Temurin 25.0.4 and `JAVA_HOME` is unset. Gradle 8.6's
launcher starts under JDK 25, but AGP 8.2.2 aborts configuration with a bare, uninterpretable
message:

```text
FAILURE: Build failed with an exception.
* What went wrong:
25.0.4
BUILD FAILED in 702ms
```

Re-running the identical command with `-Dorg.gradle.java.home` pointed at JDK 21.0.11 succeeded
(`BUILD SUCCESSFUL in 47s`). AGP 8.2.2 supports JDK 17–21; JDK 22+ is outside its version-parsing
range, which is why the failure surfaces as a version string rather than a diagnosis.

**Alternatives considered**:
- *Add `org.gradle.java.home` to `gradle.properties`* — rejected: that file is version-controlled, so
  hardcoding a machine-specific absolute path would break every other clone. If the maintainer wants
  this persisted, `%USERPROFILE%\.gradle\gradle.properties` or a Gradle toolchain declaration is the
  correct home, and that is a candidate fix in RU-08, not an audit prerequisite.
- *Upgrade AGP/Gradle to a JDK-25-compatible pair* — rejected: a toolchain upgrade during a
  regression audit changes the very baseline being audited, and would itself need regression proof.

**Audit consequence**: RU-08 carries a candidate finding — the build declares no Java toolchain and
no supported-JDK documentation, so a fresh clone on a current JDK fails with a message
(`25.0.4`) that names neither the cause nor the fix.

---

## R2. Verification tooling actually available

**Decision**: The audit's evidence tiers are, in descending strength:

| Tier | Tool | Status in this environment | What it can prove |
|---|---|---|---|
| T1 | `testDebugUnitTest` | **Verified working** — `BUILD SUCCESSFUL in 47s`, all ~40 JVM test files pass | Pure-logic defects: policy engine decisions, date/reset math, schedule matching, DNS classification, bloom-filter behavior, preference serialization |
| T2 | `assembleRelease` (R8 + resource shrinking) | **Verified working** — `BUILD SUCCESSFUL in 3m 38s`, with `minifyReleaseWithR8`, `shrinkReleaseRes`, and `validateSigningRelease` all executing | Release-only breakage: keep-rule staleness, reflection/Gson stripping, resource shrinking removing referenced assets |
| T3 | `assembleDebugAndroidTest` | To be used in the fix phase | That instrumented tests *compile* — never that they pass |
| T4 | Source and call-graph analysis | Always available | Structural defects: coroutine scope ownership, main-thread I/O, silent catches, exported surfaces, dead code, cross-process assumptions |
| T5 | Device execution | **Unavailable** (maintainer-confirmed) | Nothing — every claim needing it is labelled needs-verification per FR-025 |

**Rationale**: FR-024 makes these tiers the definition of "confirmed". Recording them as an explicit
ladder prevents the audit from silently promoting a T4 inference into a stated fact, which is the
failure mode the spec's FR-010 exists to prevent.

**Alternatives considered**: *Treat Android Lint as a primary tier* — rejected, see R3.

---

## R3. Android Lint availability

**Decision**: Lint is a best-effort supplementary signal, not a required tier. The audit attempts
`lintDebug` once; if the lint-gradle artifact cannot be resolved, the audit proceeds on T1/T2/T4 and
records unused-resource and accessibility findings from manual reference analysis instead.

**Rationale**: `app/build.gradle.kts` disables release lint deliberately, and the in-repo comment
states the cause — on a TLS-inspecting network the `lint-gradle` download from `dl.google.com` fails
and blocks the signed build:

```102:108:app/build.gradle.kts
    lint {
        // The release-only "lintVital" gate tries to download lint-gradle from dl.google.com. On a
        // TLS-inspecting network that download fails and blocks the signed build, even though it has
        // no bearing on the produced APK. Skip it so release builds are reproducible offline/behind a
        // proxy; run `./gradlew lintDebug` explicitly when you want a lint report.
        checkReleaseBuilds = false
    }
```

An audit that depended on lint would therefore be non-reproducible on the maintainer's own network.

**Alternatives considered**:
- *Require lint for the unused-resource inventory (US5)* — rejected: reference analysis over 23
  resource files is tractable by hand and is not network-dependent.
- *Add detekt or ktlint* — rejected: adding static-analysis tooling is a dependency change outside
  this feature's scope, and a new tool's default ruleset would flood the report with style noise that
  competes with the Critical findings.

**Audit consequence**: RU-08 must assess whether `checkReleaseBuilds = false` hides real release
defects, since it removes the only automatic gate over the minified build.

---

## R4. Compiler-warning baseline

**Decision**: Warnings are collected from a forced recompile (`--rerun-tasks` on
`compileDebugKotlin`) rather than from an incremental build, and unused-symbol/unused-import warnings
are treated as Low-severity findings against the constitution's Code Quality Standards.

**Rationale**: The verified test run reported `compileDebugKotlin UP-TO-DATE`, so it emitted no
warnings at all. Reading warning silence from an up-to-date build as "no warnings exist" would be a
false negative — precisely the kind of unearned conclusion FR-010 forbids. The constitution states
that unused-import, unused-symbol, and unreachable-code warnings are defects to fix rather than
suppress, so the warning set is audit input, not noise.

The point is already proven: the `assembleRelease` probe compiled release Kotlin from scratch and
emitted a warning the incremental run had hidden —

```text
w: .../com/grayzone/app/ui/theme/Theme.kt:19:22 Parameter 'context' is never used
```

plus three `javac` warnings that `source`/`target` value 8 is obsolete. The unused parameter is a
direct Principle I candidate finding in RU-07; the obsolete-target warnings are an RU-08 candidate.
This confirms warnings must be harvested from forced recompiles for every unit, not sampled from
whatever happens to rebuild.

**Alternatives considered**: *Use `-Werror`* — rejected: it would fail the build on pre-existing
warnings before the audit could triage them by severity.

---

## R5. Highest-yield analysis axes for this specific codebase

**Decision**: Weight analysis effort toward five structural properties of Grayzone rather than
sweeping the checklist uniformly.

1. **No `ViewModel` layer exists.** Zero files match `*ViewModel*`. Screen state lives in Compose
   state plus SharedPreferences helpers, so "is state restored or silently lost?" is a live question
   for all 12 screens, and any `rememberSaveable` gap becomes real data loss on process death.
2. **The accessibility service runs in a separate `:a11y` process** (`android:process=":a11y"`), so
   any in-memory singleton, companion-object cache, or `StateFlow` shared with the main process is
   *two* independent instances. This was predicted to be the single most likely source of "protection
   silently stopped" defects.

   > **⚠️ REFUTED BY EVIDENCE (T010, 2026-07-31).** This axis produced **zero** findings, and that is
   > a supported result rather than an analysis gap. `GrayzoneAccessibilityService.kt` is 131 lines and
   > is the only class in `:a11y`; verified by reading it in full, its entire import set is
   > `AccessibilityService`, `AccessibilityServiceInfo`, `Intent`, `Log`, `AccessibilityEvent`. It
   > touches only its own three `const val`s, the stateless `GrayzoneLogger`, and framework calls — no
   > SharedPreferences, no Room, no repository, no policy object. Both boundary crossings use real IPC:
   > a package-targeted broadcast (`:86–89`) and a `startForegroundService` Intent extra (`:93–99`).
   > `GrayzoneApplication.kt:54` additionally guards initialization with a real process check.
   >
   > The design is deliberately minimal, and the audit credits it. Remaining value on this axis is
   > **latent traps** rather than defects: `UsageDatabase` lacks `enableMultiInstanceInvalidation()`
   > (`UsageDatabase.kt:34–47`), prefs coherence holds only because `:a11y` never reads prefs, and
   > `ProtectionStatus.accessibilityActive` invites a writer in the wrong process. See
   > `audit/evidence/cross-process-inventory.md`.
   >
   > **Consequence for effort allocation**: weight shifts to axes 3, 4, and 5, and to Principle III
   > error handling (T011/T030). Axis 2 is answered.
3. **Three long-lived components with silent failure modes** — `GrayzoneAccessibilityService`,
   `OverlayService` (`foregroundServiceType="specialUse"`), and `AdBlockVpnService` (exported, with
   `BIND_VPN_SERVICE`). Recovery after system kill, notification correctness, and user-visible
   failure signalling are the audit's core questions.
4. **Correctness depends on wall-clock and calendar boundaries** — session expiry via `AlarmManager`,
   daily budget resets, streak evaluation, and a 24h VPN hold. Timezone changes, DST boundaries, and
   Doze deferral all attack the same logic.
5. **State is persisted in three places with different durability** — Room, SharedPreferences, and a
   shared-storage anti-tamper backup. Divergence between them across upgrade, data-clear, and process
   death is a high-value target, and only Room has schema-versioned migration.

**Rationale**: Each property maps to a spec scope area that would otherwise be answered generically.
Effort spent here yields Critical findings; effort spent uniformly yields Low ones.

**Alternatives considered**: *Uniform sweep in package order* — rejected: it front-loads
low-severity UI and resource findings and delays the punch-list-worthy service defects, inverting
the priority the maintainer asked for.

---

## R6. Per-review-unit attack plan

Concrete question set per unit, so analysis is auditable rather than impressionistic.

| Unit | Primary questions |
|---|---|
| RU-01 | Does `GrayzoneApplication.onCreate` do blocking I/O (prefs, files, DB, blocklists) on the main thread? Does `MainActivity` survive process death and rotation? Do any `PrefsKeys` entries read a renamed/legacy key (upgrade path)? Does `GrayzoneLogger` write user-identifying data (package names, domains) to logcat in release? Does `OemBatteryHelper` detect failure and guide the user, or fail silently? |
| RU-02 | Is the 1→2 Room migration destructive, and is it tested against populated data rather than an empty DB? Any DAO call on the main thread? Does `UsageEvent` growth have a bound and does `CleanupWorker` actually run under Doze? Are query columns indexed? Does `StateBackupManager`'s shared-storage I/O handle permission denial and absent storage explicitly? Does daily-reset logic use a fixed zone or the device zone? |
| RU-03 | Do foreground/background decisions stay correct across clock changes and stale timestamps? Is strategy selection centralized (Principle II) or re-derived at call sites? Are boundary conditions (expiry exactly now, negative remaining) covered? |
| RU-04 | Does each foreground service post a compliant notification within the required window, and recover after being killed? What coroutine scope owns the accessibility event handling, and is it cancelled on `onDestroy`/`onUnbind`? Does `BootReceiver` restore all three components, and does it survive a boot with permissions revoked? Are `AlarmManager` alarms exact/allow-while-idle as the guarantee requires, and rescheduled after reboot and process death? Does `:a11y` read state the main process wrote in memory? |
| RU-05 | Are overlay windows and views removed on every exit path, including exceptions? Does presentation selection duplicate policy conditionals? What happens to an active overlay on rotation, split-screen entry, and posture change? |
| RU-06 | Are the VPN descriptor, sockets, and threads closed on `onRevoke`, `onDestroy`, and error paths? Is packet-loop I/O off the main thread and free of per-packet allocation? Do malformed/truncated DNS packets throw into the loop and kill the tunnel? What is the peak memory of blocklist/bloom loading from `res/raw`? Does the 24h hold expire correctly across process death and Doze? Is the exported VPN service reachable in a way that matters? |
| RU-07 | Which screens hold state that dies on process death (`remember` vs `rememberSaveable` vs persisted)? Is any long-running work launched from composition scope? Do sheets/dialogs leak on configuration change? Dark mode, RTL, large-font, and small-density behavior? Missing content descriptions on interactive elements? |
| RU-08 | Do the ProGuard keep rules match reality (a rule names `AppAccessibilityService`, which does not exist — the class is `GrayzoneAccessibilityService`), and are the VPN service and receivers protected where reflection or manifest instantiation requires it? Are exported components (`MainActivity`, `AdBlockVpnService`, `BootReceiver`) safe against malformed or hostile intents? Do declared permissions match actual use? Does `allowBackup=false` agree with `backup_rules.xml`/`data_extraction_rules.xml`? Does `accessibility_service_config.xml` request more capability than needed? Is anything minSdk-29-unsafe? |
| RU-09 | Which resources are unreferenced? Is `notification_countdown.xml` layout robust to long text and large fonts? Are user-facing strings hardcoded in Compose instead of `strings.xml` (blocks locale support)? |
| RU-10 | For each finding, does a test already exist? Does any existing test assert the defective behavior? Is `MigrationTest` exercising populated data? Are there duplicate/overlapping tests to consolidate under Principle I? |

---

## R7. Fix sequencing and regression containment

**Decision**: Fixes are applied in four severity tiers (Critical → High → Medium → Low). The JVM
suite runs after each tier, and `assembleRelease` runs after the Critical and Low tiers at minimum.
Within a tier, fixes are grouped by root cause, not by file.

**Rationale**: FR-022 requires tier sequencing so a regression is attributable to a narrow batch.
Grouping by root cause honors FR-009 and prevents the same underlying defect from being patched
inconsistently in two places — a real risk here, since SharedPreferences access, coroutine scoping,
and service restart logic each appear in multiple units.

**Alternatives considered**:
- *One commit per finding* — rejected: root causes span files, so per-finding commits would split
  single logical fixes and leave intermediate states inconsistent.
- *All fixes then one verification pass* — rejected: a single failing test at the end would not
  identify which of dozens of changes caused it, defeating the purpose of regression safety.

---

## R8. Handling scope areas with no subject in this codebase

**Decision**: Record a not-applicable outcome with the reason, and do not synthesize findings.

- **Retrofit/OkHttp concerns** (retry storms, unhandled response codes, certificate pinning): no HTTP
  client is declared in `app/build.gradle.kts`. Network-layer analysis instead targets the VPN's own
  DNS sockets and `res/raw` blocklist loading.
- **Fragment back-stack and `onSaveInstanceState` for Fragments**: the app is single-Activity Compose
  with no Fragments. The equivalent risk is re-examined as Compose navigation/state and rapid
  back-press against overlay dismissal.
- **RxJava/LiveData chains**: neither is a dependency. The equivalent risk is re-examined as
  `StateFlow`/`SharedFlow` collection without subscribers and orphaned collectors, notably in
  `DnsTrafficBus`.
- **DI-graph scoping defects**: no Hilt/Dagger. The equivalent risk is manual singleton and
  companion-object lifetime, which is *more* dangerous here given the `:a11y` process split.

**Rationale**: FR-017 requires an explicit not-applicable outcome with justification. Mapping each
absent technology to its real equivalent in this codebase preserves the brief's intent instead of
discarding the question.

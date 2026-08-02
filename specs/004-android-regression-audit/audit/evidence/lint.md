# Evidence: Android Lint (T006)

**Command**: `.\gradlew.bat lintDebug -Dorg.gradle.java.home="C:\Program Files\jdk-21.0.11" --console=plain`

**Result**: `BUILD SUCCESSFUL in 1m 9s`. Reports at `app/build/reports/lint-results-debug.{html,xml,txt}`.

## ⚠️ Research R3 expectation corrected

Research R3 predicted lint might fail because `lint-gradle` cannot be resolved behind a
TLS-inspecting proxy — the documented reason `checkReleaseBuilds = false` exists. **Lint ran
successfully in this environment.** It is therefore promoted from "best-effort supplementary signal"
to an available evidence tier for RU-09 (unused/hardcoded resources) and A3 (configuration).

This does not invalidate the `checkReleaseBuilds = false` question for RU-08 — the release-only
`lintVital` gate is a different task and may still fail on the maintainer's network. It does mean the
audit is not forced to derive resource findings by hand.

## Totals

27 issues, all severity `Warning`, zero errors.

| Count | Issue ID | Relevance |
|---|---|---|
| 11 | `GradleDependency` | RU-08, Low — outdated dependency versions |
| 5 | `IconLauncherShape` | RU-09, Low |
| 3 | `ObsoleteSdkInt` | **Principle I — unreachable code the Kotlin compiler did not report** |
| 2 | `MissingPermission` | Investigated below — **false positive**, but exposes a real adjacent issue |
| 2 | `ApplySharedPref` | A2/A5 — synchronous `commit()` disk write |
| 1 | `HardcodedText` | RU-09 — confirms the hardcoded-strings question in T028 |
| 1 | `BatteryLife` | RU-08 — Play Store policy |
| 1 | `UnusedAttribute` | RU-08, Low |
| 1 | `ObsoleteLintCustomCheck` | Noise, not audit input |

## E1. `MissingPermission` — FALSE POSITIVE (verified, not assumed)

Lint flags `setExactAndAllowWhileIdle` at `service/SessionExpiryScheduler.kt:48` and
`service/vpn/VpnHoldExpiryScheduler.kt:39` as requiring `SCHEDULE_EXACT_ALARM`, which the manifest
does not declare (verified by search: zero occurrences of `SCHEDULE_EXACT_ALARM` or
`USE_EXACT_ALARM` anywhere in `app/`).

Reading the call site disproves the defect. `SessionExpiryScheduler.arm()` already:

1. Guards with `Build.VERSION.SDK_INT < S || am.canScheduleExactAlarms()` before calling the exact API
2. Falls back to `setAndAllowWhileIdle` when not permitted
3. Catches `SecurityException` for the check-then-call race, with a comment explaining exactly that
4. Catches the fallback's own failure and logs via `GrayzoneLogger.e` with the package name

**Classification: no defect.** This code satisfies Constitution Principle III well — it is a positive
example, and the audit must not report it as a finding. `VpnHoldExpiryScheduler.kt:37` uses the same
guard shape and needs the same confirmation in T019.

## E2. Real issue exposed by E1 — inexact alarms are the DEFAULT path on API 31+

Because no exact-alarm permission is declared, `canScheduleExactAlarms()` returns `false` on API 31+
**unless** the user has granted the battery-optimization exemption. The inexact
`setAndAllowWhileIdle` path is therefore the normal path on modern devices, not the exceptional one.

Under Doze, `setAndAllowWhileIdle` is throttled — the alarm can be deferred well past `activeUntil`.
The consequence is that a session's lock arrives late, and nothing tells the user that timing is
approximate. That is protection degrading silently, which the FR-006 rubric ranks above a visible
error.

**Classification**: **High candidate** for RU-04 (and RU-06 for the 24h VPN hold). Distinct from the
lint warning, and it would have been missed by treating the lint warning as either true or false
without reading the code. Filed as **F-003** by T018.

**Reconciliation question ANSWERED by T018 (statically, no device needed).** The two surfaces disagree:

- The **lock screen does** reconcile against wall-clock time — `LockPresentation.initialSeconds` is
  `(lockedUntil - now) / 1000` and `SessionExpiryDecision.decide` re-reads `now`.
- The **notification chronometer does not**. It is anchored once and never recomputed, so it renders
  **negative values for the entire duration of the Doze deferral**.

That is a user-visible symptom of the inexact-alarm default, and it is the same negative-countdown
defect that feature `002-fix-timeout-accessibility-bugs` was created to fix — reappearing through a
different mechanism (deferral rather than the old in-memory `delay()`). Only the *magnitude* of the
deferral remains `needs-verification`, since App Standby bucket behavior is device-specific.

## E3. `ObsoleteSdkInt` — unreachable code (Principle I)

| Location | Message |
|---|---|
| `OemBatteryHelper.kt:163` | Unnecessary; `SDK_INT` is always >= 29 |
| `service/OverlayService.kt:789` | Unnecessary; `SDK_INT` is always >= 29 |
| `res/mipmap-anydpi-v26` | Folder config `v26` unnecessary; `minSdkVersion` is 29 |

These are dead branches and a redundant resource folder that the Kotlin compiler did **not** warn
about — direct confirmation of the limitation recorded in `warnings.md`. → T059/T060/T062 candidates
(Low), with Principle V requiring proof the branch is unreachable before removal (lint's `SDK_INT >= 29`
reasoning is that proof, given `minSdk = 29`).

## E4. `ApplySharedPref` — synchronous disk write

| Location | Message |
|---|---|
| `service/OverlayService.kt:531` | Use `apply()`; `commit` writes to persistent storage immediately |
| `service/OverlayService.kt:600` | Same |

**⚠️ CORRECTED 2026-07-31 — this section originally implied a main-thread violation. It is not one.**

Both flagged sites run **off** the main thread, independently traced by T008 and T009:
`OverlayService.kt:531` sits in a `suspend fun` reachable only from `serviceScope` launches on
`Dispatchers.IO`, and `:600` is inside an explicit `withContext(Dispatchers.IO)` block. The `commit()`
calls are still synchronous writes, but they are synchronous writes on an IO dispatcher, which is
ordinary.

**Classification**: **Low**, downgraded from Medium–High. Style preference only; no ANR risk. T018
should not raise these as a threading finding.

**And lint missed the one that matters.** `StateBackupManager.kt:250` performs a genuine main-thread
`commit()` — lint's `ApplySharedPref` check does not fire there because that call site *uses the return
value*, which suppresses it. That site is reached from `GrayzoneApplication.onCreate` **and from
`MainActivity.onResume` on every single resume**, and the `commit()` is preceded on the same thread by
a shared-storage file read, an AES-GCM decrypt, and a full Gson parse.

Note also that the `commit()` there is **deliberate and correct**: `StateBackupManager.kt:248–249`
documents that restored values must be durably visible before the rest of `onCreate` reads them on the
same launch. The defect is the *threading of the whole chain*, not the `commit()` choice — a fix that
swaps in `apply()` would break the documented invariant. → T020, High candidate.

**Lesson for the audit**: this is the second lint claim that inverted on inspection (see §E1). Lint
located a real code smell in the wrong place and stayed silent on the severe instance. Treat every
lint ID as a lead requiring a call-chain trace, never as a finding.

## E5. `BatteryLife` — Play policy

`ui/OnboardingScreen.kt:209` uses `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which lint reports as
violating Play Store content policy for acceptable use cases. The app's OEM-kill resilience strategy
depends on this exemption (and, per E2, so does its exact-alarm capability).

**Classification**: RU-08 candidate. This is a product/policy tension, not a crash — record it, do not
inflate it.

## E6. `UnusedAttribute`

`AndroidManifest.xml:48` — `dataExtractionRules` applies only to API 31+, current min is 29. Harmless
(the attribute is ignored below 31) but worth noting alongside the `allowBackup=false` vs
`backup_rules.xml` question in T025.

## E7. `HardcodedText`

`res/layout/notification_countdown.xml:37` — hardcoded `"Focus session"` instead of a `@string`
resource. Confirms T028's localization question has at least one real instance; T028 must also check
Compose sources, which lint does not cover for this check.

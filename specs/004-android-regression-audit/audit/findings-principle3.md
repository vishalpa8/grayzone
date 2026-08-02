# Principle III Findings â€” Robust Error Handling (T030)

**Feature**: `004-android-regression-audit` | **Created**: 2026-07-31 (T030)
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md)
**ID block**: F-205 â€¦ F-249 (used: F-205 â€¦ F-220)
**Governing rule**: [`.specify/memory/constitution.md:65-76`](../../../.specify/memory/constitution.md) â€” Principle III
**Primary input**: [`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md) (T011)
**Callers source**: [`evidence/reference-index.md`](./evidence/reference-index.md) (T007) â€” the only source used for the
`Callers` sub-field below.

## Scope and method

This file converts the T011 Principle III sweep into contract-compliant findings. The sweep itself is
not repeated: it catalogued **75 catch blocks** and **224 I/O boundaries** across all 68 main-source
files and identified **21 violations** and **84 unprotected boundaries**. What follows is the
disposition of that inventory â€” the 14 violations this task owns, plus the highest-consequence
unprotected boundaries raised as findings in their own right, because Principle III's second sentence
("every I/O boundary MUST have explicit failure handling") is violated by an absent handler exactly as
much as by an empty one.

Coverage is all 10 review units. Every finding is tagged with the review unit that owns its primary
location; where one root cause spans units, the other unit's sites appear under `Affected surfaces`
per the contract's Review-unit rule.

### Grouping decisions

Sites were merged into one finding only where they share **one root cause and one fix shape**:

| Finding | Sites merged | Why one finding |
|---|---|---|
| F-205 | 2 | Identical construct (`startForeground` outside any guard in the service entry path) and identical fix (wrap, log, tear the foreground state down). |
| F-207 | 2 | Both are unguarded prefs I/O executed directly on `Application.onCreate`'s critical path; one guard covers both. |
| F-211 | 11 | One construct (`startActivity`/`startForegroundService`/`startService`/`VpnService.prepare` with no try), one fix (wrap + log + user-visible failure), one existing model to copy. |
| F-214 | 3 | Three literally identical empty catches around `socket.close()` in one file; one shared `closeQuietly` fixes all three. |
| F-217 | 2 | One event (catch widening in the uncommitted diff), one fix shape (narrow the clause or document the widening and special-case the non-recoverable class). |
| F-218 | 2 | Comment-only catch on an expected/cosmetic path; fix is a one-line log at both. |
| F-219 | 3 | `catch { <default> }` with no record of intent; fix is a log before the default at all three. |

Deliberately **not** merged, because the fixes differ:

- `MainActivity.kt:169`, `:106`, `:261` (F-208 / F-209 / F-210) look like one pattern but need three
  different repairs: a protection-health signal, a flag-ordering correction, and a user-visible
  fallback affordance. Merging them would put three different fixes behind one punch-list entry.
- `BootReceiver.kt:61` (F-212) is split out of the F-211 group: its fix must additionally preserve the
  restore intent the way the sibling catch at `BootReceiver.kt:74` already does, and its consequence is
  silent protection loss rather than a visible crash.

## Ownership â€” violations filed by other tasks, referenced here, NOT re-filed

These are real Principle III violations. They are recorded here for completeness of the Principle III
picture only; the finding record for each lives with the task named.

| Ref | Location | Violation | Filed by |
|---|---|---|---|
| C1 | `service/OverlayService.kt:989â€“994` | `addView` failure logged without throwable/package/mode, then `return` â€” no overlay is shown at all | T018 (also T023) |
| C2 | `service/vpn/AdBlockVpnService.kt:119` | `try { protect(socket) } catch { /* ignore */ }` â€” comment-only catch; the `Boolean` return is discarded too | T019 |
| C4 | `service/vpn/AdBlockVpnService.kt:286â€“288` | `vpnInterface?.close()` in `catch (e: Exception) { /* ignored */ }` | T019 |
| C3 | `data/ScheduleManager.kt:33â€“43` | `catch (_: Exception) { emptyList() }`, then the next UI edit persists the empty list over the user's rules | T020 (also T024) |
| â€” | `data/StateBackupManager.kt:256` | blank `ANDROID_ID` silently poisons the AES key | T020 |
| â€” | `data/StateBackupManager.kt:128` | restore failure logged without the throwable | T020 |
| â€” | `data/StreakManager.kt:148` | corrupt achievements JSON silently overwritten | T020 |

`service/vpn/DnsResolverPool.kt:107,112,123` was flagged for possible overlap with T019. **T019's output
file `findings-ru06.md` does not exist at the time of writing** (verified by directory listing of
`specs/004-android-regression-audit/audit/`), so it is filed here as F-214. If T019 later files the
same sites, F-214 should be withdrawn in favour of its record rather than kept as a duplicate.

## Cross-cutting constraints on every fix in this file

Three facts constrain almost every fix below; they are stated once here rather than repeated in
sixteen `Fix` fields.

1. **`GrayzoneLogger.d()` and `.i()` are compiled out of behaviour in release.** `shouldLog()` gates
   `DEBUG`/`INFO` on `BuildConfig.DEBUG` (`GrayzoneLogger.kt:37-40`). A fix that satisfies Principle III
   with a debug log is invisible in the shipped build. That is acceptable for the Low findings (the
   constitution requires the construct to record its reason, and the source comment plus the debug log
   do that) but it must be a conscious choice, not an accident. Anything whose failure a user could feel
   must use `w()` or `e()`.
2. **Release stack traces are unusable.** `app/proguard-rules.pro` keeps `Signature` and `*Annotation*`
   but **not** `SourceFile`/`LineNumberTable`, and `isMinifyEnabled = true` for release
   (`app/build.gradle.kts:57-68`). A logged throwable therefore arrives obfuscated and line-free in a
   release build, so every message added below must name the operation and the resource in the message
   text, not rely on the trace. This is why the fixes specify message content rather than just "log it".
3. **A logging fix is currently untestable in the JVM suite.** `app/build.gradle.kts` has no
   `testOptions { unitTests.isReturnDefaultValues = true }` and no Robolectric (deps at `:150-152` are
   JUnit 4 + Mockito only), so any unit test that drives code into a new `GrayzoneLogger` call fails with
   `Method w in android.util.Log not mocked`. Several `Test to add` entries below therefore require that
   one-line `testOptions` addition as a prerequisite; it is called out where it applies.

---

# Findings

## Critical

### F-205 â€” `startForeground` is unguarded in both service entry points, so protection can fail to start at all  [Critical]

- **Review unit**: RU-04 (`service`)
- **Location**: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’ `OverlayService.onCreate()`
  (line 144); and `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’
  `AdBlockVpnService.startVpn()` (line 189)
- **Issue**: Both foreground-service entry points call `startForeground(NOTIF_ID, buildNotification())`
  with no failure handling. In `OverlayService.onCreate()` the call sits between
  `ProtectionHealthRepository.updateOverlayStatus(true)` (`:142`) and every piece of setup that follows â€”
  the `WindowManager` handle (`:145`), the session-expiry re-arm loop (`:157-162`), and both receiver
  registrations (`:167-168`). A throw there kills the service after the health repository has already
  been told the overlay is active, so `ProtectionStatus.overlayActive` reports `true` for a dead service
  while no friction screen, lockout, or countdown can ever appear. In `AdBlockVpnService` the call was
  deliberately hoisted **above** the try at `:191` to satisfy the FGS contract (the reasoning is written
  down at `:185-188` and is correct), but hoisting it out of the try left it unguarded: a throw
  propagates out of `startVpn()` â†’ `onStartCommand()` (`:167`) â†’ process crash, and because
  `PrefsKeys.VPN_ENABLED` is never cleared on that path, `BootReceiver` and `START_STICKY` retry the
  identical crash on every launch and every boot. `ForegroundServiceStartNotAllowedException` (API 31+
  background start) and a `SecurityException` for a missing `POST_NOTIFICATIONS`/FGS type are the
  realistic throwers; `MainActivity.kt:253` requests `POST_NOTIFICATIONS` and ignores the result
  (`:235`), so the denied-notification state is reachable by design.
- **Trigger condition**:
  1. Install, complete onboarding, enable the VPN so `PrefsKeys.VPN_ENABLED` is `true`.
  2. Deny the notification permission: `adb shell pm revoke com.grayzone.app android.permission.POST_NOTIFICATIONS`.
  3. Force-stop the app: `adb shell am force-stop com.grayzone.app`.
  4. Provoke a background service start rather than a foreground one â€” `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.grayzone.app` (or reboot the device).
  5. Observe with `adb logcat --pid=$(adb shell pidof com.grayzone.app)`: either the service starts, or
     `ForegroundServiceStartNotAllowedException` appears with no Grayzone log line explaining it.
  6. Re-launch the app and repeat step 4 to confirm the VPN path retries the same failure indefinitely.
- **Blast radius**:
  - *Callers*: `OverlayService.onCreate()` runs on the first start from `MainActivity.MainScreen()`
    (`MainActivity.kt:257`), `BootReceiver.onReceive()` (`BootReceiver.kt:40`),
    `SessionExpiryReceiver.onReceive()` (`SessionExpiryReceiver.kt:26`), and
    `GrayzoneAccessibilityService.onAccessibilityEvent()`'s watchdog
    (`GrayzoneAccessibilityService.kt:93`). `AdBlockVpnService.startVpn()` is reached from
    `onStartCommand()` (`:146`, `:167`), whose starters per the reference index are
    `MainActivity.startRestoredVpn()` (`MainActivity.kt:163`), `ui/VpnScreen.startVpnService()`
    (`VpnScreen.kt:527`), and `BootReceiver.onReceive()` (`BootReceiver.kt:69`).
  - *Process death*: this is the path that produces process death. The VPN half is self-perpetuating â€”
    the persisted `VPN_ENABLED` intent survives the crash and re-triggers it, so the failure is a loop,
    not an event.
  - *Config change*: none; neither service is Activity-scoped.
  - *Foreground/background*: decisive. All four `OverlayService` starters except `MainActivity.kt:257`
    fire while the app is backgrounded, which is exactly where API 31+ refuses a foreground-service
    start. The fix must not assume a visible Activity.
  - *Cross-process*: yes. `GrayzoneAccessibilityService` runs in `:a11y` (`AndroidManifest.xml:82`) and
    its watchdog start (`:93`) is a backgroundâ†’foreground-service start issued from a second process â€”
    the case most likely to be refused precisely when `OverlayService` is already dead.
  - *Persistence*: `AdBlockVpnService` leaves `PrefsKeys.VPN_ENABLED = true` across the crash; that
    persisted value is what makes the retry loop.
  - *Release-only*: no behavioural divergence, but see cross-cutting constraint 2 â€” the crash trace is
    obfuscated in release, so the added message must be self-describing.
- **Fix** (minimal): wrap each call. In `OverlayService.onCreate()`, put `startForeground` in a
  try/catch that logs
  `GrayzoneLogger.e(LogComponent.OVERLAY, "startForeground failed; OverlayService cannot run", e)`,
  calls `ProtectionHealthRepository.updateOverlayStatus(false)` to undo the optimistic `:142` write, and
  `stopSelf()` instead of continuing into a half-built service. In `AdBlockVpnService.startVpn()`, wrap
  the hoisted call in its own try/catch that logs
  `GrayzoneLogger.e(LogComponent.VPN, "startForeground failed before establish(); VPN not started", e)`,
  calls `ProtectionHealthRepository.updateVpnStatus(false)`, and `stopSelf()` â€” reusing the teardown the
  existing catch already performs at `:246-250`. Do not move the call back inside the try: the ordering
  comment at `:185-188` is correct and must be preserved.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/ForegroundStartFailureTest.kt`, asserting that when
  `startForeground` throws, `ProtectionHealthRepository.status.value.overlayActive` is `false` and the
  service is stopped rather than left half-initialised. **Cannot be executed here** (no device or
  emulator available to this audit).
- **Existing test conflict**: none. `ProtectionStatusTest.kt` drives the repository directly and does
  not assert on service lifecycle.
- **Verification**: `needs-verification` (evidence tier T4 â€” both call sites read directly)
  - *Missing*: an API 31+ device on which a background-initiated `startForegroundService` for these two
    components can be observed being refused.
  - *Procedure*: steps 1â€“6 above; capture `adb logcat` around each start and
    `adb shell dumpsys activity services com.grayzone.app` to confirm whether the service is running.
  - *Severity effect*: confirmed refusal on either component â†’ stays **Critical** (protection never
    starts, and the VPN half loops). If the platform never refuses these starts for this app's
    configuration and the only reachable thrower is a notification failure â†’ **High**, because the
    unguarded call is still the single point that decides whether protection exists at all.
- **Build scope**: everyday-build
- **Constitution**: III (I/O and service-boundary code with no explicit failure handling)
- **Affected surfaces**: `service/vpn/AdBlockVpnService.kt:189` (RU-06) shares this root cause and is
  fixed by the same shape. Related unguarded notification I/O in the same two files â€”
  `OverlayService.kt:728`, `:755`, `:1181-1187` and `AdBlockVpnService.kt:126`, `:578-588` â€” is left to
  T018/T019 because those failures lose a countdown rather than all protection.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt, AdBlockVpnService.kt)`
- **Disposition**: `fix-pending-device-verification`

---

## High

### F-206 â€” `appScope` is the one coroutine scope with no `CoroutineExceptionHandler`, so background work can kill the process  [High]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’ `GrayzoneApplication.appScope`
  (line 36), consumed by `registerStateBackup()` (lines 96â€“100, 128â€“133), `flushBackupIfDirty()`
  (line 139), `runDeferredStartupWork()` (line 148), `catchUpStreaks()` (line 162)
- **Issue**: `CoroutineScope(SupervisorJob() + Dispatchers.IO)` is created with no
  `CoroutineExceptionHandler`. `SupervisorJob` stops one child's failure from cancelling its siblings; it
  does **not** stop an uncaught exception from reaching the thread's default handler, which on Android
  terminates the process. Both service scopes in this codebase install a handler
  (`OverlayService.kt:107-117`, `AdBlockVpnService.kt:92-102`) and the VPN thread installs an
  `UncaughtExceptionHandler` (`AdBlockVpnService.kt:351-358`) â€” this scope is the sole exception, which
  makes it an inconsistency rather than a house style. Two of its five launch sites are unguarded:
  `:96-100` calls `StateBackupManager.backupExists()` â†’ `hiddenFile().exists()`
  (`StateBackupManager.kt:152`) and `writeBackup()` â†’ `Environment.isExternalStorageManager()`
  (`:139`) with no try, and `flushBackupIfDirty()`'s launch at `:139` calls `writeBackup()` the same way.
  A `SecurityException` or `IllegalStateException` from shared storage on either path kills the app
  during startup or immediately after the user backgrounds it â€” the moment the anti-tamper backup is
  supposed to be written. `runDeferredStartupWork()` and `catchUpStreaks()` are individually guarded, so
  the exposure is specifically the backup paths.
- **Trigger condition**:
  1. Install and complete onboarding so the app is in its normal state.
  2. Grant, then revoke, all-files access:
     `adb shell appops set com.grayzone.app MANAGE_EXTERNAL_STORAGE deny`.
  3. Make the shared-storage probe fail â€” remove or make unreadable `/sdcard/.grayzone/`
     (`adb shell rm -rf /sdcard/.grayzone` and confirm the parent is not writable for the app).
  4. Cold-start the app: `adb shell am force-stop com.grayzone.app` then launch it.
  5. Change any setting to set `backupDirty`, then press Home to trigger `flushBackupIfDirty()`.
  6. Watch `adb logcat -b crash`: a `FATAL EXCEPTION: DefaultDispatcher-worker-N` with no preceding
     Grayzone log line is the defect.
- **Blast radius**:
  - *Callers*: `appScope` is private; its only referencing class is `GrayzoneApplication`, which the
    reference index lists as instantiated by the framework in the main process only (`AndroidManifest.xml`
    `android:name`). Work reachable inside it: `StateBackupManager.backupExists()` and
    `StateBackupManager.writeBackup()` (index: referenced from `GrayzoneApplication.kt`, 2 sites),
    `StreakEvaluator.run()` (index: `GrayzoneApplication.kt`, `service/OverlayService.kt`,
    `ui/HomeScreen.kt`), and `DefaultMonitoredApps.seedIfNeeded()` (index: `GrayzoneApplication.kt`).
  - *Process death*: this finding *is* a process-death source. Killing the process during
    `flushBackupIfDirty()` also loses the backup write that was in flight, which is the one write that
    exists to survive a clear-data.
  - *Config change*: none â€” Application-scoped, not Activity-scoped.
  - *Foreground/background*: the flush path runs precisely when the app goes to the background
    (`onActivityStopped`, `:114-117`), so a crash here happens with no UI to show it. The 15-minute
    flush loop at `:128-133` runs with no Activity at all.
  - *Cross-process*: none. `onCreate()` returns early for `:a11y` at `:54`, so this scope never exists
    in the second process.
  - *Persistence*: yes â€” the scope's dominant work is the encrypted `/sdcard/.grayzone/backup.dat`
    write. A crash here means the anti-tamper snapshot silently stops being refreshed.
  - *Release-only*: none.
- **Fix** (minimal): give the scope a handler that matches the two services â€”
  `CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> GrayzoneLogger.e(LogComponent.BOOT, "Uncaught exception in GrayzoneApplication appScope", t) })`.
  That alone converts a process kill into a logged failure and satisfies Principle III for all five
  launch sites at once.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/AppScopeHandlerTest.kt`, constructing the same
  `SupervisorJob() + Dispatchers.IO + handler` combination used by the production scope, launching a
  child that throws, and asserting the handler is invoked exactly once and the scope stays active for a
  subsequent launch. Fails today (no handler exists to construct), passes once the handler is extracted
  to a named, testable value. Requires `testOptions { unitTests.isReturnDefaultValues = true }` in
  `app/build.gradle.kts` if the assertion drives the real `GrayzoneLogger` (cross-cutting constraint 3);
  otherwise assert on an injected callback.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T4). The absence of a handler is visible at
  `GrayzoneApplication.kt:36`; the presence of one in both comparable scopes is visible at
  `OverlayService.kt:107-117` and `AdBlockVpnService.kt:92-102`. The *crash* consequence is
  needs-verification and covered by the procedure above.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: III (async boundary with no explicit failure handling); the missing handler is also
  the enabling condition for the unguarded storage boundaries at `StateBackupManager.kt:139` and `:152`.
- **Affected surfaces**: `GrayzoneApplication.kt:96-100` and `:139` are the two unguarded launch bodies;
  `data/StateBackupManager.kt:139`, `:152` (RU-02) are the boundaries they reach. Boundary-level
  hardening of `StateBackupManager` belongs to T020; the missing handler is this finding.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-207 â€” `Application.onCreate` reads preferences unguarded on the startup critical path, including inside the function that exists to repair corrupt preferences  [High]

- **Disposition**: **withdrawn â€” consolidated into F-060** (`findings-ru01.md`), which owns the same
  defect at the corrected severity of **Critical** (the rubric's Critical row is "crashes the app," and
  this throws inside `Application.onCreate`). Retained for traceability only; do not action separately.
  Two details below are *not* in F-060 and have been carried across per Reconciliation 4 in
  `findings.md`: the second identical construct at `resetRuntimeStateIfNeeded()`
  (`GrayzoneApplication.kt:71` â†’ `RuntimeSessionReset.kt:29â€“43`) and the four exact read sites
  (`:181`, `:190`, `:199`, `:208`). One correction to the analysis below: uninstalling is **not** merely
  "the only recovery" â€” clear-data fails because `MANAGE_EXTERNAL_STORAGE` is a system-held appop that
  survives it, and uninstall works only because it revokes that appop, which also destroys every lock.
- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `GrayzoneApplication.validateConfiguration()` (lines 176â€“215, called at line 70) and
  `GrayzoneApplication.resetRuntimeStateIfNeeded()` (lines 271â€“279, called at line 71)
- **Issue**: `validateConfiguration()` exists to detect and repair corrupt configuration, but it reads
  the very preferences it is repairing with no protection: `getInt(WAIT_SECONDS)` (`:181`),
  `getInt(SESSION_MINUTES)` (`:190`), `getInt(LOCKOUT_MINUTES)` (`:199`), `getStringSet(MONITORED_APPS)`
  (`:208`). `SharedPreferences.getInt` throws `ClassCastException` when a key's stored type differs from
  the accessor's, and this app has a live mechanism for producing exactly that: `StateBackupManager`'s
  restore writes values back by their *recorded* type with no schema gate
  (`StateBackupManager.kt:235-243`, `SCHEMA_VERSION` written at `:202` but never read), and
  `restoreIfWiped(this)` runs at `:64` â€” **six lines before** `validateConfiguration()`. So the restore
  can plant the type mismatch and the repair function is then killed by it, throwing out of
  `Application.onCreate` and making the app unlaunchable with no path to self-heal. Uninstalling is the
  only recovery, which for a self-control app means the corruption becomes a bypass.
  `resetRuntimeStateIfNeeded()` (`:271-279` â†’ `RuntimeSessionReset.resetIfNeeded`,
  `RuntimeSessionReset.kt:29-43`) is the same construct one line later and fails the same way.
- **Trigger condition**:
  1. Install and use the app normally so a backup exists at `/sdcard/.grayzone/backup.dat`.
  2. Plant a type drift the way an upgrade or a stale restore would. Directly:
     `adb shell run-as com.grayzone.app` then edit
     `shared_prefs/GrayzonePrefs.xml`, changing `<int name="wait_seconds" value="10" />` to
     `<string name="wait_seconds">10</string>`.
  3. Cold start: `adb shell am force-stop com.grayzone.app` then launch.
  4. Observe: the app fails to start. `adb logcat -b crash` shows `ClassCastException` inside
     `GrayzoneApplication.validateConfiguration`, with no Grayzone log line.
  5. Confirm it is not self-healing by launching again â€” the same crash recurs.
- **Blast radius**:
  - *Callers*: both functions are private and have exactly one caller each,
    `GrayzoneApplication.onCreate()` (`:70`, `:71`) â€” which the reference index records as the framework
    entry point that "calls `ConfigValidator`, `DefaultMonitoredApps.seedIfNeeded`, `StateBackupManager`,
    `CleanupWorker` enqueue, `StreakEvaluator.run`". `ConfigValidator` is referenced only from
    `GrayzoneApplication.kt` (10 sites). `RuntimeSessionReset.resetIfNeeded()` is referenced from
    `GrayzoneApplication.kt` (`onCreate()`) and `service/OverlayService.kt`, so the same unguarded read
    is also on an `OverlayService` path.
  - *Process death*: the failure occurs during `Application.onCreate`, so there is no process to survive
    it; every component in the app â€” Activity, both services, receivers, WorkManager worker â€” fails to
    start because they all construct the Application first.
  - *Config change*: none directly, but every Activity recreation re-enters a process that cannot be
    created.
  - *Foreground/background*: fails identically in both; a boot-time `BootReceiver` delivery also
    constructs the Application, so protection is not restored after reboot either.
  - *Cross-process*: `:a11y` is unaffected â€” `onCreate()` returns at `:54` before reaching `:70`. That
    means the accessibility service keeps broadcasting app changes into a main process that cannot
    start, which is the most confusing possible symptom.
  - *Persistence*: yes. The trigger is persisted state, and because the repair never completes, the
    corrupt value is never rewritten â€” the state is stuck.
  - *Release-only*: none.
- **Fix** (minimal): wrap the two startup steps so a corrupt preference cannot abort launch â€” in
  `onCreate()`, call `validateConfiguration()` and `resetRuntimeStateIfNeeded()` inside a try/catch that
  logs `GrayzoneLogger.e(LogComponent.BOOT, "Startup configuration check failed; continuing with defaults", e)`.
  Additionally, inside `validateConfiguration()`, read each scalar through a small local helper that
  catches `ClassCastException`, removes the offending key, and marks `needsRepair = true` â€” that is what
  turns the function into the self-healing routine its KDoc (`:171-174`) already claims it is. Both parts
  are additive; no existing behaviour changes when the preferences are well-typed.
- **Rewrite justification**: not a rewrite. The per-key helper is four lines added inside the existing
  function; the repair/`editor.apply()` structure at `:177-225` is retained unchanged.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ConfigValidatorTest.kt` (extend the existing class), adding
  `wait seconds stored as the wrong type is removed and repaired to the default`, driven through
  `FakeSharedPreferences` (`app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`) seeded with a
  `String` under `PrefsKeys.WAIT_SECONDS`. Before the fix the read throws `ClassCastException` out of the
  test; after, it returns `ConfigValidator.DEFAULT_WAIT_SECONDS` and the key is repaired. This requires
  the per-key read helper to be extracted to a testable function rather than left inline in
  `GrayzoneApplication`.
- **Existing test conflict**: none. `ConfigValidatorTest.kt` currently tests only the pure range/repair
  functions and is unaffected; `RuntimeSessionResetTest.kt` seeds correctly-typed keys and continues to
  pass.
- **Verification**: `needs-verification` (evidence tier T4 â€” all four reads and both call sites read
  directly)
  - *Missing*: a device on which a type-drifted preference can be planted and the resulting
    `Application.onCreate` failure observed.
  - *Procedure*: steps 1â€“5 above. On a non-rooted device use `adb shell run-as` (the debug build is
    installable but `isDebuggable = false` per `app/build.gradle.kts:76`, so `run-as` may be unavailable â€”
    in that case plant the drift through the restore path by editing a decrypted backup and letting
    `restoreIfWiped` apply it).
  - *Severity effect*: confirmed unlaunchable app â†’ **Critical** (crashes the app, and the corruption is
    self-sustaining). If `ClassCastException` proves unreachable because no realistic path writes a
    mistyped key â†’ **Medium**, since the unguarded read on the startup critical path remains a Principle
    III violation with a narrow trigger.
- **Build scope**: everyday-build
- **Constitution**: III (I/O boundary with no explicit failure handling)
- **Affected surfaces**: `data/RuntimeSessionReset.kt:29-43` (RU-02) is the second unguarded body and is
  also reached from `service/OverlayService.kt`. The same `ClassCastException` exposure exists at
  `OverlayService.kt:150-158` and `:631-641` (RU-04, T018) and across the read-only UI prefs reads
  catalogued in the inventory Â§2.2 (RU-07, T026); those are separate findings because their fixes are
  local to their own entry points. The upstream cause â€” a restore with no schema gate and no key
  allowlist â€” is T020's.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-208 â€” The restored VPN silently fails to resume and the UI keeps showing a hold countdown for a tunnel that is not running  [High]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `startRestoredVpn()` (lines 161â€“172,
  catch at line 169)
- **Issue**: `catch (_: Exception) { }` with only a comment. The `_` discards the exception by
  construction, so nothing is logged, counted, or surfaced. Two operations are swallowed: the
  `startForegroundService(ACTION_START)` at `:163-167` (which can throw
  `ForegroundServiceStartNotAllowedException` or `SecurityException`) and
  `VpnHoldExpiryScheduler.rearmFromPrefs(context)` at `:168`. The function exists specifically to fix the
  bug its own KDoc names at `:113-116` â€” "leaving the UI showing a hold countdown while the VPN is
  actually off" â€” so a silent failure here reinstates exactly the defect it was written to prevent. The
  user sees the VPN screen's locked-on countdown while no tunnel exists, and because
  `ProtectionHealthRepository.markVpnReconnectPending` is never called on this path, the existing
  "reconnecting" banner does not fire either. A failure at `:168` additionally leaves the 24-hour
  auto-off alarm unarmed, so the hold may never release and the user is locked into a hold that is not
  even enforcing anything. The comment "the user can still enable the VPN manually" is a
  self-control-app rationalisation: the whole point of the hold is that the user should not have to, and
  should not be able to, manage this by hand.
- **Trigger condition**:
  1. Enable the AdBlock VPN so a 24-hour hold is recorded and `PrefsKeys.VPN_ENABLED` is `true`.
  2. Kill the tunnel without clearing intent: `adb shell am force-stop com.grayzone.app`.
  3. Make the foreground-service start fail â€” revoke notifications
     (`adb shell pm revoke com.grayzone.app android.permission.POST_NOTIFICATIONS`) and/or put the app in
     the restricted bucket (`adb shell am set-standby-bucket com.grayzone.app restricted`).
  4. Launch the app and go to the VPN tab.
  5. Observe: the screen shows "Locked on â€” Xh left" while `adb shell dumpsys activity services com.grayzone.app`
     shows no running `AdBlockVpnService`, and `adb logcat` contains no Grayzone line for the failure.
- **Blast radius**:
  - *Callers*: `startRestoredVpn()` is private to `MainActivity.kt`; its callers are
    `reconcileVpnState()` (`MainActivity.kt:152`) and the `vpnConsentLauncher` result callback
    (`MainActivity.kt:243`). `reconcileVpnState()` is called from `MainScreen()`'s `LaunchedEffect`
    (`:258`); the reference index records `MainScreen()` as composed by `MainAppContent()`. Downstream,
    `VpnHoldExpiryScheduler.rearmFromPrefs()` is referenced from `MainActivity.kt` (2 sites),
    `service/vpn/AdBlockVpnService.kt`, and `service/BootReceiver.kt`;
    `AdBlockVpnService.ACTION_START` from `MainActivity.kt`, `ui/VpnScreen.kt`, `service/BootReceiver.kt`.
  - *Process death*: the failure is *caused* by a post-process-death restart; the fix must therefore work
    from a cold start with no in-memory state and must read intent from preferences only.
  - *Config change*: the call sits in a `LaunchedEffect(Unit)` inside `MainScreen()`, so a rotation
    re-runs it; a fix that shows a one-shot UI signal must survive recomposition without re-prompting.
  - *Foreground/background*: the call is deliberately made from the foreground (`:118-119`) where an FGS
    start is permitted; the failure modes that remain are permission- and bucket-driven, not
    background-restriction-driven.
  - *Cross-process*: none. `MainActivity` and `AdBlockVpnService` are both default-process
    (`AndroidManifest.xml:121-132` declares no `android:process` for the VPN service).
  - *Persistence*: yes. `PrefsKeys.VPN_ENABLED` stays `true` and the `VpnHoldLockStore` timestamps stay
    written, so the persisted state continues to claim protection that does not exist.
  - *Release-only*: none.
- **Fix** (minimal): replace the empty body with
  `GrayzoneLogger.e(LogComponent.VPN, "Failed to resume restored VPN", e)` and
  `ProtectionHealthRepository.markVpnReconnectPending(true)`, so the "Protection interrupted â€”
  reconnecting" state the VPN screen already renders reflects reality. Delete the misleading comment.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/VpnRestoreFailureTest.kt`, asserting that when
  `startForegroundService` throws, `ProtectionHealthRepository.status.value.vpnReconnectPending` becomes
  `true`. **Cannot be executed here** (no device). A `jvm-unit` alternative is possible only if the
  service-start call is hoisted behind an injectable lambda, which is a larger change than this finding
  justifies.
- **Existing test conflict**: none. `VpnHoldLockTest.kt` covers only the pure hold arithmetic;
  `ProtectionStatusTest.kt` already exercises `markVpnReconnectPending` directly.
- **Verification**: `needs-verification` (evidence tier T4 â€” catch body and both swallowed calls read
  directly)
  - *Missing*: a device on which `startForegroundService` can be made to throw from the foreground.
  - *Procedure*: steps 1â€“5 above; confirm the service state with
    `adb shell dumpsys activity services com.grayzone.app`.
  - *Severity effect*: confirmed silent no-op with the countdown still displayed â†’ **Critical** under the
    FR-006 Grayzone calibration (protection off, UI says on, nothing logged). If the start proves
    unfailable from the foreground and only the `rearmFromPrefs` half can throw â†’ **High**, since the
    consequence narrows to an unarmed auto-off alarm.
- **Build scope**: everyday-build
- **Constitution**: III (silent catch; failure masked with no recorded reason)
- **Affected surfaces**: none â€” the other two `MainActivity` silent catches (F-209, F-210) share the
  construct but not the fix.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

---

### F-209 â€” The all-files-access prompt is marked "already shown" before it is shown, so one swallowed failure disables the anti-tamper backup forever  [High]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `requestAllFilesAccessOnce()`
  (lines 87â€“110, catch at line 106)
- **Issue**: Two defects compound. The catch at `:106` is comment-only â€” `catch (_: Exception) { }` around
  the fallback `startActivity(ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)` at `:102-105`, so an
  `ActivityNotFoundException` from both settings intents disappears with no record. Worse, the
  once-only flag is written **before** the attempt: `prefs.edit().putBoolean(ALL_FILES_PROMPTED, true).apply()`
  at `:92`, and the early return at `:91` reads it. So a single failure permanently closes the only path
  to all-files access. Without it, `StateBackupManager.writeBackup` returns early every time
  (`StateBackupManager.kt:161-164`) and no backup is ever written â€” which means a clear-data escapes
  every lock, permanently and silently, in an app whose stated purpose is that it should not. The comment
  at `:107` and the KDoc at `:84-85` both claim "fallback storage will be used"; there is no fallback
  storage anywhere in `StateBackupManager`, so the comment documents behaviour that does not exist and
  would stop a reviewer from recognising the gap.
- **Trigger condition**:
  1. Install on a device or image whose Settings package lacks both all-files-access activities (Android
     TV/automotive images and several OEM builds). Simulate with
     `adb shell pm disable-user --user 0 com.android.settings` on a throwaway device/emulator, or use an
     image without those activities.
  2. Launch the app and reach `MainScreen` (or trigger `onResume`, `MainActivity.kt:50`).
  3. Confirm the flag was set despite the failure:
     `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml | findstr all_files_prompted`.
  4. Re-enable Settings and relaunch. Observe the prompt never appears again.
  5. Confirm the consequence: use the app, then check that `/sdcard/.grayzone/backup.dat` is never
     created (`adb shell ls -la /sdcard/.grayzone/`).
  6. Clear app data (`adb shell pm clear com.grayzone.app`) and confirm all locks and monitored apps are
     gone with no restore.
- **Blast radius**:
  - *Callers*: `requestAllFilesAccessOnce()` is private to `MainActivity.kt`, called from
    `MainActivity.onResume()` (`:50`) and `MainScreen()`'s `LaunchedEffect` (`:256`); the reference index
    records `MainScreen()` as composed by `MainAppContent()` and `MainActivity` as the manifest launcher.
    Downstream, `StateBackupManager.hasAllFilesAccess()` is referenced from `MainActivity.kt` and
    internally by `restoreIfWiped()`/`writeBackup()`; `StateBackupManager.writeBackup()` from
    `GrayzoneApplication.kt` (2 sites); `restoreIfWiped()` from `GrayzoneApplication.kt` (`onCreate()`)
    and `MainActivity.kt`. `PrefsKeys.ALL_FILES_PROMPTED` has exactly one referencing file,
    `MainActivity.kt`.
  - *Process death*: irrelevant to the trigger, but the persisted flag means the defect survives every
    process death, reinstall-preserving backup, and app upgrade.
  - *Config change*: the `LaunchedEffect(Unit)` re-runs on recomposition; the early return at `:91` makes
    that harmless today and must stay harmless after the fix.
  - *Foreground/background*: called only from the foreground, so `startActivity` background restrictions
    do not apply; `ActivityNotFoundException` is the live thrower.
  - *Cross-process*: none.
  - *Persistence*: central to the defect. `ALL_FILES_PROMPTED` is written at `:92` with `apply()` and is
    never reset anywhere in the codebase, so the state is terminal.
  - *Release-only*: none.
- **Fix** (minimal): move the flag write out of `:92` and set it only on the branch where an intent
  actually launched (after the successful `startActivity` at `:94-99`, and after the fallback at
  `:102-105`); in the catch at `:106`, log
  `GrayzoneLogger.w(LogComponent.BOOT, "No all-files-access settings activity; anti-tamper backup disabled", e)`
  and leave the flag unset so the next resume retries. Delete the inaccurate "fallback storage" comment
  at `:107` and correct the KDoc claim at `:84-85`.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/AllFilesPromptOnceTest.kt`, driving the extracted decision
  ("should we prompt, and may we mark as prompted?") over `FakeSharedPreferences`
  (`app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`) and asserting that a failed launch
  leaves `PrefsKeys.ALL_FILES_PROMPTED` absent while a successful launch sets it. Fails before the fix
  (the flag is set unconditionally), passes after. Requires the flag/launch ordering to be expressed as a
  small pure function taking a `launch: () -> Boolean`, mirroring how `AccessibilityPromptPolicy`
  (`AccessibilityPromptState.kt`) already separates the decision from the persistence.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T4). The ordering defect needs no device: the flag write at
  `MainActivity.kt:92` precedes the `try` at `:93`, and the early return at `:91` reads the same key. The
  downstream consequence is confirmed by `StateBackupManager.kt:161-164`, which returns early without
  all-files access. Only the *frequency* of `ActivityNotFoundException` on real devices is unverified,
  and that affects likelihood, not correctness.
- **Build scope**: everyday-build
- **Constitution**: III (comment-only catch; failure masked with no recorded reason). The false comment at
  `:107` and the KDoc at `:84-85` are also documentation drift â€” see `findings-docs.md` (T025b) for the
  docs-accuracy pattern.
- **Affected surfaces**: the sibling catch at `MainActivity.kt:100` is **compliant** â€” it falls through to
  the alternate intent at `:102` and is a documented recovery, not a swallow. Do not "fix" it.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-210 â€” The automatic VPN re-consent dialog can fail to open with no record, defeating the reinstall-resistant hold  [High]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainScreen()`, the `onConsentNeeded`
  lambda passed to `reconcileVpnState()` (lines 258â€“264, catch at line 261)
- **Issue**: `catch (_: Exception) { }` with only a comment around
  `vpnConsentLauncher.launch(consentIntent)`. `ActivityResultLauncher.launch` throws
  `ActivityNotFoundException` when no VPN-consent activity resolves and `IllegalStateException` when the
  launcher is invoked outside a registered lifecycle state â€” both plausible from a `LaunchedEffect`. The
  flow this guards is described at `MainActivity.kt:141-148`: it exists so that "reinstalling can't be
  used to escape the hold". When it fails silently, reinstalling *does* escape the hold, and the only
  evidence is its absence. Meanwhile `VpnHoldExpiryScheduler.rearmFromPrefs(context)` has already run at
  `:147`, so the countdown keeps ticking honestly in the UI while there is no tunnel and no dialog â€” the
  worst combination, because the display looks correct.
- **Trigger condition**:
  1. Enable the VPN, let the 24-hour hold start, and confirm the VPN screen shows "Locked on".
  2. Force VPN consent to be revoked: `adb shell pm clear com.grayzone.app` on a device that revokes VPN
     consent on clear-data, or uninstall and reinstall while an external backup exists at
     `/sdcard/.grayzone/backup.dat` so the hold is restored.
  3. Make the consent activity unresolvable â€” `adb shell pm disable-user --user 0 com.android.vpndialogs`.
  4. Launch the app.
  5. Observe: no consent dialog appears, the VPN screen still shows the hold countdown,
     `adb shell dumpsys activity services com.grayzone.app` shows no `AdBlockVpnService`, and
     `adb logcat` contains no Grayzone line.
- **Blast radius**:
  - *Callers*: the lambda is passed at `MainActivity.kt:258` from `MainScreen()`'s `LaunchedEffect` into
    `reconcileVpnState()` (`:121-154`), which invokes it at `:148`. `vpnConsentLauncher` is registered at
    `:239-245` and, on `RESULT_OK`, calls `startRestoredVpn()` (`:243`) â€” the function F-208 covers, so a
    failure here and a failure there are the two ends of the same recovery path. Per the reference index,
    `MainScreen()` is composed by `MainAppContent()`, and `MainActivity` is the manifest launcher.
    `VpnHoldExpiryScheduler.rearmFromPrefs()` (already called at `:147`) is referenced from
    `MainActivity.kt` (2 sites), `service/vpn/AdBlockVpnService.kt`, `service/BootReceiver.kt`;
    `VpnHoldLock.isHoldActive()` is internal to `VpnHoldLock.canUserDisable()`.
  - *Process death*: the whole path exists to run after a process/data lifecycle event, so the fix must
    not depend on in-memory state.
  - *Config change*: `LaunchedEffect(Unit)` re-runs after recreation; `reconcileVpnState()` re-reads
    preferences each time, so a retry is safe and a fix may rely on that.
  - *Foreground/background*: the launcher requires a resumed Activity. A fix must not attempt the launch
    from a background state, which is itself one of the `IllegalStateException` causes.
  - *Cross-process*: none.
  - *Persistence*: yes â€” `PrefsKeys.VPN_ENABLED` and the `VpnHoldLockStore` timestamps stay set, so the
    persisted state promises an enforced hold that is not enforced.
  - *Release-only*: none.
- **Fix** (minimal): log
  `GrayzoneLogger.e(LogComponent.VPN, "Failed to launch VPN consent dialog; hold is not enforced", e)`
  and set `ProtectionHealthRepository.markVpnReconnectPending(true)` so the VPN screen's existing
  interrupted-protection state renders instead of a clean countdown. That converts a silent failure into
  the visible in-app prompt the user needs to re-grant consent manually.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/VpnConsentLaunchFailureTest.kt`, asserting that a throwing
  launcher results in `vpnReconnectPending == true` rather than a clean status. **Cannot be executed
  here** (no device).
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T4 â€” catch body read directly)
  - *Missing*: a device where `com.android.vpndialogs` can be disabled, or an image that lacks it.
  - *Procedure*: steps 1â€“5 above.
  - *Severity effect*: confirmed â†’ **Critical**, because the outcome is a hold the UI claims is active
    and nothing enforces, with no log. If `launch()` proves effectively unfailable on stock images â†’
    **Medium**, as the remaining exposure is the `IllegalStateException` lifecycle race.
- **Build scope**: everyday-build
- **Constitution**: III (comment-only catch)
- **Affected surfaces**: none; F-208 covers the sibling failure on the same recovery path with a different
  fix.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-211 â€” Eleven `startActivity` / service-start / `VpnService.prepare` calls on UI paths have no failure handling, so the buttons that fix protection can crash the app  [High]

- **Review unit**: RU-07 (`ui` + `ui.theme`)
- **Location**: eight sites in `ui/`, three in the root package â€”
  `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` â†’ `OnboardingScreen()` (lines 104, 156, 212);
  `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()` (lines 175, 187);
  `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt` â†’ `VpnScreen()` (line 98), `startVpnService()`
  (line 527), `stopVpnService()` (line 533), `refreshVpnNotification()` (line 539);
  `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `reconcileVpnState()` (line 139), `MainScreen()`
  (line 257)
- **Issue**: Eleven IPC boundaries with no `try`, no handler, and no user-visible failure path.
  `startActivity` throws `ActivityNotFoundException` whenever the target settings activity is absent â€”
  routine on OEM builds, Android TV/automotive images, and work profiles â€” and the affected buttons are
  precisely the "fix this" affordances for accessibility, overlay, and battery-optimisation, i.e. the
  controls a user reaches when protection is already broken. `VpnService.prepare()` (`MainActivity.kt:139`,
  `VpnScreen.kt:98`) and the three service-control calls (`VpnScreen.kt:527`, `:533`, `:539`) fail the
  same way, and `MainActivity.kt:257`'s `startForegroundService(OverlayService)` sits inside a
  `LaunchedEffect` where an FGS restriction takes the Activity down instead of starting protection. The
  inconsistency is the strongest evidence that these are oversights rather than a decision: **every other
  call site of these same APIs in this codebase is wrapped** â€” `BootReceiver.kt:39-44`,
  `SessionExpiryReceiver.kt:21-33`, `GrayzoneAccessibilityService.kt:92-105`, `OverlayService.kt:806-820`
  and `:957-965`, `OemBatteryHelper.kt:59-77`, `SharedComponents.kt:207-212`, and
  `MainActivity.kt:93-109`. A failed `stopVpnService()` (`:533`) is the worst of the group: it leaves the
  UI and the tunnel disagreeing about whether the VPN is on.
  **Honesty note on reachability**: per the reference index, `refreshVpnNotification()` is
  **UNREFERENCED**, so `VpnScreen.kt:539` is currently unreachable from main source. It is listed because
  it is the same construct in the same file and would become live the moment the function acquires a
  caller; it must not be counted as a live crash path today.
- **Trigger condition**:
  1. Install on a device whose Settings lacks one of the target activities, or simulate:
     `adb shell pm disable-user --user 0 com.android.settings`.
  2. Launch the app and reach Onboarding (or Home, if onboarding is complete).
  3. Tap "Enable Accessibility" (`OnboardingScreen.kt:104`), then repeat for the overlay button (`:156`)
     and the battery button (`:212`), and for Home's permission card (`HomeScreen.kt:187`) and battery
     card (`:175`).
  4. Observe the app crash rather than showing a message. Capture with `adb logcat -b crash`.
  5. For the VPN group, disable `com.android.vpndialogs` and toggle the VPN switch on the VPN tab
     (`VpnScreen.kt:98`, `:527`).
  6. Confirm no Grayzone log line precedes any crash.
- **Blast radius**:
  - *Callers*: per the reference index â€” `OnboardingScreen()` is composed by `MainAppContent()`
    (`Screen.ONBOARDING`); `HomeScreen()` by `MainScreen()` (`Tab.HOME`); `VpnScreen()` by `MainScreen()`
    (`Tab.VPN`); `MainScreen()` by `MainAppContent()`; `MainAppContent()` by `MainActivity.onCreate()`.
    `startVpnService()` is called from `VpnScreen.kt` at 2 sites (`beginEnableVpn()` and the permission
    callback), `stopVpnService()` from `VpnScreen.kt` (`tryDisableVpn()`), and `refreshVpnNotification()`
    from nowhere (**UNREFERENCED**). `settingsIntentFor()` (used at `HomeScreen.kt:187`) is referenced
    only from `ui/HomeScreen.kt`. `AdBlockVpnService.ACTION_START` is referenced from `MainActivity.kt`,
    `ui/VpnScreen.kt`, `service/BootReceiver.kt`; `ACTION_STOP` from `ui/VpnScreen.kt` and the service
    itself; `ACTION_REFRESH_NOTIFICATION` from `ui/VpnScreen.kt` â€” which the index notes is a chain whose
    only entry point is the unreferenced function.
  - *Process death*: none of these hold state across death; the fix is purely local.
  - *Config change*: all eleven sites are inside composables or `LaunchedEffect`s that re-run on
    recreation. A fix that shows a `Toast` must not fire repeatedly on rotation â€” attach it to the click
    handler, not to composition.
  - *Foreground/background*: all eleven run with a visible Activity, so `startActivity` background
    restrictions do not apply and `ActivityNotFoundException` is the live thrower.
    `MainActivity.kt:257` is the exception: it runs from `LaunchedEffect` at first composition, which on
    API 31+ can still race a background state.
  - *Cross-process*: none. All eleven are default-process; the equivalent call from `:a11y`
    (`GrayzoneAccessibilityService.kt:93`) is already wrapped.
  - *Persistence*: none directly. Indirectly, a failed `stopVpnService()` (`:533`) leaves
    `PrefsKeys.VPN_ENABLED` and the tunnel state disagreeing, because the persisted flag is cleared by
    the service, which never receives the intent.
  - *Release-only*: none.
- **Fix** (minimal): wrap each call in a try/catch that logs with the intent action and shows the user a
  short `Toast`, copying the pattern already used at `OemBatteryHelper.kt:59-77` (catch, log with the
  throwable, return `false`) and `SharedComponents.kt:203-212` (catch, log, Toast). For the five
  settings-button sites, a single shared `ui/` helper â€”
  `fun Context.startSettingsIntentSafely(intent: Intent, what: String)` â€” removes the duplication and
  gives one place to fix. For `MainActivity.kt:257`, wrap and log
  `GrayzoneLogger.e(LogComponent.OVERLAY, "Failed to start OverlayService from MainScreen", e)` so a
  refused start is diagnosable rather than fatal.
- **Rewrite justification**: not a rewrite. The shared helper is additive; each call site changes from
  `context.startActivity(x)` to `context.startSettingsIntentSafely(x, "...")` with no behavioural change
  on the success path.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/ui/StartSettingsIntentSafelyTest.kt`, driving the extracted helper
  with a launcher lambda that throws `ActivityNotFoundException` and asserting it returns `false` and does
  not propagate, and returns `true` on success. Fails before the fix (no helper; the exception escapes),
  passes after. Requires `testOptions { unitTests.isReturnDefaultValues = true }` in
  `app/build.gradle.kts` if the helper logs via `GrayzoneLogger` (cross-cutting constraint 3).
- **Existing test conflict**: none. No UI file has any test coverage today (`app/src/androidTest/` holds
  only the three Room suites).
- **Verification**: `confirmed` (evidence tier T4) that all eleven sites are unguarded â€” verified by
  direct read and by an exhaustive search for `startActivity(`, `startForegroundService(`,
  `startService(`, `VpnService.prepare(` across `app/src/main/java/com/grayzone/app`, which returns
  exactly these eleven unwrapped sites plus the wrapped ones enumerated above. The crash *outcome* is
  needs-verification: it depends on a device or image that actually lacks the target activities
  (procedure in steps 1â€“6).
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: III (IPC boundaries with no explicit failure handling)
- **Affected surfaces**: `ui/VpnScreen.kt:100` (`vpnPermLauncher.launch(permIntent)`) is a twelfth
  unguarded launch of the same intent through a different API and should be fixed in the same pass; it is
  not counted in the eleven because it is an `ActivityResultLauncher`, not one of the three
  `Context` APIs. `service/BootReceiver.kt:61` is the same construct but is filed separately as F-212
  because its fix and consequence differ.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-212 â€” An unguarded `VpnService.prepare()` inside `BootReceiver` can abort the whole boot-time protection restore  [High]

- **Review unit**: RU-04 (`service`)
- **Location**: `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` â†’ `BootReceiver.onReceive()`
  (line 61)
- **Issue**: `android.net.VpnService.prepare(context)` is called with no protection inside a
  `BroadcastReceiver`. Everything around it in the same function is guarded: the `OverlayService` start at
  `:40` is wrapped with a logged catch at `:42`, and the VPN start at `:69` is wrapped at `:74` with a
  catch whose message explicitly records that the restore intent is preserved. `prepare()` sits between
  them, unguarded. A throw there escapes `onReceive`, which for a receiver means the broadcast is
  abandoned mid-way: the VPN is never restarted, `VpnHoldExpiryScheduler` is never re-armed, and the boot
  restore path stops without a log. Because this happens at boot with no UI, the user's only signal is
  that protection quietly did not come back â€” the exact failure class the FR-006 calibration ranks
  hardest. This differs from F-211's sites in both consequence (silent protection loss versus a visible
  crash) and required fix (the intent must be preserved so a later launch retries, matching the
  deliberate behaviour of the `:74` catch).
- **Trigger condition**:
  1. Enable the VPN so `PrefsKeys.VPN_ENABLED` is `true` and a hold is recorded.
  2. Reboot the device, or simulate:
     `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.grayzone.app`.
  3. Make `prepare()` fail â€” disable `com.android.vpndialogs`
     (`adb shell pm disable-user --user 0 com.android.vpndialogs`) or run under a restricted user profile
     where VPN creation is disallowed by device policy.
  4. Observe: `adb shell dumpsys activity services com.grayzone.app` shows no `AdBlockVpnService`, and
     `adb logcat` shows neither the `:74` "Failed to start AdBlockVpnService; restore intent preserved"
     line nor any other Grayzone line.
  5. Confirm the hold alarm was not re-armed: `adb shell dumpsys alarm | findstr grayzone`.
- **Blast radius**:
  - *Callers*: per the reference index, `BootReceiver` is registered in `AndroidManifest.xml` for boot and
    package-replaced, and referenced from `MainActivity.kt` and `service/vpn/AdBlockVpnService.kt`;
    `onReceive()` is invoked by the Android framework and "restarts `OverlayService`,
    `AdBlockVpnService`, re-arms `VpnHoldExpiryScheduler`". Downstream in the same function:
    `VpnHoldLockStore.read(context)` (index: referenced from `AdBlockVpnService.kt`,
    `VpnHoldExpiryScheduler.kt`, `BootReceiver.kt`), `VpnHoldLock.State.isTracked` (index:
    `MainActivity.kt`, `VpnHoldLockStore.kt`, `AdBlockVpnService.kt`, `VpnHoldExpiryScheduler.kt`,
    `BootReceiver.kt`), and `VpnHoldExpiryScheduler.rearmFromPrefs()` (index: `MainActivity.kt` 2 sites,
    `AdBlockVpnService.kt`, `BootReceiver.kt`).
  - *Process death*: this path runs in a freshly created process at boot, so nothing in memory survives to
    compensate; a fix cannot rely on prior state.
  - *Config change*: not applicable to a receiver.
  - *Foreground/background*: strictly background with no UI. A user-visible error is impossible here, so
    the failure must at minimum be logged and the persisted intent left intact for a later retry.
  - *Cross-process*: none â€” `BootReceiver` is default-process.
  - *Persistence*: yes. `PrefsKeys.VPN_ENABLED` and the `VpnHoldLockStore` timestamps stay set while the
    tunnel does not run, so persisted state and reality diverge until the next app launch (where
    `reconcileVpnState()` â€” itself covered by F-208/F-210 â€” is the only recovery).
  - *Release-only*: none.
- **Fix** (minimal): wrap `:61` in a try/catch that logs
  `GrayzoneLogger.e(LogComponent.BOOT, "VpnService.prepare failed during boot restore; VPN intent preserved", e)`
  and falls through to the rest of `onReceive` without clearing any persisted flag â€” matching the
  intent-preserving behaviour the `:74` catch already documents.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/BootRestoreFailureTest.kt`, asserting that a throwing
  `prepare()` still leaves `PrefsKeys.VPN_ENABLED` set and does not abort the remainder of `onReceive`.
  **Cannot be executed here** (no device). Note the reference index records `BootReceiver` as having **no
  test coverage** of any kind today, which is why this is worth adding rather than extending.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T4 â€” the unguarded call and both neighbouring
  guarded calls read directly)
  - *Missing*: a device on which `VpnService.prepare()` can be made to throw at boot.
  - *Procedure*: steps 1â€“5 above.
  - *Severity effect*: confirmed abort of the boot restore â†’ **Critical** under the FR-006 calibration
    (protection silently absent after reboot, no crash the user sees, no log). If `prepare()` proves
    non-throwing on all reachable configurations â†’ **Low**, as the finding reduces to an unguarded
    boundary with no demonstrated trigger.
- **Build scope**: everyday-build
- **Constitution**: III (IPC boundary with no explicit failure handling)
- **Affected surfaces**: none. The two neighbouring calls in the same function (`:40`, `:69`) are already
  compliant and are credited below.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-213 â€” `StreakEvaluator.run` is launched unguarded from Home's composition while its other two callers both wrap it  [High]

- **Review unit**: RU-07 (`ui` + `ui.theme`)
- **Location**: `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()`, the `ON_RESUME`
  observer (lines 56â€“58)
- **Issue**: `streakScope.launch { StreakEvaluator.run(context) }` runs inside a
  `rememberCoroutineScope()` with no `try`, no `CoroutineExceptionHandler`, and no supervision.
  `StreakEvaluator.run` reaches two throwing boundaries with no local protection of its own:
  `UsageDatabase.getInstance(...).usageDao()` and `dao.getDailySummary(dateKey)`
  (`StreakEvaluator.kt:27`, `:32`) â€” and `UsageDatabase.getInstance` builds a Room database whose
  `fallbackToDestructiveMigration` was deliberately removed in favour of `MIGRATION_1_2`
  (`UsageDatabase.kt:34-47`), so a corrupt database file or a failed migration throws to whoever touched
  it first â€” plus unguarded preference reads at `StreakEvaluator.kt:18-23`. A `rememberCoroutineScope`
  job that throws propagates to the default handler and takes `MainActivity` down, on **every** resume of
  the Home tab, producing a loop the user cannot escape without clearing data â€” which in this app means
  escaping their own locks.
  The evidence that this is an oversight is the inconsistency itself: the reference index records exactly
  three callers of `StreakEvaluator.run()`, and the other two both wrap it â€”
  `GrayzoneApplication.catchUpStreaks()` (`GrayzoneApplication.kt:163-167`, logged catch) and
  `OverlayService` (`OverlayService.kt:288-292`, logged catch). One of three callers omitting the guard is
  not a design decision.
- **Trigger condition**:
  1. Install and use the app so the Room database exists and Home is reachable.
  2. Corrupt the database:
     `adb shell run-as com.grayzone.app` then overwrite the first bytes of
     `databases/usage_database` (or `adb shell run-as com.grayzone.app rm databases/usage_database-wal`
     mid-write) so the next open fails.
  3. Launch the app and open the Home tab.
  4. Background and foreground the app to fire `ON_RESUME`.
  5. Observe the crash in `adb logcat -b crash`, with the trace passing through
     `StreakEvaluator.run`, and confirm no Grayzone log line precedes it.
  6. Repeat step 4 to confirm the crash recurs on every resume.
- **Blast radius**:
  - *Callers*: per the reference index, `StreakEvaluator.run()` has three callers â€”
    `GrayzoneApplication.kt` (`onCreate()`), `service/OverlayService.kt`, and `ui/HomeScreen.kt` â€”
    described there as "three independent triggers for the same evaluation". `HomeScreen()` is composed by
    `MainScreen()` (`Tab.HOME`). Inside the evaluation: `UsageDatabase.getInstance()` (index:
    `service/OverlayService.kt`, `data/CleanupWorker.kt`, `data/StreakEvaluator.kt`),
    `UsageDao.getDailySummary()` (index: `data/StreakEvaluator.kt`), and
    `StreakManager.evaluatePendingDays()` (index: `data/StreakEvaluator.kt`, 2 sites).
  - *Process death*: this finding produces process death. Because the trigger is persisted (a corrupt
    database file), the crash survives every restart.
  - *Config change*: `rememberCoroutineScope()` is cancelled and recreated across recomposition, but the
    `ON_RESUME` observer re-fires after recreation, so rotation does not clear the loop â€” it re-enters it.
  - *Foreground/background*: the launch is bound to `ON_RESUME`, so the crash lands exactly when the user
    returns to the app.
  - *Cross-process*: none directly. Worth noting that `UsageDatabase` lacks
    `enableMultiInstanceInvalidation()` (`UsageDatabase.kt:34-47`) â€” a latent hazard tracked in
    `evidence/cross-process-inventory.md`, not part of this finding.
  - *Persistence*: read-only within this path, but the failure originates in persisted Room state and in
    the unguarded preference reads at `StreakEvaluator.kt:18-23`.
  - *Release-only*: none.
- **Fix** (minimal): make the third caller match the other two â€”
  `streakScope.launch { try { StreakEvaluator.run(context) } catch (e: Exception) { GrayzoneLogger.w(LogComponent.DATABASE, "Streak evaluation from Home failed: ${e.message}", e) } }`.
  One caller, three lines, and the inconsistency disappears. The stronger form (moving the guard inside
  `StreakEvaluator.run` so no caller can omit it) is preferable but changes behaviour for the other two
  callers, so it is out of scope for a minimal fix and belongs to T020.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StreakEvaluatorGuardTest.kt`, asserting that a throwing
  evaluation is contained rather than propagated. This requires `StreakEvaluator.run`'s database access
  to be reachable through a seam; if no seam is added, the honest alternative is `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/HomeStreakFailureTest.kt` against a deliberately corrupt
  database, which **cannot be executed here** (no device).
- **Existing test conflict**: none. `StreakManagerTest.kt` covers `evaluatePendingDays` and
  `checkDailyStreakForDate` as pure logic over `FakeSharedPreferences` and does not touch Room.
- **Verification**: `confirmed` (evidence tier T4) for the inconsistency, which is the finding's core
  evidence: `HomeScreen.kt:56-58` has no guard, `GrayzoneApplication.kt:163-167` and
  `OverlayService.kt:288-292` both do, and the reference index confirms those are the only three callers.
  The crash *outcome* is needs-verification per steps 1â€“6.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: III (database/async boundary with no explicit failure handling)
- **Affected surfaces**: `data/StreakEvaluator.kt:18-23` (prefs reads) and `:27`, `:32` (Room access) are
  the unprotected boundaries this reaches; they are RU-02 and belong to T020. `ui/HomeScreen.kt:45-55`
  (monitored-count prefs reads in the same composable) is the same exposure class and is covered by the
  UI prefs-read sweep (T026).
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Medium

### F-214 â€” Three empty catches around `socket.close()` leak UDP file descriptors on the exact path an error already took  [Medium]

> **ðŸš« WITHDRAWN 2026-07-31 by the lead auditor â€” superseded by F-031 in
> [`findings-ru06.md`](./findings-ru06.md).**
>
> **Disposition**: `withdrawn`. This finding was filed conditionally, as its own note below explains,
> because `findings-ru06.md` did not exist when T030 ran. T019 has since filed the same three
> `DnsResolverPool.kt:107,112,123` sites as **F-031**, and RU-06 is the owning review unit. F-031 is the
> record of authority; only one of the two may survive, per the disposition state machine in
> `data-model.md`.
>
> The analysis below is retained for traceability, **not** for action. Do not fix from this record, do
> not count it in violation totals, and do not schedule it in a fix tier. One caveat carried forward
> into F-031: the note in the RU-10 section below â€” that `DnsResolverPoolTest.kt`'s two existing tests
> will break when the logging fix lands unless the `testOptions { unitTests.isReturnDefaultValues = true }`
> change ships with it â€” remains valid and must travel with F-031.

- **Review unit**: RU-06 (`service.vpn`)
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/DnsResolverPool.kt` â†’
  `DnsResolverPool.releaseSocket()` (lines 105â€“117; catches at 107 and 112) and `DnsResolverPool.shutdown()`
  (lines 119â€“127; catch at 123)
- **Issue**: Three literally empty catch bodies â€” `try { socket.close() } catch (_: Exception) {}` at
  `:107` and `:112`, and `try { it.close() } catch (e: Exception) {}` at `:123`. An empty catch is
  explicitly prohibited by Principle III, and these are not on a harmless path: `:107` runs precisely when
  `reusable == false`, i.e. after `performQuery`'s catch at `:67` has already marked the socket
  as damaged, so it is the failure path swallowing its own follow-on failure. The consequence is leaked
  UDP file descriptors. The pool is bounded at 20 concurrent permits (`:22`), which bounds concurrency but
  not the number of leaked descriptors, and leaked ones accumulate against the process limit. Once
  exhausted, `DatagramSocket()` fails at `:92`, `acquireSocket()` returns `null`, `performQuery` returns
  `null` for every query, and the VPN reports "All DNS servers failed" while the notification and the VPN
  screen both still claim filtering is active â€” DNS resolution stops for the whole device while the app
  says protection is healthy. `:123` additionally binds `e` and never uses it, which is a Principle I
  unused-symbol violation in the same three lines.
- **Trigger condition**:
  1. Enable the VPN and confirm DNS traffic appears in the live feed on the VPN tab.
  2. Drive sustained query failures so the `reusable = false` path at `DnsResolverPool.kt:70` is taken
     repeatedly â€” toggle airplane mode on and off in a loop, or block outbound UDP/53 at the network while
     an app generates DNS load.
  3. Sample the process's open descriptors over several minutes:
     `adb shell ls -l /proc/$(adb shell pidof com.grayzone.app)/fd | find /c "socket"` repeatedly.
  4. Observe whether the socket count grows monotonically rather than plateauing near the 20-permit bound.
  5. If it grows, continue until resolution fails and confirm the notification still reads "Grayzone
     Protection Active".
- **Blast radius**:
  - *Callers*: per the reference index, `DnsResolverPool` is referenced only from
    `service/vpn/AdBlockVpnService.kt` (2 sites); `performQuery()` from `AdBlockVpnService.kt`;
    `shutdown()` from `AdBlockVpnService.kt` (3 sites). `releaseSocket()` and `acquireSocket()` are
    private and called only from `performQuery()` (`:43`, `:77`). The pool instance is created in
    `AdBlockVpnService.createResolverPool()` (`:115-120`) and recreated in `startVpn()` (`:211-213`).
  - *Process death*: descriptors are released when the process dies, so a leak is bounded by process
    lifetime â€” but this is a long-lived foreground VPN service designed to run for 24-hour holds, which is
    exactly the lifetime in which a slow leak matters.
  - *Config change*: none.
  - *Foreground/background*: the pool runs entirely inside the VPN service with no UI dependency; the
    symptom (DNS stops resolving) is device-wide and appears in other apps first.
  - *Cross-process*: none. `AdBlockVpnService` is default-process (`AndroidManifest.xml:121-132`), so the
    pool shares a process with the UI that reports its health.
  - *Persistence*: none.
  - *Release-only*: none; but see cross-cutting constraint 1 â€” a `d()`-level log here would be invisible
    in release, so the reason must be logged at `w()` on the `reusable == false` path.
- **Fix** (minimal): add one private helper in the same file and use it at all three sites â€”
  `private fun closeQuietly(socket: DatagramSocket, reason: String) { try { socket.close() } catch (e: Exception) { GrayzoneLogger.w(LogComponent.DNS, "Failed to close DNS socket ($reason)", e) } }`
  â€” called with `"not reusable"` at `:107`, `"pool shut down"` at `:112`, and `"shutdown sweep"` at
  `:123`. That satisfies Principle III at all three sites with one recorded reason each and removes the
  unused `e` binding at `:123`.
- **Rewrite justification**: not a rewrite. The helper is six lines added to the existing class; the
  pooling logic, the semaphore, and the `isShutdown` protocol are untouched.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` (extend the existing class),
  adding `a socket whose close throws does not propagate out of shutdown`, using a `DatagramSocket`
  subclass that overrides `close()` to throw. Fails before the fix only if the empty catches are removed;
  its real value is as a regression guard that the new `closeQuietly` still contains the throw. Asserting
  the *log* additionally requires `testOptions { unitTests.isReturnDefaultValues = true }` in
  `app/build.gradle.kts` (cross-cutting constraint 3), because `GrayzoneLogger.w` calls the unmocked
  `android.util.Log.w`.
- **Existing test conflict**: `DnsResolverPoolTest.kt`'s two existing tests pass today because their
  queries time out without throwing, so no logging path is entered. Adding a `GrayzoneLogger` call to
  `releaseSocket()` puts a logging call on the path both tests execute â€” **both will begin failing with
  `Method w in android.util.Log not mocked` unless the `testOptions` line is added in the same change.**
  This is a hard prerequisite, not a nicety.
- **Verification**: `confirmed` (evidence tier T4) that all three catches are empty and that `:123`
  binds an unused `e` â€” read directly at `DnsResolverPool.kt:105-127`. The descriptor-exhaustion
  *consequence* is `needs-verification`:
  - *Missing*: a device on which sustained resolver failures can be driven and `/proc/<pid>/fd` sampled.
  - *Procedure*: steps 1â€“5 above.
  - *Severity effect*: confirmed monotonic descriptor growth ending in total resolution failure â†’
    **High**, because DNS stops working device-wide while the app reports protection active. If closes on
    an already-damaged `DatagramSocket` prove never to throw in practice â†’ **Low**, leaving three
    constitution-literal empty catches plus the unused binding.
- **Build scope**: everyday-build
- **Constitution**: III (empty catch, explicitly prohibited) at all three sites; I (unused `e` binding) at
  `:123`
- **Affected surfaces**: `DnsResolverPool.kt:67` is a related `logged-bare` catch ("DNS query failed:
  ${e.message}" with no server and no domain) recorded by the inventory as *compliant with a note* â€” it is
  not part of this finding, but the same pass could add the server IP the caller already knows
  (`AdBlockVpnService.kt:563-566`). `DnsResolverPool.kt:93`'s `protectSocket(socket)` invocation is
  guarded locally but its lambda swallows at `AdBlockVpnService.kt:119` â€” that is C2, owned by T019.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-215 â€” The custom-prompts editor silently replaces corrupt JSON with an empty list and then writes it back, destroying the user's prompts  [Medium]

- **Review unit**: RU-07 (`ui` + `ui.theme`)
- **Location**: `app/src/main/java/com/grayzone/app/ui/CustomPromptsSheet.kt` â†’ `CustomPromptsSheet()`,
  the `initialPrompts` initialiser (lines 38â€“48, catch at line 44) and `savePrompts()` (lines 53â€“56)
- **Issue**: `catch (e: Exception) { emptyList() }` â€” `e` is bound and never used, so a
  `JsonSyntaxException` on `PrefsKeys.CUSTOM_PROMPTS_JSON` produces an empty list with nothing logged. The
  data loss follows two lines later: `prompts` is seeded from that empty list (`:50`), the sheet renders
  "No custom prompts added yet." (`:141`), and the user's first add (`:125`) or delete (`:157`) calls
  `savePrompts`, which does
  `prefs.edit().putString(CUSTOM_PROMPTS_JSON, gson.toJson(newList)).apply()` (`:55`) â€” overwriting the
  stored JSON with the truncated list. Every custom reflection prompt the user wrote is gone, permanently,
  with no message. The decisive evidence that this is a defect rather than a policy is that the **same
  preference key is parsed correctly elsewhere in the same app**: `OverlayService.kt:851-860` wraps the
  identical `fromJson<List<String>>` call, logs "Failed to parse custom prompts JSON; falling back to
  defaults", and degrades to the default pool without writing anything back. Two readers of one key
  disagree, and the destructive one is the one with a write path.
- **Trigger condition**:
  1. Open Settings â†’ Custom Prompts and add two or three prompts.
  2. Corrupt the stored value the way a schema drift across an upgrade would:
     `adb shell run-as com.grayzone.app` and edit `shared_prefs/GrayzonePrefs.xml`, replacing the
     `custom_prompts_json` value with `[{"a":1}` (malformed).
  3. Force-stop and relaunch, then open Settings â†’ Custom Prompts.
  4. Observe the sheet shows "No custom prompts added yet." with no error.
  5. Add one new prompt.
  6. Re-read `shared_prefs/GrayzonePrefs.xml` and confirm the original prompts are gone and only the new
     one remains. Confirm `adb logcat` shows no Grayzone line for the parse failure.
- **Blast radius**:
  - *Callers*: per the reference index, `CustomPromptsSheet()` is composed by `SettingsScreen()`, which is
    composed by `MainScreen()` (`Tab.SETTINGS`). The preference key `PrefsKeys.CUSTOM_PROMPTS_JSON` has
    exactly two referencing files, `ui/CustomPromptsSheet.kt` and `service/OverlayService.kt`;
    `PrefsKeys.USE_CUSTOM_PROMPTS_ONLY` the same two. The other consumer of the parsed value is
    `Prompts.resolvePool()` (index: referenced from `service/OverlayService.kt`), with `Prompts.DEFAULT`
    referenced from `ui/CustomPromptsSheet.kt`, `service/OverlayService.kt`, and `resolvePool()` itself.
  - *Process death*: not required for the defect; the loss is committed by a normal user edit.
  - *Config change*: the parse runs inside `remember { }` (`:38`), so it re-executes when the composable
    is recreated after a configuration change â€” each recreation re-derives the empty list, and any edit
    after any recreation triggers the destructive write.
  - *Foreground/background*: foreground only, inside a modal bottom sheet.
  - *Cross-process*: none.
  - *Persistence*: central. The write at `:55` is the data-loss step, and it is a plain `apply()` on
    `SharedPreferences`; once flushed, the previous value is unrecoverable except from
    `/sdcard/.grayzone/backup.dat` if a snapshot predates the overwrite.
  - *Release-only*: `gson.fromJson<List<String>>` relies on `TypeToken` reflection, which
    `app/proguard-rules.pro:7` protects with `-keepattributes Signature`; the parse itself is therefore
    release-safe. No divergence.
- **Fix** (minimal): copy the compliant sibling. Log
  `GrayzoneLogger.w(LogComponent.OVERLAY, "Failed to parse ${PrefsKeys.CUSTOM_PROMPTS_JSON} (len=${json.length}); prompt editor opened empty", e)`
  in the catch at `:44`, and record that the parse failed so `savePrompts` refuses to write until the user
  has explicitly acted on a successfully parsed list â€” the smallest form is a
  `var parseFailed by remember { mutableStateOf(false) }` set in the catch and checked at the top of
  `savePrompts` (`:53`), returning early so the corrupt value is preserved rather than replaced.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/PromptResolutionTest.kt` (extend the existing class), adding
  `malformed custom prompts json is reported and not overwritten`, driven through an extracted pure
  function `parseCustomPrompts(json: String?): List<String>?` returning `null` on failure, over
  `FakeSharedPreferences`. Fails before the fix (parse failure is indistinguishable from "no prompts", so
  the write proceeds), passes after (`null` blocks the write).
- **Existing test conflict**: none. `PromptResolutionTest.kt` currently exercises `Prompts.resolvePool()`
  only and does not touch the editor's parse path.
- **Verification**: `confirmed` (evidence tier T4). No device is needed: the catch returning `emptyList()`
  (`:44`), the seeding of `prompts` from it (`:50`), and the unconditional overwrite in `savePrompts`
  (`:55`) reached from the add (`:125`) and delete (`:157`) handlers are all visible in one file, and the
  compliant counter-example at `OverlayService.kt:851-860` establishes that the app already knows the
  correct shape.
- **Build scope**: everyday-build
- **Constitution**: III (failure masked by returning a default with no recorded reason)
- **Affected surfaces**: `ui/CustomPromptsSheet.kt:55` also has an unguarded `gson.toJson(newList)` whose
  failure would propagate into a Compose click handler and crash the sheet mid-edit; it shares this file
  and should be handled in the same pass. The equivalent overwrite-on-corrupt pattern at
  `data/StreakManager.kt:148` (RU-02) is the same defect on a different key and is owned by T020.
- **Observed against**: `557a963`
- **Disposition**: **withdrawn â€” consolidated into F-148** (`findings-ru07.md`), which owns the same
  defect at **High** (data-loss + silent empty list). Medium here understated the overwrite half.
  T031 Sweep log.

---

### F-216 â€” Any `PackageManager` failure is read as "app not installed", so default monitored apps can be permanently skipped  [Medium]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `GrayzoneApplication.seedDefaultMonitoredAppsIfNeeded()` (lines 232â€“266, catch at line 249)
- **Issue**: The `filter` at `:237-252` calls `packageManager.getPackageInfo(pkg, â€¦)` and treats
  **every** exception as "not installed": `catch (_: Exception) { false }`. The expected exception is
  `PackageManager.NameNotFoundException`, and catching it is correct. But the same clause also absorbs
  `TransactionTooLargeException`, `DeadObjectException`, and `RuntimeException` from a stressed package
  manager â€” all realistic during cold start, which is exactly when this runs (`appScope` launch from
  `runDeferredStartupWork()`, `:148-154`). A transient binder failure is then indistinguishable from a
  genuinely absent app. The consequence is not transient: `DefaultMonitoredApps.seedIfNeeded` is a
  one-shot, and on a *partial* failure it still writes `PrefsKeys.DEFAULT_APPS_SEEDED = true` at `:260`,
  so a first-time user who hit a binder hiccup gets an app that monitors nothing and never re-seeds. Only a
  *total* failure leaves the retry open, because `seedIfNeeded` returns `null` and the early return at
  `:257` skips the write â€” which means the more common partial failure is the more damaging one.
- **Trigger condition**:
  1. Prepare a device with at least one of `DefaultMonitoredApps.DEFAULT_PACKAGES` installed.
  2. Clear app data so the seeding runs fresh: `adb shell pm clear com.grayzone.app`.
  3. Put the package manager under load during the cold start â€” launch a package-heavy operation in
     parallel (e.g. `adb shell pm list packages -f` in a tight loop) and start the app.
  4. Open the Apps tab and check whether the default packages are monitored.
  5. Read the flag: `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml | findstr default_apps_seeded`.
  6. If the flag is `true` while a default package is installed and unmonitored, the miss is permanent â€”
     confirm by relaunching and re-checking.
- **Blast radius**:
  - *Callers*: `seedDefaultMonitoredAppsIfNeeded()` is private with one caller,
    `runDeferredStartupWork()` (`:150`), itself called only from `onCreate()` (`:72`) â€” which the reference
    index records as calling `DefaultMonitoredApps.seedIfNeeded` among others. `DefaultMonitoredApps`
    (object) and `seedIfNeeded()` are referenced only from `GrayzoneApplication.kt`; `DEFAULT_PACKAGES`
    from `GrayzoneApplication.kt` and `seedIfNeeded()`. `PrefsKeys.DEFAULT_APPS_SEEDED` is referenced from
    `GrayzoneApplication.kt` and `data/DefaultMonitoredApps.kt`. The value written,
    `PrefsKeys.MONITORED_APPS`, is read by `GrayzoneApplication.kt`, `data/RuntimeSessionReset.kt`,
    `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/HomeScreen.kt`,
    `data/StreakEvaluator.kt`, `ui/LimitsScreen.kt` â€” so a wrong seed propagates to every screen and to the
    service that enforces sessions.
  - *Process death*: the work runs on `appScope`, which per F-206 has no exception handler; a non-swallowed
    throw here would currently kill the process. That interaction is why the fix must log rather than
    rethrow.
  - *Config change*: none.
  - *Foreground/background*: runs during `Application.onCreate` on an IO dispatcher, before any UI exists,
    so no user-visible signal is possible â€” a log is the only available channel.
  - *Cross-process*: none. `onCreate()` returns at `:54` for `:a11y`, so this never runs there.
  - *Persistence*: yes, and the persistence is what makes it permanent â€”
    `putStringSet(MONITORED_APPS, seeded).putBoolean(DEFAULT_APPS_SEEDED, true).apply()` at `:258-261`.
  - *Release-only*: none.
- **Fix** (minimal): split the clause â€” keep `catch (_: PackageManager.NameNotFoundException) { false }`
  for the expected case, and add
  `catch (e: Exception) { GrayzoneLogger.w(LogComponent.BOOT, "getPackageInfo failed for $pkg; treating as not installed", e); false }`
  for everything else. That is the whole Principle III fix. Making the seed retry after a non-`NotFound`
  failure (by skipping the `DEFAULT_APPS_SEEDED` write when any lookup failed for an unexpected reason) is
  the behavioural half and should be part of the same change, since logging alone still leaves the miss
  permanent.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/DefaultMonitoredAppsTest.kt` (extend the existing class),
  adding `seeding is not marked complete when a package lookup failed for an unexpected reason` by giving
  `seedIfNeeded` an explicit "lookups were reliable" input. Fails before the fix (the caller cannot express
  the distinction), passes after. The `getPackageInfo` call itself needs a device or a Mockito-stubbed
  `PackageManager`; Mockito 5.7.0 is already a test dependency (`app/build.gradle.kts:151`).
- **Existing test conflict**: none. `DefaultMonitoredAppsTest.kt` exercises the pure `seedIfNeeded`
  contract and continues to pass; adding a parameter requires updating its existing call sites, which is a
  mechanical change to that file.
- **Verification**: `confirmed` (evidence tier T4). The over-broad catch at `:249`, the one-shot flag write
  at `:260`, and the early return at `:257` that distinguishes total from partial failure are all read
  directly. Whether a binder failure occurs in practice is a frequency question, not a correctness one, and
  is covered by the procedure above.
- **Build scope**: everyday-build
- **Constitution**: III (failure masked by returning a default with no recorded reason)
- **Affected surfaces**: `AppUtils.kt:78-83` (`pm.queryIntentActivities` + `info.loadLabel`) is the same
  unguarded `PackageManager` exposure with a different consequence (app-list screens crash) and belongs to
  the RU-01 sweep. `Extensions.kt:10-12` is the **compliant** counter-example: it catches around
  `getApplicationLabel` and falls back to the package name with the fallback documented in its KDoc â€” do
  not "fix" it.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-217 â€” Two catch clauses were widened in the uncommitted diff, folding programming errors and `OutOfMemoryError` into ordinary retry paths  [Medium]

- **Review unit**: RU-06 (`service.vpn`)
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’
  `AdBlockVpnService.runVpnLoop()` (catch at line 379, widened from `IOException` to `Exception`); and
  `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` â†’ `BlocklistManager.load()`
  (catch at line 151, widened from `Exception` to `Throwable`)
- **Issue**: Both widenings are uncommitted working-tree changes against `557a963`
  (`evidence/prior-fix-watchlist.md`, elevated-attention table). Neither is silent â€” both log â€” so
  neither is a swallowed exception. The Principle III concern is the second requirement: a catch must
  "handle the error meaningfully", and routing a class of error into a recovery designed for a different
  class is not meaningful handling.
  At `AdBlockVpnService.kt:379`, the packet loop's recovery is a consecutive-error counter, a 5-second
  non-consecutive reset window, and an escalation to a full service restart at five strikes
  (`:379-407`). That is correct policy for transient `IOException`s on a tun descriptor. Widened to
  `Exception`, a deterministic programming error â€” a `NullPointerException` or an
  `IndexOutOfBoundsException` in `DnsPacketHelper` on a malformed packet â€” now enters the same path: it
  recurs on every iteration, trips the 5-strike escalation, restarts the service, and recurs again. A
  reproducible bug becomes an invisible restart loop that reads in the log as a flaky network.
  At `BlocklistManager.kt:151`, `catch (e: Throwable)` now also catches `Error`, and the operation it
  wraps is the load of roughly 10 MB of Bloom filters from `res/raw` (`:147-149`). An
  `OutOfMemoryError` is therefore caught, state is reset, `loadStarted.set(false)` at `:156` re-arms the
  retry â€” and the next call reattempts the identical allocation on a device that just proved it cannot
  satisfy it. There is no counter and no backoff, and the load runs on a raw `Thread` (`:145`) with no
  handle, join, or interrupt, so nothing can stop the cycle. The reset is coherent and it does log at
  `:157`, which makes it defensible â€” but `catch (Throwable)` needs the written justification the
  constitution's suppression rule expects, and it currently has none.
- **Trigger condition**:
  1. Confirm the widened clauses are present in the working tree:
     `git diff 557a963 -- app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt`
     and look for `catch (e: Exception)` at the packet loop and `catch (e: Throwable)` at the loader.
  2. For the packet loop: enable the VPN and feed it a malformed DNS packet that drives a bounds error in
     `DnsPacketHelper`, then watch `adb logcat` for repeated `VPN loop error #N` lines followed by
     "VPN failed 5 times consecutively, restarting service".
  3. Confirm the log gives no way to tell a network error from a code error at that point.
  4. For the loader: constrain memory â€” `adb shell am start --user 0 -n com.grayzone.app/.MainActivity`
     under `adb shell setprop dalvik.vm.heapgrowthlimit 32m` on a test device â€” enable the VPN, and watch
     for `Error loading bloom filters` with an `OutOfMemoryError` cause.
  5. Confirm the retry re-arms by triggering `BlocklistManager.load` again (stop and restart the VPN) and
     observing the identical allocation attempt.
- **Blast radius**:
  - *Callers*: `runVpnLoop()` is private and is the body of the `vpnThread` created at
    `AdBlockVpnService.kt:223`; the thread carries an `UncaughtExceptionHandler` (`:351-358`) that restarts
    the tunnel, so the widened catch is the *inner* of two nested recovery mechanisms â€” a fact the fix must
    preserve. Per the reference index, `BlocklistManager.load()` is referenced from
    `service/vpn/AdBlockVpnService.kt` (2 sites: `onCreate()` at `:110` and the restart path); the state it
    resets is consumed by `BlocklistManager.isBlocked()` and `isDoHBypass()`, both referenced from
    `service/vpn/DnsQueryClassifier.kt` (4 and 3 sites), which is itself referenced from
    `AdBlockVpnService.kt` (4 sites). `BlocklistManager.isLoaded` is a public flag with no external reader.
  - *Process death*: the `Throwable` catch at `BlocklistManager.kt:151` *prevents* a process death that
    would otherwise occur (an uncaught throwable on any thread is fatal on Android), which is the strongest
    argument for keeping it â€” but it converts that death into an unbounded retry, so the fix is to bound
    the retry, not to remove the catch.
  - *Config change*: none.
  - *Foreground/background*: both run inside the VPN foreground service with no UI; failures are
    log-only. `BlocklistManager` failing leaves `isLoaded = false`, so `isBlocked()` fails open and every
    domain is allowed while the notification still says protection is active.
  - *Cross-process*: none. Per the reference index, `BlocklistManager`'s object statics live per-process
    and only the VPN process loads them; `AdBlockVpnService` is default-process.
  - *Persistence*: none. Neither path writes preferences or Room.
  - *Release-only*: the ~10 MB `res/raw` load is unaffected by R8 (assets are not shrunk by
    `isShrinkResources` when referenced through `R.raw`), but a release build's obfuscated trace makes the
    already-ambiguous "VPN loop error" line even harder to attribute â€” see cross-cutting constraint 2.
- **Fix** (minimal): at `AdBlockVpnService.kt:379`, keep the broad clause but make the error class
  visible and stop treating a code error as a network error â€” log
  `e.javaClass.simpleName` in the message and, for a non-`IOException`, escalate immediately instead of
  consuming a strike (`if (e !is java.io.IOException) { GrayzoneLogger.e(LogComponent.VPN, "Non-IO error in VPN packet loop (${e.javaClass.simpleName}); restarting", e); â€¦ }`).
  At `BlocklistManager.kt:151`, keep `Throwable` but add the justification comment the widening lacks, and
  do not re-arm the retry after an `Error`: `if (e is Error) { /* leave loadStarted true */ }` so an OOM is
  not retried into the same allocation. Both are additive; neither narrows a clause that is currently
  protecting against a process kill.
- **Rewrite justification**: not a rewrite. Both changes are conditionals added inside existing catch
  bodies; the backoff/reset structures at `AdBlockVpnService.kt:379-407` and the state reset at
  `BlocklistManager.kt:152-157` are retained.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/vpn/BlocklistManagerTest.kt` (extend the existing class),
  adding `an Error during load does not re-arm the retry`, asserting `loadStarted` stays set after an
  injected `OutOfMemoryError` so the identical allocation is not reattempted. Fails before the fix
  (`:156` resets unconditionally), passes after. The packet-loop half is not unit-testable without a tun
  descriptor; its counterpart is `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnLoopErrorClassTest.kt`, which **cannot be
  executed here** (no device).
- **Existing test conflict**: none. `BlocklistManagerTest.kt` and `BlocklistDecisionTest.kt` exercise
  `load()` and the decision surface but do not assert on `loadStarted` re-arming.
- **Verification**: `confirmed` (evidence tier T4 for the code; T2-equivalent for the diff). Both widened
  clauses are visible in the working tree (`AdBlockVpnService.kt:379` catches `Exception`;
  `BlocklistManager.kt:151` catches `Throwable`) and both widenings are independently recorded in
  `evidence/prior-fix-watchlist.md`'s elevated-attention table as changes introduced by the uncommitted
  diff. The *behavioural* consequences (restart loop, OOM retry) are needs-verification per steps 2â€“5.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: III (a catch must handle the error meaningfully; routing a non-recoverable or
  programming error into a transient-failure recovery does not). The undocumented `Throwable` widening
  also engages the Code Quality Standards rule that broad suppressions require a written technical
  justification.
- **Affected surfaces**: `AdBlockVpnService.kt:415` is a third broad catch on the same file's outer loop,
  recorded by the inventory as compliant but silent when `isRunning == false`; `AdBlockVpnService.kt:228`
  wraps builder â†’ `establish()` â†’ prefs â†’ thread start in one clause. Both are RU-06 breadth flags for
  T019, not part of this finding.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt, BlocklistManager.kt)`
- **Disposition**: `verified`

---

## Low

### F-218 â€” Two comment-only catches on expected paths: harmless in effect, prohibited by construction  [Low]

- **Review unit**: RU-06 (`service.vpn`)
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’
  `AdBlockVpnService.applyAppBypassList()` (lines 264â€“271, catch at line 268); and
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` â†’
  `OverlayService.clearStaleSystemGrayscale()` (lines 774â€“784, catch at line 781)
- **Issue**: Both catches have a comment for a body and nothing else. `applyAppBypassList` catches
  `PackageManager.NameNotFoundException` when bypassing a package that is not installed â€” an expected,
  benign outcome, and the aggregate result *is* logged two lines later at `:272-275`.
  `clearStaleSystemGrayscale` catches `SecurityException` while clearing a stale system daltonizer
  setting, an operation its own KDoc at `:773` already documents as "Best-effort â€¦ No-op without grant",
  and the effect is purely cosmetic.
  **Say it plainly: the user impact of both is nil. The compliance problem is not.** Principle III states
  that "an empty or comment-only catch is prohibited" without qualification, and these are the two
  clearest instances of the prohibited construct in the codebase. The reason the rule has no
  benign-path exemption is visible elsewhere in this same audit: `MainActivity.kt:106`, `:169`, and `:261`
  (F-208â€“F-210) each carry a comment asserting the failure is harmless, and in all three cases the
  assertion is wrong. A codebase that permits the construct when the author believes it is harmless
  cannot distinguish these two cases from those three by inspection. Fixing them costs one line each and
  removes the pattern.
- **Trigger condition**:
  1. Confirm the construct without a device â€” read `AdBlockVpnService.kt:265-270` and
     `OverlayService.kt:775-783`; both catch bodies contain only a comment.
  2. Exercise the bypass path: enable the VPN on a device missing at least one of `DEFAULT_BYPASS_APPS`
     and observe `adb logcat` â€” the per-package skip produces no line, only the aggregate
     "VPN bypass applied to N installed payment/streaming apps".
  3. Exercise the grayscale path: start `OverlayService` without `WRITE_SECURE_SETTINGS`
     (the normal state) and observe that the `SecurityException` at `:781` produces no line at all.
- **Blast radius**:
  - *Callers*: `applyAppBypassList()` is private with one caller, `startVpn()`
    (`AdBlockVpnService.kt:202`); per the reference index `AdBlockVpnService` is started from
    `MainActivity.kt` (4 sites), `ui/VpnScreen.kt` (7 sites), `service/BootReceiver.kt` (6 sites), and
    `service/vpn/VpnHoldExpiryScheduler.kt` (2 sites). `clearStaleSystemGrayscale()` is private with one
    caller, `OverlayService.onCreate()` (`:148`); per the index `OverlayService` is started from
    `MainActivity.kt` (2 sites), `service/GrayzoneAccessibilityService.kt` (5 sites),
    `service/BootReceiver.kt` (4 sites), and `service/SessionExpiryReceiver.kt` (3 sites). The preference
    key behind the bypass list, `PrefsKeys.VPN_BYPASS_APPS`, has a single reader and no writer â€” a separate
    Medium lead already recorded in `findings.md` and owned by T019/T059/T025b.
  - *Process death*: none. Neither catch holds state.
  - *Config change*: none.
  - *Foreground/background*: both run during service startup with no UI; a log is the only channel.
  - *Cross-process*: `OverlayService.onCreate()` â€” and therefore `clearStaleSystemGrayscale()` â€” is
    reachable via a cross-process start from `:a11y`'s watchdog (`GrayzoneAccessibilityService.kt:93`), so
    the log will appear under the main-process copy of `GrayzoneLogger`, not the `:a11y` copy.
  - *Persistence*: `clearStaleSystemGrayscale()` writes `Settings.Secure` (a system, not app, store);
    the write is the operation being guarded and is a no-op without the grant.
  - *Release-only*: material here. A `GrayzoneLogger.d()` fix satisfies the constitution in source but is
    compiled out of behaviour in release (`GrayzoneLogger.kt:37-40`), so neither event is observable in a
    shipped build. That is an acceptable and deliberate outcome for two cosmetic paths, but it must be a
    stated choice â€” see cross-cutting constraint 1.
- **Fix** (minimal): one line in each body.
  `GrayzoneLogger.d(LogComponent.VPN, "Bypass skipped, package not installed: $pkg")` at
  `AdBlockVpnService.kt:269`, and
  `GrayzoneLogger.d(LogComponent.OVERLAY, "No WRITE_SECURE_SETTINGS grant; stale grayscale not cleared", e)`
  at `OverlayService.kt:782`. Keep the existing explanatory comments â€” they are the "recorded, intentional
  reason" the constitution asks for and should not be replaced by the log.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/service/vpn/BypassListTest.kt`, asserting that a
  `NameNotFoundException` for one package does not prevent the remaining packages from being applied and
  that `applied` reflects only the successes. **Cannot be executed here** (no device). No test is proposed
  for `clearStaleSystemGrayscale()`: it is a single guarded system-settings write with no observable
  output, and a test asserting only that a debug log fired would encode the logging call rather than any
  behaviour.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T4). Both bodies read directly; both contain only a comment.
  No runtime claim is made â€” the finding asserts the presence of a prohibited construct and explicitly
  asserts that its impact is cosmetic.
- **Build scope**: everyday-build
- **Constitution**: III (comment-only catch, explicitly prohibited) at both sites
- **Affected surfaces**: `service/OverlayService.kt:781` (RU-04) is the second site; it is grouped here
  rather than filed separately because the root cause and the fix are identical. The nine remaining
  `SILENT` catches in the codebase are all filed elsewhere: `MainActivity.kt:106/:169/:261` (F-209/F-208/
  F-210), `DnsResolverPool.kt:107/:112/:123` (F-214), `DnsPacketHelper.kt:107` (F-220), and
  `AdBlockVpnService.kt:119`/`:288` (C2/C4, T019).
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt, OverlayService.kt)`
- **Disposition**: `verified`

---

### F-219 â€” Three reads mask their failure by returning a default with no recorded reason  [Low]

- **Review unit**: RU-01 (`com.grayzone.app` root)
- **Location**: `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’ `getInstalledApps()` (lines 84â€“88,
  catch at line 86) and `isPrivateDnsActive()` (lines 154â€“165, catch at line 162);
  `app/src/main/java/com/grayzone/app/data/StreakManager.kt` â†’ `StreakManager.getAchievements()`
  (catch at line 134)
- **Issue**: Three instances of `catch (_: Exception) { <default> }` where the discarding `_` means no
  record of the failure survives â€” the specific construct Principle III's last sentence names ("Failures
  MUST NOT be masked by returning defaults without a recorded, intentional reason").
  `AppUtils.kt:86` returns `null` for a failed icon decode; the UI already renders a placeholder for a
  null icon (`ui/AppsScreen.kt:302-311`), so the visible consequence is a missing icon.
  `StreakManager.kt:134` returns `emptyMap()` for an unparseable `ACHIEVEMENTS_JSON` on the read-only
  display path (`ui/StreakCard.kt:33,40`), so badges appear locked until the next successful parse â€” and
  unlike its sibling at `:148`, this one does not write back, which is what keeps it Low.
  `AppUtils.kt:162` is Low for a different and less comfortable reason: it returns `false` from
  `isPrivateDnsActive()`, and reporting "Private DNS off" when it is actually on would tell the user DNS
  filtering works while it is being fully bypassed on port 853 â€” that is Medium behaviour. It is Low
  **only** because the reference index records `isPrivateDnsActive()` as **UNREFERENCED**, so no production
  caller exists. That same fact means the promise made in `BlocklistManager.kt:28-30` ("must be surfaced as
  a UI warning instead (see HomeScreen)") is unimplemented, and the function is dead code under Principle
  I. Its severity is contingent on staying dead.
  As with F-218: for the first two the impact is genuinely minor; the compliance failure is not, because a
  masked default is indistinguishable from a real result at every call site.
- **Trigger condition**:
  1. Icon path: install an app whose launcher icon fails to decode (a corrupted or adaptive-icon-only
     package on an older image), open the Apps tab, and observe a placeholder with no log line in
     `adb logcat`.
  2. Achievements path: `adb shell run-as com.grayzone.app` and set `achievements_json` in
     `shared_prefs/GrayzonePrefs.xml` to `{"a":` (malformed); relaunch and open Home. Observe all badges
     render locked, with no log line.
  3. Private DNS path: confirm statically that the function has no caller â€”
     `rg "isPrivateDnsActive" app/src` returns only its declaration at `AppUtils.kt:154`. No runtime step
     exists because no runtime path exists.
- **Blast radius**:
  - *Callers*: per the reference index â€” `getInstalledApps()` is called from `getInstalledAppsCached()`
    and `refreshInstalledApps()` within `AppUtils.kt`; `getInstalledAppsCached()` from `ui/AppsScreen.kt`
    and `ui/LimitsScreen.kt`; `refreshInstalledApps()` is itself **UNREFERENCED**; `drawableToBitmap()` is
    called only from `getInstalledApps()`. `isPrivateDnsActive()` â€” **UNREFERENCED** (zero main and zero
    test references). `StreakManager.getAchievements()` is called from `ui/StreakCard.kt` (2 sites), and
    `StreakManager` itself from `service/OverlayService.kt`, `data/StreakEvaluator.kt`, `ui/StreakCard.kt`.
  - *Process death*: none.
  - *Config change*: the icon cache (`AppMetadataCache`, `AppUtils.kt:21-47`) is a process-lifetime
    singleton with a 1-minute validity, so a failed icon is re-attempted after expiry rather than being
    cached forever â€” which is why the icon case does not escalate.
  - *Foreground/background*: all three are read paths reached from composition or from a suspend function
    on `Dispatchers.IO`; none run in the background without UI.
  - *Cross-process*: none. None of the three is reachable from `:a11y` (the index's `:a11y`-reachable set
    is 11 declarations, and none of these appear in it).
  - *Persistence*: read-only at all three sites. `StreakManager.kt:134` reads `ACHIEVEMENTS_JSON` and does
    **not** write back â€” the destructive sibling on the same key is `:148`, owned by T020.
  - *Release-only*: as with F-218, a `d()`-level fix is compiled out in release; the achievements case
    should therefore use `w()`, since a user losing visible badges is a real (if minor) support signal.
- **Fix** (minimal): record the reason at each site before returning the default.
  `AppUtils.kt:86` â†’ `catch (e: Exception) { GrayzoneLogger.d(LogComponent.BOOT, "Icon decode failed for $pkg", mapOf("error" to e.message)); null }`.
  `StreakManager.kt:134` â†’ `catch (e: Exception) { GrayzoneLogger.w(LogComponent.DATABASE, "Failed to parse ${PrefsKeys.ACHIEVEMENTS_JSON}; showing no achievements", e); emptyMap() }`.
  `AppUtils.kt:162` â†’ log the read failure the same way `isAccessibilityServiceEnabled()` already does at
  `AppUtils.kt:119-125` (the compliant sibling ten lines above it, which catches, logs with context, and
  degrades conservatively). Whether `isPrivateDnsActive()` should be wired into Home's status card or
  deleted is a Principle I / feature-gap decision owned by T021 and T059 â€” this finding only requires that
  the catch stop being silent, whichever way that decision goes.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StreakManagerTest.kt` (extend the existing class), adding
  `malformed achievements json yields no achievements and is reported`, driven over
  `FakeSharedPreferences` with an invalid `ACHIEVEMENTS_JSON`. Fails before the fix only in its reporting
  assertion; passes after. Requires `testOptions { unitTests.isReturnDefaultValues = true }` in
  `app/build.gradle.kts` (cross-cutting constraint 3), because `GrayzoneLogger.w` calls unmocked
  `android.util.Log.w`. No test is proposed for the two `AppUtils` sites: one needs a real
  `PackageManager` and the other has no caller to test through.
- **Existing test conflict**: `StreakManagerTest.kt` currently drives `getAchievements()` with
  well-formed or absent JSON, so it does not enter the catch and is unaffected by the log â€” **until** the
  new test above enters it, at which point the `testOptions` prerequisite applies to the whole class.
- **Verification**: `confirmed` (evidence tier T4) for all three constructs, read directly. The
  `UNREFERENCED` status of `isPrivateDnsActive()` is corroborated three ways: the reference index's
  unreferenced list, `evidence/r8.md`'s whole-class removal observation, and a direct search over
  `app/src`. No runtime claim is made.
- **Build scope**: everyday-build
- **Constitution**: III (failure masked by a default with no recorded reason) at all three sites; I
  additionally at `AppUtils.kt:154-165`, which is unreferenced dead code.
- **Affected surfaces**: `data/StreakManager.kt:134` is in RU-02 and is grouped here because the construct
  and the fix are identical to the two RU-01 sites; the destructive variant on the same key
  (`StreakManager.kt:148`) is a separate defect owned by T020. `Extensions.kt:11` and
  `MainActivity.kt:100` are **compliant** uses of the same shape â€” both have a documented fallback â€” and
  must not be "fixed".
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-220 â€” A DNS parse failure increments a counter that nothing in production ever reads  [Low]

- **Review unit**: RU-06 (`service.vpn`)
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/DnsPacketHelper.kt` â†’
  `DnsPacketHelper.getQuestionKey()` (lines 88â€“111, catch at line 107)
- **Issue**: The catch increments `domainParseErrors` (`:108`) and returns `null`, with no log. The counter
  is observable only through `getErrorStats()` (`:30-31`), which the reference index records as
  **TEST-ONLY** â€” its sole readers are `app/src/test/java/com/grayzone/app/DnsPacketTest.kt:195` and
  `app/src/test/java/com/grayzone/app/IntegrationTest.kt:110`. In a shipped build the failure is therefore
  invisible: the counter increments into a value with no reader, which is a swallowed exception wearing a
  metric's clothing. The functional impact is genuinely small â€” a null question key makes
  `forwardDnsQueryWithDeduplication` take the non-deduplicated path
  (`AdBlockVpnService.kt:496-500`), so the query still resolves, just without dedup. The finding is that
  the same file's six other catches all log with the throwable *and* name the exact operation and IP
  version ("Failed to wrap DNS response (IPv6)") on top of their counters (`:147`, `:164`, `:217`, `:295`,
  `:335`, `:385`) â€” so this one site is inconsistent with its own six siblings, not with an external
  standard.
- **Trigger condition**:
  1. Confirm the counter has no production reader: `rg "getErrorStats" app/src` returns the declaration at
     `DnsPacketHelper.kt:30` and two test files only.
  2. Enable the VPN and send a DNS query with a compressed or malformed question section so
     `getQuestionKey` throws â€” e.g. replay a captured packet with a pointer label in the question
     (`adb shell` + a UDP sender to the tunnel's DNS address).
  3. Observe `adb logcat`: the sibling failures at `:147` and beyond log; this one produces nothing.
  4. Confirm resolution still succeeds, establishing the impact is limited to lost dedup and lost
     observability.
- **Blast radius**:
  - *Callers*: per the reference index, `getQuestionKey()` is referenced from
    `service/vpn/AdBlockVpnService.kt`; `getErrorStats()` is **TEST-ONLY** (`DnsPacketTest.kt`,
    `IntegrationTest.kt`); `DnsQuestionKey` (the return type) is referenced from `AdBlockVpnService.kt`
    and twice within `DnsPacketHelper.kt`. `DnsPacketHelper` itself is referenced from
    `AdBlockVpnService.kt` (9 sites).
  - *Process death*: `domainParseErrors` is an in-memory `AtomicInteger` (`:17`) that resets to zero on
    every process start, so even the counter's value is not durable â€” a second reason it cannot serve as
    production telemetry.
  - *Config change*: none.
  - *Foreground/background*: runs on the VPN packet thread with no UI.
  - *Cross-process*: none. `AdBlockVpnService` and `DnsPacketHelper` are default-process; the counter is a
    per-process static, so a hypothetical second process would keep its own copy.
  - *Persistence*: none.
  - *Release-only*: decisive for this finding. `getErrorStats()` has no main-source reader, so R8 has no
    reason to keep it; `evidence/r8.md` records whole-class removal of similarly unreferenced surfaces.
    The counter therefore may not exist at all in a release build, making the failure not merely
    unobservable but unrepresented.
- **Fix** (minimal): mirror the sibling at `:147` â€” add
  `GrayzoneLogger.d(LogComponent.DNS, "Failed to parse DNS question key; forwarding without dedup", mapOf("error" to e.message))`
  alongside the existing increment, so the failure is recorded in a debug build and the counter stops being
  the only witness. Whether `getErrorStats()` should acquire a production reader or be deleted is a
  Principle I question owned by T059/T064 and is out of scope here.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` (extend the existing class), adding
  `a malformed question section increments the parse-error counter and does not throw`, constructed from
  the file's existing `ipv4DnsQuery` helper with a corrupted question section. Fails before the fix only if
  the assertion covers the reporting; the non-throwing half passes today. **Prerequisite**: adding a
  `GrayzoneLogger.d` call to this path will make the new test fail with
  `Method d in android.util.Log not mocked` unless `testOptions { unitTests.isReturnDefaultValues = true }`
  is added to `app/build.gradle.kts` (cross-cutting constraint 3). The class's existing tests use
  well-formed packets and never enter the catch, so they are unaffected.
- **Existing test conflict**: none today. `DnsPacketTest.kt:191-204` asserts on counter deltas from
  *successful* parses and continues to pass; `IntegrationTest.kt:110` reads the same stats map and is
  likewise unaffected.
- **Verification**: `confirmed` (evidence tier T4). The counter-only catch is read directly at
  `DnsPacketHelper.kt:107-110`; the absence of a production reader is corroborated by the reference index's
  TEST-ONLY marking and a direct search returning only the two test files.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build (the R8 note above affects observability in release, not the fix)
- **Constitution**: III (silent catch â€” a counter with no reader is not "logging with enough context to
  diagnose")
- **Affected surfaces**: none. The six sibling catches in the same file are compliant and are credited
  below.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Positive examples â€” credit these, do not "fix" them

The sweep identified 14 compliant patterns. They are restated here because the punch list that follows
from this file will be worked by someone applying a "no silent catches" rule mechanically, and several of
these look superficially like violations. Narrowing, deleting, or "tidying" any of them would be a
regression.

1. **`service/SessionExpiryScheduler.kt:52-62` â€” the reference implementation.** Catches the narrow
   `SecurityException`, explains why in a comment at `:53` ("Exact-alarm permission changed between check
   and call â€” fall back to the inexact path"), takes a documented recovery action at `:55`, and logs the
   *fallback's own* failure with the package name at `:57-61`. This is the shape every other alarm and IPC
   catch in the codebase should converge on, and it is the model the fixes in F-211 and F-212 point at.
   Note also that `findings.md` already records the lint `MissingPermission` warning on this function as a
   **confirmed non-finding** for exactly this reason.
2. **`service/OverlayService.kt:851-860` â€” the model Gson site.** Parses `CUSTOM_PROMPTS_JSON` inside a
   try, logs both what failed and what it is doing about it ("Failed to parse custom prompts JSON; falling
   back to defaults"), and degrades to the default prompt pool **without writing anything back**. It is the
   direct counter-example to F-215, which parses the same key in the same app and destroys it. Any fix to
   `CustomPromptsSheet.kt:44` should converge on this, not the reverse.
3. **`service/GrayzoneAccessibilityService.kt:39-63` and `:66-112` â€” deliberately broad, correctly
   documented, and running in `:a11y`.** The `catch (e: Exception)` in both `onServiceConnected` and the
   whole of `onAccessibilityEvent` is intentional, and the reason is written down at `:39-40` and `:67-68`:
   an uncaught exception is one of the triggers that makes Android revoke an accessibility service. Both
   handlers log with `LogComponent.ACCESSIBILITY`, and `onUnbind` (`:124-130`) additionally logs a
   suspected revocation. **Do not narrow these**, and do not apply F-217's "narrow the clause" reasoning
   here â€” the widening critique in F-217 concerns clauses whose *recovery* is mismatched to the error
   class; these two have exactly one recovery (stay alive) and it is correct for every error class.
   This service is also the only class in the `:a11y` process, so a crash here is unobservable from the
   main process's health state.
4. **`service/vpn/AdBlockVpnService.kt:204-208` â€” refuses to mask a failure with a default.** `establish()`
   returning `null` is converted into an `IllegalStateException` carrying the actual diagnosis ("VPN
   permission revoked or another VPN is active") rather than entering a broken "running without an
   interface" state, with the reasoning stated at `:204-206`.
5. **`service/vpn/AdBlockVpnService.kt:185-189` and `:246-250`** â€” documents the
   `startForeground`-before-`establish()` ordering and, on failure, explicitly tears the foreground state
   down so the process is not killed for a mismatched foreground-service lifecycle. F-205's fix must
   **preserve** this ordering; the defect is the missing guard, not the placement.
6. **`service/vpn/AdBlockVpnService.kt:344-412` â€” error handling with real policy.** A consecutive-error
   counter, a 5-second non-consecutive reset window, exponential backoff, and escalation to a full service
   restart, each step logged. F-217 asks only that the error *class* be distinguished within this policy,
   not that the policy be replaced.
7. **`data/StateBackupManager.kt:191-197`** â€” treats a decryption failure as tamper evidence, explains the
   two causes in a comment at `:194`, logs with the throwable, and refuses to restore garbage.
8. **`data/StateBackupManager.kt:248-250`** â€” uses `commit()` rather than `apply()` **and returns its
   boolean**, with the reason written down: restored values must be durably visible before anything else
   reads them on the same launch. **No fix in this audit may swap this for `apply()`.**
9. **`data/CleanupWorker.kt:56-62`** â€” logs with the throwable and returns `Result.retry()`, so a transient
   database failure is retried rather than silently dropped.
10. **`SharedComponents.kt:181-188`** â€” checks `exists()`/`canRead()` *before* the operation and tells the
    user with a Toast; the catch at `:208` also surfaces the message to the user rather than only to
    logcat. This is the model for F-211's user-visible failure requirement.
11. **`service/vpn/GrayzoneBloomFilter.kt:60-96`** â€” validates magic, version, hash count, bit count, byte
    alignment, and size with `require(...)`, each with a specific message, and throws on a truncated stream
    at `:93` instead of returning a half-built filter. Failure propagates to the single logging handler at
    `BlocklistManager.kt:151`. This is the codebase's only rethrow-style design and it is correct â€” the
    file has zero catches by intent, which a mechanical "every file must handle errors" reading would
    wrongly flag.
12. **`service/vpn/DnsPacketHelper.kt`** â€” six of its seven catches (`:147`, `:164`, `:217`, `:295`,
    `:335`, `:385`) log with the throwable *and* name the exact operation and IP version, on top of
    per-failure counters. F-220 concerns only the seventh.
13. **`service/OverlayService.kt:107-117`, `service/vpn/AdBlockVpnService.kt:92-102`, and `:351-358`** â€”
    installed `CoroutineExceptionHandler`s and a thread `UncaughtExceptionHandler` so async failures cannot
    vanish. These are the pattern F-206 asks `GrayzoneApplication.kt:36` to adopt.
14. **`GrayzoneApplication.kt:52-54`** â€” the `:a11y` process guard, with the reason stated
    (SharedPreferences is not multi-process safe and two writers corrupt the backup). Removing this early
    return to "make startup uniform" would corrupt the anti-tamper backup.

Two further compliant-with-a-note sites deserve mention so they are not re-reported:
`service/OverlayService.kt:233` (logs an `unregisterReceiver` failure without saying which of the two
receivers) and `service/vpn/DnsResolverPool.kt:67` (logs a query failure without the server or domain).
Both sit on paths whose outcome is logged elsewhere, and the inventory records both as compliant. They are
message-quality improvements, not violations.

---

## Principle III compliance summary

### Violations

The T011 sweep identified **21 Principle III violations** across 75 catch blocks, plus **84 of 224 I/O
boundaries with no failure handling**. This file disposes of that inventory as follows.

| | Count |
|---|---|
| Violations identified by the sweep | 21 |
| â€” filed by other tasks (C1â€“C4 + 3 `data` sites), referenced above, not re-filed here | 7 |
| â€” filed here as findings | 14 |
| Unprotected I/O boundaries raised here as findings in their own right | 18 call sites across 6 findings |
| Catch clauses widened by the uncommitted diff (not in the sweep's 21; carried in from `evidence/prior-fix-watchlist.md`) | 2 |
| **Findings in this file** | **16** (F-205 â€¦ F-220) |

The 16 findings cover **34 distinct defective sites**: 14 catch-block violations, 18 unprotected boundary
sites (2 Ã— `startForeground`, 2 Ã— unguarded `Application.onCreate` config steps, 1 Ã— missing
`CoroutineExceptionHandler`, 11 Ã— unguarded UI-path IPC, 1 Ã— `BootReceiver` `VpnService.prepare`,
1 Ã— unguarded `StreakEvaluator.run` launch), and the 2 widened catch clauses. Grouping is why the finding
count is far lower than the site count: 18 of the 34 sites are folded into a record owned by another site
because they share both a root cause and a fix.

### By severity

| Severity | Count | Findings |
|---|---|---|
| Critical | 1 | F-205 |
| High | 8 | F-206, F-207, F-208, F-209, F-210, F-211, F-212, F-213 |
| Medium | 4 | F-214, F-215, F-216, F-217 |
| Low | 3 | F-218, F-219, F-220 |
| **Total** | **16** | |

Five findings would escalate on device confirmation, and the escalation is stated per finding: F-207,
F-208, F-210, and F-212 to **Critical**, and F-214 to **High**. Three would de-escalate if their runtime
claim is refuted (F-205 â†’ High, F-207 â†’ Medium, F-212 â†’ Low). Nothing here is rated on speculation: every
Critical/High rating rests on a construct read directly in the source, with the runtime consequence
labelled `needs-verification` and carrying a procedure.

### Findings in the 9 uncommitted-modification files

Six of the sixteen sit in files carrying uncommitted working-tree changes against `557a963`:

| Finding | Uncommitted file(s) involved | Introduced by the uncommitted change? |
|---|---|---|
| F-205 | `OverlayService.kt`, `AdBlockVpnService.kt` | No â€” pre-existing; the diff touched neither `startForeground` call |
| F-206 | `GrayzoneApplication.kt` | No â€” the diff added the `:a11y` early return (`:52-54`), not the scope |
| F-207 | `GrayzoneApplication.kt` | No â€” pre-existing |
| F-216 | `GrayzoneApplication.kt` | No â€” pre-existing |
| F-217 | `AdBlockVpnService.kt`, `BlocklistManager.kt` | **Yes** â€” both catch widenings are the uncommitted diff |
| F-218 | `AdBlockVpnService.kt`, `OverlayService.kt` | No â€” pre-existing |

So exactly **one** finding (F-217, Medium) is a regression introduced by the uncommitted work; the other
five are pre-existing defects that happen to live in modified files. The remaining ten findings are against
`557a963` unchanged. `GrayzoneAccessibilityService.kt` and `DnsTrafficBus.kt` â€” the other two uncommitted
files in scope for Principle III â€” produce **no** findings: the accessibility service's two broad catches
are credited positive example 3, and `DnsTrafficBus.kt` has zero catch blocks and zero I/O boundaries (its
uncommitted change added `@Synchronized` to `clear()`, which the watchlist records as complete and
correct).

### Per review unit

Each finding is counted once, under the unit owning its primary location; sites in other units appear as
`Affected surfaces` and are shown in parentheses.

| Review unit | Findings owned | IDs | Additional sites carried from other units |
|---|---|---|---|
| RU-01 `com.grayzone.app` root | 7 | F-206, F-207, F-208, F-209, F-210, F-216, F-219 | â€” (F-207 reaches `RuntimeSessionReset.kt`, RU-02, downstream; F-219 carries `StreakManager.kt:134`, RU-02) |
| RU-02 `data` | 0 | â€” | 1 site carried by F-219 (`StreakManager.kt:134`), plus `RuntimeSessionReset.kt:29-43` reached by F-207. All other `data` violations are T020's |
| RU-03 `policy` | 0 | â€” | none. Examined: all 3 files (`SessionModels.kt`, `SessionExpiryDecision.kt`, `SessionPolicyEngine.kt`) contain **zero catch blocks and zero I/O boundaries** â€” pure decision logic, confirmed by the T011 sweep's zero-catch list. `no-defect-found` for Principle III. |
| RU-04 `service` | 2 | F-205, F-212 | â€” (F-205 carries `AdBlockVpnService.kt:189`, RU-06) |
| RU-05 `service.overlay` | 0 | â€” | none. Examined: all 5 presentation files; the sweep confirms zero catches and zero I/O (`OverlayPresentation.kt:63-68` and the four strategies are pure). `no-defect-found` for Principle III. |
| RU-06 `service.vpn` | 4 | F-214, F-217, F-218, F-220 | â€” (F-218 carries `OverlayService.kt:781`, RU-04) |
| RU-07 `ui` + `ui.theme` | 3 | F-211, F-213, F-215 | â€” (F-211 carries `MainActivity.kt:139` and `:257`, RU-01) |
| RU-08 build / manifest / config | 0 | â€” | none owned, but two build-file facts are load-bearing prerequisites for fixes here: the missing `testOptions { unitTests.isReturnDefaultValues = true }` and the missing `-keepattributes SourceFile,LineNumberTable` (cross-cutting constraints 2 and 3). Both belong to T025/T025a. |
| RU-09 resources | 0 | â€” | `not-applicable`. Resource files contain no error-handling constructs; the `res/raw` **loading** boundaries are all compliant (inventory Â§2.4, 6 of 6). |
| RU-10 existing tests | 0 | â€” | none owned. Recorded for T029: three findings here (F-214, F-219, F-220) cannot get their JVM test without the `testOptions` change, and `DnsResolverPoolTest.kt`'s two existing tests will **break** when F-214's logging fix lands unless that change ships with it. |

Counting every defective site by the unit that physically contains it, rather than by owning finding, the
distribution of the 34 sites is:

| Review unit | Sites | Breakdown |
|---|---|---|
| RU-01 | 11 | `GrayzoneApplication.kt` 4 (`:36`, `:70`, `:71`, `:249`), `MainActivity.kt` 5 (`:106`, `:139`, `:169`, `:257`, `:261`), `AppUtils.kt` 2 (`:86`, `:162`) |
| RU-02 | 1 | `StreakManager.kt:134` |
| RU-04 | 3 | `OverlayService.kt` 2 (`:144`, `:781`), `BootReceiver.kt:61` |
| RU-06 | 8 | `AdBlockVpnService.kt` 3 (`:189`, `:268`, `:379`), `DnsResolverPool.kt` 3 (`:107`, `:112`, `:123`), `BlocklistManager.kt:151`, `DnsPacketHelper.kt:107` |
| RU-07 | 11 | `VpnScreen.kt` 4 (`:98`, `:527`, `:533`, `:539`), `OnboardingScreen.kt` 3 (`:104`, `:156`, `:212`), `HomeScreen.kt` 3 (`:57`, `:175`, `:187`), `CustomPromptsSheet.kt:44` |
| **Total** | **34** | |

# Findings â€” RU-01 (`com.grayzone.app` root package)

**Task**: T021 Â· **Review unit**: RU-01 Â· **Finding ID block**: F-060 â€¦ F-079 (all 20 used)

**Subject**: the 13 top-level files of `app/src/main/java/com/grayzone/app/`. Subpackages
(`data/`, `service/`, `ui/`, `policy/`) are out of scope and are referenced only where a root-package
defect reaches them.

**Evidence base** (read, not re-derived): [`findings.md`](./findings.md) Â·
[`evidence/reference-index.md`](./evidence/reference-index.md) Â·
[`evidence/cross-process-inventory.md`](./evidence/cross-process-inventory.md) Â·
[`evidence/concurrency-inventory.md`](./evidence/concurrency-inventory.md) Â·
[`evidence/prefs-inventory.md`](./evidence/prefs-inventory.md) Â·
[`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md) Â·
[`evidence/r8.md`](./evidence/r8.md) Â· [`evidence/lint.md`](./evidence/lint.md)

**Revision**: `HEAD = 557a963`. `git diff --name-only` confirms that of the 13 subject files only
`GrayzoneApplication.kt` is modified in the working tree. `app/build.gradle.kts` is also modified and
is load-bearing for F-064; it belongs to RU-08 (T025) and is cited, not owned.

**Environment**: no device and no emulator. Every runtime claim (ANR, OEM background kill, low-memory
reclaim, logcat volume) is marked `needs-verification` with an exact procedure and a
severity-per-outcome.

**Not reported here** (confirmed NON-findings per [`findings.md`](./findings.md), re-checked against
the source and deliberately excluded): `onCreate` double-initialising expensive state â€” the process
guard at `GrayzoneApplication.kt:54` is correct and `ExistingPeriodicWorkPolicy.KEEP` at `:292` is a
second layer; cross-process false sharing of in-memory state; `PrefsKeys` being dead (R8 inlines its
`const val`s, and 21 main-source files reference it).

**Referenced, not re-filed** (owned by another task; listed so the punch list does not double-count):

| Defect | Owner | Where it touches RU-01 |
|---|---|---|
| Main-thread storage read + AES-GCM decrypt + Gson parse + `commit()` in `StateBackupManager.restoreIfWiped` | T020 | called from `GrayzoneApplication.kt:64` and `MainActivity.kt:48` |
| Backup restore has no schema gate and no key allowlist (`StateBackupManager.kt:200â€“251`) | T020 | is the upstream enabler of **F-060** |
| `RuntimeSessionReset` composes `daily_reset_date_runtime` from a per-app prefix | T020 | called from `GrayzoneApplication.kt:273` |
| Three swallowed catches in `MainActivity.kt` at `:106`, `:169`, `:261` | T030 | all three are in RU-01 files |
| `shareApk` copies the whole APK on the main thread (`SettingsScreen.kt:82` â†’ `SharedComponents.kt:193â€“195`) | T026 | implementation is `SharedComponents.kt`; see **F-075** for the two adjacent defects T026 will not catch |
| `vpn_bypass_apps` read but never written (`PrefsKeys.kt:66`) | already filed in [`findings-docs.md`](./findings-docs.md) | key is declared in an RU-01 file |
| Permission-loss behaviour of `AccessibilityPromptState.kt` / `SetupRequirements.kt` | T022 | RU-01 files this task covers for lifecycle and dead code only |

**New lead referred to T026** (not filed here, the file is not mine):
`ui/OnboardingScreen.kt:242` calls `SetupRequirements.missing(hasUsageAccess, hasOverlay)` â€” the
first parameter is declared `accessibilityEnabled`, but the argument is a *usage-access* flag. Those
are different permissions. `ui/HomeScreen.kt:66` passes `accessibilityOn` to the same parameter, so
the two screens can disagree about what is missing. Flagged for T026 to confirm and file.

---

## Findings

### F-060 â€” `validateConfiguration()` reads restored preferences with type-specific getters and no guard, so a poisoned backup crashes every launch and clearing data cannot recover it  [Critical]

- **Review unit**: RU-01
- **Disposition**: **canonical.** T030 filed the same defect independently as F-207 (rated High); F-207 is
  withdrawn in favour of this finding at Critical, per Reconciliation 4 in `findings.md`. Two items
  absorbed from F-207 and binding on the fix:
  1. **A second instance of the identical construct must be fixed in the same pass** â€”
     `resetRuntimeStateIfNeeded()` (`GrayzoneApplication.kt:71` â†’ `RuntimeSessionReset.kt:29â€“43`) is the
     same unguarded typed-getter read one line later and fails the same way. Patching only
     `validateConfiguration()` leaves the boot loop reachable.
  2. **Recovery, stated precisely.** Clear-data does not recover, because `MANAGE_EXTERNAL_STORAGE` is a
     system-held appop that survives a data wipe, so the restore reads the same poisoned backup.
     Uninstall *does* recover, but only because it revokes that appop â€” so the sole recovery path is also
     the one that destroys every lock. The recovery is itself the bypass, which is why this cannot be
     downgraded to "recoverable, therefore High."
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `GrayzoneApplication.validateConfiguration()` (lines 175â€“226), reached from
  `GrayzoneApplication.onCreate()` (line 70); second instance at `resetRuntimeStateIfNeeded()` (line 71)
  â†’ `RuntimeSessionReset.kt:29â€“43` (absorbed from F-207)
- **Issue**: `validateConfiguration()` calls `prefs.getInt(WAIT_SECONDS, â€¦)` (`:181`),
  `getInt(SESSION_MINUTES, â€¦)` (`:190`), `getInt(LOCKOUT_MINUTES, â€¦)` (`:199`) and
  `getStringSet(MONITORED_APPS, null)` (`:208`) with no `try`/`catch` anywhere in the method and no
  `catch` around its call site. `SharedPreferences.getInt` throws `ClassCastException` when the
  stored value is not an `Int`; `getStringSet` throws it when the stored value is not a `Set`. The
  method is therefore only safe while every value has the type the app itself wrote â€” but
  `StateBackupManager.applyInto` writes **arbitrary keys with arbitrary JSON-derived types and no
  allowlist and no schema gate** (T020's finding), and `restoreIfWiped` runs at `onCreate:64`,
  **six lines before** `validateConfiguration()`. A backup file containing `"wait_seconds": "30"`
  (a string, e.g. from a hand-edited or older-schema snapshot in the world-readable
  `/sdcard/.grayzone/` folder) therefore makes `Application.onCreate` throw before any Activity or
  service exists. The user-visible consequence is that the app cannot be launched at all â€” and the
  standard recovery, Settings â†’ Clear data, makes it *worse*: it removes the live prefs, which is
  exactly the condition `restoreIfWiped` tests for at `StateBackupManager.kt:107`, so the poisoned
  snapshot is re-applied and the next launch crashes identically. The installation is bricked until
  the user finds and deletes a hidden file on shared storage or uninstalls. For an app whose entire
  purpose is to keep running, this is a total, silent protection loss with no in-app signal.
  The bitter irony is that the method whose stated job is "prevents crashes â€¦ from invalid stored
  values" (`:172â€“173`) is itself the crash site: it validates *ranges* but never *types*.
- **Trigger condition**:
  1. Install and complete onboarding so a backup exists (`adb shell ls /sdcard/.grayzone/`).
  2. Replace the snapshot with one whose `wait_seconds` entry is a JSON string rather than a number.
     Statically equivalent and device-free: any `applyInto` run where the JSON value for a key that
     `validateConfiguration` reads with `getInt` deserialises to `String`, `Double`, or `Boolean`.
  3. `adb shell pm clear com.grayzone.app` to force the restore branch.
  4. Launch the app. Observe `ClassCastException` from `SharedPreferencesImpl.getInt` in
     `GrayzoneApplication.onCreate`: `adb logcat -b crash`.
  5. Repeat steps 3â€“4. The crash recurs, demonstrating the restore loop.
- **Blast radius**:
  - *Callers*: `validateConfiguration()` is `private` and has exactly one caller,
    `GrayzoneApplication.onCreate()` (`:70`). `GrayzoneApplication` itself is referenced by
    `AndroidManifest.xml` (`android:name`), `MainActivity.kt`, `data/ConfigValidator.kt` (KDoc),
    `data/RuntimeSessionReset.kt` (KDoc) and `data/StateBackupManager.kt` (reference index,
    `GrayzoneApplication.kt` section). Because the failure is in `Application.onCreate`, the
    effective caller set is *every* component in the main process: `MainActivity`, `OverlayService`,
    `AdBlockVpnService`, `SessionExpiryReceiver`, `BootReceiver`, `CleanupWorker`.
  - *Process death*: this is the process-death case. The crash kills the process during creation, so
    nothing downstream ever initialises; a restart re-enters the same path.
  - *Config change*: not implicated â€” the failure precedes any Activity, so no configuration change
    can reach or clear it.
  - *Foreground/background*: worse in the background. A background start (boot, alarm, accessibility
    bind) hits the same `onCreate` and dies with no UI, so the user sees only that protection stopped.
  - *Cross-process*: not reachable from `:a11y`. The guard at `:54` returns before this code for any
    process whose name is not the package name, so the `:a11y` process is unaffected and will keep
    running while the main process crash-loops â€” which is itself the misleading part: accessibility
    events keep arriving and nothing services them.
  - *Persistence*: reads four preference keys and, on the repair path (`:215`), writes them back with
    `apply()`. The fix must keep that repair write, because a repair is the only way a poisoned value
    is ever corrected.
  - *Release-only*: no. `ClassCastException` from a framework getter is not affected by R8; the
    method is reachable and kept in both variants (`evidence/r8.md` does not list it as removed).
- **Fix** (minimal): wrap each typed read in a helper that catches `ClassCastException` and treats a
  wrong-typed value exactly as an out-of-range one â€” remove the key and write the default. Roughly:

  ```kotlin
  private fun SharedPreferences.getIntOrRepair(key: String, default: Int, editor: SharedPreferences.Editor): Int =
      try { getInt(key, default) } catch (e: ClassCastException) {
          GrayzoneLogger.w(LogComponent.BOOT, "Wrong-typed pref $key, resetting to $default", e)
          editor.remove(key).putInt(key, default); default
      }
  ```

  applied at `:181`, `:190`, `:199`, and the same shape for `getStringSet` at `:208`. This is four
  call-site substitutions plus one private helper, and it keeps the existing range validation and
  the `needsRepair`/`apply()` bookkeeping untouched. It also composes correctly with T020's
  allowlist fix rather than depending on it: even after the backup is allowlisted, a schema change
  that alters a key's type would otherwise reintroduce this crash.
- **Rewrite justification**: not applicable â€” this is a patch, not a rewrite. A blanket
  `try { â€¦ } catch (e: Exception) { }` around the whole method was considered and **rejected**: it
  would abandon validation for every key after the first bad one and would violate Principle III by
  masking the failure without a recorded reason.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/ConfigValidationTypeSafetyTest.kt`,
  using the existing `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt` double, asserting
  that a preferences map in which `wait_seconds` holds a `String` and `monitored_apps` holds a
  `String` yields the documented defaults and does not throw. Before the fix the test throws
  `ClassCastException`; after the fix it returns `DEFAULT_WAIT_SECONDS` and `emptySet()`.
- **Existing test conflict**: none. `app/src/test/java/com/grayzone/app/data/ConfigValidatorTest.kt`
  exercises `ConfigValidator`'s pure range predicates only and never constructs a `SharedPreferences`,
  so it neither covers nor conflicts with this path.
- **Verification**: `confirmed` (evidence tier T2).
  Confirmed by reading the source: `GrayzoneApplication.kt:175â€“226` contains no `try`, and
  `onCreate:70` is not inside one; `StateBackupManager.kt:104â€“131`'s `try` wraps only
  `restoreIfWiped` itself and returns `false` on failure, so it cannot protect a later caller. The
  throwing behaviour of `SharedPreferences.getInt`/`getStringSet` on a type mismatch is the
  documented platform contract. The end-to-end poisoning chain is corroborated by the already-filed
  T020 finding that `applyInto` writes arbitrary untyped keys with no schema gate
  (`findings.md`, leads table), not by device observation.
- **Build scope**: `everyday-build`
- **Constitution**: III (an I/O boundary â€” SharedPreferences â€” with no explicit failure handling, at
  the single most consequential point in the app's lifecycle)
- **Affected surfaces**: `GrayzoneApplication.resetRuntimeStateIfNeeded()` (`:271â€“279`) â†’
  `data/RuntimeSessionReset.resetIfNeeded` performs the same unguarded typed reads
  (`getString` at `:29`, `getStringSet` at `:36`) one line earlier in `onCreate`'s sequence and has
  the identical exposure; the fix should cover both, but the `RuntimeSessionReset` body belongs to
  T020. `MainActivity.kt:58â€“60` and `ui/theme/Theme.kt:28` read `THEME_MODE` with `getString` on the
  UI path with no guard â€” same class, lower consequence (an Activity crash, not a process crash).
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-061 â€” The app's entire OEM background-kill mitigation is built and never wired, leaving extended condition E6 unanswered  [High]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/OemBatteryHelper.kt` â†’ the whole object
  (lines 17â€“185): `needsOemOptimization()` (`:22`), `getManufacturerName()` (`:32`),
  `getOptimizationInstructions()` (`:41`), `openBatterySettings()` (`:59`),
  `getBatterySettingsIntent()` (`:82`, private), `canVerifyOptimization()` (`:179`)
- **Issue**: 185 lines covering eight manufacturers â€” MIUI autostart (`:86â€“95`), ColorOS startup
  manager (`:97â€“106`), vivo iManager background start-up (`:108â€“117`), realme via ColorOS
  (`:119â€“128`), OxygenOS chain-launch (`:130â€“139`), EMUI/Magic UI protected apps (`:141â€“150`) and
  Samsung app-details for the sleeping-apps flow (`:152â€“159`) â€” have **zero callers**. Triple
  corroborated: `evidence/r8.md` records whole-class removal by the shrinker; the reference index
  reports 0 references for the object and for every public member (`getManufacturerName()`'s only
  reference is the object's own `getOptimizationInstructions()`); a direct source search finds the
  single occurrence `OemBatteryHelper.kt:17`, its own declaration. Meanwhile the app's only
  battery-resilience affordance is the generic `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` at
  `ui/OnboardingScreen.kt:209` and `ui/HomeScreen.kt:172` â€” which this file's own doc comment
  (`:12â€“13`) declares insufficient: *"Xiaomi, Oppo, Vivo, Realme, OnePlus aggressively kill
  background services even when battery optimization is disabled."* The user-visible consequence is
  that on the OEM ROMs that make up the majority of the Android install base, `OverlayService` and
  `AdBlockVpnService` are killed hours after the user last opened the app, protection silently stops,
  and the app never told the user about the one setting that would have prevented it. **This is the
  answer to extended condition E6 (OEM background-kill resilience): the mitigation exists, is
  correct-looking, and is not connected to anything.**
- **Trigger condition**:
  1. Install on a Xiaomi (MIUI/HyperOS), Oppo/realme (ColorOS), vivo (Funtouch/OriginOS) or OnePlus
     (OxygenOS) device.
  2. Complete onboarding, granting the generic battery-optimisation exemption when prompted at
     `OnboardingScreen.kt:209`.
  3. Confirm the app never surfaces the OEM autostart screen: search the UI for the strings at
     `OemBatteryHelper.kt:43â€“51` â€” none appear anywhere in `ui/`.
  4. Enable a limit on a monitored app, then leave the device idle and screen-off for 2â€“4 hours.
  5. Open the monitored app. Observe that no friction overlay appears. Confirm the process is gone
     with `adb shell ps -A | grep grayzone` and confirm the kill with
     `adb logcat -d | grep -iE "grayzone.*(kill|died|ANR)"`.
- **Blast radius**:
  - *Callers*: **none**. Enumerated from the reference index `OemBatteryHelper.kt` section:
    `OemBatteryHelper` (object) â€” 0 references; `needsOemOptimization()` â€” 0;
    `getManufacturerName()` â€” `(self)` `getOptimizationInstructions()` only;
    `getOptimizationInstructions()` â€” 0; `openBatterySettings()` â€” 0; `canVerifyOptimization()` â€” 0.
    Under option (a) the new callers would be `ui/OnboardingScreen.kt` (at the existing
    battery-optimisation step, `:209`) and `ui/HomeScreen.kt` (`:172`).
  - *Process death*: this finding is *about* process death. Nothing in the file holds state, so
    neither option risks losing any; option (a)'s "user was shown the OEM screen" flag, if added,
    must be persisted rather than held in memory or it will re-prompt on every process start.
  - *Config change*: option (a) adds UI to two Composables that already use `remember`; a prompt
    driven by `remember` would reappear on rotation. Any added state must be `rememberSaveable` or
    preference-backed. See F-076 for the same defect already present.
  - *Foreground/background*: `openBatterySettings()` calls `context.startActivity` and every branch
    sets `FLAG_ACTIVITY_NEW_TASK` (`:93`, `:104`, `:115`, `:126`, `:137`, `:148`, `:157`, `:166`), so
    it is safe from a non-Activity context â€” but on Android 10+ a background activity start is
    blocked outright, so option (a) must call it only from a foreground, user-initiated tap. Both
    proposed call sites already satisfy that.
  - *Cross-process*: not reachable from `:a11y` under either option. The two proposed call sites are
    Composables in the main process, and `Build.MANUFACTURER` is a process-local constant.
  - *Persistence*: the file writes nothing today. Option (a) adds one boolean preference so the
    prompt is shown once rather than on every visit; it must go through `PrefsKeys` and it will be
    captured by `StateBackupManager`'s `prefs.all` snapshot (`:167`) like every other key.
  - *Release-only*: **yes, and it is the tell.** R8 removes the whole class today precisely because
    nothing reaches it, so the code ships in neither variant. Wiring it up changes the release APK's
    contents; the existing `proguard-rules.pro` needs no new keep rule, because the new call sites
    make it reachable through ordinary tree-shaking.
- **Fix** (minimal): **two honest options; the maintainer chooses.**

  **(a) Wire it up â€” closes a High-severity feature gap with code that already exists.** At
  `ui/OnboardingScreen.kt:209` and `ui/HomeScreen.kt:172`, after the existing generic
  battery-optimisation request, add:

  ```kotlin
  if (OemBatteryHelper.needsOemOptimization()) {
      // headline: OemBatteryHelper.getManufacturerName()
      // body:     OemBatteryHelper.getOptimizationInstructions()
      // button:   OemBatteryHelper.openBatterySettings(context)
  }
  ```

  Roughly ten lines per site inside the existing card layouts, plus one `PrefsKeys` boolean so the
  card is dismissible. Two gaps in the existing code must be closed as part of (a), or the wire-up
  is worse than nothing:
  1. **No fallback when the OEM component is missing.** `openBatterySettings` returns `false` when
     `startActivity` throws (`:69â€“76`), and the caller then has nothing to show. On a ROM where
     `com.miui.securitycenter/â€¦AutoStartManagementActivity` was renamed or un-exported, the user
     taps a button and nothing happens. `getBatterySettingsIntent` must fall through to its own
     `else` branch (`:161â€“171`) when the OEM-specific component does not resolve.
  2. **Package visibility.** `AndroidManifest.xml:10â€“15` scopes `<queries>` to `MAIN`/`LAUNCHER`
     intents and deliberately does not request `QUERY_ALL_PACKAGES` (`:4â€“9`). On Android 11+ an
     explicit `ComponentName` into a package that is not visible fails with
     `ActivityNotFoundException` even when the package is installed. MIUI's Security Center has a
     launcher entry and is likely visible; `com.coloros.safecenter` and
     `com.vivo.permissionmanager` may not be. Whether each component resolves is
     `needs-verification` below. The correct resolution is `resolveActivity` plus the fallback in
     (1), **not** adding `QUERY_ALL_PACKAGES` â€” the manifest comment at `:7â€“8` correctly notes that
     permission is a Play-policy blocker.

  **(b) Delete the file â€” Principle I compliance, Low severity.** `git rm OemBatteryHelper.kt`.
  Behaviour-preserving by Principle V's standard: unreachability is *proven* by R8 removal plus the
  reference index plus direct search, not assumed. This closes the dead-code violation and leaves
  E6 unanswered and undocumented.

  **Recommendation: (a).** Three reasons. First, severity asymmetry â€” (b) resolves a Low
  (cleanliness) and leaves a High (protection silently stops on the majority of Android hardware)
  open, whereas (a) resolves both, since wired code is not dead code. Second, cost asymmetry â€” the
  hard part, the per-OEM intent table, is already written and reviewed; what is missing is about
  twenty lines at two call sites that already exist and already render permission cards. Third,
  the app's own analysis already concluded (a): the doc comment at `:12â€“13` states that the generic
  exemption the app *does* request is not sufficient, so deleting the file means shipping a
  mitigation the codebase itself documents as inadequate. **The choice is the maintainer's**, and
  option (b) is a legitimate call if the verification below shows the intents no longer resolve on
  current ROMs â€” but in that case the right move is to fix the intents, not to abandon E6.

  Independently of (a)/(b): `:163`'s `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)` is
  unreachable-false at `minSdk = 29` (`app/build.gradle.kts:28`); lint reports it as
  `ObsoleteSdkInt` (`evidence/lint.md` Â§E3). Under (a), delete the check and its `else null`
  (`:163â€“170`) so the generic fallback is unconditional â€” which is also what fix (1) above needs.
- **Rewrite justification**: not applicable under either option. Option (a) is additive at the call
  sites plus two localised repairs inside `getBatterySettingsIntent`; option (b) is a deletion.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/OemBatteryHelperTest.kt`,
  asserting that `getOptimizationInstructions()` returns a manufacturer-specific string for each of
  the eight keys in `needsOemOptimization()`'s list (`:24â€“26`) and the generic fallback otherwise,
  and that `needsOemOptimization()` is case-insensitive (it lowercases at `:23`, so a device
  reporting `"Xiaomi"` matches). `Build.MANUFACTURER` is a static final field, so the test must
  extract the manufacturer string into a parameter â€” which is itself a small, worthwhile change to
  `getOptimizationInstructions(manufacturer: String = Build.MANUFACTURER.lowercase())`. Under option
  (b) no test is added and this file is deleted instead. Note that `openBatterySettings` and
  `getBatterySettingsIntent` need `instrumented` coverage to be meaningful and **cannot be executed
  here** â€” no device is available.
- **Existing test conflict**: none. No test in `app/src/test/` references `OemBatteryHelper`.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: physical devices running current MIUI/HyperOS, ColorOS, Funtouch/OriginOS,
    OxygenOS, EMUI/Magic UI and One UI. Two independent questions: (i) does each hardcoded
    `ComponentName` still resolve and is it still exported, and (ii) is each target package visible
    to this app under the scoped `<queries>` at `AndroidManifest.xml:10â€“15`.
  - *Procedure*: per device, `adb shell dumpsys package <target> | grep -A3 <ActivityName>` to
    confirm the component exists and is `exported=true`; then
    `adb shell am start -n <pkg>/<cls>` to confirm it launches. Then install the app with option
    (a) applied and confirm the button opens the screen rather than silently failing. Separately,
    for the E6 claim itself, run steps 4â€“5 of the trigger condition above on one aggressive-OEM
    device with and without the OEM autostart setting enabled, and compare whether the process
    survives a 4-hour idle window.
  - *Severity effect*: the **High** rating is for the feature gap and does not depend on the device
    result â€” it is established statically by zero callers plus the file's own doc comment plus the
    generic-only request at the two call sites. The device result changes the *fix*, not the
    severity: if the components still resolve, (a) is a straightforward wire-up; if some no longer
    resolve, (a) still stands but must lead with the instruction text (`:41â€“53`) and use the intent
    only as a best-effort shortcut with the fallback from fix (1). If the E6 idle test shows the
    process surviving on aggressive OEMs even without the autostart setting, the gap drops to
    **Medium** (guidance still absent, but the observed impact is smaller than the file claims).
- **Build scope**: `everyday-build`
- **Constitution**: I under option (b) â€” an entire unreferenced file, which Principle I requires be
  removed rather than retained. Under option (a) the violation is resolved by making the code
  reachable, which is the outcome Principle I actually wants.
- **Affected surfaces**: `ui/OnboardingScreen.kt:209` and `ui/HomeScreen.kt:172` (the two generic
  battery-optimisation requests that would host the wire-up; `evidence/lint.md` Â§E5 separately flags
  `:209` for the `BatteryLife` policy warning, owned by T025). `evidence/lint.md` Â§E3 lists the
  `ObsoleteSdkInt` at `:163` alongside `OverlayService.kt:789`, routed to the T059â€“T064 sweep.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

---

### F-062 â€” `appScope` has no `CoroutineExceptionHandler`, so an uncaught failure in either unprotected backup coroutine kills the whole process  [Medium]

- **Disposition**: **withdrawn â€” consolidated into F-206** (`findings-principle3.md`), which owns the
  same defect at the corrected severity of **High**. The Medium rating here understated it: Medium is
  "incorrect behavior in narrow conditions," and process death is a crash, not incorrect behavior. F-206
  is canonical because it enumerates all five `appScope` launch sites and establishes that the app's two
  service scopes both install handlers, making this an inconsistency rather than house style. Retained
  for traceability only; do not action separately. See Reconciliation 5 in `findings.md`.
- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’ `appScope` (line 36)
  and its unprotected launches in `registerStateBackup()` (lines 96â€“100, 128â€“133) and
  `flushBackupIfDirty()` (line 139)
- **Issue**: `appScope` is `CoroutineScope(SupervisorJob() + Dispatchers.IO)` with no
  `CoroutineExceptionHandler` in the context. `SupervisorJob` stops one child's failure from
  cancelling its siblings, but it does **not** handle the exception: a coroutine started with
  `launch` and no handler routes its uncaught exception to the thread's
  `Thread.UncaughtExceptionHandler`, which on Android is the process-killing default. Two of the
  five `appScope.launch` sites have no `try`/`catch`: the one-time baseline write at `:96â€“100`,
  where `StateBackupManager.backupExists` (`StateBackupManager.kt:151â€“152`) calls
  `Environment.isExternalStorageManager()` and `File.exists()` with no internal guard; and the
  flush-loop body at `:128â€“133` together with the inner launch at `:139`. (The other three launches
  are correctly protected: `:148â€“154` and `:162â€“168` each wrap their body in `try`/`catch`, and
  `StateBackupManager.writeBackup` catches `Exception` internally at `:176â€“178`.) The user-visible
  consequence is that a `SecurityException` from a storage-permission state change, or an `Error`
  that `writeBackup`'s `catch (e: Exception)` does not cover, terminates the app from a background
  thread â€” taking `OverlayService` and `AdBlockVpnService` with it and stopping protection with no
  crash dialog the user is likely to see, since the failure originates while the app is backgrounded.
  The KDoc at `:34â€“35` â€” *"SupervisorJob so one failure never tears the scope down for later
  callers"* â€” is literally true and reads as a durability guarantee, which makes it more likely a
  future maintainer adds an unguarded `launch` here believing it is covered.
- **Trigger condition**:
  1. Grant all-files access so `backupExists`/`writeBackup` take their real path.
  2. Force a failure in the unprotected window. Statically: any throw from
     `Environment.isExternalStorageManager()` or `File.exists()` inside the `:96â€“100` launch.
     Device-observable proxy: revoke `MANAGE_EXTERNAL_STORAGE` via
     `adb shell appops set com.grayzone.app MANAGE_EXTERNAL_STORAGE deny` immediately after launch,
     while the baseline-write coroutine is in flight.
  3. Observe with `adb logcat -b crash` whether the process terminates with the exception's stack
     showing `GrayzoneApplication$registerStateBackup$1.invokeSuspend` rather than a handled warning.
  4. Confirm the services died: `adb shell dumpsys activity services com.grayzone.app`.
- **Blast radius**:
  - *Callers*: `appScope` is `private` and is used at exactly five sites, all within
    `GrayzoneApplication`: `registerStateBackup()` `:96` and `:128`, `flushBackupIfDirty()` `:139`,
    `runDeferredStartupWork()` `:148`, `catchUpStreaks()` `:162`. `registerStateBackup()`,
    `runDeferredStartupWork()` and `catchUpStreaks()` are each called once, from
    `onCreate()` (`:75`, `:72`, `:73`). `flushBackupIfDirty()` has two callers:
    `onActivityStopped` (`:116`) and the flush loop (`:131`). Cross-checked against
    `evidence/concurrency-inventory.md`, which lists `appScope` as a scope with no cancellation site.
  - *Process death*: the defect *causes* process death. Note the converse is a non-issue and is
    stated here so it is not re-raised: `appScope` having no `cancel()` site is **correct** for an
    `Application`-scoped scope, because the process is the scope's lifetime and the `while (true)`
    loop at `:129` cannot outlive it. There is no leak; the missing handler is the whole defect.
  - *Config change*: no effect. `appScope` is owned by the `Application`, not an Activity, and no
    configuration change recreates it.
  - *Foreground/background*: strictly worse in the background, which is where this code is designed
    to run â€” the flush loop at `:128â€“133` exists specifically to capture changes made while no
    Activity is visible (`:126â€“127`), so a crash there happens exactly when nobody is watching.
  - *Cross-process*: not reachable from `:a11y`. The guard at `:54` returns before
    `registerStateBackup()` is ever called, so the scope is only ever constructed in the main
    process. This is intentional and correct â€” see F-078 on how thinly that invariant is held.
  - *Persistence*: the affected coroutines write the external backup file via
    `StateBackupManager.writeBackup` and read `prefs.all`. A crash between the dirty-flag clear and
    the write is the subject of F-063, which is a separate root cause and separately filed.
  - *Release-only*: no. Coroutine exception routing is a runtime property of `kotlinx.coroutines`,
    unaffected by shrinking, and `evidence/r8.md` does not report `GrayzoneApplication` members as
    removed.
- **Fix** (minimal): add a handler to the scope's context at `:36` â€” one line, no call-site changes:

  ```kotlin
  private val appScope = CoroutineScope(
      SupervisorJob() + Dispatchers.IO +
      CoroutineExceptionHandler { _, t ->
          GrayzoneLogger.e(LogComponent.BOOT, "Uncaught exception in appScope", t)
      }
  )
  ```

  This matches the pattern the codebase already uses correctly elsewhere â€”
  `OverlayService.kt:108â€“112` and `AdBlockVpnService.kt:93â€“97` both install exactly this handler on
  their service scopes. `GrayzoneApplication` is the one scope that was missed. Update the KDoc at
  `:34â€“35` in the same change so it describes what the scope actually guarantees.
- **Rewrite justification**: not applicable â€” one-line context addition.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/AppScopeExceptionHandlerTest.kt`,
  building a scope with the same context shape and asserting that a `launch` whose body throws
  invokes the handler and leaves the scope active for a subsequently launched child. Before the fix
  the equivalent handler-free scope propagates to the thread's default handler; after the fix the
  handler records the throwable and the second child still runs.
- **Existing test conflict**: none. No test constructs `GrayzoneApplication` or references `appScope`.
- **Verification**: `confirmed` (evidence tier T2).
  Confirmed by reading `GrayzoneApplication.kt:36` (no handler in the context) and `:96â€“100`,
  `:128â€“133`, `:139` (no `try`/`catch`), contrasted with `:148â€“154` and `:162â€“168` (both guarded),
  and corroborated by `evidence/concurrency-inventory.md`'s scope inventory. The exception-routing
  behaviour of `launch` without a handler under `SupervisorJob` is the documented `kotlinx.coroutines`
  contract. The *probability* of a throw from `Environment.isExternalStorageManager()` in the wild is
  not established here, which is why this is Medium and not High.
- **Build scope**: `everyday-build`
- **Constitution**: III (I/O boundary â€” shared-storage access â€” with no explicit failure handling on
  two of five paths)
- **Affected surfaces**: `flushBackupIfDirty()` (`:137â€“141`) shares the scope and inherits the same
  exposure. The correctly-handled counterparts are `service/OverlayService.kt:105â€“113` and
  `service/vpn/AdBlockVpnService.kt:90â€“98`, which are the model for the fix.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-063 â€” `flushBackupIfDirty` clears the dirty flag before the write succeeds, so a failed or skipped backup write is silently never retried  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `GrayzoneApplication.flushBackupIfDirty()` (lines 137â€“141)
- **Issue**: `backupDirty.compareAndSet(true, false)` clears the flag and *then* launches the write
  (`:138â€“139`). The flag therefore records "a write was attempted", not "a write succeeded", and the
  two paths where the write does not happen both leave the flag clear:
  `StateBackupManager.writeBackup` returns early with only an info log when all-files access is
  absent (`StateBackupManager.kt:161â€“163`), and it swallows any write failure into a warning
  (`:176â€“178`). Because the launch is fire-and-forget, `flushBackupIfDirty` cannot observe either.
  The consequence is specific to this app's threat model: the backup exists so that a user who
  clears app data to escape their own locks has the state restored. Every preference change made
  before all-files access is granted â€” which is the entire first run, since the permission is
  requested lazily at `MainActivity.kt:50` â€” is marked flushed and never re-flushed. If the user
  then clears data before making any further preference change, `restoreIfWiped` finds either no
  file or a stale one, and the locks the user set are gone. The app reports nothing: the "State
  backup skipped" notice at `StateBackupManager.kt:162` is an `i()` call, which F-064 shows is
  compiled out in every variant the project builds. The bound on the damage is real and worth
  stating: the very next preference change re-dirties the flag, and `writeBackup` snapshots all of
  `prefs.all` (`:167`), so one subsequent edit repairs everything. That self-healing property is
  what keeps this Medium rather than High.
- **Trigger condition**:
  1. Fresh install. Decline the all-files-access prompt that `MainActivity.kt:50` raises.
  2. Configure monitored apps and limits, generating many preference writes; each flips
     `backupDirty` at `:104`.
  3. Background the app so `onActivityStopped` (`:116`) calls `flushBackupIfDirty`. The flag clears
     at `:138`; `writeBackup` returns at `StateBackupManager.kt:161â€“163` without writing.
  4. Grant all-files access from system settings, and return to the app **without changing any
     preference**.
  5. `adb shell pm clear com.grayzone.app`, then relaunch. Observe the app comes up unconfigured:
     no backup file existed to restore. Confirm with `adb shell ls -la /sdcard/.grayzone/`.
- **Blast radius**:
  - *Callers*: `flushBackupIfDirty()` is `private` with exactly two callers, both in
    `GrayzoneApplication`: `ActivityLifecycleCallbacks.onActivityStopped()` (`:116`) and the flush
    loop in `registerStateBackup()` (`:131`). The flag it reads, `backupDirty` (`:44`), is set at
    exactly one place, the `OnSharedPreferenceChangeListener` at `:103â€“105`, registered at `:106`.
  - *Process death*: the flag is in-memory (`AtomicBoolean`, `:44`) and does not survive the
    process. A process death between the clear and the write loses the pending change permanently,
    with no record that anything was pending â€” the same failure as an errored write, reached a
    different way. A fix that keeps the flag set until the write is confirmed narrows this window
    but cannot close it; only a persisted "pending" marker would, which is not warranted here.
  - *Config change*: no effect. The flag is `Application`-scoped and no Activity recreation touches
    it. Note that a rotation *does* fire `onActivityStopped` (`:114`) and therefore a flush, so
    rotations opportunistically mask the bug by triggering extra write attempts.
  - *Foreground/background*: the primary flush point *is* the background transition (`:116`), and
    the design comment at `:85â€“87` correctly identifies backgrounding as the critical instant â€”
    which is exactly when this defect discards the change.
  - *Cross-process*: not reachable from `:a11y`; `registerStateBackup()` is only called after the
    guard at `:54`. However, the flush loop's stated purpose (`:126â€“127`) is to capture changes the
    accessibility service makes while no UI is visible â€” and per
    `evidence/cross-process-inventory.md` the `:a11y` process never touches prefs, so that loop's
    rationale is already weaker than the comment implies. Worth correcting alongside the fix.
  - *Persistence*: writes the encrypted snapshot at `/sdcard/.grayzone/` via
    `StateBackupManager.writeBackup`. The defect is that the write silently does not occur; nothing
    corrupt is written.
  - *Release-only*: no â€” but note the compounding interaction with F-064: the diagnostics that would
    reveal this (`i()` at `StateBackupManager.kt:162`, `d()`/`i()` at `:172â€“175`) are compiled out
    in both variants, so the failure is unobservable in every build the project produces.
- **Fix** (minimal): move the flag clear to the write's success path â€” set it only after
  `writeBackup` has actually written, and re-set it if it has not:

  ```kotlin
  private fun flushBackupIfDirty() {
      if (!backupDirty.compareAndSet(true, false)) return
      appScope.launch {
          if (!StateBackupManager.writeBackup(this@GrayzoneApplication)) backupDirty.set(true)
      }
  }
  ```

  This requires `writeBackup` to return `Boolean` instead of `Unit` â€” a three-line change at
  `StateBackupManager.kt:160â€“179` (`return false` at `:163`, `return false` in the catch at `:178`,
  `return true` after `:175`) that belongs to T020 and must be coordinated. No caller of
  `writeBackup` currently uses its result, so the signature change is source-compatible. Re-setting
  the flag on failure is deliberately optimistic: it re-arms the next flush rather than retrying
  immediately, which preserves the battery rationale documented at `:79â€“92`.
- **Rewrite justification**: not applicable â€” a reordering plus a return type.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/BackupDirtyFlagTest.kt`,
  modelling the flag-and-writer pair with a writer stub that reports failure, asserting the flag is
  still `true` after a failed flush and `false` after a successful one. Before the fix the flag is
  `false` in both cases; after the fix only the success case clears it.
- **Existing test conflict**: none. No test references `backupDirty` or `flushBackupIfDirty`.
- **Verification**: `confirmed` (evidence tier T2).
  Confirmed by reading `GrayzoneApplication.kt:137â€“141` (clear precedes launch; result discarded)
  together with `StateBackupManager.kt:160â€“179` (early return at `:161â€“163`, swallowed failure at
  `:176â€“178`, `Unit` return). No device needed: the ordering and the discarded outcome are visible
  in the source.
- **Build scope**: `everyday-build`
- **Constitution**: III (a failure at an I/O boundary is masked â€” the caller records success by
  default with no recorded, intentional reason)
- **Affected surfaces**: `GrayzoneApplication.registerStateBackup()`'s baseline write at `:96â€“100`
  has the mirror-image gap â€” it writes only when no backup exists, so a corrupt-but-present file is
  never replaced at startup. `data/StateBackupManager.writeBackup` (`:160â€“179`) must change signature
  for the fix; T020 owns that file.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-064 â€” `BuildConfig.DEBUG` is `false` in every variant the project builds, so `GrayzoneLogger`'s DEBUG and INFO channels are dead â€” including the only observability into the anti-tamper backup  [Medium]

- **Review unit**: RU-01
- **Verification status**: **CONFIRMED (T1, static)** by the lead auditor â€” no device or Gradle run
  needed. The generated constants were already on disk from this session's `--rerun-tasks` compile:
  `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java:7,9` reads
  `DEBUG = false` alongside `BUILD_TYPE = "debug"`, and the release file reads the same `DEBUG = false`.
  That is direct AGP output proving `DEBUG` follows `debuggable` and not the build-type name. Trigger
  step 4 below is discharged; steps 1â€“3 and 5 remain optional corroboration only.
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneLogger.kt` â†’
  `GrayzoneLogger.shouldLog()` (line 38), gating `d()` (line 47) and `i()` (line 57). Root cause in
  `app/build.gradle.kts` â†’ `android.buildTypes.debug` (lines 69â€“78, **uncommitted**)
- **Issue**: `shouldLog` maps `DEBUG` and `INFO` to `BuildConfig.DEBUG` (`:38`). AGP derives
  `BuildConfig.DEBUG` from the build type's `debuggable` property, not from its name. The working
  tree adds a `debug` block that sets `isDebuggable = false` (`app/build.gradle.kts:75`), so
  `BuildConfig.DEBUG` is `false` in the **debug** variant as well as in release. Every
  `GrayzoneLogger.d(â€¦)` and `GrayzoneLogger.i(â€¦)` call in the app is therefore a no-op in both
  variants â€” there is no build the project can produce in which they emit. This is a **regression
  introduced by the uncommitted change**: at `557a963` there is no `debug` block, AGP's default
  `debuggable true` applies, and the debug variant logged normally. The consequence is not a
  behaviour change for users; it is the silent loss of the app's entire diagnostic channel at the
  moment an audit needs it most. The specific casualties matter: the boot banner at
  `GrayzoneApplication.kt:56â€“59`, the "State backup written (N bytes)" confirmation at
  `StateBackupManager.kt:172â€“175`, the "State backup skipped â€” all-files access not granted" notice
  at `:162`, the "Configuration repaired" notice at `GrayzoneApplication.kt:216â€“219`, the "Runtime
  session state reset" notice at `:274â€“277`, and the default-apps seed notice at `:262â€“265`. Those
  are precisely the signals that would surface F-060, F-063 and the T020 restore findings on a real
  device â€” and they were turned off by a change whose stated purpose (`build.gradle.kts:70â€“75`) is
  about signing and installability and says nothing about logging. Nobody chose this.
- **Trigger condition**:
  1. `./gradlew :app:assembleDebug`.
  2. Install and launch on any device; `adb logcat -s Grayzone`.
  3. Observe that no `D/Grayzone` or `I/Grayzone` line ever appears, including the startup banner
     that `GrayzoneApplication.kt:56` emits unconditionally on the main process's first `onCreate`.
  4. Confirm the cause without a device: inspect the generated constant at
     `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java` and observe
     `public static final boolean DEBUG = false;`.
  5. `git stash` the `build.gradle.kts` change and repeat steps 1â€“3; the banner reappears.
- **Blast radius**:
  - *Callers*: `shouldLog()` is called by `d()` (`:47`), `i()` (`:57`), `w()` (`:65`) and `e()`
    (`:74`), and by `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` (reference index).
    The suppressed methods' callers, enumerated from the reference index: `d()` â€”
    `GrayzoneApplication.kt`, `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`,
    `service/vpn/DnsPacketHelper.kt`, `service/GrayzoneAccessibilityService.kt`,
    `data/StateBackupManager.kt`; `i()` â€” `GrayzoneApplication.kt`, `service/OverlayService.kt`,
    `service/vpn/AdBlockVpnService.kt`, `service/SessionExpiryScheduler.kt`,
    `data/StateBackupManager.kt`, `OemBatteryHelper.kt` (itself dead â€” see F-061).
  - *Process death*: no effect on state. The practical impact is that a crash-and-restart leaves no
    breadcrumbs, so a process-death investigation has only `w()`/`e()` output to work from.
  - *Config change*: none.
  - *Foreground/background*: none directly, but the loss is felt most in the background, where the
    flush loop (`GrayzoneApplication.kt:128â€“133`) and the backup writer run and where `i()` was the
    only evidence they ran at all.
  - *Cross-process*: `GrayzoneLogger` is used from `:a11y` (`service/GrayzoneAccessibilityService.kt`
    calls `d()`, `w()` and `e()`), so the suppression applies to both processes. `BuildConfig` is a
    compile-time constant, so the two processes agree.
  - *Persistence*: none â€” the logger writes nothing to prefs, Room, or storage.
  - *Release-only*: **no, and that is the finding.** Before the uncommitted change this was
    release-only and intentional. After it, the everyday build behaves identically to release. Note
    the secondary effect: with `BuildConfig.DEBUG` a compile-time `false`, the `if (!shouldLog(â€¦))
    return` guards fold to unconditional returns and R8 can strip the bodies of `d()` and `i()`
    entirely in both variants, so even attaching a debugger will not recover the output.
- **Fix** (minimal): decouple the log gate from the debuggable flag rather than reverting the build
  change, whose signing rationale is sound. Add a `BuildConfig` field on the debug type in
  `app/build.gradle.kts`:

  ```kotlin
  debug {
      isDebuggable = false
      buildConfigField("boolean", "VERBOSE_LOGGING", "true")
      // â€¦existing signing configâ€¦
  }
  release {
      buildConfigField("boolean", "VERBOSE_LOGGING", "false")
      // â€¦existingâ€¦
  }
  ```

  and change `GrayzoneLogger.kt:38` to
  `LogLevel.DEBUG, LogLevel.INFO -> BuildConfig.VERBOSE_LOGGING`. Two lines of build script and one
  line of Kotlin. This restores the original intent â€” verbose in the everyday build, quiet in
  release â€” without giving up the non-debuggable, release-signed everyday APK. **The
  `build.gradle.kts` half belongs to RU-08 / T025**; the `GrayzoneLogger.kt:38` half is RU-01's and
  is the reason this is filed here. Also update the now-false KDoc at `:44` ("Only logs in DEBUG
  builds") and `:54` ("Kept lightweight in production") to name the new flag.
- **Rewrite justification**: not applicable â€” one gate expression and two build-script lines.
- **Test to add**: `jvm-unit` â€” extend
  `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt`, which already exercises
  `shouldLog`, with assertions that `WARN`/`ERROR` are enabled unconditionally and that
  `DEBUG`/`INFO` follow the verbose flag. Because `BuildConfig` fields are not variable at test
  time, this requires `shouldLog` to take the flag as a defaulted parameter â€”
  `fun shouldLog(level: LogLevel, verbose: Boolean = BuildConfig.VERBOSE_LOGGING)`. Before the fix
  no test can distinguish the two channels; after it, the four-way truth table is pinned.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt`
  references `shouldLog` and must be updated for the added parameter. It should not need behavioural
  changes, since the parameter is defaulted.
- **Verification**: `confirmed` (evidence tier **T1** â€” upgraded from T2 during T054/E8).
  Originally confirmed by reading `GrayzoneLogger.kt:37â€“40` and `app/build.gradle.kts:69â€“78` in the
  working tree, plus `git diff app/build.gradle.kts` showing the whole `debug` block is uncommitted
  and absent at `557a963`, with the AGP derivation rule taken as documented behaviour. **That
  inference is no longer needed:** the later `--rerun-tasks` build on JDK 21 regenerated
  `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java`, which was read
  directly and contains `DEBUG = false` alongside `BUILD_TYPE = "debug"`. Step 4 of the trigger
  condition is therefore **already executed and green**, not an outstanding maintainer action.
- **Build scope**: `everyday-build` (the everyday build is exactly what changed)
- **Constitution**: `none` for the Core Principles. It does breach the Development Workflow gate that
  behaviour changes be called out rather than disguised â€” the commit message for the `debug` block
  describes signing and installability and does not mention that it disables all DEBUG/INFO logging.
- **Affected surfaces**: every `d()`/`i()` call site listed under *Callers*. `evidence/r8.md` should
  be re-read after the fix, since restoring the bodies changes what the shrinker keeps.
  **Blast radius is bounded and the bound is now proven** (T054/E8): a sweep of every `BuildConfig.`
  reference in `app/src` returns exactly three â€” `GrayzoneApplication.kt:58` (`VERSION_NAME`, not
  variant-dependent), `GrayzoneLogger.kt:38` (this gate), and `GrayzoneLogger.kt:78` (commented out,
  owned by F-074). **There is no `if (BuildConfig.DEBUG)` branch anywhere in application code, and
  `app/src/debug/` and `app/src/release/` do not exist.** So this finding costs observability and
  nothing else â€” no feature, guard, or protection path changes behaviour on the flag. The fix cannot
  regress runtime behaviour, which is why it stays Medium despite touching every variant.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts)` â€” note the
  subject file `GrayzoneLogger.kt` is itself at `557a963`; only its input changed.
- **Disposition**: `verified`

---

### F-065 â€” Release builds write monitored package names and queried domains to logcat, because WARN and ERROR are ungated and the logger offers no redaction  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneLogger.kt` â†’
  `GrayzoneLogger.shouldLog()` (line 39), `w()` (lines 64â€“67), `e()` (lines 73â€“81)
- **Issue**: `shouldLog` returns `true` unconditionally for `WARN` and `ERROR` (`:39`), so `w()` and
  `e()` emit in release builds, and the logger provides no redaction, hashing, or
  sensitive-argument affordance. Callers therefore interpolate user-identifying values directly.
  Confirmed sites, from a full search of `app/src/main` for `GrayzoneLogger.w(`/`.e(`:
  **queried domains** â€” `service/vpn/AdBlockVpnService.kt:519â€“523` logs `"DNS query failed for
  $domain"` with the literal hostname the user's device resolved, and
  `service/vpn/DnsPacketHelper.kt:149â€“153` logs a DNS-packet parse failure with the throwable
  attached; **monitored package names** â€” `service/SessionExpiryScheduler.kt:57â€“60`, `:64â€“67`,
  `:77â€“80` (`"â€¦session expiry alarm for $packageName"`),
  `service/SessionExpiryReceiver.kt:28â€“32` (`"Failed to deliver session expiry for $packageName"`),
  and `service/OverlayService.kt:1058â€“1061`, `:1067â€“1070`
  (`"Invalid session duration $sessionMins mins for $packageNameâ€¦"`); **behavioural metadata** â€”
  `service/vpn/AdBlockVpnService.kt:142â€“145` logs the remaining VPN hold duration. The app's privacy
  posture is that it collects nothing, and for a self-control app the set of monitored packages and
  the domains the user resolves are among the most sensitive facts about them. Two honest bounds
  keep this at Medium rather than higher. First, these are error and warning paths, not the normal
  path â€” the routine per-query DNS logging goes through `d()`, which is gated (and, per F-064,
  currently dead everywhere). Second, since Android 4.1 `READ_LOGS` is not grantable to third-party
  apps, so the realistic readers are a connected `adb` session, a captured bug report, a
  system/OEM-privileged app, or a rooted device â€” not an arbitrary installed app. What prevents this
  from being Low is that the domain-logging trigger is not rare: `AdBlockVpnService.kt:519` fires on
  ordinary DNS failure, which happens routinely on flaky mobile networks, captive portals, and
  airplane-mode transitions, so a meaningful sample of browsing behaviour accumulates in the release
  log buffer over normal use.
- **Trigger condition**:
  1. Install a release build. Enable the AdBlock VPN and monitor at least one app.
  2. `adb logcat -c`, then `adb logcat -s Grayzone`.
  3. Put the device on a flaky or captive-portal network, or toggle airplane mode mid-browse, and
     open a browser. Observe `E/Grayzone [DNS] DNS query failed for <hostname>` lines naming real
     sites.
  4. Force an alarm-scheduling failure (e.g. exhaust the alarm quota, or revoke the exact-alarm
     capability) and observe `E/Grayzone [OVERLAY] Failed to arm session expiry alarm for
     <package>`.
  5. `adb bugreport` and grep the captured log for the same strings, to confirm they survive into an
     artifact users routinely share with support.
- **Blast radius**:
  - *Callers*: `shouldLog()` â€” `d()` (`:47`), `i()` (`:57`), `w()` (`:65`), `e()` (`:74`), and
    `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt`. `w()` â€” `service/OverlayService.kt`,
    `service/vpn/AdBlockVpnService.kt`, `service/vpn/DnsResolverPool.kt`,
    `service/vpn/DnsPacketHelper.kt`, `service/vpn/VpnHoldExpiryScheduler.kt`,
    `service/GrayzoneAccessibilityService.kt`, `service/SessionExpiryScheduler.kt`,
    `data/StateBackupManager.kt`, `GrayzoneApplication.kt`, `AppUtils.kt`, `OemBatteryHelper.kt`.
    `e()` â€” `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`,
    `service/vpn/DnsResolverPool.kt`, `service/vpn/DnsPacketHelper.kt`,
    `service/vpn/VpnHoldExpiryScheduler.kt`, `service/GrayzoneAccessibilityService.kt`,
    `service/SessionExpiryScheduler.kt`, `service/SessionExpiryReceiver.kt`, `data/CleanupWorker.kt`.
  - *Process death*: irrelevant to the leak â€” logcat is a kernel-backed ring buffer that outlives the
    process, so records written before a crash remain readable afterwards.
  - *Config change*: none.
  - *Foreground/background*: worse in the background. The DNS paths run inside `AdBlockVpnService`
    with no UI present, so the user has no indication that anything is being written.
  - *Cross-process*: yes â€” `service/GrayzoneAccessibilityService.kt` runs in `:a11y` and calls `w()`
    and `e()`. Its current messages (`:101â€“104`, `:107â€“111`, `:125â€“128`) carry no package names, but
    that process observes every foreground app change, so it is the highest-risk place for a future
    message to interpolate one. A redaction affordance in the logger is what prevents that.
  - *Persistence*: the logger writes nothing to prefs, Room, or storage. Logcat is the persistence
    surface, and it is outside the app's control.
  - *Release-only*: **yes.** This is the release-build behaviour specifically: the debug/info channel
    is gated, the warn/error channel is not. R8 does not remove `w()`/`e()` bodies because the guard
    is not constant-foldable to `false`. A fix that gates on a `BuildConfig` constant would let R8
    strip the calls; a fix that redacts at the interpolation site would not. Redaction is preferable â€”
    losing error logging in release would make field diagnosis impossible.
- **Fix** (minimal): the root cause owned here is that the logger has no way to mark an argument as
  sensitive, so add one â€” a small redaction helper in `GrayzoneLogger.kt` and a per-level policy,
  rather than silencing `w()`/`e()`:

  ```kotlin
  /** Stable, non-reversible label for a user-identifying value; full value only when verbose. */
  fun redact(value: String?): String =
      if (value.isNullOrEmpty()) "<none>"
      else if (BuildConfig.VERBOSE_LOGGING) value
      else "#" + Integer.toHexString(value.hashCode())
  ```

  (`VERBOSE_LOGGING` is the flag F-064 introduces; until then, `BuildConfig.DEBUG` works.) The eight
  call sites then become e.g. `"DNS query failed for ${GrayzoneLogger.redact(domain)}"`. A hash keeps
  the logs correlatable for diagnosis â€” repeated failures on one domain still group â€” without naming
  it. **Scope split**: the `redact` helper and the policy decision are RU-01's; the eight call-site
  edits belong to T018 (`OverlayService`, `SessionExpiryScheduler`, `SessionExpiryReceiver`) and
  T019 (`AdBlockVpnService`, `DnsPacketHelper`), and are listed under *Affected surfaces* so those
  tasks can apply them.
- **Rewrite justification**: not applicable â€” one added function plus one-token call-site changes.
  A blanket `shouldLog(WARN/ERROR) -> BuildConfig.VERBOSE_LOGGING` was considered and **rejected**:
  it would delete all release diagnostics, including the VPN and overlay failures that are the only
  evidence of the Critical findings elsewhere in this audit.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/LoggerRedactionTest.kt`,
  asserting that `redact("instagram.com")` does not contain `"instagram"` when the verbose flag is
  off, returns the input when it is on, is stable across calls for the same input, and maps
  `null`/empty to a fixed placeholder. Fails before the fix (no such function); passes after.
- **Existing test conflict**: none for the new function.
  `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` asserts the current `shouldLog`
  behaviour and is unaffected, because the fix does not change the level policy.
- **Verification**: `confirmed` (evidence tier T2) for the leak itself.
  Confirmed by reading `GrayzoneLogger.kt:39` (unconditional `true`) and by an exhaustive search of
  `app/src/main` for `GrayzoneLogger.w(`/`GrayzoneLogger.e(`, which enumerated the eight
  interpolating sites listed in *Issue*. No device is required to establish that the strings are
  written. What is **not** established statically is the *rate* â€” how many distinct domains a real
  user's device would deposit in the log buffer over a day.
  - *Missing*: a device on a typical mobile network, running a release build.
  - *Procedure*: steps 1â€“5 above over a 24-hour period of normal use; then
    `adb logcat -d -s Grayzone | grep -c "DNS query failed for"` and count distinct hostnames.
  - *Severity effect*: if the count is effectively zero on a healthy network, the exposure is
    limited to the package-name sites and drops to **Low**. If distinct domains accumulate at any
    meaningful rate, **Medium** stands. It does not reach High, because exploitation still requires
    adb, root, or system privilege.
- **Build scope**: `release-only`
- **Constitution**: `none` of Iâ€“VI directly. It breaches the Code Quality Standard that
  "I/O and service-boundary code MUST log failures with actionable context â€¦ **without leaking user
  data**" â€” the second clause is violated at all eight sites.
- **Affected surfaces**: `service/vpn/AdBlockVpnService.kt:519â€“523` and `:142â€“145` (T019);
  `service/vpn/DnsPacketHelper.kt:149â€“153` (T019); `service/SessionExpiryScheduler.kt:57â€“60`,
  `:64â€“67`, `:77â€“80` (T018); `service/SessionExpiryReceiver.kt:28â€“32` (T018);
  `service/OverlayService.kt:1058â€“1061`, `:1067â€“1070` (T018). Also
  `SharedComponents.kt:209`, which bypasses `GrayzoneLogger` entirely and so cannot be redacted at
  all â€” see F-075.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-066 â€” The only in-app VPN/overlay reconciliation runs in a startup `LaunchedEffect(Unit)` that never re-runs on resume, so the restore performed by `onResume` is not acted on until the Activity is recreated  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainScreen()`'s
  `LaunchedEffect(Unit)` (lines 247â€“265), against `MainActivity.onResume()` (lines 42â€“53) and
  `MainAppContent()`'s `DisposableEffect` (lines 211â€“217)
- **Issue**: The Activity re-runs the backup-restore probe on **every** resume (`:47â€“48`), and
  `MainAppContent` re-evaluates which screen to show on every `ON_RESUME` (`:211â€“217`). But the work
  that *acts on* a restore â€” starting `OverlayService` (`:257`) and reconciling the VPN against its
  persisted intent via `reconcileVpnState` (`:258â€“264`) â€” sits in a `LaunchedEffect(Unit)` inside
  `MainScreen`. Keyed on `Unit`, it runs once per composition of `MainScreen` and never again while
  that composition survives, which it does across any number of resumes as long as `evaluateScreen()`
  keeps returning `Screen.MAIN`. The result is an asymmetry that the code's own comments show was
  not intended: `reconcileVpnState`'s KDoc (`:112â€“120`) says it exists to handle "notably after a
  backup restore, which brings back `VPN_ENABLED` and the 24h hold timestamps but does not restart
  the service, leaving the UI showing a hold countdown while the VPN is actually off" â€” yet the
  restore that creates that exact condition is triggered from `onResume`, which cannot reach it. The
  user-visible consequence: a user who clears app data while the app is merely backgrounded, then
  returns to it, gets their prefs restored (locks come back, the VPN screen shows a live hold
  countdown) while the tunnel stays off and no overlay service is running. Protection is off and the
  UI says it is on â€” the app's worst failure mode. The same gap applies to any mid-session loss:
  if `OverlayService` is killed by the OEM (F-061) or `AdBlockVpnService` is revoked while the user
  is inside the app, returning to the foreground does not restart either. Only a full Activity
  recreation does â€” which, because no `android:configChanges` is declared
  (`AndroidManifest.xml:55â€“63`), means a rotation or a system dark-mode toggle silently repairs the
  app while a plain resume does not. That is a strange enough coupling that it will not be
  rediscovered by anyone reading either half alone.

  Assessed separately, as asked: re-running `StateBackupManager.restoreIfWiped` on **every** resume
  is **correct** and should not be changed. The user can reach Settings â†’ Clear data and return in
  seconds, so the probe must be at the resume boundary to be a real anti-tamper guarantee, and the
  early return at `StateBackupManager.kt:107` makes it a cheap no-op once live state is present. The
  defect is not that it runs too often; it is that its consequences are wired to a different, rarer
  event. (Its *threading* is T020's finding and is not re-filed here.)
- **Trigger condition**:
  1. Set up the app fully: locks configured, AdBlock VPN enabled with an active 24-hour hold.
  2. Press Home to background the app; do **not** swipe it from Recents, so the Activity survives.
  3. `adb shell pm clear com.grayzone.app` â€” or, on-device, Settings â†’ Apps â†’ Grayzone â†’ Clear data.
  4. Return to the app from Recents. `onResume` fires, `restoreIfWiped` restores prefs, and the VPN
     screen shows a live hold countdown.
  5. Confirm no tunnel exists: `adb shell dumpsys connectivity | grep -i vpn`, and confirm the
     overlay service is not running: `adb shell dumpsys activity services com.grayzone.app`.
  6. Rotate the device. Observe the Activity is recreated, `LaunchedEffect(Unit)` re-runs, and both
     services now start â€” proving the reconciliation logic is correct and only its trigger is wrong.
- **Blast radius**:
  - *Callers*: `MainScreen()` is composed by `MainAppContent()` at `:224` (the `Screen.MAIN` branch);
    `MainAppContent()` is called from `MainActivity.onCreate()` at `:75` (reference index,
    `MainActivity.kt` section). `reconcileVpnState()` (`:121`) and `startRestoredVpn()` (`:161`) are
    file-private with callers `MainScreen`'s `LaunchedEffect` (`:258`) and, for `startRestoredVpn`,
    also `reconcileVpnState:152` and the consent launcher callback at `:243`.
    `requestAllFilesAccessOnce()` (`:87`) has two callers: `MainActivity.onResume()` (`:50`) and the
    same `LaunchedEffect` (`:256`). `MainActivity` itself is referenced by `AndroidManifest.xml`
    (launcher), `service/OverlayService.kt:745`, `:1199` and
    `service/vpn/AdBlockVpnService.kt:611` (notification content intents).
  - *Process death*: process death is the one path that *works* â€” a cold start recreates the
    Activity, so `LaunchedEffect(Unit)` runs and reconciliation happens. That is why the gap is easy
    to miss in testing: killing the app to test it hides the bug.
  - *Config change*: a configuration change also recreates the Activity and therefore also repairs
    the state, since `MainActivity` declares no `android:configChanges`
    (`AndroidManifest.xml:55â€“63`). Any fix that adds `configChanges` for other reasons would remove
    this accidental repair path and make the defect strictly worse â€” worth noting before someone
    adds it to stop the tab resetting (F-076).
  - *Foreground/background*: this is the axis the defect lives on. A background â†’ foreground
    transition is exactly the event that should re-reconcile and is exactly the one that does not.
  - *Cross-process*: not reachable from `:a11y`. `MainScreen` is main-process UI. Note the related
    hazard already recorded in `evidence/cross-process-inventory.md`: `ProtectionStatus.vpnActive`,
    which `reconcileVpnState` reads at `:137`, is in-memory main-process state, so it reports `false`
    after any main-process restart regardless of the tunnel's real condition â€” which makes
    reconciliation on resume more important, not less.
  - *Persistence*: `reconcileVpnState` writes prefs on one path â€” clearing `VPN_ENABLED` and the
    hold record when the hold has elapsed (`:132â€“133`). Running it more often does not risk
    duplicate writes: `:123` returns immediately when `VPN_ENABLED` is false, and `:137` returns
    when the tunnel is already up, so the whole function is idempotent by construction. That is what
    makes the fix safe.
  - *Release-only*: no. Compose effect keying is a runtime property; `evidence/r8.md` reports no
    removals in `MainActivity`.
- **Fix** (minimal): move the reconciliation into the same lifecycle observer that already exists
  rather than adding a second one. `MainAppContent` already installs a `DisposableEffect` observing
  `ON_RESUME` (`:211â€“217`); `MainScreen` should do the equivalent for its startup block, or the
  reconciling half of it. Smallest correct change â€” inside `MainScreen`, replace the
  `LaunchedEffect(Unit)` body's last two statements with a resume-scoped repeat:

  ```kotlin
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(lifecycleOwner) {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
          context.startForegroundService(Intent(context, OverlayService::class.java))
          reconcileVpnState(context) { consentIntent -> â€¦ }
      }
  }
  ```

  The one-shot parts â€” the `POST_NOTIFICATIONS` request (`:248â€“255`) and
  `requestAllFilesAccessOnce` (`:256`) â€” should stay in the `LaunchedEffect(Unit)`, since both are
  already idempotent by their own flags but there is no reason to re-evaluate them each resume.
  `repeatOnLifecycle` is already the codebase's established pattern for exactly this
  (`SharedComponents.kt:42`, `:66`), so this uses an idiom the project already owns. Starting
  `OverlayService` repeatedly is safe: `startForegroundService` on a running service delivers
  another `onStartCommand`, which the service already handles.
- **Rewrite justification**: not applicable â€” the effect body is moved, not rewritten. A larger
  alternative (hoisting reconciliation into `MainActivity.onResume` next to the restore probe) was
  considered and **rejected**: `reconcileVpnState` needs the Compose `vpnConsentLauncher`
  (`:239â€“245`) to raise the consent dialog, and moving it out of composition would mean rebuilding
  that with the older `onActivityResult` machinery.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/VpnReconciliationIdempotencyTest.kt`, asserting that the
  decision function behind `reconcileVpnState` is idempotent and safe to run on every resume: given
  `VPN_ENABLED = false` it takes no action; given an elapsed hold it clears the flag once and takes
  no action on a second call; given an active hold and `vpnActive = true` it takes no action. This
  requires extracting the decision from the Android calls in `reconcileVpnState` (`:121â€“154`) into a
  pure function, in the style the codebase already uses for `VpnHoldLock` â€” see the existing
  `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt`. Before the fix there is no
  test that reconciliation is resume-safe; after it, the idempotency the fix depends on is pinned.
- **Existing test conflict**: none. `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt`
  covers `VpnHoldLock.isHoldActive`/`remainingMs`, which the fix does not change.
- **Verification**: `confirmed` (evidence tier T2) for the mechanism.
  Confirmed by reading `MainActivity.kt:247` (`LaunchedEffect(Unit)` â€” keyed on a constant, so it
  runs once per composition), `:211â€“217` (the resume observer that exists and only re-evaluates
  `currentScreen`), `:42â€“53` (the restore probe on every resume), and `AndroidManifest.xml:55â€“63`
  (no `configChanges`, so recreation on rotation is the accidental repair path). That
  `LaunchedEffect(Unit)` does not re-run on an Activity resume without recomposition is the
  documented Compose contract.
  - *Missing*: device confirmation of the end-to-end user-visible symptom in step 4 â€” specifically
    that the VPN screen renders a live hold countdown while no tunnel exists.
  - *Procedure*: steps 1â€“6 above on any API 29+ device.
  - *Severity effect*: **Medium** stands on the static mechanism alone. If step 4 shows the UI
    actively claiming protection is on while it is off, this becomes **High** â€” protection degraded
    with an affirmatively misleading indicator is worse than protection merely absent.
- **Build scope**: `everyday-build`
- **Constitution**: `none` of Iâ€“VI. This is a lifecycle-correctness defect, not a principle breach.
- **Affected surfaces**: `service/BootReceiver.kt` implements the mirror-image reconciliation that
  `reconcileVpnState`'s KDoc says it mirrors (`:117`); the two must stay in step, and a divergence
  there is T018's. `MainActivity.onResume()` (`:42â€“53`) is the trigger that currently has no
  consumer.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-067 â€” Neither `GrayzoneApplication` nor any other component handles `onTrimMemory`/`onLowMemory`, and the one cache-release method in the root package is reachable only from dead code  [Medium]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’ the class as a whole
  (lines 32â€“304); and `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’
  `AppMetadataCache.invalidate()` (lines 43â€“46)
- **Issue**: A search of all of `app/src/main` for `onTrimMemory`, `onLowMemory`, `TRIM_MEMORY` and
  `ComponentCallbacks` returns **zero matches**. Neither the `Application` nor any Activity or
  Service participates in Android's memory-pressure protocol, so the platform has no lever to make
  this app shed memory short of killing the process. That matters more than usual here because of
  what the process holds: roughly 10 MB of Bloom filters loaded by `BlocklistManager` for DNS
  filtering, plus `AppMetadataCache` (`AppUtils.kt:21â€“47`), which retains one `AppInfo` per
  launchable app each holding a 40Ã—40 `RGB_565` bitmap (`:15`, `:96â€“101`) â€” on the order of a few
  hundred KB for a typical device, held for the process's entire life. The cache's expiry logic
  gates only `get()` (`:26â€“33`); `peek()` (`:36`) returns `cachedApps` regardless of age, and
  `cachedApps` is never nulled except by `invalidate()` (`:43â€“46`) â€” whose sole caller is
  `refreshInstalledApps()` (`:73`), which is itself unreferenced (F-071). **The only memory-release
  path in the root package is unreachable.** The consequence for this app is specific: memory
  pressure resolves as process death, and process death for a self-control app means the overlay
  and VPN services stop until something restarts them â€” the same silent protection loss as F-061,
  reached by a different route. The mitigating fact, stated plainly: both services run in the
  foreground with notifications, which places the process high in the low-memory killer's ranking,
  so on a device with adequate RAM this may never fire. That is why this is Medium and not High.
- **Trigger condition**:
  1. Install on a low-RAM device (2 GB or less) or use `adb shell am set-watch-heap`-style pressure.
  2. Enable the AdBlock VPN so the Bloom filters are resident, and open the Apps tab so
     `AppMetadataCache` is populated with bitmaps.
  3. Record the baseline: `adb shell dumpsys meminfo com.grayzone.app`.
  4. Apply pressure by launching several large apps in sequence, or
     `adb shell am send-trim-memory com.grayzone.app RUNNING_CRITICAL`.
  5. Observe the app takes no action â€” re-run `dumpsys meminfo` and confirm the native and Java heap
     are unchanged, since no `onTrimMemory` callback exists to receive the signal.
  6. Continue applying pressure and watch for the kill:
     `adb logcat -d | grep -iE "lowmemorykiller|am_kill.*grayzone"`. Then verify protection stopped
     by opening a monitored app and confirming no friction overlay appears.
- **Blast radius**:
  - *Callers*: there are none to enumerate â€” the defect is an **absent** override, so the relevant
    fact is that `GrayzoneApplication` (referenced from `AndroidManifest.xml` via `android:name`,
    plus `MainActivity.kt`, `data/ConfigValidator.kt`, `data/RuntimeSessionReset.kt` and
    `data/StateBackupManager.kt` per the reference index) never receives the callback because it
    never declares it. For the release path that would be wired up:
    `AppMetadataCache.invalidate()` â€” 1 caller, `refreshInstalledApps()` (`:73`), which has 0
    callers; `AppMetadataCache.peek()` â€” 1 caller, `peekCachedApps()` (`:67`), which is called from
    `ui/AppsScreen.kt`; `AppMetadataCache.get()` â€” 1 caller, `getInstalledAppsCached()` (`:54`).
  - *Process death*: this finding is about the *cause* of process death. Nothing that would be
    released holds unrecoverable state: the Bloom filters reload from disk and `AppMetadataCache`
    rebuilds from `PackageManager`, so a trim callback is safe to implement.
  - *Config change*: no effect; `AppMetadataCache` is a process-level `object` and survives Activity
    recreation, which is the point of `peek()` (`:35`, "instant UI paint on screen re-entry").
  - *Foreground/background*: the relevant callback level is `TRIM_MEMORY_UI_HIDDEN`, delivered when
    the app's UI goes away while the process lives â€” exactly this app's steady state, since the
    services keep running with no Activity. That is the highest-value trim level to implement and
    the one currently ignored.
  - *Cross-process*: `:a11y` is a separate process with its own memory budget and is trimmed and
    killed independently. It holds no large caches, so it is not the concern; but a fix must not
    assume a single process, and `GrayzoneApplication.onCreate`'s guard at `:54` means any
    `onTrimMemory` override would need to be safe to run in both processes (it is called before the
    guard's effect, since `onTrimMemory` is a separate callback).
  - *Persistence*: none. Everything at risk is a rebuildable in-memory cache; no fix writes anything.
  - *Release-only*: no. `evidence/r8.md` records no removals here beyond the already-noted
    `OemBatteryHelper`. Note that if `refreshInstalledApps()` is deleted per F-071 without wiring
    `invalidate()` to a trim callback, `invalidate()` becomes dead too and would be swept next.
- **Fix** (minimal): override `onTrimMemory` in `GrayzoneApplication` and release the one cache that
  already has a release method:

  ```kotlin
  override fun onTrimMemory(level: Int) {
      super.onTrimMemory(level)
      if (level >= TRIM_MEMORY_UI_HIDDEN) clearAppMetadataCache()
  }
  ```

  with a new top-level `fun clearAppMetadataCache() = AppMetadataCache.invalidate()` in
  `AppUtils.kt` beside the existing `peekCachedApps()` (`:67`), which is the file's established
  pattern for exposing the private object. Roughly six lines across two files, and it makes
  `invalidate()` live rather than dead â€” resolving part of F-071 as a side effect. The Bloom filters
  are deliberately **not** included: they are the VPN's working set and dropping them would disable
  filtering, so trimming them is a `BlocklistManager` design question for T019, not a one-line change
  here. Note this makes memory pressure *safer*, not zero-risk; the honest claim is that it gives the
  platform a lever it currently does not have.
- **Rewrite justification**: not applicable â€” one override plus one accessor.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/AppMetadataCacheTest.kt`,
  asserting that after `set(list)` both `get()` and `peek()` return the list, and that after
  `invalidate()` **both** return `null`. Before the fix the assertion holds but nothing calls
  `invalidate()`; the test's value is that it pins the release contract the new trim handler depends
  on, and it prevents the sweep tasks from deleting `invalidate()` as dead code. Requires making
  `AppMetadataCache` internal rather than private, or testing through the new
  `clearAppMetadataCache()`/`peekCachedApps()` pair â€” the latter is preferable, as it keeps the
  object private.
- **Existing test conflict**: none. No test references `AppMetadataCache`, `peekCachedApps`, or
  `getInstalledAppsCached`.
- **Verification**: `needs-verification` (evidence tier T4).
  The *absence* is `confirmed` statically: a search of `app/src/main` for `onTrimMemory`,
  `onLowMemory`, `TRIM_MEMORY` and `ComponentCallbacks` returns zero matches, and
  `AppUtils.kt:43â€“46`/`:72â€“75` show the invalidation path's only caller is unreferenced. What needs a
  device is the *consequence*.
  - *Missing*: a low-RAM device and a measurement of whether this process is actually reclaimed
    under pressure, given both services hold foreground notifications.
  - *Procedure*: steps 1â€“6 above, comparing `dumpsys meminfo` before and after
    `am send-trim-memory â€¦ RUNNING_CRITICAL`, and running a 24-hour soak with heavy app-switching
    while watching for `lowmemorykiller` entries naming the package.
  - *Severity effect*: if the process is observed being killed under realistic pressure while a lock
    is active, this is **High** â€” protection stops silently, which the rubric ranks above a visible
    error. If the foreground-service priority keeps it resident through the soak, it drops to
    **Low** (unreleased caches with no observed user impact, plus the dead `invalidate()`).
- **Build scope**: `everyday-build`
- **Constitution**: I, for the transitively-dead `AppMetadataCache.invalidate()` â€” the fix resolves
  it by making the method reachable rather than by deleting it.
- **Affected surfaces**: `service/vpn/BlocklistManager.kt` holds the ~10 MB of Bloom filters and is
  the larger half of the memory footprint; whether any of it is trimmable belongs to T019.
  `ui/AppsScreen.kt` consumes `peekCachedApps()` and must tolerate a `null` return after a trim â€” it
  already does, since `peek()` is declared nullable (`:36`).
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)` â€” `AppUtils.kt`
  is at `557a963`.
- **Disposition**: `verified`

---

### F-068 â€” Two orphaned preference keys have no migration mechanism, and the anti-tamper backup re-restores them through the very reset that would have cleared them  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/PrefsKeys.kt` â†’ the `PrefsKeys` object as the
  declared single source of truth for preference keys (lines 4â€“87); orphans are
  `grayscale_enabled` and `budget_locked_`, which no longer appear in it
- **Issue**: `evidence/prefs-inventory.md` established that `grayscale_enabled` (superseded by
  `theme_mode` in commit `0a41a1a`) and `budget_locked_` remain in the `GrayzonePrefs` file on any
  device upgraded from a version that wrote them. A search of `app/src` confirms neither string, nor
  any constant naming them, appears anywhere in the current source â€” so they are read by nothing,
  written by nothing, and removed by nothing. The app has **no** SharedPreferences migration
  mechanism of any kind: `PrefsKeys.kt` declares only current keys, and there is no version counter,
  no `onUpgrade` equivalent, and no removal pass. The aggravating detail is the interaction with the
  anti-tamper backup: `StateBackupManager.kt:167` snapshots `prefs.all`, so the orphans are captured
  and re-applied by `restoreIfWiped`. A user who clears app data â€” the one action that would have
  removed them â€” gets them back. They are, in effect, permanent. The user-visible consequence today
  is nil: nothing reads them, so nothing behaves wrongly. What they cost is future correctness and
  file size. `budget_locked_` is a per-package *prefix*, so on a device with many monitored apps it
  is not two entries but one per app, all of them decrypted, Gson-parsed and re-committed on the
  main thread by the restore chain that T020 already flags as an ANR risk. And a future feature that
  reintroduces either name â€” plausible for `budget_locked_`, given the app has an active daily-budget
  feature â€” would silently inherit stale values from an unrelated older release.
- **Trigger condition**:
  1. Install a release predating commit `0a41a1a` (the `grayscale_enabled` â†’ `theme_mode` change),
     toggle the grayscale setting, and configure a daily budget so `budget_locked_<pkg>` is written.
  2. Upgrade in place to the current build.
  3. `adb shell run-as com.grayzone.app cat shared_prefs/GrayzonePrefs.xml | grep -E
     "grayscale_enabled|budget_locked_"`. Observe both are still present.
  4. Grant all-files access and background the app so a backup is written.
  5. `adb shell pm clear com.grayzone.app`, relaunch to trigger `restoreIfWiped`, and repeat step 3.
     Observe the orphans have returned.
- **Blast radius**:
  - *Callers*: **none** â€” that is the definition of the defect. Enumerated for the keys that would
    host the fix: `PrefsKeys` is referenced by 21 main-source files (reference index). The
    superseding key `THEME_MODE` (`PrefsKeys.kt:10`) has one writer, `ui/SettingsScreen.kt:136`, and
    three readers: `MainActivity.kt:60`, `:67`, `ui/SettingsScreen.kt:38`, `ui/theme/Theme.kt:28`.
    `data/StateBackupManager.writeBackup`, which propagates the orphans, is called from
    `GrayzoneApplication.kt:98` and `:139`.
  - *Process death*: no effect â€” these are on-disk preference entries and survive everything.
  - *Config change*: no effect.
  - *Foreground/background*: no effect on the orphans themselves, but the backup write that
    perpetuates them is triggered on backgrounding (`GrayzoneApplication.kt:116`).
  - *Cross-process*: not applicable â€” per `evidence/cross-process-inventory.md` the `:a11y` process
    never reads or writes preferences, so a one-time cleanup pass in the main process cannot race
    with it. That is what makes the fix safe to run unguarded in `onCreate`.
  - *Persistence*: this is entirely a persistence finding. The fix writes: it removes keys, which
    must be done with the same `apply()` discipline as `validateConfiguration()`'s repair path
    (`GrayzoneApplication.kt:215`), and the removal must also be reflected in any existing backup â€”
    otherwise the next restore reintroduces what was just cleaned. Running the cleanup *after*
    `restoreIfWiped` (i.e. anywhere in `onCreate` at or after `:70`) handles this, because the next
    `writeBackup` snapshots the cleaned `prefs.all`.
  - *Release-only*: no. `evidence/r8.md` confirms `PrefsKeys`' constants are inlined, not removed;
    the object is live.
- **Fix** (minimal): add a one-shot, versioned cleanup â€” the migration mechanism the app currently
  lacks, in its smallest useful form. In `PrefsKeys.kt`, add
  `const val PREFS_SCHEMA_VERSION = "prefs_schema_version"` and a list of retired key names/prefixes.
  In `GrayzoneApplication`, call a new `migratePreferencesIfNeeded()` from `onCreate` immediately
  after `validateConfiguration()` (`:70`), which reads the stored version, and if it is below the
  current one, removes every key whose name equals a retired name or starts with a retired prefix
  (`prefs.all.keys` iteration, since `budget_locked_` is a prefix), then writes the new version. On
  the order of fifteen lines. Deliberately conservative: it removes only names on an explicit
  retired list, never anything unrecognised, so a key added by a newer version and read by an older
  one is untouched.
- **Rewrite justification**: not applicable â€” additive.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/PrefsMigrationTest.kt`, using
  the existing `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`, asserting that a
  preferences map containing `grayscale_enabled`, `budget_locked_com.instagram.android`,
  `theme_mode` and `monitored_apps` retains the latter two and loses the former two after one
  migration pass, that the pass is idempotent (a second run changes nothing), and that the schema
  version is recorded. Fails before the fix (no such function); passes after.
- **Existing test conflict**: none. No test references either orphan.
- **Verification**: `confirmed` (evidence tier T2).
  The orphans themselves are established by `evidence/prefs-inventory.md` and are not re-derived
  here. Independently confirmed for this finding: a search of `app/src` for `grayscale_enabled`,
  `budget_locked_`, `GRAYSCALE` and `BUDGET_LOCKED` returns matches only in the audit documents â€”
  zero in application source â€” establishing that nothing reads, writes, or removes them. The
  re-restoration mechanism is confirmed by reading `StateBackupManager.kt:167` (`prefs.all`) against
  `GrayzoneApplication.kt:64` (restore before any other prefs access).
- **Build scope**: `everyday-build`
- **Constitution**: I. Retired keys are unreferenced state that Principle I requires be removed
  rather than left in place; the constitution's scope is "code, resources, or files", and persisted
  keys the app can no longer explain are the same failure mode.
- **Affected surfaces**: `data/StateBackupManager.kt:167` (`prefs.all` snapshot) is what makes the
  orphans permanent; the schema-gate finding against `applyInto` is T020's and the fix here should
  land after it, so the restore path also drops retired keys. `evidence/prefs-inventory.md` also
  records `vpn_bypass_apps` as read-but-never-written â€” a related but distinct defect, already filed
  in [`findings-docs.md`](./findings-docs.md) and not re-filed here. Severity is held at **Low**
  rather than the Lowâ€“Medium band in `findings.md`: the concrete impact today is a handful of extra
  entries in a ~1 KB file, and the migration risk is prospective.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-069 â€” `Extensions.millisUntilMidnight()` is unreferenced, duplicates `DateUtils.millisUntilMidnight()`, and returns an absolute timestamp where its name and KDoc promise a duration  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/Extensions.kt` â†’ top-level
  `millisUntilMidnight()` (lines 75â€“84)
- **Issue**: Two defects with one root cause, filed together because neither exists without the
  other. First, the function is unreferenced (reference index, `Extensions.kt` section: 0 callers),
  while `DateUtils.millisUntilMidnight()` (`DateUtils.kt:100â€“108`) is the live one, called from
  `service/OverlayService.kt` and covered by `app/src/test/java/com/grayzone/app/DateUtilsTest.kt`.
  Second â€” and this is why deleting it is not merely cleanliness â€” the two are not equivalent. Both
  build the same calendar for the start of tomorrow, but `DateUtils` returns
  `calendar.timeInMillis - System.currentTimeMillis()` (`:107`), a duration, while the `Extensions`
  copy returns `cal.timeInMillis` (`:83`), an absolute epoch timestamp, under the KDoc
  "Milliseconds until local midnight" (`:75`). The dead copy is wrong by roughly 1.8 Ã— 10Â¹Â² and has
  the name and documentation of the correct one. There is no user-visible consequence today,
  precisely because nothing calls it â€” but the failure mode if anyone does is severe and quiet: it
  would be passed to a `delay`, a `postDelayed`, or an `AlarmManager` set-after interval and produce
  a timer ~57 years out, which reads as "the daily reset never fires" rather than as an error.
  Notably this is a *third* copy of the same calendar arithmetic: `DateUtils.startOfNextDayMillis()`
  (`:64â€“73`, private, correct) is the second.
- **Trigger condition**:
  1. Confirm it is unreferenced: search `app/src` for `millisUntilMidnight` and observe the only
     call site is `service/OverlayService.kt`, which resolves to `DateUtils.millisUntilMidnight()`
     via its `DateUtils.` qualifier.
  2. Demonstrate the divergence without a device, in a scratch JVM test:
     `assertEquals(DateUtils.millisUntilMidnight(), millisUntilMidnight())` fails, the second value
     being an absolute epoch in the order of 1.8 Ã— 10Â¹Â² and the first bounded by 86 400 000.
  3. Observe that R8 does not flag it, because `evidence/r8.md` reports removals by class and this is
     a top-level function compiled into `ExtensionsKt` alongside live members.
- **Blast radius**:
  - *Callers*: **none** for `Extensions.millisUntilMidnight()` (reference index). For contrast, the
    correct `DateUtils.millisUntilMidnight()` has caller `service/OverlayService.kt` plus the test
    `app/src/test/java/com/grayzone/app/DateUtilsTest.kt`. Other members of `Extensions.kt` are
    live and must not be disturbed by the deletion: `getAppName()` (`ui/` screens),
    `formatDuration()` (`app/src/test/java/com/grayzone/app/FormatDurationTest.kt` and `ui/`),
    `getNormalizedRemainingMillis()` (`ui/AppsScreen.kt:206`, `ui/LimitsScreen.kt:98`,
    `Extensions.kt:55`), `isAnyAppLocked()`, `stayedUnderAllBudgets()`, `Prompts`.
  - *Process death*: none â€” a pure function holding no state.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: `ExtensionsKt` is loaded in both processes, but this member is called from
    neither, so removing it cannot affect `:a11y`.
  - *Persistence*: writes nothing.
  - *Release-only*: no. It survives R8 today as a member of a kept file-class; removing it changes
    only the DEX size.
- **Fix** (minimal): delete lines 75â€“84 of `Extensions.kt`. Behaviour-preserving under Principle V's
  standard, because unreachability is demonstrated by reference analysis rather than assumed. Do
  **not** "fix" the return value and keep it â€” that would leave two functions with the same name and
  the same behaviour in the same package, which is the Principle II violation restated. If a caller
  is ever needed, `DateUtils.millisUntilMidnight()` already exists and is tested.
- **Rewrite justification**: not applicable â€” a deletion.
- **Test to add**: none for the deletion itself; Principle V is satisfied by the reference evidence.
  Worth adding while the area is open: extend
  `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` with an assertion that
  `DateUtils.millisUntilMidnight()` is strictly positive and at most 86 400 000, which pins the
  duration-not-timestamp contract and would fail loudly if the wrong implementation were ever
  reinstated.
- **Existing test conflict**: none. `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` exercises
  `DateUtils` members only and does not reference the `Extensions.kt` copy.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by the reference index (0 callers) and by reading both implementations side by side:
  `Extensions.kt:83` returns `cal.timeInMillis`; `DateUtils.kt:107` returns
  `calendar.timeInMillis - System.currentTimeMillis()`.
- **Build scope**: `everyday-build`
- **Constitution**: I (unreferenced function) and II (duplicated logic that a single abstraction â€”
  `DateUtils` â€” already owns, with the duplicate having silently diverged, which is precisely the
  "inconsistent behaviour and missed-branch bugs" the principle names)
- **Affected surfaces**: `DateUtils.startOfNextDayMillis()` (`DateUtils.kt:64â€“73`) is a third copy
  of the same calendar arithmetic. It is private, correct, and serves a different return contract,
  so it is **not** a defect â€” but after this deletion `DateUtils` should own all of it, and
  `millisUntilMidnight()` (`:100â€“108`) could be expressed as
  `startOfNextDayMillis(System.currentTimeMillis()) - System.currentTimeMillis()`. Optional, and
  outside the minimal fix. Aggregation belongs to the dead-code sweep, T059â€“T064.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-070 â€” `AppUtils.isPrivateDnsActive()` is unreferenced, so the Private-DNS bypass its own documentation calls a user-facing warning is never surfaced  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’ `isPrivateDnsActive()`
  (lines 142â€“165, KDoc `:142â€“153`, body `:154â€“165`)
- **Issue**: The function detects whether Android's Private DNS (DoT on port 853) is active and is
  unreferenced (reference index, `AppUtils.kt` section: 0 callers). Its own KDoc states the stakes:
  Private DNS "routes DNS queries directly over TLS to a third-party resolver on port 853,
  completely bypassing our VPN's DNS-interception mechanism" and "the user must be warned so they
  can disable it or understand the limitation" (`:145â€“147`). That warning does not exist anywhere in
  the UI. The consequence is a smaller sibling of F-061: when a user has Private DNS set to
  `hostname` mode, the AdBlock VPN's filtering is inert, while the VPN screen and the ongoing
  notification both report it as active. Unlike F-061, the code here is a *detector*, not a
  mitigation â€” wiring it up buys an honest status indicator, not restored filtering â€” which is why
  this is Low rather than High. Two secondary notes on the body, relevant only if it is wired up:
  the `catch (_: Exception)` at `:162â€“164` discards the exception and returns `false`, i.e. "safe",
  which for a security-posture check is the wrong default direction and is a Principle III concern
  (`evidence/error-handling-inventory.md` classifies the file's catches); and `:158` uses the string
  literal `"private_dns_mode"` with a comment noting the constant it shadows, which is correct
  because `Settings.Global.PRIVATE_DNS_MODE` is `@hide`, and the comment earns its place.
- **Trigger condition**:
  1. On a device, set Settings â†’ Network â†’ Private DNS to a hostname such as `dns.google`.
  2. Enable Grayzone's AdBlock VPN.
  3. Browse to a domain on the blocklist and observe it resolves and loads normally â€” filtering is
     bypassed.
  4. Observe the app's VPN screen and notification still report protection as active, and that no
     warning about Private DNS appears anywhere: search `ui/` for `isPrivateDnsActive` or for any of
     the KDoc's warning language and find nothing.
- **Blast radius**:
  - *Callers*: **none** (reference index). Other members of `AppUtils.kt` are live and must not be
    disturbed: `getInstalledAppsCached()`, `peekCachedApps()`, `getInstalledApps()`,
    `drawableToBitmap()`, `isAccessibilityServiceEnabled()` (called from `MainActivity.kt:190` and
    `ui/` screens), `settingsIntentFor()` (`ui/HomeScreen.kt`), `isBatteryOptimized()` (`ui/`).
    Under the wire-up option the new caller would be `ui/VpnScreen.kt`.
  - *Process death*: none â€” a stateless read of a system setting.
  - *Config change*: none for the function. If wired into a Composable, the result must be re-read on
    resume rather than held in `remember`, since the user can change Private DNS in system settings
    and return â€” the same trap as F-066 and F-076.
  - *Foreground/background*: the setting can change while the app is backgrounded, so any indicator
    must refresh on resume, not only at composition.
  - *Cross-process*: not reachable from `:a11y`. `Settings.Global` is readable from any process, but
    the accessibility service has no reason to consult it.
  - *Persistence*: reads `Settings.Global`; writes nothing.
  - *Release-only*: no. `evidence/r8.md` reports whole-class removal for `OemBatteryHelper` only;
    `AppUtilsKt` is kept for its live members, so this function ships unused in both variants.
- **Fix** (minimal): **two options, as with F-061, and the same reasoning applies at lower stakes.**
  (a) Wire it up: in `ui/VpnScreen.kt`, when the VPN is enabled and `isPrivateDnsActive(context)`
  returns `true`, render a warning row explaining that Private DNS bypasses filtering â€” roughly ten
  lines beside the existing status card, with the read refreshed on `ON_RESUME`. (b) Delete lines
  142â€“165. **Recommendation: (a)**, because the app's honesty about its own protection status is the
  property this audit repeatedly finds broken (F-066, F-061), and this is the cheapest place to
  improve it. But the case is weaker than F-061's â€” a detector without a mitigation only tells the
  user something is wrong â€” so (b) is entirely defensible. If (a) is chosen, also change `:162â€“164`
  to log via `GrayzoneLogger.w` before returning `false`, so a read failure is diagnosable.
- **Rewrite justification**: not applicable under either option.
- **Test to add**: `jvm-unit` â€” under option (a),
  `app/src/test/java/com/grayzone/app/PrivateDnsDetectionTest.kt`, asserting the mode-string
  predicate: `null` and `"off"` are inactive, `"opportunistic"` and `"hostname"` are active. This
  requires extracting the two-line decision at `:160â€“161` into a pure
  `fun isPrivateDnsMode(mode: String?): Boolean`, leaving `isPrivateDnsActive` as the thin
  `Settings.Global` read â€” the same shape the codebase already uses in `AccessibilityServiceIds`.
  Under option (b) no test is added. Fails before (no such function); passes after.
- **Existing test conflict**: none. No test references `isPrivateDnsActive`.
- **Verification**: `confirmed` (evidence tier T1) for the dead-code claim â€” 0 callers in the
  reference index, corroborated by a direct search of `app/src`.
  The *behavioural* claim in the KDoc, that Private DNS fully bypasses the VPN's DNS interception,
  is `needs-verification`:
  - *Missing*: a device with Private DNS in `hostname` mode and a packet capture.
  - *Procedure*: steps 1â€“3 above, with `adb shell dumpsys connectivity | grep -i "private dns"` to
    confirm the mode, and a capture confirming port-853 traffic leaves the device outside the tun
    interface.
  - *Severity effect*: **Low** is driven by the dead code and does not depend on the result. If the
    bypass is confirmed *and* option (a) is declined, the app knowingly ships a silent
    protection-loss condition it can detect and does not report, which would make the omission
    **Medium** on the same reasoning as F-061.
- **Build scope**: `everyday-build`
- **Constitution**: I (unreferenced function) under option (b); resolved by reachability under (a).
- **Affected surfaces**: `service/vpn/AdBlockVpnService.kt` is the component whose guarantee is
  undermined; whether it should degrade or report differently under Private DNS is T019's question.
  `ui/VpnScreen.kt` is the wire-up site under option (a). Aggregation belongs to T059â€“T064.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-071 â€” `AppUtils.refreshInstalledApps()` is unreferenced, which also makes the app-metadata cache's only invalidation path unreachable  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AppUtils.kt` â†’ `refreshInstalledApps()`
  (lines 69â€“75), and the `AppMetadataCache.invalidate()` it alone calls (lines 43â€“46)
- **Issue**: `refreshInstalledApps()` is unreferenced (reference index, `AppUtils.kt` section: 0
  callers). Its KDoc â€” "Force refresh of app cache (call when user manually refreshes)" (`:70`) â€”
  describes a manual-refresh affordance that does not exist in the UI. On its own that is ordinary
  dead code. What makes it worth its own record is the transitive effect:
  `AppMetadataCache.invalidate()` (`:43â€“46`) has exactly one caller, this function (`:73`), so the
  cache has **no reachable way to be cleared**. `AppMetadataCache.get()` (`:26â€“33`) enforces a
  one-minute validity window by returning `null` when stale, but it never nulls `cachedApps`, and
  `peek()` (`:36`) returns the stale list unconditionally by design. The bitmaps therefore live for
  the process's lifetime. The user-visible consequence is minor and worth stating honestly: a newly
  installed app appears in the list within a minute anyway, via `get()`'s expiry, so the missing
  manual refresh costs nothing functionally. The cost is the retained memory, which is F-067's
  subject â€” and the two findings have opposite fixes, which is why the ordering matters.
- **Trigger condition**:
  1. Search `app/src` for `refreshInstalledApps` and observe the only occurrence is the declaration
     at `AppUtils.kt:72`.
  2. Search for `invalidate()` within `AppUtils.kt` and observe its only call is at `:73`, inside
     that unreferenced function.
  3. Open the Apps tab to populate the cache, navigate away, and confirm via
     `adb shell dumpsys meminfo com.grayzone.app` that the bitmap allocation is not released on
     screen exit or on backgrounding.
- **Blast radius**:
  - *Callers*: `refreshInstalledApps()` â€” **none** (reference index).
    `AppMetadataCache.invalidate()` â€” one caller, `refreshInstalledApps()` (`:73`), therefore
    transitively unreachable. For contrast the live neighbours are
    `AppMetadataCache.get()` â† `getInstalledAppsCached()` (`:54`) â† `ui/AppsScreen.kt`;
    `AppMetadataCache.set()` â† `getInstalledAppsCached()` (`:58`);
    `AppMetadataCache.peek()` â† `peekCachedApps()` (`:67`) â† `ui/AppsScreen.kt`.
  - *Process death*: the cache is in-memory and dies with the process, so process death is currently
    the only thing that clears it.
  - *Config change*: no effect â€” `AppMetadataCache` is a process-level `object` that deliberately
    survives Activity recreation, which is `peek()`'s stated purpose (`:63â€“65`).
  - *Foreground/background*: no effect today. Under F-067's fix, backgrounding
    (`TRIM_MEMORY_UI_HIDDEN`) becomes the point at which the cache is cleared.
  - *Cross-process*: not reachable from `:a11y`. `getInstalledApps` is called only from UI paths in
    the main process; the accessibility service does not enumerate packages.
  - *Persistence*: writes nothing â€” an in-memory cache only.
  - *Release-only*: no. `AppUtilsKt` is kept for its live members, so this ships in both variants.
- **Fix** (minimal): **do not delete `invalidate()`.** Two coordinated steps, in this order.
  1. Delete `refreshInstalledApps()` (lines 69â€“75) â€” unreferenced, and its "manual refresh" feature
     does not exist.
  2. Give `invalidate()` a live caller as part of F-067's fix: add
     `fun clearAppMetadataCache() = AppMetadataCache.invalidate()` beside `peekCachedApps()` (`:67`)
     and call it from `GrayzoneApplication.onTrimMemory`.

  If step 1 lands without step 2, `invalidate()` becomes dead and the T059â€“T064 sweep will
  legitimately remove it â€” leaving the cache permanently unclearable and quietly making F-067 worse.
  Flagging that ordering dependency is the main value of this record.
- **Rewrite justification**: not applicable â€” a deletion plus a two-line accessor.
- **Test to add**: covered by F-067's
  `app/src/test/java/com/grayzone/app/AppMetadataCacheTest.kt`, which asserts that after
  `clearAppMetadataCache()` both `get()` and `peek()` return `null`. That test is what keeps
  `invalidate()` demonstrably live. No separate test is added for the deletion; Principle V is
  satisfied by the reference evidence.
- **Existing test conflict**: none. No test references `refreshInstalledApps`,
  `getInstalledAppsCached`, or `AppMetadataCache`.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by the reference index (0 callers for `refreshInstalledApps`) and by reading
  `AppUtils.kt:43â€“46` and `:72â€“75` â€” `invalidate()`'s only call site is inside the unreferenced
  function.
- **Build scope**: `everyday-build`
- **Constitution**: I (unreferenced function, plus one transitively unreachable private method)
- **Affected surfaces**: F-067 (missing `onTrimMemory`) shares the root cause and must be fixed
  together. `ui/AppsScreen.kt` consumes `peekCachedApps()` and already handles a `null` return.
  Aggregation belongs to the dead-code sweep, T059â€“T064, which must be given the ordering
  dependency above.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-072 â€” `DateUtils.formatTimestamp()` and `getTodayStartMillis()` are unreferenced, and `isSameDay()` is exercised only by its own test  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/DateUtils.kt` â†’ `formatTimestamp()`
  (lines 89â€“95), `getTodayStartMillis()` (lines 126â€“136), `isSameDay()` (lines 110â€“115)
- **Issue**: Three members of `DateUtils` have no production caller (reference index,
  `DateUtils.kt` section). `formatTimestamp()` (`:93`) is unreferenced and is the sole reason the
  `timestampFormatter` `ThreadLocal` at `:22â€“24` exists, so a second `SimpleDateFormat` instance is
  allocated per thread that touches `DateUtils` and never used. `getTodayStartMillis()` (`:129`) is
  unreferenced. `isSameDay()` (`:113`) is called only from
  `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` â€” a test asserting behaviour no production
  path depends on, which is the specific pattern the audit brief calls test-only code. There is no
  user-visible consequence; this is a Principle I record, filed so the T059â€“T064 sweep has a
  concrete list. Note also that `getTodayStartMillis()` mutates a `Calendar` initialised to *now*
  and never sets the date, so it is a fourth instance of the calendar arithmetic F-069 already found
  duplicated three times â€” worth knowing before anyone "reuses" it.
- **Trigger condition**:
  1. Search `app/src/main` for `formatTimestamp`, `getTodayStartMillis` and `isSameDay`; observe the
     only occurrences are the declarations in `DateUtils.kt`.
  2. Search `app/src/test` for the same; observe `isSameDay` appears in `DateUtilsTest.kt` and the
     other two appear nowhere.
  3. Observe that `timestampFormatter` (`:22â€“24`) is referenced only by `formatTimestamp` (`:94`),
     making it transitively dead.
- **Blast radius**:
  - *Callers*: `formatTimestamp()` â€” **none**. `getTodayStartMillis()` â€” **none**. `isSameDay()` â€”
    `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` only. Transitively, the private
    `timestampFormatter` (`:22`) â€” one caller, `formatTimestamp()` (`:94`). Live members that must
    not be disturbed: `getCurrentDateKey()` (`data/` and `service/` callers),
    `formatDateKey()` (`data/RuntimeSessionReset.kt:42`, `DateUtils.kt:80`, `:114`),
    `getYesterdayDateKey()`, `millisUntilMidnight()` (`service/OverlayService.kt`),
    `shouldResetDailyRuntimeState()` (`data/RuntimeSessionReset.kt:31`), and the private
    `startOfNextDayMillis()` (`:58`).
  - *Process death*: none â€” pure functions and `ThreadLocal` formatters holding no app state.
  - *Config change*: none. Worth noting the formatters are pinned to `Locale.US` (`:19`, `:23`),
    which is correct for the storage key format and immune to locale changes â€” a deliberate choice
    that should survive any edit here.
  - *Foreground/background*: none.
  - *Cross-process*: `DateUtils` is loaded in both processes (`service/OverlayService.kt` uses it,
    and `:a11y` loads the class transitively), but none of the three dead members is called from
    either, so removal cannot affect `:a11y`.
  - *Persistence*: none of the three writes anything. `formatDateKey`, which does feed persisted
    date keys, is live and out of scope for this record.
  - *Release-only*: no. `evidence/r8.md` does not report `DateUtils` as removed â€” it is kept for its
    live members, so the three dead ones ship in both variants.
- **Fix** (minimal): delete `formatTimestamp()` (`:89â€“95`), the now-unused `timestampFormatter`
  (`:22â€“24`), and `getTodayStartMillis()` (`:126â€“136`). For `isSameDay()` (`:110â€“115`) the choice is
  the maintainer's and is genuinely close: it is a two-line wrapper over the live `formatDateKey`,
  so keeping it costs almost nothing, but Principle I is explicit that a symbol must be reachable
  from a live execution path and a test is not one. Recommendation: delete it and its assertions in
  `DateUtilsTest.kt` together, since a test whose only subject is unused code cannot fail in a way
  anyone acts on. Unreachability is demonstrated by the reference index, satisfying Principle V.
- **Rewrite justification**: not applicable â€” deletions.
- **Test to add**: none. The correct action is deletion, and the audit brief's prohibition on test
  placeholders applies: adding coverage to keep dead code alive would invert Principle I.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` references
  `isSameDay` and its assertions must be removed with the function. Its assertions covering
  `formatDateKey`, `getCurrentDateKey`, `millisUntilMidnight` and `shouldResetDailyRuntimeState`
  are unaffected and must be retained.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by the reference index (`DateUtils.kt` section: 0 references for `formatTimestamp` and
  `getTodayStartMillis`; test-only for `isSameDay`), corroborated by a direct search of `app/src`.
- **Build scope**: `everyday-build`
- **Constitution**: I (unreferenced functions, plus one transitively dead private field)
- **Affected surfaces**: F-069 removes a fourth copy of the same midnight arithmetic from
  `Extensions.kt`; both records feed the dead-code sweep at T059â€“T064, which should apply them in
  one pass so `DateUtils` is reviewed once.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-073 â€” `SetupRequirements.actionLabel()` is test-only, and the one screen that needs it re-derives the string inline  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/SetupRequirements.kt` â†’ `actionLabel()`
  (lines 44â€“46), against `ui/HomeScreen.kt:194`
- **Issue**: `actionLabel()` produces the call-to-action for the next permission to fix â€”
  `"Turn on ${label(it)}"` â€” and its only caller is
  `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` (reference index). The live screen
  that renders exactly that CTA, `ui/HomeScreen.kt:194`, builds the identical string inline:
  `Text("Turn on ${SetupRequirements.label(requirement)}", â€¦)`. So this is not plain dead code; it is
  a single-source-of-truth abstraction that exists, is tested, and is bypassed by the only caller it
  was written for. The consequence is the divergence risk Principle II names: the test pins one
  string and the app renders another built from the same parts, so changing the wording in
  `actionLabel()` leaves the UI unchanged while the suite keeps passing â€” a test that cannot fail
  when its subject's real behaviour changes. `SetupRequirements`' other members are correctly used:
  `missing()` from `ui/HomeScreen.kt:66` and `ui/OnboardingScreen.kt:242`, `label()` from
  `ui/HomeScreen.kt:194` and `ui/OnboardingScreen.kt:243`, `headline()` and `body()` from
  `ui/HomeScreen.kt:133` and `:138`.
- **Trigger condition**:
  1. Search `app/src/main` for `actionLabel`; observe the only occurrence is the declaration at
     `SetupRequirements.kt:45`.
  2. Search `app/src/test`; observe `SetupRequirementsTest.kt` asserts on it.
  3. Read `ui/HomeScreen.kt:194` and observe the same string composed inline from `label()`.
  4. Change the wording in `actionLabel()` â€” for example to `"Enable ${label(it)}"` â€” and run
     `./gradlew :app:testDebugUnitTest`. Observe the suite fails on the changed assertion while the
     app's UI is unaffected, demonstrating the test guards nothing the user sees.
- **Blast radius**:
  - *Callers*: `actionLabel()` â€” `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` only
    (reference index). `label()` â€” `(self)` at `:29` (`headline`) and `:41` (`body`), plus
    `ui/HomeScreen.kt:194` and `ui/OnboardingScreen.kt:243`. `missing()` â€” `ui/HomeScreen.kt:66`,
    `ui/OnboardingScreen.kt:242`. `headline()` â€” `ui/HomeScreen.kt:133`. `body()` â€”
    `ui/HomeScreen.kt:138`. The `SetupRequirement` enum is additionally used by
    `AppUtils.settingsIntentFor()` (`AppUtils.kt:129â€“137`).
  - *Process death*: none â€” a pure function over an enum.
  - *Config change*: none for the function. The screen that would call it re-evaluates permissions on
    resume, which is correct and unchanged by this fix.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y` â€” `SetupRequirements` is consumed only by UI code in
    the main process.
  - *Persistence*: writes nothing.
  - *Release-only*: no. `evidence/r8.md` does not report `SetupRequirements` as removed; it is kept
    for its live members. `actionLabel` may be stripped as unused in release, which is precisely the
    signal that it has no production caller.
- **Fix** (minimal): use it. Replace the inline construction at `ui/HomeScreen.kt:194` with
  `SetupRequirements.actionLabel(missingSetup)` â€” a one-line change at a call site that already has
  `missingSetup` in scope from `:66`. This makes the function live, makes the existing test
  meaningful, and removes the duplicated string composition. Prefer this over deleting
  `actionLabel()`: deletion would satisfy Principle I but leave the CTA string derived at the call
  site, which is the Principle II half of the defect. Note the signatures differ â€”
  `actionLabel` takes the `List<SetupRequirement>` and applies `firstOrNull()` itself (`:46`),
  while `HomeScreen` is inside a per-requirement loop; the call site should pass the list, or the
  loop should be reconsidered, which is T026's to judge. **Scope split**: the observation and the
  function are RU-01's; the `ui/HomeScreen.kt` edit belongs to T026.
- **Rewrite justification**: not applicable â€” a one-line call-site substitution.
- **Test to add**: none. `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` already
  asserts `actionLabel`'s behaviour; the fix makes those existing assertions load-bearing, which is
  the outcome wanted. Adding a new test would not improve on that.
- **Existing test conflict**: none. `SetupRequirementsTest.kt` needs no change â€” after the fix its
  assertions govern a string the user actually sees.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by the reference index (test-only) and by direct search: `actionLabel` appears once in
  `app/src/main` (its declaration) and the duplicated composition is visible at
  `ui/HomeScreen.kt:194`.
- **Build scope**: `everyday-build`
- **Constitution**: I (a symbol reachable only from tests is not on a live execution path) and II
  (the string-derivation the abstraction owns is re-derived at the call site)
- **Affected surfaces**: `ui/HomeScreen.kt:194` (T026 applies the fix). Separately noted for T026 and
  **not filed here**: `ui/OnboardingScreen.kt:242` passes a usage-access flag to `missing()`'s
  `accessibilityEnabled` parameter, while `ui/HomeScreen.kt:66` passes the accessibility flag â€” the
  two screens can therefore disagree about which permission is missing.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-074 â€” `GrayzoneLogger.e()` retains a commented-out Crashlytics block  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneLogger.kt` â†’ `GrayzoneLogger.e()`
  (lines 77â€“80)
- **Issue**: Three lines of commented-out Kotlin sit at the end of `e()`:

  ```kotlin
  // if (!BuildConfig.DEBUG && throwable != null) {
  //     FirebaseCrashlytics.getInstance().recordException(throwable)
  // }
  ```

  Principle IV prohibits this without qualification: "Commented-out code blocks MUST NOT be
  committed or retained." The preceding line 77, `// Future: Production crash reporting
  integration`, is a genuine comment expressing intent and is fine â€” the violation is specifically
  the three disabled statements at `:78â€“80`. There is no user-visible consequence; the harm
  Principle IV names is that the block is untested and unverified while masquerading as context. It
  has already decayed: the app declares no Firebase dependency in `app/build.gradle.kts`, so the
  snippet would not compile if uncommented, and its guard is `BuildConfig.DEBUG` â€” which F-064 shows
  is now `false` in every variant, so even the intent it records is stale. The same pattern appears
  in the class KDoc's "Production crash reporting integration point" (`:11`) and `e()`'s own KDoc
  (`:71`), which promise an integration that does not exist.
- **Trigger condition**:
  1. Open `app/src/main/java/com/grayzone/app/GrayzoneLogger.kt` and read lines 77â€“80.
  2. Confirm the code cannot compile as written: search `app/build.gradle.kts` for `firebase` or
     `crashlytics` and find no dependency and no plugin.
  3. Confirm the guard is stale: `BuildConfig.DEBUG` is `false` in both variants per F-064, so
     `!BuildConfig.DEBUG` is unconditionally true and the block does not express what it appears to.
- **Blast radius**:
  - *Callers*: `e()` is called from `data/CleanupWorker.kt`, `service/OverlayService.kt`,
    `service/vpn/AdBlockVpnService.kt`, `service/vpn/DnsResolverPool.kt`,
    `service/vpn/DnsPacketHelper.kt`, `service/vpn/VpnHoldExpiryScheduler.kt`,
    `service/GrayzoneAccessibilityService.kt`, `service/SessionExpiryScheduler.kt`,
    `service/SessionExpiryReceiver.kt` (reference index). None is affected by the deletion, which
    removes no executable statement.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: `e()` is called from `:a11y` via `service/GrayzoneAccessibilityService.kt:58`
    and `:107`, but removing a comment changes nothing in either process.
  - *Persistence*: none.
  - *Release-only*: no. Comments are not emitted to DEX, so R8 is not involved and the release APK
    is byte-identical after the deletion.
- **Fix** (minimal): delete lines 78â€“80. Keep line 77 if the intent is still current; if crash
  reporting is not planned, delete `:77` too and drop the corresponding claims from the KDoc at
  `:11` and `:71` so the documentation stops describing an integration point that does not exist.
- **Rewrite justification**: not applicable â€” a deletion.
- **Test to add**: none. Removing comments cannot change behaviour, and adding a test would be the
  placeholder pattern the contract prohibits.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by reading `GrayzoneLogger.kt:77â€“80`, and by the absence of any Firebase dependency in
  `app/build.gradle.kts`.
- **Build scope**: `everyday-build`
- **Constitution**: IV (commented-out code retained in a committed file)
- **Affected surfaces**: none â€” the block is self-contained. Aggregation belongs to the sweep at
  T059â€“T064.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-075 â€” `shareApk` leaves a full APK copy in the cache that nothing ever deletes, and reports its failure through `android.util.Log` instead of `GrayzoneLogger`  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/SharedComponents.kt` â†’ `shareApk()`
  (lines 178â€“216); specifically the cache write at `:191â€“195` and the log call at `:209`
- **Issue**: Two small defects in one function, both distinct from the main-thread copy that T026
  owns and neither of which that fix would resolve. First, `shareApk` copies the running APK into
  `cacheDir/shared/Grayzone.apk` (`:191â€“195`) and never removes it. A search of the whole codebase
  for `cacheDir` finds this one site: `data/CleanupWorker` prunes the Room database and does not
  touch the cache directory, so a copy of the APK â€” tens of megabytes â€” persists after the share
  completes and is overwritten, not deleted, on each subsequent share. Android may reclaim `cacheDir`
  under storage pressure, so this is bounded rather than a true leak, but the app is holding a large
  file it has no further use for the moment the chooser is dismissed. Second, the failure path logs
  via `android.util.Log.e("ShareApk", â€¦)` (`:209`) rather than `GrayzoneLogger`. This is the only
  such bypass in the root package. Its consequences are concrete: the message uses a different tag,
  so it is invisible to `adb logcat -s Grayzone`; it is outside the level policy, so it cannot be
  gated; and â€” the reason it matters for this audit â€” it is outside the redaction affordance F-065
  proposes, so it can never be made privacy-safe by a central change. The user-visible consequence
  of both is small, which is why this is Low.
- **Trigger condition**:
  1. Open Settings and tap the share entry (`ui/SettingsScreen.kt:82`). Complete or cancel the
     chooser.
  2. `adb shell run-as com.grayzone.app ls -la cache/shared/`. Observe `Grayzone.apk` at full APK
     size.
  3. Background the app, wait, and re-check. Observe the file persists.
  4. For the logging half: force a failure â€” for example
     `adb shell pm disable-user --user 0 <chooser-capable package>` or fill the cache partition â€”
     then run `adb logcat -s Grayzone` and observe nothing, and `adb logcat -s ShareApk` and observe
     the error, demonstrating it is outside the app's own log channel.
- **Blast radius**:
  - *Callers*: `shareApk()` â€” `ui/SettingsScreen.kt:82`, inside a `.clickable { }` lambda (reference
    index, `SharedComponents.kt` section). That is the only caller. Other members of
    `SharedComponents.kt` are live and unaffected: `rememberResumedTicker()`, `ResumedInterval()`
    (`ui/QuickFocusCard.kt:33`, `ui/DailyBreakCard.kt:37`, `ui/ScheduleSheet.kt:43`), `ActivePill()`,
    `GZCard()`, `SectionLabel()`, `HowItWorksStep()`, `GZLoadingSpinner()`.
  - *Process death*: the cached file survives process death â€” that is what makes it persistent
    rather than transient. A cleanup registered only in memory would not run after a kill; deleting
    on the success path is the reliable placement.
  - *Config change*: a rotation during the chooser recreates the Activity but not the file, so a
    cleanup tied to composition would be skipped. Another argument for deleting inline.
  - *Foreground/background*: the share is user-initiated in the foreground; the file outlives the
    interaction into the background indefinitely.
  - *Cross-process*: the copy is exposed through `FileProvider` (`:196â€“200`) with
    `FLAG_GRANT_READ_URI_PERMISSION` (`:204`), so a *different* app holds a read grant on it while
    the share is in flight. Deletion must therefore not be immediate â€” see the fix.
  - *Persistence*: writes `cacheDir/shared/Grayzone.apk`. `mkdirs()` at `:191` discards its return
    value (`evidence/error-handling-inventory.md` entry 93 classifies this as partial handling),
    though the outer `try` at `:179` does cover the subsequent failure.
  - *Release-only*: no. `evidence/r8.md` does not report `SharedComponents` members as removed.
- **Fix** (minimal): two independent one-liners.
  1. Delete the stale copy before writing the new one, at the top of the function's body â€” the safe
     placement, because it cannot revoke a grant that a receiving app is still using:
     `java.io.File(java.io.File(context.cacheDir, "shared"), "Grayzone.apk").delete()` immediately
     before `:191`. This bounds the residue to one copy between shares rather than one forever, and
     avoids the lifetime problem that deleting after `startActivity` would create.
  2. Replace `:209` with
     `GrayzoneLogger.e(LogComponent.CLEANUP, "Could not share APK", e)`, bringing the call inside the
     level policy and the redaction affordance. The Toast at `:210â€“214` is correct and should stay â€”
     `evidence/error-handling-inventory.md` entry 6 already classifies this catch as compliant for
     telling the user; the change is only about which channel the developer-facing half uses.

  These are deliberately independent of T026's threading fix (moving the copy off the main thread),
  and either ordering works.
- **Rewrite justification**: not applicable â€” two single-line changes.
- **Test to add**: none of value. Both halves are thin wrappers over Android APIs
  (`File.delete`, `Log`) with no extractable decision, and the audit brief prohibits placeholder
  tests. The behaviour that *is* worth pinning â€” that the main-thread copy moves off the main
  thread â€” belongs to T026's fix.
- **Existing test conflict**: none. No test references `shareApk`.
- **Verification**: `confirmed` (evidence tier T2).
  Confirmed by reading `SharedComponents.kt:191â€“195` (write with no delete) and `:209`
  (`android.util.Log.e`), and by a codebase-wide search for `cacheDir` returning this file only â€”
  establishing that no cleanup path exists anywhere, including `data/CleanupWorker`.
- **Build scope**: `everyday-build`
- **Constitution**: `none` of Iâ€“VI for the cache residue. The logging half breaches the Code Quality
  Standard that I/O and service-boundary code log failures with actionable context, by routing
  outside the app's own logging framework where neither the level policy nor F-065's redaction can
  reach it.
- **Affected surfaces**: `ui/SettingsScreen.kt:82` is the single call site and is where T026's
  main-thread finding (`findings.md`, leads table) applies. F-065's redaction fix cannot cover
  `:209` until this record's second half lands.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-076 â€” `MainScreen`'s tab and `MainAppContent`'s screen selection use `remember`, so both are lost on rotation, dark-mode toggle, and process death  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainScreen()`'s
  `selectedTab` (line 231), `MainAppContent()`'s `currentScreen` (line 209), and
  `MainActivity.onCreate()`'s `themeMode` (lines 59â€“61)
- **Issue**: All three are `remember`, not `rememberSaveable`, and `MainActivity` declares no
  `android:configChanges` (`AndroidManifest.xml:55â€“63`), so any configuration change â€” rotation,
  system dark-mode toggle, font-size change, locale change, multi-window resize â€” destroys and
  recreates the Activity and resets each to its initial value. `onCreate` provides no
  `savedInstanceState` handling either (`:55â€“56` calls `super` and goes straight to `setContent`).
  The user-visible consequence is that a user reading the Limits tab who toggles dark mode, or
  rotates, lands back on Home. Two of the three are then self-repairing and only one is a real
  loss: `currentScreen` (`:209`) is re-derived by `evaluateScreen()`, which reads live permission
  state, so recreating it produces the correct value; `themeMode` (`:59â€“61`) is re-read from
  preferences, so it is also correct. Only `selectedTab` (`:231`) has no backing store and is
  genuinely lost. Process death behaves identically â€” worse in that it is not recoverable at all,
  but the app has no long-lived screen state worth restoring beyond the tab, which is why this is
  Low rather than Medium. Recorded because the same `remember`-instead-of-`rememberSaveable` habit is
  the trap that F-061's and F-070's proposed wire-ups would fall into.
- **Trigger condition**:
  1. Open the app and navigate to the Limits tab.
  2. Rotate the device, or toggle system dark mode from the quick-settings tile.
  3. Observe the app returns to the Home tab.
  4. For process death: with the app on Limits, background it, then
     `adb shell am kill com.grayzone.app`, then return from Recents. Observe Home again.
  5. Confirm the cause: `AndroidManifest.xml:55â€“63` declares no `android:configChanges`, so the
     Activity is recreated rather than reconfigured in place.
- **Blast radius**:
  - *Callers*: `MainScreen()` is composed by `MainAppContent()` (`:224`); `MainAppContent()` is
    called from `MainActivity.onCreate()` (`:75`) (reference index, `MainActivity.kt` section).
    `MainActivity` is referenced by `AndroidManifest.xml` (launcher), `service/OverlayService.kt:745`
    and `:1199`, and `service/vpn/AdBlockVpnService.kt:611` (notification content intents) â€” those
    entry points always start a fresh task, so they are unaffected by the saved-state question.
  - *Process death*: `rememberSaveable` covers process death via the saved-instance-state bundle, so
    the fix addresses both this axis and configuration change with the same change. An `enum` is not
    `Parcelable` by default, so `selectedTab` must be saved as its `name` or `ordinal` with a custom
    `Saver`, or be stored as a `String`.
  - *Config change*: the primary axis. Rotation, `uiMode`, `fontScale`, locale and multi-window
    resize all recreate the Activity today.
  - *Foreground/background*: a plain background/foreground cycle preserves everything, since the
    Activity survives. This is the mirror image of F-066, where the plain resume is the broken path
    and recreation is the working one â€” the two findings are worth reading together, because a fix
    for one must not assume the other's behaviour.
  - *Cross-process*: not reachable from `:a11y` â€” this is main-process UI state.
  - *Persistence*: none of the three writes anything as part of this defect. Note `evaluateScreen()`
    *does* write on recreation â€” `ACCESSIBILITY_PROMPTED_FOR_LOSS` at `:196` and
    `ONBOARDING_COMPLETED` at `:199` â€” so every rotation performs two preference writes and, via the
    change listener at `GrayzoneApplication.kt:103â€“105`, flips the backup dirty flag. Harmless
    (both are idempotent writes of the same value) but worth knowing before anyone reduces
    recreation frequency and wonders why backup timing changed.
  - *Release-only*: no. Compose state retention is a runtime property; `evidence/r8.md` reports no
    removals in `MainActivity`.
- **Fix** (minimal): change `:231` from `remember` to `rememberSaveable` with a saver for the `Tab`
  enum â€” smallest form is to hold the name:

  ```kotlin
  var selectedTabName by rememberSaveable { mutableStateOf(Tab.HOME.name) }
  val selectedTab = Tab.valueOf(selectedTabName)
  ```

  or, cleaner, keep the enum and supply
  `rememberSaveable(stateSaver = Saver({ it.name }, { Tab.valueOf(it) }))`. Leave `:209` and
  `:59â€“61` as `remember`: both are re-derived from authoritative sources on recreation, and making
  them saveable would be actively wrong â€” a saved `currentScreen` could restore `Screen.MAIN` after
  the user revoked a permission while the app was away, defeating `evaluateScreen()`. **Do not fix
  this by adding `android:configChanges` to the manifest.** It would appear to solve the symptom but
  would remove the Activity recreation that is currently the only thing repairing F-066's
  reconciliation gap, making a Medium finding worse to fix a Low one.
- **Rewrite justification**: not applicable â€” one state declaration.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/MainScreenStateRestorationTest.kt`, using Compose's
  `StateRestorationTester` to assert that the selected tab survives a simulated state restore.
  **This cannot be executed here** â€” no device or emulator is available â€” so it must be written and
  run by a maintainer. Fails before the fix (tab resets to `HOME`); passes after. A `jvm-unit` test
  is not appropriate: the behaviour under test is Compose's saved-state machinery, and asserting it
  without that machinery would test nothing.
- **Existing test conflict**: none. No test references `MainScreen`, `MainAppContent`, or `Tab`.
- **Verification**: `confirmed` (evidence tier T2).
  Confirmed by reading `MainActivity.kt:209`, `:231` and `:59â€“61` (all `remember`), `:55â€“56`
  (no `savedInstanceState` handling), and `AndroidManifest.xml:55â€“63` (no `android:configChanges`,
  so the default recreation behaviour applies). That `remember` does not survive Activity recreation
  is the documented Compose contract.
- **Build scope**: `everyday-build`
- **Constitution**: `none` of Iâ€“VI. A lifecycle-correctness and polish defect.
- **Affected surfaces**: F-066 depends on the current recreation behaviour and must be read
  alongside this record before anyone changes `configChanges`. F-061's and F-070's proposed
  wire-ups would introduce new Composable state and must use `rememberSaveable` or a preference
  from the start.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-077 â€” `AccessibilityServiceIds.relativeClassName()` and `SetupRequirements.label()` are `public` but used only inside their own files  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AccessibilityServiceIds.kt` â†’
  `relativeClassName()` (lines 36â€“40); and
  `app/src/main/java/com/grayzone/app/SetupRequirements.kt` â†’ `label()` (lines 22â€“25)
- **Issue**: Both are declared `public` (Kotlin's default) while every caller is inside the
  declaring object or, for `label()`, a UI file that could equally use the higher-level helpers.
  `relativeClassName()` is a helper for `isServiceEnabled()` and has no caller outside
  `AccessibilityServiceIds` (reference index: `(self)` `isServiceEnabled()` only). `label()` is
  called from `(self)` `headline()` (`:29`) and `body()` (`:41`), and from `ui/HomeScreen.kt:194`
  and `ui/OnboardingScreen.kt:243` â€” so it is genuinely used externally and is **not** a visibility
  defect on its own; it is listed here because F-073's fix removes the `ui/HomeScreen.kt:194` use,
  after which its only remaining external caller is `ui/OnboardingScreen.kt:243`. There is no
  user-visible consequence. The cost is that a wider-than-necessary surface is what lets call sites
  reach past an abstraction â€” which is exactly how F-073 happened: `label()` being public is what
  made re-deriving the CTA at the call site possible instead of calling `actionLabel()`. Recorded so
  the sweep tightens the surface rather than only deleting.
- **Trigger condition**:
  1. Search `app/src` for `relativeClassName`; observe occurrences only in
     `AccessibilityServiceIds.kt` (declaration at `:37`, call at `:24`) and in
     `app/src/test/java/com/grayzone/app/AccessibilityServiceIdsTest.kt` if it asserts on it
     directly.
  2. Change `fun relativeClassName` to `private fun relativeClassName` and compile. Observe
     `app/src/main` still compiles, confirming no production caller outside the object.
  3. Repeat for `label()` after F-073's fix and observe `ui/OnboardingScreen.kt:243` still requires
     it to be public.
- **Blast radius**:
  - *Callers*: `relativeClassName()` â€” `(self)` `isServiceEnabled()` at `:24` (reference index
    records no external references). `isServiceEnabled()`, the public entry point, is called from
    `AppUtils.isAccessibilityServiceEnabled()` (`AppUtils.kt:114â€“118`) and covered by
    `app/src/test/java/com/grayzone/app/AccessibilityServiceIdsTest.kt`. `label()` â€” `(self)` at
    `:29` and `:41`, plus `ui/HomeScreen.kt:194` and `ui/OnboardingScreen.kt:243`.
  - *Process death*: none â€” pure functions over strings and enums.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: `AccessibilityServiceIds` is reachable from both processes via
    `AppUtils.isAccessibilityServiceEnabled`, but visibility is a compile-time property and narrowing
    it changes no runtime behaviour in either.
  - *Persistence*: writes nothing.
  - *Release-only*: no â€” but note that narrowing visibility helps R8: a `private` function in an
    `object` is a stronger inlining candidate, and `evidence/r8.md` already shows the shrinker
    aggressively inlining small pure helpers in this package (see the `AccessibilityPromptPolicy`
    note under *no-defect-found* in the coverage summary).
  - *Test visibility*: if `AccessibilityServiceIdsTest.kt` asserts on `relativeClassName` directly,
    `private` will break it; `internal` plus `@VisibleForTesting` is then the right narrowing, since
    unit tests compile against the same module. Check before applying.
- **Fix** (minimal): change `relativeClassName` (`:37`) to `private`, or to `internal` with
  `@VisibleForTesting` if the existing test asserts on it directly. Leave `label()` public â€” it has a
  genuine external caller â€” and revisit only if `ui/OnboardingScreen.kt:243` is ever changed to use
  `headline()`/`body()` instead. Two-word change; no behaviour change.
- **Rewrite justification**: not applicable â€” a visibility modifier.
- **Test to add**: none. Visibility narrowing is verified by compilation, and adding a test for it
  would be the placeholder pattern the contract prohibits.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/AccessibilityServiceIdsTest.kt`
  may reference `relativeClassName` directly; if it does, use `internal` +`@VisibleForTesting`
  rather than `private`, and the test needs no edit. Confirm before applying.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by the reference index (`relativeClassName` â€” `(self)` only; `label` â€” self plus two
  `ui/` callers) and by direct search of `app/src/main`.
- **Build scope**: `everyday-build`
- **Constitution**: `none` of Iâ€“VI strictly â€” neither symbol is unreferenced, so Principle I is not
  breached. It supports the Code Quality Standard that call sites depend on abstractions rather than
  on internals.
- **Affected surfaces**: F-073 (`SetupRequirements`) removes one of `label()`'s two external callers
  and should be applied first, so the visibility decision is made against the final call graph.
  Aggregation belongs to T059â€“T064.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-078 â€” The `:a11y` process's preferences isolation is enforced only by an early `return` in `onCreate`, with no guard at the access points it protects  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `GrayzoneApplication.onCreate()` (lines 52â€“54)
- **Issue**: The guard `if (Application.getProcessName() != packageName) return` (`:54`) is correct
  and is the reason several plausible defects are not defects â€” it is credited as a positive example
  below and in the coverage summary. The finding is about how thinly the invariant it establishes is
  held. The comment at `:52â€“53` states the rule plainly: "The `:a11y` process must NEVER touch prefs,
  the backup file, WorkManager, or restore â€” SharedPreferences isn't multi-process safe and two
  writers corrupt the backup." That rule is enforced at exactly one place, in one method, by control
  flow. Nothing at the *access* points enforces it: `getSharedPreferences(PrefsKeys.PREFS_NAME, â€¦)`
  can be called from any class in either process and will silently succeed in `:a11y`, giving that
  process its own incoherent copy of the preference cache. `evidence/cross-process-inventory.md`
  confirms the invariant currently holds â€” the accessibility service genuinely never reads
  preferences â€” and separately records it as one of three latent traps. The consequence is
  prospective and that is why it is Low: a future change adding a single preference read to
  `GrayzoneAccessibilityService`, or to any helper it calls, would silently break the backup's
  integrity with no compile error, no lint warning, and no test failure. The risk is elevated right
  now because the `:a11y` split is uncommitted and recent, so the code around it has not yet
  accumulated the habits that keep such invariants alive â€” and `evidence/prior-fix-watchlist.md`
  already shows one regression from that same split (the loss of `updateAccessibilityStatus`'s
  callers).
- **Trigger condition**:
  1. Read `GrayzoneApplication.kt:52â€“54` and confirm the guard exists in `onCreate` only.
  2. Search `app/src/main` for `getSharedPreferences` and confirm no call site checks the process
     name.
  3. Demonstrate the gap: add a `getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
     .getBoolean(PrefsKeys.GRAYZONE_ENABLED, true)` read to
     `service/GrayzoneAccessibilityService.onServiceConnected`, build, and run
     `./gradlew :app:lintDebug` plus the unit suite. Observe both pass and the app runs â€” the
     invariant is broken with no signal.
  4. On a device, confirm the divergence: change a preference from the UI and observe the `:a11y`
     process's cached value does not update, via a log added at the new read site.
- **Blast radius**:
  - *Callers*: `onCreate()` is called by the framework once per process.
    `GrayzoneApplication` is referenced by `AndroidManifest.xml` (`android:name`), `MainActivity.kt`,
    `data/ConfigValidator.kt` (KDoc), `data/RuntimeSessionReset.kt` (KDoc) and
    `data/StateBackupManager.kt` (reference index). The components the invariant constrains are the
    `:a11y` residents â€” `service/GrayzoneAccessibilityService.kt` per
    `app/src/main/AndroidManifest.xml`'s `android:process=":a11y"` â€” and every helper reachable from
    them, which today includes `AppUtils.isAccessibilityServiceEnabled()`,
    `AccessibilityServiceIds`, `DateUtils` and `GrayzoneLogger`, none of which touches preferences.
  - *Process death*: no effect. The guard is re-evaluated on every process creation, which is
    correct.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: this finding is entirely about the cross-process axis. Per
    `evidence/cross-process-inventory.md`, preference coherence between the two processes holds only
    because `:a11y` never reads them; `MODE_MULTI_PROCESS` is deprecated and unreliable, so there is
    no safe way to relax the rule â€” it must be enforced, not managed.
  - *Persistence*: the invariant protects both the `GrayzoneAccessibilityService` preference cache
    and the external backup file, which `StateBackupManager` writes from the main process only. Two
    writers to the backup is the failure the comment at `:53` names.
  - *Release-only*: no. `Application.getProcessName()` is available at `minSdk = 29`, and R8 does
    not alter the guard.
- **Fix** (minimal): make the invariant self-enforcing at the access point rather than only at
  `onCreate`. Add a single accessor and route preference access through it:

  ```kotlin
  // in PrefsKeys.kt or a small Prefs.kt
  fun Context.grayzonePrefs(): SharedPreferences {
      check(Application.getProcessName() == packageName) {
          "SharedPreferences must not be accessed from the :a11y process"
      }
      return getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
  }
  ```

  Migrating every `getSharedPreferences(PrefsKeys.PREFS_NAME, â€¦)` call site to it is a mechanical
  change across the files `evidence/prefs-inventory.md` already enumerates, and it converts a
  documented rule into a loud, immediate failure in exactly the process that must not do it. A
  `check` rather than a silent fallback is deliberate: an `:a11y` preference read is a programming
  error, and Principle III's prohibition on masking failures by returning defaults applies. If a
  hard failure in a shipping build is judged too aggressive, the same accessor can log via
  `GrayzoneLogger.e` in release and `check` in the everyday build â€” but note F-064 must be fixed
  first, or the release log would be the only signal and it would be an error-level one that reaches
  logcat (F-065). **Lower-cost alternative** if the migration is unwanted: keep the guard where it is
  and add the `check` only to a new accessor used by any future code, leaving existing call sites
  alone. That is weaker but still better than a comment.
- **Rewrite justification**: not applicable â€” one accessor plus mechanical call-site substitution.
  The substitution touches many files, which is why the lower-cost alternative is offered; the root
  cause is not repaired by patching any single site, because the defect is the *absence* of a
  choke point.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/PrefsProcessGuardTest.kt`,
  asserting that the guard predicate â€” extracted as
  `fun isPrefsAccessAllowed(processName: String?, packageName: String): Boolean` â€” returns `true`
  for an exact match and `false` for `"com.grayzone.app:a11y"` and for `null`. Fails before the fix
  (no such function); passes after. The `check`'s throwing behaviour needs `instrumented` coverage
  in the real `:a11y` process to be meaningful and **cannot be executed here**.
- **Existing test conflict**: none. `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`
  is used by tests that construct preferences directly and would not go through the new accessor, so
  it is unaffected.
- **Verification**: `confirmed` (evidence tier T2) for the structural claim.
  Confirmed by reading `GrayzoneApplication.kt:52â€“54` (the sole guard), by
  `evidence/cross-process-inventory.md`'s finding that no falsely-shared state exists today, and by
  its record of this as one of three latent traps. The claim that a future `:a11y` preference read
  would produce an incoherent cache follows from `SharedPreferences` not being multi-process safe â€”
  documented platform behaviour, and the reason the guard was written.
- **Build scope**: `everyday-build`
- **Constitution**: `none` of Iâ€“VI. It supports the Development Workflow gate that reviews verify
  invariants, by making one machine-checkable instead of comment-documented.
- **Affected surfaces**: `service/GrayzoneAccessibilityService.kt` is the process the rule
  constrains (T017's unit). `evidence/cross-process-inventory.md` records two sibling traps â€” 
  `UsageDatabase` lacking `enableMultiInstanceInvalidation()` and
  `ProtectionStatus.accessibilityActive` inviting a writer in the wrong process â€” both routed to
  T020 and neither re-filed here. `service/OverlayService.kt:164â€“166`'s stale comment claiming a
  shared process with the accessibility service is T018's, and is the single most likely way this
  invariant gets broken by someone reading the wrong file.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt)`
- **Disposition**: `verified`

---

### F-079 â€” `AppLockState` has no reader, so its three preference keys and the meaning of "locked" are re-derived at three call sites that have already diverged  [Low]

- **Review unit**: RU-01
- **Location**: `app/src/main/java/com/grayzone/app/AppLockState.kt` â†’ the whole file (lines 1â€“12)
- **Issue**: The prefs inventory's observation is confirmed: `AppLockState` declares no preference
  keys and never touches `SharedPreferences`. That is **not** a defect in itself â€” the type is named
  "state", not "store", and holding values already read is exactly what a data class should do. The
  defect is what is missing around it. The type exists, per its KDoc (`:3â€“7`), to be the single
  named representation of an app's runtime lock state, replacing an error-prone
  `Triple<Long, Long, Long>`. But it ships with no factory and no predicates, so every consumer
  re-derives both halves of the job. The three preference reads are duplicated verbatim between
  `ui/AppsScreen.kt:190â€“194` and `ui/LimitsScreen.kt:70â€“74` â€” byte-identical blocks â€” and a third
  time in `Extensions.isAnyAppLocked()` (`Extensions.kt:52â€“56`). More consequentially, the
  *interpretation* has already diverged: the two screens compute
  `isAppLocked = currentTime > activeUntil && currentTime < lockedUntil`
  (`ui/AppsScreen.kt:207`, `ui/LimitsScreen.kt:99`), while `isAnyAppLocked` computes
  `(now < activeUntil) || (now in (activeUntil + 1)..lockedUntil) || (remaining > 0)`
  (`Extensions.kt:56`). Those are different predicates over the same three fields. They may both be
  correct for their own question â€” "is this app locked right now" versus "is anything at all
  active" â€” but nothing in the code says so, and the type that was introduced to make this
  unambiguous is silent on it. The user-visible consequence today is nil; the risk is that renaming
  any of `ACTIVE_UNTIL`, `LOCKED_UNTIL` or `REMAINING_MILLIS` requires finding three places, and
  changing what "locked" means requires finding two more. There is also a small irony worth fixing
  while there: both screens construct the empty case positionally as `AppLockState(0L, 0L, 0L)`
  (`ui/AppsScreen.kt:201`, `ui/LimitsScreen.kt:93`), which is precisely the ordering hazard the KDoc
  claims named fields make impossible.
- **Trigger condition**:
  1. Read `ui/AppsScreen.kt:188â€“196` and `ui/LimitsScreen.kt:68â€“76` side by side and observe the
     blocks are identical.
  2. Read `Extensions.kt:52â€“56` and observe the same three keys read a third time with a different
     predicate.
  3. Demonstrate the coupling: rename `PrefsKeys.REMAINING_MILLIS` and observe that three separate
     files require edits, none of them `AppLockState.kt`.
  4. Demonstrate the divergence: pick a `now` strictly less than `activeUntil`. `isAnyAppLocked`
     reports `true` (a session is running); the screens' `isAppLocked` reports `false`. Both are
     defensible; nothing in the code explains which question each is answering.
- **Blast radius**:
  - *Callers*: `AppLockState` â€” `ui/AppsScreen.kt` (`:190`, `:201`) and `ui/LimitsScreen.kt`
    (`:70`, `:93`) (reference index). The three preference keys it mirrors have wider use:
    `PrefsKeys.ACTIVE_UNTIL`, `LOCKED_UNTIL` and `REMAINING_MILLIS` are read and written across
    `service/OverlayService.kt`, `data/RuntimeSessionReset.kt` (`:38â€“40`), `Extensions.kt` (`:53â€“55`)
    and the two screens, per `evidence/prefs-inventory.md`.
  - *Process death*: none. `AppLockState` instances are transient view state rebuilt from
    preferences on each composition, keyed by `remember(monitoredApps, currentTime)`.
  - *Config change*: none beyond F-076's general recreation behaviour; the values are re-read from
    preferences on recreation, which is correct.
  - *Foreground/background*: none. The screens re-read on their ticker, so returning to the
    foreground shows current values.
  - *Cross-process*: not reachable from `:a11y` â€” the type is used only by main-process UI. The keys
    it mirrors *are* written by `OverlayService` in the main process; per
    `evidence/cross-process-inventory.md` the accessibility process does not participate.
  - *Persistence*: `AppLockState` itself writes nothing. A factory added by the fix would read
    preferences, which is a read-only operation on the main thread â€” acceptable at the scale
    involved (three `getLong` calls per monitored app) and no worse than the current duplicated
    reads, since it replaces rather than adds work.
  - *Release-only*: no. R8 may inline a small data class's accessors, which is expected and harmless;
    `evidence/r8.md` reports no removal of `AppLockState`.
- **Fix** (minimal): give the type the reader it was missing â€” three lines in `AppLockState.kt`:

  ```kotlin
  companion object {
      fun read(prefs: SharedPreferences, pkg: String) = AppLockState(
          activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L),
          lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + pkg, 0L),
          remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
      )
  }
  ```

  Then `ui/AppsScreen.kt:190â€“194` and `ui/LimitsScreen.kt:70â€“74` each collapse to
  `AppLockState.read(prefs, pkg)`, and `AppLockState(0L, 0L, 0L)` at `:201`/`:93` becomes
  `AppLockState()`, using the defaults the class already declares (`:9â€“11`). **Scope split**: the
  companion object is RU-01's; the two `ui/` call-site edits belong to T026. Deliberately **not**
  included: moving the two divergent lock predicates onto the type. That would change behaviour in
  the UI and requires deciding which predicate is right for which question â€” a design call for T026
  with the screens in front of it, not something to fold into a mechanical deduplication.
  `Extensions.isAnyAppLocked` is left alone for the same reason.
- **Rewrite justification**: not applicable â€” an added factory and two call-site substitutions.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/AppLockStateTest.kt`, using the
  existing `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`, asserting that
  `AppLockState.read(prefs, "com.example")` maps each of the three prefixed keys to the correct
  field â€” in particular that `locked_until_` lands in `lockedUntil` and not in `activeUntil`, which
  is the transposition the KDoc claims to prevent and which nothing currently checks â€” and that a
  package with no stored keys yields `AppLockState()`. Fails before the fix (no such function);
  passes after.
- **Existing test conflict**: none. No test references `AppLockState`.
- **Verification**: `confirmed` (evidence tier T1).
  Confirmed by reading `AppLockState.kt:1â€“12` (no preference access, no factory, no predicates)
  against `ui/AppsScreen.kt:188â€“196`, `:201`, `:207`, `ui/LimitsScreen.kt:68â€“76`, `:93`, `:99` and
  `Extensions.kt:52â€“56`. The prefs inventory's claim that the file declares no keys is confirmed
  independently and is **not** a defect on its own â€” the defect is the missing reader, and the
  divergence it permitted.
- **Build scope**: `everyday-build`
- **Constitution**: II. The type is the abstraction that should own how lock state is read and
  interpreted; instead, three call sites re-derive the read and two re-derive the meaning â€”
  "duplicated conditional logic that a strategy should own", which the principle names as the
  primary source of inconsistent behaviour.
- **Affected surfaces**: `ui/AppsScreen.kt:188â€“196`, `:201`, `:207` and `ui/LimitsScreen.kt:68â€“76`,
  `:93`, `:99` (T026 applies the call-site edits and owns the predicate-divergence question).
  `Extensions.isAnyAppLocked()` (`Extensions.kt:48â€“57`) is the third read site and is deliberately
  left unchanged by the minimal fix.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Positive examples

Credited because they are the patterns the rest of the codebase should be measured against, and
because several of them are the reason plausible defects in this unit are **not** defects.

1. **The `:a11y` process guard** â€” `GrayzoneApplication.kt:52â€“54`. `Application.getProcessName() !=
   packageName` at the very top of `onCreate`, with a comment stating the invariant and its
   rationale. This single line is why the "double initialisation" family of defects does not exist
   here: preferences, the backup file, WorkManager, and the restore path are all unreachable from the
   second process. It is the correct construction, applied early, and documented. F-078 is about
   strengthening it, not about it being wrong.
2. **Defence in depth on the WorkManager schedule** â€” `GrayzoneApplication.kt:284â€“303`. The process
   guard would have been enough, and `ExistingPeriodicWorkPolicy.KEEP` (`:292`) is applied anyway,
   with the whole block in a `try`/`catch` that logs (`:300â€“302`). Two independent protections
   against the same failure.
3. **The backup flush design** â€” `GrayzoneApplication.kt:78â€“133`. The KDoc at `:79â€“92` states the
   problem (bursty writes), the solution (a dirty flag plus three flush points), and the measured
   trade-off. Flushing on backgrounding (`:116`) because that is when the user can reach Settings to
   clear data is a genuinely good insight, and the note at `:126â€“127` that `delay()` adds no wakelock
   and suspends in Doze shows the battery cost was thought through. F-063 is a defect *within* a
   sound design.
4. **`DateUtils.getCurrentDateKey()`'s midnight clamp** â€” `DateUtils.kt:46â€“61`. Caching a formatted
   date key for a minute is obvious; clamping the expiry to the next local midnight (`:58`) so an
   event in the first minute of a new day cannot be stamped with yesterday's key is not, and the
   comment at `:39â€“43` explains exactly which two subsystems that would have corrupted.
5. **`AccessibilityServiceIds`** â€” the whole file. It exists because substring matching on
   `ENABLED_ACCESSIBILITY_SERVICES` "can both false-negative â€¦ and false-positive" (`:8â€“9`), it
   handles both the flattened and relative component forms OEMs actually use (`:32`), it replicates
   `TextUtils.SimpleStringSplitter` semantics with the reason stated (`:26`), and it is pure and
   therefore unit-testable â€” which it is. This is the model the rest of this unit's extraction
   suggestions point at.
6. **`AccessibilityPromptPolicy`** â€” `AccessibilityPromptState.kt:11â€“33`. A pure policy with a named
   `Decision` return type, a KDoc naming the failure it prevents (the revoke â†’ re-prompt loop) and a
   link to the contract that specifies it. Called from `MainActivity.kt:192` and covered by
   `AccessibilityPromptStateTest.kt`.
7. **Correct `CoroutineExceptionHandler` usage in the services** â€”
   `service/OverlayService.kt:105â€“113` and `service/vpn/AdBlockVpnService.kt:90â€“98` both install one.
   They are the pattern F-062 asks `GrayzoneApplication` to adopt; the fix is to match existing
   in-repo practice, not to import a new one.
8. **Lifecycle-aware tickers** â€” `SharedComponents.kt:37â€“73`. `rememberResumedTicker` and
   `ResumedInterval` both wrap their loops in `repeatOnLifecycle(Lifecycle.State.RESUMED)`, so
   once-per-second UI refreshes stop waking the CPU when the app is backgrounded.
   `evidence/concurrency-inventory.md` (U1, U2) independently records both as correct. F-066's fix
   reuses this idiom.
9. **`shareApk`'s pre-flight check** â€” `SharedComponents.kt:181â€“188`. It checks `exists()` and
   `canRead()` *before* the copy and tells the user in plain language when the check fails, rather
   than letting an exception become a generic error.
   `evidence/error-handling-inventory.md` (entries 92, 6) classifies this as compliant and calls it
   out as a model.
10. **Scoped package visibility** â€” `AndroidManifest.xml:4â€“15`. The `<queries>` block is limited to
    launcher intents and the comment explains why `QUERY_ALL_PACKAGES` was deliberately declined.
    That restraint is what makes F-061's wire-up require care, and it is the right trade.

---

## Coverage summary

One row per file. Every `no-defect-found` names specifically what was examined.

| # | File | Outcome | Findings | What was examined |
|---|---|---|---|---|
| 1 | `GrayzoneApplication.kt` | findings filed | F-060 (Critical), F-062, F-063, F-067, F-078 | Full read of all 304 lines. `onCreate`'s ordering and the process guard (`:49â€“76`); `validateConfiguration`'s four typed reads and repair path (`:175â€“226`); `appScope` construction and all five launch sites (`:36`, `:96`, `:128`, `:139`, `:148`, `:162`); the dirty-flag protocol (`:44`, `:103â€“107`, `:116`, `:137â€“141`); the activity-count backgrounding detection (`:47`, `:110â€“124`); the flush loop (`:128â€“133`); `seedDefaultMonitoredAppsIfNeeded`'s package-visibility handling (`:232â€“266`); `resetRuntimeStateIfNeeded` (`:271â€“279`); `schedulePeriodicCleanup`'s `KEEP` policy and try/catch (`:284â€“303`); and a search establishing no `onTrimMemory`/`onLowMemory` override. Double-initialisation was re-checked and confirmed a **non-finding**. |
| 2 | `MainActivity.kt` | findings filed | F-066, F-076 | Full read of all 314 lines. `onResume`'s restore probe and its pre-`super` placement (`:42â€“53`); `onCreate`/`setContent` and theme-mode re-read on `ON_RESUME` (`:55â€“79`); `requestAllFilesAccessOnce`'s once-flag and nested fallback (`:87â€“110`); `reconcileVpnState`'s idempotency and hold-expiry branch (`:121â€“154`); `startRestoredVpn` (`:161â€“172`); `evaluateScreen`'s prompt-policy and onboarding gating (`:189â€“207`); the `ON_RESUME` `DisposableEffect` (`:211â€“217`); `MainScreen`'s `LaunchedEffect(Unit)` (`:247â€“265`); both activity-result launchers (`:233â€“245`); and `AndroidManifest.xml:55â€“63` for `configChanges`/`launchMode`. The three swallowed catches at `:106`, `:169`, `:261` are **T030's** and are referenced, not re-filed. |
| 3 | `GrayzoneLogger.kt` | findings filed | F-064, F-065, F-074 | Full read of all 82 lines. `shouldLog`'s level mapping (`:37â€“40`) traced to `BuildConfig.DEBUG` and thence to `app/build.gradle.kts:69â€“78` and its uncommitted status via `git diff`; the four emit methods and their guards (`:46â€“81`); the commented-out Crashlytics block (`:77â€“80`); the absence of any redaction or sensitive-argument affordance; and an exhaustive search of `app/src/main` for `GrayzoneLogger.w(`/`.e(` call sites, enumerating the eight that interpolate domains or package names. |
| 4 | `OemBatteryHelper.kt` | finding filed | F-061 (High) | Full read of all 185 lines. All eight manufacturer branches and their `ComponentName`s (`:85â€“172`); `needsOemOptimization`'s list and lowercasing (`:22â€“27`); `getOptimizationInstructions` (`:41â€“53`); `openBatterySettings`'s try/catch/log/return-false shape (`:59â€“77`), which is Principle III compliant; `canVerifyOptimization` (`:179â€“184`); the dead `SDK_INT >= M` branch (`:163`); `FLAG_ACTIVITY_NEW_TASK` on every branch; and cross-checks against `AndroidManifest.xml:10â€“15` (package visibility), `evidence/r8.md` (whole-class removal), the reference index (0 callers) and a direct search (single occurrence at `:17`). |
| 5 | `AppUtils.kt` | findings filed | F-070, F-071; contributes to F-067 | Full read of all 165 lines. `AppMetadataCache`'s expiry, `peek` and `invalidate` (`:21â€“47`) and its reachability graph; `getInstalledAppsCached` (`:53â€“60`); `refreshInstalledApps` (`:72â€“75`); `getInstalledApps`'s `Dispatchers.IO` dispatch and per-icon catch (`:77â€“91`); `drawableToBitmap`'s RGB_565 downscaling (`:93â€“106`); `isAccessibilityServiceEnabled`'s try/catch/log (`:108â€“126`) â€” **no defect**, it logs with context and returns a safe default deliberately; `settingsIntentFor`'s exhaustive `when` (`:129â€“137`); `isBatteryOptimized` (`:139â€“140`); `isPrivateDnsActive` (`:142â€“165`). |
| 6 | `Extensions.kt` | finding filed | F-069 | Full read of all 125 lines. `getAppName`'s fallback-to-package catch (`:9â€“11`) â€” **no defect**, the fallback is the documented intent; `formatDuration` (`:19â€“25`), covered by `FormatDurationTest.kt`; `getNormalizedRemainingMillis`'s 24-hour clamp (`:27â€“37`); `isAnyAppLocked`'s three-key read and predicate (`:48â€“57`), which feeds F-079; `stayedUnderAllBudgets`'s date-key gating (`:63â€“73`); `millisUntilMidnight` (`:75â€“84`) compared line by line against `DateUtils.kt:100â€“108`; `Prompts.DEFAULT` and `resolvePool`'s empty-pool fallback (`:93â€“125`) â€” **no defect**, and the KDoc at `:88â€“92` correctly records the deduplication it performed. |
| 7 | `DateUtils.kt` | finding filed | F-072 | Full read of all 137 lines. Both `ThreadLocal` formatters and their `Locale.US` pinning (`:18â€“24`); the `@Volatile` cache fields and midnight-clamped expiry (`:27â€“61`); `startOfNextDayMillis` (`:64â€“73`); `formatDateKey` (`:79â€“81`); `getYesterdayDateKey` (`:83â€“87`); `formatTimestamp` (`:93â€“95`); `millisUntilMidnight` (`:100â€“108`); `isSameDay` (`:113â€“115`); `shouldResetDailyRuntimeState` (`:121â€“124`); `getTodayStartMillis` (`:129â€“136`). |
| 8 | `SharedComponents.kt` | finding filed | F-075 | Full read of all 216 lines. `rememberResumedTicker` (`:37â€“50`) and `ResumedInterval` (`:57â€“73`) â€” both **no defect** and credited as positive examples; the five pure Composables `ActivePill`, `GZCard`, `SectionLabel`, `HowItWorksStep`, `GZLoadingSpinner` (`:75â€“176`) â€” **no defect**, all stateless, correctly parameterised, no lifecycle or I/O concerns; `shareApk`'s pre-flight check (`:181â€“188`), cache copy (`:191â€“195`), `FileProvider` grant and `ClipData` (`:196â€“205`), chooser launch (`:207`) and catch (`:208â€“215`). The main-thread copy at `:193â€“195` is **T026's** and is referenced, not re-filed. |
| 9 | `AppLockState.kt` | finding filed | F-079 | Full read of all 12 lines, plus every construction site. Confirmed the prefs inventory's observation that it declares no keys and never touches `SharedPreferences` â€” and confirmed that is **not** a defect: the name and KDoc describe a value holder, and holding already-read values is the correct role. Examined the KDoc's `Triple` claim (`:5â€“6`) against the real `lockStateMap` at `ui/AppsScreen.kt:188` and `ui/LimitsScreen.kt:68` â€” the claim is accurate and current. The filed defect is the missing factory and the resulting three-site duplication and predicate divergence. |
| 10 | `AccessibilityServiceIds.kt` | finding filed | F-077 | Full read of all 41 lines. `isServiceEnabled`'s null/blank guard, `SimpleStringSplitter` semantics, slash-position bounds checks and dual full/relative class-name matching (`:18â€“34`) â€” **no defect**; this is the strongest pure function in the unit and is credited as a positive example. `relativeClassName`'s prefix check and null return (`:37â€“40`) â€” logic **no defect**; visibility only. Cross-checked against `AppUtils.kt:114â€“118` (its single production entry point) and `AccessibilityServiceIdsTest.kt`. |
| 11 | `SetupRequirements.kt` | finding filed | F-073; contributes to F-077 | Full read of all 47 lines. `missing()`'s ordering guarantee â€” detection before drawing (`:15â€“20`) â€” **no defect**, and the ordering comment earns its place; `label()` (`:22â€“25`); `headline()`'s three-way size branch (`:27â€“31`) â€” **no defect**; `body()`'s per-requirement copy and multi-requirement join (`:33â€“42`) â€” **no defect**, each string names the concrete capability lost; `actionLabel()` (`:44â€“46`) against `ui/HomeScreen.kt:194`. Permission-loss behaviour is **T022's** by the scope split and is not assessed here. |
| 12 | `AccessibilityPromptState.kt` | no-defect-found | none | Full read of all 33 lines. Examined `AccessibilityPromptPolicy.onObserved`'s two-branch policy and both `Decision` fields (`:25â€“32`) for the revoke â†’ re-prompt loop it exists to prevent; the `Decision` data class (`:14â€“19`); and the KDoc's link to `specs/002-fix-timeout-accessibility-bugs/contracts/accessibility-permission.md` (`:9`), confirmed to point at a real contract. Verified against its only production call site, `MainActivity.kt:192â€“196`, that the returned `promptedForLoss` is persisted (`:196`) and `shouldPrompt` drives the screen (`:206`) â€” both fields are consumed, neither is dropped. **Specifically resolved: R8's whole-class removal of `AccessibilityPromptPolicy` is a members-inlining artifact, not dead code.** The reference index is the arbiter and records `MainActivity.kt` plus `AccessibilityPromptStateTest.kt` as references; `onObserved` is a two-branch pure function returning a small data class, which R8 inlines and scalarises at the single call site, leaving no class to keep. No lifecycle defect: the policy is stateless, the persistence lives in the caller, and it is re-evaluated on every `ON_RESUME` via `MainActivity.kt:211â€“217`. |
| 13 | `PrefsKeys.kt` | no-defect-found for the file; hosts F-068 | F-068 is filed against the missing migration mechanism, not against the file's contents | Full read of all 95 lines. Verified every declared key against `evidence/prefs-inventory.md`: all 33 constants in `PrefsKeys` and all four in `OverlayMode` correspond to a live read or write, so the object is **not** dead â€” independently confirming the recorded non-finding, and corroborated by `evidence/r8.md` (constants inlined, not removed) and by 21 referencing main-source files. Checked the four per-package prefixes (`:35â€“37`, `:45â€“48`, `:51â€“53`) for collisions â€” none; each prefix is distinct and none is a prefix of another. Confirmed `GRAYZONE_ENABLED` (`:8`) is live (`ui/AppsScreen.kt:48`, `:132`; `service/OverlayService.kt:278`, `:441`, `:641`). `VPN_BYPASS_APPS` (`:66`) is read-never-written â€” already filed in `findings-docs.md`, referenced not re-filed. The `daily_reset_date_runtime` composition that reaches outside this object is **T020's** (`data/RuntimeSessionReset.kt:17`). |

### Severity roll-up

| Severity | Count | IDs |
|---|---|---|
| Critical | 1 | F-060 |
| High | 1 | F-061 |
| Medium | 6 | F-062, F-063, F-064, F-065, F-066, F-067 |
| Low | 12 | F-068 â€¦ F-079 |
| **Total** | **20** | F-060 â€¦ F-079 |

### Pending device verification

Carried from this unit to `pending-device.md` (T083). Each has its procedure and severity-per-outcome
in the finding body.

| Finding | Runtime claim requiring a device | Severity if confirmed / if refuted |
|---|---|---|
| F-061 | Do the eight OEM `ComponentName`s still resolve, and are their packages visible under the scoped `<queries>`? Separately, does the process survive a 4-hour idle window on an aggressive OEM without the autostart setting? | High either way for the feature gap; Medium if the OEM kill is not reproducible |
| F-065 | Rate of distinct domains reaching the release log buffer over 24 hours of normal use | Medium / Low if effectively zero |
| F-067 | Is the process actually reclaimed under memory pressure, given both services hold foreground notifications? | High / Low |
| F-066 | Does the UI affirmatively claim protection is on while the tunnel is off after a mid-session clear-data? | High / Medium |
| F-070 | Does Private DNS in `hostname` mode fully bypass the VPN's DNS interception? | Medium if confirmed and the detector stays unwired / Low |
| F-060 | End-to-end reproduction of the poisoned-backup crash loop (the mechanism is confirmed statically) | Critical either way; device run is corroboration |
| F-076 | `StateRestorationTester` instrumented test â€” cannot be executed here | Low |
| F-078 | Instrumented confirmation that the `check` fires in the real `:a11y` process | Low |

# Reference Index — Grayzone main source (T007)

Cross-reference index for every top-level declaration and every public/internal member under
`app/src/main/java/com/grayzone/app/` (**68 Kotlin files**, 8 packages).

This file is the authoritative source for the mandatory **callers** field in audit-finding
blast-radius analysis. Every finding must enumerate callers by name from this index.

## ⚠️ ACCURACY WARNING — this index contains confirmed errors (added 2026-07-31 by the lead auditor)

T019 found six stale or incorrect rows while analyzing RU-06 and recorded them in a
**Reference-index corrections** section of `../findings-ru06.md`. Two were independently re-verified by
the lead auditor and are definitively wrong:

| Row | Index claimed | Verified reality |
|---|---|---|
| `AdBlockVpnService.onRevoke()` | listed as an existing override | **Not overridden at all** — zero matches for `onRevoke` anywhere in `app/src/main`. Confirms finding F-025. |
| `DnsTrafficBus.clear()` | marked **TEST-ONLY** | Called from `AdBlockVpnService.shutdownVpn` at `:293` — a live main-source caller. **Not test-only.** |

**Mandatory consequence for the dead-code sweeps (T059–T064).** A `TEST-ONLY` or `UNREFERENCED` label
in this index is a **lead, not proof**. Constitution Principle V requires proof before removal, and
this index has now demonstrably mislabelled at least one live declaration as test-only. Therefore:

> Before proposing any deletion, re-verify the candidate with a direct search across `app/src/main`,
> `app/src/test`, and `app/src/androidTest`. Cite that search in the finding. A deletion justified by
> this index alone is not acceptable evidence.

Findings already corroborated by multiple independent signals are unaffected — for example the
`OemBatteryHelper` deadness claim rests on the R8 `usage.txt` whole-class removal, this index, **and** a
direct search, so it stands. Two of T019's corrections also *strengthened* register non-findings rather
than undermining them (`BlocklistManager.load()` does have exactly one call site).

Everything else in this index remains useful; it is the only complete caller map available, and the
error rate found so far is roughly six rows out of 468 declarations.

## How to read this index

- **`Referenced from`** lists MAIN-SOURCE call sites only (file, plus the enclosing function where it
  disambiguates). A declaration's own declaring file is listed as `(self)` when the only uses are
  inside the file that declares it.
- **`Notes`** carries the audit markers:
  - `**UNREFERENCED**` — zero references anywhere (main or test). Dead-code candidate under
    Constitution Principle I.
  - `**TEST-ONLY**` — referenced only from `app/src/test/` or `app/src/androidTest/`, never from main
    source. Also a dead-code candidate: production code does not depend on it.
  - `:a11y` — referenced from `service/GrayzoneAccessibilityService.kt`, which the manifest declares
    with `android:process=":a11y"`. Anything marked `:a11y` is loaded into a **second process** with
    its own copy of all JVM statics. Object singletons marked `:a11y` therefore do **not** share
    state with the main process — this is the key cross-process audit risk.
  - `test:` — additionally referenced from test sources (declaration is still live in main).
- **Convention for data-class constructor properties**: where a `data class`/`class` exposes plain
  constructor properties with no independent call graph, they are indexed as one grouped row
  (`Type.{a, b, c}`) whose reference set equals the type's construction/read sites. Properties with
  their own reference significance (computed properties, `val` with getters) get their own row.
- Private declarations are excluded (not part of the public/internal API surface).
- Compose `@Composable` functions record the composing parent in `Referenced from`.

**Process topology (for `:a11y` reasoning).** `GrayzoneAccessibilityService` imports nothing from
`com.grayzone.app` except `GrayzoneLogger`/`LogComponent` and a `Class` literal for `OverlayService`.
It communicates with the main process only via `sendBroadcast(ACTION_APP_CHANGED)` and
`startService(OverlayService, action = "com.grayzone.app.ACTION_APP_CHANGED_WATCHDOG")`. Note the
watchdog action is a **hardcoded string literal**, not a reference to
`OverlayService.ACTION_APP_CHANGED_WATCHDOG` — the two constants are coupled by convention only.

---

## Package `com.grayzone.app` (root, 13 files)

### `AccessibilityPromptState.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AccessibilityPromptPolicy` | object | `MainActivity.kt` (`MainAppContent.evaluateScreen()`) | test: `AccessibilityPromptStateTest.kt` |
| `AccessibilityPromptPolicy.Decision` | data class (nested) | `AccessibilityPromptState.kt` (return type of `onObserved`), `MainActivity.kt` (`evaluateScreen()`) | test: `AccessibilityPromptStateTest.kt` |
| `Decision.shouldPrompt` | val (property) | `MainActivity.kt` (`evaluateScreen()`) | test: `AccessibilityPromptStateTest.kt` |
| `Decision.promptedForLoss` | val (property) | `MainActivity.kt` (`evaluateScreen()`) | test: `AccessibilityPromptStateTest.kt` |
| `AccessibilityPromptPolicy.onObserved()` | fun (object member) | `MainActivity.kt` (`evaluateScreen()`) | test: `AccessibilityPromptStateTest.kt` |

### `AccessibilityServiceIds.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AccessibilityServiceIds` | object | `AppUtils.kt` (`isAccessibilityServiceEnabled()`) | test: `AccessibilityServiceIdsTest.kt` |
| `AccessibilityServiceIds.isServiceEnabled()` | fun (object member) | `AppUtils.kt` (`isAccessibilityServiceEnabled()`) | test: `AccessibilityServiceIdsTest.kt` |
| `AccessibilityServiceIds.relativeClassName()` | fun (object member) | `(self)` — `isServiceEnabled()` only | public but no external main caller; no test caller |

### `AppLockState.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AppLockState` | data class | `ui/AppsScreen.kt`, `ui/LimitsScreen.kt` | |
| `AppLockState.{activeUntil, lockedUntil, remainingMillis}` | val (constructor properties) | `ui/AppsScreen.kt`, `ui/LimitsScreen.kt` | constructed and read at both call sites |

### `AppUtils.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AppInfo` | data class | `AppUtils.kt`, `ui/AppsScreen.kt`, `ui/PerAppSettingsSheet.kt` | |
| `AppInfo.{packageName, name, icon}` | val (constructor properties) | `ui/AppsScreen.kt`, `ui/PerAppSettingsSheet.kt`, `AppUtils.kt` | |
| `peekCachedApps()` | fun (top-level) | `ui/AppsScreen.kt`, `ui/LimitsScreen.kt` | |
| `getInstalledAppsCached()` | suspend fun (top-level) | `ui/AppsScreen.kt`, `ui/LimitsScreen.kt` | |
| `refreshInstalledApps()` | suspend fun (top-level) | — | **UNREFERENCED** |
| `getInstalledApps()` | suspend fun (top-level) | `(self)` — `getInstalledAppsCached()`, `refreshInstalledApps()` | reachable only via the cached wrapper |
| `drawableToBitmap()` | fun (top-level) | `(self)` — `getInstalledApps()` | |
| `isAccessibilityServiceEnabled()` | fun (top-level) | `MainActivity.kt`, `ui/HomeScreen.kt`, `ui/OnboardingScreen.kt` | |
| `settingsIntentFor()` | fun (top-level) | `ui/HomeScreen.kt` | |
| `isBatteryOptimized()` | fun (top-level) | `ui/HomeScreen.kt` | |
| `isPrivateDnsActive()` | fun (top-level) | — | **UNREFERENCED** — dead despite being a real VPN-bypass signal |

### `DateUtils.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DateUtils` | object | `data/RuntimeSessionReset.kt`, `service/OverlayService.kt`, `data/StreakEvaluator.kt`, `data/ScheduleManager.kt` | test: `DateUtilsTest.kt`, `DailyResetLogicTest.kt`, `CleanupWorkerTest.kt`, `ScheduleManagerTest.kt`, `RuntimeSessionResetTest.kt` |
| `DateUtils.getCurrentDateKey()` | fun (object member) | `service/OverlayService.kt`, `data/ScheduleManager.kt` | test: `CleanupWorkerTest.kt`, `ScheduleManagerTest.kt`, `DateUtilsTest.kt` |
| `DateUtils.formatDateKey()` | fun (object member) | `data/RuntimeSessionReset.kt` | test: `DateUtilsTest.kt`, `DailyResetLogicTest.kt`, `RuntimeSessionResetTest.kt` |
| `DateUtils.getYesterdayDateKey()` | fun (object member) | `data/StreakEvaluator.kt` | test: `DateUtilsTest.kt` |
| `DateUtils.formatTimestamp()` | fun (object member) | — | **UNREFERENCED** |
| `DateUtils.millisUntilMidnight()` | fun (object member) | `service/OverlayService.kt` | test: `DateUtilsTest.kt`; name collides with top-level `millisUntilMidnight()` in `Extensions.kt` |
| `DateUtils.isSameDay()` | fun (object member) | — | **TEST-ONLY** — `DateUtilsTest.kt` |
| `DateUtils.shouldResetDailyRuntimeState()` | fun (object member) | `data/RuntimeSessionReset.kt` | test: `DateUtilsTest.kt`, `DailyResetLogicTest.kt` |
| `DateUtils.getTodayStartMillis()` | fun (object member) | — | **UNREFERENCED** |

### `Extensions.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `Context.getAppName()` | fun (extension, top-level) | `service/OverlayService.kt` (8 sites) | |
| `formatDuration()` | fun (top-level) | `service/overlay/LockPresentation.kt` | test: `FormatDurationTest.kt`, `OverlayPresentationTest.kt` |
| `getNormalizedRemainingMillis()` | fun (top-level) | `service/OverlayService.kt`, `ui/AppsScreen.kt`, `ui/LimitsScreen.kt`, `(self)` `isAnyAppLocked()` | test: `LockGuardLogicTest.kt`, `SessionStateLogicTest.kt` |
| `isAnyAppLocked()` | fun (top-level) | `service/OverlayService.kt`, `ui/AppsScreen.kt`, `ui/SettingsScreen.kt` | test: `LockGuardLogicTest.kt` |
| `stayedUnderAllBudgets()` | fun (top-level) | — | **TEST-ONLY** — `LockGuardLogicTest.kt`. Streak logic in `StreakEvaluator` computes budgets itself instead of calling this. |
| `millisUntilMidnight()` | fun (top-level) | — | **UNREFERENCED** — duplicate of `DateUtils.millisUntilMidnight()`, which is the one `OverlayService` uses |
| `Prompts` | object | `service/OverlayService.kt`, `ui/CustomPromptsSheet.kt`, `ui/SettingsScreen.kt` | test: `PromptResolutionTest.kt` |
| `Prompts.DEFAULT` | val (object member) | `ui/CustomPromptsSheet.kt`, `service/OverlayService.kt`, `(self)` `resolvePool()` | test: `PromptResolutionTest.kt` |
| `Prompts.resolvePool()` | fun (object member) | `service/OverlayService.kt` | test: `PromptResolutionTest.kt` |

### `GrayzoneApplication.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `GrayzoneApplication` | class (`Application`) | `AndroidManifest.xml` (`android:name`), `MainActivity.kt`, `data/ConfigValidator.kt` (kdoc), `data/RuntimeSessionReset.kt` (kdoc), `data/StateBackupManager.kt` | entry point; instantiated by the framework in the **main** process only |
| `GrayzoneApplication.onCreate()` | override fun | Android framework | calls `ConfigValidator`, `DefaultMonitoredApps.seedIfNeeded`, `StateBackupManager`, `CleanupWorker` enqueue, `StreakEvaluator.run` |

### `GrayzoneLogger.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `LogComponent` | enum class | `data/CleanupWorker.kt`, `AppUtils.kt`, `GrayzoneApplication.kt`, `OemBatteryHelper.kt`, `service/OverlayService.kt`, `data/StateBackupManager.kt`, `service/vpn/AdBlockVpnService.kt`, `service/vpn/DnsResolverPool.kt`, `service/vpn/VpnHoldExpiryScheduler.kt`, `service/vpn/DnsPacketHelper.kt`, `service/GrayzoneAccessibilityService.kt`, `service/SessionExpiryReceiver.kt`, `service/SessionExpiryScheduler.kt` | `:a11y` — enum values `OVERLAY, VPN, DNS, DATABASE, SCHEDULE, SESSION, BOOT, ACCESSIBILITY` |
| `LogLevel` | enum class | `(self)` — `shouldLog()` and the `d/i/w/e` helpers | test: `CleanupAndLoggingTest.kt`; values `DEBUG, INFO, WARN, ERROR` |
| `GrayzoneLogger` | object | 14 main files (see `LogComponent` row) | `:a11y` — **duplicated per process**: the `:a11y` copy has its own statics |
| `GrayzoneLogger.shouldLog()` | fun (object member) | `(self)` — `d()`, `i()`, `w()`, `e()` | test: `CleanupAndLoggingTest.kt` |
| `GrayzoneLogger.d()` | fun (object member) | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`, `service/vpn/DnsPacketHelper.kt`, `service/GrayzoneAccessibilityService.kt`, `data/StateBackupManager.kt`, others | `:a11y` |
| `GrayzoneLogger.i()` | fun (object member) | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`, `service/SessionExpiryScheduler.kt` | |
| `GrayzoneLogger.w()` | fun (object member) | `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`, `service/GrayzoneAccessibilityService.kt`, `data/StateBackupManager.kt`, `service/vpn/VpnHoldExpiryScheduler.kt` | `:a11y` |
| `GrayzoneLogger.e()` | fun (object member) | `data/CleanupWorker.kt`, `AppUtils.kt`, `service/OverlayService.kt`, `service/vpn/AdBlockVpnService.kt`, `service/GrayzoneAccessibilityService.kt`, `service/SessionExpiryReceiver.kt`, others | `:a11y` |

### `MainActivity.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `MainActivity` | class (`ComponentActivity`) | `AndroidManifest.xml` (launcher), `service/OverlayService.kt` (notification intents), `service/vpn/AdBlockVpnService.kt`, `data/StateBackupManager.kt` | |
| `Screen` | enum class | `(self)` — `evaluateScreen()`, `MainAppContent()` | values `ONBOARDING, MAIN` |
| `Tab` | enum class | `(self)` — `MainScreen()` | values `HOME, APPS, VPN, LIMITS, SETTINGS` |
| `MainAppContent()` | @Composable fun | composed by `MainActivity.onCreate()` (inside `GrayzoneTheme`) | |
| `MainScreen()` | @Composable fun | composed by `MainAppContent()` (`Screen.MAIN` branch) | |

### `OemBatteryHelper.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `OemBatteryHelper` | object | — | **UNREFERENCED** — entire file is dead; nothing in main or test touches it |
| `OemBatteryHelper.needsOemOptimization()` | fun (object member) | — | **UNREFERENCED** |
| `OemBatteryHelper.getManufacturerName()` | fun (object member) | `(self)` — `getOptimizationInstructions()` | unreachable: only caller is itself dead |
| `OemBatteryHelper.getOptimizationInstructions()` | fun (object member) | — | **UNREFERENCED** |
| `OemBatteryHelper.openBatterySettings()` | fun (object member) | — | **UNREFERENCED** |
| `OemBatteryHelper.canVerifyOptimization()` | fun (object member) | — | **UNREFERENCED** |

### `PrefsKeys.kt`

`PrefsKeys` is the shared-state contract for `SharedPreferences("GrayzonePrefs")` and is referenced
from 21 main files. It is **not** referenced from the `:a11y` process.

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `PrefsKeys` | object | `Extensions.kt`, `GrayzoneApplication.kt`, `MainActivity.kt`, `data/RuntimeSessionReset.kt`, `service/vpn/VpnHoldLockStore.kt`, `service/OverlayService.kt`, `data/StreakEvaluator.kt`, `ui/CustomPromptsSheet.kt`, `ui/OnboardingScreen.kt`, `data/StateBackupManager.kt`, `data/StreakManager.kt`, `ui/LimitsScreen.kt`, `ui/AppsScreen.kt`, `service/vpn/AdBlockVpnService.kt`, `ui/theme/Theme.kt`, `data/ScheduleManager.kt`, `ui/HomeScreen.kt`, `ui/PerAppSettingsSheet.kt`, `ui/SettingsScreen.kt`, `service/BootReceiver.kt` | test: `RuntimeSessionResetTest.kt`, `ScheduleManagerTest.kt`, `StreakManagerTest.kt`, `LockGuardLogicTest.kt` |
| `PREFS_NAME` | const val | 19 main files (all `getSharedPreferences` sites) | |
| `GRAYZONE_ENABLED` | const val | `ui/AppsScreen.kt`, `service/OverlayService.kt` | |
| `THEME_MODE` | const val | `MainActivity.kt`, `ui/SettingsScreen.kt`, `ui/theme/Theme.kt` | |
| `ACCESSIBILITY_PROMPTED_FOR_LOSS` | const val | `MainActivity.kt`, `ui/OnboardingScreen.kt` | |
| `ONBOARDING_COMPLETED` | const val | `MainActivity.kt`, `data/StateBackupManager.kt` | |
| `ALL_FILES_PROMPTED` | const val | `MainActivity.kt` | |
| `WAIT_SECONDS` | const val | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/PerAppSettingsSheet.kt` | |
| `SESSION_MINUTES` | const val | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | |
| `LOCKOUT_MINUTES` | const val | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | |
| `ACTIVE_UNTIL` | const val | `Extensions.kt`, `data/RuntimeSessionReset.kt`, `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/LimitsScreen.kt` | test: `RuntimeSessionResetTest.kt`, `LockGuardLogicTest.kt` |
| `LOCKED_UNTIL` | const val | `Extensions.kt`, `data/RuntimeSessionReset.kt`, `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | test: `RuntimeSessionResetTest.kt`, `LockGuardLogicTest.kt` |
| `REMAINING_MILLIS` | const val | `Extensions.kt`, `data/RuntimeSessionReset.kt`, `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/LimitsScreen.kt` | test: `RuntimeSessionResetTest.kt`, `LockGuardLogicTest.kt` |
| `MONITORED_APPS` | const val | `GrayzoneApplication.kt`, `data/RuntimeSessionReset.kt`, `ui/AppsScreen.kt`, `service/OverlayService.kt`, `ui/SettingsScreen.kt`, `ui/HomeScreen.kt`, `data/StreakEvaluator.kt`, `ui/LimitsScreen.kt` | test: `RuntimeSessionResetTest.kt` |
| `DEFAULT_APPS_SEEDED` | const val | `GrayzoneApplication.kt`, `data/DefaultMonitoredApps.kt` | |
| `PER_APP_WAIT_SECONDS` | const val | `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt` | |
| `PER_APP_SESSION_MINUTES` | const val | `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | |
| `PER_APP_LOCKOUT_MINUTES` | const val | `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | |
| `PER_APP_HAS_CUSTOM` | const val | `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt`, `ui/LimitsScreen.kt` | |
| `DAILY_BUDGET_MINUTES` | const val | `Extensions.kt`, `service/OverlayService.kt`, `ui/PerAppSettingsSheet.kt`, `data/StreakEvaluator.kt` | test: `LockGuardLogicTest.kt` |
| `DAILY_USED_MILLIS` | const val | `Extensions.kt`, `service/OverlayService.kt` | test: `LockGuardLogicTest.kt`, `RuntimeSessionResetTest.kt` |
| `DAILY_RESET_DATE` | const val | `Extensions.kt`, `data/RuntimeSessionReset.kt`, `service/OverlayService.kt` | test: `LockGuardLogicTest.kt`, `RuntimeSessionResetTest.kt` |
| `SCHEDULE_RULES_JSON` | const val | `data/ScheduleManager.kt` | test: `ScheduleManagerTest.kt` |
| `FOCUS_MODE_ACTIVE` | const val | `data/ScheduleManager.kt` | test: `ScheduleManagerTest.kt` |
| `FOCUS_MODE_UNTIL` | const val | `data/ScheduleManager.kt` | test: `ScheduleManagerTest.kt` |
| `BREAK_UNTIL` | const val | `data/ScheduleManager.kt` | |
| `BREAK_USED_DATE` | const val | `data/ScheduleManager.kt` | test: `ScheduleManagerTest.kt` |
| `VPN_BYPASS_APPS` | const val | `service/vpn/AdBlockVpnService.kt` | single reader; no UI writes it |
| `CUSTOM_PROMPTS_JSON` | const val | `ui/CustomPromptsSheet.kt`, `service/OverlayService.kt` | |
| `USE_CUSTOM_PROMPTS_ONLY` | const val | `ui/CustomPromptsSheet.kt`, `service/OverlayService.kt` | |
| `CURRENT_STREAK` | const val | `data/StreakManager.kt` | test: `StreakManagerTest.kt` |
| `LONGEST_STREAK` | const val | `data/StreakManager.kt` | |
| `STREAK_LAST_DATE` | const val | `data/StreakManager.kt` | test: `StreakManagerTest.kt` |
| `TOTAL_SESSIONS_BLOCKED` | const val | `data/StreakManager.kt` | |
| `TOTAL_TIME_SAVED_MINS` | const val | `data/StreakManager.kt` | |
| `ACHIEVEMENTS_JSON` | const val | `data/StreakManager.kt` | test: `StreakManagerTest.kt` |
| `VPN_ENABLED` | const val | `MainActivity.kt`, `service/BootReceiver.kt`, `service/vpn/AdBlockVpnService.kt` | |
| `VPN_HOLD_STARTED_WALL` | const val | `service/vpn/VpnHoldLockStore.kt` | |
| `VPN_HOLD_STARTED_ELAPSED` | const val | `service/vpn/VpnHoldLockStore.kt` | |
| `OverlayMode` | object | `service/OverlayService.kt`, `service/overlay/OverlayPresentation.kt` | test: `OverlayModeTest.kt`, `OverlayPresentationTest.kt` |
| `OverlayMode.FRICTION` | const val | `service/OverlayService.kt`, `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayModeTest.kt`, `OverlayPresentationTest.kt` |
| `OverlayMode.LOCK` | const val | `service/OverlayService.kt`, `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayModeTest.kt`, `OverlayPresentationTest.kt` |
| `OverlayMode.BUDGET_LOCK` | const val | `service/OverlayService.kt`, `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayModeTest.kt`, `OverlayPresentationTest.kt` |
| `OverlayMode.SCHEDULE_LOCK` | const val | `service/OverlayService.kt`, `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayModeTest.kt`, `OverlayPresentationTest.kt` |

### `SetupRequirements.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SetupRequirement` | enum class | `AppUtils.kt` (`settingsIntentFor()`), `SetupRequirements.kt` | test: `SetupRequirementsTest.kt`; values `ACCESSIBILITY, OVERLAY` |
| `SetupRequirements` | object | `ui/OnboardingScreen.kt`, `ui/HomeScreen.kt` | test: `SetupRequirementsTest.kt` |
| `SetupRequirements.missing()` | fun (object member) | `ui/OnboardingScreen.kt`, `ui/HomeScreen.kt` | test: `SetupRequirementsTest.kt` |
| `SetupRequirements.label()` | fun (object member) | `(self)` — `body()` | no external main caller; no test caller |
| `SetupRequirements.headline()` | fun (object member) | `ui/HomeScreen.kt` | test: `SetupRequirementsTest.kt` |
| `SetupRequirements.body()` | fun (object member) | `ui/HomeScreen.kt` | test: `SetupRequirementsTest.kt` |
| `SetupRequirements.actionLabel()` | fun (object member) | — | **TEST-ONLY** — `SetupRequirementsTest.kt`; the UI builds its own CTA text |

### `SharedComponents.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `rememberResumedTicker()` | @Composable fun | `ui/AppsScreen.kt`, `ui/LimitsScreen.kt`, `ui/SettingsScreen.kt` | |
| `ResumedInterval()` | @Composable fun | `ui/DailyBreakCard.kt`, `ui/QuickFocusCard.kt`, `ui/ScheduleSheet.kt` | |
| `ActivePill()` | @Composable fun | `ui/HomeScreen.kt` | |
| `GZCard()` | @Composable fun | `ui/VpnScreen.kt`, `ui/LimitsScreen.kt`, `ui/AppsScreen.kt`, `ui/DailyBreakCard.kt`, `ui/HomeScreen.kt`, `ui/QuickFocusCard.kt`, `ui/StreakCard.kt`, `ui/SettingsScreen.kt` | most-composed shared primitive |
| `SectionLabel()` | @Composable fun | `ui/HomeScreen.kt`, `ui/VpnScreen.kt` | |
| `HowItWorksStep()` | @Composable fun | `ui/HomeScreen.kt` (4 call sites) | |
| `GZLoadingSpinner()` | @Composable fun | `ui/AppsScreen.kt` | |
| `shareApk()` | fun (top-level) | `ui/SettingsScreen.kt` | |

---

## Package `com.grayzone.app.data` (15 files)

### `CleanupWorker.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `CleanupWorker` | class (`CoroutineWorker`) | `GrayzoneApplication.kt` (WorkManager enqueue) | test: `CleanupWorkerTest.kt` |
| `CleanupWorker.doWork()` | override suspend fun | WorkManager runtime | |
| `CleanupWorker.RETENTION_DAYS` | const val (companion) | `(self)` — default arg of `cutoffDateKey()` | test: `CleanupWorkerTest.kt` |
| `CleanupWorker.cutoffDateKey()` | fun (companion) | `(self)` — `doWork()` | test: `CleanupWorkerTest.kt` |

### `ConfigValidator.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ConfigValidator` | object | `GrayzoneApplication.kt` (10 sites in `onCreate()`) | test: `ConfigValidatorTest.kt` |
| `DEFAULT_WAIT_SECONDS` | const val | `GrayzoneApplication.kt`, `(self)` `repairWaitSeconds()` | test: `ConfigValidatorTest.kt`; duplicated by `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` |
| `DEFAULT_SESSION_MINUTES` | const val | `GrayzoneApplication.kt`, `(self)` | test: `ConfigValidatorTest.kt` |
| `DEFAULT_LOCKOUT_MINUTES` | const val | `GrayzoneApplication.kt`, `(self)` | test: `ConfigValidatorTest.kt` |
| `WAIT_SECONDS_RANGE` | val | `(self)` — `repairWaitSeconds()`, `isWaitSecondsValid()` | |
| `SESSION_MINUTES_RANGE` | val | `(self)` — `repairSessionMinutes()`, `isSessionMinutesValid()` | |
| `LOCKOUT_MINUTES_RANGE` | val | `(self)` — `repairLockoutMinutes()`, `isLockoutMinutesValid()` | |
| `repairWaitSeconds()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |
| `repairSessionMinutes()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |
| `repairLockoutMinutes()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |
| `isWaitSecondsValid()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |
| `isSessionMinutesValid()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |
| `isLockoutMinutesValid()` | fun (object member) | `GrayzoneApplication.kt` | test: `ConfigValidatorTest.kt` |

### `DailyBudget.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DailyBudget` | object | `service/OverlayService.kt` (5 sites) | test: `DailyBudgetTest.kt` |
| `DailyBudget.remainingMs()` | fun (object member) | `service/OverlayService.kt` | test: `DailyBudgetTest.kt` |
| `DailyBudget.accumulateUsedMs()` | fun (object member) | `service/OverlayService.kt` | test: `DailyBudgetTest.kt` |
| `DailyBudget.grantedSessionMs()` | fun (object member) | `service/OverlayService.kt` | test: `DailyBudgetTest.kt` |
| `DailyBudget.shouldShowBudgetLock()` | fun (object member) | `service/OverlayService.kt` | test: `DailyBudgetTest.kt` |

### `DefaultMonitoredApps.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DefaultMonitoredApps` | object | `GrayzoneApplication.kt` | test: `DefaultMonitoredAppsTest.kt` |
| `DEFAULT_PACKAGES` | val (object member) | `GrayzoneApplication.kt`, `(self)` `seedIfNeeded()` | |
| `seedIfNeeded()` | fun (object member) | `GrayzoneApplication.kt` (`onCreate()`) | test: `DefaultMonitoredAppsTest.kt` |

### `PerAppLimits.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `PerAppLimits` | object | `service/OverlayService.kt` (2 sites) | test: `PerAppLimitsTest.kt` |
| `PerAppLimits.sessionMinutes()` | fun (object member) | `service/OverlayService.kt` | test: `PerAppLimitsTest.kt` |
| `PerAppLimits.lockoutMinutes()` | fun (object member) | `service/OverlayService.kt` | test: `PerAppLimitsTest.kt` |
| `PerAppLimits.waitSeconds()` | fun (object member) | — | **TEST-ONLY** — `PerAppLimitsTest.kt`. `OverlayService` resolves the per-app wait value inline instead, so per-app wait override bypasses this helper. |

### `ProtectionHealthRepository.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ProtectionHealthRepository` | object | `MainActivity.kt`, `service/OverlayService.kt`, `ui/VpnScreen.kt`, `service/vpn/AdBlockVpnService.kt` (5 sites) | test: `ProtectionStatusTest.kt`. In-memory `StateFlow` singleton — **not** shared with `:a11y` |
| `status` | val `StateFlow<ProtectionStatus>` | `MainActivity.kt`, `ui/VpnScreen.kt`, `ui/HomeScreen.kt`, `ui/LimitsScreen.kt` | test: `ProtectionStatusTest.kt` |
| `updateVpnStatus()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` (3 sites) | test: `ProtectionStatusTest.kt` |
| `markVpnReconnectPending()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` (2 sites) | test: `ProtectionStatusTest.kt` |
| `updateOverlayStatus()` | fun (object member) | `service/OverlayService.kt` (2 sites) | test: `ProtectionStatusTest.kt` |
| `updateAccessibilityStatus()` | fun (object member) | — | **TEST-ONLY** — `ProtectionStatusTest.kt`. The a11y service runs in `:a11y` and cannot write this singleton, so `accessibilityActive` is never updated in production. |

### `ProtectionStatus.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ProtectionStatus` | data class | `data/ProtectionHealthRepository.kt` | test: `ProtectionStatusTest.kt` |
| `ProtectionStatus.{vpnActive, overlayActive, accessibilityActive, vpnReconnectPending}` | val (constructor properties) | `ui/VpnScreen.kt`, `ui/HomeScreen.kt`, `ui/LimitsScreen.kt`, `data/ProtectionHealthRepository.kt` | `accessibilityActive` has no production writer (see `updateAccessibilityStatus`) |
| `ProtectionStatus.isFullyProtected` | val (computed) | — | **TEST-ONLY** — `ProtectionStatusTest.kt` |
| `ProtectionStatus.isDegraded` | val (computed) | — | **TEST-ONLY** — `ProtectionStatusTest.kt` |

### `RuntimeSessionReset.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `RuntimeSessionReset` | object | `GrayzoneApplication.kt`, `service/OverlayService.kt` (2 sites) | test: `RuntimeSessionResetTest.kt` |
| `resetIfNeeded()` | fun (object member) | `GrayzoneApplication.kt` (`onCreate()`), `service/OverlayService.kt` | test: `RuntimeSessionResetTest.kt` |

### `ScheduleManager.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ScheduleRule` | data class | `data/ScheduleManager.kt`, `ui/ScheduleSheet.kt` | test: `ScheduleManagerTest.kt`, `ScheduleMatchingTest.kt`, `ScheduleLogicTest.kt` |
| `ScheduleRule.{id, name, startHour, startMinute, endHour, endMinute, daysOfWeek, enabled}` | val (constructor properties) | `data/ScheduleManager.kt` (`matchesAnyRule`, `toggleRule`), `ui/ScheduleSheet.kt` | |
| `ScheduleManager` | class | `service/OverlayService.kt`, `ui/DailyBreakCard.kt`, `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt` | test: `ScheduleManagerTest.kt`, `ScheduleMatchingTest.kt`, `ScheduleLogicTest.kt` |
| `getScheduleRules()` | fun (member) | `ui/ScheduleSheet.kt`, `(self)` `addRule/removeRule/toggleRule/isCurrentlyScheduled` | test: `ScheduleManagerTest.kt` |
| `saveScheduleRules()` | fun (member) | `(self)` — `addRule()`, `removeRule()`, `toggleRule()` | public but no external main or test caller |
| `addRule()` | fun (member) | `ui/ScheduleSheet.kt` (`AddRuleDialog` confirm) | test: `ScheduleManagerTest.kt` |
| `removeRule()` | fun (member) | `ui/ScheduleSheet.kt` | test: `ScheduleManagerTest.kt` |
| `toggleRule()` | fun (member) | `ui/ScheduleSheet.kt` | test: `ScheduleManagerTest.kt` |
| `isCurrentlyScheduled()` | fun (member) | `service/OverlayService.kt` | test: `ScheduleManagerTest.kt` |
| `startFocusMode()` | fun (member) | `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt` | test: `ScheduleManagerTest.kt` |
| `stopFocusMode()` | fun (member) | `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt` | test: `ScheduleManagerTest.kt` |
| `isFocusModeActive()` | fun (member) | `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt`, `(self)` `isCurrentlyScheduled()` | test: `ScheduleManagerTest.kt` |
| `getFocusModeRemainingMillis()` | fun (member) | `ui/ScheduleSheet.kt` (3), `ui/QuickFocusCard.kt` (3) | test: `ScheduleManagerTest.kt` |
| `isBreakActive()` | fun (member) | `service/OverlayService.kt` (4 sites), `ui/DailyBreakCard.kt` | test: `ScheduleManagerTest.kt` |
| `getBreakRemainingMillis()` | fun (member) | `ui/DailyBreakCard.kt` (3 sites) | test: `ScheduleManagerTest.kt` |
| `canStartBreakToday()` | fun (member) | `ui/DailyBreakCard.kt` (3 sites) | test: `ScheduleManagerTest.kt` |
| `startDailyBreak()` | fun (member) | `ui/DailyBreakCard.kt` | test: `ScheduleManagerTest.kt` |
| `stopBreak()` | fun (member) | `ui/DailyBreakCard.kt` | test: `ScheduleManagerTest.kt` |
| `BREAK_DURATION_MILLIS` | const val (companion) | `(self)` — `startDailyBreak()` | test: `ScheduleManagerTest.kt` |
| `matchesAnyRule()` | fun (companion) | `(self)` — `isCurrentlyScheduled()` | test: `ScheduleMatchingTest.kt` |
| `DAY_NAMES` | val (companion) | — | **UNREFERENCED** — `ScheduleSheet` renders day chips with its own labels |
| `WEEKDAYS` | val (companion) | `ui/ScheduleSheet.kt`, `(self)` `ALL_DAYS` | |
| `WEEKEND` | val (companion) | `ui/ScheduleSheet.kt`, `(self)` `ALL_DAYS` | |
| `ALL_DAYS` | val (companion) | — | **TEST-ONLY** — `ScheduleManagerTest.kt` |

### `StateBackupManager.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `StateBackupManager` | object | `GrayzoneApplication.kt` (7 sites), `MainActivity.kt` (3 sites) | |
| `FLUSH_INTERVAL_MS` | const val | `GrayzoneApplication.kt` (2 sites) | |
| `restoreIfWiped()` | fun (object member) | `GrayzoneApplication.kt` (`onCreate()`), `MainActivity.kt` | |
| `hasAllFilesAccess()` | fun (object member) | `MainActivity.kt`, `(self)` `restoreIfWiped()`/`writeBackup()` | |
| `backupExists()` | fun (object member) | `GrayzoneApplication.kt` | |
| `writeBackup()` | fun (object member) | `GrayzoneApplication.kt` (2 sites) | |

### `StreakEvaluator.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `StreakEvaluator` | object | `GrayzoneApplication.kt`, `service/OverlayService.kt`, `ui/HomeScreen.kt` | |
| `StreakEvaluator.run()` | suspend fun (object member) | `GrayzoneApplication.kt` (`onCreate()`), `service/OverlayService.kt`, `ui/HomeScreen.kt` | three independent triggers for the same evaluation |

### `StreakManager.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `Achievement` | data class | `data/StreakManager.kt` (13 sites), `ui/StreakCard.kt` | test: `StreakManagerTest.kt` |
| `Achievement.{id, title, description, icon, unlockedDate}` | val (constructor properties) | `data/StreakManager.kt`, `ui/StreakCard.kt` | |
| `Achievement.isUnlocked` | val (computed) | `ui/StreakCard.kt` (2 sites) | test: `StreakManagerTest.kt` |
| `StreakManager` | class | `service/OverlayService.kt`, `data/StreakEvaluator.kt`, `ui/StreakCard.kt` | test: `StreakManagerTest.kt` |
| `getCurrentStreak()` | fun (member) | `ui/StreakCard.kt` (2 sites), `(self)` | test: `StreakManagerTest.kt` |
| `getLongestStreak()` | fun (member) | `ui/StreakCard.kt` (2 sites) | test: `StreakManagerTest.kt` |
| `getTotalSessionsBlocked()` | fun (member) | `(self)` — `getAchievements()`, `recordBlockedSession()` | test: `StreakManagerTest.kt` |
| `getTotalTimeSavedMins()` | fun (member) | `(self)` — `getAchievements()` | test: `StreakManagerTest.kt`; not surfaced in any UI |
| `recordBlockedSession()` | fun (member) | `service/OverlayService.kt` | test: `StreakManagerTest.kt` |
| `checkDailyStreak()` | fun (member) | — | **UNREFERENCED** — superseded by `checkDailyStreakForDate()`; nothing calls the "today" variant |
| `evaluatePendingDays()` | suspend fun (member) | `data/StreakEvaluator.kt` (2 sites) | test: `StreakManagerTest.kt` |
| `checkDailyStreakForDate()` | fun (member) | `(self)` — `evaluatePendingDays()` | test: `StreakManagerTest.kt` |
| `getAchievements()` | fun (member) | `ui/StreakCard.kt` (2 sites) | test: `StreakManagerTest.kt` |
| `pendingDates()` | fun (companion) | `(self)` — `evaluatePendingDays()` | test: `StreakManagerTest.kt` |
| `ALL_ACHIEVEMENTS` | val (companion) | `(self)` — `getAchievements()` | |

### `UsageDao.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `UsageDao` | interface (`@Dao`) | `data/UsageDatabase.kt` (`usageDao()`), `service/OverlayService.kt`, `data/CleanupWorker.kt`, `data/StreakEvaluator.kt` | |
| `insertEvent()` | suspend fun | `service/OverlayService.kt` (2 sites) | test: `UsageDaoStatsTest.kt`, `PeakHourAggregationTest.kt` (androidTest) |
| `getDailySummary()` | suspend fun | `data/StreakEvaluator.kt` | test: `UsageDaoStatsTest.kt` |
| `getDailyUsageForApp()` | suspend fun | — | **TEST-ONLY** — `UsageDaoStatsTest.kt` |
| `getWeeklyTotals()` | suspend fun | — | **TEST-ONLY** — `UsageDaoStatsTest.kt` |
| `getTotalSessionsBlocked()` | suspend fun | — | **TEST-ONLY** — `UsageDaoStatsTest.kt` |
| `getTotalBlockedDurationMillis()` | suspend fun | — | **TEST-ONLY** — `UsageDaoStatsTest.kt` |
| `getTotalSessions()` | suspend fun | — | **TEST-ONLY** — `UsageDaoStatsTest.kt` |
| `getEventsForDate()` | suspend fun | — | **UNREFERENCED** |
| `getStartTimeTotals()` | suspend fun | — | **TEST-ONLY** — `PeakHourAggregationTest.kt` |
| `getTotalEventCount()` | suspend fun | — | **UNREFERENCED** |
| `getAppUsageSince()` | suspend fun | — | **UNREFERENCED** |
| `deleteEventsOlderThan()` | suspend fun | `data/CleanupWorker.kt` (`doWork()`) | test: `UsageDaoStatsTest.kt` |
| `getEventCount()` | suspend fun | `data/CleanupWorker.kt` (`doWork()`) | test: `UsageDaoStatsTest.kt` |
| `DailySummaryRow` | data class | `data/UsageDao.kt` (`getDailySummary()`, `getAppUsageSince()`) | consumed in main only via `StreakEvaluator` |
| `DailySummaryRow.{packageName, appName, totalMillis, sessionCount}` | val (constructor properties) | `data/StreakEvaluator.kt`, Room codegen | |
| `DateTotalRow` | data class | `data/UsageDao.kt` (`getWeeklyTotals()`) | reachable only from a **TEST-ONLY** query |
| `DateTotalRow.{dateKey, totalMillis}` | val (constructor properties) | Room codegen | |
| `StartTimeTotalRow` | data class | `data/UsageDao.kt` (`getStartTimeTotals()`) | reachable only from a **TEST-ONLY** query |
| `StartTimeTotalRow.{hourOfDay, totalMillis}` | val (constructor properties) | Room codegen, `PeakHourAggregationTest.kt` | |

### `UsageDatabase.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `UsageDatabase` | abstract class (`RoomDatabase`) | `service/OverlayService.kt` (3 sites), `data/CleanupWorker.kt`, `data/StreakEvaluator.kt` | test: `MigrationTest.kt`, `UsageDaoStatsTest.kt`, `PeakHourAggregationTest.kt` |
| `usageDao()` | abstract fun | `service/OverlayService.kt`, `data/CleanupWorker.kt`, `data/StreakEvaluator.kt` | test: androidTest DAO suites |
| `MIGRATION_1_2` | internal val (companion) | `(self)` — `getInstance()` builder | test: `MigrationTest.kt` |
| `getInstance()` | fun (companion) | `service/OverlayService.kt`, `data/CleanupWorker.kt`, `data/StreakEvaluator.kt` | test: `PeakHourAggregationTest.kt` |

### `UsageEvent.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `UsageEvent` | data class (`@Entity`) | `service/OverlayService.kt` (3 sites), `data/UsageDao.kt`, `data/UsageDatabase.kt` | test: `UsageDaoStatsTest.kt`, `PeakHourAggregationTest.kt` |
| `UsageEvent.{id, packageName, appName, startTime, endTime, durationMillis, wasBlocked, dateKey}` | val (constructor properties) | `service/OverlayService.kt`, `data/UsageDao.kt` queries | |

---

## Package `com.grayzone.app.policy` (3 files)

### `SessionModels.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AppEvent` | sealed class | `policy/SessionPolicyEngine.kt` (5 sites), `service/OverlayService.kt` (2 sites) | test: `SessionPolicyEngineTest.kt`, `...ForegroundTest.kt`, `...BackgroundTest.kt` |
| `AppEvent.AppForegrounded` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: all three engine suites |
| `AppEvent.AppBackgrounded` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `SessionPolicyEngineTest.kt`, `...BackgroundTest.kt` |
| `AppEvent.TimerTick` | data class | `policy/SessionPolicyEngine.kt` | test: `...BackgroundTest.kt`; **no main-source producer** — `OverlayService` never emits `TimerTick` |
| `SessionState` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` (2 sites) | test: `PolicyTestFixtures.kt` + engine suites |
| `SessionState.{packageName, isMonitored, isGrayzoneEnabled, activeUntil, lockedUntil, budgetRemainingMs, sessionRemainingMs, defaultSessionMins, defaultLockoutMins, isScheduleLocked, isOnBreak}` | val (constructor properties) | `policy/SessionPolicyEngine.kt` (`evaluate()`), `service/OverlayService.kt` | |
| `SessionState.hasActiveSession()` | fun (member) | `policy/SessionPolicyEngine.kt` (3 sites), `service/OverlayService.kt` (4 sites) | test: `SessionStatePredicateTest.kt`, `...BackgroundTest.kt` |
| `SessionState.isLockedOut()` | fun (member) | `policy/SessionPolicyEngine.kt` (2 sites) | test: `SessionStatePredicateTest.kt`, `SessionPolicyEngineTest.kt` |
| `SessionState.isBudgetExhausted()` | fun (member) | `policy/SessionPolicyEngine.kt` | test: `SessionStatePredicateTest.kt` |
| `SessionCommand` | sealed class | `policy/SessionPolicyEngine.kt` (17 sites), `service/OverlayService.kt` (11 sites) | test: all engine suites |
| `SessionCommand.DismissOverlay` | object | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: engine suites |
| `SessionCommand.ShowWaitScreen` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` (2 sites) | test: `...ForegroundTest.kt`, `SessionPolicyEngineTest.kt` |
| `SessionCommand.ShowLockoutScreen` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `...ForegroundTest.kt`, `SessionPolicyEngineTest.kt` |
| `SessionCommand.ShowScheduleLockScreen` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `...ForegroundTest.kt`, `SessionPolicyEngineTest.kt` |
| `SessionCommand.ShowBudgetLockScreen` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `...ForegroundTest.kt`, `SessionPolicyEngineTest.kt` |
| `SessionCommand.RecordUsage` | data class | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `SessionPolicyEngineTest.kt`, `...BackgroundTest.kt` |
| `SessionCommand.UpdateState` | data class | `policy/SessionPolicyEngine.kt` (4 sites), `service/OverlayService.kt` (3 sites) | test: all engine suites |
| `SessionCommand.CancelCountdownNotification` | object | `policy/SessionPolicyEngine.kt`, `service/OverlayService.kt` | test: `SessionPolicyEngineTest.kt`, `...BackgroundTest.kt` |

### `SessionExpiryDecision.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SessionExpiryOutcome` | sealed class | `policy/SessionExpiryDecision.kt` (9 sites), `service/OverlayService.kt` (3 sites) | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryOutcome.NoOp` | object | `policy/SessionExpiryDecision.kt`, `service/OverlayService.kt` | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryOutcome.ClampCountdown` | object | `policy/SessionExpiryDecision.kt`, `service/OverlayService.kt` | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryOutcome.ShowLock` | object | `policy/SessionExpiryDecision.kt`, `service/OverlayService.kt` | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryDecision` | object | `service/OverlayService.kt` | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryDecision.decide()` | fun (object member) | `service/OverlayService.kt` (session-expiry broadcast handler) | test: `SessionExpiryDecisionTest.kt` |
| `SessionExpiryDecision.clampRemainingMs()` | fun (object member) | — | **TEST-ONLY** — `SessionExpiryDecisionTest.kt`; `OverlayService` clamps inline |

### `SessionPolicyEngine.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SessionPolicyEngine` | class | `service/OverlayService.kt` | test: `PolicyTestFixtures.kt`, `SessionPolicyEngineTest.kt`, `...ForegroundTest.kt`, `...BackgroundTest.kt` |
| `SessionPolicyEngine.evaluate()` | fun (member) | `service/OverlayService.kt` (2 sites) | test: all three engine suites |
| `DEFAULT_WAIT_SECONDS` | const val (companion) | `(self)` — `evaluate()` | test: `ConfigValidatorTest.kt`, `...ForegroundTest.kt`; duplicates `ConfigValidator.DEFAULT_WAIT_SECONDS` |

---

## Package `com.grayzone.app.service` (5 files)

### `BootReceiver.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `BootReceiver` | class (`BroadcastReceiver`) | `AndroidManifest.xml` (boot + package-replaced), `MainActivity.kt`, `service/vpn/AdBlockVpnService.kt` | no test coverage |
| `BootReceiver.onReceive()` | override fun | Android framework | restarts `OverlayService`, `AdBlockVpnService`, re-arms `VpnHoldExpiryScheduler` |

### `GrayzoneAccessibilityService.kt` — **runs in process `:a11y`**

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `GrayzoneAccessibilityService` | class (`AccessibilityService`) | `AndroidManifest.xml` (`android:process=":a11y"`), `AppUtils.kt` (`isAccessibilityServiceEnabled()`), `GrayzoneApplication.kt`, `service/OverlayService.kt` (4 sites) | `:a11y` — process boundary owner. test: `AccessibilityServiceIdsTest.kt` (class-name only) |
| `ACTION_APP_CHANGED` | const val (companion) | `service/OverlayService.kt` (receiver registration), `(self)` broadcast | `:a11y` — the only state channel out of `:a11y` |
| `EXTRA_PACKAGE_NAME` | const val (companion) | `service/OverlayService.kt` (2 sites), `service/SessionExpiryReceiver.kt` (3 sites), `service/SessionExpiryScheduler.kt`, `(self)` (3 sites) | `:a11y` — name collides with `SessionExpiryReceiver.EXTRA_PACKAGE_NAME` (same literal, two declarations) |
| `onServiceConnected()` | override fun | Android framework | |
| `onAccessibilityEvent()` | override fun | Android framework | broadcasts `ACTION_APP_CHANGED`; starts `OverlayService` with a **hardcoded** `"com.grayzone.app.ACTION_APP_CHANGED_WATCHDOG"` string |
| `onInterrupt()` | override fun | Android framework | |
| `onDestroy()` | override fun | Android framework | |
| `onUnbind()` | override fun | Android framework | |

### `OverlayService.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `OverlayService` | class (`Service`) | `AndroidManifest.xml`, `MainActivity.kt` (2 sites), `service/GrayzoneAccessibilityService.kt` (5 sites), `service/BootReceiver.kt` (4 sites), `service/SessionExpiryReceiver.kt` (3 sites), `data/DailyBudget.kt`/`Extensions.kt`/`data/PerAppLimits.kt`/`data/RuntimeSessionReset.kt`/`policy/SessionModels.kt`/`service/overlay/OverlayPresentation.kt` (kdoc) | `:a11y` — started cross-process by the a11y watchdog. Largest consumer in the codebase; no unit tests |
| `TAG` | const val (companion) | `(self)` (3 sites) | |
| `CHANNEL_ID` | const val (companion) | `(self)` (4 sites) | |
| `NOTIF_ID` | const val (companion) | `(self)` (4 sites) | |
| `ACTION_STOP` | const val (companion) | `(self)` (2 sites) | name collides with `AdBlockVpnService.ACTION_STOP` |
| `ACTION_START_COUNTDOWN` | const val (companion) | `(self)` (3 sites) | started from within the service only |
| `ACTION_STOP_COUNTDOWN` | const val (companion) | `(self)` (3 sites) | |
| `ACTION_SESSION_EXPIRED` | const val (companion) | `(self)` (3 sites), `service/SessionExpiryScheduler.kt` | duplicate literal of `SessionExpiryReceiver.ACTION_SESSION_EXPIRED` |
| `ACTION_APP_CHANGED_WATCHDOG` | const val (companion) | `(self)` (3 sites) | `:a11y` sends the same literal **without** referencing this constant |
| `onCreate()` / `onStartCommand()` / `onDestroy()` / `onBind()` | override fun | Android framework | orchestrates `SessionPolicyEngine`, `SessionExpiryScheduler`, `DailyBudget`, `ScheduleManager`, `StreakManager`, `UsageDatabase` |

### `SessionExpiryReceiver.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SessionExpiryReceiver` | class (`BroadcastReceiver`) | `service/OverlayService.kt` (registration), `service/SessionExpiryScheduler.kt` (3 sites) | no test coverage |
| `ACTION_SESSION_EXPIRED` | const val (companion) | `(self)` (4 sites), `service/SessionExpiryScheduler.kt`, `service/OverlayService.kt` | duplicate literal of `OverlayService.ACTION_SESSION_EXPIRED` |
| `EXTRA_PACKAGE_NAME` | const val (companion) | `(self)` (3 sites), `service/SessionExpiryScheduler.kt`, `service/OverlayService.kt` | duplicate literal of `GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME` |
| `onReceive()` | override fun | Android framework | forwards to `OverlayService` |

### `SessionExpiryScheduler.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SessionExpiryScheduler` | object | `service/OverlayService.kt` (5 sites), `service/SessionExpiryReceiver.kt` | no test coverage |
| `SessionExpiryScheduler.arm()` | fun (object member) | `service/OverlayService.kt` (4 sites) | name collides with `VpnHoldExpiryScheduler.arm()` |
| `SessionExpiryScheduler.cancel()` | fun (object member) | `service/OverlayService.kt` | |

---

## Package `com.grayzone.app.service.overlay` (5 files)

### `OverlayPresentation.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SecondsInputs` | data class | `service/OverlayService.kt` (2 sites), all four presentations | test: `OverlayPresentationTest.kt` |
| `SecondsInputs.{now, lockedUntil, waitSeconds}` | val (constructor properties) | `service/overlay/{Friction,Lock,BudgetLock,ScheduleLock}Presentation.kt` | test: `OverlayPresentationTest.kt` |
| `CountdownFinishContext` | class | `service/OverlayService.kt` (2 sites), all four presentations | test: `OverlayPresentationTest.kt` |
| `CountdownFinishContext.{packageName, appName}` | val (constructor properties) | presentations' `onCountdownFinished()` | |
| `CountdownFinishContext.markAppUnlocked` | val (lambda property) | `service/overlay/FrictionPresentation.kt`, `service/OverlayService.kt` (3 sites) | test: `OverlayPresentationTest.kt` |
| `CountdownFinishContext.dismissOverlay` | val (lambda property) | `service/overlay/FrictionPresentation.kt`, `service/overlay/LockPresentation.kt`, `service/OverlayService.kt` | test: `OverlayPresentationTest.kt` |
| `CountdownFinishContext.isStillForeground` | val (lambda property) | `service/overlay/LockPresentation.kt`, `service/OverlayService.kt` (2 sites) | test: `OverlayPresentationTest.kt` |
| `CountdownFinishContext.showFriction` | val (lambda property) | `service/overlay/LockPresentation.kt`, `service/OverlayService.kt` | test: `OverlayPresentationTest.kt` |
| `SkipContext` | class | `service/OverlayService.kt` (2 sites), all four presentations | test: `OverlayPresentationTest.kt` |
| `SkipContext.{packageName, waitedMs}` | val (constructor properties) | `service/overlay/FrictionPresentation.kt` | |
| `SkipContext.recordFrictionSkip` | val (lambda property) | `service/overlay/FrictionPresentation.kt`, `service/OverlayService.kt` (2 sites) | |
| `OverlayPresentation` | interface | `service/OverlayService.kt` (2 sites), 4 implementing objects | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.title` | abstract val | `service/OverlayService.kt`, 4 implementations | |
| `OverlayPresentation.skipButtonLabel` | abstract val | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.showsProgress` | abstract val | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.countdownTextSizeSp` | abstract val | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.bodyText()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.countdownText()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.subText()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.initialSeconds()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.onCountdownFinished()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.onSkip()` | abstract fun | `service/OverlayService.kt`, 4 implementations | test: `OverlayPresentationTest.kt` |
| `OverlayPresentation.resolve()` | fun (companion) | `service/OverlayService.kt` | test: `OverlayPresentationTest.kt`; sole factory mapping `OverlayMode` → presentation |

### `FrictionPresentation.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `FrictionPresentation` | object (`: OverlayPresentation`) | `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayPresentationTest.kt`; reached in main only through `resolve()` |

### `LockPresentation.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `LockPresentation` | object (`: OverlayPresentation`) | `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayPresentationTest.kt` |

### `BudgetLockPresentation.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `BudgetLockPresentation` | object (`: OverlayPresentation`) | `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayPresentationTest.kt` |

### `ScheduleLockPresentation.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ScheduleLockPresentation` | object (`: OverlayPresentation`) | `service/overlay/OverlayPresentation.kt` (`resolve()`) | test: `OverlayPresentationTest.kt` |

---

## Package `com.grayzone.app.service.vpn` (12 files)

### `AbusedTldList.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AbusedTldList` | class | `service/vpn/BlocklistManager.kt` (6 sites), `service/vpn/DomainZoneMatcher.kt` (kdoc) | test: `AbusedTldListTest.kt` |
| `AbusedTldList.size` | val (computed) | `service/vpn/BlocklistManager.kt` (load logging) | test: `AbusedTldListTest.kt` |
| `AbusedTldList.blocks()` | fun (member) | `service/vpn/BlocklistManager.kt` (`isBlocked()`) | test: `AbusedTldListTest.kt`, `BlocklistDecisionTest.kt`, `DnsQueryClassifierTest.kt` |
| `EMPTY` | val (companion) | `service/vpn/BlocklistManager.kt` (2 sites, fallback) | test: `AbusedTldListTest.kt` |
| `readFrom()` | fun (companion) | `service/vpn/BlocklistManager.kt` (2 sites) | test: `AbusedTldListTest.kt` |
| `parse()` | fun (companion) | `(self)` — `readFrom()` | test: `AbusedTldListTest.kt`, `CleanupWorkerTest.kt`, `IntegrationTest.kt` |

### `AdBlockVpnService.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AdBlockVpnService` | class (`VpnService`) | `AndroidManifest.xml`, `MainActivity.kt` (4 sites), `ui/VpnScreen.kt` (7 sites), `service/BootReceiver.kt` (6 sites), `service/vpn/VpnHoldExpiryScheduler.kt` (2 sites), `service/vpn/DnsQueryClassifier.kt` (kdoc), `PrefsKeys.kt` (kdoc) | no unit tests (packet logic is tested via `DnsPacketHelper`/`DnsQueryClassifier`) |
| `TAG` | const val (companion) | `(self)` (4 sites) | |
| `ACTION_START` | const val (companion) | `MainActivity.kt`, `ui/VpnScreen.kt`, `service/BootReceiver.kt` | |
| `ACTION_STOP` | const val (companion) | `ui/VpnScreen.kt`, `(self)` (4 sites) | collides by name with `OverlayService.ACTION_STOP` |
| `ACTION_HOLD_EXPIRED` | const val (companion) | `service/vpn/VpnHoldExpiryScheduler.kt` (4 sites), `(self)` (2 sites) | duplicate literal of `VpnHoldExpiryScheduler.ACTION_HOLD_EXPIRED` |
| `ACTION_REFRESH_NOTIFICATION` | const val (companion) | `ui/VpnScreen.kt` (`refreshVpnNotification()`), `(self)` (2 sites) | only caller chain starts at an **UNREFERENCED** function |
| `NOTIF_ID` | const val (companion) | `(self)` (3 sites) | |
| `CHANNEL_ID` | const val (companion) | `(self)` (3 sites) | |
| `isRunning` | var (companion, `@Volatile`) | `(self)` (8 sites) | public mutable static; no external main reader — UI reads `ProtectionHealthRepository.status` instead |
| `onStartCommand()` / `onDestroy()` / `onRevoke()` | override fun | Android framework | |

### `BlocklistManager.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `BlocklistManager` | object | `service/vpn/DnsQueryClassifier.kt` (4 sites), `service/vpn/AdBlockVpnService.kt` (2 sites) | test: `BlocklistManagerTest.kt`, `BlocklistDecisionTest.kt`, `DnsQueryClassifierTest.kt`, `DomainNormalizationTest.kt`. Object statics live per-process; only the VPN process loads them |
| `isLoaded` | var (`@Volatile`) | `(self)` (3 sites: `load()`, `isBlocked()`) | public mutable flag with no external reader |
| `load()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` (2 sites) | test: `BlocklistManagerTest.kt`, `BlocklistDecisionTest.kt` |
| `normalizeDomain()` | fun (object member) | `service/vpn/DnsQueryClassifier.kt`, `service/vpn/AdBlockVpnService.kt`, `(self)` (2 sites) | test: `DomainNormalizationTest.kt`, `DnsQueryClassifierTest.kt` |
| `isDoHBypass()` | fun (object member) | `service/vpn/DnsQueryClassifier.kt` (3 sites) | test: `BlocklistDecisionTest.kt`, `DnsQueryClassifierTest.kt` |
| `isBlocked()` | fun (object member) | `service/vpn/DnsQueryClassifier.kt` (4 sites) | test: `BlocklistManagerTest.kt`, `BlocklistDecisionTest.kt`, `DnsQueryClassifierTest.kt` |

### `DnsPacketHelper.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DnsPacketHelper` | object | `service/vpn/AdBlockVpnService.kt` (9 sites) | test: `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `DnsQuestionKey` | data class (nested) | `service/vpn/AdBlockVpnService.kt`, `(self)` (2 sites) | |
| `DnsQuestionKey.{...}` | val (constructor properties) | `service/vpn/AdBlockVpnService.kt` | |
| `getErrorStats()` | fun (object member) | — | **TEST-ONLY** — `DnsPacketTest.kt`, `IntegrationTest.kt`; no production telemetry consumes it |
| `isDnsQuery()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `getDnsPayloadOffset()` | fun (object member) | `service/vpn/AdBlockVpnService.kt`, `(self)` (6 sites) | test: `DnsPacketTest.kt` |
| `getDnsPayloadLength()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | |
| `getQuestionKey()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt` |
| `getDomainName()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `createSinkholeResponse()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `createNxDomainResponse()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `wrapDnsResponse()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsPacketTest.kt` |

### `DnsQueryClassifier.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DnsQueryClassifier` | object | `service/vpn/AdBlockVpnService.kt` (4 sites) | test: `DnsQueryClassifierTest.kt` |
| `DnsQueryClassifier.Action` | enum class (nested) | `service/vpn/AdBlockVpnService.kt`, `(self)` | test: `DnsQueryClassifierTest.kt`; values `NXDOMAIN_DOH, SINKHOLE_BLOCK, FORWARD` |
| `Action.NXDOMAIN_DOH` | enum entry | `service/vpn/AdBlockVpnService.kt`, `(self)` (2 sites) | test: `DnsQueryClassifierTest.kt` |
| `Action.SINKHOLE_BLOCK` | enum entry | `service/vpn/AdBlockVpnService.kt`, `(self)` | test: `DnsQueryClassifierTest.kt` |
| `Action.FORWARD` | enum entry | `service/vpn/AdBlockVpnService.kt`, `(self)` | test: `DnsQueryClassifierTest.kt` |
| `FIREFOX_DOH_CANARY` | const val | `(self)` — `classify()` | no external main or test reference |
| `classify()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsQueryClassifierTest.kt` |

### `DnsResolverPool.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DnsResolverPool` | class | `service/vpn/AdBlockVpnService.kt` (2 sites) | test: `DnsResolverPoolTest.kt` |
| `performQuery()` | suspend fun (member) | `service/vpn/AdBlockVpnService.kt` | test: `DnsResolverPoolTest.kt` |
| `shutdown()` | fun (member) | `service/vpn/AdBlockVpnService.kt` (3 sites) | test: `DnsResolverPoolTest.kt` |

### `DnsTrafficBus.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DnsTrafficBus` | object | `ui/VpnScreen.kt` (5 sites), `service/vpn/AdBlockVpnService.kt` (7 sites) | test: `DnsTrafficBusTest.kt`. In-memory `StateFlow`; VPN service and UI are in the **same** process, so this works — unlike a `:a11y` equivalent |
| `MAX_EVENTS` | const val | `(self)` (5 sites) | test: `DnsTrafficBusTest.kt` |
| `DnsEvent` | data class (nested) | `ui/VpnScreen.kt` (2 sites), `service/vpn/AdBlockVpnService.kt` (3 sites), `(self)` (5 sites) | test: `DnsTrafficBusTest.kt` |
| `DnsEvent.{domain, status, timestampMs}` | val (constructor properties) | `ui/VpnScreen.kt` (`VpnTrafficRow`), `(self)` | test: `DnsTrafficBusTest.kt` |
| `DnsEvent.Status` | enum class (nested) | `ui/VpnScreen.kt`, `service/vpn/AdBlockVpnService.kt`, `(self)` `emit()` | test: `DnsTrafficBusTest.kt` |
| `Status.ALLOWED` | enum entry | `ui/VpnScreen.kt` (4 sites), `service/vpn/AdBlockVpnService.kt` | test: `DnsTrafficBusTest.kt` |
| `Status.BLOCKED_AD` | enum entry | `ui/VpnScreen.kt` (3 sites), `service/vpn/AdBlockVpnService.kt` | test: `DnsTrafficBusTest.kt` |
| `Status.BLOCKED_DOH` | enum entry | `ui/VpnScreen.kt` (3 sites), `service/vpn/AdBlockVpnService.kt` | test: `DnsTrafficBusTest.kt` |
| `events` | val `StateFlow<List<DnsEvent>>` | `ui/VpnScreen.kt` (`LiveTrafficFeed()`) | test: `DnsTrafficBusTest.kt` |
| `emit(event)` | fun (object member) | `service/vpn/AdBlockVpnService.kt` (3 sites), `(self)` `emit(domain, status)` | test: `DnsTrafficBusTest.kt` |
| `emit(domain, status)` | fun (object member, overload) | `service/vpn/AdBlockVpnService.kt` | test: `DnsTrafficBusTest.kt` |
| `clear()` | fun (object member) | — | **TEST-ONLY** — `DnsTrafficBusTest.kt`; nothing in main clears the bus (event list is capped instead) |

### `DomainZoneMatcher.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DomainZoneMatcher` | object | `service/vpn/BlocklistManager.kt` (2 sites), `service/vpn/AbusedTldList.kt` (kdoc) | test: `DomainZoneMatcherTest.kt` |
| `anyMatch()` | fun (object member) | `service/vpn/BlocklistManager.kt` (2 sites: `isBlocked()`, `isDoHBypass()`) | test: `DomainZoneMatcherTest.kt` |

### `GrayzoneBloomFilter.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `GrayzoneBloomFilter` | class (private constructor) | `service/vpn/BlocklistManager.kt` (5 sites) | test: `BloomFilterTest.kt` |
| `mightContain()` | fun (member) | `service/vpn/BlocklistManager.kt` (`isBlocked()`) | test: `BloomFilterTest.kt` |
| `readFrom()` | fun (companion) | `service/vpn/BlocklistManager.kt` (`load()`) | test: `BloomFilterTest.kt` |

### `VpnHoldExpiryScheduler.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `VpnHoldExpiryScheduler` | object | `MainActivity.kt` (3 sites), `service/vpn/AdBlockVpnService.kt` (4 sites), `service/BootReceiver.kt` (2 sites) | no test coverage |
| `arm()` | fun (object member) | `service/vpn/AdBlockVpnService.kt`, `(self)` `rearmFromPrefs()` | name collides with `SessionExpiryScheduler.arm()` |
| `cancel()` | fun (object member) | `MainActivity.kt`, `service/vpn/AdBlockVpnService.kt`, `(self)` | |
| `rearmFromPrefs()` | fun (object member) | `MainActivity.kt` (2 sites), `service/vpn/AdBlockVpnService.kt`, `service/BootReceiver.kt` | |
| `ACTION_HOLD_EXPIRED` | const val (companion) | `(self)` | duplicate literal of `AdBlockVpnService.ACTION_HOLD_EXPIRED` |
| `VpnHoldExpiryReceiver` | class (`BroadcastReceiver`) | `AndroidManifest.xml`, `(self)` `arm()` PendingIntent | no test coverage |

### `VpnHoldLock.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `VpnHoldLock` | object | `MainActivity.kt` (2 sites), `service/vpn/VpnHoldLockStore.kt` (4 sites), `ui/VpnScreen.kt` (7 sites), `service/vpn/AdBlockVpnService.kt` (6 sites), `service/vpn/VpnHoldExpiryScheduler.kt` (2 sites), `service/BootReceiver.kt` (3 sites) | test: `VpnHoldLockTest.kt` |
| `HOLD_MS` | val | `(self)` (3 sites) | test: `VpnHoldLockTest.kt` |
| `VpnHoldLock.State` | data class (nested) | `service/vpn/VpnHoldLockStore.kt`, `ui/VpnScreen.kt`, `service/vpn/AdBlockVpnService.kt`, `service/BootReceiver.kt` | test: `VpnHoldLockTest.kt` |
| `State.startedWallMs` | val (property) | `service/vpn/VpnHoldLockStore.kt`, `(self)` (4 sites) | |
| `State.startedElapsedMs` | val (property) | `service/vpn/VpnHoldLockStore.kt`, `(self)` (2 sites) | |
| `State.isTracked` | val (property) | `MainActivity.kt`, `service/vpn/VpnHoldLockStore.kt`, `service/vpn/AdBlockVpnService.kt`, `service/vpn/VpnHoldExpiryScheduler.kt`, `service/BootReceiver.kt`, `(self)` (2 sites) | |
| `remainingMs()` | fun (object member) | `ui/VpnScreen.kt` (2 sites), `service/vpn/AdBlockVpnService.kt` (2 sites), `(self)` (3 sites) | test: `VpnHoldLockTest.kt` |
| `isHoldActive()` | fun (object member) | `(self)` — `canUserDisable()` | test: `VpnHoldLockTest.kt` |
| `canUserDisable()` | fun (object member) | `service/vpn/AdBlockVpnService.kt`, `ui/VpnScreen.kt` | test: `VpnHoldLockTest.kt` |
| `expiresAtWallMs()` | fun (object member) | `service/vpn/VpnHoldExpiryScheduler.kt` (5 sites) | test: `VpnHoldLockTest.kt` |
| `formatRemaining()` | fun (object member) | `service/vpn/AdBlockVpnService.kt` (2 sites), `ui/VpnScreen.kt` (3 sites) | |

### `VpnHoldLockStore.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `VpnHoldLockStore` | object | `MainActivity.kt` (3 sites), `ui/VpnScreen.kt` (7 sites), `service/vpn/AdBlockVpnService.kt` (9 sites), `service/vpn/VpnHoldExpiryScheduler.kt`, `service/BootReceiver.kt` (3 sites) | no direct test coverage (logic tested via `VpnHoldLock`) |
| `prefs(context)` | fun (object member) | `ui/VpnScreen.kt`, `(self)` (2 sites) | |
| `read(prefs)` | fun (object member) | `MainActivity.kt`, `ui/VpnScreen.kt`, `(self)` `read(context)` | |
| `read(context)` | fun (object member, overload) | `service/vpn/AdBlockVpnService.kt`, `service/vpn/VpnHoldExpiryScheduler.kt`, `service/BootReceiver.kt` | |
| `markEnabledIfAbsent()` | fun (object member) | `MainActivity.kt`, `service/vpn/AdBlockVpnService.kt` | |
| `clear(prefs)` | fun (object member) | `(self)` `clear(context)` | |
| `clear(context)` | fun (object member, overload) | `service/vpn/AdBlockVpnService.kt`, `service/vpn/VpnHoldExpiryScheduler.kt` | |

---

## Package `com.grayzone.app.ui` (12 files)

All screens are composed from `MainActivity.kt`. No UI file has any test coverage
(`app/src/androidTest/` contains only Room DAO tests).

### `HomeScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `HomeData` | data class | `ui/HomeScreen.kt` (`HomeScreen()`) | constructed and consumed inside the same file only |
| `HomeData.monitoredCount` | val (constructor property) | `ui/HomeScreen.kt` | |
| `HomeScreen()` | @Composable fun | composed by `MainScreen()` (`Tab.HOME`) | composes `QuickFocusCard`, `DailyBreakCard`, `StreakCard`, `GZCard`, `ActivePill`, `SectionLabel`, `HowItWorksStep` |

### `AppsScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `AppsScreen()` | @Composable fun | composed by `MainScreen()` (`Tab.APPS`) | composes `GZCard`, `GZLoadingSpinner`, `PremiumAppListItem`, `PerAppSettingsSheet` |
| `PremiumAppListItem()` | @Composable fun | composed by `AppsScreen()` | |

### `LimitsScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `LimitsScreen()` | @Composable fun | composed by `MainScreen()` (`Tab.LIMITS`) | composes `GZCard` |

### `SettingsScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `SettingsScreen()` | @Composable fun | composed by `MainScreen()` (`Tab.SETTINGS`) | composes `GZCard`, `CustomPromptsSheet`, `ScheduleSheet`; calls `shareApk()` |

### `VpnScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `VpnScreen()` | @Composable fun | composed by `MainScreen()` (`Tab.VPN`) | composes `GZCard`, `SectionLabel`, `StatChip`, `LiveTrafficFeed` |
| `startVpnService()` | fun (top-level) | `ui/VpnScreen.kt` (2 sites: `beginEnableVpn()`, permission callback) | file-local use only |
| `stopVpnService()` | fun (top-level) | `ui/VpnScreen.kt` (`tryDisableVpn()`) | file-local use only |
| `refreshVpnNotification()` | fun (top-level) | — | **UNREFERENCED** — the only main path that would send `ACTION_REFRESH_NOTIFICATION` |

### `OnboardingScreen.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `OnboardingScreen()` | @Composable fun | composed by `MainAppContent()` (`Screen.ONBOARDING`) | |

### `QuickFocusCard.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `QuickFocusCard()` | @Composable fun | composed by `HomeScreen()` | composes `GZCard`, `ResumedInterval` |

### `DailyBreakCard.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `DailyBreakCard()` | @Composable fun | composed by `HomeScreen()` | composes `GZCard`, `ResumedInterval` |

### `StreakCard.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `StreakCard()` | @Composable fun | composed by `HomeScreen()` | composes `GZCard`; reads `StreakManager` |

### `PerAppSettingsSheet.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `PerAppSettingsSheet()` | @Composable fun | composed by `AppsScreen()` | |

### `CustomPromptsSheet.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `CustomPromptsSheet()` | @Composable fun | composed by `SettingsScreen()` | `Extensions.kt` kdoc references it |

### `ScheduleSheet.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ScheduleSheet()` | @Composable fun | composed by `SettingsScreen()` | composes `ResumedInterval`, `AddRuleDialog` |
| `AddRuleDialog()` | @Composable fun | composed by `ScheduleSheet()` | |

---

## Package `com.grayzone.app.ui.theme` (3 files)

### `Color.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `ThemeMode` | object | `MainActivity.kt` (4 sites), `ui/theme/Theme.kt` (5 sites), `ui/SettingsScreen.kt` (5 sites) | |
| `ThemeMode.SYSTEM` | const val | `MainActivity.kt` (4), `ui/SettingsScreen.kt` (3), `ui/theme/Theme.kt` (3) | |
| `ThemeMode.LIGHT` | const val | `ui/SettingsScreen.kt`, `ui/theme/Theme.kt` | |
| `ThemeMode.DARK` | const val | `ui/SettingsScreen.kt`, `ui/theme/Theme.kt` | |
| `GzPalette` | data class | `(self)` — `LightGzPalette`, `DarkGzPalette` | |
| `GzPalette.{primary, primaryLight, primaryDark, primaryContainer, accent, accentDim, background, surface, surfaceElevated, surfaceHigh, border, borderSubtle, textPrimary, textSecondary, textTertiary, green, greenContainer, red, redContainer, amber, amberContainer, chartBlue, chartCyan, chartPink, chartOrange, chartYellow, chartTeal, streakFire, streakGold, achievementBg, achievementBorder, focusBlue, focusBlueDim, onPrimary}` | val (34 constructor properties) | `(self)` — read by the 34 `GZ*` accessor vals via `GzThemeState.palette` | each property is read by exactly one accessor; unused accessors mean the property is unused too |
| `LightGzPalette` | val (top-level) | `ui/theme/Theme.kt` (`GrayzoneTheme()`), `(self)` `GzThemeState.palette` default | |
| `DarkGzPalette` | val (top-level) | `ui/theme/Theme.kt` (`GrayzoneTheme()`) | |
| `GzThemeState` | object | `ui/theme/Theme.kt` (`GrayzoneTheme()` writes it), `(self)` (35 accessor reads) | mutable global theme state read by every `GZ*` token |
| `GzThemeState.palette` | var (Compose state) | `ui/theme/Theme.kt`, `(self)` (35 sites) | |
| `GZPrimary` | val (top-level, getter) | `MainActivity.kt`, `SharedComponents.kt`, and 11 `ui/` files | |
| `GZPrimaryLight` | val (top-level, getter) | `ui/HomeScreen.kt` | |
| `GZPrimaryDark` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZPrimaryContainer` | val (top-level, getter) | `MainActivity.kt`, `ui/AppsScreen.kt`, `ui/VpnScreen.kt`, `ui/SettingsScreen.kt`, `ui/HomeScreen.kt`, `ui/ScheduleSheet.kt`, `ui/LimitsScreen.kt` | |
| `GZAccent` | val (top-level, getter) | `ui/VpnScreen.kt`, `ui/PerAppSettingsSheet.kt`, `ui/HomeScreen.kt`, `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt` | |
| `GZAccentDim` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZBackground` | val (top-level, getter) | `MainActivity.kt` and 6 `ui/` screens | |
| `GZSurface` | val (top-level, getter) | `MainActivity.kt`, `SharedComponents.kt`, 7 `ui/` files | |
| `GZSurfaceElevated` | val (top-level, getter) | `ui/AppsScreen.kt`, `ui/VpnScreen.kt`, `ui/ScheduleSheet.kt`, `ui/QuickFocusCard.kt` | |
| `GZSurfaceHigh` | val (top-level, getter) | `ui/AppsScreen.kt`, `ui/CustomPromptsSheet.kt`, `ui/PerAppSettingsSheet.kt`, `ui/SettingsScreen.kt`, `ui/ScheduleSheet.kt`, `ui/VpnScreen.kt` | |
| `GZBorder` | val (top-level, getter) | `MainActivity.kt`, `SharedComponents.kt`, 8 `ui/` files | |
| `GZBorderSubtle` | val (top-level, getter) | `ui/AppsScreen.kt` | |
| `GZTextPrimary` | val (top-level, getter) | 12 `ui/` files | |
| `GZTextSecondary` | val (top-level, getter) | `SharedComponents.kt` + 13 `ui/` files | |
| `GZTextTertiary` | val (top-level, getter) | `MainActivity.kt`, `SharedComponents.kt`, 10 `ui/` files | |
| `GZGreen` | val (top-level, getter) | `SharedComponents.kt` + 7 `ui/` files | |
| `GZGreenContainer` | val (top-level, getter) | `SharedComponents.kt`, `ui/AppsScreen.kt`, `ui/HomeScreen.kt`, `ui/OnboardingScreen.kt` | |
| `GZRed` | val (top-level, getter) | `SharedComponents.kt` + 10 `ui/` files | |
| `GZRedContainer` | val (top-level, getter) | `SharedComponents.kt`, `ui/AppsScreen.kt`, `ui/LimitsScreen.kt`, `ui/SettingsScreen.kt` | |
| `GZAmber` | val (top-level, getter) | `ui/VpnScreen.kt`, `ui/HomeScreen.kt`, `ui/StreakCard.kt`, `ui/LimitsScreen.kt` | |
| `GZAmberContainer` | val (top-level, getter) | `ui/HomeScreen.kt` (2), `ui/VpnScreen.kt` (2) | |
| `GZChartBlue` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively (see `GZChartColors`) |
| `GZChartCyan` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively |
| `GZChartPink` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively |
| `GZChartOrange` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively |
| `GZChartYellow` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively |
| `GZChartTeal` | val (top-level, getter) | `(self)` — `GZChartColors` only | dead transitively |
| `GZChartColors` | val (top-level, getter) | — | **UNREFERENCED** — no charts exist; makes the 6 `GZChart*` tokens dead too |
| `GZStreakFire` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZStreakGold` | val (top-level, getter) | `ui/StreakCard.kt` | |
| `GZAchievementBg` | val (top-level, getter) | `ui/StreakCard.kt` | |
| `GZAchievementBorder` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZFocusBlue` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZFocusBlueDim` | val (top-level, getter) | — | **UNREFERENCED** |
| `GZOnPrimary` | val (top-level, getter) | `ui/SettingsScreen.kt` | |

### `Theme.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `resolveDarkTheme()` | fun (top-level) | `(self)` — `isNightMode()`, `GrayzoneTheme()` | no test coverage |
| `isNightMode()` | fun (top-level) | `service/OverlayService.kt` (2 sites) | overlay window theming reads theme prefs directly |
| `GrayzoneTheme()` | @Composable fun | composed by `MainActivity.onCreate()` | writes `GzThemeState.palette`, applies `Typography` |

### `Type.kt`

| Declaration | Kind | Referenced from | Notes |
|---|---|---|---|
| `Typography` | val (top-level) | `ui/theme/Theme.kt` (`GrayzoneTheme()`) | |

---

## Dead-code leads (consolidated)

### UNREFERENCED — zero references in main or test (23)

| Declaration | File |
|---|---|
| `refreshInstalledApps()` | `AppUtils.kt` |
| `isPrivateDnsActive()` | `AppUtils.kt` |
| `DateUtils.formatTimestamp()` | `DateUtils.kt` |
| `DateUtils.getTodayStartMillis()` | `DateUtils.kt` |
| `millisUntilMidnight()` (top-level) | `Extensions.kt` |
| `OemBatteryHelper` (object) | `OemBatteryHelper.kt` |
| `OemBatteryHelper.needsOemOptimization()` | `OemBatteryHelper.kt` |
| `OemBatteryHelper.getOptimizationInstructions()` | `OemBatteryHelper.kt` |
| `OemBatteryHelper.openBatterySettings()` | `OemBatteryHelper.kt` |
| `OemBatteryHelper.canVerifyOptimization()` | `OemBatteryHelper.kt` |
| `OemBatteryHelper.getManufacturerName()` | `OemBatteryHelper.kt` (only caller is itself dead) |
| `ScheduleManager.DAY_NAMES` | `data/ScheduleManager.kt` |
| `StreakManager.checkDailyStreak()` | `data/StreakManager.kt` |
| `UsageDao.getEventsForDate()` | `data/UsageDao.kt` |
| `UsageDao.getTotalEventCount()` | `data/UsageDao.kt` |
| `UsageDao.getAppUsageSince()` | `data/UsageDao.kt` |
| `refreshVpnNotification()` | `ui/VpnScreen.kt` |
| `GZPrimaryDark` | `ui/theme/Color.kt` |
| `GZAccentDim` | `ui/theme/Color.kt` |
| `GZChartColors` (+ 6 `GZChart*` tokens dead transitively) | `ui/theme/Color.kt` |
| `GZStreakFire` | `ui/theme/Color.kt` |
| `GZAchievementBorder` | `ui/theme/Color.kt` |
| `GZFocusBlue`, `GZFocusBlueDim` | `ui/theme/Color.kt` |

### TEST-ONLY — referenced only from `app/src/test/` or `app/src/androidTest/` (17)

| Declaration | File | Test that keeps it alive |
|---|---|---|
| `DateUtils.isSameDay()` | `DateUtils.kt` | `DateUtilsTest.kt` |
| `stayedUnderAllBudgets()` | `Extensions.kt` | `LockGuardLogicTest.kt` |
| `SetupRequirements.actionLabel()` | `SetupRequirements.kt` | `SetupRequirementsTest.kt` |
| `PerAppLimits.waitSeconds()` | `data/PerAppLimits.kt` | `PerAppLimitsTest.kt` |
| `ProtectionHealthRepository.updateAccessibilityStatus()` | `data/ProtectionHealthRepository.kt` | `ProtectionStatusTest.kt` |
| `ProtectionStatus.isFullyProtected` | `data/ProtectionStatus.kt` | `ProtectionStatusTest.kt` |
| `ProtectionStatus.isDegraded` | `data/ProtectionStatus.kt` | `ProtectionStatusTest.kt` |
| `ScheduleManager.ALL_DAYS` | `data/ScheduleManager.kt` | `ScheduleManagerTest.kt` |
| `UsageDao.getDailyUsageForApp()` | `data/UsageDao.kt` | `UsageDaoStatsTest.kt` |
| `UsageDao.getWeeklyTotals()` | `data/UsageDao.kt` | `UsageDaoStatsTest.kt` |
| `UsageDao.getTotalSessionsBlocked()` | `data/UsageDao.kt` | `UsageDaoStatsTest.kt` |
| `UsageDao.getTotalBlockedDurationMillis()` | `data/UsageDao.kt` | `UsageDaoStatsTest.kt` |
| `UsageDao.getTotalSessions()` | `data/UsageDao.kt` | `UsageDaoStatsTest.kt` |
| `UsageDao.getStartTimeTotals()` | `data/UsageDao.kt` | `PeakHourAggregationTest.kt` |
| `SessionExpiryDecision.clampRemainingMs()` | `policy/SessionExpiryDecision.kt` | `SessionExpiryDecisionTest.kt` |
| `DnsPacketHelper.getErrorStats()` | `service/vpn/DnsPacketHelper.kt` | `DnsPacketTest.kt`, `IntegrationTest.kt` |
| `DnsTrafficBus.clear()` | `service/vpn/DnsTrafficBus.kt` | `DnsTrafficBusTest.kt` |

### Public but with no caller outside its own declaring file (visibility-tightening candidates)

`AccessibilityServiceIds.relativeClassName()`, `SetupRequirements.label()`,
`ScheduleManager.saveScheduleRules()`, `AdBlockVpnService.isRunning`,
`BlocklistManager.isLoaded`, `DnsQueryClassifier.FIREFOX_DOH_CANARY`,
`StreakManager.getTotalTimeSavedMins()`, `AppUtils.getInstalledApps()`,
`AppUtils.drawableToBitmap()`.

---

## Summary statistics

| Metric | Count |
|---|---|
| Files indexed | **68** (all `.kt` under `app/src/main/java/com/grayzone/app/`) |
| Total declarations indexed | **468** rows (top-level declarations + public/internal members; grouped constructor-property rows count once) |
| Unreferenced declarations (zero main + zero test references) | **23** |
| Test-only-referenced declarations | **17** |
| Declarations reachable from the `:a11y` process | **11** |
| Declarations with no caller outside their declaring file | **9** |

### `:a11y`-reachable declarations (the full cross-process surface, 11)

`GrayzoneAccessibilityService` (class), `GrayzoneAccessibilityService.ACTION_APP_CHANGED`,
`GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME`, `GrayzoneLogger` (object),
`GrayzoneLogger.d()`, `GrayzoneLogger.w()`, `GrayzoneLogger.e()`, `LogComponent` (enum),
`LogComponent.ACCESSIBILITY`, `OverlayService` (class literal, for `startService`), and the
watchdog action string that shadows `OverlayService.ACTION_APP_CHANGED_WATCHDOG`.

**Cross-process consequence.** `GrayzoneLogger` and `LogComponent` are the only shared *code*, and
because `:a11y` gets its own class loader, `GrayzoneLogger`'s statics are duplicated. Nothing else
crosses the boundary: `:a11y` never touches `PrefsKeys`, `ProtectionHealthRepository`,
`DnsTrafficBus`, or any other in-memory singleton. This is why
`ProtectionHealthRepository.updateAccessibilityStatus()` is **TEST-ONLY** — the process that knows
the accessibility state cannot write the singleton the UI reads.

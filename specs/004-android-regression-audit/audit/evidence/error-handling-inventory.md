# Error-Handling Inventory (T011)

**Scope**: every `catch` block and every I/O boundary in `app/src/main/java/com/grayzone/app/` —
packages: root, `data/`, `policy/`, `service/`, `service/overlay/`, `service/vpn/`, `ui/`, `ui/theme/`.
**Files scanned**: 68 Kotlin files (complete; no sampling).
**Governing rule**: `.specify/memory/constitution.md:65-76` — Principle III, Robust Error Handling.
**Method**: exhaustive `catch`-token sweep across the tree, then full read of every file containing a
`catch` or an I/O call, plus every remaining file to confirm it is pure logic.

**Process weighting**: `service/GrayzoneAccessibilityService.kt` runs in a separate process
(`app/src/main/AndroidManifest.xml:82`, `android:process=":a11y"`). An uncaught exception there kills
foreground-app detection — i.e. all friction and lock screens — while the UI process still looks
healthy. Findings on that path are weighted up accordingly.

> Note on a stale claim in the code: `service/OverlayService.kt:164-166` says "OverlayService shares a
> process with GrayzoneAccessibilityService, and an uncaught crash here makes Android disable (revoke)
> the accessibility service." That is no longer true — the accessibility service was moved to `:a11y`
> (`AndroidManifest.xml:82`, and see `GrayzoneApplication.kt:66-69`). The defensive receiver-registration
> guards it justifies are still correct and valuable, but the stated rationale is out of date.

---

## PART 1 — Every catch block (75 total)

Classification values are exactly as specified: `handled`, `rethrown`, `logged-with-context`,
`logged-bare`, `SILENT`, `returns-default-silently`.

| # | File:line | Caught type | Body classification | Context logged | Verdict |
|---|---|---|---|---|---|
| 1 | `Extensions.kt:11` | `Exception` (`_`) | handled | none (documented fallback in KDoc `Extensions.kt:8`) | compliant |
| 2 | `OemBatteryHelper.kt:69` | `Exception` | logged-with-context | `"Failed to open OEM battery settings"` + throwable | compliant |
| 3 | `AppUtils.kt:86` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 4 | `AppUtils.kt:119` | `Exception` | logged-with-context | `"Failed to read accessibility enabled state: ${e.message}"` | compliant |
| 5 | `AppUtils.kt:162` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 6 | `SharedComponents.kt:208` | `Exception` | handled | `Log.e("ShareApk", "Could not share APK", e)` + user Toast | compliant |
| 7 | `MainActivity.kt:100` | `Exception` (`_`) | handled | none (falls through to alternate intent at `:102`) | compliant |
| 8 | `MainActivity.kt:106` | `Exception` (`_`) | SILENT | none (comment only) | **VIOLATION** |
| 9 | `MainActivity.kt:169` | `Exception` (`_`) | SILENT | none (comment only) | **VIOLATION** |
| 10 | `MainActivity.kt:261` | `Exception` (`_`) | SILENT | none (comment only) | **VIOLATION** |
| 11 | `GrayzoneApplication.kt:151` | `Exception` | logged-with-context | `"Deferred startup work failed: ${e.message}"` | compliant (flag: wraps PM query + prefs write) |
| 12 | `GrayzoneApplication.kt:165` | `Exception` | logged-with-context | `"Streak catch-up on start failed: ${e.message}"` | compliant |
| 13 | `GrayzoneApplication.kt:249` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 14 | `GrayzoneApplication.kt:300` | `Exception` | logged-with-context | `"Failed to schedule cleanup: ${e.message}"` | compliant |
| 15 | `data/ScheduleManager.kt:38` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 16 | `data/StreakManager.kt:134` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 17 | `data/StreakManager.kt:148` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 18 | `data/CleanupWorker.kt:56` | `Exception` | handled (`Result.retry()`) | `"Database cleanup failed"` + throwable | compliant |
| 19 | `data/StateBackupManager.kt:128` | `Exception` | logged-bare | `"State restore failed: ${e.message}"` — no throwable, no step/path | **VIOLATION** |
| 20 | `data/StateBackupManager.kt:176` | `Exception` | logged-with-context | `"State backup write failed"` + throwable | compliant |
| 21 | `data/StateBackupManager.kt:193` | `Exception` | logged-with-context | `"Backup decryption failed; ignoring file"` + throwable | compliant |
| 22 | `data/StateBackupManager.kt:256` | `Exception` (`_`) | returns-default-silently | none | **VIOLATION** |
| 23 | `service/BootReceiver.kt:42` | `Exception` | logged-with-context | `"Failed to start OverlayService: ${e.message}"` | compliant |
| 24 | `service/BootReceiver.kt:74` | `Exception` | logged-with-context | `"Failed to start AdBlockVpnService; restore intent preserved: ${e.message}"` | compliant |
| 25 | `service/SessionExpiryReceiver.kt:27` | `Exception` | logged-with-context | `"Failed to deliver session expiry for $packageName"` + throwable | compliant |
| 26 | `service/SessionExpiryScheduler.kt:52` | `SecurityException` | handled | none at site; explanatory comment `:53` + documented fallback `:55` | compliant (model) |
| 27 | `service/SessionExpiryScheduler.kt:56` | `Exception` (`e2`) | logged-with-context | `"Failed to arm session expiry alarm for $packageName"` + throwable | compliant (model) |
| 28 | `service/SessionExpiryScheduler.kt:63` | `Exception` | logged-with-context | `"Failed to arm session expiry alarm for $packageName"` + throwable | compliant |
| 29 | `service/SessionExpiryScheduler.kt:76` | `Exception` | logged-with-context | `"Failed to cancel session expiry alarm for $packageName: ${e.message}"` | compliant |
| 30 | `service/GrayzoneAccessibilityService.kt:57` | `Exception` | logged-with-context | `"onServiceConnected failed"` + throwable | compliant |
| 31 | `service/GrayzoneAccessibilityService.kt:100` | `Exception` | logged-with-context | `"Failed to watchdog OverlayService: ${e.message}"` | compliant |
| 32 | `service/GrayzoneAccessibilityService.kt:106` | `Exception` | logged-with-context | `"onAccessibilityEvent failed"` + throwable | compliant (breadth intentional, documented `:67-68`) |
| 33 | `service/OverlayService.kt:195` | `Exception` | logged-with-context | `"Failed to register app-change receiver"` + throwable | compliant |
| 34 | `service/OverlayService.kt:220` | `Exception` | logged-with-context | `"Failed to register SCREEN_OFF receiver"` + throwable | compliant |
| 35 | `service/OverlayService.kt:233` | `Exception` | logged-bare | `"unregisterReceiver failed: ${e.message}"` — does not say which of the two receivers | compliant (note) |
| 36 | `service/OverlayService.kt:250` | `Exception` | logged-with-context | `"Failed to perform midnight reset"` + throwable | compliant |
| 37 | `service/OverlayService.kt:290` | `Exception` | logged-with-context | `"Midnight streak catch-up failed: ${e.message}"` | compliant |
| 38 | `service/OverlayService.kt:382` | `Exception` | logged-with-context | `"Failed to process foreground app change"` + throwable (no package name) | compliant (flag: wraps whole transition) |
| 39 | `service/OverlayService.kt:428` | `Exception` | logged-with-context | `"Failed to pause session on screen off"` + throwable | compliant |
| 40 | `service/OverlayService.kt:616` | `Exception` | logged-with-context | `"Failed to log usage"` + throwable (no package name) | compliant (flag: also wraps budget-lock display) |
| 41 | `service/OverlayService.kt:781` | `SecurityException` (`_`) | SILENT | none (comment only) | **VIOLATION** (Low) |
| 42 | `service/OverlayService.kt:814` | `Exception` | logged-with-context | `"Failed to open overlay settings"` + throwable | compliant |
| 43 | `service/OverlayService.kt:854` | `Exception` | logged-with-context | `"Failed to parse custom prompts JSON; falling back to defaults: ${e.message}"` | compliant (model for Gson) |
| 44 | `service/OverlayService.kt:959` | `Exception` | logged-with-context | `"Failed to return to home screen"` + throwable | compliant |
| 45 | `service/OverlayService.kt:992` | `Exception` | logged-bare | `Log.e(TAG, "addView failed: ${e.message}")` — no throwable, no package, no overlay mode | **VIOLATION** |
| 46 | `service/OverlayService.kt:1040` | `Exception` | logged-with-context | `"Failed to remove overlay: ${e.message}"` | compliant |
| 47 | `service/OverlayService.kt:1128` | `Exception` | logged-with-context | `Log.e(TAG, "Failed to log blocked session: ${e.message}")` (raw Log, no throwable) | compliant (note) |
| 48 | `service/vpn/AdBlockVpnService.kt:119` | `Exception` | SILENT | none (`/* ignore */`) | **VIOLATION** |
| 49 | `service/vpn/AdBlockVpnService.kt:228` | `Exception` | handled | `Log.e(TAG,"Failed to start VPN",e)` + Toast + `GrayzoneLogger.e` + FGS teardown | compliant (flag: wraps builder→establish→prefs→thread) |
| 50 | `service/vpn/AdBlockVpnService.kt:268` | `PackageManager.NameNotFoundException` | SILENT | none at site; aggregate `applied` count logged `:272-275` | **VIOLATION** (Low) |
| 51 | `service/vpn/AdBlockVpnService.kt:288` | `Exception` | SILENT | none (`/* ignored */`) | **VIOLATION** |
| 52 | `service/vpn/AdBlockVpnService.kt:379` | `Exception` | handled | `"VPN loop error #$consecutiveErrors: ${e.message}"`, backoff, restart at 5 | compliant |
| 53 | `service/vpn/AdBlockVpnService.kt:415` | `Exception` | handled | `"Fatal VPN error"` + throwable, restart — **only when `isRunning`** | compliant (note: silent when `isRunning==false`) |
| 54 | `service/vpn/AdBlockVpnService.kt:518` | `Exception` | logged-with-context | `"DNS query failed for $domain"` + throwable | compliant |
| 55 | `service/vpn/BlocklistManager.kt:151` | `Throwable` | logged-with-context | `Log.e(TAG,"Error loading bloom filters",e)` + full state reset + retry re-armed | compliant (flag: broad `Throwable` over 3 resource loads) |
| 56 | `service/vpn/BlocklistManager.kt:192` | `Exception` | handled | none; comment `:193` states the intent; caches negative result | compliant (note: fail-open, no counter) |
| 57 | `service/vpn/DnsResolverPool.kt:62` | `SocketTimeoutException` | handled | none at site; caller logs server IP at `AdBlockVpnService.kt:563-566` | compliant |
| 58 | `service/vpn/DnsResolverPool.kt:67` | `Exception` | logged-bare | `"DNS query failed: ${e.message}"` — no server, no domain | compliant (note) |
| 59 | `service/vpn/DnsResolverPool.kt:95` | `Exception` | logged-with-context | `"Failed to create DatagramSocket"` + throwable | compliant |
| 60 | `service/vpn/DnsResolverPool.kt:107` | `Exception` (`_`) | SILENT | none (empty body `{}`) | **VIOLATION** |
| 61 | `service/vpn/DnsResolverPool.kt:112` | `Exception` (`_`) | SILENT | none (empty body `{}`) | **VIOLATION** |
| 62 | `service/vpn/DnsResolverPool.kt:123` | `Exception` (`e` unused) | SILENT | none (empty body `{}`) | **VIOLATION** |
| 63 | `service/vpn/DnsPacketHelper.kt:107` | `Exception` | SILENT | counter only (`domainParseErrors`); `getErrorStats()` has no production reader | **VIOLATION** (Low) |
| 64 | `service/vpn/DnsPacketHelper.kt:147` | `Exception` | logged-with-context | `"Failed to parse domain name from DNS packet: ${e.message}"` + throwable + counter | compliant |
| 65 | `service/vpn/DnsPacketHelper.kt:164` | `Exception` | logged-with-context | `"Failed to create sinkhole response (delegated to NXDOMAIN)"` + throwable + counter | compliant |
| 66 | `service/vpn/DnsPacketHelper.kt:217` | `Exception` | logged-with-context | `"Failed to create NXDOMAIN response (IPv4)"` + throwable + counter | compliant |
| 67 | `service/vpn/DnsPacketHelper.kt:295` | `Exception` | logged-with-context | `"Failed to wrap DNS response (IPv4)"` + throwable + counter | compliant |
| 68 | `service/vpn/DnsPacketHelper.kt:335` | `Exception` | logged-with-context | `"Failed to create NXDOMAIN response (IPv6)"` + throwable + counter | compliant |
| 69 | `service/vpn/DnsPacketHelper.kt:385` | `Exception` | logged-with-context | `"Failed to wrap DNS response (IPv6)"` + throwable + counter | compliant |
| 70 | `service/vpn/VpnHoldExpiryScheduler.kt:43` | `SecurityException` | handled | none at site; inexact-alarm fallback at `:45` (no explanatory comment) | compliant |
| 71 | `service/vpn/VpnHoldExpiryScheduler.kt:46` | `Exception` (`e2`) | logged-with-context | `"Failed to arm VPN hold expiry alarm"` + throwable | compliant |
| 72 | `service/vpn/VpnHoldExpiryScheduler.kt:49` | `Exception` | logged-with-context | `"Failed to arm VPN hold expiry alarm"` + throwable | compliant |
| 73 | `service/vpn/VpnHoldExpiryScheduler.kt:57` | `Exception` | logged-with-context | `"Failed to cancel VPN hold expiry alarm: ${e.message}"` | compliant |
| 74 | `service/vpn/VpnHoldExpiryScheduler.kt:91` | `Exception` | logged-with-context | `"Failed to deliver VPN hold expiry stop"` + throwable | compliant |
| 75 | `ui/CustomPromptsSheet.kt:44` | `Exception` (`e` unused) | returns-default-silently | none | **VIOLATION** |

### Files with zero catch blocks (confirmed, 46)

`AccessibilityPromptState.kt`, `AccessibilityServiceIds.kt`, `AppLockState.kt`, `DateUtils.kt`,
`GrayzoneLogger.kt`, `PrefsKeys.kt`, `SetupRequirements.kt`;
`data/ConfigValidator.kt`, `data/DailyBudget.kt`, `data/DefaultMonitoredApps.kt`,
`data/PerAppLimits.kt`, `data/ProtectionHealthRepository.kt`, `data/ProtectionStatus.kt`,
`data/RuntimeSessionReset.kt`, `data/StreakEvaluator.kt`, `data/UsageDao.kt`,
`data/UsageDatabase.kt`, `data/UsageEvent.kt`;
`policy/SessionExpiryDecision.kt`, `policy/SessionModels.kt`, `policy/SessionPolicyEngine.kt`;
`service/overlay/BudgetLockPresentation.kt`, `service/overlay/FrictionPresentation.kt`,
`service/overlay/LockPresentation.kt`, `service/overlay/OverlayPresentation.kt`,
`service/overlay/ScheduleLockPresentation.kt`;
`service/vpn/AbusedTldList.kt`, `service/vpn/DnsQueryClassifier.kt`,
`service/vpn/DnsTrafficBus.kt`, `service/vpn/DomainZoneMatcher.kt`,
`service/vpn/GrayzoneBloomFilter.kt`, `service/vpn/VpnHoldLock.kt`,
`service/vpn/VpnHoldLockStore.kt`;
`ui/AppsScreen.kt`, `ui/DailyBreakCard.kt`, `ui/HomeScreen.kt`, `ui/LimitsScreen.kt`,
`ui/OnboardingScreen.kt`, `ui/PerAppSettingsSheet.kt`, `ui/QuickFocusCard.kt`,
`ui/ScheduleSheet.kt`, `ui/SettingsScreen.kt`, `ui/StreakCard.kt`, `ui/VpnScreen.kt`;
`ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt`.

`GrayzoneBloomFilter.kt` deliberately uses `require(...)`/`throw` (`:60-81`, `:93`) instead of catching —
failures propagate to the single handler at `BlocklistManager.kt:151`. That is a valid "rethrow"-style design.

---

## PART 2 — Every I/O boundary

### 2.1 Room / database

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 1 | `Room.databaseBuilder(...).build()` | `data/UsageDatabase.kt:34-47` | database | none | **UNPROTECTED** |
| 2 | `db.execSQL` idx_date_key (migration 1→2) | `data/UsageDatabase.kt:27` | database | none | **UNPROTECTED** |
| 3 | `db.execSQL` idx_pkg_date | `data/UsageDatabase.kt:28` | database | none | **UNPROTECTED** |
| 4 | `db.execSQL` idx_start_time | `data/UsageDatabase.kt:29` | database | none | **UNPROTECTED** |
| 5 | `db.execSQL` idx_was_blocked | `data/UsageDatabase.kt:30` | database | none | **UNPROTECTED** |
| 6 | `@Dao` suspend queries/insert (declarations) | `data/UsageDao.kt:12-78` | database | none by design; throw to caller | n/a (caller-dependent) |
| 7 | `getInstance().usageDao()` | `service/OverlayService.kt:570` | database | try/catch `:616`, logged | compliant |
| 8 | `dao.insertEvent(UsageEvent)` | `service/OverlayService.kt:574` | database | try/catch `:616`, logged | compliant |
| 9 | `usageDao().insertEvent(...)` (friction skip) | `service/OverlayService.kt:1117` | database | try/catch `:1128`, logged | compliant |
| 10 | `getInstance().usageDao()` | `data/CleanupWorker.kt:42` | database | try/catch `:56` + `Result.retry()` | compliant |
| 11 | `dao.deleteEventsOlderThan` | `data/CleanupWorker.kt:47` | database | try/catch `:56` + retry | compliant |
| 12 | `dao.getEventCount()` | `data/CleanupWorker.kt:48` | database | try/catch `:56` + retry | compliant |
| 13 | `getInstance().usageDao()` | `data/StreakEvaluator.kt:27` | database | none locally; 2 of 3 callers wrap | partial |
| 14 | `dao.getDailySummary(dateKey)` | `data/StreakEvaluator.kt:32` | database | none locally | partial |
| 15 | `StreakEvaluator.run(context)` in composition scope | `ui/HomeScreen.kt:56-58` | database (via IPC to Room) | none — no try, no exception handler | **UNPROTECTED** |
| 16 | `StreakEvaluator.run(...)` | `GrayzoneApplication.kt:164` | database | try/catch `:165`, logged | compliant |
| 17 | `StreakEvaluator.run(...)` | `service/OverlayService.kt:289` | database | try/catch `:290`, logged | compliant |

### 2.2 SharedPreferences (read / write)

Rows are at function granularity with exact line ranges. `apply()` is fire-and-forget by contract;
`commit()` returns a boolean that must be checked. The realistic failure mode for reads is a
`ClassCastException` when a key's stored type drifts across an upgrade (E3) — see §3.5.

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 18 | `getSharedPreferences` + change listener registration | `GrayzoneApplication.kt:102-106` | SharedPreferences | none | **UNPROTECTED** |
| 19 | `validateConfiguration` reads (`getInt`×3, `getStringSet`) | `GrayzoneApplication.kt:176-212` | SharedPreferences | none — runs directly in `onCreate` (`:70`) | **UNPROTECTED** |
| 20 | `editor.apply()` (config repair) | `GrayzoneApplication.kt:215` | SharedPreferences | none | unverified write |
| 21 | seed reads (`contains`, `getStringSet`) | `GrayzoneApplication.kt:233-236` | SharedPreferences | try/catch `:151`, logged | compliant |
| 22 | seed write `edit().putStringSet().putBoolean().apply()` | `GrayzoneApplication.kt:258-261` | SharedPreferences | try/catch `:151`, logged | compliant |
| 23 | `getSharedPreferences` (runtime reset) | `GrayzoneApplication.kt:272` | SharedPreferences | none | **UNPROTECTED** |
| 24 | `getString`, `getStringSet`, remove-chain, `putString().apply()` | `data/RuntimeSessionReset.kt:29-43` | SharedPreferences | none locally | partial (caller-dependent) |
| 25 | theme read `getString(THEME_MODE)` | `MainActivity.kt:58-67` | SharedPreferences | none | **UNPROTECTED** |
| 26 | all-files prompt read + `putBoolean().apply()` | `MainActivity.kt:90-92` | SharedPreferences | none | **UNPROTECTED** |
| 27 | VPN reconcile read `getBoolean(VPN_ENABLED)` | `MainActivity.kt:122-123` | SharedPreferences | none | **UNPROTECTED** |
| 28 | stale-hold clear `putBoolean().apply()` | `MainActivity.kt:132` | SharedPreferences | none | unverified write |
| 29 | `evaluateScreen` reads + 3 × `edit().apply()` | `MainActivity.kt:183-221` | SharedPreferences | none | **UNPROTECTED** |
| 30 | `onCreate` reads (`getStringSet`, `getLong` per pkg) | `service/OverlayService.kt:150-158` | SharedPreferences | none — no guard in `onCreate` | **UNPROTECTED** |
| 31 | midnight-reset reads | `service/OverlayService.kt:258-278` | SharedPreferences | try/catch `:250`, logged | compliant |
| 32 | foreground-transition prefs handle | `service/OverlayService.kt:333` | SharedPreferences | try/catch `:382`, logged | compliant |
| 33 | screen-off prefs handle | `service/OverlayService.kt:419` | SharedPreferences | try/catch `:428`, logged | compliant |
| 34 | `buildSessionState` reads (set/bool/long×3/int×4/string) | `service/OverlayService.kt:439-467` | SharedPreferences | caller try `:382`/`:428` | compliant |
| 35 | `executeCommand` `editor.commit()` — **return value ignored** | `service/OverlayService.kt:515-531` | SharedPreferences | try `:382` only; no `commit()` check | partial |
| 36 | budget write `edit().commit()` — **return value ignored** | `service/OverlayService.kt:597-600` | SharedPreferences | try `:616`; no `commit()` check | partial |
| 37 | `handleSessionExpired` reads (`getLong`×2, `getBoolean`) | `service/OverlayService.kt:631-641` | SharedPreferences | none — reached from `onStartCommand:672` | **UNPROTECTED** |
| 38 | `showOverlay` reads (bool/int×2/string/bool) | `service/OverlayService.kt:826-849` | SharedPreferences | coroutine handler `:107` only | partial |
| 39 | `markAppUnlocked` reads | `service/OverlayService.kt:1048-1081` | SharedPreferences | coroutine handler `:107` | partial |
| 40 | `markAppUnlocked` `edit()...apply()` | `service/OverlayService.kt:1091-1095` | SharedPreferences | none | unverified write |
| 41 | `recordFrictionSkip` reads | `service/OverlayService.kt:1103-1108` | SharedPreferences | coroutine handler `:107` | partial |
| 42 | `prefs(context)` accessor | `service/vpn/VpnHoldLockStore.kt:11-12` | SharedPreferences | none | **UNPROTECTED** |
| 43 | `read` — `getLong`×2 | `service/vpn/VpnHoldLockStore.kt:14-17` | SharedPreferences | none | **UNPROTECTED** |
| 44 | `markEnabledIfAbsent` `edit().putLong×2.apply()` | `service/vpn/VpnHoldLockStore.kt:30-33` | SharedPreferences | none | unverified write |
| 45 | `clear` `edit().remove×2.apply()` | `service/vpn/VpnHoldLockStore.kt:37-40` | SharedPreferences | none | unverified write |
| 46 | `ACTION_STOP` hold read | `service/vpn/AdBlockVpnService.kt:137-138` | SharedPreferences | none | **UNPROTECTED** |
| 47 | sticky-restart hold read | `service/vpn/AdBlockVpnService.kt:157-158` | SharedPreferences | none | **UNPROTECTED** |
| 48 | VPN_ENABLED write + hold mark | `service/vpn/AdBlockVpnService.kt:217-221` | SharedPreferences | try/catch `:228`, logged | compliant |
| 49 | bypass-list read `getStringSet` | `service/vpn/AdBlockVpnService.kt:260-261` | SharedPreferences | covered by try `:228` | compliant |
| 50 | shutdown write `edit().putBoolean().apply()` + clear | `service/vpn/AdBlockVpnService.kt:301-304` | SharedPreferences | none | **UNPROTECTED** |
| 51 | restart-intent read | `service/vpn/AdBlockVpnService.kt:316-317` | SharedPreferences | coroutine handler `:92` | partial |
| 52 | `onDestroy` intent read | `service/vpn/AdBlockVpnService.kt:333-334` | SharedPreferences | none | **UNPROTECTED** |
| 53 | notification hold read | `service/vpn/AdBlockVpnService.kt:591` | SharedPreferences | none | **UNPROTECTED** |
| 54 | boot reads + `edit().apply()` + `clear` | `service/BootReceiver.kt:34-54` | SharedPreferences | none | **UNPROTECTED** |
| 55 | schedule-rules read `getString` | `data/ScheduleManager.kt:34` | SharedPreferences | see Gson row #123 | partial |
| 56 | schedule-rules write `putString().apply()` | `data/ScheduleManager.kt:42` | SharedPreferences | none | unverified write |
| 57 | focus/break reads (`getLong`×6, `getBoolean`, `getString`) | `data/ScheduleManager.kt:68,96,98,107,116,119,123` | SharedPreferences | none | **UNPROTECTED** |
| 58 | focus/break writes (`edit()...apply()` ×4) | `data/ScheduleManager.kt:82-85,89-92,131-134,139` | SharedPreferences | none | unverified write |
| 59 | streak counters read `getInt`×4 | `data/StreakManager.kt:34-37` | SharedPreferences | none | **UNPROTECTED** |
| 60 | blocked-session write | `data/StreakManager.kt:46-49` | SharedPreferences | none | unverified write |
| 61 | streak-date reads `getString` | `data/StreakManager.kt:82,96` | SharedPreferences | none | **UNPROTECTED** |
| 62 | streak write `edit()...apply()` | `data/StreakManager.kt:110-114` | SharedPreferences | none | unverified write |
| 63 | achievements read `getString` ×2 | `data/StreakManager.kt:129,143` | SharedPreferences | see Gson rows #125-126 | partial |
| 64 | achievements write `putString().apply()` | `data/StreakManager.kt:153` | SharedPreferences | none | unverified write |
| 65 | restore prefs handle + `contains` sentinel | `data/StateBackupManager.kt:105-107` | SharedPreferences | try/catch `:128` | partial (logged-bare) |
| 66 | backup prefs handle + `prefs.all` snapshot | `data/StateBackupManager.kt:166-167` | SharedPreferences | try/catch `:176`, logged | compliant |
| 67 | restore `editor.commit()` — **return value used** | `data/StateBackupManager.kt:232-250` | SharedPreferences | try `:128` + `commit()` result returned | compliant |
| 68 | streak-budget reads | `data/StreakEvaluator.kt:18-23` | SharedPreferences | caller-dependent | partial |
| 69 | monitored-count reads | `ui/HomeScreen.kt:45-55` | SharedPreferences | none | **UNPROTECTED** |
| 70 | app-list + lock-state reads | `ui/AppsScreen.kt:38-48,191-193` | SharedPreferences | none | **UNPROTECTED** |
| 71 | monitored-toggle writes `edit()...apply()` ×2 | `ui/AppsScreen.kt:239-243,246` | SharedPreferences | none | unverified write |
| 72 | master-switch write | `ui/AppsScreen.kt:132` | SharedPreferences | none | unverified write |
| 73 | limits-dashboard reads | `ui/LimitsScreen.kt:25-29,71-73,103-105` | SharedPreferences | none | **UNPROTECTED** |
| 74 | settings reads (`getString`, `getInt`×3, `getStringSet`) | `ui/SettingsScreen.kt:35-54` | SharedPreferences | none | **UNPROTECTED** |
| 75 | settings writes (theme, wait, session, lockout) | `ui/SettingsScreen.kt:136,166,184,209-212` | SharedPreferences | none | unverified write |
| 76 | per-app reads (`getBoolean`, `getInt`×7) | `ui/PerAppSettingsSheet.kt:29-43` | SharedPreferences | none | **UNPROTECTED** |
| 77 | per-app writes (×6 incl. 24h block at `:154`) | `ui/PerAppSettingsSheet.kt:78,104,118,139-142,154,180` | SharedPreferences | none | unverified write |
| 78 | prompts reads | `ui/CustomPromptsSheet.kt:33-39` | SharedPreferences | see Gson row #127 | partial |
| 79 | prompts writes (`putString`, `putBoolean`) | `ui/CustomPromptsSheet.kt:55,93` | SharedPreferences | none | unverified write |
| 80 | accessibility-prompt-flag reset write | `ui/OnboardingScreen.kt:50-51` | SharedPreferences | none | unverified write |
| 81 | overlay theme read `getString(THEME_MODE)` | `ui/theme/Theme.kt:27-28` | SharedPreferences | none — called from `OverlayService.kt:1151` | **UNPROTECTED** |
| 82 | VPN-screen hold reads | `ui/VpnScreen.kt:49,55,65,79,107,116` | SharedPreferences | none | **UNPROTECTED** |

### 2.3 File and shared-storage I/O

`data/StateBackupManager.kt` writes to `/sdcard/.grayzone/backup.dat` via the raw `File` API using
all-files access — permission denial and absent/unmounted storage are live failure modes.

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 83 | `Environment.isExternalStorageManager()` | `data/StateBackupManager.kt:139` | shared storage / permission | none in `hasAllFilesAccess`; `writeBackup` calls it **before** its try (`:161`) | **UNPROTECTED** |
| 84 | `ContextCompat.checkSelfPermission(WRITE_EXTERNAL_STORAGE)` | `data/StateBackupManager.kt:141-143` | permission | none | **UNPROTECTED** |
| 85 | `hiddenFile().exists()` (`backupExists`) | `data/StateBackupManager.kt:152` | shared-storage file | none; caller `GrayzoneApplication.kt:97` has no try and `appScope` has no handler | **UNPROTECTED** |
| 86 | `File(Environment.getExternalStorageDirectory(), ".grayzone/backup.dat")` | `data/StateBackupManager.kt:184` | shared-storage path | none | **UNPROTECTED** |
| 87 | `file.parentFile?.mkdirs()` — **return ignored** | `data/StateBackupManager.kt:170` | disk | try `:176`, logged; `mkdirs()` boolean unchecked | partial |
| 88 | `file.writeBytes(bytes)` | `data/StateBackupManager.kt:171` | disk | try/catch `:176`, logged with throwable | compliant |
| 89 | `file.exists()` / `file.readBytes()` | `data/StateBackupManager.kt:189-190` | disk | **outside** the local try at `:191`; only the outer `:128` logged-bare catch | partial |
| 90 | `Cipher.doFinal` decrypt (tamper/wrong key) | `data/StateBackupManager.kt:288` (via `:192`) | crypto over file bytes | try/catch `:193`, logged with throwable | compliant |
| 91 | `Cipher.doFinal` encrypt + `SecureRandom` | `data/StateBackupManager.kt:268-272` | crypto | try `:176`, logged | compliant |
| 92 | `File(context.applicationInfo.sourceDir)` + `exists()/canRead()` | `SharedComponents.kt:180-188` | disk | try `:208` **and** explicit pre-check with user Toast | compliant |
| 93 | `File(cacheDir,"shared").mkdirs()` — return ignored | `SharedComponents.kt:191` | disk | try `:208` | partial |
| 94 | `inputStream().use { outputStream().use { copyTo } }` | `SharedComponents.kt:193-195` | disk | try `:208`, logged + Toast | compliant |
| 95 | `FileProvider.getUriForFile` | `SharedComponents.kt:196-200` | IPC/file | try `:208` | compliant |
| 96 | `FileInputStream(pfd.fileDescriptor)` | `service/vpn/AdBlockVpnService.kt:348` | VPN descriptor | try `:415`, logged + restart | compliant |
| 97 | `FileOutputStream(pfd.fileDescriptor)` | `service/vpn/AdBlockVpnService.kt:349` | VPN descriptor | try `:415`, logged + restart | compliant |
| 98 | `inputStream.read(packet)` (tunnel read loop) | `service/vpn/AdBlockVpnService.kt:363` | VPN descriptor | try `:379`, logged, backoff, restart | compliant |
| 99 | `outputStream.write(nxDomain)` | `service/vpn/AdBlockVpnService.kt:468` | VPN descriptor | none locally; sync path covered by `:379` | partial |
| 100 | `outputStream.write(sinkhole)` | `service/vpn/AdBlockVpnService.kt:475` | VPN descriptor | none locally; sync path covered by `:379` | partial |
| 101 | `outputStream.write(wrapped)` | `service/vpn/AdBlockVpnService.kt:537` | VPN descriptor | try `:518` for the `:517` path; `:498`/`:510` paths fall to handler `:92` | partial |

### 2.4 `res/raw` asset loading

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 102 | `openRawResource(R.raw.adblock_bloom)` + `use` | `service/vpn/BlocklistManager.kt:228` | res/raw | try/catch `:151` (`Throwable`), logged + state reset | compliant |
| 103 | `openRawResource(R.raw.adult_bloom)` (same helper) | `service/vpn/BlocklistManager.kt:148` → `:228` | res/raw | try/catch `:151` | compliant |
| 104 | `openRawResource(R.raw.abused_tlds)` + `use` | `service/vpn/BlocklistManager.kt:236` | res/raw | try/catch `:151` | compliant |
| 105 | `GrayzoneBloomFilter.readFrom(stream)` header/format validation | `service/vpn/GrayzoneBloomFilter.kt:55-87` | res/raw parse | `require(...)` throws with a descriptive message → `:151` | compliant (rethrow-style) |
| 106 | `readFully` truncated-stream detection | `service/vpn/GrayzoneBloomFilter.kt:89-96` | res/raw read | explicit `throw IllegalArgumentException(errorMessage, EOFException())` at `:93` | compliant |
| 107 | `AbusedTldList.readFrom` `bufferedReader().use` + `lineSequence` | `service/vpn/AbusedTldList.kt:34-37` | res/raw read | none locally; propagates to `:151` | compliant |

### 2.5 VPN descriptor / socket / DatagramSocket / DNS packet parsing

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 108 | `startForeground(NOTIF_ID, buildNotification())` before `establish()` | `service/vpn/AdBlockVpnService.kt:189` | FGS start | none — outside the try at `:191` | **UNPROTECTED** |
| 109 | `Builder().addAddress/addDnsServer/addRoute/setSession` | `service/vpn/AdBlockVpnService.kt:192-201` | VPN config | try `:228`, logged + Toast + teardown | compliant |
| 110 | `builder.establish()` (+ explicit null → `IllegalStateException`) | `service/vpn/AdBlockVpnService.kt:207-208` | VPN descriptor | try `:228`; null treated as failure with a diagnostic message | compliant |
| 111 | `builder.addDisallowedApplication(pkg)` | `service/vpn/AdBlockVpnService.kt:266` | VPN + PackageManager | try `:268` — comment-only body | **VIOLATION** (row 50) |
| 112 | `vpnInterface?.close()` | `service/vpn/AdBlockVpnService.kt:287` | VPN descriptor | try `:288` — empty/ignored | **VIOLATION** (row 51) |
| 113 | `protect(socket)` | `service/vpn/AdBlockVpnService.kt:119` | VPN socket protection | try `:119` — `/* ignore */` | **VIOLATION** (row 48) |
| 114 | `vpnThread?.interrupt()` | `service/vpn/AdBlockVpnService.kt:285` | thread/IPC | none | acceptable (non-throwing) |
| 115 | `InetAddress.getByName(serverIp)` | `service/vpn/AdBlockVpnService.kt:551` | network resolve | none locally; `:515` path covered by `:518` | partial |
| 116 | `DatagramSocket()` creation | `service/vpn/DnsResolverPool.kt:92` | UDP socket | try `:95`, logged with throwable | compliant |
| 117 | `protectSocket(socket)` invocation | `service/vpn/DnsResolverPool.kt:93` | VPN socket protection | try `:95`, but lambda swallows at `AdBlockVpnService.kt:119` | partial |
| 118 | `socket.send(outPacket)` | `service/vpn/DnsResolverPool.kt:51` | UDP send | try `:67` + non-reusable marking | compliant |
| 119 | `socket.soTimeout = ...` | `service/vpn/DnsResolverPool.kt:57` | socket option | try `:67` | compliant |
| 120 | `socket.receive(inPacket)` | `service/vpn/DnsResolverPool.kt:60` | UDP receive | try `:62` (timeout → fallback) / `:67` | compliant |
| 121 | `socket.close()` ×3 | `service/vpn/DnsResolverPool.kt:107,112,123` | socket close | empty catches | **VIOLATION** (rows 60-62) |
| 122 | DNS packet parse/build surface: `isDnsQuery`, `getDnsPayloadOffset/Length`, `getQuestionKey`, `getDomainName`, `createSinkhole/NxDomain(IPv4,IPv6)`, `wrapDnsResponse(IPv4,IPv6)`, `computeIpChecksum`, `computeUdpChecksumIPv6` | `service/vpn/DnsPacketHelper.kt:39,47,82,88,113,158,175,228,244,306,346,396` | packet parsing | explicit bounds checks throughout; 7 catches (`:107,147,164,217,295,335,385`) — 6 logged, `:107` counter-only | compliant except `:107` |

### 2.6 PackageManager queries

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 123 | `pm.queryIntentActivities(intent, 0)` | `AppUtils.kt:78-80` | PackageManager | none | **UNPROTECTED** |
| 124 | `info.loadLabel(pm)` | `AppUtils.kt:83` | PackageManager | none (outside the icon try) | **UNPROTECTED** |
| 125 | `info.loadIcon(pm)` + `drawableToBitmap` | `AppUtils.kt:85` | PackageManager | try `:86` → silent `null` | **VIOLATION** (row 3) |
| 126 | `getApplicationLabel(getApplicationInfo(pkg,0))` | `Extensions.kt:10` | PackageManager | try `:11` → documented `pkg` fallback | compliant |
| 127 | `packageManager.resolveActivity(HOME)` | `service/OverlayService.kt:547-550` | PackageManager | caller try `:382` | compliant |
| 128 | `packageManager.getLaunchIntentForPackage(pkg)` | `service/OverlayService.kt:557` | PackageManager | caller try `:382` | compliant |
| 129 | `packageManager.getPackageInfo(pkg, ...)` (API 33+ / legacy) | `GrayzoneApplication.kt:240,246` | PackageManager | try `:249` → silent `false` | **VIOLATION** (row 13) |
| 130 | `PowerManager.isIgnoringBatteryOptimizations` | `AppUtils.kt:140` | system service | none; callers `ui/HomeScreen.kt:44,54`, `ui/OnboardingScreen.kt:38,46` | **UNPROTECTED** |

### 2.7 System settings / secure settings

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 131 | `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)` | `AppUtils.kt:110-113` | secure settings | try `:119`, logged; conservative `false` | compliant |
| 132 | `Settings.Global.getString("private_dns_mode")` | `AppUtils.kt:156-159` | global settings | try `:162` → silent `false` | **VIOLATION** (row 5) |
| 133 | `Settings.Secure.getString(ANDROID_ID)` | `data/StateBackupManager.kt:255` | secure settings | try `:256` → silent `""` | **VIOLATION** (row 22) |
| 134 | `Settings.Secure.putString(daltonizer, "0")` | `service/OverlayService.kt:776-780` | secure settings write | try `:781` — comment only | **VIOLATION** (row 41) |
| 135 | `Settings.canDrawOverlays(this)` | `service/OverlayService.kt:790` | settings check | none (non-throwing) + explicit warn/Toast branch | compliant |
| 136 | `Settings.canDrawOverlays(context)` | `MainActivity.kt:191`, `ui/HomeScreen.kt:43,53`, `ui/OnboardingScreen.kt:37,45` | settings check | none (non-throwing) | acceptable |
| 137 | `Environment.isExternalStorageManager()` | `MainActivity.kt:89` | storage permission | none | **UNPROTECTED** |
| 138 | `AccessibilityServiceIds.isServiceEnabled` parsing of the raw setting | `AccessibilityServiceIds.kt:18-34` | settings parsing | pure, null/blank-safe, no exceptions possible | compliant |
| 139 | `SetupRequirements.missing/label/headline/body` | `SetupRequirements.kt:16-46` | pure (settings-derived) | pure | compliant |

### 2.8 AlarmManager scheduling

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 140 | `am.canScheduleExactAlarms()` | `service/SessionExpiryScheduler.kt:46` | AlarmManager | try `:52`/`:63` | compliant |
| 141 | `setExactAndAllowWhileIdle` | `service/SessionExpiryScheduler.kt:48` | AlarmManager | try `:52` → documented inexact fallback | compliant |
| 142 | `setAndAllowWhileIdle` (primary) | `service/SessionExpiryScheduler.kt:50` | AlarmManager | try `:63`, logged with package | compliant |
| 143 | `setAndAllowWhileIdle` (fallback) | `service/SessionExpiryScheduler.kt:55` | AlarmManager | try `:56`, logged with package | compliant |
| 144 | `PendingIntent.getBroadcast` | `service/SessionExpiryScheduler.kt:28-33` (called `:44`) | IPC | none — built before the try at `:45` | partial |
| 145 | `alarmManager().cancel(pendingIntent)` | `service/SessionExpiryScheduler.kt:75` | AlarmManager | try `:76`, logged with package | compliant |
| 146 | `canScheduleExactAlarms` + `set*AndAllowWhileIdle` | `service/vpn/VpnHoldExpiryScheduler.kt:37-41` | AlarmManager | try `:43`/`:49` | compliant |
| 147 | fallback `setAndAllowWhileIdle` | `service/vpn/VpnHoldExpiryScheduler.kt:45` | AlarmManager | try `:46`, logged | compliant |
| 148 | `alarmManager(context).cancel(...)` | `service/vpn/VpnHoldExpiryScheduler.kt:56` | AlarmManager | try `:57`, logged | compliant |
| 149 | `PendingIntent.getBroadcast` | `service/vpn/VpnHoldExpiryScheduler.kt:24-29` (called `:35`) | IPC | none — built before the try at `:36` | partial |
| 150 | `alarmManager.set(ELAPSED_REALTIME, +2s, pendingIntent)` (restart after swipe-away) | `service/OverlayService.kt:698-703` | AlarmManager | none | **UNPROTECTED** |
| 151 | `PendingIntent.getService` (restart intent) | `service/OverlayService.kt:691-696` | IPC | none | **UNPROTECTED** |

### 2.9 WindowManager addView / removeView

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 152 | `getSystemService(WINDOW_SERVICE) as WindowManager` | `service/OverlayService.kt:145` | system service + cast | none (in `onCreate`) | **UNPROTECTED** |
| 153 | `windowManager?.addView(root, wlp)` | `service/OverlayService.kt:990` | WindowManager | try `:992` — `Log.e` message only, then `return` | **VIOLATION** (row 45) |
| 154 | `windowManager?.removeView(it)` | `service/OverlayService.kt:1039` | WindowManager | try `:1040`, logged | compliant |
| 155 | `root.startAnimation(alphaAnim)` | `service/OverlayService.kt:1001` | view/UI | none | acceptable (cosmetic) |
| 156 | overlay-mode strategy resolution + callbacks | `service/overlay/OverlayPresentation.kt:63-68` and the 4 presentations | pure | pure; no I/O, no catches | compliant |

### 2.10 Notification posting and `startForeground`

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 157 | `startForeground(NOTIF_ID, buildNotification())` | `service/OverlayService.kt:144` | FGS start | none | **UNPROTECTED** |
| 158 | `createNotificationChannel` | `service/OverlayService.kt:1181-1187` | NotificationManager | none | **UNPROTECTED** |
| 159 | `nm.notify(NOTIF_ID, countdown notification)` | `service/OverlayService.kt:728-750` | notification post | none locally; `:528` path covered by `:382`, `:666`/`:1097` not | partial |
| 160 | `nm.notify(NOTIF_ID, buildNotification())` (cancel countdown) | `service/OverlayService.kt:755` | notification post | none locally | partial |
| 161 | `RemoteViews(...)` + `setChronometer` | `service/OverlayService.kt:722-726` | notification layout | none | partial |
| 162 | `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` | `service/OverlayService.kt` n/a; `service/vpn/AdBlockVpnService.kt:249,308-309` | FGS lifecycle | inside try `:228` / plain call | acceptable |
| 163 | `nm.notify(NOTIF_ID, buildNotification())` (refresh action) | `service/vpn/AdBlockVpnService.kt:125-126` | notification post | none | **UNPROTECTED** |
| 164 | `createNotificationChannel` | `service/vpn/AdBlockVpnService.kt:578-588` | NotificationManager | none | **UNPROTECTED** |
| 165 | `PendingIntent.getActivity/getService` in notifications | `service/OverlayService.kt:743,1199`; `service/vpn/AdBlockVpnService.kt:609,618` | IPC | none | acceptable |

### 2.11 WorkManager

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 166 | `PeriodicWorkRequestBuilder<CleanupWorker>(7, DAYS).build()` | `GrayzoneApplication.kt:286-288` | WorkManager | try `:300`, logged | compliant |
| 167 | `WorkManager.getInstance(this).enqueueUniquePeriodicWork(...)` | `GrayzoneApplication.kt:290-294` | WorkManager enqueue | try `:300`, logged | compliant |
| 168 | `CleanupWorker.doWork()` body | `data/CleanupWorker.kt:40-63` | WorkManager execution | try `:56` + `Result.retry()` | compliant |

### 2.12 Gson / JSON (persisted data)

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 169 | `sharedGson.fromJson<List<String>>(customPromptsJson, type)` | `service/OverlayService.kt:851-853` | Gson parse | try `:854`, logged, falls back to defaults | compliant |
| 170 | `gson.fromJson(json, List<ScheduleRule>)` | `data/ScheduleManager.kt:36-37` | Gson parse | try `:38` → silent `emptyList()` | **VIOLATION** (row 15) |
| 171 | `gson.toJson(rules)` | `data/ScheduleManager.kt:42` | Gson serialize | none | **UNPROTECTED** |
| 172 | `gson.fromJson(json, Map<String,String>)` (achievements read) | `data/StreakManager.kt:132-133` | Gson parse | try `:134` → silent `emptyMap()` | **VIOLATION** (row 16) |
| 173 | `gson.fromJson(json, MutableMap<String,String>)` (unlock path) | `data/StreakManager.kt:146-147` | Gson parse | try `:148` → silent `mutableMapOf()`, then overwrites the stored JSON at `:153` | **VIOLATION** (row 17) |
| 174 | `gson.toJson(existing)` | `data/StreakManager.kt:153` | Gson serialize | none | **UNPROTECTED** |
| 175 | `gson.fromJson<List<String>>(json, type)` (prompts editor) | `ui/CustomPromptsSheet.kt:42-43` | Gson parse | try `:44` → silent `emptyList()`, then overwrites at `:55` | **VIOLATION** (row 75) |
| 176 | `gson.toJson(newList)` | `ui/CustomPromptsSheet.kt:55` | Gson serialize | none | **UNPROTECTED** |
| 177 | `JsonParser.parseString(json).asJsonObject` | `data/StateBackupManager.kt:230` | JSON parse (restore) | none locally; outer `:128` logged-bare | partial |
| 178 | `root.getAsJsonObject("entries")` | `data/StateBackupManager.kt:231` | JSON parse | `?: return false` (explicit) + outer `:128` | partial |
| 179 | `obj.get("t")?.asString` and `asBoolean/asInt/asLong/asFloat/asString` per entry | `data/StateBackupManager.kt:235-240` | JSON coercion | none locally; outer `:128` logged-bare | partial |
| 180 | `obj.getAsJsonArray("v").map { it.asString }` | `data/StateBackupManager.kt:242` | JSON coercion | none locally; outer `:128` | partial |
| 181 | `JsonObject`/`JsonArray` build (serialize) | `data/StateBackupManager.kt:200-227` | JSON serialize | try `:176`, logged | compliant |

### 2.13 IPC: broadcasts, service starts, activity starts

| # | Boundary | File:line | Kind | Failure handling | Verdict |
|---|---|---|---|---|---|
| 182 | `ContextCompat.registerReceiver(APP_CHANGED)` | `service/OverlayService.kt:188-193` | IPC | try `:195`, logged | compliant |
| 183 | `ContextCompat.registerReceiver(SCREEN_OFF)` | `service/OverlayService.kt:213-218` | IPC | try `:220`, logged | compliant |
| 184 | `unregisterReceiver(receiver)` | `service/OverlayService.kt:232` | IPC | try `:233`, logged-bare | compliant (note) |
| 185 | `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)` | `service/OverlayService.kt:806-813` | IPC | try `:814`, logged | compliant |
| 186 | `startActivity(HOME)` after skip | `service/OverlayService.kt:957-958` | IPC | try `:959`, logged | compliant |
| 187 | `Toast.makeText(...).show()` on revoked overlay | `service/OverlayService.kt:797-803` | UI/IPC | none; is itself the user-facing failure signal | compliant |
| 188 | `serviceInfo = info` (a11y config apply) | `service/GrayzoneAccessibilityService.kt:55` | Android service callback | try `:57`, logged | compliant |
| 189 | `sendBroadcast(ACTION_APP_CHANGED)` with explicit package | `service/GrayzoneAccessibilityService.kt:86-89` | IPC (cross-process) | outer try `:106`, logged | compliant |
| 190 | `ContextCompat.startForegroundService(OverlayService)` watchdog | `service/GrayzoneAccessibilityService.kt:93-99` | IPC (cross-process) | try `:100`, logged | compliant |
| 191 | `onAccessibilityEvent` event access (`event.eventType`, `event.packageName`) | `service/GrayzoneAccessibilityService.kt:71-73` | Android service callback | outer try `:106`, logged | compliant |
| 192 | `context.startForegroundService(OverlayService)` | `service/BootReceiver.kt:40` | IPC | try `:42`, logged | compliant |
| 193 | `android.net.VpnService.prepare(context)` | `service/BootReceiver.kt:61` | IPC / VPN consent | none — inside a `BroadcastReceiver` | **UNPROTECTED** |
| 194 | `context.startForegroundService(AdBlockVpnService)` | `service/BootReceiver.kt:69` | IPC | try `:74`, logged, intent preserved | compliant |
| 195 | `context.startForegroundService(OverlayService)` (session expiry) | `service/SessionExpiryReceiver.kt:22-26` | IPC | try `:27`, logged with package | compliant |
| 196 | `context.startService(AdBlockVpnService ACTION_HOLD_EXPIRED)` | `service/vpn/VpnHoldExpiryScheduler.kt:90` | IPC | try `:91`, logged | compliant |
| 197 | `startActivity(MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)` | `MainActivity.kt:94-99` | IPC | try `:100` → fallback intent | compliant |
| 198 | `startActivity(MANAGE_ALL_FILES_ACCESS_PERMISSION)` | `MainActivity.kt:102-105` | IPC | try `:106` — comment only | **VIOLATION** (row 8) |
| 199 | `VpnService.prepare(context)` | `MainActivity.kt:139` | IPC / VPN consent | none | **UNPROTECTED** |
| 200 | `context.startForegroundService(AdBlockVpnService ACTION_START)` | `MainActivity.kt:163-167` | IPC | try `:169` — comment only | **VIOLATION** (row 9) |
| 201 | `context.startForegroundService(OverlayService)` | `MainActivity.kt:257` | IPC | none | **UNPROTECTED** |
| 202 | `vpnConsentLauncher.launch(consentIntent)` | `MainActivity.kt:260` | IPC | try `:261` — comment only | **VIOLATION** (row 10) |
| 203 | `permissionLauncher.launch(POST_NOTIFICATIONS)` | `MainActivity.kt:253` | IPC | none; result callback ignores denial (`:235`) | partial |
| 204 | `checkSelfPermission(POST_NOTIFICATIONS)` | `MainActivity.kt:249-251` | permission | none (non-throwing) | acceptable |
| 205 | `context.startForegroundService(ACTION_START)` | `ui/VpnScreen.kt:526-529` | IPC | none | **UNPROTECTED** |
| 206 | `context.startService(ACTION_STOP)` | `ui/VpnScreen.kt:532-535` | IPC | none | **UNPROTECTED** |
| 207 | `context.startService(ACTION_REFRESH_NOTIFICATION)` | `ui/VpnScreen.kt:538-543` | IPC | none | **UNPROTECTED** |
| 208 | `VpnService.prepare(context)` | `ui/VpnScreen.kt:98` | IPC / VPN consent | none | **UNPROTECTED** |
| 209 | `vpnPermLauncher.launch(permIntent)` | `ui/VpnScreen.kt:100` | IPC | none | **UNPROTECTED** |
| 210 | `startActivity(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` | `ui/HomeScreen.kt:171-175` | IPC | none | **UNPROTECTED** |
| 211 | `startActivity(settingsIntentFor(context, requirement))` | `ui/HomeScreen.kt:187` | IPC | none | **UNPROTECTED** |
| 212 | `startActivity(ACTION_ACCESSIBILITY_SETTINGS)` | `ui/OnboardingScreen.kt:104` | IPC | none | **UNPROTECTED** |
| 213 | `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)` | `ui/OnboardingScreen.kt:156` | IPC | none | **UNPROTECTED** |
| 214 | `startActivity(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` | `ui/OnboardingScreen.kt:209-212` | IPC | none | **UNPROTECTED** |
| 215 | `context.startActivity(intent)` (OEM battery settings) | `OemBatteryHelper.kt:63` | IPC | try `:69`, logged, returns `false` | compliant |
| 216 | `context.startActivity(Intent.createChooser(...))` | `SharedComponents.kt:207` | IPC | try `:208`, logged + Toast | compliant |
| 217 | `TimePickerDialog(...).show()` ×2 | `ui/ScheduleSheet.kt:254-257,271-274` | UI/IPC | none | acceptable |
| 218 | `registerActivityLifecycleCallbacks(...)` | `GrayzoneApplication.kt:109-124` | Android callback | none | acceptable |
| 219 | `Application.getProcessName()` process guard | `GrayzoneApplication.kt:54` | process/IPC | explicit early return for `:a11y` | compliant |
| 220 | `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` — **no `CoroutineExceptionHandler`** | `GrayzoneApplication.kt:36` | async boundary | none | **UNPROTECTED** |
| 221 | `serviceScope` with `CoroutineExceptionHandler` | `service/OverlayService.kt:107-117` | async boundary | handler logs uncaught throwables | compliant |
| 222 | `serviceScope` with `CoroutineExceptionHandler` | `service/vpn/AdBlockVpnService.kt:92-102` | async boundary | handler logs uncaught throwables | compliant |
| 223 | `Thread.setUncaughtExceptionHandler` on the VPN thread | `service/vpn/AdBlockVpnService.kt:351-358` | async boundary | logs + restarts the tunnel | compliant |
| 224 | `rememberCoroutineScope().launch { StreakEvaluator.run }` | `ui/HomeScreen.kt:56-58` | async boundary | none | **UNPROTECTED** |

---

## PART 3 — Analysis

### VIOLATIONS — ranked

Ranked by user-visible consequence. Severity rubric for this app: protection that fails with **no
crash and no user notification** is the worst outcome (Critical); a swallowed exception in a cosmetic
UI path is Low.

#### Critical — protection silently absent or silently stopped

**C1. `service/OverlayService.kt:992` — the pause/lock screen fails to appear and nothing says so**

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "addView failed: ${e.message}"); return
}
```

- **Swallowed**: any `WindowManager.addView` failure (`BadTokenException`, revoked
  `SYSTEM_ALERT_WINDOW`, `WindowManager$InvalidDisplayException`, OEM overlay restrictions).
- **User experience**: the monitored app opens with no friction screen, and a LOCK / BUDGET_LOCK /
  SCHEDULE_LOCK never appears — the app is fully usable during a lockout. The `return` at `:993` also
  skips the countdown job, so `markAppUnlocked` never runs and the session bookkeeping diverges from
  what the user sees. Nothing is shown to the user; the log line does not even record the package or
  the overlay mode, so the incident cannot be attributed afterwards.
- **Minimal fix**: log via `GrayzoneLogger.e(LogComponent.OVERLAY, "addView failed for $packageName mode=$mode", e)`
  and surface the failure the way `:791-803` already does for a revoked overlay permission (warn +
  Toast), so a silently-skipped block becomes visible.

**C2. `service/vpn/AdBlockVpnService.kt:119` — DNS sockets may be routed into our own tunnel, silently**

```kotlin
try { protect(socket) } catch (e: Exception) { /* ignore */ }
```

- **Swallowed**: `VpnService.protect()` failure — the resolver's `DatagramSocket` is then **not**
  excluded from the tunnel.
- **User experience**: upstream DNS queries are re-routed back into our own VPN interface. Depending
  on the device this yields a resolution loop or dead DNS: pages stop loading while the notification
  says "Grayzone Protection Active" (`:597`) and the VPN screen shows "Filtering DNS traffic"
  (`ui/VpnScreen.kt:155`). No log, no counter, nothing in the UI.
- **Minimal fix**: `GrayzoneLogger.e(LogComponent.VPN, "protect(socket) failed — resolver socket is inside the tunnel", e)`
  and treat it as a socket-creation failure (return `null` from `acquireSocket`) rather than handing
  back an unprotected socket.

**C3. `data/ScheduleManager.kt:38` — every schedule rule silently disappears**

```kotlin
} catch (_: Exception) { emptyList() }
```

- **Swallowed**: `JsonSyntaxException` / `IllegalStateException` on `SCHEDULE_RULES_JSON`.
- **User experience**: `getScheduleRules()` returns empty, so `isCurrentlyScheduled()` (`:75`) returns
  false and `SessionPolicyEngine` never emits a schedule lock. Bedtime and work-hours blocking simply
  stop working. Worse, the Schedule sheet then shows "No schedules configured."
  (`ui/ScheduleSheet.kt:160`) and the next `addRule`/`toggleRule`/`removeRule` call writes the
  now-empty list back through `saveScheduleRules` (`:42`) — permanent, silent loss of the user's rules.
- **Minimal fix**: log with the key name and the raw length
  (`GrayzoneLogger.e(LogComponent.SCHEDULE, "Failed to parse SCHEDULE_RULES_JSON (len=${json.length}); schedules disabled", e)`)
  and do not let a parse failure be overwritten — quarantine the bad value under a backup key before
  any subsequent write.

**C4. `service/vpn/AdBlockVpnService.kt:288` — the tunnel descriptor close failure is discarded**

```kotlin
} catch (e: Exception) { /* ignored */ }
```

- **Swallowed**: `IOException`/`IllegalStateException` from `vpnInterface?.close()` (`:287`).
- **User experience**: `vpnInterface` is nulled at `:289` regardless, so the descriptor is leaked. On
  the restart path (`restartVpnAfterDelay` → `shutdownVpn` → `startVpn`) a still-open descriptor makes
  the next `establish()` fail, and that failure is reported to the user as "Another VPN may be active"
  (`:235`) — a misleading message for a bug in our own teardown. Meanwhile `PrefsKeys.VPN_ENABLED`
  stays true (`:329-332`), so the UI keeps promising protection that is off.
- **Minimal fix**: `GrayzoneLogger.w(LogComponent.VPN, "Failed to close VPN interface during shutdown", e)`
  before nulling the field.

#### High — anti-tamper and protection *recovery* silently fail

**H1. `data/StateBackupManager.kt:256` — a failed ANDROID_ID read silently poisons the backup key**

```kotlin
} catch (_: Exception) {
    ""
}
```

- **Swallowed**: any failure reading `Settings.Secure.ANDROID_ID`.
- **User experience**: `deriveKey` (`:261-263`) then derives the AES-256 key from `"|$KEY_PEPPER"`.
  A backup written under the degraded key cannot be decrypted once the real ID is readable again (and
  vice versa) — `decrypt` fails the GCM tag and `readBackup` refuses the file (`:193-196`). The
  anti-tamper guarantee is gone: clearing app data really does erase the user's locks, permanently and
  with no signal. This is the exact scenario the whole file exists to prevent (`:29-32`).
- **Minimal fix**: log the failure and refuse to encrypt/decrypt with a blank id — return `null` and
  skip the write rather than producing an undecryptable file.

**H2. `data/StateBackupManager.kt:128` — the one anti-tamper restore path fails opaquely**

```kotlin
} catch (e: Exception) {
    GrayzoneLogger.w(LogComponent.BOOT, "State restore failed: ${e.message}")
    false
}
```

- **Swallowed context**: the throwable itself. This single catch spans the prefs read (`:105`), the
  file read (`:190`, which sits *outside* the inner try at `:191`), JSON parsing and per-entry
  coercion (`:230-243`), and `editor.commit()` (`:250`). For a `NullPointerException` or
  `IllegalStateException` with a null message, the log reads literally `State restore failed: null`.
- **User experience**: after a clear-data the locks stay wiped and the user is free of their own
  limits. Nobody can tell from the log whether it was a permission denial, a corrupt file, a schema
  drift, or a failed commit. The same file logs correctly 48 lines later (`:177` passes `e`), so this
  is an inconsistency, not a house style.
- **Minimal fix**: pass the throwable and name the step —
  `GrayzoneLogger.e(LogComponent.BOOT, "State restore failed at ${hiddenFile().absolutePath}", e)`;
  raise the level to `e` since a failure here defeats the anti-tamper design.

**H3. `MainActivity.kt:169` — the restored VPN never resumes and the UI keeps lying**

```kotlin
} catch (_: Exception) {
    // Best-effort; the user can still enable the VPN manually from the VPN screen.
}
```

- **Swallowed**: `startForegroundService` failures (`ForegroundServiceStartNotAllowedException`,
  `SecurityException`) plus any throw from `VpnHoldExpiryScheduler.rearmFromPrefs` (`:168`).
- **User experience**: this function exists specifically to fix "the UI shows a hold countdown while
  the VPN is actually off" (`:114-116`). When it fails silently, that exact bug returns: the VPN
  screen displays "Locked on — Xh left" (`ui/VpnScreen.kt:277`) with no tunnel running. A failure at
  `:168` also leaves the 24 h auto-off alarm unarmed, so the hold may never release.
- **Minimal fix**: `GrayzoneLogger.e(LogComponent.VPN, "Failed to resume restored VPN", e)` and set
  `ProtectionHealthRepository.markVpnReconnectPending(true)` so the existing
  "Protection interrupted — reconnecting" banner (`ui/VpnScreen.kt:156`) tells the truth.

**H4. `MainActivity.kt:106` — the all-files-access prompt fails once and is never retried**

```kotlin
} catch (_: Exception) {
    // No settings activity available; fallback storage will be used.
}
```

- **Swallowed**: `ActivityNotFoundException` from both settings intents (`:94`, `:102`).
- **User experience**: `PrefsKeys.ALL_FILES_PROMPTED` was already set to `true` at `:92` *before* the
  attempt, so `requestAllFilesAccessOnce` early-returns forever after (`:91`). Without all-files
  access `writeBackup` returns early every time (`data/StateBackupManager.kt:161-164`) and no backup
  ever exists — clear-data escapes all locks. The comment is also factually wrong: there is no
  fallback storage anywhere in `StateBackupManager`.
- **Minimal fix**: log the failure, only set `ALL_FILES_PROMPTED` after an intent actually launched,
  and delete the inaccurate comment.

**H5. `MainActivity.kt:261` — the VPN consent dialog silently never appears**

```kotlin
} catch (_: Exception) {
    // No VPN consent activity available; the user can enable the VPN manually.
}
```

- **Swallowed**: `ActivityNotFoundException` / `IllegalStateException` from
  `vpnConsentLauncher.launch`.
- **User experience**: after a reinstall or clear-data that revoked VPN consent, the automatic one-tap
  re-consent flow (designed at `:141-148` so reinstalling cannot escape the hold) never opens. The
  hold countdown keeps ticking in the UI while no tunnel exists, and no log records why.
- **Minimal fix**: `GrayzoneLogger.e(LogComponent.VPN, "Failed to launch VPN consent dialog", e)` and
  fall back to a visible in-app prompt on the VPN screen.

#### Medium — resource leaks, lost user data, lost observability

**M1. `service/vpn/DnsResolverPool.kt:107`, `:112`, `:123` — three empty catches on socket close**

```kotlin
try { socket.close() } catch (_: Exception) {}          // :107
try { socket.close() } catch (_: Exception) {}          // :112
try { it.close() } catch (e: Exception) {}              // :123 (binds `e`, never uses it)
```

- **Swallowed**: every close failure, on the exact path taken after an error made a socket
  non-reusable (`:70`, `:106`).
- **User experience**: leaked UDP file descriptors. The pool is bounded at 20 permits (`:22`), but
  leaked descriptors accumulate against the process limit; once exhausted, `DatagramSocket()` fails
  (`:92`), `performQuery` returns `null`, `performDnsQuery` reports "All DNS servers failed"
  (`AdBlockVpnService.kt:568-571`) and every allowed domain stops resolving — while the VPN still
  reports Active. `:123` additionally trips Principle I (unused binding).
- **Minimal fix**: one shared `closeQuietly` that logs at debug/warn with the reason
  (`reusable=false` vs `shutdown`), and drop the unused `e` at `:123`.

**M2. `data/StreakManager.kt:148` — corrupt achievements JSON is silently overwritten**

```kotlin
} catch (_: Exception) { mutableMapOf() }
```

- **Swallowed**: parse failure of `ACHIEVEMENTS_JSON` inside `unlockAchievement`.
- **User experience**: the map resets to empty and `:153` immediately writes it back
  (`prefs.edit().putString(ACHIEVEMENTS_JSON, gson.toJson(existing)).apply()`), destroying every
  previously unlocked achievement permanently. The user watches their badges disappear with no
  explanation.
- **Minimal fix**: log with the key name, and on a parse failure skip the write instead of committing
  the empty map (or copy the bad value to a `*_corrupt` key first).

**M3. `ui/CustomPromptsSheet.kt:44` — the user's custom prompts are silently destroyed**

```kotlin
} catch (e: Exception) { emptyList() }
```

- **Swallowed**: parse failure of `CUSTOM_PROMPTS_JSON`; `e` is bound and never used.
- **User experience**: the editor opens empty ("No custom prompts added yet.", `:141`); the first add
  or delete calls `savePrompts` (`:53-56`), which overwrites the stored JSON with the truncated list.
  Every custom reflection prompt the user wrote is gone. Note the same JSON is parsed *correctly* 
  in the overlay path at `service/OverlayService.kt:851-860` (logged, falls back to defaults), so the
  two readers disagree.
- **Minimal fix**: reuse the overlay's pattern — log with `GrayzoneLogger.w` and do not write back
  until the user makes an explicit change to a successfully parsed list.

**M4. `GrayzoneApplication.kt:249` — a PackageManager failure is silently read as "app not installed"**

```kotlin
} catch (_: Exception) {
    false
}
```

- **Swallowed**: everything `getPackageInfo` can throw, not just `NameNotFoundException` — including
  `TransactionTooLargeException` and `DeadObjectException` when the package manager is under pressure
  during cold start.
- **User experience**: Instagram/YouTube are treated as absent, so the one-shot default monitored-app
  seeding (`:253-261`) skips them. A first-time user gets an app that monitors nothing and never says
  why. Only a total failure leaves the retry open; a partial failure still writes
  `DEFAULT_APPS_SEEDED = true` at `:260`, making the miss permanent.
- **Minimal fix**: catch `NameNotFoundException` for the expected case and log anything else
  (`GrayzoneLogger.w(LogComponent.BOOT, "getPackageInfo failed for $pkg; treating as not installed", e)`).

#### Low — cosmetic, expected, or currently unreachable

**L1. `service/vpn/AdBlockVpnService.kt:268`** — comment-only catch of
`PackageManager.NameNotFoundException` when bypassing an uninstalled app. Expected and benign; the
aggregate outcome *is* logged at `:272-275`. Constitution-literal violation (comment-only body) with
no user impact. Fix: `GrayzoneLogger.d(LogComponent.VPN, "Bypass skipped, not installed: $pkg")`.

**L2. `service/OverlayService.kt:781`** — comment-only catch of `SecurityException` while clearing a
stale system grayscale setting. The intent is documented in the KDoc at `:773` ("Best-effort … No-op
without grant"), and the operation is cosmetic. Fix: a single debug log.

**L3. `service/vpn/DnsPacketHelper.kt:107`** — increments `domainParseErrors` but never logs. The
counter is only observable through `getErrorStats()`, which has no production reader (only
`app/src/test/.../DnsPacketTest.kt:195` and `IntegrationTest.kt:110`), so in a shipped build the
failure is invisible. Impact is limited: a null question key makes
`forwardDnsQueryWithDeduplication` take the non-deduplicated path (`AdBlockVpnService.kt:496-500`),
so the query still resolves. Fix: add a `GrayzoneLogger.d` mirroring the sibling catch at `:147`.

**L4. `AppUtils.kt:86`** — `catch (_: Exception) { null }` around icon decoding. The UI already renders
a placeholder for a null icon (`ui/AppsScreen.kt:302-311`), so the consequence is a missing icon.
Fix: debug-log the package whose icon failed.

**L5. `AppUtils.kt:162`** — `catch (_: Exception) { false }` in `isPrivateDnsActive`. If it could be
reached it would be Medium: reporting "Private DNS off" when it is actually on tells the user DNS
filtering works while it is being fully bypassed on port 853. It is Low **only** because the function
has no production caller today (grep: defined at `AppUtils.kt:154`, referenced nowhere in
`src/main`) — which also means the KDoc's "must be surfaced as a UI warning instead (see HomeScreen)"
(`service/vpn/BlocklistManager.kt:28-30`) is unimplemented, and the function itself is dead code
under Principle I. Fix: wire it into `HomeScreen`'s status card *and* log the read failure.

**L6. `data/StreakManager.kt:134`** — `catch (_: Exception) { emptyMap() }` in `getAchievements`.
Read-only display path (`ui/StreakCard.kt:33,40`), so badges appear locked until the next successful
parse. Fix: log once with the key name.

### Positive examples — credit these, do not "fix" them

1. **`service/SessionExpiryScheduler.kt:52-62`** — the reference implementation. Catches the narrow
   `SecurityException`, explains why in a comment (`:53` "Exact-alarm permission changed between check
   and call — fall back to the inexact path"), takes a documented recovery action (`:55`), and logs the
   *fallback's own* failure with the package name (`:57-61`). Every other alarm/IPC catch in the
   codebase should look like this.
2. **`service/OverlayService.kt:851-860`** — the model Gson site: parses `CUSTOM_PROMPTS_JSON` inside a
   try, logs what failed and what it is doing about it ("Failed to parse custom prompts JSON; falling
   back to defaults"), and degrades to the default prompt pool. Contrast with `ScheduleManager.kt:38`
   and `StreakManager.kt:134/148`.
3. **`service/vpn/AdBlockVpnService.kt:204-208`** — refuses to mask a failure with a default:
   `establish()` returning `null` is converted into an `IllegalStateException` carrying the actual
   diagnosis ("VPN permission revoked or another VPN is active") instead of entering a broken
   "running without an interface" state. The comment at `:204-206` states the reasoning.
4. **`service/vpn/AdBlockVpnService.kt:185-189` and `:246-250`** — documents the
   `startForeground`-before-`establish()` ordering and, on failure, explicitly tears the foreground
   state down so the process is not killed for a mismatched foreground-service lifecycle.
5. **`service/vpn/AdBlockVpnService.kt:344-412`** — error handling with real policy: a consecutive-error
   counter, a 5 s non-consecutive reset window, exponential backoff, and an escalation to a full
   service restart, each step logged.
6. **`service/GrayzoneAccessibilityService.kt:39-63` and `:66-112`** — the broad `catch (e: Exception)`
   here is deliberate and correct for the `:a11y` process, and the reason is written down (`:39-40`,
   `:67-68`): an uncaught exception is one of the triggers that makes Android revoke an accessibility
   service. Both handlers log with component context. `onUnbind` (`:124-130`) additionally logs a
   possible revocation. Do not narrow these.
7. **`data/StateBackupManager.kt:191-197`** — treats a decryption failure as tamper evidence, explains
   the two causes in a comment (`:194`), logs with the throwable, and refuses to restore garbage.
8. **`data/StateBackupManager.kt:248-250`** — uses `commit()` rather than `apply()` *and returns its
   boolean*, with the reason written down: restored values must be durably visible before anything
   else reads them on the same launch.
9. **`data/CleanupWorker.kt:56-62`** — logs with the throwable and returns `Result.retry()`, so a
   transient database failure is retried instead of silently dropped.
10. **`SharedComponents.kt:181-188`** — checks `exists()`/`canRead()` *before* the operation and tells
    the user with a Toast; the catch at `:208` also surfaces the message to the user rather than only
    to logcat.
11. **`service/vpn/GrayzoneBloomFilter.kt:60-96`** — validates magic, version, hash count, bit count,
    byte alignment and size with `require(...)`, each with a specific message, and throws on a
    truncated stream (`:93`) instead of returning a half-built filter. Failure propagates to the single
    logging handler at `BlocklistManager.kt:151`.
12. **`service/vpn/DnsPacketHelper.kt`** — six of its seven catches log with the throwable *and* name
    the exact operation and IP version ("Failed to wrap DNS response (IPv6)"), on top of per-failure
    counters.
13. **`service/OverlayService.kt:107-117`, `service/vpn/AdBlockVpnService.kt:92-102`,
    `:351-358`** — installed `CoroutineExceptionHandler`s and a thread `UncaughtExceptionHandler` so
    async failures cannot vanish. (`GrayzoneApplication.kt:36` is the one scope missing this — see below.)
14. **`GrayzoneApplication.kt:52-54`** — the `:a11y` process guard, with the reason stated
    (SharedPreferences is not multi-process safe and two writers corrupt the backup).

### Unprotected I/O boundaries

Boundaries with **no** failure handling at all — an exception propagates. Ordered by consequence.

| Boundary | File:line | What happens |
|---|---|---|
| `startForeground(NOTIF_ID, buildNotification())` in `onCreate` | `service/OverlayService.kt:144` | **Dead service.** A `ForegroundServiceStartNotAllowedException` (API 31+ background start) or a notification failure throws out of `onCreate` → OverlayService crashes → no friction, no lockouts, no countdown. `ProtectionHealthRepository.updateOverlayStatus(true)` already ran at `:142`, so the health state reports the overlay as active while it is dead. |
| `startForeground(...)` before `establish()` | `service/vpn/AdBlockVpnService.kt:189` | **Crash + no VPN.** Deliberately hoisted above the try (`:185-188`) to satisfy the FGS contract, but that leaves it unguarded: it throws out of `startVpn` → `onStartCommand` (`:167`) → process crash. `PrefsKeys.VPN_ENABLED` stays true, so every launch and every boot retries the same crash. |
| `alarmManager.set(ELAPSED_REALTIME, +2s, restart PendingIntent)` in `onTaskRemoved` | `service/OverlayService.kt:698-703` | **Silently skipped restart.** If `set` or `PendingIntent.getService` (`:691`) throws, the self-restart after a Recents swipe never schedules and the exception escapes `onTaskRemoved`. Protection stays off until the next app launch or a11y watchdog event. |
| `handleSessionExpired` prefs reads | `service/OverlayService.kt:631-641` | **Session never locks.** Reached synchronously from `onStartCommand:672` with no try. A `ClassCastException` from a drifted `ACTIVE_UNTIL`/`LOCKED_UNTIL` key (E3) crashes the service instead of applying the lockout. |
| `onCreate` prefs reads + expiry re-arm loop | `service/OverlayService.kt:150-162` | **Dead service on corrupt prefs.** No guard; a bad `MONITORED_APPS` or per-package `ACTIVE_UNTIL` type kills the service at startup, so in-flight sessions are never re-armed. |
| `validateConfiguration()` prefs reads | `GrayzoneApplication.kt:176-215` (called `:70`) | **App won't start.** The function exists to repair corrupt config, but its own `getInt`/`getStringSet` calls are unguarded — a type-drifted key throws out of `Application.onCreate` and the app cannot launch at all. Ironic and directly relevant to E3. |
| `resetRuntimeStateIfNeeded()` / `RuntimeSessionReset.resetIfNeeded` | `GrayzoneApplication.kt:272`, `data/RuntimeSessionReset.kt:29-43` (called `:71`) | Same class of failure in `Application.onCreate`: an unguarded prefs read/write on the startup critical path. |
| `appScope` has no `CoroutineExceptionHandler` | `GrayzoneApplication.kt:36` | **Crash from a background job.** Unlike the two services, this scope installs no handler, so anything escaping the launches at `:96`, `:128`, `:139`, `:148`, `:162` reaches the default handler and kills the process. `backupExists` → `hiddenFile().exists()` (`data/StateBackupManager.kt:152`) and `Environment.isExternalStorageManager()` (`:139`) run inside `:96-100` with no try. |
| `Room.databaseBuilder(...).build()` + the 1→2 migration | `data/UsageDatabase.kt:34-47`, `:27-30` | **Crash at first DB touch.** `fallbackToDestructiveMigration` was deliberately removed in favour of `MIGRATION_1_2` (`:42`), so a corrupt file or a failed `execSQL` throws to whichever caller touched it first — including `OverlayService.kt:570` (caught) and `ui/HomeScreen.kt:57` (not caught). |
| `StreakEvaluator.run(context)` from composition | `ui/HomeScreen.kt:56-58` | **UI crash on ON_RESUME.** `rememberCoroutineScope().launch` with no try and no handler; a Room failure or a prefs `ClassCastException` inside `StreakEvaluator` (`data/StreakEvaluator.kt:18-32`) crashes MainActivity every time Home resumes. The other two callers of the same function *do* guard it (`GrayzoneApplication.kt:163-167`, `service/OverlayService.kt:288-292`). |
| `pm.queryIntentActivities(...)` + `info.loadLabel(pm)` | `AppUtils.kt:78-83` | **App-list screens crash.** No guard; a `TransactionTooLargeException`/`DeadObjectException` propagates through `getInstalledAppsCached` into the `LaunchedEffect` at `ui/AppsScreen.kt:51-54` and `ui/LimitsScreen.kt:31-33`. Only the icon decode inside the loop is protected (`:84-88`). |
| `PowerManager.isIgnoringBatteryOptimizations` | `AppUtils.kt:140` | Unguarded system-service call evaluated during composition (`ui/HomeScreen.kt:44,54`; `ui/OnboardingScreen.kt:38,46`) — a throw crashes the screen. |
| `VpnService.prepare(context)` | `service/BootReceiver.kt:61` | **Boot-time crash.** Inside a `BroadcastReceiver`, so a throw here aborts the whole boot restart path — including the already-successful OverlayService start bookkeeping — and the VPN is never restored. |
| `VpnService.prepare(context)` | `MainActivity.kt:139`, `ui/VpnScreen.kt:98` | Unguarded consent probe on the UI path; a throw crashes the activity instead of showing the enable flow. |
| `context.startForegroundService(OverlayService)` | `MainActivity.kt:257` | **UI crash instead of starting protection.** Inside `LaunchedEffect`; on API 31+ a background-start restriction or missing notification permission throws and takes the activity down. Every other call site of this exact API is wrapped (`BootReceiver.kt:39-44`, `SessionExpiryReceiver.kt:21-33`, `GrayzoneAccessibilityService.kt:92-105`). |
| `startForegroundService` / `startService` VPN control | `ui/VpnScreen.kt:527,533,539` | Unguarded; the VPN toggle can crash the screen, and a failed stop leaves the UI and the tunnel disagreeing. |
| `vpnPermLauncher.launch(permIntent)` | `ui/VpnScreen.kt:100` | Unguarded `ActivityNotFoundException` → crash on tapping the enable switch. |
| `startActivity(...)` in permission cards | `ui/HomeScreen.kt:175,187`; `ui/OnboardingScreen.kt:104,156,212` | **Crash on the "fix this" button.** `ActivityNotFoundException` is a real outcome on OEM builds and Android TV/tablet images that lack these settings activities. `OemBatteryHelper.kt:59-77` and `service/OverlayService.kt:806-820` already wrap the same pattern. |
| `nm.notify(...)` / `createNotificationChannel` | `service/OverlayService.kt:728,755,1181-1187`; `service/vpn/AdBlockVpnService.kt:126,578-588` | Unguarded notification posts. `onStartCommand:666` and `:669` reach `nm.notify` with no protection, so a notification failure crashes the service rather than losing a countdown. |
| `getSystemService(WINDOW_SERVICE) as WindowManager` | `service/OverlayService.kt:145` | Unchecked cast in `onCreate`; failure kills the service before any overlay can ever be shown. |
| `gson.toJson(...)` on every persist path | `data/ScheduleManager.kt:42`, `data/StreakManager.kt:153`, `ui/CustomPromptsSheet.kt:55` | Serialization is unguarded; a throw propagates into a UI click handler (`ui/ScheduleSheet.kt:62,176,187`; `ui/CustomPromptsSheet.kt:125,157`) and crashes the sheet mid-edit. |
| `PendingIntent.getBroadcast` built outside the try | `service/SessionExpiryScheduler.kt:44`, `service/vpn/VpnHoldExpiryScheduler.kt:35` | The alarm `set` calls are carefully guarded, but the `PendingIntent` construction that precedes them is not — a failure there bypasses all that handling and propagates to the caller (e.g. `service/OverlayService.kt:529`). |
| `file.readBytes()` outside its own try | `data/StateBackupManager.kt:190` | An `IOException`/`SecurityException` skips the local handler at `:191` and lands in the outer logged-bare catch at `:128`, so the diagnosis ("could not read the file") is lost. |
| `VpnHoldLockStore` prefs read/write | `service/vpn/VpnHoldLockStore.kt:11-40` and callers `service/vpn/AdBlockVpnService.kt:137,157,333,591`, `service/BootReceiver.kt:34-54` | Unguarded on the 24 h-hold critical path. A throw in `onStartCommand`/`onDestroy`/`buildNotification` crashes the VPN service; in `BootReceiver` it aborts boot restoration. |
| `shutdownVpn` prefs write | `service/vpn/AdBlockVpnService.kt:301-304` | Unguarded write during teardown; a failure leaves `VPN_ENABLED` inconsistent with reality, so START_STICKY/BootReceiver make the wrong decision. |
| `ui/theme/Theme.kt:27-28` prefs read | called from `service/OverlayService.kt:1151` | The overlay resolves its colour scheme by reading prefs on the Main thread with no guard; a throw here happens *while building the lock screen*, so the lock is not shown. |
| Read-only prefs reads across all UI screens | `ui/AppsScreen.kt:38-48,191-193`; `ui/LimitsScreen.kt:25-29,71-73,103-105`; `ui/SettingsScreen.kt:35-54`; `ui/PerAppSettingsSheet.kt:29-43`; `ui/HomeScreen.kt:45-55`; `MainActivity.kt:58-67,183-221` | Individually low risk (SharedPreferences reads rarely throw), but all of them are `ClassCastException`-exposed after a schema drift or a restore that wrote a key with the wrong type — see below. |

**Related observability gap (not a catch, but it hides the above):**
`ProtectionHealthRepository.updateAccessibilityStatus` (`data/ProtectionHealthRepository.kt:34`) has
no production caller — only `app/src/test/.../ProtectionStatusTest.kt`. So
`ProtectionStatus.accessibilityActive` is always `false` (`data/ProtectionStatus.kt:6`), and
`isFullyProtected` / `isDegraded` (`:10-14`) can never report the true state of the `:a11y` process.
If the accessibility service is revoked or crashes, no health signal fires. `GrayzoneAccessibilityService`
should call it from `onServiceConnected` (`:36`) and `onUnbind`/`onDestroy` (`:119-130`).

### Gson / JSON parse paths (upgrade-path / E3 risk)

Every `fromJson` / `toJson` / `JsonParser` site on persisted data. After an app upgrade, stored JSON
can be malformed or schema-drifted; Gson then throws `JsonSyntaxException` (and
`IllegalStateException` / `NumberFormatException` from the `as*` coercions).

| Site | File:line | Persisted key / source | Handles `JsonSyntaxException`? | E3 (upgrade) consequence |
|---|---|---|---|---|
| `gson.fromJson<List<String>>` | `service/OverlayService.kt:851-853` | `CUSTOM_PROMPTS_JSON` | **Yes — logged, falls back to defaults** | Prompt pool degrades to defaults; logged. Best-in-class. |
| `gson.fromJson(List<ScheduleRule>)` | `data/ScheduleManager.kt:36-37` | `SCHEDULE_RULES_JSON` | **No — silent `emptyList()`** | Adding/renaming/removing a `ScheduleRule` field across a release drops every rule. Schedule locks stop firing, and the next UI edit persists the empty list. **Critical (C3).** |
| `gson.toJson(rules)` | `data/ScheduleManager.kt:42` | `SCHEDULE_RULES_JSON` | No guard | Serialization failure propagates into a UI click handler and crashes the sheet. |
| `gson.fromJson(Map<String,String>)` | `data/StreakManager.kt:132-133` | `ACHIEVEMENTS_JSON` | **No — silent `emptyMap()`** | Badges display as locked after a drift. **Low (L6).** |
| `gson.fromJson(MutableMap<String,String>)` | `data/StreakManager.kt:146-147` | `ACHIEVEMENTS_JSON` | **No — silent `mutableMapOf()`, then overwrites at `:153`** | First post-upgrade unlock erases all achievement history permanently. **Medium (M2).** |
| `gson.toJson(existing)` | `data/StreakManager.kt:153` | `ACHIEVEMENTS_JSON` | No guard | Unguarded write on the same path. |
| `gson.fromJson<List<String>>` | `ui/CustomPromptsSheet.kt:42-43` | `CUSTOM_PROMPTS_JSON` | **No — silent `emptyList()`, then overwrites at `:55`** | Same key as the compliant overlay site, handled worse: a post-upgrade drift silently deletes the user's prompts on their next edit. **Medium (M3).** |
| `gson.toJson(newList)` | `ui/CustomPromptsSheet.kt:55` | `CUSTOM_PROMPTS_JSON` | No guard | Unguarded write. |
| `JsonParser.parseString(json).asJsonObject` | `data/StateBackupManager.kt:230` | `/sdcard/.grayzone/backup.dat` (decrypted) | Only via the outer logged-bare catch at `:128` | A drifted backup schema aborts the entire restore. `editor.commit()` at `:250` is never reached, so nothing is half-applied — but the user's locks stay wiped and the log says only `State restore failed: <msg>`. **High (H2).** |
| `root.getAsJsonObject("entries")` | `data/StateBackupManager.kt:231` | same | Explicit `?: return false` | Handled, though indistinguishable from "no backup" in the logs. |
| Per-entry `obj.get("t")?.asString`, `asBoolean/asInt/asLong/asFloat/asString` | `data/StateBackupManager.kt:235-240` | same | No per-entry guard | `SCHEMA_VERSION` (`:61`) is written at `:202` but **never read back** in `applyInto`. One entry whose recorded type no longer matches (e.g. a pref that changed from `Int` to `Long` between releases) throws and discards the *whole* restore. **High** — and it is the single most likely E3 failure in this file. Minimal fix: read and branch on `schema`, and wrap each entry so one bad key cannot void the rest. |
| `obj.getAsJsonArray("v").map { it.asString }` | `data/StateBackupManager.kt:242` | same | No per-entry guard | Same as above for `stringset` entries (e.g. `MONITORED_APPS`) — the highest-value key in the backup. |
| `JsonObject`/`JsonArray` build | `data/StateBackupManager.kt:200-227` | same | try `:176`, logged with throwable | Compliant. |

**Cross-cutting E3 note**: `applyInto` restores values by their *recorded* type
(`data/StateBackupManager.kt:236-243`). If a release changes a key's type in `PrefsKeys`, the restore
writes the old type and the next reader — none of which are guarded (see §"Unprotected I/O
boundaries") — throws `ClassCastException` at, for example `service/OverlayService.kt:158`,
`GrayzoneApplication.kt:181`, or `data/ScheduleManager.kt:68`. There is no schema-version gate on the
restore path today.

### Statistics

**Catch blocks: 75 total** across 22 files (46 of the 68 files contain none).

| Classification | Count | Share |
|---|---|---|
| `logged-with-context` | 41 | 54.7% |
| `handled` | 11 | 14.7% |
| `SILENT` | 11 | 14.7% |
| `returns-default-silently` | 8 | 10.7% |
| `logged-bare` | 4 | 5.3% |
| `rethrown` | 0 | 0.0% |

Per-file catch counts: `service/OverlayService.kt` 15 · `service/vpn/AdBlockVpnService.kt` 7 ·
`service/vpn/DnsPacketHelper.kt` 7 · `service/vpn/DnsResolverPool.kt` 6 ·
`service/vpn/VpnHoldExpiryScheduler.kt` 5 · `MainActivity.kt` 4 · `service/SessionExpiryScheduler.kt` 4 ·
`data/StateBackupManager.kt` 4 · `GrayzoneApplication.kt` 4 · `AppUtils.kt` 3 ·
`service/GrayzoneAccessibilityService.kt` 3 · `service/vpn/BlocklistManager.kt` 2 ·
`service/BootReceiver.kt` 2 · `data/StreakManager.kt` 2 · `Extensions.kt` 1 · `OemBatteryHelper.kt` 1 ·
`SharedComponents.kt` 1 · `data/CleanupWorker.kt` 1 · `data/ScheduleManager.kt` 1 ·
`service/SessionExpiryReceiver.kt` 1 · `ui/CustomPromptsSheet.kt` 1.

**Broad-catch flags** (`catch (e: Exception)` / `Throwable` over a block large enough to hide
unrelated failures): 7 — `service/OverlayService.kt:382`, `:616`;
`service/vpn/AdBlockVpnService.kt:228`, `:415`; `service/vpn/BlocklistManager.kt:151` (`Throwable`);
`GrayzoneApplication.kt:151`; `data/StateBackupManager.kt:128`. Two further broad catches are
**intentional and credited**: `service/GrayzoneAccessibilityService.kt:57` and `:106`.

**I/O boundaries: 224 catalogued.**

| Kind | Boundaries | Unprotected |
|---|---|---|
| Room / database | 17 | 6 |
| SharedPreferences | 65 | 42 |
| File / shared storage (incl. VPN descriptor streams) | 19 | 4 |
| `res/raw` asset loading | 6 | 0 |
| VPN descriptor / socket / DatagramSocket / DNS parsing | 15 | 1 |
| PackageManager | 8 | 3 |
| System / secure settings | 9 | 1 |
| AlarmManager scheduling | 12 | 2 |
| WindowManager | 5 | 2 |
| Notifications / `startForeground` | 9 | 4 |
| WorkManager | 3 | 0 |
| Gson / JSON on persisted data | 13 | 4 |
| IPC (broadcasts, service starts, activity starts, async scopes) | 43 | 15 |
| **Total** | **224** | **84** |

Of the 84 unprotected boundaries, 42 are SharedPreferences reads/writes whose realistic failure mode
is a post-upgrade `ClassCastException` (E3); 12 are unguarded `startActivity`/`startForegroundService`
/`VpnService.prepare` IPC calls that crash on `ActivityNotFoundException` or FGS restrictions; and 6
sit on the two service `onCreate`/`onStartCommand` paths, where a throw means protection never starts.
A further 25 boundaries are **partial** — guarded only by a catch several frames up, or by an
unchecked `commit()`/`mkdirs()` return value.

**Violations by severity: 21 total**

| Severity | Count | Sites |
|---|---|---|
| Critical | 4 | `service/OverlayService.kt:992`; `service/vpn/AdBlockVpnService.kt:119`; `data/ScheduleManager.kt:38`; `service/vpn/AdBlockVpnService.kt:288` |
| High | 5 | `data/StateBackupManager.kt:256`; `data/StateBackupManager.kt:128`; `MainActivity.kt:169`; `MainActivity.kt:106`; `MainActivity.kt:261` |
| Medium | 6 | `service/vpn/DnsResolverPool.kt:107`, `:112`, `:123`; `data/StreakManager.kt:148`; `ui/CustomPromptsSheet.kt:44`; `GrayzoneApplication.kt:249` |
| Low | 6 | `service/vpn/AdBlockVpnService.kt:268`; `service/OverlayService.kt:781`; `service/vpn/DnsPacketHelper.kt:107`; `AppUtils.kt:86`; `AppUtils.kt:162`; `data/StreakManager.kt:134` |

Every `SILENT` (11) and `returns-default-silently` (8) block is a violation by Principle III, plus the
2 `logged-bare` blocks whose logging is too thin to diagnose the failure they hide
(`data/StateBackupManager.kt:128`, `service/OverlayService.kt:992`). The other 2 `logged-bare` blocks
(`service/OverlayService.kt:233`, `service/vpn/DnsResolverPool.kt:67`) are recorded as compliant with a
note, since both sit on cleanup paths whose outcome is logged elsewhere.

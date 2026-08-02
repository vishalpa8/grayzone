# T010 — Cross-process state inventory (`:a11y` vs default process)

**Scope:** every Kotlin `object`, `companion object` mutable field, top-level `val`/`var`,
`StateFlow`/`SharedFlow`/`MutableStateFlow`, `mutableStateOf`, and in-memory cache in
`app/src/main/java/com/grayzone/app/`.

**Method:** read-only. No file other than this one was modified.

## Process topology (established from the manifest)

| Component | Manifest line | Process |
|---|---|---|
| `.GrayzoneAccessibilityService` | `AndroidManifest.xml:79-90` (`android:process=":a11y"`, line 82) | **`:a11y`** |
| `.MainActivity` | `AndroidManifest.xml:55-63` | default |
| `.service.OverlayService` | `AndroidManifest.xml:65-72` | default |
| `.service.vpn.AdBlockVpnService` | `AndroidManifest.xml:121-132` | default |
| `.service.SessionExpiryReceiver` | `AndroidManifest.xml:97-100` | default |
| `.service.vpn.VpnHoldExpiryReceiver` | `AndroidManifest.xml:106-109` | default |
| `.service.BootReceiver` | `AndroidManifest.xml:139-149` | default |
| `CleanupWorker` (WorkManager) | `GrayzoneApplication.kt:286-294` | default |

`android:process` appears exactly once in the whole source tree (`AndroidManifest.xml:82`), so
`:a11y` is the only non-default process.

### The `:a11y` code surface is very small

`GrayzoneAccessibilityService.kt` is 131 lines and reads/writes **no** mutable shared state. Its
complete set of touched declarations, direct and transitive:

| Touched from `:a11y` | Site | Nature |
|---|---|---|
| `GrayzoneAccessibilityService.TAG` | `GrayzoneAccessibilityService.kt:31` | `private const val` |
| `GrayzoneAccessibilityService.ACTION_APP_CHANGED` | declared `:32`, used `:86` | `const val` |
| `GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME` | declared `:33`, used `:87`, `:97` | `const val` |
| `GrayzoneLogger.{d,w,e}` + `LogComponent.ACCESSIBILITY` | `:58-62`, `:78-82`, `:101-104`, `:107-111`, `:125-128` | stateless object (`GrayzoneLogger.kt:34-82`) |
| `sendBroadcast(Intent)` | `:86-89` | real IPC |
| `startForegroundService(Intent(.., OverlayService::class.java))` | `:93-99` | real IPC |
| `serviceInfo = AccessibilityServiceInfo()` | `:42-55` | framework-owned, per-process by design |

It does **not** read SharedPreferences, does **not** open Room, does **not** touch
`ProtectionHealthRepository`, `BlocklistManager`, `DnsTrafficBus`, `DateUtils`, `ScheduleManager`,
`RuntimeSessionReset`, or any policy object. Both of its cross-boundary calls are real IPC channels
carrying the package name in Intent extras.

Consequence: the widely-feared `:a11y` divergence **does not exist in this build**. The inventory
below records that explicitly so the audit does not "fix" a boundary that is already clean, and so
the latent traps (§ *Latent traps*) are recorded for anyone tempted to add state later.

## Inventory

| Declaration | File:line | Kind | Written in | Read in | Verdict |
|---|---|---|---|---|---|
| `GrayzoneAccessibilityService.TAG` | `service/GrayzoneAccessibilityService.kt:31` | `companion const val` | never (compile-time) | `:a11y` | `a11y-only` |
| `GrayzoneAccessibilityService.ACTION_APP_CHANGED` | `service/GrayzoneAccessibilityService.kt:32` | `companion const val` | never (compile-time) | `:a11y` (`:86`), default (`OverlayService.kt:191`) | `correctly-shared` (const inlined into each process's dex at compile time; the value crosses as a broadcast action string, not as runtime state) |
| `GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME` | `service/GrayzoneAccessibilityService.kt:33` | `companion const val` | never (compile-time) | `:a11y` (`:87`,`:97`), default (`OverlayService.kt:182`,`:674`) | `correctly-shared` (same; used as an Intent-extra key) |
| `GrayzoneLogger` (whole object) | `GrayzoneLogger.kt:34-82` | `object` | n/a — no mutable state | `:a11y` and default | `correctly-shared` (two class copies exist, but there is no mutable field, so nothing can diverge) |
| `GrayzoneApplication.appScope` | `GrayzoneApplication.kt:36` | instance `val CoroutineScope` | default (`:96`,`:128`,`:139`,`:148`,`:162`) | default | `main-only` (allocated in both processes — see § *Initialization duplication*) |
| `GrayzoneApplication.backupListener` | `GrayzoneApplication.kt:40` | instance `var` | default (`:107`) | default (held only to defeat GC) | `main-only` |
| `GrayzoneApplication.backupDirty` | `GrayzoneApplication.kt:44` | `AtomicBoolean` | default (`:104`,`:138`) | default (`:138`) | `main-only` |
| `GrayzoneApplication.startedActivityCount` | `GrayzoneApplication.kt:47` | instance `var Int` | default (`:111`,`:115`) | default (`:116`) | `main-only` |
| `OverlayService.TAG` / `CHANNEL_ID` / `NOTIF_ID` | `service/OverlayService.kt:64-66` | `companion const val` | never | default | `main-only` |
| `OverlayService.ACTION_STOP` … `ACTION_SESSION_EXPIRED` | `service/OverlayService.kt:67-70` | `companion const val` | never | default (`:660-672`, `SessionExpiryReceiver.kt:23`) | `main-only` |
| `OverlayService.ACTION_APP_CHANGED_WATCHDOG` | `service/OverlayService.kt:71` | `companion const val` | never | default only (`:673`) — `:a11y` uses the raw literal at `GrayzoneAccessibilityService.kt:96`, not this constant | `main-only` |
| `OverlayService.DEFAULT_WAIT` | `service/OverlayService.kt:72` | `companion const val` | never | default | `main-only` |
| `OverlayService` chrome colour constants | `service/OverlayService.kt:75-96` | `companion const val` / `val` | never | default (`:1150-1161`) | `main-only` |
| `OverlayService.sharedGson` | `service/OverlayService.kt:98` | `companion val Gson` | init only | default (`:853`) | `main-only` |
| `OverlayService.windowManager` | `service/OverlayService.kt:101` | instance `var` | default (`:145`) | default (`:990`,`:1039`) | `main-only` |
| `OverlayService.overlayView` | `service/OverlayService.kt:102` | instance `var` | default (`:991`,`:1044`) | default (`:1036`) | `main-only` |
| `OverlayService.countdownJobUI` | `service/OverlayService.kt:103` | instance `var Job?` | default (`:1004`,`:1035`) | default (`:1003`,`:1035`) | `main-only` |
| `OverlayService.secondsRemaining` | `service/OverlayService.kt:104` | instance `var Int` | default (`:839`,`:1007`) | default (`:908`,`:944`,`:1005`) | `main-only` |
| `OverlayService.coroutineExceptionHandler` | `service/OverlayService.kt:107` | instance `val` | init only | default | `main-only` |
| `OverlayService.serviceScope` | `service/OverlayService.kt:115` | instance `val CoroutineScope` | init / cancelled `:765` | default | `main-only` |
| `OverlayService.lastForegroundPackage` | `service/OverlayService.kt:121` | `@Volatile` instance `var` | default (`:351`,`:422`) | default (`:263`,`:330`,`:332`,`:416`,`:567`,`:640`,`:1027`) | `main-only` — the value *originates* in `:a11y` but arrives via broadcast/Intent extra (`GrayzoneAccessibilityService.kt:86-99` → `OverlayService.kt:181-184`,`:674`) |
| `OverlayService.sessionStartTime` | `service/OverlayService.kt:122` | `@Volatile` instance `var` | default (`:281`,`:379`,`:407`,`:1099`) | default (`:399`) | `main-only` |
| `OverlayService.cachedHomeLauncherPkg` | `service/OverlayService.kt:123` | instance `var` (cache) | default (`:547`) | default (`:546`,`:553`) | `main-only` |
| `OverlayService.homeLauncherCacheTime` | `service/OverlayService.kt:124` | instance `var` (cache TTL) | default (`:551`) | default (`:546`) | `main-only` |
| `OverlayService.scheduleManager` | `service/OverlayService.kt:125` | `lateinit var ScheduleManager` | default (`:146`) | default (`:279`,`:480`,`:481`,`:608`,`:642`) | `main-only` |
| `OverlayService.transitionLock` | `service/OverlayService.kt:127` | instance `val` monitor | n/a | default (`:305`,`:314`) | `main-only` |
| `OverlayService.queuedForegroundPackage` | `service/OverlayService.kt:128` | instance `var` | default (`:306`,`:320`) | default (`:315`) | `main-only` |
| `OverlayService.transitionJob` | `service/OverlayService.kt:129` | instance `var Job?` | default (`:308`,`:317`) | default (`:307`) | `main-only` |
| `OverlayService.appChangeReceiver` | `service/OverlayService.kt:132` | instance `var` | default (`:194`,`:762`) | default (`:761`) | `main-only` |
| `OverlayService.screenReceiver` | `service/OverlayService.kt:136` | instance `var` | default (`:219`,`:764`) | default (`:763`) | `main-only` |
| `OverlayService.policyEngine` | `service/OverlayService.kt:138` | instance `val` (stateless engine) | init only | default (`:357`,`:400`) | `main-only` |
| `ProtectionHealthRepository._status` | `data/ProtectionHealthRepository.kt:9` | `object MutableStateFlow` | default only — `OverlayService.kt:142`,`:760`; `AdBlockVpnService.kt:226`,`:248`,`:311`,`:319`,`:336` | default only — `VpnScreen.kt:51`, `MainActivity.kt:137` | `main-only` |
| `ProtectionHealthRepository.status` | `data/ProtectionHealthRepository.kt:10` | `object StateFlow` | derived | default only | `main-only` |
| `ProtectionStatus.accessibilityActive` | `data/ProtectionStatus.kt:6` | data-class field behind the object flow | **never written in production** — `updateAccessibilityStatus` (`ProtectionHealthRepository.kt:34-36`) has no production caller; only `ProtectionStatusTest.kt:14`,`:46` | read via `isFullyProtected`/`isDegraded` (`ProtectionStatus.kt:11`,`:14`), themselves never read in `app/src/main` | `main-only` (dead writer — see § *Latent traps*, item 1) |
| `DnsTrafficBus._events` | `service/vpn/DnsTrafficBus.kt:35` | `object MutableStateFlow<List<DnsEvent>>` | default — `AdBlockVpnService.kt:445`,`:451`,`:457` (`emit`), `:293` (`clear`) | default — `VpnScreen.kt:293`,`:414` | `main-only` |
| `DnsTrafficBus.events` | `service/vpn/DnsTrafficBus.kt:36` | `object StateFlow` | derived | default | `main-only` |
| `DnsTrafficBus.idCounter` | `service/vpn/DnsTrafficBus.kt:24` | `AtomicLong` | default (`:30`) | default | `main-only` |
| `AdBlockVpnService.isRunning` | `service/vpn/AdBlockVpnService.kt:33-35` | `companion @Volatile var` | default (`:214`,`:284`) | default (`:124`,`:183`,`:357`,`:361`,`:416`) — no reader outside the service | `main-only` |
| `AdBlockVpnService.DNS_SERVERS` / `DNS_TIMEOUT_MS` / `DEFAULT_BYPASS_APPS` | `service/vpn/AdBlockVpnService.kt:41`,`:47`,`:58` | `companion val`/`const` (immutable) | init only | default | `main-only` |
| `AdBlockVpnService.vpnInterface` | `service/vpn/AdBlockVpnService.kt:86` | `@Volatile` instance `var` | default (`:207`,`:289`) | default (`:124`,`:146`,`:166`,`:287`,`:343`) | `main-only` |
| `AdBlockVpnService.vpnThread` | `service/vpn/AdBlockVpnService.kt:87` | `@Volatile` instance `var` | default (`:223`,`:290`) | default (`:224`,`:285`) | `main-only` |
| `AdBlockVpnService.resolverPool` | `service/vpn/AdBlockVpnService.kt:89` | `@Volatile` instance `var` | default (`:112`,`:212`,`:298`) | default (`:211`,`:297`,`:548`) | `main-only` |
| `AdBlockVpnService.serviceScope` | `service/vpn/AdBlockVpnService.kt:100` | instance `val CoroutineScope` | init / cancelled `:339` | default | `main-only` |
| `AdBlockVpnService.pendingQueries` | `service/vpn/AdBlockVpnService.kt:105` | `ConcurrentHashMap` (dedup cache) | default (`:291`,`:503`,`:526`) | default (`:503`) | `main-only` |
| `BlocklistManager.adFilter` | `service/vpn/BlocklistManager.kt:15` | `object var` (≈6 MB Bloom filter) | default (`:147`,`:152`) — only writer is `load()`, only called from `AdBlockVpnService.kt:110` | default (`:219`) | `main-only` |
| `BlocklistManager.adultFilter` | `service/vpn/BlocklistManager.kt:16` | `object var` (≈4 MB Bloom filter) | default (`:148`,`:153`) | default (`:220`) | `main-only` |
| `BlocklistManager.abusedTlds` | `service/vpn/BlocklistManager.kt:20` | `object var` | default (`:149`,`:154`) | default (`:218`) | `main-only` |
| `BlocklistManager.isLoaded` | `service/vpn/BlocklistManager.kt:138-139` | `object @Volatile var` | default (`:150`,`:155`) | default (`:216`) | `main-only` |
| `BlocklistManager.loadStarted` | `service/vpn/BlocklistManager.kt:80` | `AtomicBoolean` (load guard) | default (`:144`,`:156`) | default (`:144`) | `main-only` |
| `BlocklistManager.normalizedDomainCache` | `service/vpn/BlocklistManager.kt:85-89` | `object` LRU `LinkedHashMap` (cap 4000) | default (`:174`,`:180`,`:187`,`:190`,`:194`) | default (`:167`) | `main-only` |
| `BlocklistManager.cacheLock` | `service/vpn/BlocklistManager.kt:84` | `object` monitor | n/a | default | `main-only` |
| `BlocklistManager.dohBypassDomains` | `service/vpn/BlocklistManager.kt:34` | immutable `Set` | init only | default (`:202`) | `main-only` |
| `BlocklistManager.highConfidenceAdDomains` / `highConfidenceAdultDomains` | `service/vpn/BlocklistManager.kt:94`,`:122` | immutable `Set` | init only | default (`:214-215`) | `main-only` |
| `BlocklistManager.ipv4LiteralRegex` | `service/vpn/BlocklistManager.kt:81` | immutable `Regex` | init only | default (`:179`) | `main-only` |
| `UsageDatabase.INSTANCE` | `data/UsageDatabase.kt:16-17` | `companion @Volatile var` (Room handle) | default (`:44`) | default (`:35`) — callers `OverlayService.kt:570`,`:1117`; `StreakEvaluator.kt:27`; `CleanupWorker.kt:42` | `main-only` (see § *Latent traps*, item 2) |
| `UsageDatabase.MIGRATION_1_2` | `data/UsageDatabase.kt:24` | `internal val` (immutable) | init only | default (`:42`) | `main-only` |
| `AppMetadataCache.cachedApps` | `AppUtils.kt:22` | `private object var` (app list + icon bitmaps) | default (`:39`,`:44`) | default (`:28`,`:36`) | `main-only` |
| `AppMetadataCache.cacheTimestamp` | `AppUtils.kt:23` | `private object var` | default (`:40`,`:45`) | default (`:28`) | `main-only` |
| `DateUtils.cachedDateKey` | `DateUtils.kt:27-28` | `object @Volatile var` | default (`:57`) | default (`:50`) | `main-only` — `:a11y` never calls `DateUtils` |
| `DateUtils.cacheExpiry` | `DateUtils.kt:31-32` | `object @Volatile var` | default (`:58`) | default (`:51`) | `main-only` |
| `DateUtils.dateKeyFormatter` / `timestampFormatter` | `DateUtils.kt:18`,`:22` | `object ThreadLocal` | per-thread lazily | default | `main-only` |
| `GzThemeState.palette` | `ui/theme/Color.kt:131-134` | `object` `mutableStateOf` (Compose snapshot) | default (`Theme.kt`, via `internal set`) | default (Compose accessors `Color.kt:138-208`) | `main-only` — `OverlayService` deliberately does **not** read this; it uses its own constants (`OverlayService.kt:75-96`, resolved at `:1150-1161`) |
| `LightGzPalette` / `DarkGzPalette` | `ui/theme/Color.kt:54`,`:91` | top-level immutable `val` | init only | default | `main-only` |
| `Typography` | `ui/theme/Type.kt:9` | top-level immutable `val` | init only | default | `main-only` |
| `DnsResolverPool.idleSockets` | `service/vpn/DnsResolverPool.kt:23` | instance `MutableList` (socket pool) | default (`:86`,`:114`,`:122`,`:125`) | default (`:85`) | `main-only` |
| `DnsResolverPool.semaphore` / `lock` | `service/vpn/DnsResolverPool.kt:22`,`:26` | instance `val` | n/a | default | `main-only` |
| `DnsResolverPool.isShutdown` | `service/vpn/DnsResolverPool.kt:27` | `@Volatile` instance `var` | default (`:121`) | default (`:40`,`:84`,`:111`) | `main-only` |
| `ScheduleManager.prefs` / `gson` | `data/ScheduleManager.kt:29-31` | instance `val` (cached `SharedPreferences` handle) | init only | default (constructed at `OverlayService.kt:146`, plus UI sheets) | `main-only` |
| `StreakManager.prefs` / `gson` / `dateFormat` | `data/StreakManager.kt:29-32` | instance `val` | init only | default (`OverlayService.kt:1111`, `StreakEvaluator.kt:28`, UI) | `main-only` |
| `ScheduleManager.Companion` constants (`BREAK_DURATION_MILLIS`, `DAY_NAMES`, `WEEKDAYS`, `WEEKEND`, `ALL_DAYS`) | `data/ScheduleManager.kt:143`,`:187`,`:196-198` | `companion` immutable | init only | default | `main-only` |
| `StreakManager.ALL_ACHIEVEMENTS` | `data/StreakManager.kt:217` | `companion` immutable `List` | init only | default | `main-only` |
| `CleanupWorker.RETENTION_DAYS` | `data/CleanupWorker.kt:27` | `companion const val` | never | default | `main-only` |
| `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` | `policy/SessionPolicyEngine.kt:10` | `companion const val` | never | default | `main-only` |
| `SessionExpiryReceiver.ACTION_SESSION_EXPIRED` / `EXTRA_PACKAGE_NAME` | `service/SessionExpiryReceiver.kt:37-38` | `companion const val` | never | default (`SessionExpiryScheduler.kt:25-26`) | `main-only` |
| `AdBlockVpnService.ACTION_*` / `NOTIF_ID` / `CHANNEL_ID` | `service/vpn/AdBlockVpnService.kt:24-31` | `companion const val` | never | default (`MainActivity.kt:165`, `BootReceiver.kt:67`, `VpnHoldExpiryScheduler.kt:87`) | `main-only` |
| `VpnHoldExpiryReceiver.ACTION_HOLD_EXPIRED` | `service/vpn/VpnHoldExpiryScheduler.kt:97` | `companion const val` | never | default | `main-only` |
| `BootReceiver.TAG` / `BOOT_ACTIONS` | `service/BootReceiver.kt:81`,`:83` | `companion` immutable | init only | default | `main-only` |
| `AbusedTldList.Companion` (`EMPTY`, `readFrom`) | `service/vpn/AbusedTldList.kt:31` | `companion` immutable | init only | default | `main-only` |
| `GrayzoneBloomFilter.Companion` | `service/vpn/GrayzoneBloomFilter.kt:44` | `companion` (factory) | n/a | default | `main-only` |
| `OverlayPresentation.Companion` | `service/overlay/OverlayPresentation.kt:56` | `companion` (resolver) | n/a | default (`OverlayService.kt:828`) | `main-only` |
| `FrictionPresentation`, `LockPresentation`, `ScheduleLockPresentation`, `BudgetLockPresentation` | `service/overlay/FrictionPresentation.kt:4`, `LockPresentation.kt:6`, `ScheduleLockPresentation.kt:4`, `BudgetLockPresentation.kt:4` | stateless `object` | n/a | default | `main-only` |
| `PrefsKeys` | `PrefsKeys.kt:4` | `object`, all `const val` | never | default only (`:a11y` never reads prefs) | `main-only` |
| `OverlayMode` | `PrefsKeys.kt:90` | `object`, `const val` | never | default | `main-only` |
| `SetupRequirements` | `SetupRequirements.kt:13` | stateless `object` | n/a | default | `main-only` |
| `AccessibilityServiceIds` | `AccessibilityServiceIds.kt:11` | stateless `object` | n/a | default (`AppUtils.kt:114`) | `main-only` |
| `AccessibilityPromptPolicy` | `AccessibilityPromptState.kt:11` | stateless `object` (pure) | n/a | default (`MainActivity.kt:192`) | `main-only` |
| `RuntimeSessionReset` | `data/RuntimeSessionReset.kt:15` | stateless `object` (prefs-only) | n/a | default (`GrayzoneApplication.kt:273`, `OverlayService.kt:259`,`:338`) | `main-only` |
| `DailyBudget` | `data/DailyBudget.kt:8` | stateless `object` (pure math) | n/a | default | `main-only` |
| `PerAppLimits` | `data/PerAppLimits.kt:7` | stateless `object` (pure math) | n/a | default (`OverlayService.kt:449`,`:454`) | `main-only` |
| `ConfigValidator` | `data/ConfigValidator.kt:9` | stateless `object` | n/a | default (`GrayzoneApplication.kt:181-204`) | `main-only` |
| `DefaultMonitoredApps` | `data/DefaultMonitoredApps.kt:7` | `object`, immutable list | init only | default (`GrayzoneApplication.kt:237`,`:253`) | `main-only` |
| `StreakEvaluator` | `data/StreakEvaluator.kt:15` | stateless `object` | n/a | default (`GrayzoneApplication.kt:164`, `OverlayService.kt:289`) | `main-only` |
| `SessionExpiryDecision` | `policy/SessionExpiryDecision.kt:21` | stateless `object` (pure) | n/a | default (`OverlayService.kt:636`) | `main-only` |
| `SessionExpiryScheduler` | `service/SessionExpiryScheduler.kt:18` | stateless `object` (AlarmManager only) | n/a | default | `main-only` |
| `VpnHoldExpiryScheduler` | `service/vpn/VpnHoldExpiryScheduler.kt:15` | stateless `object` (AlarmManager only) | n/a | default | `main-only` |
| `VpnHoldLock` | `service/vpn/VpnHoldLock.kt:12` | `object`, `HOLD_MS` immutable + pure fns | init only | default | `main-only` |
| `VpnHoldLockStore` | `service/vpn/VpnHoldLockStore.kt:9` | stateless `object` (prefs bridge) | n/a | default | `main-only` |
| `StateBackupManager` | `data/StateBackupManager.kt:59` | stateless `object`, all `const` | n/a | default only — `GrayzoneApplication.kt:54` explicitly bars `:a11y` | `main-only` |
| `DomainZoneMatcher` | `service/vpn/DomainZoneMatcher.kt:16` | stateless `object` (pure) | n/a | default | `main-only` |
| `DnsQueryClassifier` | `service/vpn/DnsQueryClassifier.kt:16` | stateless `object` (pure, injectable) | n/a | default (`AdBlockVpnService.kt:442`) | `main-only` |
| `DnsPacketHelper` | `service/vpn/DnsPacketHelper.kt:8` | stateless `object` | n/a | default | `main-only` |
| `Prompts.DEFAULT` | `Extensions.kt:93-107` | `object`, immutable `List` | init only | default (`OverlayService.kt:862`, `CustomPromptsSheet.kt`) | `main-only` |
| `OemBatteryHelper` | `OemBatteryHelper.kt:17` | stateless `object` | n/a | default | `main-only` |
| `ThemeMode` | `ui/theme/Color.kt:11` | `object`, `const val` | never | default | `main-only` |
| `MAX_PAUSED_SESSION_MILLIS` | `Extensions.kt:27` | top-level `private const val` | never | default (`Extensions.kt:35`) | `main-only` |
| `APP_ICON_SIZE_PX` | `AppUtils.kt:15` | top-level `private const val` | never | default | `main-only` |

## FALSELY-SHARED state (candidate findings)

**None. There is no `FALSELY-SHARED` declaration in this build.**

This is a supported conclusion, not an absence of investigation. For a declaration to be
`FALSELY-SHARED`, `:a11y` must read or write it. `GrayzoneAccessibilityService.kt` (the only class
in `:a11y`) touches exactly seven declarations, enumerated in § *The `:a11y` code surface is very
small*: three of its own `const val`s, the stateless `GrayzoneLogger`, and three framework calls.
Not one of them is mutable shared state. Every mutable declaration in the inventory above is
written and read entirely within the default process.

The two places where information genuinely crosses the boundary both use real IPC:

1. `GrayzoneAccessibilityService.kt:86-89` — `sendBroadcast` with the package name in an Intent
   extra, received at `OverlayService.kt:178-202`.
2. `GrayzoneAccessibilityService.kt:93-99` — `startForegroundService` with the package name in an
   Intent extra, received at `OverlayService.kt:673-675`.

Nothing is passed by shared memory, so there is no read that "cannot observe a write."

### Latent traps (not findings today — recorded so they are not introduced)

1. **`ProtectionStatus.accessibilityActive` has no writer at all.**
   `ProtectionHealthRepository.updateAccessibilityStatus` (`data/ProtectionHealthRepository.kt:34-36`)
   is called only from `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt:14`,`:46`.
   No production call site exists. Therefore `accessibilityActive` is permanently `false`, and
   `ProtectionStatus.isFullyProtected` (`data/ProtectionStatus.kt:11`) can never return `true`.
   Today this is inert because `isFullyProtected` and `isDegraded` (`:11`,`:14`) have no reader in
   `app/src/main` either — `VpnScreen.kt:52-53` and `MainActivity.kt:137` read only `vpnActive` and
   `vpnReconnectPending`. **Severity: Low as shipped (dead code path), but it is a booby trap.**
   The natural "fix" — calling `updateAccessibilityStatus(true)` from
   `GrayzoneAccessibilityService.onServiceConnected` — would execute in `:a11y` and write the
   `:a11y` copy of the `MutableStateFlow` at `data/ProtectionHealthRepository.kt:9`. The default
   process's UI would still read `false` forever, producing exactly the class of defect this task
   is looking for: a health indicator permanently claiming protection is degraded while it is
   actually working. The correct implementation is a broadcast or a
   `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` re-read in the default process — the latter
   already exists as `isAccessibilityServiceEnabled` (`AppUtils.kt:108-126`) and is what
   `MainActivity.kt:190` uses.

2. **`UsageDatabase` is not configured for multi-process access.**
   `data/UsageDatabase.kt:34-47` builds the Room database with WAL (`:41`) but without
   `enableMultiInstanceInvalidation()`. All four call sites are in the default process
   (`OverlayService.kt:570`,`:1117`; `StreakEvaluator.kt:27`; `CleanupWorker.kt:42`), so the single
   `INSTANCE` (`:16-17`) is correct as shipped. If `:a11y` is ever given a Room call, each process
   gets its own `INSTANCE` and its own invalidation tracker, so `Flow`/`LiveData` queries in one
   process would not refresh on the other's writes. **Severity if introduced: High.**

3. **`SharedPreferences` is safe here only because `:a11y` is excluded.**
   `MODE_PRIVATE` prefs are not multi-process coherent, and the codebase relies on that being a
   non-issue: `GrayzoneApplication.kt:52-54` bars `:a11y` from prefs entirely, and the accessibility
   service never calls `getSharedPreferences`. Any future prefs read from `:a11y` would see a
   snapshot frozen at that process's first load. **Severity if introduced: Critical** — `:a11y`
   would enforce or skip locks based on limits the user had already changed.

## StateFlow / SharedFlow crossing the boundary

**No `StateFlow` or `SharedFlow` crosses the process boundary. Both flows in the app live wholly
inside the default process, and both work correctly.**

There are exactly two flows in `app/src/main`:

**`DnsTrafficBus` (`service/vpn/DnsTrafficBus.kt:18-71`) — publisher and collector are the SAME
process, so it works.** Its doc comment ("In-process bus", `:8`) is accurate.

- Publisher: `AdBlockVpnService.handleDnsQuery` → `DnsTrafficBus.emit` at
  `service/vpn/AdBlockVpnService.kt:445`, `:451`, `:457`, plus `DnsTrafficBus.clear()` at `:293`.
  `AdBlockVpnService` is declared at `AndroidManifest.xml:121-132` with **no** `android:process`, so
  it runs in the **default** process.
- Collectors: `VpnScreen.kt:293` and `VpnScreen.kt:414` (`DnsTrafficBus.events.collectAsState()`),
  hosted by `MainActivity` (`AndroidManifest.xml:55-63`, **default** process).

Publisher and collectors are in the same process, so collectors do receive events. The `:a11y`
process never references `DnsTrafficBus` — it holds a second, permanently empty copy of the object
only if the class is ever loaded there, and nothing in `GrayzoneAccessibilityService.kt` loads it.
**This is correct as written and must not be "fixed."**

**`ProtectionHealthRepository.status` (`data/ProtectionHealthRepository.kt:9-10`) — also entirely
default-process.** Writers: `OverlayService.kt:142`,`:760` and `AdBlockVpnService.kt:226`,`:248`,
`:311`,`:319`,`:336`. Readers: `VpnScreen.kt:51` and `MainActivity.kt:137`. All default process, so
the VPN banner and reconnect indicator function. The one field with no writer is
`accessibilityActive` — see § *Latent traps*, item 1.

`Compose` `mutableStateOf` holders (`GzThemeState.palette`, `ui/theme/Color.kt:131-134`; the
`remember`/`mutableStateOf` in `MainActivity.kt:59`,`:209`,`:231`) are all confined to the UI in the
default process.

## Correct IPC channels in use

The app's cross-process and cross-restart design is sound. These mechanisms should be credited and
left alone:

| Channel | Where | What it carries |
|---|---|---|
| **Explicit, package-targeted broadcast** `:a11y` → default | send `service/GrayzoneAccessibilityService.kt:86-89` (`setPackage(...)` at `:88`); receive `service/OverlayService.kt:178-202` with `RECEIVER_NOT_EXPORTED` at `:192` | foreground package name, in an Intent extra |
| **`startForegroundService` + Intent extra** `:a11y` → default (watchdog that also revives a swiped-away `OverlayService`) | `service/GrayzoneAccessibilityService.kt:93-99`; handled at `service/OverlayService.kt:673-675` | foreground package name |
| **Process detection guard** | `GrayzoneApplication.kt:52-54` | keeps prefs / backup / WorkManager / restore out of `:a11y` entirely — the single reason this app has no cross-process prefs corruption |
| **`SharedPreferences` as the durable source of truth, re-read per operation** | `OverlayService.kt:150`,`:258`,`:333`,`:419`,`:586`,`:631`,`:826`,`:1048`,`:1103`; `buildSessionState` re-reads all limits every transition at `:438-483` | monitored apps, per-app limits, `activeUntil`/`lockedUntil`/`remainingMillis`, budgets. Because `MainActivity` and `OverlayService` share one process, they share one `SharedPreferences` instance, so a limit edited in the UI is observed by the enforcement path on the very next app switch — no cache to invalidate |
| **`commit()` where ordering matters** | `OverlayService.kt:531`,`:600`; `StateBackupManager.kt:250` | makes restored/updated state durably visible before the next read in the same launch |
| **AlarmManager + manifest-registered receiver** (survives Doze, screen-off, and process death) | arm/cancel `service/SessionExpiryScheduler.kt:41-82`; deliver `service/SessionExpiryReceiver.kt:18-34`; re-arm after restart `OverlayService.kt:154-162` | session expiry per package — explicitly replaces an older in-memory `delay()` (documented at `SessionExpiryScheduler.kt:11-17`) |
| **AlarmManager for the 24h VPN hold** | `service/vpn/VpnHoldExpiryScheduler.kt:32-76`; receiver `:82-99`; state persisted in prefs via `service/vpn/VpnHoldLockStore.kt:14-43` | hold expiry — the hold survives reboot because it is stored in prefs, not memory, and `VpnHoldLock.remainingMs` (`VpnHoldLock.kt:25-34`) prefers `elapsedRealtime` with a wall-clock reboot fallback |
| **`BOOT_COMPLETED` receiver reconstructing state from prefs** | `service/BootReceiver.kt:28-78` | restarts `OverlayService` unconditionally (`:40`) and the VPN only if `VPN_ENABLED` persists (`:48`) |
| **Room as the durable usage store** | write `OverlayService.kt:574-584`,`:1117-1127`; read `StreakEvaluator.kt:32`, `CleanupWorker.kt:47-48` | usage events; streaks are judged from the DB rather than a prefs snapshot (rationale at `StreakManager.kt:60-73`) |
| **Date-keyed prefs as a midnight-rollover safety net** | `data/RuntimeSessionReset.kt:25-45`, called from `GrayzoneApplication.kt:273`, `OverlayService.kt:259` and again per transition at `:338` | makes the day-boundary reset idempotent and independent of any live timer |
| **Encrypted out-of-sandbox snapshot** | `data/StateBackupManager.kt:104-179` | anti-tamper restore after clear-data; deliberately single-writer (see the guard comment at `GrayzoneApplication.kt:52-53`) |

## Initialization duplication

`GrayzoneApplication.onCreate()` **does** run once per process, so it is entered twice (default and
`:a11y`).

**The code detects the current process.** `GrayzoneApplication.kt:54`:

```54:54:app/src/main/java/com/grayzone/app/GrayzoneApplication.kt
        if (Application.getProcessName() != packageName) return
```

This is the only process-detection site in the entire source tree — a repo-wide search for
`getProcessName`, `RunningAppProcessInfo`, and `myPid` returns this one line. `<application>` has
no `android:process` (`AndroidManifest.xml:43-53`), so the default process name equals the package
name and the comparison is correct. `Application.getProcessName()` is a static API-28 method and
`minSdk = 29` (`app/build.gradle.kts:28`), so no version guard is needed.

### What runs in `:a11y` anyway (before the guard, or independent of it)

| Item | File:line | Runs twice? | Harmful? |
|---|---|---|---|
| `super.onCreate()` | `GrayzoneApplication.kt:50` | Yes — precedes the guard at `:54` | No. Base `Application` bookkeeping only. |
| `appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` | `GrayzoneApplication.kt:36` | Yes — constructor field initializers run before the `onCreate` body | Effectively no. The `:a11y` copy is never used, so no coroutine is launched and the `Dispatchers.IO` worker pool stays unstarted; cost is one `SupervisorJob` + one scope object. |
| `backupListener` (null init) | `GrayzoneApplication.kt:40` | Yes | No. Stays `null` in `:a11y`; `registerStateBackup()` is behind the guard. |
| `backupDirty = AtomicBoolean(false)` | `GrayzoneApplication.kt:44` | Yes | No. Never flipped in `:a11y` — the prefs listener that sets it (`:103-107`) is behind the guard. |
| `startedActivityCount = 0` | `GrayzoneApplication.kt:47` | Yes | No. `:a11y` hosts no Activity. |
| `GrayzoneApplication` + `GrayzoneLogger` class load/verify | — | Yes | No. Kilobytes. |

### What the guard correctly prevents from running twice

| Item | File:line | Why double execution would matter |
|---|---|---|
| `StateBackupManager.restoreIfWiped(this)` | `GrayzoneApplication.kt:64` | Two processes decrypting and `commit()`-ing the same prefs snapshot concurrently; also two writers to `/sdcard/.grayzone/backup.dat`, the exact corruption the comment at `:52-53` names. |
| `validateConfiguration()` | `GrayzoneApplication.kt:70` (body `:175-226`) | Duplicate prefs repair writes from two processes with incoherent prefs caches. |
| `resetRuntimeStateIfNeeded()` | `GrayzoneApplication.kt:71` (body `:271-279`) | Two independent midnight resets racing on the same `DAILY_RESET_DATE + "runtime"` key. |
| `runDeferredStartupWork()` → `seedDefaultMonitoredAppsIfNeeded()` | `GrayzoneApplication.kt:72`, `:147-155`, `:232-266` | Duplicate `PackageManager` enumeration plus a race on the `DEFAULT_APPS_SEEDED` sentinel. |
| `catchUpStreaks()` → `StreakEvaluator.run` | `GrayzoneApplication.kt:74`... `:161-169` | Would open a **second Room instance in `:a11y`** (`StreakEvaluator.kt:27` → `UsageDatabase.getInstance`) and double-evaluate streak days. This is the single biggest thing the guard buys. |
| `schedulePeriodicCleanup()` → `WorkManager.enqueueUniquePeriodicWork` | `GrayzoneApplication.kt:74`, `:284-303` | **No duplicate enqueue occurs.** Doubly protected: the guard prevents `:a11y` reaching `:290`, and `ExistingPeriodicWorkPolicy.KEEP` (`:292`) is idempotent regardless. WorkManager's own auto-initialization runs via the merged `androidx.startup.InitializationProvider`, which carries no `android:process` and therefore instantiates in the default process only. |
| `registerStateBackup()` | `GrayzoneApplication.kt:75`, `:94-134` | Two prefs change-listeners and two `FLUSH_INTERVAL_MS` loops (`:128-133`) writing the same encrypted file. |

### Specifically checked and NOT duplicated

- **Room instances.** `UsageDatabase.getInstance` (`data/UsageDatabase.kt:34`) is called only from
  `OverlayService.kt:570`,`:1117`, `StreakEvaluator.kt:27`, and `CleanupWorker.kt:42` — all default
  process. `INSTANCE` (`:16-17`) is created once. `:a11y` never opens the database, so there is no
  second connection to `grayzone_usage.db` and no invalidation-tracker split.
- **Blocklist loading and its memory cost.** `BlocklistManager.load` has exactly one call site,
  `service/vpn/AdBlockVpnService.kt:110`, in the default process. The ~6 MB ad and ~4 MB adult Bloom
  filters (`BlocklistManager.kt:12-16`, loaded at `:147-149`) are therefore resident **once**, not
  twice. `loadStarted` (`:80`,`:144`) additionally makes the load idempotent within a process. The
  hypothesised doubling of blocklist RAM in `:a11y` **does not occur.**
- **Notification channels.** `createNotificationChannel()` exists in `OverlayService.kt:1181-1187`
  (called from `:143`) and `AdBlockVpnService.kt:578-588` (called from `:111`). Both services are
  default process, the two channel IDs differ (`grayzone_overlay` at `OverlayService.kt:65`,
  `adblock_vpn_channel` at `AdBlockVpnService.kt:31`), and `createNotificationChannel` is idempotent
  by ID. `:a11y` creates no channel and posts no notification.

### One open question (`needs verification`, memory-only, not correctness)

`GrayzoneAccessibilityService.kt:95` evaluates `OverlayService::class.java` to build the Intent's
`ComponentName`. That loads and verifies the `OverlayService` class in `:a11y`. Whether it also
triggers `OverlayService`'s companion `<clinit>` — which would allocate a `Gson` instance
(`OverlayService.kt:98`) in `:a11y` — depends on whether obtaining the `Class` object counts as an
active use under ART; retrieving `Class` alone normally does not, and no static member of
`OverlayService` is accessed from `:a11y` (line `:96` uses the raw literal
`"com.grayzone.app.ACTION_APP_CHANGED_WATCHDOG"` rather than the constant at `OverlayService.kt:71`).
This affects a few kilobytes, never behaviour. **What would settle it:** compare per-process
footprints with `adb shell dumpsys meminfo com.grayzone.app` vs `com.grayzone.app:a11y`, or run with
`-verbose:class` and check whether `OverlayService`'s static initializer executes in `:a11y`. It can
also be made moot by replacing `OverlayService::class.java` with an explicit
`ComponentName(packageName, "com.grayzone.app.service.OverlayService")`.

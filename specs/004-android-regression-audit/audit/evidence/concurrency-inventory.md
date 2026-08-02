# Evidence: Concurrency Inventory (T009)

**Scope**: every `CoroutineScope`, launched coroutine, `Thread`, `Executor`, `Handler`, timer and
alarm under `app/src/main/java/com/grayzone/app/`, read-only.

**Method**: exhaustive search for `CoroutineScope(`, `GlobalScope`, `MainScope`, `lifecycleScope`,
`rememberCoroutineScope`, `launch`, `async`, `withContext`, `runBlocking`, `Dispatchers.`, `Thread(`,
`Executor`, `Handler(`, `postDelayed`, `Timer`, `SupervisorJob`, `Job()`, `cancel()`, `delay(`,
`.await`, `suspend fun`, plus `@Volatile`, `@Synchronized`, `synchronized(`, `Mutex`, `Semaphore`,
`Atomic`, `ConcurrentHashMap`, `ThreadLocal`, `MutableStateFlow`, `LaunchedEffect`,
`DisposableEffect`, then reading every file that matched.

## Process topology (read this first)

`AndroidManifest.xml:82` is the only `android:process` declaration in the app:

```79:83:app/src/main/AndroidManifest.xml
        <service
            android:name=".service.GrayzoneAccessibilityService"
            android:exported="false"
            android:process=":a11y"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
```

Therefore:

- **`:a11y` process** hosts `GrayzoneAccessibilityService` **only**. It creates **zero** coroutine
  scopes, zero threads, zero executors, zero handlers of its own. Its two callbacks
  (`onServiceConnected`, `onAccessibilityEvent`) run entirely on that process's main thread and do
  nothing but build an `AccessibilityServiceInfo`, `sendBroadcast`, and `startForegroundService`.
- **main process** hosts everything else: `MainActivity`, `OverlayService`, `AdBlockVpnService`,
  all three receivers, WorkManager, Room, and every scope in the table below.
- Because `GrayzoneApplication.appScope` is a field initializer (`GrayzoneApplication.kt:36`), the
  scope object *is* constructed in the `:a11y` process, but `onCreate` returns before any use:

```52:54:app/src/main/java/com/grayzone/app/GrayzoneApplication.kt
        // The :a11y process must NEVER touch prefs, the backup file, WorkManager, or restore —
        // SharedPreferences isn't multi-process safe and two writers corrupt the backup.
        if (Application.getProcessName() != packageName) return
```

  So no coroutine is ever launched in `:a11y`. Every `object` singleton (`BlocklistManager`,
  `DnsTrafficBus`, `ProtectionHealthRepository`, `DateUtils`, `AppMetadataCache`) exists as a
  **separate instance per process**; none is shared across the split.

**Stale comment contradicting the manifest** — `OverlayService.kt:165-166` claims the opposite and
would mislead anyone reasoning about threading from it:

```164:166:app/src/main/java/com/grayzone/app/service/OverlayService.kt
        // Register broadcast receivers. Must never throw out of onCreate: OverlayService shares a
        // process with GrayzoneAccessibilityService, and an uncaught crash here makes Android
        // disable (revoke) the accessibility service.
```

`OverlayService` has no `android:process` attribute (`AndroidManifest.xml:65-72`), so it runs in the
main process and does **not** share a process with the accessibility service.

## Inventory

Total: **43 rows** — 5 explicitly-constructed `CoroutineScope`s, 18 launched coroutines/jobs,
2 raw `Thread`s, 2 one-shot main-`Handler` posts, 2 framework executors, 2 `AlarmManager` schedulers,
1 bounded socket pool, 11 Compose lifecycle-bound coroutine groups. Process is main unless stated.

| ID | Scope/thread | Declared in (file:line) | Dispatcher | Owner lifecycle | Cancelled at (file:line) | Can outlive owner? | Notes |
|---|---|---|---|---|---|---|---|
| C1 | `appScope` | `GrayzoneApplication.kt:36` | `Dispatchers.IO` + `SupervisorJob` | `Application` (process) | **never** | N/A — dies only with the process | Hosts J4's infinite loop. Constructed in `:a11y` too but unused (`:54`). |
| C2 | `serviceScope` (OverlayService) | `OverlayService.kt:115-117` | `Dispatchers.IO` + `SupervisorJob` + CEH | `Service` | `OverlayService.kt:765` | No | Cancelled in `onDestroy`; body quoted below. |
| C3 | `serviceScope` (AdBlockVpnService) | `AdBlockVpnService.kt:100-102` | `Dispatchers.IO` + `SupervisorJob` + CEH | `Service` | `AdBlockVpnService.kt:339` | No | Cancelled in `onDestroy`; body quoted below. |
| C4 | `streakScope` | `HomeScreen.kt:47` | `AndroidUiDispatcher.Main` (inherited from composition) | composition | framework, on decomposition | No | Runs `StreakEvaluator.run` on the **main thread** — see main-thread section. |
| C5 | `scope` (LiveTrafficFeed) | `VpnScreen.kt:416` | `AndroidUiDispatcher.Main` | composition | framework, on decomposition | No | Only `animateScrollToItem` (`:419`). |
| J1 | `transitionJob` — app-transition drain | launched `OverlayService.kt:308`, field `:129` | IO (C2 default) | C2 | via C2 `:765`; self-clears `:317` | No | Guarded by `synchronized(transitionLock)` `:305`,`:314`. |
| J2 | `countdownJobUI` — overlay countdown | launched `OverlayService.kt:1004`, field `:103` | `Dispatchers.Main` | C2 | `:1003`, `:1035`, and C2 `:765` | No | `isActive` re-checked at `:1020` before granting a session. |
| J3 | midnight-reset loop | `OverlayService.kt:244` | IO | C2 | via C2 `:765` only | No | `while (true)` + `delay(millisUntilMidnight)` `:247`. No handle kept. |
| J4 | backup flush loop | `GrayzoneApplication.kt:128` | IO | C1 | **never** | N/A (process-lifetime) | `while (true) { delay(120_000) }` — unstoppable by design; no cancel path exists. |
| J5 | screen-off session pause | `OverlayService.kt:417` | IO | C2 | via C2 `:765` | No | |
| J6 | show overlay ×4 (friction/lockout/schedule/budget) | `OverlayService.kt:492`, `:497`, `:502`, `:507` | `Dispatchers.Main` | C2 | via C2 `:765` | No | Launched from `executeCommand`, itself on IO. |
| J7 | clamp countdown notification | `OverlayService.kt:648` | `Dispatchers.Main` | C2 | via C2 `:765` | No | |
| J8 | show lock on session expiry | `OverlayService.kt:650` | IO → `withContext(Main)` `:652` | C2 | via C2 `:765` | No | |
| J9 | record friction skip | `OverlayService.kt:947` | `Dispatchers.IO` (explicit) | C2 | via C2 `:765` | No | Calls `recordFrictionSkip` `:1102`, which nests J10. |
| J10 | insert blocked-session row | `OverlayService.kt:1113` | IO | C2 | via C2 `:765` | No | Nested inside J9. |
| J11 | `restartVpnAfterDelay` | `AdBlockVpnService.kt:315` | IO | C3 | via C3 `:339` | **Yes, logically** | Calls `startVpn()` at `:323` unconditionally after `delay` — see race R6. |
| J12 | forward DNS query | `AdBlockVpnService.kt:458` | IO | C3 | via C3 `:339` | No | One coroutine **per forwarded DNS query**; `withContext(Dispatchers.IO)` at `:497`/`:515`. |
| J13 | one-time baseline backup write | `GrayzoneApplication.kt:96` | IO | C1 | never (C1) | N/A | File write + AES-GCM encrypt off-main. Correct. |
| J14 | dirty-flag backup write | `GrayzoneApplication.kt:139` | IO | C1 | never (C1) | N/A | Called from `flushBackupIfDirty` `:137`, itself called from `onActivityStopped` (main) `:116`. |
| J15 | deferred startup work | `GrayzoneApplication.kt:148` | IO | C1 | never (C1) | N/A | `PackageManager.getPackageInfo` loop `:237-252` off-main. Correct. |
| J16 | streak catch-up on start | `GrayzoneApplication.kt:162` | IO | C1 | never (C1) | N/A | |
| J17 | streak catch-up on `ON_RESUME` | `HomeScreen.kt:56` | **Main** (C4) | C4 | framework | No | Main-thread prefs + Gson + Room entry point. See main-thread section. |
| J18 | scroll traffic feed | `VpnScreen.kt:419` | Main (C5) | C5 | framework | No | |
| T1 | VPN packet-loop thread | `AdBlockVpnService.kt:223-224` | raw `Thread`, unnamed, default priority | `Service` (`vpnThread` field `:87`) | `interrupt()` `:285`; fd closed `:287` | **Yes, briefly** | Not a coroutine — `serviceScope.cancel()` does not touch it. See VPN section. |
| T2 | blocklist loader thread | `BlocklistManager.kt:145-159` | raw `Thread`, unnamed | **`object` singleton**, started from `AdBlockVpnService.onCreate:110` | **never** interrupted or joined | **Yes** | Reads ~10 MB of `res/raw` and writes static fields; keeps running after the VPN service is destroyed. |
| H1 | main-looper Toast post | `OverlayService.kt:797` | main looper | one-shot | n/a | No | No `postDelayed`, no retained `Handler` field. |
| H2 | main-looper Toast post | `AdBlockVpnService.kt:232` | main looper | one-shot | n/a | No | Same shape. |
| X1 | Room query/transaction executors | `UsageDatabase.kt:36-43` | AndroidX default arch IO executor | `UsageDatabase` singleton | never (app-lifetime singleton) | N/A | Not overridden — no `setQueryExecutor`/`setTransactionExecutor`, no `allowMainThreadQueries()`. |
| X2 | WorkManager executor → `CleanupWorker` | enqueued `GrayzoneApplication.kt:286-294`; body `CleanupWorker.kt:40` | `Dispatchers.Default` (`CoroutineWorker` default; not overridden in `CleanupWorker.kt:21-24`) | WorkManager | WorkManager cancels on stop | No | Weekly periodic. |
| A1 | `SessionExpiryScheduler` alarms | `SessionExpiryScheduler.kt:41-70` | `AlarmManager` → `SessionExpiryReceiver.onReceive` (main thread) | per-package `PendingIntent` | `SessionExpiryScheduler.kt:75`, called from `OverlayService.kt:267`, `:523` | Yes (survives process death — intentional) | Replaced an in-memory `delay()` lock path per its own doc comment `:14-16`. |
| A2 | `VpnHoldExpiryScheduler` alarm | `VpnHoldExpiryScheduler.kt:32-52` | `AlarmManager` → `VpnHoldExpiryReceiver.onReceive` (main thread) | single `PendingIntent` | `VpnHoldExpiryScheduler.kt:56`, called from `AdBlockVpnService.kt:152`, `:175`, `:304` | Yes (intentional) | |
| P1 | `DnsResolverPool` (semaphore + idle UDP sockets) | `DnsResolverPool.kt:22-23`, constructed `AdBlockVpnService.kt:115-120` | `kotlinx.coroutines.sync.Semaphore(20)`; queries run in `withContext(Dispatchers.IO)` `:48` | `AdBlockVpnService` (`resolverPool` field `:89`) | `shutdown()` `DnsResolverPool.kt:119`, called `AdBlockVpnService.kt:297` | No | Deliberately synchronous shutdown so it works after C3 is cancelled (comment `:295-296`). |
| U1 | `rememberResumedTicker` | `SharedComponents.kt:41-48` | composition (Main) | `LaunchedEffect` + `repeatOnLifecycle(RESUMED)` | framework | No | Correct pattern: suspends when backgrounded. |
| U2 | `ResumedInterval` | `SharedComponents.kt:64-72` | Main | same | framework | No | Used by `QuickFocusCard.kt:33`, `DailyBreakCard.kt:37`, `ScheduleSheet.kt:43`. |
| U3 | startup permissions/services effect | `MainActivity.kt:247-265` | Main | composition | framework | No | Calls `startForegroundService` `:257` and `reconcileVpnState` `:258` on the main thread. |
| U4 | VPN hold ticker | `VpnScreen.kt:74-83` | Main | `repeatOnLifecycle(RESUMED)` | framework | No | Re-reads prefs every 1 s at `:79` on the main thread. |
| U5 | app-list load | `AppsScreen.kt:51-54` | Main → `withContext(IO)` inside `getInstalledApps` (`AppUtils.kt:77`) | composition | framework | No | Heavy work correctly on IO. |
| U6 | app-list load | `LimitsScreen.kt:31-33` | same as U5 | composition | framework | No | |
| U7 | traffic-feed autoscroll effect | `VpnScreen.kt:418-420` | Main | composition | framework | No | |
| U8 | spinner animation | `SharedComponents.kt:149-158` | Compose frame clock | composition | framework | No | |
| U9 | limits pulse animation | `LimitsScreen.kt:80` | Compose frame clock | composition | framework | No | |
| U10 | 7 × `DisposableEffect` lifecycle observers | `MainActivity.kt:64`, `:211`; `HomeScreen.kt:49`; `StreakCard.kt:35`; `SettingsScreen.kt:51`; `VpnScreen.kt:62`; `OnboardingScreen.kt:41` | main thread callbacks | composition | `onDispose` removes observer in every case | No | All 7 correctly remove their observer. |
| U11 | `collectAsState` collectors | `VpnScreen.kt:51`, `:293`, `:414` | Main | composition | framework | No | `ProtectionHealthRepository.status`, `DnsTrafficBus.events`. |

**Absent primitives (verified, zero occurrences):** `GlobalScope`, `MainScope`, `lifecycleScope`,
`runBlocking`, `async`/`await` other than the one `CompletableDeferred.await` at
`AdBlockVpnService.kt:510`, `java.util.Timer`/`TimerTask`, `Handler.postDelayed`, `HandlerThread`,
`Executors.newSingleThreadExecutor`, `kotlinx.coroutines.sync.Mutex`.

## Scopes with no cancellation site

**One scope is never cancelled: `GrayzoneApplication.appScope` (`GrayzoneApplication.kt:36`).** Its
owner is the `Application`, so it cannot leak past the process, and `SupervisorJob` keeps one failure
from killing later callers. The consequence is narrower than a leak: J4
(`GrayzoneApplication.kt:128-133`) is a `while (true) { delay(120_000); flushBackupIfDirty() }` loop
with no cancel path, so the flush cadence can never be stopped or reconfigured at runtime. Not a
service leak; record as design-fixed-forever rather than a leak.

**Both `Service` scopes are cancelled.** Proof, quoted in full.

`OverlayService.onDestroy` — cancels C2 at `:765`, and there is no `onUnbind` (it returns a null
binder at `:769`):

```758:769:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    override fun onDestroy() {
        super.onDestroy()
        com.grayzone.app.data.ProtectionHealthRepository.updateOverlayStatus(false)
        safeUnregister(appChangeReceiver)
        appChangeReceiver = null
        safeUnregister(screenReceiver)
        screenReceiver = null
        serviceScope.cancel()
        dismissOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null
```

Both receivers are unregistered and both fields nulled — no receiver leak.

`AdBlockVpnService.onDestroy` — cancels C3 at `:339`:

```327:340:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
    override fun onDestroy() {
        super.onDestroy()
        // Do NOT clear the persisted VPN_ENABLED flag here: onDestroy also fires
        // when the OS kills the service, and the flag represents user intent that
        // BootReceiver / START_STICKY use to restore protection. The flag is only
        // cleared by an explicit ACTION_STOP / hold expiry (stopVpn).
        val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        val wantsVpn = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
        if (wantsVpn) {
            com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
        }
        shutdownVpn(clearPersistedFlag = false, stopService = false)
        serviceScope.cancel()
    }
```

Note the ordering: `shutdownVpn` (`:338`) runs **before** `serviceScope.cancel()` (`:339`), and
`shutdownVpn` can reach `restartVpnAfterDelay` indirectly via T1's uncaught-exception handler — see
race R6. `AdBlockVpnService` overrides **no** `onUnbind` and **no `onRevoke`** (verified: zero
occurrences of `onRevoke` in the whole source tree) — see the VPN section.

`GrayzoneAccessibilityService` (`:a11y`) has nothing to cancel; both teardown callbacks are
log-only, quoted in full:

```119:130:app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.ACCESSIBILITY,
            "Accessibility service unbound by the system (possible revocation/crash)"
        )
        return super.onUnbind(intent)
    }
```

This is correct for a service that owns no scope. One adjacent gap: `updateAccessibilityStatus`
(`ProtectionHealthRepository.kt:34`) has **zero call sites** anywhere in the tree, and could not work
from here even if called — `ProtectionHealthRepository` is a per-process `object`, so a write from
`:a11y` would be invisible to the UI in the main process. `accessibilityActive` in the health model
is therefore permanently at its default.

## GlobalScope / unstructured concurrency

**Zero `GlobalScope` uses. Zero `MainScope` uses.** All three long-lived scopes carry a
`SupervisorJob` the owner holds a reference to (`GrayzoneApplication.kt:36`,
`OverlayService.kt:115-117`, `AdBlockVpnService.kt:100-102`), and both service scopes also install a
`CoroutineExceptionHandler` (`OverlayService.kt:107-113`, `AdBlockVpnService.kt:92-98`). This is the
structured-concurrency shape the audit was looking for and it is present.

The unstructured concurrency that **does** exist is not coroutine-based — it is the two raw
`Thread`s, neither of which any `Job` can cancel:

1. **T1, the VPN packet thread** (`AdBlockVpnService.kt:223-224`):

```223:224:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
            vpnThread = Thread { runVpnLoop() }
            vpnThread?.start()
```

   `serviceScope.cancel()` at `:339` has no effect on it; the only stop mechanism is `interrupt()` +
   fd close in `shutdownVpn` (`:285-289`).

2. **T2, the blocklist loader** (`BlocklistManager.kt:145-159`) — a thread started from a
   **`object` singleton**, with no owner, no handle, no join and no interrupt:

```143:160:app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt
    fun load(context: Context) {
        if (!loadStarted.compareAndSet(false, true)) return
        Thread {
            try {
                adFilter    = loadFilter(context, R.raw.adblock_bloom, "ads")
                adultFilter = loadFilter(context, R.raw.adult_bloom,   "adult")
                abusedTlds  = loadAbusedTlds(context)
                isLoaded = true
            } catch (e: Throwable) {
                adFilter = null
                adultFilter = null
                abusedTlds = AbusedTldList.EMPTY
                isLoaded = false
                loadStarted.set(false)
                Log.e(TAG, "Error loading bloom filters", e)
            }
        }.start()
    }
```

   Started from `AdBlockVpnService.onCreate:110`. It reads ~10 MB of `res/raw` (per the doc comment
   at `GrayzoneBloomFilter.kt:10-12`), so it can still be running when the service is destroyed, and
   it will then write `adFilter`/`adultFilter`/`abusedTlds`/`isLoaded` after its starter is gone.
   Because the fields are static this is benign for correctness, but it is an uncancellable
   background thread holding a `Context` reference (`loadFilter(context, …)`) — see race R3 for the
   visibility problem with those same fields.

Both are `Thread` defaults: **no name** (so they appear as `Thread-N` in traces/ANRs) and
**no priority set** (`Process.THREAD_PRIORITY_BACKGROUND` is never applied), which is worth noting
for a packet-processing thread.

## Blocking work on the main thread

### First, the lint correction: `OverlayService.kt:531` and `:600` are **not** on the main thread

`lint.md` E4 flagged both `commit()` sites and left the main-thread question open for this task. Both
are on `Dispatchers.IO`, traced by call chain:

- **`:531`** is in `executeCommand`, a `suspend fun` (`OverlayService.kt:485`). Its only two callers
  are `processForegroundAppChanged` (`:373`) and `backgroundApp` (`:406`). `processForegroundAppChanged`
  is reached only from `drainForegroundTransitions` (`:323`), launched at `:308` on C2 (IO default).
  `backgroundApp` is reached from `processForegroundAppChanged` (`:348`) and from `handleScreenOff`'s
  `serviceScope.launch` (`:417`, IO default). There is no `withContext(Main)` anywhere in that chain
  — the `Main` hops inside `executeCommand` (`:492`, `:497`, `:502`, `:507`) are *nested launches*,
  so the enclosing body stays on IO.
- **`:600`** is inside `logUsageAndCheckBudget`, whose body is explicitly `= withContext(Dispatchers.IO)`:

```559:563:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    private suspend fun logUsageAndCheckBudget(
        pkg: String,
        startTime: Long,
        wasBlocked: Boolean = false
    ) = withContext(Dispatchers.IO) {
```

**Classification: the `ApplySharedPref` warnings are real (`commit()` is a synchronous fsync-bearing
write) but the main-thread aggravation lint implies does not apply.** Severity should be lowered
accordingly. The remaining cost is that `:531` blocks the IO coroutine on every state update — on an
app-switch hot path — and `:600` blocks it on every budget accumulation.

### The genuine main-thread blocking I/O

| # | Site | Main thread because | Blocking work |
|---|---|---|---|
| B1 | `GrayzoneApplication.kt:64` → `StateBackupManager.restoreIfWiped` | `Application.onCreate` is the main thread | `hiddenFile().readBytes()` (`StateBackupManager.kt:190`) from **shared external storage**, AES-256-GCM `doFinal` (`:288`), `JsonParser.parseString` (`:230`), then a synchronous **`editor.commit()`** (`:250`) |
| B2 | `MainActivity.kt:47-48` → `StateBackupManager.restoreIfWiped` | `Activity.onResume` is the main thread; runs **before** `super.onResume()` | identical to B1, repeated on **every** resume once all-files access is granted |
| B3 | `SettingsScreen.kt:82` → `shareApk` (`SharedComponents.kt:178-216`) | Compose `.clickable { }` lambda | copies the **entire APK** synchronously: `source.inputStream().use { … input.copyTo(output) }` (`SharedComponents.kt:193-195`) |
| B4 | `HomeScreen.kt:56-57` (J17) → `StreakEvaluator.run` | `rememberCoroutineScope()` inherits the composition's `AndroidUiDispatcher.Main` | `getSharedPreferences` (`StreakEvaluator.kt:18`), `StreakManager` ctor prefs load (`StreakManager.kt:29-30`), then per pending day `prefs.edit().apply()` + Gson serialize in `checkDailyStreakForDate`/`unlockAchievement` (`StreakManager.kt:110-114`, `:153`). Up to 90 iterations (`StreakManager.kt:78`) |
| B5 | `MainActivity.kt:189-207` `evaluateScreen()` | called during composition (`:209`) and from the `ON_RESUME` observer (`:213`) | `Settings.Secure.getString` binder call via `isAccessibilityServiceEnabled` (`AppUtils.kt:110-113`), plus up to two `prefs.edit().apply()` (`:196`, `:199`) |
| B6 | `StreakCard.kt:31-33` and `:38-40` | composition + `ON_RESUME` observer | prefs load and **Gson deserialize** of the achievements JSON (`StreakManager.kt:129-135`) |
| B7 | `HomeScreen.kt:42-46` | composition | `Settings.Secure` read, `Settings.canDrawOverlays`, `PowerManager.isIgnoringBatteryOptimizations` (`AppUtils.kt:139-140`), prefs load |
| B8 | `OverlayService.showOverlay` | invoked only from `Dispatchers.Main` (`:492`,`:497`,`:502`,`:507`,`:612`, `:282` inside `withContext(Main)`, and the J2 finish path `:1028`) | prefs load `:826`, **Gson deserialize** of custom prompts `:848-861`, plus `resources`/`displayMetrics` work |
| B9 | `OverlayService.markAppUnlocked` (`:1047-1100`) | called from J2 on `Dispatchers.Main` via `CountdownFinishContext.markAppUnlocked` (`:1025`) | 7 prefs reads + `prefs.edit()…apply()` (`:1091-1095`) + `startCountdownNotification` |
| B10 | `OverlayService.onCreate` (`:140-176`) | `Service.onCreate` is the main thread | `ScheduleManager(this)` prefs load (`:146`), prefs load `:150-151`, `clearStaleSystemGrayscale` → `Settings.Secure.putString` (`:776-780`) |
| B11 | `OverlayService.handleSessionExpired` (`:630-643`) | reached from `onStartCommand:672`, main thread | prefs load `:631-634`, plus `scheduleManager.isBreakActive()` `:642` |
| B12 | `AdBlockVpnService.onStartCommand` (`:122-170`) | `Service.onStartCommand` is the main thread | `VpnHoldLockStore.prefs`/`read` `:137-138`, `:157-158`; `prefs.edit()…apply()` `:218`; `buildNotification()` prefs read `:591` |
| B13 | `BootReceiver.onReceive` (`:28-78`) | receiver main thread, no `goAsync()` | prefs load `:34`, `VpnHoldLockStore.read` `:49`, `prefs.edit().apply()` `:53` |
| B14 | `VpnScreen.kt:79` | U4 ticker on Main | re-reads two prefs **every second** while the screen is resumed |
| B15 | `QuickFocusCard.kt:29-34`, `:90`, `:102`; `ScheduleManager.isCurrentlyScheduled` (`:66-76`) | composition / click on Main | prefs reads + **Gson deserialize of schedule rules** on the main thread |

**Not** main-thread I/O (verified, so the audit does not double-count): the `res/raw` bloom-filter
load runs on T2 (`BlocklistManager.kt:145`); `getInstalledApps` is `withContext(Dispatchers.IO)`
(`AppUtils.kt:77`); `StateBackupManager.writeBackup` always runs on C1/IO (`GrayzoneApplication.kt:96`,
`:139`); `recordFrictionSkip` runs on `Dispatchers.IO` (`OverlayService.kt:947`).

**Ranking note.** B1/B2 are the strongest candidates: they combine an external-storage read, an
AES-GCM decrypt, a JSON parse and a synchronous `commit()` on `Application.onCreate` /
`Activity.onResume`. The `restoreIfWiped` doc comment acknowledges the design (`StateBackupManager.kt:99-100`:
"Runs synchronously so the restored values are visible before the rest of onCreate … Keep the payload
small") — the *ordering* requirement is real, but the payload is "every `PREFS_NAME` entry"
(`:167`), which grows with the number of monitored apps (each app contributes ~8 keys). B3 is
unbounded — APK size — with no coroutine at all.

The `SharedPreferences` reads at B5–B15 each block only on the **first** `getSharedPreferences` for a
given file per process (Android loads the file on a background thread and blocks getters until it is
ready); subsequent reads are in-memory map hits. They are listed for completeness, but the
first-touch-per-process cost lands on `Application.onCreate` (B1's own `getSharedPreferences` at
`StateBackupManager.kt:105`) rather than on each of these sites.

## Room database access

`UsageDatabase` is built without `allowMainThreadQueries()` (`UsageDatabase.kt:36-43`), so **Room's
main-thread assertion is active**.

**All 13 DAO functions are `suspend`; there are zero blocking DAO methods and zero `Flow` queries**
(`UsageDao.kt:12, 18, 22, 28, 32, 36, 40, 44, 60, 64, 68, 74, 78`). Room therefore dispatches every
one of them onto its own query executor (X1) regardless of the caller's dispatcher, so **no call site
can trip the main-thread check**.

| DAO call site | Caller's dispatcher | Would Room's main-thread check allow it? |
|---|---|---|
| `OverlayService.kt:574` `dao.insertEvent(...)` (dao obtained `:570`) | `Dispatchers.IO` — enclosing `logUsageAndCheckBudget` is `withContext(Dispatchers.IO)` (`:563`) | Yes — `suspend`, and not on main anyway |
| `OverlayService.kt:1117` `usageDao().insertEvent(...)` | IO — J10, launched `:1113` inside J9 (`:947`, explicit `Dispatchers.IO`) | Yes |
| `CleanupWorker.kt:47` `dao.deleteEventsOlderThan(...)` | `Dispatchers.Default` — `CoroutineWorker` default, not overridden | Yes — `suspend` |
| `CleanupWorker.kt:48` `dao.getEventCount()` | `Dispatchers.Default` | Yes |
| `StreakEvaluator.kt:32` `dao.getDailySummary(dateKey)` | **Three different dispatchers**: IO from `GrayzoneApplication.kt:164` (C1) and from `OverlayService.kt:289` (C2, inside J3); **Main** from `HomeScreen.kt:57` (C4/J17) | Yes even on Main — `getDailySummary` is `suspend` (`UsageDao.kt:18`), so Room moves the query off the main thread itself |

The `HomeScreen` → `StreakEvaluator` path is therefore **safe with respect to Room** but still does
its *SharedPreferences and Gson* work on the main thread (B4). The distinction matters: the Room
part is correct; the surrounding prefs/JSON part is not.

`UsageDatabase.getInstance` is also called on all three dispatchers (`OverlayService.kt:570`, `:1117`;
`CleanupWorker.kt:42`; `StreakEvaluator.kt:27`). `Room.databaseBuilder(...).build()` does not open the
database file — the open is deferred to the first query, which runs on X1 — so the main-thread cost
of `getInstance` is small. Its **locking is broken**, though; see race R1.

`MIGRATION_1_2` (`UsageDatabase.kt:24-32`) creates four indexes and runs on X1 during the first
query after an upgrade, not on the caller's thread.

## VPN packet loop

The loop is `runVpnLoop()` (`AdBlockVpnService.kt:342-425`).

**Which thread it runs on.** T1, the raw unnamed `Thread` created at `:223-224` and started at
`:224`. It is *not* on `serviceScope`, so `serviceScope.cancel()` (`:339`) cannot stop it. It
installs its own uncaught-exception handler at `:351-358`, which calls `restartVpnAfterDelay(3000)`
if `isRunning`.

**Whether it blocks.** Yes, by design, in three places:

1. `Builder.setBlocking(true)` (`:200`), so `inputStream.read(packet)` at `:363` is a blocking read.
2. `Thread.sleep(backoffMs)` at `:412` — the error-backoff path, up to 1600 ms.
3. The sinkhole/NXDOMAIN write path runs **inline on T1**: `handleDnsQuery` (`:434`) calls
   `writeNxDomain`/`writeSinkhole` (`:446`, `:452`) which do
   `synchronized(outputStream) { outputStream.write(...) }` (`:468`, `:475`) on T1 itself. Only the
   `FORWARD` branch hands off to a coroutine (`:458`). So a blocked domain's response and the *next*
   packet read are serialized on one thread, and T1 can also block on the `outputStream` monitor held
   by a J12 coroutine writing a forwarded response (`:537`).

Upstream resolution itself does **not** block T1: it happens in J12 on `Dispatchers.IO`, bounded to
20 concurrent sockets by P1's semaphore (`DnsResolverPool.kt:22, 42`) with `withTimeoutOrNull` plus
`SO_TIMEOUT` (`:49`, `:57`).

**Whether it allocates per packet.** The 32767-byte read buffer is correctly hoisted out of the loop
(`:360`, with a comment saying so). Everything after that allocates **per DNS query**:

| Allocation | Site |
|---|---|
| `packet.copyOf(length)` — defensive copy so the reused buffer can be handed to a coroutine | `AdBlockVpnService.kt:370` |
| `StringBuilder` + one `String` per DNS label + the joined domain | `DnsPacketHelper.kt:132`, `:141`, `:146` |
| `DnsEvent` + `idCounter.incrementAndGet().toString()` — a `String` per query | `DnsTrafficBus.kt:30` |
| **A whole new `List<DnsEvent>` of up to 50 elements, per query** | `DnsTrafficBus.kt:43-53` |
| `ByteArray(requestLength)` for each NXDOMAIN/sinkhole response | `DnsPacketHelper.kt:183`, `:309` |
| `ByteArray(newTotalLength)` for each wrapped forwarded response | `DnsPacketHelper.kt:251`, `:349` |
| `ByteArray(32767)` receive buffer **per upstream query** | `DnsResolverPool.kt:53` |
| `CompletableDeferred` + `DnsQuestionKey` per forwarded query | `AdBlockVpnService.kt:502`, `:495` |
| `SHA-256` digest output per zone probe (the `MessageDigest` itself is `ThreadLocal`-cached, correctly) | `GrayzoneBloomFilter.kt:32`, `:51-53` |
| A coroutine per forwarded query | `AdBlockVpnService.kt:458` |

The `DnsTrafficBus` list copy is the notable one: it runs on **every** query even with the VPN screen
closed, and the code says that is deliberate (`DnsTrafficBus.kt:57-61`). At 50 events that is a
50-element array copy plus a `String` allocation per DNS query, on the packet thread, under a
class-level monitor (`@Synchronized`, `:39`).

**How it terminates.**

- **`onRevoke`: not overridden.** Verified — zero occurrences of `onRevoke` in the tree. Termination
  on revocation therefore relies on `VpnService.onRevoke`'s default `stopSelf()`, which reaches
  `onDestroy` → `shutdownVpn` (`:338`). That works, but the app gets no hook to log the revocation,
  to set `markVpnReconnectPending`, or to distinguish user revocation from an OS kill. Given that
  this app's entire premise is that protection must not silently lapse, the missing override is a
  real gap, not a style nit.
- **`onDestroy`** (`:327-340`) calls `shutdownVpn(clearPersistedFlag = false, stopService = false)`
  then `serviceScope.cancel()`.
- **`shutdownVpn`** (`:283-312`) is the actual stop mechanism:

```283:293:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
    private fun shutdownVpn(clearPersistedFlag: Boolean, stopService: Boolean) {
        isRunning = false
        vpnThread?.interrupt()
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* ignored */ }
        vpnInterface = null
        vpnThread = null
        pendingQueries.clear()
```

  `isRunning = false` (`:284`) ends the `while (isRunning)` loop (`:361`) — but only once the
  in-flight `read()` returns. `interrupt()` (`:285`) does not unblock a blocking
  `FileInputStream.read` on a tun fd; the fd `close()` at `:287` is the mechanism that makes the
  pending read fail so the loop can exit through its `catch` at `:379`. **Whether the close reliably
  unblocks the read on all devices needs verification** — on some kernels/implementations a
  concurrent close on a blocked descriptor can surface as `EBADF`, an error return, or (rarely) leave
  the read parked. What would settle it: instrument a stop while traffic is flowing and confirm from
  logcat that `"VPN loop error"` (`:383-386`) or loop exit is observed, or take a thread dump after
  `stopVpn()` and confirm no `Thread-N` is still parked in `read`. `vpnThread` is also nulled at
  `:290` **without a `join()`**, so no code path ever waits for T1 to actually die.

  A related hazard: `pendingQueries.clear()` (`:291`) drops the map entries **without completing the
  `CompletableDeferred`s**. Any J12 coroutine parked on `existingQuery.await()` (`:510`) is left
  waiting on a deferred nobody will complete — it is only freed when `serviceScope.cancel()` runs.
  On the `stopVpn` path (`:278-281`, which passes `stopService = true` but never cancels the scope)
  those waiters are not cancelled by anything in `shutdownVpn`; they are released only when
  `stopSelf()` eventually produces `onDestroy` → `:339`. There is no `withTimeout` around the
  `await` at `:510`.

## Race conditions

### R1 — `UsageDatabase` double-checked locking is missing its second check (real defect)

```34:47:app/src/main/java/com/grayzone/app/data/UsageDatabase.kt
        fun getInstance(context: Context): UsageDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UsageDatabase::class.java,
                    "grayzone_usage.db"
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)  // Add migration instead of destructive fallback
                .build()
                INSTANCE = instance
                instance
            }
        }
```

There is no `INSTANCE?.let { return@synchronized it }` inside the lock. Two threads that both read
`INSTANCE == null` at `:35` serialize on the monitor and then **each build a separate
`UsageDatabase`**; the second overwrites `INSTANCE` while the first thread keeps using its own
instance. `@Volatile` on the field (`:16`) makes publication visible but does nothing about the
duplicate construction.

- **State**: `INSTANCE` (`UsageDatabase.kt:17`) and, downstream, the set of open SQLite connections.
- **Writers**: any of C1/IO (`StreakEvaluator.kt:27` via `GrayzoneApplication.kt:164`), C2/IO
  (`OverlayService.kt:570`, `:1117`), X2/`Dispatchers.Default` (`CleanupWorker.kt:42`), and Main
  (`StreakEvaluator.kt:27` via `HomeScreen.kt:57`).
- **Readers**: the same four sites.
- **Reachable window**: `GrayzoneApplication.onCreate` fires J16 (`:162`) at process start while
  `OverlayService.onCreate` is starting in the same process, and `CleanupWorker` can run
  concurrently. Two `RoomDatabase` instances on one WAL file means independent connection pools and
  invalidation trackers — duplicate `insertEvent`s cannot corrupt rows, but `WRITE_AHEAD_LOGGING`
  (`:41`) plus two pools invites `SQLiteDatabaseLockedException` under write contention, and any
  future `Flow`/`InvalidationTracker` use would silently miss cross-instance changes.

### R2 — `AppMetadataCache` is unsynchronized by declaration, but not currently reachable from two threads

```21:47:app/src/main/java/com/grayzone/app/AppUtils.kt
private object AppMetadataCache {
    private var cachedApps: List<AppInfo>? = null
    private var cacheTimestamp = 0L
```

- **State**: `cachedApps` and `cacheTimestamp` — plain `var`s, **no `@Volatile`, no lock, no
  `Atomic`**, and written non-atomically as two separate stores (`:39`, `:40`).
- **Writer**: `set(...)` (`:38-41`), reached only from `getInstalledAppsCached` (`:57-59`).
- **Reader**: `peek()` (`:36`) via `peekCachedApps()` (`:67`), called during composition at
  `AppsScreen.kt:41` and `LimitsScreen.kt:27`.

**This is not a live race, and the audit should not report it as one.** `getInstalledAppsCached`
(`:53`) is a `suspend fun` with **no** `withContext` of its own; the `withContext(Dispatchers.IO)`
lives one level down inside `getInstalledApps` (`:77`) and *returns to the caller's context*. So the
`.also { AppMetadataCache.set(it) }` at `:57-59` runs on whatever dispatcher called it — and its only
two call sites are `LaunchedEffect` bodies on the main thread (`AppsScreen.kt:51-52`,
`LimitsScreen.kt:31-32`). Writers and readers are therefore all main-thread today.
`refreshInstalledApps` (`:72`), the only other mutator (`invalidate()` at `:74`), has **zero call
sites**.

**Classification: latent, Low.** The object is documented as a cache but offers no thread-safety, so
the first caller added from a background dispatcher (e.g. prefetching the app list from `appScope`)
introduces the torn read for free. Worth a `@Volatile`/`AtomicReference` note, not a defect finding.

### R3 — `BlocklistManager` filter fields written by T2, read by T1

- **State**: `adFilter` (`BlocklistManager.kt:15`), `adultFilter` (`:16`), `abusedTlds` (`:20`) —
  all plain `var`s with **no `@Volatile`**. Only `isLoaded` (`:138`) is `@Volatile`.
- **Writer**: T2, the loader thread (`:147`, `:148`, `:149`, then `isLoaded = true` at `:150`; and
  the failure path `:152-156`).
- **Reader**: T1, the VPN packet thread, via `isBlocked` (`:211-223`) → `:219-220` reads `adFilter`
  and `adultFilter` into locals, `:218` reads `abusedTlds`.

The read is *mostly* protected by accident: `isLoaded` is `@Volatile` and is written **after** the
three filter fields (`:147-150`), so a reader that observes `isLoaded == true` at `:216` also
observes the three preceding writes (volatile-store/volatile-load release-acquire). The code even
copies to locals at `:219-220` to avoid a TOCTOU null. **But `abusedTlds` is read at `:218`, after
the `isLoaded` gate, so that one is covered too.** The residual hazard is the *failure* path
(`:152-156`), which writes `adFilter = null`/`adultFilter = null`/`abusedTlds = EMPTY` **before**
setting `isLoaded = false` (`:155`) — a reader can be between the `isLoaded` check at `:216` and the
filter reads at `:219-220` and then see the nulls. The `!= null` guards at `:221-222` make that safe
in practice. **Classification: correct-by-accident rather than by declaration.** Adding `@Volatile`
to the three fields would make the guarantee explicit instead of dependent on the write ordering of
an unrelated flag.

`normalizedDomainCache` (`:85-89`) is a non-thread-safe `LinkedHashMap` in access-order mode, but
every access is inside `synchronized(cacheLock)` (`:166`, `:174`, `:180`, `:186`, `:190`, `:194`) —
correctly guarded, including the `get` (access-order `LinkedHashMap` mutates on read, so the lock on
the read path at `:167` is required and present).

### R4 — `OverlayService.sessionStartTime`: `@Volatile` does not make read-modify-write atomic

```121:122:app/src/main/java/com/grayzone/app/service/OverlayService.kt
    @Volatile private var lastForegroundPackage: String? = null
    @Volatile private var sessionStartTime: Long = 0L
```

- **State**: `sessionStartTime`.
- **Writers**: J1/IO at `:380` (`sessionStartTime = now`) and `:407` (`= 0L` in `backgroundApp`);
  J5/IO at `:421` via `backgroundApp`; **J2/Main** at `:1099` via `markAppUnlocked`.
- **Reader**: `backgroundApp` at `:399` — `val bgDuration = if (sessionStartTime > 0) now - sessionStartTime else 0L`.

`@Volatile` gives visibility, not atomicity. The read-compute-write in `backgroundApp` (`:399` read,
`:407` write) is not atomic against `markAppUnlocked`'s write on the Main dispatcher (`:1099`), so a
session grant that lands between the read and the write can be double-counted or dropped in the usage
duration passed to `RecordUsage` (`:401`) → `logUsageAndCheckBudget` (`:512`) → the daily budget
accumulation (`:590-600`). Impact is a mis-accounted budget, which for this app is a correctness bug
in the protection logic, not cosmetic. The comment at `:119-120` explicitly names all three writers
but concludes `@Volatile` is sufficient, which is the incorrect inference.

`lastForegroundPackage` is only ever assigned whole references (`:351`, `:422`) and read as a whole
(`:263`, `:330`, `:332`, `:416`, `:567`, `:640`, `:1027`), so `@Volatile` **is** sufficient there.

### R5 — `AdBlockVpnService` lifecycle fields: guarded by `@Volatile` but not mutually exclusive

- **State**: `isRunning` (`:33-35`, companion `@Volatile`), `vpnInterface` (`:86`), `vpnThread`
  (`:87`), `resolverPool` (`:89`) — all `@Volatile`, **no lock, no `Mutex`**.
- **Writers**: `startVpn` (`:207`, `:212`, `:214`, `:223`) on **Main** (from `onStartCommand:167`)
  *and* on **IO** (from J11 `:323`); `shutdownVpn` (`:284`, `:289`, `:290`, `:298`) on **Main**
  (`onDestroy:338`, `stopVpn` from `onStartCommand:150`) *and* on **IO** (J11 `:321`).
- **Reader**: T1 (`:343` `vpnInterface`, `:361` `isRunning`, `:357`, `:416`), plus
  `onStartCommand:124`, `:146`, `:166`.

`startVpn` and `shutdownVpn` are not mutually exclusive, so `onStartCommand` on Main and J11 on IO
can interleave — e.g. J11 sets `isRunning = false` and closes the fd at `:284-289` while a Main-thread
`startVpn` is between `establish()` (`:207`) and `isRunning = true` (`:214`), producing a live tunnel
with `isRunning == false` (loop never starts, or T1 exits immediately) or a second T1 running against
a closed fd. `startVpn`'s only guard is `if (isRunning) return` (`:183`), which is a check-then-act
with no lock.

### R6 — restart-after-delay overrides an authorized stop (logic race, real user impact)

```314:325:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
    private fun restartVpnAfterDelay(delayMs: Long) {
        serviceScope.launch {
            val prefs = getSharedPreferences(com.grayzone.app.PrefsKeys.PREFS_NAME, MODE_PRIVATE)
            val wantsVpn = prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)
            if (wantsVpn) {
                com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
            }
            shutdownVpn(clearPersistedFlag = false, stopService = false)
            kotlinx.coroutines.delay(delayMs)
            startVpn()
        }
    }
```

`wantsVpn` is read **once, before** the delay (`:317`) and is used **only** to set the reconnect
banner (`:318-320`). `startVpn()` at `:323` is then called **unconditionally**. If an authorized stop
lands during the 2–3 s window — `ACTION_STOP` after hold expiry (`:150`), `ACTION_HOLD_EXPIRED`
(`:132`), or the alarm-driven `VpnHoldExpiryReceiver` (`VpnHoldExpiryScheduler.kt:90`) — that stop
clears `VPN_ENABLED` (`:302`) and calls `stopSelf()`, and then this coroutine re-establishes the
tunnel anyway. The intent flag is now `false` while the tunnel is up: `BootReceiver` will not restore
it, `MainActivity.reconcileVpnState` (`:123`) will bail, and the VPN screen's own state comes from
`ProtectionHealthRepository` (`VpnScreen.kt:51-52`) which `startVpn` sets to active (`:226`) —
a VPN running with no persisted intent behind it. Re-reading `VPN_ENABLED` (or checking
`coroutineContext.isActive` plus the hold state) immediately before `:323` is what the guard is
missing.

The symmetric hazard on the T1 exception handler is *nearly* guarded: `:357`
`if (isRunning) restartVpnAfterDelay(3000)` is a check-then-act against
`shutdownVpn`'s `isRunning = false` (`:284`), so a restart can still be scheduled if T1 reads
`isRunning` just before the store. It then hits the unconditional `startVpn()` above.

### Correctly synchronized shared state (verified, so the audit does not misreport it)

| State | Protection | Evidence |
|---|---|---|
| `queuedForegroundPackage`, `transitionJob` | `synchronized(transitionLock)` on **both** the write and the read paths | `OverlayService.kt:127`, `:305-309`, `:314-322` |
| VPN tun `outputStream` writes (T1 **and** J12 coroutines) | `synchronized(outputStream)` on all three write sites | `AdBlockVpnService.kt:468`, `:475`, `:537` |
| `pendingQueries` dedup map | `ConcurrentHashMap` + `putIfAbsent` (`:503`) + two-arg `remove(key, deferred)` (`:526`) — the correct compare-and-remove | `AdBlockVpnService.kt:105`, `:503`, `:526` |
| `DnsTrafficBus._events` | `@Synchronized` on `emit` and `clear`; list **replaced**, never mutated; `AtomicLong` id | `DnsTrafficBus.kt:24`, `:39`, `:54`, `:67` |
| `DnsResolverPool.idleSockets` / `isShutdown` | `synchronized(lock)` on acquire/release/shutdown; `@Volatile isShutdown`; plain lock chosen so `shutdown()` is callable from `onDestroy` | `DnsResolverPool.kt:26-27`, `:83`, `:110`, `:120` |
| `GrayzoneApplication.backupDirty` | `AtomicBoolean.compareAndSet(true, false)` — atomic test-and-clear, no lost flush | `GrayzoneApplication.kt:44`, `:138` |
| `BlocklistManager.loadStarted` | `AtomicBoolean.compareAndSet(false, true)` — exactly-once thread start | `BlocklistManager.kt:80`, `:144` |
| `DnsPacketHelper` counters | `AtomicInteger` throughout | `DnsPacketHelper.kt:17-24` |
| `SimpleDateFormat` / `MessageDigest` (both non-thread-safe) | `ThreadLocal.withInitial` | `DateUtils.kt:18`, `:22`; `GrayzoneBloomFilter.kt:51-53` |
| `ProtectionHealthRepository._status` | `MutableStateFlow.update { }` — atomic CAS loop | `ProtectionHealthRepository.kt:13`, `:27`, `:31`, `:35` |
| `startedActivityCount` | plain `var` but **main-thread confined** — `ActivityLifecycleCallbacks` are main-thread only | `GrayzoneApplication.kt:47`, `:111`, `:115` |
| `secondsRemaining`, `overlayView`, `windowManager`, `countdownJobUI` | plain `var`s but **main-thread confined** — every writer/reader is on `Dispatchers.Main` or a main-thread callback | `OverlayService.kt:101-104`; writes `:839`, `:1007`, `:991`, `:1035`, `:1044`, `:1004` |
| `DateUtils.cachedDateKey` / `cacheExpiry` | both `@Volatile`; a benign torn read only causes a redundant re-format, never a wrong key (`cacheExpiry` is clamped to midnight at `:58`) | `DateUtils.kt:27-32`, `:46-61` |

`OverlayService.cachedHomeLauncherPkg` / `homeLauncherCacheTime` (`:123-124`) are plain `var`s
written and read only from `isHomeLauncher` (`:542-554`), whose only caller is `hasLauncherIntent`
(`:556-557`), itself called only at `:329` — inside J1 on IO. So they are confined to a single coroutine, but consecutive
dispatches of that coroutine can land on **different IO threads**, and no `@Volatile` or lock creates
the happens-before edge explicitly. In practice the dispatcher and the `transitionLock` around the
job handoff supply it. **Worst case if it does not: a stale launcher package for up to 30 s** — the
cache is a 30 s TTL optimization, so this is Low, but it is unsynchronized by declaration.

## Cross-references for downstream tasks

- **T018 (A2 threading)**: `OverlayService.kt:531` and `:600` are on `Dispatchers.IO`, **not** the
  main thread — `lint.md` E4's severity should be reduced. The real main-thread I/O findings are
  B1/B2 (`StateBackupManager.restoreIfWiped` from `Application.onCreate` and `Activity.onResume`)
  and B3 (`shareApk` copying the APK on a click handler).
- **T019 (VPN)**: `onRevoke` is not overridden; T1 has no `join()` and its `read()` unblock path
  needs runtime verification; `restartVpnAfterDelay` (R6) can resurrect an authorized-stopped tunnel.
- **Room/data**: R1 (`UsageDatabase.getInstance` missing the inner null check) is an independent
  defect not surfaced by lint or the compiler.
- **Process split**: any finding about `ProtectionHealthRepository`, `BlocklistManager`,
  `DnsTrafficBus`, `AppMetadataCache` or `DateUtils` must state which process it applies to; none of
  them crosses the `:a11y` boundary, and `updateAccessibilityStatus` is dead code that could not work
  cross-process even if wired up.

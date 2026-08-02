# Findings â€” RU-06 `service.vpn`

**Task**: T019 | **Review unit**: RU-06 | **Reserved block**: F-020 â€¦ F-039 | **Used**: F-020 â€¦ F-038
**Contract**: [`findings.md` Â§ Required field template](./findings.md) | **Severity rubric**: FR-006 + Grayzone calibration
**Subject**: `app/src/main/java/com/grayzone/app/service/vpn/` (12 files)

**Evidence base** (read before analysis, not re-derived): [`findings.md`](./findings.md),
[`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md),
[`evidence/concurrency-inventory.md`](./evidence/concurrency-inventory.md),
[`evidence/reference-index.md`](./evidence/reference-index.md),
[`evidence/prefs-inventory.md`](./evidence/prefs-inventory.md).

**Baseline**: `AdBlockVpnService.kt`, `BlocklistManager.kt`, and `DnsTrafficBus.kt` carry uncommitted
modifications and are recorded as `working-tree @ 557a963 (uncommitted: <file>)`. The other nine files are
clean at `557a963`.

**Analysis conditions**: static only â€” no device, no emulator, no `adb`. Every runtime claim is marked
`needs-verification` with an exact procedure and the severity that follows from each outcome. Findings are
ordered Critical first, then High, Medium, Low.

**Counts**: 1 Critical, 4 High, 13 Medium, 1 Low (F-020 â€¦ F-038; F-039 left unallocated). Two of the eleven
assigned leads were split because their halves have independent fixes: lead 5 â†’ F-029 + F-030, and lead 11 â†’
F-021 + F-035. Lead 8's central question â€” *does the app guarantee no crash offline?* â€” resolves in the app's
favour, so F-032 files only the observability half of that area.

---

## Critical

### F-020 â€” `protect(socket)` failure is swallowed twice, so the resolver's own DNS re-enters the tunnel it serves while the UI still reports filtering active  [Critical]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.createResolverPool()` (lines 115â€“120), specifically line 119; consumed at `DnsResolverPool.acquireSocket()` (`DnsResolverPool.kt:91â€“102`, call at `:93`)
- **Issue**: `createResolverPool` hands `DnsResolverPool` this protect callback:

```119:119:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
        try { protect(socket) } catch (e: Exception) { /* ignore */ }
```

  Two independent failure signals are destroyed here.
  (a) **The exception path.** A comment-only catch is explicitly prohibited by Principle III. Worse, it
  defeats a fail-closed design that already exists one layer down: `acquireSocket()` calls
  `protectSocket(socket)` *inside* its own `try` (`DnsResolverPool.kt:91â€“102`), so an escaping throw would
  make `acquireSocket` return `null`, log `"Failed to create DatagramSocket"` with the throwable, and cause
  `performQuery` â†’ `performDnsQuery` â†’ `writeForwardedResponse` to drop the answer â€” a loud, diagnosable,
  no-leak outcome. Swallowing the throw at `:119` converts that designed failure into a *successful* return
  of an unprotected socket.
  (b) **The false-return path.** `VpnService.protect(DatagramSocket)` returns `Boolean`; the return value is
  discarded, so a non-throwing failure is equally invisible. This half cannot be fixed by catch-block
  hygiene alone.
  An unprotected resolver socket has its packets routed by the tunnel this very service established
  (`:207`, `addRoute("10.0.0.3", 32)` + `addRoute("fd00:â€¦:2", 128)`). Consequence chain: the socket's
  upstream queries to `8.8.8.8`/`1.1.1.1`/`9.9.9.9` (`:41â€“45`) are matched against our own routes, so the
  resolver's egress traffic loops back into the tun instead of leaving the device. `runVpnLoop` reads them,
  `isDnsQuery` accepts them, and they are forwarded again â€” either a self-feeding loop or, more commonly,
  a total DNS blackhole for every app inside the tunnel. Nothing surfaces it: the foreground notification
  still says "Grayzone Protection Active" (`:597`), `ProtectionHealthRepository.updateVpnStatus(true)` was
  already posted (`:226`), and `VpnScreen` still renders "Filtering DNS traffic". This is the exact
  "protection failing with no crash and no user notification" case the FR-006 Grayzone calibration ranks
  at or above a visible error.
- **Trigger condition**:
  1. Enable the AdBlock VPN so `onCreate` builds the pool (`:112`) and `startVpn` establishes the tunnel (`:207`).
  2. The first forwarded (non-blocked) DNS query calls `performDnsQuery` (`:552`) â†’ `pool.performQuery` â†’ `acquireSocket()` (`DnsResolverPool.kt:82`).
  3. `idleSockets` is empty, so a new `DatagramSocket` is created (`:92`) and `protectSocket(socket)` runs (`:93`).
  4. `protect()` either throws (binder failure to the VPN service, or the app is no longer the prepared VPN owner after a revoke that has not yet reached us) or returns `false` (kernel refused `SO_MARK`/`FWMARK` on the fd).
  5. Both outcomes are discarded; the unprotected socket enters the pool (`releaseSocket` â†’ `idleSockets.add`, `DnsResolverPool.kt:114`) and is reused for every subsequent query for the life of the pool.
- **Blast radius**:
  - *Callers*: `createResolverPool()` is private with two call sites â€” `AdBlockVpnService.onCreate()` (`:112`) and `AdBlockVpnService.startVpn()` (`:212`). The lambda itself is invoked only from `DnsResolverPool.acquireSocket()` (`DnsResolverPool.kt:93`), whose sole constructor call site is `AdBlockVpnService.kt:115`. `DnsResolverPool.performQuery()` has one caller, `AdBlockVpnService.performDnsQuery()` (`:552`); `performDnsQuery` has one caller, `forwardDnsQueryWithDeduplication()` (`:497`, `:515`), itself called only from `handleDnsQuery()` (`:459`), called only from `runVpnLoop()` (`:371`). The enclosing service is referenced from `AndroidManifest.xml:121â€“132`, `MainActivity.kt` (4 sites), `ui/VpnScreen.kt` (7 sites), `service/BootReceiver.kt` (6 sites), `service/vpn/VpnHoldExpiryScheduler.kt` (2 sites), plus kdoc mentions in `service/vpn/DnsQueryClassifier.kt` and `PrefsKeys.kt` (`evidence/reference-index.md:600`).
  - *Process death*: the pool is rebuilt from scratch on the next `onCreate` (`:112`), so a transient failure does not persist â€” but `VPN_ENABLED` (`:218`) plus `BootReceiver` means the service restarts automatically, and a *persistent* cause (revoked VPN consent) reproduces the same silent state on every restart with no escalation and no user-visible difference.
  - *Config change*: none â€” the service holds no `Configuration`-dependent state and is not recreated on rotation.
  - *Foreground/background*: none for the mechanism. It worsens the user impact: with the app backgrounded, the only channel that could report the failure is the notification, and the notification text is unconditional (`:597â€“603`).
  - *Cross-process*: not reachable from `:a11y`. `AdBlockVpnService` declares no `android:process` (`AndroidManifest.xml:121â€“132`) and the pool never leaves the default process.
  - *Persistence*: no direct writes. Indirect and harmful: `startVpn` persists `VPN_ENABLED = true` (`:218`) and starts the 24 h hold (`:220`) *after* `establish()` succeeds, so the app records durable "protection on" intent for a session whose DNS path may be broken.
  - *Release-only*: no. The lambda is called through a stored function reference in a live object graph; R8 cannot elide it, and no shrinking behavior alters the fix.
- **Fix** (minimal): make the callback fail loudly and let the pool's existing fail-closed path do the rest. Replace line 119 with:

```kotlin
    ) { socket ->
        if (!protect(socket)) {
            throw IllegalStateException("VpnService.protect() refused the resolver socket")
        }
    }
```

  `acquireSocket()`'s existing `catch` (`DnsResolverPool.kt:95â€“101`) then logs the throwable with context and
  returns `null`, upstream queries fail closed instead of leaking, and no new plumbing is needed. Pair it with
  one added signal so the failure is not merely logged: call
  `ProtectionHealthRepository.markVpnReconnectPending(true)` (already used at `:319`, `:336`) from the same
  branch, so `VpnScreen`'s existing "Protection interrupted â€” reconnecting" state renders instead of
  "Filtering DNS traffic". No new UI is required.
- **Rewrite justification**: not a rewrite â€” one statement replaced, reusing the pool's existing error path and the repository's existing degraded state.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` (extend), asserting that a `protectSocket` lambda which throws, and separately one that signals refusal, both cause `performQuery(...)` to return `null` and leave `idleSockets` empty (no unprotected socket pooled). Fails before the fix for the *false-return* case regardless of the catch block, which is the half no catch-hygiene change can cover.
- **Existing test conflict**: none. `DnsResolverPoolTest.kt` supplies its own `protectSocket` lambda, so tightening the production callback does not change its expectations.
- **Verification**: `needs-verification` (evidence tier `T4`) for the loop-vs-blackhole question only; the swallowed-signal defect itself is `confirmed` by reading `:119` and the `Boolean` return type of `VpnService.protect`.
  - *Missing*: a device with the VPN active and a way to force `protect()` to fail (a second VPN app taking the prepared slot mid-session, or revoking VPN consent from Settings while the tunnel is up).
  - *Procedure*: (1) install and enable Grayzone's VPN; (2) `adb shell dumpsys connectivity | grep -i vpn` to confirm the tunnel; (3) revoke VPN consent (Settings â†’ Network â†’ VPN â†’ Grayzone â†’ gear â†’ Forget) *without* stopping the service, or start a second VPN app; (4) `adb logcat -s AdBlockVpnService Grayzone` and watch for a `protect` diagnostic (there will be none before the fix); (5) resolve a domain from another app and capture with `adb shell tcpdump -i any port 53` to see whether resolver egress leaves the device or re-enters `tun0`.
  - *Severity effect*: stays **Critical** in all outcomes, because the swallowed exception plus the discarded `Boolean` already remove every signal regardless of whether the downstream symptom is a loop or a blackhole. Verification only distinguishes *which* silent failure the user gets; it cannot lower the severity.
- **Build scope**: `everyday-build`
- **Constitution**: III (silent failure), and II by consequence (protection claimed but not delivered)
- **Affected surfaces**: the discarded-`Boolean` pattern is unique to this call site â€” `protect()` appears nowhere else in `app/src`. The comment-only-catch pattern it shares is filed as C1 (`OverlayService.kt:989â€“994`), C3 (`ScheduleManager.kt:33â€“43`), and F-024 below.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `fix-pending-device-verification`

---

## High

### F-021 â€” The hold-expiry alarm stops the VPN without re-validating elapsed time, so moving the system clock forward ends the 24 h hold on demand  [High]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/VpnHoldExpiryScheduler.kt` â†’ `VpnHoldExpiryReceiver.onReceive()` (lines 82â€“94) and `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.stopVpnAfterHoldExpired()` (lines 172â€“180), reached from `onStartCommand()` (lines 131â€“134)
- **Issue**: `VpnHoldLock` is deliberately built so wall-clock tampering cannot shorten the hold â€” `remainingMs` prefers `SystemClock.elapsedRealtime()` and only falls back to wall time when it detects a reboot (`VpnHoldLock.kt:25â€“34`), and its kdoc states the intent explicitly (`:9â€“10`). Every *interactive* consumer honors that: `MainActivity.kt:129`, `BootReceiver.kt:52`, `VpnHoldExpiryScheduler.rearmFromPrefs():71`, and `onStartCommand`'s `ACTION_STOP` branch (`:141`) all gate on `isHoldActive`/`canUserDisable`. The **alarm path does not**. `arm()` schedules `AlarmManager.RTC_WAKEUP` at `VpnHoldLock.expiresAtWallMs(state)` â€” pure wall clock (`:39`, `:41`, `:75`) â€” and when it fires, `VpnHoldExpiryReceiver` performs no validation at all: it logs "VPN 24h hold expired" and dispatches `ACTION_HOLD_EXPIRED` (`:85â€“90`), which `onStartCommand` routes straight to `stopVpnAfterHoldExpired()` (`:131â€“134`), which unconditionally calls `stopVpn()`, `VpnHoldLockStore.clear()`, and `VpnHoldExpiryScheduler.cancel()` (`:173â€“175`). Because Android fires `RTC` alarms whose trigger time has moved into the past, a user who sets the device clock forward 24 h gets the alarm immediately, the VPN stops, and the hold record is deleted â€” no reboot needed, no root needed, `elapsedRealtime` untouched. For an app whose README advertises "Anti-Bypass Protection" and whose only enforcement primitive is this hold, the headline anti-bypass control has a one-setting defeat. This is protection ending early with no user-visible error, and the log line actively misreports it as a legitimate expiry.
- **Trigger condition**:
  1. Enable the VPN; `startVpn` records the hold (`:220`) and arms `RTC_WAKEUP` at `startedWallMs + 24h` (`:221` â†’ `rearmFromPrefs` â†’ `arm`).
  2. Before 24 h of real time elapses, set the system date/time forward past that instant (Settings â†’ System â†’ Date & time â†’ disable automatic, advance the date).
  3. `AlarmManager` delivers `ACTION_HOLD_EXPIRED` on the next wakeup.
  4. `onReceive` forwards it with no elapsed-time check; `stopVpnAfterHoldExpired()` stops the tunnel and clears the hold.
  5. The user restores the clock. `VPN_HOLD_STARTED_*` is gone, so a later re-enable starts a *fresh* hold â€” the original one is unrecoverable.
- **Blast radius**:
  - *Callers*: `VpnHoldExpiryReceiver` is instantiated only by the framework via the `PendingIntent` built in `VpnHoldExpiryScheduler.pendingIntent()` (`:20â€“30`) and is declared at `AndroidManifest.xml:106â€“109`. `arm()` has exactly one caller â€” `rearmFromPrefs()` (`:75`); the reference index's claim of an additional `AdBlockVpnService.kt` caller is stale (see Â§ Reference-index corrections). `rearmFromPrefs()` callers: `AdBlockVpnService.startVpn()` (`:221`), `BootReceiver.onReceive()` (`:72`), `MainActivity.kt:147`, `MainActivity.kt:168`. `cancel()` callers: `AdBlockVpnService.kt:152`, `:175`, `:304`, and `rearmFromPrefs()` (`:66`). `stopVpnAfterHoldExpired()` is private with two callers: `onStartCommand()` (`:132`) and `onStartCommand()`'s sticky-restart expiry check (`:162`).
  - *Process death*: the alarm survives process death (that is its purpose), so the bypass works with the app killed â€” the receiver cold-starts the process and stops the VPN. It does **not** survive reboot: alarms are cleared, and `BootReceiver.kt:52` re-evaluates with `isHoldActive` before clearing, so the reboot path is correctly guarded.
  - *Config change*: none.
  - *Foreground/background*: works in both. Backgrounded is the realistic case; `context.startService` at `:90` succeeds because the VPN's own foreground service puts the app in a foreground process state. With the VPN already down there is nothing to stop, and the resulting `IllegalStateException` is caught and logged (`:91â€“93`).
  - *Cross-process*: not reachable from `:a11y`; the receiver has no `android:process`.
  - *Persistence*: yes, and the destructive half. `stopVpnAfterHoldExpired` â†’ `VpnHoldLockStore.clear` removes `VPN_HOLD_STARTED_WALL` and `VPN_HOLD_STARTED_ELAPSED` (`VpnHoldLockStore.kt:36â€“41`), and `stopVpn` â†’ `shutdownVpn(clearPersistedFlag = true)` writes `VPN_ENABLED = false` (`:302`). The user's committed 24 h is erased, not merely paused.
  - *Release-only*: no. The receiver is manifest-declared and keep-ruled by AGP (`evidence/r8.md` Â§F2).
- **Fix** (minimal): re-validate in the authorized-stop path rather than in the receiver, so both alarm delivery and sticky-restart delivery are covered by one check. In `stopVpnAfterHoldExpired()` (`:172`), gate on the same predicate the interactive paths use:

```kotlin
    private fun stopVpnAfterHoldExpired() {
        val state = VpnHoldLockStore.read(this)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (VpnHoldLock.isHoldActive(state, nowWall, nowElapsed)) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.VPN,
                "Hold-expiry stop ignored â€” hold still active for " +
                    VpnHoldLock.formatRemaining(VpnHoldLock.remainingMs(state, nowWall, nowElapsed))
            )
            VpnHoldExpiryScheduler.rearmFromPrefs(this)
            return
        }
        // existing body
    }
```

  `rearmFromPrefs` re-arms at the (now future) wall-clock instant, so a clock that is later corrected still
  expires correctly. `isHoldActive` already prefers `elapsedRealtime`, so no new time source is introduced.
- **Rewrite justification**: not a rewrite â€” one guard added to an existing function, reusing `VpnHoldLock` as-is.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt` (extend) for the pure half: assert `isHoldActive(State(startedWall, startedElapsed), nowWall = startedWall + 25h, nowElapsed = startedElapsed + 1h)` is `true`, i.e. a forward wall jump with a sane elapsed clock does not expire the hold. The guard itself needs `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnHoldExpiryTest.kt` (new), driving `ACTION_HOLD_EXPIRED` into the service with a tampered wall clock and asserting the tunnel stays up and the prefs keys survive.
- **Existing test conflict**: none. `VpnHoldLockTest.kt` tests `VpnHoldLock` in isolation and never exercises the receiver or the service; the new assertion is additive.
- **Verification**: `confirmed` for the missing guard (static read of `:82â€“94`, `:131â€“134`, `:172â€“180` â€” no elapsed-time check on that path). `needs-verification` (evidence tier `T4`) only for whether `AlarmManager` fires the already-past `RTC_WAKEUP` immediately versus at the next alarm-window tick.
  - *Missing*: a device on which the system clock can be advanced (automatic time disabled).
  - *Procedure*: (1) enable the VPN, note `adb shell dumpsys alarm | grep -A3 VPN_HOLD_EXPIRED`; (2) `adb shell settings put global auto_time 0`, then set the date 2 days ahead via Settings; (3) `adb logcat -s Grayzone` and watch for "VPN 24h hold expired â€” auto-disabling"; (4) confirm the tunnel is down (`dumpsys connectivity`) and `VPN_HOLD_STARTED_WALL` is gone (`adb shell run-as com.grayzone.app cat shared_prefs/*.xml`).
  - *Severity effect*: **High** if the alarm fires promptly on the clock jump (bypass on demand). Still **High** if delivery is deferred to the next alarm window â€” the bypass is merely slower, and the missing guard also makes every deferred/duplicate delivery unsafe. Drops to **Medium** only if a device is found where `RTC_WAKEUP` alarms are *never* delivered after a forward clock change, which contradicts documented `AlarmManager` behavior.
- **Build scope**: `everyday-build`
- **Constitution**: II (anti-bypass), III (the log line reports a tamper as a normal expiry)
- **Affected surfaces**: `onStartCommand`'s sticky-restart branch (`:161â€“163`) reaches the same unguarded function, but it *does* check `isHoldActive` first, so it is already correct â€” which is exactly why the fix belongs inside `stopVpnAfterHoldExpired` rather than in the receiver. `SessionExpiryReceiver`/`SessionExpiryScheduler` (RU-04) share the wall-clock-alarm shape and should be checked for the same missing re-validation by T018.
- **Observed against**: `VpnHoldExpiryScheduler.kt` at `557a963`; `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-022 â€” `startVpn` and `shutdownVpn` are not mutually exclusive; one Main/IO interleaving wedges the service at `isRunning = true` with no tunnel, no thread, and a fresh 24 h hold  [High]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.startVpn()` (lines 182â€“252, critical span 207â€“226) against `AdBlockVpnService.shutdownVpn()` (lines 283â€“312)
- **Issue**: `startVpn` runs on the main thread from `onStartCommand` (`:146`, `:167`) **and** on `Dispatchers.IO` from `restartVpnAfterDelay` (`:323`). `shutdownVpn` runs on the main thread from `stopVpn` (`:279`) and `onDestroy` (`:338`), and on IO from `restartVpnAfterDelay` (`:321`). Neither function is `synchronized` and `startVpn`'s `if (isRunning) return` (`:183`) is a check-then-act on a `@Volatile` field â€” visibility without atomicity, the same class of defect `evidence/concurrency-inventory.md` records as R5. `@Volatile` on `isRunning`, `vpnInterface`, and `vpnThread` (`:86â€“87`, added in the uncommitted diff) is necessary and welcome but cannot make a multi-step start/stop atomic. The damaging interleaving: IO is inside `startVpn` between `establish()` (`:207`) and `isRunning = true` (`:214`) when Main runs a complete `shutdownVpn` â€” Main sets `isRunning = false`, closes and nulls the freshly established `vpnInterface` (`:287â€“289`), nulls `vpnThread`, shuts down and nulls the pool (`:297â€“298`), and (for `stopVpn`) clears `VPN_ENABLED` and the hold. IO then continues obliviously: it recreates the pool (`:211â€“213`), sets `isRunning = true` (`:214`), rewrites `VPN_ENABLED = true` (`:218`), calls `markEnabledIfAbsent` â€” which, because Main just cleared the keys, starts a **brand-new 24 h hold** (`VpnHoldLockStore.kt:29`) â€” arms a new expiry alarm (`:221`), starts the packet thread (`:223`), and posts `updateVpnStatus(true)` (`:226`). The thread immediately hits `val pfd = vpnInterface ?: return` (`:343`) and dies. Terminal state: `isRunning == true`, `vpnInterface == null`, no packet thread, a foreground notification reading "Grayzone Protection Active", `ProtectionStatus.vpnActive == true`, `VPN_ENABLED == true`, and a full 24 h hold the user did not ask for. It is also **unrecoverable in-process**: `ACTION_START` â†’ `if (vpnInterface == null) startVpn()` â†’ `if (isRunning) return` (`:183`); `ACTION_STOP` â†’ hold active â†’ "Ignoring ACTION_STOP" â†’ `if (vpnInterface == null) startVpn()` â†’ same early return (`:146`). Zero filtering, no error, and the Stop button is hidden by `buildNotification` because the hold is active (`:616`).
- **Trigger condition**:
  1. The packet loop hits its escalation path â€” five consecutive `read` errors within 5 s (`:394`), an uncaught exception on the VPN thread (`:357`), or a fatal outer throw (`:422`) â€” so `restartVpnAfterDelay` is in flight on IO.
  2. During the 2â€“3 s delay the tunnel is already down (`shutdownVpn` at `:321`) and `vpnInterface == null`.
  3. The delay elapses and IO enters `startVpn`, reaching `establish()` at `:207`.
  4. Within that window the main thread delivers a stop: the user taps Stop with the hold already elapsed (`:150`), or `onDestroy` fires (`:338`).
  5. Main completes `shutdownVpn` before IO reaches `:214`.
- **Blast radius**:
  - *Callers*: `startVpn()` is private with three call sites â€” `onStartCommand()` `:146` and `:167`, `restartVpnAfterDelay()` `:323`. `shutdownVpn()` is private with three call sites â€” `stopVpn()` `:279`, `restartVpnAfterDelay()` `:321`, `onDestroy()` `:338`. `stopVpn()` callers: `onStartCommand()` `:150`, `stopVpnAfterHoldExpired()` `:173`. `isRunning` is a public `@Volatile` companion `var` with a private setter, referenced only within the class (8 sites, `evidence/reference-index.md:608`); no external main-source reader, so the corrupted flag is not observable outside the service. Externally visible state that *is* wrong: `ProtectionHealthRepository.updateVpnStatus()` (3 sites in this file, read by `MainActivity.kt:137` and `ui/VpnScreen.kt:51`) and the `VPN_ENABLED`/`VPN_HOLD_STARTED_*` prefs (`MainActivity.kt`, `service/BootReceiver.kt`).
  - *Process death*: this is the only escape. A fresh process runs `onCreate` â†’ `onStartCommand` with `isRunning == false`, so the wedge clears â€” but nothing *causes* process death here, and `START_STICKY` does not help because the service was never killed.
  - *Config change*: none.
  - *Foreground/background*: no effect on the mechanism; backgrounded is the realistic case for the `onDestroy` variant, and it removes the last chance the user has of noticing.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: yes, and this is the worst half â€” `VPN_ENABLED = true` is rewritten (`:218`) after an authorized stop cleared it, and `markEnabledIfAbsent` (`:220`) imposes a new 24 h hold the user cannot cancel, with a matching alarm (`:221`). The prefs then contradict the user's last explicit action.
  - *Release-only*: no.
- **Fix** (minimal): make start and stop mutually exclusive and confirm intent *inside* the lock. Add a private `private val lifecycleLock = Any()` and wrap the bodies of `startVpn()` and `shutdownVpn()` in `synchronized(lifecycleLock) { â€¦ }`. Neither body suspends (both are plain functions; `restartVpnAfterDelay` already `delay`s *outside* them), so no suspension-under-lock hazard is introduced â€” the same reasoning `DnsResolverPool` documents for its own plain lock (`DnsResolverPool.kt:24â€“26`). Inside `startVpn`, after acquiring the lock, re-read intent before establishing:

```kotlin
        if (isRunning) return
        if (!getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PrefsKeys.VPN_ENABLED, false) && callerIsRestart) return
```

  The `VPN_ENABLED` re-read is the same check F-023 needs, so one guard closes both.
- **Rewrite justification**: not a rewrite â€” two `synchronized` blocks plus one re-read; no restructuring of the start/stop logic.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnLifecycleGuardTest.kt` (new), extracting the guard as a pure predicate (`shouldStart(isRunning, hasInterface, wantsVpn)`) and asserting no combination yields "start" when persisted intent is `false`. The interleaving itself needs `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnRestartRaceTest.kt` (new), driving `ACTION_STOP` concurrently with an induced restart and asserting the terminal state is never `isRunning && vpnInterface == null`.
- **Existing test conflict**: none â€” `AdBlockVpnService` has no unit tests (`evidence/reference-index.md:600`), which is why this survived.
- **Verification**: `confirmed` for the absence of mutual exclusion and for the terminal state's unrecoverability (static: `:183`, `:207â€“226`, `:283â€“312`, `:146`, `:166â€“168`). `needs-verification` (evidence tier `T4`) for how often the window is actually hit.
  - *Missing*: a device plus a way to force the restart path (five consecutive tun read errors, or `adb shell kill -9` of a peer VPN) while a stop is delivered in the same 2â€“3 s.
  - *Procedure*: (1) enable the VPN and wait out the hold (or clear `VPN_HOLD_STARTED_*` via `run-as`); (2) induce the restart path by toggling airplane mode rapidly to provoke tun read failures, watching for "VPN failed 5 times consecutively, restarting service" in `adb logcat -s Grayzone`; (3) within the following 2 s send `adb shell am start-service -a com.grayzone.app.STOP_VPN -n com.grayzone.app/.service.vpn.AdBlockVpnService`; (4) after 5 s check `dumpsys connectivity` for a tunnel, `dumpsys activity services` for the live service, and the prefs for `VPN_HOLD_STARTED_WALL`.
  - *Severity effect*: the *consequence* is Critical-grade by the FR-006 Grayzone calibration â€” protection silently absent while the UI claims it is active, plus a 24 h lock-in the user cannot cancel. It is filed **High** because the trigger needs a specific Main/IO interleaving inside a ~milliseconds-wide window that only opens while a restart is in flight. If the maintainer weights consequence over trigger probability (as the register does for C1), promote to **Critical**. If verification reproduces it, promote to **Critical** unconditionally.
- **Build scope**: `everyday-build`
- **Constitution**: II, III
- **Affected surfaces**: `evidence/concurrency-inventory.md` R5 records the same check-then-act shape; `OverlayService`'s start/stop pair should be checked by T018 for the analogous gap.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `fix-pending-device-verification`

### F-023 â€” `restartVpnAfterDelay` calls `startVpn()` unconditionally after its delay, so it can resurrect a tunnel the user deliberately stopped  [High]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.restartVpnAfterDelay()` (lines 314â€“325)
- **Issue**: the function reads `VPN_ENABLED` at `:317`, but only to decide whether to set `markVpnReconnectPending` (`:319`). The value never gates the restart: after `delay(delayMs)` (`:322`) it calls `startVpn()` (`:323`) with no re-read of intent, no cancellation check, and no `isActive` test.

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

  The only thing that currently prevents resurrection is an accident of ordering: `stopVpn` ends in
  `stopSelf()` (`:309`), whose `onDestroy` calls `serviceScope.cancel()` (`:339`), which cancels the pending
  `delay`. That is a race, not a designed safeguard â€” it depends on `onDestroy` being dispatched to the main
  thread inside the 2â€“3 s window, and the framework keeps a `VpnService` alive while it holds a binding, so
  the ordering is not statically guaranteed. When the cancel loses, `startVpn()` re-establishes the tunnel,
  rewrites `VPN_ENABLED = true` (`:218`), and â€” because `stopVpn` cleared the hold keys â€” `markEnabledIfAbsent`
  starts a **fresh 24 h hold** (`:220`, `VpnHoldLockStore.kt:29`). The user's explicit stop is reverted and
  they are locked in for another day, with the notification's Stop action hidden (`:616`). Even in the benign
  ordering, the code is one refactor away from the bad one, and `shutdownVpn(clearPersistedFlag = false)` at
  `:321` deliberately leaves the service alive during the delay so the window is real.
- **Trigger condition**:
  1. The packet loop escalates and `restartVpnAfterDelay(2000)` / `(3000)` launches (`:401`, `:357`, `:422`).
  2. Within the delay the user stops protection with the hold already elapsed: `ACTION_STOP` â†’ `canUserDisable` true (`:141`) â†’ `stopVpn()` (`:150`) â†’ `VPN_ENABLED = false`, hold cleared, alarm cancelled, `stopSelf()`.
  3. `onDestroy`'s `serviceScope.cancel()` (`:339`) does not run before the delay expires (system under load, or the framework has not yet dropped its binding to the `VpnService`).
  4. `startVpn()` runs, re-establishes the tunnel, re-persists `VPN_ENABLED = true`, and arms a new 24 h hold.
- **Blast radius**:
  - *Callers*: `restartVpnAfterDelay()` is private with three call sites â€” the VPN thread's uncaught-exception handler (`:357`), the packet-loop escalation after five consecutive errors (`:401`), and `runVpnLoop`'s outer catch (`:422`). It in turn calls `shutdownVpn()` (`:321`) and `startVpn()` (`:323`), whose call sites are enumerated in F-022. `markVpnReconnectPending()` has two call sites in this file (`:319`, `:336`) and is read through `ProtectionHealthRepository.status` by `MainActivity.kt:137` and `ui/VpnScreen.kt:51` (`evidence/reference-index.md:294`).
  - *Process death*: `serviceScope` dies with the process, so a killed process cannot resurrect the tunnel from this path â€” but `VPN_ENABLED = true` was already rewritten, so `BootReceiver`/`START_STICKY` restore it later anyway, which is the persistent half of the damage.
  - *Config change*: none.
  - *Foreground/background*: no effect on the mechanism; backgrounded means the user sees only that the VPN key icon never went away.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: yes â€” `VPN_ENABLED = true` (`:218`) and `VPN_HOLD_STARTED_WALL` / `VPN_HOLD_STARTED_ELAPSED` (`:220`) are written after the user's stop cleared them, plus a re-armed expiry alarm (`:221`).
  - *Release-only*: no.
- **Fix** (minimal): re-read intent after the delay and bail out if it is gone:

```kotlin
            kotlinx.coroutines.delay(delayMs)
            if (!prefs.getBoolean(com.grayzone.app.PrefsKeys.VPN_ENABLED, false)) {
                com.grayzone.app.GrayzoneLogger.i(
                    com.grayzone.app.LogComponent.VPN,
                    "Restart cancelled â€” VPN was disabled during the reconnect delay"
                )
                com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(false)
                return@launch
            }
            startVpn()
```

  This is the same re-read F-022's guard needs; implement once. `markVpnReconnectPending(false)` also clears
  the "Protection interrupted â€” reconnecting" state that would otherwise stick after an abandoned restart.
- **Rewrite justification**: not a rewrite â€” one guard inserted between the existing `delay` and `startVpn`.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnLifecycleGuardTest.kt` (new, shared with F-022), asserting the extracted predicate returns "do not restart" when `VPN_ENABLED` is `false` at the post-delay read even though it was `true` at the pre-delay read. `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt` already provides the prefs double.
- **Existing test conflict**: none â€” no test exercises `restartVpnAfterDelay`.
- **Verification**: `confirmed` for the missing post-delay guard (static read of `:314â€“325`). `needs-verification` (evidence tier `T4`) for whether `serviceScope.cancel()` in practice always wins the race against the 2â€“3 s delay.
  - *Missing*: a device, plus timing instrumentation on `onDestroy`.
  - *Procedure*: (1) clear the hold so Stop is permitted; (2) induce the restart path (see F-022's procedure) and confirm "restarting service" in `adb logcat -s Grayzone`; (3) immediately send `ACTION_STOP`; (4) wait 10 s and check `adb shell dumpsys connectivity | grep -i vpn` for a live tunnel and `run-as com.grayzone.app cat shared_prefs/*.xml` for `vpn_enabled` and `vpn_hold_started_wall`.
  - *Severity effect*: **High** if resurrection reproduces (an authorized stop is reverted *and* a new 24 h hold is imposed â€” user-visible but not recoverable for 24 h). Stays **High** even if `onDestroy` always wins, because the safety property is accidental, undocumented, and the `stopService = false` shutdown at `:321` keeps the window open by design; drop to **Medium** only if a maintainer adds an explicit, tested ordering guarantee instead of the guard.
- **Build scope**: `everyday-build`
- **Constitution**: II (a stop the app silently undoes), III
- **Affected surfaces**: `onDestroy` (`:327â€“340`) shares the "read `VPN_ENABLED`, then act" shape but acts immediately, so it is correct as written.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-024 â€” `vpnInterface?.close()` failure is discarded in a comment-only catch, so tunnel teardown can fail with no record anywhere  [High]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.shutdownVpn()` (lines 286â€“288, function 283â€“312)
- **Issue**: register candidate C4.

```286:289:app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* ignored */ }
        vpnInterface = null
```

  A comment-only catch â€” a Principle III violation by construction, and the second instance of the pattern in
  this file after F-020. `shutdownVpn` is the single teardown funnel for all three lifecycle exits (user stop,
  restart, destroy), so this is the one place where a failure to release the tun file descriptor could be
  detected, and it is the one place guaranteed not to report it. The reference is then nulled at `:289`,
  discarding the only handle, so nothing downstream can retry or diagnose. Additionally the *thread* half of
  teardown is unverified: `vpnThread?.interrupt()` (`:285`) cannot interrupt a thread parked in
  `FileInputStream.read` on a tun fd â€” only closing the fd can â€” and `vpnThread` is nulled at `:290` with no
  `join()` (see F-029).
- **C4 escalation verdict â€” not confirmed; **stays High**.** The proposed chain was leaked descriptor â†’
  `establish()` fails â†’ user sees "Another VPN may be active". Each link is weaker than it looks:
  (a) `ParcelFileDescriptor.close()` throwing is already rare, and when it does throw (an `IOException`
  surfacing a deferred write error) the native fd is still released by the same call â€” `close(2)` failures do
  not leave the descriptor open on Linux; (b) even with a genuinely leaked fd, `VpnService.Builder.establish()`
  does not fail *because* the app holds a stale tun fd. The framework tears down the previous tun when a new
  one is established for the same app, and the documented failure modes are "app is not the prepared VPN" and
  "another VPN took over" â€” both about the prepared-VPN slot, not fd count; (c) the misleading message is real
  but has a different and much more likely cause, which is why it is filed separately as F-036: the Toast at
  `:233â€“237` is the single blanket message for *every* exception the broad `catch (e: Exception)` at `:228`
  can see. So the message half of the chain is a genuine defect that does not depend on this catch block at
  all. **What is missing to confirm the chain**: a device demonstration that a thrown `close()` leaves
  `/proc/<pid>/fd` holding the tun descriptor *and* that a subsequent `establish()` then returns `null` or
  throws. Absent that, filing C4 as Critical would assert a mechanism I could not establish. The defect that
  *is* fully confirmed â€” a teardown failure that no log, metric, or UI state records, in the funnel every exit
  path shares â€” is High on its own terms under the FR-006 rubric.
- **Trigger condition**:
  1. The VPN is running with `vpnInterface` non-null.
  2. Any teardown path runs: `stopVpn()` (`:279`), `restartVpnAfterDelay()` (`:321`), or `onDestroy()` (`:338`).
  3. `ParcelFileDescriptor.close()` throws â€” a deferred `IOException` on the tun fd, or an `IllegalStateException` if the descriptor was already detached.
  4. The exception is discarded; `vpnInterface = null` (`:289`) drops the handle. No log entry, no `ProtectionHealthRepository` signal, no notification change.
- **Blast radius**:
  - *Callers*: `shutdownVpn()` is private with three call sites â€” `stopVpn()` (`:279`), `restartVpnAfterDelay()` (`:321`), `onDestroy()` (`:338`). `stopVpn()` is reached from `onStartCommand()` (`:150`) and `stopVpnAfterHoldExpired()` (`:173`); `stopVpnAfterHoldExpired()` from `onStartCommand()` (`:132`, `:162`), i.e. from the alarm path in F-021. `vpnInterface` is a private `@Volatile` field read at `:124`, `:146`, `:166`, `:287`, `:343`.
  - *Process death*: the kernel closes all descriptors, so a leak cannot outlive the process. This bounds the worst case and is part of why the escalation fails.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: no direct writes, but `shutdownVpn` continues past the swallowed failure to write `VPN_ENABLED = false` (`:302`) and clear the hold (`:303`) when `clearPersistedFlag` is set â€” so prefs record a clean stop even if teardown was not clean.
  - *Release-only*: no.
- **Fix** (minimal): log with context and keep going; teardown must not abort, but it must not be silent.

```kotlin
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.e(
                com.grayzone.app.LogComponent.VPN,
                "Failed to close tun interface during shutdown (clearPersistedFlag=$clearPersistedFlag)",
                e
            )
        }
```

  The `clearPersistedFlag` value identifies which of the three teardown paths was running, which is the
  detail that makes the entry diagnosable.
- **Rewrite justification**: not a rewrite â€” the catch body gains a logging call.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnTeardownLoggingTest.kt` (new), extracting the close-and-report step into a small internal helper taking a `Closeable` and a logger lambda, asserting a throwing `Closeable` produces exactly one error record carrying the throwable. This is the only way to cover it without a device, and it keeps the assertion on the behavior that matters (the failure is reported), not on the log text.
- **Existing test conflict**: none.
- **Verification**: `confirmed` for the silent catch (static). `needs-verification` (evidence tier `T4`) for the escalation chain described above.
  - *Missing*: a device, plus a way to make `ParcelFileDescriptor.close()` throw on a tun fd â€” realistically only reachable by detaching the fd first, which the app never does. This is itself evidence that the trigger is rare.
  - *Procedure*: (1) enable the VPN; (2) `adb shell ls -l /proc/$(pidof com.grayzone.app)/fd | grep tun` and record the count; (3) stop and restart the VPN 20 times via `am start-service`; (4) re-check the fd list for accumulation; (5) if any accumulates, attempt a further start and check `logcat` for `establish() returned null` plus the on-screen Toast text.
  - *Severity effect*: **Critical** only if step 4 shows accumulating tun descriptors *and* step 5 shows a failed `establish()` â€” i.e. the full chain. **High** in every other outcome, which is the current disposition. It cannot fall below High: a teardown failure with no record in the shared funnel of all three exit paths is a Principle III violation independent of any downstream symptom.
- **Build scope**: `everyday-build`
- **Constitution**: III
- **Affected surfaces**: F-020 (`:119`) is the other comment-only catch in this file; `DnsResolverPool.kt:107,112,123` (F-031) are the empty-catch variant of the same close-failure blindness. Register candidates C1 and C3 are the same pattern in other units.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `fix-pending-device-verification`

---

## Medium

### F-025 â€” `onRevoke()` is not overridden, so a revoked VPN is indistinguishable from an OS kill and is never logged or surfaced  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService` (class body, lines 20â€“623; the absent override belongs beside `onDestroy()` at lines 327â€“340)
- **Issue**: `VpnService.onRevoke()` is the framework's dedicated notification that the user or another app took the VPN slot away. `AdBlockVpnService` does not override it (confirmed by reading the full class; `evidence/reference-index.md:609` lists `onRevoke()` among the class's overrides, which is an index error â€” see Â§ Reference-index corrections). The framework default calls `stopSelf()`, so teardown *does* eventually happen through `onDestroy` â†’ `shutdownVpn` (`:338`), and `onDestroy` does set `markVpnReconnectPending(true)` when `VPN_ENABLED` is still true (`:335â€“336`) â€” so the app is not left in a wholly false state. What is lost is the ability to tell *why* protection ended. Revocation and an OS kill produce byte-identical observable state, and the app responds to both by advertising "reconnecting" and letting `START_STICKY`/`BootReceiver` retry a start that, after a revoke, cannot succeed: `establish()` returns `null` (`:207â€“208`), the broad catch fires, and the user gets the blanket "Another VPN may be active" Toast (F-037) on every retry â€” a Toast storm blaming the wrong cause. For an app whose stated posture is that protection must not fail silently, the single most important "protection was taken away" event has no hook, no log line, and no distinct UI state.
- **Trigger condition**:
  1. VPN is running with `VPN_ENABLED = true`.
  2. The user revokes consent (Settings â†’ Network â†’ VPN â†’ Grayzone â†’ Forget) or another VPN app calls `prepare()` and takes the slot.
  3. The framework calls `onRevoke()`; the default implementation calls `stopSelf()`.
  4. `onDestroy` â†’ `markVpnReconnectPending(true)` â†’ `shutdownVpn`. `VPN_ENABLED` stays `true` (`:329â€“332`, deliberately).
  5. The next `ACTION_START` from `MainActivity`, `VpnScreen`, or `BootReceiver` fails at `establish()` and shows the misleading Toast. Repeat on every app open and every boot.
- **Blast radius**:
  - *Callers*: `onRevoke()` would be called only by the Android framework (`evidence/reference-index.md:609`). The paths that observe its consequences: `onDestroy()` (framework), `shutdownVpn()` (`:338`), and the restart entry points that then fail â€” `MainActivity.kt` (4 sites referencing the service), `ui/VpnScreen.kt` (7 sites), `service/BootReceiver.kt` (6 sites) (`evidence/reference-index.md:600`).
  - *Process death*: `VPN_ENABLED = true` persists, so every subsequent process start retries and fails identically. The absence of a revoke record means no code can ever break that loop.
  - *Config change*: none.
  - *Foreground/background*: revocation typically happens with the app backgrounded, so the Toast may not even be seen â€” leaving no signal at all.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: nothing new is written, but the *absence* of a write is the defect: no persisted "consent revoked" marker exists, so the retry loop cannot be suppressed and onboarding cannot re-prompt for consent.
  - *Release-only*: no. An `onRevoke` override is a framework-called method on a manifest-declared service; AGP's generated keep rules cover it (`evidence/r8.md` Â§F2).
- **Fix** (minimal): add the override next to `onDestroy`, logging the distinct cause and marking the degraded state explicitly:

```kotlin
    override fun onRevoke() {
        com.grayzone.app.GrayzoneLogger.w(
            com.grayzone.app.LogComponent.VPN,
            "VPN consent revoked by the user or another VPN app â€” protection stopping"
        )
        com.grayzone.app.data.ProtectionHealthRepository.markVpnReconnectPending(true)
        super.onRevoke()
    }
```

  `super.onRevoke()` preserves the framework's `stopSelf()`, so teardown is unchanged. Suppressing the Toast
  storm is F-036's fix, not this one; this finding only restores the missing signal.
- **Rewrite justification**: not a rewrite â€” an added override that delegates to `super`.
- **Test to add**: `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnRevokeTest.kt` (new), asserting that after the VPN is revoked `ProtectionStatus.vpnReconnectPending` is `true` and a revoke-specific log record exists. A JVM test cannot reach `onRevoke`, which is precisely why this gap survived.
- **Existing test conflict**: none. `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` drives the repository directly and is unaffected.
- **Verification**: `confirmed` that the override is absent (full-file read). `needs-verification` (evidence tier `T4`) for the retry-loop and Toast-storm consequences.
  - *Missing*: a device on which VPN consent can be revoked while the service runs.
  - *Procedure*: (1) enable the VPN; (2) revoke consent in Settings; (3) `adb logcat -s Grayzone AdBlockVpnService` and confirm no revoke-specific entry appears (only `onDestroy`'s path); (4) reopen the app and count Toasts and `establish() returned null` entries; (5) reboot and confirm `BootReceiver` repeats the failed start.
  - *Severity effect*: **Medium** as filed â€” observability and a confusing retry loop, with teardown itself already correct via the framework default. Raise to **High** if step 4/5 shows the failed-start loop also leaves `ProtectionStatus` reporting `vpnActive == true` at any point, or if the Toast fires while the app is backgrounded and thus unattributable.
- **Build scope**: `everyday-build`
- **Constitution**: III (the app's most important stop reason has no record), II
- **Affected surfaces**: F-036 (the misleading Toast that this scenario triggers repeatedly); `evidence/concurrency-inventory.md` records the same missing-override observation.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-026 â€” The blocklist load catch was widened to `Throwable`, so an `OutOfMemoryError` silently degrades filtering to ~38 hardcoded domains while the UI reports full protection  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` â†’ `BlocklistManager.load()` (lines 143â€“160, catch at 151) and `BlocklistManager.isBlocked()` (lines 211â€“223, gate at 216)
- **Issue**: the uncommitted diff widened `catch (e: Exception)` to `catch (e: Throwable)` at `:151`. That now swallows `OutOfMemoryError` â€” the single most likely failure of this exact function, which allocates a 6 MB and a 4 MB `ByteArray` (`GrayzoneBloomFilter.kt:80`; see F-027) â€” along with every other `Error`. On failure the handler nulls both filters, resets `abusedTlds` to `EMPTY`, sets `isLoaded = false`, and resets `loadStarted` (`:152â€“156`). `isBlocked` then short-circuits at `:216` (`if (!isLoaded) return false`), so filtering silently collapses to the DoH endpoint list plus the two hardcoded fallback sets â€” 25 ad domains (`:94â€“120`) and 13 adult domains (`:122â€“136`), 38 total, against Bloom filters representing hundreds of thousands. Nothing reports the collapse: the failure is logged with `android.util.Log.e` (`:157`) rather than `GrayzoneLogger`, so it never reaches the app's own diagnostics surface; `isLoaded` has no external reader (`evidence/reference-index.md:616`); and the VPN notification and `VpnScreen` continue to report active filtering. A parent enabling adult-content blocking gets ~13 domains' worth of protection and is told it is on. Widening to `Throwable` also means an `OutOfMemoryError` raised on the loader thread is absorbed rather than surfacing as a crash â€” the app keeps running in a heap-exhausted state instead of failing loudly.
- **Trigger condition**:
  1. `AdBlockVpnService.onCreate()` calls `BlocklistManager.load(this)` (`:110`) â€” the only call site.
  2. The loader thread reads `R.raw.adblock_bloom` (6.00 MB) then `R.raw.adult_bloom` (4.00 MB) into two contiguous byte arrays (`GrayzoneBloomFilter.kt:80â€“81`).
  3. On a low-heap device, or with the process already holding Room + Compose + overlay state, either allocation throws `OutOfMemoryError`.
  4. `catch (e: Throwable)` absorbs it; `isLoaded` stays `false`; only `Log.e` records it.
  5. The VPN starts and runs normally, matching 38 domains and forwarding everything else.
- **Blast radius**:
  - *Callers*: `load()` has exactly **one** call site â€” `AdBlockVpnService.onCreate()` (`:110`). (The reference index's "2 sites" is stale; see Â§ Reference-index corrections. This confirms rather than contradicts the register's double-load non-finding.) `isBlocked()` is called only from `DnsQueryClassifier.classify()`'s default argument (`DnsQueryClassifier.kt:29`), whose sole production caller is `AdBlockVpnService.handleDnsQuery()` (`:442`). `isLoaded` is referenced only inside `BlocklistManager` (`:150`, `:155`, `:216`). `normalizeDomain()` is called from `DnsQueryClassifier.kt:30`, `AdBlockVpnService.kt:440`, and internally (`:201`, `:213`). `isDoHBypass()` from `DnsQueryClassifier.kt:28,38` and `BlocklistManager.isBlocked()` (`:212`).
  - *Process death*: `loadStarted.set(false)` at `:156` makes a retry *possible*, and since `load()` is called from `onCreate`, a stop-then-start of the service within the same process does retry. But nothing retries automatically while the service stays up, and nothing tells the user to try.
  - *Config change*: none.
  - *Foreground/background*: the load runs on a background thread from `onCreate`, so it can complete or fail with the app in either state; the user is never in a position to observe it.
  - *Cross-process*: not reachable from `:a11y`. `BlocklistManager` is an `object`, so its state is per-process and only the default process loads it (`evidence/reference-index.md:615`).
  - *Persistence*: none â€” the filters are read-only resources and nothing is written.
  - *Release-only*: no.
- **Fix** (minimal): narrow the catch back and report through the app's own logger.

```kotlin
            } catch (e: Exception) {
                adFilter = null
                adultFilter = null
                abusedTlds = AbusedTldList.EMPTY
                isLoaded = false
                loadStarted.set(false)
                com.grayzone.app.GrayzoneLogger.e(
                    com.grayzone.app.LogComponent.VPN,
                    "Blocklist load failed â€” filtering degraded to fallback domains only",
                    e
                )
            }
```

  Letting `OutOfMemoryError` propagate is the correct behavior for an unrecoverable heap failure; F-027
  addresses the allocation itself. Hardening note for the same edit: `adFilter`, `adultFilter`, and
  `abusedTlds` (`:15`, `:16`, `:20`) are not `@Volatile`. They are currently published safely only because
  every write precedes the volatile `isLoaded = true` (`:150`) and every read follows the volatile read at
  `:216` â€” a correct but undocumented piggyback that a future reordering of the `isLoaded` check would
  silently break. Add a comment stating the invariant, or mark the three fields `@Volatile`.
- **Rewrite justification**: not a rewrite â€” the catch type is reverted and one logging call replaces `Log.e`.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` (extend), asserting that with `isLoaded == false` a known Bloom-only domain is *not* blocked while a `highConfidenceAdDomains` entry still is, pinning the degraded contract so it cannot change unnoticed; plus an assertion that a failing load reports through the injected logger.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` and `app/src/test/java/com/grayzone/app/service/vpn/BlocklistDecisionTest.kt` exercise `isBlocked` and may assume a loaded state; both need to declare which state they run in once the degraded contract is asserted explicitly. No behavioral change is required of them.
- **Verification**: `confirmed` for the widened catch, the degraded matching set, and the missing user signal (static reads of `:143â€“160`, `:211â€“223`, `:94â€“136`). `needs-verification` (evidence tier `T4`) for whether the 6 MB allocation actually OOMs on real low-end devices.
  - *Missing*: a low-RAM device or an emulator configured with a small heap (`ro.dalvik.vm.heapgrowthlimit` â‰ˆ 96â€“128 MB).
  - *Procedure*: (1) launch an emulator with a 512 MB RAM profile; (2) open the app, browse to grow the Compose/Room footprint, then enable the VPN; (3) `adb logcat | grep -E "OutOfMemory|Error loading bloom filters"`; (4) with the VPN reported active, resolve a domain present only in the Bloom filter (not in the 38 fallbacks) and confirm from `DnsTrafficBus`/`VpnScreen` whether it is ALLOWED.
  - *Severity effect*: **Medium** as filed (silent degradation, narrow trigger). Escalate to **High** if step 4 shows filtering degraded while the UI reports active on a device configuration the app supports (`minSdk = 29` implies such devices are in scope); escalate to **Critical** if the degraded state also survives a service restart, since protection would then be permanently absent with no signal.
- **Build scope**: `everyday-build`
- **Constitution**: III (widened catch, wrong logger, no user signal), II
- **Affected surfaces**: `evidence/prior-fix-watchlist.md` pairs this with the packet-loop widening filed as F-028. The `Log.e`-instead-of-`GrayzoneLogger` inconsistency also appears at `AdBlockVpnService.kt:229` and `:280`.
- **Observed against**: `working-tree @ 557a963 (uncommitted: BlocklistManager.kt)`
- **Disposition**: `verified`

### F-027 â€” ~10 MB of Bloom filters are held for the whole process lifetime with no memory-pressure release and no `onTrimMemory` handler anywhere in the app  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` â†’ fields `adFilter` / `adultFilter` (lines 15â€“16), populated by `load()` (lines 143â€“160); allocation at `app/src/main/java/com/grayzone/app/service/vpn/GrayzoneBloomFilter.kt` â†’ `GrayzoneBloomFilter.readFrom()` (line 80)
- **Issue**: peak transient allocation and steady state are the same, which is the good news â€” `readFrom` allocates exactly one `ByteArray(byteCount)` and streams the resource into it with `readFully` (`:80â€“81`), so there is no intermediate `readBytes()` copy and no doubling (credited below). The defect is retention. `res/raw/adblock_bloom.bin` is 6.00 MB and `res/raw/adult_bloom.bin` 4.00 MB, so the two arrays are ~10 MB of contiguous large-object-space allocation, loaded sequentially (peak 6 MB, then 6 + 4 = 10 MB resident). They are held by `BlocklistManager`, a Kotlin `object`, so they live as long as the process â€” `shutdownVpn` (`:283â€“312`) never releases them, meaning a user who stops the VPN and keeps browsing the app's own UI still carries 10 MB of dead filter data in the same process that runs Compose, Room, and `OverlayService`. There is **no** `onTrimMemory`, `onLowMemory`, or `registerComponentCallbacks` implementation anywhere in `app/src` (verified by search: zero matches), so the app never surrenders this memory under pressure and gives the OS no cheaper option than killing the process â€” which for this app means killing protection. `AndroidManifest.xml` does not set `android:largeHeap`, so the default per-app heap limit applies. Secondary robustness note: `MAX_FILTER_BYTES = 32 MB` (`GrayzoneBloomFilter.kt:48`) is validated *before* the array is allocated (`:76â€“80`), so a corrupt header claiming 32 MB would allocate 32 MB before `readFully` failed; the resources ship inside the signed APK so this is not attacker-controlled, but the cap is 3Ã— larger than any shipped filter and could be tightened.
- **Trigger condition**:
  1. Enable the VPN once; `onCreate` â†’ `load()` populates both filters (`:147â€“148`).
  2. Stop the VPN. `shutdownVpn` releases the tun fd, the pool, and the traffic bus, but not the filters.
  3. Continue using the app, or leave it backgrounded.
  4. Memory pressure arrives (another app in the foreground, a camera session). The framework calls `onTrimMemory` on every component â€” no implementation exists, so nothing is released.
  5. The OS chooses to kill the process instead. `VPN_ENABLED = true` plus `START_STICKY`/`BootReceiver` restart it, and the 10 MB is loaded again.
- **Blast radius**:
  - *Callers*: `adFilter`/`adultFilter` are private, written in `load()` (`:147â€“148`, `:152â€“153`) and read in `isBlocked()` (`:219â€“222`). `GrayzoneBloomFilter.readFrom()` has one caller, `BlocklistManager.loadFilter()` (`:229`); `mightContain()` one caller, `BlocklistManager.matchesFilter()` (`:258`) via `DomainZoneMatcher.anyMatch()`. `load()`'s single call site is `AdBlockVpnService.onCreate()` (`:110`). The retained memory affects every component in the default process: `MainActivity`, `OverlayService`, `UsageDatabase`, and `AdBlockVpnService` itself.
  - *Process death*: the only mechanism that currently reclaims the memory â€” i.e. the app relies on being killed, which is the outcome it least wants.
  - *Config change*: none directly; a rotation-heavy session inflates the Compose footprint against which this 10 MB competes.
  - *Foreground/background*: backgrounded is where it matters. A background process holding 10 MB it will not release is a prime LMK candidate, and `OverlayService`'s lockout enforcement dies with it.
  - *Cross-process*: not reachable from `:a11y`, and correctly so â€” `object` statics are per-process and only the default process loads them (`evidence/reference-index.md:615`). A future move of blocking logic into `:a11y` would duplicate all 10 MB.
  - *Persistence*: none â€” read-only resources, no writes.
  - *Release-only*: no. R8 cannot shrink `res/raw` binaries, and both are referenced by `R.raw` constants in live code.
- **Fix** (minimal): add a memory-pressure release path and one release call on VPN stop. In `BlocklistManager`, add:

```kotlin
    /** Release ~10 MB of filters under memory pressure; load() re-reads them on demand. */
    fun release() {
        adFilter = null
        adultFilter = null
        abusedTlds = AbusedTldList.EMPTY
        isLoaded = false
        loadStarted.set(false)
    }
```

  Call it from a new `GrayzoneApplication.onTrimMemory(level)` when `level >= TRIM_MEMORY_RUNNING_LOW`, and
  from `shutdownVpn` when `clearPersistedFlag` is true (the user-stopped case, where the filters are certainly
  not needed). `load()` already guards re-entry with `loadStarted` and is idempotent, so re-loading after a
  release is safe. Note the ordering requirement: `release()` must null the filters *before* clearing
  `isLoaded` only if the piggyback publication in F-026 is left as-is â€” safest is to set `isLoaded = false`
  first so readers gated at `:216` never see nulled filters through a stale `true`.
- **Rewrite justification**: not a rewrite â€” one new function plus two call sites; the loader and filter format are untouched.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/BloomFilterTest.kt` (extend), asserting `release()` then `load()` restores matching for a domain the filter contains, i.e. release is reversible and `loadStarted` does not wedge. Peak-RSS behavior needs `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/BlocklistMemoryTest.kt` (new), sampling `Debug.getMemoryInfo` before load, after load, and after `release()`.
- **Existing test conflict**: none. `BloomFilterTest.kt` builds filters from fixtures and does not depend on `BlocklistManager`'s retention.
- **Verification**: `confirmed` for retention, for the single-allocation load path, and for the total absence of memory-pressure callbacks (search across `app/src`: zero matches). `needs-verification` (evidence tier `T4`) for actual peak RSS and for whether the 6 MB contiguous allocation fails on supported low-end devices.
  - *Missing*: a device or emulator with a constrained heap, plus `dumpsys meminfo` sampling.
  - *Procedure*: (1) `adb shell dumpsys meminfo com.grayzone.app` before enabling the VPN; (2) enable it, wait for "Loaded ads bloom filter" / "Loaded adult bloom filter" in `logcat`; (3) sample `meminfo` again and compare Java heap and total PSS; (4) stop the VPN and sample a third time to confirm the 10 MB is still resident; (5) `adb shell am send-trim-memory com.grayzone.app RUNNING_CRITICAL` and sample a fourth time to confirm nothing is released.
  - *Severity effect*: **Medium** as filed. Escalate to **High** if step 5 combined with LMK logs (`adb logcat -b events | grep am_kill`) shows the process being killed under pressure it could have survived by releasing these 10 MB â€” that turns a memory-hygiene issue into a protection-availability issue. Drops to **Low** if measured total PSS growth is well under 10 MB (i.e. the arrays are being paged from the APK rather than copied), which the streaming read into a heap array makes unlikely.
- **Build scope**: `everyday-build`
- **Constitution**: II (availability of protection under pressure), VI
- **Affected surfaces**: `GrayzoneApplication` is the natural host for `onTrimMemory` and currently has none; `OverlayService`'s retained bitmaps/views (RU-05) would benefit from the same hook, so T023 should be consulted before the callback is added in two places.
- **Observed against**: `working-tree @ 557a963 (uncommitted: BlocklistManager.kt)`; `GrayzoneBloomFilter.kt` at `557a963`
- **Disposition**: `verified`

### F-028 â€” The packet-loop catch was widened from `IOException` to `Exception`, folding programming errors into the retry-and-backoff path, and the diff deleted the comment documenting the fd/read contract  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.runVpnLoop()` (lines 342â€“425; inner catch at 379, outer catch at 415)
- **Issue**: the uncommitted diff widened the inner per-iteration catch from `java.io.IOException` to `Exception` (`:379`). The loop's error handling is designed for *transient I/O*: count consecutive failures, back off 100â†’1600 ms, and restart the service after five (`:380â€“412`). Widening the type routes deterministic programming errors through that machinery. Concretely, everything inside the `try` is now covered: `DnsPacketHelper.isDnsQuery` (`:366`), `getDomainName` (`:367`), `packet.copyOf` (`:370`), and `handleDnsQuery` (`:371`) â€” which reaches `DnsQueryClassifier.classify`, `BlocklistManager.isBlocked`, and the synchronous `writeNxDomain`/`writeSinkhole` paths (`:446`, `:452`). An `ArrayIndexOutOfBoundsException` or `IllegalStateException` from any of those is now indistinguishable from a tun read failure: it is logged at `w` with only `e.message` and no throwable (`:383â€“386`), retried five times, and then escalated to a full VPN restart (`:401`) that cannot possibly fix a logic bug â€” producing a restart loop with no stack trace anywhere. Note the asymmetry: the *outer* catch (`:415`) logs the throwable properly, and the loop's `d`/`e` calls elsewhere do too; only the inner hot-path handler drops it, and that is the one that fires per packet. Separately, the same diff removed the comment block that documented why `FileInputStream`/`FileOutputStream` construction sits where it does and how closing the tun fd is expected to unblock a parked `read` â€” the exact invariant F-029 needs verified. Deleting the only written record of a subtle contract while widening the catch that hides its violations compounds the risk.
- **Trigger condition**:
  1. A malformed or unusual DNS packet, or any latent bug in the parse/classify/write path, raises a non-`IOException` inside the loop body (`:362â€“378`).
  2. The widened catch absorbs it and logs `"VPN loop error #N: <message>"` with no throwable (`:383â€“386`).
  3. Because the cause is deterministic, the next packet reproduces it; `consecutiveErrors` reaches 5 within 5 s (`:389â€“394`).
  4. `restartVpnAfterDelay(2000)` runs (`:401`) and `break` exits the loop.
  5. The restart re-establishes the tunnel and the same packet class reproduces the fault â€” a restart loop, each cycle briefly dropping protection, with no diagnosable evidence.
- **Blast radius**:
  - *Callers*: `runVpnLoop()` is private with one call site â€” the raw thread created in `startVpn()` (`:223`). Everything it can catch: `DnsPacketHelper.isDnsQuery()`, `getDomainName()` (both called only from here, `evidence/reference-index.md:630,634`), and `handleDnsQuery()` â†’ `DnsQueryClassifier.classify()` (one caller, `:442`) â†’ `BlocklistManager.isBlocked()` (one production caller, `DnsQueryClassifier.kt:29`). `restartVpnAfterDelay()` call sites: `:357`, `:401`, `:422`.
  - *Process death*: no persistence, so a restart loop continues indefinitely until the process dies for another reason.
  - *Config change*: none.
  - *Foreground/background*: no effect on the mechanism; backgrounded means the repeated protection gaps are invisible.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none directly. `restartVpnAfterDelay` uses `clearPersistedFlag = false` (`:321`), so prefs are untouched â€” correct.
  - *Release-only*: no, but relevant: R8 strips line numbers unless `-keepattributes SourceFile,LineNumberTable` is set, so a message-only log entry is even less diagnosable in release. Worth checking against `evidence/r8.md` when this is fixed.
- **Fix** (minimal): keep the widened catch (a broad catch on the packet thread is defensible â€” an escaping exception kills protection entirely), but make it diagnosable and stop treating logic errors as transient:

```kotlin
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    consecutiveErrors++
                    com.grayzone.app.GrayzoneLogger.w(
                        com.grayzone.app.LogComponent.VPN,
                        "VPN loop error #$consecutiveErrors (${e.javaClass.simpleName})",
                        e
                    )
                    if (e !is java.io.IOException) {
                        // Not transient: a restart cannot fix it, so do not spend the retry budget.
                        consecutiveErrors = maxConsecutiveErrors
                    }
                    â€¦
```

  Passing `e` restores the stack trace; the type test keeps the backoff for genuine I/O and escalates
  non-I/O faults immediately instead of five packets later. Also restore the deleted comment documenting the
  fd-close/read-unblock contract.
- **Rewrite justification**: not a rewrite â€” the throwable is added to an existing log call and one branch is inserted.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnLoopErrorPolicyTest.kt` (new), extracting the classify-and-count step into a pure function `nextErrorState(previous, throwable)` and asserting an `IOException` consumes one retry with backoff while a `RuntimeException` escalates immediately. This is testable without a tun fd, which is what makes the extraction worthwhile.
- **Existing test conflict**: none.
- **Verification**: `confirmed` for the widened type, the dropped throwable, and the deleted comment (working-tree read of `:379â€“412`, diff against `557a963`). `needs-verification` (evidence tier `T4`) for whether any real packet actually raises a non-`IOException` here â€” see F-034 for the one concrete candidate, and note that `getDomainName` and the response builders already catch internally (`DnsPacketHelper.kt:147`, `:217`, `:295`, `:335`, `:385`), so the realistic escape route is a bounds error in `isDnsQuery` (`:39â€“45`), which indexes `packet[udpOffset + 2]` after only a `length < 28` check.
  - *Missing*: a device plus a crafted-packet injection path (a local UDP sender aimed at the tun DNS address).
  - *Procedure*: (1) enable the VPN; (2) from an on-device shell, send truncated and malformed UDP datagrams to `10.0.0.3:53` and `[fd00:â€¦:2]:53` with `nc`/a small script, including a 28-byte packet whose IHL claims 15 and an IPv6 packet with a hop-by-hop header; (3) `adb logcat -s Grayzone` and watch for "VPN loop error" entries and whether five accumulate into "restarting service"; (4) confirm whether the tunnel drops.
  - *Severity effect*: **Medium** as filed (diagnosability plus a wrong retry policy). Escalate to **High** if step 3 shows a reproducible packet that reaches five consecutive errors and drops the tunnel â€” that is remote-ish input causing repeated protection loss. Drops to **Low** if verification shows no non-`IOException` is reachable, leaving only the missing throwable in the log.
- **Build scope**: `everyday-build`
- **Constitution**: III (a log too thin to diagnose, per the register's `logged-bare` class)
- **Affected surfaces**: F-026 is the sibling widening (`Exception` â†’ `Throwable`) from the same diff; `evidence/prior-fix-watchlist.md` tracks both. The `e.message`-only logging shape also appears at `DnsResolverPool.kt:71â€“74`.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-029 â€” The VPN packet thread is unnamed, uncancellable, and never joined; teardown assumes closing the tun fd unblocks a parked `read()` and nothing verifies it  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.startVpn()` (lines 223â€“224) and `AdBlockVpnService.shutdownVpn()` (lines 285â€“290)
- **Issue**: `vpnThread = Thread { runVpnLoop() }` (`:223`) creates a raw thread that no `Job` can cancel â€” the T1 entry in `evidence/concurrency-inventory.md`. Teardown does `vpnThread?.interrupt()` (`:285`), which cannot unblock a thread parked in `FileInputStream.read` on a tun fd (interruption only affects `InterruptibleChannel`, not `FileInputStream`), then closes the fd (`:287`) and nulls the handle (`:290`) with **no `join()`**. So `shutdownVpn` returns while the old thread may still be inside `read`, and once it returns from `read` it evaluates `while (isRunning)` (`:361`) â€” now `false` â€” and exits. That is *probably* fine, but three consequences follow from having no join and no name:
  (a) During a restart, the old thread can still be alive when `startVpn` creates the new one (`:223`), so two loops can briefly share `handleDnsQuery` and the `outputStream` writes. The writes themselves are safe â€” every write is `synchronized(outputStream)` (`:468`, `:475`, `:537`) â€” but the old thread holds a `FileOutputStream` over a closed fd, so its writes throw and are counted as loop errors against the *new* tunnel's error budget only if they share state; they do not, so the practical effect is spurious log noise plus one wasted thread.
  (b) If closing the fd does **not** unblock `read` on some device or ART version, the old thread leaks permanently, holding a `FileInputStream`, a 32767-byte buffer (`:360`), and an implicit reference to the service â€” one leaked thread and ~32 KB per restart cycle, and the restart path is automatic (`:357`, `:401`, `:422`).
  (c) The thread is anonymous (no `name`, no priority), so it appears in ANR traces and `dumpsys` as `Thread-N`. For the single most important thread in the app â€” the one whose stall means no DNS filtering â€” that materially impedes exactly the diagnosis this audit is trying to enable.
- **Trigger condition**:
  1. VPN running; the packet thread is blocked in `inputStream.read(packet)` (`:363`) â€” its normal state.
  2. Any teardown or restart path calls `shutdownVpn` (`:279`, `:321`, `:338`).
  3. `interrupt()` (`:285`) has no effect on the parked `read`; the fd is closed (`:287`); `vpnThread = null` (`:290`) drops the only handle before anyone can join it.
  4. `shutdownVpn` returns and, on the restart path, `startVpn` starts a second thread (`:223`) 2â€“3 s later.
  5. Whether the first thread ever left `read` is unobservable â€” no join, no name, no log on loop exit.
- **Blast radius**:
  - *Callers*: the thread body `runVpnLoop()` is private with one call site (`:223`, inside `startVpn`). `vpnThread` is a private `@Volatile` field touched at `:87`, `:223`, `:224`, `:285`, `:290`. `startVpn()` call sites: `onStartCommand()` `:146`, `:167`, `restartVpnAfterDelay()` `:323`. `shutdownVpn()` call sites: `stopVpn()` `:279`, `restartVpnAfterDelay()` `:321`, `onDestroy()` `:338`.
  - *Process death*: the only guaranteed cleanup for a leaked thread. `START_STICKY` plus `VPN_ENABLED` then restarts the service, so a leak repeats rather than resolving.
  - *Config change*: none.
  - *Foreground/background*: none for the mechanism. A leaked thread raises the process's cost while backgrounded, feeding F-027's kill risk.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none.
  - *Release-only*: no. Note that with R8's default obfuscation an anonymous thread's stack frames are also renamed, compounding (c).
- **Fix** (minimal): name the thread and join it with a bounded timeout before dropping the handle.

```kotlin
            vpnThread = Thread({ runVpnLoop() }, "grayzone-vpn-packet-loop")
            vpnThread?.start()
```

```kotlin
        val thread = vpnThread
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) { /* see F-024 fix */ }
        thread?.join(1000)
        if (thread?.isAlive == true) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.VPN,
                "VPN packet thread still alive 1s after tun close â€” read() did not unblock"
            )
        }
        vpnInterface = null
        vpnThread = null
```

  Closing the fd *before* joining is the ordering that makes the join terminate; the warning converts the
  unverified assumption in (b) into a signal that shows up in the field. `shutdownVpn` runs on Main from two
  of its three call sites, so the join must stay bounded â€” 1 s is well inside the ANR budget and only elapses
  in the failure case this log is meant to catch.
- **Rewrite justification**: not a rewrite â€” a thread name, a bounded join, and one diagnostic.
- **Test to add**: `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/VpnThreadTeardownTest.kt` (new), asserting that after a stop no thread named `grayzone-vpn-packet-loop` remains in `Thread.getAllStackTraces()`. The naming is what makes this assertable at all, which is part of the fix's value.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier `T4`) â€” this is the lead's explicitly flagged unknown.
  - *Missing*: a device, plus thread-state observation across restart cycles.
  - *Procedure*: (1) enable the VPN; (2) `adb shell ps -T -p $(pidof com.grayzone.app)` and record the thread count; (3) stop and restart the VPN 20 times; (4) re-run the thread listing and compare â€” an increase of one per cycle proves `read` does not unblock; (5) `adb shell kill -3 $(pidof com.grayzone.app)` and inspect `/data/anr/traces.txt` for threads parked in `read` on a tun fd.
  - *Severity effect*: **Medium** as filed. Escalate to **High** if step 4 shows accumulation, since automatic restarts would then leak a thread and 32 KB per cycle indefinitely and eventually exhaust the process. Drops to **Low** if no accumulation occurs, leaving only the naming and the unverified-assumption-without-a-signal problem.
- **Build scope**: `everyday-build`
- **Constitution**: III (an assumed invariant with no failure signal), VI
- **Affected surfaces**: F-030 is the sibling raw thread. `evidence/concurrency-inventory.md` T1 records this thread; the uncommitted diff's addition of `Thread.currentThread().setUncaughtExceptionHandler` (`:351â€“358`) partially mitigates the "thread dies silently" half and is credited below.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-030 â€” The blocklist loader thread is started from an `object` with no handle, cannot be cancelled, and holds a `Context` past the service that started it  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` â†’ `BlocklistManager.load()` (lines 143â€“160, thread at 145)
- **Issue**: `Thread { â€¦ }.start()` (`:145`) with the reference discarded â€” the T2 entry in `evidence/concurrency-inventory.md`. Because `BlocklistManager` is an `object`, there is no owner to hold the handle, so there is no join, no interrupt, and no way to observe completion. The thread captures the `context` parameter (`:143`), which is the `AdBlockVpnService` instance (`AdBlockVpnService.kt:110` passes `this`), and uses it at `:147â€“149` via `context.resources`. It therefore keeps the `Service` reachable for the duration of a ~10 MB two-file read (F-027). If the service is destroyed during that window â€” a start immediately followed by a stop, or an OS kill â€” the loader keeps running and keeps the destroyed `Service` alive until it finishes; `shutdownVpn` (`AdBlockVpnService.kt:283â€“312`) has no hook to stop it. Nothing cancels the work when its result is no longer needed, and nothing reports that it finished, so a caller cannot distinguish "still loading" from "failed" (F-026) â€” `isLoaded == false` means both.
- **Trigger condition**:
  1. `AdBlockVpnService.onCreate()` calls `load(this)` (`:110`), starting the loader with a reference to the service.
  2. The user stops the VPN within the load window (~10 MB of resource reads), or the OS kills the service.
  3. `onDestroy` â†’ `shutdownVpn` runs; the loader is untouched and keeps reading, holding the destroyed `Service`.
  4. The loader completes and publishes filters into the `object`, where they are retained for the process lifetime (F-027) even though no VPN is running.
- **Blast radius**:
  - *Callers*: `load()` has exactly one call site â€” `AdBlockVpnService.onCreate()` (`:110`). Inside the thread: `loadFilter()` (`:147`, `:148`) â†’ `GrayzoneBloomFilter.readFrom()` (`:229`); `loadAbusedTlds()` (`:149`) â†’ `AbusedTldList.readFrom()` (`:236`). The fields it publishes are read by `isBlocked()` (`:216â€“222`), whose only production caller is `DnsQueryClassifier.classify()` (`DnsQueryClassifier.kt:29`) from `AdBlockVpnService.handleDnsQuery()` (`:442`).
  - *Process death*: kills the thread mid-read; the next `onCreate` starts a fresh one. `loadStarted` (`:80`) lives in the `object`, so within a surviving process a second `load()` during an in-flight load is correctly a no-op (`:144`) â€” the one concurrency control here, and it works.
  - *Config change*: none.
  - *Foreground/background*: the load can straddle either state; there is no user-visible signal in either.
  - *Cross-process*: not reachable from `:a11y`. Per-process `object` state means only the default process ever runs this thread.
  - *Persistence*: none â€” read-only resource reads.
  - *Release-only*: no.
- **Fix** (minimal): use the application context and give the thread a name, so the `Service` is not retained and the thread is identifiable. At `AdBlockVpnService.kt:110` pass `applicationContext` instead of `this`, and at `BlocklistManager.kt:145`:

```kotlin
        Thread({
            // existing body
        }, "grayzone-blocklist-loader").start()
```

  Switching to `applicationContext` removes the leak without introducing cancellation machinery: the work is
  short, idempotent, and its result is cached in a process-scoped `object`, so cancelling it has little value
  while holding a `Service` for its duration has real cost. If the maintainer wants cancellation, the correct
  shape is to move the load onto `GrayzoneApplication`'s scope rather than to add a `Job` to an `object` â€” but
  that is a larger change than this finding requires.
- **Rewrite justification**: not a rewrite â€” one argument changed at the call site plus a thread name.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` (extend), asserting a second `load()` while the first is in flight does not start a second read (drive `loadStarted` through the public API and count resource opens with a fake `Context`), pinning the one guarantee this code does provide. The leak itself needs `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/service/vpn/BlocklistLoaderLeakTest.kt` (new), asserting the service is weakly reachable after `onDestroy` while a load is in flight.
- **Existing test conflict**: none.
- **Verification**: `confirmed` for the missing handle, the captured `Service` context, and the absent cancellation (static reads of `:143â€“160`, `AdBlockVpnService.kt:110`). `needs-verification` (evidence tier `T4`) for how long the retention window actually is.
  - *Missing*: a device, plus heap-dump timing.
  - *Procedure*: (1) enable then immediately disable the VPN (within ~200 ms, scripted via two `am start-service` calls); (2) `adb shell am dumpheap com.grayzone.app /data/local/tmp/h.hprof` right after; (3) inspect for a reachable `AdBlockVpnService` instance held by a `Thread` frame; (4) repeat 20 times and confirm no accumulation (each loader should finish and release).
  - *Severity effect*: **Medium** as filed. Drops to **Low** if the measured window is a few hundred milliseconds and no instance accumulates â€” the fix is cheap either way. Escalate to **High** only if a destroyed `Service` is found retained indefinitely, which would require the loader to block, and its only blocking calls are bounded resource reads.
- **Build scope**: `everyday-build`
- **Constitution**: VI, III (no completion or failure signal)
- **Affected surfaces**: F-029 is the sibling raw thread; `evidence/concurrency-inventory.md` T2 records this one.
- **Observed against**: `working-tree @ 557a963 (uncommitted: BlocklistManager.kt, AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-031 â€” Three empty socket-close catches in the resolver pool discard every UDP descriptor-release failure  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/DnsResolverPool.kt` â†’ `DnsResolverPool.releaseSocket()` (lines 105â€“117, catches at 107 and 112) and `DnsResolverPool.shutdown()` (lines 119â€“127, catch at 123)
- **Issue**: three catches that discard the exception entirely â€” `try { socket.close() } catch (_: Exception) {}` at `:107` (non-reusable socket) and `:112` (pool already shut down), and `try { it.close() } catch (e: Exception) {}` at `:123` (draining `idleSockets`). The `_` at `:107`/`:112` discards by construction; `:123` binds `e` and then ignores it, which additionally trips the compiler's unused-variable analysis. All three are on the descriptor-release path of a pool whose own kdoc says its purpose is "to prevent File Descriptor exhaustion" (`:14â€“15`) â€” so the class's stated reason for existing is guarded by three handlers that cannot report the failure it is designed to avoid. Every DNS query that ends non-reusably passes through `:107` (`reusable = false` is set by the `catch` at `:67â€“75`, i.e. exactly the error path), so under sustained upstream failure this is the hot path, and a socket that fails to close leaks silently up to the process fd limit. `DatagramSocket.close()` is documented not to throw `IOException`, which is presumably the reasoning â€” but the catches are on `Exception`, so they also absorb `SecurityException`, `NullPointerException`, and anything a future refactor introduces.
- **Trigger condition**:
  1. VPN running; DNS queries flow through `performQuery` (`:33`).
  2. A query fails in a way that sets `reusable = false` (`:70`) â€” any exception from the send/receive block, which offline conditions produce steadily.
  3. `releaseSocket(socket, false)` (`:77`) reaches `:107` and calls `close()`.
  4. `close()` throws; the exception is discarded; the socket may remain open.
  5. Repeats per failed query. With `maxConcurrentSockets = 20` (`AdBlockVpnService.kt:116`) the pool bounds *concurrent* sockets, not leaked ones, so accumulation is unbounded.
- **Blast radius**:
  - *Callers*: `releaseSocket()` is private with one call site â€” the `finally` in `performQuery()` (`:77`). `shutdown()` has one verified call site â€” `AdBlockVpnService.shutdownVpn()` (`:297`); the reference index's "3 sites" is stale (see Â§ Reference-index corrections). `performQuery()` has one caller â€” `AdBlockVpnService.performDnsQuery()` (`:552`). The pool is constructed only at `AdBlockVpnService.kt:115` via `createResolverPool()`, called from `onCreate()` (`:112`) and `startVpn()` (`:212`).
  - *Process death*: the kernel reclaims all descriptors, bounding the worst case to one process lifetime â€” but the VPN service is long-lived by design, so that bound is weak.
  - *Config change*: none.
  - *Foreground/background*: none for the mechanism; a background VPN accumulating leaked fds is the realistic case.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): one shared, logging close helper used by all three sites.

```kotlin
    private fun closeQuietly(socket: DatagramSocket, reason: String) {
        try {
            socket.close()
        } catch (e: Exception) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.DNS,
                "Failed to close DNS socket ($reason): ${e.message}"
            )
        }
    }
```

  Call it as `closeQuietly(socket, "non-reusable")` (`:107`), `closeQuietly(socket, "pool-shutdown")` (`:112`),
  and `closeQuietly(it, "drain")` (`:123`). `w` rather than `e` is right here: a close failure degrades but
  does not break the current query. This also removes the unused-variable warning at `:123`.
- **Rewrite justification**: not a rewrite â€” three call sites redirected to one new private helper.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` (extend), using a `DatagramSocket` subclass whose `close()` throws, asserting `performQuery` still returns normally, the socket is not returned to `idleSockets`, and exactly one warning is reported through an injected logger.
- **Existing test conflict**: none. `DnsResolverPoolTest.kt` already constructs pools with custom lambdas; adding a throwing-close double does not alter existing cases.
- **Verification**: `confirmed` (static) â€” three empty catches on the close path, plus the discarded binding at `:123`. No device needed for the defect; only the leak *rate* would need measurement.
  - *Missing*: a device, for fd-accumulation measurement only.
  - *Procedure*: (1) enable the VPN; (2) `adb shell ls /proc/$(pidof com.grayzone.app)/fd | wc -l`; (3) force sustained upstream failure (airplane mode on with Wi-Fi off) while an app retries DNS for 5 minutes; (4) re-count descriptors; (5) restore connectivity and confirm the count returns to baseline.
  - *Severity effect*: **Medium** as filed (Principle III violation on the class's stated purpose). Escalate to **High** if step 4 shows monotonic growth, since fd exhaustion would eventually break all DNS and thus all filtering. Cannot drop below **Medium**: three discard-by-construction catches on a resource-release path violate Principle III regardless of the measured leak rate.
- **Build scope**: `everyday-build`
- **Constitution**: III
- **Affected surfaces**: `evidence/error-handling-inventory.md` M1 records these three as `SILENT`. The same close-failure blindness appears at `AdBlockVpnService.kt:287` (F-024).
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-032 â€” Upstream resolver failure and mid-resolution connectivity loss are entirely invisible, and the live traffic feed labels failed queries `ALLOWED`  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.handleDnsQuery()` (line 457), `AdBlockVpnService.writeForwardedResponse()` (lines 530â€“538, early return at 535), `AdBlockVpnService.performDnsQuery()` (lines 544â€“573); with `app/src/main/java/com/grayzone/app/service/vpn/DnsResolverPool.kt` â†’ `DnsResolverPool.performQuery()` (lines 33â€“80)
- **Issue**: this is brief-area A3.5. **The no-crash guarantee holds** â€” I traced every offline path and found no unhandled escape: `withTimeoutOrNull` bounds each attempt (`DnsResolverPool.kt:49`), `SocketTimeoutException` is caught explicitly (`:62`), a broad catch handles the rest and marks the socket non-reusable (`:67â€“75`), `acquireSocket` returns `null` on socket-creation failure (`:95â€“102`), `performDnsQuery` returns `null` after all three servers fail and logs it (`:568â€“572`), `writeForwardedResponse` returns early on `null` (`:535`), and `forwardDnsQueryWithDeduplication` wraps the whole thing (`:518â€“523`). `serviceScope` also carries a `CoroutineExceptionHandler` (`:92â€“98`). Offline DNS therefore degrades to a silent drop, and the client's own resolver retries and times out â€” correct, crash-free behavior. **What the user sees is the defect.** Three things go wrong at once: (a) nothing at all is surfaced â€” no notification change, no `ProtectionHealthRepository` signal, no UI state, so a user whose DNS is dead sees only that pages do not load, with the app's notification still reading "Grayzone Protection Active"; (b) because *all* DNS now flows through the tunnel, the drop is attributable to Grayzone rather than to the network, so the natural user action is to blame and disable the app â€” which the 24 h hold forbids; (c) worst, `DnsTrafficBus.emit(domain, ALLOWED)` fires at `:457` **before** the query is forwarded and is never corrected, so the live traffic feed and its stats chips positively report `ALLOWED` for queries that were never answered. The one screen built to show what the DNS layer is doing states the opposite of what happened. Latency is also worse than it looks: `DNS_TIMEOUT_MS = 2000` (`:47`) across three sequential servers (`:550`) means up to 6 s per query while offline, and each of the 20 semaphore permits (`:116`) is held for up to 2 s, so forwarded queries queue behind a slow drain of ~10 per second.
- **Trigger condition**:
  1. VPN running, tunnel established.
  2. Connectivity drops or the upstream resolvers become unreachable (airplane mode, captive portal, flaky mobile data).
  3. An app resolves a non-blocked domain â†’ `handleDnsQuery` emits `ALLOWED` to the bus (`:457`) and launches the forward coroutine (`:458`).
  4. All three servers time out (2 s each); `performDnsQuery` returns `null` (`:572`).
  5. `writeForwardedResponse` returns without writing (`:535`). The client times out. The VPN screen shows the domain as `ALLOWED`; the notification still claims protection is active.
- **Blast radius**:
  - *Callers*: `handleDnsQuery()` is private with one call site â€” `runVpnLoop()` (`:371`). `writeForwardedResponse()` is private with two â€” `forwardDnsQueryWithDeduplication()` `:498` and `:517`. `performDnsQuery()` is private with two â€” `:497`, `:515`. `DnsTrafficBus.emit(domain, status)` is called from `AdBlockVpnService.kt:445`, `:451`, `:457` (`evidence/reference-index.md:673`); `DnsTrafficBus.events` is read only by `ui/VpnScreen.kt`'s `LiveTrafficFeed()` (`evidence/reference-index.md:671`), with `Status.ALLOWED` referenced at 4 sites in `VpnScreen.kt` (`:668`).
  - *Process death*: `DnsTrafficBus` is in-memory, so the misleading history is lost on restart â€” no persistent wrong data.
  - *Config change*: none.
  - *Foreground/background*: backgrounded, the only signal channel is the notification, which is unconditional (`:597â€“603`). Foregrounded, the user is actively shown incorrect `ALLOWED` rows.
  - *Cross-process*: not reachable from `:a11y`; publisher and collectors are all default-process, so the bus itself works as documented (register non-finding).
  - *Persistence*: none â€” the bus is ephemeral by design (`DnsTrafficBus.kt:11`) and no prefs or Room writes occur on this path.
  - *Release-only*: no.
- **Fix** (minimal): move the `ALLOWED` emit to where the outcome is known, and add one degraded signal. Delete the emit at `:457`, and in `writeForwardedResponse` (`:530`) emit based on the result â€” `ALLOWED` when a payload was written, and a new `Status.FAILED` (or reuse the existing `markVpnReconnectPending`) when `dnsPayload == null`. Since `DnsEvent.Status` is a nested enum with a small, enumerated set of consumers (`ui/VpnScreen.kt`, 4 + 3 + 3 sites for the three existing values), adding one value requires touching only `VpnScreen`'s `when` over status. In `performDnsQuery`, after the "All DNS servers failed" log (`:568â€“571`), call `ProtectionHealthRepository.markVpnReconnectPending(true)` on a consecutive-failure threshold so the existing "Protection interrupted" UI state carries the message â€” reusing state that is already wired rather than inventing a new surface.
- **Rewrite justification**: not a rewrite â€” one emit relocated, one enum value added, one existing repository call reused. The alternative (a resolver-health tracker) would be a new subsystem for the same user-visible outcome.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/DnsTrafficBusTest.kt` (extend), asserting a forwarded query that yields no payload does **not** produce an `ALLOWED` event; and `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` (extend), asserting `performQuery` returns `null` (not throws) when the socket send fails and when the receive times out.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/service/vpn/DnsTrafficBusTest.kt` covers `emit`/`clear`/capping and is unaffected by relocation, but any test asserting the *count* of emitted events per query would need updating. Adding `Status.FAILED` requires `VpnScreen`'s status `when` to stay exhaustive.
- **Verification**: `confirmed` for the no-crash guarantee, the silent drop, and the premature `ALLOWED` emit (static traversal of every branch above). `needs-verification` (evidence tier `T4`) for the observed user experience and the queue-drain behavior under load.
  - *Missing*: a device with controllable connectivity.
  - *Procedure*: (1) enable the VPN and confirm normal browsing; (2) enable airplane mode with Wi-Fi off; (3) open the Grayzone VPN screen and attempt to load a site from another app; (4) confirm the domain appears as `ALLOWED` in the live feed and the notification still reads "Protection Active"; (5) `adb logcat -s Grayzone` and confirm "All DNS servers failed for this query"; (6) restore connectivity and time how long queued queries take to drain.
  - *Severity effect*: **Medium** as filed â€” misreported state and no degraded signal, with correctness (no crash, no leak, fail-closed) intact. Escalate to **High** if step 6 shows the backlog wedging DNS for a noticeable period after connectivity returns, since that turns a display defect into a functional one. It does not reach Critical: protection is not *weakened* by an upstream failure â€” nothing is wrongly allowed through the filter, only wrongly *labelled*.
- **Build scope**: `everyday-build`
- **Constitution**: III (the UI asserts an outcome that did not occur)
- **Affected surfaces**: `DnsTrafficBus.emit` at `:445` and `:451` (the blocked paths) are emitted after a *decision* rather than an outcome and are correct, because `writeNxDomain`/`writeSinkhole` are synchronous â€” only the async forward path at `:457` has this problem.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt, DnsTrafficBus.kt)`; `DnsResolverPool.kt` at `557a963`
- **Disposition**: `verified`

### F-033 â€” `vpn_bypass_apps` is read but never written, so the user-extendable half of the bypass list is unreachable and its prefs key is dead  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.applyAppBypassList()` (lines 259â€“276, read at 260â€“261; doc comment at 254â€“258)
- **Issue**: `applyAppBypassList` reads `PrefsKeys.VPN_BYPASS_APPS` into `userBypass` (`:260â€“261`). Nothing in `app/src` ever writes that key â€” the only two occurrences are this read and the `const val` declaration at `PrefsKeys.kt:66`, corroborated by `evidence/prefs-inventory.md` and `evidence/reference-index.md:186` ("single reader; no UI writes it"). `userBypass` therefore always resolves to `emptySet()`. **Precise scope, because the obvious reading overstates it**: `DEFAULT_BYPASS_APPS` *is* applied (`:264`), so the 23 shipped payment/banking/streaming exclusions work correctly and the feature's protective purpose is intact. What is unreachable is only the user-extendable half. Three artifacts assert otherwise: the function's own doc comment says "plus any user-chosen packages" (`:255`), the log line at `:272â€“275` reports a count that can never include user entries, and `PrefsKeys.VPN_BYPASS_APPS` exists solely to serve a writer that does not. So this is a dead read plus a dead constant plus a doc comment contradicting its own function â€” one of the two `getStringSet` reads in the app with no writer. The documentation half (README's "the exclusion list is user-extendable") is already filed as F-268; this finding owns the code half only.
- **Trigger condition**:
  1. A user wants to exclude an app that is not in `DEFAULT_BYPASS_APPS` â€” a regional banking app, a work VPN client, a DRM streaming app not in the list.
  2. They look for the setting the doc comment implies. No UI writes `vpn_bypass_apps` (verified across `app/src`), so none exists.
  3. `applyAppBypassList` runs on every `startVpn` (`:202`) with `userBypass = emptySet()`.
  4. The app stays inside the tunnel; if it breaks under VPN, the user's only remedy is disabling the VPN â€” which the 24 h hold prevents.
- **Blast radius**:
  - *Callers*: `applyAppBypassList()` is private with one call site â€” `startVpn()` (`:202`), inside the `Builder` chain before `establish()`. `PrefsKeys.VPN_BYPASS_APPS` has exactly one referencing file, `service/vpn/AdBlockVpnService.kt` (`evidence/reference-index.md:186`). `DEFAULT_BYPASS_APPS` is a private companion `val` with one reader (`:264`). By contrast `VPN_ENABLED` has three referencing files â€” `MainActivity.kt`, `service/BootReceiver.kt`, `service/vpn/AdBlockVpnService.kt` (`evidence/reference-index.md:195`) â€” which is what a live key looks like.
  - *Process death*: none â€” the key is read fresh on every `startVpn`.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`; `:a11y` never reads prefs (`evidence/cross-process-inventory.md`).
  - *Persistence*: yes by definition â€” a `SharedPreferences` key in `PREFS_NAME` with a reader and no writer. `StateBackupManager` snapshots `prefs.all`, so if anything ever wrote it (a sideloaded or tampered backup), the value would be restored and applied to the tunnel with no validation â€” the same unvalidated-restore hazard the register records for `per_app_wait_<pkg>`. Worth noting because this key controls *which apps escape filtering*: a hostile or stale snapshot writing `vpn_bypass_apps` would silently exempt arbitrary packages, and the code path to apply it is fully live.
  - *Release-only*: no. R8 inlines the `const val` at its single use site and may drop the declaration, which changes nothing behaviorally.
- **Fix** (minimal): decide the feature's status and make the code state it truthfully. Two acceptable minimal fixes, in preference order.
  1. *If the feature is intended* (the doc comment and README say so): keep the read and add the missing writer. The smallest version is a multi-select in the existing per-app settings surface that writes `putStringSet(PrefsKeys.VPN_BYPASS_APPS, â€¦)`, plus validation that entries are installed packages. This is a UI change and therefore outside RU-06's minimal-fix scope â€” it belongs to whoever owns the settings screen; this finding's job is to establish that no writer exists.
  2. *If it is not intended*: delete the read at `:260â€“261`, apply `DEFAULT_BYPASS_APPS.forEach` directly at `:264`, delete `PrefsKeys.VPN_BYPASS_APPS`, and correct the doc comment at `:255` to describe only the default list. That is a three-line deletion that removes a dead read, a dead constant, and a false comment.
  Whichever is chosen, the log at `:272â€“275` should stop implying user contributions ("VPN bypass applied to $applied of ${DEFAULT_BYPASS_APPS.size + userBypass.size} configured apps").
- **Rewrite justification**: not a rewrite in either option. Option 1 adds a writer without touching the reader; option 2 is pure deletion.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnBypassListTest.kt` (new), extracting the merge step as `bypassPackages(default: List<String>, user: Set<String>): Set<String>` and asserting the union deduplicates and that an empty user set yields exactly the defaults. Under option 1 this test is what proves the user half reaches the builder; under option 2 it pins the defaults-only contract. `app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt` supplies the prefs double.
- **Existing test conflict**: none â€” no test references `VPN_BYPASS_APPS` or `applyAppBypassList`.
- **Verification**: `confirmed` (static, exhaustive) â€” two occurrences in `app/src`, both non-writing, cross-checked against `evidence/prefs-inventory.md` and `evidence/reference-index.md:186`. No device verification is required or would add anything.
  - *Missing*: nothing.
  - *Procedure*: n/a â€” the claim is a search result over the whole source tree, not a runtime behavior. To confirm on-device: `adb shell run-as com.grayzone.app cat shared_prefs/*.xml | grep vpn_bypass_apps` returns nothing after any amount of app usage.
  - *Severity effect*: **Medium** regardless of outcome; there is no runtime observation that could change it. It stays below High because the protective default works â€” only the extension point is missing.
- **Build scope**: `everyday-build`
- **Constitution**: IV (a comment asserting the opposite of the code), V
- **Affected surfaces**: F-268 owns the documentation half (README's user-extendable claim). `evidence/prefs-inventory.md` records this as its audit-finding candidate. The `PrefsKeys.kt` kdoc referencing `AdBlockVpnService` (`evidence/reference-index.md:600`) should be reviewed with option 2.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`; `PrefsKeys.kt` at `557a963`
- **Disposition**: `verified`

### F-034 â€” The IPv6 response builders hardcode a 48-byte DNS offset, contradicting the extension-header-aware parser, so blocked and forwarded answers over such packets are malformed  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/DnsPacketHelper.kt` â†’ `DnsPacketHelper.createNxDomainResponseIPv6()` (lines 306â€“344, offset at 308), `DnsPacketHelper.wrapDnsResponseIPv6()` (lines 346â€“394, offsets at 348, 351, 363, 378, 380â€“381), `DnsPacketHelper.computeUdpChecksumIPv6()` (lines 396â€“421)
- **Issue**: `getDnsPayloadOffset` correctly walks IPv6 extension headers â€” hop-by-hop (0), routing (43), destination options (60), fragment (44), AH (51) â€” and returns the true UDP payload offset (`:55â€“77`). `getDomainName` was deliberately changed to use it, and its comment says so: "Reuse the shared offset logic so IPv6 extension headers are handled consistently, instead of assuming a fixed 48-byte DNS offset" (`:117â€“118`). The **response** builders never got that fix. `createNxDomainResponseIPv6` hardcodes `val dnsOffset = 48` (`:308`) and writes the DNS flags at `[50]`/`[51]` and `ANCOUNT` at `[54]`/`[55]`; `wrapDnsResponseIPv6` hardcodes a 40-byte IPv6 header plus 8-byte UDP header throughout (`:348`, `:351`, `:363`, `:378`), and `computeUdpChecksumIPv6` reads the UDP length from `[44]`/`[45]` and sums from offset 40 (`:397`, `:400`, `:406`). For any IPv6 DNS query carrying an extension header the parse succeeds â€” so the domain is correctly extracted and correctly classified â€” and then the response is written into the wrong bytes. The blocked path corrupts the extension header and produces a checksum over the wrong region, so the client discards it: the block still "works" by timeout rather than by NXDOMAIN, which is slower and looks like a network fault. The forward path is worse: it copies only the first 40 bytes plus 8, dropping the extension headers while leaving `NextHeader` at `[6]` pointing at a header that is no longer present, so the client's stack rejects the datagram and the allowed domain simply fails to resolve. Both are silent â€” the builders' catches (`:335`, `:385`) only fire on bounds errors, and a wrong-offset write of a same-length packet raises nothing.
- **Trigger condition**:
  1. VPN running with the IPv6 tun address and route configured (`:196â€“198`).
  2. An app sends a DNS query over IPv6 to `fd00:1:fd00:1:fd00:1:fd00:2` **carrying at least one extension header** â€” a fragment header for an oversized query, or hop-by-hop/destination options inserted by a VPN-chaining or diagnostic stack.
  3. `isDnsQuery` and `getDomainName` handle it correctly via `getDnsPayloadOffset` (`:41`, `:119`).
  4. Classification proceeds normally; the response is built by `createNxDomainResponseIPv6` (blocked) or `wrapDnsResponseIPv6` (allowed), both assuming offset 48.
  5. A malformed packet is written to the tun. The client times out. No error is logged.
- **Blast radius**:
  - *Callers*: `createNxDomainResponseIPv6()` is private with one call site â€” `createNxDomainResponse()` (`:178`), which is called from `AdBlockVpnService.writeNxDomain()` (`:466`) and from `createSinkholeResponse()` (`:163`), which is called from `AdBlockVpnService.writeSinkhole()` (`:473`). `wrapDnsResponseIPv6()` is private with one call site â€” `wrapDnsResponse()` (`:247`), called from `AdBlockVpnService.writeForwardedResponse()` (`:536`). `computeUdpChecksumIPv6()` is private with two call sites â€” `:333`, `:383`. `getDnsPayloadOffset()` is called from `AdBlockVpnService.performDnsQuery()` (`:545`) and six times within this file (`evidence/reference-index.md:631`). So both the DoH-canary/NXDOMAIN path and the ad/adult sinkhole path are affected, i.e. every blocking decision on such packets.
  - *Process death*: none â€” pure functions over packet bytes.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none. Diagnostic counters (`nxdomainCreationErrors`, `responseWrapErrors`, `:19â€“20`) are in-memory and exposed only through `getErrorStats()` (`:30`), which has **no caller** â€” so even the counters that exist cannot be seen.
  - *Release-only*: no.
- **Fix** (minimal): pass the real offset instead of assuming it, exactly as `getDomainName` does. In `createNxDomainResponse` (`:178`) and `wrapDnsResponse` (`:247`), compute `val dnsOffset = getDnsPayloadOffset(requestPacket, requestLength) ?: return null` and hand it to the IPv6 helpers; inside them, replace the literal `48` with that parameter and derive the UDP header offset as `dnsOffset - 8` (the shape `isDnsQuery` already uses at `:42`). For `wrapDnsResponseIPv6`, copy `dnsOffset - 8` bytes of headers rather than 40, and for `computeUdpChecksumIPv6` take the UDP offset as a parameter instead of hardcoding 40/44/46. If the maintainer prefers to keep the builders simple, the alternative minimal fix is to *reject* extension-header packets explicitly â€” `if (dnsOffset != 48) { responseWrapErrors.incrementAndGet(); GrayzoneLogger.w(â€¦); return null }` â€” which is fail-closed and diagnosable rather than silently wrong. Either is acceptable; the second is smaller and I would take it first, with the first as a follow-up.
- **Rewrite justification**: not a rewrite â€” a parameter threaded through three private functions, or a two-line guard.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` (extend), building an IPv6 DNS query with a hop-by-hop extension header and asserting: `getDnsPayloadOffset` returns 56; `getDomainName` parses the domain correctly (already passing); and `createNxDomainResponse` either writes the NXDOMAIN flags at offset 56 (fix 1) or returns `null` and increments the error counter (fix 2). Fails before either fix, since the current code writes at 48 and returns a packet.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` and `app/src/test/java/com/grayzone/app/IntegrationTest.kt` cover `createNxDomainResponse`/`wrapDnsResponse` (`evidence/reference-index.md:636,637`); their existing IPv6 cases presumably use extension-header-free packets, which stay valid under both fixes.
- **Verification**: `confirmed` for the inconsistency and the wrong write offsets (static: `:308` versus `:47â€“80`, plus the `:117â€“118` comment proving the fix was applied selectively). `needs-verification` (evidence tier `T4`) for real-world frequency â€” how often an Android app emits an IPv6 DNS query with an extension header. My static expectation is *rare*: Linux/Android stacks do not add hop-by-hop or destination options to ordinary small UDP datagrams, and DNS queries are far below any MTU that would trigger fragmentation. That rarity is why this is Medium and not High.
  - *Missing*: a device on an IPv6-capable network, plus a crafted-packet sender.
  - *Procedure*: (1) enable the VPN on an IPv6 network; (2) from an on-device shell send a DNS query to `[fd00:1:fd00:1:fd00:1:fd00:2]:53` with a hop-by-hop header, for a domain in the blocklist and for one not in it; (3) capture with `adb shell tcpdump -i tun0 -X` and inspect the response's DNS flags position and checksum validity; (4) confirm the client sees a valid NXDOMAIN or answer; (5) separately, sample `getErrorStats()` â€” which requires adding a caller, itself part of the fix's value.
  - *Severity effect*: **Medium** as filed. Escalate to **High** if step 2 shows ordinary app traffic (not crafted) reaching this path, since blocking would then silently fail for real users. Drops to **Low** if verification shows no reachable production path emits extension headers, leaving a latent correctness bug plus a dead-code concern.
- **Build scope**: `everyday-build`
- **Constitution**: III (a silently wrong write with no counter increment and no log), IV (a comment at `:117â€“118` describing a fix that was not applied to the sibling functions)
- **Affected surfaces**: `getErrorStats()` (`:30â€“37`) has no caller anywhere in `app/src`, so all six diagnostic counters in this file are write-only â€” worth folding into whichever finding T030 files for observability. `wrapDnsResponse`'s IPv4 path (`:244â€“304`) correctly derives everything from `ihl` and is the model the IPv6 path should follow.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-035 â€” The 24 h hold expiry rides the inexact alarm path on API 31+, so Doze can defer the automatic VPN disable  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/VpnHoldExpiryScheduler.kt` â†’ `VpnHoldExpiryScheduler.arm()` (lines 32â€“52, guard at 37) and `AndroidManifest.xml` (no exact-alarm permission declared)
- **Issue**: **Guard shape confirmed equivalent to `SessionExpiryScheduler`**, which the register records as a positive example and a confirmed non-finding: `arm()` tests `Build.VERSION.SDK_INT < S || am.canScheduleExactAlarms()` (`:37`), uses `setExactAndAllowWhileIdle` only when that holds (`:39`), falls back to `setAndAllowWhileIdle` otherwise (`:41`), catches `SecurityException` and retries inexact (`:43â€“45`), and logs the fallback failure with the throwable (`:47`). The lint `MissingPermission` warning on `:39` is a false positive here for the same reason it is there â€” do not "fix" it. Two cosmetic deltas from the `SessionExpiryScheduler` shape, neither a defect: the `catch (e: SecurityException)` block has no explanatory comment, and its inner `catch (e2: Exception)` shadows nothing but reads awkwardly. The **real issue** is the one `evidence/lint.md` Â§E2 flags and the register carries as a High candidate: `AndroidManifest.xml` declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`, so on API 31+ `canScheduleExactAlarms()` returns `false` by default and the inexact path is what actually runs in production. `setAndAllowWhileIdle` is subject to Doze batching â€” historically ~9 minutes at minimum and longer in deep Doze â€” so the automatic disable at the end of the 24 h hold arrives late by an unbounded margin. The user-visible consequence is the *opposite* of F-021's: the hold overstays. The user who committed to 24 hours finds the VPN still enforced afterwards, with the Stop action hidden from the notification while `holdActive` remains true (`:616`) â€” except that `remainingMs` has already reached 0, so `holdActive` is `false` and Stop *is* available. So the practical impact is bounded: the VPN keeps running past expiry until the alarm lands or any of `MainActivity.kt:129`, `BootReceiver.kt:52`, or `onStartCommand:161` re-evaluates, and the user can stop it manually in the meantime. That bound is why this is Medium here rather than the High the register estimated for the session-lock case, where a deferred alarm means a *lock* fires late.
- **Trigger condition**:
  1. Device on API 31+ (the app's `minSdk` is 29, so both paths ship).
  2. VPN enabled; `arm()` finds `canScheduleExactAlarms() == false` because no exact-alarm permission is declared, and calls `setAndAllowWhileIdle(RTC_WAKEUP, startedWall + 24h)` (`:41`).
  3. At the 24 h mark the device is in Doze (screen off, stationary, typical overnight).
  4. The alarm is batched to a later maintenance window.
  5. The VPN keeps filtering past the hold. The notification's Stop action is present (the hold has expired by `remainingMs`), so the user can recover manually â€” but the advertised automatic behavior did not happen on time.
- **Blast radius**:
  - *Callers*: `arm()` has exactly one caller â€” `rearmFromPrefs()` (`:75`). `rearmFromPrefs()` callers: `AdBlockVpnService.startVpn()` (`:221`), `BootReceiver.onReceive()` (`:72`), `MainActivity.kt:147`, `MainActivity.kt:168`. `cancel()` callers: `AdBlockVpnService.kt:152`, `:175`, `:304`, `rearmFromPrefs()` (`:66`). The `PendingIntent` targets `VpnHoldExpiryReceiver`, declared at `AndroidManifest.xml:106â€“109`. Note the same manifest gap affects `SessionExpiryScheduler.arm()`, whose callers are `service/OverlayService.kt` (4 sites) (`evidence/reference-index.md:522`) â€” that instance is T018's.
  - *Process death*: alarms survive it, which is the design intent and works. `rearmFromPrefs` on boot (`BootReceiver.kt:72`) and on app open (`MainActivity.kt:147`, `:168`) provides real redundancy â€” credited below.
  - *Config change*: none.
  - *Foreground/background*: Doze applies only when idle, so the deferral is a background-only phenomenon. The compensating re-checks at `MainActivity.kt:129` and `onStartCommand:161` fire when the user returns to the app, which is exactly when they would notice.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: `VPN_HOLD_STARTED_WALL` / `VPN_HOLD_STARTED_ELAPSED` remain set until the stop path clears them (`VpnHoldLockStore.kt:36â€“41`), so a deferred alarm leaves correct â€” merely stale-acting â€” state. No corruption.
  - *Release-only*: no. The receiver is manifest-declared and covered by AGP's generated keep rules (`evidence/r8.md` Â§F2).
- **Fix** (minimal): decide the permission question explicitly rather than defaulting into the inexact path. The hold's automatic release is a user-facing commitment ("then turns off automatically", `ui/VpnScreen.kt:205`; "then auto-off", `AdBlockVpnService.kt:600`), which is a reasonable basis for declaring `<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />` and prompting via `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` during onboarding â€” the guard already handles refusal correctly, so no code change is needed. If the maintainer prefers not to request it (Play policy scrutiny is real for exact alarms), then the minimal fix is honesty plus a cheap backstop: log which path was taken in `arm()` (one line, currently absent â€” the success path is silent), and rely on the existing re-check call sites, which already cover the app-open and boot cases. Do **not** change the guard.
- **Rewrite justification**: not a rewrite â€” a manifest permission line and/or one log statement.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldExpirySchedulerTest.kt` (new), extracting the path decision as `fun exactAllowed(sdkInt: Int, canScheduleExact: Boolean): Boolean` and asserting `exactAllowed(29, false) == true` and `exactAllowed(31, false) == false`, so the branch is pinned and a future edit cannot silently invert it. `VpnHoldExpiryScheduler` currently has **no test coverage** at all (`evidence/reference-index.md:695`).
- **Existing test conflict**: none.
- **Verification**: `confirmed` for the guard's correctness, for the absent manifest permission, and therefore for the inexact path being the production default on API 31+ (static: `:32â€“52`, manifest search for `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` â†’ zero matches). `needs-verification` (evidence tier `T4`) for the actual deferral magnitude.
  - *Missing*: a device that can be forced into Doze, plus a shortened hold for practicality.
  - *Procedure*: (1) enable the VPN; (2) `adb shell dumpsys alarm | grep -B2 -A5 VPN_HOLD_EXPIRED` and record whether the entry is listed as exact (`when=` with `window=0`) or inexact/batched; (3) force Doze â€” `adb shell dumpsys deviceidle force-idle`; (4) advance to just past the expiry using the same alarm entry and observe delivery latency in `adb logcat -s Grayzone` against "VPN 24h hold expired"; (5) confirm the VPN eventually stops and prefs are cleared.
  - *Severity effect*: **Medium** as filed, because the overstay is recoverable â€” the user can stop manually once `remainingMs` hits 0, and three independent re-check call sites converge on the correct state. Escalate to **High** if step 4 shows deferral beyond a few hours *and* the notification's Stop action is unavailable during it (i.e. if `holdActive` is somehow still true), since the user would then be locked in past the commitment with no recourse. Note this finding's fix must be coordinated with F-021: making the alarm *exact* increases the value of F-021's guard, because a precise wall-clock alarm is precisely what a clock-forward attack exploits.
- **Build scope**: `everyday-build`
- **Constitution**: II (a user-facing timing commitment the mechanism cannot honor), III (the taken path is never logged)
- **Affected surfaces**: `service/SessionExpiryScheduler.kt` (RU-04, T018) shares the identical guard and the identical manifest gap; the permission decision is one manifest edit covering both, so the two findings should be fixed together.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

### F-036 â€” Every VPN startup failure produces the same "Another VPN may be active" Toast, so revoked consent and permission denial are misattributed  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.startVpn()`'s catch (lines 228â€“251; Toast at 232â€“238, log at 240â€“244)
- **Issue**: `startVpn` wraps its whole body in `catch (e: Exception)` (`:228`) and responds with one hardcoded message: "Failed to start VPN service. Another VPN may be active. Please disable it and try again." (`:235`). The `try` spans the `Builder` chain, `applyAppBypassList`, `establish()`, pool creation, prefs writes, hold marking, alarm arming, and thread start (`:191â€“227`), so that one sentence is shown for causes as different as revoked VPN consent (`establish()` returns `null` â†’ the `IllegalStateException` at `:208` â€” whose own message correctly distinguishes "permission revoked or another VPN is active"), a `SecurityException` from `addDisallowedApplication`, a prefs write failure, or an `OutOfMemoryError`-adjacent failure starting the thread. The accompanying `GrayzoneLogger.e` hedges with "possibly due to another active VPN or permission denial" (`:242`) and does carry the throwable â€” so the *log* is adequate and the *user-facing* message is not. This matters because of F-025: after a revoke, every `ACTION_START` from `MainActivity`, `VpnScreen`, or `BootReceiver` fails here, so the user gets this same wrong instruction repeatedly and is directed to hunt for a nonexistent second VPN app instead of re-granting consent. Handling in the block is otherwise good and worth crediting: `updateVpnStatus(false)` (`:248`), then `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` (`:249â€“250`) with a comment explaining the foreground-service contract (`:246â€“247`) â€” the failure path leaves no phantom notification and no dangling foreground state.
- **Trigger condition**:
  1. VPN consent has been revoked, or a genuine second VPN holds the prepared slot, or any other exception occurs in `:191â€“227`.
  2. `startVpn` runs from `onStartCommand` (`:146`, `:167`) or `restartVpnAfterDelay` (`:323`).
  3. `establish()` returns `null` and the throw at `:208` fires â€” or any other exception surfaces.
  4. The single Toast is posted to the main looper (`:232â€“237`) regardless of cause.
  5. The user follows incorrect instructions; the real fix (re-granting consent through the app's own `VpnService.prepare` flow) is never suggested. Repeats on every app open and boot.
- **Blast radius**:
  - *Callers*: `startVpn()` is private with three call sites â€” `onStartCommand()` `:146`, `:167`, `restartVpnAfterDelay()` `:323`. The failure is externally observable through `ProtectionHealthRepository.updateVpnStatus()` (3 sites in this file; read by `MainActivity.kt:137` and `ui/VpnScreen.kt:51`) and the Toast. Start requests originate from `MainActivity.kt` (4 sites), `ui/VpnScreen.kt` (7 sites), and `service/BootReceiver.kt` (6 sites) (`evidence/reference-index.md:600`), so all three entry points surface the same wrong message.
  - *Process death*: `VPN_ENABLED` stays `true` when the failure occurs before `:218`, so `START_STICKY`/`BootReceiver` retry indefinitely and re-show the Toast on each attempt, including at boot.
  - *Config change*: none.
  - *Foreground/background*: a Toast posted while the app is backgrounded may be suppressed or shown unattributed on newer Android versions, so the boot-time retry may produce no signal at all â€” the failure becomes fully silent.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: no writes on the failure path (the prefs writes at `:218â€“221` are inside the `try` and precede most failure modes only if `establish()` succeeded). Correct as written.
  - *Release-only*: no.
- **Fix** (minimal): branch the message on the cause that is already distinguishable, and stop repeating it.

```kotlin
            val message = if (e is IllegalStateException) {
                "Grayzone can't start its VPN. Re-allow the VPN connection request, or turn off any other VPN app."
            } else {
                "Grayzone couldn't start VPN filtering (${e.javaClass.simpleName}). Please try again."
            }
```

  `establish()` returning `null` is already funnelled through the explicit `IllegalStateException` at `:208`,
  so this needs no new detection. Pair it with a suppression so the Toast is shown at most once per start
  attempt initiated by the user rather than on every sticky/boot retry â€” the simplest version is to skip the
  Toast when the start came from `restartVpnAfterDelay` or a sticky restart (a boolean parameter on
  `startVpn`), which also fixes the boot-time storm.
- **Rewrite justification**: not a rewrite â€” one conditional message plus one boolean parameter.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/VpnStartFailureMessageTest.kt` (new), extracting the message selection as a pure function of the throwable and asserting an `IllegalStateException` maps to the consent-oriented text while other exceptions do not claim another VPN is active.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (static) â€” one unconditional message under a broad catch covering `:191â€“227`. `needs-verification` (evidence tier `T4`) only for whether backgrounded Toasts are shown at all on current Android, which affects the *silence* half rather than the misattribution half.
  - *Missing*: a device on which VPN consent can be revoked.
  - *Procedure*: (1) enable the VPN; (2) revoke consent in Settings; (3) reopen the app and count Toasts, noting the exact text; (4) reboot and observe whether any Toast appears from `BootReceiver`'s start attempt; (5) correlate with `adb logcat -s Grayzone` entries for "VPN startup failed".
  - *Severity effect*: **Medium** as filed (a misleading message on a recoverable, user-visible failure). Escalate to **High** if step 4 shows no user-visible signal at all after a boot-time failure, since protection would then be absent with the user believing it restored. This is also the message half of the C4 chain in F-024: it is filed here because its cause is the broad catch at `:228`, not the swallowed `close()`.
- **Build scope**: `everyday-build`
- **Constitution**: III (a diagnostic message that misidentifies the fault), II
- **Affected surfaces**: F-025 is what makes this repeat; the two should be fixed together so a revoke both logs correctly and reports correctly.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### F-037 â€” DNS responses larger than the tun MTU are silently dropped, and the tunnel never sets an MTU  [Medium]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.startVpn()`'s `Builder` chain (lines 192â€“200, no `setMtu`) and `AdBlockVpnService.writeForwardedResponse()` (lines 530â€“538); packet assembly at `app/src/main/java/com/grayzone/app/service/vpn/DnsPacketHelper.kt` â†’ `wrapDnsResponse()` (lines 244â€“304, size at 250)
- **Issue**: the `Builder` chain sets addresses, DNS servers, routes, session, and `setBlocking(true)` but never calls `setMtu` (`:192â€“200`), so the tun takes the framework default. Meanwhile the receive buffer is `ByteArray(32767)` (`DnsResolverPool.kt:53`) and the response is trimmed only to what arrived (`:61`), so `wrapDnsResponse` will build a packet of `ihl + 8 + dnsLength` bytes (`:250`) with no size check against the interface MTU. A large EDNS0 response therefore produces a write of more than the MTU to the tun fd, which fails; the resulting `IOException` propagates out of `writeForwardedResponse` (`:537`) into `forwardDnsQueryWithDeduplication`'s catch (`:518â€“523`), where it is logged as "DNS query failed for $domain" â€” indistinguishable from an upstream timeout â€” and the client is left to time out. Exposure is limited: the query is forwarded verbatim including whatever OPT record the client set, and Android's resolver and Chrome advertise buffer sizes around 1232 bytes, comfortably inside a 1500-byte default MTU. Responses large enough to matter mostly arise from DNSSEC-validating or deliberately large-answer queries. So this is a narrow correctness gap rather than a common failure â€” but it is undiagnosable when it happens, and `setMtu` is a one-line declaration of intent that would make the boundary explicit.
- **Trigger condition**:
  1. VPN running; an app sends a DNS query advertising a large EDNS0 buffer (or a resolver returns an unusually large answer).
  2. `performDnsQuery` returns a payload of, say, 4096 bytes (`:552`, `DnsResolverPool.kt:61`).
  3. `wrapDnsResponse` builds an IP packet of `20 + 8 + 4096` bytes with no MTU check (`:250`).
  4. `outputStream.write(wrapped)` (`:537`) fails on the tun fd.
  5. The exception is logged as a generic query failure (`:519â€“523`); the client times out with no indication that the answer existed and was discarded.
- **Blast radius**:
  - *Callers*: `writeForwardedResponse()` is private with two call sites â€” `forwardDnsQueryWithDeduplication()` `:498` and `:517`. `wrapDnsResponse()` has one caller â€” `AdBlockVpnService.writeForwardedResponse()` (`:536`) (`evidence/reference-index.md:637`) â€” and delegates to `wrapDnsResponseIPv6()` for IPv6 (`:247`). The `Builder` chain is in `startVpn()`, whose three call sites are enumerated in F-022.
  - *Process death*: none â€” per-packet behavior.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): declare the MTU and fail diagnosably above it. Add `.setMtu(1500)` to the `Builder` chain (`:200`), and in `writeForwardedResponse` check the assembled size before writing:

```kotlin
        if (wrapped.size > VPN_MTU) {
            com.grayzone.app.GrayzoneLogger.w(
                com.grayzone.app.LogComponent.DNS,
                "Dropping ${wrapped.size}-byte DNS response â€” exceeds tun MTU $VPN_MTU"
            )
            return
        }
```

  Setting the MTU explicitly also removes a dependence on an undocumented framework default. A fuller fix
  would set the DNS `TC` (truncated) bit so the client retries over TCP, but that requires editing the DNS
  header of a forwarded payload and is more than this finding warrants.
- **Rewrite justification**: not a rewrite â€” one builder call and one size guard.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/DnsPacketTest.kt` (extend), asserting `wrapDnsResponse` with a 4096-byte payload produces a packet whose length is reported correctly in the IP header, and that the guard's predicate rejects it at a 1500-byte MTU.
- **Existing test conflict**: none â€” existing `wrapDnsResponse` cases use small payloads.
- **Verification**: `needs-verification` (evidence tier `T4`) for whether the oversized write actually fails and for the default MTU's value on supported devices.
  - *Missing*: a device, plus a DNS query that reliably yields a large answer.
  - *Procedure*: (1) enable the VPN; (2) `adb shell ip link show tun0` and record the MTU; (3) from an on-device shell run a query known to return a large answer with a large advertised buffer (`dig +bufsize=4096 dnssec-failed.org` equivalent, or a TXT record over 1500 bytes) against `10.0.0.3`; (4) `adb logcat -s Grayzone` and look for "DNS query failed for" with an `IOException`; (5) capture `tcpdump -i tun0` to confirm no response reached the client.
  - *Severity effect*: **Medium** as filed. Drops to **Low** if the framework's default MTU turns out to accommodate the largest realistic response, or if the oversized write silently truncates instead of failing (still wrong, but only for exotic queries). Escalate to **High** only if common traffic â€” ordinary browsing with DNSSEC-validating resolvers â€” reproduces it, since that would break name resolution for real users with the failure logged as a generic timeout.
- **Build scope**: `everyday-build`
- **Constitution**: III (a discarded answer reported as a query failure)
- **Affected surfaces**: `wrapDnsResponseIPv6` (`DnsPacketHelper.kt:346`) has the same unbounded `48 + dnsLength` sizing and needs the same guard; F-034 already covers its offset handling.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`; `DnsPacketHelper.kt` at `557a963`
- **Disposition**: `verified`

---

## Low

### F-038 â€” `pendingQueries.clear()` during teardown orphans in-flight deduplication entries, and followers `await()` with no timeout  [Low]

- **Review unit**: RU-06
- **Location**: `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt` â†’ `AdBlockVpnService.shutdownVpn()` (line 291) and `AdBlockVpnService.forwardDnsQueryWithDeduplication()` (lines 488â€“528, follower await at 510)
- **Issue**: `pendingQueries` is a `ConcurrentHashMap<DnsQuestionKey, CompletableDeferred<ByteArray?>>` (`:105`) used so duplicate concurrent questions share one upstream query. Two small defects. (a) `shutdownVpn` calls `pendingQueries.clear()` (`:291`) without completing the deferreds it removes. Any follower already holding a reference from `putIfAbsent` (`:503`) keeps awaiting the leader, and a *new* query for the same key after the clear creates a second leader â€” so a restart can produce a duplicate upstream query, defeating the deduplication for that key. The leader's `finally` uses `remove(key, deferred)` (`:526`), which correctly declines to remove a *different* deferred, so no cross-talk occurs; the cost is a redundant query, not incorrect data. (b) Followers call `existingQuery.await()` with no timeout (`:510`), so a follower's lifetime is coupled to the leader's worst case â€” three servers Ã— 2 s = 6 s (`:47`, `:550`). In practice the leader always completes: its `try/catch/finally` completes the deferred on both the success (`:516`) and failure (`:524`) paths, and if the leader is cancelled by `serviceScope.cancel()` the follower is cancelled with it, since both live in the same scope. So there is no permanent hang â€” which is why this is Low rather than a leak finding.
- **Trigger condition**:
  1. Two apps query the same domain/type/class concurrently; the second becomes a follower at `:504â€“511`.
  2. The VPN is torn down or restarted (`shutdownVpn`, `:291`) while the leader's upstream query is in flight.
  3. `pendingQueries.clear()` drops the map entry without completing the deferred.
  4. A third query for the same key arrives after the restart, becomes a new leader, and issues a second upstream query.
- **Blast radius**:
  - *Callers*: `pendingQueries` is a private field touched at `:105`, `:291`, `:503`, `:526`. `forwardDnsQueryWithDeduplication()` is private with one call site â€” `handleDnsQuery()` (`:459`), inside `serviceScope.launch`. `DnsPacketHelper.getQuestionKey()` has one caller (`:495`) (`evidence/reference-index.md:633`); `DnsQuestionKey` is referenced from `AdBlockVpnService.kt` and within `DnsPacketHelper` (`:627`). `shutdownVpn()` call sites are enumerated in F-024.
  - *Process death*: the map is in-memory; everything is discarded cleanly.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): complete the deferreds while clearing, so followers resolve immediately instead of waiting for a leader that is going away.

```kotlin
        pendingQueries.values.forEach { it.complete(null) }
        pendingQueries.clear()
```

  `CompletableDeferred.complete` is idempotent-safe (it returns `false` if already completed), so racing with
  the leader's own `complete` at `:516` is harmless. A follower then receives `null` and
  `writeForwardedResponse` returns without writing (`:535`) â€” the same outcome as an upstream failure, which
  is correct during teardown. Adding a timeout to `:510` is optional once this is in place.
- **Rewrite justification**: not a rewrite â€” one line added before the existing `clear()`.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/service/vpn/DnsDeduplicationTest.kt` (new), extracting the map operations into a small testable holder and asserting that clearing completes all outstanding deferreds with `null` and that a follower's `await()` returns rather than suspending indefinitely.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (static) â€” the clear does not complete, and `await()` has no timeout. No device verification would add anything; the analysis above already establishes that no permanent hang is reachable, which is what bounds the severity.
  - *Missing*: nothing for the defect. Only the frequency of duplicate upstream queries after a restart would need measurement.
  - *Procedure*: (1) enable the VPN; (2) generate concurrent identical queries from two apps; (3) induce a restart mid-flight (see F-022's procedure); (4) `adb logcat -s Grayzone` and count "Deduplicating DNS query" entries against upstream queries captured with `tcpdump`.
  - *Severity effect*: stays **Low** in all outcomes â€” the worst case is a redundant upstream query and a follower waiting out an already-bounded timeout. Escalate to **Medium** only if verification shows followers surviving their scope's cancellation, which the shared-scope structure rules out statically.
- **Build scope**: `everyday-build`
- **Constitution**: none
- **Affected surfaces**: none â€” this deduplication map is used only here.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

### Unallocated: F-039

`F-039` is left unused so the register's `Used` column reads `F-020 â€¦ F-038`. One candidate was considered and
deliberately not filed: `DnsTrafficBus.emit` (`DnsTrafficBus.kt:39â€“55`) allocates a fresh list of up to 50
elements, one `DnsEvent`, and one `String` id per DNS query, and takes a class-level monitor on the packet
thread even when no collector is attached. It is real but too small to report â€” a few tens of kilobytes per
second of garbage under heavy browsing, against a per-query budget already bounded by a 2 s DNS timeout â€” and
the KDoc at `:57â€“60` documents keeping the feed alive while the screen is closed as a deliberate choice.

---

## Reference-index corrections

`evidence/reference-index.md` is the audit's sole source for enumerated callers, so five rows I relied on and
found inaccurate are recorded here rather than silently worked around. Each was checked by exhaustive search
over `app/src`.

| Row | Index claim | Verified in the working tree |
|---|---|---|
| `:609` | `AdBlockVpnService` overrides `onRevoke()` | **No such override exists.** Only `onCreate`, `onStartCommand`, `onDestroy`. This is the subject of F-025 and matches `evidence/concurrency-inventory.md`. |
| `:674` | `DnsTrafficBus.clear()` is **TEST-ONLY**; "nothing in main clears the bus" | `AdBlockVpnService.shutdownVpn()` calls it at `:293`. The row predates the uncommitted diff. The `@Synchronized` annotation on `clear()` (`DnsTrafficBus.kt:67`) is therefore load-bearing in production, not just in tests. |
| `:617` | `BlocklistManager.load()` â€” `AdBlockVpnService.kt` (2 sites) | One site only: `onCreate()` `:110`. This **strengthens** the register's double-load non-finding rather than contradicting it. |
| `:657` | `DnsResolverPool.shutdown()` â€” `AdBlockVpnService.kt` (3 sites) | One site only: `shutdownVpn()` `:297`. |
| `:696` | `VpnHoldExpiryScheduler.arm()` â€” `AdBlockVpnService.kt`, plus `(self)` | One site only: `rearmFromPrefs()` `:75`. `AdBlockVpnService` calls `rearmFromPrefs` (`:221`) and `cancel` (`:152`, `:175`, `:304`), never `arm` directly. |
| `:713` | `VpnHoldLock.isHoldActive()` â€” `(self)` only | Also called from `MainActivity.kt:129`, `BootReceiver.kt:52`, `:71`, and `VpnHoldExpiryScheduler.kt:71`. This matters: those four call sites are the ones that *do* guard correctly, which is what isolates F-021 to the alarm path. |

## Positive examples credited

Nine things in this unit are done well and should be preserved through any fix.

1. **`VpnHoldLock` prefers the monotonic clock.** `remainingMs` uses `elapsedRealtime` and falls back to wall time only when it detects a reboot (`VpnHoldLock.kt:25â€“34`), with the rationale in the kdoc (`:9â€“10`). This is the right design for a tamper-resistant hold and is fully unit-tested (`VpnHoldLockTest.kt`). F-021 is a failure to *use* it on one path, not a flaw in it.
2. **`startForeground` before `establish()`.** `startVpn` posts the notification first (`:189`) with a comment explaining the ~5 s foreground-service deadline and that `establish()` can throw (`:185â€“188`). The failure path then unwinds it properly with `updateVpnStatus(false)`, `stopForeground(STOP_FOREGROUND_REMOVE)`, and `stopSelf()` (`:248â€“250`), so a failed start leaves no phantom notification.
3. **`establish()`'s `null` is treated as a failure, not a broken running state.** `?: throw IllegalStateException(...)` (`:207â€“208`) with a comment naming both causes â€” exactly the fail-loudly choice Principle III asks for, and the distinguishable signal F-036's fix builds on.
4. **`onDestroy` deliberately does *not* clear `VPN_ENABLED`,** with a four-line comment explaining that `onDestroy` also fires on an OS kill and that the flag encodes user intent for `BootReceiver`/`START_STICKY` (`:329â€“332`). This is a subtle invariant recorded where the next reader will need it.
5. **All tun writes are serialized.** `synchronized(outputStream)` at `:468`, `:475`, and `:537` correctly guards concurrent writes from the packet thread and the forwarding coroutines. Without this, F-029's overlapping-thread window would be a data-corruption bug rather than log noise.
6. **The Bloom filter loads with no allocation doubling.** `readFrom` validates magic, version, hash count, bit count, byte alignment, and a size cap *before* allocating, then streams into a single array with `readFully` (`GrayzoneBloomFilter.kt:55â€“96`). Header validation before allocation is the right order, and there is no `readBytes()` intermediate copy.
7. **The TLD false-positive guard is deliberate, documented, and duplicated in the right places.** `DomainZoneMatcher` never queries a bare TLD (`:17â€“28`), `AbusedTldList` exists precisely because bare TLDs cannot be Bloom-filtered without risking blackholing an entire TLD (`AbusedTldList.kt:5â€“18`), and `blocks()` refuses dotless input (`:26â€“27`). Three files agree, each explaining why.
8. **`DnsResolverPool` uses a plain lock on purpose.** The comment at `:24â€“26` explains that all critical sections are non-suspending so `shutdown()` can be called synchronously from `Service.onDestroy()` â€” and `shutdownVpn` does exactly that (`:295â€“297`, with its own matching comment). This is the reasoning F-022's fix reuses.
9. **The uncommitted diff's concurrency hardening is a genuine improvement.** `@Volatile` on `isRunning`, `vpnInterface`, `vpnThread`, and `resolverPool` (`:33`, `:86â€“89`) fixes real visibility gaps; moving the stream construction inside the `try` (`:348â€“349`) closes a resource gap; and `Thread.currentThread().setUncaughtExceptionHandler` (`:351â€“358`) means the packet thread no longer dies in silence. F-022 and F-028 are about what those changes do *not* cover, not about their being wrong.

## Coverage summary

One row per file in RU-06. For every `no-defect-found`, the specific constructs examined are named.

| File | Findings | Verdict | What was examined |
|---|---|---|---|
| `AdBlockVpnService.kt` | F-020, F-022, F-023, F-024, F-025, F-028, F-029, F-032, F-033, F-036, F-037, F-038 (+ F-021, F-026, F-027, F-030 partly) | defects found | Full file (623 lines) read line by line: companion constants and `DNS_SERVERS`/`DEFAULT_BYPASS_APPS`; `coroutineExceptionHandler` and `serviceScope`; `onCreate`; `createResolverPool`; all four `onStartCommand` action branches; `stopVpnAfterHoldExpired`; `startVpn`'s `Builder` chain, `establish`, prefs/hold writes, thread start, and catch; `applyAppBypassList`; `stopVpn`/`shutdownVpn`; `restartVpnAfterDelay`; `onDestroy`; `runVpnLoop`'s inner and outer catches, backoff and escalation arithmetic; `handleDnsQuery` and the three classifier branches; `writeNxDomain`/`writeSinkhole`/`writeForwardedResponse` and their `synchronized` blocks; `forwardDnsQueryWithDeduplication`; `performDnsQuery`'s fallback loop; `createNotificationChannel`; `buildNotification` including the conditional Stop action. Cross-checked against the uncommitted diff and `AndroidManifest.xml:121â€“132`. |
| `BlocklistManager.kt` | F-026, F-027, F-030 | defects found | Full file (259 lines): filter fields and their publication ordering against the `@Volatile isLoaded` piggyback; the 30-entry DoH/DoT set and its "last verified" date; `loadStarted` CAS; the bounded LRU `normalizedDomainCache` and its `cacheLock` critical sections; both high-confidence fallback sets (25 + 13 entries); `load`'s thread and widened catch; `normalizeDomain`'s IPv4-literal, IPv6, whitespace, and single-label rejections; `isDoHBypass`; `isBlocked`'s ordering (DoH â†’ high-confidence â†’ `isLoaded` gate â†’ abused TLDs â†’ filters); `loadFilter`/`loadAbusedTlds` resource handling; `matchesHighConfidence`/`matchesFilter`. |
| `DnsPacketHelper.kt` | F-034, F-037 (assembly half) | defects found | Full file (422 lines): all six `AtomicInteger` counters and `getErrorStats` (no caller found anywhere in `app/src`); `isDnsQuery`'s `length < 28` guard and its `udpOffset` arithmetic; `getDnsPayloadOffset` for IPv4 IHL and the IPv6 extension-header walk including the fragment-header special case; `getDnsPayloadLength`; `getQuestionKey`'s label walk, compression-pointer rejection and 63-byte limit; `getDomainName`; `createSinkholeResponse`'s delegation to NXDOMAIN; `createNxDomainResponse` (IPv4) address/port swaps, RCODE and ANCOUNT writes, and `computeIpChecksum`; `wrapDnsResponse` (IPv4) length recomputation and transaction-ID preservation; both IPv6 builders; `computeUdpChecksumIPv6`'s pseudo-header sum and odd-length handling. |
| `DnsResolverPool.kt` | F-031, F-032 (upstream half) | defects found | Full file (128 lines): `Semaphore(maxConcurrentSockets)` and `withPermit` scoping; the plain `lock` and every critical section; `@Volatile isShutdown` and the early return at `:40`; `performQuery`'s `withContext` + `withTimeoutOrNull` nesting, the explicit `SocketTimeoutException` catch, the `reusable` flag, and the `finally` release; `acquireSocket`'s idle-pop, socket creation, and `protectSocket` placement inside the `try`; `releaseSocket`'s two close paths; `shutdown`'s drain. |
| `VpnHoldExpiryScheduler.kt` | F-021, F-035 | defects found | Full file (99 lines): `alarmManager`/`pendingIntent` construction with `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`; `arm`'s past-time early return, the `canScheduleExactAlarms` guard, both alarm calls, the `SecurityException` fallback and its nested catch; `cancel`; `rearmFromPrefs`'s `isTracked` and `isHoldActive` checks; `VpnHoldExpiryReceiver.onReceive`'s action filter, `startService` dispatch and its catch; the `ACTION_HOLD_EXPIRED` constant duplicated against `AdBlockVpnService`. Compared field-for-field against `SessionExpiryScheduler.arm()`'s guard shape and against `AndroidManifest.xml:106â€“109`. |
| `VpnHoldLock.kt` | â€” | **no-defect-found** | Full file (52 lines). Examined: `HOLD_MS` = 24 h via `TimeUnit`; `State.isTracked`'s `startedWallMs > 0` test; `remainingMs`'s reboot detection (`nowElapsedMs < started`), its preference for the elapsed clock, the wall-clock fallback, and `coerceAtLeast(0)` on both branches; `isHoldActive` and `canUserDisable` as thin derivations (no independent time logic to diverge); `expiresAtWallMs`'s `isTracked` guard against returning a bare `HOLD_MS`; `formatRemaining`'s `coerceAtLeast(1)` (never renders "0m") and its hours/minutes split. Checked specifically for: an untracked state producing a nonzero remaining time (it cannot â€” `:26`), a negative remaining time leaking to callers (clamped), and a wall-clock jump shortening the hold (it cannot on this path â€” the elapsed branch is preferred, which is exactly why F-021 is about the alarm path instead). Backed by `VpnHoldLockTest.kt`. |
| `VpnHoldLockStore.kt` | â€” | **no-defect-found** | Full file (44 lines). Examined: both `prefs()`/`read()` overloads for consistent use of `PrefsKeys.PREFS_NAME` + `MODE_PRIVATE`; the `getLong(..., 0L)` defaults matching `State.isTracked`'s sentinel; `markEnabledIfAbsent`'s `if (read(prefs).isTracked) return` guard, which is the invariant preventing reconnects from resetting the 24 h (the property F-022 and F-023 exploit *after* another path clears the keys â€” the guard itself is correct); the injectable `nowWallMs`/`nowElapsedMs` defaults, which make the write testable; both `clear()` overloads removing exactly the two keys they own and nothing else; consistent `apply()` (no main-thread `commit()`). Checked specifically for a partial write leaving wall set without elapsed â€” both keys go in one `edit()` batch (`:30â€“33`), so no torn state is reachable. Key ownership cross-checked against `evidence/prefs-inventory.md` and `evidence/reference-index.md:196â€“197`. |
| `DnsTrafficBus.kt` | F-032 (mislabelling; the bus's own mechanics are sound) | no defect in this file | Full file (71 lines). Examined: `MAX_EVENTS = 50` and the two `buildList` branches (verified the capped branch keeps exactly 50 by taking `subList(0, MAX_EVENTS - 1)`); `AtomicLong` id generation; `DnsEvent`'s defaulted `timestampMs`/`id`; `@Synchronized` on both `emit` and `clear`; list *replacement* rather than mutation, which is what makes `StateFlow` equality fire for Compose collectors; `asStateFlow()` exposure preventing external mutation. The register's "collectors receive nothing" claim is a confirmed non-finding and is not re-reported. The premature `ALLOWED` label is filed against the *caller* (`AdBlockVpnService.kt:457`), not here. `emit`'s per-query allocation was considered and deliberately not filed â€” see Â§ Unallocated: F-039. |
| `DnsQueryClassifier.kt` | â€” | **no-defect-found** | Full file (43 lines). Examined: the safety-critical ordering asserted in the kdoc (`:6â€“14`) against the implementation â€” Firefox canary first via a case-insensitive comparison on the *raw* domain (`:32`, correct: it must not depend on normalization succeeding), then DoH/DoT, then the blocklist, then forward; the injectable `isDoHBypass`/`isBlocked`/`normalize` defaults, which are what make this the unit's best-tested logic; the `normalized != null` guards on both blocking branches; the `else -> FORWARD` fallback. Checked specifically for: whether a `null` normalization can reach a blocking branch (it cannot), and whether forwarding on `null` is a bypass (it is not â€” `normalizeDomain` returns `null` only for IP literals, whitespace-bearing input, and single-label hosts, none of which the Bloom filters or TLD list can match anyway; bare-TLD queries are deliberately unmatched per `DomainZoneMatcher`'s documented guard). Backed by `DnsQueryClassifierTest.kt`. |
| `DomainZoneMatcher.kt` | â€” | **no-defect-found** | Full file (29 lines). Examined: the empty-domain early return; the full-domain probe before the parent walk; the `dotIndex < domain.length - 1` bound preventing a trailing-dot infinite loop; the `parent.contains('.')` test that stops the walk before bare TLDs (the documented false-positive guard); `indexOf('.', dotIndex + 1)` advancing monotonically, so termination is guaranteed for any input. Checked specifically for: consecutive dots (`a..b` â€” handled, produces an empty-label parent that simply fails the predicate), a leading dot, a domain of only dots, and predicate-invocation count (one per level, and `GrayzoneBloomFilter.mightContain` computes a single SHA-256 per call rather than one per hash, so the cost is bounded). Backed by `DomainZoneMatcherTest.kt`. |
| `GrayzoneBloomFilter.kt` | F-027 (retention; the file's own logic is sound) | no defect in this file | Full file (114 lines). Examined: `mightContain`'s empty-domain guard, the `(h1 + i*h2) % bitCount` index derivation, and the byte/bit split; `baseHashes`' single SHA-256 with a `ThreadLocal<MessageDigest>` (correct â€” `MessageDigest` is not thread-safe and this is called from the packet thread and coroutines); `readU32`'s `and 0xFF` masking on every byte (no sign-extension bug); `readFrom`'s magic/version/hash-count/bit-count/alignment/size validations, all performed *before* the allocation; `readFully`'s partial-read loop and its `-1` handling; the commented-out trailing-bytes check at `:83` with its explanatory comment (a deliberate tolerance, not a leftover). Checked specifically for: `bitCount` overflow on the `toInt()` at `:80` (guarded by the 32 MB cap at `:76`), a zero `h2` degenerating all probes to one bit (explicitly handled at `:34`), and a negative `byteIdx` (impossible given `bitCount > 0` and the modulo). Backed by `BloomFilterTest.kt`. |
| `AbusedTldList.kt` | â€” | **no-defect-found** | Full file (62 lines). Examined: the private constructor plus `EMPTY` singleton used as the failure fallback; `blocks()`'s `lastDot <= 0` guard (rejects both dotless input and a leading-dot-only string) and its `lastDot == length - 1` guard (rejects a trailing dot), then an exact match on the final label only â€” which is what keeps `zip.example.com` reachable while blocking `downloads.example.zip`, exactly as the kdoc claims (`:14â€“15`); `readFrom`'s `bufferedReader(...).use`; `parse`'s BOM trim, comment and blank skipping, multi-label skipping (with a comment explaining that suffix blocks are already covered by the ads filter's parent walk), and `isValidLabel`'s 63-byte limit, leading/trailing-hyphen rejection, and character allowlist. Checked specifically for: case sensitivity (input is lowercased at `:47`, and callers pass already-normalized domains), and whether a hostile feed line could inject a bare `com` (it could, and that is a *feed* concern the exact-match design deliberately accepts rather than a code defect â€” the file's kdoc states the tradeoff). Backed by `AbusedTldListTest.kt`. |

## Non-findings re-confirmed during this pass

Recorded so a later reader does not re-litigate them. None is reported as a finding.

| Claim | Status after RU-06 analysis |
|---|---|
| `DnsTrafficBus` collectors receive nothing | Confirmed non-finding. `AdBlockVpnService` declares no `android:process` (`AndroidManifest.xml:121â€“132`), so publisher (`AdBlockVpnService.kt:445`, `:451`, `:457`) and collectors (`ui/VpnScreen.kt`) share the default process. The "in-process bus" comment is accurate. |
| Lint `MissingPermission` on `setExactAndAllowWhileIdle` | Confirmed non-finding for `VpnHoldExpiryScheduler.kt:39` too â€” I compared the guard field-for-field against `SessionExpiryScheduler` and it is equivalent (F-035 documents the two cosmetic deltas). The manifest gap is a separate issue, filed as F-035, not a lint fix. |
| R8 stripping manifest components | Not re-examined; `evidence/r8.md` Â§F2 stands. `VpnHoldExpiryReceiver` is manifest-declared (`AndroidManifest.xml:106â€“109`) and covered by AGP's generated keep rules. |
| Double-loading of the Bloom filters | Confirmed non-finding, and **strengthened**: `BlocklistManager.load()` has exactly one call site (`AdBlockVpnService.kt:110`), not the two the reference index lists, and `loadStarted.compareAndSet` (`:144`) guards re-entry independently. |

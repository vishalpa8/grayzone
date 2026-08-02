# Findings â€” RU-02 (`app/src/main/java/com/grayzone/app/data/`)

**Task**: T020 | **Feature**: `004-android-regression-audit` | **Created**: 2026-07-31
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md) | **Register**: [`findings.md`](./findings.md)
**Reserved ID block**: F-040 â€¦ F-059

## Scope examined

All 15 files in the review unit were read in full â€” no sampling:

| File | Lines | Outcome |
|---|---|---|
| `UsageDatabase.kt` | 43 | F-040, F-041, F-042 |
| `UsageDao.kt` | 76 | F-052, F-053 |
| `UsageEvent.kt` | 35 | F-052 |
| `CleanupWorker.kt` | 62 | F-047, F-054 |
| `StateBackupManager.kt` | 271 | F-043, F-044, F-045, F-046 |
| `ConfigValidator.kt` | 25 | F-048 (range authority) |
| `PerAppLimits.kt` | 13 | F-048, F-049 |
| `DailyBudget.kt` | 61 | F-050 |
| `RuntimeSessionReset.kt` | 40 | F-051 |
| `ScheduleManager.kt` | 172 | F-055, F-056 â€” parse/overwrite half owned by **F-205**, not re-filed |
| `StreakManager.kt` | 203 | F-057, F-058 |
| `StreakEvaluator.kt` | 30 | covered under F-040 / F-058 |
| `ProtectionHealthRepository.kt` | 31 | F-059 |
| `ProtectionStatus.kt` | 13 | covered under F-059 |
| `DefaultMonitoredApps.kt` | 26 | no defect found (see closing notes) |

Supporting artifacts read: `app/schemas/com.grayzone.app.data.UsageDatabase/1.json` and `2.json`,
`app/src/androidTest/.../MigrationTest.kt`, `UsageDaoStatsTest.kt`, `PeakHourAggregationTest.kt`,
`app/src/test/.../DailyBudgetTest.kt`, `PerAppLimitsTest.kt`, `StreakManagerTest.kt`,
`GrayzoneApplication.kt`, `OverlayService.kt` (call sites), `SessionPolicyEngine.kt`,
`SessionModels.kt`, `DateUtils.kt`, `PrefsKeys.kt`, `app/build.gradle.kts`, and `git diff` for the
nine uncommitted files.

## Findings

### F-040 â€” `UsageDatabase.getInstance` double-checked locking has no second null check, so two Room instances can be built over one WAL file  [High]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/UsageDatabase.kt` â†’ `UsageDatabase.Companion.getInstance()` (lines 34â€“47)
- **Issue**: The idiom is `INSTANCE ?: synchronized(this) { â€¦build(); INSTANCE = instance; instance }`.
  The `synchronized` block **never re-reads `INSTANCE`**, so the lock serialises the builders but does
  not deduplicate them. Two threads that both observe `INSTANCE == null` each construct a full
  `UsageDatabase` â€” each with its own `SupportSQLiteOpenHelper`, its own SQLite connection pool, and its
  own `InvalidationTracker`. The second write to `INSTANCE` orphans the first instance, whose connection
  pool stays open and is still held by whichever caller received it. `@Volatile` on `INSTANCE` (`:16`)
  fixes visibility, not the missing check, and is what makes the bug look correct on a skim.
  User-visible consequence: two writer connections against one WAL file in one process. WAL permits many
  readers but exactly one writer, so a concurrent `insertEvent` can fail with
  `SQLiteDatabaseLockedException` after the per-connection busy timeout. That insert is the app's only
  record of a session, and its failure is swallowed at `OverlayService.kt:1128â€“1130`
  (`Log.e(TAG, "Failed to log blocked session: ${e.message}")`), so a lost usage event produces a wrong
  daily budget and a wrong streak with no user signal. The orphaned instance is also a permanent
  fd/heap leak for the life of the process.
- **Trigger condition**:
  1. Install and grant accessibility + overlay permissions, with at least one monitored app.
  2. Force-stop the app so the next event is a cold start: `adb shell am force-stop com.grayzone.app`.
  3. Open a monitored app. Cold start now runs `GrayzoneApplication.onCreate` â†’ `catchUpStreaks()`
     (`GrayzoneApplication.kt:161â€“169`, `appScope` = `Dispatchers.IO`, `:36`) â†’ `StreakEvaluator.run` â†’
     `getInstance`, **concurrently with** `OverlayService`'s `serviceScope` (`Dispatchers.IO`,
     `OverlayService.kt:115â€“116`) reaching `getInstance` at `:570` or `:1117`.
  4. Instrument the race without a device by adding a counter to the builder lambda; assert it is
     called exactly once across N concurrent callers (see *Test to add*).
- **Blast radius**:
  - *Callers*: four in main source, on three different threads â€”
    `OverlayService.logUsageEvent()` (`OverlayService.kt:570`, `Dispatchers.IO`),
    `OverlayService.recordFrictionSkip()` (`OverlayService.kt:1117`, `serviceScope` = `Dispatchers.IO`),
    `CleanupWorker.doWork()` (`CleanupWorker.kt:42`, WorkManager's task executor), and
    `StreakEvaluator.run()` (`StreakEvaluator.kt:27`), itself called from three places â€”
    `GrayzoneApplication.catchUpStreaks()` (`:164`), `OverlayService.kt:289` (midnight reset), and
    `HomeScreen.kt:57` (ON_RESUME). Instrumented tests (`UsageDaoStatsTest`,
    `PeakHourAggregationTest`, `MigrationTest`) build in-memory databases directly and never go through
    `getInstance`, which is why no existing test can catch this.
  - *Process death*: `INSTANCE` is process-local static state, so it resets to `null` on every process
    death. This does not mitigate the bug â€” it re-arms it, because the widest race window is exactly
    the cold-start burst in step 3.
  - *Config change*: no effect; the field is static and not tied to any Activity or Context lifecycle.
  - *Foreground/background*: the highest-concurrency moment is a background-initiated cold start, when
    `OverlayService` is launched by `GrayzoneAccessibilityService.kt:93â€“99` while no UI exists, so a
    fix must not assume an Activity is alive.
  - *Cross-process*: **not reachable from `:a11y`.** Verified by direct search: `UsageDatabase` appears
    in main source only in `UsageDatabase.kt`, `CleanupWorker.kt`, `StreakEvaluator.kt`, and
    `OverlayService.kt`, all default-process, and `GrayzoneApplication.onCreate` returns at `:54`
    (`Application.getProcessName() != packageName`) before `catchUpStreaks()`. So this is a
    single-process race only. See F-042 for the latent cross-process half.
  - *Persistence*: yes â€” writes to `grayzone_usage.db` in WAL mode
    (`RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING`, `:41`). Two connection pools also both attempt the
    lazy open and, on an upgrading install, `MIGRATION_1_2`; Room wraps migrations in a transaction and
    the migration body is `CREATE INDEX IF NOT EXISTS` (idempotent), so corruption is unlikely, but
    concurrent open + migrate over two helpers on one file is untested here (see *Verification*).
  - *Release-only*: no. R8 does not alter field access ordering or synchronisation, and the defect is
    present identically in both variants.
- **Fix** (minimal): add the missing inner check â€” five characters of structure, no redesign:
  ```kotlin
  fun getInstance(context: Context): UsageDatabase =
      INSTANCE ?: synchronized(this) {
          INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
      }
  ```
  The `also { INSTANCE = it }` publishes before the block's value escapes, and the inner `INSTANCE ?:`
  makes the second entrant reuse the first instance.
- **Test to add**: `jvm-unit` â€” `app/src/test/java/com/grayzone/app/data/UsageDatabaseInstanceTest.kt`.
  Room's builder needs a Context, so extract the builder into an injectable
  `internal var builder: (Context) -> UsageDatabase` (or a `@VisibleForTesting` factory hook) and assert
  that a `CountDownLatch`-synchronised burst of 16 threads calling `getInstance` invokes the factory
  **exactly once** and receives 16 identical references. Before the fix the factory is invoked 2+ times
  and distinct references are returned (flaky-by-construction, so gate it on a latch rather than
  timing); after the fix the count is 1 deterministically.
- **Existing test conflict**: none. No existing test touches `getInstance`.
- **Verification**: `confirmed` (evidence tier T1) for the missing check and the duplicate-instance
  outcome â€” both are settled by reading `UsageDatabase.kt:34â€“47`. `needs-verification` for the
  `SQLiteDatabaseLockedException` consequence.
  - *Missing*: a device to demonstrate that two live connection pools actually produce a write
    conflict rather than being absorbed by Android's ~2.5 s busy timeout, and that concurrent
    open + `MIGRATION_1_2` over two helpers is safe.
  - *Procedure*: on any API 29+ device, add temporary logging of `System.identityHashCode(instance)` in
    the builder lambda, run steps 1â€“3 above 20 times (`adb shell am force-stop` + open a monitored app),
    and grep logcat for more than one distinct hash per process. Then, with two instances confirmed,
    drive concurrent `insertEvent` from both and watch for `SQLiteDatabaseLockedException`.
  - *Severity effect*: duplicate instances confirmed and write conflicts confirmed â†’ **High** (silent
    loss of usage events â†’ wrong budgets and streaks, matching the register's "silently" calibration).
    Duplicate instances confirmed but writes always absorbed by the busy timeout â†’ **Medium** (resource
    leak and a latent trap that becomes user-visible the moment any `Flow`/`LiveData` DAO query is
    added, since `InvalidationTracker` is per-instance and observers on the orphan would never see the
    other instance's writes). Not lower than Medium in any outcome: the leaked connection pool is
    unconditional.
- **Build scope**: everyday-build
- **Constitution**: II (one obvious way â€” the standard Room singleton idiom exists and is subtly
  mis-transcribed here)
- **Affected surfaces**: none â€” this is the only double-checked-locking singleton in `app/src/main`.
  Verified: `synchronized(` appears in main source only at `UsageDatabase.kt:35`.
- **Observed against**: `557a963` (file not among the nine uncommitted files)
- **Disposition**: `verified`

### F-041 â€” Room database has no downgrade fallback, so installing an older APK over existing data kills all usage recording â€” silently on three paths and as an app crash on the fourth  [High]

> **Severity revised from Medium to High during this task.** The original write-up asserted that all
> call sites catch and log, so the app degrades silently rather than crashing. That is wrong for one
> path: `HomeScreen.kt:56â€“58` launches `StreakEvaluator.run` in a `rememberCoroutineScope()` with **no**
> `try/catch` and no `CoroutineExceptionHandler`, so the Room open failure propagates and crashes the
> app on Home resume (see F-058, which owns that unguarded caller). The `Severity effect` field below
> already stated "if the exception escapes to a crash on any path â†’ High"; that condition is now
> confirmed statically, so the rating is corrected here rather than left for T034.

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/UsageDatabase.kt` â†’ `UsageDatabase.Companion.getInstance()` (lines 34â€“47)
- **Issue**: The builder registers `MIGRATION_1_2` and deliberately omits
  `fallbackToDestructiveMigration()` â€” correct, and the comment at `:42` says so. But it also omits
  `fallbackToDestructiveMigrationOnDowngrade()`. If a database at schema version 2 is opened by a build
  whose `@Database(version = â€¦)` is lower, Room throws `IllegalStateException("Can't downgrade
  database from version 2 to 1")` from the lazy open â€” i.e. from inside whichever coroutine made the
  first DAO call, not from `getInstance`. Every one of the three write/read paths already wraps the DAO
  call in a broad catch that only logs: `OverlayService.kt:1128â€“1130`, `OverlayService.kt` around the
  `:570` usage write, and `GrayzoneApplication.catchUpStreaks()` `:165â€“167`. On those three paths the app
  does not crash â€” it runs with **all usage recording and streak evaluation permanently dead**, with no
  user-visible signal and no in-app indication that statistics have stopped. **The fourth path is worse**:
  `HomeScreen.kt:56â€“58` calls `StreakEvaluator.run` from a `rememberCoroutineScope()` with no `try/catch`
  and no `CoroutineExceptionHandler`, so there the same `IllegalStateException` escapes and crashes the
  app on Home resume (F-058 owns that missing guard). `CleanupWorker` fares differently again: its catch
  returns `Result.retry()` (`CleanupWorker.kt:62`), which for a `PeriodicWorkRequest` reschedules with
  backoff, so the app retries a permanently-unsatisfiable operation forever.
  This matters more than usual for this project because the app ships an APK-sharing feature
  (`SettingsScreen.kt:82` `shareApk`) and a root-level `INSTALL.md` (untracked), i.e. sideloading is a
  documented distribution path, and sideloading is the way a version downgrade happens.
- **Trigger condition**:
  1. Install the current build and open a monitored app once so at least one `UsageEvent` is written
     and the database is created at version 2.
  2. Build or obtain an APK whose `UsageDatabase` `@Database(version = 1)` (i.e. any build predating
     the index migration) with a lower `versionCode`.
  3. Install it over the top without clearing data: `adb install -r -d app-debug.apk`.
  4. Open a monitored app, complete the friction wait, then background it. Expect a usage event.
  5. Observe: no new row (`adb shell run-as com.grayzone.app sqlite3 â€¦/grayzone_usage.db 'select
     count(*) from usage_events'` is unchanged), the Stats/Home surfaces stop advancing, and logcat
     shows only the swallowed `Failed to log blocked session` / `Streak catch-up on start failed`
     lines. The app itself keeps working, which is what makes it silent.
- **Blast radius**:
  - *Callers*: same four as F-040 â€” `OverlayService.kt:570`, `OverlayService.kt:1117`,
    `CleanupWorker.doWork()` (`:42`), `StreakEvaluator.run()` (`:27`, reached from
    `GrayzoneApplication.kt:164`, `OverlayService.kt:289`, `HomeScreen.kt:57`).
  - *Process death*: no mitigation. The failure is a property of the on-disk file versus the installed
    schema, so it reproduces on every process start until data is cleared or the newer APK is
    reinstalled.
  - *Config change*: no effect.
  - *Foreground/background*: the failure is equally silent in both; the background paths
    (`OverlayService`, `CleanupWorker`) have no UI to surface it, which is why nothing reports it.
  - *Cross-process*: not applicable â€” Room is never opened in `:a11y` (see F-040's *Cross-process*).
  - *Persistence*: yes. Note the correct fix is **not** destructive-on-downgrade by default for the
    lock-bearing state â€” but `usage_events` holds only analytics (sessions, durations, blocked counts),
    not locks or budgets (those live in SharedPreferences), so discarding it on downgrade loses history
    only and is the appropriate trade for keeping recording alive.
  - *Release-only*: no.
- **Fix** (minimal): add `.fallbackToDestructiveMigrationOnDowngrade()` to the builder chain at
  `UsageDatabase.kt:41â€“43`. One line, and it cannot affect the upgrade path because it only engages
  when the stored version exceeds the compiled version.
- **Test to add**: `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/data/DowngradeTest.kt`,
  using `MigrationTestHelper` to `createDatabase(TEST_DB, 2)`, close, then open the database through a
  version-1 `RoomDatabase` configuration and assert the open succeeds with an empty `usage_events`
  rather than throwing `IllegalStateException`. **This test cannot be executed in the current
  environment** â€” no device or emulator is available (see the closing environment note), so it must be
  landed as a pending instrumented test and run by a maintainer.
- **Existing test conflict**: none. `MigrationTest.migrate1To2` only exercises the upgrade direction.
- **Verification**: `needs-verification` (evidence tier T2 â€” the missing builder call is confirmed by
  reading `:34â€“47`; the runtime consequence is inferred from Room's documented behaviour, not observed).
  - *Missing*: a device, plus a version-1 APK, to confirm that Room throws on the downgrade open and
    that the three call-site catches swallow it rather than the app crashing.
  - *Procedure*: steps 1â€“5 above.
  - *Severity effect*: silent permanent loss of recording confirmed â†’ **Medium** as filed (incorrect
    behaviour in narrow conditions â€” it requires a downgrade install, which Android's package manager
    rejects for ordinary user installs without `-d`). If it turns out the exception escapes to a crash
    on any path (e.g. a future call site without a catch) â†’ **High**. If a maintainer confirms
    sideloaded downgrades are a real support case for this app â†’ **High**, because it becomes
    protection-adjacent silent data loss rather than a narrow condition.
- **Build scope**: everyday-build
- **Constitution**: III (an I/O boundary â€” Room's lazy open â€” whose failure is caught and logged only,
  leaving the app running in a degraded state it never reports)
- **Affected surfaces**: `CleanupWorker.doWork()`'s `Result.retry()` on a permanent failure
  (`CleanupWorker.kt:56â€“63`) is the same root cause seen from the worker side; a fix should distinguish
  transient from permanent failures there. Recorded under F-047 rather than duplicated here.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

### F-042 â€” `enableMultiInstanceInvalidation()` is absent; harmless today only because a single early-return keeps Room out of `:a11y`  [Low]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/UsageDatabase.kt` â†’ `UsageDatabase.Companion.getInstance()` (lines 34â€“47)
- **Issue**: The builder does not call `enableMultiInstanceInvalidation()`, so if `grayzone_usage.db`
  were ever opened from a second process, neither process's `InvalidationTracker` would learn about the
  other's writes. **I re-verified whether this is live rather than accepting the carried-in lead, and it
  is not**: `GrayzoneAccessibilityService` is the only class in `:a11y`
  (`android:process=":a11y"` in `AndroidManifest.xml`, one of the nine uncommitted files), it never
  references Room, and `GrayzoneApplication.onCreate` returns at `:54` on
  `Application.getProcessName() != packageName` â€” *before* `catchUpStreaks()` (`:73`) and
  `schedulePeriodicCleanup()` (`:74`), the only two `onCreate` paths that reach Room. So there is
  currently exactly one process opening the database and **no user-visible defect**. What makes this
  worth recording rather than dropping is that the safety property is invisible at the call site: the
  entire guarantee rests on one `return` in a different file, and the app has just been split into two
  processes by uncommitted work. A future change that moves usage logging into `:a11y` â€” the natural
  place for it, since that process observes the app switches â€” would introduce silent stat divergence
  plus a second WAL writer with no compile-time or lint signal.
  Filed as Low per the rubric (maintainability / latent hazard, no current user consequence); it is
  **not** dead code and nothing here should be deleted.
- **Trigger condition**: no observable defect exists at `557a963`. The condition that would make it
  observable, stated so it can be regression-tested rather than rediscovered:
  1. Add any Room access (a DAO call, or moving `logUsageEvent` into the accessibility service) to a
     class that runs in `:a11y`.
  2. Trigger writes from both processes and observe that queries in one process return stale results,
     and that concurrent writes contend on the single WAL writer slot.
- **Blast radius**:
  - *Callers*: as enumerated in F-040 â€” all four are default-process today.
  - *Process death*: not applicable.
  - *Config change*: not applicable.
  - *Foreground/background*: not applicable.
  - *Cross-process*: this **is** the cross-process axis. Current state: one process. The invariant that
    keeps it that way is `GrayzoneApplication.kt:54` plus the comment at `:52â€“53` ("The `:a11y` process
    must NEVER touch prefs, the backup file, WorkManager, or restore"), which correctly states the
    intent but does not mention Room, so the written invariant is narrower than the real one.
  - *Persistence*: yes, indirectly â€” WAL mode is enabled (`:41`), and WAL across processes is
    supported by SQLite but requires the Room-level invalidation opt-in for correctness of cached
    reads.
  - *Release-only*: no.
- **Fix** (minimal): two lines, both documentation-of-invariant rather than behaviour change â€”
  (a) extend the `:52â€“53` comment to name Room explicitly ("â€¦must NEVER touch prefs, the backup file,
  WorkManager, restore, **or the Room database**"), and (b) add a KDoc line on `getInstance` stating
  that single-process access is an invariant and that `enableMultiInstanceInvalidation()` must be added
  if that ever changes. Adding `enableMultiInstanceInvalidation()` pre-emptively is **not** recommended:
  it starts a per-process invalidation service and content-observer machinery for no benefit while the
  invariant holds, which trades a documented assumption for permanent overhead.
- **Rewrite justification**: not applicable â€” no code change proposed.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/SingleProcessRoomInvariantTest.kt`, a guard test asserting
  that the set of main-source files referencing `UsageDatabase` is exactly
  {`UsageDatabase.kt`, `CleanupWorker.kt`, `StreakEvaluator.kt`, `OverlayService.kt`} by scanning
  `app/src/main` from the test's working directory. Fails when a future change adds a fifth referencing
  file, forcing an explicit decision about which process it runs in. This is the only mechanism
  available: nothing in Kotlin's type system or in lint encodes process affinity.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). Both halves were established statically:
  the missing builder call by reading `UsageDatabase.kt:34â€“47`, and the single-process invariant by
  searching all of `app/src` for `UsageDatabase` (four main-source files, all default-process) and by
  reading the `GrayzoneApplication.onCreate` guard at `:54`. This corroborates â€” from the Room side and
  by independent search â€” the `cross-process-inventory.md` conclusion that `:a11y` touches no shared
  state.
- **Build scope**: everyday-build
- **Constitution**: IV (a cross-process invariant that is enforced in one file and documented in
  another, with the documentation narrower than the invariant)
- **Affected surfaces**: the same "safe only because `:a11y` never reads it" argument covers
  SharedPreferences coherence, already carried in `evidence/cross-process-inventory.md`, and
  `ProtectionStatus.accessibilityActive` (see F-059). All three share one root cause: process affinity
  is an unwritten convention.
- **Observed against**: `working-tree @ 557a963` (uncommitted: `AndroidManifest.xml` introduces
  `android:process=":a11y"`, and `GrayzoneApplication.kt` introduces the `:54` guard â€” so both sides of
  this hazard originate in uncommitted work)
- **Disposition**: `verified`

### F-043 â€” `StateBackupManager.applyInto` restores arbitrary preference keys with file-supplied types and never reads `SCHEMA_VERSION`, so the anti-tamper backup is itself the poisoning vector  [Critical]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’ `StateBackupManager.applyInto()` (lines 229â€“251), with its counterpart `serialize()` (lines 200â€“227)
- **Issue**: `applyInto` applies an external file to SharedPreferences with **three** distinct validations
  absent, and it is the upstream cause of the audit's most severe finding (F-060 / C7).
  1. **No schema gate.** `serialize` stamps the envelope with `"schema" = SCHEMA_VERSION` at `:202`, but
     `applyInto` reads only `root.getAsJsonObject("entries")` (`:231`). `SCHEMA_VERSION` has exactly
     **two** occurrences in `app/src` â€” its declaration at `:61` and that write at `:202` â€” verified by
     direct search over the whole source tree, not taken from the reference index. So a snapshot
     produced by *any* schema version is applied key-for-key. The constant exists solely to be written.
     The same is true of the other two envelope fields: `"androidId"` (`:203`) and `"savedAt"` (`:204`)
     are also written and never read. The entire envelope is write-only; only `entries` is consumed.
  2. **No key allowlist.** The loop at `:233` iterates `entries.entrySet()` and writes every key it
     finds. Because `writeBackup` snapshots `prefs.all` unfiltered (`:167`), the file already contains
     orphaned keys (the prefs inventory records `grayscale_enabled` and `budget_locked_`), per-app keys
     for packages that may no longer be installed, and anything any past or future version ever wrote.
     All of it is restored unconditionally.
  3. **The type of every write is chosen by the file, not by the app.** `obj.get("t")?.asString`
     (`:235`) selects among `putBoolean` / `putInt` / `putLong` / `putFloat` / `putString` /
     `putStringSet`. Nothing cross-checks the key against the type the app actually reads it with, so
     the on-disk file is the sole authority for each preference's Java type.
  SharedPreferences is type-strict: `getInt` on a key stored as a `String` throws
  `ClassCastException`. That is precisely the chain **F-060 (Critical, C7)** owns â€”
  `GrayzoneApplication.onCreate` calls `restoreIfWiped` at `:64` and then reads those values with
  unguarded typed getters at `:181`, `:190`, `:199`, `:208`, followed by
  `resetRuntimeStateIfNeeded()` at `:71` â†’ `RuntimeSessionReset.resetIfNeeded` (`:29`, `:36`). The
  consequence (an unlaunchable app that clear-data cannot repair) is F-060's to state and is not
  restated here. **What this finding contributes is the missing validation that makes it possible**:
  `applyInto` will write any key with any type on the word of a file that lives outside the sandbox,
  and the one field that could have gated it is written but never read.
  A fourth, lesser property is worth recording because it constrains the fix: a single malformed entry
  aborts the *entire* restore. `obj.get("v")` returns `null` when `"v"` is absent (â†’ NPE) and
  `asInt` on a non-numeric string throws `NumberFormatException`; either escapes `applyInto` and is
  caught by `restoreIfWiped`'s outer handler at `:128â€“131`, which returns `false` after a
  `GrayzoneLogger.w` (one of the two levels that actually emit â€” see F-064). Because the exception
  precedes `editor.commit()` at `:250`, **nothing partial is written**, which is a genuine positive and
  must be preserved: the restore is atomic today and a per-entry `try/catch` fix would destroy that.
  The cost of the atomicity is that one bad entry silently means no anti-tamper restore at all.
- **Trigger condition**:
  1. On an API 30+ device with all-files access granted and onboarding completed, confirm the snapshot
     exists: `adb shell ls -l /sdcard/.grayzone/backup.dat`.
  2. Establish a type mismatch between the snapshot and the running build. **The realistic,
     no-attacker path is version drift**: with version N installed, let the flush loop write a snapshot
     (`FLUSH_INTERVAL_MS` = 120 s, `:75`, or simply background the app â€” `GrayzoneApplication.kt:116`).
     Then install version N+1 in which any key present in that snapshot is read with a different typed
     getter. This is not hypothetical: `evidence/prefs-inventory.md` records a real change of exactly
     this shape â€” `grayscale_enabled` (Boolean) became `theme_mode` (String) â€” which escaped only
     because the *name* changed at the same time. There is no SharedPreferences migration anywhere in
     the app, so nothing would catch a rename-free type change.
  3. Clear app data: Settings â†’ Apps â†’ Grayzone â†’ Storage â†’ Clear storage. Confirm the appop survived
     the wipe: `adb shell appops get com.grayzone.app MANAGE_EXTERNAL_STORAGE` â†’ `allow`.
  4. Launch the app. `onCreate` restores the stale-typed snapshot (`:64`) and then
     `validateConfiguration()` (`:70`) calls `prefs.getInt(PrefsKeys.WAIT_SECONDS, â€¦)` (`:181`) against
     a value stored as a `String`.
  5. Observe that the app does not launch, and that repeating step 3 does not help â€” the poisoned file
     is still present and the appop still granted, so the same snapshot is restored again. Note also
     that the app never gets far enough to fix itself: `registerStateBackup()`, the only thing that
     would overwrite the bad snapshot with a good one, is at `:75`, five lines *after* the throw.
  6. For completeness, the direct-forgery variant: craft `entries` containing
     `{"wait_seconds":{"t":"string","v":"5"}}` and encrypt it with the app's scheme (SHA-256 of
     `ANDROID_ID|KEY_PEPPER`; `KEY_PEPPER` is a compile-time constant at `:87`). **This variant needs
     root and is not the path that matters** â€” on API 26+ `Settings.Secure.ANDROID_ID` is scoped per
     app-signing-key, so neither another app nor `adb shell` can read the value the app derives its key
     from. Step 2 is the path a maintainer should reproduce.
- **Blast radius**:
  - *Callers*: `applyInto` is `private` with exactly one caller â€” `restoreIfWiped` (`:117`). Verified by
    direct search over `app/src`: `applyInto` appears only at its declaration and that call.
    `restoreIfWiped` has exactly two main-source call sites (plus its declaration and a KDoc mention at
    `:50`): `GrayzoneApplication.onCreate` (`GrayzoneApplication.kt:64`) and `MainActivity.onResume`
    (`MainActivity.kt:48`, guarded by `hasAllFilesAccess` at `:47`). The effective blast radius is
    every typed reader of the restored keys: `validateConfiguration()`
    (`GrayzoneApplication.kt:181, 190, 199, 208`), `RuntimeSessionReset.resetIfNeeded`
    (`RuntimeSessionReset.kt:29, 36`), `OverlayService.buildSessionState` (`:439â€“467`),
    `OverlayService` `:835â€“837`, `:1051â€“1053`, `:1104â€“1108`, `ScheduleManager`, `StreakManager`, and
    `AdBlockVpnService.kt:261`.
  - *Process death*: makes it worse rather than better. The restore is in `Application.onCreate`, so
    every process start re-applies the file; the only gate is the `ONBOARDING_COMPLETED` sentinel
    (`:107`), which is absent exactly when the poison is applied. The state is self-sustaining because
    the code that would refresh the snapshot (`registerStateBackup()`, `GrayzoneApplication.kt:75`) is
    ordered after the read that throws.
  - *Config change*: no effect â€” neither `applyInto` nor `restoreIfWiped` holds Activity-scoped state.
  - *Foreground/background*: the second entry point is `MainActivity.onResume`, before
    `super.onResume()`, so a snapshot that only becomes readable when the user returns from granting
    all-files access is applied during a resume; the resulting throw then lands in the Activity resume
    path rather than in `Application.onCreate`. Same root cause, different stack, and the fix must
    cover both.
  - *Cross-process*: not reachable from `:a11y`. `GrayzoneApplication.onCreate` returns at `:54` before
    `:64`, and the comment at `:52â€“53` states the requirement explicitly ("The `:a11y` process must
    NEVER touch prefs, the backup file, WorkManager, or restore â€” SharedPreferences isn't multi-process
    safe and two writers corrupt the backup"). Verified. One cross-process consequence does follow
    though: the crash kills only the default process, so `GrayzoneAccessibilityService` keeps running
    in `:a11y` and can still fire `startForegroundService` at `GrayzoneAccessibilityService.kt:93â€“99`
    into a process that dies in `onCreate` â€” the user sees an app that will not open while its
    protection machinery keeps thrashing.
  - *Persistence*: yes, on both sides. Reads `/sdcard/.grayzone/backup.dat` â€” deliberately outside the
    sandbox â€” and writes SharedPreferences `GrayzonePrefs` with `commit()` (`:250`). The write is
    all-or-nothing, which is a property to preserve (see the *Issue* section's fourth point).
  - *Release-only*: the defect is not release-only, but the *diagnosability* of its crash is:
    `-keepattributes SourceFile,LineNumberTable` is absent from `app/proguard-rules.pro` (carried in
    from T030/T021), so a release stack trace for this `ClassCastException` carries no line numbers.
    That makes the proguard change a practical prerequisite for triaging any field report of this.
- **Fix** (minimal): three additive checks inside the existing loop â€” no restructuring of the file,
  no change to the crypto or the atomic-commit behaviour.
  1. Read the field that already exists: `if (root.get("schema")?.asInt != SCHEMA_VERSION) return false`
     â€” the gate `SCHEMA_VERSION` was evidently written for.
  2. **Make the app, not the file, the authority on each key's type.** `PrefsKeys` already centralises
     every key, so add one `internal val RESTORABLE: Map<String, PrefType>` there (per-app keys matched
     against the existing prefix constants `PER_APP_WAIT_SECONDS`, `PER_APP_SESSION_MINUTES`,
     `PER_APP_LOCKOUT_MINUTES`, `ACTIVE_UNTIL`, `LOCKED_UNTIL`, â€¦), and in the loop skip any key absent
     from the map and any entry whose `"t"` disagrees with the expected type, logging each skip with
     `GrayzoneLogger.w` so a drifting snapshot is visible in the field.
  3. Clamp on the way in for keys that already have a validator: route the three global ints through
     `ConfigValidator.repairWaitSeconds` / `repairSessionMinutes` / `repairLockoutMinutes`, and the
     per-app ints through the same ranges, so a restored value can never be outside what the UI can
     produce.
  **If only one change is affordable, it is step 2.** It alone removes both the type crash (the app's
  expectation wins over the file's claim) and the arbitrary-key injection. Step 1 alone is *not*
  sufficient â€” within a single schema version a drifted type is still applied â€” which is worth stating
  because "bump the schema check" is the tempting one-liner and it does not fix this.
- **Rewrite justification**: not applicable. All three changes are additive guards inside the existing
  `for` loop; the surrounding structure, the AES-GCM envelope, and the deliberate `commit()` (`:248â€“250`)
  are all correct as written and must not be replaced. In particular the `commit()` must **not** be
  swapped for `apply()` â€” the comment at `:248â€“249` correctly explains why it is synchronous â€” the
  threading of the whole chain is a separate finding (F-044).
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupRestoreTest.kt`. `applyInto` and `serialize` are
  `private`; widen them to `internal` with `@VisibleForTesting` and drive them with a
  `mutableMapOf<String, Any?>`-backed `SharedPreferences`/`Editor` test double â€” neither function
  touches Android beyond those two interfaces, so no Robolectric and, notably, **no dependency on the
  missing `testOptions { unitTests.isReturnDefaultValues = true }`** that constrains several sibling
  findings' tests. Four assertions: (a) a snapshot whose `"schema"` is `2` is rejected, returns
  `false`, and writes nothing; (b) a key absent from the allowlist is not written; (c) an entry
  declaring `{"t":"string"}` for `wait_seconds` is skipped rather than stored as a `String`; (d) a
  well-formed in-range int key round-trips through `serialize` â†’ `applyInto` unchanged. (a)â€“(c) fail
  before the fix â€” today the schema is ignored, the unknown key is written, and the poisoned `String`
  is stored â€” and pass after.
- **Existing test conflict**: none. `StateBackupManager` has **zero** occurrences in `app/src/test` and
  `app/src/androidTest` (direct search) â€” the app's central anti-tamper mechanism has no test of any
  kind, which corroborates the "6 of 10 prior fixes have no automated test" process-gap lead assigned
  to T029.
- **Verification**: `confirmed` (evidence tier T1) for every element of the missing validation. Each was
  established by reading source and by direct search rather than from the reference index: the absent
  schema read (`SCHEMA_VERSION` = 2 occurrences in `app/src`, `:61` and `:202`), the write-only
  `androidId`/`savedAt` envelope fields (`:203`, `:204`, no readers), the absent allowlist (`:233â€“247`),
  the file-supplied typing (`:235`), and the unguarded downstream typed reads
  (`GrayzoneApplication.kt:181, 190, 199, 208`). Only the end-to-end boot loop is
  `needs-verification`, and F-060 owns that.
  - *Missing*: device execution of trigger steps 3â€“5, plus confirmation on the target OEM that
    `MANAGE_EXTERNAL_STORAGE` survives clear-data.
  - *Procedure*: steps 1â€“5 above; the `appops get` check in step 3 is the decisive one.
  - *Severity effect*: appop survives clear-data â†’ **Critical**, as filed and as C7 records, because the
    app is unlaunchable and the user cannot recover without deleting a hidden file they were never told
    about. Appop revoked on clear-data by a particular OEM â†’ the crash becomes recoverable in one
    clear-data and *that path* is **High** â€” but the finding stays **Critical** regardless, because the
    same missing gates carry the value-poisoning path (F-112: a restored `per_app_wait_<pkg>` of `0`
    makes friction instantaneous), which produces no crash, no notification, and therefore is not
    recovered by anything the user would think to do. Per the register's calibration, silent protection
    loss ranks at or above a visible error.
- **Build scope**: everyday-build
- **Constitution**: III (an I/O boundary â€” an untrusted file deliberately stored outside the sandbox â€”
  parsed and applied to security-relevant state with no validation of schema, key, type, or range)
- **Affected surfaces**: `serialize()` (`:200â€“227`) is the write half of the same root cause â€” it
  snapshots `prefs.all` unfiltered at `:167`, which is what puts unknown and orphaned keys into the file
  to begin with, so the allowlist belongs on both sides; `RuntimeSessionReset.resetIfNeeded`
  (`RuntimeSessionReset.kt:29, 36`) and `OverlayService.buildSessionState` (`:439â€“467`) are further
  unguarded typed readers of restored values. Related findings owned elsewhere, cited not re-filed:
  **F-060** (the `ClassCastException`-in-`onCreate` consequence, Critical, C7), **F-112** (per-app wait
  seconds consumed unvalidated at `OverlayService.kt:835â€“843`), **F-063**
  (`flushBackupIfDirty` clears the dirty flag before the write succeeds), and RU-06's `vpn_bypass_apps`
  finding (`findings-ru06.md`) â€” a key with a live reader and no writer, whose *only* possible writer is
  this restore path, meaning this function is the sole mechanism by which arbitrary packages could be
  exempted from DNS filtering.
- **Observed against**: `working-tree @ 557a963` (`StateBackupManager.kt` is **not** among the nine
  uncommitted files, so this defect predates the working-tree diff; the `GrayzoneApplication.kt`
  `:54` process guard and the `:64`â†’`:70` ordering that complete the chain *are* uncommitted)
- **Disposition**: `verified`

### F-044 â€” `restoreIfWiped` does shared-storage read, AES-GCM decrypt, Gson parse and a blocking `commit()` on the main thread inside `Application.onCreate`, on the same critical path as a `startForegroundService` deadline  [High]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’ `restoreIfWiped()`
  (lines 104â€“131) â†’ `readBackup()` (186â€“198) â†’ `decrypt()` (281â€“289) â†’ `applyInto()` (229â€“251, the
  `commit()` at `:250`)
- **Issue**: When the sentinel path is taken, one synchronous main-thread call chain performs: an
  AppOps binder round-trip (`Environment.isExternalStorageManager()`, `:139`), a `File.exists()` and a
  full `File.readBytes()` against `/sdcard/.grayzone/backup.dat` (`:189â€“190`), an AES-256-GCM decrypt
  (`:281â€“289`), a Gson parse of the whole document (`:230`), and a `SharedPreferences.commit()` (`:250`)
  â€” which fsyncs the prefs XML before returning. On Android 11+ `/sdcard` is served through **FUSE**
  (sdcardfs was removed), so reads there have materially higher and less predictable latency than
  app-internal storage, and all-files access does not change that.
  **I traced every call chain rather than trusting the dispatcher claims, and both entry points are the
  main thread**: `GrayzoneApplication.onCreate` (`:64`) is a framework main-thread callback with no
  dispatcher, and `MainActivity.onResume` (`:48`) likewise, executing *before* `super.onResume()`.
  Neither is wrapped in `withContext` or a coroutine.
  The sharpest consequence is not general jank, it is a missed foreground-service deadline.
  `Application.onCreate` runs **before** `Service.onCreate` in a process started for a service, and
  `GrayzoneAccessibilityService.kt:93â€“99` starts `OverlayService` via `startForegroundService`, which
  gives the app roughly 5 seconds to reach `startForeground`. Time spent in `restoreIfWiped` is charged
  against that budget, so a slow FUSE read plus crypto plus fsync can produce
  `ForegroundServiceDidNotStartInTimeException` â€” i.e. **protection never starts**, on precisely the
  code path whose purpose is to make protection survive a wipe. This is the same deadline F-205
  identifies from the `startForeground` side.
  The code asserts its own safety without enforcing it: the KDoc at `:99â€“100` says "Runs synchronously
  â€¦ Keep the payload small (control state only)" and `GrayzoneApplication.kt:63` repeats "Synchronous by
  design and kept small so it never ANRs onCreate". Nothing keeps it small â€” `writeBackup` serialises
  `prefs.all` (`:167`), which grows with monitored apps (four to five keys per package, per
  `PrefsKeys.kt:45â€“48`), plus `SCHEDULE_RULES_JSON` and `CUSTOM_PROMPTS_JSON`, both unbounded strings.
- **Correction to a carried-in lead â€” this is not "on every `MainActivity.onResume`".** The register's
  leads table states "Main-thread storage read + AES-GCM decrypt + Gson parse + `commit()` on every
  `MainActivity.onResume`". Verified against source and it is narrower: `restoreIfWiped` short-circuits
  at `:107` on `prefs.contains(PrefsKeys.ONBOARDING_COMPLETED)` **before any file I/O**, so for a
  user who has completed onboarding the resume path costs only the binder call plus a cached prefs
  lookup. The full chain runs when the sentinel is absent, which is (a) throughout the entire onboarding
  flow â€” where the user bounces repeatedly to system Settings for accessibility, overlay, battery and
  all-files access, generating an `onResume` each time â€” and (b) after any data wipe, which is the
  designed trigger. What *does* run on every single resume, main thread, before `super.onResume()`, is
  the `hasAllFilesAccess(this)` binder call at `MainActivity.kt:47`. The finding stands at High on the
  `onCreate` path; the leads table should be amended so T034 does not tier the fix on a false frequency.
- **Trigger condition**:
  1. Grant all-files access and complete onboarding on an API 30+ device, then use the app long enough
     to accumulate a realistic prefs set (10+ monitored apps with per-app overrides, several schedule
     rules, custom prompts).
  2. Clear app data so the sentinel is absent and a backup exists:
     `adb shell pm clear com.grayzone.app`, then confirm the appop survived
     (`adb shell appops get com.grayzone.app MANAGE_EXTERNAL_STORAGE`) and the file is present
     (`adb shell ls -l /sdcard/.grayzone/backup.dat`).
  3. Cause the process to be started *for the service*, not for the UI, so the
     `startForegroundService` deadline is in play â€” open a monitored app so
     `GrayzoneAccessibilityService.kt:93â€“99` fires while the app's default process is dead.
  4. Measure: `adb logcat -s ActivityManager:* StrictMode:*` and watch for
     `Slow operation`/`ForegroundServiceDidNotStartInTimeException`, and time `onCreate` by adding a
     temporary `SystemClock.elapsedRealtime()` delta around `:64`.
  5. Amplify to make the effect visible on a fast device: place a large file at the same path or run
     step 3 while the device is under I/O load (`adb shell dd if=/dev/zero of=/sdcard/ballast bs=1M
     count=2000`), which is a fair test because FUSE latency is exactly what is unbounded here.
- **Blast radius**:
  - *Callers*: two, both main-thread â€” `GrayzoneApplication.onCreate` (`GrayzoneApplication.kt:64`) and
    `MainActivity.onResume` (`MainActivity.kt:48`). Enumerated by direct search over `app/src`;
    `restoreIfWiped` has no other reference except its declaration and the KDoc mention at `:50`.
    Downstream in `onCreate`, five more operations queue behind it: `validateConfiguration()` (`:70`),
    `resetRuntimeStateIfNeeded()` (`:71`), `runDeferredStartupWork()` (`:72`), `catchUpStreaks()`
    (`:73`), `schedulePeriodicCleanup()` (`:74`), `registerStateBackup()` (`:75`).
  - *Process death*: this is the *cause* of a potential process death (ANR kill or foreground-service
    timeout), and every subsequent process start repeats the work while the sentinel is absent, so a
    slow device can loop: start â†’ slow restore â†’ service timeout â†’ restart.
  - *Config change*: a rotation of `MainActivity` triggers `onResume` again, so the `:47` binder call
    runs once per rotation. Immaterial while the sentinel is present; during onboarding it repeats the
    stat of `/sdcard`.
  - *Foreground/background*: the worst case is background-initiated (step 3), when there is no Activity
    and the process exists only to serve `OverlayService`. A fix must therefore not depend on an
    Activity or on `lifecycleScope`.
  - *Cross-process*: not reachable from `:a11y` (`GrayzoneApplication.kt:54` returns first). But the
    *victim* is cross-process: the timeout is imposed by a `startForegroundService` call originating in
    `:a11y`, and that side's guard at `GrayzoneAccessibilityService.kt:100â€“105` cannot catch a deadline
    missed in the default process.
  - *Persistence*: yes â€” reads `/sdcard/.grayzone/backup.dat` and writes `GrayzonePrefs` via a blocking
    `commit()` (`:250`). The `commit()` is **deliberate and correctly justified** by the comment at
    `:248â€“249`; a fix must not substitute `apply()`, which would break the ordering guarantee the whole
    feature depends on. The defect is the threading of the chain, not the choice of `commit()`.
  - *Release-only*: no.
- **Why Lint missed it, and why that matters for the fix**: `ApplySharedPref` does not fire here because
  the call site *uses* `commit()`'s return value (`return editor.commit()`), which suppresses the check.
  Meanwhile Lint did flag two `ApplySharedPref` sites in `OverlayService.kt` (`:531`, `:600`) that both
  turned out to be on `Dispatchers.IO` and were correctly downgraded to Low. So the tool flagged two
  non-issues and missed the real one â€” every dispatcher in this finding was traced by hand, and the same
  should be assumed necessary elsewhere.
- **Fix** (minimal): keep the synchronous restore â€” the ordering requirement documented at
  `GrayzoneApplication.kt:61â€“63` is real and correct â€” and instead bound its cost and stop paying it
  when it cannot possibly be needed.
  1. In `MainActivity.onResume`, swap the two conditions at `:47â€“48` so the cheap sentinel check runs
     before `hasAllFilesAccess()`. That removes a per-resume main-thread binder call for every
     set-up user, and changes no behaviour: `restoreIfWiped` already returns `false` in that case.
  2. Bound the payload for real by restoring only an allowlisted key set â€” **the same allowlist F-043
     requires**, so one change satisfies both findings. This converts "kept small" from a comment into
     an invariant and is what makes the synchronous read defensible.
  3. Install a `StrictMode.ThreadPolicy` with `detectDiskReads()`/`detectDiskWrites()` in `onCreate`
     for non-release builds so any future regression here is loud rather than silent. Note this needs a
     dedicated `BuildConfig` flag rather than `BuildConfig.DEBUG`, which is `false` in every variant
     this project builds (F-064).
- **Rewrite justification**: not applicable; no component is replaced. Explicitly *rejected* as a fix:
  moving `restoreIfWiped` to a background dispatcher, because the comment at
  `GrayzoneApplication.kt:61â€“63` establishes that the restored values must be visible before
  `validateConfiguration()` and before any service or UI reads preferences on the same launch. Making it
  async would require a start-up barrier that every reader honours â€” a much larger change than the
  defect warrants, and one that would introduce a new class of ordering bug.
- **Test to add**: two, because the cheap half and the expensive half need different mechanisms.
  (a) `jvm-unit` â€” `app/src/test/java/com/grayzone/app/data/StateBackupPayloadBoundTest.kt`, asserting
  that `serialize()` (widened to `internal`) applied to a realistic prefs map of 25 monitored apps with
  full per-app overrides plus a 4 KB `SCHEDULE_RULES_JSON` produces output under an explicit byte cap,
  and that keys outside the allowlist are excluded. Fails before the fix (today `prefs.all` is copied
  wholesale, so the output grows without bound); passes after.
  (b) `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/StartupStrictModeTest.kt`, installing
  `StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build()` before
  `Application.onCreate` and asserting the process survives startup with the sentinel absent and a
  backup file present. **This test cannot be executed in the current environment** â€” no device or
  emulator is available â€” so it must be landed pending and run by a maintainer.
- **Existing test conflict**: none. `StateBackupManager` has zero occurrences in `app/src/test` and
  `app/src/androidTest`.
- **Verification**: `confirmed` (evidence tier T1) that the chain is synchronous on the main thread at
  both call sites, and that each element of the chain is present â€” all established by reading
  `StateBackupManager.kt:104â€“131, 186â€“198, 229â€“251, 281â€“289`, `GrayzoneApplication.kt:49â€“76` (a
  framework main-thread callback with no dispatcher) and `MainActivity.kt:42â€“53`.
  `needs-verification` for the magnitude and for the foreground-service-timeout consequence.
  - *Missing*: a device to measure actual `onCreate` duration with a realistic payload on FUSE-backed
    shared storage, and to demonstrate a `ForegroundServiceDidNotStartInTimeException` or an ANR. No
    device or emulator is available here, and this is a latency claim â€” it cannot be settled statically.
  - *Procedure*: steps 1â€“5 above. The decisive measurement is the `onCreate` delta in step 4 on a
    low-end API 30â€“34 device with the step 5 I/O ballast in place.
  - *Severity effect*: measured `onCreate` cost pushes past the `startForegroundService` window, or an
    ANR is observed â†’ **High** as filed (protection fails to start, with the only trace in logcat, and
    `d()`/`i()` are no-ops so even that trace is thin). Cost measures in low tens of milliseconds even
    under load â†’ **Medium**, because the defect reduces to main-thread I/O on a rare path plus a
    per-resume binder call, which is a real but narrow correctness-of-threading issue rather than a
    protection failure. It does not drop to Low in either outcome: main-thread disk I/O in
    `Application.onCreate` is a defect on its own terms, and the payload is unbounded by construction.
- **Build scope**: everyday-build
- **Constitution**: III (I/O boundary on the startup critical path whose failure mode â€” exceeding a
  framework deadline â€” is neither handled nor observable)
- **Affected surfaces**: `writeBackup()` (`:160â€“179`) performs the mirror-image work (serialise +
  encrypt + `writeBytes`) but is correctly called from `appScope` (`Dispatchers.IO`) at
  `GrayzoneApplication.kt:98` and `:139` â€” a credited positive that shows the codebase knows the right
  pattern and applies it on the write side only. `MainActivity.kt:47` (the per-resume binder call) is
  RU-01's file; the one-line reordering belongs to this fix, so T034 should route it here rather than
  opening a parallel RU-01 item. F-205 (`startForeground` unguarded) shares the deadline; F-064
  (`d()`/`i()` are no-ops) is why a timeout here would be nearly undiagnosable.
- **Observed against**: `working-tree @ 557a963` (`StateBackupManager.kt` and `MainActivity.kt` are not
  uncommitted; `GrayzoneApplication.kt` and `GrayzoneAccessibilityService.kt` â€” the `onCreate` ordering
  and the `startForegroundService` watchdog that creates the deadline â€” both are)
- **Disposition**: `fix-pending-device-verification`

### F-045 â€” The backup envelope stamps `savedAt` but never reads it, so replacing the file with an older copy rolls every lock back â€” no key, no root, and no permission required  [High]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’ `applyInto()`
  (lines 229â€“251) and `serialize()` (lines 200â€“227, the `savedAt` write at `:204`)
- **Issue**: `serialize` records `"savedAt" = System.currentTimeMillis()` (`:204`) and `applyInto` never
  reads it â€” verified by direct search: `savedAt` has exactly one occurrence in `app/src`, that write.
  There is consequently **no freshness, monotonicity or rollback check** anywhere in the restore path.
  Combined with the fact that the snapshot lives at a fixed, unauthenticated-by-position path
  (`/sdcard/.grayzone/backup.dat`, `:183â€“184`) and is overwritten in place (`:171`), the anti-tamper
  mechanism can be defeated by **copying the file aside early and copying it back later** â€” a pure
  replay. The attacker needs nothing the design tries to withhold: the payload stays encrypted and the
  GCM tag stays valid, because it is the app's own genuine ciphertext, merely from an earlier moment.
  Concretely: right after onboarding (before locks and limits are configured) the file encodes a
  permissive state. `adb pull` it, or copy it with any file manager or over MTP â€” `/sdcard` is
  user-visible storage and the hidden `.grayzone` folder is only hidden from media scanners, not
  access-controlled. Later, clear app data, push the old copy back, and launch: `restoreIfWiped` sees
  the sentinel absent, decrypts successfully, and applies the permissive state. Every lockout, every
  budget, every schedule rule is gone, and the app reports success â€” `GrayzoneLogger.i` at `:119â€“122`,
  "State restored from external backup after a data/cache wipe", which is additionally a no-op in every
  variant this project builds (F-064).
  The same reasoning gives an even shorter path that the KDoc's "Honest limitations" section
  (`:48â€“53`) does not list: **`rm /sdcard/.grayzone/backup.dat` before clearing data**. The listed
  limitations cover a revoked permission and a factory reset, but not deletion or replay of the file by
  the very adversary the threat model names â€” the user. This is a documentation gap as well as a code
  gap, and it matters because the README advertises "Anti-Bypass Protection".
  This is filed separately from F-043 because it is a different missing check (freshness, not
  type/allowlist), with a different harm (silent total lock bypass, not a crash) and a different fix
  (a monotonic counter, not a key allowlist). It is not a duplicate of F-043 and must not be merged
  into it â€” merging would corrupt the fix tiering, since a maintainer could complete F-043's allowlist
  fully and leave this wide open.
- **Trigger condition**:
  1. Fresh install on an API 30+ device. Complete onboarding, grant all-files access, but configure **no**
     locks yet.
  2. Wait for the baseline write (`GrayzoneApplication.kt:96â€“100` writes one if none exists) and copy
     the permissive snapshot off-device: `adb pull /sdcard/.grayzone/backup.dat ./clean.dat`. No root.
  3. Now configure real protection: monitor several apps, set short sessions and long lockouts, add a
     bedtime schedule rule. Background the app so the dirty flush overwrites the snapshot
     (`GrayzoneApplication.kt:116`).
  4. Trigger a lockout on a monitored app and confirm the lock screen appears.
  5. Clear app data (Settings â†’ Apps â†’ Grayzone â†’ Storage â†’ Clear storage), then restore the old file:
     `adb push ./clean.dat /sdcard/.grayzone/backup.dat`.
  6. Launch the app. Observe that it restores "successfully" into the permissive pre-lock state: no
     monitored apps, no lockouts, no schedule. Protection is gone with no error and no notification â€”
     the failure mode the register's calibration ranks at or above a visible crash.
  7. Variant with even fewer steps: at step 5 run `adb shell rm /sdcard/.grayzone/backup.dat` instead of
     pushing, then clear data. `readBackup` returns `null` at `:189` and `restoreIfWiped` logs "Prefs
     look wiped but no external backup was found to restore" at `:111â€“114` â€” via `GrayzoneLogger.i`,
     which emits nothing (F-064). The wipe is completely successful and completely silent.
- **Blast radius**:
  - *Callers*: `applyInto` â† `restoreIfWiped` (`:117`) â† `GrayzoneApplication.onCreate`
    (`GrayzoneApplication.kt:64`) and `MainActivity.onResume` (`MainActivity.kt:48`). The write side that
    creates the replayable artifact is `writeBackup` (`:160â€“179`) â† `GrayzoneApplication.kt:98`
    (baseline) and `:139` (`flushBackupIfDirty`). All enumerated by direct search over `app/src`.
  - *Process death*: no mitigation â€” the check that is missing would be a persisted one, and process
    death is irrelevant to it. Restore re-runs on every process start while the sentinel is absent.
  - *Config change*: no effect.
  - *Foreground/background*: the replay is applied on whichever entry point runs first after the swap,
    `Application.onCreate` for a cold start or `MainActivity.onResume` if the user is already in the app
    when access is granted. Both paths are equally unguarded.
  - *Cross-process*: not reachable from `:a11y` (`GrayzoneApplication.kt:54`). Relevant nonetheless: the
    comment at `:52â€“53` warns that "two writers corrupt the backup", showing the authors reasoned about
    concurrent writers but not about an external writer, which is the actual threat here.
  - *Persistence*: yes, and this finding is entirely about persistence semantics â€” the file is treated
    as trusted durable state while being an ordinary user-writable file in shared storage. Any fix must
    persist its freshness marker somewhere the user cannot roll back in the same motion, which is the
    hard part (see *Fix*).
  - *Release-only*: no.
- **Fix** (minimal): give the snapshot a monotonically increasing generation counter and refuse to apply
  one that goes backwards.
  1. In `serialize`, replace or supplement `"savedAt"` with `"generation"`, an integer stored in
     SharedPreferences and incremented on every `writeBackup`.
  2. In `applyInto`, read it and reject a snapshot whose generation is lower than the highest ever seen
     â€” which is the honest difficulty: after clear-data the app has no local memory of the high-water
     mark. The minimal durable place that survives clear-data but not uninstall is a second tiny file
     alongside the snapshot holding the signed high-water mark; a user who rolls back the snapshot but
     not the marker is rejected, and a user who rolls back both is detectable because the marker's
     signature covers the generation. This raises the bar from "copy one file" to "understand the
     scheme", which is the correct target â€” the KDoc at `:84â€“86` already states the intended threat
     model as "obfuscation-grade â€” enough to lock out a curious user, not a reverse-engineer".
  3. Independently and immediately: extend the "Honest limitations" list at `:48â€“53` to state that
     deleting or replaying the file defeats the backup. That is a two-line documentation change, it
     costs nothing, and it stops the next reader from over-trusting the mechanism.
- **Rewrite justification**: not a rewrite. Step 2 adds a file; it does not restructure the manager.
  Stating the alternative honestly: a *complete* fix is not achievable on-device against a user with
  file access â€” that is inherent, exactly as the KDoc says about the permission. The goal of the fix is
  to close the trivial no-knowledge path, not to become tamper-proof, and the finding should not be
  actioned as if the latter were possible.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupRollbackTest.kt`, driving `internal`-widened
  `serialize`/`applyInto` with a map-backed `SharedPreferences` double: assert that applying a snapshot
  whose generation is lower than the recorded high-water mark returns `false` and writes nothing, that
  an equal generation is also rejected, and that a higher generation applies. All three fail before the
  fix, because no generation is read at all today.
- **Existing test conflict**: none â€” no test references `StateBackupManager`.
- **Verification**: `confirmed` (evidence tier T1) for the missing check: `savedAt` has exactly one
  occurrence in `app/src` (the write at `:204`), `applyInto` reads only `entries` (`:231`), and the path
  is a fixed constant pair at `:66â€“67` resolved against `Environment.getExternalStorageDirectory()`
  (`:183â€“184`). `needs-verification` for the end-to-end replay.
  - *Missing*: a device to confirm that `/sdcard/.grayzone/backup.dat` is readable and writable via
    `adb pull`/`adb push` and via an ordinary file-manager app without root on the target API level,
    and that a replayed snapshot is accepted.
  - *Procedure*: steps 1â€“7 above. Step 2's `adb pull` succeeding without root is the decisive check.
  - *Severity effect*: replay accepted â†’ **High**, and arguably Critical by the register's calibration
    since protection is silently and completely removed. It is filed at **High** deliberately, following
    the restraint precedent set for C4: the harm requires a *deliberately adversarial* user performing a
    multi-step file operation, whereas the audit's Criticals (C5, C6, C7) either need no adversary or
    need only a Settings toggle. If a maintainer judges `adb`-free replay via an ordinary file manager
    to be within the app's stated threat model, this should be **raised to Critical** â€” the mechanism is
    confirmed, only its weighting is a judgement call. Replay rejected on the target device for a reason
    not visible in source (e.g. the OEM blocks non-owner writes into hidden shared-storage folders) â†’
    **Medium**, retained because the deletion variant in step 7 would still work.
- **Build scope**: everyday-build
- **Constitution**: III (untrusted external input applied to security-relevant state without validation)
- **Affected surfaces**: `readBackup()` (`:186â€“198`) is where a freshness check would sit alongside the
  existing decrypt failure handling; the `"schema"` and `"androidId"` envelope fields (`:202â€“203`) are
  write-only in exactly the same way and are covered by F-043; the KDoc "Honest limitations" block
  (`:48â€“53`) is the documentation half. F-209 (all-files prompt flag set before the prompt succeeds,
  owned elsewhere) is adjacent â€” it silently loses wipe resistance by a different mechanism â€” and
  F-063 (`flushBackupIfDirty` clears the dirty flag before the write succeeds) can leave the on-disk
  snapshot older than the live state, which widens this finding's window without being the same defect.
- **Observed against**: `working-tree @ 557a963` (`StateBackupManager.kt` is not among the nine
  uncommitted files; this defect predates the working-tree diff)
- **Disposition**: `verified`

### F-046 â€” `androidId()` swallows failure and returns `""`, silently degrading the backup key from device-bound to a constant every APK holder can compute  [High]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’ `androidId()`
  (lines 253â€“258) and `deriveKey()` (lines 260â€“264)
- **Issue**: `androidId()` is `try { Settings.Secure.getString(â€¦).orEmpty() } catch (_: Exception) { "" }`.
  Two independent routes produce `""`: `getString` returning `null` (absorbed by `.orEmpty()` at `:255`)
  and any throw (absorbed by the `catch (_: Exception)` at `:256`, which discards the exception **by
  construction** â€” the exact Principle III pattern the sweep counts). `deriveKey` then computes
  `SHA-256("" + "|" + KEY_PEPPER)`. `KEY_PEPPER` is a compile-time string constant at `:87`
  (`"grayzone::state::v1::b7Q9x2Lm"`), so the resulting AES-256 key is a **fixed, device-independent
  value that anyone holding the APK can compute in one line**.
  The failure is completely silent and self-consistent: `encrypt` and `decrypt` both derive the same
  degraded key, so the backup keeps working perfectly. Nothing logs, nothing warns, no test covers it,
  and the app behaves identically â€” which is why this cannot be caught by observation. The security
  property the KDoc claims at `:37` ("The payload is encrypted so a user who locates the file can
  neither read nor edit it") and at `:83â€“86` (device-bound key derived from ANDROID_ID) is simply gone,
  and gone in the one direction that matters: an adversary who can now both **read** the state and
  **forge** a valid snapshot. Forging is what turns this into a protection failure rather than a
  disclosure â€” a forged snapshot feeds F-043's unvalidated `applyInto`, which will write any key with
  any type, so the attacker gets both the silent lock-removal path and the unlaunchable-app path.
  Empty or null `ANDROID_ID` is not hypothetical: the value is documented as possibly null, and it is
  per-user and per-signing-key on API 26+, so newly created secondary users, work profiles and
  restricted profiles are the realistic conditions. The severity here is genuinely conditional on how
  often that occurs, which is why the outcome table below is explicit.
  **Separately, the crypto that is present is competent and should not be "fixed".** Recorded so a
  reviewer does not churn it: the GCM IV is 12 fresh `SecureRandom` bytes per write (`:268`) with no
  nonce reuse, the tag is 128 bits (`:90`), the envelope is versioned (`:266`, `:274`, checked at
  `:282`), decrypt distinguishes an unrecognised format (returns `null`) from a wrong key or tampered
  payload (throws, handled at `:193â€“197`), and the deliberate avoidance of the Android Keystore is
  correct and correctly explained at `:81â€“83` â€” Keystore keys die with app data, i.e. with the exact
  action being defended against. The single-SHA-256 derivation is weak in absolute terms but the KDoc
  scopes it honestly at `:84â€“86` as "obfuscation-grade â€” enough to lock out a curious user, not a
  reverse-engineer with the APK", which matches the threat model. **The defect is the silent fallback to
  a known constant, not the choice of algorithm.**
- **Trigger condition**:
  1. Create a secondary user or work profile on an API 30+ device, or otherwise reach a state where
     `Settings.Secure.ANDROID_ID` is empty for this app's signing identity.
  2. Install and set up Grayzone in that profile, grant all-files access, and let a backup be written.
  3. Verify the degraded key directly: compute `SHA-256("|grayzone::state::v1::b7Q9x2Lm")` off-device
     and use it to AES-256-GCM-decrypt `/sdcard/.grayzone/backup.dat` (skip byte 0, take the next 12 as
     the IV, per `:266`). Successful decryption proves the key is not device-bound.
  4. To confirm the forgery half, re-encrypt a modified `entries` object with that same key, push it
     back, clear app data, and launch â€” `applyInto` accepts it (F-043).
  5. Force the condition without a second profile, for a faster check: temporarily change `androidId()`
     to `return ""` and observe that the app's backup round-trips with no error, warning or behavioural
     difference of any kind. That the app is indistinguishable in this state **is** the finding.
- **Blast radius**:
  - *Callers*: `androidId()` has two call sites, both in this file â€” `serialize()` (`:203`, writes the id
    into the envelope, where it is never read back; see F-043) and `deriveKey()` (`:261`, the security-
    relevant one). `deriveKey()` in turn has two callers, `encrypt()` (`:270`) and `decrypt()` (`:286`).
    Enumerated by direct search over `app/src`: `androidId` appears at `:203`, `:254`, `:261` only.
  - *Process death*: no effect â€” the key is derived on demand at every `encrypt`/`decrypt`, never cached,
    so the degradation is stateless and reproduces on every call.
  - *Config change*: no effect.
  - *Foreground/background*: no effect; both the write path (`appScope`/`Dispatchers.IO`) and the read
    path (main thread, F-044) derive the key identically.
  - *Cross-process*: not reachable from `:a11y` (`GrayzoneApplication.kt:54`). Worth noting that
    ANDROID_ID is per-*user*, so a multi-user device already yields different keys per profile â€” which is
    correct behaviour, and is also the condition most likely to produce the empty value.
  - *Persistence*: yes, and asymmetrically dangerous. If a snapshot is written under the degraded key and
    ANDROID_ID later becomes available, `decrypt` throws, `readBackup` logs at `:195` and returns `null`,
    and the anti-tamper backup is silently unusable â€” so the bug also destroys the user's own recovery
    path, not only the attacker's barrier. The reverse order (written with a real id, read with `""`)
    fails the same way.
  - *Release-only*: no. Note that `KEY_PEPPER` is a `const val` `String`, so R8 inlines it and it remains
    trivially recoverable from the release APK by `strings`; that is inherent to the documented
    obfuscation-grade design and is not itself the defect.
- **Fix** (minimal): make the absence of usable key material an explicit, loud, non-silent condition
  rather than a substitution.
  1. Change `androidId()` to return `String?` â€” `null` on empty or on throw â€” and log the throw with
     `GrayzoneLogger.w` including the exception (the current `catch (_: Exception)` cannot, because it
     binds nothing).
  2. In `deriveKey()`, refuse to derive from a missing id: have `encrypt`/`decrypt`'s callers treat it as
     "backup unavailable" â€” `writeBackup` skips with a `w()` log (same shape as its existing
     `hasAllFilesAccess` early return at `:161â€“164`, but at a level that actually emits), and
     `readBackup` returns `null`. No backup is strictly better than a backup encrypted with a published
     key, because the latter is an active forgery channel into F-043's unvalidated restore.
  3. Mix in one more device-stable, non-constant input so an empty ANDROID_ID is not the sole point of
     failure â€” `Build.FINGERPRINT` is a reasonable choice: it survives clear-data, is not app-scoped, and
     costs nothing. This is a hardening step, not required for correctness, and must not be mistaken for
     making the scheme cryptographically strong.
- **Rewrite justification**: not applicable. The encryption envelope, IV handling, tag length, version
  byte and Keystore-avoidance rationale are all sound and are explicitly preserved; only the key-material
  fallback changes.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupKeyDerivationTest.kt`. Extract `deriveKey`'s pure
  part into `internal fun keyMaterial(androidId: String?): ByteArray?` and assert: (a) `keyMaterial(null)`
  and `keyMaterial("")` both return `null` rather than a key; (b) `keyMaterial("abc")` returns 32 bytes;
  (c) two different ids produce different keys. (a) fails before the fix â€” today both produce the same
  well-defined 32-byte key, and the test can pin that constant to demonstrate it. No Android framework
  types are involved, so this needs neither Robolectric nor the missing
  `testOptions { unitTests.isReturnDefaultValues = true }`.
- **Existing test conflict**: none â€” no test references `StateBackupManager`.
- **Verification**: `confirmed` (evidence tier T1) for the mechanism. `:255` applies `.orEmpty()` to a
  nullable return and `:256` catches with `_`, so `""` is the value on both failure routes; `:261`
  concatenates it into the digest input unconditionally with no emptiness check anywhere in the file.
  `needs-verification` only for how often `ANDROID_ID` is actually empty in the field.
  - *Missing*: a device â€” ideally with a secondary user and a work profile â€” to establish whether
    `Settings.Secure.getString(â€¦, ANDROID_ID)` ever returns null or empty for this app on supported API
    levels (29â€“34). This is the only open question and it cannot be answered statically.
  - *Procedure*: steps 1â€“3 above for the real condition; step 5 is a one-line source change that
    demonstrates the degraded path is behaviourally invisible and can be done without a device.
  - *Severity effect*: empty `ANDROID_ID` occurs on any supported configuration â†’ **High**, as filed: the
    anti-tamper encryption is silently absent for those users and becomes a forgery channel into F-043,
    with no crash and no notification. Empty `ANDROID_ID` proves unreachable on all supported API levels
    â†’ **Low**, as defensive-code cleanliness â€” but it should still be fixed, because the guarantee then
    rests on undocumented platform behaviour with a silent catch standing between it and the security
    property. It is filed at High rather than Critical following the restraint precedent applied to C4:
    the harmful chain is fully specified but its precondition is unconfirmed.
- **Build scope**: everyday-build
- **Constitution**: III (`catch (_: Exception)` discarding the exception by construction, at a boundary
  where the fallback silently weakens a security property)
- **Affected surfaces**: `serialize()`'s `"androidId"` envelope field (`:203`) records the same value and
  is never read (F-043); F-043 is the reason forgery matters, since `applyInto` validates nothing;
  `readBackup()`'s decrypt handler (`:193â€“197`) is where a key-material change surfaces as an unusable
  backup. The wider pattern â€” `catch` clauses that bind `_` and substitute a default â€” is the sweep's
  "returns-default-silently" category in `evidence/error-handling-inventory.md`; this instance is
  distinguished by the default being *security-relevant key material*.
- **Observed against**: `working-tree @ 557a963` (`StateBackupManager.kt` is not among the nine
  uncommitted files)
- **Disposition**: `verified`

### F-047 â€” Backup write/restore failures are unobservable: the skip path logs at a level that never emits, and `restoreIfWiped`'s Boolean result is discarded at both call sites  [Medium]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` â†’
  `writeBackup()` (lines 160â€“179, the early return at `:161â€“164`) and `restoreIfWiped()` (lines 104â€“131),
  observed against its call sites `GrayzoneApplication.kt:64` and `MainActivity.kt:48`
- **Issue**: The anti-tamper backup can be absent, stale or failing indefinitely with **no signal on any
  channel** â€” not to the user, not to the UI, and effectively not to logcat.
  1. `writeBackup` returns early when all-files access is missing and reports it with
     `GrayzoneLogger.i` (`:162`). `i()` is a no-op in **every variant this project builds** â€” the debug
     variant's generated `BuildConfig.java` has `DEBUG = false` (F-064, confirmed from
     `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java:7`). So the one
     statement recording "this device has no anti-tamper backup" emits nothing, anywhere, ever. The
     success log at `:172â€“175` and both `restoreIfWiped` outcome logs at `:111â€“114` and `:119â€“122` are
     `i()` as well â€” **four of the five observability points in this file are dead**, leaving only the
     `w()` failure paths at `:129`, `:177` and `:195`.
  2. `restoreIfWiped` returns `Boolean` and documents it at `:102` (`@return true if a backup was found
     and applied`). **Both** call sites discard it: `GrayzoneApplication.kt:64` is a bare statement, and
     `MainActivity.kt:48` is a bare statement inside an `if`. So `false` â€” which conflates "live state
     present, nothing to do" (`:107`), "no backup file" (`:110`), "restore threw" (`:128â€“131`) and
     "`commit()` returned false" (`:250`) into one indistinguishable value â€” is not examined by anyone.
     Four distinct outcomes, three of them failures, collapsed and dropped.
  3. There is **no UI surface for backup state at all**: searching `app/src/main/java/com/grayzone/app/ui`
     for `backup` returns **zero matches**. Nothing on Home, Settings or the onboarding flow tells the
     user whether wipe resistance is actually in place. For an app whose README advertises "Anti-Bypass
     Protection", the protection can be entirely absent while the UI is indistinguishable from the
     protected state.
  The user-visible consequence is the register's worst category: the user believes their locks will
  survive a data wipe, and they will not, and nothing tells them. Note the distinction from **F-209**
  (owned elsewhere), which explains *one cause* of never obtaining the permission â€” the prompt flag being
  persisted before the prompt succeeds. This finding is that even when the cause is something else
  entirely (permission revoked later by the OS, storage full, an OEM that resets appops), the resulting
  absence of protection is unreportable and unreported. F-209's fix does not address it.
- **Trigger condition**:
  1. Install and complete onboarding but **deny** all-files access (or grant it, then revoke it
     afterwards via Settings â†’ Apps â†’ Grayzone â†’ Permissions, or
     `adb shell appops set com.grayzone.app MANAGE_EXTERNAL_STORAGE deny`).
  2. Configure real protection: monitor apps, set sessions and lockouts, trigger a lockout.
  3. Background the app to fire `flushBackupIfDirty` (`GrayzoneApplication.kt:116`) and wait past
     `FLUSH_INTERVAL_MS` (120 s, `:75`) so the coarse loop also runs.
  4. Inspect every channel: `adb shell ls -la /sdcard/.grayzone/` (no file), `adb logcat | grep -i
     grayzone` (nothing about the backup, because `:162` is `i()`), and walk the whole UI (no indication).
  5. Clear app data and relaunch. Every lock is gone. The user's expectation â€” the app's headline
     anti-bypass promise â€” was never true, and step 4 shows there was no way for them or for a support
     engineer to find out.
- **Blast radius**:
  - *Callers*: `writeBackup` â† `GrayzoneApplication.kt:98` (one-time baseline, inside
    `appScope.launch`) and `GrayzoneApplication.kt:139` (`flushBackupIfDirty`, also `appScope.launch`).
    `restoreIfWiped` â† `GrayzoneApplication.kt:64` and `MainActivity.kt:48`. `backupExists` â†
    `GrayzoneApplication.kt:97` only. `hasAllFilesAccess` â† `StateBackupManager.kt:152`, `:161`, `:187`
    and `MainActivity.kt:47`. All enumerated by direct search over `app/src`.
  - *Process death*: no mitigation and no worsening; the condition is a missing permission or a missing
    file, both of which outlive the process. Every restart repeats the silent skip.
  - *Config change*: no effect.
  - *Foreground/background*: `flushBackupIfDirty` runs on backgrounding (`GrayzoneApplication.kt:116`),
    which is deliberately the moment the user could reach Settings to clear data. That makes the silent
    skip maximally costly: the app skips the write at exactly the instant the write matters most.
  - *Cross-process*: not reachable from `:a11y` (`GrayzoneApplication.kt:54`).
  - *Persistence*: yes â€” this is about whether a persisted artifact exists at all. Any fix must record
    the last successful write somewhere durable so the UI can report staleness, not merely presence.
  - *Release-only*: no, but the observability half is variant-independent for the wrong reason: `i()` is
    dead in *both* variants (F-064), so unlike a normal debug-only log this cannot be recovered by
    building a debug APK.
- **Fix** (minimal): three small changes, none structural.
  1. Raise the two failure-relevant logs from `i()` to `w()`: the all-files skip at `:162` and the
     "no external backup was found" case at `:111â€“114`. Both describe a degraded state, so `w()` is the
     correct level independently of F-064, and it makes them emit today.
  2. Persist a `last_backup_written_at` timestamp on the success path at `:171` and surface it in
     Settings as a one-line protection-health row ("Wipe protection: active, last saved <time>" /
     "not active â€” grant all-files access"). This is the change that closes the user-facing half, and it
     has a natural home: `ProtectionHealthRepository` already exists for exactly this purpose (see
     F-058).
  3. Use `restoreIfWiped`'s return value at `GrayzoneApplication.kt:64` â€” at minimum log a `w()` when it
     returns `false` while a backup file exists, which is the only combination that indicates a real
     failure. Alternatively narrow the return type to a small sealed result so the four outcomes stop
     being one `Boolean`; that is the better fix but is larger than minimal, so it is offered second.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupObservabilityTest.kt`, against an
  `internal`-widened seam for the access check and a fake logger: assert that when all-files access is
  absent, `writeBackup` records a **warning-level** event (not info) and does not touch the filesystem
  double; and that `restoreIfWiped` returns `false` distinguishably for "sentinel present" versus
  "backup file present but parse failed". Fails before the fix on the level assertion (today it is
  `i()`), and on the distinguishability assertion (today both are a bare `false`).
- **Existing test conflict**: none â€” no test references `StateBackupManager`.
- **Verification**: `confirmed` (evidence tier T1). The log levels are read directly at `:111`, `:119`,
  `:162`, `:172` (all `GrayzoneLogger.i`) versus `:129`, `:177`, `:195` (`w`); `i()` being a no-op is
  already confirmed from generated build output in the register's T021 notes; the discarded return values
  are read at `GrayzoneApplication.kt:64` and `MainActivity.kt:48`; and the absence of any UI surface is
  a zero-match search over `app/src/main/java/com/grayzone/app/ui` for `backup`. No device needed for any
  of it.
- **Build scope**: everyday-build
- **Constitution**: III (a failure at an I/O boundary that is neither propagated to the caller nor
  reported on any channel the user or a maintainer can observe)
- **Affected surfaces**: `backupExists()` (`:151â€“152`) is the existing predicate a status row would reuse;
  `ProtectionHealthRepository` / `ProtectionStatus` (see F-058) is the right home for the health signal
  and already models three of four protection legs. Cited, not re-filed: **F-064** (`d()`/`i()` are
  no-ops in every variant â€” the reason half this file's logging is dead), **F-209** (the all-files prompt
  flag is persisted before the prompt succeeds â€” one upstream cause of the permission never arriving),
  and **F-063** (`flushBackupIfDirty` clears the dirty flag before the write succeeds â€” a second way the
  on-disk snapshot silently falls behind, which a `last_backup_written_at` timestamp would also expose).
- **Observed against**: `working-tree @ 557a963` (`StateBackupManager.kt` and `MainActivity.kt` are not
  uncommitted; `GrayzoneApplication.kt` is, and `app/build.gradle.kts`'s uncommitted
  `debug { isDebuggable = false }` block is what makes `i()` dead in the debug variant too)
- **Disposition**: `verified`

### F-048 â€” `CleanupWorker` is enqueued with `KEEP` and no version tag, so its retention policy is frozen on every existing install and nothing can observe whether it ever runs  [Medium]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/CleanupWorker.kt` â†’ `CleanupWorker.doWork()`
  (lines 40â€“64), scheduled by `app/src/main/java/com/grayzone/app/GrayzoneApplication.kt` â†’
  `schedulePeriodicCleanup()` (lines 284â€“303)
- **Issue**: Two defects in one scheduling contract, plus a correction to the Doze hypothesis this task
  was asked to test.
  1. **The work definition is permanently frozen.** `enqueueUniquePeriodicWork("database_cleanup",
     ExistingPeriodicWorkPolicy.KEEP, cleanupRequest)` (`GrayzoneApplication.kt:290â€“294`) keeps the
     *already-enqueued* request and discards the new one. The unique name carries no version suffix and
     nothing anywhere calls `cancelUniqueWork` or `ExistingPeriodicWorkPolicy.UPDATE` â€” verified by
     direct search: `WorkManager` appears in main source only at `GrayzoneApplication.kt:10` and `:290`.
     So any future change to `RETENTION_DAYS` (`CleanupWorker.kt:27`), to the 7-day period, or to the
     constraints takes effect **only on installs that have never scheduled it** â€” i.e. new users. Every
     existing user keeps the old retention forever, silently. The comment at `:292` ("Don't restart if
     already scheduled") explains the intent, which is sound as far as it goes: `KEEP` is the right
     choice for avoiding a reschedule on every process start. It is the wrong choice for delivering a
     changed policy, and nothing distinguishes the two cases.
  2. **Whether retention ever ran is unobservable.** The success path reports via `GrayzoneLogger.i`
     (`CleanupWorker.kt:50â€“53`) and the scheduling path via `GrayzoneLogger.d`
     (`GrayzoneApplication.kt:296â€“299`). Both are no-ops in every variant this project builds (F-064,
     confirmed from generated `BuildConfig.java`). Only the failure path emits (`e()` at `:57â€“61`) and
     the scheduling failure (`w()` at `:301`). So a maintainer cannot tell a working retention policy
     from one that has never executed, and the app stores no `last_cleanup_at` marker.
- **The Doze hypothesis this task was asked to test does not hold â€” and the intuitive fix would make it
  worse.** `schedulePeriodicCleanup` calls no `setConstraints`, so the request carries
  `Constraints.NONE`. Doze defers WorkManager jobs to maintenance windows rather than cancelling them,
  and a 7-day period is orders of magnitude longer than any Doze deferral, so an unconstrained weekly
  job is the *most* Doze-tolerant configuration available. Adding `requiresDeviceIdle`,
  `requiresBatteryNotLow` or `requiresCharging` â€” the reflexive "make it a good citizen" change â€” is
  what would introduce a real starvation risk. **No constraints is correct here and must not be
  "fixed".** The genuine survival risk is not Doze but OEM process/job killers, which the app's own
  `OemBatteryHelper.kt` documents in detail and which is never wired up (an unowned feature gap already
  assigned to T021/T052/T059 â€” cited, not re-filed).
- **And growth is bounded in rows but not in bytes.** `deleteEventsOlderThan` (`UsageDao.kt:73â€“74`) is a
  plain `DELETE` with no `VACUUM` and the database runs in WAL mode (`UsageDatabase.kt:41`), so freed
  pages are reused but the file never shrinks: `grayzone_usage.db` plateaus at its high-water mark
  rather than tracking the row count, which contradicts the KDoc's "~2MB stable" claim
  (`CleanupWorker.kt:16â€“19`) in the file-size sense even when it holds in the row sense. The true row
  bound is also 97 days rather than 90, since cleanup is weekly. Both are minor; they are recorded
  because the KDoc states specific figures that are not what the code produces.
- **Trigger condition**:
  1. Install the current build, complete onboarding, and let `Application.onCreate` schedule the work.
     Confirm: `adb shell dumpsys jobscheduler | grep -A5 com.grayzone.app` shows one periodic job.
  2. Change `CleanupWorker.RETENTION_DAYS` from `90L` to `7L`, rebuild, and install as an **update**
     (`adb install -r`) so app data and the WorkManager database survive.
  3. Launch the app and inspect the enqueued request:
     `adb shell dumpsys jobscheduler | grep -A20 com.grayzone.app`, or read WorkManager's own record via
     `WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("database_cleanup")`.
  4. Observe the original request is still enqueued â€” the new retention is not in effect for this
     install and never will be. For the observability half, grep logcat for the cleanup message
     (`adb logcat | grep -i cleanup`) after forcing a run
     (`adb shell cmd jobscheduler run -f com.grayzone.app <jobId>`) and observe nothing, because `:50`
     is `i()`.
- **Blast radius**:
  - *Callers*: `CleanupWorker` is instantiated only by WorkManager; its sole enqueue site is
    `GrayzoneApplication.schedulePeriodicCleanup()` (`:284â€“303`), called once from `onCreate` (`:74`).
    `CleanupWorker.cutoffDateKey()` has one production caller (`doWork()`, `:45`) plus
    `app/src/test/java/com/grayzone/app/data/` coverage of the pure boundary. `doWork` reaches
    `UsageDatabase.getInstance` (`:42`), `deleteEventsOlderThan` (`:47`) and `getEventCount` (`:48`) â€”
    and those two DAO methods have **no other main-source caller**, so this worker is the only thing
    that bounds the table.
  - *Process death*: WorkManager persists the request in its own database, so the schedule survives
    process death and reboot â€” which is exactly what makes `KEEP` sticky. Process death is why the
    `KEEP` guard exists (services restart the process often) and also why the frozen definition never
    gets refreshed.
  - *Config change*: no effect.
  - *Foreground/background*: runs entirely in the background with no UI; there is no surface on which a
    failure or a stale policy could be noticed.
  - *Cross-process*: not reachable from `:a11y` â€” `GrayzoneApplication.onCreate` returns at `:54` before
    `:74`, so the enqueue happens only in the default process. That guard is also what makes the
    doubly-protected `KEEP` note in the register's non-findings table correct.
  - *Persistence*: yes, twice over â€” WorkManager's own request database (the frozen definition) and
    `grayzone_usage.db` (the rows being deleted). A fix that switches to `UPDATE` changes persisted
    WorkManager state on next launch for every install, which is the intended effect but should be
    stated in the changelog.
  - *Release-only*: no. Note `CleanupWorker` is instantiated reflectively by WorkManager, so it depends
    on AGP's generated keep rules; `evidence/r8.md` confirms all manifest/reflective components appear
    in `seeds.txt`, so no additional rule is needed.
- **Fix** (minimal): two small changes, and deliberately **not** adding constraints.
  1. Switch `ExistingPeriodicWorkPolicy.KEEP` to `ExistingPeriodicWorkPolicy.UPDATE`
     (`GrayzoneApplication.kt:292`). `UPDATE` preserves the existing schedule's timing where it can but
     applies the new parameters, which is exactly the semantics the comment at `:292` was reaching for.
     If the project's WorkManager version predates `UPDATE`, the equivalent is to append a version
     suffix to the unique name (`"database_cleanup_v2"`) and cancel the old name â€” noted as the
     fallback, not the preference, because it leaks a migration constant.
  2. Raise the cleanup result log at `CleanupWorker.kt:50` from `i()` to `w()` **or** persist a
     `last_cleanup_at` timestamp and surface it beside the backup-health row proposed in F-047. The
     timestamp is preferable: it is the only way to answer "has retention ever run on this device?"
     without a logcat session.
- **Rewrite justification**: not applicable â€” both changes are one-liners at existing call sites.
- **Test to add**: `jvm-unit` â€” extend
  `app/src/test/java/com/grayzone/app/data/CleanupWorkerCutoffTest.kt` (or create it if the existing
  pure-boundary coverage lives elsewhere) with an assertion that `cutoffDateKey` is strictly ordered and
  parseable, plus a new `jvm-unit` guard asserting the enqueue policy constant: extract the request
  construction into an `internal fun cleanupWorkPolicy(): ExistingPeriodicWorkPolicy` and assert it is
  `UPDATE`. That is a thin test, and it is honest about what can be tested here â€” the meaningful test is
  the instrumented one below.
  `instrumented` â€” `app/src/androidTest/java/com/grayzone/app/data/CleanupSchedulingTest.kt`, using
  `androidx.work.testing.WorkManagerTestInitHelper` to enqueue the work, re-enqueue with a changed
  period, and assert via `getWorkInfosForUniqueWork` that the *new* period is in effect. This fails
  before the fix (the original request is retained) and passes after. **It cannot be executed in the
  current environment** â€” no device or emulator â€” and it additionally requires the
  `androidx.work:work-testing` dependency, which is not currently in `app/build.gradle.kts`, so landing
  it is a two-part change.
- **Existing test conflict**: none. No existing test enqueues work or asserts on the scheduling policy.
- **Verification**: `confirmed` (evidence tier T1) for all three static claims â€” `KEEP` with no version
  suffix and no `cancelUniqueWork` anywhere (`GrayzoneApplication.kt:290â€“294`, plus a whole-tree search
  for `WorkManager` returning only `:10` and `:290`); the absence of `setConstraints` (`:286â€“288`); and
  the dead observability (`i()`/`d()` at `CleanupWorker.kt:50` and `GrayzoneApplication.kt:296`, with
  `DEBUG = false` already confirmed from generated output). The Doze conclusion is a reasoned reading of
  WorkManager/JobScheduler semantics against `Constraints.NONE`, not a measurement.
  - *Missing*: a device to confirm the frozen-definition behaviour end-to-end (steps 1â€“4) and to confirm
    that an unconstrained weekly job does in fact execute across multi-day Doze on a real OEM build.
  - *Procedure*: steps 1â€“4 above. For the Doze half:
    `adb shell dumpsys deviceidle force-idle`, leave the device idle overnight, then
    `adb shell dumpsys jobscheduler` and check the job's last-run time.
  - *Severity effect*: frozen definition confirmed â†’ **Medium** as filed (a real defect, but its harm is
    a future policy change silently not applying, not a present failure). If a device shows the job
    never executing at all on a mainstream OEM build â†’ **High**, because retention would then be
    entirely notional and `usage_events` would grow without bound â€” but note that outcome would be
    caused by OEM job killing, whose fix is the unwired `OemBatteryHelper`, not this scheduling code.
- **Build scope**: everyday-build
- **Constitution**: III (the success path of a background boundary is reported only through a log level
  that never emits, so the operation is unverifiable in the field)
- **Affected surfaces**: `CleanupWorker.doWork()`'s `Result.retry()` (`:62`) returns retry for *every*
  exception including permanent ones, so a Room open that can never succeed retries with backoff
  indefinitely â€” that is F-041's failure mode seen from the worker side and is recorded there as an
  affected surface; the fix should distinguish transient from permanent by returning `Result.failure()`
  for `IllegalStateException` from Room's open. `UsageDao.deleteEventsOlderThan` /
  `getEventCount` (`UsageDao.kt:73â€“78`) have no other main-source caller. **Prefs, not Room, is where
  unbounded growth actually happens** in this app: per-app keys are never removed for unmonitored or
  uninstalled packages (F-053), and `StateBackupManager.writeBackup` snapshots `prefs.all`, so that
  growth feeds F-044's unbounded main-thread payload. Cited, not re-filed: **F-064** (`d()`/`i()` dead),
  the `OemBatteryHelper` feature gap (T021/T052/T059).
- **Positive worth recording** (I checked for it specifically and it is absent): the classic
  locale-digits bug does not apply. `CleanupWorker.cutoffDateKey` uses
  `SimpleDateFormat("yyyy-MM-dd", Locale.US)` (`:35`) and `DateUtils`' cached formatters use
  `Locale.US` too (`DateUtils.kt:19, 23`), so the `dateKey < :cutoffDate` string comparison in SQL is
  reliable on every locale. Had either omitted the locale, an Arabic-Indic or Devanagari digit locale
  would have produced keys that never compare less-than and retention would have silently deleted
  nothing forever.
- **Observed against**: `working-tree @ 557a963` (`CleanupWorker.kt` is not uncommitted;
  `GrayzoneApplication.kt` is, and `app/build.gradle.kts`'s `debug { isDebuggable = false }` is what
  kills the `i()`/`d()` observability in the debug variant)
- **Disposition**: `verified`

### F-049 â€” Focus mode and the daily break are pure wall-clock deadlines, so changing the device date grants unlimited one-hour windows in which nothing is locked at all  [Critical]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/ScheduleManager.kt` â†’
  `isCurrentlyScheduled()` (line 69, `FOCUS_MODE_UNTIL`), `isBreakActive()` (lines 115â€“116,
  `BREAK_UNTIL`), and `canStartBreakToday()` (lines 122â€“123, `BREAK_USED_DATE`)
- **Issue**: Three wall-clock comparisons against persisted epoch values, in the file that owns the two
  features whose entire purpose is to be time-bounded:
  ```115:123:app/src/main/java/com/grayzone/app/data/ScheduleManager.kt
      fun isBreakActive(): Boolean =
          System.currentTimeMillis() < prefs.getLong(PrefsKeys.BREAK_UNTIL, 0L)
      // ...
      fun canStartBreakToday(): Boolean =
          prefs.getString(PrefsKeys.BREAK_USED_DATE, "") != com.grayzone.app.DateUtils.getCurrentDateKey()
  ```
  **The daily break is the most powerful switch in the app.** `SessionPolicyEngine.evaluate` checks it
  second, before every other gate (`SessionPolicyEngine.kt:29â€“32`): `if (state.isOnBreak && event is
  AppEvent.AppForegrounded) { commands.add(DismissOverlay); return commands }`. While a break is
  active, **no friction screen, no lockout, and no schedule block fires for any app** â€” the comment at
  `ScheduleManager.kt:112â€“113` says so explicitly. The break is rationed to one hour per calendar day by
  a single string comparison of `BREAK_USED_DATE` against `DateUtils.getCurrentDateKey()`, which is
  derived from `System.currentTimeMillis()`.
  Advancing the device date by one day therefore makes `canStartBreakToday()` return `true` again, and
  the ration resets. This is repeatable without limit: change the date, take a fresh hour of total
  unlock, change it again. No root, no adb, no developer options â€” Settings â†’ Date & time â†’ turn off
  automatic â†’ change date. For a self-control app, that is not an edge case; it is *the* bypass, and it
  is broader than C5/C6 because it suspends every protection mechanism simultaneously rather than
  releasing one app's lockout.
  The forward-clock direction also ends an in-progress focus session or break early (`:69`, `:116`
  compare against absolute deadlines), and the backward direction extends them â€” including the honest-user
  harm: an NTP correction of a fast clock silently shortens or lengthens a break the app promised.
  **This repository already contains the fix, applied to a less important feature.**
  `service/vpn/VpnHoldLock.kt:9` documents the dual-clock defence verbatim â€” "Remaining time prefers
  `State.startedElapsedMs` so wall-clock changes cannot skip the wait" â€” with a dedicated prefs key and a
  reboot fallback. So the pattern is written, documented and shipped for the 24-hour VPN hold, and
  absent from the friction core. This is the same inconsistency the register records for C5/C6/F-021,
  now confirmed in a fourth and fifth location.
  **Scope note so the fix is not over-applied**: `isCurrentlyScheduled()`'s *rule matching*
  (`matchesAnyRule`, `:160â€“184`, driven by `Calendar.getInstance()` at `:71â€“73`) is correctly wall-clock â€”
  a bedtime rule of 22:00â€“06:00 is *defined* in local time and must move with the clock and with DST.
  Only the three **duration**-anchored values (`FOCUS_MODE_UNTIL`, `BREAK_UNTIL`, `BREAK_USED_DATE`) are
  defects. Conflating the two would break scheduling.
- **Trigger condition**:
  1. Set up protection: monitor at least one app, set a short session and a long lockout, and confirm
     the friction screen appears when opening it.
  2. Start the daily break from the UI and confirm every monitored app opens freely.
  3. End the break (or wait it out) and confirm the break can no longer be started today â€”
     `canStartBreakToday()` is now `false`.
  4. Settings â†’ Date & time â†’ disable "Set time automatically" â†’ advance the **date** by one day.
  5. Return to Grayzone. Observe the daily break is available again. Start it. Every monitored app is
     freely usable for another hour, with no friction, no lockout and no schedule block.
  6. Repeat steps 4â€“5 indefinitely. Each date change buys another hour of complete unlock.
  7. Forward-clock variant on the deadline itself: start a break, then advance the clock past
     `BREAK_UNTIL`. The break ends immediately â€” verifiable with
     `adb shell run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml`
     to read `break_until` and compare.
- **Blast radius**:
  - *Callers*: `isBreakActive()` â†’ `OverlayService.buildSessionState()` (`OverlayService.kt:481`,
    supplying `SessionState.isOnBreak`), consumed by `SessionPolicyEngine.evaluate` (`:29`).
    `isCurrentlyScheduled()` â†’ `OverlayService.buildSessionState()` (`OverlayService.kt:480`), consumed at
    `SessionPolicyEngine.kt:44`. `canStartBreakToday()` â†’ `startDailyBreak()` (`:130`) and the UI.
    `getBreakRemainingMillis()` / `getFocusModeRemainingMillis()` feed the UI countdowns. All enumerated
    by direct search; `ScheduleManager` is instantiated in `OverlayService` and in the UI screens.
  - *Process death*: no mitigation â€” every value is in SharedPreferences and is re-read on each call, so
    the bypass survives process death, force-stop and reboot. Worse for a monotonic fix: `elapsedRealtime`
    resets on reboot, so the fix needs the same reboot fallback `VpnHoldLock` already implements.
  - *Config change*: no effect.
  - *Foreground/background*: the decisive read happens in the background, on every app switch, inside
    `OverlayService.buildSessionState`. So the bypass takes effect with no UI involvement and the user
    never has to reopen Grayzone after changing the date â€” step 5's "return to Grayzone" is only needed
    to *start* the break.
  - *Cross-process*: not reachable from `:a11y` â€” `GrayzoneAccessibilityService` reads no prefs
    (independently re-verified). The app-switch signal originates in `:a11y` but the state read happens
    in the default process.
  - *Persistence*: yes â€” `FOCUS_MODE_UNTIL`, `FOCUS_MODE_ACTIVE`, `BREAK_UNTIL` and `BREAK_USED_DATE` in
    `GrayzonePrefs`. All four are also captured by `StateBackupManager`'s `prefs.all` snapshot, so a
    restored snapshot can reset `BREAK_USED_DATE` to an old date and hand back the ration without any
    clock change at all â€” a second, independent route to the same bypass through F-043's unvalidated
    restore.
  - *Release-only*: no.
- **Fix** (minimal): apply the pattern already in the repository, to the two duration-anchored features
  only.
  1. For `BREAK_UNTIL` and `FOCUS_MODE_UNTIL`, store the start in **both** clocks â€” `System.currentTimeMillis()`
     and `SystemClock.elapsedRealtime()` â€” and compute remaining time from the elapsed-realtime delta,
     preferring it over wall clock, with the wall-clock value as the reboot fallback. This is
     `VpnHoldLock.remainingMs`'s exact shape (`VpnHoldLock.kt:25`); the minimal change is to reuse that
     helper rather than write a third copy, since a fix for C5/C6 will need the same primitive.
  2. For the ration, stop keying it on a formatted local date. Persist `BREAK_USED_AT` as an
     elapsed-realtime-anchored timestamp and require a fixed 24-hour separation, or at minimum reject a
     `getCurrentDateKey()` that is *earlier* than the stored `BREAK_USED_DATE` (monotonic date), which
     alone kills the repeat-by-date-change loop in step 6 at the cost of one comparison.
  3. Because this is one pattern applied in five places (C5, C6, F-021, and the two here), it should be
     a single pass with one shared helper, not five patches â€” T034 should group them.
- **Rewrite justification**: not a rewrite. The functions keep their signatures and their callers; only
  the stored representation of two deadlines and one ration marker changes.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ScheduleManagerBreakClockTest.kt`. `canStartBreakToday` and
  `isBreakActive` currently read `System.currentTimeMillis()` directly, so the minimal seam is to add
  `now: Long` and `nowElapsed: Long` parameters with defaults (the same injectability
  `RuntimeSessionReset.resetIfNeeded` already has at `RuntimeSessionReset.kt:27`, and
  `matchesAnyRule` already has by extraction â€” the file's own precedent). Then assert: (a) a break
  started at `nowElapsed = T` is inactive at `nowElapsed = T + 3_600_001` regardless of what the wall
  clock says; (b) rewinding the wall clock by a day does **not** make `canStartBreakToday()` true; (c)
  advancing the wall clock past `BREAK_UNTIL` does not end a break whose elapsed budget remains. All
  three fail before the fix.
- **Existing test conflict**: none for the break/focus paths â€” existing `ScheduleManager` coverage
  targets `matchesAnyRule`, which this fix deliberately does not touch. Adding default-valued parameters
  is source-compatible, so existing callers and tests continue to compile.
- **Verification**: `confirmed` (evidence tier T1). Every element is settled statically: the three
  wall-clock comparisons (`:69`, `:116`, `:122â€“123`), the break's total-override semantics
  (`SessionPolicyEngine.kt:29â€“32`, read directly), the wiring through
  `OverlayService.buildSessionState` (`:480â€“481`), and the existence of the correct pattern elsewhere
  (`VpnHoldLock.kt:9, 25`). No device is needed to establish the mechanism.
  - *Missing*: device confirmation that the Settings date change is not blocked on the target build and
    that `DateUtils.getCurrentDateKey()`'s one-minute cache (`DateUtils.kt:46â€“61`) does not incidentally
    delay the reset by up to 60 seconds â€” it would not prevent it, since `cacheExpiry` is clamped to the
    next midnight and a date change invalidates the comparison on the next expiry.
  - *Procedure*: steps 1â€“6 above, entirely through the Settings UI, no adb required.
  - *Severity effect*: **Critical** as filed on the ration-reset path (step 6): protection for every
    monitored app is completely suspended, repeatedly, with no crash and no notification, which is the
    register's worst category and matches the C5 precedent exactly â€” C5 is Critical for releasing *one*
    app's lockout, and this releases all of them. If a maintainer establishes that the daily break is
    intentionally trust-based and out of the threat model, the deadline-drift half (steps 7 and the
    focus-mode path) still stands at **High**. It does not go below High in any outcome, because the
    honest-user harm â€” an NTP correction silently shortening or extending a promised break â€” needs no
    adversary. **Recommend the register add this as C8** and group it with C5/C6/F-021 for a single
    dual-clock fix.
- **Build scope**: everyday-build
- **Constitution**: II (a defence pattern that exists, is documented in-tree, and is applied
  inconsistently), and the same clock-integrity concern as C5/C6
- **Affected surfaces**: `getFocusModeRemainingMillis()` (`:106â€“109`) and `getBreakRemainingMillis()`
  (`:118â€“119`) render the drifted values to the UI, so they must move to the same clock or the countdown
  will disagree with the gate â€” the identical symptom the register records for
  `OverlayService.kt:701,721`, where the notification already anchors to `elapsedRealtime` while policy
  does not. `isFocusModeActive()` (`:95â€“104`) additionally **writes** persisted state from inside a
  read â€” it calls `stopFocusMode()` (`:99`), which does a prefs `apply()` â€” so a predicate invoked from
  Compose can mutate storage and flip `backupDirty`, triggering an encrypt-and-write of the external
  backup as a side effect of rendering; that is a separate small defect recorded here rather than given
  its own ID, and a fix for this finding should take it. Cited, not re-filed: **F-110**/**C5**,
  **F-111**/**C6** (`policy/SessionModels.kt:30â€“31`), **F-021** (VPN hold expiry), **F-205**
  (`ScheduleManager.kt:33â€“43` malformed-JSON-to-empty-list plus the `addRule` overwrite â€” a different
  defect in the same file, owned by T030), and **F-043** (the restore path that offers a second route to
  the same ration reset).
- **Observed against**: `working-tree @ 557a963` (`ScheduleManager.kt` is not among the nine uncommitted
  files; `OverlayService.kt`, which supplies the two values to the engine, is)
- **Disposition**: `verified`

### F-050 â€” `PerAppLimits` clamps to ranges wider than `ConfigValidator` declares and its `fallback` parameters are unreachable, so the app's "validated" limit resolver enforces a policy no other layer agrees with  [Medium]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/PerAppLimits.kt` â†’ `sessionMinutes()`,
  `lockoutMinutes()`, `waitSeconds()` (lines 9â€“16), against
  `app/src/main/java/com/grayzone/app/data/ConfigValidator.kt` (lines 15â€“17)
- **Issue**: Two defects with one root cause â€” all three functions in `PerAppLimits` implement the wrong
  contract.
  1. **The ranges contradict the declared policy, and always in the permissive direction.** Laid out
     against every other authority in the app:

     | Quantity | `ConfigValidator` (`:15â€“17`) | `PerAppLimits` (`:9â€“16`) | Per-app UI slider (`PerAppSettingsSheet.kt`) | `OverlayService.markAppUnlocked` (`:1056`, `:1065`) |
     |---|---|---|---|---|
     | wait seconds | `3..30` | `1..60` | `3f..30f` (`:101`) | not clamped â€” read raw at `:836` |
     | session minutes | `1..60` | `1..1440` | `1f..60f` (`:115`) | `coerceIn(1, 1440)` |
     | lockout minutes | `15..300` | `15..1440` | `15f..1440f` (`:135`) | `coerceIn(15, 1440)` |

     **Four independent range authorities, pairwise inconsistent, and `PerAppLimits` â€” the one whose
     KDoc says it exists so "UI sheets and OverlayService stay in sync" â€” is the loosest of them.** This
     matters directly to a sibling finding: **F-112** (owned by T024) correctly identifies that
     `per_app_wait_<pkg>` reaches `OverlayService.kt:836` unvalidated, and proposes routing it through
     `PerAppLimits.waitSeconds()`. That fix is **incomplete as written**: `coerceIn(1, 60)` still admits
     1- and 2-second friction, below the app's own declared floor of 3, and admits 60 seconds, double the
     declared ceiling. So the proposed fix would leave friction weaker than policy on the low end. This
     is not a duplicate of F-112 â€” it is the defect in F-112's remedy, and if it is not fixed in the same
     pass, F-112 will be closed while the hole is still open.
     Note the lockout row is the one place where `ConfigValidator` is arguably the wrong authority rather
     than the victim: the per-app UI legitimately offers up to 24 h, so `LOCKOUT_MINUTES_RANGE = 15..300`
     describes the *global* key only. That asymmetry is undocumented in either file, which is why three
     layers picked three different answers.
  2. **The `fallback` parameters are unreachable dead code.** Each body is
     `(...).coerceIn(lo, hi).takeIf { it > 0 } ?: fallback` with `lo >= 1` in all three cases. `coerceIn`
     on an `Int` with a positive lower bound always returns a positive value, so `takeIf { it > 0 }` can
     never return `null` and `?: fallback` can never be evaluated. All three `fallback` parameters
     (defaults `10`, `60`, `5`) are therefore dead, and â€” more importantly â€” the *intent* they encode is
     silently absent: a caller passing an explicit fallback gets it ignored. `PerAppLimitsTest.kt` never
     passes a fallback, so nothing detects this.
     This is **not** a proposal to delete anything. Per the reference-index accuracy warning I verified
     `PerAppLimits`' live status by direct search rather than trusting the index: `sessionMinutes` and
     `lockoutMinutes` are **live** at `OverlayService.kt:449` and `:454`; only `waitSeconds` is
     test-only (`PerAppLimitsTest.kt:12, 19, 38â€“39`). The register's characterisation of
     `PerAppLimits.waitSeconds()` as test-only is accurate; a reader skimming it could easily conclude
     the whole object is dead, and it is not.
- **Trigger condition**:
  1. Statically, for the dead-code half â€” no device needed: call
     `PerAppLimits.waitSeconds(hasCustom = true, perApp = 0, global = 5, fallback = 99)`. The documented
     intent is `99`; the actual result is `1`. Likewise
     `PerAppLimits.sessionMinutes(true, 0, 10, fallback = 99)` returns `1`, not `99`.
  2. For the range half, observably on a device: set a per-app wait of 3 s in the UI (the minimum the
     slider allows), then write a value below the declared floor by the route that skips the slider â€”
     `adb shell run-as com.grayzone.app` and edit `per_app_wait_<pkg>` to `1` in
     `/data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml`, or restore a snapshot containing it
     (F-043).
  3. Apply F-112's proposed fix (route `OverlayService.kt:836` through `PerAppLimits.waitSeconds`),
     rebuild, and open the monitored app.
  4. Observe the friction countdown is **1 second**, not the 3 the app's own `WAIT_SECONDS_RANGE`
     declares as the minimum. The value survives `validateConfiguration()` because that repairs only the
     global key (`GrayzoneApplication.kt:181â€“187`).
- **Blast radius**:
  - *Callers*: `PerAppLimits.sessionMinutes()` â€” `OverlayService.buildSessionState()`
    (`OverlayService.kt:449`) and `PerAppLimitsTest.kt`. `PerAppLimits.lockoutMinutes()` â€”
    `OverlayService.buildSessionState()` (`:454`) and `PerAppLimitsTest.kt`.
    `PerAppLimits.waitSeconds()` â€” `PerAppLimitsTest.kt` only (test-only; **not** dead, and must not be
    deleted, since F-112's fix makes it live). `ConfigValidator`'s predicates â€” `validateConfiguration()`
    (`GrayzoneApplication.kt:182, 191, 200`) and `ConfigValidatorTest.kt`. All enumerated by direct
    search over `app/src`.
  - *Process death*: no effect â€” both objects are stateless and pure.
  - *Config change*: no effect.
  - *Foreground/background*: `buildSessionState` runs on every app switch in the background, so the
    resolved values take effect with no UI involvement; a fix cannot rely on the UI re-clamping.
  - *Cross-process*: not reachable from `:a11y` â€” both are consumed only in the default process.
  - *Persistence*: neither function writes. They *read* values that persistence can poison: the
    per-app keys are written by `PerAppSettingsSheet.kt:104, 118, 140â€“141` (bounded) and by
    `StateBackupManager.applyInto` (unbounded â€” F-043). So persistence is exactly why the clamp bounds
    matter: the slider is not the only writer.
  - *Release-only*: no. R8 will inline these one-line functions, but that changes nothing semantically;
    the unreachable `?: fallback` branch is simply removed, which is consistent with the
    `evidence/r8.md` observation that unused projections disappear.
- **Fix** (minimal): make `ConfigValidator` the single authority and have `PerAppLimits` use it.
  1. Add `val PER_APP_LOCKOUT_MINUTES_RANGE = 15..(24 * 60)` to `ConfigValidator` to record the
     deliberate global-vs-per-app asymmetry explicitly, and document on `LOCKOUT_MINUTES_RANGE` that it
     governs the global key only. This is the honest fix for the one row where the divergence is
     intentional.
  2. Change the three `PerAppLimits` bodies to clamp with the `ConfigValidator` ranges
     (`WAIT_SECONDS_RANGE`, `SESSION_MINUTES_RANGE`, `PER_APP_LOCKOUT_MINUTES_RANGE`) via
     `coerceIn(range.first, range.last)`, and **delete** the now-provably-unreachable
     `.takeIf { it > 0 } ?: fallback` tail along with the `fallback` parameters. Deleting them is safe
     and verifiable: no caller in `app/src` passes one (direct search), so the change is
     source-compatible.
  3. Land it together with F-112's routing change, since F-112 is what makes `waitSeconds` live and is
     the finding whose correctness depends on this one.
- **Rewrite justification**: not applicable â€” three one-line bodies change and three unreachable
  parameters are removed.
- **Test to add**: `jvm-unit` â€” extend `app/src/test/java/com/grayzone/app/data/PerAppLimitsTest.kt`
  with boundary assertions tied to `ConfigValidator`'s constants rather than to literals, so the two can
  never drift again: `assertEquals(ConfigValidator.WAIT_SECONDS_RANGE.first, PerAppLimits.waitSeconds(true, 0, 5))`,
  the same at the top of the range, and the equivalents for session and lockout. Before the fix the wait
  assertion fails (returns `1`, expects `3`) and the ceiling assertion fails (returns `60`, expects
  `30`); after, all pass. Deriving the expected values from `ConfigValidator` is the point â€” a literal
  `3` would re-create the drift the finding is about.
- **Existing test conflict**: **yes.** `PerAppLimitsTest.kt:38â€“39` currently asserts the divergent
  behaviour directly â€” `assertEquals(1, PerAppLimits.waitSeconds(true, 0, 5))` and
  `assertEquals(60, PerAppLimits.waitSeconds(true, 120, 5))`. Both must be updated to `3` and `30` when
  the clamp is corrected. This is a test that pins the defect, so it will fail on the fix and must be
  changed deliberately, not "repaired" by reverting the clamp.
- **Verification**: `confirmed` (evidence tier T1). Every cell of the table above was read from source:
  `ConfigValidator.kt:15â€“17`, `PerAppLimits.kt:9â€“16`, `PerAppSettingsSheet.kt:101, 115, 135`,
  `OverlayService.kt:836, 1056, 1065`. The unreachability of `?: fallback` follows from `coerceIn`'s
  contract with a positive lower bound and needs no execution. The live/test-only status of each function
  was established by direct search over `app/src`, not from the reference index.
- **Build scope**: everyday-build
- **Constitution**: II (four range authorities for three quantities, where one obvious way should exist â€”
  and the file whose stated purpose is to keep them in sync is the one that diverges most)
- **Affected surfaces**: `OverlayService.kt:1051â€“1053` and `:1104â€“1108` re-derive the per-app-vs-global
  choice inline instead of calling `PerAppLimits`, so they are two further copies of the same decision
  with their own bounds â€” T024's F-116 examines `:1065` and confirms it agrees with
  `PerAppLimits.lockoutMinutes`, so those two are consistent; `:836` (wait) is the one that is not, and
  that is F-112. `SessionPolicyEngine.DEFAULT_WAIT_SECONDS` (`:10`),
  `ConfigValidator.DEFAULT_WAIT_SECONDS` (`:11`) and `OverlayService.DEFAULT_WAIT` (`:72`) are three
  copies of the same default constant, already recorded by T024. Cited, not re-filed: **F-112**,
  **F-116**, **F-043** (the unvalidated write path that makes out-of-range per-app values reachable at
  all).
- **Observed against**: `working-tree @ 557a963` (`PerAppLimits.kt`, `ConfigValidator.kt` and
  `PerAppSettingsSheet.kt` are not uncommitted; `OverlayService.kt` is)
- **Disposition**: `verified`

### F-051 â€” `DailyBudget.grantedSessionMs` treats a non-positive remaining budget as "unlimited" and grants a full session, and an existing test pins that behaviour  [Low]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/DailyBudget.kt` â†’ `grantedSessionMs()`
  (lines 51â€“52)
- **Issue**: The function is
  `if (budgetRemainingMs <= 0L) sessionMs else minOf(sessionMs, budgetRemainingMs)`. The KDoc at
  `:44â€“45` explains the guard as the unlimited-budget case â€” "For unlimited budgets `budgetRemainingMs`
  is `Long.MAX_VALUE`, so the full requested `sessionMs` is returned" â€” but `Long.MAX_VALUE` is not
  `<= 0`, so unlimited budgets are handled by the `minOf` branch, correctly and without needing the
  guard. The `<= 0` branch is therefore reached only by the **opposite** condition: a budget that is
  exactly exhausted or overshot (`remainingMs()` at `:19` deliberately returns negative values when
  usage exceeds the budget, and `DailyBudgetTest.kt:37â€“40` asserts exactly that). In that state the
  function grants the **full** requested session â€” the largest possible grant at the moment the budget
  is most depleted, which is the inverse of the clamp's stated purpose at `:47â€“49`.
  **Reachability, stated honestly: I could not find a path that reaches it, and I looked for one.**
  `SessionPolicyEngine.evaluate` gates on `state.isBudgetExhausted()` (`SessionPolicyEngine.kt:70â€“73`),
  which is `budgetRemainingMs <= 0` (`SessionModels.kt:32`) â€” the *identical* threshold â€” and returns
  `ShowBudgetLockScreen` before any session can be granted. `OverlayService.markAppUnlocked` re-reads
  the same two preferences (`:1076â€“1082`) that `buildSessionState` read (`:461â€“468`), and
  `DAILY_USED_MILLIS` is only written on backgrounding (`:590`), so it cannot change while the monitored
  app is in the foreground during the friction wait. So today the branch is dead.
  It is filed as **Low** on that basis â€” a latent trap and a misleading comment, not a live bypass. What
  makes it worth an ID rather than a note is that the trap is *load-bearing on an exact numeric
  coincidence between two files*: the moment anyone changes `isBudgetExhausted()` to `< 0`, adds a
  tolerance, or introduces any path to `markAppUnlocked` that does not pass the engine's budget gate, an
  exhausted daily budget starts handing out full-length sessions silently. That is a Critical-shaped
  outcome sitting behind a one-character change in a different file, with a test pinning the wrong
  behaviour so the change would look safe.
- **Trigger condition**: no observable defect at `557a963`. The condition, stated so it can be
  regression-tested rather than rediscovered:
  1. Change `SessionModels.kt:32` from `budgetRemainingMs <= 0` to `budgetRemainingMs < 0` â€” a plausible
     "don't lock at exactly zero" tweak â€” and rebuild.
  2. Set a 10-minute daily budget on a monitored app and use it until exactly exhausted.
  3. Open the app. The engine no longer reports exhausted, the wait screen shows, and
     `markAppUnlocked` grants a full session on a budget with zero remaining.
  4. Observe the countdown notification runs the full session length with the daily budget at or below
     zero, and no budget lock appears.
- **Blast radius**:
  - *Callers*: `grantedSessionMs()` has exactly one production caller â€”
    `OverlayService.markAppUnlocked()` (`OverlayService.kt:1083â€“1086`) â€” plus
    `DailyBudgetTest.kt:63â€“89`. `markAppUnlocked` is `private` and is reached only through the friction
    screen's unlock path. `DailyBudget.remainingMs()` has two production callers
    (`OverlayService.kt:463` for the engine's `SessionState`, and `:1077` for the grant), which is why
    the two reads agreeing matters.
  - *Process death*: no effect â€” the function is pure; the values behind it are in prefs and survive.
  - *Config change*: no effect.
  - *Foreground/background*: the grant happens while the monitored app is foreground, which is precisely
    why `DAILY_USED_MILLIS` cannot move underneath it and the branch stays unreachable. A future change
    that accumulates usage continuously rather than on backgrounding would break that argument.
  - *Cross-process*: not reachable from `:a11y`.
  - *Persistence*: indirectly â€” the result is written as `ACTIVE_UNTIL` and `LOCKED_UNTIL`
    (`OverlayService.kt:1091â€“1095`), so if the branch ever became reachable it would persist an
    over-long session that outlives the process and re-reads as an active session on the next app switch
    (`SessionPolicyEngine.kt:56`).
  - *Release-only*: no.
- **Fix** (minimal): delete the special case. `grantedSessionMs` becomes
  `minOf(sessionMs, budgetRemainingMs).coerceAtLeast(0L)`. That returns `0` for an exhausted budget, and
  `0` is the correct answer: `activeUntil = now + 0` (`OverlayService.kt:1088`) is immediately in the
  past, so `hasActiveSession` is false and the very next evaluation shows the budget lock â€” the
  behaviour the engine intends. Then correct the KDoc at `:44â€“45`, which currently describes the guard as
  the unlimited case it is not.
- **Rewrite justification**: not applicable â€” one expression.
- **Test to add**: `jvm-unit` â€” modify `app/src/test/java/com/grayzone/app/data/DailyBudgetTest.kt`,
  replacing the existing `granted session falls back to full when remaining is non-positive` test with
  `granted session is zero when budget is exhausted`, asserting
  `assertEquals(0L, DailyBudget.grantedSessionMs(session, 0L))` and the same for `-1L`. Fails before the
  fix (returns the full session), passes after. Add a companion assertion that
  `grantedSessionMs(session, Long.MAX_VALUE) == session` so the unlimited case stays pinned and the fix
  cannot regress it.
- **Existing test conflict**: **yes, and it is the most important part of this finding.**
  `DailyBudgetTest.kt:82â€“89` is:
  ```82:89:app/src/test/java/com/grayzone/app/data/DailyBudgetTest.kt
      @Test
      fun `granted session falls back to full when remaining is non-positive`() {
          // Should never be reached (engine gates on exhaustion first), but must
          // not hand out a zero-length session if it is.
          val session = 10 * 60 * 1000L
          assertEquals(session, DailyBudget.grantedSessionMs(session, 0L))
          assertEquals(session, DailyBudget.grantedSessionMs(session, -1L))
      }
  ```
  The comment shows the author reached the same unreachability conclusion I did, and then chose the
  wrong safe value: "must not hand out a zero-length session" is the premise to challenge, because a
  zero-length session is exactly what should happen â€” it routes straight to the budget lock. The test
  must be rewritten, not deleted, and the fix cannot land while it stands.
- **Verification**: `confirmed` (evidence tier T1) both that the branch inverts its stated purpose and
  that it is currently unreachable. The unreachability argument rests on three files read directly:
  `SessionModels.kt:32` (`isBudgetExhausted()` = `<= 0`), `SessionPolicyEngine.kt:70â€“73` (the gate
  precedes every grant path), and `OverlayService.kt:461â€“468` versus `:1076â€“1082` (the same two prefs
  keys read at both points, with no writer in between â€” `DAILY_USED_MILLIS` is written only at `:590`,
  on backgrounding). No device is needed for either half.
- **Build scope**: everyday-build
- **Constitution**: III in spirit â€” a defensive branch whose fallback value is wrong, so the defence
  silently does the opposite of what its comment claims â€” though the certain violation is II
  (two files encode the same exhaustion threshold and correctness depends on them matching exactly).
- **Affected surfaces**: `DailyBudget.remainingMs()` (`:11â€“20`) is the source of the negative sentinel
  and is fine as written; `SessionModels.isBudgetExhausted()` (`SessionModels.kt:32`) is the coincident
  threshold â€” a fix here removes the coupling, which is the real value of the change;
  `DailyBudget.shouldShowBudgetLock()` (`:55â€“65`) is a *third* encoding of exhaustion
  (`usedMs >= budgetMins * 60 * 1000L`), reached from `OverlayService.kt:604`, so the app expresses "is
  the budget used up?" three different ways in two files.
- **Observed against**: `working-tree @ 557a963` (`DailyBudget.kt`, `DailyBudgetTest.kt`,
  `SessionModels.kt` and `SessionPolicyEngine.kt` are not uncommitted; `OverlayService.kt` is)
- **Disposition**: `verified`

### F-052 â€” `RuntimeSessionReset` clears session state only for currently-monitored apps, so an app removed from monitoring keeps a future lockout that revives when it is re-added  [Medium]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/RuntimeSessionReset.kt` â†’ `resetIfNeeded()`
  (lines 25â€“45, the loop at `:36â€“41`)
- **Issue**: The daily reset enumerates `prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet())`
  (`:36`) and removes `ACTIVE_UNTIL`, `LOCKED_UNTIL` and `REMAINING_MILLIS` only for those packages.
  Any package that is **not currently in the monitored set** keeps whatever session state it had, forever
  â€” the reset never sees it again, and nothing else removes per-package keys anywhere in the app
  (verified: `remove(PrefsKeys.LOCKED_UNTIL` appears only here and in `OverlayService`'s per-package
  writes; there is no unmonitor-time cleanup).
  Two consequences, one benign and one not.
  - **Stale future lockout revives.** If a package is removed from monitoring while `LOCKED_UNTIL + pkg`
    is in the future, that value persists. Re-adding the package restores the old lockout:
    `buildSessionState` reads `lockedUntil` straight from prefs (`OverlayService.kt:443`) and the engine
    shows the lockout screen (`SessionPolicyEngine.kt:62â€“65`) counting down to a timestamp the user
    cannot connect to anything they just did. Because the daily reset only ever touched monitored apps,
    the value can be arbitrarily old â€” a lockout set months ago with a 24 h duration is harmless, but
    the same is not true of the values `StateBackupManager` restores (F-043), which can carry a
    far-future `LOCKED_UNTIL` for a package the user has never monitored.
  - **Unbounded preference growth.** Per-package keys accumulate for every package ever monitored, and
    for every package present in any restored snapshot, and are never removed even when the package is
    uninstalled. `PrefsKeys.kt:45â€“48` alone defines four per-app keys, plus `ACTIVE_UNTIL`,
    `LOCKED_UNTIL`, `REMAINING_MILLIS`, `DAILY_BUDGET_MINUTES`, `DAILY_USED_MILLIS`,
    `DAILY_RESET_DATE`. This is the app's **real** unbounded-growth problem â€” not `usage_events`, which
    is bounded by retention (F-048) â€” and it compounds F-044, because
    `StateBackupManager.writeBackup` snapshots `prefs.all` (`:167`) and that payload is decrypted and
    parsed on the main thread in `Application.onCreate`.
  The KDoc's framing is what conceals it: `:8â€“10` says the function "Clears runtime-only session lock
  state (active sessions, lockouts, paused remainders) once per calendar day, so every day starts
  fresh". It does that for monitored apps; for everything else nothing starts fresh, ever.
- **Trigger condition**:
  1. Monitor an app, open it, and let a session expire so a lockout is active. Confirm the lockout
     screen appears and note the remaining time.
  2. While the lockout is still in the future, remove that app from monitoring in the Apps screen.
  3. Confirm the key survived:
     `adb shell run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml`
     and observe `locked_until_<pkg>` is still present with a future value.
  4. Wait past local midnight (or set `daily_reset_date_runtime` to an earlier date) so
     `resetIfNeeded` runs. Re-read the XML: `locked_until_<pkg>` is **still** there, because the package
     is no longer in `MONITORED_APPS`.
  5. Re-add the app to monitoring and open it. Observe the lockout screen appears immediately, counting
     down to the original timestamp, with no action by the user that would explain it.
- **Blast radius**:
  - *Callers*: `RuntimeSessionReset.resetIfNeeded()` has two production callers â€”
    `GrayzoneApplication.resetRuntimeStateIfNeeded()` (`GrayzoneApplication.kt:71`, reached from
    `onCreate`) and `OverlayService` on the midnight-crossing path (`OverlayService.kt` around
    `:247â€“273`, the `millisUntilMidnight` timer) â€” plus its unit tests. Both were confirmed by direct
    search; the KDoc at `:12â€“13` names exactly these two, and the KDoc is accurate.
  - *Process death*: the reset is idempotent per calendar day via `DAILY_RESET_DATE + "runtime"`
    (`:29`, `:42`), so process death simply means the next start performs it. Orphaned keys are
    unaffected by process death â€” they are persistent by definition.
  - *Config change*: no effect.
  - *Foreground/background*: the `OverlayService` caller runs in the background at midnight while the
    user may be inside a monitored app, so the fix must remain safe to run mid-session.
  - *Cross-process*: not reachable from `:a11y` â€” `GrayzoneApplication.kt:54` returns before `:71`, and
    the `OverlayService` caller is default-process. This matters because the function writes prefs, and
    the `:52â€“53` comment states `:a11y` must never do that.
  - *Persistence*: yes, and it is the substance of the finding â€” the defect is which persisted keys are
    *not* removed. A fix that enumerates all keys must use `prefs.all.keys` and filter by the prefix
    constants in `PrefsKeys`, which is also the mechanism F-043's allowlist needs, so the two fixes
    share a helper.
  - *Release-only*: no.
- **Fix** (minimal): stop enumerating the monitored set and enumerate the *keys* instead. Replace the
  `monitoredApps.forEach` loop (`:36â€“41`) with a pass over `prefs.all.keys` removing any key that starts
  with `PrefsKeys.ACTIVE_UNTIL`, `PrefsKeys.LOCKED_UNTIL` or `PrefsKeys.REMAINING_MILLIS`. This is
  strictly more correct (it is a superset of today's behaviour), it is the same number of lines, and it
  fixes both consequences at once â€” the stale lockout and the growth. It also removes the dependency on
  `MONITORED_APPS` being readable, which matters because that key is one of the ones a poisoned restore
  can type-drift (F-043).
  A second, independent line worth taking in the same pass: remove a package's per-app keys at the point
  it is unmonitored, in the Apps screen's toggle handler. That is the real root of the growth; the reset
  change is the safety net.
- **Rewrite justification**: not applicable â€” the loop's iteration source changes.
- **Test to add**: `jvm-unit` â€” extend the existing `RuntimeSessionReset` test file (the function
  already takes an injectable `now` at `:27`, so it is directly unit-testable with a map-backed
  `SharedPreferences` double). Assert that a prefs map containing `locked_until_com.foo` and
  `active_until_com.foo` while `MONITORED_APPS` is `emptySet()` has **both keys removed** after
  `resetIfNeeded` reports `true`. Fails before the fix (both keys survive), passes after. Add a second
  assertion that `DAILY_BUDGET_MINUTES` and `DAILY_USED_MILLIS` are **not** removed, since the KDoc at
  `:9â€“10` states daily budgets reset through their own date-keyed storage and the fix must not widen
  into them.
- **Existing test conflict**: none â€” existing coverage asserts the monitored-app and
  same-day-idempotence cases, both of which the fix preserves.
- **Verification**: `confirmed` (evidence tier T1). The loop's iteration source is read directly at
  `:36`, the three removed keys at `:38â€“40`, and the absence of any other per-package cleanup was
  established by searching `app/src` for removals of these prefixes. The revival path is confirmed by
  reading `OverlayService.kt:443` (raw `lockedUntil` read) and `SessionPolicyEngine.kt:62â€“65` (the
  lockout gate). No device needed.
- **Build scope**: everyday-build
- **Constitution**: III is not the issue here; this is I/II â€” a stated invariant ("every day starts
  fresh") that the implementation delivers for only part of the key space.
- **Affected surfaces**: the `DAILY_RESET_DATE + "runtime"` composite key (`:17`, `:29`, `:42`) builds a
  global marker inside a per-app key namespace, outside `PrefsKeys`' knowledge â€” carried in the register
  as a Medium lead for T020/T021, and it belongs to this same function: renaming the
  `DAILY_RESET_DATE` prefix makes `getString` return `""`, which triggers exactly one spurious reset that
  wipes every active session and lockout after an upgrade. The minimal mitigation is to promote the
  composite to a named constant in `PrefsKeys` so a rename cannot silently split it; that is a one-line
  change and should ship with this fix. `StateBackupManager.serialize`'s `prefs.all` snapshot (`:167`)
  is what turns the growth into a startup-latency problem (F-044). Cited, not re-filed: **F-043**,
  **F-044**, **F-060** (which absorbs the `resetRuntimeStateIfNeeded` unguarded-read instance at
  `:29`/`:36`).
- **Observed against**: `working-tree @ 557a963` (`RuntimeSessionReset.kt` is not uncommitted;
  `GrayzoneApplication.kt` and `OverlayService.kt`, its two callers, both are)
- **Disposition**: `verified`

### F-053 â€” Two of the four `usage_events` indices serve no query the app actually runs, and the composite is ordered against the dominant one; the documented "50-100x faster" claim is unevidenced  [Low]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/UsageEvent.kt` â†’ `@Entity(indices = â€¦)`
  (lines 19â€“27) and `app/src/main/java/com/grayzone/app/data/UsageDatabase.kt` â†’ `MIGRATION_1_2`
  (lines 24â€“32)
- **Issue**: Four indices are declared and created. Matched against every query in `UsageDao.kt`:

  | Index | Columns | Query it serves | Verdict |
  |---|---|---|---|
  | `idx_date_key` | `dateKey` | `getDailySummary` (`:17`), `getWeeklyTotals` (`:27`), `getEventsForDate` (`:43`), `getAppUsageSince` (`:67`), `deleteEventsOlderThan` (`:73`) | earns its keep |
  | `idx_pkg_date` | `packageName, dateKey` | `getDailyUsageForApp` (`:21`) only | usable, but see below |
  | `idx_start_time` | `startTime` | **none** | write cost, no read benefit |
  | `idx_was_blocked` | `wasBlocked` | `getTotalSessionsBlocked` (`:31`), `getTotalBlockedDurationMillis` (`:35`) â€” **neither has a main-source caller** | serves tests only |

  `idx_start_time` cannot serve the only query that mentions `startTime` in a filtering or grouping
  position: `getStartTimeTotals` (`:59`) groups by
  `CAST(strftime('%H', startTime / 1000, 'unixepoch', 'localtime') AS INTEGER)`, an expression over the
  column, and SQLite cannot use a plain column index for an expression key. The other `startTime`
  reference is `getEventsForDate`'s `ORDER BY startTime DESC` (`:43`) after `WHERE dateKey = ?`, for
  which SQLite will use `idx_date_key` and then sort; a `(dateKey, startTime)` composite would serve
  both clauses, whereas a `startTime`-only index serves neither.
  `idx_pkg_date`'s column order is also backwards for the app's dominant pattern: `getDailySummary` and
  `getAppUsageSince` filter on `dateKey` and group by `packageName`, which `(dateKey, packageName)` would
  serve end-to-end; the declared `(packageName, dateKey)` has the wrong leading column for those and is
  useful only to `getDailyUsageForApp`.
  So the app pays four B-tree inserts on every `insertEvent` (`OverlayService.kt:1117`, once per friction
  skip, plus the usage write near `:570`) for two indices that no live query uses.
  **The "50-100x" claim in both files is unevidenced and, for two of the four, cannot be true.**
  `UsageEvent.kt:17` says "Expected performance improvement: 50-100x faster queries at scale" and
  `UsageDatabase.kt:22` says "Impact: 50-100x faster queries on large datasets (>10k events)". No
  benchmark exists in the repository (no `androidTest` benchmark, no macrobenchmark module â€” verified by
  search). More decisively, "at scale" is not a state this app reaches: `CleanupWorker`'s 90-day
  retention caps the table at roughly 18,250 rows by its own KDoc estimate (`CleanupWorker.kt:16â€“19`),
  and a full scan of 18k narrow rows is single-digit milliseconds on any supported device. **This is
  filed Low precisely because the performance stakes are negligible either way** â€” the defect is a
  documented claim the code does not support, plus two indices whose write cost buys nothing. Inflating
  it would be severity inflation of exactly the kind the contract prohibits.
- **Trigger condition**: no user-visible defect. Verifiable statically and, if desired, on a device:
  1. Read `UsageDao.kt` and confirm no query filters, joins or groups on `wasBlocked` from a live
     caller, and none can use a plain `startTime` index.
  2. On a device, prove it with SQLite's own planner:
     `adb shell run-as com.grayzone.app sqlite3 databases/grayzone_usage.db "EXPLAIN QUERY PLAN SELECT CAST(strftime('%H', startTime / 1000, 'unixepoch', 'localtime') AS INTEGER) as hourOfDay, SUM(durationMillis) as totalMillis FROM usage_events GROUP BY hourOfDay ORDER BY totalMillis DESC;"`
     and observe `SCAN usage_events` with no index use.
  3. Repeat for `SELECT COUNT(*) FROM usage_events WHERE wasBlocked = 1` and note that even when the
     planner does use `idx_was_blocked`, the query has no main-source caller (step 1).
- **Blast radius**:
  - *Callers*: the indices are consumed by the SQLite planner, not by Kotlin code. The write cost falls on
    `UsageDao.insertEvent` (`:11â€“12`), whose two production callers are `OverlayService.kt:1117`
    (`recordFrictionSkip`) and the usage write reached from `OverlayService.kt:570`. The queries that
    would benefit are enumerated in the table above; their caller status is established in F-054.
  - *Process death*: no effect â€” indices are on-disk schema.
  - *Config change*: no effect.
  - *Foreground/background*: inserts happen in the background on `Dispatchers.IO`
    (`OverlayService.kt:115â€“116`), so the extra write cost never touches the main thread. This is part of
    why the severity is Low.
  - *Cross-process*: not applicable â€” the database is opened in one process only (F-042).
  - *Persistence*: yes â€” the indices are part of the persisted schema and are recorded in
    `app/schemas/â€¦/2.json:66â€“103`. Any change to them requires a **new migration (2â†’3)** and a new schema
    JSON; they cannot be edited in place, which is the main reason to weigh this fix carefully against
    its negligible benefit.
  - *Release-only*: no.
- **Fix** (minimal): **do nothing to the indices, and fix the documentation.** This is the recommendation,
  not a hedge. Dropping `idx_start_time` and `idx_was_blocked` would require a 2â†’3 migration plus a new
  schema JSON plus an updated `MigrationTest`, to save four B-tree writes per session on a table capped at
  ~18k rows â€” a poor trade, and migrations are the highest-risk change available in this file. The
  minimal correct action is to replace the unevidenced "50-100x" claims at `UsageEvent.kt:17` and
  `UsageDatabase.kt:22` with what is actually true: which query each index serves, that two currently
  serve none, and that the table is retention-capped so index choice is not performance-critical. If a
  future change introduces a live caller for the peak-hour or blocked-count queries at scale, the note
  will tell the next reader that `(dateKey, startTime)` and `(dateKey, packageName)` are the shapes worth
  having.
- **Rewrite justification**: not applicable â€” the proposed change is to comments only, deliberately.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/data/QueryPlanTest.kt`, running `EXPLAIN QUERY PLAN` against
  an in-memory database (the pattern `UsageDaoStatsTest` and `PeakHourAggregationTest` already use) and
  asserting that `getDailySummary`'s plan uses `idx_date_key` and that `getStartTimeTotals`' plan is a
  scan. The second assertion documents the limitation in executable form so nobody re-adds a
  `startTime` index expecting it to help. **Cannot be executed in the current environment** â€” no device
  or emulator â€” so it lands pending.
- **Existing test conflict**: none, and worth noting for anyone tempted to drop an index:
  `MigrationTest.migrate1To2` (`:46â€“49`) asserts all four index names exist after migration, so removing
  any of them requires editing that test as well as adding a 2â†’3 migration.
- **Verification**: `confirmed` (evidence tier T1) for the index-to-query mapping and for the absence of
  any benchmark. Every query in `UsageDao.kt` was read and matched by hand against the four index
  definitions in `UsageEvent.kt:21â€“26`, and the declared schema was cross-checked against
  `app/schemas/com.grayzone.app.data.UsageDatabase/2.json:66â€“103` (names, columns and uniqueness all
  match the migration's `CREATE INDEX` statements exactly). The claim that SQLite cannot use a column
  index for an expression key is standard planner behaviour, and step 2 above is offered to demonstrate
  it rather than asserting it as measured here.
- **Build scope**: everyday-build
- **Constitution**: none directly; this is documentation accuracy and dead write-cost â€” closest to VI if
  the constitution covers evidence for performance claims, otherwise `none`.
- **Affected surfaces**: `MIGRATION_1_2` (`UsageDatabase.kt:24â€“32`) creates the same four indices and
  carries the same unevidenced claim in its KDoc; `app/schemas/â€¦/2.json` is the third place the set is
  recorded. `evidence/r8.md` Â§F4's observation that `DateTotalRow` and `StartTimeTotalRow` are removed by
  R8 is the same underlying fact seen from the shrinker â€” see F-054, which owns it.
- **Observed against**: `557a963` (neither `UsageEvent.kt` nor `UsageDatabase.kt` is among the nine
  uncommitted files)
- **Disposition**: `verified`

### F-054 â€” Eight of `UsageDao`'s eleven query methods have no main-source caller: the analytics layer is fully built, tested by instrumented tests, and surfaced nowhere  [Low]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/UsageDao.kt` (lines 14â€“68) and the projection
  types `DateTotalRow` (lines 89â€“93) and `StartTimeTotalRow` (lines 95â€“99)
- **Issue**: Direct search over `app/src` for each DAO method gives:

  | Method | Main-source callers | Other references |
  |---|---|---|
  | `insertEvent` (`:12`) | `OverlayService.kt:1117`, and the write reached from `:570` | tests |
  | `getDailySummary` (`:18`) | `StreakEvaluator.kt:32` | `UsageDaoStatsTest.kt:60` |
  | `deleteEventsOlderThan` (`:74`) | `CleanupWorker.kt:47` | â€” |
  | `getEventCount` (`:78`) | `CleanupWorker.kt:48` | â€” |
  | `getDailyUsageForApp` (`:22`) | **none** | `UsageDaoStatsTest.kt:66` |
  | `getWeeklyTotals` (`:28`) | **none** | `UsageDaoStatsTest.kt:76` |
  | `getTotalSessionsBlocked` (`:32`) | **none** | `UsageDaoStatsTest.kt:90` |
  | `getTotalBlockedDurationMillis` (`:36`) | **none** | `UsageDaoStatsTest.kt:91` |
  | `getTotalSessions` (`:40`) | **none** | `UsageDaoStatsTest.kt:92` |
  | `getEventsForDate` (`:44`) | **none** | none at all |
  | `getStartTimeTotals` (`:60`) | **none** | `PeakHourAggregationTest.kt:74` |
  | `getTotalEventCount` (`:64`) | **none** | none at all |
  | `getAppUsageSince` (`:68`) | **none** | none at all |

  So the app records usage events and then reads back only one aggregate (`getDailySummary`, for streak
  evaluation) plus the two cleanup counters. Eight query methods â€” per-app daily totals, weekly totals,
  blocked-session counts, blocked duration, total sessions, per-date event lists, peak-hour buckets,
  and per-app totals over a window â€” have no production consumer, and three of them
  (`getEventsForDate`, `getTotalEventCount`, `getAppUsageSince`) are referenced nowhere at all, not even
  by a test.
  Meanwhile the statistics the UI *does* show come from SharedPreferences counters in `StreakManager`
  (`TOTAL_SESSIONS_BLOCKED`, `TOTAL_TIME_SAVED_MINS`, `CURRENT_STREAK`, `LONGEST_STREAK`), which are
  incremented independently at `StreakManager.kt:43â€“51`. So the app maintains **two parallel statistics
  systems**: a normalised event table with a full query surface that nothing reads, and a set of prefs
  counters that the UI reads and that can drift from the events (a lost `insertEvent` â€” see F-040 â€”
  changes one and not the other).
  **This corroborates and completes the `evidence/r8.md` Â§F4 lead** assigned to this task ("`DateTotalRow`
  / `StartTimeTotalRow` removed by R8 â†’ DAO projections possibly exercised only by instrumented tests").
  It is now confirmed from a second, independent direction: `DateTotalRow` is returned only by
  `getWeeklyTotals` and `StartTimeTotalRow` only by `getStartTimeTotals`, and neither method has a
  production caller, which is exactly why R8 can remove both types. The lead's "possibly" can be dropped.
  Filed **Low** per the rubric's dead-code row. **Explicitly not a deletion recommendation** â€” see *Fix*.
- **Trigger condition**: no runtime defect. The verification that establishes it, repeatable by anyone:
  1. For each method name in the table, search `app/src` and confirm the caller set. This is the check
     the reference-index accuracy warning requires, and it is what I did rather than relying on the index.
  2. Confirm the shrinker agrees: `evidence/r8.md` Â§F4 already records `DateTotalRow` and
     `StartTimeTotalRow` as whole-class removals, which is only possible if no reachable code constructs
     them.
  3. Confirm the parallel system: open the Home screen and observe the displayed streak and
     blocked-session figures, then read `StreakManager.kt:34â€“37` and see they come from prefs, not from
     `usage_events`.
- **Blast radius**:
  - *Callers*: enumerated exhaustively in the table above by direct search over `app/src`. Three methods
    have zero references of any kind; five are instrumented-test-only; three are live.
  - *Process death*: not applicable.
  - *Config change*: not applicable.
  - *Foreground/background*: not applicable.
  - *Cross-process*: not applicable â€” one process opens the database (F-042).
  - *Persistence*: yes, and this is the substantive risk: the app is **paying the full write cost** of a
    normalised event table (plus four indices, F-053) to support reads that do not happen, while the
    figures the user actually sees come from prefs counters that can silently diverge from it. Any
    decision here should be about which system is the source of truth, not about deleting methods.
  - *Release-only*: yes, in one respect worth recording â€” the two projection classes are already absent
    from the release build (R8 removes them), so the `getWeeklyTotals` and `getStartTimeTotals` code paths
    are exercised **only** by instrumented tests running against a debug-ish build. If a caller is ever
    added, its first execution in release will be against code that has never run there. That is the
    practical reason this finding matters more than a typical dead-code entry.
- **Fix** (minimal): **do not delete these methods.** Two of them â€”`getStartTimeTotals` and
  `getWeeklyTotals`â€” carry non-trivial, deliberately-reasoned SQL: the comment at `:53â€“57` documents a
  real aliasing hazard that was found and fixed ("A `startTime` alias would collide with the real
  `startTime` column and make `GROUP BY startTime` ambiguous"), and `PeakHourAggregationTest` exists as a
  named regression test for it ("Regression test for D4"). Deleting that would discard a fix and its
  test. The minimal action is a decision, recorded in the file:
  1. Add a KDoc line at the top of `UsageDao` stating which methods are currently unconsumed and that
     the UI's statistics come from `StreakManager`'s prefs counters, so the next reader does not assume
     the table is the source of truth.
  2. Route the one figure that exists in both systems through the events table rather than the prefs
     counter â€” `getTotalSessionsBlocked()` (`UsageDao.kt:32`) versus
     `StreakManager.getTotalSessionsBlocked()` (`StreakManager.kt:36`), which are two functions with the
     same name and different sources. That single change removes the drift and gives the events table a
     production consumer, at which point most of the "dead" surface becomes justified rather than
     unexplained.
  3. Only after that, if any method is still unconsumed, decide deletion â€” with a fresh direct search, not
     from this table, since this audit's own experience is that index-derived dead-code claims have been
     wrong twice (`AdBlockVpnService.onRevoke`, `DnsTrafficBus.clear`) and that two same-named functions
     can differ in liveness (`millisUntilMidnight`).
- **Rewrite justification**: not applicable; no rewrite and, deliberately, no deletion.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StatisticsSourceConsistencyTest.kt`, asserting that the
  blocked-session figure the UI renders and the figure derivable from recorded events agree for a
  scripted sequence of friction skips. This requires step 2 of the fix to exist, so it is a
  fix-accompanying test rather than a failing-first test; stated plainly because the contract forbids
  test placeholders. The failing-first test for the *current* state is not writable â€” "no caller exists"
  is not an assertable runtime property, which is precisely why this class of defect survives.
- **Existing test conflict**: none. `UsageDaoStatsTest` and `PeakHourAggregationTest` must both be
  **kept** â€” they are the only exercise these queries get, and `PeakHourAggregationTest` guards a real
  prior fix.
- **Verification**: `confirmed` (evidence tier T1). Each row of the caller table came from a direct
  search over `app/src` for the method name, performed for this finding rather than read from
  `evidence/reference-index.md`, in line with the standing accuracy warning about that file. The R8
  corroboration is from `evidence/r8.md` Â§F4.
- **Build scope**: everyday-build (the finding), with a `release-only` consequence noted above â€” the
  projections are absent from release, so add this to the release-only view.
- **Constitution**: V (Principle V demands proof before removal â€” which is why this is filed as a
  documented decision rather than a deletion), and II (two parallel statistics systems for one concept).
- **Affected surfaces**: `StreakManager.getTotalSessionsBlocked()` (`StreakManager.kt:36`) is the
  prefs-backed twin of `UsageDao.getTotalSessionsBlocked()` (`UsageDao.kt:32`); `DateTotalRow` and
  `StartTimeTotalRow` (`UsageDao.kt:89â€“99`) are the projection types R8 removes; F-053 is the index-side
  view of the same fact; `evidence/r8.md` Â§F4 and `evidence/reference-index.md` both record fragments of
  it. **`dead-code-inventory.md` (T064) must not list these methods as deletable** on the strength of
  this finding â€” the *Fix* section states why.
- **Observed against**: `557a963` (`UsageDao.kt` is not among the nine uncommitted files)
- **Disposition**: `verified`

### F-055 â€” `ScheduleRule` is deserialized by plain Gson, so adding any field silently sets `enabled = false` on every saved rule and every schedule lock stops firing  [Medium]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/ScheduleManager.kt` â†’ `ScheduleRule`
  (lines 13â€“22) and `getScheduleRules()` (lines 33â€“39)
- **Issue**: `getScheduleRules` parses `SCHEDULE_RULES_JSON` with `gson.fromJson(json, type)` for
  `List<ScheduleRule>`. Gson is not Kotlin-aware: it allocates instances through `Unsafe` and never runs
  the Kotlin constructor, so **default parameter values are not applied and non-null types are not
  enforced**. For any field absent from the stored JSON, Gson leaves the JVM default â€” `false` for
  `Boolean`, `0` for `Int`, `null` for reference types â€” regardless of what the Kotlin declaration says.
  `ScheduleRule.enabled` (`:21`) is declared `val enabled: Boolean = true`. If a stored rule object lacks
  an `enabled` member, it deserializes as **`false`**, and `matchesAnyRule` returns early for it
  (`:168`: `if (!rule.enabled) return@any false`). The rule silently stops blocking. There is no crash,
  no log, and the UI will render the rule as disabled, so the user sees a toggle that is off without
  having turned it off â€” or, worse, does not look, and simply finds that bedtime no longer locks
  anything.
  The same mechanism makes `daysOfWeek: Set<Int>` (`:20`) deserialize as `null` if absent, and
  `matchesAnyRule` then evaluates `dayOfWeek in rule.daysOfWeek` (`:175`, `:178`, `:179`) against `null`
  â†’ `NullPointerException`, thrown from `isCurrentlyScheduled()` (`:75`) inside
  `OverlayService.buildSessionState()` (`:480`) â€” i.e. **on every app switch**.
  **The existing `try/catch` does not cover either case.** The handler at `:38` catches parse failures;
  here Gson *succeeds* and returns structurally-valid objects with wrong field values, so the
  malformed-JSON path (owned by **F-205**) never engages. This is a distinct defect in the same
  function and is not a re-filing of F-205: F-205 is about a thrown parse error becoming an empty list
  and then being written back over the user's rules; this is about a *successful* parse producing wrong
  rules.
  **Reachability, stated precisely.** Today every stored rule is written by `saveScheduleRules` (`:42`)
  from a complete object, so all members are present and no current install is affected. The two routes
  that make it live are both realistic:
  (a) **any future field added to `ScheduleRule`** â€” every rule saved by the previous version lacks it,
  so for a `Boolean` it silently becomes `false` and for a `Set`/`String` it becomes `null`; and
  (b) **`StateBackupManager.applyInto`** (F-043), which restores `SCHEDULE_RULES_JSON` as an arbitrary
  string from an unvalidated file, so a snapshot written by an older version with fewer fields is applied
  verbatim.
  Route (a) is the one that should drive the fix: it converts an ordinary, innocuous-looking schema
  addition into silent, total loss of schedule enforcement.
- **Trigger condition**:
  1. Create a bedtime schedule rule (e.g. 22:00â€“06:00, all days) and confirm a monitored app is blocked
     inside that window.
  2. Simulate the upgrade case without changing code: read the stored JSON
     (`adb shell run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/GrayzonePrefs.xml`),
     remove the `"enabled":true` member from the rule object, and write it back.
  3. Restart the app and re-enter the schedule window. Observe the app is **not** blocked, and the
     Schedule screen shows the rule as disabled.
  4. For the null-field variant, repeat step 2 removing `"daysOfWeek"` instead, then switch to a
     monitored app and observe the `NullPointerException` in logcat
     (`adb logcat | grep -i grayzone`) from `matchesAnyRule`, and that the app-switch evaluation is lost.
  5. Equivalent code-side reproduction of route (a): add any `val newField: Boolean = true` to
     `ScheduleRule`, rebuild, install as an update, and observe every pre-existing rule now has
     `enabled = false` â€” because the old JSON also lacks nothing, which is the subtle part: it is the
     *new* field that is absent, and Gson's `Unsafe` allocation means the whole object skips constructor
     defaults, so `enabled` is only preserved because it happens to be present in the JSON. Removing
     `enabled` from the JSON (step 2) and adding a new field are two views of the same hazard.
- **Blast radius**:
  - *Callers*: `getScheduleRules()` â†’ `isCurrentlyScheduled()` (`:75`), `addRule()` (`:46`),
    `removeRule()` (`:52`), `toggleRule()` (`:57`), plus the Schedule UI. `isCurrentlyScheduled()` â†’
    `OverlayService.buildSessionState()` (`OverlayService.kt:480`), consumed by
    `SessionPolicyEngine.evaluate` at `:44â€“47` (`ShowScheduleLockScreen`). `matchesAnyRule` is also
    called directly by its unit tests. Enumerated by direct search over `app/src`.
  - *Process death*: no mitigation â€” the defect is in the persisted JSON, so every process start
    re-reads the same wrong rules.
  - *Config change*: no effect.
  - *Foreground/background*: the decisive read is `isCurrentlyScheduled()` on every app switch in the
    background, so a schedule that has silently stopped firing produces no UI event at all. For the NPE
    variant, the throw lands in `OverlayService`'s coroutine and is absorbed by its
    `coroutineExceptionHandler` (`OverlayService.kt:116`), so the app survives and simply fails to
    evaluate that app switch â€” silent protection loss rather than a crash.
  - *Cross-process*: not reachable from `:a11y` â€” `GrayzoneAccessibilityService` reads no prefs
    (independently re-verified). The app-switch signal originates there but the rule evaluation is
    default-process.
  - *Persistence*: yes, and it is the crux â€” the defect exists only because the persisted form and the
    Kotlin declaration can disagree, with Gson silently resolving the disagreement in favour of JVM
    defaults. Any fix must be about the persistence boundary, not about `matchesAnyRule`.
  - *Release-only*: no, but adjacent: R8 obfuscation of field names would break Gson reflection entirely.
    `evidence/r8.md` records no `@SerializedName` annotations and no Gson keep rules on this class, so a
    future change to the shrinker configuration could turn a silent-wrong-value bug into total schedule
    loss. Worth a line in `release-only.md`.
  - *Persistence* and *Release-only* together are why this is Medium rather than Low despite being latent.
- **Fix** (minimal): make the absent-field case explicit rather than silent.
  1. Declare the fields Gson must find, and validate after parsing. The smallest robust change inside
     `getScheduleRules` is to parse into `List<ScheduleRule?>`, then filter and repair: drop entries whose
     `daysOfWeek` is `null` (logging with `GrayzoneLogger.w`), and â€” the important half â€” **do not rely on
     `enabled`'s Kotlin default**; treat a missing `enabled` as `true` by reading the raw
     `JsonObject` for that member, or by changing the field to `val enabled: Boolean? = null` with an
     `isEnabled` accessor defaulting to `true`. The second is cleaner and keeps the call site at `:168`
     a one-liner (`if (rule.isEnabled != true) â€¦` â†’ `if (!rule.isEnabled)`).
  2. Add `@SerializedName` to every `ScheduleRule` field so the wire format is pinned independently of
     Kotlin names and of R8.
  3. Version the stored JSON, so a future field addition can be detected rather than inferred from
     absence. This is the same missing-schema-gate theme as F-043 and should reuse whatever convention
     that fix establishes.
  Step 1 alone closes the harm; steps 2 and 3 stop it recurring.
- **Rewrite justification**: not applicable â€” the parse call site gains a validation pass; the data class
  and `matchesAnyRule` keep their shape.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ScheduleRuleDeserializationTest.kt`, feeding raw JSON strings
  through the same Gson configuration: (a) a rule object with no `enabled` member must deserialize to an
  effectively-enabled rule and `matchesAnyRule` must match inside its window; (b) a rule object with no
  `daysOfWeek` must be dropped rather than producing an object that throws when matched. Both fail before
  the fix â€” (a) yields `enabled = false` and no match, (b) yields a `NullPointerException` â€” and pass
  after. `matchesAnyRule` is already `companion`-level and pure (`:160`), so no Android framework or
  `SharedPreferences` double is needed and the missing
  `testOptions { unitTests.isReturnDefaultValues = true }` is not a blocker.
- **Existing test conflict**: none. Existing coverage drives `matchesAnyRule` with hand-constructed
  `ScheduleRule` objects, which go through the Kotlin constructor and therefore never exhibit this â€” that
  is exactly why the defect is invisible to the current test suite, and worth stating so the new test is
  not merged into the old file's style.
- **Verification**: `confirmed` (evidence tier T1) for the code shape and the consequence path:
  the plain `gson.fromJson` with a `TypeToken` and no Kotlin adapter (`:36â€“37`), no
  `@SerializedName` anywhere in the file, `enabled`'s Kotlin default at `:21`, the early return at
  `:168`, the `null`-dereferencing membership tests at `:175â€“179`, and the wiring through
  `OverlayService.kt:480` â†’ `SessionPolicyEngine.kt:44`. Gson's `Unsafe`-based instantiation bypassing
  Kotlin constructors and defaults is documented Gson behaviour rather than something measured here.
  - *Missing*: nothing for the mechanism. A device is only needed to demonstrate the end-to-end
    user-visible effect of steps 2â€“4, which is a convenience, not a dependency.
  - *Procedure*: steps 1â€“4 above; step 5 is the code-side equivalent and needs only a rebuild.
  - *Severity effect*: **Medium** as filed, because no shipped install is affected today â€” the harm needs
    either a future field addition or a restored older snapshot. If a field is added to `ScheduleRule` in
    any release without this fix â†’ **Critical**, since every existing user's schedule locks would
    silently stop firing with no crash and no notification, which is the register's worst category. The
    finding should therefore be treated as a **blocker on any change to `ScheduleRule`'s shape** rather
    than as ordinary Medium backlog, and T034 should annotate it that way.
- **Build scope**: everyday-build
- **Constitution**: III (a deserialization boundary whose failure mode is silently-wrong data rather than
  an exception, so the existing handler cannot see it)
- **Affected surfaces**: `StreakManager.getAchievements()` (`:128â€“140`) and `unlockAchievement()`
  (`:142â€“155`) use the same plain-Gson pattern for `Map<String, String>`, which is a less dangerous shape
  (a map has no fields to default) but the same convention â€” worth pinning with `@SerializedName` at the
  same time; `OverlayService.kt:848â€“860` parses `CUSTOM_PROMPTS_JSON` with the shared Gson instance, and
  `CustomPromptsStore` is owned by **F-148** (F-215 withdrawn). Cited, not re-filed: **F-241** (the
  malformed-JSON and `addRule`-overwrite defect in this same function â€” C3), **F-043** (the restore
  route that makes an older-format JSON reachable without an upgrade).
- **Observed against**: `working-tree @ 557a963` (`ScheduleManager.kt` is not among the nine uncommitted
  files; `OverlayService.kt`, which consumes the result on every app switch, is)
- **Disposition**: `verified`

## Closing notes â€” T020 wrap-up

> Written 2026-07-31 by a follow-up gap check, after the original T020 agent was terminated by resource
> exhaustion with F-055 complete. No existing record was re-analysed, renumbered, or edited; this section
> only closes the unit and records what the crash prevented. Where this section disagrees with an earlier
> record, the record is left as written and the disagreement is called out below for the register
> maintainer.

### ID range consumed

Reserved block: **F-040 â€¦ F-059**. Consumed: **F-040 â€¦ F-055, sixteen records, no gaps and no reuse.**
**F-056, F-057, F-058 and F-059 were never written** and are released back to the register. The gap check
found no unanswered required-coverage area that would justify spending them (see the coverage map below).

Two artefacts of the crash are left in place rather than rewritten:

- The **`Scope examined` table's `Outcome` column was written against an earlier numbering plan** and is
  off by one against the records as filed from `UsageDao.kt` downwards â€” e.g. it lists
  `RuntimeSessionReset.kt` as F-051 where the filed record is F-052, and `UsageDao.kt` as F-052/F-053
  where the filed records are F-053/F-054. The drift starts at `StateBackupManager.kt`, which produced
  five records (F-043 â€¦ F-047) where the plan allowed four. The **filed record headings are
  authoritative**; the table is not.
- **Four forward references point at records that do not exist**: F-058 (cited at the F-041 severity note,
  in F-041's *Issue*, and twice in F-047) and F-059 (cited in F-042's *Blast radius*). Under the earlier
  plan these were to cover `StreakManager.kt`, `StreakEvaluator.kt`, `ProtectionHealthRepository.kt` and
  `ProtectionStatus.kt`. **No record was filed for any of those four files.** They were demonstrably read
  â€” they are cited as supporting evidence throughout F-040, F-041, F-047 and F-054 â€” but whether the
  absence of a record means "no defect warranted one" or "the crash intervened first" cannot be
  determined from the surviving output. Closing that question would require re-analysis rather than a gap
  check, so it is **flagged for the register maintainer** rather than filed here. The two specific
  defects those references assert as owned elsewhere are: the unguarded `StreakEvaluator.run` caller at
  `HomeScreen.kt:56â€“58` (confirmed present by this gap check â€” `rememberCoroutineScope()`, no `try/catch`,
  no `CoroutineExceptionHandler`), and `ProtectionHealthRepository`/`ProtectionStatus` as the proposed
  home for F-047's health signal. Both are currently **unowned**.

### Severity counts

Counted from the sixteen filed headings, not estimated.

| Severity | Count | Findings |
|---|---|---|
| Critical | 2 | F-043, F-049 |
| High | 5 | F-040, F-041, F-044, F-045, F-046 |
| Medium | 5 | F-047, F-048, F-050, F-052, F-055 |
| Low | 4 | F-042, F-051, F-053, F-054 |
| **Total** | **16** | F-040 â€¦ F-055 |

One internal inconsistency, left uncorrected: **F-041's heading is `[High]`** per the revision note
directly beneath it, but the *Severity effect* field further down still opens "silent permanent loss of
recording confirmed â†’ **Medium** as filed". The revision note supersedes it â€” the escalation condition
that field names ("if the exception escapes to a crash on any path â†’ High") was confirmed statically via
`HomeScreen.kt:56â€“58`. **High is the filed severity** and is what the count above uses.

### Required-coverage map

| # | Required area | Answered by | How it is answered |
|---|---|---|---|
| 1 | Is the 1â†’2 Room migration destructive? | **F-041**, with **F-053** and **F-040** | **Both directions are assessed.** *Upgrade*: non-destructive and correct â€” `MIGRATION_1_2` is registered and `fallbackToDestructiveMigration()` is deliberately omitted (F-041 *Issue*); the migration body is four `CREATE INDEX IF NOT EXISTS` statements and nothing else, so no data is dropped (F-053 *Fix*/*Affected surfaces*, F-040 *Persistence*, re-verified against `UsageDatabase.kt:24â€“32` during this gap check). *Downgrade*: `fallbackToDestructiveMigrationOnDowngrade()` is missing, which is the filed defect. |
| 2 | Main-thread DAO calls | **F-044**, plus the sweep recorded below | F-044 owns the main-thread I/O defect on the *backup* path (`restoreIfWiped` in `Application.onCreate`). The distinct Room-DAO question is answered by the call-site enumerations in **F-040** (*Blast radius â†’ Callers*, four `getInstance` sites with their dispatchers) and **F-054** (per-method caller table), and by the explicit sweep recorded under *Room DAO main-thread sweep* below: **no Room DAO call reaches the main thread**, so no record was filed. |
| 3 | Unbounded `UsageEvent` growth; does `CleanupWorker` survive Doze? | **F-048**, with **F-052** | *Growth*: F-048 *Issue* establishes the table is bounded in rows by retention (~18k) but that the retention **definition** is frozen by the `KEEP` policy, and that a `DELETE` without `VACUUM` bounds rows but not file bytes. F-052 redirects the app's real unbounded growth to per-package SharedPreferences keys. *Doze*: **answered, and the hypothesis is refuted** â€” F-048 records that the worker uses `Constraints.NONE` on a 7-day period, which is the most Doze-tolerant configuration available, and warns that adding `requiresDeviceIdle`/`requiresCharging` would make it worse. The residual risk is named as OEM job killing, not Doze, with a device procedure attached. |
| 4 | Missing indices | **F-053** | Inverts the expected answer: no index is missing; two of the four that exist (`idx_start_time`, `idx_was_blocked`) serve no query the app runs, the composite is ordered against the dominant query, and the KDoc's "50-100x faster" claim is unevidenced. Filed Low with an explicit recommendation *not* to remove them. |
| 5 | `StateBackupManager` shared-storage failure handling | **F-043, F-045, F-046, F-047** | Four angles: no schema/type gate on restore (F-043, Critical, pinned); `savedAt` written but never read, so an older snapshot replays (F-045); `androidId()` failure silently degrading the key to a constant (F-046); and failure being unobservable â€” the skip path logs through a no-op level and both call sites discard the Boolean result (F-047). |

**All five areas are answered. No new record was filed, and F-056â€“F-059 stay unused.**

### Consolidated `needs-verification` list

No device or emulator was available for T020 or for this gap check. Gradle tasks in this environment
require an explicit JDK 21 (`./gradlew <task> "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`).
Fifteen of the sixteen records are `confirmed` at evidence tier T1; **F-041 is the unit's only record whose
overall verification status is `needs-verification`**. Eight records carry an open device-dependent
question, listed here with the procedure and the severity-per-outcome each record states.

| Finding | Open question | Procedure | Severity per outcome |
|---|---|---|---|
| **F-041** (status `needs-verification`, T2) | That Room actually throws on the downgrade open, and that the three call-site catches swallow it rather than crashing. Needs a device **and a version-1 APK**. | F-041 trigger steps 1â€“5 (`adb install -r -d`, then check `select count(*) from usage_events` is unchanged). | Silent permanent loss of recording â†’ Medium; exception escapes to a crash on any path â†’ High (already confirmed statically at `HomeScreen.kt:56â€“58`, which is why it is filed High); maintainer confirms sideloaded downgrade is a real support case â†’ High. |
| **F-040** | Whether two live connection pools actually produce a write conflict or are absorbed by Android's ~2.5 s busy timeout; and whether concurrent open + `MIGRATION_1_2` over two helpers is safe. | Log `System.identityHashCode(instance)` in the builder lambda on any API 29+ device; F-040 *Procedure*. | Duplicate instances **and** write conflicts â†’ High; duplicates but writes always absorbed â†’ Medium. Not below Medium in any outcome. |
| **F-043** | Whether the `MANAGE_EXTERNAL_STORAGE` appop survives clear-data on the target OEM. | F-043 trigger steps 3â€“5; the `appops get` check in step 3 is decisive. | Appop survives clear-data â†’ Critical (as filed, as C7 records). Appop revoked by a particular OEM â†’ that path is High, but the finding stays **Critical** regardless, because the same missing gates carry the silent value-poisoning path. |
| **F-044** | Actual `onCreate` duration with a realistic payload on FUSE-backed shared storage; whether a `ForegroundServiceDidNotStartInTimeException` or ANR occurs. Pure latency claim â€” cannot be settled statically. | F-044 trigger steps 1â€“5; the decisive measurement is the `onCreate` delta in step 4. | Past the `startForegroundService` window, or an ANR â†’ High (as filed); low tens of ms even under load â†’ Medium. Never Low. |
| **F-045** | Whether `/sdcard/.grayzone/backup.dat` is readable/writable via `adb pull`/`adb push` **and** via an ordinary file manager without root at the target API level, and whether a replayed snapshot is accepted. | F-045 trigger steps 1â€“7; step 2's `adb pull` succeeding without root is decisive. | Replay accepted â†’ High as filed (deliberate restraint, per the C4 precedent). If a maintainer judges `adb`-free replay via an ordinary file manager to be in the threat model â†’ **raise to Critical**. |
| **F-046** | Whether `Settings.Secure.getString(â€¦, ANDROID_ID)` ever returns null/empty for this app on API 29â€“34 â€” ideally tested with a secondary user and a work profile. The only open question in the record. | F-046 trigger steps 1â€“3 for the real condition; step 5 is a one-line source change that forces it. | Empty `ANDROID_ID` occurs on any supported configuration â†’ High (as filed); proven unreachable on all supported levels â†’ Low, but still worth fixing. |
| **F-048** | The frozen-definition behaviour end-to-end, and whether an unconstrained weekly job in fact executes across multi-day Doze on a real OEM build. | F-048 trigger steps 1â€“4; for the Doze half, `adb shell dumpsys deviceidle force-idle`, leave idle overnight, then check execution. | Frozen definition confirmed â†’ Medium (as filed); device shows the job never executing on a mainstream OEM build â†’ High â€” but note the cause would then be OEM job killing, whose fix is the unwired `OemBatteryHelper`, not this scheduling code. |
| **F-049** | Whether the Settings date change is blocked on the target build, and whether `DateUtils.getCurrentDateKey()`'s one-minute cache (`DateUtils.kt:46â€“61`) delays the reset by up to 60 s â€” it would not prevent it. | F-049 trigger steps, in particular the ration-reset path at step 6. | Critical as filed on the ration-reset path. If the daily break is established as intentionally trust-based and out of the threat model, the deadline-drift half still stands at High. **Never below High.** |

**F-055** states a conditional escalation (Medium as filed â†’ **Critical** the moment any field is added to
`ScheduleRule`) but has **no verification dependency** â€” its *Missing* field reads "nothing for the
mechanism"; a device only demonstrates the user-visible effect. It should be tracked as a **blocker on any
change to `ScheduleRule`'s shape**, not as a device-verification item.

The remaining seven records â€” F-042, F-047, F-050, F-051, F-052, F-053, F-054 â€” are `confirmed` with no
*Missing* sub-field and carry **no open verification item at all**.

### Room DAO main-thread sweep (negative result)

Recorded here rather than as a finding, because the sweep found no defect. Verified directly against
`app/src/main` during this gap check rather than taken from the reference index:

- `UsageDatabase.getInstance(...).usageDao()` has **exactly four main-source call sites**:
  `StreakEvaluator.kt:27`, `CleanupWorker.kt:42`, `OverlayService.kt:570`, `OverlayService.kt:1117` â€”
  matching F-040's enumeration exactly.
- **Every one of `UsageDao`'s thirteen functions is declared `suspend`**, and the builder does **not**
  call `allowMainThreadQueries()` (`UsageDatabase.kt:36â€“43`). Room therefore dispatches every DAO body to
  its own query executor regardless of the caller's dispatcher, and a blocking main-thread access would
  throw rather than silently stall.
- Three of the four call sites are already on `Dispatchers.IO` or WorkManager's task executor. The fourth
  reaches `StreakEvaluator.run` from `HomeScreen.kt:56â€“58`, which is a **`rememberCoroutineScope()`, i.e.
  the main dispatcher** â€” but the DAO call inside it (`StreakEvaluator.kt:32`) is `suspend`, so the SQLite
  work still runs off the main thread, and `Room.databaseBuilder(...).build()` at `:27` performs no disk
  I/O (the open is lazy).
- The prefs reads that `StreakEvaluator.run` performs on that main-dispatcher path (`:18â€“25`) were checked
  for a blocking write and there is none: `StreakManager` uses `apply()` at every write site
  (`:49`, `:114`, `:153`), never `commit()`.

**Conclusion: no Room DAO call runs on the main thread.** The only surviving main-thread I/O defect in this
unit is F-044's, which is SharedPreferences and shared storage, not Room. The separate defect on the
`HomeScreen` path is the *missing exception guard*, not threading â€” and it is the unowned item noted above.

### F-054 spot-check

The reference index has four known errors, so F-054's "no main-source caller" claims were re-checked by
direct `Grep` over `app/src/main`. Two of the eight were required; four were checked:

- `getStartTimeTotals` â€” appears in main source **only** at its declaration, `UsageDao.kt:60`. No caller.
- `getWeeklyTotals` â€” appears in main source **only** at its declaration, `UsageDao.kt:28`. No caller.
- `getAppUsageSince` â€” appears in main source **only** at its declaration, `UsageDao.kt:68`. No caller.
- `getTotalSessionsBlocked` â€” the DAO method (`UsageDao.kt:32`) has no caller. The only other main-source
  hits are `StreakManager.kt:36`, `:44`, `:159`, which are the **prefs-backed function of the same name**
  â€” exactly the two-parallel-statistics-systems collision F-054 itself describes, not a caller of the DAO.

**The claim holds** on every method checked. One arithmetic correction, left uncorrected in the record
itself: the heading says "**Eight** of `UsageDao`'s eleven query methods", but the record's own table lists
**nine** methods with no main-source caller, and `UsageDao` declares eleven read queries of which only two
(`getDailySummary`, `getEventCount`) have a production caller. The accurate figure is **nine of eleven**.
The heading understates rather than overstates, and the severity (Low) is unaffected.

### Other closing notes

- **`DefaultMonitoredApps.kt` (26 lines) â€” no defect found.** This is the "see closing notes" referenced
  from the `Scope examined` table; the note itself was never written before the crash. No further detail
  survives, and none was reconstructed here.
- **Not re-filed, owned elsewhere** and cross-referenced from the records above: **F-215**
  (`CustomPromptsStore` â€” withdrawn into F-148), **F-216** (`PackageManager` failure read as "not
  installed"), **F-060** (`validateConfiguration` unguarded typed reads, whose upstream cause is F-043),
  **F-206** (`appScope` exception handler), **F-063** (`flushBackupIfDirty` clearing the dirty flag
  early), **F-064** (`BuildConfig.DEBUG` false in every variant, which F-047 depends on).
- **F-043 was not renumbered**, so the register's C7 cross-reference holds.
- **C3 / ScheduleManager malformed-JSON** was listed as Critical in `findings.md` but had **no F-###
  record** â€” the cross-refs pointing at "F-205" were wrong (F-205 is `startForeground`). Filed below as
  **F-241** during T031.
- **Observed against**: `working-tree @ 557a963`, unchanged from the individual records.

---

### F-241 â€” Malformed `SCHEDULE_RULES_JSON` becomes an empty list with no log, and the next `addRule`/`removeRule`/`toggleRule` persists that empty list over the user's real rules  [Critical]

- **Review unit**: RU-02
- **Location**: `app/src/main/java/com/grayzone/app/data/ScheduleManager.kt` â†’ `getScheduleRules()`
  (lines 33â€“39), consumed by `addRule` / `removeRule` / `toggleRule` (lines 45â€“60)
- **Issue**: `catch (_: Exception) { emptyList() }` discards the exception by construction. Malformed
  `SCHEDULE_RULES_JSON` (upgrade drift, backup restore, hand-edit) silently becomes an empty rule list,
  so bedtime and work-hours locks stop firing with no signal. **The data-loss half is worse**: `addRule`
  does `getScheduleRules().toMutableList()`, appends, then `saveScheduleRules` â€” so the next UI edit
  **persists the empty list over the user's real rules**. Silent protection loss followed by irreversible
  data loss. This is Critical list entry **C3**, which existed in `findings.md` without an F-### until
  T031. Distinct from F-055 (Gson succeeds but drops Kotlin defaults) and from F-205 (`startForeground`).
- **Trigger condition**:
  1. Create two schedule rules in the UI.
  2. Corrupt `SCHEDULE_RULES_JSON` in `GrayzonePrefs.xml` to invalid JSON.
  3. Relaunch; open schedule UI â€” rules appear empty; no error.
  4. Add one new rule; re-read prefs â€” original rules are gone.
- **Blast radius**:
  - *Callers*: `isCurrentlyScheduled`, `addRule`, `removeRule`, `toggleRule`, schedule UI sheets,
    `OverlayService.buildSessionState` via schedule checks.
  - *Process death*: n/a â€” prefs persist the wipe.
  - *Config change*: n/a.
  - *Foreground/background*: edit can happen anytime UI is open.
  - *Persistence*: yes â€” overwrite is the defect.
  - *Release-only*: compounded by F-125 if TypeToken strip changes parse failure modes.
- **Fix** (minimal): on parse failure, log + return the previous good list or refuse to save until
  repair; never call `saveScheduleRules` with a list derived from a failed parse. Mirror
  `OverlayService`'s custom-prompts fallback (log, don't write).
- **Test to add**: `jvm-unit` â€” `ScheduleManagerTest` â†’ `malformedJson_doesNotPersistEmptyOnAdd`.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (T1) â€” code read at `:33â€“49`.
- **Build scope**: everyday-build
- **Constitution**: III
- **Affected surfaces**: F-055 (related, distinct); F-148 (same pattern on custom prompts); F-043
- **Observed against**: `working-tree @ 557a963`
- **Disposition**: `verified`


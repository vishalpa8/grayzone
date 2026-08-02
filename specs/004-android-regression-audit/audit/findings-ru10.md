# Findings — RU-10 (existing test suites)

**Task**: T029 · **Review unit**: RU-10 · **Finding ID block**: F-190 … F-204

**Subject**: `app/src/test/java/com/grayzone/app/` (40 test classes + 2 fixture files) and
`app/src/androidTest/java/com/grayzone/app/` (3 instrumented classes). Production files are read only
where a test's claim has to be checked against the code it claims to cover; defects found in
production are referred to the owning unit, not re-filed here.

**Revision**: `HEAD = 557a963`. `git diff --name-only` returns 10 paths and **not one of them is a
test file**. That is load-bearing for this unit and is stated up front: the uncommitted `:a11y`
process split modified `GrayzoneAccessibilityService.kt`, `OverlayService.kt`, `AdBlockVpnService.kt`,
`BlocklistManager.kt`, `DnsTrafficBus.kt`, `AndroidManifest.xml` and
`accessibility_service_config.xml` with **zero accompanying test change**, and it silently orphaned
`ProtectionHealthRepository.updateAccessibilityStatus` (F-197) without turning a single test red.

**Test execution — the JVM suite passes.** Run in this session:

```
./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11" --rerun-tasks
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 1m 34s
27 actionable tasks: 27 executed
```

Parsed from `app/build/test-results/testDebugUnitTest/*.xml`: **40 test classes, 306 test methods,
0 failures, 0 errors, 0 skipped.** Every finding below therefore describes a *green* suite. Nothing
here is a broken test; the defects are tests that pass while proving less than they claim, or that
pin behaviour the audit has already ruled defective.

**Environment**: no device and no emulator. `app/src/androidTest/` (`MigrationTest`,
`UsageDaoStatsTest`, `PeakHourAggregationTest`) was read but **could not be executed**; every claim
about instrumented runtime behaviour below is labelled `needs-verification` with a device procedure
and a severity-per-outcome.

**Evidence base**: [`findings.md`](./findings.md) ·
[`contracts/finding-record.md`](../contracts/finding-record.md) ·
[`evidence/prior-fix-watchlist.md`](./evidence/prior-fix-watchlist.md) ·
[`evidence/reference-index.md`](./evidence/reference-index.md) (grepped for specific symbols only,
per the accuracy warning) · [`findings-ru01.md`](./findings-ru01.md) ·
[`findings-ru06.md`](./findings-ru06.md) · [`findings-ru03.md`](./findings-ru03.md).

**Every TEST-ONLY claim in this file was re-verified by direct `Grep` over `app/src/main`**, because
the reference index has already been caught mislabelling `DnsTrafficBus.clear()` and `onRevoke()`.
Results of that re-verification:

| Symbol | Matches in `app/src/main` | Verdict |
|---|---|---|
| `ProtectionHealthRepository.updateAccessibilityStatus` | 1 — its own declaration, `ProtectionHealthRepository.kt:34` | **test-only ✓** |
| `SetupRequirements.actionLabel` | 1 — its own declaration, `SetupRequirements.kt:45` | **test-only ✓** |
| `DateUtils.isSameDay` | 1 — its own declaration, `DateUtils.kt:113` | **test-only ✓** |
| `AccessibilityServiceIds.isServiceEnabled` | 2 — declaration `:18` **and a live caller**, `AppUtils.kt:114` | **live — not a defect** |
| `OverlayMode.{FRICTION,LOCK,BUDGET_LOCK,SCHEDULE_LOCK}` | declared `PrefsKeys.kt:90–95`, consumed by `OverlayService.kt` / `OverlayPresentation.kt` | **live — not a defect** |

**Referenced, not re-filed** (owned elsewhere; listed so T034 does not double-count):

| Defect | Owner | Where it touches RU-10 |
|---|---|---|
| Three empty socket-close catches, `DnsResolverPool.kt:107,112,123` | **F-031** (T019/RU-06) | its fix is constrained by **F-198** |
| `testOptions { unitTests.isReturnDefaultValues = true }` absent from `app/build.gradle.kts` | **T025** (RU-08) | is the enabling prerequisite for **F-198** |
| `BuildConfig.DEBUG == false` in every variant | **F-064** (T021/RU-01) | the test that should have caught it is **F-196** |
| `SetupRequirements.actionLabel()` is test-only | **F-073** (T021/RU-01) | the *test-side* consequence is **F-199** |
| `DateUtils.isSameDay()` is test-only | **F-072** (T021/RU-01) | same, **F-199** |
| `ProtectionStatus.accessibilityActive` has no production writer | T020 / `evidence/cross-process-inventory.md` | the *test-side* consequence is **F-197** |
| Wall-clock `isLockedOut` / `hasActiveSession` | **F-110 / F-111** (T024/RU-03, C5/C6) | the test pinning them is **F-190** |
| `UsageDatabase.getInstance` broken double-checked locking (`:35`) | T020 (RU-02) | no test covers it; counted in **F-201** |

---

## Findings

### F-190 — `SessionStatePredicateTest` pins the wall-clock semantics of both Critical clock-tamper defects as "exhaustive", so the C5/C6 fix cannot land without deleting 8 green tests  [High]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/policy/SessionStatePredicateTest.kt` →
  `SessionStatePredicateTest` (lines 7–58; the two affected groups are `hasActiveSession` at 18–35
  and `isLockedOut` at 40–58)
- **Issue**: This is the highest-value class of defect this task was asked to look for — a test that
  locks in a bug. The file's own KDoc (`:7–11`) declares *"Exhaustive boundary checks for the three
  raw predicates that every higher-level decision relies on. Off-by-one here would cascade into
  premature locks or leaks."* All eight predicate tests call `state(...).isLockedOut(now)` and
  `state(...).hasActiveSession(now)` with a **single wall-clock `Long`**, and assert that the answer
  is exactly `now < lockedUntil` / `now < activeUntil`. Those two expressions are C5 (F-110) and C6
  (F-111) — the audit's two Critical findings, where advancing the device clock ends an active
  lockout instantly and rewinding it revives every expired session at once. The fix for both, already
  written elsewhere in this repository, is `VpnHoldLock`'s dual-clock pattern: prefer
  `SystemClock.elapsedRealtime()` and fall back to wall-clock only across reboot. Applying it changes
  the predicate signatures from `(now: Long)` to a wall+elapsed pair, which **fails to compile all
  eight tests in this file**. A maintainer arriving at a red suite, in a file that calls itself
  exhaustive and warns that changes here "cascade into premature locks or leaks", has every incentive
  to conclude the fix is wrong rather than the test. The user-visible consequence is therefore
  indirect but real: the single most severe *escapable* protection failure in the app has an
  8-test tripwire in front of its fix, and a maintainer who trusts the suite is told the current
  behaviour is verified.
- **Trigger condition**:
  1. Apply the C5/C6 fix — give `SessionModels.isLockedOut` / `hasActiveSession` an elapsed-realtime
     parameter, mirroring `VpnHoldLock.remainingMs(state, nowWallMs, nowElapsedMs)`
     (`app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt:19` shows the shape).
  2. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  3. Observe 8 compile errors in `SessionStatePredicateTest.kt` (lines 20, 25, 30, 35, 42, 47, 52,
     57), plus the downstream engine suites listed under *Affected surfaces*.
  4. Observe that no test anywhere in `app/src/test` fails if you instead **leave** the wall-clock
     comparison in place — i.e. the suite is green both before and after the defect exists.
- **Blast radius**:
  - *Callers*: none — it is a leaf test class. The production symbols it pins are called from
    `policy/SessionPolicyEngine.kt` (foreground and background paths), which is in turn exercised by
    `SessionPolicyEngineTest` (17), `SessionPolicyEngineForegroundTest` (20),
    `SessionPolicyEngineBackgroundTest` (10) and `SessionExpiryDecisionTest` (11) — **58 further
    tests that all pass a single wall-clock `Long`** and would need the same signature change.
  - *Process death*: none — JVM unit test, no Android runtime.
  - *Config change*: none.
  - *Foreground/background*: none directly; `SessionPolicyEngineForegroundTest` and
    `...BackgroundTest` are named for the *policy* foreground/background split, not the app's, and
    both are affected identically.
  - *Cross-process*: none — `policy/` is default-process only; `:a11y` never loads it.
  - *Persistence*: none written by the test, but `lockedUntil` / `activeUntil` are persisted epoch
    millis (`PrefsKeys.LOCKED_UNTIL`, `PrefsKeys.ACTIVE_UNTIL`), so the fix must also decide what to
    do with values already on disk that were written under wall-clock semantics — the test file
    currently encodes no expectation about that at all.
  - *Release-only*: none — unit tests do not run through R8.
- **Fix** (minimal): do **not** delete these tests. Reframe the file so it can survive the C5/C6 fix
  and stop advertising a guarantee it does not check:
  1. Rename the two groups' assertions to be about *monotonic* elapsed time, and add the
     signature parameter now with a temporary pass-through, so the fix is a one-line production
     change rather than an 8-test compile break.
  2. Add the missing tests that the current file's "exhaustive" claim implies but does not contain:
     a lockout that must remain active when the wall clock jumps forward past `lockedUntil`, and an
     expired session that must remain expired when the wall clock jumps backward. Both fail today.
  3. Correct the KDoc: "exhaustive boundary checks" is true only for the off-by-one axis; it is not
     exhaustive over clock behaviour, which is the axis that actually breaks.
- **Rewrite justification**: not a rewrite — the eight existing assertions stay, and the two new
  clock-tamper assertions are additive. Deleting the file would lose the genuinely valuable
  inclusive/exclusive boundary coverage (`isLockedOut` false *exactly at* release, `:46–48`), which
  is correct and worth keeping.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/policy/SessionStatePredicateTest.kt`,
  adding `lockout survives a forward wall-clock jump` asserting `isLockedOut` stays `true` when the
  wall clock is advanced past `lockedUntil` but elapsed-realtime has not advanced, and
  `expired session is not revived by a backward wall-clock jump` asserting `hasActiveSession` stays
  `false` under the inverse. Both **fail before** the C5/C6 fix (the predicate has no elapsed input
  to consult) and **pass after**.
- **Existing test conflict**: **yes — this finding is the conflict.** `SessionStatePredicateTest`
  (8 of its 12 tests) blocks **F-110 (C5)** and **F-111 (C6)**, both Critical.
  Required change: add the elapsed-realtime parameter to all 8 call sites. Secondary conflict of the
  same shape, same fix: `SessionPolicyEngineTest`, `SessionPolicyEngineForegroundTest`,
  `SessionPolicyEngineBackgroundTest`, `SessionExpiryDecisionTest` and their shared fixture
  `policy/PolicyTestFixtures.kt` — 58 tests that thread a single wall-clock `now` into the engine.
- **Verification**: `confirmed` (evidence tier T1) — the test source, the production predicates at
  `policy/SessionModels.kt:30–31`, and the passing run recorded in this file's header were all read
  directly; no device needed.
- **Build scope**: everyday-build
- **Constitution**: II (the dual-clock defense already exists in `VpnHoldLock` and is not reused
  here), V (the suite asserts a guarantee — "exhaustive" — it does not prove)
- **Affected surfaces**: the four engine suites each declare a local `private val now = 1000L`
  (e.g. `SessionPolicyEngineTest.kt:10`) and pass it as the `now: Long` argument of
  `SessionPolicyEngine.evaluate(event, state, now, appName)` (`policy/SessionPolicyEngine.kt:17`) —
  so the elapsed-realtime parameter has to be threaded through `evaluate`'s signature and then
  through every one of those 58 call sites; `PolicyTestFixtures.state(...)` itself needs no change,
  because it builds only the persisted `SessionState` and carries no clock.
  `SessionPolicyEngineTest.kt`; `SessionPolicyEngineForegroundTest.kt`;
  `SessionPolicyEngineBackgroundTest.kt`; `SessionExpiryDecisionTest.kt`;
  `app/src/test/java/com/grayzone/app/LockGuardLogicTest.kt:39–76`, which re-derives the same
  wall-clock lock predicate over `FakeSharedPreferences` and pins it a second time from the
  prefs side (`active boundary at exactly now is not locked`, `lockout is inclusive of the
  lockedUntil instant`, `one ms past lockout is unlocked`).
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-191 — A green test asserts that a 30-day forward clock jump must grant a full fresh session and re-anchor the lockout, codifying the C5 tamper outcome as the requirement  [High]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt` →
  `` `resuming a paused session after the clock jumps forward grants full remaining` `` (lines 190–205)
- **Issue**: The test explicitly simulates wall-clock tampering — `val future = now + 30L * 24 * 60 *
  60 * 1000L // +30 days` (`:195`) — and then asserts that the engine's response is **correct**:
  `assertEquals(future + 5000L, update.newActiveUntil)` and
  `assertEquals(future + 5000L + state.defaultLockoutMins * 60 * 1000L, update.newLockedUntil)`
  (`:201–202`). In other words, a user who pauses a session, opens Settings → Date & time and moves
  the clock forward 30 days is asserted to receive their paused session back **and** a lockout
  re-anchored to the tampered clock. That is precisely the manoeuvre C5 (F-110) is filed as Critical
  for. The comment at `:192–194` frames the hazard as arithmetic hygiene — *"must anchor to the new
  `now`, not grant a stale/negative window"* — so the file records the tamper-friendly outcome as the
  *intended* behaviour, in a test whose title reads like a hardening test. The user-visible
  consequence is that the C5/C6 fix (refuse to honour a wall-clock jump; prefer
  `SystemClock.elapsedRealtime()` as `VpnHoldLock` already does) turns this test red, and the failure
  message plus the comment both tell the maintainer the fix regressed a deliberate behaviour. This is
  the single most direct instance in the suite of a test that will block a Critical fix.
- **Trigger condition**:
  1. Read `SessionPolicyEngineTest.kt:190–205`; note the assertion is on the *tampered* `future`
     clock, not on elapsed time.
  2. Apply the C5/C6 fix so that a forward wall-clock jump with no corresponding elapsed-realtime
     advance is rejected.
  3. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  4. Observe `resuming a paused session after the clock jumps forward grants full remaining` fails
     with an expected/actual mismatch on `newLockedUntil`, and that the test's own comment argues the
     old value was correct.
  5. On device (no root, no adb): pause a monitored app mid-session, Settings → Date & time →
     disable automatic → advance 30 days, reopen the app. Observe the session resumes and the
     lockout is re-based — the behaviour this test requires.
- **Blast radius**:
  - *Callers*: none — leaf test. It pins `SessionPolicyEngine.evaluate()`
    (`policy/SessionPolicyEngine.kt:17`), whose paused-session resume branch computes
    `newActiveUntil = now + sessionRemainingMs` and
    `newLockedUntil = newActiveUntil + defaultLockoutMins * 60_000`.
  - *Process death*: none — JVM unit test.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none — `policy/` is default-process only.
  - *Persistence*: the values it pins (`newActiveUntil`, `newLockedUntil`) are what
    `SessionCommand.UpdateState` writes to `PrefsKeys.ACTIVE_UNTIL` / `PrefsKeys.LOCKED_UNTIL`, so
    the tampered epoch this test blesses is the value that lands on disk and survives reboot.
  - *Release-only*: none.
- **Fix** (minimal): keep the test but **invert its expectation and rename it**. The legitimate
  property it was reaching for — a resume must not produce a stale or negative window — is worth
  keeping and is already covered by `` `when session paused, resume it without wait screen` ``
  (`:141–153`) on an untampered clock. Rename this one to
  `` `a forward wall-clock jump does not extend or re-anchor a lockout` `` and assert the post-fix
  behaviour: with elapsed-realtime unchanged, `newLockedUntil` must not move. Until the C5/C6 fix
  lands, mark it `@Ignore("pins C5/F-110 tamper behaviour — see findings-ru10.md F-191")` rather
  than leaving it green, so it cannot be cited as evidence that clock jumps are handled.
- **Rewrite justification**: not a rewrite; one assertion inverted and one title corrected. Deleting
  the test would remove the only place in the suite that even mentions a forward clock jump, which is
  the coverage the C5/C6 fix needs.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt`,
  `` `a forward wall-clock jump does not shorten an active lockout` ``: build a state with
  `lockedUntil = now + 60 * 60 * 1000L`, evaluate at `now + 30 days` with elapsed-realtime unchanged,
  and assert the command is still `ShowLockoutScreen`. **Fails before** the C5/C6 fix (the engine
  returns `ShowWaitScreen` because `isLockedOut` is a bare `now < lockedUntil`), **passes after**.
- **Existing test conflict**: **yes.** `` `resuming a paused session after the clock jumps forward
  grants full remaining` `` blocks **F-110 (C5)** and, by the same signature change, **F-111 (C6)** —
  both Critical. Required change: invert the assertion as above, or `@Ignore` it in the same commit
  as the fix. Distinct from F-190: F-190 is a compile-level signature break across 66 tests; this one
  is a semantic assertion that the tampered outcome is correct, and it would survive a
  purely-mechanical signature migration.
- **Verification**: `confirmed` (evidence tier T1) — test source read directly; the suite is green as
  recorded in this file's header, so the assertion is live and passing today.
- **Build scope**: everyday-build
- **Constitution**: II (`VpnHoldLock`'s dual-clock defense exists and this test forbids applying it
  to the session engine), V (the test's title claims hardening while the body requires the
  vulnerability)
- **Affected surfaces**: `SessionPolicyEngineTest.kt:155–172` (the companion misnamed test, filed
  separately as **F-192**); `policy/SessionPolicyEngine.kt:17` paused-session resume branch;
  `policy/SessionModels.kt:30–31`.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-192 — `when session paused but clock skew detected, clear it and show wait screen` tests no clock skew at all; its body is nine lines of the author reasoning themselves out of the test, then a duplicate assertion  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt` →
  `` `when session paused but clock skew detected, clear it and show wait screen` `` (lines 155–172)
- **Issue**: The test name promises the app's clock-skew defense. The body contains no skewed clock,
  no paused session, and no `sessionRemainingMs` — it evaluates `defaultState` (a fresh app launch)
  at the ordinary `now` and asserts `ShowWaitScreen`, which is byte-for-byte the same scenario as the
  suite's basic friction test. The nine comment lines at `:157–167` are the author's unedited
  reasoning, ending in *"Let's test the default behavior: fresh app launch."* — the test was
  abandoned mid-write and the misleading title was left in place. The consequence is false
  confidence with a documented paper trail: anyone auditing "does anything cover clock skew?" — which
  is exactly what T024 and T029 were asked to do — greps the test names, finds this one green, and
  concludes the axis is covered. Worse, `:160–161` shows the author looked directly at C5's attack
  (*"what if the user manually changes the clock to the future? Then `now` is huge, `newActiveUntil =
  now + 5000L` is still > `now`"*), reasoned only about the session-resume arithmetic, never checked
  `isLockedOut`, and concluded at `:165` that *"the clock skew block in engine isn't strictly
  needed"*. So the vulnerability was considered and dismissed in a comment that ships in the
  repository, and the test that would have caught it was replaced by a duplicate.
- **Trigger condition**:
  1. `rg -n "clock skew|clock jump" app/src/test` — two hits, both in this file.
  2. Read `SessionPolicyEngineTest.kt:155–172`. Observe the state under test is `defaultState`, with
     `sessionRemainingMs = 0L` and `lockedUntil = 0L`, and `now` is the unmodified `1000L`.
  3. Compare with `` `when app is not monitored…` `` / the wait-screen path: the assertion
     (`assertEquals(1, commands.size)`; `commands[0] is ShowWaitScreen`) is a duplicate.
  4. Delete the entire body and replace it with the fresh-launch test — the suite stays green,
     proving the test carries no unique coverage.
- **Blast radius**:
  - *Callers*: none — leaf test.
  - *Process death*: none — JVM unit test.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none — asserts a command list, writes nothing.
  - *Release-only*: none.
- **Fix** (minimal): replace the body with the test the title describes — a paused session
  (`sessionRemainingMs = 5000L`) evaluated at a `now` that has moved backward relative to the
  persisted `activeUntil`, asserting the stale remainder is cleared and `ShowWaitScreen` is emitted —
  and delete the nine comment lines. If the maintainer decides after F-190/F-191 land that the
  engine's skew branch is genuinely unreachable, delete the test outright rather than leaving a green
  placeholder under a coverage-implying name.
- **Rewrite justification**: n/a — the body is being replaced because it contains no assertion
  related to its name; there is nothing to patch.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineTest.kt`,
  `` `a backward wall-clock jump does not revive an expired session` ``: state with
  `activeUntil = now + 5_000L`, evaluated at `now - 30 days`, asserting the engine does **not** emit
  `DismissOverlay`. **Fails before** the C6/F-111 fix (`hasActiveSession` is `now < activeUntil`, so
  the rewound clock reports the session live and the engine short-circuits), **passes after**.
- **Existing test conflict**: none — the test asserts nothing that any fix would contradict. That is
  the defect.
- **Verification**: `confirmed` (evidence tier T1) — test source read directly.
- **Build scope**: everyday-build
- **Constitution**: V (a green test whose name asserts coverage that does not exist)
- **Affected surfaces**: none — the misnamed-test pattern was searched for across the suite and this
  is the only instance where the title names a mechanism the body never constructs. The adjacent
  `IntegrationTest` naming problem is a different root cause and is filed as **F-195**.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-193 — `MigrationTest`'s four index assertions use Kotlin `assert()`, which is compiled out on Android, so they can never fail  [Low]

- **Review unit**: RU-10
- **Location**: `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` →
  `MigrationTest.migrate1To2()` (lines 46–49)
- **Issue**: The only assertions in the app's only migration test are
  `assert(indices.contains("idx_date_key"))` and three siblings. Kotlin's `assert(Boolean)` is an
  inline function guarded by `kotlin._Assertions.ENABLED`, which is initialised from
  `Class.desiredAssertionStatus()`. ART does not implement JVM `-ea` and always reports assertions
  disabled, so on any device or emulator all four lines compile down to a branch that is never taken.
  The `while (cursor.moveToNext())` loop at `:41–43` still runs and the cursor is still closed, so the
  test does real work and produces a real `indices` list — and then discards it. Consequence: the
  four lines a reader takes as the test's point are decorative, and the file is the template every
  future migration test in this project will be copied from. The reason this is **Low and not
  higher** is that the property those lines check is independently enforced:
  `helper.runMigrationsAndValidate(TEST_DB, 2, true, UsageDatabase.MIGRATION_1_2)` at `:36` compares
  the post-migration schema against `app/schemas/com.grayzone.app.data.UsageDatabase/2.json`, which
  declares all four indices (`2.json:68,77,87,96` with their `createSql`), because `UsageEvent`
  declares them via `@Entity(indices = [...])` (`data/UsageEvent.kt:19–27`). So a migration that
  failed to create an index would still fail the test — via Room, not via these four lines. The
  finding is that the assertions are dead, not that the migration is unverified.
- **Trigger condition**:
  1. Read `MigrationTest.kt:46–49` — plain `assert(...)`, not `org.junit.Assert.assertTrue`. Note the
     file imports no JUnit assertion class at all (`:1–10`).
  2. Change one to `assert(indices.contains("idx_does_not_exist"))`.
  3. Run `./gradlew :app:connectedDebugAndroidTest` on a device or emulator.
  4. Observe the test still passes.
- **Blast radius**:
  - *Callers*: none — leaf instrumented test. No production symbol depends on it.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none — `MigrationTestHelper` runs in the instrumentation process against a
    temporary database file (`"migration-test"`), not the app's `grayzone_usage.db`.
  - *Persistence*: it creates and migrates a real on-disk SQLite file, but only the temporary test
    database; no app data is touched.
  - *Release-only*: none — instrumented tests build the debug variant and do not run through R8.
- **Fix** (minimal): replace all four `assert(...)` with `org.junit.Assert.assertTrue("idx_date_key
  missing after migration", indices.contains("idx_date_key"))` and add the import. Same for the
  other three. Then add a repository-wide guard so the mistake cannot recur:
  `rg -n "^\s*assert\(" app/src/test app/src/androidTest` must return nothing — today it returns
  exactly these four lines and nothing else, so the rest of the suite is clean and the fix is
  four lines.
- **Rewrite justification**: n/a.
- **Test to add**: `instrumented` — this finding *is* a test fix, so the "test to add" is the
  corrected form of the existing one: `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt`
  → `migrate1To2`, using `assertTrue` so that removing any one `CREATE INDEX` from
  `UsageDatabase.MIGRATION_1_2` (`data/UsageDatabase.kt:27–30`) fails the test on the assertion line
  with a readable message rather than on Room's schema-diff exception. **Cannot be executed here** —
  no device or emulator is available.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: execution of `MigrationTest` on a device or emulator to observe that a deliberately
    false `assert(...)` does not fail. The static half is confirmed: the source uses bare `assert`,
    and Kotlin compiles it behind `_Assertions.ENABLED`.
  - *Procedure*: on any API 29+ device or emulator, edit `MigrationTest.kt:46` to
    `assert(indices.contains("nonexistent"))`, run
    `./gradlew :app:connectedDebugAndroidTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`,
    and record whether `migrate1To2` passes. Revert afterwards.
  - *Severity effect*: assertions confirmed disabled → stays `Low` (the index property is covered by
    Room's schema validation, as established above). If some future toolchain change enables them →
    the finding is void and should be withdrawn. It cannot become higher than Low on this evidence.
- **Build scope**: everyday-build
- **Constitution**: V (an assertion that cannot fail is not evidence)
- **Affected surfaces**: none — `rg "^\s*assert\("` over both test source sets matches only
  `MigrationTest.kt:46,47,48,49`. Every other test class uses `org.junit.Assert.*`.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-194 — `MigrationTest` inserts a row and never reads it back, so no test in the repository asserts that a database upgrade preserves user data  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt` →
  `MigrationTest.migrate1To2()` (line 31 inserts; lines 39–49 assert only over `sqlite_master`)
- **Issue**: **Correcting the premise this task was given**: the migration test is *not* run over an
  empty database — `:31` inserts one fully-populated `usage_events` row before migrating. What it
  never does is look at that row again. After `runMigrationsAndValidate`, the only query is
  `SELECT name FROM sqlite_master WHERE type='index'` (`:39`); there is no
  `SELECT * FROM usage_events`, no row count, and no column-value check. So the test proves the
  *schema* survives and says nothing about whether the *data* did. The row is therefore load-bearing
  only in the sense that it makes the migration run against a non-empty table — a real and worthwhile
  property, since a `CREATE INDEX` on an empty table exercises none of SQLite's index-population
  path — but the assertion that would convert it into evidence is absent. Consequence: `usage_events`
  is the store behind streaks, daily budgets and every statistic the app shows, and losing it is the
  "loses user data" row of the severity rubric. Today the exposure is bounded, because
  `MIGRATION_1_2` is four `CREATE INDEX IF NOT EXISTS` statements (`data/UsageDatabase.kt:27–30`)
  which cannot drop rows. The exposure arrives with migration 2→3: the next migration will be written
  by copying this file, and the copy will inherit a shape that cannot detect data loss. A
  recreate-table-and-copy migration — the standard SQLite pattern for dropping or retyping a
  column — is exactly the kind that loses rows, and exactly the kind this template will wave through.
- **Trigger condition**:
  1. Read `MigrationTest.kt:26–50`. Confirm the inserted row at `:31` is never queried after `:36`.
  2. Add `db.execSQL("DELETE FROM usage_events")` as the first statement of
     `UsageDatabase.MIGRATION_1_2.migrate()` — a stand-in for a botched future migration.
  3. Run `./gradlew :app:connectedDebugAndroidTest` on a device or emulator.
  4. Observe `migrate1To2` still passes: the schema is unchanged, so `runMigrationsAndValidate` is
     satisfied and the index query still returns all four names.
- **Blast radius**:
  - *Callers*: none — leaf instrumented test.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: this is the finding's whole subject — `usage_events` is the only Room table
    (`UsageDatabase.kt:10`, `entities = [UsageEvent::class]`), and it is the sole persistence for
    `getDailySummary`, `getWeeklyTotals`, `getDailyUsageForApp`, `getTotalSessionsBlocked` and the
    90-day retention delete, all of which feed the streak and budget features.
  - *Release-only*: none.
- **Fix** (minimal): three added lines in the existing test — after `runMigrationsAndValidate`, query
  the row back and assert it survived intact:
  `db.query("SELECT packageName, durationMillis, dateKey FROM usage_events")`, `moveToFirst()`, then
  `assertEquals("com.test.app", c.getString(0))`, `assertEquals(1000L, c.getLong(1))`,
  `assertEquals("2023-10-01", c.getString(2))`, plus a row count of 1. Insert **two** rows rather
  than one so an off-by-one row loss is also detectable.
- **Rewrite justification**: n/a — additive.
- **Test to add**: `instrumented` — `app/src/androidTest/java/com/grayzone/app/data/MigrationTest.kt`
  → `migrate1To2PreservesRows`, inserting two `usage_events` rows at version 1, migrating to 2, and
  asserting both rows come back with every column value unchanged. **Fails before** a data-destroying
  migration change and **passes after** a correct one — verified by the step-2 mutation above.
  **Cannot be executed here** — no device or emulator.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: device execution to demonstrate that the `DELETE FROM usage_events` mutation leaves
    the test green. The static half — that no post-migration query touches `usage_events` — is
    `confirmed` by reading the file.
  - *Procedure*: apply the step-2 mutation, run
    `./gradlew :app:connectedDebugAndroidTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`
    on an API 29+ device, record the result, revert.
  - *Severity effect*: mutation survives (test stays green) → `Medium` as filed. If Room's
    `validateDroppedTables = true` unexpectedly also compares row state → drops to `Low`. It cannot
    be higher than Medium while the only shipped migration is index-only and therefore
    data-preserving by construction.
- **Build scope**: everyday-build
- **Constitution**: V (the test's name claims migration coverage; its assertions cover schema only)
- **Affected surfaces**: the whole `app/src/androidTest/` set shares the deeper problem that none of
  it can be executed in this project's current workflow — `UsageDaoStatsTest` (4 tests over real
  SQLite aggregation) and `PeakHourAggregationTest` (the D4 `GROUP BY startTime` regression test) are
  well-built and genuinely valuable, and are equally unrun. That is counted once, in **F-200**, and
  is not double-filed here.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-195 — Three of `IntegrationTest`'s eight tests re-implement the logic in the test body and exercise no production code at all  [Low]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/IntegrationTest.kt` →
  `testDurationValidation()` (lines 58–73), `testLockoutDurationValidation()` (lines 75–90),
  `testRemainingMillisValidation()` (lines 92–105)
- **Issue**: These three tests import nothing from the app and call nothing in it. Each declares a
  list of literals, applies `coerceIn` (or, in `testRemainingMillisValidation`, a bare comparison)
  **inside the test body**, and asserts the result. `assertTrue("Invalid remaining time should fail
  check", ms > 0 && ms < 24 * 60 * 60 * 1000)` (`:103`) evaluates the same expression on both sides:
  it is a tautology that would pass with the app deleted. The two `coerceIn` tests assert that
  `kotlin.ranges.coerceIn` works. Meanwhile the app *does* have the real clamp —
  `getNormalizedRemainingMillis` (covered by `LockGuardLogicTest:150–159`) and
  `ConfigValidator.repairWaitSeconds` (`ConfigValidatorTest`, 4 tests) — and neither is called here,
  so the bounds asserted (`1..24*60` for sessions, `15..24*60` for lockouts) are not checked against
  any bound the app actually enforces. If the UI's lockout minimum drifted from 15 minutes, this test
  would not notice. Consequence: three of the eight tests in the file the repository calls
  `IntegrationTest` — whose KDoc claims *"Integration tests for critical paths… validates end-to-end
  workflows"* (`:7–10`) — contribute zero coverage while inflating the test count that a reviewer or
  a coverage summary sees. Filed as **Low** deliberately: no defect can reach a user through this,
  and no fix is blocked. It is a truthfulness-of-the-suite problem, which is exactly the Low row of
  the rubric.
- **Trigger condition**:
  1. `rg -n "import com.grayzone" app/src/test/java/com/grayzone/app/IntegrationTest.kt` — the only
     app import is `service.vpn.DnsPacketHelper` (`:3`), used by the other five tests.
  2. Delete the bodies of `testDurationValidation`, `testLockoutDurationValidation` and
     `testRemainingMillisValidation` and replace each with `assertTrue(true)`.
  3. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  4. Observe the suite is still green and the test count is unchanged — the three tests carry no
     information.
- **Blast radius**:
  - *Callers*: none — leaf tests.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): point each test at the production function whose bound it is describing —
  `testRemainingMillisValidation` → `getNormalizedRemainingMillis` (`Extensions.kt:33`);
  `testDurationValidation` / `testLockoutDurationValidation` → whichever clamp the session and
  lockout writers actually apply. If, on inspection, no production clamp exists for session and
  lockout minutes, delete the two tests and file the missing clamp as a `policy` finding — a test
  asserting a bound nothing enforces is worse than no test.
- **Rewrite justification**: n/a.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/IntegrationTest.kt` →
  `testRemainingMillisValidation` rewritten to
  `assertEquals(0L, getNormalizedRemainingMillis(24L * 60 * 60 * 1000L))` and
  `assertEquals(1L, getNormalizedRemainingMillis(1L))`. **Fails before** if the production clamp is
  changed, **passes after** — which the current body cannot do, because it never calls it.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1) — source read directly; the absence of any app
  symbol in the three bodies is mechanical.
- **Build scope**: everyday-build
- **Constitution**: V (assertions that cannot fail), II (the same clamp is expressed three times: in
  `getNormalizedRemainingMillis`, in `LockGuardLogicTest`, and again inline here)
- **Affected surfaces**: `app/src/test/java/com/grayzone/app/SessionStateLogicTest.kt` — its single
  test, `` `paused remaining time is preserved only within the 24 hour window` `` (`:8–16`), is a
  duplicate of `LockGuardLogicTest.kt:150–159`: same function, same six boundary values, different
  assertion style. Not itself a defect (it does call the production function), but it means the
  24-hour clamp is asserted in three separate files while `StateBackupManager` is asserted in none
  (**F-201**). Also `IntegrationTest`'s remaining five tests are misleadingly *named* rather than
  empty — `testDnsQueryBlockingFlow` (`:13–22`) calls `DnsPacketHelper.createSinkholeResponse`
  directly and never consults `BlocklistManager`, so no blocking *decision* is made anywhere in the
  file despite the name; that naming problem   is counted under **F-203**.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-196 — `CleanupAndLoggingTest` asserts only the two hardcoded-`true` branches of `shouldLog`, so the branch that regressed (F-064) is the one it does not test  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` →
  `logger_shouldAllowWarningsAndErrorsInAllBuilds()` (lines 7–11)
- **Issue**: `GrayzoneLogger.shouldLog` is a two-branch `when` (`GrayzoneLogger.kt:37–40`):
  `DEBUG, INFO -> BuildConfig.DEBUG` and `WARN, ERROR -> true`. The only test in this file asserts
  `assertTrue(shouldLog(WARN))` and `assertTrue(shouldLog(ERROR))` — the branch whose body is the
  literal `true`. It is therefore green by construction and can only fail if someone deletes the
  literal. The branch that carries all the risk, and that **has already silently regressed**, is
  untested: `app/build/generated/source/buildConfig/debug/.../BuildConfig.java:7` now reads
  `DEBUG = false` because the uncommitted `debug { isDebuggable = false }` block
  (`app/build.gradle.kts:76`) makes AGP derive `BuildConfig.DEBUG` from `debuggable`, not from the
  build-type name. Every `GrayzoneLogger.d()` and `.i()` call in the app is now a no-op in *every*
  variant the project builds (F-064, RU-01). The test's own name claims coverage of exactly this —
  *"shouldAllowWarningsAndErrorsInAllBuilds"* — and the suite stayed green through the regression.
  Consequence: the app lost its entire debug/info observability, including the only logging into the
  anti-tamper backup path, and the one test class named after the logger could not detect it.
  The class name promises more than that too: `CleanupAndLoggingTest` contains no cleanup test at
  all (cleanup lives in `data/CleanupWorkerTest`, 3 tests), so the "Cleanup" half of the name is
  unbacked.
- **Trigger condition**:
  1. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11" --rerun-tasks`.
  2. Read `app/build/generated/source/buildConfig/debug/com/grayzone/app/BuildConfig.java:7` —
     `public static final boolean DEBUG = false;` in the **debug** variant.
  3. Note `CleanupAndLoggingTest` passes (recorded in this file's header: 1 test, 0 failures).
  4. Add `assertTrue(GrayzoneLogger.shouldLog(LogLevel.DEBUG))` to the same test and re-run — it
     fails, demonstrating the regression the existing assertions cannot see.
- **Blast radius**:
  - *Callers*: none — leaf test. The production symbol it covers, `GrayzoneLogger.shouldLog`, is
    called from `GrayzoneLogger.d/i/w/e` (`:47`, `:57`, `:65`, `:74`), which are used across the
    service and VPN packages.
  - *Process death*: none — but note the untested `d()`/`i()` are the calls that would have recorded
    service restarts after process death, so the missing coverage compounds exactly where diagnosis
    is hardest.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: `GrayzoneLogger` is stateless and is the only app class the `:a11y` process
    uses (`evidence/cross-process-inventory.md`), so the dead `d()`/`i()` calls also blind the
    accessibility process specifically — and no test covers that process at all.
  - *Persistence*: none.
  - *Release-only*: **no.** This is the point worth recording: the defect looks release-only and is
    not. `BuildConfig.DEBUG` is `false` in the debug variant too, so the everyday build is affected
    identically, and a unit test running against the debug variant *can* catch it.
- **Fix** (minimal): add one assertion pair to the existing test — `assertTrue(shouldLog(DEBUG))` and
  `assertTrue(shouldLog(INFO))` — which pins the intent "debug logging is on in the everyday build"
  and fails until F-064 is fixed. Rename the class to `GrayzoneLoggerTest` (or move the assertions
  into a new one) so the "Cleanup" half of the name is not claiming coverage that
  `CleanupWorkerTest` already owns.
- **Rewrite justification**: n/a — additive.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/CleanupAndLoggingTest.kt` →
  `logger_emitsDebugAndInfoInTheEverydayBuild`, asserting
  `assertTrue(GrayzoneLogger.shouldLog(LogLevel.DEBUG))` and the same for `INFO`. **Fails today**
  (`BuildConfig.DEBUG == false` in the debug variant — this is the rare case where the new test's
  pre-fix failure can be demonstrated without a device) and **passes after** F-064's fix decouples
  the log gate from `debuggable` via a dedicated `BuildConfig` field.
- **Existing test conflict**: none — the two existing assertions remain true under F-064's fix
  (`WARN`/`ERROR` stay unconditional). Note for T034: the new assertion above is deliberately
  designed to be red until F-064 lands, so it must be added **in** the F-064 fix commit, not before.
- **Verification**: `confirmed` (evidence tier T1) — `GrayzoneLogger.kt:37–40` and the generated
  `BuildConfig.java` from this session's `--rerun-tasks` build were both read directly; no device
  needed.
- **Build scope**: everyday-build
- **Constitution**: III (the app's diagnosability layer failed silently), V
- **Affected surfaces**: F-064 (RU-01) owns the production fix; this finding owns only the test-side
  gap and must not be counted as separate fix work in the punch list.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts — the root cause of
  the regression this test fails to detect)`
- **Disposition**: `verified`

### F-197 — `ProtectionStatusTest` asserts `isFullyProtected` through a setter with zero production callers, so it tests a state the shipped app can never reach — and could not detect the uncommitted change that orphaned it  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` →
  `` `repository updates are reflected in status flow` `` (lines 37–48, specifically `:46–47`), and
  `resetRepo()` (line 14)
- **Issue**: Re-verified by direct `Grep` over `app/src/main` rather than trusting the reference
  index: `updateAccessibilityStatus` has **exactly one** occurrence in main source — its own
  declaration at `data/ProtectionHealthRepository.kt:34`. Its only two callers were
  `GrayzoneAccessibilityService.onServiceConnected`/`onDestroy`, and the uncommitted `:a11y` process
  split deleted both (`evidence/prior-fix-watchlist.md`, elevated-attention table). So
  `ProtectionStatus.accessibilityActive` (`data/ProtectionStatus.kt:6`) is permanently `false` in
  production, `isFullyProtected` (`:10–11`) is unsatisfiable, and `isDegraded` (`:13–14`) reports
  `true` for a fully-healthy device. The test at `:46–47` reaches that state by calling the orphaned
  setter directly and asserts `isFullyProtected` is `true` — a transition the shipped app cannot
  perform. Two consequences, and the second is the important one. First, the assertion is not
  evidence about the product: it verifies the data class's `&&`, which the pure-constructor test at
  `:17–24` already covers without touching the repository. Second, and this is the structural
  failure: **the suite deleted the only two callers of a production API and stayed green**, because
  the test drives the repository directly instead of driving the thing that drives the repository.
  A test that cannot notice the disappearance of its subject's callers is not protecting that
  subject.
- **Trigger condition**:
  1. `rg -n "updateAccessibilityStatus" app/src/main` → one hit, the declaration
     (`ProtectionHealthRepository.kt:34`).
  2. `rg -n "updateAccessibilityStatus" app/src/test` → two hits,
     `ProtectionStatusTest.kt:14` and `:46`.
  3. Run the suite — `ProtectionStatusTest` passes, 3 tests, 0 failures.
  4. Install a build and reach a fully-set-up device (VPN on, overlay on, accessibility granted).
     `ProtectionHealthRepository.status.value.isFullyProtected` is still `false`, contradicting the
     assertion at `:47`.
- **Blast radius**:
  - *Callers*: the test is a leaf. The repository it drives **is** live for the other two legs —
    `updateOverlayStatus` from `OverlayService.kt:142,760`; `updateVpnStatus` /
    `markVpnReconnectPending` from `AdBlockVpnService.kt:226,248,311,319,336`; read at
    `MainActivity.kt:137` and `ui/VpnScreen.kt:51`. Only the accessibility leg is orphaned, which is
    why this is Medium and not Low: the object is not dead, it is *partially* wired, which is harder
    to notice.
  - *Process death*: `ProtectionHealthRepository` is an `object` holding a `MutableStateFlow` — pure
    in-memory state that resets to all-`false` on process death with no persisted mirror. No test
    covers that, and the test's `@Before resetRepo()` (`:11–15`) hides it by resetting manually.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: **this is the trap the fix must avoid.** The natural repair — restore the two
    deleted calls in `GrayzoneAccessibilityService` — would write to a `MutableStateFlow` living in
    the `:a11y` process, which is a *different object instance* from the one `MainActivity` and
    `VpnScreen` read in the default process. The write would appear to succeed and the reader would
    never see it. `evidence/cross-process-inventory.md` records this as a latent trap and rates the
    naive wiring **High**. Any test added for this must assert across the process boundary (the
    accessibility state must be re-derived in the default process from
    `AppUtils.isAccessibilityServiceEnabled`, `AppUtils.kt:108–126`), not by calling the setter.
  - *Persistence*: none — nothing about `ProtectionStatus` is persisted.
  - *Release-only*: none.
- **Fix** (minimal): change what the test drives. Replace the `updateAccessibilityStatus(true)` call
  at `:46` with an assertion over the accessibility *source of truth* the app actually consults —
  `AccessibilityServiceIds.isServiceEnabled(...)` feeding a default-process refresh — and keep the
  pure-constructor tests at `:17–35` unchanged, since those are correct and cheap. Until the
  production wiring is decided, mark `:46–47` with
  `@Ignore("asserts a state production cannot reach — see findings-ru10.md F-197")` so it cannot be
  cited as evidence that `isFullyProtected` works.
- **Rewrite justification**: n/a — one assertion redirected; the other two tests in the file are
  sound pure-data-class tests and stay.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` →
  `` `accessibility leg has a production writer` ``, a guard test asserting that the value reaching
  `ProtectionStatus.accessibilityActive` originates from the default-process accessibility probe
  rather than from a test-only setter. **Fails before** the production wiring is restored, **passes
  after**. This is the shape that would have turned red when the `:a11y` split deleted the callers.
- **Existing test conflict**: **yes, in the other direction.** `` `repository updates are reflected in
  status flow` `` (`:37–48`) will conflict with any fix that removes or renames
  `updateAccessibilityStatus` as dead code — a deletion is one of the two live options for the
  production defect. Required change: the test must be updated in the same commit, or the deletion
  will be blocked by a green test that is the function's only caller. This is the mirror image of
  F-199 and T034 should treat the pair together.
- **Verification**: `confirmed` (evidence tier T1) — direct `Grep` over `app/src/main` (not the
  reference index), plus reading `ProtectionHealthRepository.kt`, `ProtectionStatus.kt`, and the
  test. The device step in the trigger list is corroboration, not the basis of the claim.
- **Build scope**: everyday-build
- **Constitution**: I (an orphaned production function), IV (the cross-process trap above)
- **Affected surfaces**: `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt`
  (uncommitted — where the two callers were deleted); the production-side defect is owned by T020 /
  `evidence/cross-process-inventory.md` and is **not** re-filed here.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneAccessibilityService.kt,
  AndroidManifest.xml — the change that orphaned the setter this test drives)`
- **Disposition**: `verified`

### F-198 — `DnsResolverPoolTest` opens real UDP sockets and covers only the happy path; every failure branch in `DnsResolverPool` is untestable until `unitTests.isReturnDefaultValues = true` ships, which blocks verification of the F-031 fix  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` →
  `` `pool limits concurrency to max sockets` `` (lines 15–41) and
  `` `shutdown causes subsequent queries to return null without creating sockets` `` (lines 43–58)
- **Issue**: **First, a correction to the lead's caveat in `findings.md` (Reconciliation 2), which
  this task was asked to verify.** The note states that these two tests *"will break when the logging
  fix lands"*. Traced statement by statement, that is **not** what happens, and T034 should not plan
  around it. The three sites F-031 fixes are `DnsResolverPool.kt:107` and `:112` (inside
  `releaseSocket`) and `:123` (inside `shutdown`), and all three are `catch` bodies around
  `socket.close()`. Test 1 reaches `releaseSocket(socket, reusable = true)` with an open socket, so
  `:106` is false and `:111` is false and the socket goes to `idleSockets` — neither catch executes.
  Test 1 never calls `shutdown()`. Test 2 calls `shutdown()` while `idleSockets` is **empty**, so the
  `forEach` at `:122–124` has no iterations. Adding a log statement to a catch block that never runs
  cannot turn a test red. **The conclusion the caveat reaches is right; the mechanism is not**, and
  the real mechanism is worse because it is environment-dependent rather than deterministic:
  1. `DnsResolverPool` **already** logs on its two failure paths — `GrayzoneLogger.w` at `:71–74`
     (query failed) and `GrayzoneLogger.e` at `:96–100` (socket creation failed) — and
     `shouldLog(WARN)`/`shouldLog(ERROR)` are the unconditional `true` branch
     (`GrayzoneLogger.kt:39`), so both reach `android.util.Log`.
  2. `app/build.gradle.kts` has **no `testOptions` block** (confirmed by reading all 159 lines), so
     AGP's default `unitTests.returnDefaultValues = false` applies and every `android.jar` method
     throws `RuntimeException: Method w in android.util.Log not mocked.`
  3. Both tests fire **real UDP traffic at `127.0.0.1:53`** on the build machine
     (`InetAddress.getByName("127.0.0.1")`, `:30` and `:53`; `timeoutMs = 50L`). They pass here only
     because `socket.receive` times out and `SocketTimeoutException` is caught *inside* the
     `withTimeoutOrNull` block at `:62–64`. On a machine where the ICMP port-unreachable is surfaced
     to the unconnected socket instead — or where something is listening on port 53 and answers
     malformed — the exception escapes to the outer catch at `:67`, `GrayzoneLogger.w` fires, and the
     test fails with `Method w in android.util.Log not mocked` : a failure caused by the developer's
     network stack, reported as a mocking error, in a DNS test. That is the actual fragility.
  So the consequence is twofold: the two existing tests are environment-coupled and can fail for
  reasons unrelated to the code, and — the part that blocks work — **F-031's fix cannot be given the
  test its own finding record requires**, because any test that drives a socket-close failure and
  asserts the new log will hit the same unmocked `android.util.Log`. Everything below `:67` in the
  class is currently unverifiable.
- **Trigger condition**:
  1. `rg -n "testOptions" app/build.gradle.kts` → no matches.
  2. Add a test to `DnsResolverPoolTest` that forces `performQuery`'s outer catch: pass a `server`
     whose `send` throws, or shut the socket underneath the call.
  3. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  4. Observe `java.lang.RuntimeException: Method w in android.util.Log not mocked.` — not the
     assertion failure the test was written to produce.
- **Blast radius**:
  - *Callers*: the tests are leaves. `DnsResolverPool` itself is constructed at
    `AdBlockVpnService.kt` (the `resolverPool` field, `@Volatile` in the uncommitted diff) and shut
    down from `shutdownVpn`; `AdBlockVpnService` has no tests, so the pool's only coverage is this
    file.
  - *Process death*: none for the tests. Note the untested `shutdown()` path is what
    `Service.onDestroy` calls, i.e. precisely the process-death path.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none — `AdBlockVpnService` is declared with no `android:process`
    (`AndroidManifest.xml:121–132`), so the pool is default-process.
  - *Persistence*: none.
  - *Release-only*: none — unit tests do not run through R8. But the setting's absence is a
    **build-config** change owned by RU-08, so the fix lands in `app/build.gradle.kts`.
  - *Cross-cutting note*: the same `android.util.Log` wall blocks JVM tests for every Principle III
    fix in the audit, since the entire point of those fixes is to add a log call. This is not local
    to `DnsResolverPool`.
- **Fix** (minimal): two parts, and they must ship together.
  1. **Build side (owned by T025/RU-08, cited here, not re-filed)**: add
     `testOptions { unitTests.isReturnDefaultValues = true }` to `app/build.gradle.kts`'s `android`
     block so `android.util.Log` returns `0` instead of throwing.
  2. **Test side (owned here)**: stop pointing the two existing tests at a real network endpoint.
     `DnsResolverPool` already takes an injectable `protectSocket: (DatagramSocket) -> Unit`
     (`:20`), which is what makes the tests possible at all — extend the same seam to socket
     *creation* so a test can supply a socket whose `send`/`close` throw on demand. That is a
     one-parameter change (`socketFactory: () -> DatagramSocket = { DatagramSocket() }`) and it
     unlocks all five untested branches at once.
- **Rewrite justification**: not a rewrite — one constructor parameter with a default, so no
  production call site changes.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt`
  → `` `a socket that throws on close is not returned to the pool and the failure is logged` ``,
  injecting a `DatagramSocket` whose `close()` throws, calling `performQuery` then `shutdown()`, and
  asserting the pool does not retain the socket. **Fails before** F-031 (the catch is empty, so
  nothing distinguishes a failed close from a successful one) and **passes after**. It cannot even be
  written today without fix part 1.
- **Existing test conflict**: **yes, but not the one that was reported.** The conflict is that
  **F-031's required test cannot be written** until `unitTests.isReturnDefaultValues = true` is
  added; the two *existing* tests do not break, for the reason traced above. Recorded here as an
  `existing_test_conflict` because it is a hard ordering constraint on F-031's fix: the RU-08 build
  change must land first or in the same commit.
- **Verification**: `confirmed` (evidence tier T1) for the static claims — `DnsResolverPool.kt`,
  `DnsResolverPoolTest.kt`, `GrayzoneLogger.kt` and all 159 lines of `app/build.gradle.kts` were read
  directly, and the suite's green run is recorded in this file's header.
  `needs-verification` (T4) for one sub-claim only: that the loopback UDP behaviour differs across
  host platforms and can push `receive()` into the outer catch.
  - *Missing*: a second build host (Linux/macOS) to run the two tests against.
  - *Procedure*: run `./gradlew :app:testDebugUnitTest --tests '*DnsResolverPoolTest*'` on a Linux
    host with nothing bound to `127.0.0.1:53`, and again with a local resolver running (e.g.
    `systemd-resolved`), and record whether either produces
    `Method w in android.util.Log not mocked`.
  - *Severity effect*: if the flake reproduces on another host → `Medium` stands and the fix becomes
    more urgent, since the suite is then non-deterministic across developer machines. If it never
    reproduces → still `Medium`, because the blocked-verification half of the finding is confirmed
    independently of the flake.
- **Build scope**: everyday-build
- **Constitution**: III (the class's failure paths are the ones with no coverage), V
- **Affected surfaces**: `DnsResolverPool.kt:67` (`performQuery` catch), `:95` (`acquireSocket`
  catch), `:107`, `:112`, `:123` (the three F-031 sites) — five untested branches in a 128-line
  class; `app/build.gradle.kts` (fix part 1, owned by T025).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts,
  AdBlockVpnService.kt — neither is a test file; DnsResolverPool.kt itself is unmodified)`
- **Disposition**: `verified`

### F-199 — Two production functions have tests as their only consumers, so the tests are self-referential: they cannot fail for any reason a user would notice  [Low]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` → lines 37 and 45
  (sole consumers of `SetupRequirements.actionLabel()`, `SetupRequirements.kt:45`);
  `app/src/test/java/com/grayzone/app/DateUtilsTest.kt` → lines 26–27 (sole consumers of
  `DateUtils.isSameDay()`, `DateUtils.kt:113`)
- **Issue**: Both claims were re-verified by direct `Grep` over `app/src/main`, per the reference
  index's accuracy warning — each symbol has exactly one main-source occurrence, its own
  declaration. The test-side consequence, which is what this unit owns, is that these assertions
  form a closed loop: the function exists because the test calls it, and the test passes because the
  function exists. `SetupRequirementsTest` asserts `assertEquals("Turn on Accessibility",
  SetupRequirements.actionLabel(missing))` — pinning exact user-facing button copy that **no button
  displays**. If a designer changed the real button's label, this test would stay green; if someone
  changed this string, the test would go red for a string no user reads. Both directions are wrong.
  The same holds for `isSameDay`: the production day-comparison the app actually performs is
  `DateUtils.shouldResetDailyRuntimeState` (also covered in the same file at `:31–39`, and
  legitimately, since `GrayzoneApplication` calls it), so `isSameDay` is a parallel implementation
  of the same idea with a test and no caller. Rated **Low** per the rubric's dead-code row: no user
  can be harmed by it. The reason it is worth filing at all is arithmetic — it inflates the number a
  maintainer trusts. Two of `SetupRequirementsTest`'s five assertions and one of `DateUtilsTest`'s
  five tests measure the test suite rather than the app.
- **Trigger condition**:
  1. `rg -n "actionLabel" app/src/main` → 1 hit: `SetupRequirements.kt:45`, the declaration.
  2. `rg -n "actionLabel" app/src/test` → 2 hits: `SetupRequirementsTest.kt:37,45`.
  3. `rg -n "isSameDay" app/src/main` → 1 hit: `DateUtils.kt:113`, the declaration.
  4. `rg -n "isSameDay" app/src/test` → 2 hits: `DateUtilsTest.kt:26,27`.
  5. Delete both production functions and both sets of assertions; build and run the app. Nothing
     changes on any screen.
- **Blast radius**:
  - *Callers*: enumerated exhaustively above — **zero** production callers for either. The other
    members of both objects are live: `SetupRequirements.missing`/`headline`/`body` are used by
    `ui/OnboardingScreen.kt` and `ui/HomeScreen.kt`; `DateUtils.formatDateKey`,
    `shouldResetDailyRuntimeState`, `millisUntilMidnight` (`OverlayService.kt:247`),
    `getCurrentDateKey` and `getYesterdayDateKey` are all live. So neither file is dead — only these
    two members are, which is why a name-based sweep would be dangerous here.
  - *Process death*: none.
  - *Config change*: none — though `actionLabel` returns a hardcoded English string rather than a
    string resource, so if it were ever wired up it would not follow locale. Noted for T028 (RU-09),
    not filed.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: **yes, and it matters for the deletion decision.** Both functions are already
    removed by R8 in release builds as unreachable, so deleting them changes nothing about the
    shipped APK; the choice is purely about source hygiene.
- **Fix** (minimal): the maintainer picks one direction per symbol and the test follows.
  - `SetupRequirements.actionLabel` — **wire it**, don't delete it. `ui/HomeScreen.kt` and
    `ui/OnboardingScreen.kt` already call `missing()`, `headline()` and `body()` from the same object
    and then build their button labels inline; routing them through `actionLabel()` removes a
    duplication and makes the existing assertions meaningful. Cheaper than deletion and it closes
    F-073 in the same edit.
  - `DateUtils.isSameDay` — **delete it** along with `DateUtilsTest:20–28`. Nothing needs a second
    same-day predicate while `shouldResetDailyRuntimeState` exists and is live.
- **Rewrite justification**: n/a.
- **Test to add**: `jvm-unit` — none, in the delete direction. In the wire-it direction:
  `app/src/test/java/com/grayzone/app/SetupRequirementsTest.kt` →
  `` `action label matches the primary button copy` ``, asserting `actionLabel(missing)` equals the
  same constant the composable renders (extract it to a shared `const`). **Fails before** if the two
  copies diverge, **passes after** — which the current assertion cannot do, because there is no
  second copy to compare against.
- **Existing test conflict**: **yes.** Deleting `DateUtils.isSameDay` (F-072) breaks
  `DateUtilsTest:21–28`, and deleting `SetupRequirements.actionLabel` (F-073) breaks
  `SetupRequirementsTest:37` and `:45`. In both cases the test *is* the only caller, so the deletion
  cannot be done without the test edit — the tests are what make these two look "used" to any
  reference-counting sweep, which is the specific way a test can mask dead code. T059–T064 must
  search `app/src/main` only, not `app/src`, when counting references.
- **Verification**: `confirmed` (evidence tier T1) — direct `Grep` over `app/src/main` for both
  symbols, run for this finding rather than taken from `evidence/reference-index.md`, per the index's
  accuracy warning.
- **Build scope**: everyday-build
- **Constitution**: I (dead production code), V
- **Affected surfaces**: F-072 and F-073 (RU-01) own the production-side dead-code records; this
  finding owns only the test-side consequence and the ordering constraint on their removal. T034 must
  not count these as separate fix work.
- **Observed against**: `working-tree @ 557a963 (uncommitted: none of the files in this finding)`
- **Disposition**: `verified`

### F-200 — The anti-tamper backup has no test of any kind, because its serialization and restore logic is `private` behind two `Context`-taking entry points  [High]

- **Review unit**: RU-10
- **Location**: absent from `app/src/test/java/com/grayzone/app/` and
  `app/src/androidTest/java/com/grayzone/app/` — the subject is
  `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` →
  `restoreIfWiped()` (line 104), `writeBackup()` (line 160), `serialize()` (line 200),
  `applyInto()` (line 229)
- **Issue**: `rg -n "StateBackupManager" app/src/test app/src/androidTest` returns **zero matches**.
  The subsystem whose entire purpose is that locks survive a data wipe — the app's headline
  anti-bypass guarantee, and the one the user is the adversary for — has no automated coverage at
  all. This is not an oversight of effort but a consequence of shape: the only two public entry
  points, `restoreIfWiped(context)` and `writeBackup(context)`, both require an Android `Context`,
  and the logic worth testing (`serialize` at `:200`, `applyInto` at `:229`) is `private`, so a JVM
  test cannot reach it even with the `FakeSharedPreferences` fixture the suite already has
  (`app/src/test/java/com/grayzone/app/FakeSharedPreferences.kt`). The untested surface is not
  academic — it is where the audit's most severe finding lives. `applyInto` writes arbitrary keys
  with arbitrary types straight from the JSON with no allowlist, and `serialize` writes a
  `SCHEMA_VERSION` (`:202`) that `applyInto` never reads (`:229–231`), which is the chain that makes
  **F-060 (Critical, C7)** an unrecoverable boot loop: the restore runs first in
  `Application.onCreate` and plants a value whose type then throws `ClassCastException` in
  `validateConfiguration()`. Every link in that chain is pure, deterministic, `Context`-free logic —
  a round-trip test and a schema-mismatch test would both be a few lines and would both fail today.
  The consequence of the gap is therefore concrete: the fix for the audit's top-ranked Critical has
  nowhere to land a regression test, and the same absence is why the defect shipped.
- **Trigger condition**:
  1. `rg -n "StateBackupManager" app/src/test app/src/androidTest` → no matches.
  2. Read `StateBackupManager.kt:200` and `:229` — both `private fun`.
  3. Attempt to write a JVM test for `applyInto`: it cannot be called, and `restoreIfWiped` requires
     a `Context`, external-storage access, and `MANAGE_EXTERNAL_STORAGE`.
  4. Confirm no instrumented alternative exists: `app/src/androidTest/` contains only three Room
     classes (`MigrationTest`, `UsageDaoStatsTest`, `PeakHourAggregationTest`).
- **Blast radius**:
  - *Callers*: `restoreIfWiped` is called from `GrayzoneApplication.onCreate` (`:64`) and
    `MainActivity.onResume` (`MainActivity.kt:48`); `writeBackup` is launched from `appScope`.
    Both are on the startup critical path, which is what turns a restore defect into a launch
    failure rather than a feature failure.
  - *Process death*: the backup exists precisely to survive process death and data loss, so a test
    must cover the cold-start restore path, not just the write path.
  - *Config change*: none.
  - *Foreground/background*: `restoreIfWiped` runs on `MainActivity.onResume`, i.e. on every
    foreground transition, so a test must also establish it is idempotent — nothing asserts that
    today.
  - *Cross-process*: `GrayzoneApplication.kt:52–54`'s process guard means the `:a11y` process must
    **not** run the restore. That guard is uncommitted and untested; a regression removing it would
    give two processes writing the same `SharedPreferences` and the same backup file, with no test
    failure.
  - *Persistence*: this is the whole finding — the subject reads and writes `SharedPreferences`
    wholesale (`prefs.all` at `:167`) **and** a file in `/sdcard/.grayzone/`, using AES-GCM and Gson.
    A round-trip test is the single highest-value test missing from this repository.
  - *Release-only*: the backup path is one of the few places R8 could alter behaviour, since Gson
    deserialization is reflection-based. No test covers the release variant at all.
- **Fix** (minimal): change two `private` keywords to `internal` and add one test file. Making
  `serialize(context, all)` and `applyInto(prefs, json)` `internal` puts both in reach of
  `app/src/test`, and `applyInto` already takes a `SharedPreferences` interface, which
  `FakeSharedPreferences` implements — so the test needs no Android runtime, no Robolectric, and no
  change to `build.gradle.kts`. `serialize` takes a `Context`, so either narrow its signature to the
  `Map<String, Any?>` it actually serializes or accept the key material as a parameter. This is a
  smaller change than it sounds and is the prerequisite for testing F-060's fix.
- **Rewrite justification**: not a rewrite — visibility widening plus one signature narrowing. No
  call site changes and no behaviour changes.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/data/StateBackupManagerTest.kt`
  with three assertions:
  (a) `` `serialize then applyInto round-trips every value type` `` — seed a `FakeSharedPreferences`
  with a `Boolean`, `Int`, `Long`, `String` and `Set<String>`, serialize, apply into a fresh fake,
  assert equality. **Fails before** any type is dropped or coerced, passes after.
  (b) `` `applyInto rejects a snapshot whose SCHEMA_VERSION does not match` `` — assert `false` is
  returned and the target prefs are untouched. **Fails today** (the version is written at `:202` and
  never read), passes once F-060's schema gate lands.
  (c) `` `applyInto ignores keys outside the allowlist` `` — feed a snapshot containing
  `per_app_wait_com.example` set to `0` and an unknown key, and assert neither is written.
  **Fails today**, passes after the allowlist.
- **Existing test conflict**: none — there is nothing to conflict with, which is the finding.
- **Verification**: `confirmed` (evidence tier T1) — the absence was established by direct `Grep`
  over both test source sets, and the visibility modifiers by reading `StateBackupManager.kt`.
- **Build scope**: everyday-build
- **Constitution**: V (the app's central anti-bypass guarantee rests on unverified code)
- **Affected surfaces**: F-060 (Critical, RU-01) and the backup schema/allowlist lead assigned to
  T020 both need this seam before they can be regression-tested. This finding owns the test gap only;
  the production defects are not re-filed.
- **Observed against**: `working-tree @ 557a963 (uncommitted: GrayzoneApplication.kt — adds the
  process guard that a restore test would also need to cover)`
- **Disposition**: `verified`

### F-201 — Nothing tests that a monitored app actually gets a friction or lockout screen, or that the screen cannot be escaped: the three service classes that deliver every guarantee have zero coverage  [High]

- **Review unit**: RU-10
- **Location**: absent from both test source sets — the untested subjects are
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` (~1100 lines),
  `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` (131 lines),
  `app/src/main/java/com/grayzone/app/service/SessionExpiryScheduler.kt` and
  `SessionExpiryReceiver.kt`
- **Issue**: `rg -n "OverlayService|GrayzoneAccessibilityService|SessionExpiryScheduler|SessionExpiryReceiver" app/src/test app/src/androidTest`
  returns exactly **two matches, and neither is a usage**: `AccessibilityServiceIdsTest.kt:10` and
  `:11` hold the accessibility service's fully-qualified name as a `String` literal, for parsing.
  No test in either source set constructs, invokes, or observes any of these four classes. The suite
  covers the *decisions* thoroughly — 58 tests
  across four `SessionPolicyEngine` suites, 9 in `OverlayPresentationTest`, 16 in
  `LockGuardLogicTest` — and covers the *delivery* not at all. Every one of the app's two primary
  guarantees fails in the delivery layer, not the decision layer, and the audit's own Critical list
  proves it: **C1** is `windowManager.addView` failing inside a swallowing catch
  (`OverlayService.kt:989–994`), after which no overlay exists and the monitored app stays fully
  usable during a lockout; **F-205** is an unguarded `startForeground` in the same file's `onCreate`
  (`:144`) that, at `targetSdk 34`, throws `ForegroundServiceStartNotAllowedException` when the
  service is started from the background — which is what the uncommitted `:a11y` watchdog does on
  every app change. Both produce a green suite. The policy engine correctly returns
  `ShowLockoutScreen`; nothing checks that a lockout screen appears. Worse, the suite references
  `SessionCommand.DismissOverlay` **14 times** across three engine suites
  (`SessionPolicyEngineTest` 7, `...ForegroundTest` 6, `...BackgroundTest` 1), and
  `OverlayService.executeCommand` handles that command with a documented **no-op**
  (`OverlayService.kt:488–490`, the root cause behind F-005) — so the most-asserted command in the
  test suite has an executor that deliberately does nothing, and no test can tell. Consequence: the
  app's reason for existing is verified only down to the point where it stops being pure Kotlin.
- **Trigger condition**:
  1. `rg -n "OverlayService" app/src/test app/src/androidTest` → no matches.
  2. Introduce a deliberate defect: make `OverlayService.showOverlay()` return early before
     `windowManager.addView`.
  3. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  4. Observe 306 tests, 0 failures — the app now shows no lock screen at all and the suite is green.
  5. On device: grant both permissions, set a 1-minute limit, open the monitored app, wait for the
     lockout, and confirm the app is freely usable.
- **Blast radius**:
  - *Callers*: `OverlayService` is reached from `GrayzoneAccessibilityService.onAccessibilityEvent`
    (via `startForegroundService`, `:93–99`), `SessionExpiryReceiver.onReceive`, `BootReceiver`,
    `MainActivity`, and its own `onTaskRemoved` re-start alarm (uncommitted, `:680–704`). None of
    those five entry paths has a test.
  - *Process death*: prior-defect **P5** (re-arm the session alarm after process death) is
    explicitly recorded in `evidence/prior-fix-watchlist.md` as having **no test and no manual
    validation** — feature 002's task T021 is still unchecked — so this path has never been verified
    by anything. `onTaskRemoved`'s 2-second restart alarm, added in the uncommitted diff, is equally
    unverified.
  - *Config change*: rotation re-lays-out the overlay window; nothing asserts the overlay is not
    double-added or dropped. Untested.
  - *Foreground/background*: the decisive axis. `startForeground` at `:144` behaves differently by
    background state at `targetSdk 34`, and every JVM test in the suite is state-free.
  - *Cross-process*: the uncommitted `android:process=":a11y"` split means the accessibility service
    and the overlay service now run in different processes and communicate by Intent. **Nothing in
    the test suite exercises a multi-process configuration** (`evidence/prior-fix-watchlist.md`
    states this verbatim), and the split shipped with no test change at all.
  - *Persistence*: `ACTIVE_UNTIL` and `LOCKED_UNTIL` are written from two separate sites
    (`OverlayService.kt:1091–1098` and `:514–531`), each of which must independently remember to call
    `SessionExpiryScheduler.arm()`. `evidence/prior-fix-watchlist.md` Fragile-fix #1 identifies this
    as the invariant most likely to silently regress; prior defect **P4** covering it has no test.
  - *Release-only*: `OverlayService`, `MainActivity` and the accessibility service have R8 keep rules
    (`evidence/r8.md` §F2), so the classes survive minification, but no test runs against the release
    variant.
- **Fix** (minimal): do not attempt to test `OverlayService` whole — it is ~1100 lines with a
  `WindowManager`, notifications and coroutines, and a Robolectric or instrumented harness for it is
  a project, not a fix. Extract the two decisions that carry the guarantees into pure functions
  testable with what the suite already has:
  1. **Overlay attachment bookkeeping** — a small pure state type answering "is an overlay currently
     attached, and for which package and mode", so C1's failure path (`addView` throws → `return`
     with `overlayView` still null) becomes assertable without a `WindowManager`.
  2. **Alarm-arming invariant** — funnel every `ACTIVE_UNTIL` write through one helper that arms or
     cancels as part of the write (`evidence/prior-fix-watchlist.md`'s own "robust form" for
     Fragile-fix #1), so a JVM test can assert that persisting a session always schedules its expiry.
  Both are the minimal seams; neither changes behaviour.
- **Rewrite justification**: the fix deliberately avoids rewriting `OverlayService`. Two extractions
  are proposed instead precisely because a component rewrite is not justified by a coverage finding
  (FR-014).
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/OverlayAttachmentStateTest.kt`, asserting that after a
  simulated `addView` failure the bookkeeping reports **not attached** and a subsequent lock attempt
  retries rather than assuming an overlay is up. **Fails before** C1's fix (the current code returns
  with no record of the failure and no retry), **passes after**.
  Second: `app/src/test/java/com/grayzone/app/service/SessionAlarmInvariantTest.kt`, asserting that
  every path that writes `ACTIVE_UNTIL` also arms the expiry alarm. **Fails before** if either of the
  two write sites is changed to skip arming, **passes after**.
  A device-level check of the guarantee itself remains necessary and **cannot be executed here**.
- **Existing test conflict**: none — nothing currently asserts anything about these classes.
- **Verification**: `needs-verification` (evidence tier T4) for the on-device consequence;
  `confirmed` (T1) for the coverage claim itself, which is a mechanical `Grep` result over both test
  source sets.
  - *Missing*: a device or emulator to demonstrate that the step-2 mutation produces a usable
    monitored app during a lockout while the suite stays green.
  - *Procedure*: on any API 29+ device — grant Accessibility and Display-over-other-apps, add one
    monitored app with a 1-minute daily budget, open it until the budget lock fires, and confirm the
    lock screen appears; then apply the step-2 mutation, reinstall, and repeat.
  - *Severity effect*: confirmed → `High` as filed (the guarantee the app exists for has no
    automated protection). It does not rise to Critical, because a coverage gap does not itself
    disable protection — C1 and F-205 are the findings that do, and they are already filed at
    Critical by the owning units. Filing this at Critical would double-count them.
- **Build scope**: everyday-build
- **Constitution**: V (the primary guarantee is unverified), IV (no multi-process coverage after the
  uncommitted split)
- **Affected surfaces**: C1 / F-205 (RU-04, RU-05) and F-005 (`DismissOverlay` no-op) are the
  production defects this gap allows to persist; prior defects P3, P4, P5, P8, P9, P10 from
  `evidence/prior-fix-watchlist.md` are the six with no automated coverage, all of which live in
  these classes. Not re-filed.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt,
  GrayzoneAccessibilityService.kt, AndroidManifest.xml — all three modified with no test change)`
- **Disposition**: `verified`

### F-202 — Six of the ten defects feature 002 fixed have no automated test, and the manual validation assigned to cover them was never run, so those fixes have never been verified by anything  [High]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/` (the two classes that do lock in `002`
  behaviour: `policy/SessionExpiryDecisionTest.kt`, `AccessibilityPromptStateTest.kt`) versus
  `specs/002-fix-timeout-accessibility-bugs/tasks.md:110` (the unchecked validation task)
- **Issue**: `evidence/prior-fix-watchlist.md` tabulates all ten defects feature `002` fixed against
  the tests that lock each one in, and the result is **4 covered, 6 not**: P3 (the superseded
  `delay()`-based lock path was deleted — a reintroduction fails no test), P4 (arm the expiry alarm
  at every `ACTIVE_UNTIL` write), P5 (re-arm after process death or boot), P8 (the accessibility
  service cannot crash — a crash is what makes Android revoke the service), P9 (no
  interactive-window retrieval), P10 (no `disableSelf`, `onCreate` cannot throw). Three of those six
  are enforced **by grep and code review only**. The safety net that was meant to cover them is
  `specs/002-fix-timeout-accessibility-bugs/tasks.md:110` — task T021, "Execute the on-device
  validation … Scenarios A–D and E–G" — and it is verified here as still **unchecked**, carrying the
  note *"(MANUAL — requires a physical device/emulator; not run in this environment.)"*. So the
  contract clauses that were deliberately assigned to manual verification (SE-5 process-death re-arm,
  SE-7 pause-cancels-the-alarm, AH-1 crash safety) have been verified by neither automation nor a
  human. The pattern is exact and worth stating plainly: **every `002` fix that lives in pure Kotlin
  has a test, and every fix that lives in Android glue has none.** That is also precisely the
  boundary the regressions would cross, since the two original defects — a countdown running past
  zero and a monitored app never locking at session end — both lived in the glue, not the decisions.
  Consequence: the app's protection can regress to its pre-`002` behaviour with a green suite, and
  `evidence/prior-fix-watchlist.md` Fragile-fix #1 names the exact shape it would take (a new
  `ACTIVE_UNTIL` writer that forgets to arm the alarm, silently restoring "counts down, never
  locks").
- **Trigger condition**:
  1. Read `specs/004-android-regression-audit/audit/evidence/prior-fix-watchlist.md` §"Test coverage
     of the prior fixes" — the per-defect table and its "**Net**: 4 of 10" line.
  2. Read `specs/002-fix-timeout-accessibility-bugs/tasks.md:110` — `- [ ] T021`, still unchecked.
  3. Simulate the Fragile-fix #1 regression: add a third `ACTIVE_UNTIL` write in `OverlayService`
     without a matching `SessionExpiryScheduler.arm()` call.
  4. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` —
     306 tests, 0 failures. The session now counts down and never locks, which is the original P2
     defect.
- **Blast radius**:
  - *Callers*: the six uncovered fixes live in `OverlayService.onCreate()` /
    `markAppUnlocked()` / `executeCommand(UpdateState)` / `performMidnightReset()`,
    `SessionExpiryScheduler.arm()`/`cancel()`, `SessionExpiryReceiver.onReceive()`,
    `BootReceiver.onReceive()`, and `GrayzoneAccessibilityService.onServiceConnected()` /
    `onAccessibilityEvent()` / `onUnbind()`.
  - *Process death*: **P5 is entirely about process death** and has neither an automated nor a manual
    verification. `OverlayService.onCreate()` re-arms only when `activeUntil > now` (`:157–162`) and
    silently skips an already-expired session; the watchlist records that the negative-countdown
    symptom is currently masked *accidentally*, by `startForeground` overwriting the same
    notification id. Nothing tests either half.
  - *Config change*: none of the six.
  - *Foreground/background*: P4/P5 depend on Doze and background execution, which is why they were
    assigned to manual validation in the first place.
  - *Cross-process*: P8/P10 were designed around `OverlayService` and the accessibility service
    **sharing** a process. The uncommitted `android:process=":a11y"` split invalidates that premise,
    and `OverlayService.kt:164–166` still carries the stale comment asserting the old topology. No
    test covers either the old or the new arrangement.
  - *Persistence*: P4's invariant is "every write of `ACTIVE_UNTIL` arms an alarm" — a persistence
    invariant spread across two writers with no single owner.
  - *Release-only*: none.
- **Fix** (minimal): this finding's fix is a process change plus one test, not a test-suite rewrite.
  1. Convert `002`'s task T021 from an open manual checkbox into either an executed-and-recorded
     validation run or an explicit written waiver naming what is unverified. An indefinitely-open
     manual verification task is indistinguishable from no verification, and it has now survived two
     subsequent features (`003`, `004`).
  2. Close the single highest-value gap in code rather than by procedure — P4 — by funnelling every
     `ACTIVE_UNTIL` write through one helper that arms or cancels as part of the write, exactly as
     `evidence/prior-fix-watchlist.md` prescribes. That converts a review-memory invariant into a
     testable one and is shared with **F-201**'s second extraction, so the two findings cost one fix
     between them.
- **Rewrite justification**: n/a — no component is rewritten.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/SessionAlarmInvariantTest.kt` (the same file proposed
  in F-201; one file satisfies both), asserting that a session grant produces both the persisted
  `ACTIVE_UNTIL` **and** an armed expiry, and that clearing a session produces both the cleared value
  **and** a cancellation. **Fails before** the funnel exists (the two writers can diverge), **passes
  after**.
  Additionally `jvm-unit` — `app/src/test/java/com/grayzone/app/service/AccessibilityConfigTest.kt`
  for P9, asserting `res/xml/accessibility_service_config.xml` declares no
  `flagRetrieveInteractiveWindows`; today that fix is grep-enforced and a re-added flag would pass
  CI silently. Note the config file is one of the uncommitted modifications, which is exactly when
  such a guard earns its keep.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1) — `evidence/prior-fix-watchlist.md`'s coverage
  table and `specs/002-fix-timeout-accessibility-bugs/tasks.md:110` were both read directly in this
  session; the unchecked box and its explanatory note are quoted verbatim above.
- **Build scope**: everyday-build
- **Constitution**: V (fixes shipped without verification), II (the arm-on-write invariant is
  duplicated across two call sites instead of owned once)
- **Affected surfaces**: overlaps **F-201** by construction — F-201 is the coverage gap stated
  against the app's guarantees, this one is the same gap stated against the prior feature's contract
  clauses, and they share one of the two proposed fixes. T034 should treat
  `SessionAlarmInvariantTest.kt` as a single work item satisfying both, and must not count the fix
  twice.
- **Observed against**: `working-tree @ 557a963 (uncommitted: OverlayService.kt,
  GrayzoneAccessibilityService.kt, accessibility_service_config.xml, AndroidManifest.xml — four of
  the files carrying the six unverified fixes were modified with no test change)`
- **Disposition**: `verified`

### F-203 — No test ever loads the blocklists, so the Bloom-filter path that does the actual DNS filtering — and the `catch (Throwable)` that silently disables it — is completely unexercised  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/service/vpn/BlocklistDecisionTest.kt` (9 tests)
  and `app/src/test/java/com/grayzone/app/BlocklistManagerTest.kt` (1 test), versus
  `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` → `load()` (lines 143–160),
  `loadFilter()` (227–233), `loadAbusedTlds()` (235–241), and `isBlocked()`'s post-load branch
  (lines 216–222)
- **Issue**: `BlocklistManager.isBlocked` short-circuits at `:216` — `if (!isLoaded) return false` —
  and `isLoaded` is set only inside `load(context)`, which requires an Android `Context`, reads
  `R.raw.adblock_bloom` / `R.raw.adult_bloom` / `R.raw.abused_tlds`, and does its work on a raw
  `Thread`. No JVM test can call it, and `app/src/androidTest/` contains only three Room classes, so
  no instrumented test does either. Every one of the 10 assertions across the two test classes
  therefore runs with `isLoaded == false` and exercises only the pre-load safety net: the 30
  hardcoded DoH endpoints (`:34–78`), 25 high-confidence ad domains (`:94–120`) and 13 adult domains
  (`:122–136`). Those tests are well-written — `BlocklistDecisionTest` covers the parent-zone walk,
  case and trailing-dot normalisation, and correctly asserts that partial matches like
  `notdoubleclick.net` and `doubleclick.net.evil.example` do **not** block. But 68 hardcoded strings
  are not the product. The shipped filters are ~6 MB and ~4 MB (`:12–14`), and lines 216–222 —
  `abusedTlds.blocks()` plus both `matchesFilter()` calls — are the feature. They have never
  executed under test. The sharpest part is the failure path: `load()`'s catch was **widened from
  `Exception` to `Throwable` in the uncommitted diff** (`:151`), and its body sets `adFilter = null`,
  `adultFilter = null`, `abusedTlds = EMPTY`, `isLoaded = false` and `loadStarted.set(false)`, then
  `Log.e`s. After that, `isBlocked` returns `false` for every domain outside the 68 hardcoded ones,
  the VPN notification still says filtering is active, and no test in the repository would notice.
  That is the "silently disables protection" shape — the production defect belongs to RU-06, but the
  reason it can ship undetected is this coverage gap.
- **Trigger condition**:
  1. `rg -n "BlocklistManager.load|isLoaded" app/src/test app/src/androidTest` → no matches;
     `isLoaded` is `false` for the entire suite.
  2. Add `if (true) throw RuntimeException("simulated")` as the first line of
     `BlocklistManager.load()`'s `Thread` body.
  3. Run `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` —
     306 tests, 0 failures.
  4. On device: install, enable the VPN, and browse. Ads and adult domains outside the 68 hardcoded
     entries resolve normally while the notification still claims filtering is on.
- **Blast radius**:
  - *Callers*: `BlocklistManager.load` is called once, from `AdBlockVpnService.kt:110`;
    `isBlocked`/`isDoHBypass` are called from the VPN packet loop. `AdBlockVpnService` has no tests,
    so the whole call chain is untested end to end.
  - *Process death*: `isLoaded` and both filters are in-memory `object` state that dies with the
    process; the VPN service must re-`load()` on restart, and `loadStarted` is an `AtomicBoolean`
    that gates it. No test covers the reload-after-restart sequence.
  - *Config change*: none.
  - *Foreground/background*: the load runs on a raw `Thread` started from the service; if the process
    is killed mid-load the filters are half-null. `evidence/concurrency-inventory.md` already flags
    this thread as uncancellable. Untested.
  - *Cross-process*: none — `AdBlockVpnService` has no `android:process`, so `BlocklistManager` is a
    single default-process singleton.
  - *Persistence*: none — the filters are read-only assets.
  - *Release-only*: **relevant.** `R.raw` resources are subject to `isShrinkResources = true`
    (`app/build.gradle.kts:59`). Nothing verifies the three raw assets survive shrinking, and no test
    runs against the release variant. If they were stripped, `load()` would throw, the widened
    `catch (Throwable)` would swallow it, and filtering would be silently off **in release only**.
    This is the single strongest reason to add the instrumented test below.
- **Fix** (minimal): add one instrumented test rather than restructuring `BlocklistManager`. The
  class already has everything needed — `load(context)` is public and `isLoaded` is a public
  `@Volatile val` with a private setter — so an androidTest can call `load()`, poll `isLoaded`, and
  then assert real filtering. Optionally add an `internal` overload taking pre-built
  `GrayzoneBloomFilter` instances so the post-load branch can also be reached from a JVM test
  without assets; `GrayzoneBloomFilter` is already independently tested (`BloomFilterTest`, 10
  tests), so this is a seam, not new logic.
- **Rewrite justification**: n/a.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/vpn/BlocklistLoadTest.kt`, calling
  `BlocklistManager.load(context)`, waiting for `isLoaded`, then asserting (a) `isLoaded` is `true`,
  proving all three `R.raw` assets are present and parseable, and (b) a domain that exists **only**
  in the Bloom filter — not in any hardcoded list — is blocked, proving the post-load branch runs.
  **Fails before** if any asset is missing, corrupted, or shrunk away, **passes after**.
  **Cannot be executed here** — no device or emulator.
- **Existing test conflict**: none — the 10 existing assertions are all pre-load and remain valid.
  One naming correction belongs with this fix: `BlocklistManagerTest`'s single test duplicates a
  strict subset of `BlocklistDecisionTest`'s ad and adult assertions (`doubleclick.net`,
  `adservice.google.com`, `pornhub.com`, `xvideos.com`) and should be folded into it; and
  `IntegrationTest.testDnsQueryBlockingFlow` (`:13–22`) is named for a blocking *decision* it never
  makes — it calls `DnsPacketHelper.createSinkholeResponse` directly and never consults
  `BlocklistManager` — so it should be renamed `testSinkholeResponseConstruction`.
- **Verification**: `needs-verification` (evidence tier T4) for the release-shrinking sub-claim;
  `confirmed` (T1) for the coverage gap itself, which follows mechanically from
  `if (!isLoaded) return false` at `:216` and the absence of any `load()` call in either test source
  set.
  - *Missing*: a device or emulator to run the proposed `BlocklistLoadTest` against both the debug
    and the minified release variant.
  - *Procedure*: build a release APK, install on any API 29+ device, enable the VPN, and check
    logcat for `Loaded ads bloom filter` / `Loaded adult bloom filter` (`:231`) — noting these are
    `Log.d` calls and therefore **currently suppressed by F-064**, so F-064 must be fixed first or
    the check must be done with a temporary `Log.e`. Then resolve a Bloom-only domain and observe
    whether it is sinkholed.
  - *Severity effect*: if the release build's assets survive and filtering works → stays `Medium`
    (a real gap, no active defect). If shrinking strips a raw asset → the production finding rises
    to `Critical` under the "silently disables protection" rule and belongs to RU-06/RU-08, not
    here; this finding would stay `Medium` as the coverage gap that hid it.
- **Build scope**: everyday-build (the gap); the shrinking sub-claim is `release-only`
- **Constitution**: V (the DNS-filtering guarantee is asserted only against a hardcoded fallback
  list)
- **Affected surfaces**: `BlocklistManagerTest.kt` (duplicate, fold in); `IntegrationTest.kt:13–43`
  (three DNS tests named for flows they do not exercise); the widened `catch (Throwable)` at
  `BlocklistManager.kt:151` is owned by RU-06 and is cited, not re-filed.
- **Observed against**: `working-tree @ 557a963 (uncommitted: BlocklistManager.kt — the widened
  catch is part of the uncommitted diff; app/build.gradle.kts)`
- **Disposition**: `verified`

### F-204 — The 24-hour VPN hold is tested only as arithmetic: its persistence and its expiry receiver have no tests, and the receiver is the exact place the hold can be short-circuited  [Medium]

- **Review unit**: RU-10
- **Location**: `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockTest.kt` (6 tests, all
  against pure functions), versus the two untested collaborators
  `app/src/main/java/com/grayzone/app/service/vpn/VpnHoldLockStore.kt` →
  `markEnabledIfAbsent()` (lines 24–34), `read()` (14–19), `clear()` (36–43), and
  `app/src/main/java/com/grayzone/app/service/vpn/VpnHoldExpiryScheduler.kt` →
  `arm()` (32–52), `rearmFromPrefs()` (63–76), `VpnHoldExpiryReceiver.onReceive()` (83–94)
- **Issue**: `VpnHoldLockTest` is a good test class and covers the right property —
  `remaining_prefersElapsedClock` (`:14–21`) and `remaining_fallsBackToWallAfterReboot` (`:23–32`)
  pin the monotonic-clock defense that the lead auditor identifies as the pattern the session engine
  should have copied. But it stops at `VpnHoldLock`'s pure math. The two classes that make the hold
  real have **zero** tests, and the gap lands exactly on the short-circuit. `VpnHoldExpiryScheduler`
  arms an `RTC_WAKEUP` alarm at `expiresAtWallMs` (`:39`, `:41`) — a **wall-clock** trigger — and
  `VpnHoldExpiryReceiver.onReceive` (`:83–94`) does no validation whatsoever before sending
  `ACTION_HOLD_EXPIRED` to `AdBlockVpnService`: it checks only that the intent action matches, logs,
  and stops the tunnel. So advancing the device clock past `startedWallMs + HOLD_MS` fires the alarm
  and disables the VPN, even though `VpnHoldLock.isHoldActive(state, nowWall, nowElapsed)` — which
  the scheduler itself calls in `rearmFromPrefs` at `:71` and which would reject the tampered
  clock — is never consulted on the delivery path. That is F-021, filed by RU-06. What this unit
  owns is why it survived: **the defense was implemented, documented, unit-tested at the arithmetic
  layer, and then not applied at the one boundary where it decides anything.** The second half of the
  gap is `VpnHoldLockStore`, and it is a missed opportunity rather than a hard problem —
  `read(prefs)`, `markEnabledIfAbsent(prefs, nowWallMs, nowElapsedMs)` and `clear(prefs)` all take a
  plain `SharedPreferences` and injectable clocks, deliberately shaped for testing, and the suite
  already ships `FakeSharedPreferences`. `markEnabledIfAbsent`'s whole contract is the comment at
  `:21–23` — *"restarts/reconnects must not reset the 24h"* — which is the anti-bypass property of
  the feature, and it is asserted nowhere despite being testable in four lines today with no build
  change.
- **Trigger condition**:
  1. `rg -n "VpnHoldLockStore|VpnHoldExpiryScheduler|VpnHoldExpiryReceiver" app/src/test app/src/androidTest`
     → no matches.
  2. Read `VpnHoldExpiryReceiver.onReceive` (`VpnHoldExpiryScheduler.kt:83–94`) — no
     `VpnHoldLock.isHoldActive` call, no state read at all.
  3. Delete the `if (read(prefs).isTracked) return` guard at `VpnHoldLockStore.kt:29` — the
     restart-resets-the-hold defect — and run
     `./gradlew :app:testDebugUnitTest "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
     306 tests, 0 failures.
  4. On device (no root, no adb): enable the VPN, then Settings → Date & time → disable automatic →
     advance the clock 25 hours. Observe the VPN is auto-disabled well before 24 real hours elapse.
- **Blast radius**:
  - *Callers*: `VpnHoldLockStore` is read by `AdBlockVpnService`, `BootReceiver`, `MainActivity` and
    `ui/VpnScreen.kt`; `VpnHoldExpiryScheduler.arm`/`cancel`/`rearmFromPrefs` are called from
    `AdBlockVpnService` and `BootReceiver`. None of those five callers has a test.
  - *Process death*: `rearmFromPrefs()` (`:63–76`) exists specifically for service restart and boot,
    and correctly consults `isHoldActive` — the one place the defense *is* applied on a delivery
    path. It has no test, so the asymmetry between it and the receiver is undocumented and easy to
    "fix" in the wrong direction.
  - *Config change*: none.
  - *Foreground/background*: the alarm is `setExactAndAllowWhileIdle` with a documented fallback to
    `setAndAllowWhileIdle` when `canScheduleExactAlarms()` is false (`:37–42`) — a Doze-correct
    implementation with proper `SecurityException` handling, and one of the audit's positive
    examples. The inexact fallback means the hold can end *late*, which is harmless, but nothing
    tests the branch selection.
  - *Cross-process*: none — all of this is default-process.
  - *Persistence*: `VPN_HOLD_STARTED_WALL` and `VPN_HOLD_STARTED_ELAPSED` (`PrefsKeys.kt`) are the
    persisted anchors. `markEnabledIfAbsent`'s idempotence is the property that stops a
    reconnect from resetting the 24 hours, and it is untested — a regression there would silently
    convert the hold into "24 hours from the last reconnect", which a user can trigger at will.
  - *Release-only*: `VpnHoldExpiryReceiver` is manifest-declared, so AGP generates its keep rule
    (`evidence/r8.md` §F2). No release-variant test exists, but no release-specific risk is
    identified.
- **Fix** (minimal): one guard and two test files, none of which needs a build change.
  1. **Production (owned by RU-06/F-021, cited here)**: `VpnHoldExpiryReceiver.onReceive` must read
     `VpnHoldLockStore.read(context)` and return early unless
     `VpnHoldLock.isHoldActive(state, now, nowElapsed)` is `false` — i.e. re-validate against the
     monotonic clock before honouring a wall-clock alarm, exactly as `rearmFromPrefs` already does at
     `:71`. Three lines, reusing functions that exist and are already tested.
  2. **Test (owned here)**: cover `VpnHoldLockStore` with the existing `FakeSharedPreferences`.
- **Rewrite justification**: n/a.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldLockStoreTest.kt` with
  `` `markEnabledIfAbsent does not reset an existing hold` `` (call it twice with different clocks
  and assert the persisted anchors are unchanged — **fails before** if the `isTracked` guard at
  `:29` is removed, **passes after**), `` `markEnabledIfAbsent records both clocks on a fresh hold` ``,
  and `` `clear removes both anchors so the hold reads untracked` ``.
  Second: `app/src/test/java/com/grayzone/app/service/vpn/VpnHoldExpiryDecisionTest.kt` asserting the
  pure predicate the receiver should consult — that a state whose elapsed-realtime age is under 24 h
  must **not** be treated as expired even when its wall-clock age exceeds 24 h. **Fails before**
  F-021's fix (no such check exists on the delivery path), **passes after**.
- **Existing test conflict**: none. `VpnHoldLockTest`'s six tests remain valid unchanged — the
  proposed work is purely additive, which is worth noting because it is the only guarantee in this
  audit where the existing tests are aimed at the right property and simply stop short.
- **Verification**: `confirmed` (evidence tier T1) for the coverage gap and for the receiver's
  missing validation — `VpnHoldExpiryScheduler.kt:83–94`, `VpnHoldLockStore.kt:24–43` and
  `VpnHoldLockTest.kt` were all read directly. The device step in the trigger list corroborates
  F-021 and is that finding's to verify, not this one's.
- **Build scope**: everyday-build
- **Constitution**: II (the dual-clock defense is implemented once and applied inconsistently), V
- **Affected surfaces**: **F-021** (RU-06) owns the receiver's missing re-validation; this finding
  owns the test gap and the `VpnHoldLockStore` coverage. The same asymmetry — a correct monotonic
  defense that a delivery path fails to consult — is the theme linking F-021 to C5/F-110 and
  C6/F-111, so the tests proposed here and in **F-190** should be designed together.
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt — a caller of both
  untested classes, modified with no test change)`
- **Disposition**: `verified`

---

## Coverage verdict on the three required areas

**1. Coverage against the app's stated guarantees.**

| Guarantee | Decision layer | Delivery layer | Verdict |
|---|---|---|---|
| Monitored apps get a friction/lockout screen | **Strong** — 58 engine tests + 9 `OverlayPresentationTest` + 16 `LockGuardLogicTest` | **None** — `OverlayService` and `GrayzoneAccessibilityService` have zero tests | **F-201** (High) |
| Lockouts cannot be escaped | **Present but wrong** — the two predicates are pinned to wall-clock semantics and asserted "exhaustive" | **None** | **F-190**, **F-191**, **F-192** |
| DNS filtering blocks ad and adult domains | **Strong for components** — 58 tests across bloom, zone-matching, normalisation, packet parsing | **None for the real blocklist** — `isLoaded` is `false` in every test | **F-203** (Medium) |
| The anti-tamper backup survives a data wipe | **None** | **None** | **F-200** (High) — zero tests of any kind |
| The 24-hour VPN hold cannot be short-circuited | **Good** — 6 `VpnHoldLockTest` tests, correctly aimed at the monotonic clock | **None** — store and expiry receiver untested | **F-204** (Medium) |

The shape is consistent across all five: **pure Kotlin is well tested, and everything that touches
the Android framework is not tested at all.** 306 green tests, and not one of them constructs an
Android component. That is also the boundary every Critical in this audit sits on.

**2. Does `MigrationTest` exercise populated data?** **Partly — the premise needs correcting.** It is
not run over an empty database: `MigrationTest.kt:31` inserts a fully-populated `usage_events` row at
version 1 before migrating, so the migration does run against a non-empty table. What is missing is
the assertion — the row is never read back (**F-194**), so the test proves the schema survived and is
silent on whether the data did. Separately, its four visible assertions use Kotlin `assert()`, which
is compiled out on Android, so they never execute (**F-193**); the index property they check is
nonetheless covered, by Room's `runMigrationsAndValidate` against
`app/schemas/com.grayzone.app.data.UsageDatabase/2.json`. Net: the migration is better covered than
it looks and the data is worse covered than it looks.

**3. Tests asserting stale or defective behaviour.** Four found, and they are the most valuable
results in this unit:

| Finding | Test | Behaviour it locks in | Fix it blocks |
|---|---|---|---|
| **F-191** | `resuming a paused session after the clock jumps forward grants full remaining` | A +30-day wall-clock jump must grant a fresh session and re-anchor the lockout | **F-110 (C5)**, **F-111 (C6)** — Critical |
| **F-190** | `SessionStatePredicateTest`, 8 tests | `isLockedOut` / `hasActiveSession` are bare wall-clock comparisons, declared "exhaustive" | **F-110 (C5)**, **F-111 (C6)** — Critical |
| **F-197** | `repository updates are reflected in status flow` | `isFullyProtected` is reachable — it is not, in production | Removal of the orphaned `updateAccessibilityStatus` |
| **F-199** | `SetupRequirementsTest:37,45`; `DateUtilsTest:26,27` | Two production functions look "used" to a reference count | **F-072**, **F-073** dead-code removal |

**F-192** is the fifth of the family and the inverse case: a test whose name asserts clock-skew
coverage that its body does not contain — it blocks nothing, which is precisely why it is dangerous.


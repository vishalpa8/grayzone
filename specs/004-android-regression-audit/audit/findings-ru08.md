# Findings — RU-08: build configuration, R8 keep rules, manifest (T025)

**Review unit**: RU-08 | **Task**: T025 | **ID block**: F-125 … F-144
**Files owned**: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
(plus the manifest-referenced XML config it declares: `res/xml/accessibility_service_config.xml`,
`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`)
**Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md) · **Register**: [`findings.md`](./findings.md)

**IDs used**: F-125 … F-142, 18 findings (F-143 and F-144 unused).
**Counts**: **0 Critical · 2 High · 6 Medium · 10 Low**.
No Critical is claimed from this unit. F-125 is the one candidate and is deliberately held at High
because its harmful link is unproven — the same restraint applied to C4 and F-206. Its
*Severity effect* states the exact device outcome that would raise it.

## Scope boundaries observed

- **F-064 (RU-01) is cited, not re-filed.** The uncommitted `debug { isDebuggable = false }` block at
  `app/build.gradle.kts:69–78` is the root cause of `BuildConfig.DEBUG == false` in *both* variants
  (confirmed from `app/build/generated/source/buildConfig/{debug,release}/…/BuildConfig.java:7,9`).
  The fix edits a file this unit owns; the finding remains F-064. The build-side change RU-08
  recommends is **not** reverting the block — its signing/installability rationale is sound — but
  adding a dedicated field, e.g. `buildConfigField("boolean", "VERBOSE_LOGGING", "true")` in `debug`
  and `"false"` in `release`, and re-pointing `GrayzoneLogger`'s gate at it.
- **F-250 … F-252** (repository-root config) are T025a's. This unit touched no root-level file.
- **F-265 … F-267** (`README.md` / `INSTALL.md` drift) are T025b's.
- **Inexact alarms / no `SCHEDULE_EXACT_ALARM` declaration** (`evidence/lint.md` §E2) is a manifest
  permission gap but is already assigned to T018/T019. Cited under coverage area 3, not re-filed.
- The `BatteryLife` warning at `OnboardingScreen.kt:209` (`evidence/lint.md` §E5) was assigned to this
  task and is filed as **F-135**.

## Evidence tiers used

`T1` = generated build artifact or tool output read directly in this session. All R8 claims below cite
`app/build/outputs/mapping/release/` from the 2026-07-31 `assembleRelease` recorded in
[`evidence/r8.md`](./evidence/r8.md); no rebuild was performed. `configuration.txt` is the **merged**
configuration R8 actually applied, so it settles whether `proguard-android-optimize.txt` supplies a
rule the project file omits.

---

### F-125 — Gson keep block omits the TypeToken rule, and R8 has already removed four live `TypeToken` subclasses from the release build  [High]

- **Review unit**: RU-08
- **Location**: `app/proguard-rules.pro` → the `── Gson / TypeToken ──` block (lines 5–18); consequences
  at `app/src/main/java/com/grayzone/app/service/OverlayService.kt` → `showOverlay()` (line 852),
  `data/StreakManager.kt` → `getAchievements()` (line 132) and `unlockAchievement()` (line 146),
  `ui/CustomPromptsSheet.kt` → `CustomPromptsSheet()` (line 42)
- **Issue**: Gson resolves generic types through the anonymous-subclass trick
  `object : TypeToken<T>() {}.type`, which requires the anonymous subclass to survive shrinking with
  its generic supertype signature intact. Gson's own R8 guidance requires **three** rules; this file
  has two of them (`-keepattributes Signature`, `-keep class com.google.gson.** { *; }`) and omits
  `-keep class * extends com.google.gson.reflect.TypeToken`. `-keepattributes Signature` is not a
  substitute — it preserves signatures on classes R8 *keeps*, and says nothing about whether R8 keeps
  the anonymous subclass at all. Gson 2.10.1 ships no consumer rules to cover the gap.
  The release build shows the gap is not theoretical: **four of the five `TypeToken` anonymous
  subclasses in the app were removed**, and every one of the four is in live code. The one survivor is
  the only one whose type argument has its own explicit `-keep` rule. If any removal changed runtime
  type resolution, each affected read fails *silently* — all four call sites wrap `fromJson` in a catch
  that returns an empty collection — so the user sees their custom friction prompts and their unlocked
  achievements quietly disappear in the release APK only, with no error anywhere.
- **Trigger condition**:
  1. `./gradlew :app:assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  2. `rg --no-ignore -N 'type\$1' app/build/outputs/mapping/release/usage.txt` — lists the removals:
     `StreakManager$getAchievements$existing$type$1`,
     `StreakManager$unlockAchievement$existing$type$1`,
     `OverlayService$showOverlay$customPrompts$type$1`,
     `CustomPromptsSheetKt$CustomPromptsSheet$initialPrompts$1$type$1`.
  3. `rg --no-ignore -N 'type\$1 ->' app/build/outputs/mapping/release/mapping.txt` — returns only
     `com.grayzone.app.data.ScheduleManager$getScheduleRules$type$1 -> z3.h:`. The other four are
     absent from the mapping entirely, so they were shrunk away rather than horizontally merged.
  4. Install the release APK, write a custom friction prompt in Settings → Custom prompts, then open a
     monitored app and observe whether the friction overlay shows the custom prompt.
- **Blast radius**:
  - *Callers*: `OverlayService.showOverlay()` (`:852`, `TypeToken<List<String>>`, reached on every
    friction overlay); `StreakManager.getAchievements()` (`:132`, `TypeToken<Map<String,String>>`,
    called from `StreakCard.kt:33,40`); `StreakManager.unlockAchievement()` (`:146`,
    `TypeToken<MutableMap<String,String>>`, called ten times from `checkAndUnlockAchievements()`
    `:162–171`); `CustomPromptsSheet()` (`:42`, called from `SettingsScreen.kt:254`). All four verified
    live by direct search over `app/src`, not from the reference index.
  - *Process death*: none — the defect is in the shipped dex, identical across process lifetimes.
  - *Config change*: none.
  - *Foreground/background*: the `OverlayService` site runs while the app is backgrounded, so a
    failure there is invisible to anyone watching the UI.
  - *Cross-process*: none — all four sites are default-process. `:a11y` holds no Gson call.
  - *Persistence*: `unlockAchievement` **writes back** what it read
    (`prefs.edit().putString(ACHIEVEMENTS_JSON, gson.toJson(existing))`, `:153`). If the read silently
    yields an empty map, the next unlock persists a map containing only the new id — the same
    read-empty-then-overwrite data-loss shape as C3, so a broken read destroys achievement history
    rather than merely failing to display it.
  - *Release-only*: yes, entirely. `isMinifyEnabled` is false for `debug`, so no amount of everyday
    testing can surface this, and `lint { checkReleaseBuilds = false }` (F-130) removes the one gate
    that inspects the release variant.
- **Fix** (minimal): add the missing rule to the Gson block in `app/proguard-rules.pro`:
  `-keep class * extends com.google.gson.reflect.TypeToken` and
  `-keepclassmembers class * extends com.google.gson.reflect.TypeToken { <fields>; }`. Then re-run
  `assembleRelease` and re-check step 3 — all five subclasses must appear in `mapping.txt`.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt`, reading `app/proguard-rules.pro`
  from the project directory and asserting it contains a rule matching
  `-keep class \* extends com\.google\.gson\.reflect\.TypeToken`. Fails before the fix, passes after.
  This is a static-content assertion, so it runs on the JVM with no device; it guards against the rule
  being dropped again, which is the failure mode F-127 shows this file is prone to.
- **Existing test conflict**: none. `StreakManagerTest.kt:40,50,80,81,87` exercises `getAchievements()`
  on the JVM against unminified classes, so it passes both before and after — which is precisely why
  the existing suite cannot detect this.
- **Verification**: `needs-verification` (evidence tier `T1` for the removals, `T4` for the runtime
  consequence)
  - *Missing*: the four removals are **confirmed** from `usage.txt` and `mapping.txt`. What is not
    established is whether R8 rewrote the call sites into something equivalent (in which case the
    removal is a benign optimization) or degraded type resolution. Deciding this statically would
    require disassembling the release dex; deciding it behaviourally requires a device.
  - *Procedure*: on any API 29+ device, install `app/build/outputs/apk/release/app-release.apk`.
    (a) Settings → Custom prompts, add the prompt `verify-125`, then open a monitored app past its
    limit and check whether `verify-125` appears on the friction overlay. (b) Trigger the
    `first_resist` achievement (block one session) and confirm the Home streak card shows it, then
    trigger a second achievement and confirm the first is still shown. Repeat both against a debug
    build to establish the contrast.
  - *Severity effect*: custom prompts absent or achievements lost in release but present in debug →
    **Critical** (silent protection/UX degradation plus achievement data loss, release-only, per the
    Grayzone calibration). Both behave identically in release and debug → **Low**, the rule is still
    required as hardening against the next R8 version but no user is affected today. Held at **High**
    rather than Critical because the harmful link is unproven, following the restraint precedent set
    for C4 and F-206.
- **Build scope**: release-only
- **Constitution**: III (a failure path that resolves to a silent empty collection), V (release
  behaviour asserted only where an artifact proves it)
- **Affected surfaces**: `ScheduleManager.kt:36` uses the same idiom and is the one site that
  survived — because `-keep class com.grayzone.app.data.ScheduleRule { *; }` keeps its type argument.
  That asymmetry is the strongest available evidence for the mechanism, and it means the existing
  `ScheduleRule`/`Achievement` keep rules are load-bearing in a way `evidence/r8.md` §F3 did not
  realise: they are incidentally protecting TypeToken resolution, not just field names.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-126 — `SourceFile` / `LineNumberTable` are absent from the merged R8 configuration, so release stack traces carry no line numbers  [Medium]

- **Review unit**: RU-08
- **Location**: `app/proguard-rules.pro` → whole file (lines 1–33); merged result at
  `app/build/outputs/mapping/release/configuration.txt:25,128,129,240`
- **Issue**: The release build keeps only `AnnotationDefault`, `Signature` and `*Annotation*`. Neither
  `-keepattributes SourceFile,LineNumberTable` nor `-renamesourcefileattribute SourceFile` appears
  anywhere in the merged configuration, which settles the question the project file alone could not:
  `proguard-android-optimize.txt` does **not** supply them either. Every release stack trace therefore
  reports `Unknown Source` with no line number. The audit's Principle III tier is 21 error-handling
  fixes whose entire purpose is that a field failure leaves a diagnosable trace; without line numbers
  a retraced trace identifies the method but not which of several I/O boundaries inside it threw. The
  consequence is not a user-visible failure — it is that the Tier-2 work buys materially less than it
  appears to, which is why this is Medium rather than High.
- **Trigger condition**:
  1. `rg -n --no-ignore "keepattributes|renamesourcefileattribute" app/build/outputs/mapping/release/configuration.txt`
  2. Observe four matches, none mentioning `SourceFile` or `LineNumberTable`.
  3. Any release-build crash or `GrayzoneLogger.e(..., throwable)` output then prints frames as
     `at z3.i.a(Unknown Source)`.
- **Blast radius**:
  - *Callers*: not a call graph. Affects every `catch` that logs a throwable — the highest-value ones
    being `AdBlockVpnService.startVpn()` `:240–244`, `GrayzoneAccessibilityService.onServiceConnected()`
    `:58–62` and `onAccessibilityEvent()` `:107–111`, `StateBackupManager.readBackup()` `:195` and
    `writeBackup()` `:177`, plus the 21 sites enumerated in
    [`evidence/error-handling-inventory.md`](./evidence/error-handling-inventory.md).
  - *Process death*: worse after process death — an uncaught throw in `:a11y` leaves only a logcat
    trace, and that trace is the unnumbered one.
  - *Config change*: none.
  - *Foreground/background*: the services that fail most interestingly fail in the background, where
    the trace is the only evidence that exists.
  - *Cross-process*: `:a11y` frames are affected identically; the process name in logcat still
    distinguishes them.
  - *Persistence*: none.
  - *Release-only*: yes. Debug builds are unminified and retain full debug info, so the gap is
    invisible until something fails on a user's device.
- **Fix** (minimal): append to `app/proguard-rules.pro`:
  `-keepattributes SourceFile,LineNumberTable` and `-renamesourcefileattribute SourceFile`. The second
  line is required alongside the first — it replaces the real filename with the literal `SourceFile`
  so no source layout is leaked while line numbers stay retraceable via
  `app/build/outputs/mapping/release/mapping.txt`.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` (same file as F-125), asserting the
  file contains both `-keepattributes SourceFile,LineNumberTable` and
  `-renamesourcefileattribute SourceFile`. Fails before, passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing. `configuration.txt` is the merged configuration R8 applied, so the absence is
    established from tool output rather than by reading the project file alone. This upgrades the
    prerequisite propagated from T030/T021, which could only assert the absence from
    `proguard-rules.pro`.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: release-only
- **Constitution**: III (diagnosability is the stated purpose of the error-handling requirement), V
- **Affected surfaces**: sequencing, not code — **T034 should schedule this before the Principle III
  tier**, since it is a two-line change that determines how much every fix in that tier is worth in
  the field. It also compounds with F-064 (`d()`/`i()` dead in both variants) and F-065 (`w()`/`e()`
  ungated and leaking package names in release): the app logs the sensitive material, drops the
  diagnostic material, and cannot place what remains.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-127 — Keep rule names a class that no longer exists, and keep rules fail silently  [Low]

- **Review unit**: RU-08
- **Location**: `app/proguard-rules.pro` → `── Accessibility Service ──` block, line 29
  (`-keep class com.grayzone.app.service.AppAccessibilityService { *; }`)
- **Issue**: The class was renamed to `GrayzoneAccessibilityService`; the keep rule was not updated.
  R8 does not warn about a `-keep` rule that matches nothing, so the file has been asserting
  protection for a non-existent class for as long as the rename has existed. No release risk today —
  `evidence/r8.md` §F2 confirms the real class is retained by AGP's manifest-generated rules, and
  §F1 records **0 occurrences in both `seeds.txt` and `usage.txt`**, meaning the rule is inert rather
  than mis-targeted. The finding is that this file is silently unverified, which is the same defect
  class that produced F-125 in a case where it *does* matter.
- **Trigger condition**:
  1. `rg -n "AppAccessibilityService" app/src` → zero matches.
  2. `rg -n --no-ignore "AppAccessibilityService" app/build/outputs/mapping/release/seeds.txt app/build/outputs/mapping/release/usage.txt`
     → zero matches in either file.
  3. `rg -n "AppAccessibilityService" app/proguard-rules.pro` → one match at line 29.
- **Blast radius**:
  - *Callers*: none — the named class does not exist. The live class
    `com.grayzone.app.service.GrayzoneAccessibilityService` is present in `seeds.txt` (`evidence/r8.md`
    §F2) and is bound by the framework via the manifest, never by app code.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: the class the rule *meant* to name is the sole class in `:a11y`. Had AGP not
    generated a keep rule for it, the stale rule would have let R8 strip the app's only foreground-app
    detector — so the near-miss is worth recording even though the outcome is benign.
  - *Persistence*: none.
  - *Release-only*: yes; the rule only participates in minified builds.
- **Fix** (minimal): delete line 29. Do **not** rename it to `GrayzoneAccessibilityService` — that
  would recreate the redundancy F-128 describes, since AGP already generates the rule.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` (same file as F-125/F-126),
  asserting that every `-keep class com.grayzone.app.…` rule in `app/proguard-rules.pro` names a class
  whose corresponding `.kt` source exists under `app/src/main/java`. Fails before the fix on line 29,
  passes after, and catches the next rename.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: release-only
- **Constitution**: I (configuration asserting something untrue), V
- **Affected surfaces**: `evidence/r8.md` §F1 records this as a lead; this finding is its disposition.
  The same silent-failure property is what allowed F-125 to go unnoticed.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-128 — `OverlayService` and `MainActivity` keep rules are redundant with AGP's generated rules and disable member shrinking on the two largest classes  [Low]

- **Review unit**: RU-08
- **Location**: `app/proguard-rules.pro` lines 30 and 33
  (`-keep class com.grayzone.app.service.OverlayService { *; }`,
  `-keep class com.grayzone.app.MainActivity { *; }`)
- **Issue**: Both classes are declared in `AndroidManifest.xml`, and AGP generates keep rules for
  every manifest component — `evidence/r8.md` §F2 confirms all seven components appear in
  `seeds.txt`. The hand-written rules add nothing to *class* retention. They do add something
  unintended: `{ *; }` keeps **every member, unshrunk and unobfuscated**, on the app's largest class
  (`OverlayService.kt`, ~1200 lines) and its Activity. AGP's generated rules keep the class and its
  framework entry points, leaving private helpers eligible for shrinking and renaming; `{ *; }`
  removes both. The cost is dex size and a small reduction in obfuscation of exactly the class that
  implements the anti-bypass logic. The comment on line 32, `# MainActivity (used in PendingIntent)`,
  also states a rationale that is not the reason the class survives: a `PendingIntent` holds a
  `ComponentName`, which the manifest rule already covers.
- **Trigger condition**:
  1. `rg -n --no-ignore "^com\.grayzone\.app\.(MainActivity|service\.OverlayService)$" app/build/outputs/mapping/release/seeds.txt`
     → both present.
  2. Delete lines 28–33 of `app/proguard-rules.pro` and re-run
     `./gradlew :app:assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  3. Re-run step 1 → both still present, proving the rules were redundant. Diff `usage.txt` against
     the `evidence/r8.md` §F5 baseline (220 whole-class `com.grayzone.app.*` removals, `seeds.txt`
     3,091 lines) to quantify what the `{ *; }` rules were pinning.
- **Blast radius**:
  - *Callers*: `MainActivity` is referenced from `OverlayService.kt:743,1199` and
    `AdBlockVpnService.kt:609` (notification `PendingIntent`s) and launched by the system via the
    `LAUNCHER` filter. `OverlayService` is started from `MainActivity.kt:257`,
    `GrayzoneAccessibilityService.kt:93`, and `BootReceiver.kt:40`. All are `ComponentName`/class-literal
    references, which R8 tracks; none is reflective.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: the `GrayzoneAccessibilityService.kt:93` start crosses from `:a11y` into the
    default process, but by `Intent` with a class literal, so it does not need a reflective keep.
  - *Persistence*: none.
  - *Release-only*: yes. Removing the rules changes only the minified output, so step 3 above is the
    required gate before this is actioned.
- **Fix** (minimal): delete lines 30 and 33 together with the now-empty section headers (28, 32), then
  run step 3 to confirm `seeds.txt` still lists both classes before accepting the change. Keep the
  change in the same commit as F-127's deletion, since both edit the same two blocks.
- **Rewrite justification**: not a rewrite — two line deletions.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ProguardRulesTest.kt` (same file as F-125–F-127),
  asserting `app/proguard-rules.pro` contains no `-keep` rule naming a component that is also declared
  in `app/src/main/AndroidManifest.xml`. Fails before on both lines, passes after.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier `T1` for the redundancy, `T2` for the removal
  being safe)
  - *Missing*: an `assembleRelease` with the two rules deleted. The redundancy itself is confirmed
    from `seeds.txt`; what is unproven is that nothing else in the build depended on the member-level
    retention as a side effect.
  - *Procedure*: steps 2–3 above. No device needed. If `seeds.txt` loses either class, or `usage.txt`
    newly removes a member reached only reflectively, restore the rule and close this as a non-finding.
  - *Severity effect*: stays **Low** either way — this is cleanliness plus a marginal size and
    obfuscation gain, with no user-visible behaviour attached.
- **Build scope**: release-only
- **Constitution**: I (redundant configuration), II (the same retention decision expressed twice)
- **Affected surfaces**: `evidence/r8.md` §F2 records this as a lead; this is its disposition. The
  deletion must not extend to the `ScheduleRule` / `Achievement` rules on lines 17–18 — F-125 shows
  those are load-bearing for `TypeToken` resolution, not merely for field names.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-129 — No `testOptions { unitTests.isReturnDefaultValues = true }`, so the F-031 logging fix will break two existing tests when it lands  [Medium]

- **Review unit**: RU-08
- **Location**: `app/build.gradle.kts` → the `android { }` block (lines 22–112); the `testOptions`
  block is absent entirely, confirmed by `rg -n "testOptions|isReturnDefaultValues|unitTests" *.kts`
  returning zero matches across the repository
- **Issue**: Without this flag, every call into an unmocked `android.jar` stub throws
  `RuntimeException: Method … not mocked` in JVM unit tests. That is the default and often the right
  default, but it makes an entire class of test unwritable here: any code path that touches
  `android.util.Log` — which is what `GrayzoneLogger` ultimately calls — cannot be exercised on the
  JVM. The Principle III tier consists of 21 fixes whose common shape is *"replace a silent catch with
  a logged one"*, so the fixes systematically add the one call the test environment refuses to serve.
  The consequence is concrete and already scheduled: `DnsResolverPoolTest.kt` currently passes because
  the three socket-close catches at `DnsResolverPool.kt:107,112,123` are empty. **F-031 replaces those
  empty catches with logging, and both existing tests in that file will then throw.** A fix that turns
  a green suite red on landing will either be reverted or land with the tests deleted, and deleting
  them removes the only JVM coverage of the resolver pool.
- **Trigger condition**:
  1. `rg -n "testOptions" app/build.gradle.kts` → no match.
  2. Apply F-031: replace the three empty catches in
     `app/src/main/java/com/grayzone/app/service/vpn/DnsResolverPool.kt` with `GrayzoneLogger.w(...)`.
  3. `./gradlew :app:testDebugUnitTest --tests "*DnsResolverPoolTest*" "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`
  4. Both tests in `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt` fail with
     `Method w in android.util.Log not mocked`.
- **Blast radius**:
  - *Callers*: not a call graph. The blocked test surface is every class that logs — most directly
    `DnsResolverPool` (F-031), and prospectively the `Test to add` entries attached to F-205
    (`startForeground` guards), F-206 (`appScope` handler), and C1/C2/C4.
  - *Process death*: none — build configuration only.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: no. The flag affects `testDebugUnitTest` only and has no effect on either shipped
    artifact.
- **Fix** (minimal): add inside `android { }`, next to the existing `sourceSets` block:
  ```kotlin
  testOptions {
      unitTests.isReturnDefaultValues = true
  }
  ```
  Ship it **in the same commit as F-031**, not after it, or step 3 above lands as a broken build.
- **Test to add**: `jvm-unit` — no new file. The assertion is that
  `app/src/test/java/com/grayzone/app/service/vpn/DnsResolverPoolTest.kt`'s two existing tests still
  pass once F-031's logging is in place. Before this change they throw `Method … not mocked`; after
  it, `Log.w` returns `0` and both pass. That is the behavioural before/after this finding owns.
- **Existing test conflict**: `DnsResolverPoolTest.kt` — both tests. They require no source change,
  but they *do* require this build change to survive F-031. Recorded here so T034 sequences the two
  together rather than tiering them independently.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing for the absence of the flag. The knock-on failure is a standard, documented
    property of the Android Gradle unit-test runtime rather than a runtime claim about this app.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: III (the fixes this blocks are the Principle III fixes), VI
- **Affected surfaces**: reconciliation 2 in [`findings.md`](./findings.md) already records the F-031
  dependency; this finding is its build-side owner. Corroborated independently by T030 and T021.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts)`
- **Disposition**: `verified`

---

### F-130 — `lint { checkReleaseBuilds = false }` removes the only lint gate that inspects the release variant, and nothing replaces it  [Medium]

- **Review unit**: RU-08
- **Location**: `app/build.gradle.kts` → `android { lint { } }` (lines 102–108) — **added by the
  uncommitted diff**
- **Issue**: The stated rationale is accurate as far as it goes: `lintVital` resolves
  `com.android.tools.lint:lint-gradle` from `dl.google.com` at task time, and that resolution does
  fail behind a TLS-inspecting proxy, blocking a signed build for a reason unrelated to the APK. The
  defect is the scope of the remedy. `lintVital` is the **only** task that runs lint against the
  release variant, and the release variant is the only one with `isMinifyEnabled = true` and
  `isShrinkResources = true`. Disabling it therefore removes the sole automated check positioned to
  notice release-only regressions — which is exactly the category F-125 falls into, and exactly what
  `evidence/r8.md` §F5 says T069 must watch for. The comment offers `./gradlew lintDebug` as the
  substitute, but nothing enforces it: there is **no `.github` directory and no CI of any kind** in
  this repository (verified: `Test-Path .github` → false), and no release checklist references lint.
  So the mitigation is an unenforced instruction inside a code comment, and the practical effect is
  that lint has left the release path entirely.
- **Trigger condition**:
  1. `./gradlew :app:assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` completes
     without running any lint task.
  2. Introduce a release-only defect — for example delete
     `-keep class com.grayzone.app.data.ScheduleRule { *; }` from `app/proguard-rules.pro`.
  3. Re-run step 1 → still `BUILD SUCCESSFUL`, with no warning, and `usage.txt` silently gains the
     removals.
- **Blast radius**:
  - *Callers*: not a call graph. Checks now unrun on the release variant include the `NewApi` family
    (coverage area 6 below), `ObsoleteSdkInt` (`evidence/lint.md` §E3), `BatteryLife` (§E5, F-135),
    and `HardcodedText` (§E7).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: yes by definition — the setting has no effect on the debug variant, which is why
    the everyday build looks unaffected.
- **Fix** (minimal): keep the network problem solved but restore the gate. Replace the block with:
  ```kotlin
  lint {
      checkReleaseBuilds = true
      abortOnError = false      // never block a signed build on a lint finding
      htmlReport = true
  }
  ```
  and, if the download itself is the blocker on the maintainer's network, run release builds with
  `--offline` once the lint artifact is in the Gradle cache. If the download proves genuinely
  unavoidable, the minimum acceptable alternative is to keep `checkReleaseBuilds = false` **and** add
  a documented, mandatory `./gradlew :app:lintRelease` step to the release procedure — but the comment
  as written is not that, because it names `lintDebug`, which does not analyse the minified variant.
- **Rewrite justification**: not a rewrite — the block is retained and its keys changed.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/BuildConfigurationTest.kt`, reading `app/build.gradle.kts`
  and asserting that if `checkReleaseBuilds = false` is present then `abortOnError` is also present,
  i.e. the gate was traded deliberately rather than dropped. Fails before the fix, passes after. A
  static-content assertion, so it needs no Gradle invocation.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing for the configuration facts or the absence of CI. Whether the maintainer's
    network actually requires the workaround is not something this audit can or needs to settle — the
    finding is about the unreplaced gate, not the rationale.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: release-only
- **Constitution**: V (a verification gate removed without a replacement), VI
- **Affected surfaces**: `evidence/r8.md` §F5 names this setting as the reason a release-only
  regression would go uncaught, and F-125 is a live instance of that category. F-142 compounds it:
  two further lint checks are suppressed inline via `tools:ignore` in the manifest.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/build.gradle.kts)`
- **Disposition**: `verified`

---

### F-131 — `WRITE_EXTERNAL_STORAGE` is never requested at runtime, so the anti-tamper backup is silently inert on API 29 — the declared `minSdk`  [High]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` lines 29–39 (the storage-permission block and its
  explanatory comment) with `app/build.gradle.kts:28` (`minSdk = 29`); consequence at
  `app/src/main/java/com/grayzone/app/data/StateBackupManager.kt` → `hasAllFilesAccess()`
  (lines 137–144)
- **Issue**: The manifest comment states the design explicitly: *"On Android 10 (API 29)
  MANAGE_EXTERNAL_STORAGE does not exist, so legacy WRITE_EXTERNAL_STORAGE +
  requestLegacyExternalStorage is used instead."* The permission is declared with
  `android:maxSdkVersion="29"`, and `hasAllFilesAccess()` implements the branch —
  `ContextCompat.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PERMISSION_GRANTED` below API 30.
  But `WRITE_EXTERNAL_STORAGE` is a **dangerous** permission, so declaring it grants nothing; it must
  be requested at runtime, and the app never does. A search over `app/src/main` for
  `requestPermissions`, `ActivityCompat.request` and `permissionLauncher` finds exactly one runtime
  request, `POST_NOTIFICATIONS` at `MainActivity.kt:253`. The Android 10 path is additionally skipped
  by the only prompt that exists: `requestAllFilesAccessOnce()` returns immediately at
  `MainActivity.kt:88` when `SDK_INT < R`. So on API 29, `hasAllFilesAccess()` is permanently `false`,
  which makes `writeBackup()` return at its guard (`:161–164`), `readBackup()` return `null` (`:187`),
  and `backupExists()` return `false` (`:152`). The entire anti-tamper state backup — the feature
  whose stated purpose is that locks survive a user clearing app data — is off on Android 10,
  permanently, and the only trace is an `i()`-level log that F-064 has already established is a no-op
  in every variant. No user is ever told. The friction and lockout core still works; what is silently
  lost is wipe resistance, on the app's own declared minimum platform.
- **Trigger condition**:
  1. `rg -n "requestPermissions|ActivityCompat\.request|permissionLauncher" app/src/main` → one
     result, `POST_NOTIFICATIONS`.
  2. Install on an API 29 device or AVD and complete onboarding.
  3. Set a lock, then Settings → Apps → Grayzone → Storage → **Clear data**.
  4. Relaunch. Expected per the documented design: locks restored from `/sdcard/.grayzone/`. Actual:
     no backup was ever written, so all locks are gone.
  5. Confirm the cause: `adb shell dumpsys package com.grayzone.app | grep WRITE_EXTERNAL` shows the
     permission `granted=false`.
- **Blast radius**:
  - *Callers*: `hasAllFilesAccess()` is called from `StateBackupManager.backupExists()` `:152`,
    `writeBackup()` `:161`, `readBackup()` `:187`, and `MainActivity.onResume()` `:47`. Downstream,
    `restoreIfWiped()` is called from `GrayzoneApplication.onCreate()` `:64` and
    `MainActivity.onResume()` `:48`.
  - *Process death*: no change — a missing permission grant does not vary with process lifetime.
  - *Config change*: none.
  - *Foreground/background*: the fix must request the permission from an `Activity`, so it can only
    run in the foreground; `GrayzoneApplication.onCreate()` cannot prompt. That constrains the fix to
    `MainActivity`, where `requestAllFilesAccessOnce()` already lives.
  - *Cross-process*: none — `:a11y` touches no storage.
  - *Persistence*: **yes.** This is the write side of the app's only durable anti-tamper store. On
    API 29 no snapshot is ever written, so there is nothing to restore and nothing to poison — which
    incidentally means F-060's unrecoverable-boot-loop chain cannot fire on API 29 either.
  - *Release-only*: no. Identical in both variants.
- **Fix** (minimal): extend `requestAllFilesAccessOnce()` in `MainActivity.kt` so the `SDK_INT < R`
  branch, instead of returning, requests `WRITE_EXTERNAL_STORAGE` through the existing
  `rememberLauncherForActivityResult(RequestPermission())` launcher, reusing the same
  `ALL_FILES_PROMPTED` prompt-once flag. Two conditions on the fix: set the flag only after a
  successful launch (the same one-line ordering defect F-209 identifies for the API 30+ branch), and
  do not wire it from `GrayzoneApplication`, which has no Activity to prompt from.
- **Rewrite justification**: not a rewrite — one branch inside an existing function.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/data/StateBackupPermissionTest.kt`, asserting that the
  permission-gate decision returns `NEEDS_LEGACY_REQUEST` for `sdkInt = 29` with the permission
  ungranted, and `NEEDS_MANAGE_ALL_FILES` for `sdkInt = 30`. This requires extracting the branch in
  `hasAllFilesAccess()` into a pure `fun requiredGrant(sdkInt: Int, legacyGranted: Boolean)` so it is
  testable without a `Context` — a small, justified extraction, since the current form takes a
  `Context` purely to reach `checkSelfPermission`. Fails before the fix (no such decision exists),
  passes after.
- **Existing test conflict**: none. No current test covers `StateBackupManager`'s permission gate.
- **Verification**: `needs-verification` (evidence tier `T1` for the missing request, `T4` for the
  end-to-end wipe behaviour)
  - *Missing*: an API 29 device or AVD. The static half is confirmed — the permission is declared, the
    branch reads it, and no code requests it.
  - *Procedure*: steps 2–5 above on an API 29 AVD. Repeat on API 30+ to establish the contrast: there
    the `MANAGE_EXTERNAL_STORAGE` prompt does fire and the backup is written.
  - *Severity effect*: confirmed as described → **High**, and Critical specifically on API 29 by the
    Grayzone calibration, since an advertised anti-bypass protection is off with no notification. Held
    at High overall because it is confined to one platform version, the primary friction and lockout
    protections are unaffected, and what is lost is wipe resistance rather than locking itself — the
    same reasoning that held F-209 at High. If the maintainer instead decides Android 10 is not
    supported, the correct resolution is to raise `minSdk` to 30 and delete manifest lines 32–33 and
    38–39, at which point this becomes **Low** (dead declaration) and merges with F-132.
- **Build scope**: everyday-build
- **Constitution**: III (a permission failure that resolves to a silent skip), IV (the manifest
  comment documents an invariant the code does not implement)
- **Affected surfaces**: `MainActivity.requestAllFilesAccessOnce()` `:87–110` is the single place both
  this fix and F-209's ordering fix land, so they should be made in one pass. `README.md` /
  `INSTALL.md` accuracy about Android 10 support is T025b's (F-265 … F-267) — flagged to that task,
  not filed here.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-132 — `READ_EXTERNAL_STORAGE` is declared and never referenced anywhere in the app  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` lines 40–41
  (`<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />`)
- **Issue**: A search over `app/src` for `READ_EXTERNAL_STORAGE` returns the manifest line and one
  doc-comment mention at `StateBackupManager.kt:41`; there is no `checkSelfPermission`, no runtime
  request, and no code path conditioned on it. `hasAllFilesAccess()` checks only
  `WRITE_EXTERNAL_STORAGE` below API 30 and `Environment.isExternalStorageManager()` above. The
  `maxSdkVersion="32"` bound is also inconsistent with its neighbour on line 39, which caps
  `WRITE_EXTERNAL_STORAGE` at 29 — the two halves of the same legacy-storage story disagree about
  which platform range they serve. The user-visible effect is that Android 11 and 12 devices see the
  app requesting a storage-read capability it does not use, on an app that advertises minimal
  permission scope.
- **Trigger condition**:
  1. `rg -n "READ_EXTERNAL_STORAGE" app/src` → manifest line 40 and the comment at
     `StateBackupManager.kt:41` only.
  2. `adb shell dumpsys package com.grayzone.app | grep READ_EXTERNAL` on an API 31 device shows the
     permission listed as requested and never granted.
- **Blast radius**:
  - *Callers*: none. Enumerated by the search in step 1 — the only occurrences are the declaration
    and a comment.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none — despite appearances, it participates in no storage path.
  - *Release-only*: no.
- **Fix** (minimal): delete lines 40–41. Sequence it after F-131: if the maintainer instead makes the
  API-29 legacy path work, re-check whether the *read* side of `/sdcard/.grayzone/` needs it on
  API 29 — `Environment.getExternalStorageDirectory()` reads do require it there — in which case the
  bound must be `maxSdkVersion="29"`, matching line 39, not 32.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt`, parsing
  `app/src/main/AndroidManifest.xml` and asserting that the declared permission set equals an explicit
  expected list held in the test. Fails before the fix, passes after, and forces any future permission
  addition to be a deliberate, reviewed edit. This same test carries F-133 and F-134.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: I (declared capability the app does not use)
- **Affected surfaces**: F-131 (same legacy-storage design), F-133 and F-134 (same class of unused
  declaration, same fix commit and same test).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-133 — `PACKAGE_USAGE_STATS` is declared with a lint suppression and never used, contradicting the manifest's own minimal-scope rationale  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` line 24
- **Issue**: There is no `UsageStatsManager` anywhere in the app. A search over `app/src` for
  `UsageStatsManager`, `PACKAGE_USAGE_STATS` and `ACTION_USAGE_ACCESS_SETTINGS` returns only the
  manifest line and two comments — `GrayzoneAccessibilityService.kt:20` and `AndroidManifest.xml:76`
  — both of which say the same thing: usage-stats polling was **replaced** by accessibility events.
  The permission survived the migration. It is not a harmless leftover, for two reasons specific to
  this app. First, it is a protected permission whose declaration is only accepted because line 24
  carries `tools:ignore="ProtectedPermissions"` — a suppression that hides the check designed to
  question exactly this. Second, it contradicts the design rationale stated fourteen lines above it,
  where the manifest explains that `QUERY_ALL_PACKAGES` was deliberately avoided because it *"reads
  like spyware to Play Protect / on-device scanners and is a Play-policy blocker."*
  `PACKAGE_USAGE_STATS` attracts the same scrutiny and buys nothing. For an app whose pitch is that
  its access is narrow and auditable, an unused surveillance-adjacent permission is a trust cost with
  no offsetting function.
- **Trigger condition**:
  1. `rg -n "UsageStatsManager|PACKAGE_USAGE_STATS|ACTION_USAGE_ACCESS_SETTINGS" app/src` → the
     manifest line plus two comments, no code.
  2. On any device, Settings → Apps → Special app access → Usage access, and observe Grayzone listed.
- **Blast radius**:
  - *Callers*: none. Enumerated by the search in step 1.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none — `:a11y` performs the foreground-app detection this permission used to
    serve, through `AccessibilityEvent.getPackageName()`, which needs no permission.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): delete line 24 in full, including the `tools:ignore="ProtectedPermissions"`
  attribute and the `xmlns:tools` declaration it carries (see F-142 on that declaration's placement).
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` (same file as F-132),
  asserting the declared permission set matches the expected list and, specifically, that the manifest
  contains no `tools:ignore="ProtectedPermissions"` attribute. Fails before, passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: I (declared capability the app does not use), IV (contradicts the stated
  package-visibility rationale in the same file)
- **Affected surfaces**: F-132, F-134 (same fix commit, same test). F-142 covers the `tools:ignore`
  suppressions as a pattern. `README.md`'s permission list is T025b's.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-134 — `ACCESS_NETWORK_STATE` is declared and never used  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` line 26
- **Issue**: The app contains no `ConnectivityManager`, no `NetworkCapabilities`, no `NetworkCallback`
  and no `getActiveNetwork` — a search over `app/src/main` for `ACCESS_NETWORK_STATE` and
  `ConnectivityManager` returns the manifest line and nothing else. The VPN and DNS paths use raw
  `DatagramSocket`/`InetAddress` (`DnsResolverPool.kt:9,10,92`, `AdBlockVpnService.kt:551`), which
  need `INTERNET` and not this. Notably, the *absence* of network monitoring is itself relevant to the
  VPN findings — nothing observes connectivity changes to re-establish the tunnel — but that is
  RU-06's question, and the permission would only become load-bearing if such a fix were written. As
  shipped it is an unused declaration.
- **Trigger condition**:
  1. `rg -n "ACCESS_NETWORK_STATE|ConnectivityManager|NetworkCapabilities|NetworkCallback" app/src/main`
     → the manifest line only.
- **Blast radius**:
  - *Callers*: none. Enumerated by the search in step 1.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): delete line 26 — **unless** RU-06 adopts a connectivity-callback fix for the VPN
  reconnect paths, in which case keep it and add the callback. Action this after RU-06's disposition
  is known, not before.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` (same file as F-132 and
  F-133). Fails before, passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: I
- **Affected surfaces**: F-132, F-133 (same fix commit, same test). RU-06's VPN reconnect findings
  gate whether the deletion is correct.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-135 — `BatteryLife`: the only battery-exemption mechanism wired up is the one the app's own code documents as insufficient  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` line 21
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); use sites at
  `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt:209` (the site lint flags) and
  `ui/HomeScreen.kt:172`
- **Issue**: Lint's `BatteryLife` check flags `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  because Play restricts whitelist-exemption requests to a narrow set of acceptable use cases; a
  foreground-service screen-time app is a plausible but not automatic fit, so this is real
  distribution risk rather than a style nit. The more interesting half is functional. The app's own
  `OemBatteryHelper.kt` doc comment states that this generic exemption is not enough — *"Xiaomi, Oppo,
  Vivo, Realme, OnePlus aggressively kill background services even when battery optimization is
  disabled"* — and that file implements per-OEM handling for eight manufacturers. `OemBatteryHelper`
  has **zero callers** (triple-corroborated in `evidence/r8.md` §F4, the reference index, and direct
  search). So the app carries the Play-policy exposure of the weaker mechanism while the stronger one
  it already wrote sits unwired. Rated Low on its own terms — a declared permission plus a lint
  warning, with no defect in the code that uses it; the consequential half is the unwired helper,
  already filed as the E6 lead.
- **Trigger condition**:
  1. `./gradlew :app:lintDebug "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  2. Open `app/build/reports/lint-results-debug.html` and find `BatteryLife` at
     `OnboardingScreen.kt:209`, as recorded in `evidence/lint.md` §E5.
- **Blast radius**:
  - *Callers*: two, both UI — `OnboardingScreen.kt:209` (onboarding step) and `HomeScreen.kt:172`
    (Home remediation card). Enumerated by direct search for
    `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` over `app/src`.
  - *Process death*: none directly; the exemption's *purpose* is to reduce process death, which is
    why the gap matters.
  - *Config change*: none.
  - *Foreground/background*: the intent is launched from the foreground only, which is correct.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: no — but with F-130 in place, `lintVital` never re-checks this on the release
    variant, so the warning is visible only to someone who runs `lintDebug` by hand.
- **Fix** (minimal): keep the permission and both call sites — they are the correct first step — and
  suppress the warning narrowly and with a reason via `@Suppress("BatteryLife")` on the enclosing
  composable at `OnboardingScreen.kt:209`, rather than leaving an unexplained standing warning.
  Record the Play "acceptable use" justification in the same comment, since it is needed at submission.
- **Test to add**: `jvm-unit` — no new test is meaningful for a lint policy warning; the appropriate
  gate is the lint run itself, which F-130's
  `app/src/test/java/com/grayzone/app/build/BuildConfigurationTest.kt` restores by asserting lint is
  not disabled without `abortOnError`. Inventing a separate test here would be a placeholder.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1` — recorded in `evidence/lint.md` §E5 from a lint
  run, and both call sites verified by direct search)
  - *Missing*: nothing for the warning itself. Whether Play would reject the listing is outside this
    audit's scope.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: I
- **Affected surfaces**: the `OemBatteryHelper` E6 lead in [`findings.md`](./findings.md) (owned by
  T021/T052/T059) is the substantive half of this story; this finding closes §E5's assignment and
  points at it. F-130 determines whether this warning is ever seen again.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-136 — `BootReceiver` is exported and two of its three filter actions are unprotected, so any installed app can drive its whole body  [Medium]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` lines 139–149 (`<receiver .service.BootReceiver
  android:exported="true">`); body at
  `app/src/main/java/com/grayzone/app/service/BootReceiver.kt` → `onReceive()` (lines 28–78)
- **Issue**: The receiver is exported and filters three actions. `android.intent.action.BOOT_COMPLETED`
  is a protected broadcast and cannot be forged. `android.intent.action.QUICKBOOT_POWERON` and
  `com.htc.intent.action.QUICKBOOT_POWERON` are OEM extensions and are **not** in AOSP's
  protected-broadcast list, so any installed app — or `adb shell` — can deliver them by name to this
  component. `onReceive` does allowlist the action (`:29–30`), but an allowlist offers no protection
  when two of the three allowed actions are themselves forgeable; it prevents an *unrelated* action
  from entering the body, not a spoofed boot. The body then runs in full on an attacker's schedule:
  a `startForegroundService(OverlayService)` attempt, a `SharedPreferences` read, and — on the branch
  at `:52–57` — a write that clears `VPN_ENABLED` and wipes the 24-hour hold record via
  `VpnHoldLockStore.clear(prefs)`. That write is not a hold bypass, because it is guarded by
  `!VpnHoldLock.isHoldActive(...)` and `VpnHoldLock` is correctly monotonic-clock based (verified at
  `VpnHoldLock.kt:25–37`: a spoofed broadcast mid-uptime takes the elapsed-clock branch and computes
  the hold correctly). What the exposure does yield is an unprivileged app able to trigger repeated
  foreground-service start attempts and prefs writes at will, on a device where each failed attempt
  produces only a message-only `Log.e` (`:43`, `:75`). The correct posture is that the component
  should not be reachable from outside the app at all.
- **Trigger condition**:
  1. Install Grayzone on any API 29+ device.
  2. `adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -n com.grayzone.app/.service.BootReceiver`
  3. `adb logcat -s GrayzoneBootReceiver` shows `Boot detected (android.intent.action.QUICKBOOT_POWERON); restarting Grayzone services`.
  4. Repeat with `com.htc.intent.action.QUICKBOOT_POWERON` and observe the same. Repeat from a
     second, unprivileged app using `sendBroadcast` with the explicit `ComponentName` to confirm the
     `adb` result is not shell-privilege-specific.
- **Blast radius**:
  - *Callers*: the platform, at boot. In-app there are none — `rg -n "BootReceiver" app/src` finds the
    class declaration, the manifest entry, and two doc-comment references (`MainActivity.kt:117`,
    `AndroidManifest.xml:135`); no code sends this broadcast.
  - *Process death*: the receiver exists precisely to recover from process death, so it must stay
    functional at real boot. A fix that breaks real-boot delivery is worse than the defect.
  - *Config change*: none.
  - *Foreground/background*: always background. At API 31+ a spoofed (non-exempt) delivery means
    `startForegroundService` at `:40` throws `ForegroundServiceStartNotAllowedException`, which is
    caught at `:42`; a genuine `BOOT_COMPLETED` carries the exemption and succeeds. So the attacker
    cannot use this to start a service, only to make the app try and log a failure.
  - *Cross-process*: none — `BootReceiver` runs in the default process; `:a11y` is not involved.
  - *Persistence*: **yes** — `:53` writes `VPN_ENABLED=false` and `:54` clears the hold record. Both
    are reachable only when the hold has genuinely expired, so the write is state-correct; the defect
    is that an external party chooses when it happens.
  - *Release-only*: no.
- **Fix** (minimal): set `android:exported="false"` on the receiver. The system is exempt from the
  export check when delivering its own broadcasts, and this repository contains its own proof:
  `androidx.work.impl.background.systemalarm.RescheduleReceiver` is merged into this app's manifest
  with `android:exported="false"` and a `BOOT_COMPLETED` intent-filter
  (`app/build/intermediates/merged_manifests/release/AndroidManifest.xml:249–259`) — Google's own
  library, shipping in this build. The same reasoning is corroborated in-app by
  `GrayzoneAccessibilityService`, which the framework binds successfully despite `exported="false"`
  (manifest `:81`).
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt`, parsing
  `app/src/main/AndroidManifest.xml` and asserting that every `<receiver>` and `<service>` with
  `android:exported="true"` either declares an `android:permission` or appears in an explicit
  `EXPECTED_PUBLIC_COMPONENTS` allowlist held in the test. Fails before the fix on `BootReceiver`
  (exported, no permission, not allowlisted), passes after. Also carries F-138.
- **Existing test conflict**: none. `rg -n "BootReceiver" app/src/test app/src/androidTest` returns no
  matches — the receiver has no test at all, which is itself part of the 6-of-10-untested prior-fix
  gap recorded in `evidence/prior-fix-watchlist.md`.
- **Verification**: `needs-verification` (evidence tier `T1` for the manifest facts and the WorkManager
  precedent, `T4` for the OEM delivery behaviour)
  - *Missing*: a device, for two questions. (a) Whether a spoofed `QUICKBOOT_POWERON` is in fact
    delivered — this depends on the platform's protected-broadcast list, which AOSP does not include
    these actions in but an OEM build may. (b) Whether an OEM that emits `QUICKBOOT_POWERON` still
    reaches a non-exported receiver after the fix; the OEM's sender is the system, so it should, but
    that is the one claim here that is not settled by in-repo evidence.
  - *Procedure*: steps 2–4 above to establish (a). For (b), on an HTC/Xiaomi/OnePlus device with the
    fix applied, reboot and confirm `Boot detected` still appears in logcat; if it does not, keep
    `exported="true"` and drop the two OEM actions from the filter instead, relying on
    `BOOT_COMPLETED` alone, which cannot be spoofed.
  - *Severity effect*: spoofed delivery confirmed → **Medium**, as filed. Delivery blocked by the
    platform on the tested devices → **Low**, since the export is then unnecessary surface rather than
    an exploitable one. It does not reach High in either case: no protection is disabled and no state
    is corrupted, which is what separates this from the Critical/High tier.
- **Build scope**: everyday-build
- **Constitution**: I (an export that is not required by any caller)
- **Affected surfaces**: `BootReceiver.kt`'s body belongs to RU-04, and two defects there are visible
  from this angle and should be routed to it rather than filed here: the message-only `Log.e` at `:43`
  and `:75` (Principle III, thin logging) and the raw `Log.d` at `:32`, which bypasses
  `GrayzoneLogger` and so is *not* subject to the F-064 gate — meaning the one place the app logs
  unconditionally in release is the boot path. F-137 covers a second defect in the same manifest
  element.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-137 — The boot receiver's intent-filter declares `category.DEFAULT`, an activity idiom that means nothing on a broadcast  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` line 147
  (`<category android:name="android.intent.category.DEFAULT" />` inside the `BootReceiver` filter)
- **Issue**: `category.DEFAULT` exists so that an *activity* can be started by an implicit intent
  through `Context.startActivity`. Broadcast intent resolution does not use it: a filter matches when
  it contains every category present on the incoming intent, and system boot broadcasts carry none, so
  a category in the filter is inert. The line is a copy of the activity-filter idiom and states a
  requirement that does not exist. Filed separately from F-136 because the root cause is different —
  F-136 is an incorrect `exported` value, this is a meaningless element — and merging them would
  produce one record describing two defects, which the contract prohibits.
- **Trigger condition**:
  1. Read `app/src/main/AndroidManifest.xml:143–148`; the category sits alongside three actions.
  2. `adb shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -n com.grayzone.app/.service.BootReceiver`
     with no `-c` argument still reaches the receiver (see F-136 step 3), demonstrating that no
     category is required for a match.
- **Blast radius**:
  - *Callers*: none — the element affects no code path. The only other intent-filters in the app are
    `MainActivity`'s `MAIN`/`LAUNCHER` (manifest `:59–62`, where `LAUNCHER` is correct and required),
    `GrayzoneAccessibilityService`'s (`:84–86`) and `AdBlockVpnService`'s (`:129–131`), neither of
    which declares a category.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): delete line 147. Make the edit in the same commit as F-136 so the receiver
  element is touched once.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt` (same file as F-136),
  asserting that no `<receiver>` intent-filter declares a `<category>`. Fails before, passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1` — the element is present in both the source and
  the merged manifest at
  `app/build/intermediates/merged_manifests/release/AndroidManifest.xml:166`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: I
- **Affected surfaces**: F-136 (same manifest element, same commit).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-138 — `MainActivity` is exported with default task affinity and launch mode, so any app can insert itself into or hijack Grayzone's task  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` lines 55–63 (`<activity .MainActivity
  android:exported="true">`)
- **Issue**: `exported="true"` with a `LAUNCHER` filter is correct and required — this is the app's
  launcher entry. What is missing is any hardening around it. The activity declares no
  `android:launchMode` (so it is `standard`), no `android:taskAffinity` (so its affinity defaults to
  the package name, `com.grayzone.app`), and no `android:excludeFromRecents`. A malicious app can
  declare `android:taskAffinity="com.grayzone.app"` on one of its own activities and, with
  `allowTaskReparenting` or an appropriate launch mode, have its screen surface inside what the user
  believes is Grayzone's task — the task-affinity hijack pattern. For an ordinary app this is
  boilerplate hardening; for this one it is slightly more pointed, because the app's threat model
  explicitly includes a user motivated to bypass it, and the screens being impersonated are the ones
  that grant and revoke protection. The second, smaller exposure is that any app can `startActivity`
  `MainActivity` and thereby drive `onResume()` (`:42–53`), which runs
  `StateBackupManager.restoreIfWiped` — a main-thread AES-GCM decrypt plus Gson parse plus `commit()`
  — on every resume, and can burn F-209's one-shot `ALL_FILES_PROMPTED` flag.
  **Recorded as examined and clean**: hostile *extras* handling. `MainActivity` reads no
  `Intent` extras at all, overrides no `onNewIntent`, and passes `savedInstanceState` straight to
  `super`. A garbage or malformed launch intent therefore has no parsing surface to attack, and the
  audit found no missing-extras defect on this component.
- **Trigger condition**:
  1. `adb shell am start -n com.grayzone.app/.MainActivity --es garbage "$(python -c 'print("A"*100000)')" --ei n -1`
     → launches normally; no crash, no behaviour change, confirming the extras surface is clean.
  2. Build a second app with `<activity android:taskAffinity="com.grayzone.app"
     android:allowTaskReparenting="true">`, launch Grayzone, then launch the second app, then return
     to Grayzone from Recents, and observe which activity is presented.
- **Blast radius**:
  - *Callers*: launched by the system from the launcher; referenced in-app only as a
    `PendingIntent`/`Intent` target — `OverlayService.kt:743,1199` and `AdBlockVpnService.kt:609`
    (notification content intents). No app code calls `startActivity(MainActivity)` directly.
  - *Process death*: none introduced here.
  - *Config change*: `launchMode`/`taskAffinity` changes interact with rotation and Recents
    behaviour, so RU-07's configuration work (T027) must re-check any change made here.
  - *Foreground/background*: the hijack is only meaningful when the user brings the task forward from
    Recents, i.e. a background-to-foreground transition.
  - *Cross-process*: none.
  - *Persistence*: indirect — an external launch reaches `onResume()`'s `restoreIfWiped` write path
    and F-209's flag write. Both are owned elsewhere; noted so the manifest fix is not credited with
    resolving them.
  - *Release-only*: no.
- **Fix** (minimal): add `android:launchMode="singleTask"` and `android:taskAffinity=""` to the
  `<activity>` element. The empty affinity puts the activity in a task no other package can name,
  which is the standard mitigation and requires no code change.
- **Rewrite justification**: not a rewrite — two attributes on an existing element.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestExportSurfaceTest.kt` (same file as F-136 and
  F-137), asserting that every exported `<activity>` declares both `android:taskAffinity` and
  `android:launchMode`. Fails before the fix, passes after.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier `T1` for the manifest attributes, `T4` for the
  hijack)
  - *Missing*: a device and a purpose-built second app. The manifest facts are confirmed; the
    exploitability of task-affinity hijack varies by Android version, and the behaviour was
    substantially tightened after Android 11.
  - *Procedure*: step 2 above, on API 29 and on API 34, to bracket the supported range.
  - *Severity effect*: hijack reproducible on a supported version → **Medium** (a spoofable
    permission-granting screen in an anti-bypass app). Not reproducible → stays **Low**, as
    defence-in-depth hardening on an otherwise clean exported surface.
- **Build scope**: everyday-build
- **Constitution**: none (hardening, not a violated principle)
- **Affected surfaces**: F-209 (the one-shot prompt flag reachable from an external launch) and the
  `restoreIfWiped`-on-`onResume` main-thread lead owned by T020 — both are amplified by this export
  but neither is caused by it, so neither is re-filed here.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-139 — `allowBackup="false"` makes `fullBackupContent="@xml/backup_rules"` dead configuration, leaving one live and one unreachable copy of the same exclusion list  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` lines 45–48 (`android:allowBackup="false"`,
  `android:fullBackupContent="@xml/backup_rules"`, `android:dataExtractionRules="@xml/data_extraction_rules"`);
  `app/src/main/res/xml/backup_rules.xml` (7 lines);
  `app/src/main/res/xml/data_extraction_rules.xml` (15 lines)
- **Issue**: Three declarations describe one policy and they are not all live.
  `android:fullBackupContent` is consulted only for Auto Backup on API ≤ 30, and only when
  `allowBackup` is `true`; with `allowBackup="false"` it is never read on any platform the app
  supports. `backup_rules.xml` is therefore an unreachable file. On API 31+ the live declaration is
  `dataExtractionRules`, and specifically its `<device-transfer>` section — `allowBackup` gates cloud
  backup but not device-to-device transfer, so those four `<exclude>` entries are the ones actually
  doing work. The result is that the same four paths (`GrayzonePrefs.xml`, `grayzone_usage.db` and
  its `-shm`/`-wal` siblings) are listed three times across two files, one copy of which has no
  effect. The maintenance hazard is concrete and directional: a contributor adding a second
  preferences file or a second database must update the two live lists, and the presence of a
  plausible-looking dead file makes it easy to update that one and believe the job is done — at which
  point the new file is silently included in device-to-device transfer.
  **This is the answer to coverage area 4**: the configuration is redundant, not self-contradictory.
  `allowBackup="false"` and the `dataExtractionRules` exclusions point the same way, and
  `data_extraction_rules.xml` must **not** be deleted as "redundant with `allowBackup=false`" — that
  would be the harmful misreading this finding exists to prevent.
- **Trigger condition**:
  1. Read manifest lines 45–48 — `allowBackup="false"` and `fullBackupContent` are declared together.
  2. `adb shell bmgr backupnow com.grayzone.app` → refused, the app does not participate in backup,
     confirming `backup_rules.xml` is never consulted.
  3. Add a hypothetical second prefs file to `backup_rules.xml` only, then perform a device-to-device
     transfer, and observe it is transferred despite the exclusion.
- **Blast radius**:
  - *Callers*: no code reads these files; they are consumed by the platform's backup agent. The paths
    they name are owned by `PrefsKeys.PREFS_NAME` (`GrayzonePrefs`) and `UsageDatabase`
    (`grayzone_usage.db`).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none — backup and transfer run outside the app's lifecycle entirely.
  - *Cross-process*: none.
  - *Persistence*: **yes, by definition.** These files decide whether the app's entire persisted
    state leaves the device. The exclusions are the correct choice for an anti-tamper app: the
    external `/sdcard/.grayzone/` snapshot is keyed to `ANDROID_ID` (`StateBackupManager.androidId`),
    so a transferred copy would not decrypt on the new device anyway, and transferring prefs without
    it would move locks without their tamper anchor.
  - *Release-only*: no.
- **Fix** (minimal): delete `android:fullBackupContent="@xml/backup_rules"` from manifest line 47 and
  delete `app/src/main/res/xml/backup_rules.xml`. Keep `android:allowBackup="false"` and keep
  `android:dataExtractionRules="@xml/data_extraction_rules"` unchanged.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/BackupRulesTest.kt`, asserting that when
  `android:allowBackup="false"` is present in `app/src/main/AndroidManifest.xml` the manifest declares
  no `android:fullBackupContent`, and that every `path` excluded in `data_extraction_rules.xml`'s
  `<device-transfer>` block is also excluded in its `<cloud-backup>` block. Fails before the fix on
  the first assertion, passes after; the second assertion guards the drift this finding describes.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier `T1` for the declarations, `T2` for the
  platform semantics)
  - *Missing*: device confirmation of the load-bearing sub-claim — that `<device-transfer>` is honoured
    while `allowBackup="false"`. Everything else is settled from the files. This matters because the
    fix depends on it: if `allowBackup="false"` also suppressed device transfer, then
    `data_extraction_rules.xml` would be dead too and the correct fix would be larger.
  - *Procedure*: on two API 31+ devices, configure a lock, run a device-to-device transfer, and check
    on the target whether `GrayzonePrefs.xml` and `grayzone_usage.db` are present
    (`adb shell run-as com.grayzone.app ls -R`). Expected: absent.
  - *Severity effect*: stays **Low** under either outcome — no user-visible behaviour changes today.
    Only the size of the correct fix changes.
- **Build scope**: everyday-build
- **Constitution**: I (dead configuration), II (one policy expressed three times)
- **Affected surfaces**: `StateBackupManager`'s external snapshot is the app's real backup mechanism
  and lives on shared storage, outside every backup domain, so it is unaffected by all three
  declarations. F-131 is the finding that this mechanism does not run at all on API 29.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-140 — The accessibility service is scoped to every app on the device while its own configuration file claims "MINIMAL scope"  [Medium]

- **Review unit**: RU-08
- **Location**: `app/src/main/res/xml/accessibility_service_config.xml` (whole file, 8 lines) —
  **modified by the uncommitted diff**; declared from `app/src/main/AndroidManifest.xml:87–89`
- **Issue**: `android:packageNames` is not declared, and an absent `packageNames` means *all
  packages*. The service therefore receives a `TYPE_WINDOW_STATE_CHANGED` event for every window
  change on the device — banking apps, password managers, messaging apps, the dialer — not only the
  apps the user chose to monitor. The first line of the file says the opposite: *"Accessibility
  service configuration: MINIMAL scope for maximum trustworthiness."* `GrayzoneAccessibilityService`'s
  class doc makes a stronger claim still: *"Minimal, transparent, and trustworthy by design."* The
  scope-narrowing attribute that would make those claims true is the one attribute the file omits.
  The uncommitted diff **removed** `android:packageNames="@null"` from this file. That change is
  behaviourally inert — `@null` resolves to null, which already meant "all packages" — but it removed
  the only textual marker that the scope decision had ever been considered, and it did so in the same
  diff that split the service into the `:a11y` process. Two things keep this at Medium rather than
  High. The event payload is genuinely narrow: `canRetrieveWindowContent="false"` means the service
  cannot read screen content, and `onAccessibilityEvent` uses only `event.packageName` (`:73`).
  And device-wide event delivery is *necessary* for the feature — the service must see a switch to an
  unmonitored app in order to know the user left a monitored one. So the defect is not that the scope
  is wrong; it is that the scope is undeclared, undocumented, and described as its opposite, on the
  single most trust-sensitive permission the app requests. The related but distinct hazard — that the
  attribute could not be added effectively even if someone tried — is F-141.
- **Trigger condition**:
  1. `rg -n "packageNames" app/src` → zero matches.
  2. `git diff app/src/main/res/xml/accessibility_service_config.xml` → shows
     `- android:packageNames="@null"`.
  3. Enable the service, then `adb shell setprop log.tag.GZAccessibility D` and open an unrelated app
     such as the dialer; the debug log at `GrayzoneAccessibilityService.kt:78–82` records
     `App changed` with that package. (Note: this observation currently requires reverting F-064,
     since `GrayzoneLogger.d()` is a no-op in every variant.)
- **Blast radius**:
  - *Callers*: the framework, which parses this file when the user enables the service. The values are
    consumed by `GrayzoneAccessibilityService.onServiceConnected()` `:36–64` and
    `onAccessibilityEvent()` `:66–113`.
  - *Process death*: none — the configuration is re-parsed on each bind.
  - *Config change*: none.
  - *Foreground/background*: events are delivered continuously while the service is enabled,
    regardless of whether Grayzone is in the foreground; that is the point of the service.
  - *Cross-process*: **yes, and it constrains the fix.** The only correct way to narrow scope
    dynamically is `serviceInfo.packageNames = <monitored set>` inside `onServiceConnected`, but the
    monitored set lives in `SharedPreferences` and `GrayzoneAccessibilityService` runs in `:a11y`,
    which — per `evidence/cross-process-inventory.md`, re-verified by reading the file in full — never
    reads preferences, deliberately. Adding a prefs read there would introduce the exact cross-process
    coherence hazard that inventory says the app currently avoids only by accident. A fix must
    therefore push the set across the existing IPC boundary rather than read it locally.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): do not narrow `packageNames` — the feature needs device-wide window events.
  Instead, correct the two claims so the configuration and the documentation agree: replace the
  file's header comment with an accurate statement of the scope and why it is required (all packages
  for switch detection; no window content; package name only), and update the class doc at
  `GrayzoneAccessibilityService.kt:9–27` to match. If the maintainer instead wants genuine narrowing,
  it must be done through the `:a11y` IPC boundary as described above, and F-141 must be fixed first
  or the narrowing will be discarded at connect time.
- **Rewrite justification**: not a rewrite — comment text plus, optionally, an explicit
  `android:packageNames` restored with a justifying comment.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/AccessibilityConfigTest.kt`, resolving the
  service through `AccessibilityManager.getInstalledAccessibilityServiceList()` and asserting that its
  `AccessibilityServiceInfo.packageNames` matches the intended value and that
  `canRetrieveWindowContent` is `false`. **This test cannot be executed in this environment** — no
  device or emulator is available — so it is specified for T083's pending-device queue. It fails
  before F-141's fix (the runtime override discards whatever the XML declares) and passes after.
- **Existing test conflict**: none. `rg -n "accessibility_service_config" app/src/test app/src/androidTest`
  returns no matches; the configuration has no test.
- **Verification**: `confirmed` (evidence tier `T1` for the absent attribute and the contradicting
  comments; the diff is confirmed from `git diff`)
  - *Missing*: nothing for the finding as filed. The privacy *consequence* of device-wide delivery is
    bounded by `canRetrieveWindowContent="false"`, which is itself confirmed from the file.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: IV (documentation asserting an invariant the configuration does not hold)
- **Affected surfaces**: F-141 (the runtime override that would defeat any narrowing), F-065 (`w()`/`e()`
  ungated in release and leaking package names — the reason device-wide event scope has a real
  privacy tail), and `README.md`'s accessibility-scope claims, which are T025b's.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/res/xml/accessibility_service_config.xml, app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

### F-141 — `onServiceConnected` replaces `serviceInfo` with a default-constructed object, so any scope narrowing added to the XML config is silently discarded  [Medium]

- **Review unit**: RU-08
- **Location**: `app/src/main/res/xml/accessibility_service_config.xml` (the declaration side, 8 lines)
  against `app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt` →
  `onServiceConnected()` (lines 41–55)
- **Issue**: `onServiceConnected` builds `AccessibilityServiceInfo()` **from scratch** and assigns it
  to `serviceInfo`, rather than mutating the instance the framework already populated from the XML.
  A default-constructed `AccessibilityServiceInfo` has `packageNames = null`, `flags = 0` and
  `notificationTimeout = 0`; the code then sets `eventTypes`, `feedbackType` and `notificationTimeout`
  back to the same three values the XML already declares. Today that is a no-op with a duplication
  smell: four settings are declared in two places and must be kept in sync by hand, with nothing to
  detect divergence. Tomorrow it is a trap. **Any `android:packageNames` added to the XML — which is
  precisely F-140's remediation, and the first thing a reviewer would reach for — is wiped the moment
  the service connects**, because the replacement object carries `packageNames = null`. The same
  applies to any `android:accessibilityFlags` a future change might add. The failure is silent: the
  XML still reads as if the scope were narrowed, the service still works, and the narrowing simply
  does not happen. This is filed separately from F-140 because it is a distinct root cause — F-140 is
  a missing declaration and an inaccurate comment; this is an override that makes the declaration
  unenforceable — and because F-140's fix is unsafe until this one lands.
- **Trigger condition**:
  1. Add `android:packageNames="com.example.target"` to
     `app/src/main/res/xml/accessibility_service_config.xml`.
  2. Build, install, enable the service.
  3. `adb shell dumpsys accessibility | grep -A5 GrayzoneAccessibilityService` and read the effective
     `packageNames` — it is `null`, not the declared value.
  4. Comment out the `serviceInfo = info` assignment at
     `GrayzoneAccessibilityService.kt:55`, rebuild, and repeat step 3 — the declared value now takes
     effect.
- **Blast radius**:
  - *Callers*: `onServiceConnected()` is a framework callback invoked once per bind; no app code calls
    it. `serviceInfo` is not read or written anywhere else — `rg -n "serviceInfo" app/src` returns only
    this assignment.
  - *Process death*: the override re-runs on every rebind, so it also re-wipes the XML values after
    any `:a11y` process death — meaning the trap cannot be worked around by setting the value once at
    startup from elsewhere.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: **yes.** This code is the sole occupant of `:a11y`. Any fix that makes the
    runtime scope reflect real state has to get that state across the process boundary; it cannot read
    `SharedPreferences` here without introducing the coherence hazard `evidence/cross-process-inventory.md`
    records the app as currently avoiding.
  - *Persistence*: none.
  - *Release-only*: no.
- **Fix** (minimal): stop constructing a new object. Replace lines 42–55 with a mutation of the
  existing framework-supplied instance, preserving everything the XML declared:
  ```kotlin
  serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
      eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      notificationTimeout = 100L
  }
  ```
  The `?:` fallback keeps the existing defensive posture — the surrounding `try`/`catch` at `:41,57`
  exists because a throw here can cause the OS to revoke the service, and that reasoning is sound and
  must be preserved. Better still, once the values are known not to be discarded, delete the three
  assignments entirely and let the XML be the single source of truth, which removes the duplication
  as well as the trap.
- **Rewrite justification**: not a rewrite — the object construction is replaced by a mutation of the
  same three fields, and the guard structure is untouched.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/AccessibilityConfigTest.kt` (same file as F-140),
  asserting that the connected service's effective `AccessibilityServiceInfo.packageNames` equals the
  value declared in `accessibility_service_config.xml`. **Cannot be executed in this environment** —
  no device or emulator — so it is specified for T083's pending-device queue. Fails before the fix
  (effective value `null` regardless of the XML), passes after.
- **Existing test conflict**: none. `GrayzoneAccessibilityService` has no test in either source set.
- **Verification**: `needs-verification` (evidence tier `T1` for the code and XML, `T4` for the
  effective runtime value)
  - *Missing*: a device, to confirm that the framework's own `serviceInfo` is non-null at
    `onServiceConnected` time and that assigning a fresh object does in fact discard XML-declared
    values rather than merging them. The code facts — a fresh `AccessibilityServiceInfo()` assigned to
    `serviceInfo`, and `packageNames` never set — are confirmed by reading the file.
  - *Procedure*: steps 1–4 above on any API 29+ device.
  - *Severity effect*: override confirmed to discard the XML → **Medium** as filed (a latent trap that
    silently defeats the natural fix for a trust-sensitive setting). If the platform merges rather
    than replaces → **Low**, reducing to the duplication finding alone, since the four values would
    then simply be declared twice with no divergence risk.
- **Build scope**: everyday-build
- **Constitution**: II (the same configuration expressed in two places), IV
- **Affected surfaces**: F-140 — these two must be actioned together and in this order, or F-140's fix
  will appear to work and will not. `evidence/prior-fix-watchlist.md` flags
  `GrayzoneAccessibilityService.kt` as elevated-attention; this is a second defect in it beyond the
  process-split consequences already recorded.
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/res/xml/accessibility_service_config.xml, app/src/main/java/com/grayzone/app/service/GrayzoneAccessibilityService.kt)`
- **Disposition**: `verified`

---

### F-142 — The manifest suppresses two lint checks inline, one of them the check that would have flagged F-131, and declares `xmlns:tools` on child elements  [Low]

- **Review unit**: RU-08
- **Location**: `app/src/main/AndroidManifest.xml` line 24 (`tools:ignore="ProtectedPermissions"` plus
  an inline `xmlns:tools`) and lines 35–37 (`tools:ignore="ScopedStorage"` plus a second inline
  `xmlns:tools`)
- **Issue**: Two things, one root cause — suppressions applied without a recorded reason. The
  substantive half is `tools:ignore="ScopedStorage"` on `MANAGE_EXTERNAL_STORAGE`. That check exists
  to make an author justify all-files access and to surface the scoped-storage alternatives, and it is
  the check positioned to question exactly the legacy-storage story that F-131 shows does not work on
  API 29. Suppressing it silently is how a documented-but-unimplemented design survives review.
  `tools:ignore="ProtectedPermissions"` on line 24 does the same for a permission the app does not use
  at all (F-133). Neither suppression carries a comment explaining why it is acceptable, so a reader
  cannot distinguish "considered and accepted" from "made the warning go away". The cosmetic half:
  `xmlns:tools` is declared twice, on two `<uses-permission>` children, rather than once on the
  `<manifest>` root where every other Android project puts it. It works, but it means a third
  suppression elsewhere in the file would need a third declaration, and it obscures which elements
  are suppressed. Combined with F-130 removing the release lint gate entirely, the app now has two
  inline suppressions and no release-variant lint run.
- **Trigger condition**:
  1. `rg -n "tools:ignore|xmlns:tools" app/src/main/AndroidManifest.xml` → four matches on lines 24,
     35–37.
  2. Remove `tools:ignore="ScopedStorage"` and run
     `./gradlew :app:lintDebug "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"` → the
     `ScopedStorage` warning appears against `MANAGE_EXTERNAL_STORAGE`, pointing at the same
     legacy-storage path F-131 documents as broken.
- **Blast radius**:
  - *Callers*: none — build-time attributes, stripped from the packaged manifest (verified: no
    `tools:` attribute appears in
    `app/build/intermediates/merged_manifests/release/AndroidManifest.xml`, where lines 31 and 42
    carry the bare permissions).
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: no — but the suppressions are only observable when lint runs, which F-130 has
    reduced to manual `lintDebug` invocations.
- **Fix** (minimal): move a single `xmlns:tools="http://schemas.android.com/tools"` declaration to the
  `<manifest>` root element and delete both inline copies. Delete
  `tools:ignore="ProtectedPermissions"` along with line 24 entirely (F-133). Keep
  `tools:ignore="ScopedStorage"` — all-files access is a deliberate and defensible choice for this
  app — but add a one-line comment stating the justification, and only after F-131 makes the
  legacy-storage branch actually function, so the suppression covers a working design rather than a
  documented intention.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/build/ManifestPermissionsTest.kt` (same file as F-132–F-134),
  asserting that `xmlns:tools` is declared at most once in `app/src/main/AndroidManifest.xml` and that
  every surviving `tools:ignore` attribute is immediately preceded by an XML comment. Fails before the
  fix, passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier `T1`)
  - *Missing*: nothing.
  - *Procedure*: n/a.
  - *Severity effect*: n/a.
- **Build scope**: everyday-build
- **Constitution**: V (a check disabled without a recorded justification)
- **Affected surfaces**: F-130 (the release lint gate), F-131 (the design `ScopedStorage` would have
  questioned), F-133 (the permission `ProtectedPermissions` would have questioned).
- **Observed against**: `working-tree @ 557a963 (uncommitted: app/src/main/AndroidManifest.xml)`
- **Disposition**: `verified`

---

## Coverage verdicts — the six required areas

| # | Area | Verdict | Findings |
|---|---|---|---|
| 1 | Keep-rule staleness | **Defects found**, including one beyond `evidence/r8.md`'s two recorded leads | F-125, F-126, F-127, F-128 |
| 2 | Hostile / malformed intent, missing extras | **Defects found on `BootReceiver`; `MainActivity` and `AdBlockVpnService` clean on extras** | F-136, F-137, F-138 |
| 3 | Declared-vs-used permissions | **Defects found** — three unused declarations and one declared-but-never-requested | F-131, F-132, F-133, F-134, F-135 |
| 4 | `allowBackup=false` vs `backup_rules.xml` | **Redundant, not contradictory** — one dead file, one live one | F-139 |
| 5 | Over-broad `accessibility_service_config.xml` | **Defects found**, and the natural fix is currently unenforceable | F-140, F-141 |
| 6 | minSdk-29 safety | **One defect, in permissions rather than API guards** — every version-gated API call is correctly guarded | F-131 (and F-132) |

### Area 2 in detail

`evidence/r8.md` §F2 established that all seven manifest components survive minification, so the
question here was reachability, not retention. Each exported component was examined against a hostile
caller:

- **`MainActivity`** — exported by necessity (`LAUNCHER`). Reads **no** intent extras, has no
  `onNewIntent`, and forwards `savedInstanceState` unmodified to `super`. There is no parsing surface
  to attack and **no missing-extras defect**. What remains is task-affinity hardening → F-138.
- **`AdBlockVpnService`** — `exported="true"` but carries
  `android:permission="android.permission.BIND_VPN_SERVICE"`, which the platform enforces for
  `startService`, `stopService` and `bindService` alike, not only for bind. `BIND_VPN_SERVICE` is
  signature-level and is held by the system alone, so neither a third-party app nor `adb shell` can
  deliver `ACTION_STOP`, `ACTION_HOLD_EXPIRED` or `ACTION_REFRESH_NOTIFICATION`. The `exported="true"`
  is required by `VpnService` itself. Null-intent handling on sticky restart is also correct:
  `onStartCommand` (`:122–170`) tests `intent?.action` with safe calls throughout and falls through to
  a hold re-check. **No defect** — recorded here rather than filed, per the contract's rule against
  silent scope drops.
- **`BootReceiver`** — the one genuine exposure → F-136, F-137.
- **`OverlayService`, `SessionExpiryReceiver`, `VpnHoldExpiryReceiver`, `GrayzoneAccessibilityService`,
  `FileProvider`** — all `exported="false"`. The two receivers are driven by `AlarmManager`
  `PendingIntent`s, which the system sends as the app's own UID, so `exported="false"` is both correct
  and sufficient. `GrayzoneAccessibilityService` additionally carries `BIND_ACCESSIBILITY_SERVICE`.
  **No defect.**

### Area 6 in detail — every version-gated call was checked

`minSdk = 29`, `targetSdk = 34`, `compileSdk = 34`. Enumerating `Build.VERSION.SDK_INT` across
`app/src/main` returns seven guards, and each one guards the right thing:

| Site | API needed | Guard | Verdict |
|---|---|---|---|
| `StateBackupManager.kt:138` → `Environment.isExternalStorageManager()` | 30 | `>= R` | correct |
| `MainActivity.kt:88` → `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` | 30 | `< R` early return | correct |
| `MainActivity.kt:248` → `POST_NOTIFICATIONS` request | 33 | `>= TIRAMISU` | correct |
| `GrayzoneApplication.kt:239` | 33 | `>= 33` | correct |
| `SessionExpiryScheduler.kt:46`, `VpnHoldExpiryScheduler.kt:37` → `canScheduleExactAlarms()` | 31 | `< S \|\| canScheduleExactAlarms()` | correct — and already credited as a positive example in `evidence/lint.md` §E1 |
| `OverlayService.kt:789`, `OemBatteryHelper.kt:163` | 23 | `>= M` | dead branches at `minSdk 29` — the `ObsoleteSdkInt` lead, owned by T059/T060 |

Three further API-level hazards were checked and are clean, recorded so they are not re-investigated:

- **`registerReceiver` without an export flag** would crash at `targetSdk 34`. Both call sites
  (`OverlayService.kt:188,213`) use `androidx.core.content.ContextCompat.registerReceiver`, which
  requires the flag argument. **No defect.**
- **`PendingIntent` without an explicit mutability flag** throws at API 31+. All six sites
  (`OverlayService.kt:691,743,1199`, `AdBlockVpnService.kt:609,618`, `SessionExpiryScheduler.kt:28`,
  `VpnHoldExpiryScheduler.kt:24`) pass `FLAG_IMMUTABLE`. **No defect.**
- **`foregroundServiceType="specialUse"` and the `<property>` element** are API 34 and API 31
  constructs respectively, and both appear in the manifest. Older platforms ignore unrecognised
  manifest values and tags, and the corresponding `FOREGROUND_SERVICE_SPECIAL_USE` permission is
  declared (`:20`). **No defect.**

The only minSdk-29 defect found is not an unguarded API call but the opposite: a guarded API-29
*branch* whose runtime permission is never requested → **F-131**.

## Confirmed non-findings from this unit

Recorded so the audit does not report them, and so a later task does not re-open them.

| Claim | Why it is not a defect |
|---|---|
| The uncommitted removal of `android:packageNames="@null"` changed the service's scope | It did not. `@null` resolves to `null`, which is what an absent attribute already means. The change is inert; the *scope* was always device-wide, which is F-140. |
| `ksp { arg("room.schemaLocation", …) }` is misplaced inside `defaultConfig` | It resolves to the project-level `ksp` extension and works. Proof: `app/schemas/com.grayzone.app.data.UsageDatabase/{1,2}.json` exist and are wired into `androidTest` assets by `build.gradle.kts:110`. |
| `isShrinkResources = true` may strip resources loaded reflectively | `rg -n "getIdentifier" app/src` → zero matches. No reflective resource lookup exists. |
| `AdBlockVpnService` being `exported="true"` is an attack surface | Gated by `android:permission="android.permission.BIND_VPN_SERVICE"`, which the platform enforces on start as well as bind, and which is signature-level. See Area 2 above. |
| `GrayzoneAccessibilityService` with `exported="false"` may prevent the framework from binding it | It does not — the system is exempt from the export check for its own binds, and the service is demonstrably live throughout this audit. This is also the precedent supporting F-136's fix. |
| Gson 2.10.1 ships consumer ProGuard rules that cover `TypeToken` | It does not. `configuration.txt` — the merged configuration R8 applied — contains only the app's own hand-written Gson block at lines 126–137. This is what makes F-125 a real gap rather than a duplicate of a library rule. |
| `proguard-android-optimize.txt` supplies `SourceFile`/`LineNumberTable` | It does not. Same evidence: `configuration.txt` keeps only `AnnotationDefault`, `Signature` and `*Annotation*`. This upgrades F-126 from an inference to a confirmed fact. |
| `BuildConfig.DEBUG` being false in the debug variant is an RU-08 finding | It is **F-064** (RU-01), already confirmed from the generated `BuildConfig.java`. RU-08 owns only the build-side shape of its fix, described in *Scope boundaries* above. |


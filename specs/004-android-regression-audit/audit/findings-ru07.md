# Findings â€” RU-07 UI state handling (T026)

**Feature**: `004-android-regression-audit` | **Task**: T026 | **Review unit**: RU-07
(`app/src/main/java/com/grayzone/app/ui/`, plus `MainActivity.kt`'s composition root, which is where
RU-07's state is hosted)
**ID block**: F-145 â€¦ F-159 | **Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md)
**Register**: [`findings.md`](./findings.md)

## Scope examined

All 15 files under `ui/` (2,712 lines: `AppsScreen`, `CustomPromptsSheet`, `DailyBreakCard`,
`HomeScreen`, `LimitsScreen`, `OnboardingScreen`, `PerAppSettingsSheet`, `QuickFocusCard`,
`ScheduleSheet`, `SettingsScreen`, `StreakCard`, `VpnScreen`, `theme/Color.kt`, `theme/Theme.kt`,
`theme/Type.kt`), plus `MainActivity.kt` (the `setContent` root, `MainAppContent`, `MainScreen`) and
the RU-01 composables RU-07 depends on (`SharedComponents.kt`'s `rememberResumedTicker`,
`ResumedInterval`, `GZCard`; `Extensions.kt`'s `isAnyAppLocked`, `getNormalizedRemainingMillis`;
`AppUtils.kt`'s `AppMetadataCache`).

**Observed against**: `557a963`. Verified with `git status --porcelain` that **no file under
`ui/` has uncommitted modifications** â€” the nine modified files are all outside RU-07
(`build.gradle.kts`, `AndroidManifest.xml`, `GrayzoneApplication.kt`,
`GrayzoneAccessibilityService.kt`, `OverlayService.kt`, `AdBlockVpnService.kt`,
`BlocklistManager.kt`, `DnsTrafficBus.kt`, `res/xml/accessibility_service_config.xml`). Two findings
below (F-157, F-159) reach into modified files and are labelled accordingly.

## The structural fact this task exists to establish

One search across the whole of `app/src` for `rememberSaveable|ViewModel|onSaveInstanceState|SavedStateHandle`
returns **zero matches**. There is no `ViewModel`, no `SavedStateHandle`, no `onSaveInstanceState`
override, and not a single `rememberSaveable` call anywhere in the application. Every piece of UI
state in Grayzone is either a plain `remember` or a SharedPreferences read.

That is the root cause behind most of this file. It is filed once as F-145 (the missing mechanism
and the per-screen inventory), and the individually-harmful instances are filed separately so the
punch list can tier them. **F-145 must not be double-counted against F-146, F-152, or F-153.**

## Already-owned findings cited but not re-filed

| ID | Owner | Why it is cited here |
|---|---|---|
| F-076 | T021 | `MainScreen.selectedTab` is `remember`-only. The single already-known instance of F-145's class. |
| F-066 | T021 | `MainScreen`'s `LaunchedEffect(Unit)` never re-runs on resume. F-158 is its **configuration-change** mirror, which is the opposite failure. |
| F-075 | T021 | `shareApk` main-thread copy from `SettingsScreen.kt:82`. |
| F-213 | T030 | `StreakEvaluator.run` launched unguarded from `HomeScreen.kt:56â€“58`. |
| F-073 | T021 | `SetupRequirements.actionLabel()` test-only while `HomeScreen` re-derives the CTA. |
| F-211 | T030 | Eleven unguarded `startActivity`/service-start calls on UI paths, including `HomeScreen.kt:175,187`, `OnboardingScreen.kt:104,156,212`, `MainActivity.kt:257`, `VpnScreen.kt:527,533,539`. |

---

### F-145 â€” No state-restoration mechanism exists anywhere in the app: all UI state is `remember`-only, so nothing survives process death and only the prefs-backed subset survives configuration change  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/` (all 12 screen/sheet files) and
  `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainActivity.onCreate()`'s `setContent`
  block (lines 57â€“78), `MainAppContent()` (lines 179â€“226), `MainScreen()` (lines 229â€“314)
- **Issue**: The app has no `ViewModel`, no `SavedStateHandle`, no `onSaveInstanceState` override,
  and zero `rememberSaveable` calls (confirmed by a single search across `app/src`). `MainActivity`
  declares no `android:configChanges` (`AndroidManifest.xml:55â€“58`), so **every** configuration
  change â€” rotation, system dark-mode switch, font-scale change, locale change, density change,
  multi-window resize â€” destroys and recreates the Activity and discards the whole composition.
  Whether a given piece of state survives is therefore decided incidentally, by whether its
  `remember` initialiser happens to re-read SharedPreferences. The per-screen sweep below is the
  systematic result. Consequence: state that is *derived* self-heals, but every piece of
  **in-progress user input and navigation position** is silently discarded, with no visual cue that
  anything was lost â€” the user sees a screen that looks the same but has forgotten what they were
  doing.
- **Trigger condition**:
  1. Grant accessibility + overlay so the app reaches `Screen.MAIN`.
  2. Navigate to any tab other than Home, type into the Apps search box, then rotate the device
     (or run `adb shell settings put system font_scale 1.3`).
  3. Observe: the tab resets to Home (F-076), the search text is gone, and any open sheet is closed.
  4. Repeat with `adb shell am kill com.grayzone.app` while the app is backgrounded, then reopen:
     nothing at all is restored, because no saved-instance-state bundle is ever written.
- **Blast radius**:
  - *Callers*: the composition root is `MainActivity.onCreate()` â†’ `GrayzoneTheme` â†’
    `MainAppContent()` â†’ `MainScreen()` â†’ one of `HomeScreen()`, `AppsScreen()`, `VpnScreen()`,
    `LimitsScreen()`, `SettingsScreen()`; `MainAppContent()` alternatively â†’ `OnboardingScreen()`.
    Sheets are composed conditionally from `SettingsScreen` (`CustomPromptsSheet`, `ScheduleSheet`)
    and `AppsScreen` (`PerAppSettingsSheet`). Per `evidence/reference-index.md:165`, `MainAppContent()`
    has exactly one caller.
  - *Process death*: total loss. No bundle is written, so process death is indistinguishable from a
    cold start. The prefs-backed subset reloads; nothing else does.
  - *Config change*: the differentiating axis, and the reason this is Medium rather than Low. The
    table below splits the state into three classes.
  - *Foreground/background*: five screens install an `ON_RESUME` `LifecycleEventObserver` to
    re-read prefs (`HomeScreen:49â€“63`, `SettingsScreen:51â€“59`, `StreakCard:35â€“45`,
    `VpnScreen:62â€“70`, `OnboardingScreen:41â€“58`, plus `MainActivity:64â€“72` and
    `MainAppContent:211â€“217`). Three do not (`AppsScreen`, `LimitsScreen`, `QuickFocusCard`/
    `DailyBreakCard`) â€” see F-155.
  - *Cross-process*: not reachable from `:a11y`. `GrayzoneAccessibilityService` is the only class in
    `:a11y` and touches no UI state (`evidence/cross-process-inventory.md`, re-verified).
  - *Persistence*: the mitigating factor. Because the app persists nearly everything to
    SharedPreferences eagerly, most state is re-derivable. The exceptions are what this finding is
    about.
  - *Release-only*: none. R8 does not affect `remember`.
- **Per-screen inventory** (the deliverable T026 was asked for):

  | Screen | `remember` state | Survives config change? | Survives process death? | Mechanism |
  |---|---|---|---|---|
  | `MainActivity` `setContent` | `themeMode` (`:59`) | yes | yes | re-read from `THEME_MODE` in the `remember` initialiser, plus `ON_RESUME` re-read |
  | `MainAppContent` | `currentScreen` (`:209`) | yes | yes | recomputed by `evaluateScreen()` in the initialiser |
  | `MainScreen` | `selectedTab` (`:231`) | **no** | **no** | plain `mutableStateOf(Tab.HOME)` â€” **F-076**, already owned |
  | `HomeScreen` | `accessibilityOn`, `overlayOn`, `batteryIssue`, `monitoredCount` (`:42â€“46`) | yes | yes | all four re-read in the initialiser and again on `ON_RESUME` |
  | `AppsScreen` | `searchQuery` (`:46`) | **no** | **no** | plain `mutableStateOf("")`; user's typed filter is discarded |
  | `AppsScreen` | `selectedAppForSettings` (`:49`) | **no** | **no** | holds a non-`Parcelable` `AppInfo` with a `Bitmap` â€” **F-154** |
  | `AppsScreen` | `installedApps`, `isLoading` (`:42,47`) | yes | partially | `peekCachedApps()` is a process-global `object` cache; survives config change, dies with the process (then the spinner returns) |
  | `AppsScreen` | `monitoredApps`, `grayzoneEnabled` (`:43,48`) | yes | yes | prefs-backed initialisers |
  | `LimitsScreen` | `installedApps`, `monitoredApps` (`:27,28`) | yes | yes | prefs / global cache |
  | `SettingsScreen` | `waitSeconds`, `sessionMinutes`, `lockoutMinutes` (`:40â€“42`) | yes | yes | prefs-backed â€” but never re-read after first composition, which is its own defect (**F-146**) |
  | `SettingsScreen` | `showCustomPrompts`, `showSchedule` (`:44,45`) | **no** | **no** | both sheets close on rotation â€” **F-154** (prompts), **F-152** (schedule) |
  | `SettingsScreen` | `monitoredApps` (`:47`) | yes | yes | prefs + `ON_RESUME` |
  | `PerAppSettingsSheet` | `hasCustom`, `waitSeconds`, `sessionMinutes`, `lockoutMinutes`, `dailyBudget` (`:33,40â€“43`) | n/a â€” the sheet itself is destroyed first (F-154) | n/a | prefs-backed, but unreachable after the host state is lost |
  | `CustomPromptsSheet` | `newPrompt` (`:51`) | **no** | **no** | the prompt the user is typing is discarded |
  | `CustomPromptsSheet` | `prompts`, `useCustomOnly` (`:36,50`) | yes | yes | prefs-backed â€” but see **F-147** for the parse path |
  | `ScheduleSheet` | `focusDuration` (`:37`), `showAddRuleDialog` (`:40`) | **no** | **no** | slider position and dialog visibility both discarded â€” **F-152** |
  | `ScheduleSheet` | `rules`, `isFocusMode`, `focusRemainingMillis` (`:35,36,38`) | yes | yes | re-read from `ScheduleManager` in the initialisers |
  | `AddRuleDialog` | `name`, `startHour`, `startMin`, `endHour`, `endMin`, `weekdaysSelected`, `weekendsSelected` (`:216â€“227`) | **no** | **no** | the entire in-progress schedule rule â€” **F-152** |
  | `VpnScreen` | `showEnableConfirm` (`:57`), `statusMessage` (`:56`) | **no** | **no** | the 24-hour-lock consent dialog and the "VPN stays on for â€¦" explanation both vanish |
  | `VpnScreen` | `holdState`, `currentTime`, `currentElapsed` (`:55,58,59`) | yes | yes | re-read from `VpnHoldLockStore` in the initialiser, on `ON_RESUME`, and every second |
  | `QuickFocusCard` | `selectedMinutes` (`:43`) | **no** | **no** | the chosen focus duration resets to 30 |
  | `QuickFocusCard` / `DailyBreakCard` | `isFocusMode`, `focusRemainingMillis`, `isBreakActive`, `canStartToday`, `remainingMillis` | yes on config change, **stale on resume** | yes | re-read in the initialiser but **never on `ON_RESUME`** â€” **F-155** |
  | `StreakCard` | `currentStreak`, `bestStreak`, `achievements` (`:31â€“33`) | yes | yes | initialiser + `ON_RESUME` |
  | `OnboardingScreen` | `hasUsageAccess`, `hasOverlay`, `hasBatteryOpt` (`:36â€“38`) | yes | yes | initialiser + `ON_RESUME` |

  Net: **13 pieces of state across 7 files are lost on every configuration change**, and every one
  of them is either in-progress user input or navigation position.
- **Fix** (minimal): two changes, neither of which requires introducing a `ViewModel`.
  1. Convert the 13 non-derived states to `rememberSaveable`. All but one are `String`, `Int`, or
     `Boolean` and need no custom `Saver`. The exception is `AppsScreen.selectedAppForSettings`
     (`AppInfo`, holds a `Bitmap`); save the `packageName` and re-resolve from `peekCachedApps()` â€”
     see F-154. `MainScreen.selectedTab` is an `enum` and needs `rememberSaveable { mutableStateOf(Tab.HOME) }`
     with the built-in enum support, or store `Tab.name`.
  2. Add `android:configChanges="uiMode"` to `MainActivity` **only if** the maintainer wants the
     in-app dark-mode toggle and the system dark-mode switch to behave identically (see F-174). This
     is optional and orthogonal; item 1 is the actual fix.
- **Rewrite justification**: not applicable â€” this is a mechanical per-site change, and no rewrite
  is proposed. Explicitly **not** recommending a `ViewModel` layer: FR-014 requires demonstrating
  that the minimal patch is insufficient, and `rememberSaveable` resolves every case in the table.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/StateRestorationTest.kt`, using
  `StateRestorationTester.emulateSavedInstanceStateRestore()` to assert that after restoration the
  Apps search field still contains the typed query and the selected tab is unchanged. Fails before
  (both reset), passes after. **Cannot be executed here**: no device is available, and
  `androidx.compose.ui:ui-test-junit4` is not currently a dependency â€” `app/build.gradle.kts:156â€“157`
  declares only `ui-tooling` and `ui-test-manifest`, so `androidTestImplementation("androidx.compose.ui:ui-test-junit4")`
  must be added first. Flagging this as a prerequisite rather than a separate finding, since RU-10
  (T029) owns test-infrastructure gaps.
- **Existing test conflict**: none. No test in the repository references any RU-07 file â€” verified
  against the 46-file test inventory; `app/src/androidTest` contains only three Room tests
  (`MigrationTest`, `PeakHourAggregationTest`, `UsageDaoStatsTest`).
- **Verification**: `confirmed` (evidence tier T1) â€” established by direct search over `app/src`
  (zero matches for the four restoration mechanisms) and by reading all 12 screen files. The
  per-screen "survives?" column is derived from whether each `remember` initialiser re-reads a
  persistent source, which is statically decidable and does not need a device.
- **Build scope**: everyday-build
- **Constitution**: II (the same "re-read prefs in the initialiser" idiom is hand-written 24 times
  with no shared helper, and three screens diverge from it)
- **Affected surfaces**: all 12 files in the table. F-076 (already filed) is one row of it.

  **Duplicate declared: F-232 (T050, Medium) is the same defect.** E4's sweep for foldables and
  multi-window independently found that `rememberSaveable` occurs zero times and filed it, listing
  four representative sites. F-145 is the systematic version T026 was commissioned to produce: the
  full 13-entry per-screen inventory, the survives-config-change versus survives-process-death split
  per entry, and the `AppInfo`-is-not-`Parcelable` obstacle that makes one of the thirteen a
  non-mechanical fix. **These must not both reach the punch list.** The recommendation is that F-145
  survives as the record and F-232 is withdrawn against it, following the register's existing
  precedent (F-207 â†’ F-060, F-214 â†’ F-031, F-062 â†’ F-206) â€” but the withdrawal is T050's call to
  make, not this task's, so both are left `reported` and the collision is declared here and in the
  roll-up rather than resolved unilaterally. Nothing in the two records contradicts: F-232's severity,
  root cause and fix direction all agree with F-145's.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-146 â€” Dragging the lockout slider permanently and silently rewrites the session limit, then persists it from an unrelated control  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` -> `SettingsScreen()`
  lockout `Slider` (lines 199-214), specifically `onValueChange` at 201-204 and
  `onValueChangeFinished` at 208-213. Duplicated verbatim in
  `app/src/main/java/com/grayzone/app/ui/PerAppSettingsSheet.kt` -> `PerAppSettingsSheet()`
  (lines 129-144).
- **Issue**: The lockout slider clamps the session limit down inside `onValueChange`
  (`if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes`), which fires on **every
  intermediate value of the drag**, and never restores it when the drag continues upward. Its
  `onValueChangeFinished` then persists **both** keys. So a single gesture that drags lockout from
  1440 down through 15 and back up to 1440 leaves `sessionMinutes` at 15 and writes
  `SESSION_MINUTES = 15` to preferences. The user changed the lockout duration and the app silently
  changed how long their sessions last, with the "Session Limit: N minutes" label above having
  scrolled or not been looked at. The direction of harm is that the app becomes **more** restrictive
  than what the user configured without saying so - the same class of broken promise as F-111's
  inverse case, where a clock correction extends a lockout beyond what was agreed.
- **Trigger condition**:
  1. Open Settings. Drag "Session Limit" to 60 minutes and release; drag "Lockout Duration" to
     24h 0m and release. Observe both labels read `Session Limit: 60 minutes` and
     `Lockout Duration: 24h 0m`.
  2. Without lifting the finger, drag the Lockout slider all the way left to `15m`, then all the way
     back right to `24h 0m`, then release.
  3. Observe the Session Limit label now reads `Session Limit: 15 minutes`.
  4. Confirm it was persisted, not merely displayed:
     `adb shell run-as com.grayzone.app cat /data/data/com.grayzone.app/shared_prefs/*.xml | findstr session_minutes`
     (or leave the tab and return, which re-reads from prefs).
- **Blast radius**:
  - *Callers*: `SettingsScreen()` is composed only by `MainScreen()` (`MainActivity.kt:310`).
    `PerAppSettingsSheet()` is composed only by `AppsScreen()` (`AppsScreen.kt:258`). Downstream
    readers of the corrupted value: `OverlayService.kt:456-457` and its session-grant path at
    `:1070-1084` read `SESSION_MINUTES`; `LimitsScreen.kt:104` displays it;
    `GrayzoneApplication.kt` validates only `WAIT_SECONDS`, not `SESSION_MINUTES`, so nothing
    repairs it.
  - *Process death*: irrelevant to the trigger - the corruption is already in preferences before the
    process can die, which is what makes it silent rather than transient.
  - *Config change*: a configuration change mid-drag discards the whole composition (F-145), so the
    clamp is lost and prefs keep the previous value. That means rotation is an accidental *undo* and
    the defect is not reproducible across one.
  - *Foreground/background*: none. Both slider callbacks are synchronous main-thread.
  - *Cross-process*: none. Not reachable from `:a11y`.
  - *Persistence*: **yes** - writes `PrefsKeys.SESSION_MINUTES` and `PrefsKeys.LOCKOUT_MINUTES` via
    `apply()` (`SettingsScreen.kt:209-212`), and the per-app twin writes
    `PER_APP_SESSION_MINUTES`/`PER_APP_LOCKOUT_MINUTES` (`PerAppSettingsSheet.kt:139-142`). Both are
    snapshotted into the anti-tamper backup by `StateBackupManager` (`prefs.all`), so the wrong value
    is also what a later wipe-recovery restores.
  - *Release-only*: none.
- **Fix** (minimal): move the clamp out of `onValueChange` and into `onValueChangeFinished`, so it is
  evaluated once against the final value rather than against every intermediate one:

  ```kotlin
  onValueChange = { lockoutMinutes = it.toInt() },
  onValueChangeFinished = {
      if (sessionMinutes > lockoutMinutes) sessionMinutes = lockoutMinutes
      prefs.edit()
          .putInt(PrefsKeys.SESSION_MINUTES, sessionMinutes)
          .putInt(PrefsKeys.LOCKOUT_MINUTES, lockoutMinutes)
          .apply()
  }
  ```

  Apply the identical change at `PerAppSettingsSheet.kt:131-143`. Better still, extract the
  session-vs-lockout invariant into one pure function next to `ConfigValidator` so the two copies
  cannot diverge again - see the Constitution note below.
- **Rewrite justification**: not applicable; the fix is a two-line reordering at each of two sites.
- **Test to add**: `jvm-unit` - `app/src/test/java/com/grayzone/app/data/ConfigValidatorTest.kt`
  (extend the existing file), asserting a new pure
  `ConfigValidator.clampSessionToLockout(sessionMins, lockoutMins)` returns 60 for
  `(60, 1440)` and 15 for `(60, 15)`, and - the assertion that actually catches this defect - that
  applying it to the *sequence* `1440 -> 15 -> 1440` yields 60, not 15. Fails before the fix (the
  current inline clamp is path-dependent), passes after (the extracted function is a pure function of
  the final pair).
- **Existing test conflict**: none. `ConfigValidatorTest.kt` currently covers only
  `WAIT_SECONDS_RANGE`/`isWaitSecondsValid`/`repairWaitSeconds`; adding a case does not change
  existing assertions.
- **Verification**: `confirmed` (evidence tier T1). No device needed: `onValueChange` is documented
  to fire continuously during the drag, the clamp has no inverse branch, and
  `onValueChangeFinished` unconditionally persists `sessionMinutes`. All three facts are visible in
  the 16 lines cited.
- **Build scope**: everyday-build
- **Constitution**: II - the lockout slider, its clamp, and its dual-key write are duplicated
  verbatim between `SettingsScreen.kt:199-214` and `PerAppSettingsSheet.kt:129-144`, and
  `ConfigValidator` already exists as the home for exactly this kind of bounds rule but validates
  only `WAIT_SECONDS`.
- **Affected surfaces**: `PerAppSettingsSheet.kt:129-144` (per-app twin, same defect, same fix).
  The session slider's own clamp (`SettingsScreen.kt:179`, `PerAppSettingsSheet.kt:114`) is
  `coerceAtMost` on the value being dragged and is **not** affected - it is idempotent.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-147 â€” `SettingsScreen` reads the three global limits once and never again, while re-reading `monitoredApps` on every resume, so any external write is silently reverted by the next slider release  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` â†’ `SettingsScreen()`
  (state at lines 40â€“42; the `ON_RESUME` observer that deliberately excludes them at lines 50â€“59;
  the unconditional write-backs at lines 166, 183â€“185, 208â€“213)
- **Issue**: `waitSeconds`, `sessionMinutes`, and `lockoutMinutes` are seeded from preferences in
  their `remember` initialisers and are never refreshed thereafter. The very next declaration in the
  same composable installs an `ON_RESUME` `LifecycleEventObserver` that **does** re-read
  `monitoredApps` â€” so the file demonstrates the correct idiom four lines below the three states
  that omit it. Because each slider's `onValueChangeFinished` unconditionally persists whatever the
  in-memory value happens to be, any write to `WAIT_SECONDS` / `SESSION_MINUTES` /
  `LOCKOUT_MINUTES` from outside this composition is silently overwritten the next time the user
  touches a slider. The user-visible consequence is that the Settings tab can display and then
  re-commit stale limits with no indication that it is fighting another writer.
- **Trigger condition**:
  1. Open the Settings tab and leave it composed (do not switch tabs â€” a tab switch disposes the
     screen and the `remember` initialisers re-read, which masks the defect).
  2. Change one of the three keys from outside the composition. Two real writers exist:
     `GrayzoneApplication.validateConfiguration()` repairing `WAIT_SECONDS`
     (`GrayzoneApplication.kt:181â€“185`), and `StateBackupManager.restoreIfWiped` â†’`applyInto`,
     invoked from `MainActivity.onResume()` (`MainActivity.kt:47â€“51`), which writes every key in the
     snapshot. For a deterministic repro without either, write the key directly:
     `adb shell run-as com.grayzone.app` then edit the prefs XML, or force the restore path.
  3. Return to the still-composed Settings tab. The label shows the **old** value.
  4. Nudge any slider and release. Re-read prefs: the external value is gone, replaced by the stale
     one.
- **Blast radius**:
  - *Callers*: `SettingsScreen()` â† `MainScreen()` (`MainActivity.kt:310`), single call site.
    Competing writers of the same keys, enumerated from `evidence/reference-index.md:193â€“195` and
    re-verified by direct search: `GrayzoneApplication.kt` (validate/repair),
    `service/OverlayService.kt` (reads only â€” `:456â€“457`, `:1070â€“1084`),
    `ui/PerAppSettingsSheet.kt` (reads the globals as slider defaults at `:36â€“38`, writes only the
    `per_app_*` keys), `ui/LimitsScreen.kt` (reads only, `:104â€“105`), and
    `data/StateBackupManager.kt` (writes arbitrary keys on restore).
  - *Process death*: the defect disappears â€” a new process re-composes and re-reads. This is why it
    is Medium and not High: the stale window is bounded by the lifetime of one composition.
  - *Config change*: also acts as an accidental repair, since recreation re-runs the initialisers.
  - *Foreground/background*: **this is the axis that matters.** The screen survives
    backgroundâ†’foreground without recomposing, and `MainActivity.onResume` runs the backup restore
    on exactly that transition (`MainActivity.kt:42â€“53`), so foregrounding is precisely when an
    external write can land under a live, stale composition.
  - *Cross-process*: none. Not reachable from `:a11y`.
  - *Persistence*: **yes** â€” writes `WAIT_SECONDS` (`:166`), `SESSION_MINUTES` (`:184`, `:210`),
    `LOCKOUT_MINUTES` (`:211`), all via `apply()`.
  - *Release-only*: none.
- **Fix** (minimal): add the three keys to the `ON_RESUME` observer that already exists at lines
  51â€“59, alongside the `monitoredApps` re-read:

  ```kotlin
  if (event == Lifecycle.Event.ON_RESUME) {
      monitoredApps = prefs.getStringSet(PrefsKeys.MONITORED_APPS, emptySet()) ?: emptySet()
      waitSeconds = prefs.getInt(PrefsKeys.WAIT_SECONDS, 5)
      sessionMinutes = prefs.getInt(PrefsKeys.SESSION_MINUTES, 10)
      lockoutMinutes = prefs.getInt(PrefsKeys.LOCKOUT_MINUTES, 60)
  }
  ```

  Do **not** instead register an `OnSharedPreferenceChangeListener`: it would fire during the user's
  own drag and fight the slider.
- **Rewrite justification**: not applicable; four lines added to an observer that already exists.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/SettingsResumeReReadTest.kt`, asserting that after
  writing `SESSION_MINUTES = 45` to preferences and driving the host lifecycle through
  `ON_PAUSE`â†’`ON_RESUME`, the "Session Limit" node reads 45. Fails before (reads the seeded value),
  passes after. **Cannot be executed here** â€” no device, and `ui-test-junit4` is not a declared
  dependency (see F-145).
- **Existing test conflict**: none â€” no test references `SettingsScreen`.
- **Verification**: `confirmed` (evidence tier T1) that the re-read is absent and that the write-back
  is unconditional; both are visible in the cited lines. The *frequency* of a competing external
  write is `needs-verification`, which is what holds this at Medium rather than High.
  - *Missing*: a device run establishing whether `StateBackupManager.restoreIfWiped` can actually
    complete a restore while a `SettingsScreen` composition is live, given that clearing app data
    force-stops the process.
  - *Procedure*: on any API 29+ device, open Settings, background the app, grant/revoke all-files
    access so `MainActivity.onResume` takes the `restoreIfWiped` branch, return, nudge a slider, and
    diff the prefs XML before and after.
  - *Severity effect*: if a live-composition restore is reachable â†’ **High** (the anti-tamper restore
    is silently undone by an ordinary tap). If the restore always follows a process kill â†’ stays
    **Medium** (stale display plus a lost `validateConfiguration` repair, no anti-tamper impact).
- **Build scope**: everyday-build
- **Constitution**: II (the correct `ON_RESUME` re-read idiom is present in the same function, four
  lines away, and is not applied to these three states)
- **Affected surfaces**: `PerAppSettingsSheet.kt:33â€“43` has the same shape â€” six prefs-seeded states
  and no `ON_RESUME` observer at all â€” but the sheet's lifetime is short and it is destroyed by any
  configuration change (F-154), so the stale window is much smaller. `VpnScreen.kt:55` gets this
  right (`holdState` is re-read both on `ON_RESUME` and every second).
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-148 â€” A malformed custom-prompts JSON is silently turned into an empty list, and the next add or delete persists that empty list over the user's real prompts  [High]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/CustomPromptsSheet.kt` â†’
  `CustomPromptsSheet()`, the `initialPrompts` initialiser (lines 38â€“48) and the local
  `savePrompts()` (lines 53â€“56), reached from the add button (line 125) and the delete button
  (lines 154â€“157)
- **Issue**: This is the C3 shape, in the UI layer. `gson.fromJson` is wrapped in
  `catch (e: Exception) { emptyList() }` â€” the exception is bound and then discarded, so a
  Principle III violation and a silent one. `prompts` is seeded from that empty list, the sheet
  renders "No custom prompts added yet.", and the moment the user adds a prompt or deletes anything,
  `savePrompts` writes `gson.toJson(newList)` back to `CUSTOM_PROMPTS_JSON` â€” **destroying the
  original JSON that was merely unparseable, not absent.** The failure is doubly silent: no log (and
  even if one were added, `GrayzoneLogger.d()`/`.i()` are no-ops in every variant per F-064), no
  toast, and a UI state ("no prompts") that is indistinguishable from the legitimate empty case. The
  user loses authored content and cannot tell that anything was lost.
- **Trigger condition**:
  1. Add two or three custom prompts through Settings â†’ Manage Custom Prompts, and confirm they
     appear under "Your Prompts".
  2. Corrupt the stored value â€” truncate the JSON, or let the unvalidated backup restore write it:
     `StateBackupManager.applyInto` applies arbitrary keys with arbitrary types from
     `/sdcard/.grayzone/` with no allowlist and no schema gate (the F-060 chain), so a snapshot from
     any other schema version is sufficient.
  3. Reopen the sheet. It reads "No custom prompts added yet." with no error.
  4. Add one new prompt. Re-read `CUSTOM_PROMPTS_JSON`: it now contains exactly one entry. The
     original prompts are unrecoverable from the app.
- **Blast radius**:
  - *Callers*: `CustomPromptsSheet()` â† `SettingsScreen()` (`SettingsScreen.kt:254`), single call
    site. The corrupted key's other consumer is `service/OverlayService.kt`, which reads
    `CUSTOM_PROMPTS_JSON` and `USE_CUSTOM_PROMPTS_ONLY` to build the friction-screen prompt pool
    (`evidence/reference-index.md:214â€“215`); with `USE_CUSTOM_PROMPTS_ONLY = true` and the list
    silently emptied, the friction screen loses its reflection question entirely, which is the
    feature's whole point.
  - *Process death*: no effect on the mechanism â€” the damage is already committed to preferences.
  - *Config change*: destroys the sheet before the user can act (F-154), which incidentally delays
    but does not prevent the overwrite.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: **yes** â€” `savePrompts` writes `PrefsKeys.CUSTOM_PROMPTS_JSON` via `apply()`
    (`:55`). The key is inside `prefs.all` and therefore inside the anti-tamper snapshot, so the
    emptied value also propagates into the external backup and is what a later wipe-recovery
    restores.
  - *Release-only*: none.
- **Fix** (minimal): make the parse failure distinguishable from "no prompts", and refuse to
  overwrite on the failure path. Change the initialiser to a tri-state and gate `savePrompts`:

  ```kotlin
  val parsed: List<String>? = remember {
      val json = prefs.getString(PrefsKeys.CUSTOM_PROMPTS_JSON, null) ?: return@remember emptyList()
      try {
          gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
      } catch (e: Exception) {
          GrayzoneLogger.e(LogComponent.PREFS, "custom prompts JSON unparseable; refusing to overwrite", e)
          null
      }
  }
  ```

  With `parsed == null`, render an explicit "Your saved prompts could not be read" state and disable
  the add/delete controls, so the user is told and nothing is destroyed. Note the logger call must
  use `e()`, not `d()`/`i()`, for the reason recorded in F-064.
- **Rewrite justification**: not applicable â€” the change is confined to the initialiser plus a guard
  on two buttons.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/PromptResolutionTest.kt` (extend the existing file), asserting
  that a new pure `Prompts.parseCustom(json: String?)` returns `emptyList()` for `null`,
  the parsed list for valid JSON, and **`null`** for `"{ not json"`, and that a helper
  `Prompts.mayOverwrite(parsed)` is `false` in the third case. Fails before (there is no such
  function; the behaviour is inline and returns `emptyList()` for both the absent and the corrupt
  case), passes after. The existing `FakeSharedPreferences.kt` covers the prefs side without a
  device.
- **Existing test conflict**: none. `PromptResolutionTest.kt` currently exercises
  `Prompts.resolvePool()`; extracting the parse does not change what it asserts, but the extraction
  must keep `resolvePool`'s current signature so those assertions still compile.
- **Verification**: `confirmed` (evidence tier T1). The catch, the empty-list fallback, and the
  unconditional overwrite are all visible in the 20 lines cited; no runtime evidence is required to
  establish the sequence.
- **Build scope**: everyday-build
- **Constitution**: III (I/O boundary whose exception is bound to `e` and then discarded, returning
  a default silently â€” the "returns-default-silently" category in
  `evidence/error-handling-inventory.md`)
- **Affected surfaces**: `ScheduleManager.getScheduleRules()` (`ScheduleManager.kt:33â€“43`) is the
  same defect on the schedule side and is **already filed as C3** by T020/T024 â€” do not double-count
  the fix. This finding is the *third* instance of the pattern and shows it is a house idiom rather
  than a one-off: an unparseable persisted JSON becomes an empty collection, and the next UI edit
  commits the emptiness. `ScheduleSheet.kt:35` consumes the C3 version and re-persists through
  `addRule`/`removeRule`/`toggleRule` at `:62,176,187`, which is the RU-07 surface of C3.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-149 â€” Thirty unguarded typed preference reads run inside composition, so F-060's poisoned-restore chain crashes the UI too â€” and guarding `validateConfiguration` alone will not close it  [Medium]

- **Review unit**: RU-07
- **Location**: every screen in `app/src/main/java/com/grayzone/app/ui/`; the full enumeration is in
  the table below. Representative: `ui/LimitsScreen.kt` â†’ `LimitsScreen()` (lines 71â€“73, 103â€“105,
  inside the `LazyColumn` item body) and `ui/PerAppSettingsSheet.kt` â†’ `PerAppSettingsSheet()`
  (lines 33, 36â€“38, 40â€“43)
- **Issue**: RU-07 reads SharedPreferences with typed getters (`getInt`, `getLong`, `getBoolean`,
  `getString`, `getStringSet`) at 30 sites, all directly inside composable bodies or `remember`
  initialisers, and **none** is guarded. A `ClassCastException` thrown from any of them propagates
  out of the composition and terminates the Activity. This is the same root cause as **F-060**
  (`applyInto` writes arbitrary keys with arbitrary types from the external snapshot, with no
  allowlist and no schema gate because `SCHEMA_VERSION` is written and never read), so this is not a
  new defect class. It is filed because **F-060's stated fix does not cover it**: F-060 and F-207
  between them name four read sites in `validateConfiguration()` (`GrayzoneApplication.kt:181,190,199,208`)
  plus `RuntimeSessionReset.kt:29â€“43`. Hardening those five leaves the 30 below exposed, and the
  resulting failure is worse-shaped than a boot crash because it is *partial*: the app launches, the
  poisoned key belongs to one screen, and the user gets an app that dies every time they touch one
  tab while the rest works â€” which reads as a random crash rather than as corrupt state.
- **Enumerated read sites** (direct search over `app/src/main/java/com/grayzone/app/ui` plus the
  composition root):

  | File | Lines | Getter and key |
  |---|---|---|
  | `MainActivity.kt` | 60, 67 | `getString(THEME_MODE)` Ã—2 |
  | `MainActivity.kt` | 91, 123, 193, 199, 205 | `getBoolean(ALL_FILES_PROMPTED, VPN_ENABLED, ACCESSIBILITY_PROMPTED_FOR_LOSS, ONBOARDING_COMPLETED Ã—2)` |
  | `ui/theme/Theme.kt` | 28 | `getString(THEME_MODE)` â€” also reached from `OverlayService.overlayChrome()` at `:1151`, i.e. **outside** any Activity |
  | `ui/HomeScreen.kt` | 46, 55 | `getStringSet(MONITORED_APPS)` Ã—2 |
  | `ui/AppsScreen.kt` | 44, 48 | `getStringSet(MONITORED_APPS)`, `getBoolean(GRAYZONE_ENABLED)` |
  | `ui/AppsScreen.kt` | 191â€“193 | `getLong(ACTIVE_UNTIL/LOCKED_UNTIL/REMAINING_MILLIS + pkg)` Ã—3, **per monitored package, every 2 s** |
  | `ui/LimitsScreen.kt` | 29 | `getStringSet(MONITORED_APPS)` |
  | `ui/LimitsScreen.kt` | 71â€“73 | `getLong(...)` Ã—3 per package, every 2 s |
  | `ui/LimitsScreen.kt` | 103â€“105 | `getBoolean(PER_APP_HAS_CUSTOM)`, `getInt(PER_APP_SESSION_MINUTES / SESSION_MINUTES)`, `getInt(PER_APP_LOCKOUT_MINUTES / LOCKOUT_MINUTES)` â€” un-`remember`ed, inside the item body |
  | `ui/SettingsScreen.kt` | 38, 40â€“42, 48, 54 | `getString(THEME_MODE)`, `getInt` Ã—3, `getStringSet` Ã—2 |
  | `ui/PerAppSettingsSheet.kt` | 33, 36â€“38, 40â€“43 | `getBoolean` Ã—1, `getInt` Ã—7 |
  | `ui/CustomPromptsSheet.kt` | 36, 39 | `getBoolean(USE_CUSTOM_PROMPTS_ONLY)`, `getString(CUSTOM_PROMPTS_JSON)` â€” note `:39` sits **outside** the `try` at `:41`, so a type mismatch here is not caught by F-148's catch either |
  | `Extensions.kt:53â€“55` (called from composition at `AppsScreen.kt:61`, `SettingsScreen.kt:62`) | 53â€“55 | `getLong(...)` Ã—3 per monitored package |
- **Trigger condition**:
  1. Place a snapshot at `/sdcard/.grayzone/` whose `entries` map gives any key above a type other
     than the one the getter expects â€” e.g. `daily_budget_com.example` as a `String`. No attacker is
     required: `applyInto` has no allowlist and `SCHEMA_VERSION` is never read, so an ordinary
     version-to-version type change produces this, exactly as the `grayscale_enabled` (Boolean) â†’
     `theme_mode` (String) rename in `0a41a1a` nearly did.
  2. Clear app data so `restoreIfWiped` applies the snapshot, then launch.
  3. If the poisoned key is not one of `validateConfiguration`'s four, the app launches normally.
  4. Open the tab that reads it (Limits for `per_app_*`, Apps for the per-package longs, the per-app
     sheet for `daily_budget_`). The Activity dies with `ClassCastException` inside composition. It
     will do so on every subsequent visit to that tab.
- **Blast radius**:
  - *Callers*: the whole composition tree â€” `MainActivity.onCreate()` â†’ `MainAppContent()` â†’
    `MainScreen()` â†’ the five tab screens and three sheets. `Theme.kt:28` additionally has a
    non-Activity caller, `OverlayService.overlayChrome()` (`OverlayService.kt:1151`), so a poisoned
    `theme_mode` takes down the **overlay service** as well as the UI â€” and that path stops
    protection rather than merely crashing a screen.
  - *Process death*: does not help. The snapshot is re-read on the next launch, exactly as F-060
    established, and clearing app data does not recover because `MANAGE_EXTERNAL_STORAGE` is an
    appop held by the system.
  - *Config change*: recomposition re-executes the read, so the crash repeats on rotation.
  - *Foreground/background*: `MainActivity.onResume` runs `restoreIfWiped` on every resume
    (`MainActivity.kt:47â€“51`), so the poison can be (re-)applied under a live composition and then
    fire on the next recomposition.
  - *Cross-process*: **yes, via one site.** `Theme.kt:28` is called by `OverlayService`, which is in
    the default process; `:a11y` is not involved. But it means the same unguarded read is on both a
    UI path and a protection path.
  - *Persistence*: reads only. No writes from these sites.
  - *Release-only*: R8 strips line numbers because `-keepattributes SourceFile,LineNumberTable` is
    absent from `app/proguard-rules.pro` (recorded in `evidence/r8.md`, propagated to T025), so in
    release the crash report will not identify which of the 30 reads threw.
- **Fix** (minimal): **do not** wrap 30 call sites. The correct minimal fix is the one F-060 already
  needs â€” add a key/type allowlist to `StateBackupManager.applyInto` and gate the restore on
  `SCHEMA_VERSION` â€” because that removes the only realistic way for a preference to acquire the
  wrong type. This finding's actionable content is a scope correction on F-060: **the fix must be
  the allowlist in `applyInto`, not per-getter guards in `validateConfiguration`**, and T034 should
  record that guarding `validateConfiguration` alone changes an unlaunchable app into a
  crash-on-one-tab app rather than resolving the defect. The single site worth guarding locally is
  `Theme.kt:28`, because it is the only one reachable from the overlay service:

  ```kotlin
  val mode = try {
      prefs.getString(PrefsKeys.THEME_MODE, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM
  } catch (e: ClassCastException) {
      GrayzoneLogger.e(LogComponent.PREFS, "theme_mode has wrong type; falling back to SYSTEM", e)
      ThemeMode.SYSTEM
  }
  ```
- **Rewrite justification**: not applicable; the recommendation is explicitly *against* a broad
  change and in favour of one upstream guard plus one local guard.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/StateBackupAllowlistTest.kt`, asserting that
  `applyInto` given an entries map containing `daily_budget_com.example -> "60"` (String where an
  Int is expected) writes **nothing** for that key and leaves the existing Int intact, using the
  existing `FakeSharedPreferences.kt`. Fails before (the value is written as a String and the next
  `getInt` throws), passes after. This test belongs to the F-060 fix and is named here so the two
  findings share one test rather than two.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). The 30 unguarded reads are
  `confirmed` by direct search; what is not confirmed is that a type-mismatched preference is
  actually reachable in the field.
  - *Missing*: a demonstration that `applyInto` writes a value whose type differs from what a
    getter expects. The audit has established the *mechanism* (no allowlist, no schema gate) but not
    an observed instance.
  - *Procedure*: on any API 30+ device with all-files access granted, write
    `/sdcard/.grayzone/<snapshot>` with `daily_budget_com.android.chrome` as a JSON string, clear app
    data, relaunch, and open the Limits tab. Expect `java.lang.ClassCastException:
    java.lang.String cannot be cast to java.lang.Integer` from `LimitsScreen`.
  - *Severity effect*: confirmed reachable â†’ **High**, and the `Theme.kt:28` variant that takes down
    `OverlayService` would be **Critical** (protection stops). Unreachable in practice â†’ **Low**
    (defensive hygiene). Held at **Medium** because the audit has already declined to rate an
    unconfirmed harmful chain Critical (the C4 precedent), and because F-060 owns the chain.
- **Build scope**: everyday-build
- **Constitution**: III (30 I/O boundaries with no failure handling â€” these are part of the 84
  unprotected boundaries counted in `evidence/error-handling-inventory.md`)
- **Affected surfaces**: `LimitsScreen.kt:103â€“105` deserves a separate note for T020/T024: besides
  being unguarded, it is the **third** independent inline re-derivation of per-app-vs-global limit
  resolution â€” after `OverlayService.kt:836â€“843` and `:451â€“458` â€” while `PerAppLimits.waitSeconds()`
  (`PerAppLimits.kt:15`), which exists precisely to do this with a validated fallback, remains
  test-only. `LimitsScreen`'s copy also hardcodes the defaults `10` and `60` a fourth time. Routing
  all three through `PerAppLimits` is the same fix the per-app-validation lead in `findings.md`
  already calls for; RU-07 contributes the third call site.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-150 â€” The per-app settings sheet has no lock gate and is reachable during enforcement through an ungated icon button; its always-enabled daily-budget slider ends an in-force budget lock instantly  [High]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` â†’ `PremiumAppListItem()`
  (lines 334â€“346, the `IconButton` whose only condition is `isMonitored`) and
  `app/src/main/java/com/grayzone/app/ui/PerAppSettingsSheet.kt` â†’ `PerAppSettingsSheet()`
  (the daily-budget `Slider`, lines 175â€“186, which has no `enabled` parameter at all)
- **Issue**: `SettingsScreen` deliberately locks the global limits while enforcement is in progress:
  it computes `anyAppLockedOrActive` (`:61â€“63`), disables all three sliders and both sheet buttons
  (`:165,182,207,222,239`), and tells the user why â€” *"Settings are locked while an app is active,
  paused, or in timeout."* (`:110`). That is a stated anti-bypass rule. The per-app path does not
  implement it. `PerAppSettingsSheet` never computes `anyAppLockedOrActive`, never consults
  `isAnyAppLocked`, and gates its sliders only on `hasCustom` â€” whose own switch (`:74â€“86`) is
  unconditionally enabled. Reachability is the second half: `PremiumAppListItem` correctly withholds
  the row's `combinedClickable` when `isToggleEnabled` is false (`:284â€“290`), but then renders a
  settings `IconButton` wired to the same `onLongPress` callback with **`isMonitored` as its only
  condition** (`:334â€“346`). So the door the row closes during a lockout, the gear icon leaves open.

  The sharpest consequence is the daily budget. `DailyBudget.remainingMs` recomputes from
  `DAILY_BUDGET_MINUTES` on every evaluation (`DailyBudget.kt:11â€“20`), and the policy engine's
  budget gate is `state.isBudgetExhausted()` â†’ `budgetRemainingMs <= 0`
  (`SessionPolicyEngine.kt:70â€“73`, `SessionModels.kt:32`). The budget lock is therefore **not**
  materialised as a timestamp the way a lockout is â€” it is derived live. Dragging the budget slider
  from 30 m to 240 m while a `BUDGET_LOCK` overlay is in force makes `remainingMs` positive, and the
  next foreground evaluation lets the app through. The user escapes a daily budget by opening a
  sheet the app believes it has locked.
- **Trigger condition**:
  1. Monitor an app, long-press it, set Daily Budget to the minimum (5 m), close the sheet.
  2. Use the monitored app until the budget lock overlay appears.
  3. Return to Grayzone â†’ Apps tab. The row is tinted and its switch and row-tap are disabled, as
     designed.
  4. Tap the **gear icon** on the same row. The sheet opens.
  5. Drag "Daily Budget" to 4h 0m and release. Close the sheet.
  6. Open the monitored app. It opens. No budget lock. Nothing was logged and nothing warned the
     user that a supposedly-locked setting had been changed.
  7. Repeat steps 3â€“4 during a *lockout* (not a budget lock) and set "Wait Duration" to 3 s to
     confirm the same door works for the friction timer.
- **Blast radius**:
  - *Callers*: `PerAppSettingsSheet()` â† `AppsScreen()` (`AppsScreen.kt:258`), single call site,
    driven by `selectedAppForSettings` which is set from `onLongPress` â€” invoked from two places,
    the row's `combinedClickable` (gated) at `:287` and the `IconButton` (ungated) at `:336`.
    Downstream readers of the keys this sheet writes: `OverlayService.kt:836â€“843`
    (`PER_APP_WAIT_SECONDS`), `:451â€“458` (`PER_APP_SESSION_MINUTES`, `PER_APP_LOCKOUT_MINUTES`),
    `:461â€“468` and `:1076â€“1084` (`DAILY_BUDGET_MINUTES`), plus `LimitsScreen.kt:103â€“105` and
    `StreakEvaluator.kt:22â€“25`.
  - *Process death*: no effect â€” the values are persisted immediately on slider release.
  - *Config change*: closes the sheet (F-154), which is an accidental partial mitigation and not a
    control.
  - *Foreground/background*: the budget escape takes effect on the **next** `AppForegrounded`
    evaluation, so the user must background Grayzone and reopen the target app â€” which is the normal
    flow anyway.
  - *Cross-process*: none directly, but the policy consumer is `OverlayService` in the default
    process and the trigger is an `AccessibilityEvent` from `:a11y`, so the escape is observed one
    IPC hop away from where it is written.
  - *Persistence*: **yes** â€” `PER_APP_HAS_CUSTOM` (`:78`), `PER_APP_WAIT_SECONDS` (`:104`),
    `PER_APP_SESSION_MINUTES` (`:118`, `:140`), `PER_APP_LOCKOUT_MINUTES` (`:141`),
    `DAILY_BUDGET_MINUTES` (`:180`), and `LOCKED_UNTIL` (`:154`), all via `apply()`.
  - *Release-only*: none.
- **Fix** (minimal): apply the gate the app already wrote, at both the door and the controls.
  1. In `AppsScreen`, pass the existing per-row `isToggleEnabled` through to the gear:
     `if (isMonitored && !isLocked && !isActiveOrPaused) { IconButton(...) }` â€” or, better, hoist
     `anyAppLockedOrActive` (already computed at `AppsScreen.kt:60â€“62`) and gate on it so the rule
     matches `SettingsScreen`'s wording exactly.
  2. In `PerAppSettingsSheet`, accept a `settingsLocked: Boolean` parameter, apply
     `enabled = !settingsLocked` to the `hasCustom` switch and the daily-budget slider (the three
     limit sliders already have an `enabled` expression to extend), and render the same
     `Settings are locked while an app is active, paused, or in timeout.` banner the global screen
     shows. Passing the flag in is what makes the two screens provably consistent; recomputing it
     inside the sheet would create a fourth copy of the predicate.
- **Rewrite justification**: not applicable â€” one condition added at the call site and one parameter
  threaded into the sheet.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/LockGuardLogicTest.kt` (extend the existing file), asserting a
  new pure `SettingsGate.isEditable(anyAppLockedOrActive: Boolean, scope: Scope)` returns `false`
  for both `Scope.GLOBAL` and `Scope.PER_APP` when a lock is in force â€” i.e. that the two scopes
  cannot diverge. Fails before (no such function exists and the per-app path has no gate), passes
  after. `LockGuardLogicTest.kt` already covers `isAnyAppLocked` with `FakeSharedPreferences`, so the
  fixtures exist. A follow-up `instrumented` test in
  `app/src/androidTest/java/com/grayzone/app/ui/PerAppSheetGateTest.kt` should assert the gear icon
  has no click action while the row is locked; **it cannot be executed here** (no device,
  `ui-test-junit4` not declared).
- **Existing test conflict**: none. `LockGuardLogicTest.kt` asserts `isAnyAppLocked` behaviour and
  is unaffected by adding a caller-side gate.
- **Verification**: `confirmed` (evidence tier T1) for both halves, statically:
  the `IconButton`'s only condition is `isMonitored` (`AppsScreen.kt:334`), the daily-budget slider
  has no `enabled` argument (`PerAppSettingsSheet.kt:175â€“186`), and
  `DailyBudget.remainingMs`/`SessionPolicyEngine:70` recompute the budget from the live preference
  rather than from a stored deadline. No device is needed to establish that the gate is absent; a
  device would only measure how quickly step 6 succeeds.
- **Build scope**: everyday-build
- **Constitution**: II (the same anti-bypass rule is implemented on one screen and absent on the
  other, and the "is this locked" predicate is derived independently in `SettingsScreen.kt:61`,
  `AppsScreen.kt:60`, and per-row in `PremiumAppListItem` â€” the `AppLockState`-accessor problem
  F-079 describes, seen from the enforcement side)
- **Affected surfaces**: the "Block Immediately for 24 Hours" button in the same sheet
  (`PerAppSettingsSheet.kt:151â€“161`) has its own defect â€” F-151. `SettingsScreen`'s gate is the
  correct model and should not be changed.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

---

### F-151 â€” "Block Immediately for 24 Hours" writes only `LOCKED_UNTIL` and never clears the session keys, so it is a no-op whenever an active session is live  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/PerAppSettingsSheet.kt` â†’
  `PerAppSettingsSheet()`, the "Block Immediately for 24 Hours" `Button` (lines 151â€“161)
- **Issue**: The handler is three statements: compute `now + 24h`, `putLong(LOCKED_UNTIL + pkg, â€¦)`,
  `onDismiss()`. It does not touch `ACTIVE_UNTIL + pkg` or `REMAINING_MILLIS + pkg`. The policy
  engine checks `state.hasActiveSession(now)` at `SessionPolicyEngine.kt:56` and returns
  `DismissOverlay` **before** it reaches the lockout gate at `:62` â€” an ordering the code documents
  as deliberate and correct for its own reason (`:50â€“55`: during a normal session `lockedUntil` is
  already `activeUntil + lockout`, so checking lockout first would show a wrong countdown). The
  consequence for this button is that while `activeUntil` is in the future, the app the user just
  asked to block for 24 hours stays fully usable, the sheet closes as if the action succeeded, and no
  feedback of any kind is given. The button is the app's only "block this right now" affordance, so a
  silent no-op on it is a protection promise the app does not keep.

  The paused-session case is **not** affected and should not be reported as such: with `activeUntil`
  in the past and `REMAINING_MILLIS > 0`, the lockout gate at `:62` is reached before the
  resume-paused-session branch at `:76`, so the 24-hour block is honoured. This narrowing is why the
  finding is Medium.
- **Trigger condition**:
  1. Monitor an app. Open it, clear the friction screen, and start a session.
  2. Reach Grayzone's Apps tab **without** the accessibility service delivering the
     `AppForegrounded`â†’background transition for the monitored app â€” because that transition is what
     pauses the session and clears `activeUntil` (`SessionPolicyEngine.kt:118â€“131`). Two ways to get
     there: disable the accessibility service momentarily, or use a launcher gesture the service does
     not report. This is the step that needs device confirmation.
  3. Tap the gear icon on the row (reachable during a session per F-150), then
     "Block Immediately for 24 Hours".
  4. Reopen the monitored app. It opens normally. Confirm the write landed:
     `LOCKED_UNTIL + pkg` is `now + 86400000` in the prefs XML while the app is still usable.
- **Blast radius**:
  - *Callers*: single site â€” the button in `PerAppSettingsSheet`, reachable only from `AppsScreen`
    (`AppsScreen.kt:258`). Readers of `LOCKED_UNTIL`, from `evidence/reference-index.md:197`
    re-verified by direct search: `Extensions.isAnyAppLocked`, `data/RuntimeSessionReset.kt`,
    `ui/AppsScreen.kt:192`, `service/OverlayService.kt`, `ui/LimitsScreen.kt:72`.
  - *Process death*: none â€” the write is committed before the sheet closes.
  - *Config change*: closes the sheet mid-flow (F-154); if it happens before the tap, nothing is
    written at all and the user believes they blocked the app.
  - *Foreground/background*: **the deciding axis.** Whether `activeUntil` is still in the future when
    the user reaches this button depends entirely on whether `:a11y` delivered the backgrounding
    event for the monitored app.
  - *Cross-process*: **yes.** The state that determines whether this button works is cleared by
    `OverlayService` in response to an `AccessibilityEvent` originating in `:a11y`. A missed or
    dropped event leaves `activeUntil` stale and silently disarms this button â€” so the two processes
    can disagree about whether a session is live, and the UI has no way to tell.
  - *Persistence*: **yes** â€” writes `PrefsKeys.LOCKED_UNTIL + pkg` via `apply()` (`:154`).
  - *Release-only*: none.
- **Fix** (minimal): clear the session keys in the same edit, so the write cannot be shadowed by the
  gate that precedes it:

  ```kotlin
  onClick = {
      val until = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
      prefs.edit()
          .putLong(PrefsKeys.LOCKED_UNTIL + pkg, until)
          .putLong(PrefsKeys.ACTIVE_UNTIL + pkg, 0L)
          .putLong(PrefsKeys.REMAINING_MILLIS + pkg, 0L)
          .apply()
      onDismiss()
  }
  ```

  Clearing `REMAINING_MILLIS` as well is deliberate: without it, the leftover paused session is
  resumed the instant the 24 hours elapse (`SessionPolicyEngine.kt:76â€“88`), which is not what
  "block immediately for 24 hours" means to a user.
- **Rewrite justification**: not applicable; two keys added to an existing edit.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/policy/SessionPolicyEngineForegroundTest.kt` (extend the
  existing file), asserting that for a state with `activeUntil = now + 5 min` and
  `lockedUntil = now + 24 h`, `evaluate(AppForegrounded, â€¦)` currently returns `DismissOverlay`, and
  that after the fix â€” modelled as `activeUntil = 0` â€” it returns `ShowLockoutScreen`. Fails before
  (the first assertion documents the defect; the second cannot be satisfied), passes after.
  `PolicyTestFixtures.kt` already provides the `SessionState` builder.
- **Existing test conflict**: **yes, and it matters.** `SessionPolicyEngineForegroundTest.kt` and
  `SessionStatePredicateTest.kt` encode the active-session-before-lockout ordering as correct
  behaviour, which it is. The fix must therefore change the **caller**, not the engine â€” any
  "fix" that reorders the gates will break those tests and reintroduce the wrong-countdown bug the
  comment at `SessionPolicyEngine.kt:50â€“55` documents. Recording this explicitly so T034 does not
  tier this as a policy change.
- **Verification**: `needs-verification` (evidence tier T2). The write set is `confirmed` by reading
  the handler, and the gate ordering is `confirmed` at `SessionPolicyEngine.kt:56â€“65`. What is
  unconfirmed is reachability.
  - *Missing*: a device demonstration that a user can reach this button while `ACTIVE_UNTIL + pkg` is
    still in the future â€” i.e. that the `:a11y` backgrounding event can be missed or delayed.
  - *Procedure*: on any API 29+ device, start a session on a monitored app, then
    `adb shell settings put secure enabled_accessibility_services ""` to drop the service without
    killing Grayzone, open Grayzone, tap the gear and "Block Immediately", re-enable the service, and
    reopen the monitored app. Also worth trying the same flow with a recents-switch and with a
    launcher swipe, since `GrayzoneAccessibilityService` reacts to window-state-change events only.
  - *Severity effect*: reachable without disabling the service â†’ **High** (an explicit protection
    action silently does nothing). Reachable only by disabling accessibility â†’ stays **Medium**
    (protection is already off in that state). Not reachable at all â†’ **Low** (defensive
    completeness, since `REMAINING_MILLIS` is still left set and revives the old session after the
    block).
- **Build scope**: everyday-build
- **Constitution**: none directly. It is a missing-write defect rather than an error-handling or
  duplication one.
- **Affected surfaces**: `OverlayService`'s own lock-setting paths write `ACTIVE_UNTIL`,
  `LOCKED_UNTIL`, and `REMAINING_MILLIS` together (`SessionCommand.UpdateState`,
  `SessionPolicyEngine.kt:81â€“86`, `:121â€“131`), which is the correct pattern and the model this
  handler should follow. `AppsScreen.kt:239â€“243` is the only other RU-07 site that writes this key
  family, and it removes all three together â€” also correct in shape, though see F-157.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-152 â€” The schedule-creation flow is three levels of `remember`-only state, so a single configuration change silently discards an entire in-progress rule  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` â†’ `SettingsScreen()`
  (`showSchedule`, line 45; the conditional composition at lines 256â€“258) â†’
  `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` â†’ `ScheduleSheet()`
  (`showAddRuleDialog`, line 40; `focusDuration`, line 37) â†’ `AddRuleDialog()` (lines 216â€“227)
- **Issue**: Reaching the "Add Custom Schedule" dialog requires three nested pieces of visibility and
  form state, and every one of them is a plain `remember`: `showSchedule` in `SettingsScreen`,
  `showAddRuleDialog` in `ScheduleSheet`, and seven form fields in `AddRuleDialog` (`name`,
  `startHour`, `startMin`, `endHour`, `endMin`, `weekdaysSelected`, `weekendsSelected`). Because
  `MainActivity` declares no `android:configChanges`, any configuration change destroys all three
  levels at once. The user is returned to the Settings tab with the sheet closed, the dialog gone,
  and the rule they were composing â€” name, both times, and day selection â€” discarded. Nothing is
  written until the Save button, so there is no partial recovery, and no message is shown. The same
  gesture also resets `focusDuration` to 30, so a user who had dialled a 90-minute deep-work block
  and rotated gets 30 without noticing.

  The configuration changes that do this are not exotic: rotation, a system dark-mode switch (which
  this app's own Settings screen encourages the user to interact with), a font-scale change, and a
  locale change. Two of them are things a user may plausibly do *while* configuring a schedule.
- **Trigger condition**:
  1. Settings â†’ "Manage Schedule Focus Modes" â†’ "+ Add Custom Schedule".
  2. Type a name, set a start and end time, tick Weekends.
  3. Rotate the device (or `adb shell settings put system font_scale 1.15`, or toggle system dark
     mode with `adb shell cmd uimode night yes`).
  4. Observe: the dialog, the sheet, and every field are gone, and the app is showing the Settings
     tab. Repeat and observe that the Home tab is showing instead, because `selectedTab` is also lost
     (F-076).
- **Blast radius**:
  - *Callers*: `ScheduleSheet()` â† `SettingsScreen()` (`SettingsScreen.kt:257`), single site.
    `AddRuleDialog()` â† `ScheduleSheet()` (`ScheduleSheet.kt:58`), single site. Confirmed against
    `evidence/reference-index.md:345â€“352`, which lists `ui/ScheduleSheet.kt` as the sole UI consumer
    of `ScheduleRule` and of `addRule`/`removeRule`/`toggleRule`.
  - *Process death*: total loss, same as configuration change, and additionally the sheet's
    `rules`/`isFocusMode` re-seed from `ScheduleManager` â€” which is the C3 path, so a corrupted
    `SCHEDULE_RULES_JSON` re-reads as an empty rule list here too.
  - *Config change*: **the whole finding.** No level of the three is saveable.
  - *Foreground/background*: unaffected â€” backgrounding does not destroy the composition, and
    `ResumedInterval` (`ScheduleSheet.kt:43`) correctly suspends the focus countdown while not
    resumed.
  - *Cross-process*: none.
  - *Persistence*: nothing is written until `onSave` â†’ `scheduleManager.addRule(rule)`
    (`ScheduleSheet.kt:62`), which is exactly why the loss is total.
  - *Release-only*: none.
- **Fix** (minimal): convert all nine states to `rememberSaveable`. Every one is a `String`, `Int`, or
  `Boolean`, so no `Saver` is needed:

  ```kotlin
  var showSchedule by rememberSaveable { mutableStateOf(false) }        // SettingsScreen.kt:45
  var showAddRuleDialog by rememberSaveable { mutableStateOf(false) }   // ScheduleSheet.kt:40
  var focusDuration by rememberSaveable { mutableStateOf(30) }          // ScheduleSheet.kt:37
  var name by rememberSaveable { mutableStateOf("My Schedule") }        // AddRuleDialog.kt:216
  // â€¦and startHour, startMin, endHour, endMin, weekdaysSelected, weekendsSelected
  ```

  This is the F-145 mechanism applied to the one flow where the loss is a whole user-authored
  object rather than a scroll position.
- **Rewrite justification**: not applicable â€” nine single-word substitutions.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/ScheduleFormRestorationTest.kt`, using
  `StateRestorationTester.emulateSavedInstanceStateRestore()` to assert that after restoration the
  Add-Rule dialog is still displayed and its name field still contains the typed text. Fails before
  (the dialog is gone), passes after. **Cannot be executed here**: no device, and
  `ui-test-junit4` is not a declared dependency (see F-145).
- **Existing test conflict**: none. `ScheduleManagerTest.kt`, `ScheduleMatchingTest.kt`, and
  `ScheduleLogicTest.kt` all drive `ScheduleManager` directly and never compose the sheet, so they
  cannot detect this and are unaffected by the fix.
- **Verification**: `confirmed` (evidence tier T1). Every one of the nine declarations is a plain
  `remember` in the cited lines, and `AndroidManifest.xml:55â€“58` declares no `configChanges` for
  `MainActivity`, so Activity recreation on a configuration change is the platform default. No device
  is needed to establish either fact.
- **Build scope**: everyday-build
- **Constitution**: I (in-progress user input discarded without notice is a correctness gap, not a
  style one)
- **Affected surfaces**: `CustomPromptsSheet.kt:51` (`newPrompt`) and `VpnScreen.kt:56â€“57`
  (`statusMessage`, `showEnableConfirm`) lose in-progress input the same way but each loses a single
  field rather than a composite object; they are rows in F-145's table. `QuickFocusCard.kt:43`
  (`selectedMinutes`) is the same one-field case.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-153 â€” `AddRuleDialog` shows a platform `TimePickerDialog` from a Compose click handler with no disposal, leaking its window when the Activity is recreated  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` â†’ `AddRuleDialog()`, the
  two `TimePickerDialog(...).show()` calls (lines 253â€“257 for start time and 270â€“274 for end time)
- **Issue**: Both time pickers are raw `android.app.TimePickerDialog` instances constructed with
  `context` (`LocalContext.current`, i.e. the Activity) and shown imperatively from a Compose
  `onClick`. Nothing holds a reference to either dialog, so nothing can dismiss it: there is no
  `DisposableEffect`, no `onDispose { dialog.dismiss() }`, and no `rememberSaveable` state to
  recreate it from. Two consequences follow.

  First, if the Activity is destroyed while a picker is showing â€” which any configuration change does,
  since `MainActivity` declares no `configChanges` â€” the dialog's window is still attached to a dead
  Activity and Android logs `android.view.WindowLeaked: Activity com.grayzone.app.MainActivity has
  leaked window ... that was originally added here`. The picker vanishes along with the entire
  enclosing dialog and sheet (F-152), so the user loses their place and the framework records a leak.

  Second, the `OnTimeSetListener` closes over the `startHour`/`startMin` setters of the composition
  that created it. If the picker outlives that composition, the callback writes into discarded
  `MutableState` objects, so a time the user *did* pick lands nowhere. There is no crash and no
  message; the button simply still shows the old time.
- **Trigger condition**:
  1. Settings â†’ "Manage Schedule Focus Modes" â†’ "+ Add Custom Schedule" â†’ tap the Start Time button.
  2. With the time picker open, rotate the device.
  3. Observe the picker, the dialog, and the sheet all disappear, and check logcat:
     `adb logcat -d | findstr WindowLeaked` â€” expect a `WindowLeaked` entry naming `MainActivity`.
  4. Variant that produces the silent-write case: with the picker open, run
     `adb shell settings put system font_scale 1.3` from another shell, then set a time in the picker
     if it is still interactive. The chosen time is not reflected anywhere.
- **Blast radius**:
  - *Callers*: `AddRuleDialog()` â† `ScheduleSheet()` (`ScheduleSheet.kt:58`), single site. These are
    the only two `TimePickerDialog` constructions in the codebase â€” direct search over `app/src`
    returns exactly `ScheduleSheet.kt:254` and `:271`.
  - *Process death*: not applicable; the window dies with the process.
  - *Config change*: **the whole finding.** The leak and the lost callback both require Activity
    recreation, which any configuration change performs here.
  - *Foreground/background*: backgrounding does not destroy the Activity, so the picker survives it
    correctly.
  - *Cross-process*: none.
  - *Persistence*: none â€” the picker writes only to composition state.
  - *Release-only*: none, though the `WindowLeaked` trace will carry no line numbers in release
    because `-keepattributes SourceFile,LineNumberTable` is absent (`evidence/r8.md`).
- **Fix** (minimal): keep the picker's visibility in saveable state and let Compose own the window,
  rather than showing an imperative dialog. Since `startHour`/`startMin` become `rememberSaveable`
  under F-152, only the *which picker is open* flag needs adding:

  ```kotlin
  var picker by rememberSaveable { mutableStateOf<String?>(null) }   // null | "start" | "end"
  // â€¦Button(onClick = { picker = "start" })
  if (picker != null) {
      val isStart = picker == "start"
      DisposableEffect(picker) {
          val dialog = TimePickerDialog(context, { _, h, m ->
              if (isStart) { startHour = h; startMin = m } else { endHour = h; endMin = m }
              picker = null
          }, if (isStart) startHour else endHour, if (isStart) startMin else endMin, true)
          dialog.setOnCancelListener { picker = null }
          dialog.show()
          onDispose { dialog.dismiss() }
      }
  }
  ```

  The `onDispose { dialog.dismiss() }` is the part that closes the leak; the saveable flag is what
  makes the picker come back after recreation instead of silently disappearing. A pure-Compose
  `TimePicker` from Material3 would also work and would fix F-162's light-themed-picker problem in
  the same change â€” but that is a larger edit, and the minimal fix above is sufficient for this
  finding.
- **Rewrite justification**: not applicable. The minimal patch above is a local restructuring of one
  `onClick`; a full migration to Material3 `TimePicker` is noted as an option, not a requirement.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/TimePickerLifecycleTest.kt`, asserting that after
  opening the start-time picker and calling
  `StateRestorationTester.emulateSavedInstanceStateRestore()`, no `WindowLeaked` is reported and the
  picker is displayed again. Fails before (the picker is gone and the framework logs a leak), passes
  after. **Cannot be executed here**: no device, and `ui-test-junit4` is not declared. A
  `WindowLeaked` assertion additionally requires reading logcat from the test, so the practical
  version asserts only that the picker is re-displayed.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). That no disposal exists is `confirmed`
  by reading lines 250â€“280; that the framework emits `WindowLeaked` for this exact shape is
  documented platform behaviour but has not been observed on a device here.
  - *Missing*: logcat from a device showing `WindowLeaked` after step 2.
  - *Procedure*: steps 1â€“3 above on any API 29+ device, then `adb logcat -d | findstr WindowLeaked`.
  - *Severity effect*: leak confirmed â†’ stays **Medium** (a leaked window plus lost input, both
    recoverable by re-entering the flow). If the platform reaps the token silently with no leak and
    no user-visible artefact beyond the F-152 loss â†’ **Low**, and the finding collapses into F-152.
- **Build scope**: everyday-build
- **Constitution**: none directly; it is a lifecycle-ownership defect.
- **Affected surfaces**: these are the only two imperative platform dialogs in the app. Every other
  dialog and sheet is a Compose `AlertDialog` or `ModalBottomSheet`
  (`VpnScreen.kt:383`, `ScheduleSheet.kt:229`, and the three `ModalBottomSheet`s), all of which are
  correctly owned by the composition and dismissed with it.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-154 â€” Sheet visibility is held in non-saveable state, and `AppsScreen` keys it on an `AppInfo` that carries a `Bitmap`, so every sheet closes on any configuration change  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` â†’ `AppsScreen()`
  (`selectedAppForSettings`, line 49; the conditional composition at lines 257â€“262) and
  `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` â†’ `SettingsScreen()`
  (`showCustomPrompts`, line 44; the conditional composition at lines 253â€“255)
- **Issue**: All three `ModalBottomSheet`s in the app are composed conditionally on a plain
  `remember`ed flag, so a configuration change closes them. `AppsScreen`'s case is the one that
  cannot be fixed by a one-word substitution: `selectedAppForSettings` is a
  `mutableStateOf<AppInfo?>`, and `AppInfo` (`AppUtils.kt:13`) is
  `data class AppInfo(val packageName: String, val name: String, val icon: Bitmap?)` â€” not
  `Parcelable`, and holding a decoded `Bitmap`, so it cannot be handed to `rememberSaveable` without
  a custom `Saver` and must not be, since bundling a bitmap risks
  `TransactionTooLargeException`. The user-visible effect: while the user is adjusting a monitored
  app's wait/session/lockout/budget values â€” the most consequential settings in the app â€” a rotation
  or a system dark-mode switch closes the sheet with no warning, and because the search query is lost
  at the same time (F-145), they are returned to an unfiltered list and have to find the app again.
  Values already released on a slider are persisted, so the result is a **half-applied**
  configuration: some sliders committed, the rest silently abandoned.
- **Trigger condition**:
  1. Apps tab. Type a query to filter to one app, long-press it (or tap its gear icon) to open the
     per-app sheet.
  2. Adjust "Wait Duration" and release, then start dragging "Daily Budget".
  3. Rotate the device.
  4. Observe: the sheet is closed, the search box is empty, and the list is unfiltered. Re-open the
     sheet and confirm Wait Duration kept its new value while Daily Budget did not â€” a partially
     applied edit with no indication of which half landed.
- **Blast radius**:
  - *Callers*: `PerAppSettingsSheet()` â† `AppsScreen.kt:258`; `CustomPromptsSheet()` â†
    `SettingsScreen.kt:254`; `ScheduleSheet()` â† `SettingsScreen.kt:257` (the third is covered by
    F-152). `AppInfo` is constructed in `AppUtils.getInstalledApps()` (`:89`) and consumed by
    `ui/AppsScreen.kt` and `ui/PerAppSettingsSheet.kt`, matching
    `evidence/reference-index.md:98â€“99`.
  - *Process death*: total loss, and additionally `peekCachedApps()` returns `null` in a fresh
    process, so `isLoading` is `true` and the list shows a spinner before the app can be found again.
  - *Config change*: **the whole finding.**
  - *Foreground/background*: sheets correctly survive backgrounding; the composition is not
    destroyed.
  - *Cross-process*: none.
  - *Persistence*: indirect but important â€” the sheet's sliders persist on release
    (`PerAppSettingsSheet.kt:104,118,139â€“142,180`), so closing it mid-edit leaves a partially written
    per-app configuration in preferences. Combined with the missing per-app validation recorded in
    `findings.md`, a partially applied edit is not self-correcting.
  - *Release-only*: none.
- **Fix** (minimal): make the flags saveable, and key the per-app sheet on the package name rather
  than the object:

  ```kotlin
  // SettingsScreen.kt:44â€“45
  var showCustomPrompts by rememberSaveable { mutableStateOf(false) }

  // AppsScreen.kt:49
  var selectedPkgForSettings by rememberSaveable { mutableStateOf<String?>(null) }
  // â€¦onLongPress = { selectedPkgForSettings = app.packageName }
  // â€¦at the composition site:
  selectedPkgForSettings
      ?.let { pkg -> filtered.firstOrNull { it.packageName == pkg } }
      ?.let { app -> PerAppSettingsSheet(app = app, onDismiss = { selectedPkgForSettings = null }) }
  ```

  Resolving through the already-composed `filtered` list means no bitmap crosses the bundle, and the
  `?.let` chain handles the case where the app was uninstalled while the process was gone. Do **not**
  write a `Saver` for `AppInfo`; the bitmap must not enter saved instance state.
- **Rewrite justification**: not applicable â€” one substitution plus a five-line lookup at the
  composition site.
- **Test to add**: `instrumented` â€”
  `app/src/androidTest/java/com/grayzone/app/ui/PerAppSheetRestorationTest.kt`, asserting that after
  opening the per-app sheet for a known package and calling
  `StateRestorationTester.emulateSavedInstanceStateRestore()`, the node with text
  `Settings for <app name>` is still displayed. Fails before (the sheet is gone), passes after.
  **Cannot be executed here**: no device, and `ui-test-junit4` is not declared (see F-145).
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). `AppInfo`'s non-`Parcelable`, `Bitmap`-carrying
  shape is at `AppUtils.kt:13`; the three flags are plain `remember` at the cited lines; the
  conditional-composition pattern means loss of the flag unconditionally removes the sheet. All
  static.
- **Build scope**: everyday-build
- **Constitution**: I
- **Affected surfaces**: `VpnScreen.kt:57` (`showEnableConfirm`) has the same shape for the 24-hour
  VPN consent `AlertDialog`; the consequence there is smaller because the user only has to tap the
  toggle again, and the dialog exists precisely to make them think twice.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-155 â€” Home's "Protection ready" state is derived from permission checks only and never from `ProtectionHealthRepository`, so a dead overlay or VPN service still reads green  [High]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` â†’ `HomeScreen()` (state at
  lines 42â€“46, refreshed at 52â€“55; the status card and `ActivePill` at 66â€“68 and 112â€“199)
- **Issue**: Home decides whether the app is protecting the user from three values â€”
  `isAccessibilityServiceEnabled(context)`, `Settings.canDrawOverlays(context)`, and
  `isBatteryOptimized(context)` â€” all of which answer *"is the permission granted?"*, not *"is the
  service running?"*. `isActive = !needsSetup` therefore renders a green `ActivePill` and the
  headline "Protection ready Â· N apps monitored" whenever the two permissions are held, regardless
  of whether `OverlayService` or `AdBlockVpnService` is alive.

  The app already has the correct signal and Home does not read it.
  `ProtectionHealthRepository.status` is a live `StateFlow` written by `OverlayService.kt:142,760`
  and `AdBlockVpnService.kt:226,248,311,319,336`, and its `overlayActive` / `vpnActive` /
  `vpnReconnectPending` fields are exactly this question. **Direct search over
  `app/src/main/java/com/grayzone/app/ui` returns a single UI reader: `VpnScreen.kt:51â€“53`.**
  `HomeScreen` and `LimitsScreen` contain zero references to it.

  This is what makes the audit's Critical findings *silent*. When `windowManager.addView` fails and
  `OverlayService` returns without creating an overlay (**C1**), or when `startForeground` throws in
  `OverlayService.onCreate` and the service dies (**F-205**), the only screen that could tell the
  user shows a green pill and the words "Protection ready". Protection has stopped, the app says it
  has not, and the user has no way to find out. Per the FR-006 Grayzone calibration â€” *"protection
  stopping with no crash and no notification ranks at or above a visible error"* â€” the missing
  signal is the load-bearing part, and this finding is the reason the signal is missing.
- **Trigger condition**:
  1. Complete setup so Home shows the green "Protection ready" card.
  2. Kill the overlay service without touching permissions:
     `adb shell am stopservice com.grayzone.app/.service.OverlayService`
     (or `adb shell am kill com.grayzone.app` and reopen only the Activity, or trigger an OEM
     battery-manager kill on a Xiaomi/Oppo device).
  3. Return to Home and pull it into view. Observe the pill is still green and the headline still
     reads "Protection ready".
  4. Open a monitored app. No friction screen appears. Nothing in the app ever indicated that
     protection had stopped.
- **Blast radius**:
  - *Callers*: `HomeScreen()` â† `MainScreen()` (`MainActivity.kt:306`), single site.
    `ActivePill()` â† `HomeScreen()` only (`evidence/reference-index.md:249`). The signal it should
    consume, `ProtectionHealthRepository`, is written from `service/OverlayService.kt` and
    `service/vpn/AdBlockVpnService.kt` and read at `MainActivity.kt:137` (inside
    `reconcileVpnState`) and `ui/VpnScreen.kt:51`.
  - *Process death*: **makes it worse.** `ProtectionHealthRepository` is an in-memory `StateFlow`
    singleton, so after a process restart it reports its defaults until a service writes to it;
    a fix must therefore not treat "repository says inactive" as proof of failure without also
    checking that the service was asked to start.
  - *Config change*: recreation re-runs the three permission checks, so the wrong answer is
    recomputed identically. There is no configuration under which this self-corrects.
  - *Foreground/background*: the `ON_RESUME` observer at `:49â€“63` refreshes all three permission
    checks, so the screen is diligent about the wrong inputs. Adding the repository to the same
    observer is not sufficient either, since the repository is a flow and should be collected.
  - *Cross-process*: **relevant, and it constrains the fix.** `ProtectionHealthRepository` is an
    in-process singleton, explicitly **not** shared with `:a11y`
    (`evidence/cross-process-inventory.md`, `evidence/reference-index.md:318`). `overlayActive` and
    `vpnActive` are written from the default process and are therefore readable here. Its third
    field, `accessibilityActive`, is **not** usable: the uncommitted `:a11y` process split deleted
    both `updateAccessibilityStatus` call sites, so it has no production writer and is pinned
    `false` (recorded in `evidence/prior-fix-watchlist.md`). A naive fix that switches Home to
    `status.isFullyProtected` would therefore make Home report "not protected" **always** â€” the
    "High if wired naively" trap already recorded in `findings.md`. The correct fix keeps
    `isAccessibilityServiceEnabled()` for the accessibility leg and adds the repository only for the
    overlay and VPN legs.
  - *Persistence*: none â€” this screen only reads.
  - *Release-only*: none.
- **Fix** (minimal): collect the repository alongside the existing permission checks and require
  both, for the two legs that have a working writer:

  ```kotlin
  val health by com.grayzone.app.data.ProtectionHealthRepository.status.collectAsState()
  // â€¦
  val isActive = !needsSetup && health.overlayActive
  ```

  and add a distinct amber state â€” reusing the "Battery may interrupt protection" card shape already
  present at `:132â€“141` â€” reading e.g. *"Permissions are on but the monitoring service isn't
  running"*, with a button that calls `startForegroundService(OverlayService)`. Deliberately **not**
  proposing `isFullyProtected`, for the `accessibilityActive` reason above. `LimitsScreen` should
  get the same treatment or be left alone; it makes no protection claim, so it is out of scope for
  the minimal fix.
- **Rewrite justification**: not applicable â€” one `collectAsState`, one conjunct, one extra branch in
  an existing `when`.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ProtectionStatusTest.kt` (extend the existing file),
  asserting a new pure `ProtectionStatus.homeBanner(accessibilityGranted: Boolean, overlayGranted:
  Boolean, overlayRunning: Boolean, batteryOptimised: Boolean)` returns `Banner.SERVICE_DOWN` for
  `(true, true, false, false)` â€” the case Home currently renders as `Banner.READY`. Fails before (no
  such function; the logic is inline and has no `overlayRunning` input), passes after. Keeping it a
  pure function is what makes it JVM-testable without a device.
- **Existing test conflict**: **yes, in spirit.** `ProtectionStatusTest.kt:14,46` are currently the
  **only** callers of `ProtectionHealthRepository.updateAccessibilityStatus`, which is what makes
  the missing production writer invisible to the suite. Extending this file is the right place, but
  the new cases must not be written against `isFullyProtected`, which is unsatisfiable in production
  until the accessibility writer is restored.
- **Verification**: `confirmed` (evidence tier T1). Established statically: `HomeScreen.kt` contains
  zero references to `ProtectionHealthRepository` (direct search over `ui/`), `isActive` is defined
  as `!needsSetup` at `:68`, and `needsSetup` derives only from the two permission booleans via
  `SetupRequirements.missing(accessibilityOn, overlayOn)` at `:66`. What a device would add is only
  a measurement of how long the stale green state persists.
- **Build scope**: everyday-build
- **Constitution**: II (the app maintains a protection-health model and its primary status screen
  does not consume it, so "is protection working" is answered two different ways in two places)
- **Affected surfaces**: `LimitsScreen` also makes no use of the repository. **Reference-index
  correction for T059â€“T064**: `evidence/reference-index.md:319` lists
  `ProtectionHealthRepository.status` as read by `MainActivity.kt`, `ui/VpnScreen.kt`,
  `ui/HomeScreen.kt`, and `ui/LimitsScreen.kt`, and `:330` lists `ProtectionStatus`'s four
  properties as read by `ui/VpnScreen.kt`, `ui/HomeScreen.kt`, and `ui/LimitsScreen.kt`. **Both rows
  are wrong.** Direct search over `app/src/main/java/com/grayzone/app/ui` returns exactly two
  matches, `VpnScreen.kt:51` and `:52`; `HomeScreen.kt` and `LimitsScreen.kt` have none. This is a
  third confirmed reference-index error after `AdBlockVpnService.onRevoke()` and
  `DnsTrafficBus.clear()`, and it is the dangerous direction â€” it makes a real coverage gap look
  like it is already covered.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-156 â€” `QuickFocusCard` and `DailyBreakCard` are the only Home cards with no resume re-read, so focus and break state go stale in the background and "Start" re-arms a window that is already running  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/QuickFocusCard.kt` â†’ `QuickFocusCard()`
  (lines 29â€“41, 100â€“112) and `app/src/main/java/com/grayzone/app/ui/DailyBreakCard.kt` â†’
  `DailyBreakCard()` (lines 32â€“46, 110â€“125)
- **Issue**: Both cards seed their state from `ScheduleManager` in `remember` initialisers and then
  rely solely on `ResumedInterval(enabled = â€¦)` to keep it fresh. The gate is the problem: the
  interval only runs while the state it is meant to correct is already `true`. If focus mode or the
  daily break **starts or ends while these cards are composed but the app is backgrounded**, nothing
  re-reads it. `HomeScreen` itself (`:49â€“63`) and `StreakCard` (`:35â€“45`) both install an `ON_RESUME`
  observer; these two cards, composed as siblings inside the same `LazyColumn`
  (`HomeScreen.kt:201â€“203`), do not. Three concrete consequences:

  1. Focus mode started elsewhere â€” from `ScheduleSheet` on the Settings tab (`ScheduleSheet.kt:141`)
     â€” leaves `QuickFocusCard` showing "Deep Work / Start". Tapping Start calls
     `scheduleManager.startFocusMode(selectedMinutes)` again, which **re-arms the window from now**,
     silently extending or truncating a focus block the user did not intend to change.
  2. `DailyBreakCard.canStartToday` is captured once and refreshed only inside the
     `enabled = isBreakActive` interval. Crossing midnight while Home stays composed therefore leaves
     the card reading "Used for today Â· resets at midnight" with no Start button, on a day when the
     break is in fact available. The feature is unavailable until the tab is left and re-entered or
     the process is recycled.
  3. Symmetrically, a break or focus window that ended while backgrounded leaves the card claiming it
     is active until the next resume â€” though this case *does* self-heal, because the interval runs
     with `enabled = true` and detects `remaining <= 0` on its first tick after resuming.
- **Trigger condition**:
  1. Home tab. Confirm the Deep Work card shows "Start".
  2. Without leaving Home, background the app. From Settings on a second run â€” or more simply, let a
     schedule rule fire â€” start focus mode. To force it deterministically:
     `adb shell am start -n com.grayzone.app/.MainActivity`, go to Settings â†’ Manage Schedule Focus
     Modes â†’ Start Focus Mode, then **use the back gesture rather than the tab bar** so Home is not
     recomposed, and background the app.
  3. Foreground the app and look at Home. The Deep Work card still reads "Lock all apps for 30 mins"
     with a Start button, while focus mode is running.
  4. Tap Start and observe from `ScheduleManagerTest`'s key
     (`PrefsKeys.FOCUS_MODE_ACTIVE` and its end timestamp) that the window was re-armed from now.
  5. For the midnight case: start a daily break, let it finish, keep Home composed past local
     midnight, and observe the card still reads "Used for today".
- **Blast radius**:
  - *Callers*: `QuickFocusCard()` â† `HomeScreen.kt:201`; `DailyBreakCard()` â† `HomeScreen.kt:202`.
    Both single sites. The state they read is written by `ScheduleManager.startFocusMode` /
    `stopFocusMode` / `startDailyBreak` / `stopBreak`, whose other callers are `ui/ScheduleSheet.kt`
    (`:123,141`) and `service/OverlayService.kt` (which calls `isBreakActive()` at four sites) â€”
    matching `evidence/reference-index.md:354â€“362`.
  - *Process death*: no effect; a fresh process re-seeds correctly.
  - *Config change*: acts as an accidental repair, since recreation re-runs the initialisers.
  - *Foreground/background*: **the whole finding.** This is the only axis on which it fails.
  - *Cross-process*: none. `ScheduleManager` is prefs-backed and both readers are in the default
    process; `:a11y` never reads preferences (`evidence/cross-process-inventory.md`).
  - *Persistence*: **yes, on the harmful path.** Tapping Start on a stale card writes
    `PrefsKeys.FOCUS_MODE_ACTIVE` and its expiry, overwriting a live focus window.
    `startDailyBreak()` similarly writes the once-per-day marker.
  - *Release-only*: none.
- **Fix** (minimal): give both cards the `ON_RESUME` observer their siblings already have. In
  `QuickFocusCard`, after line 41:

  ```kotlin
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
          if (event == Lifecycle.Event.ON_RESUME) {
              isFocusMode = scheduleManager.isFocusModeActive()
              focusRemainingMillis = scheduleManager.getFocusModeRemainingMillis()
          }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  ```

  and the equivalent in `DailyBreakCard` for `isBreakActive`, `canStartToday`, and
  `remainingMillis`. Separately, `ScheduleManager.startFocusMode` should be made idempotent â€” return
  `false` if a window is already active, as `startDailyBreak()` already does (`DailyBreakCard.kt:113`
  checks its boolean return) â€” so the re-arm cannot happen even from a stale caller. That second half
  is a `data` change and should be routed to T020.
- **Rewrite justification**: not applicable; the fix is the existing idiom copied into two more
  files, which is itself the argument for the shared helper noted below.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/data/ScheduleManagerTest.kt` (extend the existing file),
  asserting `startFocusMode(30)` followed by `startFocusMode(60)` leaves the **original** expiry
  intact and returns `false` on the second call. Fails before (the second call overwrites the
  expiry), passes after. This covers consequence 1 without a device; consequences 2 and 3 need the
  instrumented lifecycle test below.
- **Existing test conflict**: `ScheduleManagerTest.kt` currently asserts that `startFocusMode`
  sets the expiry unconditionally. If such an assertion exists it must be updated to reflect the
  idempotent contract â€” flagging this because the change is behavioural, not additive.
- **Verification**: `confirmed` (evidence tier T1) that both cards lack the observer while
  `HomeScreen` and `StreakCard` have it, and that `ResumedInterval`'s `enabled` parameter
  short-circuits at `SharedComponents.kt:65` (`if (!enabled) return@LaunchedEffect`) so the interval
  cannot detect a state it is gated on. The midnight case follows from `canStartToday` being read
  only in the initialiser and inside the `isBreakActive` branch.
- **Build scope**: everyday-build
- **Constitution**: II (the `ON_RESUME` re-read idiom is hand-written seven times across RU-07 and
  omitted in these two; a `rememberPrefsBackedState`-style helper next to `rememberResumedTicker` in
  `SharedComponents.kt` would remove all seven copies and the two omissions with them)
- **Affected surfaces**: `AppsScreen` and `LimitsScreen` also lack an `ON_RESUME` observer, but both
  are driven by `rememberResumedTicker(2000L)`, which is *not* gated on a condition and does refresh
  their prefs reads every two seconds while resumed â€” so they are covered by a different mechanism
  and are not part of this finding.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-157 â€” Un-monitoring an app deletes its session and lockout state with no re-validation at write time, guarded only by a lock snapshot that can be two seconds stale  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` â†’ `AppsScreen()`'s `onToggle`
  lambda (lines 233â€“247, specifically the un-check branch at 237â€“244) and the guard it depends on,
  `PremiumAppListItem()`'s `isToggleEnabled` (line 278) computed from `lockStateMap`
  (lines 188â€“196)
- **Issue**: Turning a monitored app off issues
  `remove(ACTIVE_UNTIL + pkg).remove(LOCKED_UNTIL + pkg).remove(REMAINING_MILLIS + pkg)`. Deleting a
  live `LOCKED_UNTIL` is the app's most direct bypass, so the write must be guarded â€” and it is, but
  only in the UI and only against a snapshot. `lockStateMap` is `remember(monitoredApps, currentTime)`
  where `currentTime` comes from `rememberResumedTicker(2000L)`, so the three preference values behind
  `isToggleEnabled` are re-read at most once every two seconds. The handler itself performs **no
  check**: `PremiumAppListItem` withholds the click sources when `isToggleEnabled` is false
  (`:284â€“290`, `:349â€“350`), and that is the entire defence. If a lock lands in the interval between
  two ticks, the row still renders as enabled and a tap deletes the lock that was just written.

  The direction of the staleness matters and mostly protects the app: for the ordinary
  session-expiry transition, a `currentTime` in the past keeps `isActiveOrPaused` true and the toggle
  disabled, so it fails safe. The exposed case is a lock that arrives **without** a preceding session
  â€” `SCHEDULE_LOCK` and `BUDGET_LOCK` both write while the row reads "Ready" â€” and the per-app
  "Block Immediately for 24 Hours" button, which writes `LOCKED_UNTIL` directly
  (`PerAppSettingsSheet.kt:154`). A user watching the clock at their own bedtime schedule boundary
  has a two-second window in which the row is still tappable.
- **Trigger condition**:
  1. Monitor an app and add a schedule rule whose start time is one minute from now
     (Settings â†’ Manage Schedule Focus Modes â†’ + Add Custom Schedule).
  2. Sit on the Apps tab with the row visible and reading "Ready".
  3. At the schedule boundary, tap the row's switch within ~2 s of the rule becoming active.
  4. Read the prefs XML: `locked_until_<pkg>` is gone. Re-enable monitoring for the app and open it â€”
     no schedule lock. Expected behaviour is that the toggle refuses the write, or that the lock
     survives being un-monitored.
  5. A more deterministic variant that removes the timing element: open the per-app sheet, tap
     "Block Immediately for 24 Hours" (which dismisses the sheet), and tap the row's switch before the
     next tick.
- **Blast radius**:
  - *Callers*: `onToggle` is invoked from three places inside `PremiumAppListItem` â€” the row's
    `combinedClickable` `onClick` (`:286`), the `Switch`'s `onCheckedChange` (`:349`), and nowhere
    else. All are gated on `isToggleEnabled`. Other writers/readers of the three keys:
    `service/OverlayService.kt` via `SessionCommand.UpdateState`,
    `data/RuntimeSessionReset.kt`, `Extensions.isAnyAppLocked`, `ui/LimitsScreen.kt:71â€“73`,
    `ui/PerAppSettingsSheet.kt:154` (matching `evidence/reference-index.md:196â€“198`).
  - *Process death*: none â€” the deletion is committed immediately.
  - *Config change*: recomposition re-reads the lock map, so the window closes. Not a mitigation
    anyone can rely on.
  - *Foreground/background*: `rememberResumedTicker` suspends while not resumed
    (`SharedComponents.kt:41â€“48`), which is correct for battery but means the **first** tick after
    resuming is what closes the window. Immediately after foregrounding the app, the snapshot is as
    old as the time spent in the background â€” so the exposure is not bounded by 2 s on resume, it is
    bounded by 2 s *after* the first post-resume tick. That widens the window materially and is the
    part of this finding a device should measure.
  - *Cross-process*: **relevant.** The locks being deleted are written by `OverlayService` in
    response to `AccessibilityEvent`s from `:a11y`, so the UI is deleting state produced by a
    different component with no shared lock and no compare-and-set.
  - *Persistence*: **yes** â€” three `remove()` calls plus `putStringSet(MONITORED_APPS)` via `apply()`
    (`:239â€“246`).
  - *Release-only*: none.
- **Fix** (minimal): re-validate inside the handler, immediately before the write, instead of trusting
  the rendered snapshot. `isAnyAppLocked` already exists as the single-source-of-truth predicate
  (`Extensions.kt:48â€“57`) but is set-wide; the per-package form it needs is the accessor F-079 says
  `AppLockState` is missing. Add it and use it here:

  ```kotlin
  onToggle = { checked ->
      if (!checked) {
          val now = System.currentTimeMillis()
          val live = AppLockState(
              activeUntil = prefs.getLong(PrefsKeys.ACTIVE_UNTIL + app.packageName, 0L),
              lockedUntil = prefs.getLong(PrefsKeys.LOCKED_UNTIL + app.packageName, 0L),
              remainingMillis = prefs.getLong(PrefsKeys.REMAINING_MILLIS + app.packageName, 0L),
          )
          if (live.isEngaged(now)) return@PremiumAppListItem   // fresh read wins over the snapshot
          // â€¦existing removes
      }
      // â€¦existing set update
  }
  ```

  Adding `AppLockState.isEngaged(now)` is the same accessor the F-079 fix calls for, so the two
  findings share one change. Note the fix reduces the window to the microseconds between the read and
  the write rather than eliminating it; eliminating it needs the write to live behind
  `OverlayService`, which is a larger change and not justified for a two-second race.
- **Rewrite justification**: not applicable â€” one accessor added to an existing data class and one
  guard added to an existing handler.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/LockGuardLogicTest.kt` (extend the existing file), asserting
  `AppLockState(activeUntil = 0, lockedUntil = now + 60_000, remainingMillis = 0).isEngaged(now)` is
  `true` â€” the exact shape a `SCHEDULE_LOCK` or a "Block Immediately" write produces, and the shape
  the current UI predicate `currentTime > activeUntil && currentTime < lockedUntil` gets right only
  when `currentTime` is fresh. Fails before (no such accessor), passes after. `FakeSharedPreferences`
  and the existing `isAnyAppLocked` cases in that file give the fixtures.
- **Existing test conflict**: none. `LockGuardLogicTest.kt` asserts `isAnyAppLocked` and
  `getNormalizedRemainingMillis`, neither of which changes.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: the handler has
  no guard, `isToggleEnabled` derives from a `remember(monitoredApps, currentTime)` map, and the
  ticker interval is 2000 ms. Unconfirmed: that a user can actually land a tap inside the window.
  - *Missing*: a device run establishing the real exposure, in particular the post-resume case where
    the snapshot is older than 2 s.
  - *Procedure*: steps 1â€“5 above on any API 29+ device. For the resume case: put a monitored app into
    a `BUDGET_LOCK`, background Grayzone for a minute, foreground it directly onto the Apps tab, and
    tap the switch immediately â€” before the first tick â€” then diff the prefs XML.
  - *Severity effect*: reachable on the resume path without precise timing â†’ **High** (a lockout is
    deleted by an ordinary tap, and for a self-control app the user is in the threat model, so this
    is a bypass rather than a race). Reachable only inside a â‰¤2 s window at a schedule boundary â†’
    stays **Medium**. Not reachable because the first post-resume recomposition always precedes the
    first possible tap â†’ **Low**.
- **Build scope**: everyday-build
- **Constitution**: II (three sites derive "is this app locked" independently â€” `AppsScreen.kt:207â€“208`,
  `LimitsScreen.kt:99â€“101`, and `Extensions.isAnyAppLocked` â€” and the first two have already diverged
  in meaning, which is the substance of F-079)
- **Affected surfaces**: `LimitsScreen.kt:99â€“101` computes the same predicate from the same 2 s-stale
  map but is read-only, so it can display a stale status but cannot delete anything.
  `AppsScreen.kt:130â€“135`'s master monitoring switch has the mirror-image guard
  (`if (!anyAppLockedOrActive || !grayzoneEnabled)`) built from the same stale `currentTime`, and it
  writes `GRAYZONE_ENABLED`, which the policy engine treats as a global kill switch
  (`SessionPolicyEngine.kt:21â€“24`) â€” the same fresh-read guard should be applied there.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-158 â€” The live DNS feed's auto-scroll is keyed on `events.size`, so it stops working permanently once the 50-event cap is reached, and until then leaks an uncancellable coroutine per event  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt` â†’ `LiveTrafficFeed()`
  (lines 413â€“420, specifically `LaunchedEffect(events.size) { if (events.isNotEmpty()) scope.launch { listState.animateScrollToItem(0) } }`
  at 418â€“420)
- **Issue**: Two defects in three lines.

  First, the key is wrong. `DnsTrafficBus` is a fixed-capacity ring: `MAX_EVENTS = 50`, and once
  saturated `emit()` replaces the list while keeping the size at 50
  (`DnsTrafficBus.kt:20,43â€“47`). `events.size` therefore climbs 1â€¦50 and then **never changes
  again**, so `LaunchedEffect` never re-launches and the auto-scroll silently stops. Fifty DNS
  queries is a few seconds of ordinary browsing, so the feature works only for the first moments
  after the VPN starts and is dead for the rest of the session. The user sees a feed pinned wherever
  they last were while new entries are inserted at index 0 above them â€” the screen looks frozen even
  though the counters above it keep incrementing.

  Second, the launch is misrouted. `scope` is a `rememberCoroutineScope()` (line 416) launched from
  *inside* a `LaunchedEffect`. That defeats the point of `LaunchedEffect`: its coroutine is cancelled
  when the key changes, but the work it started on `scope` is not, so it survives until the whole
  composable leaves the composition. During the 1â€¦50 ramp this launches 50 scroll animations that
  each immediately lose `LazyListState`'s scroll mutex to the next one.
- **Trigger condition**:
  1. Enable the AdBlock VPN and open the VPN screen with the live feed visible.
  2. Browse in another app until the "Queries" counter passes 50, then return to the VPN screen.
  3. Observe the counters continue to rise while the feed no longer scrolls to the newest entry.
     Scroll down manually and confirm new entries are being inserted above without the view
     following.
  4. Confirm the cap is the cause: with fewer than 50 events (right after enabling the VPN, or after
     `ACTION_STOP` clears the feed via `DnsTrafficBus.clear()`), the auto-scroll works.
- **Blast radius**:
  - *Callers*: `LiveTrafficFeed()` is `private` and composed from exactly one place,
    `VpnScreen.kt:376`, and only when `vpnRunning` is true. `DnsTrafficBus.events` has exactly one
    UI collector (`evidence/reference-index.md:698`), plus the aggregate counters at
    `VpnScreen.kt:293â€“295`.
  - *Process death*: `DnsTrafficBus` is in-memory, so the feed starts empty and the auto-scroll works
    again for the first 50 events. Restarting the app is the only way a user "fixes" this.
  - *Config change*: same â€” recomposition does not reset `events`, which lives in a process-global
    `object`, so the defect persists across rotation. `rememberLazyListState()` is internally
    saveable so the scroll offset is retained, which makes the frozen feed look deliberate.
  - *Foreground/background*: the VPN keeps emitting while backgrounded, so the feed is normally
    already saturated by the time the user next looks at it. This is why the working case is rare in
    practice.
  - *Cross-process*: **none, and this is worth recording as a non-finding.** `AdBlockVpnService` is
    declared without `android:process` (`AndroidManifest.xml:121â€“132`), so publisher and collector
    share the default process and the in-memory bus genuinely works â€” already established in
    `findings.md`'s confirmed non-findings. The defect here is purely the key and the scope.
  - *Persistence*: none â€” the bus does zero disk I/O by design.
  - *Release-only*: none.
- **Fix** (minimal): key on the newest event's identity rather than the list length, and let
  `LaunchedEffect` own the coroutine so it is cancellable:

  ```kotlin
  LaunchedEffect(events.firstOrNull()?.id) {
      if (events.isNotEmpty()) listState.animateScrollToItem(0)
  }
  ```

  `DnsEvent.id` is already a monotonic counter used as the list key (`DnsTrafficBus.kt:22â€“23`,
  `VpnScreen.kt:440`), so it changes on every emit regardless of saturation. Removing the inner
  `scope.launch` also removes the need for `rememberCoroutineScope()` at line 416 â€” check whether it
  has another use in the function before deleting it (it does not).
- **Rewrite justification**: not applicable â€” the fix is smaller than the code it replaces.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/service/vpn/DnsTrafficBusTest.kt` (extend the existing file),
  asserting that after emitting `MAX_EVENTS + 5` events the list `size` is exactly `MAX_EVENTS`
  while `events.first().id` is strictly greater than it was at saturation. That is the precise
  invariant the fix relies on and the one the current key violates: it proves `size` is unusable as a
  change signal and `id` is usable. Fails to be asserted before (no such test), passes after.
- **Existing test conflict**: none. `DnsTrafficBusTest.kt` covers `emit` ordering and the cap;
  adding an id-monotonicity case does not change existing assertions.
- **Verification**: `confirmed` (evidence tier T1). The cap and the size-preserving replacement are
  at `DnsTrafficBus.kt:20,43â€“47`; the key is `events.size` at `VpnScreen.kt:418`; the inner
  `scope.launch` is at `:419`. The conclusion follows arithmetically and needs no device. Noting for
  completeness that `DnsTrafficBus.kt` **is** one of the nine uncommitted files, but `git diff` shows
  its only change is adding `@Synchronized` to `clear()` â€” the cap and the emit semantics this finding
  depends on are unchanged, and `VpnScreen.kt` itself is clean.
- **Build scope**: everyday-build
- **Constitution**: III is not implicated (no I/O boundary). This is a correctness defect in a
  reactive key.
- **Affected surfaces**: none â€” the pattern appears once. `VpnScreen`'s other collectors
  (`:51`, `:293`, `:414`) use `collectAsState()` correctly and recompose on every list replacement,
  which is exactly why the counters keep updating while the scroll does not: the same data drives one
  path that works and one that does not, in the same screen.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-159 â€” `MainScreen`'s startup effect re-runs on every configuration change, re-issuing a foreground-service start, a permission request, and potentially the VPN consent dialog  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` â†’ `MainScreen()`'s
  `LaunchedEffect(Unit)` (lines 247â€“265)
- **Issue**: F-066 records that this effect never re-runs on resume. The mirror-image problem is that
  it **does** re-run on every configuration change, because Activity recreation produces a new
  composition and `LaunchedEffect(Unit)` fires once per composition, not once per process. So a
  rotation, a system dark-mode switch, a font-scale change, or a locale change each re-execute all
  four side effects: the `POST_NOTIFICATIONS` check and possible `permissionLauncher.launch`,
  `requestAllFilesAccessOnce`, `context.startForegroundService(OverlayService)`, and
  `reconcileVpnState`. The effect is written as if it runs once at startup â€” the doc comment on
  `reconcileVpnState` says *"after the app (re)starts"* (`:113`) â€” and two of the four are
  idempotent only by accident:

  - `requestAllFilesAccessOnce` is guarded by the `ALL_FILES_PROMPTED` flag (`:91`), so it is
    genuinely once-only. Correct.
  - `startForegroundService(OverlayService)` is unguarded (`:257`, one of F-211's eleven sites) and
    is re-issued on every configuration change. `OverlayService.onStartCommand` must therefore be
    idempotent against repeated bare starts â€” which is RU-04's contract to verify, not RU-07's, but
    RU-07 is the caller creating the load.
  - `reconcileVpnState` re-reads preferences each time, and on the branch where consent has been
    revoked it calls `VpnHoldExpiryScheduler.rearmFromPrefs` and then
    `vpnConsentLauncher.launch(consentIntent)` (`:147â€“148`, `:258â€“264`). So a user whose VPN consent
    was revoked and who declines the dialog gets it again on every rotation. The alarm re-arm is
    harmless (it targets the same timestamps), but the dialog is a modal system prompt the user has
    already dismissed.
- **Trigger condition**:
  1. Put the app in the state `reconcileVpnState` targets: `VPN_ENABLED` true with a live 24-hour
     hold, but VPN consent revoked â€” reachable by reinstalling, or by
     `adb shell pm revoke`-equivalent handling of the VPN consent, or by clearing data on a device
     that drops consent.
  2. Reach the main screen. The VPN consent dialog appears. Tap Cancel.
  3. Rotate the device. The dialog appears again. Repeat â€” it appears every time.
  4. Separately, with logcat open, rotate ten times and count
     `startForegroundService(OverlayService)` invocations.
- **Blast radius**:
  - *Callers*: `MainScreen()` â† `MainAppContent()` (`MainActivity.kt:224`), single site. The effect
    reaches `OverlayService.onStartCommand`, `AdBlockVpnService.ACTION_START` (via
    `startRestoredVpn`, `:161â€“172`), `VpnHoldExpiryScheduler.rearmFromPrefs`, and the
    `POST_NOTIFICATIONS` permission launcher.
  - *Process death*: irrelevant â€” a fresh process is the case the effect is written for and handles
    correctly.
  - *Config change*: **the whole finding**, and the axis F-066 does not cover. F-066 is "does not
    re-run when it should"; this is "re-runs when it should not". Both stem from the same
    `LaunchedEffect(Unit)`, and **a fix for F-066 that moves the work into a resume observer would
    fix this one too** â€” which is worth stating so T034 tiers them as one change rather than two.
  - *Foreground/background*: F-066's territory. Recorded here only so the two are not confused.
  - *Cross-process*: the service starts cross the process boundary into `OverlayService` (default
    process) and `AdBlockVpnService` (default process); `:a11y` is not involved. However
    `GrayzoneAccessibilityService` *also* starts `OverlayService` as a watchdog on every app change
    (`GrayzoneAccessibilityService.kt:93â€“99`, uncommitted), so `OverlayService.onStartCommand` is
    already under repeat-start load from two directions and F-205's `startForeground` risk applies to
    each.
  - *Persistence*: indirect â€” `requestAllFilesAccessOnce` writes `ALL_FILES_PROMPTED` (`:92`), and
    `reconcileVpnState` can write `VPN_ENABLED = false` and clear the hold store (`:132â€“133`) on the
    expired-hold branch. That branch is idempotent.
  - *Release-only*: none.
- **Fix** (minimal): make the one-shot work actually one-shot by keying it on process-scoped state
  rather than on composition. The smallest version keeps the effect and adds a process-level guard
  next to the existing flag idiom:

  ```kotlin
  LaunchedEffect(Unit) {
      if (!SessionBootstrap.consume()) return@LaunchedEffect   // process-scoped one-shot
      // â€¦existing body
  }
  ```

  with `SessionBootstrap` an `object` holding an `AtomicBoolean`. If F-066's fix is taken first and
  the reconciliation moves into the `ON_RESUME` observer that `MainAppContent` already installs
  (`:211â€“217`), this finding is resolved by that change and needs no separate patch â€” the
  reconciliation would then run on resume, which is both more often than never and less often than
  every rotation. **Preferred ordering: fix F-066, verify this closes with it.**
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` â€”
  `app/src/test/java/com/grayzone/app/SessionBootstrapTest.kt`, asserting that `consume()` returns
  `true` exactly once and `false` on every subsequent call. Fails before (no such object; the
  behaviour is "once per composition"), passes after. A behavioural `instrumented` test would need
  `StateRestorationTester` plus a fake service starter and is not worth the infrastructure; the
  process-scoped one-shot is the testable unit.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1) that `LaunchedEffect(Unit)` re-runs per
  composition and that `MainActivity` declares no `configChanges` (`AndroidManifest.xml:55â€“58`), so
  every configuration change produces a new composition. The consent-dialog consequence is
  `needs-verification` only in the sense that reaching the revoked-consent state requires a device;
  the control flow to it (`:137â€“150`) is unconditional given that state.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: none directly.
- **Affected surfaces**: `AppsScreen.kt:51â€“54` and `LimitsScreen.kt:31â€“33` also use
  `LaunchedEffect(Unit)`, but their bodies only call `getInstalledAppsCached(context)`, which is
  served from a process-global 60-second cache (`AppUtils.kt:53â€“60`), so a re-run is a cache hit and
  harmless. They are not part of this finding.
- **Observed against**: `working-tree @ 557a963` (uncommitted: `GrayzoneAccessibilityService.kt` â€”
  cited only for the second watchdog start path; `MainActivity.kt` itself is clean)
- **Disposition**: `verified`

---

## T026 summary

| ID | Title | Severity |
|---|---|---|
| F-145 | No state-restoration mechanism anywhere; all UI state is `remember`-only | Medium |
| F-146 | Lockout slider permanently rewrites and persists the session limit | Medium |
| F-147 | `SettingsScreen` limits read once, never re-read, written back stale | Medium |
| F-148 | Malformed custom-prompts JSON becomes an empty list and is persisted over the real prompts | High |
| F-149 | 30 unguarded typed prefs reads in composition; F-060's local fix does not cover them | Medium |
| F-150 | Per-app sheet has no lock gate and its budget slider ends an in-force budget lock | High |
| F-151 | "Block Immediately for 24 Hours" is a no-op during an active session | Medium |
| F-152 | Schedule-creation flow loses an entire in-progress rule on any configuration change | Medium |
| F-153 | `TimePickerDialog` shown with no disposal; window leaks on Activity recreation | Medium |
| F-154 | Sheet visibility is non-saveable; `AppsScreen` keys it on a `Bitmap`-carrying `AppInfo` | Medium |
| F-155 | Home's "Protection ready" ignores `ProtectionHealthRepository`; a dead service still reads green | High |
| F-156 | `QuickFocusCard`/`DailyBreakCard` lack the resume re-read their siblings have | Medium |
| F-157 | Un-monitoring deletes lock state guarded only by a 2 s-stale snapshot | Medium |
| F-158 | Live DNS feed auto-scroll dies at the 50-event cap | Medium |
| F-159 | `MainScreen`'s startup effect re-runs on every configuration change | Medium |

**Counts**: Critical 0, High 3, Medium 12, Low 0. Block F-145 â€¦ F-159 fully consumed.

No Critical is filed. The strongest candidate is F-155, and it is held at High deliberately:
Home is not the mechanism that stops protection, it is the missing signal that makes C1 and F-205
silent. That distinction matters for the punch list â€” F-155 should be fixed **with** C1 and F-205,
not tiered separately, because each of those is only "silent" for as long as this one stands.

### Verdicts on the required coverage areas

| Area | Verdict |
|---|---|
| **Per-screen `remember` vs `rememberSaveable` vs persisted state across process death** | Defective by construction and now enumerated. `rememberSaveable` occurs **zero** times in `app/src/main/java/com/grayzone/app`; with no `ViewModel`, no `SavedStateHandle` and no `onSaveInstanceState` either, there is no restoration mechanism of any kind. F-145 carries the 13-entry per-screen inventory, split by whether each entry survives a configuration change (the prefs-backed subset does) and process death (**nothing** does). F-147, F-152, F-154 and F-156 are the individual screens where that hurts most. |
| **Long-running work launched from composition scope** | Three defects filed â€” F-159 (the startup effect re-runs on every configuration change, re-issuing a service start and potentially a VPN consent dialog), F-158 (an uncancellable coroutine launched per event from inside a `LaunchedEffect`), F-153 (a platform dialog shown from a click handler with no disposal) â€” on top of the three already owned (F-075, F-213, F-066). The pattern is systemic rather than incidental. |
| **Sheet/dialog leaks on configuration change** | Confirmed as a class. F-153 is the leak proper (`TimePickerDialog` with no `onDispose`, so `WindowLeaked` on recreation); F-154 and F-152 are the state half â€” every sheet and dialog closes on any configuration change, and `AppsScreen` cannot even save which app was selected because the key it holds is an `AppInfo` carrying a `Bitmap`. |

### Duplicates and contradictions against findings already filed

- **F-145 duplicates F-232 (T050, Medium).** Same root cause, same severity, compatible fixes. See
  F-145's *Affected surfaces* for the full declaration and the recommendation that F-232 be withdrawn
  against F-145. **One of the two must be dropped before the punch list is built.**
- **F-149 vs F-060**: F-149 does not re-file F-060's poisoned-restore chain; it files the separate
  fact that thirty unguarded typed reads in composition are downstream of it, so F-060's local fix
  does not close them.
- **F-150 vs F-119 / the per-app-config gap noted in `findings.md`**: F-150 is the UI reachability
  gate, not the validation gap. Distinct fixes, and F-150 cites rather than restates.
- **F-155 vs C1 and F-205**: complementary, not duplicated â€” those two are why protection stops,
  F-155 is why the user is not told.
- **One correction to `evidence/reference-index.md`**: it does not reflect that `HomeScreen` and
  `LimitsScreen` never read `ProtectionHealthRepository`. Verified by direct search over `app/src`,
  per the audit rule, and recorded in F-155.

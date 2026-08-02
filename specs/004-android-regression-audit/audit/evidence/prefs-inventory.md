# Evidence: SharedPreferences Key Inventory (T008)

**Scope**: every `SharedPreferences` read/write in `app/src/main/java/com/grayzone/app/`.
**Method**: read `PrefsKeys.kt` and `AppLockState.kt` first, then searched the whole source tree for
`getSharedPreferences`, `get*(`/`put*(`, `edit()`, `commit()`, `apply()`, `remove(`, `contains(`, and
for every key *string literal* to catch keys built outside `PrefsKeys`. Git history of `PrefsKeys.kt`
was read to answer the E3 upgrade-path question.

**Path convention**: file paths in the tables below are relative to
`app/src/main/java/com/grayzone/app/`. `<pkg>` = a monitored app's package name appended to a
prefix key.

**Totals**

| Measure | Count |
|---|---|
| Key constants declared in `PrefsKeys` (excluding `PREFS_NAME`) | 37 |
| Of those, prefix keys that expand per monitored package | 10 |
| Keys built outside `PrefsKeys` (constant prefix + inline literal suffix) | 1 |
| **Distinct keys the current code addresses** | **38** |
| Legacy keys removed from `PrefsKeys` but still resident on upgraded devices | 2 |
| **Total keys known to have been written into `GrayzonePrefs`** | **40** |
| Distinct preference file names | 1 (`GrayzonePrefs`) |
| Keys crossing the `:a11y` process boundary | **0** (see [Cross-process hazard](#cross-process-hazard)) |
| Synchronous `commit()` call sites | 3 (1 on the main thread) |

`AppLockState.kt` declares **no** keys. It is a plain data class (`AppLockState.kt:8-12`) used to hold
the values read from the `ACTIVE_UNTIL` / `LOCKED_UNTIL` / `REMAINING_MILLIS` triple; it never touches
`SharedPreferences` itself.

## Key inventory

| Key constant | Literal value | Type | Written by | Read by | Prefs file name | Notes |
|---|---|---|---|---|---|---|
| `PrefsKeys.PREFS_NAME` | `GrayzonePrefs` | *(file name)* | — | 38 `getSharedPreferences` call sites (see [Multiple preference files](#multiple-preference-files)) | — | Not a key; the single prefs file name. Declared `PrefsKeys.kt:5` |
| `GRAYZONE_ENABLED` | `grayzone_enabled` | Boolean | `ui/AppsScreen.kt:132` | `ui/AppsScreen.kt:48`, `service/OverlayService.kt:278`, `:441`, `:641` | `GrayzonePrefs` | Master monitoring switch; default `true` at every read site |
| `THEME_MODE` | `theme_mode` | String | `ui/SettingsScreen.kt:136` | `ui/SettingsScreen.kt:38`, `MainActivity.kt:60`, `:67`, `ui/theme/Theme.kt:28` | `GrayzonePrefs` | Replaced the removed `GRAYSCALE_ENABLED` — see [Legacy / renamed keys](#legacy--renamed-keys-still-being-read). `Theme.kt:28` is also reached from `service/OverlayService.kt:1151` |
| `ACCESSIBILITY_PROMPTED_FOR_LOSS` | `accessibility_prompted_for_loss` | Boolean | `MainActivity.kt:196`, `ui/OnboardingScreen.kt:51` | `MainActivity.kt:193` | `GrayzonePrefs` | Written on every `evaluateScreen()` pass, including when the value is unchanged (`MainActivity.kt:196`) |
| `ONBOARDING_COMPLETED` | `onboarding_completed` | Boolean | `MainActivity.kt:199`, `:221` | `MainActivity.kt:205`, `data/StateBackupManager.kt:107` (`contains`) | `GrayzonePrefs` | Doubles as the "app was wiped" sentinel for the anti-tamper restore (`StateBackupManager.kt:95-107`) |
| `ALL_FILES_PROMPTED` | `all_files_prompted` | Boolean | `MainActivity.kt:92` | `MainActivity.kt:91` | `GrayzonePrefs` | Prompt-once latch, written before the intent is launched |
| `WAIT_SECONDS` | `wait_seconds` | Int | `ui/SettingsScreen.kt:166`, `GrayzoneApplication.kt:185` | `ui/SettingsScreen.kt:40`, `ui/PerAppSettingsSheet.kt:36`, `GrayzoneApplication.kt:181`, `service/OverlayService.kt:837` | `GrayzonePrefs` | Defaults disagree across sites: `5` in UI, `ConfigValidator.DEFAULT_WAIT_SECONDS` at `GrayzoneApplication.kt:181`, `DEFAULT_WAIT` (5) at `OverlayService.kt:837` |
| `SESSION_MINUTES` | `session_minutes` | Int | `ui/SettingsScreen.kt:184`, `:210`, `GrayzoneApplication.kt:194` | `ui/SettingsScreen.kt:41`, `ui/PerAppSettingsSheet.kt:37`, `ui/LimitsScreen.kt:104`, `service/OverlayService.kt:452`, `:1052`, `GrayzoneApplication.kt:190` | `GrayzonePrefs` | Same default-divergence pattern (`10` hardcoded in UI/service vs `ConfigValidator` in the validator) |
| `LOCKOUT_MINUTES` | `lockout_minutes` | Int | `ui/SettingsScreen.kt:211`, `GrayzoneApplication.kt:203` | `ui/SettingsScreen.kt:42`, `ui/PerAppSettingsSheet.kt:38`, `ui/LimitsScreen.kt:105`, `service/OverlayService.kt:457`, `:1053`, `:1108`, `GrayzoneApplication.kt:199` | `GrayzonePrefs` | Same default-divergence pattern (`60` hardcoded vs `ConfigValidator`) |
| `ACTIVE_UNTIL` | `active_until_` + `<pkg>` | Long | `service/OverlayService.kt:516`, `:1093`; removed at `service/OverlayService.kt:520`, `data/RuntimeSessionReset.kt:38`, `ui/AppsScreen.kt:240` | `Extensions.kt:53`, `service/OverlayService.kt:158`, `:442`, `:633`, `ui/AppsScreen.kt:191`, `ui/LimitsScreen.kt:71` | `GrayzonePrefs` | Prefix key. Core session state; drives `SessionExpiryScheduler.arm` (`OverlayService.kt:160`, `:529`, `:1098`) |
| `LOCKED_UNTIL` | `locked_until_` + `<pkg>` | Long | `service/OverlayService.kt:517`, `:1094`, `ui/PerAppSettingsSheet.kt:154`; removed at `service/OverlayService.kt:521`, `data/RuntimeSessionReset.kt:39`, `ui/AppsScreen.kt:241` | `Extensions.kt:54`, `service/OverlayService.kt:443`, `:634`, `ui/AppsScreen.kt:192`, `ui/LimitsScreen.kt:72` | `GrayzonePrefs` | Prefix key. `ui/PerAppSettingsSheet.kt:154` is the only UI writer of a lock deadline |
| `REMAINING_MILLIS` | `remaining_millis_` + `<pkg>` | Long | `service/OverlayService.kt:518`; removed at `service/OverlayService.kt:1092`, `data/RuntimeSessionReset.kt:40`, `ui/AppsScreen.kt:242` | `Extensions.kt:55`, `service/OverlayService.kt:446`, `ui/AppsScreen.kt:193`, `ui/LimitsScreen.kt:73` | `GrayzonePrefs` | Prefix key. Read raw (un-normalized) at `ui/AppsScreen.kt:193` and `ui/LimitsScreen.kt:73`; normalized via `getNormalizedRemainingMillis` at `Extensions.kt:55` and `OverlayService.kt:446` |
| `MONITORED_APPS` | `monitored_apps` | StringSet | `ui/AppsScreen.kt:246`, `GrayzoneApplication.kt:210`, `:259` | `ui/AppsScreen.kt:44`, `ui/HomeScreen.kt:46`, `:55`, `ui/LimitsScreen.kt:29`, `ui/SettingsScreen.kt:48`, `:54`, `service/OverlayService.kt:151`, `:264`, `:439`, `data/RuntimeSessionReset.kt:36`, `data/StreakEvaluator.kt:19`, `GrayzoneApplication.kt:208`, `:236` | `GrayzonePrefs` | The most widely read key. `Extensions.kt:48-73` receives it as a parameter rather than reading it |
| `DEFAULT_APPS_SEEDED` | `default_apps_seeded` | Boolean | `GrayzoneApplication.kt:260` | `GrayzoneApplication.kt:234` (`contains`) | `GrayzonePrefs` | Presence-checked, not value-checked |
| `PER_APP_WAIT_SECONDS` | `per_app_wait_` + `<pkg>` | Int | `ui/PerAppSettingsSheet.kt:104` | `ui/PerAppSettingsSheet.kt:40`, `service/OverlayService.kt:836` | `GrayzonePrefs` | Prefix key |
| `PER_APP_SESSION_MINUTES` | `per_app_session_` + `<pkg>` | Int | `ui/PerAppSettingsSheet.kt:118`, `:140` | `ui/PerAppSettingsSheet.kt:41`, `ui/LimitsScreen.kt:104`, `service/OverlayService.kt:451`, `:1052` | `GrayzonePrefs` | Prefix key |
| `PER_APP_LOCKOUT_MINUTES` | `per_app_lockout_` + `<pkg>` | Int | `ui/PerAppSettingsSheet.kt:141` | `ui/PerAppSettingsSheet.kt:42`, `ui/LimitsScreen.kt:105`, `service/OverlayService.kt:456`, `:1053`, `:1106` | `GrayzonePrefs` | Prefix key |
| `PER_APP_HAS_CUSTOM` | `per_app_custom_` + `<pkg>` | Boolean | `ui/PerAppSettingsSheet.kt:78` | `ui/PerAppSettingsSheet.kt:33`, `ui/LimitsScreen.kt:103`, `service/OverlayService.kt:448`, `:835`, `:1051`, `:1104` | `GrayzonePrefs` | Prefix key. Gates whether the per-app or global limit wins |
| `DAILY_BUDGET_MINUTES` | `daily_budget_` + `<pkg>` | Int | `ui/PerAppSettingsSheet.kt:180` | `Extensions.kt:68`, `ui/PerAppSettingsSheet.kt:43`, `service/OverlayService.kt:461`, `:587`, `:1076`, `data/StreakEvaluator.kt:23` | `GrayzonePrefs` | Prefix key. `0` = disabled |
| `DAILY_USED_MILLIS` | `daily_used_` + `<pkg>` | Long | `service/OverlayService.kt:598` (**`commit()`**) | `Extensions.kt:71`, `service/OverlayService.kt:467`, `:593`, `:1081` | `GrayzonePrefs` | Prefix key |
| `DAILY_RESET_DATE` | `daily_reset_date_` + `<pkg>` | String | `service/OverlayService.kt:599` (**`commit()`**) | `Extensions.kt:70`, `service/OverlayService.kt:466`, `:589`, `:1080` | `GrayzonePrefs` | Prefix key. **Its namespace is shared with the non-package suffix `"runtime"`** — see the next row and [Inline string literal keys](#keys-referenced-by-inline-string-literal-instead-of-a-prefskeys-constant) |
| *(none — `DAILY_RESET_DATE` + local literal)* | `daily_reset_date_runtime` | String | `data/RuntimeSessionReset.kt:42` | `data/RuntimeSessionReset.kt:29` | `GrayzonePrefs` | **Not a `PrefsKeys` constant.** Suffix comes from `RUNTIME_KEY_SUFFIX = "runtime"` at `data/RuntimeSessionReset.kt:17`. A *different concept* (global once-per-day runtime reset stamp) sharing the per-app budget key prefix |
| `SCHEDULE_RULES_JSON` | `schedule_rules_json` | String | `data/ScheduleManager.kt:42` | `data/ScheduleManager.kt:34` | `GrayzonePrefs` | Gson list of `ScheduleRule`; written via `addRule`/`removeRule`/`toggleRule` from `ui/ScheduleSheet.kt:62`, `:176`, `:187` |
| `FOCUS_MODE_ACTIVE` | `focus_mode_active` | Boolean | `data/ScheduleManager.kt:83`, `:90` | `data/ScheduleManager.kt:98` | `GrayzonePrefs` | **Effectively unread** — the only read (`:98`) merely decides whether to call `stopFocusMode()`; `isFocusModeActive()` returns the same result either way. Redundant with `FOCUS_MODE_UNTIL` |
| `FOCUS_MODE_UNTIL` | `focus_mode_until` | Long | `data/ScheduleManager.kt:84`, `:91` | `data/ScheduleManager.kt:68`, `:96`, `:107` | `GrayzonePrefs` | The actual source of truth for focus mode |
| `BREAK_UNTIL` | `break_until` | Long | `data/ScheduleManager.kt:132`, `:139` | `data/ScheduleManager.kt:116`, `:119` | `GrayzonePrefs` | Read indirectly by `service/OverlayService.kt:279`, `:481`, `:608`, `:642` via `isBreakActive()` |
| `BREAK_USED_DATE` | `break_used_date` | String | `data/ScheduleManager.kt:133` | `data/ScheduleManager.kt:123` | `GrayzonePrefs` | Once-per-calendar-day allowance |
| `VPN_BYPASS_APPS` | `vpn_bypass_apps` | StringSet | **nothing — no writer anywhere in `app/src`** | `service/vpn/AdBlockVpnService.kt:261` | `GrayzonePrefs` | **Read-only key; always returns the `emptySet()` default.** See [Keys read but never written](#keys-read-but-never-written-would-always-return-the-default) |
| `CUSTOM_PROMPTS_JSON` | `custom_prompts_json` | String | `ui/CustomPromptsSheet.kt:55` | `ui/CustomPromptsSheet.kt:39`, `service/OverlayService.kt:848` | `GrayzonePrefs` | Gson `List<String>`; parse failure is caught at `OverlayService.kt:854-860` |
| `USE_CUSTOM_PROMPTS_ONLY` | `use_custom_prompts_only` | Boolean | `ui/CustomPromptsSheet.kt:93` | `ui/CustomPromptsSheet.kt:36`, `service/OverlayService.kt:849` | `GrayzonePrefs` | |
| `CURRENT_STREAK` | `current_streak` | Int | `data/StreakManager.kt:111` | `data/StreakManager.kt:34` (used at `:99`, `:158`, `ui/StreakCard.kt:31`, `:38`) | `GrayzonePrefs` | |
| `LONGEST_STREAK` | `longest_streak` | Int | `data/StreakManager.kt:112` | `data/StreakManager.kt:35` (used at `:108`, `ui/StreakCard.kt:32`, `:39`) | `GrayzonePrefs` | |
| `STREAK_LAST_DATE` | `streak_last_date` | String | `data/StreakManager.kt:113` | `data/StreakManager.kt:82`, `:96` | `GrayzonePrefs` | |
| `TOTAL_SESSIONS_BLOCKED` | `total_sessions_blocked` | Int | `data/StreakManager.kt:47` | `data/StreakManager.kt:36` (used at `:44`, `:159`) | `GrayzonePrefs` | Name-collides conceptually with the Room query `UsageDao.getTotalSessionsBlocked()` (`data/UsageDao.kt:32`) — two independent counters for the same idea |
| `TOTAL_TIME_SAVED_MINS` | `total_time_saved_mins` | Int | `data/StreakManager.kt:48` | `data/StreakManager.kt:37` (used at `:45`, `:160`) | `GrayzonePrefs` | |
| `ACHIEVEMENTS_JSON` | `achievements_json` | String | `data/StreakManager.kt:153` | `data/StreakManager.kt:129`, `:143` | `GrayzonePrefs` | Gson `Map<achievementId, dateString>`; the achievement ids themselves are inline literals (`data/StreakManager.kt:162-171`) |
| `VPN_ENABLED` | `vpn_enabled` | Boolean | `service/vpn/AdBlockVpnService.kt:218` (true), `:302` (false), `service/BootReceiver.kt:53` (false), `MainActivity.kt:132` (false) | `service/vpn/AdBlockVpnService.kt:317`, `:334`, `service/BootReceiver.kt:48`, `MainActivity.kt:123` | `GrayzonePrefs` | Persisted user intent for the tunnel |
| `VPN_HOLD_STARTED_WALL` | `vpn_hold_started_wall` | Long | `service/vpn/VpnHoldLockStore.kt:31`; removed at `:38` | `service/vpn/VpnHoldLockStore.kt:15` | `GrayzonePrefs` | Read via `VpnHoldLockStore.read` from `service/vpn/AdBlockVpnService.kt:138`, `:158`, `:591`, `service/BootReceiver.kt:49`, `MainActivity.kt:125`, `ui/VpnScreen.kt:55`, `service/vpn/VpnHoldExpiryScheduler.kt:64` |
| `VPN_HOLD_STARTED_ELAPSED` | `vpn_hold_started_elapsed` | Long | `service/vpn/VpnHoldLockStore.kt:32`; removed at `:39` | `service/vpn/VpnHoldLockStore.kt:16` | `GrayzonePrefs` | Same read fan-out as above |

## Keys defined but never read

**None.** All 37 `PrefsKeys` constants have at least one read site (counting `contains()` as a read for
`ONBOARDING_COMPLETED` at `data/StateBackupManager.kt:107` and `DEFAULT_APPS_SEEDED` at
`GrayzoneApplication.kt:234`).

One key is *effectively* unread and should be treated as a dead-write candidate:

| Key | Written | Read | Why the read does not matter |
|---|---|---|---|
| `FOCUS_MODE_ACTIVE` (`focus_mode_active`) | `data/ScheduleManager.kt:83`, `:90` | `data/ScheduleManager.kt:98` | The read sits inside the already-expired branch of `isFocusModeActive()` (`data/ScheduleManager.kt:95-104`) and only decides whether to call `stopFocusMode()`. The function's return value is derived purely from `FOCUS_MODE_UNTIL`, so removing the flag would not change any behaviour. Two keys encode one piece of state — see [Legacy / renamed keys](#legacy--renamed-keys-still-being-read). |

## Keys read but never written (would always return the default)

| Key | Literal | Read site | Default returned | Consequence |
|---|---|---|---|---|
| `PrefsKeys.VPN_BYPASS_APPS` | `vpn_bypass_apps` | `service/vpn/AdBlockVpnService.kt:260-261` | `emptySet()` | **Confirmed audit finding candidate.** Searched all of `app/src` for `VPN_BYPASS_APPS` and for the literal `"vpn_bypass_apps"`: the only occurrences are the declaration (`PrefsKeys.kt:66`) and this single read. No UI, service, or receiver ever writes it. The declared feature — "Extra packages the user excluded from the VPN tunnel" (`PrefsKeys.kt:65`) — is therefore **unreachable**: the per-app VPN bypass list can never be non-empty. Added in commit `784d84d` ("Add network tools, daily break, and harden services") and never wired up. |

Note the asymmetry with the restore path: `data/StateBackupManager.kt:229-250` can write *any* key
present in the backup JSON, so on a restored device `vpn_bypass_apps` could technically be populated
if some historical build had written it. No such writer exists in current history for this key, so
in practice the default always wins.

## Keys referenced by inline string literal instead of a PrefsKeys constant

Searched for every `PrefsKeys` literal value across `app/src`. **No key literal is duplicated outside
`PrefsKeys.kt`** — that part of the single-source-of-truth contract holds. The hazards are of a
different shape: keys assembled from, or supplied as, non-constant strings.

### H1 — `daily_reset_date_runtime`: constant prefix + private inline suffix (real hazard)

```17:17:app/src/main/java/com/grayzone/app/data/RuntimeSessionReset.kt
    private const val RUNTIME_KEY_SUFFIX = "runtime"
```

Written at `data/RuntimeSessionReset.kt:42`, read at `data/RuntimeSessionReset.kt:29`, producing the
on-disk key `daily_reset_date_runtime`.

Why this is a hazard, not a style nit:

1. `PrefsKeys.DAILY_RESET_DATE` (`PrefsKeys.kt:53`) is documented as a **per-package** prefix
   ("Daily Budget (append packageName)"). Here the same prefix is used with a *literal* suffix for a
   completely different concept — the global once-per-day session-reset stamp. Two unrelated pieces of
   state share one key namespace.
2. Both concepts are `String` date keys in the same `"yyyy-MM-dd"` format, so a collision would not
   throw `ClassCastException`; it would silently mis-schedule resets. A monitored package literally
   named `runtime` would collide (theoretical — Android package names contain a dot — but the
   namespace offers no structural protection).
3. The suffix is `private` to `RuntimeSessionReset`, so `PrefsKeys` — the declared "single source of
   truth for all SharedPreferences keys" (`PrefsKeys.kt:3`) — does not know this key exists. Renaming
   `DAILY_RESET_DATE` would break the runtime-reset stamp silently: reads would return `""`, and
   `DateUtils.shouldResetDailyRuntimeState` would then fire a reset (see `RuntimeSessionReset.kt:29-33`),
   wiping every active session and lockout once after upgrade.

### H2 — `data/StateBackupManager.kt`: keys are arbitrary runtime strings

```233:244:app/src/main/java/com/grayzone/app/data/StateBackupManager.kt
        for ((key, element) in entries.entrySet()) {
            val obj = element.asJsonObject
            when (obj.get("t")?.asString) {
                "bool" -> editor.putBoolean(key, obj.get("v").asBoolean)
                "int" -> editor.putInt(key, obj.get("v").asInt)
                "long" -> editor.putLong(key, obj.get("v").asLong)
                "float" -> editor.putFloat(key, obj.get("v").asFloat)
                "string" -> editor.putString(key, obj.get("v").asString)
                "stringset" -> {
                    val set = obj.getAsJsonArray("v").map { it.asString }.toSet()
                    editor.putStringSet(key, set)
                }
```

`key` here comes from the decrypted backup file, and the snapshot side writes `prefs.all`
(`data/StateBackupManager.kt:167`, serialized at `:200-227`). Neither side validates keys against
`PrefsKeys`. This is deliberate (schema-free snapshot), but it means the restore path is
`PrefsKeys`-blind and will faithfully resurrect keys that no longer exist in the codebase. Directly
relevant to E3 below.

### H3 — adjacent inline literals that are *not* SharedPreferences keys (recorded so they are not double-counted)

| Location | Literal | What it actually is |
|---|---|---|
| `service/OverlayService.kt:663`, `:671` | `"package_name"` | Intent extra, duplicating the constant `GrayzoneAccessibilityService.EXTRA_PACKAGE_NAME` (`service/GrayzoneAccessibilityService.kt:33`), which *is* used correctly at `service/OverlayService.kt:674`. Same class of rename hazard, different storage. |
| `service/OverlayService.kt:664` | `"active_until"` | Intent extra; string is similar to the pref prefix `active_until_` but is a separate namespace |
| `service/OverlayService.kt:776-779` | `"accessibility_display_daltonizer_enabled"` | A **global `Settings.Secure` key** written by inline literal (not app prefs). Leftover from the removed grayscale feature; see E3 |
| `GrayzoneApplication.kt:291` | `"database_cleanup"` | WorkManager unique-work name |
| `data/StreakManager.kt:162-171` | `"first_day"`, `"week_warrior"`, … | Achievement ids stored *inside* the `achievements_json` value, not as pref keys. A rename here silently re-locks achievements |

## Legacy / renamed keys still being read

This is the E3 upgrade-path question. Direct answer, from `git log -p --follow -- PrefsKeys.kt`:

**No current code reads a key under a name a previous version did not write.** The upgrade hazard runs
in the opposite direction: **two keys that previous versions wrote are now orphaned on disk, and
nothing migrates or deletes them.**

### E3-a — `grayscale_enabled` → `theme_mode` (rename with no migration)

Commit `0a41a1a` ("Ship premium UI redesign and simplify protection to a 24h VPN hold") replaced the
constant in a single diff line:

```
-    const val GRAYSCALE_ENABLED  = "grayscale_enabled"
+    /** Appearance: [com.grayzone.app.ui.theme.ThemeMode] — system | light | dark. */
+    const val THEME_MODE         = "theme_mode"
```

`grayscale_enabled` was introduced in the file's initial commit `091c499` and was a **Boolean**;
`theme_mode` is a **String**. Verified by search: zero occurrences of `grayscale`, `GRAYSCALE`, or
`"grayscale_enabled"` anywhere in `app/src/main/java/com/grayzone/app/`.

Consequences on an upgraded device:

1. The stale Boolean `grayscale_enabled` entry stays in `GrayzonePrefs` forever. There is no migration
   code — searched for `migrat`/`MIGRAT` across the source: the only hits are Room's
   `UsageDatabase.MIGRATION_1_2` (`data/UsageDatabase.kt:24-42`). **There is no SharedPreferences
   migration mechanism of any kind.**
2. `data/StateBackupManager.kt:167` snapshots `prefs.all`, so the orphan is encrypted into
   `/sdcard/.grayzone/backup.dat` and restored again on every wipe-recovery — the orphan is
   *persistent across the exact reset that would otherwise clear it*.
3. Related dead code survives the rename: `service/OverlayService.kt:774-784` still clears the system
   `accessibility_display_daltonizer_enabled` secure setting "from older builds", and
   `OverlayMode.TINT`/`REMOVE_TINT` were deleted in the same commit `0a41a1a`. The feature was removed
   from the key list but its residue was not.

**Because the names differ, there is no `ClassCastException` risk** — this is silent data rot plus a
Play-review-visible "unused stale state", not a crash. Classify as Low severity, High confidence.

### E3-b — `budget_locked_<pkg>` (added and removed within two commits)

Added by commit `e4b8754` as `const val BUDGET_LOCKED_UNTIL = "budget_locked_"` and removed by the
very next commit `7790d31` ("updated new things"). Verified by search: zero occurrences of
`budget_locked` or `BUDGET_LOCKED` in current source. Any device that ran the `e4b8754` build carries
one `budget_locked_<pkg>` `Long` entry per monitored app, unread and un-deleted, and (like E3-a)
preserved by the backup/restore round-trip.

### E3-c — two keys for one concept (incomplete consolidation, not a rename)

| Pair | Evidence | Assessment |
|---|---|---|
| `focus_mode_active` + `focus_mode_until` | `data/ScheduleManager.kt:83-84`, `:90-91`, `:96-104` | `FOCUS_MODE_UNTIL` alone determines the answer; `FOCUS_MODE_ACTIVE` is vestigial bookkeeping. Two writes per state change where one would do |
| `total_sessions_blocked` (prefs) + `UsageDao.getTotalSessionsBlocked()` (Room) | `data/StreakManager.kt:47`, `data/UsageDao.kt:32` | The same statistic is counted independently in two stores with no reconciliation. Not a prefs-key defect, but a divergence risk worth a separate finding |

### E3-d — the restore path has a version field it never checks

`data/StateBackupManager.kt:61` declares `SCHEMA_VERSION = 1` and `:202` writes it into the snapshot
as `"schema"`. Searched for `SCHEMA_VERSION` and `"schema"` across `app/src`: those are the **only two
occurrences**. `applyInto` (`:229-251`) reads only `entries` and ignores `schema` entirely; `readBackup`
(`:186-198`) validates the crypto version byte (`:282`) but not the payload schema.

So a snapshot written by *any* future or past schema is applied key-for-key with no gate. Combined
with H2 and E3-a/E3-b, this is the concrete upgrade-path answer: **the app has no preference migration
or key-deprecation mechanism at all, and its backup/restore feature actively perpetuates keys from
older schemes.**

## Multiple preference files

**There is exactly one preference file: `GrayzonePrefs`.** No state is split across files.

Every one of the 38 `getSharedPreferences` call sites in `app/src/main/java/com/grayzone/app/` passes
`PrefsKeys.PREFS_NAME` with `MODE_PRIVATE`:

| File | Lines |
|---|---|
| `MainActivity.kt` | 58, 90, 122, 183 |
| `GrayzoneApplication.kt` | 102, 176, 233, 272 |
| `service/OverlayService.kt` | 150, 258, 333, 419, 586, 631, 826, 1048, 1103 |
| `service/BootReceiver.kt` | 34 |
| `service/vpn/AdBlockVpnService.kt` | 217, 260, 301, 316, 333 |
| `service/vpn/VpnHoldLockStore.kt` | 12 (`prefs(context)` helper, reused by `ui/VpnScreen.kt:49`, `service/vpn/AdBlockVpnService.kt:137`, `:157`) |
| `data/StateBackupManager.kt` | 105, 166 |
| `data/ScheduleManager.kt` | 30 |
| `data/StreakManager.kt` | 30 |
| `data/StreakEvaluator.kt` | 18 |
| `ui/AppsScreen.kt` | 38 |
| `ui/LimitsScreen.kt` | 25 |
| `ui/HomeScreen.kt` | 45 |
| `ui/SettingsScreen.kt` | 35 |
| `ui/PerAppSettingsSheet.kt` | 29 |
| `ui/CustomPromptsSheet.kt` | 33 |
| `ui/OnboardingScreen.kt` | 50 |
| `ui/theme/Theme.kt` | 27 |

`data/RuntimeSessionReset.kt` and `Extensions.kt` never open the file themselves — they receive a
`SharedPreferences` instance as a parameter (`RuntimeSessionReset.kt:26`, `Extensions.kt:49`, `:64`),
which is why they do not appear above.

Zero occurrences of `getDefaultSharedPreferences`, `createDeviceProtectedStorageContext`, or any
second file name were found in `app/src`.

## Cross-process hazard

### Process topology (verified against the manifest)

`app/src/main/AndroidManifest.xml` is the **only** manifest in `app/src` (confirmed by glob), so there
is exactly one `android:process` declaration in the whole app:

```79:83:app/src/main/AndroidManifest.xml
        <service
            android:name=".service.GrayzoneAccessibilityService"
            android:exported="false"
            android:process=":a11y"
```

Every other component declares **no** `android:process` and therefore runs in the default process
`com.grayzone.app`: `MainActivity` (`:55-58`), `OverlayService` (`:65-68`), `SessionExpiryReceiver`
(`:97-100`), `VpnHoldExpiryReceiver` (`:106-109`), `AdBlockVpnService` (`:121-125`), `BootReceiver`
(`:139-142`), and the `FileProvider` (`:111-115`).

### The process split itself is uncommitted, change-exposed work

`git diff` shows that **both halves of the isolation are uncommitted local modifications**, not
shipped code:

- `android:process=":a11y"` is an *added* line in the working-tree diff of `AndroidManifest.xml`
  (`+            android:process=":a11y"` at manifest line 82).
- The `getProcessName()` guard is an *added* block in the working-tree diff of
  `GrayzoneApplication.kt:52-54`, and the same diff rewrites the neighbouring comment from "this
  process also hosts GrayzoneAccessibilityService" to "GrayzoneAccessibilityService now runs in an
  isolated ':a11y' process".

At `HEAD`, therefore, the accessibility service runs in the **main** process and there is **no**
process guard in `Application.onCreate`. Both states — committed and working-tree — are free of
cross-process preference access (`HEAD` because there is only one process; working tree because of the
guard plus the service's prefs-free implementation), so the conclusion below holds either way. But the
isolation is new, unreviewed code, and it is what makes the `OverlayService` comment in X1 stale.

### Finding: zero keys cross the boundary — by explicit design

**`service/GrayzoneAccessibilityService.kt` contains no SharedPreferences access whatsoever.** I read
the file end to end (131 lines): it touches only `AccessibilityServiceInfo` (`:42-55`), `GrayzoneLogger`
(`:58`, `:78`, `:101`, `:107`, `:125`), `sendBroadcast` (`:86-89`), and
`startForegroundService` (`:93-99`). Searched the file for `getSharedPreferences`, `PrefsKeys`, and
`prefs`: **no matches.** `GrayzoneLogger` is also prefs-free — it wraps `android.util.Log` only
(`GrayzoneLogger.kt:1-40`).

The `:a11y` process is kept prefs-free by an explicit guard in `Application.onCreate`, which otherwise
runs in *every* process:

```52:54:app/src/main/java/com/grayzone/app/GrayzoneApplication.kt
        // The :a11y process must NEVER touch prefs, the backup file, WorkManager, or restore —
        // SharedPreferences isn't multi-process safe and two writers corrupt the backup.
        if (Application.getProcessName() != packageName) return
```

Everything that reads or writes prefs in `onCreate` — `StateBackupManager.restoreIfWiped` (`:64`),
`validateConfiguration` (`:70`), `resetRuntimeStateIfNeeded` (`:71`), `runDeferredStartupWork` (`:72`),
`catchUpStreaks` (`:73`), `registerStateBackup` (`:75`) — is *after* that return, so none of it
executes in `:a11y`. `Application.getProcessName()` requires API 28 and `minSdk = 29`
(`app/build.gradle.kts:28`), so the guard is safe on every supported device.

**Cross-process key table**

| Key written in one process and read in the other | Coherence mechanism |
|---|---|
| *(none)* | N/A — the `:a11y` process performs no preference I/O |

Corroborating negative result: zero occurrences of `MODE_MULTI_PROCESS` anywhere in `app/src`. Given
the topology above, that absence is **correct**, not a defect.

### How the two processes actually communicate (no shared prefs involved)

`:a11y` → main process uses only IPC: an explicit-package broadcast
(`service/GrayzoneAccessibilityService.kt:86-89`, received at `service/OverlayService.kt:178-194`) and
a watchdog `startForegroundService` (`:93-99`, handled at `service/OverlayService.kt:673-675`). All
preference reads triggered by an accessibility event therefore happen in the **main** process, inside
`OverlayService`.

### Findings to record anyway

| ID | Severity | Claim | Evidence |
|---|---|---|---|
| **X1** | Low (documentation) — but **change-exposed** | `OverlayService` carries a comment asserting the opposite of the manifest: that it shares a process with the accessibility service, and that a crash in its `onCreate` can cause the OS to revoke that service. Since commit-time `android:process=":a11y"`, this is false. Acting on the stale comment (or trusting it during review) hides the fact that the isolation already exists — and it is the kind of stale invariant that leads a later change to *reintroduce* prefs into `:a11y` | `service/OverlayService.kt:164-166` ("OverlayService shares a process with GrayzoneAccessibilityService, and an uncaught crash here makes Android disable (revoke) the accessibility service") vs `AndroidManifest.xml:82`. The correct, post-split statement of the same invariant is at `GrayzoneApplication.kt:66-69` ("GrayzoneAccessibilityService now runs in an isolated ':a11y' process") |
| **X2** | Low, latent | The prefs-free property of `:a11y` is enforced only by a single `if` in `GrayzoneApplication.onCreate` plus the accessibility service's current implementation. There is no lint rule, test, or assertion preventing a future edit to `GrayzoneAccessibilityService` from calling `getSharedPreferences` — at which point `MODE_PRIVATE` prefs would silently diverge between processes, with the per-app lock keys (`active_until_`, `locked_until_`, `remaining_millis_`) being exactly the state that would go stale | `GrayzoneApplication.kt:52-54`; no test found referencing `getProcessName` |
| **X3** | Informational (corroborates X2) | `data/ProtectionHealthRepository` holds in-memory state, so it cannot cross the boundary either. The uncommitted diff **removed** the two `updateAccessibilityStatus(...)` calls from the accessibility service — evidence the author recognised the boundary and deliberately stopped sharing in-memory state across it. The side effect is that `ProtectionHealthRepository.updateAccessibilityStatus` (`data/ProtectionHealthRepository.kt:34`) is now **dead code**: searched all of `app/src/main` — it has no remaining callers, while `updateOverlayStatus` (`service/OverlayService.kt:142`, `:760`) and `updateVpnStatus` (`service/vpn/AdBlockVpnService.kt:226`, `:248`, `:311`) are still called from the main process. Accessibility health must now be derived some other way; whether any consumer silently lost its signal is a T-follow-up, not a prefs question | `data/ProtectionHealthRepository.kt:34`; working-tree diff of `service/GrayzoneAccessibilityService.kt` (two removed call sites); `ui/VpnScreen.kt:51`, `MainActivity.kt:137` are the status consumers |

**Honest limitation.** Every read site in the inventory above lives in a class whose process is fixed
by the manifest, so I could attribute all of them. Two paths deserve an explicit caveat rather than a
guess: `data/StateBackupManager` is invoked from `GrayzoneApplication.onCreate:64` and
`MainActivity.onResume:48`, both main-process, and `Extensions.kt:48-73` / `data/RuntimeSessionReset`
receive an injected `SharedPreferences`, so their process is whatever the caller's is — in current
code, always the main process. If a future caller is added in `:a11y`, these two are the files where
the process would become indeterminate from local reading alone.

## Synchronous commit() call sites

Searched for `.commit()` across `app/src/main/java/com/grayzone/app/`. **Three** sites exist. Lint
(`audit/evidence/lint.md`, E4) reported only two.

| # | Location | Lint-flagged | Thread | Evidence for the thread attribution |
|---|---|---|---|---|
| 1 | `service/OverlayService.kt:531` (`editor.commit()` in `executeCommand`) | ✅ `ApplySharedPref` | **Not** main thread — IO dispatcher | `executeCommand` is a `suspend fun` (`:485`) called only at `:373` (from `processForegroundAppChanged`, launched at `:308` on `serviceScope`) and `:406` (from `backgroundApp`, reached from `:348` and from `handleScreenOff`'s launch at `:417`). `serviceScope` is `Dispatchers.IO + SupervisorJob()` (`:115-117`). No `withContext(Dispatchers.Main)` wraps the call — the only Main switches inside `executeCommand` are the nested `serviceScope.launch(Dispatchers.Main)` blocks for overlay UI (`:492`, `:497`, `:502`, `:507`), which do not contain the `commit()` |
| 2 | `service/OverlayService.kt:600` (budget accumulation) | ✅ `ApplySharedPref` | **Not** main thread — IO dispatcher | Enclosing `logUsageAndCheckBudget` is declared `= withContext(Dispatchers.IO)` at `:559-563`; the `commit()` at `:597-600` sits directly in that block, before the `withContext(Dispatchers.Main)` hop at `:611` |
| 3 | `data/StateBackupManager.kt:250` (`return editor.commit()` in `applyInto`) | ❌ **missed by lint** | **Main thread** | `applyInto` ← `restoreIfWiped` (`:117`) ← `GrayzoneApplication.onCreate:64` (`Application.onCreate` always runs on the main thread) **and** ← `MainActivity.onResume:48` (also main thread). Neither call site wraps it in a coroutine or executor |

### Why lint missed site 3 (verified, not assumed)

`ApplySharedPref` only fires when the `commit()` return value is discarded. At
`data/StateBackupManager.kt:250` the value is returned (`return editor.commit()`), so the check does
not apply. The two flagged sites at `OverlayService.kt:531` and `:600` discard it. **Lint's signal is
therefore inverted relative to actual risk here** — the one main-thread `commit()` in the app is the
one it did not report.

### Assessment

- **Sites 1 and 2 (lint's E4): downgrade.** Both run on `Dispatchers.IO`. The `commit()`-vs-`apply()`
  choice at `:531` is additionally load-bearing, not accidental: the session deadlines must be durable
  before `SessionExpiryScheduler.arm` (`:529`) and before the next foreground transition reads them.
  This answers the open question left for T018 in `audit/evidence/lint.md:88-91`: **not a main-thread
  block, so not the Medium–High threading defect lint implied.** Recommend classifying as
  informational unless a measured IO stall says otherwise.
- **Site 3: promote.** This is a genuine main-thread blocking-disk-write candidate, and `commit()` is
  the smaller half of the problem. The same main-thread call chain also performs, synchronously:
  `hasAllFilesAccess` (`:161`, `:187`), a full `File.readBytes()` from shared storage (`:190`),
  AES-256-GCM `doFinal` (`:288`), Gson parsing of the whole snapshot (`:230`), and only then the
  `commit()`. The code acknowledges the risk — "Synchronous by design and kept small so it never ANRs
  onCreate" (`GrayzoneApplication.kt:63`) and "commit(), not apply(): the restored values must be
  durably visible before the rest of onCreate" (`data/StateBackupManager.kt:248-250`) — so the
  ordering requirement is real and the fix is not simply `apply()`. The audit question is whether the
  "kept small" premise holds: the payload is `prefs.all`, which grows with the number of monitored
  apps (10 prefix keys × N packages) **and** with the orphaned legacy keys from E3. Worth a Medium
  candidate on cold-start/`onResume` ANR risk, with the E3 orphan growth as the aggravating factor.

For completeness, `apply()` is used at every other write site: `GrayzoneApplication.kt:215`, `:261`;
`MainActivity.kt:92`, `:132`, `:196`, `:199`, `:221`; `service/BootReceiver.kt:53`;
`service/OverlayService.kt:1095`; `service/vpn/AdBlockVpnService.kt:218`, `:302`;
`service/vpn/VpnHoldLockStore.kt:33`, `:40`; `data/ScheduleManager.kt:42`, `:85`, `:92`, `:134`,
`:139`; `data/StreakManager.kt:49`, `:114`, `:153`; `data/RuntimeSessionReset.kt:43`;
`ui/AppsScreen.kt:132`, `:243`, `:246`; `ui/CustomPromptsSheet.kt:55`, `:93`;
`ui/OnboardingScreen.kt:51`; `ui/PerAppSettingsSheet.kt:78`, `:104`, `:118`, `:142`, `:154`, `:180`;
`ui/SettingsScreen.kt:136`, `:166`, `:184`, `:212`.

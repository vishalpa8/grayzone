# Coverage Matrix

**Feature**: `004-android-regression-audit` | **Instantiated**: 2026-07-31 (T013)
**Contract**: [`../contracts/coverage-matrix.md`](../contracts/coverage-matrix.md)

Completeness proof for the audit. Every row must carry an outcome and evidence before the audit is
complete — including `no-defect-found` rows, where naming what was examined is mandatory (SC-008).

Allowed outcomes: `findings-reported`, `no-defect-found`, `not-applicable`, `not-verifiable`,
`deferred`. Blank = not yet assessed.

> **MERGE COMPLETE (T055, 2026-08-02).** All eight `audit/conditions/E*.md` files exist and Part C
> rows E1–E8 each carry `findings-reported` with a link to their condition file. Merge was
> fill-and-reconcile only — E7's T024 policy analysis (timezone/DST unaffected for epoch-millis;
> F-110/F-111) was preserved and extended with F-240, not replaced. No row contradictions required
> a prevail/lose ruling beyond the F-062→F-206 rename already noted in E1.
>
> *(Historical MERGE WARNING retained for audit trail: Part C was pre-populated before `conditions/`
> existed; T047–T054 were required to read their row as input first. That constraint is now satisfied.)*

## Part A — Review units (all 10 required)

| Unit | Outcome | Evidence (what was examined) | Findings | Task |
|---|---|---|---|---|
| RU-01 `com.grayzone.app` root | `findings-reported` | All 13 root files; startup I/O and ANR risk, process-death survival, release log leakage, dead code, orphaned prefs keys, low-memory handling, OEM resilience; `BuildConfig.DEBUG` settled from generated artifacts. **Permissions half (T022)**: all 7 protection-critical permissions assessed for denial, "don't ask again" and mid-session revocation — 2 fail fully silently (`POST_NOTIFICATIONS`, all-files access), 2 are detected but mis-surfaced (overlay, VPN consent), 1 (`PACKAGE_USAGE_STATS`) is declared but never used anywhere in `app/src` | F-060 … F-079 (1 Critical, 1 High, 6 Medium, 12 Low) + F-080 … F-094 (1 Critical, 8 High, 4 Medium, 2 Low) | T021, T022 |
| RU-02 `data` | `findings-reported` **(partial — see gap)** | All five required questions answered. **Migration**: upgrade is non-destructive (four `CREATE INDEX IF NOT EXISTS`, `fallbackToDestructiveMigration()` deliberately omitted); the *downgrade* direction is the defect (F-041). **Main-thread DAO**: swept clean and recorded as a negative result — every `UsageDao` function is `suspend`, `allowMainThreadQueries()` is absent, `StreakManager` uses `apply()` at all three write sites; the main-thread I/O is on the backup path (F-044), not Room. **Doze**: hypothesis *refuted* — `Constraints.NONE` on a 7-day period is the most Doze-tolerant configuration available and adding `requiresDeviceIdle` would make it worse; the real risk is OEM job killing (F-048). **Indices**: inverted — none missing, two of four serve no query (F-053). **Backup**: four angles (F-043, F-045, F-046, F-047). ⚠️ **Residual gap**: `StreakManager.kt`, `StreakEvaluator.kt`, `ProtectionHealthRepository.kt` and `ProtectionStatus.kt` were **never examined** — T056 must close this before SC-008 completeness can be claimed | F-040 … F-055 (2 Critical, 5 High, 5 Medium, 4 Low); F-056–F-059 unused | T020 |
| RU-03 `policy` | `findings-reported` | All 3 files read in full; 8 positives credited and 13 examined-and-clean behaviors named; cross-checked against the 6 JVM test files targeting the package | F-110 … F-119 (2 Critical, 3 Medium, 5 Low) | T024 |
| RU-04 `service` | `findings-reported` | All 5 files; foreground-notification windows, kill/swipe recovery, scope ownership (cited from T009, not re-derived), `BootReceiver` restore of all three legs, alarm exactness, `:a11y` boundary; 13 positives credited; every confirmed non-finding recorded as examined-and-excluded rather than dropped | F-001 … F-016 (2 Critical, 4 High, 6 Medium, 4 Low) | T018 |
| RU-05 `service.overlay` | `findings-reported` | Whole package read. **Removal**: one owner, all six `dismissOverlay()` sites on the main thread, idempotent, re-entrancy from the countdown job proven safe — one hole, `overlayView` nulled outside the `try` (F-098). **Selection**: genuinely centralised in `resolve()` with zero mode conditionals elsewhere — credited — but it fails open (F-099) and the duplication moved into `showsProgress`, now answering three questions at three sites (F-101). **Rotation**: recreate-loss is impossible here (a `WindowManager` window, no `onConfigurationChanged`); residual risk is measurement only (F-097) | F-095 … F-106 (0 Critical, 3 High, 6 Medium, 3 Low); F-107, F-108 added by lead auditor for the two forwarded boundary defects | T023 |
| RU-06 `service.vpn` | `findings-reported` | All 12 files; descriptor/socket/thread closure on revoke, destroy and exception paths; packet-loop threading and allocation; malformed-packet robustness; offline/upstream-resolver failure; blocklist memory; hold expiry across process death and Doze; 9 positives credited including `VpnHoldLock`'s monotonic-clock design | F-020 … F-038 (1 Critical, 4 High, 13 Medium, 1 Low) | T019 |
| RU-07 `ui` + `ui.theme` | `findings-reported` | Both halves complete, 30 findings, contract compliance verified mechanically (225 field labels and 105 blast-radius sub-fields per file). **State (T026)**: defective by construction and now enumerated — `rememberSaveable` appears **zero** times and there is no `ViewModel` and no `onSaveInstanceState`, so the prefs-backed subset survives a config change and *nothing* survives process death; composition-scope work is systemic (3 new defects on top of 3 already owned); sheet/dialog leaks confirmed as a class. **Config (T027)**: dark mode defective three ways though palette *selection* is sound; **RTL layout is genuinely correct** and credited (direction-aware padding, `Icons.AutoMirrored`) with text handling the failing half; density has one universal defect (icons rasterised at 40 **px**, drawn at 44 dp, so every device above mdpi upscales 2.2×–4.4×); font scale is weakest, with two findings where a control needed to *restore protection* may be unreachable; large lists structurally correct but wasteful | F-145 … F-159 (0 Critical, 3 High, 12 Medium) and F-160 … F-174 (0 Critical, 2 High, 8 Medium, 5 Low); **F-145 absorbs the withdrawn F-232** | T026, T027 |
| RU-08 build / manifest / config (app-level) | `findings-reported` | All three `app/`-level files, checked against R8's **merged** `configuration.txt` and `usage.txt` rather than the project file alone. **Keep rules**: both `r8.md` leads confirmed, plus the missing Gson `TypeToken` rule that already cost four live anonymous subclasses in the shipped release build (F-125, verified in `usage.txt`). **Hostile intent**: `BootReceiver` is the only real exposure — `MainActivity` reads no extras and has no `onNewIntent`; `AdBlockVpnService`'s export is gated by `BIND_VPN_SERVICE`, enforced on `startService` as well as bind; both recorded as examined-and-clean. **Permissions**: three unused declarations plus `WRITE_EXTERNAL_STORAGE` declared-but-never-requested, making the anti-tamper backup inert on API 29 (F-131). **`allowBackup`**: redundant, *not* contradictory — `backup_rules.xml` is unreachable and the live path is `data_extraction_rules.xml`'s `<device-transfer>`. **Accessibility config**: over-broad, and F-141 must land before F-140 or the fix is unenforceable. **minSdk-29**: all seven `SDK_INT` guards correct | F-125 … F-142 (0 Critical, 2 High, 6 Medium, 10 Low); F-143–F-144 unused | T025 |
| RU-08 build / config (repository-root — amendment 1) | `findings-reported` | Root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, wrapper properties, `gradlew`/`.bat`, `.gitignore`, `keystore.properties.example`, `.editorconfig`; `git ls-files` secret-tracking check | F-250, F-251, F-252 | T025a |
| Docs accuracy drift (amendment 2) | `findings-reported` | `README.md` requirements/setup/architecture claims, `INSTALL.md` build+signing+distribution; existence of all 10 referenced paths | F-265, F-266, F-267 | T025b |
| RU-09 resources | `findings-reported` | All of `res/` measured, not estimated. **Strings**: zero `stringResource`/`R.string.` references across 93 main-source files; `strings.xml` holds two strings, both consumed from XML only, and both are ones Android refuses as literals — so nothing was externalized by choice. No `values-*` folders. **Notification layout**: `setCustomContentView` and `setCustomBigContentView` receive the *same* `RemoteViews`, so expanding — the user's only recovery — changes nothing; timer pinned to the light accent at 3.12:1 on a dark shade. **Density**: complete mdpi→xxxhdpi ladder, dimensionally exact, adaptive icon with monochrome — the defect is the inverse, 69,067 bytes of shadowed launcher PNGs that shrinking cannot see. **`res/raw`**: exactly 10.00 MiB of bit array, 76.1% of the release APK, verified **STORED** in the shipped APK so the mmap path is available today | F-175 … F-183 (0 Critical, 1 High, 2 Medium, 6 Low); F-184–F-189 returned to the pool | T028 |
| RU-10 existing tests | `findings-reported` | Whole JVM suite run green on JDK 21 — **40 classes, 306 tests, 0 failures**; androidTest unrunnable (no device). One shape explains every gap: pure Kotlin is well covered and **nothing touching the Android framework is tested at all**, which is the boundary every Critical in this audit sits on. Zero tests for the anti-tamper backup, `OverlayService`, the accessibility service, the blocklist loader, and the VPN hold's persistence. Four tests lock in known-defective behaviour | F-190 … F-204 (0 Critical, 5 High, 7 Medium, 3 Low) | T029 |

## Part B — Brief areas (all 6 required)

| ID | Area | Outcome | Notes | Task |
|---|---|---|---|---|
| A1 | Lifecycle correctness | `findings-reported` | No Fragments — re-scoped to Compose + single-Activity. Process/startup: E1/C7/F-044/F-066. UI state: zero `rememberSaveable`, no ViewModel (RU-07). Service lifecycle: RU-04/RU-05. | F-060, F-044, F-066, F-145… | T021, T026, T047 |
| A2 | Threading & concurrency | `findings-reported` | No LiveData/RxJava — re-scoped to coroutines/`StateFlow`/`DnsTrafficBus`. Substrate: `evidence/concurrency-inventory.md`. Broken DCL (F-040), VPN start/shutdown race (F-022), main-thread restore `commit()` (F-044), `appScope` without CEH (F-206). Lint `ApplySharedPref` on OverlayService was false-positive (IO dispatcher). | F-040, F-022, F-044, F-206 | T018, T019, T020 |
| A3 | Android-specific robustness | `findings-reported` | Parent row — all five sub-areas A3.1–A3.5 answered below | see A3.1–A3.5 | T022, T027, T021, T028, T025, T019 |
| A3.1 | — permissions (denial, don't-ask-again, revoked mid-session) | `findings-reported` | All 7 protection-critical permissions assessed across `SetupRequirements.kt`, `AccessibilityPromptState.kt`, `OverlayService`, `MainActivity`, `HomeScreen`, `OnboardingScreen`. **Structural result**: every detection site fires on Activity resume while every enforcement site runs in a service, and the one persistent background surface — the ongoing notification — is a hardcoded string that re-reads nothing. Fully silent: `POST_NOTIFICATIONS`, `MANAGE_EXTERNAL_STORAGE`. Detected but mis-surfaced: overlay (recovery deep-links to the *monitored* app, confirmed), VPN consent (permanent false "reconnecting…"). Declared but entirely unused: `PACKAGE_USAGE_STATS` (confirmed — zero `UsageStatsManager`/`AppOpsManager` references in `app/src`) | F-080 … F-094 (1 Critical, 8 High, 4 Medium, 2 Low) | T022 |
| A3.2 | — configuration (dark mode, RTL/locale, density, large font) | `findings-reported` | **Dark mode**: three separate defects (hardcoded `Theme.Material.Light` window theme with no `values-night`; colour tokens read a process global published in a post-composition `SideEffect`), though the palette-selection logic itself is sound. **RTL**: layout is correct and credited — direction-aware padding throughout, `Icons.AutoMirrored` where required; the failure is text handling (bidi-unsafe time ranges, locale-dependent `String.format`). **Density**: icons rasterised at a fixed 40 **pixels** but drawn at 44 dp, so every device above mdpi shows them upscaled 2.2×–4.4×. **Large font**: the weakest axis — two High findings where a control needed to *restore protection* can be clipped off an unscrollable screen. Also settled: `Theme.kt:19:22`'s unused `context` is a **stale signature, not dead dynamic-colour logic** — no `dynamicDarkColorScheme`, no `dynamicLightColorScheme`, no `VERSION_CODES.S` branch exists anywhere | F-160 … F-174 | T027 |
| A3.3 | — low memory (`onTrimMemory`/`onLowMemory`, large assets/lists) | `findings-reported` | **Neither callback exists anywhere in the app** (F-067), and the only cache-release path is reachable only from dead code. The large-asset half is now measured rather than estimated: **exactly 10.00 MiB** of Bloom bit array copied onto the Java heap (F-027), and both assets are **verified STORED in the shipped release APK**, so the zero-heap `openRawResourceFd` mmap path is available today with no build change (F-179). That **narrows F-067**: mapped pages are clean and kernel-reclaimable regardless of `oom_adj`, so the "do foreground notifications prevent reclaim" question stops applying to these 10 MiB, and an `onTrimMemory` hook would have nothing left to release. Shrinking the assets is not an option — the ads filter measures 46.18% fill against the ~50% optimum for `k = 10`. Pinning `noCompress` (F-180) is a **precondition** of the mmap fix, since nothing in `app/build.gradle.kts` declares the packaging it would rely on. | F-067, F-027, F-179, F-180 | T021, T028 |
| A3.4 | — deep links / intents with malformed or missing extras | `findings-reported` | `MainActivity` reads no extras / no `onNewIntent` — clean. `AdBlockVpnService` export gated by `BIND_VPN_SERVICE` — clean on extras. **`BootReceiver` is the defect surface**: exported; two of three filter actions unprotected so any app can drive its body (F-136); related F-137/F-138. | F-136, F-137, F-138 | T025 |
| A3.5 | — offline / flaky network, no-crash guarantee | `findings-reported` | Upstream DNS failure is invisible **and the live feed labels failed queries `ALLOWED`** (F-032); DNS responses over the tun MTU are silently dropped with no `setMtu` ever called (F-037) | F-032, F-037 | T019 |
| A4 | Dead / unnecessary code | `findings-reported` *(partial — full inventory is T059–T064)* | Principle I leads already filed: `OemBatteryHelper` unwired (F-061), `Theme.kt` unused `context` (F-163), dead `AppUtils` helpers (F-070/F-071), `DateUtils` dead formatters (F-072), commented Crashlytics (F-074). Full symbol/resource sweep still owned by Phase 7. | F-061, F-070…F-074, F-163 | T021, T059–T064 |
| A5 | Data layer integrity | `findings-reported` | No HTTP client → network/cache items `not-applicable` (re-scoped: Room + prefs + backup). Migration non-destructive upgrade / defective downgrade (F-041); broken DCL (F-040); backup poison vector (F-043/C10); main-thread restore (F-044); indices inverted (F-053); clock-tamper on focus/break (F-049) and date-key reset (F-240). | F-040…F-055, F-049, F-240 | T020, T053 |
| A6 | Security | `findings-reported` | Prefs/backup exposure (F-043, F-065 logging); exported `BootReceiver` (F-136); R8 TypeToken strip (F-125); missing line-number attributes (F-126); `allowBackup=false` vs rules reconciled as non-contradictory (RU-08). Substrate: `evidence/r8.md`. | F-043, F-065, F-125, F-126, F-136 | T021, T025 |

## Part C — Extended conditions (all 8 required)

| ID | Condition | Outcome | Verifiability here | Task |
|---|---|---|---|---|
| E1 | Startup & process lifecycle | `findings-reported` | Full write-up in [`conditions/E1.md`](./conditions/E1.md). **T021 input preserved and corrected:** F-060 stands (C7); F-062 withdrawn → **F-206**; F-078 stands; ANR half owned by **F-044** (same `onCreate:64` call as C7); warm-start gap owned by **F-066**. **T047 COMPLETE.** Five paths answered: cold start = C7 + F-044 crash/ANR chain (force-stop sticky suppression is platform behaviour, not a new Critical); warm Recents start = F-066; first vs n-th launch = intentional restore gate, leftover backup is C7's non-adversarial trigger; `onCreate` ANR = F-044 vs false "never ANRs" comment; system kill restore = sticky + BootReceiver + LaunchedEffect, none restart accessibility (F-010). No new IDs. | F-060, F-043, F-044, F-066, F-206, F-078, F-010 | T047 |
| E2 | Interruption handling (call, screen lock, Doze, home/recents/swipe) | `findings-reported` | Full write-up in [`conditions/E2.md`](./conditions/E2.md). **Call**: defect — the `hasLauncherIntent` guard at `OverlayService.kt:329` returns *before* `backgroundApp()` at `:348`, so a non-launchable in-call package never pauses the session and the lockout can be drawn over a live call (F-222, confirmed statically). **Screen lock**: defect — the `ACTION_SCREEN_OFF` pause has no screen-on counterpart, so resumption rests on an accessibility event the app never confirms arrived (F-221). **Doze**: no new finding, but F-003 is *narrowed* — screen-off cancels the session alarm before Doze can start, so its session-side reach reduces to the cases F-221 creates. **Swipe-away**: no new defect, covered by F-004/F-005 | F-221, F-222 (both High); F-003 cited | T048 |
| E3 | Upgrade path (populated-DB migration, renamed prefs keys) | `findings-reported` | Full write-up in [`conditions/E3.md`](./conditions/E3.md). **T021 input preserved.** Prefs: no migration rail at all — orphans F-068; type-drift via backup is C7/C10 (F-060/F-043). Room: `MigrationTest.migrate1To2` **does** insert populated data before migrating; upgrade non-destructive; downgrade is **F-041**. **T049 COMPLETE.** No new IDs. | F-068, F-060, F-043, F-041 | T049 |
| E4 | Foldables / multi-window | `findings-reported` | Full write-up in [`conditions/E4.md`](./conditions/E4.md). Multi-window is **fully reachable** (targetSdk 34, no `resizeableActivity`, no `configChanges`), and the dangerous mechanism is not window geometry but that the app has **no concept of two visible apps** — one `lastForegroundPackage` fed only by `TYPE_WINDOW_STATE_CHANGED`. Focusing the second pane is read as "the monitored app left", pausing the session and cancelling the expiry alarm while that app stays visible and usable (F-230, **Critical if confirmed** — recorded as C9 pending). Overlay is a whole-display window with no relation to the monitored task and no `onConfigurationChanged` (F-231) | F-230 (Critical, pending), F-231 (High), F-232 (Medium — hold for T033, likely duplicates F-076/T026) | T050 |
| E5 | Accessibility conflicts (TalkBack, content descriptions) | `findings-reported` | Full write-up in [`conditions/E5.md`](./conditions/E5.md). **Coexistence is clean and credited** — `feedbackGeneric` with no flags and `canRetrieveWindowContent=false` leaves no mechanism to fight TalkBack, and `AccessibilityServiceIds.isServiceEnabled()` already parses the multi-service setting exactly rather than by substring, so the bug this row would normally catch is already fixed. **Operability is where the defects are**: the blocking wall is `FLAG_NOT_FOCUSABLE` with no pane title, announcement or touch-exploration check, so the blocked app keeps focus and its node tree while covered (F-233 — Critical if TalkBack can both read and activate through it). All six `Switch` sites including the master monitoring toggle are unlabelled (F-235, `confirmed`, not runtime-dependent) | F-233 (High), F-234, F-235 (Medium), F-236 (Low) | T051 |
| E6 | OEM background-kill resilience | `findings-reported` | Full write-up in [`conditions/E6.md`](./conditions/E6.md). **T021 input preserved.** App fails silently: `OemBatteryHelper` unwired (F-061); UI only offers AOSP ignore-battery-optimizations; no OEM-kill detection in `ProtectionHealthRepository`. Wire-up must use `resolveActivity` + fallback under launcher-only `<queries>` — not `QUERY_ALL_PACKAGES`. **T052 COMPLETE.** No new IDs. | F-061, F-010 | T052 |
| E7 | Time/locale edge cases | `findings-reported` | Full write-up in [`conditions/E7.md`](./conditions/E7.md). **T024 policy analysis PRESERVED** (do not overwrite): timezone/DST unaffected for epoch-millis deadlines; F-110 forward / F-111 backward. **T053 extension:** the deferred date-key half was never filed by T020 — filed as **F-240 Critical**: TZ jump that advances the local date string clears every lockout via `RuntimeSessionReset` (on next app switch at `OverlayService.kt:338`) and refreshes daily budgets. Five-surface clock-tamper theme with F-049 and VpnHoldLock (positive). **T053 COMPLETE.** | F-110, F-111, F-049, F-240 | T024, T053 |
| E8 | Debug vs release divergence | `findings-reported` | Full write-up in [`conditions/E8.md`](./conditions/E8.md). **T021 input, confirmed T1:** the generated `BuildConfig.java` for the *debug* variant has `DEBUG = false` with `BUILD_TYPE = "debug"` — AGP derives `DEBUG` from `debuggable`, not the build-type name, so the uncommitted `debug { isDebuggable = false }` kills `d()`/`i()` in **both** variants (F-064). The divergence here is inverted: debug behaves like release, so debugging cannot reproduce release logging because neither logs. **T025 input, verified from R8 artifacts**: the release build has already *shrunk away* four live Gson `TypeToken` anonymous subclasses (F-125, named in `usage.txt`), and the merged `configuration.txt` confirms neither the `TypeToken` keep rule nor `SourceFile`/`LineNumberTable` is supplied by `proguard-android-optimize.txt` (F-126). So release diverges from debug in two compounding ways — behaviour differs where JSON is deserialized, and the traces that would reveal it carry no line numbers. Combined with F-064, the everyday build cannot reproduce release behaviour *and* cannot log it. minSdk-29 runtime `needs-verification`. **T054 COMPLETE — see `audit/conditions/E8.md` for the resolved answer.** Sweep result: the divergence surface is *smaller* than assumed — only three `BuildConfig.` reads exist in `app/src`, there is no `if (BuildConfig.DEBUG)` branch in app code, and no `app/src/debug/` or `app/src/release/` source set. The sole remaining axis is `isMinifyEnabled`/`isShrinkResources`, i.e. R8 — exactly where F-125 lives. The uncommitted change closed the signing/debuggability gap while leaving the R8 gap open *and making it look closed*. E8's conclusion: F-064, F-074, F-125, F-126 and F-130 form one release-observability cluster for T034. | F-064, F-074, F-125, F-126, F-130 | T054 |

## Scope completeness check (T015)

**Original outcome (as first recorded): SC-002 NOT yet satisfied — scope amendment required before
analysis.**

> **RESOLVED 2026-07-31 — SC-002 is now satisfied *as amended*.** Both gaps below were closed by scope
> amendment rather than waiver, and the maintainer confirmed both at the T017 gate (`spec.md` line 63):
> Gap 1 became **amendment 1** (RU-08 extended to the 7 repository-root files; analyzed by **T025a** →
> `findings-ru08-root.md`), and Gap 2 became **amendment 2** (`README.md`/`INSTALL.md` in scope for
> accuracy drift only; analyzed by **T025b** → `findings-docs.md`). The gap analysis below is retained
> verbatim as the evidence trail — it is *why* the amendments exist, not an open defect.
> **T058 and T086 must not read the heading above as a live V1 failure.** The V1 row in
> `audit/validation-log.md` records this as a provisional pass on exactly this basis.

### Verified complete

| Filesystem reality | Declared unit | Match |
|---|---|---|
| 8 source packages: root (13 files), `data` (15), `policy` (3), `service` (5), `service/overlay` (5), `service/vpn` (12), `ui` (12), `ui/theme` (3) | RU-01 … RU-07 | ✅ all 8 mapped; root-package count of 13 matches the spec exactly |
| 11 resource directories: `drawable`, `layout`, `raw`, `values`, `xml`, `mipmap-anydpi-v26`, `mipmap-{h,m,xh,xxh,xxx}dpi` | RU-09 | ✅ all 11 covered by the declared globs |
| `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/*`, `app/schemas/**` | RU-08 | ✅ |
| `app/src/test/**` (42 files), `app/src/androidTest/**` (3 files) | RU-10 | ✅ |

### Gap 1 — root-level Gradle configuration is in no review unit

RU-08 names only `app/`-level build files. These repository-root files are audit-relevant and
currently unassigned:

| File | Why it matters to this audit |
|---|---|
| `build.gradle.kts` (root) | Declares AGP 8.2.2, Kotlin 1.9.22, KSP 1.9.22-1.0.17 — the versions behind the JDK-21 constraint (research R1) |
| `settings.gradle.kts` | `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, module includes |
| `gradle.properties` | JVM args, the `file.encoding=UTF-8` pins that `app/build.gradle.kts` comments depend on, `android.nonTransitiveRClass` |
| `gradle/wrapper/gradle-wrapper.properties` | Pins Gradle 8.6, `validateDistributionUrl` |
| `gradlew` / `gradlew.bat` | Wrapper entry points |
| `.gitignore` | Security-relevant: it is what keeps `keystore.properties` and `*.jks` out of version control |
| `keystore.properties.example` | Signing-config contract for fresh clones |

**Proposed amendment**: extend RU-08 from "app-level build files" to "build, manifest, and
configuration — repository-root and app-level", adding the seven files above.

### Gap 2 — documentation files unassigned

`README.md` and the untracked `INSTALL.md` are in no unit and no out-of-scope list.

**Proposed amendment**: add to the out-of-scope list, unless the maintainer wants setup instructions
checked for drift against the JDK-21 requirement discovered in research R1 — `INSTALL.md` is untracked
and new, so it may already contradict or duplicate that finding.

### Correction to `plan.md` counts

`plan.md` states "73 main-source Kotlin files" and "~40 JVM test files". Actual counts are
**68 main-source** files and **42 JVM test** files (68 + 42 + 3 instrumented = 113 total `.kt`, which
matches). Corrected in `plan.md`.

## Row count check (T058)

Required: 10 units + 6 areas + 8 conditions = **24 rows** (A3's five sub-rows are tracked in addition,
not instead — they exist so A3 cannot be marked done while a sub-area is unanswered).

**Performed 2026-08-02 (T058):**

| Bucket | Required | Present | Blank outcomes | Notes |
|---|---|---|---|---|
| Part A review units | 10 | **12** | 0 | +2 from scope amendments (RU-08-root, Docs) — both `findings-reported` |
| Part B brief areas | 6 (A1–A6) | **6** + 5 A3.* subs | 0 | A4 marked `findings-reported` *(partial)* — full dead-code inventory is Phase 7 (T059–T064); not blank |
| Part C conditions | 8 | **8** | 0 | All link to `conditions/E*.md` |
| **Canonical 24** | 24 | 24 | **0** | Pass |
| **All matrix rows** | — | 31 | **0** | Pass including amendments + A3 subs |

Zero evidence-free `no-defect-found` rows (none use that outcome). RU-02 still notes a residual gap on
`ProtectionHealthRepository` / `ProtectionStatus` production writers; Streak/DailyBudget/RuntimeSessionReset
were closed by F-049 and F-240. V6 recorded in `validation-log.md`.

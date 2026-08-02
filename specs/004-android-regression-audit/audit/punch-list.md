## Critical-First Punch List

**Feature**: `004-android-regression-audit` | **Assembled**: 2026-08-02 (T034)
**Observed against**: `working-tree @ 557a963` (uncommitted: `app/build.gradle.kts`,
`GrayzoneApplication.kt`, `OverlayService.kt`, `GrayzoneAccessibilityService.kt`,
`AdBlockVpnService.kt`, `BlocklistManager.kt`, `DnsTrafficBus.kt`, `AndroidManifest.xml`,
`accessibility_service_config.xml`, plus audit artifacts)
**Verification environment**: JDK 21 (`-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11`) ·
JVM suite green (306 tests) · `assembleRelease` green · **no device / no emulator**
**Totals**: Critical **12** active · High **~48** active · Medium **~88** · Low **~63**
(Medium/Low in the breakdown only; withdrawn duplicates excluded: F-002, F-062, F-114, F-207,
F-214, F-215, F-232)
**Sweep precondition**: T031 Sweep log covers every `findings-*.md`. T032 labelling is incomplete
for some Medium/Low rows — Critical/High rows below carry status from their records. T033 folds
applied for the pairs listed in the Sweep log.

**Clusters (fix once, not N times):**
1. **C7+C10** — F-043 → F-060 (gate restore; one fix).
2. **Clock-tamper** — F-110, F-111, F-049, F-240 (+ VPN hold already correct) → shared monotonic helper.
3. **Release observability** — F-064, F-074, F-125, F-126, F-130 (present together; F-125 is High).
4. **`startForeground`** — F-205 (absorbs F-002).
5. **JSON empty-list overwrite** — F-241 (schedules) + F-148 (prompts) — same pattern.

---

### Critical

| ID | Unit | One-line defect | User-visible consequence | Trigger (short) | Status |
|----|------|-----------------|--------------------------|-----------------|--------|
| F-060 | RU-01 | Unguarded typed prefs reads after backup restore | App cannot launch; clear-data cannot recover | Poisoned/type-drifted backup → cold start | **verified** (Tier-1) |
| F-043 | RU-02 | Backup `applyInto` has no allowlist / schema gate | Upstream of F-060; arbitrary keys/types restored | Any backup with wrong types | **verified** (Tier-1) |
| F-110 | RU-03 | Lockout is pure wall-clock (`now < lockedUntil`) | Advance clock → lockout ends instantly | Settings → set time past lockout | **verified** (Tier-1, dual-clock) |
| F-111 | RU-03 | Session active is pure wall-clock | Rewind clock → expired sessions usable again | Settings → set time backward | **verified** (Tier-1, dual-clock) |
| F-049 | RU-02 | Focus/break deadlines pure wall-clock | Date change grants unlimited break/focus windows | Change device date during break | **verified** (Tier-1) |
| F-240 | E7 | TZ jump advances date-key → daily reset | Lockouts cleared + budgets refreshed on next app switch | Change timezone across date boundary | **verified** (Tier-1) |
| F-241 | RU-02 | Malformed schedule JSON → emptyList → next edit saves empty | Bedtime locks die; rules permanently wiped on edit | Corrupt `SCHEDULE_RULES_JSON`, add a rule | **verified** (Tier-1) |
| F-001 | RU-04 | `addView` failure → no overlay, return | Lockout: monitored app stays fully usable | Revoke overlay mid-session | **fix-pending-device** |
| F-020 | RU-06 | `protect(socket)` swallow + ignore Boolean | DNS loops tunnel; UI still says filtering on | `protect` fails / returns false | **fix-pending-device** |
| F-205 | P3 | Unguarded `startForeground` (overlay + VPN) | Protection never starts; crash/retry loop | Deny notifications + background FGS start | **fix-pending-device** |
| F-080 | RU-01-perm | A11y revoke undetectable while backgrounded | No friction/lock forever; notification still "active" | Revoke a11y while app backgrounded | **fix-pending-device** |
| F-230 | E4 | Split-screen focus = "app left" | Session paused while monitored app still visible | Open monitored app in split, focus other pane | **fix-pending-device** (UsageStats guard; needs usage access) |

---

### High

| ID | Unit | One-line defect | User-visible consequence | Trigger (short) | Status |
|----|------|-----------------|--------------------------|-----------------|--------|
| F-005 | RU-04 | `DismissOverlay` is intentional no-op | Lock overlay stays after leaving monitored app | Lock → switch to launcher | **verified** |
| F-003 | RU-04 | Inexact alarms default (no exact-alarm perm) | Session lock arrives minutes late under Doze | Session across Doze | **fix-pending-device** |
| F-004 | RU-04 | Swipe-away restart alarm unsafe on API 8+ | Service may not restart after swipe-away | Swipe from recents | **fix-pending-device** |
| F-006 | RU-04 | Sync entry paths do unguarded prefs reads | Crash loop on bad prefs type in service | Type-drifted key + service start | **verified** |
| F-021 | RU-06 | Hold-expiry stops VPN without elapsed re-check | Hold can end early / wrongly | Clock change near hold end | **verified** |
| F-022 | RU-06 | `startVpn`/`shutdownVpn` race | Tunnel torn mid-start | Rapid toggle | **fix-pending-device** |
| F-023 | RU-06 | Delayed restart always calls `startVpn` | Restart after intentional stop | Stop then delay fires | **verified** |
| F-024 | RU-06 | `vpnInterface.close()` comment-only catch | Leaked TUN; next establish fails oddly | close() throws | **fix-pending-device** |
| F-040 | RU-02 | Broken double-checked locking on Room | Dual DB instances / WAL corruption risk | Concurrent first open | **verified** |
| F-041 | RU-02 | No downgrade fallback | Downgrade kills usage recording | Install older APK over data | **fix-pending-device** |
| F-044 | RU-02 | Main-thread restore decrypt+commit in `onCreate` | ANR / missed FGS deadline on cold start | Cold start with backup present | **fix-pending-device** |
| F-045 | RU-02 | Backup `savedAt` never read | Older backup file rolls locks back | Replace backup with older copy | **verified** |
| F-046 | RU-02 | `androidId` failure → empty key | Backup decryptable by any APK holder | Settings.Secure failure | **verified** |
| F-061 | RU-01 | `OemBatteryHelper` built, never wired | OEM kill → silent protection loss | Xiaomi/Oppo kill path | **fix-pending-device** |
| F-081 | RU-01-perm | Overlay recovery deep-links to monitored app | User sent to wrong Settings screen | Revoke overlay during lock | **verified** |
| F-082 | RU-01-perm | Recovery `startActivity` from background | Recovery intent dropped on API 31+ | Revoke overlay while backgrounded | **fix-pending-device** |
| F-086 | RU-01-perm | `POST_NOTIFICATIONS` result discarded | Silent: no ongoing notification | Deny notifications | **verified** |
| F-088 | RU-01-perm | All-files loss never re-detected | Anti-tamper backup silently inert | Revoke all-files after grant | **verified** |
| F-089 | RU-01-perm | VPN consent never re-checked | UI shows hold; tunnel off | Revoke VPN consent | **verified** |
| F-090 | RU-01-perm | Restore brings back prompt-suppression flags | User never re-prompted after wipe+restore | Clear data with backup | **verified** |
| F-091 | RU-01-perm | Setup models 2/7 permissions | "Protection ready" while five grants missing | Deny unused-modelled perms | **verified** |
| F-092 | RU-01-perm | A13+ a11y CTA to greyed toggle | User cannot re-enable from in-app CTA | Revoke a11y on API 33+ | **fix-pending-device** |
| F-095 | RU-05 | Friction grants session without foreground check | Session granted while app not foreground | Race leave during friction | **fix-pending-device** |
| F-096 | RU-05 | Lock `isStillForeground` single-package equality | Lock dismisses incorrectly | Multi-window / flash switch | **fix-pending-device** |
| F-097 | RU-05 | Lock escape clipped in landscape / large font | User cannot dismiss lock screen | Landscape + large font | **fix-pending-device** |
| F-125 | RU-08 | R8 stripped live Gson TypeTokens | Release-only JSON read failures / data loss | `assembleRelease` + achievements/schedules | **verified** (keep rule; runtime residual in pending-device if needed) |
| F-131 | RU-08 | `WRITE_EXTERNAL_STORAGE` never requested | Backup inert on API 29 | Fresh API 29 install | **verified** |
| F-148 | RU-07 | Custom prompts emptyList then save | All custom prompts permanently wiped | Corrupt prompts JSON, add one | **verified** |
| F-150 | RU-07 | Per-app settings reachable during lock | User can weaken limits while locked | Open sheet during lockout | **fix-pending-device** |
| F-155 | RU-07 | "Protection ready" from perms only | Home lies when services dead | Kill services, open Home | **verified** |
| F-160 | RU-07-cfg | Onboarding unscrollable / centred | Large font: cannot finish setup | Font scale 1.3+ onboarding | **fix-pending-device** |
| F-161 | RU-07-cfg | Fixed headers + non-scroll columns | Protection controls unreachable | Large font on Apps/Limits/Settings | **fix-pending-device** |
| F-177 | RU-09 | Notification body overflows collapsed row | Countdown unreadable | Long text / large font notification | **fix-pending-device** |
| F-190 | RU-10 | Tests pin wall-clock Critical semantics | Fix of F-110/F-111 blocked by green tests | Run `SessionStatePredicateTest` | **verified** |
| F-191 | RU-10 | Test asserts +30d clock jump must grant session | C5 fix must invert this test | `SessionPolicyEngineTest:191` | **verified** |
| F-200 | RU-10 | Zero tests for anti-tamper backup | C7/F-043 ship untested | (gap) | **verified** |
| F-201 | RU-10 | Nothing tests friction/lockout on monitored app | Core promise untested | (gap) | **verified** |
| F-202 | RU-10 | Six of ten prior-fix defects untested | Regressions can return silently | (gap) | **verified** |
| F-206 | P3 | `appScope` has no `CoroutineExceptionHandler` | Background failure kills process | Uncaught in backup/streak coroutines | **verified** |
| F-208 | P3 | Restored VPN fails silently; UI shows hold | Protection off, countdown lies | Restore with VPN_ENABLED | **fix-pending-device** |
| F-209 | P3 | All-files prompted flag set before show | One failed startActivity → never prompt again | Break settings activity once | **verified** |
| F-210 | P3 | VPN re-consent dialog can fail open silently | Hold UI without tunnel | Consent activity missing | **verified** |
| F-211 | P3 | Eleven unguarded startActivity/service starts | Silent failures on OEM variants | Various recovery paths | **verified** |
| F-212 | P3 | Unguarded `VpnService.prepare` in BootReceiver | Boot restore aborts entirely | prepare throws at boot | **verified** |
| F-213 | P3 | Unguarded `StreakEvaluator` from Home composition | Composition crash on DB error | Home open + DB throw | **verified** |
| F-221 | E2 | Screen-off pause, no screen-on resume | Session stuck paused after unlock | Lock screen mid-session | **verified** |
| F-222 | E2 | No-launcher in-call package skips `backgroundApp` | Lock overlay over live call; budget burns | Incoming call from telecom package | **verified** |
| F-231 | E4 | Overlay ignores multi-window geometry | Wall covers wrong pane / whole display | Split-screen + lock | **fix-pending-device** |
| F-233 | E5 | Overlay `FLAG_NOT_FOCUSABLE`, no a11y semantics | TalkBack may read/activate through lock | TalkBack + lock overlay | **fix-pending-device** |

---

### Release-only (not reproducible in the everyday build)

| ID | Unit | One-line defect | Why release-only | Status |
|----|------|-----------------|------------------|--------|
| F-125 | RU-08 | Four live Gson TypeToken subclasses removed | R8 minify only on release | **verified** (TypeToken keep; mapping check) |
| F-126 | RU-08 | No `SourceFile`/`LineNumberTable` in merged config | Release stacks have no lines | **verified** |
| F-130 | RU-08 | `checkReleaseBuilds = false` | Only release lint gate removed | **verified** |
| F-064 | RU-01 | `BuildConfig.DEBUG=false` in every variant | Everyday build also quiet (cluster with above) | **verified** |

F-064 is everyday-build too after the uncommitted `isDebuggable=false` change — listed here because it
belongs to the **release-observability cluster** with F-125/F-126/F-130 (see E8).

---

### Fix-order recommendation (first day)

1. F-043 + F-060 (unbrick / prevent brick)
2. Shared monotonic deadline helper → F-110, F-111, F-049, F-240 (and invert F-190/F-191)
3. F-241 + F-148 (JSON empty-list overwrite pattern)
4. F-205 (`startForeground` both services)
5. F-001 (`addView` must not fail open)
6. F-020 (`protect` must not fail open)
7. F-080 (a11y revoke detection)
8. F-125 (Gson TypeToken keep) before trusting release

### Progress (2026-08-02)

- **Tier-1 Critical**: complete (7 `verified`, 5 `fix-pending-device`). Gates green: JVM unit tests, `assembleRelease`, `assembleDebugAndroidTest` (compile-only).
- **Tier-2 High**: complete — punch-list Highs fixed in code; statuses `verified` or `fix-pending-device` (see table). T072/T073 closed.
- **Tier-3 Medium / Tier-4 Low**: complete — T074–T080 closed; warnings post-fix at `evidence/warnings-post-fix.md`.
- **Phase 5** (was open): T036–T046 closed 2026-08-02 — Named tests index, `release-only.md`, `verification-gaps.md`, V3–V5 pass. F-148 refuse-overwrite **actually** implemented (`Prompts.parseCustom` + sheet guard).
- **Phase 9**: `report.md`, `pending-device.md`, `constitution-summary.md` assembled; dispositions swept (T081); validation-log V1/V3–V10 updated.
- **Still intentionally open**: on-device proof list only (`pending-device.md`) — no emulator available.

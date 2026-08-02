# Findings — RU-09 (`app/src/main/res/`)

**Task**: T028 · **Review unit**: RU-09 · **Finding ID block**: F-175 … F-189 (F-175 … F-183 used, 9 of 15)

**Subject**: the 24 files under `app/src/main/res/` — `drawable/` (3), `layout/` (1), `mipmap-*` (11),
`raw/` (3), `values/` (2), `xml/` (4) — plus the Kotlin call sites that consume them. Kotlin files are
in scope only where a resource defect reaches them (`OverlayService.startCountdownNotification()`,
`BlocklistManager.load()`); their non-resource behaviour belongs to RU-04 and RU-06.

**Evidence base** (read, not re-derived): [`findings.md`](./findings.md) ·
[`evidence/prior-fix-watchlist.md`](./evidence/prior-fix-watchlist.md) ·
[`evidence/lint.md`](./evidence/lint.md) §E3/§E7 · [`evidence/r8.md`](./evidence/r8.md) ·
[`contracts/finding-record.md`](../contracts/finding-record.md)

**Revision**: `HEAD = 557a963`. `git status --porcelain app/src/main/res` reports exactly one modified
resource file, `res/xml/accessibility_service_config.xml`. Per the T028 brief that file's **content** is
owned by T025 (RU-08) and is **not** analysed here; see "Noticed, not filed" below. Every other
resource file is clean against `557a963`, so all nine findings are `Observed against: 557a963` unless
stated otherwise.

**Environment**: no device and no emulator. Every claim about rendered pixels, notification row height,
font scaling, or memory pressure is marked `needs-verification` with an exact `adb` procedure and a
severity-per-outcome. **File sizes, bit densities, PNG dimensions, APK entry compression, and reference
counts were measured, not estimated** — the measurement commands are recorded in each finding.

**Reference-index note (mandatory accuracy rule).** `evidence/reference-index.md` contains **zero**
entries for any resource: a grep for `notification_countdown`, `adblock_bloom`, `adult_bloom`,
`abused_tlds`, `ic_launcher`, `strings.xml`, `themes.xml`, `res/raw`, and `res/layout` returns no
matches. RU-09 therefore has **no index coverage at all**, which is itself a coverage gap (reported to
T055–T057). Consequently *every* reachability claim below was established by direct `Grep` over
`app/src`, searching **both** Kotlin and XML, and no finding proposes a deletion on index authority.

---

## Referenced, not re-filed (owned elsewhere; listed so the punch list does not double-count)

| Defect | Owner | Where it touches RU-09 |
|---|---|---|
| No `onTrimMemory`/`onLowMemory` anywhere; the only cache-release path is reachable only from dead code | **F-067** (T021) | RU-09 establishes the *size* of what is never released — **10,485,760 bytes** — and **F-179** shows the packaging already permits a form that needs no trim callback at all |
| Blocklist peak memory / Bloom filters (~10 MB) held and never released | **F-027** (T019) | RU-09 supplies the exact figure and the resource-side root cause (**F-179**, **F-181**) |
| README claims "4.6 million blocklist entries" vs "~800k ads + ~160k adult domains" | **F-266** (T025b) | RU-09 **resolves the discrepancy by measurement** — see the note below; no new finding filed |
| `ObsoleteSdkInt` on `res/mipmap-anydpi-v26` | lead already assigned to T059/T060/T062 | cited in **F-183**, which files the *distinct* shadowing/APK-weight defect, not the SDK-int nit |
| `HardcodedText` at `notification_countdown.xml:37` (`evidence/lint.md` §E7) | assigned to **T028** | filed as **F-176**, with the material addition that the view is also never written at runtime |

### Evidence contribution to F-266 (not a finding) — the "4.6 million" claim is the correct one

Both bloom filters were parsed and population-counted directly (commands in F-181). Inverting the
Bloom occupancy identity \( n = -\frac{m}{k}\ln(1 - f) \) on the measured fill ratio \(f\) gives:

| Asset | `m` (bits) | `k` | Set bits | Fill `f` | Derived entries `n` |
|---|---|---|---|---|---|
| `adblock_bloom.bin` | 50,331,648 | 10 | 23,243,821 | 46.1813% | **≈ 3,118,295** |
| `adult_bloom.bin` | 33,554,432 | 10 | 12,420,960 | 37.0173% | **≈ 1,551,258** |
| **Total** | 83,886,080 | | 35,664,781 | | **≈ 4,669,553** |

**≈ 4.67 million entries.** The README's "4.6 million blocklist entries" is accurate to within 1.5%.
The "~800k ads + ~160k adult domains" figure is wrong by ~3.9× and ~9.7× respectively. F-266 should be
resolved in favour of the 4.6M figure and the ~800k/~160k sentence corrected, not the reverse. (The
derivation assumes near-uniform hashing; the implementation uses Kirsch–Mitzenmacher double hashing over
SHA-256 at `GrayzoneBloomFilter.baseHashes()`, for which the identity is the standard model.)

This measurement is also what makes F-027 and F-067 rateable: the 10 MB is **not** gratuitous — it is
correctly sized for the data it holds (see F-181), so those findings cannot be resolved by shrinking the
asset. They must be resolved by changing **how** it is held, which is F-179.

---

## Noticed, not filed

- `res/xml/accessibility_service_config.xml` is the one modified resource file. Its diff removes
  `android:packageNames="@null"`. **Content owned by T025 (RU-08)** and deliberately not analysed. One
  purely structural observation passed along: the file is the **only** consumer of
  `@string/accessibility_service_description` (`:7`), which makes it the sole reason that string exists
  in `strings.xml` at all — relevant to F-175's argument, not to RU-08's.
- `app/build.gradle.kts:34–36` sets `vectorDrawables { useSupportLibrary = true }`. At `minSdk = 29`
  every supported device has native `VectorDrawable`, so this forces AndroidX compat inflation for no
  benefit. `build.gradle.kts` is RU-08's file (T025); flagged, not filed.
- There is no `res/values/dimens.xml`, no `colors.xml`, and no `plurals`. The dimension consequence is
  folded into **F-177**'s fix (it is the same patch) rather than filed separately, per the contract's
  prohibition on merged/duplicated root causes.

---

## Findings

### F-175 — The app has no localization infrastructure at all: zero `stringResource` calls in 93 source files, and the only two externalized strings are the two the framework forces  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/values/strings.xml` (lines 1–4, the whole file) → consumed only by
  `AndroidManifest.xml:51` and `res/xml/accessibility_service_config.xml:7`. Counterpart call sites:
  `app/src/main/java/com/grayzone/app/ui/**` (13 files) plus
  `service/overlay/*Presentation.kt` (5 files), `service/OverlayService.kt`, `SharedComponents.kt`,
  `MainActivity.kt`.
- **Issue**: Every user-facing string in the application is a Kotlin string literal at its render site.
  Measured, not asserted:

  | Measurement | Command | Result |
  |---|---|---|
  | `stringResource(` or `R.string.` anywhere in main source | `rg -c "stringResource\|R\.string\." app/src/main` | **0 matches, 0 files, 93 files searched** |
  | Strings declared in `strings.xml` | file read | **2** (`app_name`, `accessibility_service_description`) |
  | `@string/` references in all `res/` + manifest XML | `rg -n '@string/' app/src/main --glob '*.xml'` | **2**, both in XML, **none** from Kotlin |
  | Literals in a rendering position (`Text("…")`, `contentDescription = "…"`, `setContentTitle/Text("…")`), multiline-aware | .NET regex sweep over `app/src/main/java/**/*.kt` | **131** across **14** files (120 / 7 / 4) |
  | Broader sweep: whitespace-containing literals excluding comments (upper envelope, includes log and exception text) | same sweep, relaxed filter | **371** (**349** distinct) across **45** files |
  | `values-*` configuration-qualified folders | `Get-ChildItem res -Directory` | **0** — folders present are `drawable, layout, mipmap-anydpi-v26, mipmap-{m,h,xh,xxh,xxxh}dpi, raw, values, xml` |

  So **131 is the confirmed floor** for directly-rendered hardcoded strings and 349 the ceiling
  including diagnostics. The consequence is that the app is English-only *by construction*: adding a
  `values-es/strings.xml` would change nothing, because no code path reads a string resource. The
  sharpest form of the defect is the inversion it produces — the only two strings that **are**
  externalized are `app_name` and `accessibility_service_description`, i.e. exactly the two that
  Android refuses to accept as literals from a manifest/XML attribute. Nothing was externalized by
  choice. Every string the *user* reads while protection is acting on them — the friction overlay copy,
  the lock screens, the countdown notification, the VPN status text — is hardcoded.
- **Trigger condition**:
  1. `adb shell am start -n com.grayzone.app/.MainActivity` on any device.
  2. `adb shell settings put system system_locales es-ES` (or Settings → System → Languages → add
     Español and move it to the top), then relaunch.
  3. Observe every screen still renders English. Only the app label and the Accessibility-settings
     service description would follow the locale, and only if `values-es/` existed.
  4. Static equivalent, no device: run the `rg -c "stringResource|R\.string\." app/src/main` command
     above and observe `0 matches`.
- **Blast radius**:
  - *Callers*: `strings.xml`'s two entries have exactly two consumers, enumerated by direct grep over
    all XML: `AndroidManifest.xml:51` (`android:label`) and
    `res/xml/accessibility_service_config.xml:7` (`android:description`). Zero Kotlin consumers. In the
    other direction, the 14 files holding rendered literals are `ui/VpnScreen.kt`, `ui/ScheduleSheet.kt`,
    `ui/SettingsScreen.kt`, `ui/OnboardingScreen.kt`, `ui/LimitsScreen.kt`, `ui/PerAppSettingsSheet.kt`,
    `ui/CustomPromptsSheet.kt`, `ui/AppsScreen.kt`, `ui/DailyBreakCard.kt`, `ui/QuickFocusCard.kt`,
    `ui/StreakCard.kt`, `ui/HomeScreen.kt`, `SharedComponents.kt`, `MainActivity.kt`, with the overlay
    copy additionally in `service/overlay/{Friction,Lock,BudgetLock,ScheduleLock}Presentation.kt` and
    `service/OverlayService.kt`.
  - *Process death*: none. Resource resolution is stateless and re-resolves on every process start.
  - *Config change*: **this is the axis that is broken.** Locale is a configuration qualifier; because
    no value is read through the resource system, a locale change produces no re-resolution. Compose
    recomposes on configuration change but recomposes the same literals.
  - *Foreground/background*: none. The overlay and notification literals render identically regardless
    of app state.
  - *Cross-process*: `:a11y` renders no user-facing text (`GrayzoneAccessibilityService.kt` contains no
    UI or notification code), so the split does not widen this.
  - *Persistence*: none. No string here is written to Room, prefs, or shared storage.
  - *Release-only*: `isShrinkResources = true` (`app/build.gradle.kts:59`). The two `strings.xml`
    entries are referenced from XML, which the resource shrinker follows, so both survive — confirmed by
    the release APK containing a populated `resources.arsc` (405,432 bytes). A fix that moves literals
    into `strings.xml` and reads them via `R.string` is likewise shrinker-safe (direct `R` references),
    so the fix carries no release-only risk.
- **Fix** (minimal): this is deliberately *not* a "translate the app" task. The minimal change that
  resolves the root cause — that there is no mechanism — is to (a) create
  `res/values/strings.xml` entries for the **protection-surface** strings only, i.e. the ~30 literals in
  `service/overlay/*Presentation.kt`, `OverlayService.startCountdownNotification()`/`buildNotification()`,
  and `AdBlockVpnService`'s notification builder, and read them via `getString(R.string.…)`; and (b) add
  a `lint.xml` (or `lint { warning "HardcodedText" }` once `checkReleaseBuilds` is restored) so new
  literals are visible. Externalizing all 131 in one pass is a larger change than the finding warrants.
- **Rewrite justification**: not applicable — no component is replaced.
- **Test to add**: `jvm-unit` — `app/src/test/java/com/grayzone/app/resources/StringResourceCoverageTest.kt`,
  a source-scanning test asserting that no file under `src/main/java/com/grayzone/app/service/overlay/`
  contains a literal in a `title =`/`subtitle =`/`body =` position. Fails before the fix (5 presentation
  files match), passes after. Chosen as a source-scan rather than a Robolectric test because the
  assertion is about *where strings live*, which no runtime test can observe.
- **Existing test conflict**: `app/src/test/java/com/grayzone/app/service/overlay/OverlayPresentationTest.kt`
  asserts presentation copy by literal comparison (12 literal-bearing lines). When the presentations
  start returning `@StringRes Int`s the test must change to assert resource IDs, or the presentations
  must keep returning `String` and take a resolver — the latter keeps the test intact and is the reason
  the fix above is scoped to the call site rather than the presentation type.
- **Verification**: `confirmed` (evidence tier T1).
  - *Missing*: nothing. Every number above is from a static command over the working tree.
  - *Procedure*: `rg -c "stringResource|R\.string\." app/src/main` → `0 matches ... 93 files searched`.
  - *Severity effect*: none; not conditional.
- **Build scope**: everyday-build
- **Constitution**: II (one definition per concept — 349 distinct literals with no single source of truth)
- **Affected surfaces**: the same root cause produces **F-176** (a literal that is additionally never
  updated) and is why **F-177**'s `24sp` has nowhere to live but the layout XML.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-176 — The countdown notification's subtitle is a hardcoded English literal that is also never written at runtime, so it permanently reads "Focus session" regardless of what the session is  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/layout/notification_countdown.xml` → `@+id/notif_sub` (lines 29–37,
  literal at line 37). Consumer: `app/src/main/java/com/grayzone/app/service/OverlayService.kt` →
  `startCountdownNotification()` (lines 722–726).
- **Issue**: The layout declares `android:text="Focus session"` on `notif_sub`. The `RemoteViews` block
  that populates this layout sets **only** `notif_title` and `notif_timer`:

  ```722:726:app/src/main/java/com/grayzone/app/service/OverlayService.kt
          val body = RemoteViews(applicationContext.packageName, R.layout.notification_countdown).apply {
              setTextViewText(R.id.notif_title, appName)
              setChronometerCountDown(R.id.notif_timer, true)
              setChronometer(R.id.notif_timer, baseElapsed, null, true)
          }
  ```

  A direct grep for `notif_sub` across all of `app/src` returns **exactly one** hit — its own
  declaration in the layout. There is no `R.id.notif_sub` reference in any Kotlin file. So this is two
  defects in one line, and both are user-visible on the app's most important passive surface:
  (1) it is untranslatable (this is the `HardcodedText` lint at `:37` recorded in `evidence/lint.md`
  §E7 and assigned to T028); and (2) it is **static** — the second line of the countdown notification
  says "Focus session" whether the user is in a quick-focus session, a per-app timed session, or a
  schedule-granted window. It is the only text in the notification that could distinguish those states
  and it distinguishes nothing. A related dead literal sits at `OverlayService.kt:733`:
  `.setContentText("Session active")` is overridden by `setCustomContentView` under
  `DecoratedCustomViewStyle`, so it never renders in the shade either.
- **Trigger condition**:
  1. Grant overlay + accessibility permissions and set a limit on a monitored app.
  2. Open the monitored app and complete the friction wait so a session is granted; the countdown
     notification posts.
  3. Pull down the shade and read line 2 of the notification: `Focus session`.
  4. Grant a session by a different route (schedule window, or the quick-focus card) and repeat — line 2
     is byte-identical.
  5. Static equivalent, no device: `rg -n "notif_sub" app/src` → one hit, the layout declaration only.
- **Blast radius**:
  - *Callers*: `R.layout.notification_countdown` has exactly one caller, `OverlayService.kt:722`
    (verified by direct grep across `app/src`, Kotlin **and** XML — the layout is not `<include>`d
    anywhere). `notif_sub` has zero callers.
  - *Process death*: none. The notification is rebuilt from scratch on every
    `startCountdownNotification()` call; nothing about this text is cached across process death.
  - *Config change*: the string does not follow locale (F-175's mechanism) and, because it is baked into
    the layout rather than pushed via `RemoteViews`, it also cannot follow any other qualifier.
  - *Foreground/background*: unchanged — this is an ongoing notification rendered by SystemUI, so it is
    visible precisely when the app is backgrounded.
  - *Cross-process*: **applicable.** The layout is inflated in the **SystemUI** process, not in
    `com.grayzone.app` and not in `:a11y`. That is why the text can only be changed through a
    `RemoteViews` action (`setTextViewText`) and not by touching a `View` — any fix must use the
    `RemoteViews` API, which is the same reason the auto-size mitigation is unavailable in F-177.
  - *Persistence*: none.
  - *Release-only*: none. `R.id.notif_sub` is referenced from the layout XML, so the resource shrinker
    retains it in both variants; adding a Kotlin `setTextViewText(R.id.notif_sub, …)` does not change
    shrinker behaviour.
- **Fix** (minimal): delete `android:text="Focus session"` from the layout and add one line to the
  `RemoteViews` block — `setTextViewText(R.id.notif_sub, getString(R.string.notif_session_subtitle))`
  — with the string parameterized by the grant reason already available at the call site. Two lines
  changed, one string added.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/NotificationLayoutContractTest.kt`, parsing
  `src/main/res/layout/notification_countdown.xml` and asserting that no `TextView` carries a literal
  `android:text` attribute. Fails before the fix (`notif_sub` matches), passes after. A source/XML
  assertion is used because `RemoteViews` population cannot be observed from a JVM unit test without
  Robolectric, which the project does not depend on.
- **Existing test conflict**: none. No test under `app/src/test` or `app/src/androidTest` references
  `notification_countdown`, `notif_sub`, or `startCountdownNotification` (verified by direct grep).
- **Verification**: `confirmed` (evidence tier T1).
  - *Missing*: nothing. Both halves are settled statically — the literal by reading the layout, the
    never-written half by the single-hit grep for `notif_sub`.
  - *Procedure*: `rg -n "notif_sub" app/src` returns only
    `app/src/main/res/layout/notification_countdown.xml:30`.
  - *Severity effect*: none; not conditional.
- **Build scope**: everyday-build
- **Constitution**: I (a declared view field with no writer is dead configuration)
- **Affected surfaces**: `OverlayService.kt:733` (`setContentText("Session active")`, suppressed by
  `DecoratedCustomViewStyle` and therefore also dead); the same layout is the subject of **F-177** and
  **F-178**, which are independent root causes in the same file.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-177 — The countdown notification's body overflows the collapsed row's height budget at accessibility font scales, and because the expanded view is the same `RemoteViews` object, expanding the notification cannot reveal what was clipped  [High]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/layout/notification_countdown.xml` (lines 6–49, whole file; the
  `24sp` at line 47 and the absence of any height bound) → consumed by
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` → `startCountdownNotification()`
  (lines 722–750, specifically `:736–737`).
- **Issue**: Two facts combine, one arithmetic and one confirmed by reading the code.

  **(a) The layout has no defence against font scaling.** Every text dimension is in `sp`, which the
  system multiplies by the user's `font_scale`: the `Chronometer` at `android:textSize="24sp"`
  (`:47`), and the two `TextView`s inheriting `TextAppearance.Compat.Notification.Title` (≈14sp) and
  `…Notification.Line2` (≈12sp). The root is `android:layout_height="wrap_content"` (`:8`) with
  `4dp + 4dp` padding and **no `maxHeight`, no `minHeight`, and no `dimens.xml` to override** (there is
  no `res/values/dimens.xml` in this project at all — see "Noticed, not filed"). The collapsed
  notification row rendered by SystemUI has a bounded height, so a body that measures taller is clipped
  rather than scrolled. Taking the standard 1.2× line-spacing factor and the framework's 48dp collapsed
  row, at 1dp = 1px (mdpi):

  | `font_scale` | Left column (title + 1dp margin + sub) | Chronometer | Body incl. 8dp padding | vs 48dp row |
  |---|---|---|---|---|
  | 1.0 (default) | 16.8 + 1 + 14.4 = **32.2dp** | 28.8dp | **40.2dp** | fits, ~8dp spare |
  | 1.15 | 19.3 + 1 + 16.6 = **36.9dp** | 33.1dp | **44.9dp** | fits, ~3dp spare |
  | **1.30** | 21.8 + 1 + 18.7 = **41.5dp** | 37.4dp | **49.5dp** | **exceeds** |
  | 1.50 | 25.2 + 1 + 21.6 = **47.8dp** | 43.2dp | **55.8dp** | **exceeds** |
  | 2.00 (Android 14 max) | 33.6 + 1 + 28.8 = **63.4dp** | ~44dp (non-linear cap) | **71.4dp** | **exceeds** |

  Note which element fails first: Android 14's non-linear font scaling damps growth **above** 20sp, so
  it protects the 24sp `Chronometer` and leaves the 14sp/12sp column scaling almost linearly. The app
  name and the subtitle are therefore the first to be cut, and the existing `maxLines="1"` +
  `ellipsize="end"` on both (`:25–26`, `:35–36`) guard **width**, not height — they cannot help here.

  **(b) The user's only recovery is wired to the same object.** This half needs no device:

  ```736:737:app/src/main/java/com/grayzone/app/service/OverlayService.kt
                  .setCustomContentView(body)
                  .setCustomBigContentView(body)
  ```

  Both the collapsed and expanded views are the identical `body` instance built at `:722`. Expanding
  the notification — the universal user gesture for "I can't read this" — renders a byte-identical
  hierarchy with the same `wrap_content` body and the same `24sp`. There is no expanded variant to fall
  back to.

  **Why this is not cosmetics.** The countdown is the app's only passive indication of how much session
  time remains. A user at an accessibility font scale — the population most likely to have raised it —
  can lose the app name, the subtitle, or the digits from the one surface that tells them protection is
  counting down, and cannot recover it by expanding. That is a user-visible degradation of a protection
  surface, which the FR-006 rubric places at High.
- **Trigger condition**:
  1. Grant overlay + accessibility permissions, set a limit on a monitored app.
  2. `adb shell settings put system font_scale 1.3` (or Settings → Accessibility → Display size and
     text → Font size, two steps up).
  3. Open the monitored app and complete the friction wait so a session is granted and the countdown
     notification posts.
  4. Pull down the shade. Inspect the collapsed row: check whether the subtitle line, the app-name line,
     or the bottom of the digits is cut off.
  5. Expand the notification and confirm the expanded row is identical to the collapsed one.
  6. Repeat at `adb shell settings put system font_scale 2.0`, then restore with
     `adb shell settings put system font_scale 1.0`.
  7. Repeat steps 3–5 on a Samsung One UI and a Xiaomi HyperOS device — OEM notification templates use
     different row heights, and the file's own header comment (`:2–5`) records that OriginOS already
     broke the framework header chronometer, so OEM divergence on this surface is established.
- **Blast radius**:
  - *Callers*: one — `OverlayService.startCountdownNotification()` (`:722`). Established by direct grep
    for `notification_countdown` across `app/src` in both Kotlin and XML; the layout is not `<include>`d.
    `startCountdownNotification()` is in turn reached from three sites recorded in
    `evidence/prior-fix-watchlist.md` (Fragile fixes #4): `markAppUnlocked()`,
    `executeCommand(UpdateState)`, and the `ACTION_START_COUNTDOWN` intent handler
    (`OverlayService.kt:662–667`).
  - *Process death*: none directly — the notification is re-posted from scratch. But it interacts:
    `evidence/prior-fix-watchlist.md` Fragile fixes #2 records that after process death the countdown is
    cleared only incidentally by `startForeground(NOTIF_ID, buildNotification())`. A clipped countdown
    surviving that path is the same notification id, so the incidental overwrite still applies.
  - *Config change*: **this is the axis that is broken.** `font_scale` is a configuration property; a
    change to it triggers re-inflation of the `RemoteViews` in SystemUI with the new scale, and the
    layout has no qualifier or bound to respond with. There is no font-scale resource qualifier in
    Android, so a `values-*` folder cannot fix this — only a runtime clamp can.
  - *Foreground/background*: the defect is **only** observable while the app is backgrounded, since that
    is when the countdown notification is the user's sole view of session state.
  - *Cross-process*: **applicable, and it constrains the fix.** The layout is inflated in the SystemUI
    process from a `RemoteViews` action list. The app holds no `View` reference and cannot measure the
    result, cannot call `TextView` setters directly, and cannot read back the rendered height. Any fix
    must be expressible as a `RemoteViews` action — `setTextViewTextSize(int, int, float)` is, and is
    public API since API 16.
  - *Persistence*: none.
  - *Release-only*: none. `R.layout.notification_countdown` and all three ids are referenced directly
    from Kotlin (`R.layout.…`, `R.id.…`), so the resource shrinker retains them; the release APK's
    `resources.arsc` is intact (405,432 bytes). `@style/TextAppearance.Compat.Notification.{Title,Line2}`
    come from `androidx.core` and are referenced from layout XML, which the shrinker follows. Verified
    no release-only divergence.
- **Fix** (minimal): two changes, both small and both inside the existing call.
  1. Clamp the timer's growth in `startCountdownNotification()` rather than in the layout, because no
     resource qualifier exists for font scale:
     `body.setTextViewTextSize(R.id.notif_timer, TypedValue.COMPLEX_UNIT_PX, 24f * resources.displayMetrics.density * min(resources.configuration.fontScale, 1.3f))`.
     This preserves scaling up to 130% and stops there, keeping the digits inside the row.
  2. Give the expanded view real value instead of the same object: build a second `RemoteViews` for
     `setCustomBigContentView` that stacks the title, subtitle, and timer vertically with no height
     pressure, so expanding is a genuine recovery. If a second layout is judged too large a change, the
     strictly minimal variant is to drop `setCustomBigContentView(body)` entirely — the framework then
     falls back to the standard expanded template using `setContentTitle`/`setContentText`, which is
     unbounded, and this also gives `OverlayService.kt:733`'s currently-dead `"Session active"` text a
     purpose (see F-176).
- **Rewrite justification**: not applicable — both changes patch the existing call site and layout; no
  component is replaced. The second layout in fix (2) is an addition, not a replacement, and the
  finding explicitly offers a no-new-file variant.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/CountdownNotificationLayoutTest.kt`, inflating
  `R.layout.notification_countdown` against a `Configuration` with `fontScale = 1.3f` and asserting the
  measured height at `MeasureSpec.AT_MOST(48dp)` does not exceed 48dp. Fails before the fix, passes
  after. **This test cannot be executed in this audit** — no device or emulator is available; it is
  specified so the maintainer can run it. A `jvm-unit` companion is also worth adding at
  `app/src/test/java/com/grayzone/app/service/NotificationLayoutContractTest.kt` (shared with F-176)
  asserting that `setCustomContentView` and `setCustomBigContentView` are not passed the same
  expression — a source assertion, since the builder is not reachable from the JVM.
- **Existing test conflict**: none. No test under `app/src/test` or `app/src/androidTest` references
  `notification_countdown` or `startCountdownNotification`; `SessionExpiryDecisionTest` covers only the
  pure clamp rule, and `evidence/prior-fix-watchlist.md` already records that
  `startCountdownNotification`'s guard is untested.
- **Verification**: `needs-verification` (evidence tier T4).
  - *Missing*: a device at a raised `font_scale`. What is missing is specifically **half (a)** — the row
    height budget under `DecoratedCustomViewStyle` varies by platform version and OEM template, and the
    1.2× line-spacing factor in the table is the standard assumption, not a measurement. **Half (b) is
    confirmed statically** by reading `OverlayService.kt:736–737`, and is not conditional.
  - *Procedure*: steps 1–7 above. Minimum useful run: steps 1–5 at `font_scale 1.3` and `2.0` on one
    API 34 device; the OEM sweep in step 7 strengthens it but is not required to settle severity.
  - *Severity effect*: if text is clipped at `font_scale ≥ 1.3` on stock Android → **High** as filed
    (user-visible degradation of a protection surface, unrecoverable by expanding). If clipping starts
    only at `≥ 1.8` → **Medium** (narrow conditions). If the platform reflows or shrinks the row so
    nothing is lost at any scale → **Low**, and the finding reduces to fix (2) alone, since the
    identical-expanded-view defect stands on its own regardless of the clipping outcome.
- **Build scope**: everyday-build
- **Constitution**: `none` — this is a resource-robustness defect; no I/O or service boundary is
  involved, so Principle III is not implicated.
- **Affected surfaces**: `OverlayService.buildNotification()` (`:1195`) and
  `AdBlockVpnService` (`:604`) both post notifications on the same protection-visible plane, but both
  use standard templates with no custom `RemoteViews`, so neither shares this root cause — checked, not
  assumed. The absence of `res/values/dimens.xml` is the reason `24sp` has nowhere to live but the
  layout, and is folded into this fix rather than filed separately.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

### F-178 — The countdown digits are pinned to the light-mode accent in the layout XML while the same notification's `setColor` resolves the dark-mode accent, so one notification carries two accents and the timer drops to ~3.1:1 contrast on a dark shade  [Medium]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/layout/notification_countdown.xml` → `@+id/notif_timer`,
  `android:textColor="#0F766E"` (line 46) → consumed by
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` → `startCountdownNotification()`
  (line 741, `.setColor(overlayChrome().accent)`).
- **Issue**: The layout's own header comment states the design intent explicitly — the text appearances
  were chosen *"so the title/subtitle theme correctly (legible) across OEM skins and light/dark shades"*
  (`:4–5`). `notif_title` and `notif_sub` honour that: both use
  `TextAppearance.Compat.Notification.{Title,Line2}`, which resolve against the shade's theme. The
  `Chronometer` — the only element that actually carries the countdown — opts out with a literal
  `#0F766E`.

  This is not an arbitrary colour. `#0F766E` is `ACCENT_LIGHT`, declared at
  `OverlayService.kt:77`, and the app has a distinct dark-mode accent `ACCENT_DARK = #2DD4BF` at `:87`
  (mirrored in `ui/theme/Color.kt:55,96`). `overlayChrome()` (`:1150–1161`) selects between them via
  `isNightMode(this)`, and **the very same builder chain uses it**: `.setColor(overlayChrome().accent)`
  at `:741` is dark-mode aware. So in dark mode the notification's own tint colour is `#2DD4BF` while
  the digits inside it are `#0F766E` — two accents, one notification, decided by two different
  mechanisms.

  Measured WCAG 2.1 relative-luminance contrast (sRGB, standard formula):

  | Foreground | Relative luminance | vs typical dark shade `#1B1C1E` | vs pure black `#000000` | vs light shade `#FFFFFF` |
  |---|---|---|---|---|
  | `#0F766E` (shipped) | 0.1418 | **3.12 : 1** | 3.84 : 1 | 5.47 : 1 |
  | `#2DD4BF` (app's own dark accent) | 0.5140 | **9.16 : 1** | 11.28 : 1 | 1.99 : 1 |

  At 24sp bold the digits qualify as WCAG "large text", whose AA threshold is 3.0:1 — so the shipped
  colour scrapes past by 0.12 on a `#1B1C1E` shade and **fails** the 4.5:1 normal-text and AAA
  large-text thresholds. The app's own dark accent would give 2.9× the contrast. Note the interaction
  with **F-177**: if the clamp in F-177's fix is applied and the digits render smaller than the
  18pt-bold-equivalent "large text" boundary, the applicable threshold becomes 4.5:1 and 3.12:1 fails
  outright — so the two fixes must be designed together.
- **Trigger condition**:
  1. `adb shell cmd uimode night yes` (or Settings → Display → Dark theme → On).
  2. Grant overlay + accessibility permissions, set a limit on a monitored app, and complete the
     friction wait so a session is granted.
  3. Pull down the shade with the dark shade active and compare the teal countdown digits against the
     notification's own tint colour on the header — they are visibly different teals.
  4. Static equivalent, no device: `rg -n "0F766E" app/src` returns
     `res/layout/notification_countdown.xml:46`, `OverlayService.kt:77`, and
     `AdBlockVpnService.kt:605`; `rg -n "ACCENT_DARK" app/src` returns `OverlayService.kt:87,1153`.
     The layout uses the light constant unconditionally; the Kotlin selects.
- **Blast radius**:
  - *Callers*: one — `OverlayService.startCountdownNotification()` (`:722`), by direct grep over
    `app/src` in Kotlin and XML. `AdBlockVpnService.kt:605` hardcodes the same `0xFF0F766E` for its own
    `.setColor()` and is a sibling instance of the root cause, but it is a Kotlin constant in RU-06's
    file, not a resource, so it is listed under *Affected surfaces* rather than owned here.
  - *Process death*: none. The colour is a layout attribute re-resolved on every inflation.
  - *Config change*: **this is the axis that is broken.** `uiMode` night is a configuration qualifier,
    and there is no `res/values-night/` or `res/color/` selector in this project (folder listing in
    F-175 confirms: no `values-night`, no `color/`). So the one place where the platform would
    ordinarily solve this for free is absent — see **F-182**.
  - *Foreground/background*: observable only while backgrounded, as with F-177.
  - *Cross-process*: applicable. Inflated in SystemUI, so the colour is resolved against **SystemUI's**
    configuration, not the app's. This matters for the fix: `isNightMode(this)` at
    `OverlayService.kt:1151` reads the *app's* configuration, which can disagree with the shade's if the
    user has forced an in-app theme. A `RemoteViews.setTextColor` fix inherits that disagreement; a
    `values-night` colour resource does not, because it is resolved at inflation time in the correct
    process. That argues for the resource-side fix over the Kotlin-side one.
  - *Persistence*: none.
  - *Release-only*: none. A colour resource referenced from layout XML is followed by the resource
    shrinker; no release-only divergence.
- **Fix** (minimal): replace `android:textColor="#0F766E"` with a reference —
  `android:textColor="@color/notif_timer"` — and add `res/values/colors.xml` with
  `<color name="notif_timer">#0F766E</color>` plus `res/values-night/colors.xml` with
  `#2DD4BF`. Three lines and two small files; the platform then resolves it in SystemUI's process, which
  is the correct one. This is preferred over
  `RemoteViews.setTextColor(R.id.notif_timer, overlayChrome().accent)` precisely because of the
  cross-process note above.
- **Rewrite justification**: not applicable.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/service/NotificationColorTest.kt`, resolving
  `R.color.notif_timer` against a `Configuration` with `uiMode = UI_MODE_NIGHT_YES` and asserting it
  equals `0xFF2DD4BF`, and against `UI_MODE_NIGHT_NO` asserting `0xFF0F766E`. Fails before the fix (the
  resource does not exist), passes after. **Cannot be executed in this audit** — no device or emulator.
- **Existing test conflict**: none. No test references `notification_countdown`, `notif_timer`,
  `ACCENT_DARK`, or `overlayChrome` (verified by direct grep across `app/src/test` and
  `app/src/androidTest`).
- **Verification**: `needs-verification` (evidence tier T2).
  - *Missing*: the *code* half is confirmed — the literal at `:46`, the two accent constants at
    `OverlayService.kt:77,87`, and the dark-aware `.setColor()` at `:741` were all read directly, and
    the contrast figures are computed, not estimated. What is unverified is the **actual shade
    background colour** on real devices: the notification background is a dynamic-colour surface on
    API 31+ and is OEM-skinned, so the true ratio could differ from the `#1B1C1E` reference.
  - *Procedure*: steps 1–3 above on an API 34 device; capture the shade with
    `adb exec-out screencap -p > shade.png`, read the pixel behind the digits, and recompute the ratio.
    Repeat on a Samsung One UI device, whose shade is lighter than stock.
  - *Severity effect*: **Medium** as filed if the measured ratio lands in the 3.0–4.5:1 band (legible
    but sub-AA for anything but large text, and internally inconsistent with the notification's own
    tint). **High** if any real shade background puts it below 3.0:1, since the countdown would then be
    illegible on a protection surface. **Low** if every measured shade yields ≥ 4.5:1, at which point
    the finding reduces to the internal inconsistency between `:46` and `:741` — still a real defect,
    but cosmetic.
- **Build scope**: everyday-build
- **Constitution**: II (`#0F766E` is the same concept as `ACCENT_LIGHT` at `OverlayService.kt:77`,
  defined a third time as `0xFF0F766E` at `AdBlockVpnService.kt:605` and a fourth as
  `Color(0xFF0F766E)` at `ui/theme/Color.kt:55` — four definitions of one accent, none referencing a
  shared resource)
- **Affected surfaces**: `AdBlockVpnService.kt:605` (`.setColor(0xFF0F766E.toInt())`, hardcoded light
  accent on the VPN's own ongoing notification, with no dark-mode selection at all — a strictly worse
  instance of the same root cause, in RU-06's file); `OverlayService.kt:77,87` and
  `ui/theme/Color.kt:55,96` are the duplicate definitions. **F-182** is the missing `values-night`
  infrastructure that would make the fix here idiomatic.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-179 — The 10 MiB of blocklist assets are already packaged uncompressed, so the zero-heap memory-mapped load path is available today, yet they are copied into two permanent Java-heap `ByteArray`s instead  [Medium]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/raw/adblock_bloom.bin` (6,291,476 bytes) and
  `app/src/main/res/raw/adult_bloom.bin` (4,194,324 bytes) → decoded by
  `app/src/main/java/com/grayzone/app/service/vpn/GrayzoneBloomFilter.kt` →
  `GrayzoneBloomFilter.Companion.readFrom()` (lines 55–87, allocation at line 80), reached from
  `service/vpn/BlocklistManager.kt` → `loadFilter()` (lines 227–233) and `load()` (lines 143–160).
- **Issue**: Measured on-disk and in-memory cost, established by parsing the file headers directly
  (`magic`, `version`, `numHashes`, `bitCount` are read from the 20-byte header defined at
  `GrayzoneBloomFilter.kt:44–49`) and by enumerating the release APK's zip entries:

  | Asset | Source bytes | Header | Bit array (`bitCount / 8`) | APK entry (uncompressed / compressed) |
  |---|---|---|---|---|
  | `adblock_bloom.bin` | 6,291,476 | 20 | **6,291,456** (exactly 6 MiB) | `res/gY.bin` — 6,291,476 / **6,291,476** |
  | `adult_bloom.bin` | 4,194,324 | 20 | **4,194,304** (exactly 4 MiB) | `res/Kb.bin` — 4,194,324 / **4,194,324** |
  | `abused_tlds.txt` | 4,288 | — | n/a (parsed to `AbusedTldList`) | (text, deflated) |
  | **Total bit arrays** | | | **10,485,760 bytes = exactly 10.00 MiB** | |

  **The two facts that make this a finding:**

  1. **`Length == CompressedLength` for both `.bin` entries in the shipped release APK.** They are
     packaged with zip method `STORED`, not deflated — a well-populated Bloom filter is ~46% random
     bits, so deflate expands it and the packager falls back to storing. This is measurable, not
     inferred: `app/build/outputs/apk/release/app-release.apk` was enumerated entry by entry.
  2. **Because they are stored, `Resources.openRawResourceFd()` works on them.** That API returns an
     `AssetFileDescriptor` and throws `Resources.NotFoundException` for *compressed* resources only.
     So the memory-mapped load path — `openRawResourceFd()` → `FileChannel.map(READ_ONLY, startOffset,
     declaredLength)` → a `MappedByteBuffer` the filter indexes directly — is **available in this
     project right now, with no change to the assets, the build, or the generator.**

  Instead, `readFrom()` allocates `ByteArray(byteCountLong.toInt())` at `:80` and `readFully()`s the
  whole file into it through `openRawResource()`'s copying stream. The consequence is that 10,485,760
  bytes sit as two large contiguous **Java heap** allocations for the life of the process, where they
  are opaque to the kernel: dirty anonymous heap pages cannot be reclaimed under memory pressure, only
  released by the app itself. Memory-mapped file pages are clean and file-backed, so the kernel evicts
  and re-faults them transparently with **no app involvement at all**.

  **This is the resource-side answer to the two findings that already own the consequences.** F-027
  reports the ~10 MB as held and never released; F-067 reports that there is no `onTrimMemory`/
  `onLowMemory` anywhere and leaves open whether the process is genuinely reclaimed under pressure
  given both services hold foreground notifications. The mmap path resolves both without needing either
  answer: file-backed clean pages are reclaimable **regardless of the process's `oom_adj` bucket**, so
  the foreground-notification question that F-067 cannot settle stops mattering for these 10 MiB, and
  there is nothing left for an `onTrimMemory` hook to release. That is the distinct contribution of this
  finding — it changes what F-027 and F-067 have to be fixed *with*, not merely their size.

  **Deliberately not claimed here.** A failed 6 MiB contiguous allocation would be caught by
  `BlocklistManager.kt:151`'s `catch (e: Throwable)` — widened from `Exception` in the uncommitted diff,
  per `evidence/prior-fix-watchlist.md` — which sets `isLoaded = false`, at which point
  `isBlocked()` (`:216`) returns `false` for everything outside the ~38 hardcoded high-confidence and
  ~30 DoH domains while the VPN notification still reports filtering as active. That chain is real and
  it is why this finding matters, but **the widened catch is owned by T019/T030 and the retention by
  F-027**, so this finding does not claim it and is not rated on it. Severity is set on the
  avoidable-cost mechanism alone, which is why it is Medium and not High.
- **Trigger condition**:
  1. Static, no device — the packaging claim: unzip the release APK and compare entry sizes.
     `python -c "import zipfile;[print(i.filename,i.file_size,i.compress_size,i.compress_type) for i in zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk').infolist() if i.filename.endswith('.bin')]"`
     → both entries report `compress_size == file_size` and `compress_type == 0` (STORED).
  2. Static, no device — the header claim: read bytes 0–19 of each `.bin`; both yield magic `GZBL`,
     version 1, `numHashes` 10, and `bitCount` 50,331,648 / 33,554,432.
  3. On a device, to observe the steady-state heap cost: start the VPN, wait for
     `BlocklistManager.load()` to complete (`Log.d(TAG, "Loaded ads bloom filter")`), then
     `adb shell dumpsys meminfo com.grayzone.app` and read the **Java Heap** row.
  4. Apply the fix and repeat step 3; the Java Heap row should fall by ~10 MB and the mapped pages
     should appear under `.so mmap` / file-backed rather than in the Java heap.
- **Blast radius**:
  - *Callers*: `R.raw.adblock_bloom` and `R.raw.adult_bloom` each have exactly one reference —
    `BlocklistManager.kt:147` and `:148` — verified by direct grep for `R.raw.` across all of `app/src`
    (Kotlin **and** XML; the raw resources are not referenced from any XML). `loadFilter()` has those two
    callers only; `GrayzoneBloomFilter.readFrom()` has one caller, `loadFilter()` at `:229`, plus test
    usage. `BlocklistManager.load(context)` itself has a single production caller,
    `AdBlockVpnService.kt:110`, which `findings.md` already records as verified.
  - *Process death*: the whole 10 MiB is re-read on every process start; nothing is cached across it.
    With the mmap fix the re-read becomes lazy page faults instead of a 10 MiB eager copy, so process
    death gets **cheaper**, not more expensive. Relevant because `evidence/prior-fix-watchlist.md`
    documents `onTaskRemoved()` restarting the service after a Recents swipe.
  - *Config change*: none. `res/raw` has no configuration qualifiers and none would be meaningful.
  - *Foreground/background*: the load is started from `AdBlockVpnService`, which holds a foreground
    notification, so the allocation happens in a process the LMK is unlikely to kill. That is exactly
    why heap-vs-mapped matters: the process surviving does **not** mean the 10 MiB is reclaimable.
  - *Cross-process*: not applicable. `GrayzoneApplication.kt:54`'s process guard means `:a11y` never
    reaches this path, and `findings.md` records the filters load once from a single call site.
  - *Persistence*: none. Nothing here writes Room, prefs, or shared storage.
  - *Release-only*: **applicable and load-bearing.** The STORED packaging that enables the fix was
    verified against the **release** APK (`isMinifyEnabled = true`, `isShrinkResources = true`,
    `app/build.gradle.kts:58–59`), which is the stricter case. `R.raw.*` is referenced directly from
    Kotlin so the resource shrinker retains both entries — confirmed by their presence in the release
    APK. However, the fix's dependence on STORED packaging is currently **undeclared**, which is
    **F-180**.
- **Fix** (minimal): change `GrayzoneBloomFilter` to hold a `ByteBuffer` rather than a `ByteArray`
  (`private val bits: ByteBuffer`; `bits[byteIdx]` at `:26` becomes `bits.get(byteIdx)`), and add a
  `readMapped(afd: AssetFileDescriptor)` companion function alongside the existing `readFrom(stream)`
  that maps `afd.startOffset + HEADER_SIZE` for `bitCount / 8` bytes. `BlocklistManager.loadFilter()`
  switches to `context.resources.openRawResourceFd(resId)`. `readFrom(InputStream)` **must be kept** —
  `app/src/test/java/com/grayzone/app/BloomFilterTest.kt` constructs filters through it at `:19`, `:75`,
  `:87`, and `:100`, and `AbusedTldList` still needs the stream path — so this adds a path rather than
  replacing one. Roughly 20 lines.
  **Honest cost of the fix**: `MappedByteBuffer.get(int)` is marginally slower than `ByteArray` indexing,
  and `mightContain()` performs `numHashes = 10` probes per candidate zone per filter. The benchmark
  named below exists to bound that; if it regresses materially the fallback is to map only the larger
  6 MiB filter.
- **Rewrite justification**: not applicable — `readFrom(InputStream)` is retained and the change is
  additive. The `ByteArray` → `ByteBuffer` field change is a type substitution inside one private
  constructor, not a component rewrite.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/vpn/GrayzoneBloomFilterMappedTest.kt`, writing a small
  synthetic `GZBL` file to a temp path, mapping it via `FileChannel.map` at a **non-zero start offset**
  (to reproduce the `AssetFileDescriptor.startOffset` case, which is the part most likely to be got
  wrong), and asserting `mightContain` returns identical results to the `readFrom(stream)` filter over
  the same input set. Fails before the fix (`readMapped` does not exist), passes after. A second
  `jvm-unit` assertion in the same file pins the packaging invariant that F-180 declares.
- **Existing test conflict**: **one, and it constrains the fix.**
  `app/src/test/java/com/grayzone/app/BloomFilterTest.kt` builds `GrayzoneBloomFilter` through
  `readFrom(ByteArrayInputStream(...))` at `:19`, `:75`, `:87`, and `:100`, and its `bloomBytes(...)`
  helper plus the hashing mirror at `:127` assume the current construction path. **Required change:
  none, provided `readFrom(InputStream)` is retained** — which is exactly why the fix above is additive.
  If a maintainer instead replaces the stream constructor with the mapped one, all four sites and the
  helper must be rewritten. `BlocklistManagerTest.kt` and the seven other files under
  `service/vpn/` do not touch `GrayzoneBloomFilter` construction (verified by direct grep for
  `GrayzoneBloomFilter` and `readFrom` across both test source sets).
- **Verification**: `confirmed` (evidence tier T1) for the mechanism; `needs-verification` (T4) for the
  runtime benefit only.
  - *Missing*: nothing for the finding itself — the file sizes, the header fields, the STORED packaging
    in the release APK, and the `ByteArray` allocation at `GrayzoneBloomFilter.kt:80` were all read
    directly. What is unmeasured is the **magnitude of the runtime improvement**: the exact Java-heap
    reduction and the probe-throughput cost of `MappedByteBuffer`.
  - *Procedure*: steps 3–4 above (`dumpsys meminfo` before and after) on any API 29+ device, plus a
    microbenchmark of `mightContain` over 100k domains against both implementations.
  - *Severity effect*: **Medium** as filed — an avoidable 10 MiB of permanent heap on a path where the
    zero-cost alternative is already available. **High** if a maintainer confirms the low-RAM
    `OutOfMemoryError` → `isLoaded = false` → silent filtering loss chain on a device with a reduced
    `dalvik.vm.heapgrowthlimit`; note that chain is owned by T019/T030 and F-027, so a confirmation
    should raise **those**, not necessarily this. **Low** if `MappedByteBuffer` probing proves
    materially slower on real hardware, since the fix would then be a trade rather than a win.
- **Build scope**: everyday-build
- **Constitution**: `none`
- **Affected surfaces**: `BlocklistManager.loadAbusedTlds()` (`:235–241`) uses the same
  `openRawResource()` stream path for `R.raw.abused_tlds`, but at 4,288 bytes parsed into a set the
  mapping argument does not apply — checked and deliberately excluded. **F-180** (undeclared packaging
  dependency) and **F-181** (asset sizing) are the two other independent defects on these same assets.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-180 — Nothing declares that the blocklist assets must stay uncompressed; the packaging the app depends on is an accident of the data being incompressible  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/raw/{adblock_bloom,adult_bloom}.bin` (packaging) →
  `app/build.gradle.kts` → `android { }` block (lines 22–112), which contains **no**
  `androidResources { noCompress += … }` and no `aaptOptions`; the only `packaging { }` block
  (lines 97–101) excludes META-INF licences and says nothing about resources.
- **Issue**: The two `.bin` entries are STORED in the release APK (measured in F-179), but **not
  because anyone asked for it**. `.bin` is not in aapt2's default no-compress extension list; the
  entries are stored only because a ~46%-populated Bloom filter is incompressible, so deflate expands
  it and the packager falls back. Grep confirms the absence of any declaration: `noCompress`,
  `androidResources`, and `aaptOptions` return **zero** matches in `app/build.gradle.kts`.

  This matters because it is a silent precondition for F-179's fix and for the current load
  performance. `Resources.openRawResourceFd()` throws for compressed resources. So if a future
  regenerated filter is *sparser* — which happens the moment the filter is over-provisioned relative to
  its entry count, exactly the condition **F-181** documents for `adult_bloom.bin` at 37% fill — deflate
  would start winning, aapt2 would compress the entry, and the mapped load path would begin throwing
  `Resources.NotFoundException` at runtime. Under the current `catch (e: Throwable)` at
  `BlocklistManager.kt:151` that failure resets `isLoaded = false` and is visible only in logcat. An
  invariant that the code depends on, that no build file states, and whose violation is silent.
- **Trigger condition**:
  1. Regenerate `adult_bloom.bin` via `scripts/blocklist_tools.ps1` at a lower fill ratio (or simply
     zero a large contiguous run of the existing file to simulate a sparser filter).
  2. `./gradlew assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`.
  3. Enumerate the APK entries and observe `compress_size < file_size` for the `.bin` entry — it is now
     DEFLATED.
  4. With F-179's fix applied, start the VPN and observe `Log.e(TAG, "Error loading bloom filters", e)`
     with a `Resources.NotFoundException`, `isLoaded` stuck at `false`, and the VPN notification still
     reporting that filtering is active.
- **Blast radius**:
  - *Callers*: not a symbol, so "callers" is the set of consumers of the packaging invariant:
    `BlocklistManager.loadFilter()` (`:227–233`) today via `openRawResource()`, and additionally
    `openRawResourceFd()` once F-179 lands. Established by direct grep for `R.raw.` across `app/src`;
    there are exactly three raw-resource references, all in `BlocklistManager.kt` (`:147`, `:148`,
    `:236`), and none from XML.
  - *Process death*: none. Packaging is fixed at build time.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none — `:a11y` never loads blocklists (`GrayzoneApplication.kt:54` guard).
  - *Persistence*: none.
  - *Release-only*: **applicable.** This is the axis the finding lives on. Compression behaviour is a
    packaging decision that can differ between the debug and release pipelines and can change with an
    AGP upgrade. Both APKs currently exist in `app/build/outputs/` and the release one was the one
    enumerated, so the invariant is confirmed for the stricter variant — but it is confirmed by
    observation, not by declaration, and observation does not survive a regenerated asset.
- **Fix** (minimal): one block in `app/build.gradle.kts`, inside `android { }`:
  `androidResources { noCompress += listOf("bin") }`. Three lines. This makes the invariant explicit and
  survives any future change to the filters' bit density. (`app/build.gradle.kts` is RU-08's file; this
  finding is filed under RU-09 because the invariant is a property of the `res/raw` assets and their
  loader, and the one-line build edit is the mechanism, not the subject. T025 should be pointed at it
  rather than opening a parallel finding.)
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/vpn/BlocklistAssetPackagingTest.kt`, reading
  `src/main/res/raw/adblock_bloom.bin` and `adult_bloom.bin` from the project tree, deflating each with
  `java.util.zip.Deflater` at maximum level, and asserting the deflated size is **not smaller** than the
  input — i.e. asserting the incompressibility the packaging silently relies on. This fails the moment
  someone ships a sparse filter, which is precisely the regression window, and it runs on the JVM with
  no device. Passes today for both assets.
- **Existing test conflict**: none — and there is an established precedent for the test proposed above.
  `app/src/test/java/com/grayzone/app/service/vpn/AbusedTldListTest.kt:86–99`
  (`shipped resource covers the tlds this feature exists for`) already reads a shipped `res/raw` asset
  straight from the project tree via `File("src/main/res/raw/abused_tlds.txt")`, guarded by
  `assumeTrue(resource.exists())` so it skips when the generator has not been run. The packaging test
  should follow that exact shape, including the `assumeTrue` guard. It is the only test that touches
  `res/raw` (verified by direct grep for `adblock_bloom`, `adult_bloom`, `abused_tlds`, `res/raw`, and
  `openRawResource` across `app/src/test` and `app/src/androidTest`), and it asserts nothing about
  compression, so it does not conflict.
- **Verification**: `confirmed` (evidence tier T1).
  - *Missing*: nothing. The absence of the declaration is a grep over `app/build.gradle.kts`; the
    current STORED state is an enumeration of the release APK.
  - *Procedure*: `rg -n "noCompress|androidResources|aaptOptions" app/build.gradle.kts` → no matches.
  - *Severity effect*: none; not conditional. It is Low because nothing is broken today — the defect is
    an undeclared invariant, which the rubric places under maintainability.
- **Build scope**: release-only (the packaging behaviour is verified against, and matters most for, the
  release pipeline; the same rule applies to debug builds but the audit's release-only register should
  carry it)
- **Constitution**: `none`
- **Affected surfaces**: `res/raw/abused_tlds.txt` is plain text and *is* deflated; it is loaded through
  the stream path and must stay that way, so a blanket `noCompress` on all of `res/raw` would be wrong —
  the fix is deliberately scoped to the `bin` extension.
- **Observed against**: `557a963` (`app/build.gradle.kts` has uncommitted modifications, but none of
  them touch resource packaging — the diff adds a `debug { }` block and `lint { checkReleaseBuilds }`
  per `evidence/prior-fix-watchlist.md`, so the finding holds against both the committed and working
  tree state)
- **Disposition**: `verified`

### F-181 — The blocklist assets are 76% of the release APK, and the adult filter over-delivers on its own documented false-positive target by 21× at a cost of ~1.3 MiB of permanent APK and heap  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/raw/adult_bloom.bin` (4,194,324 bytes) and
  `app/src/main/res/raw/adblock_bloom.bin` (6,291,476 bytes); the stated target is documented at
  `app/src/main/java/com/grayzone/app/service/vpn/BlocklistManager.kt` → object header comment
  (lines 12–14) and `service/vpn/GrayzoneBloomFilter.kt` (lines 7–13). Generator:
  `scripts/blocklist_tools.ps1` (cited by both comments).
- **Issue**: Both filters were parsed and population-counted. Inverting the Bloom occupancy identity
  \( n = -\frac{m}{k}\ln(1-f) \) on the measured fill \(f\), and computing the achieved false-positive
  rate as \( f^{k} \):

  | Asset | `m` bits | `k` | Set bits | Fill `f` | Entries `n` | Achieved FP | Documented target | `m` needed for target | Excess |
  |---|---|---|---|---|---|---|---|---|---|
  | `adblock_bloom.bin` | 50,331,648 | 10 | 23,243,821 | 46.18% | ≈3,118,295 | **0.0441%** | ~0.1% | 44.8 Mbit = 5.35 MiB | 0.65 MiB (**12%**) |
  | `adult_bloom.bin` | 33,554,432 | 10 | 12,420,960 | 37.02% | ≈1,551,258 | **0.0048%** | ~0.1% | 22.3 Mbit = 2.66 MiB | **1.34 MiB (50%)** |

  **The headline conclusion is that the sizing is broadly correct and must not be "fixed" by shrinking
  it blindly** — the ads filter sits at 46.18% fill against the 50% optimum for `k = 10`, which is
  close to ideal engineering. This is the important honest result, because it means F-027's ~10 MB is a
  genuine cost of the data rather than waste, and cannot be resolved by regenerating smaller assets.

  The narrow defect is the adult filter. At 37% fill it is under-populated for its size: it achieves
  0.0048% false positives where the project documents a ~0.1% target — **21× better than asked for** —
  and pays 1.34 MiB of APK download, install footprint, and (per F-179) permanent Java heap for the
  surplus. A second symptom of the same cause: `numHashes = 10` is hardcoded identically in both
  headers, while the optimal `k = (m/n)·\ln 2` is **11** for the ads filter and **15** for the adult
  filter. One `k` was applied to two differently-proportioned filters, which is the signature of a
  generator that sizes `m` and `k` independently of the actual entry count.

  For scale: the two bit arrays total 10,485,760 bytes against a **13,778,794-byte release APK — 76.1%
  of everything the user downloads**, and 10,485,800 of 13,813,318 uncompressed APK bytes. Right-sizing
  both to the documented 0.1% target would remove ≈2.0 MiB (19%) of both the APK and the heap
  attributed to F-027.
- **Trigger condition**:
  1. Static, no device. Parse each header: bytes 0–3 = `GZBL`, 4–7 = version, 8–11 = `numHashes`,
     12–19 = `bitCount` (big-endian, per `GrayzoneBloomFilter.readI32`/`readI64` at `:98–112`).
  2. Population-count the bit array (bytes 20..EOF) and divide by `bitCount` to obtain the fill ratio.
  3. Compute `n = -(m/k)·ln(1-f)` and `p = f^k`. The values in the table above reproduce exactly.
  4. Compare `p` against the ~0.1% figure documented at `BlocklistManager.kt:13–14`.
- **Blast radius**:
  - *Callers*: `R.raw.adult_bloom` has exactly one reference, `BlocklistManager.kt:148`;
    `R.raw.adblock_bloom` exactly one, `:147`. Verified by direct grep for `R.raw.` over all of
    `app/src` in Kotlin and XML. **No deletion is proposed by this finding** — both assets are live and
    load-bearing; the proposal is a regeneration, which is why the reference check matters.
  - *Process death*: none. Asset content is fixed at build time.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none — the filters are read-only and never written back.
  - *Release-only*: the APK-percentage figure is measured against the release APK. Regenerating the
    assets changes both variants identically; no release-only divergence.
- **Fix** (minimal): regenerate `adult_bloom.bin` from `scripts/blocklist_tools.ps1` with
  `m` derived from the actual entry count and the documented target rather than fixed at 4 MiB —
  `m = ⌈n·(−ln p)/(ln 2)²⌉` and `k = round((m/n)·ln 2)` — which for 1,551,258 entries at `p = 0.001`
  gives `m ≈ 22.3 Mbit` (2.66 MiB) and `k = 10`. No app code changes: `GrayzoneBloomFilter.readFrom`
  already reads `bitCount` and `numHashes` from the header (`:68–73`) and validates them, so a
  differently-sized filter loads unmodified. **Do not apply the same treatment to `adblock_bloom.bin`
  without a decision** — its 12% surplus buys a 0.0441% false-positive rate against a 0.1% target, which
  is a defensible margin for an ads blocklist that grows between releases.
- **Rewrite justification**: not applicable — no code changes at all; this is an asset regeneration.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/service/vpn/BloomFilterSizingTest.kt`, reading both `res/raw`
  assets from the project tree, parsing the header, population-counting the bit array, and asserting
  the achieved false-positive rate `f^k` is within one order of magnitude of the documented 0.1%
  target. Fails before the fix for `adult_bloom.bin` (0.0048% is 21× off), passes after. This also
  guards the opposite regression — a filter that grows past its budget and degrades the FP rate — which
  is the more dangerous direction and is currently unguarded by anything.
- **Existing test conflict**: none. No test reads either `.bin` asset (verified by direct grep for
  `adblock_bloom` and `adult_bloom` across `app/src/test` and `app/src/androidTest`). The sizing test
  proposed above should copy the shape of
  `service/vpn/AbusedTldListTest.kt:86–99`, which is the project's existing precedent for reading a
  shipped `res/raw` asset from a JVM unit test — `File("src/main/res/raw/…")` behind an
  `assumeTrue(resource.exists())` guard so the suite still passes on a tree where the generator has not
  been run.
- **Verification**: `confirmed` (evidence tier T1).
  - *Missing*: nothing for the measurements. One modelling caveat is stated rather than hidden: the
    entry-count inversion assumes near-uniform independent hashing. The implementation uses
    Kirsch–Mitzenmacher double hashing (`h1 + i·h2 mod m`) over SHA-256 at
    `GrayzoneBloomFilter.baseHashes()` (`:31–36`), for which this identity is the standard model, and
    `bitCount` is `3·2²⁴` and `2²⁵` respectively so modulo bias is negligible. The derived `n` is
    therefore accurate to a few percent, which is far inside the margin needed to reach the conclusion.
  - *Procedure*: steps 1–3 above; any language with a popcount over the byte range reproduces the table.
  - *Severity effect*: none; not conditional. Low is correct — nothing is functionally wrong, the
    filters work and beat their target. The cost is APK and memory efficiency, which the rubric places
    under cleanliness.
- **Build scope**: everyday-build
- **Constitution**: `none`
- **Affected surfaces**: this measurement is the evidence that resolves **F-266**'s README discrepancy
  (see the dedicated section above) and is the input that makes **F-027** and **F-067** rateable.
  `BlocklistManager.kt:12–14`'s comment ("Shipped filters are ~6 MB (ads) and ~4 MB (adult) on disk/RAM,
  targeting a ~0.1% false-positive rate") is **accurate on the sizes** and is credited as such; only the
  FP-rate half is now known to be pessimistic by 21× for the adult filter.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-182 — The only theme in the project is an unconditional light theme with no `values-night` variant, so a dark-mode user gets a light window background behind every Compose screen and on the cold-start splash  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/values/themes.xml` (line 3, the whole file) →
  `<style name="Theme.Grayzone" parent="android:Theme.Material.Light.NoActionBar" />`; applied at
  `app/src/main/AndroidManifest.xml:53` (`<application android:theme>`) and `:58` (the activity).
- **Issue**: `themes.xml` declares exactly one style, and it inherits the **Light** platform theme.
  There is no `res/values-night/themes.xml`; the full folder listing of `res/` is
  `drawable, layout, mipmap-anydpi-v26, mipmap-{m,h,xh,xxh,xxxh}dpi, raw, values, xml` — **zero
  configuration-qualified `values-*` folders of any kind**. So `android:windowBackground` and
  `android:colorBackground` resolve to Material Light values in every configuration, including
  `uiMode = UI_MODE_NIGHT_YES`.

  The app is not otherwise light-only — it has a full dark palette. `ui/theme/Color.kt` defines a dark
  palette (`accent = #2DD4BF` at `:96`), `OverlayService.kt:85–93` mirrors it, `overlayChrome()`
  (`:1150`) selects on `isNightMode()`, and `findings.md`'s prefs inventory records a `theme_mode`
  preference that replaced `grayscale_enabled`. Every layer above the window is dark-aware; the window
  itself is not. Two user-visible consequences follow, both on the app's first impression:

  1. **Cold-start flash.** The window background is drawn by the system before the first Compose frame,
     and on API 31+ it is also the basis of the generated splash screen. A dark-mode user sees a light
     panel, then the app's dark surface.
  2. **Any uncovered window area is light.** Compose draws its own background, so this is bounded — but
     it is not zero: window insets, the region behind a translucent system bar, and the moment between
     `setContentView` and first composition all fall through to `windowBackground`.

  A secondary observation on the same line: `android:Theme.Material.*` is the *platform* theme family,
  superseded by AppCompat/MaterialComponents. At `minSdk = 29` it functions, but it means the app opts
  out of `android:forceDarkAllowed` handling and API 31+ dynamic-colour theming by construction, and it
  is why the fix below cannot simply be "switch the parent to a DayNight theme" — no AppCompat theme is
  available, because the project has no `appcompat` dependency (`app/build.gradle.kts:121–158`
  lists `core-ktx`, `activity-compose`, `material3`, and no `appcompat`).
- **Trigger condition**:
  1. `adb shell cmd uimode night yes` (or Settings → Display → Dark theme → On).
  2. `adb shell am force-stop com.grayzone.app`.
  3. `adb shell am start -n com.grayzone.app/.MainActivity` and watch the launch. Observe a light panel
     before the first dark frame.
  4. For a frame-accurate capture: `adb shell screenrecord --time-limit 5 /sdcard/launch.mp4` during
     step 3, then step through the first ~10 frames.
  5. Static equivalent, no device: `Get-ChildItem app/src/main/res -Directory` lists no `values-night`;
     `res/values/themes.xml:3` reads `parent="android:Theme.Material.Light.NoActionBar"`.
- **Blast radius**:
  - *Callers*: `@style/Theme.Grayzone` has exactly two references, both in `AndroidManifest.xml`
    (`:53`, `:58`), verified by direct grep over `app/src` in Kotlin **and** XML. There are no other
    styles in the project and no Kotlin reference to `R.style`.
  - *Process death*: none — the theme is applied at window creation on every launch.
  - *Config change*: **this is the axis that is broken.** `uiMode` night is a configuration qualifier
    and the resource system would resolve it for free given a `values-night` folder. Its absence means
    the one configuration the app clearly intends to support is the one the resource layer ignores.
    Rotation and locale are unaffected.
  - *Foreground/background*: the defect is visible only on the foreground transition (cold start and
    task resume), which is precisely when it is most noticeable.
  - *Cross-process*: none. `:a11y` has no window; `GrayzoneAccessibilityService` declares no theme and
    the overlay windows built by `OverlayService` construct their own `GradientDrawable` chrome
    (`makeCardBackground()`, `:1163`) rather than inheriting the activity theme — so **the protection
    surfaces are not affected by this finding**, which is why it is Low.
  - *Persistence*: none.
  - *Release-only*: none. `Theme.Grayzone` is manifest-referenced, so the shrinker retains it in both
    variants.
- **Fix** (minimal): add `res/values-night/themes.xml` declaring the same style name with a dark
  parent (`android:Theme.Material.NoActionBar`) — one new file, three lines, no code change and no new
  dependency. The resource system then resolves the correct window background per configuration. If the
  app's in-app `theme_mode` preference is meant to override the system setting, the follow-up is
  `AppCompatDelegate.setDefaultNightMode`, which requires the `appcompat` dependency and is explicitly
  **out of scope for this minimal fix** — the `values-night` folder is correct and useful on its own.
- **Rewrite justification**: not applicable.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/ThemeNightModeTest.kt`, resolving
  `android.R.attr.colorBackground` against `Theme.Grayzone` under a `Configuration` with
  `uiMode = UI_MODE_NIGHT_YES` and asserting the resolved colour's luminance is below 0.5. Fails before
  the fix (resolves the Material Light background in both modes), passes after. **Cannot be executed in
  this audit** — no device or emulator.
- **Existing test conflict**: none. No test under `app/src/test` or `app/src/androidTest` references
  `Theme.Grayzone`, `R.style`, or `uiMode` (verified by direct grep).
- **Verification**: `needs-verification` (evidence tier T2).
  - *Missing*: the *resource* half is confirmed — one theme, light parent, no `values-night`, two
    manifest references, all read directly. What is unverified is the **visible duration and extent** of
    the light background at runtime: how many frames the flash lasts, and whether Compose's own
    background covers the window fully enough that nothing else shows through.
  - *Procedure*: steps 1–4 above on an API 34 device, plus one API 29–30 device (the pre-`SplashScreen`
    starting-window behaviour differs and may make the flash longer).
  - *Severity effect*: **Low** as filed if the flash is a few frames and no steady-state surface is
    affected — cosmetic, first-impression only. **Medium** if any steady-state region (insets, bar
    scrim, or a screen that does not paint its own background) renders light in dark mode, since that is
    incorrect behaviour in a narrow but reachable condition. It does not reach High under any outcome:
    no protection surface inherits this theme, which was checked rather than assumed.
- **Build scope**: everyday-build
- **Constitution**: `none`
- **Affected surfaces**: **F-178** needs a `values-night` folder for its idiomatic fix, so the two
  should be fixed in one pass — this finding creates the folder that finding needs. The absence of
  `appcompat` (`app/build.gradle.kts`, RU-08's file) is the constraint that bounds both fixes.
- **Observed against**: `557a963`
- **Disposition**: `verified`

### F-183 — The launcher icon set was generated for a lower `minSdk` and never pruned: five density PNGs are shadowed by the adaptive icon on every supported device yet still ship 69,067 bytes in the release APK, and `roundIcon` duplicates `icon`  [Low]

- **Review unit**: RU-09
- **Location**: `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` (5 files) and
  `app/src/main/AndroidManifest.xml:49–50` (`android:icon` / `android:roundIcon`), against
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (the adaptive icon) and `minSdk = 29`
  (`app/build.gradle.kts:28`).
- **Issue**: Resource resolution for `@mipmap/ic_launcher` selects `mipmap-anydpi-v26` on every device
  running API ≥ 26, because `anydpi` outranks every specific density bucket and the `-v26` qualifier is
  satisfied. The project's `minSdk` is **29**. Therefore the five density-specific
  `ic_launcher.png` files are **never selected on any device the app supports** — they are shadowed by
  the adaptive icon 100% of the time.

  They ship anyway. Enumerating the release APK and reading each PNG's IHDR dimensions to attribute the
  obfuscated entry names:

  | Entry | Pixels | Bytes in release APK | Resolves? |
  |---|---|---|---|
  | `res/o-.png` | 192 × 192 | 31,483 | **never** (`ic_launcher` xxxhdpi) |
  | `res/RJ.png` | 144 × 144 | 18,771 | **never** (`ic_launcher` xxhdpi) |
  | `res/FS.png` | 96 × 96 | 9,591 | **never** (`ic_launcher` xhdpi) |
  | `res/yn.png` | 72 × 72 | 5,918 | **never** (`ic_launcher` hdpi) |
  | `res/9w.png` | 48 × 48 | 3,304 | **never** (`ic_launcher` mdpi) |
  | **Shadowed total** | | **69,067** | |
  | `res/{P6,7N,wG,Mu,3S}.png` | 432/324/216/162/108 | 193,403 | **live** — `ic_launcher_fg`, the adaptive foreground |

  `isShrinkResources = true` (`app/build.gradle.kts:59`) does **not** remove them: the resource shrinker
  works on name reachability, and `@mipmap/ic_launcher` *is* reachable — it simply resolves to a
  different configuration. Config-shadowed variants are outside its model. So 69,067 bytes ship in every
  release, which is 26% of the APK's total PNG payload (266,074 bytes).

  **The same root cause produces a second symptom in the manifest**: `android:roundIcon="@mipmap/ic_launcher"`
  (`:50`) points at the *same* resource as `android:icon` (`:49`). `roundIcon` exists to let a launcher
  pick a pre-masked circular bitmap; pointing it at the identical adaptive icon makes the attribute a
  no-op. Both symptoms are the signature of an Image Asset wizard run with pre-API-26 defaults that was
  never revisited after `minSdk` moved to 29 — which is also why `mipmap-anydpi-v26` still carries a
  `-v26` qualifier that can never be unsatisfied (the `ObsoleteSdkInt` lead in `evidence/lint.md` §E3,
  already owned by T059/T060/T062 and cited here, not re-filed).

  **What is correct and is deliberately credited**: the density ladder itself is complete and every
  asset is dimensionally exact. All ten PNGs were measured: `ic_launcher` at 48/72/96/144/192 px and
  `ic_launcher_fg` at 108/162/216/324/432 px — i.e. exactly 48dp and 108dp at mdpi/hdpi/xhdpi/xxhdpi/
  xxxhdpi, with the 108dp foreground matching the adaptive-icon specification. `mipmap-anydpi-v26/ic_launcher.xml`
  correctly declares `background`, `foreground`, **and** `monochrome`, so themed icons work on Android 13+.
  There is no missing density and no mis-sized asset. **Density coverage is not a defect in this app.**
- **Trigger condition**:
  1. Static, no device. `Get-ChildItem app/src/main/res/mipmap-anydpi-v26` shows `ic_launcher.xml`
     exists; `rg -n "minSdk" app/build.gradle.kts` shows `minSdk = 29`. Since 29 ≥ 26, the `-v26`
     qualifier is always satisfied and `anydpi` always outranks a density bucket.
  2. Enumerate the release APK's `res/*.png` entries and read each IHDR (bytes 16–23) for dimensions;
     the five 48/72/96/144/192 px entries are the shadowed set, totalling 69,067 bytes.
  3. On a device, to confirm which drawable is actually resolved:
     `adb shell dumpsys package com.grayzone.app | Select-String "icon"`, or in-app
     `packageManager.getApplicationIcon("com.grayzone.app")` — it returns an `AdaptiveIconDrawable`, not
     a `BitmapDrawable`, on every supported API level.
- **Blast radius**:
  - *Callers*: `@mipmap/ic_launcher` has exactly two references, `AndroidManifest.xml:49` and `:50`,
    established by direct grep across `app/src` for `ic_launcher`, `roundIcon`, and `android:icon` in
    **both** Kotlin and XML. There is **no** `R.mipmap.` reference anywhere in Kotlin (grep for
    `R.mipmap.` returns zero matches). `@mipmap/ic_launcher_fg` has exactly one reference,
    `mipmap-anydpi-v26/ic_launcher.xml:4`, and `@drawable/ic_launcher_background` /
    `@drawable/ic_launcher_monochrome` one each at `:3` and `:5` — the monochrome drawable is
    additionally live in Kotlin at `ui/HomeScreen.kt:91` (`painterResource(R.drawable.ic_launcher_monochrome)`).
    **Per the reference-index accuracy rule, every one of these was re-verified by direct search over
    `app/src` in both languages, and the index has no resource entries at all to check against.**
  - *Process death*: none.
  - *Config change*: density is a configuration qualifier, and this finding is precisely about the
    resolution outcome across it: the answer is `anydpi-v26` at every density on every supported API.
  - *Foreground/background*: none — the launcher icon is rendered by the launcher process.
  - *Cross-process*: applicable but benign. The icon is loaded by the launcher and by SystemUI, not by
    `com.grayzone.app` or `:a11y`. That is why the deletion is safe in a way a Kotlin-reference grep
    alone would not prove: the consumers are external, and they go through the same
    `PackageManager`/resource resolution that selects `anydpi-v26`.
  - *Persistence*: none.
  - *Release-only*: **applicable and measured.** The byte figures above are from the **release** APK
    with `isMinifyEnabled` and `isShrinkResources` both on, i.e. after every shrinking pass the project
    performs. Confirming the shrinker does *not* remove them is the point of measuring release rather
    than debug.
- **Fix** (minimal): delete the five `mipmap-*dpi/ic_launcher.png` files and remove
  `android:roundIcon="@mipmap/ic_launcher"` from `AndroidManifest.xml:50`. Keep every
  `ic_launcher_fg.png` — those are the live adaptive foreground and must not be touched. Optionally
  rename `mipmap-anydpi-v26` to `mipmap-anydpi` once the `ObsoleteSdkInt` cleanup task runs, but that is
  owned elsewhere and is not required for this fix.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/resources/LauncherIconResourceTest.kt`, walking
  `src/main/res/mipmap-*/` and asserting that no density-qualified directory contains a file whose base
  name also exists under `mipmap-anydpi-v26/` as an `<adaptive-icon>`. Fails before the fix (five
  `ic_launcher.png` files match), passes after. A source-tree assertion is used because resource
  *shadowing* is a build-time resolution property that no runtime test on a single device can observe —
  a device only ever tells you which variant won, never that the losers shipped.
- **Existing test conflict**: none. No test under `app/src/test` or `app/src/androidTest` references
  `ic_launcher`, `R.mipmap`, or the manifest icon attributes (verified by direct grep).
- **Verification**: `confirmed` (evidence tier T1).
  - *Missing*: nothing. The shadowing is a deterministic property of Android's resource resolution
    (`anydpi` outranks every density bucket; `-v26` is satisfied at `minSdk = 29`), not a runtime
    observation. The 69,067-byte figure is an enumeration of the shipped release APK with per-entry
    dimension attribution, so the obfuscated resource names do not weaken it.
  - *Procedure*: steps 1–2 above; step 3 is an optional on-device corroboration and is not needed to
    settle the finding.
  - *Severity effect*: none; not conditional. Low is correct under the rubric — this is dead resource
    weight with no user-visible consequence beyond ~69 KB of download.
- **Build scope**: everyday-build
- **Constitution**: I (resources retained with no reachable consumer on any supported API level)
- **Affected surfaces**: none beyond the files named. Explicitly **not** affected and must not be
  swept up in the deletion: all five `ic_launcher_fg.png` files (live adaptive foreground, 193,403
  bytes in the APK), `drawable/ic_launcher_background.xml`, and `drawable/ic_launcher_monochrome.xml`
  — the last of which has a *second*, Kotlin-side consumer at `ui/HomeScreen.kt:91` that a
  manifest-only or XML-only search would have missed. This is exactly the failure mode the
  reference-index accuracy warning exists to prevent, and it is why every reference above was searched
  in both Kotlin and XML.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Coverage summary

| Required coverage area | Verdict | Findings |
|---|---|---|
| 1. Hardcoded user-facing strings in Compose | **Defect, quantified.** 0 `stringResource`/`R.string.` in 93 files; 131 confirmed rendered literals (349 distinct including diagnostics); `strings.xml` holds 2 strings, both XML-only; 0 `values-*` folders. The app is English-only by construction. | F-175, F-176 |
| 2. `notification_countdown.xml` robustness | **Two independent defects.** Height budget exceeded from `font_scale ≈ 1.3` by arithmetic, and `setCustomBigContentView` is the same `RemoteViews` so expanding cannot recover — the second half confirmed statically. Separately the countdown digits are pinned to the light accent while the same builder resolves the dark one. Credited as correct: `maxLines`/`ellipsize` on both text views, RTL-safe `layout_marginStart`/`gravity="end"`, and Compat notification text appearances on title and subtitle. | F-177, F-178, F-176 |
| 3. Mipmap/drawable density coverage | **No coverage defect.** The ladder is complete (mdpi→xxxhdpi) and all ten PNGs are dimensionally exact (48dp and 108dp); the adaptive icon declares background, foreground **and** monochrome. The only defect is the opposite of a gap — five shadowed PNGs shipping 69,067 bytes, plus a redundant `roundIcon`. | F-183 |
| 4. Memory cost of the `res/raw` blocklist assets | **Measured.** 10,485,760 bytes (exactly 10.00 MiB) of bit array, held as two permanent Java-heap `ByteArray`s; 76.1% of the 13,778,794-byte release APK. Both entries are STORED, so the zero-heap mmap path is available today and unused. Sizing is broadly correct (ads at 46.18% fill ≈ optimal); only the adult filter over-delivers 21× on its documented target at ~1.34 MiB cost. | F-179, F-180, F-181 |

### Findings filed

| ID | Severity | Title |
|---|---|---|
| F-175 | Low | No localization infrastructure: 0 `stringResource` calls in 93 files |
| F-176 | Low | `notif_sub` is a hardcoded literal that is also never written at runtime |
| F-177 | **High** | Countdown body overflows the collapsed row at accessibility font scales; expanded view is the same `RemoteViews` |
| F-178 | Medium | Countdown digits pinned to the light accent while `setColor` resolves the dark one (~3.1:1 on a dark shade) |
| F-179 | Medium | 10 MiB of STORED assets copied to the Java heap when the mmap path is already available |
| F-180 | Low | Uncompressed packaging of the `.bin` assets is undeclared and incidental |
| F-181 | Low | Assets are 76% of the APK; adult filter over-delivers 21× on its FP target |
| F-182 | Low | Only theme is unconditional light with no `values-night` |
| F-183 | Low | Five shadowed launcher PNGs ship 69,067 bytes; `roundIcon` duplicates `icon` |

**Counts**: 1 High, 2 Medium, 6 Low. **0 Critical.** IDs used: **F-175 … F-183** (9 of the 15 reserved;
**F-184 … F-189 unused and returned to the pool**).

### Unresolvable statically

| Claim | Finding | What a device would settle |
|---|---|---|
| Whether the countdown body is actually clipped, and at which `font_scale` | F-177(a) | `adb shell settings put system font_scale 1.3` / `2.0`, inspect the collapsed row. F-177(b) needs no device. |
| The real notification-shade background colour, hence the true contrast ratio | F-178 | `adb exec-out screencap -p`, sample the pixel behind the digits |
| The Java-heap reduction and probe-throughput cost of `MappedByteBuffer` | F-179 | `dumpsys meminfo` before/after, plus a `mightContain` microbenchmark |
| Duration and extent of the light-background flash on cold start in dark mode | F-182 | `screenrecord` during a cold start with `uimode night yes` |

Everything else in this unit was settled statically with measured numbers.

### Duplicates and contradictions against findings already filed

- **No duplicates.** F-027 (blocklist memory held and never released) and F-067 (no `onTrimMemory`) are
  cited, not re-filed; F-179 is scoped to the packaging and decode path, which is a different root cause
  and a different fix. The `catch (Throwable)` widening at `BlocklistManager.kt:151` is owned by
  T019/T030 and is deliberately not claimed here, which is why F-179 is Medium rather than High.
- **One contradiction, resolved in the README's favour on one claim and against it on the other.** F-266
  records a discrepancy between "4.6 million blocklist entries" and "~800k ads + ~160k adult domains".
  Direct measurement gives **≈4,669,553 entries**, so the 4.6M figure is right to within 1.5% and the
  ~800k/~160k figure is wrong by ~3.9× and ~9.7×. F-266 should be resolved by correcting the second
  sentence. No new finding filed.
- **One correction to a code comment, referred not filed.** `BlocklistManager.kt:217` says the abused-TLD
  check is *"Cheaper than ten SHA-256 probes"*. `GrayzoneBloomFilter.baseHashes()` (`:31–36`) computes
  **one** SHA-256 digest per domain and derives both hashes from it (Kirsch–Mitzenmacher); the ten
  probes are modulo and bit-test operations, not ten digests. The ordering decision the comment defends
  is still correct — the comment's stated reason is not. `BlocklistManager.kt` is RU-06's file (T019);
  referred, not filed.
- **One agreement worth recording.** `evidence/lint.md` §E7's `HardcodedText` at
  `notification_countdown.xml:37`, assigned to T028, is filed as **F-176** — with the material addition
  that lint could not see: the view is also never written at runtime, so the string is static as well as
  untranslatable.

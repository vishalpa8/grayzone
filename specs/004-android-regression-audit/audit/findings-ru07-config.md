# Findings — RU-07 configuration variants (T027)

**Feature**: `004-android-regression-audit` | **Task**: T027 | **Review unit**: RU-07
(`app/src/main/java/com/grayzone/app/ui/`, plus `res/values/themes.xml` and `res/values/strings.xml`
where the theme and locale story lives)
**ID block**: F-160 … F-174 | **Contract**: [`../contracts/finding-record.md`](../contracts/finding-record.md)
**Register**: [`findings.md`](./findings.md) | **State-handling half**: [`findings-ru07.md`](./findings-ru07.md) (T026, F-145 … F-159)

## Scope and method

Covers the five areas T027 owns: dark mode via `ui/theme/Theme.kt`, RTL and locale handling, density
coverage across small and large screens, large-font / font-scale robustness, and large-list handling
in the monitored-app picker.

**No device or emulator is available.** Most of this area is runtime-visual, so the method was: read
every layout expression statically, derive what the measurement pass must do, and label the outcome
`needs-verification` with a concrete maintainer procedure and a severity-per-outcome statement
wherever the conclusion depends on a real viewport. Six findings (F-162, F-163, F-165, F-171, F-172,
F-174) are settled purely from resource and source files and are marked `confirmed`; F-169 and F-173
are confirmed in mechanism and need verification only for how the result looks. No screen was
skipped for lack of a device.

**Observed against**: `557a963`. `git status --porcelain` confirms **no file under `ui/` and no file
under `res/values/` has uncommitted modifications**; the only modified resource is
`res/xml/accessibility_service_config.xml`, which belongs to RU-04/RU-08.

## What the resource tree does not contain

A single directory listing settles several of the questions this task asks. `app/src/main/res`
contains exactly: `drawable`, `layout`, `mipmap-anydpi-v26`, `mipmap-{h,m,xh,xxh,xxx}dpi`, `raw`,
`values`, `xml`.

- **No `values-night/`** — so the window theme cannot vary by dark mode (F-162).
- **No `values-<locale>/`** — so the app has exactly one language (F-165).
- **No `values-land/`, no `values-sw600dp/`, no `values-w<N>dp/`, and no `dimens.xml` at all** — so
  there is one layout for every form factor and no override point for any of it (F-171).
- Density buckets exist only for launcher icons; every dimension in RU-07 is a literal `dp` or `sp`
  in Kotlin.

And a single source search settles two more: `stringResource`, `LocalDensity`, `BoxWithConstraints`,
`LocalLayoutDirection`, `fontScale`, and `windowSizeClass` return **zero matches** across
`app/src/main/java/com/grayzone/app/ui`; `WindowInsets`, `statusBarsPadding`, `navigationBarsPadding`,
`safeDrawing`, and `imePadding` return **zero matches** across the whole of `app/src/main/java`.

## Already-owned findings cited but not re-filed

| ID | Owner | Why it is cited here |
|---|---|---|
| F-076 | T021 | `selectedTab` lost on rotation **and on the dark-mode toggle**. F-174 explains why those are two different code paths with different consequences. |
| F-145 | T026 | The umbrella state-restoration finding. Every configuration change in this file also triggers it. |
| F-153 | T026 | `TimePickerDialog` lifecycle. F-162 adds that the same dialog is also light-themed in dark mode. |
| F-211 | T030 | Unguarded `startActivity`/service starts on UI paths. |

---

### F-160 — `OnboardingScreen` cannot scroll and centres its content, so at a large font scale or in landscape the permission-grant buttons — the only route to enabling protection — are clipped off both ends of the viewport  [High]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` → `OnboardingScreen()`,
  the root container at line 60
  (`Column(modifier = Modifier.fillMaxSize().background(GZBackground).padding(24.dp), verticalArrangement = Arrangement.Center)`)
  and the controls it hosts at lines 103–113, 155–165, 207–222, and 229–238
- **Issue**: The root is a `Column` with **no `verticalScroll`** — confirmed by search:
  `verticalScroll` appears in `SettingsScreen`, `ScheduleSheet`, `CustomPromptsSheet`, and
  `PerAppSettingsSheet`, and in no other file. Its content is fixed: a title, a subtitle, three
  permission cards each carrying a heading, a two-line body, and (when the permission is missing) a
  full-width button, plus either the "Continue to App" button or the "Still needed:" line. When that
  content exceeds the viewport there is no way to reach the overflow.

  `verticalArrangement = Arrangement.Center` makes it worse than a plain overflow. `Center` computes
  its starting offset as `(available − consumed) / 2`; when `consumed > available` that offset is
  **negative**, so the first child is placed above the top of the container and the last below the
  bottom. The overflow is therefore split across **both** ends rather than accumulating at the
  bottom, so the "Welcome to Grayzone" header disappears upward at the same time as the
  "Continue to App" button disappears downward. There is no scrollbar, no fade, and no affordance
  suggesting anything is missing.

  What is clipped is not decoration. `OnboardingScreen` is the gate: `MainAppContent.evaluateScreen()`
  routes here whenever accessibility or overlay is missing and the prompt policy says to prompt
  (`MainActivity.kt:189–207`), and the "Grant Accessibility" and "Grant Overlay Permission" buttons
  are the only affordances in the app that open those settings pages from this state. A user whose
  accessibility service was revoked — the exact scenario the prompt-once-per-loss policy P6 exists to
  handle — and who runs a large system font is presented with a screen from which protection cannot
  be restored.
- **Trigger condition**:
  1. Ensure the app routes to onboarding: revoke the accessibility service
     (`adb shell settings put secure enabled_accessibility_services ""`) and relaunch.
  2. Raise the font scale to the platform maximum:
     `adb shell settings put system font_scale 2.0`
  3. Observe the screen. Expect the "Welcome to Grayzone" heading to be cut off at the top and the
     third permission card and/or the bottom control to be cut off at the bottom, with no scrolling
     possible.
  4. Independently, reproduce in landscape at the default font scale:
     `adb shell settings put system accelerometer_rotation 0` then
     `adb shell settings put system user_rotation 1`
  5. Reset with `adb shell settings put system font_scale 1.0` and
     `adb shell settings put system user_rotation 0`.
- **Blast radius**:
  - *Callers*: `OnboardingScreen()` ← `MainAppContent()` (`MainActivity.kt:220`), single site.
    Reached whenever `evaluateScreen()` returns `Screen.ONBOARDING`, i.e. on first run before both
    permissions are held, and on any subsequent contiguous loss of the accessibility permission
    (`AccessibilityPromptPolicy`, prior fix P6 in `evidence/prior-fix-watchlist.md`).
  - *Process death*: no effect on the layout; the screen re-evaluates and renders identically.
  - *Config change*: **the trigger.** Rotation and a font-scale change both recreate the Activity
    (no `android:configChanges`, `AndroidManifest.xml:55–58`) and re-measure at the new constraints.
  - *Foreground/background*: the `ON_RESUME` observer at `:41–58` calls `onContinue()` as soon as
    both permissions are held (`:53`), so a user who *can* reach the buttons is not trapped. The trap
    is only for users who cannot reach them.
  - *Cross-process*: none.
  - *Persistence*: none from this screen directly; `:50–51` writes
    `ACCESSIBILITY_PROMPTED_FOR_LOSS = false` on resume once accessibility is observed enabled.
  - *Release-only*: none.
- **Fix** (minimal): make the root scrollable and stop centring content that can overflow:

  ```kotlin
  Column(
      modifier = Modifier
          .fillMaxSize()
          .background(GZBackground)
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(0.dp)   // was Arrangement.Center
  ) {
  ```

  `verticalScroll` must be applied **before** `padding` so the padding scrolls with the content.
  Dropping `Arrangement.Center` costs the vertical centring on tall screens; if that is wanted, keep
  it by giving the scroll container `Modifier.heightIn(min = …)` with a spacer strategy rather than
  by centring a possibly-overflowing column. `SettingsScreen.kt:65–71` is the in-tree model for the
  correct shape.
- **Rewrite justification**: not applicable — one modifier added, one argument removed.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/OnboardingReachabilityTest.kt`, composing
  `OnboardingScreen` inside a `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f))`
  within a constrained `Box` sized to a 360×640 dp viewport, and asserting
  `onNodeWithText("Grant Accessibility").assertIsDisplayed()`. Fails before (the node exists but is
  not displayed), passes after (the scroll container brings it into view via
  `performScrollTo()`). **Cannot be executed here**: no device, and
  `androidx.compose.ui:ui-test-junit4` is not a declared dependency —
  `app/build.gradle.kts:156–157` has only `ui-tooling` and `ui-test-manifest`. Overriding
  `LocalDensity` is what makes this a deterministic layout assertion rather than a device-dependent
  screenshot.
- **Existing test conflict**: none. No test references any RU-07 file.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: the root has no
  scroll container, uses `Arrangement.Center`, and hosts fixed content. Not confirmed: the exact
  viewport and font scale at which the buttons leave the screen, which depends on the device.
  - *Missing*: a measured device run at `font_scale 2.0` on a common phone viewport
    (360×640 dp is the conservative case; 411×866 dp is a typical modern phone).
  - *Procedure*: steps 1–5 above, on at least one 5-inch-class device and one modern phone, in both
    orientations. Record which controls are visible in each combination and whether the screen can
    be scrolled at all (it cannot).
  - *Severity effect*: if a grant button is unreachable in **default portrait** at the maximum system
    font scale → **Critical**: a large-font user cannot set the app up at all, and an existing user
    whose accessibility permission is revoked cannot restore protection, with no error and no
    scroll hint to suggest what is wrong. If unreachable only in landscape or only at scales the
    system font slider cannot produce → stays **High** (user-visible and recoverable by rotating back
    or lowering the font scale, but the user has to guess that). If everything fits at 2.0 on the
    smallest supported viewport → **Medium**, since `Arrangement.Center`'s two-sided clipping remains
    a latent trap for the next added card.
- **Build scope**: everyday-build
- **Constitution**: I (a control the user cannot reach is a correctness defect, not a cosmetic one —
  and per FR-006's Grayzone calibration this is not cosmetic because the unreachable control is the
  one that restores protection)
- **Affected surfaces**: `AppsScreen`, `LimitsScreen`, and `VpnScreen` have the related but distinct
  non-scrolling-header problem — F-161. `SettingsScreen` and the three sheets are correct.
  `HomeScreen` is correct by construction (its root is a `LazyColumn`).
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

---

### F-161 — Three screens put a tall fixed header in a non-scrolling `Column` above their list, so at a large font scale or in landscape the header consumes the viewport and the list measures to zero height  [High]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` → `AppsScreen()` (root
  `Column` at line 74; header `Column` at 75–181; `LazyColumn` at 198),
  `app/src/main/java/com/grayzone/app/ui/LimitsScreen.kt` → `LimitsScreen()` (root at 43; header at
  44–57; `LazyColumn` at 91), and
  `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt` → `VpnScreen()` (root at 120; header at
  122–168; the weighted feed at 376)
- **Issue**: All three screens are `Column(Modifier.fillMaxSize())` with an unscrollable header
  followed by the scrollable content. A `Column` measures its non-weighted children first and gives
  each the height remaining; when the header consumes everything, the list is measured with
  `maxHeight = 0` and renders nothing. The header text is in `sp` and grows with the font scale while
  the paddings are fixed `dp`, so the header's share of the viewport rises monotonically with font
  scale, and the viewport itself shrinks by roughly half in landscape.

  `AppsScreen`'s header is the worst of the three because it is the largest and because what it hides
  is the app's central control. It contains: a `top = 56.dp` pad, a 28 sp title, a 14 sp subtitle, a
  16 dp spacer, the master-enable `GZCard` (18 dp internal padding, a 24 dp icon, a 15 sp line and an
  11 sp line, and a `Switch`), and the search row (12 dp vertical padding around a 15 sp field) —
  before the `LazyColumn` of monitored apps gets a single pixel. At `font_scale 2.0` the text
  contributions roughly double while the ~120 dp of fixed padding does not shrink. In landscape on a
  typical phone the content viewport is on the order of 360 dp tall before the `Scaffold`'s
  navigation bar is subtracted (`MainActivity.kt:269–301`), and the bar itself grows with the font
  scale too (F-167).

  If the list reaches zero height the user sees a header with a search box that filters nothing
  visible, and **cannot add or remove a monitored app**. That is the screen where protection is
  configured, so it is not a cosmetic clip.

  `VpnScreen` fails differently and slightly better: its feed is `weight(1f)` so it absorbs the
  squeeze first and the toggle stays near the top. But the cards below the toggle — the "24-hour
  lock" explainer (`:238–258`), the `statusMessage` line (`:260–267`), and the "Locked on — N left"
  card (`:269–289`) — are unweighted siblings, so in the `vpnRunning && holdActive` state the stack
  is at its tallest and the feed collapses to nothing while the explanatory text is what remains.
- **Trigger condition**:
  1. Monitor several apps so the list is non-empty, and go to the Apps tab.
  2. `adb shell settings put system font_scale 2.0` — observe how much of the list survives.
  3. `adb shell settings put system accelerometer_rotation 0` then
     `adb shell settings put system user_rotation 1` — observe again in landscape at 2.0, then at
     1.0.
  4. Repeat on the Limits and VPN tabs. For VPN, do it while the 24-hour hold is active so all three
     unweighted cards are present.
  5. Reset: `adb shell settings put system font_scale 1.0`,
     `adb shell settings put system user_rotation 0`.
- **Blast radius**:
  - *Callers*: each screen has exactly one call site in `MainScreen()` —
    `AppsScreen()` at `MainActivity.kt:307`, `LimitsScreen()` at `:309`, `VpnScreen()` at `:308`.
    All three are rendered inside `Scaffold`'s content `Box(Modifier.padding(innerPadding))`
    (`:304`), so the available height is already reduced by the `NavigationBar`.
  - *Process death*: none — layout is recomputed identically.
  - *Config change*: **the trigger**, on three axes: orientation, font scale, and display size
    (`adb shell wm density`). Each recreates the Activity and re-measures.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): two options, and the choice differs per screen.
  - For `AppsScreen` and `LimitsScreen`, move the header **into** the existing `LazyColumn` as a
    leading `item { }`. That makes the whole screen scrollable, costs nothing at normal font scales,
    and is a smaller change than it looks because both headers are already self-contained `Column`s.
    The search field scrolling away is a deliberate trade-off; if a sticky search box is wanted, use
    `stickyHeader` for the search row only and put the title and master switch in a plain `item`.
  - For `VpnScreen`, wrap the fixed cards between the header and the feed in their own
    `verticalScroll` container, or give the feed a `heightIn(min = 120.dp)` so it cannot be squeezed
    to nothing while the explanatory cards keep their full height.

  In all three cases, add `Modifier.heightIn(min = …)` nowhere else — the point is that the list must
  be reachable, not that the header must shrink.
- **Rewrite justification**: not applicable. Re-parenting an existing header `Column` into an
  existing `LazyColumn` as an `item` is a mechanical move, not a rewrite; no measurement logic is
  introduced.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/AppsListReachabilityTest.kt`, composing `AppsScreen`
  inside a `Box(Modifier.size(360.dp, 360.dp))` with
  `LocalDensity provides Density(density, fontScale = 2f)` and a seeded app list, asserting that the
  first list row `assertIsDisplayed()` after `performScrollTo()`. Fails before (the row is never
  composed because the list has zero height), passes after. **Cannot be executed here**: no device,
  and `ui-test-junit4` is not declared. A constrained `Box` rather than a device viewport is what
  makes this deterministic and CI-runnable once the dependency exists.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: none of the
  three roots has a scroll container (search over `app/src` for `verticalScroll` returns only
  `SettingsScreen`, `ScheduleSheet`, `CustomPromptsSheet`, `PerAppSettingsSheet`); the headers are
  unweighted `Column` children; `Column` allocates remaining height to later children and coerces it
  at zero. Not confirmed: the font scale and orientation at which remaining height actually reaches
  zero on a real device.
  - *Missing*: measured header height against viewport height at
    `font_scale ∈ {1.0, 1.3, 1.5, 2.0}` × `{portrait, landscape}` for each of the three screens.
  - *Procedure*: steps 1–5 above. Additionally test a small display-size configuration:
    `adb shell wm size 720x1280` and `adb shell wm density 320`, resetting with
    `adb shell wm size reset` and `adb shell wm density reset`. Capture whether any list rows are
    visible in each cell.
  - *Severity effect*: list height reaching zero in **portrait** at a font scale the system slider
    can produce → **High** as filed, and arguably Critical for `AppsScreen` on the same reasoning as
    F-160, since the user cannot then configure what is protected. Reaching zero only in landscape →
    **Medium** (recoverable by rotating, and the user can see that rotating helps). Never reaching
    zero but losing more than half the list → **Low**.
- **Build scope**: everyday-build
- **Constitution**: II (`HomeScreen` solves exactly this problem correctly by making its root a
  `LazyColumn` and putting its header in an `item`; three sibling screens do not, so the same layout
  problem has two answers in one package)
- **Affected surfaces**: F-160 is the same class in `OnboardingScreen` with a different mechanism
  (`Arrangement.Center` rather than a header). F-167 (navigation-bar labels) and F-168 (fixed `dp`
  heights) compound this by consuming more of the same viewport.
- **Observed against**: `557a963`
- **Disposition**: `fix-pending-device-verification`

---

### F-162 — The application theme is hardcoded `Theme.Material.Light` with no `values-night`, so the launch window is white in dark mode and both time pickers render light-on-dark  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/res/values/themes.xml` line 3 —
  `<style name="Theme.Grayzone" parent="android:Theme.Material.Light.NoActionBar" />` — applied at
  `AndroidManifest.xml:53` (`<application android:theme="@style/Theme.Grayzone">`) and again at
  `:58` (`<activity android:name=".MainActivity" android:theme="@style/Theme.Grayzone">`); consumed
  by `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` → `AddRuleDialog()` at lines 254 and
  271
- **Issue**: The app has a complete Compose dark theme — `DarkGzPalette` (`Color.kt:91–126`), a
  `darkColorScheme` mapping (`Theme.kt:47–70`), a three-way user preference, and status/navigation
  bar recolouring in a `SideEffect` (`Theme.kt:97–106`). None of that reaches anything the platform
  draws, because the **window** theme is a single unconditional line pointing at
  `android:Theme.Material.Light.NoActionBar`, and there is no `values-night/themes.xml` to override
  it. Two visible consequences:

  1. **The starting window is white on every cold launch.** `android:windowBackground` for
     `Theme.Material.Light` is a light colour, and the starting window is drawn by the system before
     any application code runs — well before `GrayzoneTheme`'s `SideEffect` can set
     `window.statusBarColor` and long before the first Compose frame. A dark-mode user therefore gets
     a full-screen white flash on every launch of a self-control app they are likely opening at
     night.
  2. **Both `TimePickerDialog`s are light-themed.** `AddRuleDialog` constructs them with
     `TimePickerDialog(context, …)` and no theme resource id (`ScheduleSheet.kt:254`, `:271`), so
     they inherit the Activity theme — which is `Theme.Grayzone`, which is Light, unconditionally.
     In dark mode the user taps "Start Time" inside a dark sheet and gets a white platform dialog.
     This is not a flash; it is the steady state, and it is the only place in the app where the two
     theming systems are visible side by side.

  Note that the *Compose* half is correct: `MaterialTheme`'s `colorScheme` and the `GZ*` tokens both
  resolve to the dark palette, so everything Compose draws is right (subject to F-164's first-frame
  problem). This finding is specifically about the platform-drawn surfaces the Compose theme cannot
  reach.
- **Trigger condition**:
  1. Settings → Appearance → Dark. Force-stop the app: `adb shell am force-stop com.grayzone.app`.
  2. Relaunch and watch the first ~300 ms. Expect a white window before the dark UI paints.
     `adb shell am start -W -n com.grayzone.app/.MainActivity` gives the timing; a screen recording
     (`adb shell screenrecord`) captures the flash.
  3. With Dark still selected, go to Settings → Manage Schedule Focus Modes → + Add Custom Schedule →
     Start Time. Expect a light-coloured platform time picker over the dark sheet.
  4. Repeat step 2 with system dark mode instead of the in-app preference
     (`adb shell cmd uimode night yes`) to confirm the launch window is white in both cases — it is,
     because no `values-night` exists.
- **Blast radius**:
  - *Callers*: `Theme.Grayzone` is referenced twice in `AndroidManifest.xml` (`:53`, `:58`) and is
    the only style in `res/values/themes.xml`. `TimePickerDialog` is constructed at exactly two sites
    (`ScheduleSheet.kt:254,271`) — direct search over `app/src` returns no others.
  - *Process death*: **this is when it is visible.** The starting window only appears on a cold
    start, so the flash is a per-process-launch cost, and it recurs after every OEM kill of the
    process.
  - *Config change*: an Activity recreation for a system dark-mode switch does not redraw the
    starting window, so the flash is specific to cold launch. The time-picker problem is unaffected
    by configuration and is always present in dark mode.
  - *Foreground/background*: warm resumes do not show the starting window.
  - *Cross-process*: none. `OverlayService` builds its own chrome from
    `isNightMode()` (`OverlayService.kt:1151`) with its own colour constants and does not use this
    theme.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): give the theme a night variant and an explicit window background, so the
  starting window and platform dialogs follow the system.

  `res/values/themes.xml`:
  ```xml
  <style name="Theme.Grayzone" parent="android:Theme.Material.Light.NoActionBar">
      <item name="android:windowBackground">@color/gz_background</item>
  </style>
  ```
  new `res/values-night/themes.xml`:
  ```xml
  <style name="Theme.Grayzone" parent="android:Theme.Material.NoActionBar">
      <item name="android:windowBackground">@color/gz_background</item>
  </style>
  ```
  with `gz_background` defined in `res/values/colors.xml` as `#FFF7F6F3` (matching
  `LightGzPalette.background`) and in `res/values-night/colors.xml` as `#FF121416` (matching
  `DarkGzPalette.background`). **Known limitation, and it must be stated rather than hidden**: a
  resource qualifier follows the *system* dark mode, so this fixes the `ThemeMode.SYSTEM` case and
  the two forced cases only insofar as they agree with the system. Making the launch window follow
  the in-app override as well requires calling
  `AppCompatDelegate.setDefaultNightMode` / `Activity.setTheme` before `setContentView`, which is a
  larger change; the minimal fix above removes the flash for the default (`SYSTEM`) preference, which
  is where most users are. The time-picker half is fully fixed by the night variant, since the picker
  is created after the theme is resolved.
- **Rewrite justification**: not applicable — one attribute, one new qualified resource directory.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/theme/NightResourceParityTest.kt`, a resource-shape test
  asserting that every colour name declared in `res/values/colors.xml` also exists in
  `res/values-night/colors.xml`, read from the project's own resource files. Fails before (the night
  directory does not exist), passes after. This is deliberately a structural test rather than a
  visual one, because the visual assertion needs a device. A screenshot test for the time picker is
  `instrumented` and **cannot be executed here**.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). Established entirely from files:
  `res/values/themes.xml:3` is the whole theme; a directory listing of `res` shows no `values-night`;
  `AndroidManifest.xml:53,58` apply the theme; `ScheduleSheet.kt:254,271` pass `context` with no
  theme override. That the starting window uses `android:windowBackground` from the activity theme
  is documented platform behaviour. The only part a device would add is the *duration* of the flash.
- **Build scope**: everyday-build
- **Constitution**: II (the app has two theming systems — a Compose palette and a platform theme —
  and only one of them knows about dark mode)
- **Affected surfaces**: the `Toast` in `shareApk` (`SharedComponents.kt:182–187`) is system-styled
  and unaffected. `res/layout/notification_countdown.xml` is a notification layout and follows the
  notification theme, not this one — it belongs to T028/RU-09. F-153 covers the same two time pickers
  from the lifecycle side; a maintainer replacing them with the Material3 `TimePicker` composable
  would resolve both findings at once, which is worth noting for the punch list.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-163 — `resolveDarkTheme`'s unused `context` parameter is not dead dynamic-color logic; it is a leftover that forces a `LocalContext` read for an ignored argument and blocks a JVM unit test  [Low]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/theme/Theme.kt` → `resolveDarkTheme()` (line
  19, parameter at column 22), and its two call sites: `isNightMode()` (line 32) and
  `GrayzoneTheme()` (line 41)
- **Issue**: This is the audit's one confirmed compiler warning —
  `w: app/src/main/java/com/grayzone/app/ui/theme/Theme.kt:19:22 Parameter 'context' is never used`
  (`evidence/warnings.md`). **T027 was asked to determine whether it indicates dead dynamic-color
  logic. It does not.** The evidence:

  - A search across `app/src` for `dynamicDarkColorScheme`, `dynamicLightColorScheme`, `dynamicColor`,
    and `isDynamic` returns **zero matches**. There is no removed, disabled, or stubbed Material You
    path anywhere.
  - `GrayzoneTheme` builds its schemes exclusively from the hand-authored `LightGzPalette` /
    `DarkGzPalette` (`Theme.kt:42`, `47–95`), which are literal `Color(0x…)` tables in `Color.kt`.
    A dynamic-color path would need `Build.VERSION.SDK_INT >= 31` branching and a `Context`; neither
    exists, and `minSdk` is 29, so such a branch would be visible if it had ever been written.
  - The function body is a three-branch `when` on `themeMode` and `systemDark` and is a pure function
    of its last two parameters.

  The actual origin is visible in the file's shape: `resolveDarkTheme` was factored **out of**
  `isNightMode`, which genuinely needs a `Context` (it reads preferences at `:27` and
  `resources.configuration` at `:30`). The extraction kept the enclosing function's first parameter
  and never used it. The concrete costs are small but real: `GrayzoneTheme` calls
  `LocalContext.current` at `:41` **solely** to supply an argument that is discarded, and the
  function's signature makes it look context-dependent — which is precisely the misreading that
  makes the genuinely missing platform-theme support (F-162) easy to assume is handled. Removing the
  parameter also makes the function a pure `(String, Boolean) -> Boolean` that a JVM unit test can
  call directly; today it cannot be tested without constructing a `Context`, and
  `testOptions { unitTests.isReturnDefaultValues = true }` is absent from `app/build.gradle.kts`
  (recorded in `findings.md` and propagated to T025).
- **Trigger condition**:
  1. `./gradlew compileDebugKotlin "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11" --rerun-tasks --console=plain`
  2. Observe the single main-source warning quoted above. `--rerun-tasks` is required so the warning
     is not hidden behind an `UP-TO-DATE` task (research R4).
  3. Confirm the absence of any dynamic-color path by searching the source tree; expect zero matches.
- **Blast radius**:
  - *Callers*: exactly two, both in the same file — `isNightMode()` at `Theme.kt:32` and
    `GrayzoneTheme()` at `:41`. `isNightMode()` in turn has one external caller,
    `OverlayService.overlayChrome()` (`OverlayService.kt:1151`), and `GrayzoneTheme()` has one,
    `MainActivity.kt:73` — matching `evidence/reference-index.md:189`. Removing the parameter
    therefore touches three lines total.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none, but worth noting that `isNightMode` is reached from `OverlayService`, so
    the edit is not confined to the UI package and should be compiled against both call paths.
  - *Persistence*: none — the function performs no I/O. `isNightMode`, its caller, does.
  - *Release-only*: none.
- **Fix** (minimal): delete the parameter and the two arguments.

  ```kotlin
  fun resolveDarkTheme(themeMode: String, systemDark: Boolean): Boolean =
      when (themeMode) {
          ThemeMode.LIGHT -> false
          ThemeMode.DARK  -> true
          else            -> systemDark
      }
  ```

  Update `Theme.kt:32` to `resolveDarkTheme(mode, systemDark)` and `Theme.kt:41` to
  `resolveDarkTheme(themeMode, systemDark)`; the `LocalContext.current` read at `:41` and the
  `import android.content.Context` at `:4` then become removable — check `:26`'s `isNightMode`
  signature still needs the `Context` import (it does), so only the `LocalContext` read goes.
  Constitution Principle I requires **removal**, and the Code Quality Standards explicitly forbid
  resolving this with `@Suppress`.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/theme/ResolveDarkThemeTest.kt`, asserting
  `resolveDarkTheme(ThemeMode.LIGHT, systemDark = true) == false`,
  `resolveDarkTheme(ThemeMode.DARK, systemDark = false) == true`,
  `resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true) == true`, and — the case that matters for
  robustness given F-149 — `resolveDarkTheme("garbage", systemDark = false) == false`, i.e. an
  unrecognised persisted value falls back to the system rather than throwing. **This test is
  impossible before the fix**, because the current signature requires a `Context` the JVM source set
  cannot construct. That is the cleanest argument for the removal: it converts an untestable function
  into a tested one.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). The warning is on record in
  `evidence/warnings.md` from a `--rerun-tasks` compile; the absence of any dynamic-color code is
  established by direct search; the two call sites are established by direct search and corroborated
  by `evidence/reference-index.md:189`.
- **Build scope**: everyday-build
- **Constitution**: I (unused parameter; removal required, `@Suppress` forbidden)
- **Affected surfaces**: none. `evidence/warnings.md` routes this to T059 as a dead-code candidate;
  T027 owns it because the question asked was a dark-mode question. The **answer for T059** is that
  it is a stale signature, not dead functionality, so the safe action is parameter removal and there
  is no dynamic-color feature to restore or delete.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-164 — The colour tokens read a process-global palette that the theme publishes only in a post-composition `SideEffect`, so the first composed frame always uses the light palette and every reader recomposes twice per theme resolution  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/theme/Color.kt` → `GzThemeState` (lines
  131–134) and the `GZ*` accessors that read it (lines 138–209), together with
  `app/src/main/java/com/grayzone/app/ui/theme/Theme.kt` → `GrayzoneTheme()`'s
  `SideEffect { GzThemeState.palette = palette }` (lines 43–45)
- **Issue**: Grayzone's colours are **not** a `CompositionLocal`. `GzThemeState` is a top-level
  `object` holding `var palette by mutableStateOf(LightGzPalette)`, and every `GZ*` accessor reads
  that global. `GrayzoneTheme` computes the correct palette as an ordinary value at `Theme.kt:42`
  but publishes it to the global in a `SideEffect`, which by definition runs **after** the
  composition has been applied. Two consequences follow from that ordering.

  1. **The first composed frame uses whatever the global held before** — on a cold start, the
     initialiser `LightGzPalette`. In dark mode the tree therefore composes once with light colours,
     the `SideEffect` writes the dark palette, and a second composition produces the correct ones.
     `MainActivity.kt:74` makes this maximally visible: the root `Surface(color = GZBackground)` is
     one of those readers, so the first frame's full-screen background is the light `#F7F6F3`. This
     lands immediately after F-162's white starting window, so the cold-launch sequence in dark mode
     is white → light → dark.
  2. **Every reader recomposes twice per theme resolution.** Writing snapshot state from a
     `SideEffect` invalidates every reader after the frame has already been composed, scheduling a
     second pass. Because the `GZ*` tokens are read in essentially every composable in the app, that
     is a whole-tree double composition on first launch and again on each in-app theme change.

  There is also a correctness hazard independent of both: `MaterialTheme.colorScheme` (set correctly
  and immediately at `Theme.kt:107–111`) and the `GZ*` tokens are **two independent sources of truth
  for the same colours**, and they disagree for one frame — a Material component using default
  colours is right on frame 1 while its hand-coloured neighbour is wrong. And because the palette is
  a process global that is never reset, any composable rendered outside `GrayzoneTheme` silently
  picks up whatever the last theme pass left, rather than failing loudly the way a missing
  `CompositionLocal` would.

  The doc comment at `Color.kt:128–130` — *"Snapshot-backed active palette so existing `GZ*` reads
  recompose when theme changes"* — describes the intent accurately, and the snapshot backing does
  work. The defect is publishing through a `SideEffect` instead of a `CompositionLocalProvider`.
- **Trigger condition**:
  1. Settings → Appearance → Dark. `adb shell am force-stop com.grayzone.app`.
  2. `adb shell screenrecord --time-limit 5 /sdcard/launch.mp4`, relaunch, then
     `adb pull /sdcard/launch.mp4`. Step through the first frames looking for a light-background
     frame between the white starting window and the dark UI.
  3. For the double-composition half, add a temporary `SideEffect { Log.w("gz", "compose") }` to any
     screen and count entries on one cold launch — expect two, not one. `GrayzoneLogger.d()`/`.i()`
     cannot be used for this: they are no-ops in every variant (F-064).
  4. Toggle Light → Dark in-app while watching the same counter; expect two passes per toggle.
- **Blast radius**:
  - *Callers*: `GzThemeState.palette` is written at exactly one site, `Theme.kt:44`, and read by the
    accessors in `Color.kt:138–209`, which are in turn read by every file in `ui/` plus
    `SharedComponents.kt` and `MainActivity.kt`. `GZCard` alone is composed from eight files
    (`evidence/reference-index.md`), so the reader set is effectively the whole UI.
  - *Process death*: **this is when consequence 1 is visible**, because the global resets to
    `LightGzPalette` only on a fresh process.
  - *Config change*: an Activity recreation re-composes from scratch, but the process-global palette
    survives — so after the first launch, rotations do **not** show the light frame. Only a cold
    start exhibits it, which matters when reproducing.
  - *Foreground/background*: none.
  - *Cross-process*: none. `GzThemeState` lives in the default process; `OverlayService` (same
    process) does not read it, using `isNightMode()` and its own colour constants instead, so the two
    never disagree — but it does mean the app has a **third** colour source.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): publish the palette through a `CompositionLocal` so it is correct during the
  first composition instead of after it.

  `Color.kt`:
  ```kotlin
  val LocalGzPalette = staticCompositionLocalOf { LightGzPalette }
  val GZPrimary: Color @Composable @ReadOnlyComposable get() = LocalGzPalette.current.primary
  // …the remaining accessors change identically
  ```
  `Theme.kt`, replacing the `SideEffect` at `:43–45`:
  ```kotlin
  CompositionLocalProvider(LocalGzPalette provides palette) {
      MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
  ```
  The accessor bodies are a mechanical substitution and **no call site changes**, because the public
  `GZ*` names and types are preserved — which is what keeps this a minimal patch rather than a
  rewrite. `staticCompositionLocalOf` is correct here (the value changes rarely; a full subtree
  invalidation on change is what is wanted). `MainActivity.kt:74`'s `Surface(color = GZBackground)`
  sits **inside** `GrayzoneTheme`, so it picks the provider up with no change.
- **Rewrite justification**: the fix replaces the publication mechanism rather than patching it, so a
  justification is owed. Patching is genuinely insufficient: the only way to keep the global and fix
  frame 1 is to assign it during composition, and writing snapshot state during composition is
  prohibited by Compose (undefined recomposition behaviour). Seeding the global from preferences at
  `Application.onCreate` would fix the flash but not the two-sources-of-truth problem, and would add
  a preference read to the startup critical path that F-149 shows is already unguarded. The
  `CompositionLocal` is the smallest change that is also correct, and it deletes code rather than
  adding it.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/theme/PaletteSelectionTest.kt`, asserting that the pure
  selection `if (resolveDarkTheme(mode, systemDark)) DarkGzPalette else LightGzPalette` returns
  `DarkGzPalette` for `(ThemeMode.DARK, systemDark = false)` and that
  `DarkGzPalette.background != LightGzPalette.background`, so a regression that publishes the wrong
  table is caught without a device. Depends on F-163's signature change. The frame-ordering assertion
  itself is `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/theme/FirstFrameThemeTest.kt` asserting the root
  `Surface` background equals `DarkGzPalette.background` on the **first** composition under
  `GrayzoneTheme(themeMode = ThemeMode.DARK)` — and **cannot be executed here** (no device;
  `ui-test-junit4` not declared).
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: the tokens read
  a global (`Color.kt:131–134`, `138–209`), the global is written only in a `SideEffect`
  (`Theme.kt:43–45`), `SideEffect` runs after composition is applied, and the initial value is
  `LightGzPalette`. Not confirmed: whether the resulting single light frame is perceptible.
  - *Missing*: a frame-by-frame capture of a cold launch in dark mode.
  - *Procedure*: steps 1–2 above; step through the recording and count light-background frames. Also
    run with Developer options → "Profile GPU rendering" to see whether the double composition shows
    as a jank frame on first launch.
  - *Severity effect*: a visible light flash on every dark-mode cold start → stays **Medium** (with
    F-162 it becomes the app's most obvious visual defect, and the two should be fixed together). No
    perceptible flash → **Low**, on the strength of the double composition and the
    two-sources-of-truth hazard alone.
- **Build scope**: everyday-build
- **Constitution**: II (three colour sources — `MaterialTheme.colorScheme`, `GzThemeState`, and
  `OverlayService`'s overlay chrome constants — describe the same palette, and the first two can
  disagree)
- **Affected surfaces**: `OverlayService`'s chrome is the third source, resolved per drawable build
  from `isNightMode()`, so it is internally consistent at the moment an overlay is created; F-174
  covers what happens to an overlay already on screen when the theme changes. F-162 shares the
  cold-launch symptom and should be fixed in the same pass.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-165 — Every user-facing string is a hardcoded Kotlin literal; `strings.xml` holds two entries and neither is UI text, yet the manifest advertises RTL support  [Low]

- **Review unit**: RU-07
- **Location**: `app/src/main/res/values/strings.xml` (the whole file — two entries), every file in
  `app/src/main/java/com/grayzone/app/ui/`, and `AndroidManifest.xml`'s
  `android:supportsRtl="true"`
- **Issue**: `stringResource` returns **zero matches** across `app/src/main/java/com/grayzone/app/ui`.
  `strings.xml` contains `app_name` and `accessibility_service_description` — the latter required by
  the accessibility-service metadata contract, not shown in the app UI. Every other piece of text is
  a string literal in Kotlin: screen titles, permission explanations, the "Settings are locked while
  an app is active, paused, or in timeout." banner, the 24-hour-lock warnings, the bloom-filter
  false-positive disclosure, and the "How it works" steps. There is no `values-<locale>/` directory.
  The app therefore cannot be translated at all, and `android:supportsRtl="true"` advertises a
  capability that has never been exercised: with a single English resource set, the only way an RTL
  layout direction is reached is a forced-RTL developer setting or an RTL system locale with English
  text — exactly the untested configuration F-166 describes.

  Filed as Low because there is no current user-visible defect: the app ships in one language and
  works in it. It is recorded because (a) it is the honest verdict on T027's "locale handling"
  coverage area rather than a silent scope drop, (b) it makes F-166 unfixable through resources
  (bidi markup needs the string to be a resource first), and (c) several strings are composed by
  concatenation in ways that cannot survive translation:
  `"${data.monitoredCount} app${if (data.monitoredCount != 1) "s" else ""} monitored"`
  (`HomeScreen.kt`), the same pattern for "will trigger friction" (`AppsScreen.kt`),
  `"Turn on ${SetupRequirements.label(requirement)}"` (`HomeScreen.kt`), and `"${h}h ${m}m"`
  (`SettingsScreen.kt`, duplicated in `PerAppSettingsSheet.kt`). English pluralisation by string
  concatenation has no equivalent in most languages and would need `plurals` resources, so the cost
  grows with every string added.
- **Trigger condition**:
  1. `adb shell settings put system system_locales ar-EG` (or Settings → System → Languages, add
     Arabic and move it to the top), then relaunch.
  2. Observe that every string is still English. There is no resource-fallback problem — there is
     simply one resource set.
  3. Confirm the resource tree: `res` contains only `values`, with no locale qualifier.
- **Blast radius**:
  - *Callers*: all files in `ui/` plus `SharedComponents.kt` and `MainActivity.kt`.
  - *Process death*: none.
  - *Config change*: none.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none. (All seven recorded explicitly rather than omitted: this is a build-time
    resource-organisation property with no runtime state, and the contract requires the sub-fields
    regardless.)
- **Fix** (minimal): none is proposed as a defect fix — a full string extraction here would be scope
  inflation. The actionable minimum is narrower: extract only the strings F-166 needs to be
  bidi-safe, and add `plurals` for the concatenated-plural sites above so the pattern does not
  spread. Whether Grayzone is ever localised is a product decision and should be recorded as such
  rather than as an audit item to action.
- **Rewrite justification**: not applicable — no change is proposed beyond the narrow subset.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/PluralFormattingTest.kt`, asserting that an extracted
  `formatMonitoredCount(n)` returns `"1 app monitored"` for 1 and `"0 apps monitored"` for 0 — the
  case the current inline `if (count != 1) "s" else ""` gets right by accident and that a `plurals`
  resource would encode explicitly. Fails before (no such function; the logic is inline in two files
  with slightly different suffixes), passes after.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). `strings.xml` was read in full; `stringResource`
  has zero occurrences in `ui/`; the `res` directory listing contains no locale qualifier; the
  manifest declares `supportsRtl="true"`.
- **Build scope**: everyday-build
- **Constitution**: I (hardcoded UI strings are a maintainability defect). Lint's `HardcodedText`
  check flags the XML instance of this in `res/layout/notification_countdown.xml`, recorded in
  `evidence/lint.md` and owned by T028 — the Kotlin instances are invisible to that check, which is
  why they need recording here.
- **Affected surfaces**: `res/layout/notification_countdown.xml` is the one XML case and belongs to
  T028; do not double-count it. F-166 depends on this.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-166 — RTL mirroring is structurally sound but never exercised: time ranges, status strings, and duration labels interleave English words, Latin digits, and emoji with no bidi isolation, and four `String.format` calls take the ambient locale  [Low]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` → `ScheduleSheet()`'s rule
  summary and `AddRuleDialog()`'s two time buttons (the four `String.format("%02d:%02d", …)` calls);
  `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` → `PremiumAppListItem()` status subtitles;
  `app/src/main/java/com/grayzone/app/ui/LimitsScreen.kt` → the equivalent status strings;
  `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` and
  `app/src/main/java/com/grayzone/app/ui/PerAppSettingsSheet.kt` → the `"${h}h ${m}m"` labels
- **Issue**: The **good news first, because it constrains the finding**: the layout side of RTL is
  genuinely correct. Every padding and alignment in RU-07 uses direction-aware APIs — `start`/`end`
  rather than `left`/`right`, `Alignment.End`, `RoundedCornerShape(topStart/topEnd)` on the three
  sheets — and the two mirrorable icons are correctly taken from `Icons.AutoMirrored`
  (`MainActivity.kt` for the list icon, `VpnScreen.kt` for the back arrow). Compose mirrors the tree
  from `LocalLayoutDirection`, which follows the locale, so a forced-RTL run lays out correctly. I
  went looking for `left`/`right` padding and non-mirrored arrows and did not find them.

  What is not handled is the **text**. Several strings splice English words, Latin digits, and emoji
  into one run with no bidi isolation:

  - The rule summary builds `"$startStr to $endStr"` from two `String.format("%02d:%02d", …)`
    results. Two problems: `String.format` without an explicit `Locale` uses the default locale, so
    in a locale with non-Latin digit shapes the times render in those digits while the surrounding
    literals stay Latin; and the English infix `to` between two neutral-direction numeric runs has no
    `BidiFormatter` isolation, so in an RTL paragraph the two times can visually swap and the range
    reads backwards. A schedule shown as "22:00 to 06:00" reading as "06:00 to 22:00" is a wrong
    statement about when the user is locked out — not a cosmetic issue.
  - The Apps and Limits status subtitles prefix text with emoji: `"🔒 Locked (N m)"`,
    `"⏳ Session Active (N m left)"`, `"⏸ Paused (Nm)"`. Emoji are direction-neutral, so their
    placement relative to the parenthesised digits is resolved by the paragraph direction, and
    parentheses are mirrored characters. The same string can render with the lock on either side and
    the parentheses inverted.
  - `"${h}h ${m}m"` glues Latin unit letters to digits, unisolated, in two files.

  Because none of these is a resource (F-165), none can be fixed with `xliff:g` or a translated
  template; the fix has to be code-side.
- **Trigger condition**:
  1. Developer options → **Force RTL layout direction** (or
     `adb shell settings put global debug.force_rtl 1` where honoured), then relaunch.
  2. Settings → Manage Schedule Focus Modes. Add a rule from 22:00 to 06:00 and read the summary
     line; check whether the times appear in the intended order.
  3. Apps tab with a locked app: read the `🔒 Locked (N m)` subtitle; check emoji position and
     parenthesis orientation.
  4. For the digit-shaping half, switch the system locale to Arabic (Egypt) or Persian and repeat.
     The `en-XA`/`ar-XB` pseudolocales are the cleaner tool but require `isPseudoLocalesEnabled =
     true` on a build type, which `app/build.gradle.kts` does not set — and the uncommitted
     `debug { isDebuggable = false }` block makes the debug variant a poor host for it anyway.
  5. Reset: `adb shell settings put global debug.force_rtl 0`.
- **Blast radius**:
  - *Callers*: the five files listed. `String.format` without a `Locale` argument appears four times
    in `ScheduleSheet.kt`; direct search over `app/src` returns no other UI occurrence.
  - *Process death*: none.
  - *Config change*: **a locale change is a configuration change**, so it recreates the Activity and
    triggers F-145's state loss at the same time. Switching languages while composing a schedule rule
    loses the rule (F-152) *and* re-renders the times differently.
  - *Foreground/background*: none.
  - *Cross-process*: `OverlayService` builds its own overlay text; whether it has the same problem is
    RU-04's scope, not RU-07's.
  - *Persistence*: none — all of these are display-only formatting.
  - *Release-only*: none.
- **Fix** (minimal): pin the locale for machine-format times and isolate the interpolations.

  ```kotlin
  val startStr = String.format(Locale.US, "%02d:%02d", rule.startHour, rule.startMinute)
  val range = BidiFormatter.getInstance().unicodeWrap("$startStr – $endStr")
  ```

  `Locale.US` (not `Locale.getDefault()`) is correct for an `HH:mm` clock rendering that must stay in
  Latin digits alongside Latin unit letters; an en-dash rather than the English word "to" removes the
  untranslatable infix at the same time. For the status strings, move the emoji out of the string and
  render it as a separate leading composable so the text run is homogeneous — that also fixes the
  screen-reader reading of "🔒" as a word.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/TimeRangeFormattingTest.kt`, asserting that an extracted
  `formatTimeRange(22, 0, 6, 0)` returns `"22:00 – 06:00"` while the default locale is temporarily
  set to `Locale("ar", "EG")` — i.e. that the output does **not** vary with the ambient locale. Fails
  before (the inline `String.format` uses the default locale and produces Arabic-Indic digits),
  passes after. Pure JVM, no device, and it pins exactly the property that is wrong today.
- **Existing test conflict**: none. `FormatDurationTest.kt` covers a different formatter and is
  unaffected, but it is the natural home for the `"${h}h ${m}m"` extraction, so the two should be
  reviewed together.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: the four
  `String.format` calls have no `Locale` argument, the range uses an English infix, and the status
  strings interleave emoji with parenthesised digits. Not confirmed: what any of it looks like in an
  RTL paragraph.
  - *Missing*: screenshots of the schedule summary and the Apps-tab status subtitles under forced RTL
    and under an Arabic system locale.
  - *Procedure*: steps 1–5 above.
  - *Severity effect*: if the time range visually reverses under RTL → **Medium**, because the app
    then states the wrong lockout window. If only digit shaping changes and the order holds → stays
    **Low**. Given F-165 (one language, so an RTL locale can never be paired with translated text),
    the realistic exposure today is a user running an RTL system locale with an English app — common
    but not the default, hence Low as filed.
- **Build scope**: everyday-build
- **Constitution**: II (`"${h}h ${m}m"` is built inline in two files while a shared duration
  formatter with its own test already exists)
- **Affected surfaces**: F-165 is the prerequisite for doing any of this through resources. F-152
  compounds it: a locale change destroys an in-progress rule.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-167 — The five navigation-bar labels are 10 sp with `maxLines = 1` and no overflow strategy, so at a large font scale they are clipped mid-glyph inside a bar whose height does not scale  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/MainActivity.kt` → `MainScreen()`'s
  `NavigationBar` (lines 270–300), specifically `Text(label, fontSize = 10.sp, maxLines = 1)` at
  line 288 and the five-destination list at 279–285
- **Issue**: Five `NavigationBarItem`s share the screen width, so each gets roughly a fifth of it —
  about 72 dp on a 360 dp-wide phone. The labels are "Home", "Apps", "VPN", "Limits", "Settings",
  set at `fontSize = 10.sp` with `maxLines = 1` and **no `overflow` argument**, so the default
  `TextOverflow.Clip` applies: text that does not fit is cut mid-character rather than ellipsised. At
  `font_scale 2.0` the label renders at an effective 20 sp, and "Settings" at 20 sp needs materially
  more than 72 dp.

  Two things compound it. Material3's `NavigationBar` has a **fixed** height that does not scale with
  the font setting, so a taller label stack is clipped vertically as well as horizontally — icon and
  label compete for a height that cannot grow. And the bar's height is subtracted from the
  `Scaffold`'s `innerPadding` (`MainActivity.kt:302–304`), so whatever the bar keeps, the three
  unscrollable screens in F-161 lose.

  The user-visible outcome is a tab bar whose labels read as fragments — and unlike a truncated
  paragraph, a nav label is the only text identifying where a tap goes.
- **Trigger condition**:
  1. `adb shell settings put system font_scale 1.3`, look at the tab bar; then `1.5`; then `2.0`.
  2. At each step note whether "Settings" and "Limits" are complete, whether they are clipped
     mid-glyph rather than ellipsised, and whether the label is vertically clipped against the icon.
  3. Repeat in landscape, where the bar is the same height but the items are wider:
     `adb shell settings put system user_rotation 1`.
  4. Reset with `font_scale 1.0` and `user_rotation 0`.
- **Blast radius**:
  - *Callers*: single site — the `NavigationBar` in `MainScreen()`, which is the app's only
    navigation surface. All five destinations are reached through it and there is no alternative
    route to any tab.
  - *Process death*: none.
  - *Config change*: **the trigger.** A font-scale change recreates the Activity and re-measures; it
    also resets `selectedTab` to Home (F-076), so the user is returned to Home and then has to read a
    clipped label to get back.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): let the label ellipsise.

  ```kotlin
  label = {
      Text(
          label,
          fontSize = 10.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center
      )
  },
  ```

  Ellipsis is a strictly better failure mode than a mid-glyph clip: "Settin…" is legible, "Settin"
  ending in a sliced letter is not. If the labels still do not fit at the top of the scale, the
  next-smallest change is to reduce the destination count or move VPN behind Home, but that is a
  product decision and is not proposed here. Do **not** "fix" this by hardcoding a smaller
  `fontSize`: 10 sp is already below Material's recommended minimum, and reducing it further trades
  a layout failure for an accessibility one.
- **Rewrite justification**: not applicable.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/NavBarFontScaleTest.kt`, composing `MainScreen` under
  `LocalDensity provides Density(density, fontScale = 2f)` in a 360 dp-wide container and asserting
  each of the five tab nodes `assertIsDisplayed()` and that its unclipped bounds width does not
  exceed its clipped bounds width. Fails before (clipped), passes after (ellipsised, so the layout
  fits by construction). **Cannot be executed here**: no device, and `ui-test-junit4` is not declared.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: five items,
  10 sp, `maxLines = 1`, no `overflow` argument, so `TextOverflow.Clip` is the default; Material3's
  `NavigationBar` height is a fixed constant. Not confirmed: the exact scale at which each label
  breaks, which depends on device width and font.
  - *Missing*: screenshots of the tab bar at `font_scale ∈ {1.0, 1.15, 1.3, 1.5, 2.0}` on a 360 dp
    and a 411 dp-wide device.
  - *Procedure*: steps 1–4 above.
  - *Severity effect*: labels unreadable at a scale the system slider can reach → stays **Medium**
    (navigation is degraded but the icons remain distinguishable, so the app is still usable). Labels
    *or icons* not rendered at all, so a destination cannot be identified → **High**, on F-160's
    reasoning: reaching the Apps tab is how a user configures protection. Everything legible at
    2.0 → **Low**.
- **Build scope**: everyday-build
- **Constitution**: none directly.
- **Affected surfaces**: F-161 — the bar's fixed height is subtracted from the content area of the
  three unscrollable screens, so this and F-161 consume the same vertical budget and should be
  evaluated in the same device pass. F-076 owns the `selectedTab` reset noted under *Config change*.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-168 — Scaling `sp` text is placed inside containers with exact `dp` heights, and the per-app settings gear is a 36 dp touch target, so large-font users get clipped button labels and every user gets an undersized tap area  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` lines 220 and 237
  (`Modifier.fillMaxWidth().height(56.dp)`);
  `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` line 231 (the same 56 dp around
  "Continue to App"); `app/src/main/java/com/grayzone/app/ui/ScheduleSheet.kt` line 200
  (`.height(52.dp)` around "+ Add Custom Schedule");
  `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` line 337
  (`IconButton(modifier = Modifier.size(36.dp))`) and line 176
  (`Icon(… modifier = Modifier.size(16.dp).clickable { searchQuery = "" })`); and the
  `contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)` buttons at
  `QuickFocusCard.kt:95,108` and `DailyBreakCard.kt:106,121`
- **Issue**: `Modifier.height(56.dp)` is an **exact** constraint, not a minimum. Text inside it is
  laid out in `sp`, which scales with the user's font setting, while the container does not — so at a
  large scale the label is clipped by its own button. A direct search over `ui/` returns **zero**
  occurrences of `heightIn` or `widthIn`, so there is no site in the package where this was done the
  scaling way. The `4.dp` vertical content padding on the four focus/break buttons leaves essentially
  no headroom for a grown "Start"/"Stop"/"End" label.

  Separately and independently of font scale, two touch targets are undersized at **any** font
  scale: `IconButton(Modifier.size(36.dp))` makes the per-app settings gear 36 dp, and the
  search-field clear affordance at `AppsScreen.kt:173–179` is a bare `Icon` with
  `Modifier.size(16.dp).clickable { … }` — a **16 dp** tap area, a third of the 48 dp minimum, with
  no `IconButton` wrapper to supply the standard ripple or minimum size.

  These are individually minor and would be Low on their own. Two things lift them to Medium
  collectively. First, the affected controls are the load-bearing ones: "Manage Custom Prompts",
  "Manage Schedule Focus Modes", "Continue to App" (the onboarding exit), "+ Add Custom Schedule",
  and the gear. Second, they are additive with F-161 and F-167 — every fixed `dp` in a header is
  height the list does not get, and every clipped label is one more control the user cannot
  confidently identify.
- **Trigger condition**:
  1. `adb shell settings put system font_scale 2.0`.
  2. Settings tab: check whether "Manage Custom Prompts" and "Manage Schedule Focus Modes" are
     legible inside their 56 dp buttons or clipped top and bottom.
  3. Onboarding (revoke accessibility to reach it): check "Continue to App".
  4. Schedule sheet: check "+ Add Custom Schedule" in its 52 dp button.
  5. Home: check the "Start"/"Stop"/"End" buttons on the Deep Work and Daily Break cards.
  6. Reset with `font_scale 1.0`. The touch-target half needs no font scaling: Developer options →
     "Show layout bounds" at the default scale is enough.
- **Blast radius**:
  - *Callers*: each cited site is a single composition point. The gear `IconButton`
    (`AppsScreen.kt:335–345`) is rendered once per monitored row, so its touch-target problem recurs
    with every monitored app.
  - *Process death*: none.
  - *Config change*: **the trigger** — a font-scale change is a configuration change and re-measures
    everything.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): change exact heights to minimums, and give the small touch target its minimum
  size.

  ```kotlin
  // SettingsScreen.kt:220,237 · OnboardingScreen.kt:231 · ScheduleSheet.kt:200
  modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)   // was .height(56.dp) / .height(52.dp)

  // AppsScreen.kt:337
  modifier = Modifier.size(48.dp)                            // was .size(36.dp)

  // AppsScreen.kt:173-179 — wrap the clear glyph so it gets a real target and a ripple
  IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(48.dp)) {
      Icon(Icons.Filled.Close, contentDescription = "Clear search",
           tint = GZTextTertiary, modifier = Modifier.size(16.dp))
  }
  ```

  and drop the `vertical = 4.dp` content padding on the four card buttons so `ButtonDefaults`' own
  padding applies. `heightIn(min = …)` preserves the current appearance exactly at normal scales —
  the button is still 56 dp whenever the text fits — and lets it grow only when it must, which is
  what keeps this a one-word change per site rather than a layout redesign.
- **Rewrite justification**: not applicable.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/FontScaleClippingTest.kt`, composing `SettingsScreen`
  under `LocalDensity provides Density(density, fontScale = 2f)` and asserting for each named button
  that the text node's clipped and unclipped bounds are equal, plus scale-independent assertions
  that the gear `IconButton` and the search clear affordance both have touch-target bounds of at
  least 48×48 dp. The second group is deterministic and does not depend on font metrics, which makes
  it the reliable half of the test.
  Fails before, passes after. **Cannot be executed here**: no device, and `ui-test-junit4` is not
  declared.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2) for the clipping half; `confirmed`
  (T1) for the touch target. Confirmed statically: the exact `height()` constraints at the five cited
  lines, the `size(36.dp)` `IconButton`, the 4 dp vertical content padding, and the total absence of
  `heightIn`/`widthIn` in `ui/`. Not confirmed: at which scale each label actually clips.
  - *Missing*: screenshots at `font_scale ∈ {1.3, 1.5, 2.0}` of the Settings tab, the onboarding
    screen, and the schedule sheet.
  - *Procedure*: steps 1–6 above.
  - *Severity effect*: labels clipped to the point of being unidentifiable on the controls that open
    per-app settings or exit onboarding → stays **Medium**, and the onboarding case merges into
    F-160's escalated outcome, since "Continue to App" being unreadable has the same practical effect
    as it being off-screen. Labels merely tight → the clipping half drops to **Low**, but the finding
    holds at **Medium** on the 36 dp and 16 dp touch targets alone, which are confirmed and
    device-independent.
- **Build scope**: everyday-build
- **Constitution**: none directly.
- **Affected surfaces**: `HomeScreen.kt:85,94,154`, `AppsScreen.kt:103,306,310`,
  `QuickFocusCard.kt:52,57`, `DailyBreakCard.kt:55,64` and `StreakCard.kt:53` use fixed `size()` on
  **icons and avatar boxes**, which is correct — icons should not scale with the text setting. Only
  containers wrapping text are in scope here, and the distinction matters so the fix is not applied
  indiscriminately.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-169 — App icons are rasterised to a fixed 40 **pixels** and drawn at 44 dp, so on every device above mdpi they are upscaled and blurred, and `RGB_565` discards the alpha channel so transparent icons render on an opaque black square  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` → `PremiumAppListItem()`'s
  icon slot (lines 302–311, `Image(bitmap = app.icon.asImageBitmap(), modifier =
  Modifier.size(44.dp))`); producer at `app/src/main/java/com/grayzone/app/AppUtils.kt` →
  `APP_ICON_SIZE_PX = 40` (line 15) and `drawableToBitmap()` (lines 93–106)
- **Issue**: Two defects in the same three lines, both squarely in T027's density coverage area.

  **Density.** `APP_ICON_SIZE_PX` is 40 and is used as a raw pixel dimension —
  `Bitmap.createScaledBitmap(src, maxSize, maxSize, true)` at `:96` and
  `Bitmap.createBitmap(w, h, …)` at `:101` both take **pixels**. The consumer draws that bitmap at
  `Modifier.size(44.dp)`, which is a **density-independent** 44 dp. The two only agree at
  `density ≈ 0.9`, i.e. below mdpi. On the densities real devices ship at:

  | Bucket | density | 44 dp in px | source px | upscale |
  |---|---|---|---|---|
  | mdpi | 1.0 | 44 | 40 | 1.1× |
  | xhdpi | 2.0 | 88 | 40 | 2.2× |
  | xxhdpi | 3.0 | 132 | 40 | 3.3× |
  | xxxhdpi | 4.0 | 176 | 40 | 4.4× |

  Every mainstream phone is xhdpi or above, so **every user sees app icons upscaled between 2.2× and
  4.4×** in the monitored-app picker. The constant's name is honest about the unit; the bug is that
  nothing converts it. The producer has a `Context` in hand at `AppUtils.kt:53,77`, so
  `context.resources.displayMetrics.density` is available at the point the constant is used and the
  conversion was simply not done.

  **Alpha.** `drawableToBitmap` forces `Bitmap.Config.RGB_565` on both paths (`:97`, `:101`).
  `RGB_565` has **no alpha channel**. On the `BitmapDrawable` path, `.copy(RGB_565, false)` composites
  away transparency; on the `Canvas` path, `Bitmap.createBitmap(w, h, RGB_565)` starts fully opaque
  and zero-filled — i.e. **black** — and `drawable.draw(c)` leaves every transparent pixel black.
  Most launcher icons are not full-bleed squares, so the visible result is a black square behind the
  glyph, which on the light theme's `#F7F6F3` background is maximally conspicuous. `RGB_565` also
  drops colour depth to 5/6/5 bits, which banding-shifts gradient icons.

  Both were presumably chosen to keep the cache small — the trade-off F-067 quantifies. That is a
  reasonable goal and the fix below preserves it: `ARGB_8888` at a density-correct 44 dp costs about
  70 KB per icon at xxhdpi, so a 300-app device holds roughly 20 MB, which is why the fix pairs the
  size correction with an explicit cache bound rather than just widening the config.
- **Trigger condition**:
  1. Open the Apps tab on any xhdpi-or-denser device and compare the icons against the same apps in
     the system launcher or Settings → Apps. Blur is immediately visible side by side.
  2. `adb shell wm density 160` then relaunch, and compare: at mdpi the icons should look correct,
     which isolates the cause to the missing dp→px conversion rather than to the source drawable.
     Reset with `adb shell wm density reset`.
  3. For the alpha half, find an app whose launcher icon has a transparent background (many older
     apps, and anything with a non-square logo) and check for a black backing square.
- **Blast radius**:
  - *Callers*: `drawableToBitmap()` has one caller, `getInstalledApps()` (`AppUtils.kt:85`).
    `AppInfo.icon` is consumed by `AppsScreen.kt:302–311` and by `LimitsScreen.kt`'s monitored rows,
    so both list screens show the degraded icons. Direct search confirms no other producer.
  - *Process death*: the cache is in-memory only, so a fresh process re-rasterises at the same wrong
    size. There is no persisted artefact to migrate.
  - *Config change*: **a density change is a configuration change**, but `AppMetadataCache` is a
    process-global keyed on nothing and expiring after 60 s, so for up to a minute after a density
    change the list serves bitmaps rasterised for the previous density. After the fix this becomes a
    real staleness bug and the cache should be invalidated on density change — noted so the fix does
    not introduce one.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none — bitmaps are never written to disk.
  - *Release-only*: none.
- **Fix** (minimal): convert to pixels at the call site and stop discarding alpha.
  ```kotlin
  // AppUtils.kt — replace the const with a density-aware size
  private const val APP_ICON_SIZE_DP = 44
  private fun iconSizePx(context: Context): Int =
      (APP_ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()

  // getInstalledApps(): drawableToBitmap(info.loadIcon(pm), iconSizePx(context))
  // drawableToBitmap(): use Bitmap.Config.ARGB_8888 on both paths
  ```
  44 is taken from the consumer's `Modifier.size(44.dp)` so the two stop disagreeing by construction;
  if the UI size ever changes, the constant is the one place to change. Pair this with a cap on
  `AppMetadataCache` (F-067's territory) so the larger bitmaps do not simply grow the retained set.
- **Rewrite justification**: not applicable — both changes are local to `drawableToBitmap` and its
  single caller.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/IconSizingTest.kt`, asserting that
  `iconSizePx` returns 132 for `density = 3.0f` and 44 for `density = 1.0f`, against a stubbed
  `DisplayMetrics`. That is the whole defect expressed as arithmetic and needs no device or bitmap.
  A `robolectric` variant could additionally assert `drawableToBitmap(…).config ==
  Bitmap.Config.ARGB_8888`, but Robolectric is not a declared dependency, so the arithmetic test is
  the executable one.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1) for the mechanism — `APP_ICON_SIZE_PX = 40` is
  passed to two pixel-taking APIs, the consumer draws at `44.dp`, and `RGB_565` is specified on both
  paths and has no alpha channel by definition. `needs-verification` only for how objectionable the
  result looks.
  - *Missing*: a screenshot of the Apps tab on an xxhdpi device.
  - *Procedure*: steps 1–3 above.
  - *Severity effect*: blurred icons plus black boxes behind transparent ones → stays **Medium**
    (the picker is how a user identifies which app to monitor, so icon legibility is functional, not
    decorative — but names are also shown, so nothing is unreachable). Blur only, no black boxes
    observed → **Low**.
- **Build scope**: everyday-build
- **Constitution**: none directly.
- **Affected surfaces**: F-067 quantifies the retained bitmap memory and is the reason the fix must
  come with a cache bound. F-154 is the reason `AppInfo` cannot be put in saved state — it carries
  this `Bitmap`. `LimitsScreen.kt` renders the same `AppInfo.icon` and inherits both defects.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-170 — The monitored-app picker recomposes its entire list every two seconds, performing three `SharedPreferences` reads per monitored app on the main thread and re-wrapping every visible icon `Bitmap`, with no search debounce  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` → `AppsScreen()`'s ticker and
  derived state (lines 57–72), `lockStateMap` (lines 188–196), the `LazyColumn` item body (lines
  199–224), the search field (lines 160–172); and `PremiumAppListItem()`'s
  `app.icon.asImageBitmap()` (line 304). `LimitsScreen.kt` lines 60–90 repeat the same pattern.
- **Issue**: `val currentTime by rememberResumedTicker(2000L)` at `:57` makes `AppsScreen` read a
  snapshot value that changes every two seconds while the screen is visible. Everything downstream of
  that read re-runs on every tick:

  - `lockStateMap` (`:188–196`) is `remember(monitoredApps, currentTime)`, so it is rebuilt each
    tick and performs **three `prefs.getLong` calls per monitored app** — 150 synchronous
    `SharedPreferences` reads every two seconds for 50 monitored apps, on the main thread, inside
    composition. The values are in `SharedPreferences`' in-memory map so each read is cheap, but they
    are not free (each takes the instance lock), and none of them is guarded (F-149).
  - The `items` body (`:199–224`) recomputes `isAppLocked`, `isActiveOrPaused`, `normalizedRemaining`
    and builds a new `statusText` **String** for every composed row on every tick — so a screenful
    of rows allocates a fresh string set 30 times a minute.
  - `app.icon.asImageBitmap()` at `:304` is **not** `remember`ed. `asImageBitmap()` allocates a new
    `AndroidImageBitmap` wrapper on each call, so every recomposition of every visible row produces
    garbage. It also runs on each scroll-driven composition.

  Separately, the search field (`:160–172`) writes `searchQuery` on every keystroke with **no
  debounce**, and `filtered` (`:64–72`) is `remember(installedApps, searchQuery, monitoredApps)` — so
  each keystroke runs a `contains` filter over every installed app and then a full
  `sortedWith(compareBy{…}.thenBy{ it.name.lowercase() })`. The comparator calls `lowercase()` inside
  the comparison, so it allocates a new String per comparison rather than once per element: for
  n = 300 that is on the order of n log n ≈ 2,500 string allocations per keystroke, on the main
  thread. `filtered` correctly does **not** depend on `currentTime`, which is the one thing this
  screen gets right and is why the sort does not also run every two seconds.

  None of this is a correctness defect and on a fast device with 20 monitored apps it will not be
  noticed. It is filed at Medium rather than Low because the picker is the screen most likely to hold
  a long list, the work is all on the main thread inside composition, and it is the same screen
  F-161 says may already be measuring its list to zero height at large font scales — a device that
  is struggling with one will be struggling with both.
- **Trigger condition**:
  1. Monitor 40+ apps on a device with 250+ installed apps.
  2. Open the Apps tab and leave it foregrounded. Watch for a two-second jank cadence with Developer
     options → "Profile GPU rendering", or capture
     `adb shell dumpsys gfxinfo com.grayzone.app framestats` before and after 60 s idle.
  3. Type a query into the search field one character at a time and watch for input lag; a
     systrace/Perfetto capture will show the sort on the main thread per keystroke.
  4. Scroll the list rapidly and check `adb shell dumpsys meminfo com.grayzone.app` for allocation
     churn from the unremembered `asImageBitmap()`.
- **Blast radius**:
  - *Callers*: `AppsScreen()` is composed from `MainActivity.kt:307`. `PremiumAppListItem()` is used
    only by `AppsScreen`. `rememberResumedTicker` is shared with `LimitsScreen.kt`,
    `QuickFocusCard.kt`, `DailyBreakCard.kt` and `ScheduleSheet.kt`, so the tick-driven recomposition
    pattern is app-wide; `LimitsScreen` repeats the per-app `getLong` triple at `:68`.
  - *Process death*: none.
  - *Config change*: an Activity recreation re-runs the whole thing from scratch, including the
    `LaunchedEffect` app load, and discards `searchQuery` (F-145), so the user re-types and pays the
    per-keystroke sort again.
  - *Foreground/background*: `rememberResumedTicker` stops on pause, so the two-second cost is
    correctly confined to the visible screen. That is a genuine mitigation and is why this is not
    a battery finding.
  - *Cross-process*: the `getLong` reads observe `SharedPreferences` written by `OverlayService` in
    the same process; nothing here is cross-process.
  - *Persistence*: read-only — no writes on the tick path.
  - *Release-only*: R8 will not remove any of this; if anything the release build's optimisation
    makes the cost less visible in profiling, not smaller.
- **Fix** (minimal): three independent, small changes.
  ```kotlin
  // 1. Hoist the lowercase key out of the comparator (AppsScreen.kt:68-71)
  baseList.map { it to it.name.lowercase() }
          .sortedWith(compareBy({ !monitoredApps.contains(it.first.packageName) }, { it.second }))
          .map { it.first }

  // 2. Remember the image wrapper per app (AppsScreen.kt:304)
  val image = remember(app.packageName) { app.icon.asImageBitmap() }

  // 3. Derive the row's status from the tick without rebuilding the map:
  //    read the three longs once per app into lockStateMap keyed on monitoredApps only,
  //    and let currentTime drive the comparison, not the read.
  val lockStateMap = remember(monitoredApps) { /* the three getLong calls */ }
  ```
  Change 3 is the important one and is a genuine behaviour change worth stating: the prefs would then
  be re-read only when the monitored set changes, so a lock written by `OverlayService` would not be
  picked up within two seconds. That is acceptable **only** if the screen also re-reads on
  `ON_RESUME`, which F-147 already requires for the sibling screen — so 3 should be implemented
  together with F-147's resume observer, not on its own. Debouncing the search is deliberately not
  proposed: with change 1 the sort is cheap enough that a debounce would add latency for no benefit.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/AppListSortingTest.kt`, asserting that an extracted
  `sortAppsForPicker(apps, monitored)` puts monitored apps first and orders each group
  case-insensitively, over a fixture including mixed-case names. That pins the behaviour the
  comparator change must preserve — the point of the test is to make change 1 safe, since a sort
  rewrite is exactly the kind of edit that silently reverses an ordering. Pure JVM.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2). `confirmed` statically: the ticker
  interval, the `remember` keys, the three `getLong` calls per monitored app, the `lowercase()`
  inside the comparator, the unremembered `asImageBitmap()`, and the absence of any debounce. Not
  confirmed: whether any of it is measurable as jank on real hardware.
  - *Missing*: a frame-timing capture on a mid-range device with a large app list.
  - *Procedure*: steps 1–4 above. `dumpsys gfxinfo … framestats` gives the numeric answer; anything
    under the 16 ms budget means this stays a code-quality finding.
  - *Severity effect*: measurable dropped frames on the two-second cadence or visible search input
    lag → stays **Medium**. No measurable impact → **Low**, since nothing here is incorrect, only
    wasteful.
- **Build scope**: everyday-build
- **Constitution**: II (`LimitsScreen.kt:68` builds the identical `AppLockState` triple from the
  identical three keys; F-079 already owns the missing shared factory — cite, do not re-file)
- **Affected surfaces**: F-149 (these are among the thirty unguarded typed reads), F-147 (change 3
  depends on its resume observer), F-157 (the same two-second snapshot is what makes the un-monitor
  toggle racy), F-161 (same screen, same device pass), F-079 (the duplicated triple).
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-171 — The app has no responsive layout of any kind: no window size classes, no `BoxWithConstraints`, no `layout-`/`values-` qualifier directories, and no `dimens.xml`, so there is no override point for landscape, tablets, or foldables  [Medium]

- **Review unit**: RU-07
- **Location**: all of `app/src/main/java/com/grayzone/app/ui/` and `app/src/main/res/`; the
  `<activity android:name=".MainActivity">` declaration in `app/src/main/AndroidManifest.xml`
- **Issue**: A direct search over `app/src/main/java/com/grayzone/app/ui` returns **zero** matches
  for `BoxWithConstraints`, `WindowSizeClass`, `calculateWindowSizeClass`, `LocalConfiguration`,
  `heightIn` and `widthIn`. The resource tree contains exactly one `values` directory and no
  qualifier variants — no `values-night` (F-162), no `values-sw600dp`, no `values-land`, no
  `layout-land`, and **no `dimens.xml` at all**, so every dimension in the app is a Kotlin `dp`
  literal at its use site. There is therefore no mechanism, in code or in resources, by which any
  screen can render differently on a different form factor.

  The manifest compounds this rather than mitigating it. `MainActivity` declares no
  `android:screenOrientation`, so every screen rotates freely; it declares no
  `android:configChanges`, so every rotation destroys and recreates the Activity (F-145); and
  `android:resizeableActivity` is unset, which on API 24+ defaults to **true**, so the app is
  freely resizable in split-screen and desktop windowing modes — each resize being another
  configuration change. `minSdk = 29`, so every device this ships to supports multi-window.

  The consequence is that landscape is not a supported-but-imperfect layout; it is an unconsidered
  one. It is also the configuration in which F-160 and F-161 are worst, because a landscape phone
  has roughly 360 dp of height for content that was laid out against 800: `OnboardingScreen`'s
  centred non-scrolling column and the three fixed headers above `LazyColumn`s are exactly the
  constructs that fail first when height is halved. On a tablet the opposite failure applies — a
  single-column list at 24 dp side padding across 1280 dp of width, with the five-item bottom
  `NavigationBar` (F-167) that Material reserves for compact widths.

  This is filed as a distinct finding rather than folded into F-160/F-161 because those describe two
  specific screens failing; this describes the absence of the facility that would let any screen
  adapt. Fixing F-160 and F-161 makes landscape survivable on those screens and changes nothing about
  tablets, foldables, or split-screen.
- **Trigger condition**:
  1. `adb shell settings put system accelerometer_rotation 1` then
     `adb shell settings put system user_rotation 1` to force landscape. Walk all five tabs plus
     onboarding and the three sheets.
  2. Enter split-screen (recents → app icon → "Split top") and resize the divider through its full
     range, watching for clipped controls at the smallest height.
  3. `adb shell wm size 1600x2560 && adb shell wm density 320` to emulate a tablet; relaunch and
     look at line lengths, the nav bar, and the amount of empty horizontal space.
  4. Reset: `adb shell wm size reset && adb shell wm density reset &&
     adb shell settings put system user_rotation 0`.
- **Blast radius**:
  - *Callers*: every screen. `MainActivity.kt:302–313` hosts all five tabs in a single `Scaffold`
    with one layout.
  - *Process death*: none.
  - *Config change*: **this finding is about configuration change.** Rotation, resize, fold/unfold
    and density change all recreate the Activity (no `configChanges`) and all produce the same layout
    at a different size.
  - *Foreground/background*: split-screen means the app can be visible while not focused; nothing in
    RU-07 distinguishes those states, and `rememberResumedTicker` keys on `ON_RESUME`, so a
    split-screen but unfocused Grayzone still ticks. Not a defect, but worth knowing when measuring
    F-170.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): none as a single patch — the honest statement is that this is a design gap, not
  a bug with a diff. The two changes that are genuinely minimal and worth doing now are (a) fix F-160
  and F-161, which make every screen survive a short viewport, and (b) decide explicitly whether
  tablets are supported. If they are not, say so in the manifest rather than shipping an unconsidered
  layout. If they are, `BoxWithConstraints` around the `LazyColumn`s to switch to a two-pane layout
  above 600 dp is the smallest real step, and it needs a `dimens.xml` to exist first.
- **Rewrite justification**: not applicable — no rewrite is being proposed.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/LandscapeReachabilityTest.kt`, composing each screen
  in a `Box(Modifier.size(800.dp, 360.dp))` (a landscape phone viewport) and asserting that each
  screen's primary action node `assertIsDisplayed()`. That is a single parameterised test that would
  have caught F-160 and F-161 as well, which is the argument for adding it here rather than
  per-screen. **Cannot be executed here**: no device, and `ui-test-junit4` is not declared.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1) for the absence — the searches for
  `BoxWithConstraints`, `WindowSizeClass`, `LocalConfiguration`, `heightIn`/`widthIn` return zero
  matches in `ui/`, the `res` tree has one `values` directory and no `dimens.xml`, and the manifest
  sets neither `screenOrientation` nor `configChanges` nor `resizeableActivity`.
  `needs-verification` only for what the resulting layouts look like.
  - *Missing*: screenshots of all five tabs in landscape, in split-screen at minimum height, and at
    tablet dimensions.
  - *Procedure*: steps 1–4 above.
  - *Severity effect*: **Medium** as filed, because landscape is reachable by accident on any device
    with auto-rotate on, and F-160/F-161 mean specific controls may be unreachable there. If step 1
    shows a protection-enabling control unreachable in landscape, that outcome is owned by F-160 and
    F-161 and escalates there, not here — this finding stays Medium either way, since its subject is
    the missing facility rather than any one screen.
- **Build scope**: everyday-build
- **Constitution**: I (magic numbers: every dimension is an inline literal with no `dimens.xml`
  override point)
- **Affected surfaces**: F-160 and F-161 are the two screens where this bites hardest; F-167's
  five-item nav bar is the tablet-specific case; F-145 owns the state loss that every resize
  triggers; F-172 owns the hardcoded status-bar clearance, which is the same missing-abstraction
  problem applied to insets.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-172 — Top-of-screen clearance is a hardcoded literal that differs four ways across six screens, with no use of the window-insets API anywhere, so header spacing does not track the status bar and 56 dp of it is wasted height on the screens F-161 is already starving  [Low]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/AppsScreen.kt` line 78,
  `app/src/main/java/com/grayzone/app/ui/LimitsScreen.kt` line 47 and
  `app/src/main/java/com/grayzone/app/ui/VpnScreen.kt` line 125 (all
  `padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 16.dp)`);
  `app/src/main/java/com/grayzone/app/ui/HomeScreen.kt` line 80
  (`padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp)`);
  `app/src/main/java/com/grayzone/app/ui/SettingsScreen.kt` line 69 and
  `app/src/main/java/com/grayzone/app/ui/OnboardingScreen.kt` line 60 (both a uniform
  `padding(24.dp)`)
- **Issue**: Six top-level screens use four different top-padding values — 56 dp, 28 dp, 24 dp and
  24 dp — with no comment explaining any of them and no shared constant. Searching all of
  `app/src/main/java/com/grayzone/app` for `WindowInsets`, `systemBars`, `statusBars`,
  `navigationBars` and `safeDrawing` returns **zero** matches, so none of these numbers is derived
  from a real inset; they are guesses. The result is that Home's header sits 28 dp from the top while
  Apps, Limits and VPN sit 56 dp from it, so switching tabs visibly jumps the title.

  Two qualifications, because this is easy to overstate. First, `targetSdk = 34` and there is no
  `enableEdgeToEdge()` call, so the window is **not** edge-to-edge today: the platform already insets
  the content below the status bar, and these paddings are additional margin rather than the only
  thing keeping text out from under the clock. Nothing is currently drawn under the status bar, which
  is why this is Low and not Medium. Second, that protection expires on the next `targetSdk` bump:
  Android 15 forces edge-to-edge for apps targeting 35+, at which point a hardcoded 28 dp on Home is
  smaller than the status bar on most devices and the header **would** slide under it. That is a
  forward risk, stated as such, not a present defect.

  What is a present cost is the 56 dp itself. That is pure whitespace above the title on exactly the
  three screens F-161 identifies as putting a tall fixed header above a `LazyColumn` in a
  non-scrolling `Column` — so 56 dp of the height the list is being starved of buys nothing.
- **Trigger condition**:
  1. Switch between the Home and Apps tabs and watch the title's vertical position jump by 28 dp.
  2. On a device with a large display cutout or a tall status bar, check the gap above the Apps
     header against the gap above the Home header.
  3. For the forward risk, build a copy with `targetSdk = 35` and relaunch: the Home header should
     move under the status bar. This is the one step that requires a code change to reproduce, and
     it should be done before the real `targetSdk` bump rather than after.
- **Blast radius**:
  - *Callers*: six single-composition sites, one per screen. All six sit inside
    `MainActivity`'s `Scaffold` content slot except `OnboardingScreen`, which is composed
    separately — so onboarding does not even receive the `Scaffold`'s `innerPadding` and relies
    entirely on its own uniform 24 dp.
  - *Process death*: none.
  - *Config change*: rotation changes the status-bar height on some devices (and removes it entirely
    in some landscape modes), and none of these constants responds.
  - *Foreground/background*: none.
  - *Cross-process*: none.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): replace the literals with the real inset and one shared horizontal value.
  ```kotlin
  // one shared modifier, e.g. in SharedComponents.kt
  fun Modifier.screenHeaderPadding() = this
      .windowInsetsPadding(WindowInsets.statusBars)
      .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)
  ```
  This is correct under both the current non-edge-to-edge window (where `statusBars` resolves to zero
  once the decor has consumed it, leaving the intended 8 dp) and a future edge-to-edge one (where it
  resolves to the real bar height). Doing it now is what makes the `targetSdk` bump a non-event
  instead of a visual regression, and it removes roughly 48 dp of dead height from the three screens
  F-161 covers.
- **Rewrite justification**: not applicable.
- **Test to add**: `instrumented` —
  `app/src/androidTest/java/com/grayzone/app/ui/HeaderInsetTest.kt`, composing each screen under a
  `WindowInsets` stub with a 48 dp status bar and asserting that each screen's title node's top
  bounds are at least 48 dp from the container top, and that the value is **equal** across all six
  screens. The cross-screen equality assertion is the one that pins the actual defect — the
  inconsistency — and it fails today by construction. **Cannot be executed here**: no device, and
  `ui-test-junit4` is not declared.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). The four distinct values are in the six cited
  lines; the absence of any insets API is a zero-result search over
  `app/src/main/java/com/grayzone/app`; `targetSdk = 34` is in `app/build.gradle.kts:29`.
- **Build scope**: everyday-build
- **Constitution**: I (magic numbers), II (six screens each re-declare their own header padding
  where one shared modifier belongs)
- **Affected surfaces**: F-161 — 48 dp of the 56 is recoverable height on precisely the three
  starved screens. F-171 — same missing-abstraction root cause. F-160 — `OnboardingScreen` is outside
  the `Scaffold` and so has no inset source at all beyond its own literal.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-173 — The custom `Typography` is provided to `MaterialTheme` and then never referenced, so every `Text` sets `fontSize` inline and silently inherits `bodyLarge`'s 24 sp `lineHeight` — putting five 26–28 sp screen titles in a line box smaller than their own glyphs  [Medium]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/theme/Type.kt` (the whole file — six styles,
  lines 9–52); `app/src/main/java/com/grayzone/app/ui/theme/Theme.kt` line 109
  (`typography = Typography`); and the five oversized titles that inherit from it —
  `AppsScreen.kt:80`, `LimitsScreen.kt:49`, `SettingsScreen.kt:77`, `OnboardingScreen.kt:61` (all
  `fontSize = 28.sp`) and `VpnScreen.kt:149` (`fontSize = 26.sp`)
- **Issue**: `MaterialTheme.typography` has **zero** references in all of `app/src` — the only two
  mentions of `Typography` are its declaration and its being passed to `MaterialTheme`. No composable
  anywhere uses `style = MaterialTheme.typography.titleLarge` or any sibling. Every `Text` in the app
  instead passes `fontSize = N.sp` directly.

  That is not merely unused code, because of how `Text` merges styles. `MaterialTheme` sets
  `LocalTextStyle` to `typography.bodyLarge`, and `Text` merges its explicit parameters onto that
  local, ignoring parameters left `Unspecified`. `Text(…, fontSize = 28.sp)` therefore produces
  **fontSize 28 sp with `bodyLarge`'s `lineHeight = 24.sp`** (`Type.kt:35`) and its
  `letterSpacing = 0.15.sp` — a line box 4 sp shorter than the text it contains. The declared
  `displayLarge` style, which pairs 32 sp with a correct 40 sp line height (`Type.kt:13–14`), is
  exactly what these titles should be using and is unreachable because nothing reads it.

  The visible consequences are a vertically cramped or clipped title where ascenders and descenders
  exceed the line box, and outright overlapping lines if any of these titles ever wraps — which is
  the realistic path, since at a large font scale a 28 sp title on a narrow screen wraps. Because
  both values are in `sp`, the ratio does not worsen with the font-scale setting; it is wrong at
  every scale, and the wrap that exposes it becomes likely at large ones.

  The design gap underneath is that `Type.kt` defines 6 of Material3's 15 styles, so the other 9
  (including `labelLarge`, which `Button` uses, and `headlineSmall`, which `AlertDialog`'s title
  uses) silently fall back to M3's defaults. The app's type scale is therefore half bespoke and half
  Material default, and no single place governs it.
- **Trigger condition**:
  1. Open the Apps, Limits, Settings and VPN tabs at `font_scale 1.0` and look closely at the
     descenders in the titles ("Monitored Apps" has a `pp`; "Limits Dashboard" has none, which is why
     the two look different).
  2. `adb shell settings put system font_scale 2.0` and narrow the window (split-screen) until a
     title wraps to two lines; check whether the lines overlap.
  3. Confirm the inheritance directly: temporarily add `lineHeight = 36.sp` to `AppsScreen.kt:80` and
     compare. If the title's vertical extent changes, the 24 sp was in effect.
  4. Reset `font_scale 1.0`.
- **Blast radius**:
  - *Callers*: `Typography` is referenced once, at `Theme.kt:109`. The five oversized titles are the
    acute cases; every other `Text` in the app also inherits `bodyLarge`'s line height and letter
    spacing, but at 10–17 sp the 24 sp box is generous rather than tight, so those are unaffected in
    practice — the defect is specific to text larger than 24 sp.
  - *Process death*: none.
  - *Config change*: a font-scale change re-measures and is what makes the wrap case reachable.
  - *Foreground/background*: none.
  - *Cross-process*: `OverlayService` builds its overlay views without Compose and does not use
    `Typography` either; that surface is RU-04's.
  - *Persistence*: none.
  - *Release-only*: none.
- **Fix** (minimal): use the style that already exists.
  ```kotlin
  // AppsScreen.kt:80, LimitsScreen.kt:49, SettingsScreen.kt:77, OnboardingScreen.kt:61
  Text("Monitored Apps", color = GZTextPrimary, style = MaterialTheme.typography.displayLarge)
  ```
  `displayLarge` is 32 sp / 40 sp / −0.4 sp letter spacing, which is within a few sp of what these
  call sites are hand-rolling and has a correct line height. This is a one-line substitution per site
  that also makes `Type.kt` reachable for the first time. If 32 sp is judged too large, the narrower
  fix is to add `lineHeight = 36.sp` at each of the five sites — but that perpetuates the pattern
  the finding is about.
- **Rewrite justification**: not applicable.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/theme/TypographyScaleTest.kt`, asserting for each of the six
  declared styles that `lineHeight.value >= fontSize.value * 1.2` — the invariant `bodyLarge`
  satisfies (24 ≥ 19.2) and that any future style must. It does not catch the current defect directly
  (which lives at the call sites, not in `Type.kt`), and saying so matters: its purpose is to stop a
  regression in the table the fix makes load-bearing. The call-site assertion is `instrumented` —
  asserting the title node's `lineHeight` is not 24 sp — and **cannot be executed here**.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (evidence tier T2) for the visible symptom; `confirmed`
  (T1) for the mechanism — zero `MaterialTheme.typography` references in `app/src`,
  `bodyLarge.lineHeight = 24.sp` at `Type.kt:35`, and five call sites at 26–28 sp with no
  `lineHeight` argument.
  - *Missing*: a screenshot comparison of a title with and without an explicit `lineHeight`, and a
    wrapped title at a large font scale.
  - *Procedure*: steps 1–4 above; step 3 is the decisive one and takes one line of temporary code.
  - *Severity effect*: visible clipping of descenders or overlapping wrapped lines → stays
    **Medium**. Compose absorbs the mismatch with no visible artefact at any scale → **Low**, on the
    unreferenced type scale alone.
- **Build scope**: everyday-build
- **Constitution**: I (`Type.kt`'s six styles are effectively dead — provided but never read; the
  correct resolution is to **use** them, not to delete them, since the call sites are duplicating
  what they declare), II (the type scale is declared in one place and re-implemented inline at every
  call site)
- **Affected surfaces**: F-168 shares the font-scale device pass. `evidence/lint.md` does not flag
  this — no lint check exists for an unreferenced `Typography` — which is why a manual finding is the
  only route to it.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-174 — The overlay's colours are an 18-constant hand-copy of the Compose palettes with four misleading names, so the two theme surfaces can silently drift apart  [Low]

- **Review unit**: RU-07
- **Location**: `app/src/main/java/com/grayzone/app/ui/theme/Theme.kt` → `isNightMode()` (lines
  26–33) and `GrayzoneTheme`'s `(view.context as Activity)` cast (line 100);
  `app/src/main/java/com/grayzone/app/service/OverlayService.kt` → the 18 colour constants (lines
  74–96) and `overlayChrome()` (lines 1150–1161)
- **Issue**: **Scope note first, because this finding sits next to one that is already filed.**
  F-231 (T050, High) already owns the fact that the overlay is built once and never rebuilt on a
  configuration change, and it explicitly names the night-mode case: *"a night-mode switch while a
  lock screen is up leaves light chrome on a dark system."* That half is **not** re-filed here. What
  F-231 does not examine is where the overlay's colour values come from, and that is this record's
  subject.

  The app resolves "is it dark?" twice, through two paths that share only `isNightMode()`. In the
  Activity, `GrayzoneTheme` reads `Color.kt`'s palettes. In `OverlayService`, `overlayChrome()`
  (`:1150–1161`) selects between **18 hardcoded `Int` literals** declared at `:74–96` — a hand-copy
  of values that already exist in `Color.kt`. I checked
  all 18 against `LightGzPalette` and `DarkGzPalette` and **they are currently in sync** — that is
  the finding's mitigating fact and the reason it is Low rather than Medium. But they are plain
  literals with no link to the source, the comment at `:74` ("mirrors ui.theme palettes") is the only
  thing asserting the relationship, and nothing detects drift. Four of them are also misnamed
  relative to their source: `ACCENT_LIGHT` is `primary` (`0xFF0F766E`), not `accent`
  (`0xFF0D9488`), and `ACCENT_DIM_LIGHT` is `primaryContainer`, not `accentDim` — so anyone
  re-syncing them by name will pull the wrong values. `ON_ACCENT_DARK` at `:96` is also declared
  `private val` where its 17 neighbours are `private const val`, which is a compile-time constant
  lost for no reason.

  Two smaller hazards in the same lines, recorded because they are cheap to fix while this is open.
  `GrayzoneTheme`'s `(view.context as Activity)` at `Theme.kt:100` is an unchecked cast: the theme
  has exactly one call site today (`MainActivity.kt:73`) so it cannot fail now, but it means
  `GrayzoneTheme` can never be used from a `ComposeView` in a service or a `@Preview` without
  throwing, which is precisely the direction an overlay rewrite would go. And `isNightMode()`
  performs an unguarded `getString` on `THEME_MODE` (`:27–28`) from the service — the F-149 crash
  path reaching the protection surface rather than the UI.
- **Trigger condition**: there is nothing to run on a device, and saying so is the point — the
  constants are currently in sync, so today the two surfaces agree and no observation would show
  anything. The defect is latent and the trigger is a **future edit**:
  1. Change any colour in `Color.kt` — for example `LightGzPalette.background`.
  2. Rebuild. The compiler is silent, no lint check fires, and no test fails.
  3. Trigger the friction overlay and compare it against the app's own background. They now differ,
     and nothing in the repository recorded that they were ever meant to match.
  4. The four misnamed constants are checkable now, statically: compare `ACCENT_LIGHT`
     (`0xFF0F766E`) against `LightGzPalette.accent` (`0xFF0D9488`) and `LightGzPalette.primary`
     (`0xFF0F766E`).
- **Blast radius**:
  - *Callers*: `isNightMode()` has two callers — `overlayChrome()` (`OverlayService.kt:1151`) and
    `resolveDarkTheme` indirectly via `GrayzoneTheme` (`Theme.kt:41`). `overlayChrome()` is called
    per overlay-view construction. The 18 constants are read only by `overlayChrome()`.
  - *Process death*: none — the constants are compile-time values.
  - *Config change*: **owned by F-231**, which files the fact that a `uiMode` change does not rebuild
    the overlay. Not re-filed here; noted so the punch list pairs them.
  - *Foreground/background*: the two surfaces are visible at different times — the overlay shows
    precisely when the Activity is not foreground — so a drift between them is never seen
    side by side and is correspondingly easy to miss in review.
  - *Cross-process*: none — `OverlayService` runs in the default process. But the two surfaces read
    the same `THEME_MODE` key from `SharedPreferences` at different times, which is the same
    read-time-divergence class the cross-process inventory tracks.
  - *Persistence*: `THEME_MODE` is the shared persisted input; neither side writes it from these
    paths.
  - *Release-only*: none.
- **Fix** (minimal): make the constants derive from the single source. The refresh half is F-231's
  and is deliberately not restated.
  ```kotlin
  // OverlayService.kt — replace the 18 literals
  private fun overlayChrome(): OverlayChrome {
      val p = if (isNightMode(this)) DarkGzPalette else LightGzPalette
      return OverlayChrome(
          bg = p.background.toArgb(), surface = p.surface.toArgb(),
          accent = p.primary.toArgb(), accentDim = p.primaryContainer.toArgb(),
          text = p.textPrimary.toArgb(), grey = p.textSecondary.toArgb(),
          greyDim = p.textTertiary.toArgb(), red = p.red.toArgb(),
          border = p.border.toArgb(), onAccent = p.onPrimary.toArgb()
      )
  }
  ```
  `GzPalette` is a plain data class in `ui.theme` with no Compose runtime dependency at the value
  level, so a Service can read it; `Color.toArgb()` is a pure conversion. That deletes 18 constants,
  removes the naming trap, and makes drift impossible by construction. It also makes F-231's fix
  cheaper: once `overlayChrome()` is a two-line derivation, re-invoking it on a configuration change
  is trivially correct. Separately, change `ON_ACCENT_DARK` to `const val`, and replace
  `Theme.kt:100`'s `(view.context as Activity)` with `(view.context as? Activity)?.window ?: return@SideEffect`.
- **Rewrite justification**: not applicable — every change here is a mechanical substitution.
- **Test to add**: `jvm-unit` —
  `app/src/test/java/com/grayzone/app/ui/theme/OverlayChromeParityTest.kt`, asserting
  `LightGzPalette.background.toArgb() == 0xFFF7F6F3.toInt()` and the same for the other nine mapped
  roles in both palettes. Before the fix this is the drift detector the constants currently lack;
  after it, it pins the mapping. Pure JVM — `Color.toArgb()` needs no Android runtime for sRGB
  values. It passes today, which is stated plainly: its value is regression protection, not defect
  detection, and it should be added regardless of whether the refresh half is done.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1). All 18 constants at `OverlayService.kt:74–96`
  were compared by hand against `LightGzPalette` (`Color.kt:54–89`) and `DarkGzPalette`
  (`:91–126`): every value matches its counterpart, and `ACCENT_LIGHT`/`ACCENT_DIM_LIGHT` and their
  dark twins match `primary`/`primaryContainer` rather than the palette's `accent`/`accentDim`.
  `ON_ACCENT_DARK` at `:96` is a `private val` among `private const val`s. `Theme.kt:100` casts
  unchecked and `GrayzoneTheme` has exactly one call site (`MainActivity.kt:73`). Nothing here needs
  a device — which is why this is the one theming finding in the block that is fully settled.
  Held at **Low** deliberately: there is no present user-visible symptom, because the values agree
  today, and the one symptom a user could see (an overlay in the wrong theme after a night-mode
  switch) belongs to F-231 at High. What is filed here is a maintainability defect with a latent
  correctness edge, which is what Low is for.
-   - *Missing*: on-device confirmation (no emulator in this audit).
  - *Procedure*: see [`pending-device.md`](./pending-device.md) and the Trigger condition steps above.
  - *Severity effect*: unchanged if confirmed; may drop one tier if unreachable on target OEM/API.
- **Build scope**: everyday-build
- **Constitution**: I (`ON_ACCENT_DARK` needlessly non-`const`; four misleading constant names),
  II (the palette is declared in `Color.kt` and hand-copied into `OverlayService.kt` — this is the
  third colour source F-164 identifies, and the fix collapses it into the first)
- **Affected surfaces**: **F-231 (T050, High) owns the refresh half** — the overlay not being
  rebuilt on a configuration change, including the night-mode case — and this record does not
  restate it; the two should be actioned together, with this one first because it shrinks F-231's
  fix. F-164 owns the Activity-side half of the multiple-sources problem and is independent. F-149
  owns the unguarded `THEME_MODE` read at `Theme.kt:27–28`, which `overlayChrome()` inherits. F-162
  owns the `TimePickerDialog` theme, a third build-time theme resolution that also does not follow
  the app.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## Roll-up

### Findings filed

| ID | Title (short) | Severity | Verification |
|---|---|---|---|
| F-160 | `OnboardingScreen` centres non-scrolling content; permission buttons clipped off both ends | High | needs-verification |
| F-161 | Tall fixed header above a `LazyColumn` in a non-scrolling `Column` on three screens | High | needs-verification |
| F-162 | Hardcoded `Theme.Material.Light` window theme, no `values-night` | Medium | confirmed |
| F-163 | `resolveDarkTheme`'s unused `context` parameter — stale signature, not dead dynamic colour | Low | confirmed |
| F-164 | Colour tokens read a process global published in a post-composition `SideEffect` | Medium | needs-verification |
| F-165 | Every UI string is a Kotlin literal; `strings.xml` has two non-UI entries | Low | confirmed |
| F-166 | Bidi-unsafe time ranges, emoji status strings, and locale-dependent `String.format` | Low | needs-verification |
| F-167 | Five 10 sp nav labels, `maxLines = 1`, no overflow strategy, fixed-height bar | Medium | needs-verification |
| F-168 | `sp` text inside exact `dp` heights; 36 dp and 16 dp touch targets | Medium | needs-verification (touch targets confirmed) |
| F-169 | Icons rasterised at 40 px, drawn at 44 dp; `RGB_565` discards alpha | Medium | confirmed mechanism |
| F-170 | Picker recomposes every 2 s: 3N main-thread prefs reads, unremembered `asImageBitmap()` | Medium | needs-verification |
| F-171 | No responsive layout facility of any kind — no size classes, qualifiers, or `dimens.xml` | Medium | confirmed |
| F-172 | Top clearance is a literal differing four ways across six screens; no insets API | Low | confirmed |
| F-173 | `Typography` provided but never referenced; 26–28 sp titles inherit a 24 sp line height | Medium | confirmed mechanism |
| F-174 | Overlay chrome is an 18-constant hand-copy of the palettes, four of them misnamed | Low | confirmed |

**Counts**: High 2 · Medium 8 · Low 5 · Critical 0 · **total 15** (block F-160 … F-174 fully used).

### Verdicts on the required coverage areas

| Area | Verdict |
|---|---|
| **Dark mode via `ui/theme/Theme.kt`** | Defective in three distinct ways. The palette *selection* is correct — F-163 settles that `resolveDarkTheme`'s logic is sound and that the unused `context` parameter is a stale signature, **not** dead dynamic-colour code. But the palette is published one frame late (F-164), the window theme has no night variant so the launch window and both time pickers stay light (F-162), and the overlay's colours are a hand-copy that can drift (F-174, with the refresh half owned by F-231). |
| **RTL and locale handling** | Layout mirroring is **correct** — direction-aware padding throughout and `Icons.AutoMirrored` where required; I looked for `left`/`right` padding and non-mirrored arrows and found none. Text handling is not (F-166), and the app has one language with no resource externalisation at all (F-165). |
| **Density coverage** | One confirmed defect with universal reach: app icons are rasterised at a fixed 40 **pixels** and drawn at 44 dp, so every device above mdpi shows them upscaled 2.2×–4.4× (F-169). Beyond that there is no density-varying resource in the project except launcher icons (F-171). |
| **Large-font / font-scale robustness** | The weakest area. Two potentially protection-blocking (F-160, F-161), plus nav labels (F-167), fixed-height buttons and undersized touch targets (F-168), and a line-height inheritance defect affecting every screen title (F-173). |
| **Large-list handling in `AppsScreen.kt`** | Correct in structure — `LazyColumn` with a stable `key`, and `filtered` correctly excludes the ticker from its `remember` keys. Wasteful in execution (F-170), and the list can be measured to zero height before any of that matters (F-161). |

### Unresolvable statically

Every `needs-verification` finding carries its own procedure; the three that cannot be advanced at
all without hardware are: the exact font scale at which each control fails (F-160, F-161, F-167,
F-168), whether the one-frame light palette is perceptible (F-164), and whether the two-second
recomposition is measurable as jank (F-170). Two more need a code change rather than a device: the
`targetSdk = 35` edge-to-edge behaviour in F-172, and pseudolocale testing in F-166, which requires
`isPseudoLocalesEnabled` on a build type this project does not configure.

### Duplicates and contradictions against findings already filed

- **F-174 vs F-231 (T050, High) — overlap found and resolved by narrowing this record.** F-231
  already owns "the overlay is built once and never rebuilt on a configuration change", and names
  the night-mode case explicitly. F-174 was drafted covering that symptom and was **cut back** to the
  part F-231 does not examine: where the overlay's colour values come from. Its trigger, blast radius
  and fix now exclude the refresh half and cite F-231 for it. No double-count reaches the punch list.
- **F-171 vs F-230 / F-231 (T050)** — E4 covers foldables and multi-window as a *condition*, finding
  two specific defects there. F-171 files the different fact that no responsive-layout facility
  exists at all (no size classes, no resource qualifiers, no `dimens.xml`), which is the reason those
  conditions have no adaptation path. Distinct subjects, and F-171 cites rather than restates them.
- F-164 and F-174 both concern colour sources but own disjoint halves — Compose-side publication
  versus the overlay's hand-copy — with independent fixes.
- **F-169 vs F-067**: F-067 already quantifies the retained icon bitmaps as a memory-pressure input
  and correctly describes them as "40×40 `RGB_565`". F-169 does not re-file that; it files the
  separate fact that 40 is a pixel count being matched against a dp dimension, and that `RGB_565`
  discards alpha. The two fixes interact — F-169's fix enlarges the bitmaps F-067 is worried about —
  which is why F-169's fix text requires the cache bound.
- **F-170 vs F-079**: the duplicated `AppLockState` triple is F-079's; cited, not re-filed.
- **F-166 vs F-152**: a locale change is a configuration change, so it also destroys an in-progress
  schedule rule. That consequence is F-152's; F-166 notes it under *Config change* only.
- **One correction to `evidence/reference-index.md`**: it does not record that `Typography`
  (`ui/theme/Type.kt`) has zero call-site references. Direct search over `app/src` confirms the only
  two mentions are its declaration and `Theme.kt:109`. Per the audit rule, this was verified by
  direct search rather than taken from the index, and F-173 does **not** propose deleting it — the
  correct resolution is to start using it.
- **One defect found but deliberately not filed**, because it is out of T027's scope and the T026
  block is exhausted: `AddRuleDialog`'s confirm button (`ScheduleSheet.kt:303–320`) is ungated, so
  unchecking both "Weekdays" and "Weekends" saves a `ScheduleRule` with an empty `daysOfWeek`, which
  can never fire. There is also no validation that start and end times differ. This is
  input-validation behaviour, not a configuration variant, and should be routed to whichever unit
  owns `data/ScheduleManager.kt`.

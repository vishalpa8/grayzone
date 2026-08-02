# Constitution compliance summary (T084)

**Feature**: `004-android-regression-audit` | **Updated**: 2026-08-02

| Principle | Found | Fixed | Residual |
|---|---|---|---|
| **I** Cleanliness | Unused Theme `context`; dead helpers; stale ProGuard naming; unused VpnScreen modifiers | Removed / wired / renamed keep to `GrayzoneAccessibilityService` | Java 8 obsolete compile flags (toolchain 21 added) |
| **II** Consistency | Dual clock paths; JSON empty-list overwrite; setup model 2/7 perms | Shared `MonotonicDeadline`; refuse-overwrite; expanded `SetupRequirement` | Some UI typography/size-class polish still device-judged |
| **III** Explicit failure | Unguarded FGS, protect, addView, prefs, startActivity, VPN race | Guards + health signals + honest notifications | Device OEM paths remain `fix-pending-device` |
| **IV** Cross-process honesty | `:a11y` vs default process silent revoke | Refresh intent + Settings re-read on screen/midnight | — |
| **V** Behavior-preserving cleanup | Dead-code inventory with wire-or-delete flags | Safe deletions only; OemBatteryHelper wired not deleted | Unsafe test-class merges deferred (T078 note) |
| **VI** Verification | JVM suite + release assemble required | `testDebugUnitTest` green; `assembleRelease` green; androidTest compile-verified | No device/emulator — FR-024/026 honored via pending-device list |

**Tests run (VI):** `testDebugUnitTest` (JDK 21), `assembleRelease`, `assembleDebugAndroidTest` (compile only).

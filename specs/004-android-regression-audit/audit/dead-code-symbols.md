# Dead-code symbols (T059)

**Denominator**: 68 main-source Kotlin files (T015). Pre-fix baseline: `evidence/warnings.md`.
**Method**: reference-index + direct Grep re-verification (never trust index alone — see index accuracy warning).

| Symbol | Location | References in `app/src/main` | Finding / note | Safe to delete? |
|---|---|---|---|---|
| `OemBatteryHelper` (whole object) | `OemBatteryHelper.kt` | **0** (declaration only) | **F-061** High — wire-up preferred over delete | Delete only if maintainer declines OEM UX |
| `refreshInstalledApps` | `AppUtils.kt:72` | **0** | **F-071** Low | Yes |
| `isPrivateDnsActive` | `AppUtils.kt:154` | **0** | **F-070** Low | Yes |
| `DateUtils.formatTimestamp` | `DateUtils.kt:93` | **0** | **F-072** Low | Yes |
| `DateUtils.getTodayStartMillis` | `DateUtils.kt:129` | **0** | **F-072** Low | Yes |
| `DateUtils.isSameDay` | `DateUtils.kt:113` | **0** main; test-only | **F-072** / **F-199** | Keep if tests stay; else delete with tests |
| `Extensions.millisUntilMidnight` | `Extensions.kt:76` | **0** (live path uses `DateUtils.millisUntilMidnight`) | **F-069** Low duplicate | Yes — keep `DateUtils` copy |
| `SetupRequirements.actionLabel` | `SetupRequirements.kt:45` | **0** main; test-only | **F-073** Low | Wire or delete with tests |
| `Theme.kt` `context` param | `ui/theme/Theme.kt:19` | unused param | **F-163** Low — stale signature, not dead dynamic color | Remove param |
| `updateAccessibilityStatus` | `ProtectionHealthRepository.kt:34` | **0** production writers | Test-only writer; `accessibilityActive` stuck false | Wire from a11y service — **behavior change**, not cleanup |
| Stale ProGuard keep | `proguard-rules.pro:29` `AppAccessibilityService` | Class renamed to `GrayzoneAccessibilityService` | RU-08 Low lead | Replace keep name; not symbol delete |

**Not dead (re-verified):** `DnsTrafficBus` (VpnScreen collectors + AdBlockVpnService emitters), `DateUtils.millisUntilMidnight` (OverlayService), `ProtectionHealthRepository.status` (VpnScreen, MainActivity).

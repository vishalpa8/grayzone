# Dead-code inventory (T064)

**Feature**: `004-android-regression-audit` | **Consolidated**: 2026-08-02
**Denominators**: **68** main-source · **42** JVM test · **3** instrumented (T015).
**Sources**: `dead-code-symbols.md`, `dead-code-resources.md`, `dead-code-flows.md`,
`dead-code-comments.md`, `dead-code-tests.md`.
**Severity cap**: Low for pure cleanup. Items that change protection behavior are **not** cleanup.

## Safe cleanup candidates (Principle I)

| Item | Evidence | Finding | Notes |
|---|---|---|---|
| `refreshInstalledApps` | 0 main refs | F-071 | Delete |
| `isPrivateDnsActive` | 0 main refs | F-070 | Delete |
| `DateUtils.formatTimestamp` / `getTodayStartMillis` | 0 main refs | F-072 | Delete |
| `Extensions.millisUntilMidnight` | Duplicate of `DateUtils` | F-069 | Delete extension; keep DateUtils |
| `Theme.kt` unused `context` | Compiler warning | F-163 | Remove parameter |
| Crashlytics comment block | `GrayzoneLogger.kt:77–80` | F-074 | Delete comments |
| Stale ProGuard `AppAccessibilityService` keep | `proguard-rules.pro:29` | RU-08 | Rename to `GrayzoneAccessibilityService` or drop if R8 keeps via manifest |

## Wire-or-delete (not silent cleanup)

| Item | Why not silent delete | Finding |
|---|---|---|
| `OemBatteryHelper` | 185-line OEM mitigator; deleting closes Principle I but leaves E6 unanswered | F-061 High |
| `SetupRequirements.actionLabel` | Test-only; wire into UI or delete with tests | F-073 |
| `DateUtils.isSameDay` | Test-only | F-072 / F-199 |
| `updateAccessibilityStatus` | Must be wired for F-080 honesty | (gap) |

## Not dead — do not remove

| Item | Why |
|---|---|
| `DnsTrafficBus` | Live VpnScreen collectors |
| `res/raw` bloom filters | Core VPN |
| `UsageDao` unused queries | F-054 — product decision on analytics |
| `backup_rules.xml` | Stale config narrative; confirm before delete |

## Behavior-change flag (Principle V)

Removing `OemBatteryHelper` without a replacement UX changes the *documented* OEM guidance story even
if no caller exists today. Treat as accepted-risk or wire-up, not Tier-Low cleanup in T077.

## Test consolidations (T078 input)

- Rename `CleanupAndLoggingTest` → `GrayzoneLoggerTest` (F-196).
- No whole-class merges proven safe without assertion diff.

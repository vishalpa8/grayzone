# Commented-out code & strategy duplication (T062)

## Principle IV — commented-out blocks

| Site | Content | Finding |
|---|---|---|
| `GrayzoneLogger.kt:77–80` | Commented Firebase Crashlytics integration | **F-074** Low — delete comment block or implement |
| Widespread `//` KDoc/explanatory comments | Not dead code | Out of scope |

No large disabled function bodies found beyond F-074.

## Principle II — duplicated strategy conditionals

| Area | Verdict | Finding |
|---|---|---|
| Overlay presentation selection | Centralised in `OverlayPresentation.resolve()` — credited | F-099 fail-open; F-101 `showsProgress` duplication |
| Session vs lockout gates | Single engine `SessionPolicyEngine.evaluate` | Credited; clock issues are F-110/F-111 not duplication |
| Battery opt prompts | Home + Onboarding duplicate AOSP intent; OEM helper unwired | F-061 |

T062 does not mint new IDs — points at existing findings.

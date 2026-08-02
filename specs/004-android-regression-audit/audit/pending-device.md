# Pending device verification (T083)

**Feature**: `004-android-regression-audit` | **Updated**: 2026-08-02
**Environment**: No device / emulator available. JVM + `assembleRelease` are green; items below need
on-device proof before disposition can move from `fix-pending-device-verification` → `verified`.

| ID | What to verify | Procedure (short) |
|----|----------------|-------------------|
| F-001 | Overlay `addView` failure surfaces honestly | Revoke overlay mid-lock; confirm no silent unlock + health/notification reflects OFF |
| F-020 | VPN `protect` failure does not fail open | Force protect failure / conflicting VPN; UI must not claim filtering on |
| F-080 | A11y revoke updates ongoing notification | Grant a11y, background app, revoke a11y; shade shows "Protection is OFF" without opening Grayzone |
| F-092 | A13+ restricted-settings escape hatch | Sideload on API 33+; revoke a11y; use "Open app info" CTA to reach restricted settings |
| F-096 | Split-screen visibility set | Monitored app in split; focus other pane; session must not pause while Usage Access shows still-resumed |
| F-161 | Large-font list headers | Font scale 1.3+ on Apps/Limits/Vpn; lists remain scrollable with header as first item |
| F-205 | FGS startForeground denial path | Deny notifications / background FGS; services stop cleanly without crash loop |
| F-230 | Split-screen false background | Same as F-096; confirm UsageStats path with usage access granted |
| F-231 | Overlay rebuild on config change | Show lock → rotate / fold; overlay still covers correctly |
| F-233 | TalkBack pane announcement | TalkBack on; show lock; pane titled "Grayzone protection" is announced |
| F-234 | Live-region countdown | TalkBack announces countdown updates without focus trap |
| F-003 / F-035 | Inexact alarm delay honesty | Without exact-alarm grant, under Doze, lock/hold may delay; logs warn; countdown stays non-negative |

See also punch-list Critical/High rows marked **fix-pending-device**.

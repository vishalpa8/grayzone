# Dead-code flows & duplicated fetches (T061)

| Item | Verdict | Evidence |
|---|---|---|
| `DnsTrafficBus.events` | **Live** — collected in `VpnScreen` (`collectAsState` ×2); emitted from `AdBlockVpnService`; `clear()` from VPN teardown | Not orphaned (reference-index previously wrong) |
| `ProtectionHealthRepository.status` | **Live** — VpnScreen, MainActivity reconcile | Live |
| `ProtectionHealthRepository.updateAccessibilityStatus` | **Orphan writer** — no production caller | `accessibilityActive` permanently false; wire-up is a fix (F-080 cluster), not deletion |
| `UsageDao` analytics queries | **8–9 methods with no main-source caller** | **F-054** Low — built + instrumented-tested, never surfaced in UI |
| Duplicate streak/session stats | Prefs `total_sessions_blocked` vs `UsageDao.getTotalSessionsBlocked` | prefs-inventory E3-c — divergence risk, not dead flow |
| Compose re-fetch on every resume | Theme/prefs re-read on ON_RESUME | Intentional; not duplicate-fetch waste |

**Principle I removals from this file:** none recommended without product decision on analytics (F-054).

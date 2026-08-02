# Dead-code resources (T060)

**Cross-check**: RU-09 findings + release packaging notes. Full `resources.txt` shrinker log not re-parsed here; claims cite F-175…F-183.

| Resource | Status | Evidence | Action |
|---|---|---|---|
| `res/values/strings.xml` (2 strings) | **Live** — XML-only consumers; zero `stringResource` in Kotlin | F-175 | Do not delete; externalize Compose strings instead (behavior/i18n change) |
| Launcher mipmap density ladder | **Live** but shadowed by adaptive icon | F-181 — 69 KB redundant PNGs | Candidate shrink; verify Play/launcher still OK |
| `res/raw` bloom `.bin` | **Live** — VPN blocklists, 10 MiB, STORED | F-027, F-179 | Not dead — mmap candidate |
| `res/layout/notification_countdown.xml` | **Live** | F-177 | Keep; fix overflow |
| `backup_rules.xml` | **Unreachable** with `allowBackup=false` | RU-08 | Stale config, not unused resource file — document or remove with care |
| `values-night` / `values-*` locales | **Absent** | F-160 cluster | Missing, not dead |

No unreferenced drawable/mipmap name was newly proven deletable without device icon checks. Inventory capped at Low / config-stale.

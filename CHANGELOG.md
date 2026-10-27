# Changelog

All notable changes to Grayzone. Versions follow [semantic versioning](https://semver.org);
the number lives in `version.properties` and each release is tagged `vX.Y.Z`.

VPN blocklists are versioned separately (a UTC timestamp such as `202610270634`) and update
on the phone by themselves; see Settings → VPN settings → Blocklists.

## [1.1.2] - 2026-10-27

### Fixed
- Exported logs no longer repeat an "exact alarms unavailable" warning on every VPN start and
  session; the inexact alarm it described is the intended behaviour.

## [1.1.1] - 2026-10-27

### Changed
- Rotated the blocklist signing key.
- The blocklist workflow accepts the signing key in any paste format and refuses to publish
  with a key the app doesn't trust.

## [1.1.0] - 2026-10-27

### Added
- Malware, phishing and scam protection in the VPN: a 2.4-million-domain threat list, checked
  before the ad and adult lists and shown as `THREAT` in live traffic.
- Threat-filtering upstream DNS (Quad9, Cloudflare 1.1.1.2) with no unfiltered fallback.
- CNAME uncloaking: an allowed name that aliases a known threat host is blocked.
- Self-updating blocklists: rebuilt and signed daily on GitHub, verified and installed by the
  app over Wi-Fi with no restart; any failure keeps the current lists.
- Encrypted-DNS resolver and vendor telemetry lists from maintained feeds.
- Protected names that no list can block (banks, UPI, Google, WhatsApp and similar).
- Version, build number and commit in Settings and exported logs; blocklist set in both.

### Changed
- Domains on your Allow list resolve through unfiltered DNS, so Allow always works.
- Lists are sized per domain count, cutting false positives to about 1 in 11,000.

## [1.0.0] - 2026-10-27

First production release: monitored-app sessions with wait screen and timers, schedules and
limits, the 24-hour VPN with ad, tracker and adult blocking, split tunneling, custom rules,
guided permissions, structured logging with Export logs, and release signing.

# Changelog

All notable changes to Grayzone. Versions follow [semantic versioning](https://semver.org);
the number lives in `version.properties`. Pushing a new version to `main` makes GitHub Actions
publish it as release `vX.Y.Z`, using the section below as the release notes.

VPN blocklists are versioned separately (a UTC timestamp such as `202610270634`) and update
on the phone by themselves; see Settings → VPN settings → Blocklists.

## [1.3.1] - 2026-10-28

### Fixed
- Slow browsing with the VPN on. DNS now asks the fastest server on your network first
  (Cloudflare answered in ~20 ms where Quad9 took 300+ ms), and if it is slow to answer, the next
  server is asked too and the first reply wins, so a lost packet costs ~100 ms instead of 2 s.
- Cached DNS answers follow their own lifetime (30 s to 10 min) instead of a fixed 10 minutes.
- More lookups can run at once, so busy pages don't queue.

## [1.3.0] - 2026-10-28

### Changed
- Grayzone no longer uses Accessibility. It now sees which app is open through Usage access,
  which never reads screen content. This removes the system-wide slowdown Accessibility caused,
  avoids Play Protect's block on sideloaded installs, and simplifies Play Store review.
- After updating, turn on Usage access for Grayzone when asked; the old Accessibility switch is
  no longer needed.

### Removed
- The Force stop / Uninstall page guard, which required Accessibility.

## [1.2.0] - 2026-10-27

### Added
- In-app updates: Settings → App updates checks GitHub for a new version, shows what changed,
  downloads it (checksum, version and signing key verified) and opens the installer. A daily
  background check sends a notification when a new version is out.
- Automated releases: every push builds and tests the app; bumping the version publishes a
  signed release that phones pick up.

### Changed
- Two editions from one codebase: the GitHub build updates itself, the Play build leaves
  updates to the Play Store (no install permission).

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

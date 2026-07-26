# Internal Contract: Reliable Session Expiry & Countdown Clamp

**Scope**: Internal (in-app) contract between `OverlayService`, the new `SessionExpiryScheduler` /
`SessionExpiryReceiver`, and the pure `SessionExpiryDecision`. No public API / no external interface.

## Purpose

Guarantee that when a monitored app's session reaches `activeUntil`:
1. the ongoing countdown **notification never displays a negative value**, and
2. the app is **locked** when it is (or next becomes) foreground,

reliably across screen-off, Doze, and process death — without polling.

## Producers / Consumers

- **Scheduler (`SessionExpiryScheduler`)**: arms/cancels a per-package Doze-safe alarm at
  `activeUntil`. Re-armed by `OverlayService.onCreate()`/`onStartCommand()` and `BootReceiver` from
  persisted `ACTIVE_UNTIL`/`LOCKED_UNTIL`.
- **Receiver (`SessionExpiryReceiver`)**: on alarm fire, reads state and delegates to
  `SessionExpiryDecision`, then applies the result via `OverlayService`.
- **Decision (`SessionExpiryDecision`)**: pure function (see data-model.md §2).

## Behavioral contract

| ID | Given | When | Then |
|----|-------|------|------|
| SE-1 | Active session with `activeUntil = T` | `now >= T` and monitored app is foreground and `now < lockedUntil` | Countdown notification is cleared (no negative) AND `OverlayMode.LOCK` overlay is shown until `lockedUntil` |
| SE-2 | Active session with `activeUntil = T` | `now >= T` and monitored app is **not** foreground | Countdown notification is cleared/clamped to `00:00`; no overlay shown now; lock is applied on next foreground via the existing policy path |
| SE-3 | Active session | `now < T` (not yet expired) | `NoOp`; countdown continues toward `00:00` |
| SE-4 | Session expired while device asleep/Doze | Device resumes / alarm delivered in maintenance window | SE-1 or SE-2 applied; notification never left showing negative |
| SE-5 | Process killed mid-session, then `OverlayService` restarts (`START_STICKY`) or device boots | Service `onCreate`/`BootReceiver` runs | Alarm is **re-armed** from persisted `ACTIVE_UNTIL`; if already past, SE-1/SE-2 applied immediately |
| SE-6 | Global kill switch off, or daily break active | Alarm fires | `NoOp` (no lock, no negative timer) |
| SE-7 | Session paused (backgrounded) before expiry | — | Pending alarm is cancelled; pause/resume behavior unchanged |

## Display invariant

Remaining time rendered anywhere for a session is `max(0, activeUntil - now)`. The countdown
notification MUST NOT rely on a free-running count-down `Chronometer` past `activeUntil`; it is
cancelled/replaced at expiry.

## Error handling (Constitution III)

- AlarmManager `set*`/`cancel` and `NotificationManager` calls are wrapped with explicit, logged
  failure handling (component `OVERLAY`); failures never crash the service and never silently drop
  enforcement — a failed exact-alarm attempt falls back to the Doze-safe inexact path.
- If overlay permission is revoked at fire time, the existing `showOverlay` guard/logging path is
  reused (no new silent catch).

## Verification

- `SessionExpiryDecisionTest` (JVM) covers SE-1/2/3/6 and the clamp invariant deterministically.
- SE-4/SE-5/SE-7 are validated on-device per `quickstart.md`.

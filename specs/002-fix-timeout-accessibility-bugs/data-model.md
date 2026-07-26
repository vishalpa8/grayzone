# Phase 1 Data Model: Fix Session Timeout Lock & Accessibility Permission Stability

This feature adds no database schema. It reuses existing `SharedPreferences` (`GrayzonePrefs`) keys
for session timing and adds a small amount of persisted state for the accessibility prompt-once
behavior. The "model" here is the in-memory state the two pure decision units operate on.

---

## 1. Session timing state (existing — reused, not changed in shape)

Per monitored package `pkg`, read/written through `PrefsKeys`:

| Key | Meaning | Used by this feature |
|-----|---------|----------------------|
| `ACTIVE_UNTIL + pkg` | Epoch millis when the active session ends | **Alarm trigger time**; expiry decision input |
| `LOCKED_UNTIL + pkg` | Epoch millis when the post-session lockout ends | Passed to the lock overlay on expiry |
| `REMAINING_MILLIS + pkg` | Paused remaining session time | Unchanged (pause/resume path) |
| `MONITORED_APPS` | Set of monitored package names | Which packages to (re)arm alarms for |
| `GRAYZONE_ENABLED` | Global kill switch | Suppress enforcement when disabled |

No new session-timing keys are required: `ACTIVE_UNTIL`/`LOCKED_UNTIL` already fully describe when a
session expires and how long the subsequent lock lasts, so alarms can be (re)armed purely from
persisted state after a restart or boot.

---

## 2. `SessionExpiryDecision` (NEW — pure, unit-tested)

A stateless decision function that the alarm callback and service-start re-arm path call to decide
what to do at/after a session boundary. No Android dependencies.

**Inputs**

| Field | Type | Description |
|-------|------|-------------|
| `now` | `Long` | Current epoch millis |
| `activeUntil` | `Long` | Session end (0 if none) |
| `lockedUntil` | `Long` | Lockout end |
| `isForegroundPackage` | `Boolean` | Whether the monitored app is currently foreground |
| `isGrayzoneEnabled` | `Boolean` | Global kill switch |
| `isOnBreak` | `Boolean` | Daily break active |

**Output** (sealed result)

| Result | When | Effect at call site |
|--------|------|---------------------|
| `NoOp` | Disabled/on break, or session still has time (`now < activeUntil`) | Do nothing |
| `ClampCountdown` | Session ended (`now >= activeUntil`) but app not foreground | Cancel/replace countdown notification so it shows `00:00`, not negative |
| `ShowLock` | Session ended and app is foreground and `now < lockedUntil` | Clamp countdown **and** show `OverlayMode.LOCK` until `lockedUntil` |

**Invariants**
- Displayed remaining time is `max(0, activeUntil - now)` — **never negative** (clamp rule).
- `ShowLock` implies the countdown notification is cleared (no negative timer behind the lock).
- Pure and deterministic → fully covered by `SessionExpiryDecisionTest`.

---

## 3. `AccessibilityPromptState` (NEW — pure, unit-tested) + persisted flag

Encapsulates the prompt-once-per-loss rule so the UI never loops.

**Persisted key (NEW in `PrefsKeys`)**

| Key | Type | Meaning |
|-----|------|---------|
| `ACCESSIBILITY_PROMPTED_FOR_LOSS` | `Boolean` | True once the user has been prompted for the *current* loss; reset to false when the permission is next observed granted |

**State transition (pure function `next(state, event)`)**

| Current | Event | Next | Emit prompt? |
|---------|-------|------|--------------|
| granted (`prompted=false`) | observed **enabled** | granted | no |
| granted | observed **disabled** | lost, `prompted=false` | **yes (first prompt)** → set `prompted=true` |
| lost, `prompted=true` | observed **disabled** (resume again) | lost, `prompted=true` | **no** (already prompted) |
| lost (any) | observed **enabled** | granted, `prompted=false` | no (reset for future loss) |

**Invariants**
- At most **one** automatic prompt per contiguous period of the permission being disabled.
- Re-granting always resets, so a *future* genuine loss prompts again exactly once.
- Deterministic → covered by `AccessibilityPromptStateTest`.

---

## 4. Relationships / flow

```text
AlarmManager (activeUntil)  ──fires──▶  SessionExpiryReceiver
                                            │ reads ACTIVE_UNTIL/LOCKED_UNTIL + foreground pkg
                                            ▼
                                     SessionExpiryDecision  ──▶ {NoOp | ClampCountdown | ShowLock}
                                            │
                                            ▼
                              OverlayService: cancel/clamp notification, (optionally) show LOCK

Service (re)start / BootReceiver  ──re-arm──▶  SessionExpiryScheduler  ──▶ AlarmManager per active pkg

Accessibility settings changes  ──ON_RESUME observe──▶  AccessibilityPromptState.next(...)
                                            │
                                            ▼
                        prompt user at most once per loss (reset on re-grant)
```

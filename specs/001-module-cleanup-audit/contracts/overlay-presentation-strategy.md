# Internal Contract: OverlayPresentation Strategy

**Feature**: `001-module-cleanup-audit`
**Type**: Internal (in-module) contract — not a public API. No externally-visible signatures change.

This contract defines the strategy abstraction that consolidates the duplicated `OverlayMode`
branching in `OverlayService.showOverlay()`. It is internal to the `service/` module; consumers
outside the module continue to interact with `OverlayService` and `OverlayMode` exactly as before.

## Purpose

Provide a single decision owner for per-mode overlay presentation and countdown-completion behavior,
so that (a) no call site re-derives the mode discriminator, and (b) adding a mode requires adding a
strategy rather than editing conditionals.

## Interface (behavioral contract)

```text
interface OverlayPresentation {
    val title: String
    val skipButtonLabel: String
    val showsProgress: Boolean

    fun bodyText(prompt: String): String
    fun countdownText(secondsRemaining: Int): String
    fun subText(secondsRemaining: Int): String
    fun initialSeconds(inputs: SecondsInputs): Int
    fun onCountdownFinished(ctx: CountdownFinishContext)
}
```

Supporting value inputs (module-internal):

```text
SecondsInputs        // lockedUntil, custom/global wait seconds, hasCustom
CountdownFinishContext // packageName, appName, callbacks: dismissOverlay(),
                       // markAppUnlocked(pkg), reShowFriction(pkg, appName), stillForeground(pkg)
```

## Resolution contract

- `resolve(mode: Int): OverlayPresentation` MUST map each supported `OverlayMode` value to exactly one
  implementation:
  - `OverlayMode.FRICTION` → `FrictionPresentation`
  - `OverlayMode.LOCK` → `LockPresentation`
  - `OverlayMode.BUDGET_LOCK` → `BudgetLockPresentation`
  - `OverlayMode.SCHEDULE_LOCK` → `ScheduleLockPresentation`
- `OverlayMode.TINT` / `OverlayMode.REMOVE_TINT` are NOT valid inputs to `showOverlay()`/`resolve()`
  (tint is handled by `showTint()`/`dismissTint()`); they remain flagged-only and untouched.

## Behavior-equivalence requirements (must hold exactly)

For every mode, the resolved strategy MUST reproduce the current observable output byte-for-byte:

| Aspect | FRICTION | LOCK | BUDGET_LOCK | SCHEDULE_LOCK |
|--------|----------|------|-------------|---------------|
| `title` | `Pause.` | `Locked.` | `Locked.` | `Locked.` |
| `bodyText` | `"<prompt>"` (quoted) | `You have reached your session limit.` | `Daily usage limit reached.` | `Focus mode / schedule is active.` |
| `countdownText` | `"$seconds"` | `formatDuration(seconds)` | `—` | `—` |
| `subText` | `Opening in $seconds seconds…` | `Remaining lockout time` | `Resets tomorrow` | `Try again later` |
| `skipButtonLabel` | `← I'll Skip This` | `← Leave App` | `← Leave App` | `← Leave App` |
| `showsProgress` | `true` | `false` | `false` | `false` |
| `initialSeconds` | custom/global wait | `(lockedUntil-now)/1000 ≥ 0` | `0` | `0` |
| `onCountdownFinished` | `markAppUnlocked` + `dismissOverlay` | `dismissOverlay` + re-show FRICTION if still foreground | (no countdown) | (no countdown) |

Side effects that MUST remain in `OverlayService` (not moved into strategies): overlay permission
check, `dismissOverlay()` before rebuild, `cancelCountdownNotification()` on lock modes,
`showTint()`/`dismissTint()`, `recordFrictionSkip()` on FRICTION skip, and window add/remove.

## Preserved error handling

- All `try/catch` around `windowManager.addView/removeView`, DB writes, and settings intents remain
  unchanged (Principle III). The strategy carries no I/O and introduces no new catch blocks.

## Verification

- `OverlayModeTest` (existing) MUST stay green — constants unchanged.
- The refactor MUST keep the full `./gradlew :app:testDebugUnitTest` suite green (Principle VI).
- Optional: a new unit test may assert `resolve(mode)` returns the correct strategy and that each
  strategy's text/flags match the table above; this is additive and does not weaken existing tests.

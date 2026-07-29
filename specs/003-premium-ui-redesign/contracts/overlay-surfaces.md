# Contract: Overlay Surfaces

Visual + behavior boundary for friction and lock overlays drawn by `OverlayService`.

## OS-1 Behavior ownership (unchanged)

- Per-mode title, body, countdown formatting, progress visibility, skip label, and completion/skip
  effects MUST continue to come from `OverlayPresentation` strategies via `resolve(mode)`.
- Modes: Friction, Lock, Budget lock, Schedule lock — same mapping as today.
- Existing unit tests for presentation behavior remain the oracle; redesign MUST NOT change their
  expected copy/countdown/skip semantics unless a separate behavior feature authorizes it.

## OS-2 Visual chrome (redesign in scope)

Overlay chrome MUST use light-first roles aligned with the app visual system:

| Element | Requirement |
|---------|-------------|
| Root background | Light product background (not near-black) |
| Content card / surface | Light elevated surface |
| Title / body / subtext | High-contrast ink on light |
| Accent / progress / primary actions | Restrained teal/sage accent |
| Destructive emphasis (if any) | Semantic error role, restrained |

Overlays MUST NOT reintroduce the pre-redesign dark/purple gamer aesthetic.

## OS-3 Completeness during protection use

- A user who hits friction or lockout MUST experience overlays that feel part of the same redesigned
  product (spec FR-010 / SC-008).
- Timing, lock durations, and skip recording behavior MUST match pre-redesign behavior.

## OS-4 Strategy pattern integrity

- Call sites MUST NOT re-derive overlay mode presentation with new `when`/`if` branches once
  `OverlayPresentation` owns that decision (Constitution II).
- Visual tweaks that are mode-specific and behavioral belong in the strategy; pure color constants
  shared across modes may live beside overlay chrome helpers — without duplicating mode resolution.

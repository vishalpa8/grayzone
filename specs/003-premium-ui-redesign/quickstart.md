# Quickstart & Validation: Premium Lightweight UI Redesign

Validate that the redesign is light, calm, and fast-feeling while **preserving** protection behavior.
Combine automated regression tests with on-device visual checks.

## Prerequisites

- Repo on branch `003-premium-ui-redesign` (or working tree implementing this feature).
- Android SDK + JDK; device or emulator on Android 10+.
- At least one monitored app configured; ability to trigger friction/lock overlays.
- Optional: side-by-side build or screenshots of pre-redesign UI for SC-001 comparison.

## 1. Automated regression (must be green)

```bash
./gradlew :app:testDebugUnitTest
```

Expected:
- All pre-existing unit tests pass (Constitution VI; behavior preservation).
- `OverlayPresentationTest` still passes — overlay **behavior** unchanged ([overlay-surfaces.md](./contracts/overlay-surfaces.md) OS-1).

## 2. Visual system checklist (VS contracts)

On device, open the app with system dark mode **on** and **off**:

| Check | Expected |
|-------|----------|
| Home / scaffold background | Light surfaces, not near-black |
| Status / nav bar icons | Dark-on-light (light appearance) |
| Accent usage | Teal/sage used sparingly on actions / selected tab |
| Gradients / glow washes | Absent or non-competing |
| Text contrast | Primary copy readable outdoors / bright light |

Maps to [visual-system.md](./contracts/visual-system.md) VS-1–VS-3 and spec SC-001 / SC-007 / FR-011.

## 3. Destination walkthrough (PD contracts)

Visit each tab and confirm primary job + capability preservation:

1. **Home** — status clear in &lt;5s; missing permission is the dominant CTA when applicable (SC-002).
2. **Apps** — toggle monitoring; list is scannable, light rows (SC-003 task 1, SC-006).
3. **VPN** — toggle DNS blocker; status readable (SC-003 task 3).
4. **Limits** — session/lockout info still present and understandable.
5. **Stats** — summaries readable; empty state calm if no data.
6. **Settings** — change wait/session slider (SC-003 task 2); commitment lock still works visually.

Maps to [primary-destinations.md](./contracts/primary-destinations.md) and SC-003 / SC-009.

## 4. Perceived performance

| Check | Expected |
|-------|----------|
| Cold start → first useful Home content | Feels snappy; aim &lt;2s useful content (SC-004) |
| Warm tab switches | Immediate structure; no multi-second blank (SC-005) |
| Scroll Apps / Stats | Smooth; no sustained decoration jank (SC-006) |

## 5. Overlay cohesion (OS contracts)

1. Open a monitored app → complete friction wait.
2. Optionally run a short session to lockout (or trigger budget/schedule lock if configured).

Expected:
- Overlay surfaces are **light**, legible, accent-aligned — not the old dark/purple look (SC-008).
- Countdown / skip / lock timing behave as before (OS-1 / OS-3).
- `OverlayPresentation` tests still green.

## 6. Dialogs & locked controls

- Open commitment PIN / destructive confirms → light minimal dialogs (FR-009).
- With commitment lock active → disabled settings look intentional, not broken (edge case).

## 7. Success mapping

| Spec criterion | Validated by |
|----------------|--------------|
| SC-001 lighter/cleaner/less dark | §2 evaluator comparison |
| SC-002 Home status &lt;5s | §3 Home |
| SC-003 core tasks | §3 Apps / Settings / VPN |
| SC-004 cold start | §4 |
| SC-005 tab switch | §4 |
| SC-006 scroll | §3–4 |
| SC-007 restrained accent | §2 |
| SC-008 overlay identity | §5 |
| SC-009 no capability loss | §3 + FR-015 spot check |
| Behavior preservation | §1 + §5 timing |

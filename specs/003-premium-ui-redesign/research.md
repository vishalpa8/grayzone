# Phase 0 Research: Premium Lightweight UI Redesign

Resolves design unknowns for the light-first redesign. Evidence from current `ui/theme`, screens, and
`OverlayService` overlay chrome.

---

## R1. Theme polarity: light-first vs dual theme

**Decision**: Ship a **single light theme** as the product appearance for this feature. Force light
surfaces even when the system is in dark mode. Defer a full dark theme.

**Rationale**: Spec Assumptions and FR-001 explicitly require light-first and call out the current
UI as “too dark.” Dual theming doubles QA and risks reintroducing dark-dominant density. Window
insets should use light status/navigation bar appearance (`isAppearanceLightStatusBars = true`).

**Alternatives considered**:
- *Follow system light/dark*: Rejected for v1 — conflicts with “escape the dark UI” goal.
- *Light default + optional dark toggle*: Deferred; expands scope without solving the stated pain.

---

## R2. Color direction (roles and accent)

**Decision**: Replace the near-black + electric violet system (`GZBackground 0xFF07070F`,
`GZPrimary 0xFF7C4DFF`, purple-tinted borders/glows) with:

| Role | Direction |
|------|-----------|
| Background | Soft neutral off-white / warm-cool gray (~paper), not pure `#FFFFFF` glare |
| Surface | Slightly elevated white/cream for cards/sheets |
| Text primary / secondary | Near-ink charcoal + muted slate (WCAG-friendly on light) |
| Accent | One restrained **teal/sage** for primary actions and selected nav (evolve from existing `GZAccent`, not violet) |
| Success / warning / destructive | Semantic greens / ambers / reds, muted containers — never full-bleed washes |
| Borders | Neutral hairlines; no purple glow borders |

**Rationale**: Spec asks for calm wellbeing color use and restrained accents. Current purple-on-black
reads “gamer / neon,” not premium calm. Teal/sage preserves a thread of brand familiarity from the
existing accent while leaving violet gradients and glow tokens behind.

**Alternatives considered**:
- *Keep violet as accent on light*: Possible but fights “right usage of colors” and the crowded
  purple-AI aesthetic; rejected as primary accent.
- *Multi-accent rainbow charts unchanged*: Soften chart palette for light backgrounds; keep
  distinct series colors but lower saturation.

---

## R3. Visual density and layout pattern

**Decision**: Declutter by **information hierarchy**, not by removing destinations:

1. One primary purpose above the fold per destination.
2. Prefer flat sections + list rows over stacked elevated cards with borders/gradients.
3. Remove competing decorative gradients, glow washes, and redundant status chrome that do not drive
   an action.
4. Keep six-tab navigation; restyle the bar for light surfaces and clearer selected state.

**Rationale**: Spec FR-004 / FR-012 and User Story 2. Users still need Apps / VPN / Limits / Stats /
Settings (FR-005 / FR-015). Density is a layout problem, not an IA deletion problem.

**Alternatives considered**:
- *Collapse to fewer tabs*: Rejected for this feature — capability preservation (FR-015).
- *Card-heavy Material “dashboard”*: Rejected — matches the current “messy” feel.

---

## R4. Shared tokens vs one-off colors

**Decision**: All Compose screens and dialogs consume **theme role tokens** (`Color.kt` +
`MaterialTheme.colorScheme` / typography). Ban new hard-coded one-off hex colors in screens except
temporary migration leftovers that are deleted before merge. Overlay View constants in
`OverlayService` mirror the same role hex values (documented in contracts) because overlays are
classic Views, not Compose.

**Rationale**: Cohesion (User Story 3) and Constitution II-style single decision point for visual
variation. Today screens import many `GZ*` constants and also layer brushes; overlays duplicate dark
hex in companion constants (`BG`, `SURFACE`, `PURPLE`, …).

**Alternatives considered**:
- *Compose overlays*: Out of scope — high risk to protection UX; visual sync of View colors is enough.
- *XML color resources only*: Optional later; Kotlin theme tokens already own Compose.

---

## R5. Perceived performance without engine rewrites

**Decision**: Meet SC-004–SC-006 primarily by:

- Fewer layered brushes / full-screen gradients
- Simpler list rows (less per-item decoration)
- Avoiding heavy recomposition work on tab switch (keep existing tab `when` structure; do not add
  unnecessary nested animated stacks)
- Keeping data loading paths as they are unless a screen clearly blocks the UI thread for prefs/UI
  chrome (fix only if measured)

**Rationale**: Spec Assumptions: perceived lightness first; deep VPN/policy rewrites out of scope.

**Alternatives considered**:
- *Rewrite navigation with animated NavHost*: Optional polish later; not required for v1 snappiness.
- *Lazy-load every tab with placeholders*: Can feel slower; prefer instant light structure.

---

## R6. Overlay and notification cohesion

**Decision**: Refresh overlay chrome (background, surface card, text, accent, progress, buttons) to
light-first roles matching the app. Keep `OverlayPresentation` strategy API and copy/countdown/skip
behavior unchanged (existing `OverlayPresentationTest` remains oracle). Notification layout may get
light-compatible colors if it clashes; not a primary focus.

**Rationale**: Spec FR-010 / SC-008 — redesign must feel complete during real friction/lock use.
Current overlay constants are the same near-black/purple family as Compose.

**Alternatives considered**:
- *Leave overlays dark*: Rejected — jarring identity break.
- *Change overlay copy or timing*: Rejected — behavior change out of scope.

---

## R7. Contrast and readability

**Decision**: Primary body and titles must meet readable contrast on light surfaces (target WCAG AA
for normal text). Secondary text may be softer but not below usable contrast for status that drives
action. Semantic warning/error text uses semantic colors on light containers, not neon on black.

**Rationale**: Spec FR-011 and outdoor readability edge case.

---

## Resolved clarifications

No remaining `NEEDS CLARIFICATION` items in Technical Context. Defaults above are locked for
planning and implementation unless stakeholders reopen theme polarity or accent choice in
`/speckit-clarify`.

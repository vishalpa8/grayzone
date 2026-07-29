# Contract: Visual System

UI contract for Grayzone’s light-first theme. Implementers MUST satisfy these rules across Compose
screens, dialogs, and overlay View chrome.

## VS-1 Light-first surfaces

- App background and primary surfaces MUST be light (off-white / soft neutral), not near-black.
- Status and navigation bars MUST use light appearance (dark icons on light chrome).
- System dark mode MUST NOT switch Grayzone into a dark product theme for this feature.

## VS-2 Color roles

The product MUST expose and use these roles consistently:

| Role | Allowed use |
|------|-------------|
| Background / surface | Large fills only |
| Primary text / secondary text | Content hierarchy |
| Accent (teal/sage) | Primary buttons, selected nav, key links — **sparingly** |
| Success / warning / error | Status and destructive only |
| Outline | Hairline separators |

MUST NOT: large accent or gradient washes as screen backgrounds; purple glow borders; neon-on-black
semantic colors.

## VS-3 Typography & spacing

- Titles, body, and labels MUST follow the shared type scale.
- Screen padding and section gaps MUST feel generous relative to the pre-redesign dense cards.
- Critical status text MUST meet readable contrast on light surfaces (target WCAG AA for normal text).

## VS-4 Token source of truth

- Compose UI MUST read colors/type from `ui/theme` (or `MaterialTheme`), not ad-hoc hex sprawl.
- Overlay View color constants MUST mirror the same role values (documented beside theme tokens).
- After migration, unused dark-only tokens MUST be removed.

## VS-5 Motion & decoration

- Motion, if any, MUST be subtle and purposeful (state change feedback), not continuous glow or
  heavy parallax.
- Decorative brushes/gradients MUST NOT compete with primary content or CTAs.

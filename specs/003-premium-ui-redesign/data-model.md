# Phase 1 Data Model: Premium Lightweight UI Redesign

This feature adds **no database or SharedPreferences schema**. The “model” is the shared **visual
system** and the UI state presentations each destination must support. Protection entities
(sessions, locks, VPN, commitment lock) are reused unchanged.

---

## 1. Visual System (theme roles)

Logical tokens consumed by Compose and mirrored by overlay View constants.

| Role | Purpose | Validation |
|------|---------|------------|
| `background` | App chrome / scaffold fill | Light; not near-black |
| `surface` | Cards, sheets, nav bar, dialogs | Light elevated vs background |
| `surfaceVariant` | Subtle secondary panels / list zebra optional | Still light |
| `onBackground` / `onSurface` | Primary text | High contrast on light |
| `onSurfaceVariant` | Secondary text / captions | Readable; not decorative-only |
| `primary` (accent) | Primary actions, selected nav | Single restrained teal/sage |
| `onPrimary` | Text/icons on accent | Sufficient contrast |
| `outline` / `outlineVariant` | Dividers, borders | Neutral hairline |
| `error` / containers | Destructive / failure | Semantic; not full-bleed |
| `success` | Protected / healthy status | Semantic green (custom role or mapped) |
| `warning` | Attention / battery / partial setup | Semantic amber |
| Typography scale | display / title / body / label | Clear hierarchy; default family OK |
| Spacing rhythm | Screen padding, section gaps, row height | Generous; consistent |

**Invariants**
- Accents are not used as large background washes (spec FR-003 / FR-012).
- One accent family for interactive emphasis across destinations.
- Dark-only tokens (`GZGradPurple*`, glow washes, near-black backgrounds) are removed after migration.

---

## 2. Primary Destination

| Field | Description |
|-------|-------------|
| `id` | `HOME` \| `APPS` \| `VPN` \| `LIMITS` \| `STATS` \| `SETTINGS` |
| `label` | Nav label (unchanged set) |
| `primaryJob` | Single above-the-fold purpose (see contracts) |
| `secondaryContent` | Visually subordinate sections |

**Relationships**: Hosted by `MainActivity` scaffold; all remain reachable after redesign.

---

## 3. Status Affordance

Compact protection / setup signal (especially Home).

| Field | Description |
|-------|-------------|
| `state` | `ready` \| `needsSetup` \| `warning` \| `locked` (presentation-level) |
| `headline` | Short status phrase |
| `action` | Optional primary CTA (grant permission, open settings, etc.) |
| `emphasis` | Uses success/warning/accent — never competing gradients |

**Invariants**: When `needsSetup` or `warning`, the affordance outranks decorative content (spec US1).

---

## 4. Empty / Guidance State

| Field | Description |
|-------|-------------|
| `context` | e.g. no monitored apps, no stats yet |
| `message` | Calm, instructive copy |
| `action` | Optional next step |

**Invariants**: Non-alarmist; light surface; no dark empty void chrome.

---

## 5. Overlay Surface (presentation vs chrome)

| Concern | Owner | Redesign impact |
|---------|-------|-----------------|
| Copy, countdown, skip, completion | `OverlayPresentation` strategies | **Unchanged behavior** |
| Background, card, typography colors, progress tint | `OverlayService` View chrome | **Restyle to light roles** |
| Mode resolution | `OverlayPresentation.resolve` | Unchanged |

Modes (unchanged): Friction, Lock, Budget lock, Schedule lock.

---

## 6. State presentations (per screen, no new persistence)

Screens must still render existing runtime states with new visuals:

| State | Example surfaces |
|-------|------------------|
| Permission missing | Home status CTA |
| Commitment locked | Settings / VPN controls disabled appearance |
| VPN on/off / error | VPN destination |
| Empty lists | Apps / Stats |
| Active session / lockout timers | Limits (data unchanged) |

No new entities are persisted for the redesign.

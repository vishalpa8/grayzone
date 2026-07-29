# Contract: Primary Destinations

Navigation and per-tab UI contracts. Behavior of underlying features is unchanged; this contract
governs what users can reach and how each destination presents its job.

## PD-1 Destination set

These destinations MUST remain reachable from the main shell with clear labels:

| Id | Label | Primary job (above the fold) |
|----|-------|------------------------------|
| HOME | Home | Protection / setup status + next action |
| APPS | Apps | Choose which apps are monitored |
| VPN | VPN | Enable/disable DNS blocker + status |
| LIMITS | Limits | See session / pause / lockout state |
| STATS | Stats | Review usage summaries |
| SETTINGS | Settings | Adjust friction, sessions, and app preferences |

Removing or hiding any destination is **out of contract** for this feature (spec FR-015).

## PD-2 Hierarchy

- Each destination MUST make its primary job obvious within a few seconds of landing.
- Secondary sections (streaks, break cards, advanced stats, etc.) MUST be visually subordinate.
- Empty and error states MUST explain the next step calmly (see data-model Empty / Guidance State).

## PD-3 Interaction feedback

- Toggles, sliders, and list selection MUST provide immediate visual feedback.
- Ordinary preference changes MUST NOT freeze the UI for multiple seconds.
- Commitment-lock disabled controls MUST look intentionally locked (not broken), with clear affordance
  to unlock where the product already allows it.

## PD-4 Navigation chrome

- Bottom navigation MUST remain discoverable on light surfaces with a clear selected state using the
  accent role.
- Tab switches MUST feel immediate (structure of the destination visible without multi-second blank).

## PD-5 Capability preservation

- All pre-redesign user-facing controls on these destinations MUST remain available (relayout /
  restyle allowed; silent removal is a contract breach).

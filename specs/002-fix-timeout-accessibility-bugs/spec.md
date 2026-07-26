# Feature Specification: Fix Session Timeout Lock & Accessibility Permission Stability

**Feature Branch**: `002-fix-timeout-accessibility-bugs`

**Created**: 2026-07-26

**Status**: Draft

**Input**: User description: "There is two bugs on the application. After completion of the application timeout it goes to a negative counter — e.g. if I set a 10-minute limit, the counter counts down and then keeps counting into negative and does not lock the application. The second bug is on Accessibility Permissions: the permission is automatically revoked and the app asks for it again and again."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Session locks when the time limit is reached (Priority: P1)

As a user who has set a time limit for a monitored app, when my allotted time runs out I want the
app to be locked immediately and the countdown to stop at zero, so that the time limit is actually
enforced and I cannot keep using the app past my quota.

**Why this priority**: This is the core promise of the product — enforcing a time budget. Today the
countdown runs past zero into negative values and the lock never triggers, so the primary protection
silently fails. Fixing this restores the feature's fundamental value.

**Independent Test**: Set a short time limit (e.g. 1 minute) on a monitored app, use the app until
the timer reaches zero, and confirm the countdown stops at `00:00` (never shows negative) and the
locking behavior activates so the app can no longer be used.

**Acceptance Scenarios**:

1. **Given** a monitored app has a 10-minute limit and has been used for the full 10 minutes,
   **When** the countdown reaches `00:00`, **Then** the countdown stops at `00:00` and does not
   display or continue into negative values.
2. **Given** the countdown has reached `00:00`, **When** the limit is hit, **Then** the app is
   locked (the enforcement/blocking state is shown) preventing further use of the monitored app.
3. **Given** the app was locked due to time expiry, **When** the user attempts to keep using the
   monitored app, **Then** it remains locked until the limit is reset per the app's reset rules.
4. **Given** the countdown is running, **When** the user is a few seconds from expiry, **Then** the
   displayed remaining time counts down accurately toward `00:00` without drifting negative on
   transition.

---

### User Story 2 - Accessibility permission stays granted (Priority: P2)

As a user who has granted the Accessibility permission that the app needs to monitor and enforce
usage, I want that permission to remain granted and the app to stop repeatedly prompting me for it,
so that enforcement keeps working and I am not interrupted by a permission loop.

**Why this priority**: Enforcement depends on the Accessibility permission. When it is revoked and
re-requested in a loop, the app both nags the user and can lose its ability to enforce limits.
It is critical, but it is a stability/robustness fix that sits alongside the primary lock behavior.

**Independent Test**: Grant the Accessibility permission, then use the device normally for an
extended period and across app/service restarts; confirm the permission remains granted and the app
does not re-prompt for it while it is still enabled in system settings.

**Acceptance Scenarios**:

1. **Given** the Accessibility permission has been granted, **When** the user continues normal use,
   **Then** the app does not repeatedly prompt to re-grant the permission.
2. **Given** the permission is granted and enabled in system settings, **When** the enforcement
   service runs over time or restarts, **Then** the permission remains active and is not
   self-revoked by the app.
3. **Given** the permission is genuinely disabled by the user in system settings, **When** the app
   detects this, **Then** it prompts once (not in a repeating loop) to guide the user to re-enable
   it.

---

### Edge Cases

- What happens when the device is asleep, the screen is off, or the app is backgrounded exactly as
  the countdown crosses `00:00`? The limit MUST still be treated as reached and the lock applied on
  next foreground, with the counter never presented as negative.
- What happens when the time limit is reached while the monitored app is not in the foreground? The
  lock MUST engage the next time the monitored app is opened.
- What happens if the countdown value is briefly read after expiry before the lock applies? It MUST
  be clamped to `00:00` rather than shown as negative.
- What happens when the Accessibility permission is toggled off then on by the user? The app MUST
  resume enforcement without entering a re-prompt loop.
- What happens when the OS itself revokes the permission (e.g., after an update or memory pressure)?
  The app MUST detect the actual current state and prompt at most once per genuine loss, not
  continuously.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The session countdown MUST stop at `00:00` and MUST NOT decrement into or display
  negative values once the configured time limit is reached.
- **FR-002**: When the configured time limit is reached (countdown hits `00:00`), the system MUST
  transition the monitored app into its locked/blocked enforcement state.
- **FR-003**: Once locked due to time expiry, the monitored app MUST remain locked until the limit
  is reset according to the app's existing reset rules; the lock MUST NOT be bypassable by simply
  continuing to use the app.
- **FR-004**: The lock MUST engage even if the moment of expiry occurs while the monitored app is
  backgrounded, the screen is off, or the device is asleep — enforced no later than the next time
  the monitored app is brought to the foreground.
- **FR-005**: The system MUST NOT self-revoke the Accessibility permission; once granted, it remains
  in effect for enforcement.
- **FR-006**: The system MUST determine the Accessibility permission state from the actual current
  system state and MUST NOT repeatedly prompt for the permission while it is granted and enabled.
- **FR-007**: When the Accessibility permission is genuinely absent or disabled, the system MUST
  prompt the user at most once per detected loss to re-enable it, avoiding a repeating prompt loop.
- **FR-008**: Both fixes MUST preserve all other existing behavior of the enforcement service; only
  the negative-countdown/lock defect and the accessibility re-prompt/self-revoke defect are being
  corrected.

### Key Entities *(include if feature involves data)*

- **Session Timer**: The per-app countdown representing remaining allotted time; key attributes are
  configured limit, remaining time (never negative), and expired/locked state.
- **Enforcement/Lock State**: Whether a monitored app is currently blocked because its limit was
  reached; transitions from active → locked at expiry and back on reset.
- **Accessibility Permission State**: The current granted/enabled status of the Accessibility
  permission the app relies on, plus a record of whether the user has already been prompted for the
  current loss (to prevent repeat prompts).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In 100% of sessions where a limit is reached, the displayed countdown stops at `00:00`
  and never shows a negative value.
- **SC-002**: In 100% of sessions where a limit is reached, the monitored app is locked at expiry
  and cannot be used further until reset.
- **SC-003**: Time expiry that occurs while the monitored app is backgrounded or the screen is off
  results in the lock being applied on the next foreground in 100% of cases.
- **SC-004**: While the Accessibility permission remains granted and enabled, the app issues zero
  re-grant prompts over a continuous 24-hour period of normal use and across service restarts.
- **SC-005**: When the permission is genuinely lost, the app prompts no more than once per detected
  loss (no repeating loop).

## Assumptions

- "Lock the application" means putting the monitored app into the app's existing blocking/enforcement
  state (the same mechanism already used elsewhere), not a new lock experience; this feature fixes
  the trigger and countdown, not the visual design of the lock.
- The existing reset rules for a time limit (how/when the allotted time refreshes) are unchanged and
  out of scope; this feature only ensures the lock engages at expiry and releases on the existing
  reset path.
- The Accessibility permission is a prerequisite the user grants in system settings; this feature
  does not change what the permission is used for, only the app's handling of its state and prompts.
- These fixes are scoped to the foreground session-enforcement service (the same module as
  `001-module-cleanup-audit`: `OverlayService`, `GrayzoneAccessibilityService`, and related
  components) and MUST NOT expand into unrelated modules.
- Existing tests define the behavior that must remain unchanged apart from the two corrected defects.

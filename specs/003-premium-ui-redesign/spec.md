# Feature Specification: Premium Lightweight UI Redesign

**Feature Branch**: `003-premium-ui-redesign`

**Created**: 2026-07-29

**Status**: Draft

**Input**: User description: "I want you to redesign my application from scratch clean minimal UI, current one is too messy slow, and too dark, it should be premium design and light weighted and fastness and feels good while using and picked right usage of colors"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Open the app into a calm, clear home experience (Priority: P1)

A returning user opens Grayzone and immediately understands protection status, what needs attention (permissions, battery, monitored apps), and the next useful action — without scanning a crowded, dark, or visually noisy screen.

**Why this priority**: First impression and daily re-entry define whether the app feels premium and trustworthy. A messy home undermines the product’s wellbeing purpose.

**Independent Test**: Open the Home destination with typical states (fully protected vs. missing a permission). Confirm status, priorities, and primary actions are visible within one glance and feel light, calm, and uncluttered.

**Acceptance Scenarios**:

1. **Given** the app is fully set up and protection is active, **When** the user opens Home, **Then** they see a clear “protected / ready” state, a short summary of monitored coverage, and no competing decorative noise.
2. **Given** a required permission or setup step is missing, **When** the user opens Home, **Then** the missing step is the most prominent call to action and secondary content does not compete with it.
3. **Given** the user returns to Home after using other tabs, **When** they land on Home, **Then** the screen feels light (not dark-dominant), loads without perceptible lag, and remains visually consistent with the rest of the app.

---

### User Story 2 - Navigate and complete core tasks without clutter (Priority: P1)

A user moves between the main destinations (Home, Apps, VPN/blocker, Limits, Stats, Settings) and completes common tasks — toggle monitoring, adjust limits, enable the DNS blocker, review stats — with fewer visual distractions, clearer hierarchy, and faster-feeling interactions.

**Why this priority**: Everyday task flow is where “messy and slow” is felt most. Premium redesign only succeeds if core journeys feel light and effortless.

**Independent Test**: Complete a representative task on each primary destination (e.g., add/remove a monitored app, toggle VPN, change a limit, open settings). Confirm each screen has one clear purpose, readable hierarchy, and no unnecessary chrome.

**Acceptance Scenarios**:

1. **Given** the user is on any primary destination, **When** they scan the screen, **Then** the primary purpose and key controls are obvious within a few seconds without hunting through dense cards or stacked sections.
2. **Given** the user switches between destinations via navigation, **When** transitions occur, **Then** navigation remains discoverable, labels stay clear, and screens do not flash heavy dark backgrounds or feel sluggish.
3. **Given** the user performs a common control action (toggle, slider, confirm), **When** the action completes, **Then** feedback is immediate and the UI remains responsive (no multi-second freeze for ordinary preference changes).

---

### User Story 3 - Experience a cohesive light premium visual system (Priority: P2)

A user experiences a single, intentional visual language across screens and system surfaces they encounter while using Grayzone: restrained color, strong typography hierarchy, generous spacing, and light surfaces that feel premium rather than gamer-dark or generic.

**Why this priority**: Color and visual cohesion are explicit goals of the redesign and differentiate “new UI” from a mere theme swap.

**Independent Test**: Review Home, Apps, VPN, Limits, Stats, Settings, and major dialogs against a short visual checklist (light surfaces, restrained accent use, consistent spacing/type). Confirm overlays/lock screens either match the new system or are explicitly deferred with no jarring clash if refreshed.

**Acceptance Scenarios**:

1. **Given** the user browses primary screens, **When** comparing surfaces and accents, **Then** colors follow a clear role system (background, surface, text, accent, warning/success) with accents used sparingly for emphasis — not as large dark washes.
2. **Given** the user opens a dialog or confirmation (e.g., commitment PIN, destructive setting), **When** the dialog appears, **Then** it matches the same light, minimal visual language and remains easy to read.
3. **Given** the user encounters friction/lockout overlays during normal protection use, **When** those surfaces appear, **Then** they feel consistent with the redesigned product identity (calm, legible, premium) and do not reintroduce the old dark/messy aesthetic.

---

### User Story 4 - Feel speed and lightness in daily use (Priority: P2)

A user perceives the app as lightweight and fast: screens appear quickly, scrolling stays smooth, and the interface does not feel heavy or over-decorated.

**Why this priority**: The user explicitly called out slowness and heaviness; perceived performance is part of the redesign success criteria, not a later polish item.

**Independent Test**: Cold-open the app and visit each primary destination; scroll longer lists (apps, stats). Confirm first content appears quickly and interaction stays smooth on a typical mid-range device.

**Acceptance Scenarios**:

1. **Given** the app process starts from a cold state, **When** the user reaches the first interactive screen, **Then** useful content is visible quickly enough that the app feels snappy rather than sluggish.
2. **Given** a destination with a longer list (monitored apps or stats), **When** the user scrolls, **Then** scrolling remains smooth and list items stay simple enough that the screen does not feel overloaded.
3. **Given** the user repeatedly opens and leaves destinations, **When** returning to a previously visited screen, **Then** the experience remains light — no sense that the UI is “weighing down” the device for ordinary browsing.

---

### Edge Cases

- What happens when protection is partially configured (some permissions granted, others not)? Setup guidance must remain clearer than decorative content.
- How does the UI handle empty states (no monitored apps, no stats yet)? Empty states must be calm, instructive, and non-alarmist — not dark empty voids filled with placeholder chrome.
- How does the UI behave when commitment lock disables settings changes? Locked controls must remain understandable and premium, not look broken or visually noisy.
- What if the user has system-wide dark mode enabled? The redesigned Grayzone experience still presents its intended light, calm surfaces as the product default for this feature (see Assumptions).
- How are error and warning states shown (VPN failure, accessibility disabled)? Warnings use restrained semantic color and clear copy — never large alarming color fields that dominate the screen.
- What happens on small phones and large tablets/foldables? Layout remains readable with consistent hierarchy; content does not become a dense dashboard of competing cards.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The product MUST present a redesigned, light-first visual experience across all primary in-app destinations (Home, Apps, VPN/blocker, Limits, Stats, Settings).
- **FR-002**: The redesign MUST replace the current dark-dominant, visually dense presentation with a clean, minimal layout that uses generous spacing, clear typography hierarchy, and restrained decoration.
- **FR-003**: The product MUST define and consistently apply a color role system: background, surface, primary text, secondary text, accent, success, warning, and destructive — with accents used for emphasis and actions, not as large background washes.
- **FR-004**: Primary destinations MUST each communicate a single primary purpose above the fold; secondary information MUST be visually subordinate.
- **FR-005**: Navigation among primary destinations MUST remain available and labeled clearly after the redesign; users MUST be able to reach every existing primary destination without discovering new hidden paths.
- **FR-006**: Existing protection and wellbeing behaviors (session limits, friction, lockouts, monitoring, DNS blocking, commitment lock, daily break, stats recording) MUST remain available and functionally unchanged by the visual redesign.
- **FR-007**: Common interactive controls (toggles, sliders, lists, dialogs, confirmations) MUST provide immediate, clear feedback and MUST NOT introduce multi-second UI freezes for ordinary preference changes.
- **FR-008**: Empty, loading, error, locked, and permission-needed states MUST have dedicated, calm presentations that explain what the user should do next.
- **FR-009**: Major dialogs and commitment-lock flows MUST adopt the same light, minimal visual language as primary screens.
- **FR-010**: Friction, lockout, and related overlay surfaces MUST be visually refreshed to align with the new product identity so the redesign feels complete during real protection use.
- **FR-011**: The redesigned UI MUST remain readable in bright ambient light (outdoor/indoor) with sufficient contrast between text and surfaces for primary content.
- **FR-012**: The redesign MUST reduce perceived visual weight: avoid dense stacked cards, competing gradients, and redundant status chrome that do not aid a decision or action.
- **FR-013**: List-heavy screens MUST prioritize scannability (clear row structure, restrained icons, minimal per-row ornamentation).
- **FR-014**: The product MUST feel subjectively lighter and faster in daily use compared with the pre-redesign experience for cold start, tab switching, and scrolling primary lists.
- **FR-015**: Visual redesign work MUST NOT remove or disable existing user-facing capabilities present before the redesign; capability reductions require an explicit, separate decision outside this feature.

### Key Entities

- **Visual System**: The shared rules for color roles, typography scale, spacing, surface elevation, and accent usage that every screen and dialog follows.
- **Primary Destination**: A top-level place in the app (Home, Apps, VPN/blocker, Limits, Stats, Settings) with one primary user job.
- **Status Affordance**: A compact, high-clarity signal for protection readiness, warnings, or required setup — not a decorative banner.
- **Empty / Guidance State**: Content shown when there is no data or a setup step is incomplete, guiding the next action without clutter.
- **Overlay Surface**: Full-screen or floating protection UI (friction wait, lockout, schedule/budget locks) experienced outside the main tab shell but still part of the product identity.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a side-by-side review with the previous UI, at least 4 of 5 evaluators describe the new experience as lighter, cleaner, and less dark on first open of Home.
- **SC-002**: Users can identify protection/setup status and the next required action on Home in under 5 seconds in moderated walkthroughs (fully set up and one missing-permission scenario).
- **SC-003**: Users can complete three core tasks — (1) change monitored apps, (2) adjust a session/limit setting, (3) toggle the DNS blocker — without assistance, with each task taking no longer than it did before the redesign (same or fewer steps).
- **SC-004**: On a typical mid-range device, cold start to first interactive content feels snappy: first useful Home content visible in under 2 seconds under normal conditions.
- **SC-005**: Tab switches between primary destinations feel immediate: destination chrome and primary content structure appear in under 300 ms of perceived wait for already-warm navigation (no multi-second blank or stutter for ordinary switches).
- **SC-006**: Long lists (apps/stats) maintain smooth scrolling in casual use testing (no sustained jank that makes the screen feel heavy).
- **SC-007**: At least 80% of evaluators agree accent color usage feels intentional and restrained (highlights actions/status) rather than loud or decorative.
- **SC-008**: Overlay surfaces encountered during a real lock/friction event are rated as matching the new light, premium identity by at least 4 of 5 evaluators.
- **SC-009**: Zero intentional loss of existing primary capabilities after redesign verification (all pre-redesign destinations and core controls remain reachable and usable).

## Assumptions

- This feature is a **visual and interaction redesign**, not a change to protection rules, timers, VPN filtering logic, or commitment-lock policy.
- **Light-first** is the shipped product appearance for Grayzone in this feature; supporting a full alternate dark theme is out of scope unless later requested.
- Existing **primary destinations and their jobs remain**; navigation may be visually simplified but users still reach Home, Apps, VPN/blocker, Limits, Stats, and Settings.
- Overlay/friction/lock screens are **in scope** for visual alignment so the redesign feels complete during real use.
- “Premium” means calm restraint: clear hierarchy, quality typography/spacing, purposeful color — not luxury ornamentation, glassmorphism stacks, or heavy motion.
- Color direction favors a calm wellbeing palette (neutral light surfaces, soft neutrals for secondary UI, one restrained accent for primary actions, semantic colors for success/warning/error only).
- Performance goals emphasize **perceived** lightness (simpler UI, less visual work, snappy feedback) while preserving existing protection behavior; deep engine rewrites are out of scope unless required to meet the stated feel targets.
- Accessibility contrast for primary text on light surfaces is required; decorative low-contrast text is not used for critical status.
- Behavior-preserving constitution rules apply: appearance may change; protection outcomes must not silently change. Dead/old visual assets and unused UI code should be removed rather than left behind once replaced.
- System notification styling may receive light polish if needed for cohesion but is not a primary focus of this feature.

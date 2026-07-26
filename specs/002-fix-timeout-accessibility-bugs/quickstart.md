# Quickstart & Validation: Fix Session Timeout Lock & Accessibility Permission Stability

This guide validates that both defects are fixed. It combines fast automated JVM tests (the
behavior-preservation + new-logic oracle) with on-device checks for the Android-glue behavior that
cannot be unit-tested.

## Prerequisites

- Android SDK + JDK 17, repo checked out on branch `002-fix-timeout-accessibility-bugs`.
- A test device / emulator (Android 10+; also test one aggressive-battery OEM if available).
- At least one monitored app configured in Grayzone with a **short** session limit (e.g. 1 minute)
  and a lockout (e.g. 15 minutes) to make expiry fast to observe.

## 1. Automated tests (run first, must be green)

```bash
./gradlew :app:testDebugUnitTest
```

Expected:
- All **pre-existing** tests still pass (behavior preservation — spec FR-008, Constitution VI).
- New `SessionExpiryDecisionTest` passes: covers clamp-at-zero (`max(0, activeUntil-now)`),
  `ShowLock` when foreground & within lockout, `ClampCountdown` when not foreground, and `NoOp` when
  disabled/on break/not yet expired.
- New `AccessibilityPromptStateTest` passes: covers one prompt per loss and reset-on-regrant.

## 2. Bug 1 — Session locks at expiry, no negative counter

Scenario A — in foreground, screen on:
1. Open the monitored app, complete the friction wait, start a 1-minute session.
2. Watch the countdown notification. **Expected**: it counts down to `00:00` and **stops at
   `00:00`** — it never shows a negative value.
3. At expiry **Expected**: the lock overlay (`OverlayMode.LOCK`) appears and the app can no longer be
   used until the lockout ends. (Contract SE-1.)

Scenario B — screen off during expiry (Doze-safe):
1. Start a 1-minute session, then turn the screen off before it elapses.
2. Wait past expiry, turn the screen on and open the monitored app.
3. **Expected**: no negative timer is shown; the app is locked. (Contracts SE-2/SE-4.)

Scenario C — process death mid-session (re-arm):
1. Start a session, then force-stop/kill the app process (or `adb shell am kill`).
2. Reopen the monitored app after expiry.
3. **Expected**: the lock is enforced (alarm re-armed on service restart / next foreground). (SE-5.)

Scenario D — reboot mid-session:
1. Start a session, reboot the device before expiry, reopen the monitored app after expiry.
2. **Expected**: lock enforced; no negative timer. (SE-5.)

Regression checks (must be unchanged):
- Backgrounding mid-session still pauses and resumes correctly.
- Budget lock, schedule lock, and midnight reset behave as before.
- The friction wait-screen countdown still ends at `0`.

## 3. Bug 2 — Accessibility permission stability

Scenario E — no loop while granted:
1. With Accessibility granted, background/foreground the app several times.
2. **Expected**: no repeated prompts; app stays on the main screen. (Contract AP-1.)

Scenario F — one prompt per genuine loss:
1. Disable Grayzone's Accessibility service in system settings.
2. Return to the app. **Expected**: exactly **one** prompt/redirect to Accessibility settings.
3. Return again without enabling. **Expected**: **no** additional automatic prompt. (AP-2/AP-3.)
4. Re-enable the service, return to the app. **Expected**: app proceeds; disabling again later
   prompts once more. (AP-4.)

Scenario G — app is not the revoker (OEM soak):
1. On an aggressive-battery OEM device, grant Accessibility and the battery-optimization exemption.
2. Use the device normally for 24 hours.
3. **Expected**: permission remains enabled (SC-004). If a revocation still occurs, capture logcat
   around the event to confirm the trigger is the OS/OEM (not a Grayzone crash) — the service is
   hardened to be exception-safe (AH-1). (AP-5.)

## 4. Success mapping

| Spec criterion | Validated by |
|----------------|--------------|
| SC-001 (never negative) | §2 A/B + `SessionExpiryDecisionTest` |
| SC-002 (locks at expiry) | §2 A |
| SC-003 (background/off expiry locks on foreground) | §2 B/C/D |
| SC-004 (no re-prompt for 24h while granted) | §3 E/G |
| SC-005 (≤1 prompt per loss) | §3 F + `AccessibilityPromptStateTest` |

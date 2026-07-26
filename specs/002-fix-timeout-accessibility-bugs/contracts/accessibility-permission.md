# Internal Contract: Accessibility Permission Prompt-Once-Per-Loss

**Scope**: Internal (in-app) contract between the accessibility gate/UI (`MainActivity`,
`OnboardingScreen`, `HomeScreen`), the pure `AccessibilityPromptState`, and
`GrayzoneAccessibilityService`. No public API / no external interface.

## Purpose

Stop the revoke → re-prompt loop: while the Accessibility permission is granted the app must not
prompt for it; when it is genuinely lost the app prompts **at most once per loss**; the app must not
be the cause of revocation.

## Behavioral contract

| ID | Given | When | Then |
|----|-------|------|------|
| AP-1 | Permission granted & enabled | App resumes / gate evaluated repeatedly | No re-grant prompt is shown; user stays on the main screen |
| AP-2 | Permission observed enabled → later observed disabled | First observation of the loss | Exactly one prompt/redirect to Accessibility settings; `prompted` flag set |
| AP-3 | Permission still disabled, `prompted = true` | Subsequent resumes | **No** additional automatic prompt |
| AP-4 | Permission re-enabled by user | Observed enabled again | `prompted` flag reset; app proceeds; a future loss will prompt once again |
| AP-5 | Service process transiently dies but OS keeps it enabled | Service reconnects | No prompt (permission still enabled); enforcement resumes |

## Service-hardening contract (app is not the revoker)

| ID | Requirement |
|----|-------------|
| AH-1 | `onAccessibilityEvent`, `onServiceConnected`, and the broadcast send are exception-safe; no unhandled throwable can crash the service (crashes trigger OS revocation). Failures are logged (component `ACCESSIBILITY`), never silently swallowed. |
| AH-2 | The service performs only foreground-package detection; unused capabilities (e.g. interactive-window retrieval) are not requested, keeping it lightweight. Existing `ACTION_APP_CHANGED` broadcast payload/behavior is preserved. |
| AH-3 | The app never calls `disableSelf()`; it cannot and does not revoke its own permission programmatically. |

## Error handling (Constitution III)

- Reading `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and launching
  `Settings.ACTION_ACCESSIBILITY_SETTINGS` are wrapped with explicit handling; a failure degrades to
  "not prompting again this cycle" rather than crashing or looping.

## Verification

- `AccessibilityPromptStateTest` (JVM) covers AP-1..AP-4 deterministically (state machine).
- AP-5 and AH-1..AH-3 are validated on-device per `quickstart.md` (including logcat around any OEM
  revocation event to confirm the app is not the trigger).

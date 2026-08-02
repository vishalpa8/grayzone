# Release-only findings (T044)

**Feature**: `004-android-regression-audit` | **Created**: 2026-08-02

Findings whose defect or evidence is specific to minified/release builds (FR-011, SC-011).
Everyday-build punch-list rows in the release-observability cluster are cross-listed.

| ID | Severity | Unit file | One-line |
|---|---|---|---|
| F-016 | Low | `findings-ru04.md` | â€” RU-04's recovery-path failures are logged with raw `android.util.Log`, bypassing the component t |
| F-064 | Medium | `findings-ru01.md` | â€” `BuildConfig.DEBUG` is `false` in every variant the project builds, so `GrayzoneLogger`'s DEBUG  |
| F-065 | Medium | `findings-ru01.md` | â€” Release builds write monitored package names and queried domains to logcat, because WARN and ERR |
| F-125 | High | `findings-ru08.md` | Gson keep block omits the TypeToken rule, and R8 has already removed four live `TypeToken` subclasse |
| F-126 | Medium | `findings-ru08.md` | `SourceFile` / `LineNumberTable` are absent from the merged R8 configuration, so release stack trace |
| F-127 | Low | `findings-ru08.md` | Keep rule names a class that no longer exists, and keep rules fail silently |
| F-128 | Low | `findings-ru08.md` | `OverlayService` and `MainActivity` keep rules are redundant with AGP's generated rules and disable  |
| F-130 | Medium | `findings-ru08.md` | `lint { checkReleaseBuilds = false }` removes the only lint gate that inspects the release variant,  |
| F-180 | Low | `findings-ru09.md` | Nothing declares that the blocklist assets must stay uncompressed; the packaging the app depends on  |
| F-220 | Low | `findings-principle3.md` | â€” A DNS parse failure increments a counter that nothing in production ever reads |
| F-251 | Low | `findings-ru08-root.md` | Stale release-signed APK tracked-adjacent in the repository root will drift from the code |

See also punch-list **Release-only** table and condition E8.

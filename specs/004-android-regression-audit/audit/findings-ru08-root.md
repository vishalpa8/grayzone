# Findings — RU-08 repository-root configuration (T025a)

**Scope**: added by scope amendment 1 (`spec.md`). Covers root `build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`,
`gradlew`/`gradlew.bat`, `.gitignore`, `keystore.properties.example`.

**Observed against**: `557a963` (none of these files carry uncommitted modifications).
**ID block**: F-250 … F-264. Used: F-250, F-251, F-252.

---

### F-250 — The build declares no Java toolchain, so it silently depends on the ambient JDK and fails with an undiagnosable error  [Medium]

- **Review unit**: RU-08
- **Location**: `build.gradle.kts` (root, lines 1–5), `gradle.properties` (lines 1–7),
  `app/build.gradle.kts` → `compileOptions` / `kotlinOptions`, `gradle/wrapper/gradle-wrapper.properties:3`
- **Issue**: Nothing in the build declares which JDK to use. There is no `java { toolchain { … } }`,
  no `kotlin { jvmToolchain(…) }`, and no `org.gradle.java.home` in `gradle.properties`. Gradle
  therefore runs on whatever JDK is first on `PATH`. The wrapper pins Gradle 8.6, which does not
  support JDK 25, so on a machine with a current JDK the build aborts with the bare, contextless
  message `25.0.4` — no mention of Java, versions, or what to change. `app/build.gradle.kts` pins
  `sourceCompatibility`/`targetCompatibility`/`jvmTarget` to 1.8, which sets the bytecode *target* and
  does nothing to select the *toolchain*, so it does not mitigate this.
- **Trigger condition**:
  1. On a machine whose default `java` is JDK 22 or newer (JDK 25 here), clone the repo.
  2. Run the command README documents: `./gradlew installDebug`.
  3. Observe `FAILURE: Build failed with an exception.` whose "What went wrong" section is the single
     token `25.0.4`.
- **Blast radius**:
  - *Callers*: every Gradle invocation — `assembleDebug`, `assembleRelease`, `testDebugUnitTest`,
    `lintDebug`, `bundleRelease`, and Android Studio's own sync.
  - *Process death* / *config change* / *foreground-background* / *cross-process*: none. This is
    build-time only and cannot affect a shipped APK.
  - *Persistence*: none.
  - *Release-only*: no — affects debug and release equally.
  - *Who is hit*: any fresh contributor, and any CI image that bumps its default JDK. This audit hit
    it on its first command.
- **Fix** (minimal): pin the toolchain so the build selects its own JDK instead of inheriting one. In
  `app/build.gradle.kts`, inside the `kotlin` block:

```kotlin
kotlin {
    jvmToolchain(21)
}
```

  This is the minimal change: it keeps the existing 1.8 bytecode target (toolchain and target are
  independent), needs no per-developer configuration, and makes Gradle fail with an actionable
  "no matching toolchain" message that names Java 21 if the JDK is absent. The alternative —
  `org.gradle.java.home=C:\Program Files\jdk-21.0.11` in `gradle.properties` — is rejected because it
  hardcodes one machine's absolute path into a tracked file.
- **Test to add**: none. Build configuration is verified by the build itself; the check is that
  `.\gradlew.bat assembleDebug` succeeds with **no** `-Dorg.gradle.java.home` flag after the fix. Add
  that assertion to `quickstart.md` rather than to a test source set.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (evidence tier T1/T2). Reproduced directly: the JDK-25 failure is
  recorded in research R1, and every Gradle command in this audit required the explicit
  `-Dorg.gradle.java.home` flag to succeed.
- **Build scope**: `everyday-build`
- **Constitution**: none directly. Adjacent to Principle III in spirit — a failure surfaced with no
  diagnosable context — though that principle governs runtime error handling, not build config.
- **Affected surfaces**: `README.md` documents the un-flagged command (see F-265); `INSTALL.md`
  documents the flagged one. The fix removes the need for the flag in both.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-251 — Stale release-signed APK tracked-adjacent in the repository root will drift from the code  [Low]

- **Review unit**: RU-08
- **Location**: `Grayzone-v1.0.apk` (repository root), referenced by `INSTALL.md:3`
- **Issue**: A release-signed APK sits in the working tree and is the artifact `INSTALL.md` tells
  maintainers to distribute. It is correctly git-ignored (`.gitignore` has `*.apk`), so this is not a
  secret-exposure or repository-bloat problem. The risk is staleness: nothing ties the file to a
  commit, and `versionCode` is fixed at `1`, so a rebuilt APK carries an identical version while
  containing different code. Anyone following `INSTALL.md` can ship a build that predates every fix
  this audit produces, with no way to tell from the filename or version.
- **Trigger condition**:
  1. Apply any fix from this audit and rebuild.
  2. Do not overwrite `Grayzone-v1.0.apk`.
  3. Follow `INSTALL.md` and send `Grayzone-v1.0.apk` to a recipient.
  4. The recipient installs code from before the fix; `versionName` still reads `1.0`.
- **Blast radius**:
    - *Process death*: n/a (build-time config).
  - *Config change*: n/a (build-time config).
  - *Foreground/background*: n/a (build-time config).
  - *Callers*: `INSTALL.md` distribution flow only. No code references it.
  - *Persistence*: an installed stale build cannot be distinguished from a current one on-device,
    because `versionCode`/`versionName` are unchanged.
  - *Release-only*: yes by nature — it is a release artifact.
- **Fix** (minimal): out of scope to change the release process (spec excludes it), so the minimal
  in-scope fix is documentation: have `INSTALL.md` instruct the maintainer to rebuild before
  distributing, and bump `versionCode`/`versionName` per distributed build. Recorded here rather than
  patched, because `versionCode` policy is a maintainer decision.
- **Test to add**: none — process concern, not behavior.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (T1). File exists at root; `.gitignore` `*.apk` confirmed; `git status`
  does not list it, proving it is ignored; `versionCode = 1` at `app/build.gradle.kts:30`.
- **Build scope**: `release-only`
- **Constitution**: none.
- **Affected surfaces**: `INSTALL.md:3`, `app/build.gradle.kts:30–31`.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-252 — `.editorconfig` is in no review unit  [Low]

- **Review unit**: RU-08
- **Location**: `.editorconfig` (repository root)
- **Issue**: T015 enumerated repository-root configuration and scope amendment 1 listed seven files;
  `.editorconfig` was missed by both. It is unassigned, so SC-002 is still technically unmet. It is a
  formatting-convention file with no runtime effect, which is why it is Low and not a scope failure of
  consequence.
- **Trigger condition**: read `spec.md`'s RU-08 file list and compare against `Get-ChildItem` of the
  repository root; `.editorconfig` appears in the second and not the first.
- **Blast radius**:
  - *Process death*: n/a (build-time config).
  - *Config change*: n/a (build-time config).
  - *Foreground/background*: n/a (build-time config). audit bookkeeping only. No code, no build, no runtime behavior.
- **Fix** (minimal): add `.editorconfig` to RU-08's file list in `spec.md`. Applied below.
- **Test to add**: none.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (T1).
- **Build scope**: `everyday-build`
- **Constitution**: none.
- **Affected surfaces**: `spec.md` RU-08 row; `audit/coverage-matrix.md` scope check.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

## No-defect-found — verified, with what was examined (FR-003 / SC-008)

### Secret exclusion is correct

The most consequential question for these files is whether any secret is tracked. It is not.

| Check | Result |
|---|---|
| `git ls-files` filtered for `keystore\|local.properties\|.jks\|.keystore\|.env\|google-services` | Only `keystore.properties.example` — a template with no real values |
| `git ls-files --error-unmatch keystore.properties` | `error: pathspec ... did not match any file(s) known to git` — untracked, as intended |
| `grayzone-release.jks` present at root | Untracked; `.gitignore` `*.jks` covers it |
| `local.properties` present at root | Untracked; `.gitignore` lists it twice (redundant but harmless) |
| `Grayzone-v1.0.apk` present at root | Untracked; `.gitignore` `*.apk` covers it |

`app/build.gradle.kts:10–20` reads signing credentials from the git-ignored `keystore.properties` and
creates the `release` signing config only when the file exists, leaving release builds unsigned on a
fresh clone rather than failing or falling back to a checked-in key. This is the correct pattern and
the audit credits it — **no finding**.

### Dependency-resolution hardening is correct

`settings.gradle.kts:9` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, preventing a module from
introducing its own repository and enabling dependency substitution. `gradle-wrapper.properties:5`
sets `validateDistributionUrl=true`, so a tampered distribution URL is rejected. Both are correct
supply-chain posture — **no finding**.

The absence of Gradle dependency verification (checksum/signature pinning) is noted as an
observation, not a finding: it would be a hardening addition, and spec explicitly excludes dependency
work not required to fix an identified defect. The 11 `GradleDependency` lint warnings for outdated
versions fall under the same exclusion and are recorded in `evidence/lint.md`, not raised here.

### `gradle.properties` charset pinning is correct and deliberate

Lines 2–4 pin `-Dfile.encoding=UTF-8` for the Kotlin compile daemon *separately* from
`org.gradle.jvmargs`, with a comment explaining that the daemon is a distinct JVM that does not
inherit those args. Sources contain non-ASCII glyphs, so this is load-bearing, correct, and correctly
explained — **no finding**. Combined heap (2048m Gradle + 2048m Kotlin daemon) is reasonable.

## Coverage summary

| File | Outcome |
|---|---|
| root `build.gradle.kts` | findings-reported (F-250) |
| `settings.gradle.kts` | no-defect-found |
| `gradle.properties` | findings-reported (F-250, toolchain absence) |
| `gradle/wrapper/gradle-wrapper.properties` | no-defect-found |
| `gradlew` / `gradlew.bat` | no-defect-found — standard wrapper scripts, `validateDistributionUrl` on |
| `.gitignore` | no-defect-found — secret exclusion verified above |
| `keystore.properties.example` | no-defect-found — template only, no real values |
| `.editorconfig` | findings-reported (F-252, scope bookkeeping) |
| `Grayzone-v1.0.apk` (root artifact) | findings-reported (F-251) |

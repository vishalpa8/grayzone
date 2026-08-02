# Findings — documentation accuracy drift (T025b)

**Scope**: added by scope amendment 2. `README.md` and `INSTALL.md`, **accuracy drift only** —
whether stated setup/build instructions contradict verified build reality, and whether the two
documents contradict each other. Content, structure, and style are out of scope.

**Observed against**: `557a963` for `README.md`; `INSTALL.md` is **untracked** (new, never committed).
**ID block**: F-265 … F-269. Used: F-265, F-266, F-267, F-268.

---

### F-265 — README's documented build command fails on any current JDK; the fix exists only in the untracked INSTALL.md  [Medium]

- **Review unit**: RU-08 (docs drift)
- **Location**: `README.md:43–68` ("Setup & Build Instructions", steps 3–4) vs `INSTALL.md:77–84`
- **Issue**: README tells a new contributor to run `./gradlew installDebug` with no JDK qualifier.
  Because the build declares no Java toolchain (F-250), that command fails on a machine whose default
  JDK is 22 or newer, and it fails with the bare token `25.0.4` — nothing that points at Java. README
  never mentions a JDK requirement at all: its "Requirements" section (lines 29–35) lists only the
  Android OS version and device permissions. The working command,
  `./gradlew assembleRelease "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11"`, appears **only**
  in `INSTALL.md:79` — a file that is untracked, so a fresh `git clone` does not contain it. The
  knowledge required to build the project is therefore absent from the repository.
- **Trigger condition**:
  1. `git clone` the repository onto a machine with JDK 25 as the default (the state of this
     development machine).
  2. Follow `README.md` step 4 exactly: `./gradlew installDebug`.
  3. Build fails; "What went wrong" reads `25.0.4`.
  4. Search the cloned tree for `java.home` or "JDK" — no tracked file explains it, because
     `INSTALL.md` was never committed.
- **Blast radius**:
    - *Process death*: n/a (documentation).
  - *Config change*: n/a (documentation).
  - *Foreground/background*: n/a (documentation).
  - *Callers*: every new contributor and every CI setup following README. No runtime effect.
  - *Cross-document*: `README.md` and `INSTALL.md` give contradictory build invocations for the same
    project; whichever a reader finds first determines whether they succeed.
  - *Release-only*: no.
- **Fix** (minimal): two edits, both documentation.
  1. Add a JDK line to README's "Requirements": `JDK 21 (Gradle 8.6 does not support JDK 22+)`.
  2. Prefer resolving the root cause via F-250's toolchain pin, after which `./gradlew installDebug`
     works unqualified and README's existing text becomes correct without further change.
  Also commit `INSTALL.md`, or fold its build section into README, so the instructions survive a clone.
  Sequencing note: if F-250 is applied, apply it **first** — it makes edit 2 unnecessary and prevents
  documenting a workaround that is about to become obsolete.
- **Test to add**: none — documentation. Verified by following the README on a clean checkout.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (T1/T2). The JDK-25 failure is reproduced and recorded in research R1;
  every Gradle command in this audit required the explicit flag. `git status` confirms `INSTALL.md` is
  untracked.
- **Build scope**: `everyday-build`
- **Constitution**: none.
- **Affected surfaces**: `README.md:29–35`, `README.md:63–68`, `INSTALL.md:77–84`,
  `quickstart.md` (which already states the requirement correctly).
- **Observed against**: `README.md` @ `557a963`; `INSTALL.md` untracked
- **Disposition**: `verified`

---

### F-266 — README states two blocklist sizes that differ by roughly 5x  [Low]

- **Review unit**: RU-08 (docs drift)
- **Location**: `README.md:12` vs `README.md:24`
- **Issue**: Line 12 says the blocker covers "~800k known ad/tracking domains and ~160k adult content
  domains" — about 960k total. Line 24 says the Bloom filters "match domains against 4.6 million
  blocklist entries". Both describe the same feature; they cannot both be right. A reader cannot tell
  the app's actual coverage, and the discrepancy makes the Bloom-filter false-positive-rate reasoning
  in line 24 unverifiable, since that rate depends on entry count against filter size.
- **Trigger condition**: read `README.md:12`, then `README.md:24`, and compare the totals.
- **Blast radius**:
    - *Process death*: n/a (documentation).
  - *Config change*: n/a (documentation).
  - *Foreground/background*: n/a (documentation).
  - *Callers*: documentation only; no code reads these numbers.
  - *Downstream analysis*: RU-06's memory-footprint and false-positive analysis (T019) needs the real
    entry count, so this ambiguity must be resolved from the artifacts rather than the prose.
- **Fix** (minimal): derive the true count from the generator `scripts/blocklist_tools.ps1` and the
  actual filter sizes, then make both lines agree. The on-disk filters are confirmed as
  `adblock_bloom.bin` = 6.00 MB and `adult_bloom.bin` = 4.00 MB, which matches line 24's "~6 MB ads +
  ~4 MB adult" — so line 24's *sizes* are accurate, and the entry count is the figure to confirm.
- **Test to add**: none.
- **Existing test conflict**: none.
- **Verification**: `needs-verification` (T1 partially). Confirmed: the two README lines conflict, and
  the stated filter sizes match the real files.
  - *Missing*: the authoritative entry count.
  - *Procedure*: run `scripts\blocklist_tools.ps1` and record the merged entry count it reports, or
    compute it from the source feeds. No device needed — this is verifiable here, just not yet done,
    because running the generator downloads from several upstream feeds and would rewrite the
    committed `.bin` assets, which is out of bounds for a read-only audit.
  - *Severity effect*: stays Low either way; only which line is wrong changes.
- **Build scope**: `everyday-build`
- **Constitution**: none.
- **Affected surfaces**: `README.md:12`, `README.md:24`; T019's memory analysis.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-267 — README under-documents the permissions protection depends on  [Low]

- **Review unit**: RU-08 (docs drift)
- **Location**: `README.md:29–35` ("Requirements") and `README.md:70–73` (step 5) vs `INSTALL.md:52–58`
- **Issue**: README lists three permissions — Accessibility, Display over other apps, VPN — and its
  on-device setup step names only the first two. `INSTALL.md:52–58` lists five, adding **Usage access**,
  **All files access** (described there as required for the "persistent anti-tamper backup"), and
  **Notifications**. README's own feature list depends on the omitted ones: the "Live Session
  Notification" needs `POST_NOTIFICATIONS`, and daily budgets need `PACKAGE_USAGE_STATS`. A user
  following README alone grants an incomplete set, and per the questions T022 is chartered to answer,
  the app may then degrade silently rather than say what is missing.
- **Trigger condition**:
  1. Install per README and grant only Accessibility and Display-over-other-apps.
  2. Start a session and expect the documented live countdown notification.
  3. Notification permission was never requested by the documented flow.
- **Blast radius**:
    - *Process death*: n/a (documentation).
  - *Config change*: n/a (documentation).
  - *Foreground/background*: n/a (documentation).
  - *Callers*: documentation only, but it shapes which permissions a real user grants.
  - *Interacts with*: T022's analysis of whether each permission's absence is detected and surfaced.
    If T022 finds silent degradation, this drift raises that finding's likelihood of being hit rather
    than its severity.
- **Fix** (minimal): make README's "Requirements" and step 5 match `INSTALL.md:52–58` — five
  permissions, each with the one-line purpose already written there.
- **Test to add**: none.
- **Existing test conflict**: none.
- **Verification**: `confirmed` (T1) for the drift itself — the two lists are directly comparable and
  differ. Whether missing permissions degrade silently is T022's question, not asserted here.
- **Build scope**: `everyday-build`
- **Constitution**: none.
- **Affected surfaces**: `README.md:29–35`, `README.md:70–73`, `INSTALL.md:52–58`; T022.
- **Observed against**: `557a963`
- **Disposition**: `verified`

---

### F-268 — README advertises a user-extendable VPN bypass list that no code can populate  [Medium]

- **Review unit**: RU-08 (docs drift); the underlying dead key belongs to RU-06 / RU-01
- **Location**: `README.md:12` ("and the exclusion list is user-extendable") and the doc comment at
  `app/src/main/java/com/grayzone/app/service/vpn/AdBlockVpnService.kt:255` ("plus any user-chosen
  packages")
- **Issue**: `applyAppBypassList` reads the user list at `AdBlockVpnService.kt:260–261` via
  `getStringSet(PrefsKeys.VPN_BYPASS_APPS, emptySet())`. Those two occurrences — the read and the
  constant at `PrefsKeys.kt:66` — are the **only** two in `app/src`. Nothing writes the key, so
  `userBypass` always resolves to `emptySet()` and the union at `:264` reduces to
  `DEFAULT_BYPASS_APPS`. Being precise about the blast radius: the default bypass **does** work, so
  payment and streaming apps are genuinely excluded as README's first clause claims. Only the
  *user-extendable* half is unreachable. There is no Settings UI writing this key, so a user who
  wants to exclude an additional app has no path to do so, and nothing tells them that.
- **Trigger condition**:
  1. Enable the DNS blocker.
  2. Look for any UI to add an app to the VPN bypass list — none writes `vpn_bypass_apps`.
  3. Search the source: `VPN_BYPASS_APPS` appears exactly twice, both non-writes.
- **Blast radius**:
    - *Process death*: n/a (documentation).
  - *Config change*: n/a (documentation).
  - *Foreground/background*: n/a (documentation).
  - *Callers*: `applyAppBypassList` is called once, while building the VPN interface. The dead read
    costs nothing at runtime; the missing feature is the issue.
  - *Persistence*: the key is never written, so no stored state exists to migrate or clean up.
  - *Doc surfaces*: `README.md:12` and the code comment at `:255` both describe behavior that cannot
    occur — the comment is a Principle IV concern (a comment asserting something untrue), which makes
    this more than cosmetic: a future maintainer reading `:255` would believe the feature works.
  - *Release-only*: no.
- **Fix** (minimal): two candidate directions, and the choice is the maintainer's because one adds a
  feature and the other removes a claim. **(a)** Correct the docs — drop "and the exclusion list is
  user-extendable" from `README.md:12`, fix the comment at `:255`, and remove the dead read plus the
  `PrefsKeys` constant (Principle I). **(b)** Implement the writer in the VPN settings UI. (a) is the
  minimal patch and the one this audit recommends, since the spec excludes feature additions; (b) is a
  feature request to track separately.
- **Test to add**: only if (b) is chosen — a JVM unit test asserting the union at `:264` includes a
  user-added package. Under (a), removal is verified by the reference index showing zero remaining
  references.
- **Existing test conflict**: none — no test references `VPN_BYPASS_APPS`.
- **Verification**: `confirmed` (T1). Read `AdBlockVpnService.kt:259–276` directly and confirmed the
  union with `DEFAULT_BYPASS_APPS`; searched all of `app/src` for both the constant and the literal.
- **Build scope**: `everyday-build`
- **Constitution**: I (dead code — an unwritten key and its read), IV (a comment asserting behavior
  that cannot occur).
- **Affected surfaces**: `README.md:12`, `AdBlockVpnService.kt:255`, `AdBlockVpnService.kt:260–261`,
  `PrefsKeys.kt:66`. Shares a root cause with the dead-code sweep (T059) and RU-06 analysis (T019).
- **Observed against**: `working-tree @ 557a963 (uncommitted: AdBlockVpnService.kt)`
- **Disposition**: `verified`

---

## No-defect-found — verified, with what was examined (FR-003 / SC-008)

Every file path and command the two documents reference was checked for existence:

| Reference | Source | Result |
|---|---|---|
| `.\scripts\blocklist_tools.ps1` (mandatory pre-build step) | `README.md:54` | EXISTS |
| `LICENSE` file ("see the LICENSE file") | `README.md:84` | EXISTS |
| `Grayzone-v1.0.apk` in project root | `INSTALL.md:3` | EXISTS (staleness raised as F-251, not a broken reference) |
| `keystore.properties.example` | `INSTALL.md:86` | EXISTS |
| `grayzone-release.jks` + `keystore.properties`, "both git-ignored" | `INSTALL.md:7–8` | Both exist and both are confirmed untracked — claim accurate |
| `res/raw` Bloom filters "~6 MB ads + ~4 MB adult" | `README.md:24` | `adblock_bloom.bin` 6.00 MB, `adult_bloom.bin` 4.00 MB — accurate |
| `abused_tlds.txt` for exact TLD matching | `README.md:25, 56` | EXISTS |
| `app/build/outputs/bundle/release/app-release.aab` path for `bundleRelease` | `INSTALL.md:69` | Correct AGP output path |
| "release builds stay unsigned" without the keystore | `INSTALL.md:87–88` | Accurate — matches `app/build.gradle.kts:42–54, 61–63`, which creates the signing config only when the file exists |
| `-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11` | `INSTALL.md:79` | Accurate and verified working this session |

`INSTALL.md` is factually accurate throughout on build and signing; its only defect is that it is
untracked (F-265). No broken links, no missing files, no incorrect commands.

## Coverage summary

| Document | Outcome | What was examined |
|---|---|---|
| `README.md` | findings-reported (F-265, F-266, F-267) | Requirements, setup/build steps, architecture claims, all referenced paths, permission list |
| `INSTALL.md` | findings-reported (F-265 — untracked) | Build/signing commands, distribution flow, permission list, all referenced paths |

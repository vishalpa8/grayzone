# Evidence: Compiler Warning Harvest (T004)

**Command**: `.\gradlew.bat compileDebugKotlin -Dorg.gradle.java.home="C:\Program Files\jdk-21.0.11" --rerun-tasks --console=plain`

**Result**: `BUILD SUCCESSFUL` in 62s, with `--rerun-tasks` forcing a from-scratch KSP + Kotlin
compile so no warning could be hidden behind an `UP-TO-DATE` task (research R4).

## Kotlin warnings — main source set

Exactly one warning across all 73 main-source files:

> ⚠️ **The file count in the line above is wrong — see [Correction (2026-07-31)](#correction-2026-07-31)
> at the foot of this file.** The verified count is **68**. The warning result itself is unaffected.

```text
w: app/src/main/java/com/grayzone/app/ui/theme/Theme.kt:19:22 Parameter 'context' is never used
```

**Classification**: Constitution Principle I violation (unused parameter). Severity Low — no runtime
consequence. → RU-07 / T059 candidate. Note Principle I requires *removal*, and the Code Quality
Standards explicitly forbid resolving it with `@Suppress`.

## Java warnings

None in the debug Kotlin compile. The release Java compile (`compileReleaseJavaWithJavac`, observed
during the `assembleRelease` probe) emits three:

```text
warning: [options] source value 8 is obsolete and will be removed in a future release
warning: [options] target value 8 is obsolete and will be removed in a future release
warning: [options] To suppress warnings about obsolete options, use -Xlint:-options.
```

**Classification**: `app/build.gradle.kts` pins `sourceCompatibility`/`targetCompatibility` to
`VERSION_1_8` with `jvmTarget = "1.8"`. Not a defect today; a forward-compatibility risk worth
recording. → RU-08 / T025 candidate (Low).

## Non-warnings (ignore these)

The `WARNING: java.lang.System::load ... restricted method` lines come from the **Gradle launcher**
running under the JDK 25 on `PATH` before it hands off to the JDK 21 toolchain. They concern Gradle's
own native-platform library, not this codebase, and PowerShell renders them as `NativeCommandError`.
They are not audit input.

## ⚠️ Limitation — do not read silence as absence

A clean warning harvest does **not** establish Principle I compliance. `kotlinc` does not report:

- unused **imports** (only the IDE inspection does)
- unused `internal`/`public` declarations that are never called (only unused *private* members and
  parameters in some positions)
- unreferenced resources (that is lint's job, and lint may be unavailable — see `lint.md`)
- unreachable branches behind always-true/false conditions
- duplicated logic or orphaned flow collectors

Therefore T059–T063 **must** derive their inventories from the cross-reference index (T007) and
targeted searches, using this file only as a corroborating signal. A dead-code inventory that reports
"one item, per the compiler" would be a false negative.

## Correction (2026-07-31)

**The "73 main-source files" figure above is wrong. The verified count is 68.**

This file is otherwise unchanged: the original text is preserved as captured evidence, and only this
note is appended. The warning result — exactly one Kotlin warning in the main source set — is not
affected; only the denominator was wrong.

Counted directly against the working tree:

```powershell
Get-ChildItem -Path "app\src\main" -Recurse -Filter *.kt -File | Measure-Object | Select-Object -ExpandProperty Count
# → 68
```

The full reconciliation, counted the same way, is **68 main-source + 42 JVM test + 3 instrumented =
113 total `.kt`**, with zero `.java` files under `app/src/main`. This matches the T015 scope-completeness
check in [`../coverage-matrix.md`](../coverage-matrix.md) exactly.

**Where 73 came from**: `plan.md` originally stated "73 main-source Kotlin files"; T004 harvested this
file against that figure, and T015 later counted the filesystem, established 68, and corrected
`plan.md`. This file was not updated at the time. So 73 was the stale figure in both places, and 68 is
correct in both — no third value exists.

**Consumers**: T059 and T064 must cite **68** for any "swept N files" completeness claim, and T080 must
state 68 in its post-fix harvest. Per `../artifact-drift.md` case D4, T080 writes to a **new**
`warnings-post-fix.md`; this file is immutable pre-fix baseline evidence for the remainder of the audit
and must not be overwritten.

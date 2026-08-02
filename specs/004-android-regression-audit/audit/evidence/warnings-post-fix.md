# Evidence: Compiler Warning Harvest — Post-Fix (T080)

**Feature**: `004-android-regression-audit` | **Captured**: 2026-08-02
**Pre-fix baseline (immutable)**: [`warnings.md`](./warnings.md) (T004, 2026-07-31)

## Command

```text
.\gradlew.bat compileDebugKotlin "-Dorg.gradle.java.home=C:\Program Files\jdk-21.0.11" --rerun-tasks --console=plain
```

**Result**: `BUILD SUCCESSFUL` in ~23s (JDK 21).

**Main-source denominator**: **68 files** (T015 scope check in `coverage-matrix.md`), not the
superseded 73 from the original T004 narrative.

## Delta vs T004 baseline

| Category | Pre-fix (T004) | Post-fix (T080) | Notes |
|---|---|---|---|
| Kotlin unused `Theme.kt` `context` parameter | Present (Principle I) | **Resolved by deletion** | Parameter removed from `resolveDarkTheme`; not `@Suppress` |
| Kotlin unused `VpnScreen` `modifier` params | Absent | Introduced then **resolved by wiring** `modifier` into `Box`/`Column` | Caught in this harvest; fixed same session |
| Java `source/target value 8 is obsolete` | Present on release Java compile | Still present | Forward-compat risk; JDK toolchain 21 added separately (F-250) |
| Gradle launcher restricted-method noise | Present | Present | Not app source; ignore |

## Principle I confirmation

The sole T004 Principle I warning (`Theme.kt` unused `context`) was resolved by **removing the
unused parameter**, not by `@Suppress`. Any new unused-parameter warnings found in this harvest
were likewise fixed by using or removing the parameter.

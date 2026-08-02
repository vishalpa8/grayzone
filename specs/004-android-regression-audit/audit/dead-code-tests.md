# Duplicate / overlapping tests (T063)

**Denominator**: 42 JVM test files + 3 instrumented (T015).

| Pair | Overlap assessment | Recommendation |
|---|---|---|
| `BlocklistManagerTest.kt` (root) vs `service/vpn/BlocklistDecisionTest.kt` | Different layers — loader/IO vs decision predicates | **Keep both**; rename root file if confusing |
| `ScheduleLogicTest.kt` vs `data/ScheduleMatchingTest.kt` vs `data/ScheduleManagerTest.kt` | Matching rules vs manager persistence — partial overlap on day/time windows | Keep; consolidate only if identical assertions found in a later pass |
| `CleanupAndLoggingTest` vs `CleanupWorkerTest` | Logger tests misnamed under Cleanup | **F-196** — rename to `GrayzoneLoggerTest` |
| `SessionStatePredicateTest` / `SessionPolicyEngineTest` clock tests | Pin defective wall-clock behavior | **F-190, F-191** — invert with Critical fixes; not deletable as dupes |
| `DateUtilsTest` covering `isSameDay` | Production-dead symbol | Keep only if symbol kept; else delete with F-072 |
| `SetupRequirementsTest` → `actionLabel` | Production-dead symbol | Same as F-073 |

No whole test class proven byte-for-byte duplicate. Removals capped at Low / rename.

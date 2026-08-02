# Contract: Critical-First Punch List

**Feature**: `004-android-regression-audit`

The punch list is the audit's primary decision artifact (US2, FR-005). It is delivered **before** the
detailed breakdown and must be readable on its own: the maintainer decides the first day's fix order
from this alone, in under 10 minutes (SC-003).

## Required form

```markdown
## Critical-First Punch List

**Observed against**: <commit SHA | working-tree (uncommitted: N files)>
**Verification environment**: JDK 21 · JVM suite green · assembleRelease green · no device
**Totals**: Critical N · High N · Medium N · Low N   (Medium/Low detailed in the breakdown only)

### Critical

| ID | Unit | One-line defect | User-visible consequence | Trigger (short) | Status |
|----|------|-----------------|--------------------------|-----------------|--------|
| F-003 | RU-04 | <one line> | <what the user experiences> | <shortest repro> | confirmed |
| F-007 | RU-05 | <one line> | <what the user experiences> | <shortest repro> | needs-verification |

### High

| ID | Unit | One-line defect | User-visible consequence | Trigger (short) | Status |
|----|------|-----------------|--------------------------|-----------------|--------|
| F-011 | RU-02 | <one line> | <what the user experiences> | <shortest repro> | confirmed |

### Release-only (not reproducible in the everyday build)

| ID | Unit | One-line defect | Why release-only | Status |
|----|------|-----------------|------------------|--------|
| F-014 | RU-08 | <one line> | R8 / resource shrinking | confirmed |
```

## Rules

1. **Membership**: Critical and High findings only. Medium and Low MUST NOT appear (FR-005) — they
   exist in the detailed breakdown and the coverage matrix.
2. **Ordering**: all Critical rows before any High row; within a severity, most-users-affected first.
3. **Self-containedness**: every row states the user-visible consequence in plain language. A row a
   maintainer cannot act on without opening the breakdown violates SC-003.
4. **Status column**: `confirmed` or `needs-verification`, so the maintainer can see at a glance how
   much of the punch list is proven versus inferred (FR-025).
5. **Release-only separation**: release-only findings get their own table and are never interleaved
   with everyday-build rows (FR-011, SC-011).
6. **Header facts**: the observed-against state (FR-004) and the verification environment are part of
   the contract — a punch list without them cannot be reproduced later.
7. **Totals line**: includes Medium and Low counts so the maintainer knows the full defect volume
   even though those rows are elsewhere.
8. **No fixes in the punch list**: the fix and blast radius live in the finding record. The punch list
   answers "what must I fix?", not "how?".
9. **Approval gate**: the punch list is the artifact the maintainer approves. No repository mutation
   occurs before that approval (FR-021), and declined entries move to accepted-risk rather than being
   deleted (FR-023).

## Anti-patterns

- A 40-row "punch list" — if everything is Critical, the artifact has failed its purpose. Severity
  must be applied per the FR-006 rubric, with dead code and style at Low.
- Rows phrased as code observations ("uses GlobalScope") with no consequence stated.
- Mixing in Medium findings because they are quick to fix; quick-to-fix is a property of the fix
  plan, not of severity.

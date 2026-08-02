# Artifact Drift Register

**Feature**: `004-android-regression-audit` | **Recorded**: 2026-07-31
**Source**: checkbox reconciliation of [`../tasks.md`](../tasks.md) against the artifacts actually on disk

Five places where a task's text and the audit's real artifact layout have diverged. None of these is a
defect in the app under review — they are bookkeeping hazards that will cause a later task to overwrite
confirmed evidence, silently under-report, or be unable to prove it ran.

Each case records the same four facts: the task(s) affected, the artifact the task text names, what
exists instead, and the downstream task that breaks or mis-merges. A recommended resolution follows.

`tasks.md` line 23 states the contract these cases violate: *"Every task names the exact file path it
reads or writes."*

> ## ✅ Status: all five cases resolved — 2026-07-31
>
> Task text has been amended in [`../tasks.md`](../tasks.md); each case below carries a **Resolution
> applied** block, and the [Resolution log](#resolution-log--2026-07-31) at the foot lists every task ID
> touched. No checkbox state was changed, no `findings-*.md` file was written, and
> `coverage-matrix.md` was not edited — its MERGE WARNING remains the authoritative instruction for D1.
> D5's file count was independently re-verified rather than taken from either conflicting source.

## Task index — find your task before you start

| Task(s) | Case | Nature of the hazard |
|---|---|---|
| T016 | D2 | No artifact; indistinguishable from T017 |
| T031, T032, T033, T036 | D3 | Glob-scoped in-place edit, no completion marker |
| T034 | D3 | Consumer — inherits partial severity assignment silently |
| T035, T046 | D2 | Validation with nowhere to record pass/fail |
| T037, T038, T039 | D3 | In-place edit, no completion marker |
| T041, T042, T043 | D3 | **No output path named at all** |
| T047–T054 | D1 | Merge target already populated; must read row as input |
| T055 | D1 | Overwrite would destroy confirmed evidence |
| T058 | D1, D2 | Validates a matrix of mixed provenance; nowhere to record |
| T059 | D4, D5 | Cites an evidence file that is stale and will be overwritten |
| T064 | D5 | Consumer — inherits a superseded file count |
| T065 | D2, D3 | Approval gate cannot confirm its inputs were validated |
| T066, T071, T074 | D3 | No single place to read T042's named tests from |
| T080 | D4, D5 | Destroys the baseline it needs for a before/after claim |
| T081 | D3 | In-place edit, no completion marker |
| T082, T085, T086 | D1, D2, D4, D5 | Report and traceability inherit every gap above |

---

## D1 — Extended-condition analysis was written into the merge target, not the source files

**Tasks affected**: T053 primarily; T047–T052 and T054 as writers; T055 as the merge step.

**Artifact the text names**: T047–T054 each write their own file under `audit/conditions/` —
`E1.md` through `E8.md`. T055 then merges those eight outcomes into the Part C rows of
`audit/coverage-matrix.md`.

**What exists instead**: `audit/conditions/` exists but is **empty — zero files**. The Part C rows in
`coverage-matrix.md` already carry substantive content: E7 holds a complete analysis citing F-110 and
F-111, and E1, E3, E6 and E8 hold verified inputs handed down from T021. The direction of flow is
inverted — the destination is populated and the sources do not exist.

**Downstream breakage**: T055 is the mis-merge risk, and it is the severe one: a straight overwrite of
the Part C rows destroys confirmed analysis, including the clock-tamper reasoning the maintainer
identifies as load-bearing for Criticals C5 and C6. T047–T054 break in the opposite direction — each
would start from a blank page and re-derive, or contradict, conclusions already recorded. T058 then
validates a matrix whose rows have mixed and unlabelled provenance.

**Recommended resolution**: The authoritative instruction is the **MERGE WARNING at the top of
`coverage-matrix.md`**, added by the maintainer; follow it rather than this entry, which exists only so
the case is discoverable from the task list. Two additions that preserve provenance: each of T047–T054
should copy its existing row into its new `E<n>.md` under a *"Pre-existing input (from T021/T024, via
coverage-matrix Part C)"* heading before adding new analysis, so the matrix ceases to be the only
copy; and T055 should be executed as fill-blank-cells-and-reconcile, never as a row rewrite.

**Resolution applied (2026-07-31)** — pointer-only; `coverage-matrix.md` was **not** edited. A phase-level
⚠️ MERGE WARNING block was added above T047 in `tasks.md`, and each of **T047, T048, T049, T050, T051,
T052, T053, T054** now carries an explicit clause directing it to read its existing Part C row as input
per the warning at the top of `coverage-matrix.md` and copy it into `E<n>.md` under the *Pre-existing
input (from T021/T024, via coverage-matrix Part C)* heading before adding new analysis. T052 and T053
additionally name what is already in their rows (F-061; the F-110/F-111 analysis) so neither re-derives
it. **T055** now reads *fill-blank-cells-and-reconcile, never a row rewrite*, and must record both sides
where an `E<n>.md` conclusion contradicts the existing row rather than silently replacing it.

---

## D2 — Four validation and presentation tasks name no output artifact

**Tasks affected**: T016, T035, T046, T058.

**Artifact the text names**: none. T016 presents the review-unit inventory to the maintainer and
requests confirmation. T035 validates the punch list against `quickstart.md` scenario V2. T046
validates the register against V3, V4 and V5. T058 validates the matrix against V6. All four are acts
with no named destination, in a task list whose header promises every task names its path.

**What exists instead**: For T016, no independent record — the only trace is the confirmation line in
`spec.md`, which T017 writes, so T016 and T017 are indistinguishable on disk and neither can be
verified separately. For T058, `coverage-matrix.md` has a *"Row count check (T058)"* heading, but it
states the 24-row requirement rather than performing the check, and does not assert the "zero blank
outcomes" half of V6 — which is currently false. For T035 and T046, nothing at all; their subjects
(`punch-list.md` and a complete register) do not exist yet.

**Downstream breakage**: T065 is the approval gate and consumes exactly the two artifacts T035 and T046
validate; it has no way to establish that either validation ever passed, so the audit's own quality
gate is unauditable. T086 records V1, V7, V8 and V9 into `report.md`, which means V2, V3, V4 and V6
have nowhere to land and `report.md` will present a validation record that looks complete while missing
four scenarios. T082 links the matrix that T058 was supposed to prove blank-free.

**Recommended resolution**: Create one `audit/validation-log.md` with a row per quickstart scenario
(V1–V10): scenario, owning task, date, pass/fail, and the evidence checked. T035, T046, T058 and T086
all append to it, giving `report.md` a single complete source for SC validation. Whichever of T034 or
T035 runs first should create it. T016 needs no new file — but T017's `spec.md` line should be read as
covering both tasks, not just its own.

**Resolution applied (2026-07-31)** — `audit/validation-log.md` adopted, with one **Scenario record**
table (scenario, owning task, date, pass/fail, evidence checked) covering V1–V10 and one **Gate record**
table for the two 🛑 gates. **T016** creates it and stamps a dated Gate record for the scope
presentation; **T035** records V2; **T046** records V3, V4 and V5 as three rows; **T058** records V6 with
the counts actually observed, and is now explicit that it must *perform* all three checks and record them
in the log rather than rely on the `coverage-matrix.md` heading that only states the requirement.
Downstream: **T065** must confirm V2 and V3–V5 are recorded as passed before presenting, and stamps its
own Gate record; **T086** records V1/V7/V8/V9 there and summarizes the complete V1–V10 table in
`report.md`; **T082** links the log.

*Departure from the recommendation*: this file suggested T016 needed no artifact, resting on T017's
`spec.md` line. That was not adopted — it is exactly what makes T016 and T017 indistinguishable on disk,
which the case itself identifies as the defect. A Gate record row is the smallest marker that separates
them. Note that T016 is already `[X]`; its checkbox was left untouched per instruction, so this record
must be written retroactively for the completed presentation.

---

## D3 — Glob-scoped in-place tasks leave no completion marker, and three name no path

**Tasks affected**: T031, T032, T033, T036, T037, T038, T039, T040, T065, T081 operate in place across
`findings-*.md`. **T041, T042 and T043 name no output path at all.**

**Artifact the text names**: "all `specs/004-android-regression-audit/audit/findings-*.md` files" — a
glob, not a file. T041 writes the minimal fix "for every finding", T042 names the test "for every
finding", T043 records `existing_test_conflict` "in the owning finding"; none states where.

**What exists instead**: Seven of the thirteen `findings-*.md` files exist, and they **already carry
severity labels assigned inline by the analysis tasks** — the Part A rows show per-unit counts such as
RU-01 "1 Critical, 1 High, 6 Medium, 12 Low" and RU-06 "1 Critical, 4 High, 13 Medium, 1 Low". So
T031's nominal job is already partly done by other tasks, and nothing distinguishes a T031-assigned
severity from an analysis-assigned one. Equally, nothing on disk marks a completed T032, T040 or T081.

**Downstream breakage**: T034 assembles the punch list from severities; if T031 reached only some of
the thirteen files, the punch list under-reports Criticals and **the failure is silent** — a missing
row looks identical to a finding that was correctly excluded. T046 must confirm "all seven mandatory
fields present" but cannot determine whether T032 ran. T066, T071 and T074 write the tests that T042
names, and with no output path for T042 they have no single place to read those names from. T065 and
T081 record dispositions with no discrete artifact for the maintainer to review.

**Recommended resolution**: Each of these tasks should stamp a short entry in a **"Sweep log"** table
in `audit/findings.md` — the register already owns cross-file state, so it is the natural home: task,
date, files touched, findings affected. That converts "did T032 run?" from unanswerable to a lookup.
T041, T042 and T043 should additionally be read as writing into the owning per-unit `findings-*.md`
record. Finally, **T031 should be re-scoped from "assign severities" to "reconcile and normalize the
severities the analysis tasks already assigned"**, which is what the artifacts now require.

**Resolution applied (2026-07-31)** — three changes.

1. **T031 re-scoped.** "Assign severities to every candidate" became "**reconcile and normalize the
   severities the analysis tasks already assigned**", stating that T018–T030 rate findings inline so this
   task must not re-do that work: check each existing rating against the FR-006 rubric and the
   `data-model.md` Grayzone calibration, correct outliers naming the rubric clause, and **resolve
   conflicts where two units rated the same defect differently** — `F-060`/`F-207` and `F-062`/`F-206`
   are cited as the already-reconciled pattern. Folding the duplicate *records* stays with T033, so the
   two tasks do not overlap.
2. **Completion markers.** A **Sweep log** table in `audit/findings.md` — task ID, date, files touched,
   findings affected, files deliberately skipped with the reason — is now stamped by **T031, T032, T033,
   T036, T037, T038, T039, T040, T041, T042, T043, T065, T081**. **T034** must read it and confirm T031,
   T032 and T033 reached every `findings-*.md` file, naming any gap in the punch-list header instead of
   publishing totals that under-report; **T046** must do the same for T032 and T036–T043 before passing
   V3.
3. **Paths named.** **T041, T042 and T043** now write into the owning finding's record in its
   `findings-*.md` file. **T042** additionally mirrors every named test into a single **Named tests
   index** in `audit/findings.md` (finding, severity, test name, jvm-unit vs instrumented, target file),
   which **T066, T071 and T074** now read from — closing the "no single place to read those names from"
   gap.

A "Two shared bookkeeping files" section was added to the conventions at the top of `tasks.md` defining
both the Sweep log and the validation log, and declaring that appending one row to either is the single
documented exception to the `[P]` different-file rule — append-only, as the task's last action, never
rewriting another task's row. Without that, giving `[P]` tasks T037–T039 and T042–T043 a shared stamp
target would have quietly broken the parallelism contract on line 23.

---

## D4 — T080 overwrites the T004 baseline it needs for a before/after claim

**Tasks affected**: T080; T004 as the owner of the file; T059 as a consumer.

**Artifact the text names**: `audit/evidence/warnings.md` — T080 re-runs the T004 harvest and confirms
*in that same file* that every Principle I warning was resolved by deletion rather than by `@Suppress`.

**What exists instead**: `audit/evidence/warnings.md` is T004's **pre-fix baseline**: the single
`Theme.kt:19` unused-parameter warning, the three release Java `source/target value 8` warnings, and an
explicit caveat about what `kotlinc` does not report. One path is being asked to hold two mutually
exclusive states — the warning set before fixes and the warning set after them.

**Downstream breakage**: T080 breaks itself. Proving "resolved by deletion, not suppression" is
inherently a comparison, and the overwrite destroys the only "before" the comparison needs. T059 cites
`warnings.md` as a dead-code input, so once T080 has run, that citation silently resolves to post-fix
content and any Principle I claim built on it becomes unfalsifiable. T085 and T086 lose the pre-fix
evidence that SC-013 traceability rests on.

**Recommended resolution**: T080 writes a **new** file, `audit/evidence/warnings-post-fix.md`, naming
both commands and both dates, and records the explicit delta against T004's file. Treat
`audit/evidence/warnings.md` as immutable baseline evidence for the remainder of the audit.

**Resolution applied (2026-07-31)** — adopted as recommended. **T080** now names the harvest command,
writes to a **new** `audit/evidence/warnings-post-fix.md` with both commands and both dates and an
explicit delta (resolved / still present / newly introduced), and carries an emphasized instruction to
treat `audit/evidence/warnings.md` as **immutable pre-fix baseline evidence** — with the reason stated,
that a before/after claim cannot survive overwriting its own "before". **T059** is now told to read
`warnings.md` as pre-fix baseline only and pointed at `warnings-post-fix.md` for post-fix state. The
immutability instruction is also recorded in `warnings.md` itself, so a reader who opens the evidence
file without the task list still sees it.

---

## D5 — The warning harvest states a file count that a later task superseded

**Tasks affected**: T004 as author; T059 and T064 as consumers; T080 as the next writer.

**Artifact the text names**: `audit/evidence/warnings.md`, which states *"Exactly one warning across
all 73 main-source files."*

**What exists instead**: The T015 scope-completeness check in `coverage-matrix.md` establishes the real
counts as **68 main-source** files and **42 JVM test** files (68 + 42 + 3 instrumented = 113 total
`.kt`, which reconciles), and records that `plan.md` was corrected accordingly. `warnings.md` was not
updated. Two audit artifacts now state different denominators for the same sweep, and the stale one is
the piece of evidence findings cite.

**Downstream breakage**: T059 derives the unreferenced-symbol inventory from `warnings.md` plus
`reference-index.md`, so any "swept N files" completeness statement inherits the wrong denominator;
T064 then consolidates that into `dead-code-inventory.md`, and T082 may quote it in `report.md`. The
consequence is a weakened completeness claim rather than a wrong finding — but it is precisely the kind
of discrepancy that is expensive to discover during final review.

**Recommended resolution**: Until `warnings.md` is legitimately rewritten, **T059 must cite 68 from the
T015 check in `coverage-matrix.md`, not 73 from `warnings.md`**. When T080 runs, its new post-fix file
(per D4) should state 68 and reference the T015 correction. Not fixed at the time of recording because
`warnings.md` was outside the write scope of the reconciliation that found it, and other agents were
active in this directory.

**Resolution applied (2026-07-31)** — the count was **independently re-verified rather than inherited
from either conflicting source**:

```powershell
Get-ChildItem -Path "app\src\main" -Recurse -Filter *.kt -File | Measure-Object | Select-Object -ExpandProperty Count
# → 68
```

Counted the same way, the full set is **68 main-source + 42 JVM test + 3 instrumented = 113 `.kt`**, with
**zero `.java` files** under `app/src/main`. **68 is correct and 73 is stale**; no third value exists, so
the T015 check stands and `warnings.md` was the only wrong artifact. The 73 originated in `plan.md`,
which T015 corrected without updating `warnings.md`.

A dated **Correction (2026-07-31)** section was appended to `audit/evidence/warnings.md` giving the
command, the reconciliation, the provenance of the 73, and the consumers who must cite 68 — plus a
one-line pointer directly beneath the original "73 main-source files" sentence, since a reader who stops
at that line would otherwise never reach the note. The original evidence text was left verbatim.
**T059** must now cite 68 for any "swept N files" claim, **T064** must state the 68/42/3 denominators for
any coverage claim, and **T080** must state 68 in the post-fix harvest.

## Resolution log — 2026-07-31

| Case | Tasks amended in `tasks.md` | Other files written |
|---|---|---|
| D1 | T047, T048, T049, T050, T051, T052, T053, T054, T055 + phase note above T047 | none — `coverage-matrix.md` deliberately untouched |
| D2 | T016, T035, T046, T058, T065, T082, T086 | none — `validation-log.md` is created by T016 at execution time |
| D3 | T031, T032, T033, T034, T036, T037, T038, T039, T040, T041, T042, T043, T065, T066, T071, T074, T081 + conventions section | none — the Sweep log and Named tests index are written into `findings.md` at execution time |
| D4 | T059, T080 | `audit/evidence/warnings.md` (immutability note, within the correction) |
| D5 | T059, T064, T080 | `audit/evidence/warnings.md` (dated correction note) |

**Checkbox integrity**: **no `[X]`/`[ ]` state was changed by this pass**, and all 89 task IDs, `[P]`
markers and `[USn]` tags are unchanged — verified by re-extracting every task line after the edits. The
raw tally did move while the pass ran: 24 complete / 65 incomplete at the start, 25 / 64 midway, 26 / 63
at the end. Both increments are **T022** and then **T029**, marked complete by the concurrently running
analysis agents. Task text was amended around those lines without disturbing them.

### D2 follow-up — the prediction failed, and the failure is the lesson (recorded 2026-07-31, during T054)

D2's resolution row above assumed `validation-log.md` "is created by T016 at execution time." **It was
not.** T016 was marked `[X]` because the scope gate genuinely happened and was recorded in `spec.md`
line 63 — the substantive half of the task was done — but the file it also required was never written,
and the checkmark hid that for the rest of Phase 4.

The file has now been created retroactively from `spec.md`'s Scope of Review section and the confirmation
exchange, so nothing was fabricated, and T016 is honestly complete. **The generalizable lesson: a task
whose deliverable is "do X *and* record it in file Y" can be half-completed and still get checked, because
the checkbox tracks the memorable half.** D3 flagged the same shape of risk for the in-place sweep tasks
and is the reason those now stamp a Sweep log row. Any remaining task with a two-part deliverable should
be treated as suspect on the recording half specifically — the analysis half tends to get done.

**Not applied**: D2's suggestion that T016 needs no artifact of its own (see that case) — and the
follow-up above vindicates rejecting it, since the missing artifact was detectable only because the task
was defined to produce one. Everything else
was adopted, in two places with the mechanism made explicit rather than left implicit — the `[P]`
exception for the shared Sweep log, and the Named tests index that gives T066/T071/T074 a single lookup.

---

## Recording constraints (original recording pass — superseded by the Resolution log above)

Written during a read-only checkbox reconciliation. Only this file and a two-line pointer near the top
of `tasks.md` were created; no task text, checkbox, findings file, or `coverage-matrix.md` row was
altered. Every claim above was verified against the files as they stood on 2026-07-31, while seven
analysis agents were still appending to `findings-ru*.md` — so counts of *existing* findings files will
rise, which strengthens rather than invalidates D3.

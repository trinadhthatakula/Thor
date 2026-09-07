# Thor service-queue progress

**Updated:** 7 September 2026. Times below are UTC.

**22 agreed milestones: 19 complete, 1 in progress, 2 pending. Three milestones remain.**

**Branch:** `feat/worker-shell-lanes` · **PR:** [#453](https://github.com/trinadhthatakula/Thor/pull/453), open, unmerged, targeting `dev` · **Version:** 1952.

Earlier hosted checkpoint: `98be2f6c`, whose required Zulu CI passed. **The correction checkpoint below has passed local host validation and narrow reviews; fresh hosted CI is still required.** The older CI does not cover these corrections. No release, version bump, merge or physical-device acceptance is part of this work.

## Definite remaining list

| Milestone | Status | What remains | Evidence required to close |
|---|---|---|---|
| **T19 — Shizuku emulator acceptance and latency** | **Pending — blocked** | Establish authorized Shizuku/fixture prerequisites; test real operations, cancellation, reopening, recovery and package conflicts; collect at least 20 warm exports and 20 warm sweeps; remove temporary latency hooks and rebuild. | Genuine Manager consent, scenario postconditions and raw distributions; honest baseline comparison; final APK without hooks. |
| **T21 — Final whole-branch review** | **In progress** | Publish the reviewed, locally validated correction checkpoint and verify current-head CI. All final host gates and narrow reviews passed. | No unresolved verified blocking finding, complete final-source host validation and required CI green; PR stays open. |
| **T22 — Startup and jank check** | **Pending — after T21** | Measure startup and visible jank in a safe existing emulator state; fix only demonstrated regressions; validate/review/push any changes. | Actual measurements with environment limitations; before/after evidence for any correction; final remote state verified. |

**Host builds are complete; build ownership is released.** Publication and CI verification remain inside T21. Reviews, fixture maintenance and validation attempts are not new milestones. No milestone closes merely because a subagent reports completion.

## Current T21 position

Whole-branch primary review and finite security-boundary follow-ups are complete. **Fifteen confirmed candidate IDs resolve to fourteen distinct production defects**, all implemented, focused-tested and narrowly accepted. A missed instrumentation-fixture argument was subsequently corrected and reviewed PASS/PASS without changing assertions or production APIs.

The final Foss lint gate exposed ten errors in five of the reviewed files. The bounded correction is implemented:

- Scope an API30-only test shadow accurately, retaining its existing Robolectric API36 configuration.
- Replace restricted `ViewModelStore.put` calls with public `ViewModelProvider` ownership of the same instance/key, preserving clear/cancellation/admission schedules.
- Replace six `Uri.parse` calls with equivalent `toUri()` calls, preserving the URI expressions and notification identities.

**All seven final host gates pass. Foss and Store each pass 2,643 tests across 220 suites, zero failures/errors/skips; both lint variants have zero errors.** Four APK identities/signatures, actual Koin production compilation and both executed release R8 tasks were independently checked. No `missing_rules.txt` exists. The controller independently verified all 814 input hashes, 931 evidence-file hashes and report/artifact freshness. The exact five-file lint delta passed specification/quality review: five files, 15 hunks, no unread changed hunks or blocking findings.

The APKs were built before the correction commit. Their release metadata therefore embeds `98be2f6c`; the source fingerprint below identifies their actual corrected bytes. No instrumentation execution or device acceptance is implied.

The targeted IDE tool timed out, but the matching Gradle daemon and IDE logs independently confirm that its underlying build completed successfully. That distinction is preserved: the timeout is not relabeled as a successful tool response. Native Foss reports were regenerated after that IDE build.

### Corrections — not additional milestones

| Correction group | Distinct defects | State |
|---|---:|---|
| Rollback ownership, unavailable reinstall inspection, full-UUID notification identity | 3 | Implemented, focused-tested and narrowly reviewed; included in this checkpoint |
| Restore resume, acknowledged UUID, latest-start timeout, notification retry, background key derivation | 5 | Implemented, focused-tested and narrowly reviewed; included in this checkpoint |
| SAF publication recovery and legacy-export package admission | 2 | Implemented, focused-tested and narrowly reviewed under explicit provider assumptions; included in this checkpoint |
| Profile lifetime, Queue observation failure, tile count, logger footer | 4 | Implemented, focused-tested and narrowly reviewed; corrected footer evidence accepted PASS/PASS; included in this checkpoint |

The five-file lint delta is maintenance of this correction set, not five new production defects. All other 45 correction paths remain unchanged. No lint suppression, policy/configuration/dependency change, visibility widening or new production behavior was authorized.

## Current validation evidence

Final-source fingerprint: `c10facb4a2f0c74aa2577123dee96991226f3ba01bd7f5840d6c25f35cc4d712`, covering **814 source/build inputs**, with an explicit **50-path correction inventory**. Exactly the authorized five paths differ from the preceding host snapshot.

| Check | Actual result | Scope / limitation |
|---|---|---|
| **Final-source Foss JVM suite** | **2,643 tests / 220 suites; zero failures/errors/skips** | Forced run, exit 0, 54/54 tasks executed; 06:55–06:56. Saved/native XML, identities, hashes and freshness independently verified. |
| **Final-source Foss lint** | **Exit 0; zero errors, 66 warnings, 9 hints** | Forced run, 70/70 tasks executed; 06:56–06:58. All warnings are `SyntheticAccessor`; fresh XML independently parsed. |
| **Final-source Store JVM suite** | **2,643 tests / 220 suites; zero failures/errors/skips** | Forced run, exit 0, 54/54 tasks executed; 07:09–07:10. Saved/native XML, identities, hashes and freshness independently verified. |
| **Final-source Store release lint** | **Exit 0; zero errors, 53 warnings, 9 hints** | Forced run, 51/51 tasks executed; 07:10–07:12. All warnings are `SyntheticAccessor`; fresh XML independently parsed. |
| **Final APK/Koin/R8 gates** | **Four APKs verified; all forced assemblies passed** | Debug/test: 99/99 tasks; each release: 92/92. Production Koin compilation and both R8 tasks executed, no missing-rules files; signatures, package/version identities, native/saved hashes and freshness verified. |
| Five-file lint delta review | **PASS/PASS; no blocking findings** | All five files / 15 hunks reviewed; no whole-branch re-review. |
| G6 instrumentation fixture maintenance | Compilation and targeted IDE passed; review PASS/PASS | Real package coordinator supplied, existing assertions unchanged. Compilation is not instrumented execution. |
| Web dependency correction | **304 tests / 17 files passed; audit zero vulnerabilities; typecheck/build passed** | Narrow `fast-uri` 3.1.7 lockfile update. Structural accessibility checked 11 pages; contrast excluded. |
| Current-head hosted CI | Pending publication | Required Zulu 21 CI passed on `98be2f6c` (run `34075194730`), before the uncommitted corrections. No remote XML count is claimed. |
| Shizuku runtime acceptance | **Not executed for T19** | Handoff/DAO/JVM/fake tests do not establish real Shizuku operation delivery. |

### Focused review evidence

These suites overlap; their counts must not be added into a full-suite total.

| Slice | Preserved result / qualification |
|---|---|
| Ownership/reinstall/notification | RED 36 tests / 7 intended failures → GREEN 149 tests, zero failures/errors/skips; specification/quality PASS/PASS. |
| Admission/recovery/key derivation | Sixteen distinct reproduced failures → GREEN 142 tests, zero failures/errors/skips; specification/quality PASS/PASS. Android test sources compiled, not executed. |
| Export | Twelve distinct reproduced failures → GREEN 88 tests, zero failures/errors/skips. Narrow review passed with documented provider assumptions, not arbitrary-provider or power-loss durability guarantees. |
| UI/lifecycle | Initial GREEN 82 tests; corrected footer follow-up GREEN 84 tests / 9 suites, zero failures/errors/skips. Final targeted review PASS/PASS. |
| Footer evidence | Genuine mutation RED 4/4 failures. Direct logger fixtures measure 360×480dp and contain 52dp footer actions; separate real Dialog route tests verify SHARE/CANCEL/Background mapping. No claim that the separate Dialog has those dimensions. |
| Architecture-regex correction | RED 4 tests / 3 expected stack overflows → GREEN 8/8; narrow PASS/PASS. No JVM stack/configuration change. Full raw RED XML was not retained; parsed RED identities/counts and fresh GREEN evidence were preserved. |

Review coverage comprises **60 primary reports, six security cross-checks and 12 independent verification reports**, covering all **363 assigned diff segments across 359 paths**, with no missing/extra segments or primary unread ranges. The first eight reviewers failed compaction and returned no reports; none counts as coverage. Fifty-six disclosed security scopes were adjudicated into six finite boundary traces, all closed or corrected. CodeRabbit skipped the 359-file PR because its limit is 150; its SUCCESS check is not review approval.

## Completed milestones

| Task | Completed outcome |
|---|---|
| T01 | Captured the original operation-latency baseline. |
| T02 | Defined durable task contracts/state models. |
| T03 | Added the Room-backed data queue/schema. |
| T04 | Evolved privilege-sweep ownership/claim handling. |
| T05 | Implemented durable data launchers/admission. |
| T06 | Extracted archive execution into queue runners. |
| T07 | Extracted export execution into queue runners. |
| T08 | Added foreground-service platform contracts. |
| T09 | Implemented data-service execution/lifecycle/recovery. |
| T10 | Replaced the sweep store with durable queue handling. |
| T11 | Implemented/activated privilege-sweep service execution. |
| T12 | Cut migrated producers over from WorkManager; retained drain adapters. |
| T13 | Implemented durable queue/history projection (`0fc62497`). |
| T14 | Built Queue screen (`81e7e73f`). |
| T15 | Built task detail/logger UI (`baac6038`). |
| T16 | Wired durable task navigation/foreground actions (`1fe31820`). |
| T17 | Completed durable share preparation, guarded cancellation/recovery cleanup, scoped review and 45/45 focused emulator acceptance (`8efa11a7`, `ba9f8281`). |
| T18 | Completed full host validation, lint corrections, localized progress/count/Stopping notifications and scoped review (`cc67ad66`). |
| T20 | Fact-checked architecture/progress/design/plan documentation committed (`92e7d476`); PR title/body updated, feature commits pushed; branch/base/open/unmerged state verified. |

These completed milestones do not imply T19 or T22 runtime acceptance. Single-app export and bulk share preparation are durable; multi-app “Backup”/export and single-app quick share remain intentional direct paths, as the [worker README](README.md) records.

### Historical host and emulator checkpoints

- **T18:** full Foss and Store each passed **2,566 tests / 212 suites**, zero failures/errors/skips. Foss lint: zero errors / 63 `SyntheticAccessor` warnings / nine hints; Store lint: zero errors / 52 warnings / nine hints. Four APK identities verified. These results predate T21 corrections.
- **T18 source fingerprint:** `5289a966b7d0a957fb66a6c0191b39032c4e817b66d255ddc776cf7d6c584811`. Source `cc67ad66`, intended Studio/AGP build-tool checkpoint `c8da3fb3`; APKs built before those commits and matched to source hashes.
- **T17 emulator:** 45/45 passed (13 handoff + 32 DAO), 90 matched raw start/terminal frames, no failures/errors/skips/crash markers. Approved API36 emulator, before T18; not Shizuku-operation evidence.
- **Pre-lint-correction T21:** Foss and Store each passed 2,643 tests / 220 suites at 06:36–06:39 against fingerprint `e061fa93a35c0420e4caa52c08da6e65aaf8530e6d82ec66c66a905049580c95`. Preserved separately from final-source results.

### Failures retained, not hidden by reruns

- T17 first emulator run: 43/44 passed; one invalid public-export fixture in a private-share expiry test. Fixture corrected without weakening guards; final 45/45. Two transport-blocked retries executed zero tests.
- Earlier full suite: 2,555 tests / one stale expected enum-list failure omitting intentional `BUNDLE_READ`; corrected expectation and subsequent full runs passed.
- T18 initial lint: 56 Foss / 44 Store errors, corrected without blanket suppression. Intermediate callback/projection scaffolds and an invalid oversized test fixture are distinguished from genuine production regressions. A combined multi-variant compilation OOM was recovered with serial targets, not configuration changes.
- T18 narrow review found a missing Stopping notification state. Genuine regressions, minimal correction and scoped PASS/PASS re-review closed it.
- T21 original tile-state assertion observed the wrong tile. Corrected `service.qsTile` mutation tests reproduced both failures; invalid original evidence is not counted. Original footer wrapper did not constrain the separate Dialog; the corrected evidence above closes that claim.
- Final host IDE compilation exposed the missing G6 fixture argument; the initial compiler failure remains preserved. The later lint-fix IDE tool timeout is retained separately from the proven successful underlying build.
- Final Foss lint initially failed with **10 errors: NewApi 1, RestrictedApi 3, UseKtx 6**, plus 66 warnings/nine hints. The five-file correction removes those errors without suppressing checks; fresh final-source Foss lint now passes.

## Blockers and qualifications

1. **Shizuku prerequisites:** the 06:18 read-only check uniquely identified approved `Thor_Root_API36` (then `emulator-5554`), API36, boot complete, SELinux Enforcing. No installed Shizuku Manager/server or established disposable fixture was found. Current authorization/mode and app-accessible Root are unestablished. No broker self-grant, permission/security workaround or physical-device testing occurred.
2. **Local JDK exception:** local evidence uses explicitly pinned Corretto21 because Zulu21 is unavailable. Only the cited hosted run establishes Zulu validation, and it predates current corrections.
3. **Baseline comparability:** original operation baseline API37 differs from approved API36; a matched retained baseline APK is unproven. Current-only distributions cannot establish a speedup. Startup/jank sampling has not begun.
4. **Safe performance fixture:** queues must already be idle; never cancel/delete work or clear data to manufacture a benchmark. Warm startup needs a surviving process and genuinely recreated Activity, not an intent delivered to an existing Activity. No sampling during heavy host builds.
5. **Remaining diagnostics:** current Foss lint retains 66 SyntheticAccessor warnings/nine hints; Store release lint retains 53 SyntheticAccessor warnings/nine hints. No warning-free, post-R8 size or performance-improvement claim. Plural adaptations are automatically checked, not native-speaker reviewed.
6. **Preserved configuration:** AGP9.5.0-alpha04, Studio's intentional Kotlin-option update, Room schema9 and version1952 remain. Baseline-profile module remains excluded. API28/29 archive package-parser runtime verification is still outstanding.
7. **Bounded guarantees:** SAF recovery assumes truthful/stable provider metadata and ordinary create/close/rename behavior. Cleanup timeouts bound cooperative suspension/lock waits, not arbitrary blocking filesystem calls; guarded retry may reclaim inaccessible files later.
8. **Shipping logging:** the bounded static check passed because shipping `DEBUG`/`PRIVILEGE_TRACE` logging is disabled, not because all Throwable forwarding is sanitized. Debug/trace builds are outside that conclusion.
9. **Emulator startup diagnosis:** the earlier emulator process ended about 1.16 seconds after a 1,000ms launch-tool timeout; a tracked background invocation survived and booted. Invocation lifetime is implicated, but the historical exit signal/sender was not captured.

## Separate completed requests

- Merged requested Dependabot/dev updates (`a3fa6321`) and preserved the intentional Android Studio/AGP update.
- Reconciled interrupted T17 edits and stopped overlapping writers; validation uses one build owner.
- Normally booted the approved API36 AVD without a wipe or security/configuration change. Serial numbers are observations, not permanent AVD identity.
- **Storage cleanup complete:** deleted exactly the two verified-unused API37 preparation images, reclaiming **4,573,888,512 allocated bytes (4.260GiB / 4.57GB)**. Installed API36 AVD/SDK, memories, transcripts/recovery inputs and other projects were preserved. The scratch API37 bundle is intentionally incomplete; no full AVD rollback backup is claimed. No further deletion or purge is planned.

## Tracking and reporting rules

The old tracker showed 38 in-progress and 72 pending labels, mostly stale or duplicate wrappers—not jobs running continuously for days. That was my tracking error. Removed 104 redundant unresolved entries without pretending their work was complete; retained historical completed records and the same 22 canonical milestones.

- This document is the human-facing source of truth; fold corrections into their existing milestone.
- Distinguish implemented, compiled, tested, reviewed, committed, pushed and merged.
- Preserve failures as history, but never present superseded results as current.
- Review small deltas narrowly; do not restart completed milestones or whole-branch review.
- Keep #453 open/unmerged for the user's physical-device/emulator verification.

## Evidence pointers

- [Architecture](README.md), [implementation plan](../superpowers/plans/2026-09-03-typed-foreground-service-queues.md), [binding design](../superpowers/specs/2026-09-03-privilege-action-service-design.md), [latency baseline](service-queue-latency-baseline.md).
- Ignored execution workspace: `.superpowers/sdd/2026-09-03-typed-foreground-service-queues/`; its `progress.md` holds detailed handoffs and rulings.
- Current five-file maintenance: `task-21-final-lint-correction-evidence.json`, exact review diff, and preserved `task-21-final-host/09-final-foss-unit/` / `10-final-foss-lint/` reports.
- `task-21-final-host-evidence.json` records the completed seven-gate result; `task-21-final-host-controller-acceptance.json` records independent verification. Earlier failures remain preserved separately; local acceptance does not close hosted CI or device gates.
- Historical T18: `task-18-host-validation/stopping-correction-evidence.json`; prior lint/resumed-host manifests remain preserved.
- Historical T17: `/tmp/thor-task17-install-9e1ac9bc/{results.json,instrumentation-events.json,instrumentation.log}`; earlier RED/transport-blocked attempts remain separately preserved.
- Native reports under `app/build/` may be regenerated; saved reports and source fingerprints identify each historical gate.

# Thor service-queue progress

**Updated:** 7 September 2026. Latest independently checked host reports: 7 September UTC.

**Branch:** `feat/worker-shell-lanes` · **Validated app-source checkpoint:** `cc67ad66` · **Build-tool checkpoint:** `c8da3fb3`

**PR:** [#453](https://github.com/trinadhthatakula/Thor/pull/453), open, unmerged, targeting `dev`. No push during this continuation.

## The actual position

**22 agreed milestones: 18 complete, 1 in progress, 3 pending. Four milestones remain.**

**T18 is complete locally.** The lint/notification corrections are committed as `cc67ad66`. Final full suites passed **2,566 Foss tests and 2,566 Store tests**, with zero failures/errors/skips and no stale XML. Both required lint variants passed with **zero errors**; **63 Foss / 52 Store `SyntheticAccessor` warnings and 9 hints each remain**. Four final APK identities were independently verified. Scoped review found one missing Stopping notification state; genuine regressions reproduced it, the minimal correction passed, and narrow specification/quality re-review returned PASS/PASS with no findings.

**T19 remains prerequisite-blocked:** read-only checks found no installed Shizuku Manager or running Shizuku server on the approved emulator, and no established disposable test fixture. Current Shizuku authorization/mode remain unestablished. No setup, grants or app mutations were made to bypass that blocker.

**T20 is in progress:** finalize and fact-check the worker documentation and update the existing PR. The PR will prominently disclose blocked Shizuku acceptance and remain open/unmerged; publishing the verified host work does not complete or waive T19.

Counts are milestones, not effort percentages or an ETA. Corrections stay inside their existing milestone.

## Definite remaining list

| Milestone | Status | Work remaining | Evidence required to close |
|---|---|---|---|
| **T19 — Shizuku emulator acceptance and latency** | **Pending — blocked** | Establish authorized Shizuku/fixture prerequisites; exercise real operations, cancellation, reopening, recovery and package conflicts; measure at least 20 warm exports and 20 warm sweeps; remove temporary latency hooks and rebuild. | Genuine Manager consent, scenario postconditions/raw distributions, honest baseline comparability and final APK without hooks. Handoff/DAO tests are not Shizuku-operation evidence. |
| **T20 — Update and push the existing PR** | **In progress** | Finish documentation review, commit intended docs, update #453 title/body with migration details and exact evidence, then push with blocked device acceptance prominently disclosed. The inspected Studio/AGP changes are committed separately as `c8da3fb3`. | Remote/local SHAs match; base `dev`; PR open/unmerged; accurate documentation and no unsupported acceptance claim. |
| **T21 — Final whole-branch review** | Pending | Review the complete feature diff against `dev`, independently verify findings, fix real defects, revalidate, check required CI and push corrections. | No verified blocking finding unresolved; required CI green; PR still open. Distinct from completed scoped reviews. |
| **T22 — Startup and jank check** | Pending | Measure cold/warm startup and visible jank; fix only demonstrated regressions; validate/review/push changes. | Before/after measurements for corrections; final remote state verified; PR left for the user's merge. |

**Not part of this work:** releasing a version, bumping to 1953, merging #453, expanding the feature set, or claiming physical-device verification.

## Completed milestones

| Task | Completed outcome |
|---|---|
| T01 | Captured the original operation-latency baseline. |
| T02 | Defined durable task contracts/state models. |
| T03 | Added the Room-backed data-task queue/schema. |
| T04 | Evolved privilege-sweep ownership/claim handling. |
| T05 | Implemented durable data-task launchers/admission. |
| T06 | Extracted archive execution into queue runners. |
| T07 | Extracted export execution into queue runners. |
| T08 | Added foreground-service platform contracts. |
| T09 | Implemented data-service execution/lifecycle/recovery. |
| T10 | Replaced the privilege-sweep store with durable queue handling. |
| T11 | Implemented/activated privilege-sweep service execution. |
| T12 | Cut feature producers over from WorkManager; retained compatibility adapters. |
| T13 | Implemented durable queue/history projection (`0fc62497`). |
| T14 | Built Queue screen (`81e7e73f`). |
| T15 | Built task detail/logger UI (`baac6038`). |
| T16 | Wired durable task navigation/foreground actions (`1fe31820`). |
| T17 | Completed durable share preparation, guarded cancellation/recovery cleanup, scoped review and 45/45 focused emulator acceptance (`8efa11a7`, `ba9f8281`). |
| **T18** | **Completed full host validation, lint corrections, localized progress/queued-count/Stopping notifications and scoped review (`cc67ad66`).** |

These are implementation/validation milestones, not a claim that Shizuku acceptance or final whole-branch review has passed. The worker README explicitly identifies remaining direct producers rather than claiming every operation is durable.

### Additional completed requests

- Merged requested Dependabot/dev updates (`a3fa6321`): Coil 3.6.1, web dependencies and release-action update.
- Preserved the intentional Android Studio/AGP update to **9.5.0-alpha04**; not reverted as unknown churn.
- Reconciled interrupted Task17 edits and stopped overlapping writers. Host builds ran under one owner; the final owner has released that slot.
- Normally booted approved `Thor_Root_API36`; verified AVD name, API 36, boot completion and SELinux Enforcing. Serial `emulator-5554` was verified, not treated as permanent. No wipe or security/configuration change was needed.
- Completed the separately requested storage cleanup below.

## Current validation evidence

| Check | Actual result | Scope/limitation |
|---|---|---|
| **Full Foss JVM suite** | **2,566 tests / 212 suites; zero failures/errors/skips** | Forced run, exit 0; fresh XML independently parsed, 00:46 UTC. |
| **Full Store JVM suite** | **2,566 tests / 212 suites; zero failures/errors/skips** | Forced run, exit 0; fresh XML independently parsed, 00:47 UTC. |
| Foss debug lint | **Exit 0; zero errors, 63 warnings, 9 hints** | Fresh XML independently parsed. All warnings are `SyntheticAccessor`. |
| Store release lint | **Exit 0; zero errors, 52 warnings, 9 hints** | Fresh XML independently parsed. All warnings are `SyntheticAccessor`. |
| Stopping regression | **RED: 4 tests / 2 failures → GREEN: 7 tests / zero failures** | Both real builders lacked Stopping before the production edit; operation/progress/count/UUID assertions retained. |
| Scoped T18 review | **Quality PASS; corrected specification PASS; zero outstanding findings** | Original 27-file review plus narrow two-file correction review; not T21 whole-branch review. |
| Production/instrumented compilation | **Passed** | Six targets passed separately during lint correction; final affected variants compiled through refreshed full suites/assemblies. Final IDE validation preceded native output recreation. |
| Four final APK assemblies | **All exit 0; hashes/sizes independently verified** | Foss debug/test and Foss/Store release. Source digest matches committed files; APKs were built before the checkpoint commit. |
| T17 corrected emulator run | **45/45 passed; zero failures/errors/skips** | 13 handoff + 32 DAO; 90 raw frames, 45 unique matched tests, no unmatched/crash markers. API 36 emulator, before T18 changes. |
| Web dependency validation | **304 tests / 17 files passed; typecheck/build passed** | 55 typed files without diagnostics; a11y checked 11 pages with contrast excluded. Advisory remains open below. |
| Actual Shizuku operation/latency acceptance | **Not executed for T19** | No new instrumented execution after T18 edits; JVM/fake/handoff tests are not real Shizuku evidence. |
| PR | **Open, unmerged, base `dev`** | Remote `591c96ec` when last checked; local T18 checkpoint not pushed. Older CI results do not validate the new local head. |

Final reviewed/committed 27-file source fingerprint: `5289a966b7d0a957fb66a6c0191b39032c4e817b66d255ddc776cf7d6c584811`. Version **1952** and Room schema **9** remain unchanged. Validation used the intended AGP alpha04 change, now retained separately with the inspected Studio update in `c8da3fb3`.

### Historical failures and superseded checks

- T17 initial implementation: 484 JVM tests / 39 suites passed. Cancellation correction: 128 tests / 9 suites passed, including six demonstrated RED→GREEN regressions.
- Initial focused emulator run: **43/44 passed**, one invalid public-export fixture for private-share expiry. Corrected the fixture without weakening DAO guards; final **45/45 passed**. Two intervening retries executed **zero tests** because emulator transport was unavailable.
- Initial full Foss suite: **2,555 tests / one failure**, from a stale expected enum list omitting intentional `BUNDLE_READ`. Focused 1/1 and full Foss/Store 2,555 each passed after that expectation correction.
- Initial lint failed with **56 Foss / 44 Store errors**. Foss: RestrictedApi 14, PluralsCandidate 10, UnusedResources 28, InlinedApi 1, NewApi 2, UseKtx 1. Store: RestrictedApi 5, PluralsCandidate 10, UnusedResources 29. These are corrected; no blanket baseline/suppression was added.
- During lint correction, callback/projection scaffold tests initially failed, alongside the existing singular grammar defect. These are not all preexisting production bugs. A later 64-test run had one invalid oversized fixture; the domain guard was preserved. Focused 67 and full Foss/Store 2,564 each subsequently passed before the Stopping correction.
- One combined multi-variant compilation ran out of memory. Serial one-worker targets recovered without configuration changes. The IDE check regenerated `app/build`, so native reports/APKs were recreated afterward rather than relying on deleted or stale outputs.
- Initial scoped T18 specification review found missing Stopping text during cancellation cleanup. Independent verification confirmed it. Genuine regressions, minimal correction and narrow PASS/PASS re-review closed it; final full totals are 2,566 per variant.

## Known blockers and qualifications

1. **Shizuku prerequisites:** Manager/server and an authorized disposable fixture are absent. Historical grants do not establish current consent. No broker self-grant, adb/root grant or security workaround is permitted.
2. **Local JDK exception:** Zulu 21 is unavailable. Evidence uses explicitly pinned Corretto 21 under the recorded local exception; not Zulu-compliance evidence.
3. **Latency baseline differs:** original API 37 versus current approved API 36; retained comparable historical APK is unproven. Current-only distributions cannot establish a matched speedup.
4. **Pre-existing web advisory:** `fast-uri@3.1.5` has a high-severity advisory through Astro checking dependencies. Passing web tests do not clear it; no fix applied here.
5. **Remaining diagnostics/localization:** 63/52 SyntheticAccessor warnings and nine hints per variant remain accounted for. No mechanical visibility widening or post-R8 performance/size claim. Plural adaptations are authored and automatically checked, not native-speaker-reviewed.
6. **Preserved build-tool state:** AGP alpha04 and Studio's `.idea/kotlinc.xml` removal of explicit Kotlin API/language 2.4 options are intentional and remain separate from the T18 commit.
7. **Cleanup timeout is cooperative:** suspension/lock waiting is bounded, not arbitrary blocking filesystem calls. Inaccessible files may await guarded retry/startup reclamation; deterministic retry coverage exists.
8. **Startup diagnosis limitation:** the original emulator process ended roughly 1.16 seconds after a 1,000 ms context-mode launch timeout. Identical launch through a tracked background process survived and booted. Invocation lifetime is strongly implicated, but historical exit signal/sender were not captured.

## Separate storage cleanup request — complete

The user authorized deletion of verified-unused emulator preparation files only and asked to leave other projects alone. After dependency auditing, exact-file inspection and successful API 36 startup, removed only API 37 scratch `system.img` and `vendor.img`.

- Allocated bytes removed: **4,573,888,512 (4.260 GiB / 4.57 GB)**; observed free-space increase was approximately the same.
- `.claude/projects`: **9,374,552 KiB → 4,907,928 KiB**, about 9.60 GB → 5.03 GB; active logs continue growing.
- Remaining preparation content: **139,752 KiB**, including tooling, installers and recovery inputs.
- Installed API 36 AVD/SDK, memories, transcripts/evidence and other projects were preserved. No purge, retention-setting change or blanket deletion permission was added.
- Unused API 37 scratch bundle is intentionally incomplete until those two images are restored. The historically documented complete AVD rollback backup is absent; the SDK ramdisk backup is not proven to replace it. Stale memory claims were corrected.

This is not another feature milestone. No further project deletion is planned.

## Why the old tracker showed 33+ running tasks

The tracker contained 376 entries: 266 completed, 38 marked in progress and 72 pending. Those labels were not a process monitor: review/fix wrappers and interrupted entries accumulated stale states; they were not all executing continuously for days.

**That was my tracking error.** On 7 September, removed 104 redundant unresolved entries without falsely completing work. Retained historical completed entries and the same 22 canonical milestones. The current counts above—not historical wrapper counts—describe remaining work.

## Reporting rules

- This is the human-facing source of truth. Keep the 22 milestones stable; fold corrections into their parent.
- Distinguish implemented, compiled, tested, reviewed, committed, pushed and merged. Preserve failures as history, but do not present superseded results as current.
- Re-review fixes narrowly; do not restart completed milestones or whole-branch review for each small fix.
- No competing host builds. Timed performance samples must not overlap CPU-heavy host work.
- Keep #453 open/unmerged for the user's physical-device/emulator verification.

## Evidence pointers

- [Worker architecture](README.md), [implementation plan](../superpowers/plans/2026-09-03-typed-foreground-service-queues.md), [binding design](../superpowers/specs/2026-09-03-privilege-action-service-design.md), [latency baseline](service-queue-latency-baseline.md).
- Ignored execution ledger: `.superpowers/sdd/2026-09-03-typed-foreground-service-queues/progress.md`.
- Current T18 host evidence: `task-18-host-validation/stopping-correction-evidence.json` beneath that directory. Prior `lint-correction-evidence.json` and `resumed-host-evidence.json` are preserved historical results.
- Scope/review packages: `task-18-review-package.json` and `task-18-stopping-review-package.json` in the same workspace.
- Corrected T17 emulator: `/tmp/thor-task17-install-9e1ac9bc/{results.json,instrumentation-events.json,instrumentation.log}`.
- Original emulator RED: `/tmp/thor-task17-instrumentation-o7DJx4/`; blocked retries: `/tmp/thor-task17-device-rerun-pOPu4e/`, `/tmp/thor-task17-device-rerun-4edacde6/`.
- Normal startup evidence: `/tmp/thor-task17-startup-evidence-20260906T224026Z.json`.
- Native JVM reports: `app/build/test-results/testFossDebugUnitTest/`, `testStoreDebugUnitTest/`; lint: `app/build/reports/lint-results-fossDebug.*`, `lint-results-storeRelease.*`.

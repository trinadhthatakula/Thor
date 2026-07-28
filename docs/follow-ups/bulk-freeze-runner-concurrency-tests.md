# Follow-up: `BulkFreezeRunner` has no tests, and its bugs are the kind only `runTest` finds

**Status:** Deferred — blocked on the same test dependency as
[`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md); these two should land together.
**Severity:** Minor (test-coverage gap; the two known defects are fixed).
**Effort:** medium.
**Raised by:** the final whole-branch review of `fix/freezer-tile-rework` (2026-07-28), which found
two real concurrency defects in a class that no test touches.

Files: `app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt:124 (fun launch)`,
`app/src/main/java/com/valhalla/thor/data/freezer/BulkFreezeRunner.kt:209 (private suspend fun run)`,
`app/build.gradle.kts:237 (testImplementation)`, `gradle/libs.versions.toml`

## Problem

`BulkFreezeRunner` is the only stateful concurrent class on the branch. It owns a process-lifetime
scope, a `@Synchronized` job slot with same-op coalescing and conflicting-op replacement, a shared
`Semaphore`, a 30 s deadline raced against a cancellable `join()`, a `NonCancellable` post-run sweep,
and an identity-guarded `finally` that decides whether `_isRunning` may be cleared. It has **zero
tests**. Everything shipped on this branch that *is* tested (`tileVisualFor`, `tileStateFor`,
`bulkActionFor`, `bulkResultMessage`, `freezableCandidates`) is a pure function extracted precisely
because the runner itself was untestable.

Both defects the final review found are exactly what a `runTest` would have caught first:

1. **Cold-start no-op.** `run()` read `privilegeManager.state.value` — an unready snapshot
   (`active = NONE`, `isReady = false`) — so a bulk run started before the first probe returned
   `null` and silently did nothing. A test that starts a run against a `PrivilegeManager` fake whose
   state has not yet emitted asserts this in three lines.
2. **Abandoned workers outliving their batch.** The `finally` cancelled the worker job without
   joining it, and the `Semaphore` was a per-run local, so a replacement batch could allocate a fresh
   `Semaphore(5)` while up to five abandoned workers were still mutating packages. With FREEZE and
   UNFREEZE on one runner (D4) those workers are not idempotent with respect to each other: a
   straggling FREEZE could re-disable a package the replacing UNFREEZE had just enabled. A test that
   launches FREEZE, launches UNFREEZE, and asserts the recorded call order per package catches it.

The suite cannot express either today: `app/build.gradle.kts` declares exactly one unit-test
dependency, `testImplementation(libs.junit)`. No `kotlinx-coroutines-test` means no virtual time,
no `TestScope`, and no way to control the interleaving these tests are *about* — a real-time version
would be a flaky sleep race.

## Sketch

Not a decision, just the shape:

1. **Share the dependency work with `viewmodel-behavior-tests.md`.** That follow-up already proposes
   adding `kotlinx-coroutines-test` (pinned to the existing `kotlinxCoroutines` version) and turbine.
   Do it once, use it for both. Turbine is what makes the `isRunning` / `lastResult` / `freezableCount`
   emission *sequences* assertable rather than just their final values.

2. **Inject the scope, or inject the dispatcher.** The runner already takes `@Named("io")
   CoroutineDispatcher`, so a `StandardTestDispatcher` goes in through the existing seam; the
   internal `CoroutineScope(SupervisorJob() + io)` then runs on test time. Confirm the deadline race
   (`withTimeoutOrNull(DEADLINE_MS)`) actually observes virtual time through that seam before
   writing tests that depend on it.

3. **Fake, don't mock.** `FreezerRepository`, `PreferenceRepository` and `ManageAppUseCase` are all
   interfaces or thin wrappers. A `ManageAppUseCase` fake that records `(pkg, action)` in order and
   can be told to hang on a specific package is the whole test harness — that "hang on demand" knob
   is what makes the abandoned-worker case reproducible.

4. **Behaviours worth pinning:**
   - a run started before `PrivilegeState.isReady` waits and then proceeds, rather than no-opping
     (defect 1, mutation-checked: reverting to `state.value` must fail this);
   - same-op coalescing returns the *same* `Job` and does not double-act on any package;
   - conflicting-op replacement cancels the previous batch, and no package receives a FREEZE action
     after the replacing UNFREEZE batch has started (defect 2);
   - a worker that ignores cancellation cannot make the handoff wait longer than `CANCEL_GRACE_MS`;
   - never more than `MAX_CONCURRENT` workers in flight *across* two overlapping generations —
     the reason the semaphore is an instance field;
   - `_isRunning` stays `true` across a conflicting-op handoff (the identity guard in `finally`) and
     clears exactly once at the end;
   - `_lastResult` is published for FREEZE only, and `BulkResult.op` matches the run that produced it;
   - the deadline produces `unresolved > 0` rather than counting unreached packages as failures.

## Acceptance

- The two defects above are covered by tests that fail against the pre-fix code.
- No wall-clock sleeps: the whole class's tests run in virtual time.
- Mutation-checked, matching the bar set in `viewmodel-behavior-tests.md`: hoisting the `Semaphore`
  back into `run()`, or dropping the `first { it.isReady }`, must each fail at least one test.

# Follow-up: `BulkFreezeRunner` has no tests, and its bugs are the kind only `runTest` finds

**Status:** Deferred — the dependency blocker is **gone**, the design blocker is not. See
"Blocker" below; this no longer waits on
[`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md), which has landed.
**Severity:** Minor (test-coverage gap; the two known defects are fixed).
**Effort:** medium.
**Raised by:** the final whole-branch review of `fix/freezer-tile-rework` (2026-07-28), which found
two real concurrency defects in a class that no test touches.
**Revised:** 2026-07-30 on `chore/tier0-batch-1`, after the test dependencies were added and the
suite was attempted.

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

## Blocker

The blocker this document originally named — "`app/build.gradle.kts` declares exactly one unit-test
dependency, `testImplementation(libs.junit)`" — is **stale**. `kotlinx-coroutines-test` and turbine
were both added on `chore/tier0-batch-1`, and `BulkFreezeWorkerTest` already uses `runTest`. Virtual
time is available. Do not re-file that as the reason.

The real blocker is a missing seam, and it is in main source, not the test config.
`BulkFreezeRunner`'s constructor takes four collaborators a JVM unit test cannot produce, and Kotlin
classes are final by default, so none of them can be subclassed by a hand-written fake either.

**Three, as of 2026-07-31.** The `AppListViewModel` behaviour tests hit the same wall and cut four
ports through it, one of which lands here for free — see the first row. That is also the shape the
remaining three want: a port covering only what *this* caller uses, implemented by the existing
class via `@Single(binds = [...])` so the other call sites do not move.

| Collaborator | Declared at | Why a fake cannot stand in |
|---|---|---|
| ~~`PrivilegeManager`~~ | `data/manager/PrivilegeManager.kt:43` | ~~final; its `init` registers Shizuku binder and permission listeners~~ **Solved.** `domain/repository/PrivilegeStateProvider.kt` is the read-only port, shipped for the `AppListViewModel` tests, and `state` — read once, in `run()` — is all this runner ever takes from the manager. One constructor parameter, no new interface |
| `AppFreezeStateReader` | `data/freezer/AppFreezeStateReader.kt:27` | final, over the abstract `android.content.pm.PackageManager` |
| `UadHelper` | `data/source/local/UadHelper.kt:49` | final, over `android.content.Context` |
| `BulkResultNotifier` | `data/freezer/BulkResultNotifier.kt:36` | final, over `android.content.Context` |

`:app` has no mocking library on purpose — every existing test is a hand-written fake — so "add
mockk" is a change to the suite's whole approach, not a shortcut around this. The honest options are
to extract an interface for each of the four (the pattern `FreezerRepository` and `PreferenceRepository`
already follow, which is why *those* two are fakeable), or to lift the runner's decision logic out of
the class the way `freezableCandidates` and `bulkActionFor` were already lifted. Either is real main-source
work and should be decided deliberately rather than smuggled in with a test PR.

What *was* reachable without a seam is now covered:
`app/src/test/java/com/valhalla/thor/data/freezer/BulkFreezeWorkerTest.kt` pins what one worker does
to one package when the privilege layer refuses and when the batch is cancelled under it. That is a
worker body, not the runner's machinery — the acceptance criteria below remain unmet.

## Sketch

Not a decision, just the shape:

1. ~~**Share the dependency work with `viewmodel-behavior-tests.md`.**~~ Done — `kotlinx-coroutines-test`
   and turbine are in the catalog and on `:app`'s test classpath. Turbine is what will make the
   `isRunning` / `lastResult` / `freezableCount` emission *sequences* assertable rather than just their
   final values.

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
   - coalescing still happens when the repeated request is **not** the most recent launch: a
     watchlist FREEZE with a profile FREEZE queued behind it, tapped again from the tile, must get
     the running job back rather than a second watchlist batch appended to the chain — and the
     mirror case, a repeat arriving while a conflicting op is tearing the chain down, must **not**
     coalesce onto a doomed run;
   - conflicting-op replacement cancels the previous batch, and no package receives a FREEZE action
     after the replacing UNFREEZE batch has started (defect 2);
   - a worker that ignores cancellation cannot make the handoff wait longer than `CANCEL_GRACE_MS`;
   - never more than `MAX_CONCURRENT` workers in flight *across* two overlapping generations —
     the reason the semaphore is an instance field;
   - `runningRequests` keeps the *running* request across a conflicting-op handoff and empties
     exactly once at the end;
   - `runningRequests` holds **both** requests while a same-op run of a different scope is queued
     behind another — a watchlist FREEZE with a profile FREEZE serialized behind it must not drop
     the watchlist entry, which is what made the QS tile paint idle mid-freeze;
   - a run cancelled *before its body ever started* still leaves `runningRequests` — it never
     reaches the coroutine's `finally`, so retirement hangs off `invokeOnCompletion`, and a
     regression here strands the tile on "Freezing…" for the process lifetime;
   - `_lastResult` is published for FREEZE only, and `BulkResult.op` matches the run that produced it;
   - the deadline produces `unresolved > 0` rather than counting unreached packages as failures.

## Acceptance

- The two defects above are covered by tests that fail against the pre-fix code.
- No wall-clock sleeps: the whole class's tests run in virtual time.
- Mutation-checked, matching the bar set in `viewmodel-behavior-tests.md`: hoisting the `Semaphore`
  back into `run()`, or dropping the `first { it.isReady }`, must each fail at least one test.

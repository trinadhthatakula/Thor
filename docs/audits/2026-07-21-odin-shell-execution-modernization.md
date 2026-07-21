# Odin (`:suCore`) — branch review + shell-execution modernization analysis

**Date:** 2026-07-21
**Branch reviewed:** `feat/odin-phase1-sucore-prep` (PR #264 → dev)
**Method:** dynamic workflow, 20 agents / 2 concurrent tracks (~1.16M tokens). Track A = adversarial branch review (4 dimension reviewers → per-finding skeptic verifiers). Track B = shell-execution modernization (4 multi-modal readers → 3-approach design panel → judged → synthesized → completeness critic). Raw run: `wf_75d9da4f-59b`.

---

## Part 1 — Branch code review: CLEAN

4 findings raised across the 4 dimensions; **all 4 refuted** under adversarial verification (High confidence each). No changes required on the branch.

| Dimension | Finding | Verdict / why refuted |
|-----------|---------|------------------------|
| concurrency | `onShellDied` dispatch via `executor.execute` unguarded → `RejectedExecutionException` could break fail-fast | **Not an issue.** In-repo the only path passes `UiThreadHandler.executor` (coroutine-backed `mainScope.launch`, never rejects); the 2-arg `getShell(executor, …)` is never called with a custom executor. Purely hypothetical for a not-yet-published API. |
| api-boundary | coroutines `implementation` leaks `Flow`/`suspend` into public API | **Not a current defect.** `:suCore` is still consumed via `project(":suCore")`; no external compile-classpath break exists yet. Valid *only* as a Phase 2 publish item (already on the roadmap: switch to `api`). |
| api-boundary | `MainShell.get()` rethrows `CancellationException` on a plain `Exception` | **Not an issue.** The rethrow guard is correct/defensive. |
| kotlin-idiom | public-API coroutines dep declared `implementation` | Duplicate of the api-boundary finding; same Phase 2 disposition. |

**Conclusion:** the Phase-1 branch is sound as shipped. The one substantive theme (coroutines dependency scope) is already tracked as a Phase 2 publish task.

---

## Part 2 — Current-state map of how Odin executes shell commands

Odin is libsu-derived: **one persistent `su` process**, commands multiplexed over a **single stdin**, output demultiplexed by a **random-UUID sentinel** that also frames the exit code; a **hand-rolled `ReentrantLock`/`Condition`/`ArrayDeque` serial scheduler**; **dual `FutureTask` stream gobblers**; a **`CountDownLatch`-backed `Future`**. A thin, already-coroutine `ktx/` facade (`getShellAwait`, `await`, `asFlow`, `ShellRepository`) sits on top; Thor consumes shell exclusively via `ShellRepository`.

**Verified real defects in the current engine/facade** (independent of any redesign):
- `ktx/ShellRepository.kt:58` — `runInternal` swallows `CancellationException` (its sibling `isRootGranted` at :31 correctly rethrows). Live structured-concurrency bug.
- `ktx/ShellExtensions.kt` `await()` — uses single-arg `submit(cb)` → forces a **per-command Main-thread hop** (`Dispatchers.Main.immediate`) then bounces back to IO.
- `ktx/ShellExtensions.kt` `asFlow()` — `trySend` result ignored → **silently drops lines under backpressure** (the exact firehose, e.g. `logcat`, it exists for); only stdout wired; `close()` carries no cause; empty `awaitClose` → the underlying job leaks and can block the pipe. (No consumers yet — shape still free.)
- Lossy `Result<List<String>>` discards **exit code + stderr**, cascading into a concrete app bug: `RootSystemGateway.executeShellCommand` (:558-566) **hard-codes exit code 0** on success, so extensions can never see the real code/stderr.
- `internal/ResultFuture.cancel()` is a no-op (`isCancelled()` always false).
- `internal/ShellImpl` `SyncTask` handoff **swallows `InterruptedException`** (uninterruptible-wait hang class).
- `internal/JobTask.run()` — if `source.serve()` throws before `END_CMD`, both gobblers block on `readLine` forever (thread leak).

---

## Part 3 — Design panel (3 competing approaches, judged)

| Approach | Score | Core idea |
|----------|-------|-----------|
| minimal-risk (ABI-preserving adapters) | 82 | Keep Java Job/exec API; add coroutine/Flow adapters; modernize internals only |
| flow-first (idiomatic-max) | 82 | `suspend exec` + `Flow<ShellLine>` primary; structured concurrency throughout; new major |
| **phased-incremental** ✅ backbone | **88** | adapters → internals → flow-first, gated by a frozen, CI-enforced ABI |

The synthesis takes the **phased-incremental backbone** and grafts the two highest-leverage cross-candidate ideas: (a) the **pre-freeze publish-hardening gate** (`explicitApi()` + binary-compatibility-validator + `maven-publish` + `fun interface` SAM conversion) landed *before* anything freezes; (b) the **one irreversible `ShellResult(code, stdout, stderr)` decision** that fixes `executeShellCommand` and subsumes the redundant "invisible transport-cause" machinery.

---

## Part 4 — Final proposal (governing principle: *lock the surface, then rewrite the engine behind a CI-enforced-stable ABI*)

Folds into the existing Odin roadmap. Constraints treated as non-negotiable: persistent single-shell stdin/stdout pipe in a separate process; Binder/AIDL RootService; published-library ABI + Java-interop; `minSdk 24`.

### Phase 2 (with extract + publish 1.0.0)
- **Step A — free ktx correctness fixes (no ABI change), shippable now:** rethrow `CancellationException` in `runInternal`; switch `await()` to `submit(null, cb)` to kill the per-command Main-thread hop.
- **Step B — pre-freeze gate (do FIRST among surface work):** enable `explicitApi()` strict mode + binary-compatibility-validator (checked-in api dump in CI) + `maven-publish`; convert `Shell.ResultCallback`/`GetShellCallback` to `fun interface` (binary-compatible — `onShellDied` is a default method).
- **Step C — the one irreversible shape decision (must ship IN 1.0.0):** `data class ShellResult(code, stdout, stderr)` + a new `suspend fun exec(vararg cmd): ShellResult` with a **one-axis failure split** — transport failure (shell death / broken pipe / `code == -1`) **throws**; any command completion **returns** carrying the real 1..255 exit code + stderr. Migrate `RootSystemGateway.executeShellCommand` (fixes the hard-coded exit-0 bug). Keep `runCommand(): Result<List<String>>` as a `@Deprecated` shim. Gate streaming behind `@RequiresOptIn ExperimentalShellStreaming` so it stays OUT of the frozen surface. **→ tag & publish 1.0.0 with a deliberately-frozen Java+Kotlin ABI.**

### Phase 3 (post-1.0, internal, behind the frozen ABI)
- **Step D (patch):** `EXECUTOR.asCoroutineDispatcher()` seam; back `ResultFuture` with `CompletableDeferred` (keeps the `java.util.concurrent.Future` type, real best-effort `cancel()`); collapse `UiThreadHandler.runAndWait`.
- **Step E (patch):** `JobTask` dual-gobbler → `coroutineScope { async out/err }` on the seam dispatcher; fix the `serve()`-before-`END_CMD` gobbler thread leak (close the stream so `readLine` unblocks). Both drains stay concurrent.
- **Step F (patch):** make the `SyncTask` handoff wait **interruptible/cancellable** (the real hang fix); add a **pending-only** task-abandon hook wired to `invokeOnCancellation` (`await`/`getShellAwait`) and `awaitClose` (streaming). Never interrupt an in-flight command. *(shellCheck-timeout unification here is cleanup, not a correctness fix — see the critic caveat below, which disputes even this framing.)*
- **Step G (minor, additive):** graduate streaming to `Flow<ShellLine>` (stdout/stderr-tagged, eager buffer, `close(cause)`) running on a **dedicated shell** so a non-terminating stream (`logcat`) never wedges the shared main shell; drop `@RequiresOptIn`; `@Deprecated` `CallbackList`.

### Phase 4 (next major, OPTIONAL — only if justified)
- **Step H:** single-consumer `Channel`-actor scheduler + `Mutex`-coalesced `MainShell` acquisition. **Hard constraint:** `execTask` currently runs `exec0` on the *calling thread* (`ShellImpl.kt:290`), which is what keeps re-entrant Binder threads (RootService) from deadlocking on a separate consumer — an actor changes *who* runs `exec0`. Preserve that fast path + Binder re-entrancy or drop the rewrite. Behavioral/ABI change → major-gated.

### Out of scope (explicit)
Retyping/removing the public `@JvmField var Shell.EXECUTOR`; making pipe I/O non-blocking / `readLine` cancellable (impossible with a persistent-process pipe); interrupting an in-flight command (desyncs sentinel framing); a *public* `suspend` replacement for `exec/submit/enqueue` (not Java-interop friendly — keep suspend additive + ktx-only); changing the RootService Binder/AIDL contract, the su spawn/handshake, the UUID sentinel, or `minSdk`.

---

## Part 5 — Completeness critic caveats (READ BEFORE ACTING)

1. **⚠️ The MainShell init-hang is NOT fully eliminated at the thread level.** `withTimeoutOrNull` is *cooperative*: it frees the awaiting **coroutine** (so `isRootGranted` returns `false` — the follow-up's stated acceptance IS met and the UI never hangs), but it **cannot unblock an `EXECUTOR` worker parked in a blocking `shellCheck().get()` / `@Synchronized MainShell.get()`**. If a held monitor serializes subsequent acquisitions behind a hung init, threads leak and later probes queue behind it. **This is the same hang class as the original follow-up** — the user-visible symptom is fixed by Task 1, but the residual thread/monitor wedge is real and is what Step F (interruptible `SyncTask` wait) must actually address. Do **not** treat Step F's timeout-unification as mere cleanup. *(Claim grounded in the engine readers; confirm `@Synchronized`/blocking `get()` on `MainShell.get`/`shellCheck` when implementing Step F.)*
2. **RootService/Binder IPC path unmodeled.** Every fix targets the local `MainShell`. Whether remote RootService jobs return the same lossless `ShellResult`, and how AIDL marshals code/stderr across Binder, is unaddressed — yet extensions (the reason for publishing) may run privileged ops there. Decide the split-result contract.
3. **Multi-command granularity half-fixed.** `exec(vararg cmd)` returns ONE `ShellResult`, so `runCommands` still collapses per-command exit codes/stderr. Consider `Flow<CommandOutcome>` for the multi-command case.
4. **Dedicated streaming shell** doesn't inherit the main shell's cwd/env/root-auth; per-stream `su` spawn cost + re-auth are unmentioned.
5. **ABI-safety wording to correct:** `explicitApi()` narrowing implicitly-public decls to `internal` **is** a break (the "churny first dump" admits it); `fun interface` is binary-safe only if no Java anonymous subclass overrides `onShellDied`'s default (unverified).

---

## How this feeds the Odin roadmap
- **Phase 1 (this PR):** unaffected — review clean, merge as-is.
- **Phase 2:** already includes the `api`-scope switch; ADD Steps A/B/C (they shape the frozen 1.0.0 surface — cheapest to do before the first publish).
- **Phase 3/4:** Steps D–H are post-publish, ABI-safe-by-construction internal modernization.
- The residual init-hang thread wedge (caveat #1) is the highest-priority correctness item and re-opens the "MainShell follow-up" at the thread level for Step F.

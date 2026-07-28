// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkAction
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.domain.model.bulkActionFor
import com.valhalla.thor.domain.model.freezableCandidates
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs bulk freeze/unfreeze for every surface that needs it — the QS tile and the launcher
 * Freeze-all / Unfreeze-all shortcuts.
 *
 * As a @Single it owns a process-lifetime scope, which is the point: a QS shade collapse
 * destroys the TileService, and pinning the batch to a service-lifetime scope would leave a
 * partial freeze. Because the scope lives here rather than in a companion object on the
 * service, nothing retains the destroyed service.
 */
@Single
class BulkFreezeRunner(
    private val freezerRepository: FreezerRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val privilegeManager: PrivilegeManager,
    private val stateReader: AppFreezeStateReader,
    private val notifier: BulkResultNotifier,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _freezableCount = MutableStateFlow<Int?>(null)

    /**
     * How many watchlist apps could be **frozen** right now; null until the first sweep lands.
     *
     * Freeze-specific by definition, not by accident. This used to be "candidates for whichever
     * op swept last", which meant an UNFREEZE run left it holding the unfreeze count: after
     * Unfreeze-all it published 0 — "nothing to freeze" — at the exact moment every restored app
     * had become freezable. The tile is freeze-only (D1), so one op-agnostic count had no
     * consistent meaning for the only thing that reads it.
     */
    val freezableCount: StateFlow<Int?> = _freezableCount.asStateFlow()

    private val _lastResult = MutableStateFlow<BulkResult?>(null)

    /** Outcome of the last completed run, consumed once by whoever displays it. */
    val lastResult: StateFlow<BulkResult?> = _lastResult.asStateFlow()

    private val _runningOp = MutableStateFlow<BulkOp?>(null)

    /**
     * The op currently running, or null. Carries the op rather than a bare Boolean for the same
     * reason [freezableCount] is freeze-specific: a shared "is running" flag painted the
     * freeze-only tile as WORKING while a background Unfreeze-all shortcut ran.
     */
    val runningOp: StateFlow<BulkOp?> = _runningOp.asStateFlow()

    private var activeJob: Deferred<BulkResult?>? = null
    // B-1: track op alongside job so UNFREEZE during a FREEZE run starts its own batch rather
    // than silently returning the wrong job.
    private var activeOp: BulkOp? = null

    // Instance-scoped, NOT per run. A per-run Semaphore bounds one generation only: when a
    // conflicting op replaces an in-flight batch, workers abandoned by that batch may still be
    // parked in a blocking binder call, and a fresh Semaphore(5) would let five more start on
    // top of them. Sharing it caps total in-flight workers across generations.
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /**
     * Re-derive how many watchlist apps could be frozen and publish it to [freezableCount].
     *
     * Always sweeps for [BulkOp.FREEZE], whatever op prompted the sweep — see [freezableCount]
     * for why the published count is freeze-specific.
     *
     * B-4: confined to [io] so callers on the main thread (e.g. Task 7's tile) do not run
     * N PackageManager binder calls on the UI thread.
     */
    suspend fun refreshFreezableCount() {
        withContext(io) {
            val watchlist = freezerRepository.getAllPackageNames()
            _freezableCount.value =
                freezableCandidates(watchlist, BulkOp.FREEZE, stateReader::stateOf).size
        }
    }

    /**
     * [refreshFreezableCount] with its own failure handling, for the fire-and-forget sweep the
     * post-run finally abandons on timeout. It runs on [scope], so an escaping exception would
     * reach Android's default uncaught handler and kill the process — `SupervisorJob` stops a
     * failing child from cancelling its siblings, not from being reported (B-2). Nothing awaits
     * this job, so nothing else can catch it.
     */
    private suspend fun sweepFreezableCount() {
        try {
            refreshFreezableCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("BulkFreezeRunner", "post-run candidate sweep failed", e)
        }
    }

    /**
     * Start a bulk run and return it. Returns the in-flight run instead of starting a second one
     * — the pre-rework tile spawned a fresh unbounded batch over the same packages on every tap.
     *
     * Returning the run rather than making callers watch [runningOp] is deliberate: a fast run
     * can clear runningOp before an observer starts collecting, and `await()` has no such window.
     * The awaited value is the [BulkResult], or null when the run was a no-op (no privilege, or
     * nothing to act on) — which is what lets a caller that *is* allowed to show UI, such as
     * `FreezerLaunchActivity`, report an outcome the notifier may have had to drop.
     *
     * Same-op coalescing: a second tap for the same op returns the existing run.
     *
     * Conflicting-op replacement: the new job cancels the previous one and joins it before
     * touching any package. That handoff is **bounded, not absolute**. What actually holds:
     *
     * - the outgoing run gets [CANCEL_GRACE_MS] to unwind, so every worker that observes
     *   cancellation is finished before the replacement starts;
     * - [semaphore] is an instance field, so the number of workers in flight is capped at
     *   [MAX_CONCURRENT] **across** generations, not per run;
     * - a worker parked in a blocking binder call still can outlive the grace period — the
     *   Shizuku and Dhizuku reflection paths do not observe cancellation — and mutate its
     *   package after the replacement batch has begun.
     *
     * So the overlap is bounded in worker count and in expected duration; it is not
     * eliminated. This matters because FREEZE and UNFREEZE are not idempotent with respect to
     * each other: a straggling FREEZE worker can re-disable a package that the replacing
     * UNFREEZE batch already enabled, and that package is not in the unfreeze target list
     * either (it was not frozen when the candidates were computed).
     */
    @Synchronized
    fun launch(op: BulkOp): Deferred<BulkResult?> {
        // Same-op coalescing: return the in-flight run unchanged.
        activeJob?.takeIf { it.isActive && activeOp == op }?.let { return it }

        // Conflicting-op replacement: capture the previous job BEFORE overwriting the slot.
        // The new job's first act is cancelAndJoin(), so it will not touch any package until
        // the previous one has fully unwound (run body AND finally sweep). _runningOp stays
        // set across the handoff because the replaced job's finally guards the clear with an
        // identity check (see finally block).
        val previous = activeJob

        _runningOp.value = op
        val job = scope.async {
            try {
                // Wait for any replaced job to fully settle (including its NonCancellable sweep)
                // before we start. cancel() alone would not be enough — it returns immediately
                // and puts us back in a two-jobs-in-flight state.
                previous?.cancelAndJoin()
                // B-8: run() returns null for no-op cases (no privilege, empty target list);
                // do not publish BulkResult(0,0,0) because the tile already communicates
                // "nothing to freeze" — a false "Froze 0 apps" message is worse than silence.
                run(op)?.also { result ->
                    // Publish to _lastResult for FREEZE only. The tile is freeze-only (D1) and
                    // _lastResult is process-lifetime, so parking an UNFREEZE result here would
                    // render it in the tile subtitle the next time the shade opens — possibly
                    // hours later. The notification reports both ops, but only when the user
                    // permits notifications; the returned Deferred is what gives an UNFREEZE
                    // caller an unconditional surface. See the BulkResultNotifier KDoc.
                    //
                    // UNFREEZE clears instead of skipping: an unconsumed FREEZE result (one the
                    // shade never opened to display) describes packages this run has just
                    // unfrozen, so leaving it would show "Froze N apps" on a tile that is now
                    // READY again. Stale either way — drop it.
                    _lastResult.value = if (op == BulkOp.FREEZE) result else null
                    if (result.total > 0) notifier.post(result)
                }
            } catch (e: CancellationException) {
                // B-2: CancellationException is an Exception in Kotlin; rethrowing here keeps
                // structured cancellation intact and prevents the finally sweep from misreading
                // a cancelled run as a normal completion.
                throw e
            } catch (e: Exception) {
                // B-2: an escaped exception from run() (e.g. Room/DataStore IOException, binder
                // death) would reach Android's default uncaught handler and kill the process.
                // Log it and clear any stale result so the surface is not left showing the
                // previous run's outcome.
                Logger.e("BulkFreezeRunner", "bulk $op run failed unexpectedly", e)
                _lastResult.value = null
                null
            } finally {
                // B-6: sweep before clearing runningOp for a stable combined emission.
                // NOT runCatching: CancellationException is an Exception in Kotlin, and this
                // runs in a finally where cancellation is exactly what we may be unwinding
                // from. withContext(NonCancellable) lets the sweep finish even then, and the
                // narrow catch keeps a PackageManager failure from masking the real outcome.
                //
                // R4: race a cancellable join() rather than wrapping refreshFreezableCount()
                // in withTimeoutOrNull directly — for the same reason B-10 gives below. The
                // sweep ends in freezableCandidates(), a plain non-suspending filter over one
                // PackageManager binder call per watchlist entry, so a timeout wrapped around
                // it has no suspension point to fire at and would wait out the loop anyway.
                // Only join() on a separate job is actually interruptible. Without a bound
                // this is the one unbounded wait in the class: NonCancellable means a wedged
                // binder (PMS lock contention right after a batch of pm disable/uninstall is
                // not exotic) pins _runningOp and activeJob for the process lifetime, leaving
                // the tile on "Freezing…" and every later tap coalescing into the stuck job.
                withContext(NonCancellable) {
                    val sweep = scope.launch { sweepFreezableCount() }
                    if (withTimeoutOrNull(SWEEP_GRACE_MS) { sweep.join() } == null) {
                        // Abandoned, not cancelled: it still publishes when the binder returns.
                        // The cost is that runningOp clears against the pre-run count for one
                        // paint — the tile re-sweeps on every onStartListening, so it heals.
                        Logger.d(
                            "BulkFreezeRunner",
                            "post-run candidate sweep exceeded ${SWEEP_GRACE_MS}ms; releasing the run"
                        )
                    }
                }
                // R1/R2: clear runningOp only if this is still the active job. A replacement
                // job has overwritten activeJob and is already running; it must keep
                // _runningOp set across the handoff. Identity check under the monitor for
                // the same reason as the launch guard: the slot must be read atomically.
                val thisJob = coroutineContext[Job]
                synchronized(this@BulkFreezeRunner) {
                    if (activeJob === thisJob) {
                        _runningOp.value = null
                        // Drop the slot too: otherwise activeJob retains a completed Job and
                        // activeOp a stale op for the process lifetime. Only safe under the
                        // same identity check — a replacement job owns the slot now.
                        activeJob = null
                        activeOp = null
                    }
                }
            }
        }
        activeJob = job
        activeOp = op
        return job
    }

    /**
     * Clear [lastResult] after it has been shown, consuming only the exact result that was
     * displayed. Compare-and-set means a new result published between the caller's read and
     * this call is not silently dropped.
     */
    fun consumeResult(shown: BulkResult) {
        _lastResult.compareAndSet(shown, null)
    }

    // B-8: returns null when there is nothing to act on, so the caller can skip publishing a
    // misleading BulkResult(0,0,0).
    private suspend fun run(op: BulkOp): BulkResult? {
        // Await readiness here rather than trusting the caller to have awaited it. PrivilegeState
        // starts at active = NONE / isReady = false, and hasAnyPrivilege is `active != NONE`, so
        // the raw snapshot reads false until BOTH the probe and the first DataStore emission have
        // landed — reading it directly turns a cold-start run into a silent no-op.
        //
        // The tile happens to be safe because it awaits `isReady` itself before calling launch()
        // (it needs the resolved state to paint), but FreezerLaunchActivity's shortcuts run their
        // own direct probe and call straight through. The runner must not depend on its caller
        // having awaited anything.
        if (!privilegeManager.state.first { it.isReady }.hasAnyPrivilege) return null

        val watchlist = freezerRepository.getAllPackageNames()
        val targets = freezableCandidates(watchlist, op, stateReader::stateOf)
        if (targets.isEmpty()) return null

        // Resolved once per run, not per package: the mode cannot change mid-batch, and the
        // op × mode decision is a pure function so it can be unit-tested away from binders.
        val action = bulkActionFor(op, preferenceRepository.userPreferences.first().freezerMode)

        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)

        // B-10: The batch is a child of `scope`, NOT of the withTimeoutOrNull block below.
        // withTimeoutOrNull is a scoping builder, so wrapping the batch directly would cancel
        // the children and then block until they finish. The Shizuku and Dhizuku reflection
        // paths make blocking binder calls that do not observe cancellation; the root path
        // (Odin exec) does unwind promptly via suspendCancellableCoroutine, but defense in
        // depth requires the deadline to hold for the paths that do not. Racing a cancellable
        // join() instead lets us abandon and report on time.
        val job = scope.launch {
            targets.forEach { pkg ->
                launch {
                    semaphore.withPermit {
                        ensureActive()
                        val result = try {
                            when (action) {
                                BulkAction.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                                BulkAction.SUSPEND -> manageAppUseCase.setAppSuspended(pkg, true)
                                BulkAction.DISABLE -> manageAppUseCase.setAppDisabled(pkg, true)
                            }
                        } catch (e: CancellationException) {
                            // CancellationException IS an Exception in Kotlin, so it must be
                            // rethrown ahead of any broad catch or ensureActive() above is
                            // defeated and the batch silently ignores cancellation.
                            throw e
                        } catch (e: Exception) {
                            Logger.e("BulkFreezeRunner", "bulk $op failed for $pkg", e)
                            Result.failure(e)
                        }
                        // B-3: this split is safe only if a cancelled worker throws rather
                        // than returning Result.failure. SystemRepositoryImpl.runGatewayAction
                        // re-throws CancellationException, and the withContext(IO) wrappers
                        // around each call site are cancellation-atomic, so a deadline-killed
                        // worker unwinds before reaching this branch.
                        if (result.isSuccess) succeeded.incrementAndGet()
                        else failed.incrementAndGet()
                    }
                }
            }
        }

        try {
            val finished = withTimeoutOrNull(DEADLINE_MS) { job.join() } != null
            if (!finished) {
                Logger.d("BulkFreezeRunner", "bulk $op hit the ${DEADLINE_MS}ms deadline")
            }
        } finally {
            // R3: cancel the sibling batch job on every exit — timeout, normal completion
            // (harmless no-op on an already-finished job), or outer cancellation of run()'s
            // caller. Without this, cancelling the outer job (e.g. cancelAndJoin() from a
            // replacement launch) leaves up to MAX_CONCURRENT workers mutating packages
            // unsupervised because the sibling relationship means they are not automatically
            // cancelled with the caller.
            job.cancel()
            // ...then give the workers that DO observe cancellation a bounded window to
            // actually unwind, so they are gone before a replacement batch starts. Two
            // requirements shape this:
            //   NonCancellable — we are usually in this finally *because* the caller was
            //     cancelled, so a plain join() would resume-with-cancellation immediately and
            //     wait for nothing.
            //   withTimeoutOrNull — an unbounded join() re-introduces exactly the hang the R3
            //     comment above warns about, because the Shizuku/Dhizuku binder calls never
            //     observe cancellation and would hold this until they return on their own.
            // The grace period is short: it covers unwinding, not completion.
            withContext(NonCancellable) { withTimeoutOrNull(CANCEL_GRACE_MS) { job.join() } }
        }

        return BulkResult(
            op = op,
            total = targets.size,
            succeeded = succeeded.get(),
            failed = failed.get(),
        )
    }

    private companion object {
        const val MAX_CONCURRENT = 5
        const val DEADLINE_MS = 30_000L

        // How long a run waits for its abandoned workers to unwind before returning. Bounded on
        // purpose: the Shizuku/Dhizuku paths make blocking binder calls that ignore
        // cancellation, so an unbounded join would stall the next batch behind them.
        const val CANCEL_GRACE_MS = 2_000L

        // How long the post-run finally waits for the candidate sweep before releasing the run.
        // Deliberately NOT CANCEL_GRACE_MS: that bounds an unwind, this bounds real work — one
        // binder call per watchlist entry, against a PackageManager that has just been made to
        // disable or uninstall a batch of packages. Generous enough that it is a wedge detector
        // rather than a load limiter, because a sweep that misses only costs one stale paint.
        const val SWEEP_GRACE_MS = 10_000L
    }
}

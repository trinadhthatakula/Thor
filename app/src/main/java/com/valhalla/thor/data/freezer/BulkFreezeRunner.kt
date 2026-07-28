// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.freezableCandidates
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
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

    /** Candidates for the last [refreshCandidates] sweep; null until the first sweep lands. */
    val freezableCount: StateFlow<Int?> = _freezableCount.asStateFlow()

    private val _lastResult = MutableStateFlow<BulkResult?>(null)

    /** Outcome of the last completed run, consumed once by whoever displays it. */
    val lastResult: StateFlow<BulkResult?> = _lastResult.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var activeJob: Job? = null
    // B-1: track op alongside job so UNFREEZE during a FREEZE run starts its own batch rather
    // than silently returning the wrong job.
    private var activeOp: BulkOp? = null

    /**
     * Re-derive how many apps [op] would act on and publish it to [freezableCount].
     *
     * B-4: confined to [io] so callers on the main thread (e.g. Task 7's tile) do not run
     * N PackageManager binder calls on the UI thread.
     */
    suspend fun refreshCandidates(op: BulkOp) {
        withContext(io) {
            val watchlist = freezerRepository.getAllPackageNames()
            _freezableCount.value = freezableCandidates(watchlist, op, stateReader::stateOf).size
        }
    }

    /**
     * Start a bulk run and return its job. Returns the in-flight job instead of starting a
     * second one — the pre-rework tile spawned a fresh unbounded batch over the same packages
     * on every tap.
     *
     * Returning the job rather than making callers watch [isRunning] is deliberate: a fast run
     * can flip isRunning back to false before an observer starts collecting, and `join()` has
     * no such window.
     *
     * Same-op coalescing: a second tap for the same op returns the existing job.
     * Conflicting-op replacement: the new job cancels and joins the previous before running,
     * so at most one batch is ever touching packages concurrently.
     */
    @Synchronized
    fun launch(op: BulkOp): Job {
        // Same-op coalescing: return the in-flight job unchanged.
        activeJob?.takeIf { it.isActive && activeOp == op }?.let { return it }

        // Conflicting-op replacement: capture the previous job BEFORE overwriting the slot.
        // The new job's first act is cancelAndJoin(), so it will not touch any package until
        // the previous one has fully unwound (run body AND finally sweep). _isRunning stays
        // true across the handoff because the replaced job's finally guards the clear with an
        // identity check (see finally block).
        val previous = activeJob

        _isRunning.value = true
        val job = scope.launch {
            try {
                // Wait for any replaced job to fully settle (including its NonCancellable sweep)
                // before we start. cancel() alone would not be enough — it returns immediately
                // and puts us back in a two-jobs-in-flight state.
                previous?.cancelAndJoin()
                // B-8: run() returns null for no-op cases (no privilege, empty target list);
                // do not publish BulkResult(0,0,0) because the tile already communicates
                // "nothing to freeze" — a false "Froze 0 apps" message is worse than silence.
                run(op)?.let { result ->
                    _lastResult.value = result
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
            } finally {
                // B-6: sweep before clearing isRunning for a stable combined emission.
                // NOT runCatching: CancellationException is an Exception in Kotlin, and this
                // runs in a finally where cancellation is exactly what we may be unwinding
                // from. withContext(NonCancellable) lets the sweep finish even then, and the
                // narrow catch keeps a PackageManager failure from masking the real outcome.
                try {
                    withContext(NonCancellable) { refreshCandidates(op) }
                } catch (e: Exception) {
                    Logger.e("BulkFreezeRunner", "post-run candidate sweep failed", e)
                }
                // R1/R2: clear isRunning only if this is still the active job. A replacement
                // job has overwritten activeJob and is already running; it must keep
                // _isRunning = true across the handoff. Identity check under the monitor for
                // the same reason as the launch guard: the slot must be read atomically.
                val thisJob = coroutineContext[Job]
                synchronized(this@BulkFreezeRunner) {
                    if (activeJob === thisJob) _isRunning.value = false
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

        val useSuspend = op == BulkOp.FREEZE &&
                preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND

        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT)

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
                            when {
                                op == BulkOp.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                                useSuspend -> manageAppUseCase.setAppSuspended(pkg, true)
                                else -> manageAppUseCase.setAppDisabled(pkg, true)
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
            // cancelled with the caller. Do not add job.join() here — that re-introduces an
            // unbounded wait (deferred B-11).
            job.cancel()
        }

        return BulkResult(
            total = targets.size,
            succeeded = succeeded.get(),
            failed = failed.get(),
        )
    }

    private companion object {
        const val MAX_CONCURRENT = 5
        const val DEADLINE_MS = 30_000L
    }
}

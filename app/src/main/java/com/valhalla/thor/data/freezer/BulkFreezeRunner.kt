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

    /** Re-derive how many apps [op] would act on and publish it to [freezableCount]. */
    suspend fun refreshCandidates(op: BulkOp) {
        val watchlist = freezerRepository.getAllPackageNames()
        _freezableCount.value = freezableCandidates(watchlist, op, stateReader::stateOf).size
    }

    /**
     * Start a bulk run and return its job. Returns the in-flight job instead of starting a
     * second one — the pre-rework tile spawned a fresh unbounded batch over the same packages
     * on every tap.
     *
     * Returning the job rather than making callers watch [isRunning] is deliberate: a fast run
     * can flip isRunning back to false before an observer starts collecting, and `join()` has
     * no such window.
     */
    @Synchronized
    fun launch(op: BulkOp): Job {
        activeJob?.takeIf { it.isActive }?.let { return it }
        _isRunning.value = true
        val job = scope.launch {
            try {
                _lastResult.value = run(op)
            } finally {
                _isRunning.value = false
                // The sweep re-derives real state, so a killed or truncated batch self-heals:
                // whatever is left simply shows up as the next count.
                //
                // NOT runCatching: CancellationException is an Exception in Kotlin, and this
                // runs in a finally where cancellation is exactly what we may be unwinding
                // from. withContext(NonCancellable) lets the sweep finish even then, and the
                // narrow catch keeps a PackageManager failure from masking the real outcome.
                try {
                    withContext(NonCancellable) { refreshCandidates(op) }
                } catch (e: Exception) {
                    Logger.e("BulkFreezeRunner", "post-run candidate sweep failed", e)
                }
            }
        }
        activeJob = job
        return job
    }

    /** Clear [lastResult] after it has been shown, so a later shade-open does not replay it. */
    fun consumeResult() {
        _lastResult.value = null
    }

    private suspend fun run(op: BulkOp): BulkResult {
        if (!privilegeManager.state.value.hasAnyPrivilege) return BulkResult(0, 0, 0)

        val watchlist = freezerRepository.getAllPackageNames()
        val targets = freezableCandidates(watchlist, op, stateReader::stateOf)
        if (targets.isEmpty()) return BulkResult(0, 0, 0)

        val useSuspend = op == BulkOp.FREEZE &&
                preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND

        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val semaphore = Semaphore(MAX_CONCURRENT)

        // The batch is a child of `scope`, NOT of the withTimeoutOrNull block below. That is
        // the whole trick: withTimeoutOrNull is a scoping builder, so wrapping the batch
        // directly would cancel the children and then block until they finish — and these
        // children are blocking shell/binder calls that never observe cancellation, i.e. it
        // would wait for exactly the thing the timeout exists to escape. Racing a cancellable
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
                        if (result.isSuccess) succeeded.incrementAndGet()
                        else failed.incrementAndGet()
                    }
                }
            }
        }

        val finished = withTimeoutOrNull(DEADLINE_MS) { job.join() } != null
        if (!finished) {
            // Best-effort. Any op already blocked in the shell runs to completion in the
            // background; freezing is idempotent, so that is harmless and the next sweep
            // shows the truth.
            job.cancel()
            Logger.d("BulkFreezeRunner", "bulk $op hit the ${DEADLINE_MS}ms deadline")
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

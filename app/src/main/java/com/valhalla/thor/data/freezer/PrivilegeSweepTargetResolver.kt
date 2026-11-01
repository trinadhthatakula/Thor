// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.source.local.UadHelper
import com.valhalla.thor.data.source.local.UadSnapshot
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.FreezeCandidate
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.freezableCandidates
import com.valhalla.thor.domain.model.normalizeSweepTargets
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Android-backed inputs whose snapshots must be taken before a durable sweep is enqueued. */
interface PrivilegeSweepResolutionRuntime {
    val userId: Int

    /** Returns one candidate lookup closed over one UAD snapshot for the complete resolution pass. */
    fun candidatesFor(op: BulkOp): (String) -> FreezeCandidate
}

@Single(binds = [PrivilegeSweepResolutionRuntime::class])
class DefaultPrivilegeSweepResolutionRuntime(
    private val stateReader: AppFreezeStateReader,
    private val uadHelper: UadHelper,
) : PrivilegeSweepResolutionRuntime {
    override val userId: Int get() = thorUserId

    override fun candidatesFor(op: BulkOp): (String) -> FreezeCandidate {
        val uad = if (op == BulkOp.FREEZE) uadHelper.snapshot() else UadSnapshot.UNFILTERED
        return { packageName -> stateReader.candidateOf(packageName, uad) }
    }
}

/** Both directions from one live watchlist read. Mixed lists restore frozen apps first. */
data class WatchlistFreezeCandidates(
    val freezeTargets: List<String>,
    val unfreezeTargets: List<String>,
) {
    val operation: BulkOp get() = if (unfreezeTargets.isNotEmpty()) BulkOp.UNFREEZE else BulkOp.FREEZE
    val targets: List<String> get() = if (operation == BulkOp.UNFREEZE) unfreezeTargets else freezeTargets
}

/**
 * Resolves mutable UI/domain selections into the immutable snapshot persisted for WorkManager.
 *
 * This class owns no execution, lifecycle, coalescing, or result state. All target and preference
 * reads finish before [PrivilegeSweepSpec] is handed to the durable controller.
 */
@Single
class PrivilegeSweepTargetResolver(
    private val freezerRepository: FreezerRepository,
    private val freezeProfileRepository: FreezeProfileRepository,
    private val preferenceRepository: PreferenceRepository,
    private val runtime: PrivilegeSweepResolutionRuntime,
) {
    suspend fun resolveWatchlistCandidates(): WatchlistFreezeCandidates {
        val members = freezerRepository.getAllPackageNames()
        if (members.isEmpty()) return WatchlistFreezeCandidates(emptyList(), emptyList())
        // The freeze verdict carries UAD policy, but never blocks recovery of a frozen app.
        val candidateOf = runtime.candidatesFor(BulkOp.FREEZE)
        val freeze = mutableListOf<String>()
        val unfreeze = mutableListOf<String>()
        for (packageName in members) {
            val candidate = candidateOf(packageName)
            when (candidate.state) {
                FreezeState.ACTIVE -> if (!candidate.blockedFromFreeze) freeze.add(packageName)
                FreezeState.FROZEN -> unfreeze.add(packageName)
                FreezeState.ABSENT -> Unit
            }
        }
        return WatchlistFreezeCandidates(normalizeSweepTargets(freeze), normalizeSweepTargets(unfreeze))
    }

    suspend fun resolve(
        request: BulkRequest,
        source: PrivilegeSweepSource,
    ): PrivilegeSweepSpec {
        val members = when (val scope = request.scope) {
            BulkScope.Watchlist -> freezerRepository.getAllPackageNames()
            is BulkScope.Profile -> freezeProfileRepository.packagesOf(scope.id)
            BulkScope.ManagedApps -> {
                require(request.op == BulkOp.UNFREEZE) { "Combined membership is a recovery-only scope" }
                (freezerRepository.getAllPackageNames() + freezeProfileRepository.allProfilePackageNames())
                    .distinct()
            }
        }
        val targets = if (members.isEmpty()) {
            emptyList()
        } else {
            freezableCandidates(members, request.op, runtime.candidatesFor(request.op))
        }
        return resolveSelection(
            operation = request.op.toSweepOperation(),
            packageNames = targets,
            source = source,
            freezerMode = request.mode,
            profileId = (request.scope as? BulkScope.Profile)?.id,
        )
    }

    suspend fun resolveSelection(
        operation: PrivilegeSweepOperation,
        packageNames: Collection<String>,
        source: PrivilegeSweepSource,
        freezerMode: FreezerMode? = null,
        profileId: Long? = null,
        addToFreezer: Boolean = false,
    ): PrivilegeSweepSpec {
        val resolvedMode = if (operation == PrivilegeSweepOperation.FREEZE) {
            freezerMode ?: preferenceRepository.userPreferences.first().freezerMode
        } else {
            null
        }
        return PrivilegeSweepSpec(
            operation = operation,
            packageNames = normalizeSweepTargets(packageNames),
            freezerMode = resolvedMode,
            addToFreezer = addToFreezer,
            userId = runtime.userId,
            source = source,
            profileId = profileId,
        )
    }
}

internal fun BulkOp.toSweepOperation(): PrivilegeSweepOperation = when (this) {
    BulkOp.FREEZE -> PrivilegeSweepOperation.FREEZE
    BulkOp.UNFREEZE -> PrivilegeSweepOperation.UNFREEZE
}

/** Process-owned enqueue scope so short-lived tile/activity surfaces never own the handoff. */
@Single
class PrivilegeSweepSurfaceLauncher(
    private val resolver: PrivilegeSweepTargetResolver,
    private val controller: PrivilegeSweepController,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val watchlistToggleMutex = Mutex()

    /** The process owns both fresh-state resolution and enqueue, even after the shade closes. */
    fun launchWatchlistToggle(source: PrivilegeSweepSource): Deferred<PrivilegeSweepLaunchResult> =
        scope.async {
            watchlistToggleMutex.withLock {
                launchWatchlistToggleSurfaceSweep(resolver, controller, source)
            }
        }

    fun launch(
        request: BulkRequest,
        source: PrivilegeSweepSource,
    ): Deferred<PrivilegeSweepLaunchResult> = scope.async {
        launchSurfaceSweep(resolver, controller, request, source)
    }
}

/** A running tile sweep wins over a new toggle; idle taps resolve their direction from live state. */
suspend fun launchWatchlistToggleSurfaceSweep(
    resolver: PrivilegeSweepTargetResolver,
    controller: PrivilegeSweepController,
    source: PrivilegeSweepSource,
): PrivilegeSweepLaunchResult {
    suspend fun runningSweep(): PrivilegeSweepLaunchResult.Accepted? {
        val retained = controller.observeLatest(source).first() ?: return null
        if (retained.phase != PrivilegeSweepPhase.QUEUED && retained.phase != PrivilegeSweepPhase.RUNNING) {
            return null
        }
        return PrivilegeSweepLaunchResult.Accepted(retained.requestId, retained.workId, coalesced = true)
    }

    runningSweep()?.let { return it }
    val candidates = resolver.resolveWatchlistCandidates()
    val spec = resolver.resolveSelection(
        operation = candidates.operation.toSweepOperation(),
        packageNames = candidates.targets,
        source = source,
    )
    // Another surface may have accepted work while the Room/preference reads were suspended.
    runningSweep()?.let { return it }
    return controller.launch(spec)
}

/** Reconnects a short-lived surface to accepted work instead of enqueueing a duplicate request. */
suspend fun launchSurfaceSweep(
    resolver: PrivilegeSweepTargetResolver,
    controller: PrivilegeSweepController,
    request: BulkRequest,
    source: PrivilegeSweepSource,
): PrivilegeSweepLaunchResult {
    val retained = controller.observeLatest(source).first()
    val requestedOperation = request.op.toSweepOperation()
    if (retained?.operation == requestedOperation &&
        (retained.phase == PrivilegeSweepPhase.QUEUED ||
            retained.phase == PrivilegeSweepPhase.RUNNING)
    ) {
        return PrivilegeSweepLaunchResult.Accepted(
            requestId = retained.requestId,
            workId = retained.workId,
            coalesced = true,
        )
    }
    return controller.launch(resolver.resolve(request, source))
}

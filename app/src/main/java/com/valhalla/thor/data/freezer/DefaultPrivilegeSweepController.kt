// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.valhalla.thor.data.backup.job.ThorJobNotificationCapability
import com.valhalla.thor.data.backup.job.enqueueUniqueJob
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.model.normalizeSweepTargets
import com.valhalla.thor.domain.model.profileIdsFromSourceAssociations
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** WorkManager-backed implementation of the durable privilege-sweep boundary. */
@Single(binds = [PrivilegeSweepController::class])
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPrivilegeSweepController internal constructor(
    private val store: PrivilegeSweepStore,
    private val privilegeState: PrivilegeStateProvider,
    private val notifications: ThorJobNotificationCapability,
    private val workManager: PrivilegeSweepWorkManager,
    private val clock: PrivilegeSweepClock,
    private val reconciler: PrivilegeSweepReconciler,
    private val gate: PrivilegeSweepProcessGate,
    private val queueCanceller: SweepQueueCanceller,
    private val rootLaneStatusSource: RootLaneStatusSource,
) : PrivilegeSweepController {

    override val activeRequests: Flow<List<PrivilegeSweepStatus>> =
        store.observeRetained().flatMapLatest { snapshots ->
            if (snapshots.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(snapshots.map(::observeSnapshot)) { statuses -> statuses.toList() }
            }
        }

    override suspend fun launch(spec: PrivilegeSweepSpec): PrivilegeSweepLaunchResult {
        if (!privilegeState.state.first { it.isReady }.hasAnyPrivilege) {
            return PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoPrivilege)
        }
        if (!notifications.canPostJobs()) {
            return PrivilegeSweepLaunchResult.Rejected(
                PrivilegeSweepLaunchRejection.NotificationsRequired
            )
        }
        val targets = normalizeSweepTargets(spec.packageNames)
        if (targets.isEmpty()) {
            return PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoTargets)
        }

        val requestId = UUID.randomUUID()
        val work = OneTimeWorkRequestBuilder<PrivilegeSweepWorker>()
            .setInputData(workDataOf(SWEEP_REQUEST_ID_KEY to requestId.toString()))
            .build()
        val callerJob = currentCoroutineContext()[Job]

        val result = gate.serialized {
            // Once this critical section has inserted a Room snapshot, cancellation cannot release the
            // gate until WorkManager's Operation and any required rollback have both settled.
            withContext(NonCancellable) {
                reconciler.reconcileInsideGate()
                // Cancellation before persistence owes no WorkManager handoff. Check the caller's job
                // explicitly because this block itself is NonCancellable.
                callerJob?.ensureActive()

                when (val created = store.createOrFindEquivalent(
                    NewPrivilegeSweepSnapshot(
                        requestId = requestId,
                        workId = work.id,
                        operation = spec.operation,
                        freezerMode = spec.freezerMode,
                        userId = spec.userId,
                        source = spec.source,
                        createdAtEpochMs = clock.nowMs(),
                        targets = targets,
                        sourceAssociations = spec.sourceAssociations,
                    )
                )) {
                    is SweepCreateResult.Equivalent -> PrivilegeSweepLaunchResult.Accepted(
                        requestId = created.snapshot.requestId,
                        workId = created.snapshot.workId,
                        coalesced = true,
                    )

                    is SweepCreateResult.Created -> {
                        val enqueued = enqueueUniqueJob(THOR_SWEEP_CHAIN, work) {
                            workManager.enqueue(work)
                        }
                        if (enqueued == null) {
                            store.delete(created.snapshot.requestId)
                            PrivilegeSweepLaunchResult.Rejected(
                                PrivilegeSweepLaunchRejection.EnqueueFailed(
                                    "WorkManager rejected the sweep request"
                                )
                            )
                        } else {
                            PrivilegeSweepLaunchResult.Accepted(
                                requestId = created.snapshot.requestId,
                                workId = created.snapshot.workId,
                                coalesced = false,
                            )
                        }
                    }
                }
            }
        }
        // Preserve cancellation, but only after the Operation and rollback invariant above is settled.
        callerJob?.ensureActive()
        return result
    }

    override suspend fun cancelQueue() {
        queueCanceller.cancelQueue()
    }

    override fun observe(requestId: UUID): Flow<PrivilegeSweepStatus?> =
        store.observe(requestId).flatMapLatest { snapshot ->
            if (snapshot == null) {
                flowOf(null)
            } else {
                observeSnapshot(snapshot)
            }
        }

    override fun observeLatest(source: PrivilegeSweepSource): Flow<PrivilegeSweepStatus?> =
        store.observeRetained(source).flatMapLatest { snapshots ->
            val latest = snapshots.firstOrNull()
            if (latest == null) {
                flowOf(null)
            } else {
                observeSnapshot(latest)
            }
        }

    private fun observeSnapshot(snapshot: StoredPrivilegeSweep): Flow<PrivilegeSweepStatus> =
        combine(
            observePersistedWorkState(snapshot),
            rootLaneStatusSource.statuses,
        ) { (persisted, workState), laneStatuses ->
            persisted.toStatus(
                workState = workState,
                rootLaneDegraded = laneStatuses[PrivilegeExecutionLane.SWEEP]?.mode ==
                        RootLaneMode.DEGRADED,
            )
        }

    private fun observePersistedWorkState(
        snapshot: StoredPrivilegeSweep,
    ): Flow<Pair<StoredPrivilegeSweep, SweepWorkState?>> =
        workManager.observeState(snapshot.workId).transformLatest { workState ->
            if (workState != null || snapshot.terminalState != null) {
                emit(snapshot to workState)
                return@transformLatest
            }

            // Room is committed before WorkManager accepts a launch. Wait for that handoff before
            // treating an absent WorkInfo as an orphan, then refresh both stores while holding the
            // same gate. A rejected enqueue deletes its snapshot and therefore emits nothing here.
            gate.serialized {
                val persisted = store.load(snapshot.requestId) ?: return@serialized null
                persisted to workManager.currentState(persisted.workId)
            }?.let { emit(it) }
        }

    private fun StoredPrivilegeSweep.toStatus(
        workState: SweepWorkState?,
        rootLaneDegraded: Boolean,
    ): PrivilegeSweepStatus {
        val phase = terminalState?.toPhase() ?: when (workState) {
            SweepWorkState.BLOCKED, SweepWorkState.ENQUEUED -> PrivilegeSweepPhase.QUEUED
            SweepWorkState.RUNNING -> PrivilegeSweepPhase.RUNNING
            SweepWorkState.CANCELLED -> PrivilegeSweepPhase.CANCELLED
            SweepWorkState.SUCCEEDED, SweepWorkState.FAILED, null ->
                PrivilegeSweepPhase.OBSERVER_FAILURE
        }
        return PrivilegeSweepStatus(
            requestId = requestId,
            workId = workId,
            operation = operation,
            source = source,
            phase = phase,
            total = targets.size,
            succeeded = succeeded,
            failed = failed,
            busy = busy,
            unresolved = if (terminalState == null) {
                targets.size - succeeded - failed - busy
            } else {
                unresolved
            },
            rootLaneDegraded = rootLaneDegraded,
            profileIds = profileIdsFromSourceAssociations(sourceAssociations),
        )
    }

    private fun StoredSweepTerminal.toPhase(): PrivilegeSweepPhase = when (this) {
        StoredSweepTerminal.SUCCEEDED -> PrivilegeSweepPhase.SUCCEEDED
        StoredSweepTerminal.PARTIAL -> PrivilegeSweepPhase.PARTIAL
        StoredSweepTerminal.CANCELLED -> PrivilegeSweepPhase.CANCELLED
        StoredSweepTerminal.FAILED -> PrivilegeSweepPhase.FAILED
    }
}

/** The real WorkManager adapter. Tests substitute a deterministic operation and state source. */
@Single(binds = [PrivilegeSweepWorkManager::class])
internal class WorkManagerPrivilegeSweepWorkManager(
    private val context: Context,
) : PrivilegeSweepWorkManager {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun enqueue(work: OneTimeWorkRequest): Operation =
        workManager.beginUniqueWork(
            THOR_SWEEP_CHAIN,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            work,
        ).enqueue()

    override fun observeState(workId: UUID): Flow<SweepWorkState?> =
        workManager.getWorkInfoByIdFlow(workId).map { it?.state?.toSweepState() }

    override suspend fun currentState(workId: UUID): SweepWorkState? =
        observeState(workId).first()

    private fun WorkInfo.State.toSweepState(): SweepWorkState = when (this) {
        WorkInfo.State.BLOCKED -> SweepWorkState.BLOCKED
        WorkInfo.State.ENQUEUED -> SweepWorkState.ENQUEUED
        WorkInfo.State.RUNNING -> SweepWorkState.RUNNING
        WorkInfo.State.SUCCEEDED -> SweepWorkState.SUCCEEDED
        WorkInfo.State.FAILED -> SweepWorkState.FAILED
        WorkInfo.State.CANCELLED -> SweepWorkState.CANCELLED
    }
}

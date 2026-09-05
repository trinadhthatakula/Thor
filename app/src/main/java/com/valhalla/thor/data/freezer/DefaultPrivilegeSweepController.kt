// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.valhalla.thor.data.service.ServiceStartFailure
import com.valhalla.thor.data.service.ServiceStartResult
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchRejection
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.normalizeSweepTargets
import com.valhalla.thor.domain.model.profileIdsFromSourceAssociations
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepBlockReason
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.domain.repository.PrivilegeSweepRequestState
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepCreateResult
import com.valhalla.thor.util.ServiceQueueEvent
import com.valhalla.thor.util.ServiceQueueLatencyProbe
import com.valhalla.thor.util.ServiceQueueOperation
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/** Foreground-service-backed implementation of the durable privilege-sweep boundary. */
@Single(binds = [PrivilegeSweepController::class])
class DefaultPrivilegeSweepController internal constructor(
    private val store: PrivilegeSweepStore,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
    private val wakeSignal: PrivilegeQueueWakeSignal,
    private val queueCanceller: SweepQueueCanceller,
    private val rootLaneStatusSource: RootLaneStatusSource,
) : PrivilegeSweepController {

    override val activeRequests: Flow<List<PrivilegeSweepStatus>> = combine(
        store.observeRetained(),
        rootLaneStatusSource.statuses,
    ) { snapshots, lanes ->
        val degraded = lanes[PrivilegeExecutionLane.SWEEP]?.mode == RootLaneMode.DEGRADED
        snapshots.map { it.toStatus(degraded) }
    }

    override suspend fun launch(spec: PrivilegeSweepSpec): PrivilegeSweepLaunchResult {
        val targets = normalizeSweepTargets(spec.packageNames)
        if (targets.isEmpty()) {
            return PrivilegeSweepLaunchResult.Rejected(PrivilegeSweepLaunchRejection.NoTargets)
        }

        return gate.serialized {
            val nowMs = clock.nowMs()
            val created = store.createOrFindEquivalent(
                NewPrivilegeSweepSnapshot(
                    requestId = UUID.randomUUID(),
                    workId = newPrivilegeServiceExecutionId(),
                    operation = spec.operation,
                    freezerMode = spec.freezerMode,
                    userId = spec.userId,
                    source = spec.source,
                    createdAtEpochMs = nowMs,
                    targets = targets,
                    sourceAssociations = spec.sourceAssociations,
                )
            )
            val snapshot = when (created) {
                is SweepCreateResult.Created -> created.snapshot
                is SweepCreateResult.Equivalent -> {
                    val reason = created.snapshot.blockReason
                    if (reason != null) {
                        store.resumeBlockedRequest(
                            requestId = created.snapshot.requestId,
                            expectedReason = reason,
                            nowMs = nowMs,
                        )
                    }
                    store.load(created.snapshot.requestId) ?: created.snapshot
                }
            }
            ServiceQueueLatencyProbe.mark(
                ServiceQueueOperation.PRIVILEGE_SWEEP,
                ServiceQueueEvent.DURABLE_ACCEPTED,
            )
            val start = wakeSignal.wake(snapshot.requestId)
            if (start is ServiceStartResult.Rejected) {
                store.markUnclaimedStartBlocked(
                    requestId = snapshot.requestId,
                    reason = if (
                        start.reason == ServiceStartFailure.NOTIFICATION_CHANNEL_BLOCKED
                    ) {
                        PrivilegeSweepBlockReason.START_BLOCKED_NOTIFICATION
                    } else {
                        PrivilegeSweepBlockReason.START_BLOCKED
                    },
                    nowMs = clock.nowMs(),
                )
            }
            PrivilegeSweepLaunchResult.Accepted(
                requestId = snapshot.requestId,
                workId = snapshot.executionId,
                coalesced = created is SweepCreateResult.Equivalent,
            )
        }
    }

    override suspend fun cancel(requestId: UUID) {
        queueCanceller.cancel(requestId)
    }

    @Deprecated("Cancellation requires the displayed request ID")
    @Suppress("DEPRECATION")
    override suspend fun cancelQueue() {
        queueCanceller.cancelQueue()
    }

    override fun observe(requestId: UUID): Flow<PrivilegeSweepStatus?> = combine(
        store.observe(requestId),
        rootLaneStatusSource.statuses,
    ) { snapshot, lanes ->
        snapshot?.toStatus(
            lanes[PrivilegeExecutionLane.SWEEP]?.mode == RootLaneMode.DEGRADED
        )
    }

    override fun observeLatest(source: PrivilegeSweepSource): Flow<PrivilegeSweepStatus?> = combine(
        store.observeRetained(source),
        rootLaneStatusSource.statuses,
    ) { snapshots, lanes ->
        snapshots.firstOrNull()?.toStatus(
            lanes[PrivilegeExecutionLane.SWEEP]?.mode == RootLaneMode.DEGRADED
        )
    }

    private fun StoredPrivilegeSweep.toStatus(rootLaneDegraded: Boolean): PrivilegeSweepStatus =
        PrivilegeSweepStatus(
            requestId = requestId,
            workId = executionId,
            operation = operation,
            source = source,
            phase = terminalState?.toPhase() ?: requestState.toPhase(),
            total = targets.size,
            succeeded = succeeded,
            failed = failed,
            busy = busy,
            unresolved = unresolved,
            rootLaneDegraded = rootLaneDegraded || targetSnapshots.any { it.rootLaneDegraded },
            profileIds = profileIdsFromSourceAssociations(sourceAssociations),
        )

    private fun PrivilegeSweepRequestState.toPhase(): PrivilegeSweepPhase = when (this) {
        PrivilegeSweepRequestState.QUEUED,
        PrivilegeSweepRequestState.BLOCKED,
            -> PrivilegeSweepPhase.QUEUED

        PrivilegeSweepRequestState.RUNNING,
        PrivilegeSweepRequestState.CANCEL_REQUESTED,
            -> PrivilegeSweepPhase.RUNNING

        PrivilegeSweepRequestState.SUCCEEDED -> PrivilegeSweepPhase.SUCCEEDED
        PrivilegeSweepRequestState.PARTIAL -> PrivilegeSweepPhase.PARTIAL
        PrivilegeSweepRequestState.CANCELLED -> PrivilegeSweepPhase.CANCELLED
        PrivilegeSweepRequestState.FAILED -> PrivilegeSweepPhase.FAILED
    }

    private fun StoredSweepTerminal.toPhase(): PrivilegeSweepPhase = when (this) {
        StoredSweepTerminal.SUCCEEDED -> PrivilegeSweepPhase.SUCCEEDED
        StoredSweepTerminal.PARTIAL -> PrivilegeSweepPhase.PARTIAL
        StoredSweepTerminal.CANCELLED -> PrivilegeSweepPhase.CANCELLED
        StoredSweepTerminal.FAILED -> PrivilegeSweepPhase.FAILED
    }
}

private const val SERVICE_EXECUTION_VERSION_MASK = 0x0000_0000_0000_f000L
private const val SERVICE_EXECUTION_VERSION_BITS = 0x0000_0000_0000_8000L

/** A persisted discriminator prevents startup's legacy reconciler from treating service rows as orphans. */
internal fun newPrivilegeServiceExecutionId(): UUID {
    val random = UUID.randomUUID()
    return UUID(
        (random.mostSignificantBits and SERVICE_EXECUTION_VERSION_MASK.inv()) or
                SERVICE_EXECUTION_VERSION_BITS,
        random.leastSignificantBits,
    )
}

internal fun UUID.isPrivilegeServiceExecutionId(): Boolean = version() == 8

/** Retained only for legacy chain cutover and Task 12 compatibility. */
@Single(binds = [PrivilegeSweepWorkManager::class])
internal class WorkManagerPrivilegeSweepWorkManager(
    private val context: Context,
) : PrivilegeSweepWorkManager {
    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override suspend fun enqueue(work: OneTimeWorkRequest): Boolean = false

    override fun observeState(workId: UUID): Flow<SweepWorkState?> =
        if (workId.isPrivilegeServiceExecutionId()) {
            flowOf(SweepWorkState.ENQUEUED)
        } else {
            workManager.getWorkInfoByIdFlow(workId).map { it?.state?.toSweepState() }
        }

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

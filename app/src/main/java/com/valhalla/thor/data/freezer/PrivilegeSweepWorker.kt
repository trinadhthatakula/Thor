// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import androidx.work.WorkerParameters
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.data.backup.job.JobSheetTarget
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.data.backup.job.ThorJobNotifications
import com.valhalla.thor.data.backup.job.ThorJobWorker
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

internal sealed interface PrivilegeSweepRunOutcome {
    data object Success : PrivilegeSweepRunOutcome
    data class PermanentFailure(val reason: String) : PrivilegeSweepRunOutcome
}

@Single
internal class PrivilegeSweepRunner(
    private val store: PrivilegeSweepStore,
    private val executor: PrivilegeSweepItemExecutor,
    private val clock: PrivilegeSweepClock,
    private val gate: PrivilegeSweepProcessGate,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun run(
        requestIdValue: String?,
        publish: (ThorJobProgress) -> Unit = {},
        noteResult: (StoredPrivilegeSweep) -> Unit = {},
    ): PrivilegeSweepRunOutcome {
        val requestId = requestIdValue.toRequestId()
            ?: return PrivilegeSweepRunOutcome.PermanentFailure("Sweep request id is missing or invalid")

        return try {
            val stored = store.load(requestId)
                ?: return PrivilegeSweepRunOutcome.PermanentFailure("Sweep request was not found")
            stored.terminalState?.let { return terminalOutcome(stored, noteResult) }
            val snapshot = store.resetForRun(requestId)
                ?: return settledOutcome(requestId, noteResult)
            var processed = 0
            snapshot.targets.forEach { packageName ->
                val admitted = gate.serialized {
                    currentCoroutineContext().ensureActive()
                    store.load(requestId)?.terminalState == null
                }
                if (!admitted) return settledOutcome(requestId, noteResult)

                val outcome = executor.execute(snapshot, packageName)
                val recorded = withContext(NonCancellable + ioDispatcher) {
                    store.recordAttempt(requestId, outcome)
                }
                if (!recorded) return settledOutcome(requestId, noteResult)
                processed++
                publish(
                    ThorJobProgress(
                        stage = ThorJobStage.ACTING,
                        label = packageName,
                        completed = processed.toLong(),
                        total = snapshot.targets.size.toLong(),
                    )
                )
                currentCoroutineContext().ensureActive()
            }

            val terminal = if (processed == snapshot.targets.size &&
                store.load(requestId)?.let { it.failed == 0 && it.busy == 0 } == true
            ) {
                StoredSweepTerminal.SUCCEEDED
            } else {
                StoredSweepTerminal.PARTIAL
            }
            if (!store.finish(requestId, terminal, clock.nowMs())) {
                settledOutcome(requestId, noteResult)
            } else {
                val completed = store.load(requestId)
                    ?: return PrivilegeSweepRunOutcome.PermanentFailure(
                        "Sweep result disappeared after completion"
                    )
                noteResult(completed)
                PrivilegeSweepRunOutcome.Success
            }
        } catch (e: CancellationException) {
            noteExplicitCancellation(requestId, noteResult)
            throw e
        } catch (_: Exception) {
            settleInterrupted(requestId, StoredSweepTerminal.FAILED, noteResult)
            PrivilegeSweepRunOutcome.PermanentFailure("Sweep execution failed")
        }
    }

    private suspend fun settledOutcome(
        requestId: UUID,
        noteResult: (StoredPrivilegeSweep) -> Unit,
    ): PrivilegeSweepRunOutcome {
        val settled = store.load(requestId)
            ?: return PrivilegeSweepRunOutcome.PermanentFailure("Sweep request was not found")
        return terminalOutcome(settled, noteResult)
    }

    private fun terminalOutcome(
        snapshot: StoredPrivilegeSweep,
        noteResult: (StoredPrivilegeSweep) -> Unit,
    ): PrivilegeSweepRunOutcome {
        noteResult(snapshot)
        return when (snapshot.terminalState) {
            StoredSweepTerminal.SUCCEEDED, StoredSweepTerminal.PARTIAL ->
                PrivilegeSweepRunOutcome.Success

            StoredSweepTerminal.CANCELLED ->
                PrivilegeSweepRunOutcome.PermanentFailure("Sweep request was cancelled")

            StoredSweepTerminal.FAILED ->
                PrivilegeSweepRunOutcome.PermanentFailure("Sweep request failed")

            null -> PrivilegeSweepRunOutcome.PermanentFailure("Sweep request could not be settled")
        }
    }

    private suspend fun noteExplicitCancellation(
        requestId: UUID,
        noteResult: (StoredPrivilegeSweep) -> Unit,
    ) {
        try {
            withContext(NonCancellable + ioDispatcher) {
                store.load(requestId)
                    ?.takeIf { it.terminalState == StoredSweepTerminal.CANCELLED }
                    ?.let(noteResult)
            }
        } catch (_: Exception) {
            // Preserve the WorkManager interruption that caused this best-effort lookup.
        }
    }

    private suspend fun settleInterrupted(
        requestId: UUID,
        terminal: StoredSweepTerminal,
        noteResult: (StoredPrivilegeSweep) -> Unit,
    ) {
        try {
            withContext(NonCancellable + ioDispatcher) {
                store.finish(requestId, terminal, clock.nowMs())
                store.load(requestId)?.let(noteResult)
            }
        } catch (_: Exception) {
            // Preserve the execution failure that caused this best-effort settlement.
        }
    }

    private fun String?.toRequestId(): UUID? {
        if (this.isNullOrBlank()) return null
        return runCatching { UUID.fromString(this) }.getOrNull()
    }
}

@KoinWorker
internal class PrivilegeSweepWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val runner: PrivilegeSweepRunner,
    sheetTargets: JobSheetTargets,
) : ThorJobWorker(
    appContext,
    params,
    notifications,
    registry,
    sheetTargets,
) {
    override val kind = ThorJobKind.PRIVILEGE_SWEEP
    override val initialLabel = appContext.getString(R.string.sweep_notification_title)
    override val runsForeground = false
    override val sheetTarget: JobSheetTarget? = null

    override suspend fun runJob(): Result = when (
        val outcome = runner.run(
            requestIdValue = inputData.getString(SWEEP_REQUEST_ID_KEY),
            publish = ::publish,
            noteResult = { snapshot -> noteResult(snapshot.resultNotice()) },
        )
    ) {
        PrivilegeSweepRunOutcome.Success -> Result.success()
        is PrivilegeSweepRunOutcome.PermanentFailure -> fail(outcome.reason)
    }

    private fun StoredPrivilegeSweep.resultNotice(): String = applicationContext.getString(
        R.string.sweep_result_summary,
        succeeded,
        failed,
        busy,
        unresolved,
    )
}

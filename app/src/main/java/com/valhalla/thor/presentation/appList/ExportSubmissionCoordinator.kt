// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.repository.ExportJobLauncher
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Process-owned handoff from a short-lived export sheet into the durable data queue. */
@Single
class ExportSubmissionCoordinator(
    private val launcher: ExportJobLauncher,
    private val taskNavigationTargets: TaskNavigationTargets,
    @Named("io") ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val inFlightLock = Any()
    private val inFlightByPackage = mutableMapOf<String, InFlightSubmission>()

    fun submit(
        taskId: UUID,
        packageName: String,
        label: String,
        format: BundleFormat,
        treeUri: String?,
    ): Deferred<UUID?> {
        val result = CompletableDeferred<UUID?>()
        var created = false
        val submission = synchronized(inFlightLock) {
            val current = inFlightByPackage[packageName]
                ?.takeUnless { it.producer.isCompleted }
                ?: InFlightSubmission(
                    producer = scope.async(start = CoroutineStart.LAZY) {
                        submitOnce(taskId, packageName, label, format, treeUri)
                    }
                ).also {
                    inFlightByPackage[packageName] = it
                    created = true
                }
            current.candidates += SubmissionCandidate(taskId, result)
            current
        }
        if (created) {
            submission.producer.start()
            scope.launch { deliver(packageName, submission) }
        }
        return result
    }

    private suspend fun submitOnce(
        taskId: UUID,
        packageName: String,
        label: String,
        format: BundleFormat,
        treeUri: String?,
    ): UUID? = try {
        val request = AppExportRequest(
            packageName = packageName,
            format = format,
            label = label,
            treeUri = treeUri,
        )
        launcher.startExport(taskId, request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Logger.e(TAG, "export submission failed for $packageName", failure)
        null
    }

    private suspend fun deliver(
        packageName: String,
        submission: InFlightSubmission,
    ) {
        val acceptedTaskId = submission.producer.await()
        val candidates = synchronized(inFlightLock) {
            if (inFlightByPackage[packageName] === submission) {
                inFlightByPackage.remove(packageName)
            }
            submission.candidates.toList().also { submission.candidates.clear() }
        }
        candidates.forEach { candidate ->
            if (acceptedTaskId == null) {
                taskNavigationTargets.requestRejected(candidate.taskId)
            } else {
                taskNavigationTargets.requestAccepted(candidate.taskId, acceptedTaskId)
            }
            candidate.result.complete(acceptedTaskId)
        }
    }

    private class InFlightSubmission(
        val producer: Deferred<UUID?>,
        val candidates: MutableList<SubmissionCandidate> = mutableListOf(),
    )

    private data class SubmissionCandidate(
        val taskId: UUID,
        val result: CompletableDeferred<UUID?>,
    )

    private companion object {
        const val TAG = "ExportSubmission"
    }
}

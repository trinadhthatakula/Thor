// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.WorkerParameters
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.EXPORT_LABEL_KEY
import com.valhalla.thor.domain.model.EXPORT_TREE_KEY
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationStatus
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.android.annotation.KoinWorker
import kotlin.Result as KotlinResult
import org.koin.core.annotation.Named

private const val TAG = "AppExportWorker"

/**
 * One app packaged as `.apk`/`.apks`/`.xapk` and written where the user asked, behind a foreground
 * service.
 *
 * The whole job is one call into [ExportAppUseCase.exportInto]; what this class actually owns is
 * everything around it — the four strings the request arrived as, the `AppInfo` re-resolution, a
 * destination check that fails before the packaging rather than after it, and **an outcome sentence
 * on every terminal path**, which is what the notification the user sees at the end is made of.
 *
 * ### It does not open its own session
 *
 * [ExportAppUseCase.openSession] resolves the destination *and clears the saved-folder preference*
 * when that folder has gone. That is a write, it belongs to the tap the user just made, and running
 * it here would let a job enqueued yesterday silently reset today's setting. The foreground resolves
 * the session; the resolved destination travels in [AppExportRequest.treeUri]; this constructs an
 * [ExportSession] from it and reads no preference at all.
 *
 * ### It does not report cancellation
 *
 * Nothing here calls `noteResult` on the way out of a stop, and that is deliberate rather than
 * missing. A stopped worker is not necessarily a finished one: WorkManager stops workers for its own
 * reasons — a constraint, a quota, the process going away — and then **re-runs them**, so a shade
 * row saying "Export stopped before it finished. Nothing was saved." would be a lie told at the exact
 * moment the export was about to succeed. A genuine user cancel is reported by the screen instead,
 * off `ThorJobStatus.Cancelled`, which only WorkManager's own terminal state can produce.
 *
 * `sheetTarget` is null for the same kind of reason: an export has no sheet worth reopening
 * mid-flight — the progress is in the notification and the outcome is a second notification — so a
 * tap resumes the app and nothing more.
 */
@KoinWorker
internal class AppExportWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val exportApp: ExportAppUseCase,
    private val appRepository: AppRepository,
    private val fileStore: AppBundleFileStore,
    private val launchSweep: LaunchSweepBarrier,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    sheetTargets: JobSheetTargets,
) : ThorJobWorker(appContext, params, notifications, registry, sheetTargets) {

    override val kind = ThorJobKind.APP_EXPORT

    /**
     * The app's real label, unlike either archive worker's — and for free.
     *
     * This is read on the `setForeground` deadline path, where those two deliberately show the
     * package name rather than spend the budget on a `PackageManager` round trip. An export does not
     * face that trade: the label was resolved in the foreground, at tap time, and travels in the
     * request. So the shade shows "Clash of Clans" from the very first frame instead of
     * `com.supercell.clashofclans` until the worker catches up.
     */
    override val initialLabel: String
        get() = inputData.getString(EXPORT_LABEL_KEY).orEmpty()

    override val sheetTarget: JobSheetTarget? = null

    override suspend fun runJob(): Result {
        val decoded = decodeLegacyAppExportRequest(inputData.keyValueMap)
        var destination: String? = null
        val runner = AppExportTaskRunner(
            operations = object : AppExportTaskOperations {
                override suspend fun awaitLaunchSweep(): Boolean {
                    val swept = launchSweep.awaitSwept()
                    if (!swept) {
                        Logger.e(
                            TAG,
                            "launch sweep did not finish; refusing to stage ${decoded?.packageName}",
                        )
                    }
                    return swept
                }

                override suspend fun loadApp(packageName: String) =
                    appRepository.getAppDetails(packageName)

                override suspend fun isTreeWritable(treeUri: String): Boolean =
                    fileStore.isTreeWritable(treeUri)

                override suspend fun exportInto(
                    appInfo: AppInfo,
                    format: BundleFormat,
                    session: ExportSession,
                    publicationIdentity: AppExportPublicationIdentity?,
                    execution: PrivilegeExecutionContext,
                    captureProgress: VerifiedProgress,
                    captureBoundary: VerifiedOperationBoundary,
                    publicationProgress: VerifiedProgress,
                ): KotlinResult<AppExportPublication> {
                    val result = if (publicationIdentity == null) {
                        exportApp.exportInto(
                            appInfo = appInfo,
                            format = format,
                            session = session,
                            execution = execution,
                            captureProgress = captureProgress,
                            publicationProgress = publicationProgress,
                        ).map { destination ->
                            AppExportPublication(
                                destinationLabel = destination,
                                status = AppExportPublicationStatus.PUBLISHED,
                            )
                        }
                    } else {
                        exportApp.exportDurableInto(
                            appInfo = appInfo,
                            format = format,
                            session = session,
                            publicationIdentity = publicationIdentity,
                            execution = execution,
                            captureProgress = captureProgress,
                            captureBoundary = captureBoundary,
                            publicationProgress = publicationProgress,
                        )
                    }
                    return result.onFailure { cause ->
                        Logger.e(TAG, "export of ${appInfo.packageName} failed", cause)
                    }
                }

                override fun onPublished(destinationLabel: String) {
                    destination = destinationLabel
                }
            },
            ioDispatcher = ioDispatcher,
        )
        return runLegacyAppExportTask(
            taskId = id,
            decodedRequest = decoded,
            runAttemptCount = runAttemptCount,
            invalidRequestReason = getString(R.string.export_job_unreadable),
            runner = runner,
            checkpoints = LegacyWorkerCheckpointSink { progress ->
                publish(
                    decoded?.let { request ->
                        legacyAppExportProgress(
                            progress = progress,
                            preparingLabel = getString(
                                R.string.export_job_preparing,
                                request.label,
                            ),
                            packagingLabel = getString(
                                R.string.export_job_packaging,
                                request.label,
                                request.format.extension,
                            ),
                        )
                    } ?: progress
                )
            },
            results = LegacyWorkerResultSink(
                kind = DataTaskKind.APP_EXPORT,
                onSuccess = {
                    destination?.let { location ->
                        val label = decoded?.label ?: initialLabel
                        noteResult(getString(R.string.export_job_saved, label, location))
                    }
                },
                onFailure = ::noteResult,
            ),
            failureReason = { code, detail -> legacyFailureReason(code, detail, decoded) },
        )
    }

    private fun legacyFailureReason(
        code: DataTaskResultCode,
        detail: String?,
        request: AppExportRequest?,
    ): String = when (code) {
        APP_EXPORT_REQUEST_MISMATCH -> getString(R.string.export_job_unreadable)
        APP_EXPORT_CLEANUP_BUSY -> getString(R.string.export_job_cleanup_busy)
        APP_EXPORT_APP_NOT_INSTALLED -> getString(
            R.string.export_job_app_gone,
            request?.label ?: initialLabel,
        )

        APP_EXPORT_DESTINATION_UNAVAILABLE -> getString(R.string.export_job_folder_gone)
        APP_EXPORT_FAILED -> getString(
            R.string.export_failed,
            detail ?: getString(R.string.export_failed_unknown),
        )

        else -> detail ?: getString(R.string.export_failed_unknown)
    }

    private fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
        applicationContext.getString(resId, *formatArgs)
}

internal fun decodeLegacyAppExportRequest(map: Map<String, Any?>): AppExportRequest? {
    if (map.containsKey(EXPORT_TREE_KEY)) {
        val treeUri = map[EXPORT_TREE_KEY] as? String ?: return null
        if (treeUri.isBlank()) return null
    }
    return AppExportRequest.fromMap(map)
}

internal fun legacyAppExportProgress(
    progress: ThorJobProgress,
    preparingLabel: String,
    packagingLabel: String,
): ThorJobProgress = progress.copy(
    label = when (progress.stage) {
        ThorJobStage.PREPARING -> preparingLabel
        ThorJobStage.CAPTURING -> packagingLabel
        else -> progress.label
    }
)

/** Strict adapter for WorkSpecs created by released versions; it never creates a Room task. */
internal suspend fun <R> runLegacyAppExportTask(
    taskId: UUID,
    decodedRequest: AppExportRequest?,
    runAttemptCount: Int,
    invalidRequestReason: String,
    runner: DataTaskRunner,
    checkpoints: DataTaskCheckpointSink,
    results: DataTaskResultSink<R>,
    failureReason: (DataTaskResultCode, String?) -> String = { _, detail ->
        detail ?: "the export could not be completed"
    },
): R {
    val request = decodedRequest ?: return results.persist(
        exportTaskFailure(
            DataTaskResultCode("LEGACY_APP_EXPORT_REQUEST_INVALID"),
            invalidRequestReason,
        )
    )
    require(runner.kind == DataTaskKind.APP_EXPORT) {
        "runner kind ${runner.kind} does not match request kind ${DataTaskKind.APP_EXPORT}"
    }
    val executionRequest = DataTaskExecutionRequest(
        taskId = taskId,
        payload = DataTaskExecutionPayload.AppExport(
            request = request,
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
        ),
        item = DataTaskExecutionItem(
            ordinal = 0,
            packageName = request.packageName,
            displayLabel = request.label,
            deterministicStagingIdentity = LEGACY_APP_EXPORT_STAGING_IDENTITY,
            attemptCount = runAttemptCount,
        ),
        taskAttemptCount = runAttemptCount,
        resumedFrom = null,
    )
    val outcome = runner.run(executionRequest, checkpoints)
    return results.persist(outcome.toLegacyAppExportOutcome(failureReason))
}

private fun DataTaskRunOutcome.toLegacyAppExportOutcome(
    failureReason: (DataTaskResultCode, String?) -> String,
): DataTaskRunOutcome {
    val failed = this as? DataTaskRunOutcome.ItemCompleted
        ?: return if (this is DataTaskRunOutcome.TaskFailed) {
            exportTaskFailure(
                resultCode,
                failureReason(resultCode, arguments.firstOrNull()),
            )
        } else {
            this
        }
    if (failed.result.terminalState != DataTaskItemTerminalState.FAILED) return this
    val detail = failed.result.warnings.firstOrNull()?.arguments?.firstOrNull()
    return exportTaskFailure(
        failed.result.resultCode,
        failureReason(failed.result.resultCode, detail),
    )
}

/**
 * What a failed export should say went wrong, or null when the cause said nothing usable.
 *
 * Exists as a function, top-level and testable, for one reason: `AppBundleBuilderImpl` phrases the
 * shortfall failure itself — "not enough free space to pack this app's game data — about 1.4 GB more
 * is needed" — and that string is the entire actionable content of the failure. A caller that
 * substitutes its own wording, or that reaches for `Throwable.toString()` and prints
 * `java.io.IOException: …` at the user, destroys it. This is the seam where that could be lost, so
 * this is where it is pinned.
 *
 * Blank is treated as absent: `IllegalStateException()` with no message and one carrying `""` are the
 * same amount of information, and "Export failed: " with nothing after the colon reads as a bug.
 *
 * No bounding here — [ThorJobWorker.fail] and [ThorJobWorker.noteResult] both apply
 * [boundedForJobData] at their own boundary, which is where `Data`'s 10 KB rule actually lives.
 */
internal fun exportFailureReason(cause: Throwable): String? =
    cause.message?.takeIf { it.isNotBlank() }

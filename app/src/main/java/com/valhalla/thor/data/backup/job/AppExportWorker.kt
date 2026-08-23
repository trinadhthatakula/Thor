// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.WorkerParameters
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppExportRequest
import com.valhalla.thor.domain.model.EXPORT_LABEL_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.domain.usecase.ExportSession
import com.valhalla.thor.util.Logger
import org.koin.android.annotation.KoinWorker

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
        val request = AppExportRequest.fromMap(inputData.keyValueMap)
            ?: return failNoted(getString(R.string.export_job_unreadable))

        publish(ThorJobProgress(ThorJobStage.PREPARING, getString(R.string.export_job_preparing, request.label)))

        // Before anything is staged, and unconditionally — see LaunchSweepBarrier for why an export is
        // the first job on this seam that can lose a race to the launch sweep, and why waiting costs
        // nothing outside the one process start where the two overlap.
        if (!launchSweep.awaitSwept()) {
            Logger.e(TAG, "launch sweep did not finish; refusing to stage ${request.packageName}")
            return failNoted(getString(R.string.export_job_cleanup_busy))
        }

        // Re-resolved, never carried: the request holds a package name precisely because
        // publicSourceDir and splitPublicSourceDirs are snapshots that an update invalidates. Null
        // here is the app having been uninstalled since the tap, which is a sentence, not an error.
        val appInfo = appRepository.getAppDetails(request.packageName)
            ?: return failNoted(getString(R.string.export_job_app_gone, request.label))

        // Checked up front rather than discovered by the write failing. Packaging a 4 GB game and
        // *then* finding out the folder's grant was revoked spends ten minutes to produce a failure
        // that was knowable in a millisecond — and the recovery ("pick the folder again") is the same
        // either way. Downloads carries no grant and needs no check.
        request.treeUri?.let { tree ->
            if (!fileStore.isTreeWritable(tree)) {
                return failNoted(getString(R.string.export_job_folder_gone))
            }
        }

        publish(
            ThorJobProgress(
                ThorJobStage.CAPTURING,
                getString(R.string.export_job_packaging, request.label, request.format.extension),
            )
        )

        // SINGLE_STAGING_DIR, the same scope the foreground path used, because this *is* the
        // one-app export — a batch takes a scope of its own so the two cannot wipe each other's
        // half-written copy, and there is no batch on this seam yet.
        val session = ExportSession(request.target, ExportAppUseCase.SINGLE_STAGING_DIR)
        val destination = exportApp.exportInto(appInfo, request.format, session)
            .getOrElse { cause ->
                Logger.e(TAG, "export of ${request.packageName} failed", cause)
                // The cause's own words, not a generic sentence. The builder computes and phrases the
                // one failure a user can act on — "about 1.4 GB more is needed" — and flattening that
                // to "Export failed" throws away the only part that says what to do next.
                return failNoted(
                    getString(
                        R.string.export_failed,
                        exportFailureReason(cause) ?: getString(R.string.export_failed_unknown),
                    )
                )
            }

        // The sentence this whole class exists to be able to say. Noted rather than returned, because
        // `doWork`'s `finally` is what posts it and the `finally` runs on paths a `Result` does not
        // survive; the success `Data` would only be read by a screen that is still open.
        noteResult(getString(R.string.export_job_saved, request.label, destination))
        return Result.success()
    }

    /**
     * Fail *and* leave the reason in the shade, which are two different things and both are needed.
     *
     * `fail` puts the sentence in the output `Data`, where the export screen reads it — and reads it
     * only while that screen exists. An export is a background job the user was invited to walk away
     * from; the notification is the report for the case where they did. Every `return` in [runJob]
     * that is not the success goes through here, so there is no path that ends the job in silence.
     */
    private fun failNoted(reason: String): Result {
        noteResult(reason)
        return fail(reason)
    }

    private fun getString(@StringRes resId: Int, vararg formatArgs: Any): String =
        applicationContext.getString(resId, *formatArgs)
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

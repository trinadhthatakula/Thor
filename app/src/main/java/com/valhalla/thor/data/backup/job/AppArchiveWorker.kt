// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.JOB_WARNINGS_KEY
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.captureName
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.SystemRepository
// `usecase`, not `repository`: `ArchiveHeaderOutcome` is declared alongside OpenArchiveUseCase.
import com.valhalla.thor.domain.usecase.ArchiveHeaderOutcome
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import com.valhalla.thor.domain.usecase.BackupAppArchiveUseCase
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCase
import com.valhalla.thor.util.Logger
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.Named

private const val TAG = "AppArchiveWorker"

/**
 * §7.2 behind a foreground service.
 *
 * The use case owns the sequence; this owns everything that needs a `Context` or a repository — the
 * `AppInfo` lookup, the `.xapk` build, the OBB probe — and hands the results down.
 *
 * `internal`, because [BackupAppArchiveUseCase] is: a public class cannot take an internal type in
 * its constructor. Koin's compiler plugin generates into this module, so the binding still resolves.
 */
@KoinWorker
internal class ArchiveBackupWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val keys: ArchiveKeyHolder,
    private val backup: BackupAppArchiveUseCase,
    private val appRepository: AppRepository,
    private val bundleBuilder: AppBundleBuilder,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ThorJobWorker(appContext, params, notifications, registry, keys) {

    override val kind = ThorJobKind.ARCHIVE_BACKUP

    /**
     * The package name, not the label.
     *
     * `getForegroundInfo()` runs before `doWork` and cannot afford a `PackageManager` round trip on
     * the path that has to promote the service within a few seconds. The first `publish()` from the
     * use case replaces it with the label, well before a user reads the shade — but only because
     * `runJob` resolves the `AppInfo` and hands `appLabel` down. Without that parameter the use case
     * publishes `request.packageName`, then the `.xapk` file name, then a `DataClass` id, and the
     * shade shows `com.supercell.clashofclans` for the whole job. See the `backup(…)` call below.
     */
    override val initialLabel: String
        get() = inputData.getString(BACKUP_PACKAGE_KEY).orEmpty()

    override suspend fun runJob(): Result {
        val request = ArchiveBackupRequest.fromMap(inputData.keyValueMap)
            ?: return fail("this backup's request could not be read")
        // Single-use, and gone if the process died: see ArchiveKeyHolder. No retry, ever.
        val key = keys.take(id.toString())
            ?: return fail("this backup's key is no longer in memory — start it again")
        val appInfo = appRepository.getAppDetails(request.packageName)
            ?: return fail("${request.packageName} is not installed")

        var bundle: File? = null
        return try {
            val probe = if (request.includeBundle) {
                systemRepository.probeObb(request.packageName)
            } else {
                ObbProbe.None
            }
            if (request.includeBundle) {
                bundle = bundleBuilder.build(
                    appInfo = appInfo,
                    // The shared name, not a literal: ArchiveOrphanSweeper deletes this whole subtree
                    // at launch, and a second spelling of it would make the sweep miss.
                    cacheSubDir = ArchiveBundleCacheDir.NAME,
                    format = BundleFormat.XAPK,
                ).getOrElse {
                    return fail("the app's installer bundle could not be built: ${it.message}")
                }
            }

            when (
                // `withContext(ioDispatcher)`, symmetric with ArchiveRestoreWorker and for the same
                // reason: `doWork()` runs on Dispatchers.Default, the use case takes no dispatcher of
                // its own, and it blocks the caller's thread for the whole `.xapk` copy and every
                // `encryptMember`. Left on Default, a 4 GB game pins one of that pool's few threads —
                // 4 on a quad-core device — for minutes, against everything else in the app.
                val outcome = withContext(ioDispatcher) {
                    backup(
                        request = request,
                        key = key,
                        bundle = bundle,
                        bundleObbCapture = probe.captureName(),
                        bundleObbCount = (probe as? ObbProbe.Present)?.files?.size ?: 0,
                        versionCode = appInfo.versionCode,
                        versionName = appInfo.versionName,
                        // Never left to default. The parameter defaults to 0L, which the use case reads
                        // as "unmeasurable" and fails §7.4's free-space check open — silently turning
                        // the one check that stops a backup from filling the device into a no-op.
                        usableStagingBytes = usableStagingBytes(),
                        // The label the shade shows for the whole job. Without it the use case falls
                        // back to the package name and then publishes the bundle file name and each
                        // DataClass id, so a user watching a backup reads `internal_data`.
                        appLabel = appInfo.appName ?: request.packageName,
                        onProgress = ::publish,
                    )
                }
            ) {
                is ArchiveBackupOutcome.Completed -> Result.success()
                is ArchiveBackupOutcome.Failed -> fail(outcome.reason)
                ArchiveBackupOutcome.NoDestination -> fail("choose a folder for Thor's backups first")
            }
        } finally {
            // The bundle can be gigabytes and it is already inside the container. Deleted here rather
            // than in the use case, because this is what created it.
            bundle?.delete()
        }
    }

    /**
     * §7.4's measurement. `data` measures, `domain` decides — the same split as `BackupRunner` and
     * `BackupAppsUseCase`.
     *
     * `usableSpace`, not `getAllocatableBytes`, and therefore `@Suppress("UsableSpace")`: the bytes
     * have to be there for the whole of a multi-gigabyte `tar` the platform is not participating in,
     * so the cache quota `getAllocatableBytes` adds back is not spendable here. Same reasoning as
     * `ObbInstaller.usableBytes` and `AppBundleBuilderImpl`, and the reason #373's cache-clear bug is
     * the cautionary tale attached to obeying that hint.
     *
     * The **larger** of the two candidate volumes on purpose. `AppDataArchiveGatewayImpl.stagingFile`
     * picks internal cache and falls back to external, and this cannot see which it chose;
     * over-reporting costs a `tar` that fails and is retried, while under-reporting would skip a class
     * the device could have held. Zero from both is "unmeasurable", which the rule fails open on.
     */
    @Suppress("UsableSpace")
    private fun usableStagingBytes(): Long = maxOf(
        applicationContext.cacheDir?.usableSpace ?: 0L,
        applicationContext.externalCacheDir?.usableSpace ?: 0L,
    )
}

/**
 * §8.3 behind a foreground service.
 *
 * Re-reads the header and re-runs the gate — see [ArchiveRestoreRequest]. That is the whole reason
 * `installFirst` is not an input.
 */
@KoinWorker
internal class ArchiveRestoreWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    private val keys: ArchiveKeyHolder,
    private val sources: ArchiveSourceFactory,
    private val openArchive: OpenArchiveUseCase,
    private val restore: RestoreAppArchiveUseCase,
    // Still here after the facts moved out: the progress label is `appName`, and the use case is
    // handed it so the shade shows "Clash of Clans" rather than `com.supercell.clashofclans`.
    private val appRepository: AppRepository,
    private val installedFacts: ReadInstalledAppFactsUseCase,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ThorJobWorker(appContext, params, notifications, registry, keys) {

    override val kind = ThorJobKind.ARCHIVE_RESTORE

    override val initialLabel: String
        get() = inputData.getString(RESTORE_PACKAGE_KEY).orEmpty()

    override suspend fun runJob(): Result {
        val request = ArchiveRestoreRequest.fromMap(inputData.keyValueMap)
            ?: return fail("this restore's request could not be read")
        val key = keys.take(id.toString())
            ?: return fail("this restore's key is no longer in memory — start it again")

        val source = sources.open(request.uriString)
            ?: return fail("Thor could not open that backup file")

        return source.use {
            val header = when (val read = openArchive.readHeader(source)) {
                is ArchiveHeaderOutcome.Read -> read.header
                is ArchiveHeaderOutcome.NotAnArchive -> return@use fail(read.reason)
            }
            // The URI named a different archive than the screen was looking at. A `content://` URI is
            // a handle to a document, not to bytes.
            if (header.packageName != request.packageName) {
                return@use fail("that backup file is not ${request.packageName}'s any more")
            }

            val app = appRepository.getAppDetails(request.packageName)
            // The same assembly the restore screen ran before it offered the button, from the same
            // use case: two spellings of "installed" is two gates, and only one of them would have
            // been the one the user was shown.
            val installed = installedFacts(request.packageName)
            // Re-run, not replay. The app may have arrived or gone while this waited on the chain,
            // and this gate is the only signer comparison in the whole restore: when it answers
            // installFirst = false the use case deliberately performs none of its own, because the
            // check already happened here. Skipping it would make "sideload a fake com.whatsapp,
            // restore, read everything" work. It also rejects SCHEMA_TOO_NEW, INVALID_PACKAGE_NAME
            // and INVALID_USER_ID, which is what keeps untrusted header fields out of shell paths.
            val decision = evaluateArchiveRestoreGate(header, installed, request.classes)
            val allowed = decision as? ArchiveRestoreDecision.Allowed
                ?: return@use fail(
                    "this backup can no longer be restored: " +
                        (decision as ArchiveRestoreDecision.Refused).reason
                )
            // Not in the brief; see the report. The gate ran twice — once on the confirm screen and
            // again here — and this run can produce warnings the first never showed, because the app
            // may have been updated or removed while the job sat in the chain. There is no UI at this
            // point, so the log is where they go.
            // `.name`: these are `ArchiveRestoreWarning` enum entries, not sentences. The user-facing
            // wording lives with the screen that shows them (Task 17); a second copy here would be a
            // second thing to translate and a second thing to drift.
            allowed.warnings.forEach { warning -> Logger.w(TAG, "gate warning: ${warning.name}") }

            when (
                // `withContext(ioDispatcher)`, and not because the use case asks politely: it takes no
                // dispatcher of its own and blocks on the caller's for the decrypt and the bundle copy.
                // Run on WorkManager's own executor this would block a pool thread for the length of a
                // multi-gigabyte restore, and the cancellation checkpoints inside it are only prompt
                // because `io` is elastic.
                val outcome = withContext(ioDispatcher) {
                    restore(
                        source = source,
                        header = header,
                        key = key,
                        classes = request.orderedClasses(),
                        // From the gate's own decision, never from a fresh "is it installed?" probe —
                        // the two can disagree, and only this one has compared the signers.
                        installFirst = allowed.installFirst,
                        restoreObb = request.restoreObb,
                        appLabel = app?.appName ?: request.packageName,
                        onProgress = ::publish,
                    )
                }
            ) {
                is ArchiveRestoreOutcome.Completed -> {
                    outcome.warnings.forEach { Logger.w(TAG, it) }
                    // Carried out on the *success* result, not only logged. These are the sentences a
                    // restore finished in spite of — game data that could not be placed, a breadcrumb
                    // that could not be written — and a user whose game now starts and crashes has no
                    // other way to learn why. `Data` caps at 10 KB; the use case produces at most
                    // three short sentences.
                    Result.success(workDataOf(JOB_WARNINGS_KEY to outcome.warnings.toTypedArray()))
                }

                is ArchiveRestoreOutcome.Failed -> fail(
                    if (outcome.classesRestored.isEmpty()) {
                        outcome.reason
                    } else {
                        // Partial is not failure-with-nothing-done, and a user who is told only
                        // "failed" will not know their app is now holding half-restored data.
                        "${outcome.reason} (${outcome.classesRestored.joinToString { c -> c.id }} was already replaced)"
                    }
                )
            }
        }
    }
}

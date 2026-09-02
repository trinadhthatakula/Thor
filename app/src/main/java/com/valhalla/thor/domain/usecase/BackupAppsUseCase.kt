// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BackupEntry
import com.valhalla.thor.domain.model.BackupIndex
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.uniqueBundleFileNames
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.File

/** How far a run has got, for a caller that wants to render it. */
data class BackupProgress(
    /** Apps the run is *done with*, successes and failures alike — this is what a bar measures. */
    val completed: Int,
    /**
     * Apps that actually produced a file, which is a different number and the only honest one to
     * put in front of a user. [completed] drives the bar; this drives "N apps were saved".
     */
    val saved: Int,
    val total: Int,
    /**
     * Label of the most recently *started* app, or null before the first one starts.
     *
     * With bounded concurrency this is not necessarily the next app to finish — it exists to give
     * the UI something to name, not to be an authoritative "currently exporting exactly this".
     */
    val current: String?,
)

/**
 * A permit-bounded gate over the staging cache, carrying the bound it was built with.
 *
 * A bare [Semaphore] cannot answer "how many workers may stage at once", which is exactly what the
 * space pre-flight has to multiply by: [Semaphore.availablePermits] reports what is *free right
 * now*, so asking a fully-occupied gate gives 0 and asking a busy one under-counts. Pairing the
 * capacity with the semaphore removes the temptation to read one for the other.
 */
class StagingGate(val capacity: Int) {
    private val semaphore = Semaphore(capacity)

    // acquire/release rather than delegating to Semaphore.withPermit: that one is inline and takes
    // a non-suspend lambda, which a non-inline wrapper cannot forward. The try/finally is the same
    // contract, including releasing on cancellation.
    suspend fun <T> withPermit(action: suspend () -> T): T {
        semaphore.acquire()
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }
}

/** Why a run never started. Structured, not worded — the UI owns the copy. */
sealed interface BackupRejection {
    data object NothingToExport : BackupRejection

    /**
     * Not enough room in the staging cache for the largest app in the batch.
     *
     * @param requiredBytes the headroom the run wanted before it would start.
     * @param availableBytes what the cache partition actually reported.
     */
    data class InsufficientStagingSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : BackupRejection
}

sealed interface BackupRunResult {
    data class Rejected(val reason: BackupRejection) : BackupRunResult

    /**
     * The batch ran to the end. [failed] apps are recorded in the index too, so
     * `succeeded + failed == total` always holds here.
     *
     * @param location the label the file store returned for the last successful write, or null
     *   when nothing was written.
     * @param indexWritten false when every bundle landed but the manifest beside them did not —
     *   worth surfacing, because the folder is then undescribed.
     */
    data class Finished(
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val location: String?,
        val indexWritten: Boolean,
    ) : BackupRunResult

    /**
     * Emitted by the runner, not returned by the use case — [BackupAppsUseCase] rethrows.
     *
     * [saved] counts files that landed, not apps the run got through: a cancel after eight
     * attempts of which three failed saved five, and telling the user eight would be a lie about
     * what is in their folder.
     */
    data class Cancelled(val saved: Int, val total: Int) : BackupRunResult

    /**
     * The run died on something neither the batch nor a single app could absorb.
     *
     * Also runner-emitted. It exists so an unexpected failure is not *silent*: without it the
     * catch-all logs and returns null, the progress indicator vanishes, and the user is left to
     * infer from an unchanged folder that nothing happened.
     */
    data class Failed(val saved: Int, val total: Int) : BackupRunResult
}

/**
 * Exports a selected set of apps to the user's chosen export target and drops a [BackupIndex]
 * beside them.
 *
 * This is a batch wrapper over [ExportAppUseCase], not a reimplementation of it: that use case is
 * the only thing that deletes its staged cache copy after a successful write, which is the single
 * property keeping `cacheDir` bounded across a 200-app run. Anything that duplicated its body
 * would have to duplicate that too, and would drift.
 */
@Factory
class BackupAppsUseCase(
    private val exportAppUseCase: ExportAppUseCase,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * @param stagingRoot the cache directory the run's own staging scope is created under — passed
     *   in rather than resolved here so this stays free of Android types.
     * @param usableStagingBytes free space on [stagingRoot]'s volume, measured by the caller.
     *   Measuring it *here* would mean `File.usableSpace`, which under-reports on Android by
     *   ignoring the clearable cache the platform would evict to satisfy the write; the caller has
     *   `StorageManager` and can answer properly. Zero or negative means "could not measure", and
     *   the pre-flight fails open on it.
     * @param gate bounds how many apps stage concurrently. Owned by the *caller* on purpose: an
     *   instance-scoped gate caps in-flight workers across a cancel-and-replace, whereas one
     *   created per invocation would let a replacement run start a fresh set on top of workers the
     *   cancelled run has not finished unwinding.
     */
    suspend operator fun invoke(
        apps: List<AppInfo>,
        stagingRoot: File,
        usableStagingBytes: Long,
        gate: StagingGate = StagingGate(1),
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupRunResult = withContext(ioDispatcher) {
        if (apps.isEmpty()) {
            return@withContext BackupRunResult.Rejected(BackupRejection.NothingToExport)
        }

        checkStagingSpace(apps, usableStagingBytes, gate.capacity)?.let { rejection ->
            return@withContext BackupRunResult.Rejected(rejection)
        }

        // Decided once, before any worker starts, and for the whole batch at a time — see
        // [ExportSession] for why the destination cannot be re-read per app, and
        // [uniqueBundleFileNames] for why the names cannot be derived per app.
        val formats = apps.map { BundleFormat.autoFor(it) }
        val names = uniqueBundleFileNames(apps.zip(formats))
        val session = exportAppUseCase.openSession(stagingSubDir = runStagingDir())
        val stagingDir = File(stagingRoot, session.stagingSubDir)

        // Indexed rather than appended so the manifest keeps the order the user selected, whatever
        // order the workers finish in. Distinct indices need no lock; coroutineScope's join is the
        // happens-before edge that makes the writes visible afterwards.
        val slots = arrayOfNulls<BackupEntry>(apps.size)
        var completed = 0
        var saved = 0
        var current: String? = null
        var location: String? = null
        // Guards the four vars above AND the onProgress call together, so progress is monotonic:
        // incrementing under an atomic and emitting outside it lets two workers publish 2 then 1.
        val lock = Any()

        try {
            try {
                coroutineScope {
                    apps.forEachIndexed { index, app ->
                        launch {
                            gate.withPermit {
                                // Inside the permit, not outside: a cancel that lands while this
                                // worker was queued must not start a multi-GB copy it will throw
                                // away.
                                ensureActive()
                                val format = formats[index]
                                val name = names[index]
                                val label = app.appName ?: app.packageName
                                synchronized(lock) {
                                    current = label
                                    onProgress(BackupProgress(completed, saved, apps.size, current))
                                }

                                val result = try {
                                    exportAppUseCase.exportInto(app, format, session, name)
                                } catch (e: CancellationException) {
                                    // CancellationException IS an Exception in Kotlin, so it has to
                                    // be rethrown ahead of the broad catch or the ensureActive()
                                    // above is defeated and the batch ignores cancellation.
                                    throw e
                                } catch (e: Exception) {
                                    // One app must not take the batch down with it. The export path
                                    // already returns Result.failure for its own errors; this
                                    // covers anything that escapes it.
                                    Logger.e(TAG, "backup export failed for ${app.packageName}", e)
                                    Result.failure(e)
                                }

                                slots[index] = result.fold(
                                    onSuccess = { where ->
                                        synchronized(lock) {
                                            location = where
                                            saved++
                                        }
                                        entryFor(app, format, name, app.apkPayloadBytes(), null)
                                    },
                                    onFailure = { t ->
                                        entryFor(app, format, name, null, error = t.describe())
                                    }
                                )

                                synchronized(lock) {
                                    completed++
                                    onProgress(BackupProgress(completed, saved, apps.size, current))
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Still write the manifest. A cancelled run leaves real files in the user's folder
                // and an undescribed folder is the same lie a success-only index would be.
                // NonCancellable because every suspension point below would otherwise resume with
                // cancellation, and deliberately unbounded: this is one small JSON write, not the
                // multi-gigabyte stream that made the legacy bulk executor bound its own post-run work.
                withContext(NonCancellable) {
                    writeIndex(slots.filterNotNull(), stagingDir, session)
                }
                throw e
            }

            val entries = slots.filterNotNull()
            BackupRunResult.Finished(
                total = apps.size,
                succeeded = entries.count { it.error == null },
                failed = entries.count { it.error != null },
                location = location,
                indexWritten = writeIndex(entries, stagingDir, session),
            )
        } finally {
            // Outside both the run and the manifest write, so it covers every way out. The run owns
            // this directory outright, which is what makes taking the whole tree safe in a way
            // deleting a shared one never was: a cancelled run cleaning up while its replacement is
            // already staging cannot reach the replacement's files. Everything under it is either a
            // per-package staging dir the builder left behind or the manifest just written.
            // Non-suspending, so it still runs on the cancelled path.
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Fail fast when the largest app in the batch cannot be staged, rather than part-way through.
     *
     * This is the difference between "nothing happened, here is why" and a run that fills the
     * cache partition, gets its staging evicted by the platform mid-zip, and writes a half-built
     * archive that still reports success.
     *
     * Deliberately fails *open* when the partition reports nothing usable: a measurement we cannot
     * trust must not become a refusal to run, and a genuinely full disk still surfaces as per-app
     * failures recorded in the index.
     */
    private fun checkStagingSpace(
        apps: List<AppInfo>,
        usableStagingBytes: Long,
        concurrency: Int,
    ): BackupRejection? {
        val worst = apps.maxOf { it.peakStagingBytes(BundleFormat.autoFor(it)) }
        // Per app, times however many may stage at once, plus headroom for the manifest and for
        // ordinary cache churn from the rest of the app. Headroom, not a guarantee — the platform
        // can still evict cacheDir under pressure from something else entirely.
        val required = worst * concurrency.coerceAtLeast(1) + SPACE_MARGIN_BYTES
        return if (usableStagingBytes > 0 && usableStagingBytes < required) {
            BackupRejection.InsufficientStagingSpace(required, usableStagingBytes)
        } else {
            null
        }
    }

    /**
     * Stage the manifest inside the run's own staging directory, push it through the same writer
     * the bundles went through, then delete the staged copy.
     *
     * Goes through the export path's [ExportAppUseCase.writeStaged] rather than reaching for the
     * file store directly, because that is what guarantees the manifest lands in the same folder
     * the bundles did. Resolving the destination a second time here is how a non-modal destination
     * change used to produce a manifest describing a folder it was not in.
     *
     * The name carries the run's timestamp because both file-store paths write by name and delete
     * a collision first. A fixed `thor-backup.json` would mean the Tuesday export silently
     * *replaces* Monday's manifest while leaving Monday's bundles sitting there undescribed —
     * exactly the lie the manifest exists to prevent. One file per run instead; a reader that
     * wants the whole folder globs the prefix and merges.
     */
    private suspend fun writeIndex(
        entries: List<BackupEntry>,
        stagingDir: File,
        session: ExportSession,
    ): Boolean {
        if (entries.isEmpty()) return false
        val createdAt = System.currentTimeMillis()
        val staged = File(stagingDir, BackupIndex.fileNameFor(createdAt))
        return try {
            stagingDir.mkdirs()
            staged.writeText(
                BackupIndex(
                    createdAt = createdAt,
                    thorVersionCode = BuildConfig.VERSION_CODE,
                    entries = entries,
                ).encode()
            )
            exportAppUseCase.writeStaged(staged, session, BackupIndex.MIME)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing manifest costs the folder its description; it must not cost the user the
            // bundles that were already written successfully.
            Logger.e(TAG, "failed to write ${staged.name}", e)
            false
        } finally {
            // The file only. The directory is the run's and is taken whole by the caller's finally,
            // which also has to happen after this on the cancelled path.
            staged.delete()
        }
    }

    /**
     * A staging scope no other export can be inside.
     *
     * The builder wipes its per-package directory on entry, so two runs staging the same package
     * under one scope delete each other's work mid-copy — a single export of an app that a batch is
     * also exporting was enough. `nanoTime` rather than a counter because the scope has to be
     * unique across runs of *different* instances of this `@Factory` use case, and rather than a
     * wall clock because two runs can start in the same millisecond.
     *
     * Cleaned up by the caller's `finally`. A process killed mid-run leaves one behind, which the
     * platform reclaims with the rest of `cacheDir`.
     */
    private fun runStagingDir(): String = "$BATCH_STAGING_PREFIX${System.nanoTime()}"

    private fun entryFor(
        app: AppInfo,
        format: BundleFormat,
        fileName: String,
        bytes: Long?,
        error: String?,
    ) = BackupEntry(
        packageName = app.packageName,
        label = app.appName ?: app.packageName,
        versionCode = app.versionCode,
        versionName = app.versionName ?: "",
        format = format,
        // The name the bundle was *told* to take, not one re-derived here: re-deriving is what
        // made two same-labelled apps produce one file and two entries naming it.
        fileName = if (error == null) fileName else null,
        sizeBytes = bytes,
        error = error,
    )

    /** Source APK bytes: what actually ends up inside the bundle. */
    private fun AppInfo.apkPayloadBytes(): Long {
        val paths = buildList {
            (publicSourceDir ?: sourceDir)?.let { add(it) }
            addAll(splitPublicSourceDirs)
        }
        // length() is 0 for a path this process cannot stat, which is the same fail-open posture
        // checkStagingSpace takes: an unmeasurable app must not block the batch.
        return paths.sumOf { File(it).length() }
    }

    /**
     * Peak cache the builder holds for one app.
     *
     * Doubled for the zip formats because the builder copies every APK into `splits_staging` and
     * only deletes that directory *after* the zip is complete — so the copies and the archive are
     * both on disk at the same moment.
     */
    private fun AppInfo.peakStagingBytes(format: BundleFormat): Long =
        apkPayloadBytes() * if (format == BundleFormat.APK) 1 else 2

    private fun Throwable.describe(): String = "${this::class.simpleName}: ${message ?: "no message"}"

    private companion object {
        const val TAG = "BackupAppsUseCase"
        const val BATCH_STAGING_PREFIX = "export_batch_"

        // Room for the manifest plus ordinary cache churn from the rest of the app while a long
        // run is in flight. Small enough not to refuse a batch a nearly-full device could still
        // manage, big enough that the run is not living on the last few megabytes.
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}

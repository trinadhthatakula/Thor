// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.WorkerParameters
import com.valhalla.thor.data.repository.archiveStagingVolume
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY
import com.valhalla.thor.domain.model.RESTORE_URI_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSourceFactory
import com.valhalla.thor.domain.repository.SystemRepository
// `usecase`, not `repository`: `ArchiveHeaderOutcome` is declared alongside OpenArchiveUseCase.
import com.valhalla.thor.domain.usecase.ArchiveAuthenticationOutcome
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import com.valhalla.thor.domain.usecase.BackupAppArchiveUseCase
import com.valhalla.thor.domain.usecase.OpenArchiveUseCase
import com.valhalla.thor.domain.usecase.ReadInstalledAppFactsUseCase
import com.valhalla.thor.domain.usecase.RestoreAppArchiveUseCase
import com.valhalla.thor.util.Logger
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.Named

private const val TAG = "AppArchiveWorker"
internal const val ARCHIVE_AUTH_FAILURE_REASON =
    "this backup could not be authenticated and was not restored"
private val ARCHIVE_BACKUP = PrivilegeCommandClass("archive.backup")
private val ARCHIVE_RESTORE = PrivilegeCommandClass("archive.restore")

internal data class ArchiveRestorePackageFacts(
    val installed: InstalledAppFacts?,
    val appLabel: String?,
)

internal sealed interface ArchiveRestorePreflight {
    data class Ready(
        val header: ArchiveHeader,
        val packageFacts: ArchiveRestorePackageFacts,
        val decision: ArchiveRestoreDecision.Allowed,
    ) : ArchiveRestorePreflight

    data object AuthenticationRefused : ArchiveRestorePreflight
    data object PackageMismatch : ArchiveRestorePreflight
    data class GateRefused(val reason: ArchiveRestoreRefusal) : ArchiveRestorePreflight
}

/**
 * Executes the Worker's security-sensitive preflight in one testable order.
 *
 * No package fact or gate callback is reachable until complete archive authentication succeeds and
 * its package matches the persisted request. Keeping those effects behind lambdas lets JVM tests
 * prove the short circuit directly instead of inspecting source positions.
 */
internal suspend fun runArchiveRestorePreflight(
    expectedPackageName: String,
    selectedClasses: Set<DataClass>,
    authenticate: suspend () -> ArchiveAuthenticationOutcome,
    readPackageFacts: suspend (String) -> ArchiveRestorePackageFacts,
    evaluateGate: (ArchiveHeader, InstalledAppFacts?, Set<DataClass>) ->
    ArchiveRestoreDecision,
): ArchiveRestorePreflight {
    val authenticated = when (val outcome = authenticate()) {
        is ArchiveAuthenticationOutcome.Authenticated -> outcome
        ArchiveAuthenticationOutcome.WrongPassphrase,
        ArchiveAuthenticationOutcome.AuthenticationFailed,
            -> return ArchiveRestorePreflight.AuthenticationRefused
    }
    val header = authenticated.header
    if (header.packageName != expectedPackageName) return ArchiveRestorePreflight.PackageMismatch

    val packageFacts = readPackageFacts(expectedPackageName)
    return when (val decision = evaluateGate(header, packageFacts.installed, selectedClasses)) {
        is ArchiveRestoreDecision.Allowed -> ArchiveRestorePreflight.Ready(
            header = header,
            packageFacts = packageFacts,
            decision = decision,
        )

        is ArchiveRestoreDecision.Refused -> ArchiveRestorePreflight.GateRefused(decision.reason)
    }
}

internal fun archiveExecutionContext(
    commandClass: PrivilegeCommandClass,
    packageName: String,
    workRequestId: UUID,
): PrivilegeExecutionContext = PrivilegeExecutionContext(
    lane = PrivilegeExecutionLane.ARCHIVE,
    commandClass = commandClass,
    packageName = packageName,
    workRequestId = workRequestId,
)

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
    // For [usableStagingBytes] alone — the one question that decides which volume §7.4 is measured
    // against. Same implementing object as `systemRepository`; a narrow port, see [AppDataProbe].
    private val dataProbe: AppDataProbe,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
    sheetTargets: JobSheetTargets,
) : ThorJobWorker(appContext, params, notifications, registry, sheetTargets) {

    override val kind = ThorJobKind.ARCHIVE_BACKUP

    /**
     * Drop the derived key on every path `doWork` can reach.
     *
     * Was the base class's job while [ArchiveKeyHolder] was a constructor parameter of it; it is this
     * worker's now, because holding key material is a property of archive jobs and not of Thor's jobs.
     * The guarantees it rested on are unchanged — the base calls this from its `finally`, so it still
     * covers cancellation and any throw that fires before [runJob]'s own `take`, and it is still a
     * no-op afterwards (`ConcurrentHashMap.remove` on an absent key).
     *
     * The window it does **not** cover is unchanged too: a job cancelled between
     * [ArchiveKeyHolder.put] and WorkManager starting `doWork` never reaches any `finally`, so nothing
     * here runs. That branch is closed by [ArchiveKeyHolder]'s own expiry.
     */
    override fun onJobFinished() {
        keys.drop(id.toString())
    }

    /**
     * The package name, not the label.
     *
     * `getForegroundInfo()` is on the path that has to promote the service within a few seconds —
     * `ThorJobWorker.doWork` opens with `setForeground(getForegroundInfo())` — and a `PackageManager`
     * round trip does not belong there. **WorkManager itself never calls it for this work**: it
     * invokes `getForegroundInfoAsync()` from `WorkForegroundRunnable` only for an expedited
     * `WorkSpec`, and neither request `ThorJobLauncher` builds calls `setExpedited`. The deadline is
     * real; the call order the comment here used to assert was not.
     *
     * The first `publish()` from the use case replaces this with the label, well before a user reads
     * the shade — but only because `runJob` resolves the `AppInfo` and hands `appLabel` down. Without
     * that parameter the use case publishes `request.packageName`, then the `.xapk` file name, then a
     * `DataClass` id, and the shade shows `com.supercell.clashofclans` for the whole job. See the
     * `backup(…)` call below.
     */
    override val initialLabel: String
        get() = inputData.getString(BACKUP_PACKAGE_KEY).orEmpty()

    /**
     * Same package name, same reason as [initialLabel]: this is read on the way into `doWork()`, ahead
     * of `setForeground`, so it cannot afford a `PackageManager` round trip. `runJob` replaces it with
     * the real label as soon as it has one — see the `retargetSheet` call below. Until then a tap on
     * the notification opens a sheet headed with the application id, which is what `initialLabel`
     * already puts in the shade beside it.
     */
    override val sheetTarget: JobSheetTarget?
        get() = inputData.getString(BACKUP_PACKAGE_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { JobSheetTarget.Backup(packageName = it, appLabel = it) }

    override suspend fun runJob(): Result {
        val archiveRequest = decodeLegacyArchiveBackupRequest(inputData.keyValueMap)
        val runner = ArchiveBackupTaskRunner(
            operations = object : ArchiveBackupTaskOperations {
                override suspend fun reconcilePublished(
                    fileName: String,
                    expectedPackageName: String,
                    key: javax.crypto.SecretKey,
                ): ArchiveBackupOutcome.Completed? = backup.reconcilePublished(
                    fileName = fileName,
                    expectedPackageName = expectedPackageName,
                    key = key,
                )

                override suspend fun loadApp(packageName: String) =
                    appRepository.getAppDetails(packageName)

                override fun onAppResolved(appInfo: com.valhalla.thor.domain.model.AppInfo) {
                    retargetSheet(
                        JobSheetTarget.Backup(
                            packageName = appInfo.packageName,
                            appLabel = appInfo.appName ?: appInfo.packageName,
                        )
                    )
                }

                override suspend fun probeObb(
                    packageName: String,
                    execution: PrivilegeExecutionContext,
                ): ObbProbe = systemRepository.probeObb(packageName, execution)

                override suspend fun buildBundle(
                    appInfo: com.valhalla.thor.domain.model.AppInfo,
                    cacheSubDir: String,
                    format: BundleFormat,
                    execution: PrivilegeExecutionContext,
                ): kotlin.Result<File> = bundleBuilder.build(
                    appInfo = appInfo,
                    cacheSubDir = cacheSubDir,
                    format = format,
                    execution = execution,
                )

                override suspend fun usableStagingBytes(): Long = this@ArchiveBackupWorker
                    .usableStagingBytes()

                override suspend fun backup(
                    request: ArchiveBackupRequest,
                    key: javax.crypto.SecretKey,
                    bundle: File?,
                    bundleObbCapture: String,
                    bundleObbCount: Int,
                    versionCode: Long,
                    versionName: String?,
                    publicationFileName: String?,
                    usableStagingBytes: Long,
                    appLabel: String,
                    onProgress: (com.valhalla.thor.domain.model.ThorJobProgress) -> Unit,
                ): ArchiveBackupOutcome = this@ArchiveBackupWorker.backup(
                    request = request,
                    key = key,
                    bundle = bundle,
                    bundleObbCapture = bundleObbCapture,
                    bundleObbCount = bundleObbCount,
                    versionCode = versionCode,
                    versionName = versionName,
                    publicationFileName = publicationFileName,
                    usableStagingBytes = usableStagingBytes,
                    appLabel = appLabel,
                    onProgress = onProgress,
                )
            },
            ioDispatcher = ioDispatcher,
        )
        return runLegacyArchiveTask(
            taskId = id,
            decodedRequest = archiveRequest,
            invalidRequestReason = "this backup's request could not be read",
            requestFactory = { taskId, key, decoded ->
                DataTaskExecutionRequest(
                    taskId = taskId,
                    payload = DataTaskExecutionPayload.ArchiveBackup(
                        request = decoded,
                        key = key,
                        destination = com.valhalla.thor.domain.model.StoredDataDestination.ArchiveStore,
                    ),
                    item = DataTaskExecutionItem(
                        ordinal = 0,
                        packageName = decoded.packageName,
                        displayLabel = initialLabel,
                        deterministicStagingIdentity = taskId.toString(),
                        attemptCount = runAttemptCount,
                    ),
                    taskAttemptCount = runAttemptCount,
                    resumedFrom = null,
                )
            },
            takeKey = keys::take,
            runner = runner,
            checkpoints = LegacyWorkerCheckpointSink(::publish),
            results = LegacyWorkerResultSink(
                com.valhalla.thor.domain.model.DataTaskKind.ARCHIVE_BACKUP
            ),
            missingKeyReason = "this backup's key is no longer in memory — start it again",
        )
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
     * **The volume staging will actually use — never `cacheDir` unconditionally, and never the larger
     * of two.** `AppDataArchiveGatewayImpl.stagingRoot` stages under `cacheDir` when the privileged
     * runner can read `/data/data/<thor>` at mode 0700 and under `externalCacheDir` when it cannot — a
     * Shizuku shell at uid 2000 cannot — so both readers of that rule go through
     * [archiveStagingVolume]. This measured `cacheDir` outright while asserting the external route did
     * not exist, which on a device whose shared storage is a separate volume reported the wrong
     * partition's headroom; that is precisely how §7.4's gate is defeated, passing every class before
     * the `tar` fills a volume nobody measured. Taking the larger of the two is the same defect with a
     * friendlier face.
     *
     * The probe is a shell round trip and is not cached here. `DataArchiveCapabilityCache` is the
     * cached reader, but it awaits `PrivilegeState.isReady` — an unbounded suspension inside a
     * foreground-service worker. Asking [AppDataProbe] directly is what the gateway itself does moments
     * later, so the measurement and the routing cannot disagree about where the tar goes.
     *
     * Nor is over-reporting cheap. `ThorJobWorker` forbids `Result.retry()` outright — the key is in
     * process memory and a retry cannot succeed — so a `tar` that runs out of space ends the backup
     * after however many gigabytes it had already written.
     *
     * Zero is "unmeasurable", which the rule deliberately fails open on.
     */
    // No `withContext(ioDispatcher)`: the only call site is already inside one, and the probe makes
    // its own hop.
    @Suppress("UsableSpace")
    internal suspend fun usableStagingBytes(): Long =
        archiveStagingVolume(applicationContext, dataProbe.probePrivateDataCapability())
            ?.usableSpace ?: 0L
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
    sheetTargets: JobSheetTargets,
) : ThorJobWorker(appContext, params, notifications, registry, sheetTargets) {

    override val kind = ThorJobKind.ARCHIVE_RESTORE

    /** Same contract and same uncovered window as [ArchiveBackupWorker.onJobFinished]. */
    override fun onJobFinished() {
        keys.drop(id.toString())
    }

    override val initialLabel: String
        get() = inputData.getString(RESTORE_PACKAGE_KEY).orEmpty()

    /**
     * The archive URI, which is all the restore sheet needs — it re-opens the file and re-reads the
     * header for itself, exactly as it would after the picker. Unlike the backup side there is nothing
     * better to learn later, so no `retargetSheet` call follows.
     *
     * The URI is a task-scoped SAF grant, so this is only ever handed to a sheet inside the same
     * process and the same task. It is never persisted anywhere — see [JobSheetTargets].
     */
    override val sheetTarget: JobSheetTarget?
        get() = inputData.getString(RESTORE_URI_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let(JobSheetTarget::Restore)

    override suspend fun runJob(): Result {
        val archiveRequest = decodeLegacyArchiveRestoreRequest(inputData.keyValueMap)
        if (archiveRequest == null) {
            Logger.e(TAG, "ArchiveRestoreRequest could not be read from input data")
        }
        val runner = ArchiveRestoreTaskRunner(
            operations = object : ArchiveRestoreTaskOperations {
                override suspend fun open(uriString: String): ArchiveOpenOutcome =
                    sources.open(uriString)

                override suspend fun authenticate(
                    source: com.valhalla.thor.domain.repository.ArchiveSource,
                    key: javax.crypto.SecretKey,
                ): ArchiveAuthenticationOutcome = openArchive.authenticate(source, key)

                override suspend fun readPackageFacts(
                    packageName: String,
                ): ArchiveRestorePackageFacts {
                    val app = appRepository.getAppDetails(packageName)
                    return ArchiveRestorePackageFacts(
                        installed = app?.let { installedFacts(it) },
                        appLabel = app?.appName,
                    )
                }

                override fun evaluateGate(
                    header: ArchiveHeader,
                    installed: InstalledAppFacts?,
                    classes: Set<DataClass>,
                ): ArchiveRestoreDecision = evaluateArchiveRestoreGate(
                    header,
                    installed,
                    classes,
                )

                override suspend fun restore(
                    source: com.valhalla.thor.domain.repository.ArchiveSource,
                    header: ArchiveHeader,
                    key: javax.crypto.SecretKey,
                    classes: List<DataClass>,
                    installFirst: Boolean,
                    restoreObb: Boolean,
                    execution: PrivilegeExecutionContext,
                    appLabel: String,
                    onProgress: (com.valhalla.thor.domain.model.ThorJobProgress) -> Unit,
                ): ArchiveRestoreOutcome = this@ArchiveRestoreWorker.restore(
                    source = source,
                    header = header,
                    key = key,
                    classes = classes,
                    installFirst = installFirst,
                    restoreObb = restoreObb,
                    execution = execution,
                    appLabel = appLabel,
                    onProgress = onProgress,
                )
            },
            ioDispatcher = ioDispatcher,
        )
        return runLegacyArchiveTask(
            taskId = id,
            decodedRequest = archiveRequest,
            invalidRequestReason = "this restore's request could not be read",
            requestFactory = { taskId, key, decoded ->
                DataTaskExecutionRequest(
                    taskId = taskId,
                    payload = DataTaskExecutionPayload.ArchiveRestore(
                        request = decoded,
                        key = key,
                    ),
                    item = DataTaskExecutionItem(
                        ordinal = 0,
                        packageName = decoded.packageName,
                        displayLabel = initialLabel,
                        deterministicStagingIdentity = taskId.toString(),
                        attemptCount = runAttemptCount,
                    ),
                    taskAttemptCount = runAttemptCount,
                    resumedFrom = null,
                )
            },
            takeKey = keys::take,
            runner = runner,
            checkpoints = LegacyWorkerCheckpointSink(::publish),
            results = LegacyWorkerResultSink(
                com.valhalla.thor.domain.model.DataTaskKind.ARCHIVE_RESTORE
            ),
            missingKeyReason = "this restore's key is no longer in memory — start it again",
        )
    }
}

/**
 * The sentence a failed restore reports, including what it may have destroyed.
 *
 * Three facts, and the third is the one that was being dropped:
 *  - [ArchiveRestoreOutcome.Failed.reason], always.
 *  - [ArchiveRestoreOutcome.Failed.classesRestored] — a partial restore is not "nothing happened",
 *    and a user told only "failed" does not know their app is now holding another day's data.
 *  - [ArchiveRestoreOutcome.Failed.classPossiblyCleared] — populated by `RestoreAppArchiveUseCase` on
 *    a `SwapFailed`, and until now read by nobody. `swapStagedEntriesCommand` deletes the class root's
 *    entries and *then* moves the staged ones in, so a single non-zero exit spans "the guard stopped
 *    it before anything was deleted" and "the delete ran and the move did not". The second of those
 *    is the app's data gone with nothing in its place, §8.3 has no undo rung, and the user is the only
 *    one who can act on it — by restoring again, or by not launching the app until they have. Saying
 *    nothing tells them less than the truth at the one moment the truth is that their data may be gone.
 *
 * Deliberately hedged rather than asserted. Thor does not know which of the two states it is in, and
 * a sentence that claimed the data *was* cleared would be wrong roughly as often as it was right.
 *
 * Top-level so this reporting decision remains covered by a plain JVM test.
 */
internal fun restoreFailureReason(outcome: ArchiveRestoreOutcome.Failed): String = buildString {
    append(outcome.reason)
    if (outcome.classesRestored.isNotEmpty()) {
        append(" (")
        append(outcome.classesRestored.joinToString { dataClass -> dataClass.id })
        append(" was already replaced)")
    }
    val cleared = outcome.classPossiblyCleared
    if (cleared != null) {
        append(". Thor could not tell whether ")
        append(cleared.id)
        append(" was left as it was or emptied, so check the app before you use it")
    }
}

/**
 * What to tell the user about [ObbPlacement], or null when another channel already says it.
 *
 * This is the reader `ArchiveRestoreOutcome.Completed.obb` did not have. Arm by arm:
 *  - `null` — no placement ran. An install-first restore (`installBundle` places game data inside the
 *    install), a data-only archive, or the user did not ask for it. Nothing to report.
 *  - [ObbPlacement.Failed] — `RestoreAppArchiveUseCase` has **already** added "the game data could not
 *    be placed: …" to `warnings`, with the same reason. A second sentence here would print it twice.
 *  - [ObbPlacement.Placed] — game data landed, which is the restore working. It reaches the user as
 *    the log line at the call site and nothing more: the screen renders this array under
 *    `R.string.restore_done_warnings`, "Some parts did not finish:", and good news does not belong
 *    under that heading.
 *  - [ObbPlacement.NotNeeded] — the one arm no channel covers. The user ticked "restore game data",
 *    the archive *did* carry an installer, and it turned out to hold no expansion files. The existing
 *    "this archive holds no game data" warning is not this case: it is raised earlier and only when
 *    there is no bundle at all. Without this line the checkbox silently does nothing.
 *
 * Exhaustive on [ObbPlacement] on purpose — a fourth arm should not compile until someone has decided
 * what it says.
 */
internal fun obbNotice(placement: ObbPlacement?): String? = when (placement) {
    null -> null
    is ObbPlacement.Failed -> null
    is ObbPlacement.Placed -> null
    ObbPlacement.NotNeeded ->
        "the app installer in this backup carries no game data, so none was placed"
}

/**
 * Why the gate refused, in words, for the sentence the restore screen shows when the job stops.
 *
 * This site used to concatenate [ArchiveRestoreRefusal] itself. Enums have no `toString` override, so
 * what the user read was `SIGNER_MISMATCH` — a Kotlin identifier, in a sentence, at the one moment
 * they are being told their restore did not happen.
 *
 * Three things about the fix are worth stating, because each of them is a claim someone will want to
 * check:
 *
 * **The sentences are English literals, and that is the design here, not an oversight.** The screen
 * already translates all nine through `ArchiveRestoreScreen.refusalLabel`, and this is deliberately
 * not that mapping reused. The worker's channel is `JOB_ERROR_KEY` in a `Data`, which the screen
 * renders as it arrives; every other failure sentence this file produces —
 * [restoreFailureReason], [obbNotice], the `fail(...)` literals in both workers — is an untranslated
 * literal for the same reason. Reaching `refusalLabel` would mean a `Context` and `getString`, which
 * takes this function off the JVM test classpath, and that is the thing the top-level shape exists to
 * protect. The cost is real and is written down here rather than argued away: nine sentences now say
 * roughly what nine strings say, in two files, and they can drift.
 *
 * **Six of the nine arms should never be seen.** `ArchiveRestoreUiState.canStart` requires
 * `refusal == null` from this same gate over this same header and class set, and the worker's
 * package-name equality check runs before the gate. What can genuinely change between the confirm
 * screen and the job running is the *installed app* — so `SIGNER_MISMATCH`, `SIGNER_UNVERIFIABLE` and
 * `DATA_ONLY_AND_APP_ABSENT` are the reachable three. The other six are worded anyway: "unreachable"
 * is a reading of two call paths, not a property the compiler holds, and the failure mode of being
 * wrong about it is precisely the identifier-in-a-sentence this function was added to remove.
 *
 * **Exhaustive on purpose.** A tenth refusal must not compile until someone has decided what it says.
 * A `when` over an enum with no `else` is the only thing that forces that, and it is the sole reason
 * this is a `when` rather than a map. Note that `task-15-review.md`'s hard-constraint row 4 recorded
 * this property as already satisfied by *the worker*, in a build where the worker contained no `when`
 * at all and printed the constant; it is satisfied now, here, and the row has been corrected.
 */
internal fun refusalReason(refusal: ArchiveRestoreRefusal): String = when (refusal) {
    ArchiveRestoreRefusal.SIGNER_MISMATCH ->
        "the installed app is signed by a different developer than the one this backup came from"

    ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE ->
        "the installed app's signature could not be read, so Thor could not check that this backup " +
                "belongs to it"

    ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT ->
        "the app is no longer installed and this backup holds no installer to add it back from"

    ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE ->
        "this backup does not hold one of the things that were selected"

    ArchiveRestoreRefusal.NOTHING_SELECTED ->
        "nothing was selected to restore"

    ArchiveRestoreRefusal.SCHEMA_TOO_NEW ->
        "it was made by a newer version of Thor"

    ArchiveRestoreRefusal.INVALID_SCHEMA_VERSION ->
        "it does not say what format it is in, so the file is damaged or was not written by Thor"

    ArchiveRestoreRefusal.INVALID_PACKAGE_NAME ->
        "it names an app in a way Thor will not accept, so the file is damaged or was not written by " +
                "Thor"

    ArchiveRestoreRefusal.INVALID_USER_ID ->
        "it names a user profile Thor will not accept, so the file is damaged or was not written by " +
                "Thor"
}

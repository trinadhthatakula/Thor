// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveBundleCacheDir
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRefusal
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BACKUP_PACKAGE_KEY
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.JOB_WARNINGS_KEY
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.RESTORE_PACKAGE_KEY
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.captureName
import com.valhalla.thor.domain.model.evaluateArchiveRestoreGate
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
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
import java.util.Base64
import javax.crypto.SecretKey
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
     * **`cacheDir` alone, and never the larger of two volumes.** `AppDataArchiveGatewayImpl.stagingRoot`
     * resolves `File(context.cacheDir, AppDataArchiveStagingDir.NAME)` and has no external fallback —
     * that is a §14 limitation held on purpose, because a staged tar is plaintext app data and
     * external cache is world-readable on some device configurations. Measuring the *larger* of the
     * two therefore reported another volume's free space for a write that only ever lands on this
     * one, which is precisely how §7.4's gate is defeated: on a phone with a roomy SD card and a full
     * internal partition it passes every class and the `tar` fills the device.
     *
     * Nor is over-reporting cheap. `ThorJobWorker` forbids `Result.retry()` outright — the key is in
     * process memory and a retry cannot succeed — so a `tar` that runs out of space ends the backup
     * after however many gigabytes it had already written.
     *
     * Zero is "unmeasurable", which the rule deliberately fails open on.
     */
    @Suppress("UsableSpace")
    private fun usableStagingBytes(): Long = applicationContext.cacheDir?.usableSpace ?: 0L
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
    // For [wrongKeyReason] alone — one HMAC against the header's verifier before anything is
    // decrypted. The use case has its own reference; this is not a shared piece of state.
    private val cipher: AppArchiveCipher,
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

        // Two failures, two sentences. This job holds no file picker, so neither is an instruction —
        // but "that file is not a Thor backup" and "Thor could not read that file" send the user to
        // different places, and the job's reason is what the finished notification shows.
        val source = when (val opened = sources.open(request.uriString)) {
            is ArchiveOpenOutcome.Opened -> opened.source
            ArchiveOpenOutcome.NotAnArchive ->
                return fail("that file is not a Thor backup")
            ArchiveOpenOutcome.Unreadable ->
                return fail("Thor could not read that backup file")
        }

        return source.use {
            // Exhaustive on purpose, and left exhaustive on purpose. `ArchiveHeaderOutcome` has two
            // arms today and the review asked whether a third — an `Unreadable` distinct from
            // `NotAnArchive` — should be added. It is not added here: the type and its only producer
            // are `OpenArchiveUseCase`'s, outside this slice, and adding an `else ->` here *first*
            // would trade the compiler error that forces this site to be revisited for silence. This
            // `when` failing to compile is the mechanism by which a new arm gets a considered
            // sentence rather than a default one, so it stays the way it is until the arm exists.
            val header = when (val read = openArchive.readHeader(source)) {
                is ArchiveHeaderOutcome.Read -> read.header
                is ArchiveHeaderOutcome.NotAnArchive -> return@use fail(read.reason)
            }
            // The URI named a different archive than the screen was looking at. A `content://` URI is
            // a handle to a document, not to bytes.
            if (header.packageName != request.packageName) {
                return@use fail("that backup file is not ${request.packageName}'s any more")
            }
            // Before a byte is decrypted, because the key in hand may not be this archive's — see
            // [wrongKeyReason].
            wrongKeyReason(header, key, cipher)?.let { return@use fail(it) }

            // Resolved once and handed to the use case below, which would otherwise ask the
            // repository for the same package a second line later. `null` is "not installed", which
            // is a state the gate handles — see `installFirst`.
            val app = appRepository.getAppDetails(request.packageName)
            // The same assembly the restore screen ran before it offered the button, from the same
            // use case: two spellings of "installed" is two gates, and only one of them would have
            // been the one the user was shown.
            val installed = app?.let { installedFacts(it) }
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
                        refusalReason((decision as ArchiveRestoreDecision.Refused).reason)
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
                    // The only reader of `Completed.obb` in the app — its KDoc used to say nothing
                    // read it. Logged in full at every arm, and turned into a sentence only for the
                    // arm no other channel covers; see [obbNotice].
                    outcome.obb?.let { Logger.i(TAG, "game data placement: $it") }
                    val warnings = outcome.warnings + listOfNotNull(obbNotice(outcome.obb))
                    warnings.forEach { Logger.w(TAG, it) }
                    // Carried out on the *success* result, not only logged. These are the sentences a
                    // restore finished in spite of — game data that could not be placed, a breadcrumb
                    // that could not be written — and a user whose game now starts and crashes has no
                    // other way to learn why. `Data` caps at 10 KB; the use case produces at most
                    // three short sentences.
                    Result.success(workDataOf(JOB_WARNINGS_KEY to warnings.toTypedArray()))
                }

                is ArchiveRestoreOutcome.Failed -> fail(restoreFailureReason(outcome))
            }
        }
    }
}

/**
 * Why the key this job is holding cannot open the archive it just re-read — or null when it can.
 *
 * **This replaced a KDF-count comparison, and the reason matters.** The count check was a proxy for
 * one specific way the key could be wrong: `ThorJobLauncher.startRestore` used to derive with
 * `deriveKey(passphrase, salt)` — no iteration count, so this build's [KDF_ITERATIONS] — while
 * `OpenArchiveUseCase.unlock` passed `header.kdf.iterations`. Any archive not written at today's
 * number therefore unlocked on the confirm screen and then failed every GCM tag inside the job, and
 * what the user read was that their backup was damaged. It was not; the build was. That divergence is
 * now fixed at its source: `ArchiveJobLauncher.startRestore` takes `iterations` and the restore screen
 * passes the header's own. Left in place, the count check would have refused precisely the archives
 * the fix made restorable.
 *
 * What is checked instead is the thing the count was standing in for. `ArchiveHeader.verifier` is
 * `HMAC(key, "thor-data-archive-v1")`, and comparing it answers "is this key this archive's key?"
 * without caring *why* it might not be — a different round count, a different salt, or a `content://`
 * URI whose document was replaced between the confirm screen and the job (§8.3 re-reads the header for
 * exactly that reason, and the package-name check just above catches only the case where the
 * substitute belongs to another app). One HMAC, before a byte of ciphertext is touched.
 *
 * The worker cannot re-derive its way out of a mismatch: it never sees a passphrase, which is the
 * whole reason the key travels through `ArchiveKeyHolder` (§9.2). Refusing before anything is written
 * is the whole of what this layer can do, and the sentence sends the user back to the file rather than
 * leaving them with "damaged".
 *
 * Top-level rather than a method so a JVM test can reach it: nothing inside a `CoroutineWorker` is
 * reachable without an Android runtime, and this module has no Robolectric.
 */
internal fun wrongKeyReason(header: ArchiveHeader, key: SecretKey, cipher: AppArchiveCipher): String? {
    // `java.util.Base64`, matching `OpenArchiveUseCase`: `android.util.Base64` throws "not mocked"
    // under JVM tests and would take this function off the test classpath with it.
    val expected = runCatching { Base64.getDecoder().decode(header.verifier) }.getOrNull()
        ?: return "this backup's header could not be read well enough to check the passphrase"
    // `cipher.verify` is `MessageDigest.isEqual`, so a wrong-length verifier answers false rather
    // than throwing — which is the right answer here, and is reported the same way.
    return if (cipher.verify(key, expected)) {
        null
    } else {
        "this backup could not be opened with the passphrase this restore was started with — " +
            "open the file again and unlock it"
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
 * Top-level for the same reason as [wrongKeyReason]: this is the only way it can be tested.
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
 * renders as it arrives; every other failure sentence this file produces — [wrongKeyReason],
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

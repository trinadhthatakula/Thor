// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.ArchiveIntegrityException
import com.valhalla.thor.data.repository.MAX_STAGED_BUNDLE_BYTES
import com.valhalla.thor.data.repository.copyAtMostTo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationBusy
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.repository.PackageOperationCoordinator
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.annotation.Factory
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.crypto.SecretKey

sealed interface ArchiveRestoreOutcome {

    /**
     * @param warnings things the user should know that are not failures — a failed OBB placement, a
     *   class the archive skipped. Shown alongside §8.6's "launch the app to check".
     * @param obb what happened to the game data, and only when **this** class placed it. Null on an
     *   install-first restore even though game data *was* placed there — `installBundle` places OBB
     *   inside the install, and `ArchiveInstallOutcome.InstalledWithoutGameData` is the only signal
     *   that path produces. Also null when the archive holds no bundle, or the user did not ask for
     *   game data. `AppArchiveWorker` logs every arm of it and turns exactly one — the arm no
     *   [warnings] entry already covers — into a sentence for the user; a failed placement is a
     *   warning raised here, and a plain success stays quiet.
     */
    data class Completed(
        val classesRestored: List<DataClass>,
        val warnings: List<String>,
        val obb: ObbPlacement?,
    ) : ArchiveRestoreOutcome

    /**
     * @param classesRestored the classes that **did** land before the failure. "Restore failed" over a
     *   `CE` that is already replaced tells the user nothing they can act on. A class whose swap
     *   landed but whose ownership or SELinux labels could not be set is **in** this list: the class
     *   root holds the archive's copy either way, which is the fact the user has to act on, and
     *   [reason] is what says the app may not be able to read it.
     * @param classPossiblyCleared the class whose swap failed, when Thor cannot tell what that failure
     *   left behind. `swapStagedEntriesCommand` deletes the class root's entries and *then* moves the
     *   staged ones in, so one non-zero exit covers everything from "the non-empty guard stopped it
     *   before anything was deleted" to "the delete ran and the move did not" — and that second state
     *   is this app's old data gone with nothing in its place. Thor cannot tell which happened and
     *   cannot undo either (§8.3 has no undo rung), so naming the class is the whole of what the
     *   reporting can do; saying nothing tells the user *less* than the truth at the one moment the
     *   truth is that their data may be gone. Null on every other failure, all of which leave the
     *   class they stopped on exactly as it was.
     */
    data class Failed(
        val reason: String,
        val classesRestored: List<DataClass>,
        val classPossiblyCleared: DataClass? = null,
    ) : ArchiveRestoreOutcome
}

/**
 * §8.3, in order.
 *
 * Restore **replaces** a class wholesale; it does not merge. Stale files from the current install
 * would otherwise survive a restore that the user believes returned the app to a known state. The
 * confirmation before this runs says so in those words.
 *
 * **There is no retry and no resume.** The first failure ends the run and leaves the breadcrumb in
 * place, because the only two recoveries available are both worse than stopping: re-entering
 * [AppDataArchiveGateway.extractInto] deletes the staged tree (`extractCommand` opens with
 * `rm -rf '<staging>'`), and promoting a staged tree without re-extracting can promote a *different
 * archive's* leftovers into this app. §8.5's breadcrumb is what turns that stop into something the
 * user is told about.
 *
 * `internal` for the same reason [AppDataArchiveGateway] is: a public class cannot take an `internal`
 * type in its constructor. Matches [BackupAppArchiveUseCase].
 */
@Factory
internal class RestoreAppArchiveUseCase(
    private val gateway: AppDataArchiveGateway,
    private val installer: AppArchiveInstaller,
    private val breadcrumbs: ArchiveBreadcrumbStore,
    private val cipher: AppArchiveCipher,
    private val packageOperationCoordinator: PackageOperationCoordinator,
) {

    /**
     * **Precondition: the caller has already run `evaluateArchiveRestoreGate`** (§8.1, in
     * `domain/model/ArchiveRestoreGate.kt`) against this [header] and the live app, and got back an
     * `ArchiveRestoreDecision.Allowed`. This use case does **not** re-run that gate, and must never be
     * called on a `Refused` decision.
     *
     * That matters most for the signer. When [installFirst] is false there is **no signer comparison
     * anywhere in this class** — the gate already made it against the installed app, and repeating it
     * here would compare the same two values twice. A path that enqueues a restore without the gate —
     * a deep link, a retry button, a "restore again" affordance — therefore restores an archive over a
     * same-named, differently-signed package, which is the attack `ArchiveHeader.signerSha256`'s own
     * KDoc exists to name and the one refusal §8.1 allows no override for. It would also skip
     * `SCHEMA_TOO_NEW`, `INVALID_PACKAGE_NAME` and `INVALID_USER_ID`, all of which feed untrusted
     * header fields into the paths this use case writes to.
     *
     * @param installFirst take it from `ArchiveRestoreDecision.Allowed.installFirst`, never from a
     *   fresh "is it installed?" check: the two can disagree, and this one is the one the user was
     *   shown. True means the app is absent and is installed from the archive's bundle first, after
     *   which the signer *is* checked here — the gate could not check an absent app's.
     * @param restoreObb "place the archive's game data". **Not honoured when [installFirst] is true**
     *   and the header declares game data: the install runs from the container's own `.xapk` and
     *   `AppArchiveInstaller.installBundle` writes that bundle's OBB as part of it, so there is no
     *   install-first path that leaves game data out. That combination places it anyway and says so
     *   in [ArchiveRestoreOutcome.Completed.warnings]; the restore screen stops offering the toggle on
     *   that path for the same reason. On an archive that holds no bundle
     *   (`header.appBundle == null` — a data-only backup) there is nothing to place, so this is
     *   satisfied by doing nothing and saying so in [ArchiveRestoreOutcome.Completed.warnings]; it is
     *   **not** a failure, and the data restore proceeds. It is a failure whenever the header declares
     *   a bundle this run cannot produce — the entry is missing from the container, reading it threw,
     *   or Thor's cache could not hold it. See [BundleStaging].
     * @param onProgress called on the calling coroutine, like [BackupAppArchiveUseCase]'s. The worker
     *   forwards it to `JobRegistry`. This use case takes no dispatcher of its own and does blocking
     *   work — the decrypt and the bundle copy — on the caller's, so **the worker must call it on
     *   `@Named("io")`**.
     */
    suspend operator fun invoke(
        source: ArchiveSource,
        header: ArchiveHeader,
        key: SecretKey,
        classes: List<DataClass>,
        installFirst: Boolean,
        restoreObb: Boolean,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
        appLabel: String = header.packageName,
        onProgress: (ThorJobProgress) -> Unit = {},
    ): ArchiveRestoreOutcome = when (
        val lease = packageOperationCoordinator.withPackageLease(
            packageName = header.packageName,
            owner = PackageOperationOwner.ARCHIVE_RESTORE,
            admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
        ) {
            runRestore(
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
        }
    ) {
        is PackageLeaseResult.Acquired -> lease.value
        is PackageLeaseResult.Busy -> ArchiveRestoreOutcome.Failed(
            reason = PackageOperationBusy(lease.owner).message
                ?: "Package operation busy: ${lease.owner}",
            classesRestored = emptyList(),
        )
    }

    private suspend fun runRestore(
        source: ArchiveSource,
        header: ArchiveHeader,
        key: SecretKey,
        classes: List<DataClass>,
        installFirst: Boolean,
        restoreObb: Boolean,
        execution: PrivilegeExecutionContext,
        appLabel: String,
        onProgress: (ThorJobProgress) -> Unit,
    ): ArchiveRestoreOutcome {
        val pkg = header.packageName
        val restored = mutableListOf<DataClass>()
        val warnings = mutableListOf<String>()

        // The bundle is needed for an install and for OBB, and only then. Extracting it otherwise
        // would cost the app's whole download for nothing.
        Logger.i(TAG, "Starting restore: pkg=$pkg, classes=$classes, installFirst=$installFirst, restoreObb=$restoreObb")
        val staging =
            if (installFirst || restoreObb) extractBundle(source, header) else BundleStaging.None
        Logger.i(
            TAG,
            "Bundle staging completed state=" + when (staging) {
                BundleStaging.None -> "none"
                is BundleStaging.Staged -> "staged"
                is BundleStaging.Unreadable -> "unreadable"
            },
        )
        val bundle = (staging as? BundleStaging.Staged)?.file

        // §8.5's marker, written once at whichever irreversible step comes first.
        var breadcrumbWritten = false
        suspend fun markRestoreStarted() {
            if (breadcrumbWritten) return
            breadcrumbWritten = true
            if (!breadcrumbs.write(pkg, appLabel)) {
                Logger.e(TAG, "the restore breadcrumb could not be written; proceeding without it")
                warnings += "Thor could not record that this restore started, " +
                    "so it will not be able to report it if the restore is interrupted"
            }
        }

        suspend fun failBeforeAnyDataWasWritten(reason: String): ArchiveRestoreOutcome.Failed {
            Logger.e(TAG, "Restore failed before data was written: $reason")
            if (breadcrumbWritten) breadcrumbs.clear()
            return ArchiveRestoreOutcome.Failed(reason, restored)
        }

        try {
            if (staging is BundleStaging.Unreadable) {
                Logger.e(TAG, "archive bundle staging failed package=$pkg")
                return ArchiveRestoreOutcome.Failed(staging.reason, restored)
            }
            if (installFirst && bundle == null) {
                Logger.e(TAG, "installFirst was true but bundle was null")
                return ArchiveRestoreOutcome.Failed(
                    "this archive holds only data, so $appLabel cannot be installed from it", restored
                )
            }
            if (restoreObb && staging is BundleStaging.None) {
                warnings += "this archive holds no game data, so none was placed"
            }

            if (installFirst) {
                if (!restoreObb && (header.appBundle?.obbCount ?: 0) > 0) {
                    warnings += "this app was installed from the archive, and that install places " +
                        "its game data too, so it was not left out"
                }
                onProgress(ThorJobProgress(ThorJobStage.INSTALLING, appLabel))
                markRestoreStarted()
                Logger.i(TAG, "Installing archive bundle package=$pkg")
                when (val outcome = installer.installBundle(bundle!!, pkg, execution)) {
                    ArchiveInstallOutcome.Installed -> {
                        Logger.i(TAG, "Bundle installed successfully")
                    }

                    is ArchiveInstallOutcome.InstalledWithoutGameData -> {
                        Logger.w(TAG, "Bundle installed without game data: ${outcome.reason}")
                        warnings += "the game data could not be placed: ${outcome.reason}"
                    }

                    is ArchiveInstallOutcome.Failed -> {
                        Logger.e(TAG, "Bundle install failed: ${outcome.reason}")
                        return failBeforeAnyDataWasWritten(outcome.reason)
                    }

                    ArchiveInstallOutcome.Unconfirmed -> {
                        Logger.e(TAG, "Bundle install was unconfirmed")
                        return failBeforeAnyDataWasWritten(
                            "Thor could not confirm $appLabel finished installing, so it wrote no data"
                        )
                    }
                }
                val signer = gateway.signerSha256(pkg)
                Logger.i(TAG, "Installed app signer checked package=$pkg")
                if (signer == null || !signer.equals(header.signerSha256, ignoreCase = true)) {
                    return failBeforeAnyDataWasWritten(
                        "the app that installed is not signed by the key this archive was made from"
                    )
                }
            }

            val uid = gateway.appUid(pkg)
            Logger.i(TAG, "App UID for $pkg: $uid")
            if (uid == null) {
                return failBeforeAnyDataWasWritten(
                    "Thor could not read $appLabel's user id, so it wrote no data"
                )
            }

            Logger.d(TAG, "Force stopping $pkg")
            gateway.forceStop(pkg)
            markRestoreStarted()

            val totalBytes = classes.sumOf { header.member(it)?.plainBytes ?: 0L }
            var doneBytes = 0L

            for (dataClass in classes) {
                val member = header.member(dataClass)
                    ?: run {
                        Logger.e(TAG, "Missing member for dataClass ${dataClass.id}")
                        return failWithBreadcrumbKept(
                            "this archive has no ${dataClass.id} data", restored
                        )
                    }
                onProgress(restoring(appLabel, doneBytes, totalBytes))
                Logger.i(TAG, "Restoring dataClass: ${dataClass.id} (${member.plainBytes} bytes)")

                when (val outcome = restoreClass(source, dataClass, member, key, pkg, uid)) {
                    ClassOutcome.Replaced -> {
                        Logger.i(TAG, "dataClass ${dataClass.id} Replaced successfully")
                    }

                    is ClassOutcome.ReplacedUnusable -> {
                        Logger.e(TAG, "dataClass ${dataClass.id} ReplacedUnusable: ${outcome.reason}")
                        restored += dataClass
                        return failWithBreadcrumbKept(outcome.reason, restored)
                    }

                    is ClassOutcome.NotReplaced -> {
                        Logger.e(TAG, "dataClass ${dataClass.id} NotReplaced: ${outcome.reason}")
                        return failWithBreadcrumbKept(outcome.reason, restored)
                    }

                    is ClassOutcome.SwapFailed -> {
                        Logger.e(TAG, "dataClass ${dataClass.id} SwapFailed: ${outcome.reason}")
                        return failWithBreadcrumbKept(
                            outcome.reason, restored, classPossiblyCleared = dataClass
                        )
                    }
                }

                restored += dataClass
                doneBytes += member.plainBytes
            }

            if (restoreObb && !installFirst && bundle != null) {
                onProgress(restoring(appLabel, doneBytes, totalBytes))
                Logger.i(TAG, "Placing OBB game data...")
                val placement = installer.placeBundleObb(bundle, pkg)
                if (placement is ObbPlacement.Failed) {
                    Logger.e(TAG, "OBB placement failed: ${placement.reason}")
                    warnings += "the game data could not be placed: ${placement.reason}"
                }
                gateway.forceStop(pkg)
                breadcrumbs.clear()
                return ArchiveRestoreOutcome.Completed(restored, warnings, placement)
            }

            onProgress(ThorJobProgress(ThorJobStage.FINISHING, appLabel, totalBytes, totalBytes))
            gateway.forceStop(pkg)
            breadcrumbs.clear()
            Logger.i(TAG, "Restore completed successfully: classesRestored=$restored, warnings=$warnings")
            return ArchiveRestoreOutcome.Completed(restored, warnings, obb = null)
        } finally {
            bundle?.delete()
        }
    }

    private sealed interface ClassOutcome {
        data object Replaced : ClassOutcome
        data class ReplacedUnusable(val reason: String) : ClassOutcome
        data class NotReplaced(val reason: String) : ClassOutcome
        data class SwapFailed(val reason: String) : ClassOutcome
    }

    private suspend fun restoreClass(
        source: ArchiveSource,
        dataClass: DataClass,
        member: ArchiveMember,
        key: SecretKey,
        packageName: String,
        uid: Int,
    ): ClassOutcome {
        val staged = gateway.stagingFile("restore-${dataClass.id}.tar")
        Logger.d(TAG, "archive class staging started dataClass=${dataClass.id}")
        try {
            val ciphertext = try {
                source.openEntry(member.fileName)
                    ?: return ClassOutcome.NotReplaced("this archive is missing ${member.fileName}")
            } catch (e: IOException) {
                Logger.e(TAG, "archive member open failed dataClass=${dataClass.id}")
                return ClassOutcome.NotReplaced("this archive could not be read: ${e.message}")
            }
            val nonce = runCatching { Base64.getDecoder().decode(member.nonce) }.getOrNull()
                ?: return ClassOutcome.NotReplaced(
                    "this archive's ${dataClass.id} member has an unreadable nonce"
                )

            try {
                ciphertext.use { input ->
                    staged.outputStream().use { output ->
                        cipher.decryptMember(member.fileName, input, output, key, nonce, member.chunkCount)
                    }
                }
                Logger.d(TAG, "Decrypted ${member.fileName} (${staged.length()} bytes)")
            } catch (_: ArchiveIntegrityException) {
                Logger.e(TAG, "archive member integrity failed dataClass=${dataClass.id}")
                return ClassOutcome.NotReplaced(
                    "this archive's ${dataClass.id} data is damaged and was not restored"
                )
            } catch (e: IOException) {
                Logger.e(TAG, "archive member staging failed dataClass=${dataClass.id}")
                return ClassOutcome.NotReplaced(
                    "${dataClass.id} could not be written to Thor's cache: ${e.message}"
                )
            }

            currentCoroutineContext().ensureActive()

            val compressed = ArchiveCompression.fromId(member.compression) == ArchiveCompression.GZIP
            Logger.d(TAG, "Calling extractInto for ${dataClass.id} (compressed=$compressed)")
            if (!gateway.extractInto(packageName, dataClass, staged, compressed)) {
                Logger.e(TAG, "gateway.extractInto returned false for ${dataClass.id}")
                return ClassOutcome.NotReplaced("${dataClass.id} could not be unpacked")
            }
            Logger.d(TAG, "Calling swapStaged for ${dataClass.id}")
            if (!gateway.swapStaged(packageName, dataClass)) {
                Logger.e(TAG, "gateway.swapStaged returned false for ${dataClass.id}")
                return ClassOutcome.SwapFailed(
                    "${dataClass.id} could not be put into place, and Thor cannot tell whether its " +
                        "previous data was already deleted"
                )
            }
            if (dataClass.isInternal) {
                Logger.d(TAG, "Calling chownClass for ${dataClass.id} (uid=$uid)")
                if (!gateway.chownClass(packageName, dataClass, uid)) {
                    Logger.e(TAG, "gateway.chownClass returned false for ${dataClass.id}")
                    return ClassOutcome.ReplacedUnusable(
                        "${dataClass.id} was restored but its ownership could not be set"
                    )
                }
                Logger.d(TAG, "Calling relabelClass for ${dataClass.id}")
                if (!gateway.relabelClass(packageName, dataClass)) {
                    Logger.e(TAG, "gateway.relabelClass returned false for ${dataClass.id}")
                    return ClassOutcome.ReplacedUnusable(
                        "${dataClass.id} was restored but its security labels could not be set"
                    )
                }
            }
            return ClassOutcome.Replaced
        } finally {
            staged.delete()
        }
    }

    /**
     * A RESTORING tick carrying byte counts — restore is the caller [ThorJobProgress] names as the
     * byte-carrying one.
     *
     * The counter only moves when a *whole class* lands, so until the first one does, nothing has been
     * measured. That is reported as unknown (`total = 0`, an indeterminate bar), never as a literal
     * 0 %: a bar pinned at zero through a multi-gigabyte decrypt is indistinguishable from a stalled
     * job, and "I know it is zero" and "I do not know yet" are different claims. Same tri-state
     * discipline `DataClassSize` and `ObbProbe` already carry on this branch.
     */
    private fun restoring(appLabel: String, doneBytes: Long, totalBytes: Long) = ThorJobProgress(
        stage = ThorJobStage.RESTORING,
        label = appLabel,
        completed = doneBytes,
        total = if (doneBytes > 0L) totalBytes else 0L,
    )

    /**
     * The only exit taken after the breadcrumb is written, and so the only one that can be reached
     * with data already replaced. Every `Failed` built outside this function is on a path that runs
     * before the first swap, which is why they all leave `classPossiblyCleared` at its null default.
     */
    private fun failWithBreadcrumbKept(
        reason: String,
        restored: List<DataClass>,
        classPossiblyCleared: DataClass? = null,
    ): ArchiveRestoreOutcome.Failed {
        // Deliberately no `breadcrumbs.clear()`. §8.5: a surviving breadcrumb is how the next launch
        // tells the user their data may be incomplete.
        return ArchiveRestoreOutcome.Failed(reason, restored.toList(), classPossiblyCleared)
    }

    /**
     * What staging the container's `.xapk` produced.
     *
     * Three outcomes rather than a nullable [File] because they mean three different things to the
     * user: a data-only archive has nothing to install or place and that is fine, while a header that
     * promises a bundle the container does not hold is an archive to distrust. One null for both said
     * "this archive's app bundle could not be read" about an archive that never claimed one.
     */
    private sealed interface BundleStaging {

        /**
         * There is no bundle to stage, and that is not a problem. Either the header declares none
         * (`header.appBundle == null` — a data-only backup) or one is declared and this run does not
         * need it: [invoke] only calls [extractBundle] when `installFirst || restoreObb`, and skips
         * straight to `None` otherwise rather than paying the app's whole download for nothing. The
         * distinction does not reach a reader — every consumer of the staged file is guarded by the
         * same two flags — but do not read `None` as "the archive holds no bundle".
         */
        data object None : BundleStaging

        data class Staged(val file: File) : BundleStaging

        /**
         * The header declares a bundle this run could not produce: the entry is missing from the
         * container, reading it threw, the entry expanded past [MAX_STAGED_BUNDLE_BYTES], or Thor's
         * cache could not hold the copy. [reason] says which.
         */
        data class Unreadable(val reason: String) : BundleStaging
    }

    private suspend fun extractBundle(source: ArchiveSource, header: ArchiveHeader): BundleStaging {
        val declared = header.appBundle ?: return BundleStaging.None
        // Every call that can throw is inside a `runCatching`, for the same reason the decrypt's catch
        // was widened: an `IOException` escaping this function leaves `invoke`, and the worker gets a
        // raw throw where the contract promises an `ArchiveRestoreOutcome`. `openEntry` throws on a
        // truncated container and `stagingFile` throws when the cache is full — both are the expected
        // failures here, not exotic ones.
        return runCatching {
            // The header's `appBundle` is a claim about the container; the container is the authority. A
            // header promising a bundle the zip does not hold stops here rather than handing the
            // installer an empty file. This is the one non-throwing way to fail, so it returns
            // non-locally with its own message instead of falling through to the generic one below.
            val entry = source.openEntry(declared.fileName)
                ?: return BundleStaging.Unreadable(
                    "this archive says it holds ${declared.fileName}, but that file is not in it"
                )
            val out = gateway.stagingFile(THORBAK_BUNDLE_ENTRY)
            // Bounded because this is a deflated entry out of a container the user did not author, so
            // its expanded size is the container's to choose — and it is the one member written before
            // any verifier or passphrase check (`invoke` stages the bundle first, and an install-first
            // restore never asks for a credential), so it is the only restore path where a caller who
            // proved nothing decides how many bytes Thor writes. The header's `declared.bytes` is not
            // the bound: it comes out of the same container.
            //
            // `MAX_STAGED_BUNDLE_BYTES` and not `MAX_EXTRACTED_TOTAL_BYTES`, which this first used on
            // the reasoning that `extractEntries` bounds the same content downstream. It does not
            // bound all of it: that budget covers the resolved install set, and the expansion files in
            // the same `.xapk` are unpacked against a separate, larger one precisely because a game's
            // OBB set legitimately reaches gigabytes. The APK-only figure therefore refused a
            // Thor-written XAPK backup of a large game, and refused it here — before the signer check,
            // before any class is restored, and on the install-first path there is no toggle to
            // decline the game data and get the rest.
            val copied = try {
                entry.use { input ->
                    out.outputStream().use { output ->
                        input.copyAtMostTo(output, MAX_STAGED_BUNDLE_BYTES)
                    }
                }
            } catch (e: Throwable) {
                // Delete here rather than in `getOrElse`, where `out` is out of scope: a half-copied
                // `.xapk` left in the cache is what the installer would pick up on the next attempt.
                discardPartial(out)
                throw e
            }
            if (copied == null) {
                // Same reason as the catch above — `copyAtMostTo` leaves the partial output for its
                // caller to discard, and here the installer is what would otherwise find it.
                discardPartial(out)
                return BundleStaging.Unreadable(
                    "this archive's app bundle is larger than " +
                        "${MAX_STAGED_BUNDLE_BYTES / (1024 * 1024 * 1024)} GB"
                )
            }
            BundleStaging.Staged(out)
        }.getOrElse {
            // `runCatching` catches `Throwable`, so a cancellation would otherwise come back as a
            // failed restore instead of a cancelled one. `copyAtMostTo` has no suspension point today;
            // the rethrow is here so that stays true the moment it is chunked or made suspending.
            if (it is CancellationException) throw it
            Logger.e(TAG, "archive bundle staging failed package=${header.packageName}")
            BundleStaging.Unreadable("this archive's app bundle could not be unpacked: ${it.message}")
        }
    }

    /**
     * Drop a partially-written bundle, and say so when the filesystem refuses.
     *
     * Not fatal, and deliberately not an error: `stagingFile` hands back the same path every time, so
     * the next restore truncates this file before it writes and the launch sweep reclaims it either
     * way — a survivor is wasted cache, never a bundle a later install could mistake for a whole one.
     * Silent, though, and a staging directory that only ever grows is what a bug report describes as
     * "Thor is using 3 GB".
     */
    private fun discardPartial(out: File) {
        if (!out.delete() && out.exists()) {
            Logger.w(
                TAG,
                "could not delete the partial archive bundle; the launch sweep will reclaim it",
            )
        }
    }

    private companion object {
        const val TAG = "RestoreAppArchive"
    }
}

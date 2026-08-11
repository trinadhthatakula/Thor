// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.ArchiveIntegrityException
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.ObbPlacement
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.AppArchiveInstaller
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
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
     * @param obb null when OBB was not part of this restore.
     */
    data class Completed(
        val classesRestored: List<DataClass>,
        val warnings: List<String>,
        val obb: ObbPlacement?,
    ) : ArchiveRestoreOutcome

    /**
     * @param classesRestored the classes that **did** land before the failure. "Restore failed" over a
     *   `CE` that is already replaced tells the user nothing they can act on.
     */
    data class Failed(
        val reason: String,
        val classesRestored: List<DataClass>,
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
     * @param restoreObb "place the archive's game data". On an archive that holds no bundle
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
        appLabel: String = header.packageName,
        onProgress: (ThorJobProgress) -> Unit = {},
    ): ArchiveRestoreOutcome {
        val pkg = header.packageName
        val restored = mutableListOf<DataClass>()
        val warnings = mutableListOf<String>()

        // The bundle is needed for an install and for OBB, and only then. Extracting it otherwise
        // would cost the app's whole download for nothing.
        val staging =
            if (installFirst || restoreObb) extractBundle(source, header) else BundleStaging.None
        val bundle = (staging as? BundleStaging.Staged)?.file
        try {
            // "There is no bundle" is several different situations and they are kept apart. Collapsing
            // them is how "restore game data, left on, over a data-only archive" became a whole failed
            // restore that wrote nothing and blamed the archive.
            if (staging is BundleStaging.Unreadable) {
                // The header declares one and the container does not deliver it. Both consumers need
                // those exact bytes, so this stops here — before anything is destroyed.
                return ArchiveRestoreOutcome.Failed(staging.reason, restored)
            }
            if (installFirst && bundle == null) {
                return ArchiveRestoreOutcome.Failed(
                    "this archive holds only data, so $appLabel cannot be installed from it", restored
                )
            }
            if (restoreObb && staging is BundleStaging.None) {
                // Nothing to place is not a failure to place. The data restore goes ahead; the user is
                // told why no game data appeared rather than being sent back to the checkbox.
                warnings += "this archive holds no game data, so none was placed"
            }

            if (installFirst) {
                onProgress(ThorJobProgress(ThorJobStage.INSTALLING, appLabel))
                when (val outcome = installer.installBundle(bundle!!, pkg)) {
                    ArchiveInstallOutcome.Installed -> Unit

                    // The package is installed and current; only its expansions are missing. Stopping
                    // here would leave the user with an installed, empty app and nothing to do about
                    // it — so the data restore goes ahead and the reason travels as a warning, because
                    // a game whose expansions are absent starts and then crashes.
                    is ArchiveInstallOutcome.InstalledWithoutGameData ->
                        warnings += "the game data could not be placed: ${outcome.reason}"

                    is ArchiveInstallOutcome.Failed ->
                        return ArchiveRestoreOutcome.Failed(outcome.reason, restored)

                    // Never folded into `Failed`. `session.commit()` is fire-and-forget, so an install
                    // Thor could not confirm may well have succeeded — and writing data into a package
                    // whose install is unconfirmed is how someone's data ends up inside a
                    // half-installed app.
                    ArchiveInstallOutcome.Unconfirmed -> return ArchiveRestoreOutcome.Failed(
                        "Thor could not confirm $appLabel finished installing, so it wrote no data",
                        restored,
                    )
                }
                // The gate could not check an absent app's signer (Task 11), so this is the only place
                // the check can happen for an install-first restore. Skipping it would be a hole
                // straight through the one refusal §8.1 allows no override for.
                //
                // It is also what closes the gap between `header.packageName` — which every path Thor
                // writes derives from — and whatever package the `.xapk` inside the container actually
                // declares. Thor does not parse the bundle's manifest; it does not have to. The install
                // path watches `header.packageName`'s own install stamp across the commit, so a bundle
                // that installs some *other* package cannot move that stamp and comes back
                // `Unconfirmed` above; and a bundle that installs this package under a different
                // signing key is refused here. Both exits happen before a byte of data is written.
                // Null is "the question could not be answered", never "it matches" — the same refusal
                // `installLanded` makes on an unreadable install stamp.
                val signer = gateway.signerSha256(pkg)
                if (signer == null || !signer.equals(header.signerSha256, ignoreCase = true)) {
                    return ArchiveRestoreOutcome.Failed(
                        "the app that installed is not signed by the key this archive was made from",
                        restored,
                    )
                }
            }

            // After the install, never from the archive: a reinstalled app has a new uid (§8.2).
            val uid = gateway.appUid(pkg)
                ?: return ArchiveRestoreOutcome.Failed(
                    "Thor could not read $appLabel's user id, so it wrote no data", restored
                )

            gateway.forceStop(pkg)
            if (!breadcrumbs.write(pkg, appLabel)) {
                // The notice is the only thing that would tell the user a kill mid-swap left this
                // app's data half-replaced. It cannot be made to work from here, but proceeding
                // without saying so is the silence §8.5 exists to prevent.
                Logger.e(TAG, "the restore breadcrumb could not be written; proceeding without it")
                warnings += "Thor could not record that this restore started, " +
                    "so it will not be able to report it if the restore is interrupted"
            }

            val totalBytes = classes.sumOf { header.member(it)?.plainBytes ?: 0L }
            var doneBytes = 0L

            for (dataClass in classes) {
                val member = header.member(dataClass)
                    ?: return failWithBreadcrumbKept(
                        "this archive has no ${dataClass.id} data", restored
                    )
                onProgress(restoring(appLabel, doneBytes, totalBytes))

                val failure = restoreClass(source, dataClass, member, key, pkg, uid)
                if (failure != null) return failWithBreadcrumbKept(failure, restored)

                restored += dataClass
                doneBytes += member.plainBytes
            }

            // `bundle`, not `header.appBundle`: a data-only archive has nothing staged and nothing to
            // place, and the warning for that was already recorded above.
            if (restoreObb && !installFirst && bundle != null) {
                onProgress(restoring(appLabel, doneBytes, totalBytes))
                // A failed placement is a warning: the data landed, and telling the user the restore
                // failed sends them to run it again, which destroys and rewrites data that is correct.
                val placement = installer.placeBundleObb(bundle, pkg)
                if (placement is ObbPlacement.Failed) {
                    warnings += "the game data could not be placed: ${placement.reason}"
                }
                // Its own return path, so §8.3 steps 5 and 6 have to be repeated on it.
                gateway.forceStop(pkg)
                breadcrumbs.clear()
                // The real placement, not a hard-coded `NotNeeded`: "2 game data files placed" is what
                // the user is shown, and `NotNeeded` would report that nothing happened.
                return ArchiveRestoreOutcome.Completed(restored, warnings, placement)
            }

            onProgress(ThorJobProgress(ThorJobStage.FINISHING, appLabel, totalBytes, totalBytes))
            gateway.forceStop(pkg)
            breadcrumbs.clear()
            return ArchiveRestoreOutcome.Completed(restored, warnings, obb = null)
        } finally {
            // The bundle is the app's whole download, staged in Thor's *internal* cache. Leaking one
            // copy per failed or cancelled restore is the same defect `restoreClass`'s `finally`
            // prevents one level down. This `try` has real suspension points in it — the install and
            // every gateway call — so unlike a `try` around straight-line code, this `finally` is
            // genuinely live on the cancellation path.
            bundle?.delete()
        }
    }

    /**
     * One class, in §8.3's order. Returns null on success, or the reason it failed.
     *
     * The whole member is decrypted **before** [AppDataArchiveGateway.extractInto] runs, and the swap
     * comes after that. A corrupt archive therefore fails with the original data still in place —
     * which is the difference between "that archive is bad" and "your data is gone".
     */
    private suspend fun restoreClass(
        source: ArchiveSource,
        dataClass: DataClass,
        member: ArchiveMember,
        key: SecretKey,
        packageName: String,
        uid: Int,
    ): String? {
        val staged = gateway.stagingFile("restore-${dataClass.id}.tar")
        try {
            val ciphertext = try {
                source.openEntry(member.fileName)
                    ?: return "this archive is missing ${member.fileName}"
            } catch (e: IOException) {
                // A truncated or damaged container throws here (`ZipException` is an `IOException`)
                // rather than returning null, and a throw out of `invoke` costs the caller
                // `classesRestored`.
                Logger.e(TAG, "could not open ${member.fileName}", e)
                return "this archive could not be read: ${e.message}"
            }
            val nonce = runCatching { Base64.getDecoder().decode(member.nonce) }.getOrNull()
                ?: return "this archive's ${dataClass.id} member has an unreadable nonce"

            try {
                ciphertext.use { input ->
                    staged.outputStream().use { output ->
                        cipher.decryptMember(member.fileName, input, output, key, nonce, member.chunkCount)
                    }
                }
            } catch (e: ArchiveIntegrityException) {
                Logger.e(TAG, "${member.fileName} failed integrity", e)
                return "this archive's ${dataClass.id} data is damaged and was not restored"
            } catch (e: IOException) {
                // Not an integrity failure and not exotic: running out of room in Thor's internal
                // cache is *the* expected failure for a multi-gigabyte restore, and neither
                // `staged.outputStream()` nor `plaintext.write(chunk)` raises anything an
                // `ArchiveIntegrityException` catch would see. Letting it escape `invoke` would throw
                // away `classesRestored` with it — and "CE is already replaced" is exactly what
                // separates an actionable message from a useless one.
                Logger.e(TAG, "${member.fileName} could not be staged", e)
                return "${dataClass.id} could not be written to Thor's cache: ${e.message}"
            }

            // The decrypt above is this use case's one long stretch with no suspension point in it, and
            // nothing after it observes cancellation on its own: the gateway's `withContext(io)` resumes
            // *undispatched* when the caller is already on that dispatcher, so it never reaches a
            // cancellation check. Without this line a restore the user cancelled during the decrypt goes
            // on to replace the class root anyway. Placed before `extractInto` because that call is
            // itself destructive — it opens with `rm -rf` on the staging directory.
            currentCoroutineContext().ensureActive()

            val compressed = ArchiveCompression.fromId(member.compression) == ArchiveCompression.GZIP
            if (!gateway.extractInto(packageName, dataClass, staged, compressed)) {
                return "${dataClass.id} could not be unpacked"
            }
            // Past this line the original is gone.
            if (!gateway.swapStaged(packageName, dataClass)) {
                return "${dataClass.id} could not be put into place"
            }
            if (dataClass.isInternal) {
                if (!gateway.chownClass(packageName, dataClass, uid)) {
                    return "${dataClass.id} was restored but its ownership could not be set"
                }
                if (!gateway.relabelClass(packageName, dataClass)) {
                    return "${dataClass.id} was restored but its security labels could not be set"
                }
            }
            return null
        } finally {
            // Inside the loop, so peak disk is one class. Folding this up into `invoke` is the one
            // edit that breaks that and nothing else.
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

    private fun failWithBreadcrumbKept(
        reason: String,
        restored: List<DataClass>,
    ): ArchiveRestoreOutcome.Failed {
        // Deliberately no `breadcrumbs.clear()`. §8.5: a surviving breadcrumb is how the next launch
        // tells the user their data may be incomplete.
        return ArchiveRestoreOutcome.Failed(reason, restored.toList())
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
         * container, reading it threw, or Thor's cache could not hold the copy. [reason] says which.
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
            try {
                entry.use { input -> out.outputStream().use(input::copyTo) }
            } catch (e: Throwable) {
                // Delete here rather than in `getOrElse`, where `out` is out of scope: a half-copied
                // `.xapk` left in the cache is what the installer would pick up on the next attempt.
                out.delete()
                throw e
            }
            BundleStaging.Staged(out)
        }.getOrElse {
            // `runCatching` catches `Throwable`, so a cancellation would otherwise come back as a
            // failed restore instead of a cancelled one. `copyTo` has no suspension point today; the
            // rethrow is here so that stays true the moment it is chunked or made suspending.
            if (it is CancellationException) throw it
            Logger.e(TAG, "could not stage the app bundle", it)
            BundleStaging.Unreadable("this archive's app bundle could not be unpacked: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "RestoreAppArchive"
    }
}

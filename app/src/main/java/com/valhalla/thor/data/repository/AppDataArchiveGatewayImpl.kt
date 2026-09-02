// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.AppDataArchiveStagingDir
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.applyEntryVerification
import com.valhalla.thor.domain.model.chownFileCommand
import com.valhalla.thor.domain.model.chownRecursiveCommand
import com.valhalla.thor.domain.model.classifyTarExit
import com.valhalla.thor.domain.model.dataClassRoot
import com.valhalla.thor.domain.model.extractCommand
import com.valhalla.thor.domain.model.filterBackupEntries
import com.valhalla.thor.domain.model.listClassEntriesCommand
import com.valhalla.thor.domain.model.restoreconCommand
import com.valhalla.thor.domain.model.swapStagedEntriesCommand
import com.valhalla.thor.domain.model.tarCreateCommand
import com.valhalla.thor.domain.model.verifyEntriesCommand
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "AppDataArchiveGateway"

internal enum class ArchiveCommandPhase(val commandClass: PrivilegeCommandClass) {
    FORCE_STOP(PrivilegeCommandClass("archive.force_stop")),
    LIST(PrivilegeCommandClass("archive.list")),
    VERIFY(PrivilegeCommandClass("archive.verify")),
    TAR(PrivilegeCommandClass("archive.tar")),
    STAGE_CHOWN(PrivilegeCommandClass("archive.stage_chown")),
    EXTRACT(PrivilegeCommandClass("archive.extract")),
    SWAP(PrivilegeCommandClass("archive.swap")),
    CHOWN(PrivilegeCommandClass("archive.chown")),
    RESTORECON(PrivilegeCommandClass("archive.restorecon")),
}

internal fun archiveExecutionContext(
    packageName: String,
    phase: ArchiveCommandPhase,
): PrivilegeExecutionContext = PrivilegeExecutionContext(
    lane = PrivilegeExecutionLane.ARCHIVE,
    commandClass = phase.commandClass,
    packageName = packageName,
)

internal suspend fun SystemRepository.executeArchiveCommand(
    command: String,
    packageName: String,
    phase: ArchiveCommandPhase,
): Result<Pair<Int, String?>> {
    val execution = archiveExecutionContext(packageName, phase)
    Logger.d(
        TAG,
        "archive command started class=${execution.commandClass.value} package=$packageName",
    )
    return executeShellCommand(command, execution).also { result ->
        val exitCode = result.getOrNull()?.first
        if (result.isFailure || exitCode != 0) {
            Logger.e(
                TAG,
                "archive command failed class=${execution.commandClass.value} " +
                    "package=$packageName exitCode=$exitCode",
            )
        }
    }
}

internal suspend fun SystemRepository.forceStopForArchive(packageName: String): Result<Unit> {
    val execution = archiveExecutionContext(packageName, ArchiveCommandPhase.FORCE_STOP)
    Logger.d(
        TAG,
        "archive command started class=${execution.commandClass.value} package=$packageName",
    )
    return forceStopApp(packageName, execution).also { result ->
        if (result.isFailure) {
            Logger.e(
                TAG,
                "archive command failed class=${execution.commandClass.value} package=$packageName",
            )
        }
    }
}

private fun Throwable.rethrowIfStructuredExecutionFailure() {
    if (this is CancellationException || this is PrivilegeExecutionException) throw this
}

/**
 * True when [name] is safe as a per-class staging filename.
 *
 * Only the ENTIRE name being `.` or `..` is rejected (path-traversal components). A name that merely
 * contains a dot — `ce.tar`, `ce.tar.gz.enc`, `ext-data.tar.enc` — is accepted. No `/` is allowed
 * because that would escape the staging directory via `File(dir, name)`.
 *
 * Extracted from [AppDataArchiveGatewayImpl.stagingFile]'s `require` so the predicate can be pinned
 * by a test without instantiating the Android class.
 */
internal fun isSafeStagingName(name: String): Boolean =
    name.isNotBlank() && !name.contains('/') && name != ".." && name != "."

/**
 * Returns true when [outCanonical] is a file inside [stagingCanonical].
 *
 * The trailing-slash separator in `"$stagingCanonical/"` defeats the sibling-prefix attack:
 * a path like `data_archive_staging-evil/ce.tar.enc` starts with `data_archive_staging` but not
 * `data_archive_staging/`, so it fails. Both arguments must already be canonical (no `..` or
 * symlinks); this function does not call `File.canonicalPath` itself.
 *
 * Extracted from [AppDataArchiveGatewayImpl.tarClass]'s containment check so the security property
 * can be pinned by a test without any Android dependencies.
 */
internal fun isInsideStagingRoot(outCanonical: String, stagingCanonical: String): Boolean =
    outCanonical.startsWith("$stagingCanonical/")

/**
 * The volume a staged tar lands on: `cacheDir` when the privileged runner can read Thor's private data
 * directory, `externalCacheDir` when it cannot (§7.1). [AppDataArchiveGatewayImpl.stagingRoot]'s KDoc
 * owns the reasoning and the cost.
 *
 * A shared function rather than a line inside `stagingRoot`, because the choice has a second reader:
 * `ArchiveBackupWorker` measures §7.4's free space, and measuring `cacheDir` while the tar is written
 * to external storage reports a different volume's headroom. Two copies of one routing rule is exactly
 * how that happened — the worker's copy said the external route did not exist — so there is one.
 *
 * Null only if the platform returns neither directory; both callers read that as "unmeasurable".
 */
internal fun archiveStagingVolume(context: Context, privateDataCapable: Boolean): File? =
    if (privateDataCapable) context.cacheDir else context.externalCacheDir ?: context.cacheDir

@Single(binds = [AppDataArchiveGateway::class])
internal class AppDataArchiveGatewayImpl(
    private val context: Context,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
    private val probe: AppDataProbe,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AppDataArchiveGateway {

    override suspend fun thorUserId(): Int = thorUserId

    override suspend fun externalStorageDir(): String = withContext(ioDispatcher) {
        Environment.getExternalStorageDirectory()?.absolutePath ?: ""
    }

    /**
     * Resolves and creates the single staging directory. All [stagingFile] calls land here, so the
     * canonical path used in [tarClass]'s traversal guard is always the same base.
     *
     * §7.1's `externalCacheDir` route, and the condition on it is a *capability*, not availability.
     * `cacheDir` is `/data/data/<thor>` at mode 0700, which the privileged runner has to be able to
     * read for a staged tar to be produced at all: a root shell can, a Shizuku shell at uid 2000
     * cannot. [AppDataProbe.probePrivateDataCapability] is that question, and answering it `false`
     * is what moves staging to `externalCacheDir` — the same reason `AppBundleBuilderImpl` stages
     * expansion files there.
     *
     * Say the cost plainly, because this KDoc used to claim the downgrade did not exist: a staged tar
     * is plaintext app data, and on external storage it is readable by anything holding
     * `READ_EXTERNAL_STORAGE` on the device configurations where that still grants broad access. The
     * exposure lasts as long as the file does, which is why the staged tar is deleted on every exit
     * from [tarClass] under `NonCancellable` and why [ArchiveOrphanSweeper] sweeps this directory
     * under *both* roots — a sweep of `cacheDir` alone would leave exactly the copies this branch
     * writes. Pair any further staging root with a sweeper rule in the same change.
     */
    private suspend fun stagingRoot(): File = withContext(ioDispatcher) {
        val rootDir = archiveStagingVolume(context, probe.probePrivateDataCapability())
        File(rootDir, AppDataArchiveStagingDir.NAME).also { it.mkdirs() }
    }

    override suspend fun stagingFile(name: String): File = withContext(ioDispatcher) {
        // Plain filename only — no path separator, no `.` or `..` component. See [isSafeStagingName]
        // for the exact predicate and why ordinary dotted names (`ce.tar`, `ce.tar.gz.enc`) pass.
        require(isSafeStagingName(name)) {
            "stagingFile requires a plain filename with no path components, got: $name"
        }
        File(stagingRoot(), name)
    }

    override suspend fun privateStagingFile(name: String): File = withContext(ioDispatcher) {
        require(isSafeStagingName(name)) {
            "privateStagingFile requires a plain filename with no path components, got: $name"
        }
        val root = File(context.cacheDir, AppDataArchiveStagingDir.NAME).also { it.mkdirs() }
        File(root, name)
    }

    override suspend fun forceStop(packageName: String) {
        // Delegated to `forceStopApp` rather than hand-rolled. Hand-rolling has three defects:
        //   1. No package-name validation — the raw string reaches a root shell directly.
        //   2. No `--user` — a bare `am force-stop` kills the package in every profile.
        //   3. The exit code from the privileged runner is not meaningful here anyway.
        // `forceStopApp` already routes through the active gateway and includes user scoping.
        // A failure is logged, not fatal: an app that was not running produces one, and refusing the
        // backup over it would refuse most backups.
        systemRepository.forceStopForArchive(packageName)
            .exceptionOrNull()
            ?.rethrowIfStructuredExecutionFailure()
    }

    /**
     * The one place a class root is resolved.
     *
     * Every command builder below refuses a root it will not quote, so this is not the validation —
     * it is the single spelling of the resolution, so `listClass`, `tarClass` and the four restore
     * calls cannot drift apart in which user id or external root they ask about.
     */
    private suspend fun classRootOf(packageName: String, dataClass: DataClass): String? =
        dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())

    override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries {
        val root = classRootOf(packageName, dataClass)
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        val command = listClassEntriesCommand(root)
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        val (exitCode, output) = systemRepository.executeArchiveCommand(
            command = command,
            packageName = packageName,
            phase = ArchiveCommandPhase.LIST,
        ).getOrElse { failure ->
            failure.rethrowIfStructuredExecutionFailure()
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        if (exitCode != 0 || output == null) {
            // Reported as absent rather than empty-and-fine: the caller writes no member either way,
            // but `rootAbsent` is what earns a warning in the header. This is also where an absent root
            // is *detected* — `ls` on a directory that is not there exits non-zero — which is why the
            // listing command no longer prints a marker Thor would have to trust an app not to forge.
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        val listing = filterBackupEntries(dataClass, output)
        return verifyListing(packageName, root, dataClass, listing)
    }

    /**
     * Second round trip: drop any entry the listing named that is not actually there.
     *
     * `ls -A`'s reply is line-split, so a filename containing a line break arrives as two names that
     * both pass every filter and neither of which exists. Handing those to `tar` loses the real file
     * *and* makes tar exit 1, which the outcome classifier then reports as "files that changed while
     * being read" — a cause that is not the cause. This turns both halves into visible
     * `ArchiveSkip` rows and keeps them out of the tar command.
     *
     * Fails open at every exit: no command, a gateway throw, or a non-zero exit all return the listing
     * untouched. A backup must not be emptied by a refinement step.
     */
    private suspend fun verifyListing(
        packageName: String,
        root: String,
        dataClass: DataClass,
        listing: ClassEntries,
    ): ClassEntries {
        val command = verifyEntriesCommand(root, listing.kept) ?: return listing
        val (exitCode, output) = systemRepository.executeArchiveCommand(
            command = command,
            packageName = packageName,
            phase = ArchiveCommandPhase.VERIFY,
        ).getOrElse { failure ->
            failure.rethrowIfStructuredExecutionFailure()
            return listing
        }
        return applyEntryVerification(dataClass, listing, exitCode, output)
    }

    override suspend fun tarClass(
        packageName: String,
        dataClass: DataClass,
        entries: List<String>,
        out: File,
        compress: Boolean,
    ): TarOutcome {
        // Guard against path traversal before `out.absolutePath` reaches a root shell. `absolutePath`
        // does not normalise, so `..` components survive `isQuotableAbsolutePath`. `canonicalPath`
        // resolves `..` and symlinks, so the comparison is made on the real on-disk path.
        // Latent now (every caller passes a name Thor chose); not latent once restore parses member
        // names from an untrusted archive header and reaches this surface.
        //
        // Both sides are wrapped in `runCatching` so an IOException from either canonicalisation
        // refuses cleanly rather than propagating.
        val stagingCanonical = withContext(ioDispatcher) {
            runCatching { stagingRoot().canonicalPath }.getOrNull()
        } ?: return TarOutcome.Failed("could not resolve canonical path for staging root")
        val outCanonical = withContext(ioDispatcher) {
            runCatching { out.canonicalPath }.getOrNull()
        } ?: return TarOutcome.Failed("could not resolve canonical path for ${out.name}")
        if (!isInsideStagingRoot(outCanonical, stagingCanonical)) {
            return TarOutcome.Failed("${out.name} is not inside the staging directory")
        }

        val root = classRootOf(packageName, dataClass)
            ?: return TarOutcome.Failed("no usable path for ${dataClass.id}")
        val command = tarCreateCommand(root, out.absolutePath, entries, compress)
            ?: return TarOutcome.Failed("refused to build a tar command for ${dataClass.id}")

        // `committed` is set only when we are returning a usable archive. Any other exit —
        // explicit failure returns and CancellationException alike — triggers cleanup in the
        // `finally` block so plaintext app data never outlives the operation that created it.
        var committed = false
        try {
            val (exitCode, _) = systemRepository.executeArchiveCommand(
                command = command,
                packageName = packageName,
                phase = ArchiveCommandPhase.TAR,
            ).getOrElse { failure ->
                // Tar did not complete cleanly — the gateway threw. `out` may have been partially written,
                // so delete it to avoid leaving plaintext app data on disk.
                withContext(ioDispatcher) { out.delete() }
                failure.rethrowIfStructuredExecutionFailure()
                return TarOutcome.Failed("tar could not be run")
            }

            // Read the length *after* tar exits, and on the IO dispatcher — this is a stat call.
            val staged = withContext(ioDispatcher) { if (out.isFile) out.length() else 0L }
            val outcome = classifyTarExit(exitCode, staged)
            if (outcome is TarOutcome.Failed) {
                // A partial tar must never survive to be encrypted: it would restore a truncated tree
                // over the app's real data.
                withContext(ioDispatcher) { out.delete() }
                return outcome
            }

            // The shell created the file as its own uid. Attempt chown to Thor's uid if possible.
            val chown = chownFileCommand(out.absolutePath, android.os.Process.myUid())
            if (chown != null) {
                systemRepository.executeArchiveCommand(
                    command = chown,
                    packageName = packageName,
                    phase = ArchiveCommandPhase.STAGE_CHOWN,
                ).exceptionOrNull()
                    ?.rethrowIfStructuredExecutionFailure()
            }

            return if (withContext(ioDispatcher) { out.canRead() }) {
                committed = true
                outcome
            } else {
                withContext(ioDispatcher) { out.delete() }
                TarOutcome.Failed("the staged archive for ${dataClass.id} could not be read back")
            }
        } finally {
            // Handles the cancellation path: if the coroutine is cancelled while `executeShellCommand`
            // is in flight, `out` may already exist with plaintext app data and none of the explicit
            // delete calls above will have run. `NonCancellable` ensures the deletion completes even
            // while cancellation is in progress. The `!committed` guard prevents deleting a file that
            // was successfully produced and is about to be returned to the caller.
            //
            // `NonCancellable + ioDispatcher`, not `NonCancellable` alone: a bare `NonCancellable`
            // replaces the Job and *keeps the caller's dispatcher*, which for this function is whatever
            // the caller was on — every other filesystem call in here names [ioDispatcher] explicitly
            // because the function body does not run on it. Composing the two keeps the "cannot be
            // cancelled" property and puts the blocking delete where the sibling deletes already go.
            if (!committed) {
                withContext(NonCancellable + ioDispatcher) { out.delete() }
            }
        }
    }

    override suspend fun appUid(packageName: String): Int? = withContext(ioDispatcher) {
        runCatching { packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
    }

    override suspend fun signerSha256(packageName: String): String? = withContext(ioDispatcher) {
        runCatching {
            // minSdk 28 = Android P, so GET_SIGNING_CERTIFICATES is always available.
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            // The *current* signer only. `signingCertificateHistory` would let a key rotation match
            // an archive taken before the rotation, which is the one thing this check is for.
            // `apkContentsSigners` is the current set.
            val first = info.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: return@runCatching null
            MessageDigest.getInstance("SHA-256")
                .digest(first.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }.getOrElse {
            Logger.e(TAG, "reading the signer of $packageName failed", it)
            null
        }
    }

    override suspend fun extractInto(
        packageName: String,
        dataClass: DataClass,
        tar: File,
        compressed: Boolean,
    ): Boolean = runClassCommand(packageName, dataClass, ArchiveCommandPhase.EXTRACT) { root ->
        extractCommand(root, tar.absolutePath, compressed)
    }

    override suspend fun swapStaged(packageName: String, dataClass: DataClass): Boolean =
        runClassCommand(packageName, dataClass, ArchiveCommandPhase.SWAP) { root ->
            swapStagedEntriesCommand(root)
        }

    override suspend fun chownClass(packageName: String, dataClass: DataClass, uid: Int): Boolean =
        runClassCommand(packageName, dataClass, ArchiveCommandPhase.CHOWN) { root ->
            chownRecursiveCommand(root, uid)
        }

    override suspend fun relabelClass(packageName: String, dataClass: DataClass): Boolean =
        runClassCommand(packageName, dataClass, ArchiveCommandPhase.RESTORECON) { root ->
            restoreconCommand(root)
        }

    /**
     * Resolve the class root, build the command, run it, and report whether it exited 0.
     *
     * `exitCode == 0`, not `!= -1`: `RootSystemGateway.execute()` folds a *throw* into
     * `-1 to stackTraceToString()`, so any rule phrased as "not the failure code" reads Thor's own
     * stack trace as a success.
     */
    private suspend fun runClassCommand(
        packageName: String,
        dataClass: DataClass,
        phase: ArchiveCommandPhase,
        build: (String) -> String?,
    ): Boolean = withContext(ioDispatcher) {
        val root = classRootOf(packageName, dataClass) ?: run {
            Logger.e(
                TAG,
                "archive command refused class=${phase.commandClass.value} package=$packageName " +
                    "dataClass=${dataClass.id} reason=unresolved_root",
            )
            return@withContext false
        }
        val command = build(root) ?: run {
            Logger.e(
                TAG,
                "archive command refused class=${phase.commandClass.value} package=$packageName " +
                    "dataClass=${dataClass.id} reason=unsafe_input",
            )
            return@withContext false
        }
        val result = systemRepository.executeArchiveCommand(command, packageName, phase)
            .getOrElse { failure ->
                failure.rethrowIfStructuredExecutionFailure()
                return@withContext false
            }
        result.first == 0
    }
}

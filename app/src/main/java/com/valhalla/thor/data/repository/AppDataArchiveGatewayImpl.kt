// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.ClassEntries
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.TarOutcome
import com.valhalla.thor.domain.model.chownFileCommand
import com.valhalla.thor.domain.model.classifyTarExit
import com.valhalla.thor.domain.model.dataClassRoot
import com.valhalla.thor.domain.model.filterBackupEntries
import com.valhalla.thor.domain.model.listClassEntriesCommand
import com.valhalla.thor.domain.model.tarCreateCommand
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.util.Logger
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val TAG = "AppDataArchiveGateway"

/** Where the shell writes the per-class tars. One directory so a crashed job's leftovers are findable. */
private const val STAGING_DIR = "data_archive_staging"

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

@Single(binds = [AppDataArchiveGateway::class])
internal class AppDataArchiveGatewayImpl(
    private val context: Context,
    private val packageManager: PackageManager,
    private val systemRepository: SystemRepository,
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
     * §7.1 provides for an `externalCacheDir` fallback when internal cache is unavailable. This
     * implementation does not provide one (§14 limitation). A staged tar is plaintext app data;
     * external cache is on the shared external storage volume and is world-readable on some device
     * configurations. Writing plaintext app data there would defeat the exact property the rest of
     * this feature is built around. If `cacheDir` is unavailable the job will fail loudly rather than
     * silently downgrade to a less secure location.
     */
    private suspend fun stagingRoot(): File = withContext(ioDispatcher) {
        File(context.cacheDir, STAGING_DIR).also { it.mkdirs() }
    }

    override suspend fun stagingFile(name: String): File = withContext(ioDispatcher) {
        // Plain filename only — no path separator, no `.` or `..` component. See [isSafeStagingName]
        // for the exact predicate and why ordinary dotted names (`ce.tar`, `ce.tar.gz.enc`) pass.
        require(isSafeStagingName(name)) {
            "stagingFile requires a plain filename with no path components, got: $name"
        }
        File(stagingRoot(), name)
    }

    override suspend fun forceStop(packageName: String) {
        // Delegated to `forceStopApp` rather than hand-rolled. Hand-rolling has three defects:
        //   1. No package-name validation — the raw string reaches a root shell directly.
        //   2. No `--user` — a bare `am force-stop` kills the package in every profile.
        //   3. The exit code from the privileged runner is not meaningful here anyway.
        // `forceStopApp` already routes through the active gateway and includes user scoping.
        // A failure is logged, not fatal: an app that was not running produces one, and refusing the
        // backup over it would refuse most backups.
        systemRepository.forceStopApp(packageName)
            .onFailure { Logger.e(TAG, "force-stop of $packageName failed", it) }
    }

    override suspend fun listClass(packageName: String, dataClass: DataClass): ClassEntries {
        val root = dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        val command = listClassEntriesCommand(root)
            ?: return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)

        val (exitCode, output) = systemRepository.executeShellCommand(command).getOrElse {
            Logger.e(TAG, "listing ${dataClass.id} for $packageName failed", it)
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        if (exitCode != 0 || output == null) {
            // Reported as absent rather than empty-and-fine: the caller writes no member either way,
            // but `rootAbsent` is what earns a warning in the header.
            return ClassEntries(kept = emptyList(), skipped = emptyList(), rootAbsent = true)
        }
        return filterBackupEntries(dataClass, output)
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
        val stagingCanonical = withContext(ioDispatcher) { stagingRoot().canonicalPath }
        val outCanonical = withContext(ioDispatcher) {
            runCatching { out.canonicalPath }.getOrNull()
        } ?: return TarOutcome.Failed("could not resolve canonical path for ${out.name}")
        if (!outCanonical.startsWith("$stagingCanonical/")) {
            return TarOutcome.Failed("${out.name} is not inside the staging directory")
        }

        val root = dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())
            ?: return TarOutcome.Failed("no usable path for ${dataClass.id}")
        val command = tarCreateCommand(root, out.absolutePath, entries, compress)
            ?: return TarOutcome.Failed("refused to build a tar command for ${dataClass.id}")

        val (exitCode, _) = systemRepository.executeShellCommand(command).getOrElse {
            // Tar did not complete cleanly — the gateway threw. `out` may have been partially written,
            // so delete it to avoid leaving plaintext app data on disk.
            withContext(ioDispatcher) { out.delete() }
            return TarOutcome.Failed("tar could not be run: ${it.message}")
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

        // The shell created the file as its own uid, so Thor cannot open it yet. 600, because the
        // contents are plaintext app data.
        val chown = chownFileCommand(out.absolutePath, android.os.Process.myUid())
        if (chown == null) {
            // The chown builder refused. `out` exists at this point with plaintext data — delete it
            // before returning, or this becomes the one exit path that silently leaves a staged tar.
            withContext(ioDispatcher) { out.delete() }
            return TarOutcome.Failed("refused to build a chown command for ${out.name}")
        }

        // Read the exit code rather than discarding it. `canRead()` alone is DAC-only and blind to
        // SELinux policy; a non-zero exit is the more direct signal that the ownership transfer failed.
        // Both signals are checked: a zero exit but an unreadable file is also a failure.
        val (chownExit, _) = systemRepository.executeShellCommand(chown).getOrElse {
            Logger.e(TAG, "chown of ${out.name} failed", it)
            withContext(ioDispatcher) { out.delete() }
            return TarOutcome.Failed("chown for ${dataClass.id} failed: ${it.message}")
        }
        if (chownExit != 0) {
            withContext(ioDispatcher) { out.delete() }
            return TarOutcome.Failed("chown exited $chownExit for ${dataClass.id}")
        }
        return if (withContext(ioDispatcher) { out.canRead() }) {
            outcome
        } else {
            withContext(ioDispatcher) { out.delete() }
            TarOutcome.Failed("the staged archive for ${dataClass.id} could not be read back")
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
                .joinToString(separator = "") { byte -> "%02X".format(byte) }
        }.getOrElse {
            Logger.e(TAG, "reading the signer of $packageName failed", it)
            null
        }
    }
}

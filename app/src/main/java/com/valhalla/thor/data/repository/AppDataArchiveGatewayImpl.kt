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

    override suspend fun stagingFile(name: String): File = withContext(ioDispatcher) {
        val dir = File(context.cacheDir, STAGING_DIR)
        dir.mkdirs()
        File(dir, name)
    }

    override suspend fun forceStop(packageName: String) {
        // Routed through the same executeShellCommand every other command uses, so it follows the
        // active gateway rather than assuming root. A failure is logged, not fatal: an app that was
        // not running produces one, and refusing the backup over it would refuse most backups.
        systemRepository.executeShellCommand("am force-stop '$packageName'")
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
        val root = dataClassRoot(dataClass, packageName, thorUserId(), externalStorageDir())
            ?: return TarOutcome.Failed("no usable path for ${dataClass.id}")
        val command = tarCreateCommand(root, out.absolutePath, entries, compress)
            ?: return TarOutcome.Failed("refused to build a tar command for ${dataClass.id}")

        val (exitCode, _) = systemRepository.executeShellCommand(command).getOrElse {
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
            ?: return TarOutcome.Failed("refused to build a chown command for ${out.name}")
        systemRepository.executeShellCommand(chown).onFailure {
            Logger.e(TAG, "chown of ${out.name} failed", it)
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

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.content.Context
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Only an atomic move of a completed bundle can create a ready leaf. Work is never shareable. */
@Single(binds = [SharePrepareTaskOperations::class])
internal class DefaultSharePrepareTaskOperations(
    context: Context,
    private val launchSweep: LaunchSweepBarrier,
    private val apps: AppRepository,
    private val builder: AppBundleBuilder,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : SharePrepareTaskOperations {
    private val cache = context.cacheDir

    override suspend fun awaitLaunchSweep(): Boolean = launchSweep.awaitSwept()

    override suspend fun loadApp(packageName: String): AppInfo? = apps.getAppDetails(packageName)
        ?.takeIf { it.packageName == packageName && it.isInstalled }

    override suspend fun discardIncomplete(stagingSubDir: String, packageName: String): Boolean =
        withContext(ioDispatcher) {
            deleteOwnedTree(ownedDirectory(workScope(stagingSubDir), packageName))
        }

    override suspend fun buildBundle(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String,
        execution: PrivilegeExecutionContext,
        progress: VerifiedProgress,
        operationBoundary: VerifiedOperationBoundary,
    ): Result<File> = withContext(ioDispatcher) {
        val workScope = workScope(cacheSubDir)
        val work = ownedDirectory(workScope, appInfo.packageName)
        val ready = ownedDirectory(cacheSubDir, appInfo.packageName)
        require(fileName.matches(Regex("[A-Za-z0-9._-]+")) && fileName != "." && fileName != "..")
        val finalFile = File(ready, fileName)
        var publishedHere = false
        var completed = false
        try {
            // A killed process may have published the final leaf before Room received its result.
            // No attempt ever writes directly to this path, so a nonempty regular leaf is complete.
            if (Files.isRegularFile(finalFile.toPath(), NOFOLLOW_LINKS) && finalFile.length() > 0) {
                operationBoundary.onOperationCompleted()
                return@withContext Result.success(finalFile)
            }
            val built = builder.buildWithProgress(
                appInfo, workScope, format, fileName, execution, progress, operationBoundary,
            ).getOrThrow()
            val expected = File(work, fileName)
            if (built.canonicalFile != expected.canonicalFile ||
                !Files.isRegularFile(expected.toPath(), NOFOLLOW_LINKS) || expected.length() <= 0) {
                throw IOException("Share bundle incomplete")
            }
            currentCoroutineContext().ensureActive()
            if (!deleteOwnedTree(ready) || (!ready.mkdirs() && !ready.isDirectory)) {
                throw IOException("Share publication unavailable")
            }
            // Same private cache volume; no copy fallback which could leave a half-ready leaf.
            Files.move(expected.toPath(), finalFile.toPath(), ATOMIC_MOVE)
            publishedHere = true
            operationBoundary.onOperationCompleted()
            currentCoroutineContext().ensureActive()
            completed = true
            Result.success(finalFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not put file paths or exception messages into the durable runner's warning payload.
            Result.failure(IOException("Share bundle preparation failed"))
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                deleteOwnedTree(work)
                if (publishedHere && !completed) deleteOwnedTree(finalFile)
            }
        }
    }

    private fun workScope(readyScope: String): String {
        require(readyScope.matches(Regex("share_ready/item-[0-9a-f-]{36}-[0-9]+")))
        return readyScope.replaceFirst("share_ready/", "share_work/")
    }

    private fun ownedDirectory(scope: String, packageName: String): File {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")))
        val root = cache.canonicalFile
        var directory = root
        for (component in "$scope/$packageName".split('/')) {
            require(component.isNotBlank() && component != "." && component != "..")
            directory = File(directory, component)
            require(!Files.isSymbolicLink(directory.toPath()))
        }
        require(directory.canonicalFile.toPath().startsWith(root.toPath()))
        return directory
    }
}

/** Refuse symlinks instead of following a path out of the operation's private workspace. */
private fun deleteOwnedTree(file: File): Boolean {
    if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return true
    if (Files.isSymbolicLink(file.toPath())) return false
    if (file.isDirectory) {
        val children = file.listFiles() ?: return false
        if (!children.all(::deleteOwnedTree)) return false
    }
    return file.delete()
}

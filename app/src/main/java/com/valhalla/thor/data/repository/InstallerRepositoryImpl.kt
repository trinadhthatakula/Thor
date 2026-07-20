package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.valhalla.bypass.Bypass
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.receivers.InstallReceiver
import com.valhalla.thor.data.source.local.shizuku.ShizukuPackageInstallerUtils
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper
import com.valhalla.thor.data.source.local.dhizuku.DhizukuHelper
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.R
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.getDisplayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlinx.coroutines.flow.first
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

@Single(binds = [InstallerRepository::class])
class InstallerRepositoryImpl(
    private val context: Context,
    private val eventBus: InstallerEventBus,
    private val rootGateway: RootSystemGateway,
    private val shizukuReflector: ShizukuReflector,
    private val preferenceRepository: PreferenceRepository
) : InstallerRepository {

    private val defaultInstaller = context.packageManager.packageInstaller

    /**
     * Given an on-disk copy of the installer input, return the ordered list of APK
     * base names to install from a genuine bundle (XAPK/.apks/.apkm), or null when
     * it is a monolithic APK that must be streamed whole.
     *
     * Reads the bundle with [BundleZip] (ZipFile / central directory) — NOT
     * ZipInputStream, which cannot handle APKPure's STORED-with-data-descriptor
     * entries. A monolithic APK carries its own top-level AndroidManifest.xml and no
     * bundle signal (GH#207); for real bundles we prefer the manifest.json split
     * list (unioned with any present-but-unlisted splits so a stale manifest never
     * drops one) and otherwise order the .apk entries base-first (GH#159).
     */
    private fun resolveInstallSetFromFile(bundleFile: File, displayName: String?): List<String>? {
        // Single ZipFile pass for entry names + both sidecar files.
        val contents = try {
            BundleZip.read(bundleFile, setOf("manifest.json", "info.json"))
        } catch (_: Exception) {
            return null // not a readable zip → treat as a monolithic APK
        }
        if (isMonolithicApk(contents.entryNames, displayName)) return null

        val manifest = contents.bytes["manifest.json"]?.let { parseXapkManifest(String(it)) }
        val apkmInfo = contents.bytes["info.json"]?.let { parseApkmInfo(String(it)) }
        val packageHint = manifest?.packageName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.packageName?.takeIf { it.isNotBlank() }

        return resolveBundleInstallSet(contents.entryNames, manifest?.splitApkFiles(), packageHint)
            .ifEmpty { null }
    }

    override suspend fun installPackage(
        uri: Uri,
        mode: InstallMode,
        canDowngrade: Boolean,
    ) =
        withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    InstallMode.ROOT -> {
                        installWithRoot(uri, canDowngrade)
                    }

                    InstallMode.SHIZUKU -> {
                        // 1. Try Shell command first
                        val shellSuccess = try {
                            installWithShizuku(uri, canDowngrade)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Logger.e("InstallerRepo", "Shizuku shell install failed with exception, trying reflection", e)
                            false
                        }

                        if (!shellSuccess) {
                            Logger.d("InstallerRepo", "Shizuku shell install failed. Trying reflection fallback...")
                            // 2. Try Reflection
                            val privilegedInstaller = try {
                                getShizukuPackageInstaller()
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e("InstallerRepo", "Failed to get Shizuku privileged installer: ${e.message}")
                                null
                            }

                            var reflectionSuccess = false
                            if (privilegedInstaller != null) {
                                try {
                                    performPackageInstallerInstall(
                                        uri,
                                        privilegedInstaller,
                                        canDowngrade,
                                        emitErrors = false
                                    )
                                    reflectionSuccess = true
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    Logger.e("InstallerRepo", "Shizuku reflection install failed: ${e.message}")
                                }
                            }

                            if (!reflectionSuccess) {
                                Logger.d("InstallerRepo", "Shizuku reflection install failed. Falling back to normal installer...")
                                // 3. Fallback to Normal
                                performPackageInstallerInstall(
                                    uri,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        }
                    }

                    InstallMode.DHIZUKU -> {
                        // 1. Try Shell command first
                        val shellSuccess = try {
                            installWithDhizuku(uri, canDowngrade)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Logger.e("InstallerRepo", "Dhizuku shell install failed with exception, trying reflection", e)
                            false
                        }

                        if (!shellSuccess) {
                            Logger.d("InstallerRepo", "Dhizuku shell install failed. Trying reflection fallback...")
                            // 2. Try Reflection
                            val privilegedInstaller = try {
                                getDhizukuPackageInstaller()
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e("InstallerRepo", "Failed to get Dhizuku privileged installer: ${e.message}")
                                null
                            }

                            var reflectionSuccess = false
                            if (privilegedInstaller != null) {
                                try {
                                    performPackageInstallerInstall(
                                        uri,
                                        privilegedInstaller,
                                        canDowngrade,
                                        emitErrors = false
                                    )
                                    reflectionSuccess = true
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    Logger.e("InstallerRepo", "Dhizuku reflection install failed: ${e.message}")
                                }
                            }

                            if (!reflectionSuccess) {
                                Logger.d("InstallerRepo", "Dhizuku reflection install failed. Falling back to normal installer...")
                                // 3. Fallback to Normal
                                performPackageInstallerInstall(
                                    uri,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        }
                    }

                    InstallMode.NORMAL -> {
                        performPackageInstallerInstall(
                            uri,
                            defaultInstaller,
                            canDowngrade,
                            emitErrors = true
                        )
                    }

                    InstallMode.EXTERNAL -> {
                        installWithExternal(uri)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                eventBus.emit(InstallState.Error(UiText.DynamicString(e.message ?: "Unknown error during installation")))
            }
        }

    // Create a PackageInstaller using Dhizuku's binder wrapper but make the installer package
    // be this app's package name so created sessions belong to the app UID (avoids UID mismatch).
    private fun getDhizukuPackageInstaller(): PackageInstaller {
        // Prefer using the existing Shizuku helper which returns a privileged IPackageInstaller.
        // This avoids calling IPackageManager.getPackageInstaller() directly (which may not exist
        // on some ROMs / API versions and caused NoSuchMethodError).
        try {
            val iPackageInstaller = ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()
            val root = try {
                rikka.shizuku.Shizuku.getUid() == 0
            } catch (_: Exception) {
                false
            }
            val userId = if (root) android.os.Process.myUserHandle().hashCode() else 0
            val installerPackageName = context.packageName

            return ShizukuPackageInstallerUtils.createPackageInstaller(
                iPackageInstaller,
                installerPackageName,
                userId
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Bubble up so caller falls back to normal installer; log for debugging.
            Logger.e("InstallerRepo", "getDhizukuPackageInstaller failed: ${e.message}")
            throw e
        }
    }

    // Create a PackageInstaller using Shizuku's privileged installer helper (like ShizukuReflector)
    // and make the installer package be this app's package name so sessions belong to app UID.
    private fun getShizukuPackageInstaller(): PackageInstaller {
        // Reuse ShizukuPackageInstallerUtils to get a privileged IPackageInstaller safely across API levels
        val iPackageInstaller = ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()

        val shizukuUid = try {
            rikka.shizuku.Shizuku.getUid()
        } catch (_: Exception) {
            -1
        }
        val isRoot = shizukuUid == 0
        val isShell = shizukuUid == 2000

        // If Shizuku is running as root, set userId to current user; otherwise use 0
        val userId = if (isRoot) android.os.Process.myUserHandle().hashCode() else 0

        // For ADB-based Shizuku (shell), using "com.android.shell" often works better
        // than the app's own package name to avoid permission/UID mismatch issues.
        val installerPackageName = if (isShell) "com.android.shell" else context.packageName

        return ShizukuPackageInstallerUtils.createPackageInstaller(
            iPackageInstaller,
            installerPackageName,
            userId
        )
    }

    private suspend fun installWithExternal(uri: Uri) {
        withContext(Dispatchers.Main) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                val chooser = Intent.createChooser(intent, "Install with...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                // We consider this a success in terms of handing off the job
                eventBus.emit(InstallState.Success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                eventBus.emit(InstallState.Error(UiText.DynamicString("Could not open external installer: ${e.message}")))
            }
        }
    }

    private fun copyUriToTempFiles(uri: Uri, tempDir: File): List<File>? {
        return try {
            tempDir.mkdirs()
            // Copy the input to disk once, then read it with ZipFile (central
            // directory). ZipInputStream cannot handle APKPure's
            // STORED-with-data-descriptor entries and derails on the first one.
            val bundleFile = File(tempDir, "__bundle__")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(bundleFile).use { output -> input.copyTo(output) }
            } ?: return null

            val installSet = resolveInstallSetFromFile(bundleFile, uri.getDisplayName(context))
            if (installSet == null) {
                // Monolithic APK: install the copied file as-is (renamed to base.apk).
                val tempApk = File(tempDir, "base.apk")
                if (!bundleFile.renameTo(tempApk)) {
                    bundleFile.copyTo(tempApk, overwrite = true)
                    bundleFile.delete()
                }
                listOf(tempApk)
            } else {
                // Genuine bundle: extract exactly the resolved split set via ZipFile.
                val wanted = installSet.mapTo(HashSet()) { it.substringAfterLast('/') }
                val extracted = BundleZip.extractEntries(bundleFile, wanted, tempDir)
                bundleFile.delete()
                extracted.ifEmpty { null }
            }
        } catch (e: Exception) {
            Logger.e("InstallerRepo", "Failed to copy URI to temp files", e)
            null
        }
    }

    private suspend fun installWithRoot(
        uri: Uri,
        canDowngrade: Boolean,
    ) {
        eventBus.emit(InstallState.Installing(0f))

        val tempDir = File(context.cacheDir, "install_root_${UUID.randomUUID()}")
        val tempFiles = copyUriToTempFiles(uri, tempDir)

        if (tempFiles == null || tempFiles.isEmpty()) {
            eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to extract or copy installation files")))
            tempDir.deleteRecursively()
            return
        }

        eventBus.emit(InstallState.Installing(0.5f))

        try {
            val apkPaths = tempFiles.map { it.absolutePath }
            val result = if (apkPaths.size == 1) {
                rootGateway.installApp(apkPaths[0], canDowngrade)
            } else {
                rootGateway.installMultipleApks(apkPaths, canDowngrade)
            }

            if (result.isSuccess) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
            } else {
                eventBus.emit(
                    InstallState.Error(UiText.DynamicString(result.exceptionOrNull()?.message ?: "Root install failed"))
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            eventBus.emit(InstallState.Error(UiText.DynamicString("Root install error: ${e.message}")))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun installWithShizuku(uri: Uri, canDowngrade: Boolean): Boolean {
        eventBus.emit(InstallState.Installing(0f))

        val baseDir = context.externalCacheDir ?: context.cacheDir
        val tempDir = File(baseDir, "install_shizuku_${UUID.randomUUID()}")
        val tempFiles = copyUriToTempFiles(uri, tempDir)

        if (tempFiles == null || tempFiles.isEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        val installerArg = preferenceRepository.getInstallerArg()

        return try {
            val apkPaths = tempFiles.map { it.absolutePath }
            val result = if (apkPaths.size == 1) {
                val command = "pm install -r -g${if (canDowngrade) " -d" else ""}$installerArg ${
                    com.valhalla.superuser.ShellUtils.escapedString(apkPaths[0])
                }"
                ShizukuHelper.execute(command)
            } else {
                val escapedPaths = apkPaths.joinToString(" ") {
                    com.valhalla.superuser.ShellUtils.escapedString(it)
                }
                val command = "pm install-multiple -r -g${if (canDowngrade) " -d" else ""}$installerArg $escapedPaths"
                ShizukuHelper.execute(command)
            }

            if (result.first == 0) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
                true
            } else {
                Logger.e("InstallerRepo", "Shizuku shell install failed: ${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("InstallerRepo", "Shizuku shell install failed with exception: ${e.message}", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun installWithDhizuku(uri: Uri, canDowngrade: Boolean): Boolean {
        eventBus.emit(InstallState.Installing(0f))

        val baseDir = context.externalCacheDir ?: context.cacheDir
        val tempDir = File(baseDir, "install_dhizuku_${UUID.randomUUID()}")
        val tempFiles = copyUriToTempFiles(uri, tempDir)

        if (tempFiles == null || tempFiles.isEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        val installerArg = preferenceRepository.getInstallerArg()

        return try {
            val apkPaths = tempFiles.map { it.absolutePath }
            val result = if (apkPaths.size == 1) {
                val command = "pm install -r -g${if (canDowngrade) " -d" else ""}$installerArg ${
                    com.valhalla.superuser.ShellUtils.escapedString(apkPaths[0])
                }"
                DhizukuHelper.execute(command)
            } else {
                val escapedPaths = apkPaths.joinToString(" ") {
                    com.valhalla.superuser.ShellUtils.escapedString(it)
                }
                val command = "pm install-multiple -r -g${if (canDowngrade) " -d" else ""}$installerArg $escapedPaths"
                DhizukuHelper.execute(command)
            }

            if (result.first == 0) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
                true
            } else {
                Logger.e("InstallerRepo", "Dhizuku shell install failed: ${result.second}")
                false
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("InstallerRepo", "Dhizuku shell install failed with exception: ${e.message}", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun performPackageInstallerInstall(
        uri: Uri,
        packageInstaller: PackageInstaller,
        canDowngrade: Boolean,
        emitErrors: Boolean = true
    ) {
        val totalBytes = getFileSize(uri)
        var bytesProcessed = 0L
        var lastProgressEmitted = 0
        var filesWritten = false

        eventBus.emit(InstallState.Parsing)

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        if (canDowngrade) {
            try {
                // Use reflection via Bypass as it might be unresolved in some SDK configurations
                Bypass.invoke<Any?>(params::class.java, params, "setRequestDowngrade", true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(
                    "InstallerRepo",
                    "Failed to setRequestDowngrade via reflection, proceeding without downgrade flag: ${e.message}"
                )
            }
        }

        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to create session: ${e.message}")))
                return
            } else throw e
        }

        val session = try {
            packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // createSession() succeeded but the session could not be opened — abandon it
            // so the failed session isn't leaked in PackageInstaller (both paths below).
            try {
                packageInstaller.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to open session: ${e.message}")))
                return
            } else throw e
        }

        // Whole-percent progress ticks are handed off (non-blocking) through this
        // conflated channel and emitted by a child of the install coroutine below, so a
        // late tick can never land after a terminal state and cancelling the install
        // cancels the emission too.
        val progressChannel = Channel<Float>(Channel.CONFLATED)

        // Helper to track progress across different streams
        fun getTrackedStream(baseStream: InputStream): InputStream {
            return object : InputStream() {
                override fun read(): Int {
                    val b = baseStream.read()
                    if (b != -1) updateProgress(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = baseStream.read(b, off, len)
                    if (read != -1) updateProgress(read.toLong())
                    return read
                }

                override fun close() {
                    baseStream.close()
                }

                private fun updateProgress(readBytes: Long) {
                    bytesProcessed += readBytes
                    if (totalBytes > 0) {
                        val currentProgress =
                            ((bytesProcessed.toDouble() / totalBytes) * 100).toInt()
                        if (currentProgress > lastProgressEmitted) {
                            lastProgressEmitted = currentProgress
                            // Non-blocking hand-off; conflated so only the latest tick
                            // survives if the drainer is momentarily behind.
                            progressChannel.trySend(bytesProcessed.toFloat() / totalBytes)
                        }
                    }
                }
            }
        }

        try {
            // Copy the input to disk once (tracking progress), then read it with
            // ZipFile (central directory). ZipInputStream cannot handle APKPure's
            // STORED-with-data-descriptor entries and derails on the first one.
            val bundleFile = File(context.cacheDir, "install_bundle_${UUID.randomUUID()}")
            try {
                val copied = coroutineScope {
                    // Drain progress ticks on a child coroutine so emissions are bound to the
                    // install job (cancellation stops them) and can never outlive the copy phase.
                    // Closing the channel ends the drain loop; coroutineScope then awaits this child
                    // before returning, so the final tick is flushed before we continue. No explicit
                    // join(): it is redundant here, and a suspending join() in a finally could mask
                    // the real failure (e.g. an IOException from openInputStream) under cancellation.
                    launch {
                        for (fraction in progressChannel) {
                            eventBus.emit(InstallState.Installing(fraction))
                        }
                    }
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(bundleFile).use { output ->
                                getTrackedStream(input).copyTo(output)
                            }
                            true
                        } ?: false
                    } finally {
                        progressChannel.close()
                    }
                }

                if (!copied) {
                    session.abandon()
                    Logger.e("thor", "Could not open file stream.")
                    if (emitErrors) {
                        eventBus.emit(InstallState.Error(UiText.DynamicString("Could not open file stream.")))
                        return
                    } else throw Exception("Could not open file stream.")
                }

                // Genuine bundle: write each resolved split into the session, read via
                // ZipFile so STORED-with-data-descriptor entries stream correctly.
                val installSet = resolveInstallSetFromFile(bundleFile, uri.getDisplayName(context))
                if (installSet != null) {
                    val wanted = installSet.mapTo(HashSet()) { it.substringAfterLast('/').lowercase() }
                    val seen = HashSet<String>()
                    ZipFile(bundleFile).use { zf ->
                        for (entry in zf.entries()) {
                            if (entry.isDirectory) continue
                            val base = entry.name.substringAfterLast('/')
                            val key = base.lowercase()
                            if (key !in wanted || !seen.add(key)) continue
                            val size = if (entry.size >= 0) entry.size else -1L
                            session.openWrite(base, 0, size).use { out ->
                                zf.getInputStream(entry).use { inner -> inner.copyTo(out) }
                                session.fsync(out)
                            }
                            filesWritten = true
                        }
                    }
                }

                // Monolithic APK (or not a readable bundle): stream the copied file
                // whole as base.apk.
                if (!filesWritten) {
                    Logger.d("thor", "Treating stream as monolithic base.apk")
                    val size = if (bundleFile.length() > 0) bundleFile.length() else -1L
                    session.openWrite("base.apk", 0, size).use { out ->
                        bundleFile.inputStream().use { inner -> inner.copyTo(out) }
                        session.fsync(out)
                    }
                    filesWritten = true
                }
            } finally {
                bundleFile.delete()
            }

            eventBus.emit(InstallState.Installing(1.0f))

            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                setPackage(context.packageName)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            try {
                session.abandon()
            } catch (_: Exception) {
            }
            Logger.e("thorInstaller", "Install failed", e)
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString(e.message ?: "Unknown installation error")))
            } else throw e
        }
    }

    private fun getFileSize(uri: Uri): Long {
        var size = -1L
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = it.getLong(sizeIndex)
                }
            }
        }
        return size
    }
}
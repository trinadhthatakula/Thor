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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

@Single(binds = [InstallerRepository::class])
class InstallerRepositoryImpl(
    private val context: Context,
    private val eventBus: InstallerEventBus,
    private val rootGateway: RootSystemGateway,
    private val shizukuReflector: ShizukuReflector
) : InstallerRepository {

    private val defaultInstaller = context.packageManager.packageInstaller

    /**
     * Returns the ordered list of APK filenames to install from a genuine bundle
     * (XAPK/.apks/.apkm), or null when the file is a monolithic APK that must be
     * streamed whole.
     *
     * A monolithic APK carries its own top-level AndroidManifest.xml (GH#207); it
     * must never be split by its inner .apk assets. For real bundles we prefer the
     * manifest.json split list, and otherwise order the .apk entries so the base
     * comes first and config splits last (GH#159).
     */
    private fun resolveXapkApkFiles(uri: Uri): List<String>? {
        if (!isZipBundle(uri)) return null
        return try {
            var manifestBytes: ByteArray? = null
            val entryNames = mutableListOf<String>()

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) entryNames += entry.name
                        if (entry.name.equals("manifest.json", ignoreCase = true)) {
                            manifestBytes = zipStream.readBytes()
                            zipStream.closeEntry()
                        } else {
                            zipStream.closeEntry()
                        }
                        entry = zipStream.nextEntry
                    }
                }
            }

            // Monolithic-APK gate (GH#207): a file with a top-level AndroidManifest.xml
            // is a single APK — stream it whole via the monolithic path (null).
            if (isMonolithicApk(entryNames)) return null

            // Prefer the manifest.json split_apks list for a genuine XAPK — but only when
            // every declared split actually exists in the archive. A stale/partial manifest
            // (splits renamed, removed, or never packaged) must not shadow the entry-scan
            // recovery path below, or extraction would yield nothing and the install fails.
            val manifest = manifestBytes?.let { parseXapkManifest(String(it)) }
            val manifestFiles = manifest?.splitApkFiles()?.takeIf { it.isNotEmpty() }
            if (manifestFiles != null) {
                val availableNames = entryNames.mapTo(HashSet()) { it.substringAfterLast('/') }
                if (manifestFiles.all { it.substringAfterLast('/') in availableNames }) {
                    return manifestFiles
                }
            }

            // No usable manifest: order the .apk entries base-first, splits last so
            // install-multiple gets a valid base (GH#159). selectBaseApkCandidates is
            // empty only when there are no .apk entries at all → treat as monolithic.
            selectBaseApkCandidates(entryNames, manifest?.packageName).ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean) =
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
        val xapkApkFiles = resolveXapkApkFiles(uri)
        return try {
            tempDir.mkdirs()
            if (xapkApkFiles != null) {
                val extracted = mutableListOf<File>()
                val wanted = xapkApkFiles.map { it.substringAfterLast('/') }.toSet()

                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            val entryFileName = entry.name.substringAfterLast('/')
                            if (!entry.isDirectory && entryFileName in wanted) {
                                val dest = File(tempDir, entryFileName)
                                FileOutputStream(dest).use { fos -> zipStream.copyTo(fos) }
                                extracted.add(dest)
                            } else {
                                zipStream.closeEntry()
                            }
                            entry = zipStream.nextEntry
                        }
                    }
                }
                if (extracted.isEmpty()) null else extracted
            } else {
                val tempApk = File(tempDir, "base.apk")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempApk).use { output -> input.copyTo(output) }
                } ?: return null
                listOf(tempApk)
            }
        } catch (e: Exception) {
            Logger.e("InstallerRepo", "Failed to copy URI to temp files", e)
            null
        }
    }

    private suspend fun installWithRoot(uri: Uri, canDowngrade: Boolean) {
        eventBus.emit(InstallState.Installing(0f))

        val tempDir = File(context.cacheDir, "install_root_${System.currentTimeMillis()}")
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
        val tempDir = File(baseDir, "install_shizuku_${System.currentTimeMillis()}")
        val tempFiles = copyUriToTempFiles(uri, tempDir)

        if (tempFiles == null || tempFiles.isEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        return try {
            val apkPaths = tempFiles.map { it.absolutePath }
            val result = if (apkPaths.size == 1) {
                val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${
                    com.valhalla.superuser.ShellUtils.escapedString(apkPaths[0])
                }"
                ShizukuHelper.execute(command)
            } else {
                val escapedPaths = apkPaths.joinToString(" ") {
                    com.valhalla.superuser.ShellUtils.escapedString(it)
                }
                val command = "pm install-multiple -r -g${if (canDowngrade) " -d" else ""} $escapedPaths"
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
        val tempDir = File(baseDir, "install_dhizuku_${System.currentTimeMillis()}")
        val tempFiles = copyUriToTempFiles(uri, tempDir)

        if (tempFiles == null || tempFiles.isEmpty()) {
            tempDir.deleteRecursively()
            return false
        }

        eventBus.emit(InstallState.Installing(0.5f))

        return try {
            val apkPaths = tempFiles.map { it.absolutePath }
            val result = if (apkPaths.size == 1) {
                val command = "pm install -r -g${if (canDowngrade) " -d" else ""} ${
                    com.valhalla.superuser.ShellUtils.escapedString(apkPaths[0])
                }"
                DhizukuHelper.execute(command)
            } else {
                val escapedPaths = apkPaths.joinToString(" ") {
                    com.valhalla.superuser.ShellUtils.escapedString(it)
                }
                val command = "pm install-multiple -r -g${if (canDowngrade) " -d" else ""} $escapedPaths"
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

    private fun isZipBundle(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                        magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() // PK header
            } ?: false
        } catch (_: Exception) {
            false
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

        var session = try {
            packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (emitErrors) {
                eventBus.emit(InstallState.Error(UiText.DynamicString("Failed to open session: ${e.message}")))
                return
            } else throw e
        }

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
                            CoroutineScope(Dispatchers.IO).launch {
                                eventBus.emit(InstallState.Installing(bytesProcessed.toFloat() / totalBytes))
                            }
                        }
                    }
                }
            }
        }

        try {
            // ATTEMPT 1: Try as XAPK/APKS/APKM bundle
            // Use manifest.json split_apks list if available; otherwise include all .apk entries.
            val xapkApkFiles = resolveXapkApkFiles(uri)

            if (xapkApkFiles != null) {
                val wanted = xapkApkFiles.map { it.substringAfterLast('/') }.toSet()
                try {
                    context.contentResolver.openInputStream(uri)?.use { bundleStream ->
                        ZipInputStream(bundleStream).use { zipStream ->
                            var entry = zipStream.nextEntry
                            while (entry != null) {
                                val entryFileName = entry.name.substringAfterLast('/')
                                if (!entry.isDirectory && entryFileName in wanted) {
                                    filesWritten = true
                                    val size = entry.size

                                    if (size == -1L) {
                                        // Unknown size: buffer to temp file first
                                        val tempFile = File(
                                            context.cacheDir,
                                            "temp_${System.currentTimeMillis()}_$entryFileName"
                                        )
                                        FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }
                                        val actualSize = tempFile.length()
                                        session.openWrite(entryFileName, 0, actualSize).use { out ->
                                            tempFile.inputStream().use { fis -> fis.copyTo(out) }
                                            session.fsync(out)
                                        }
                                        tempFile.delete()
                                    } else {
                                        // Known size: stream directly
                                        session.openWrite(entryFileName, 0, size).use { out ->
                                            val buffer = ByteArray(65536)
                                            var len: Int
                                            while (zipStream.read(buffer).also { len = it } > 0) {
                                                out.write(buffer, 0, len)
                                            }
                                            session.fsync(out)
                                        }
                                    }
                                } else {
                                    zipStream.closeEntry()
                                }
                                entry = zipStream.nextEntry
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("thor", "Bundle extraction failed, trying fallback: ${e.message}")
                    if (filesWritten) {
                        try {
                            session.abandon()
                            session.close()
                        } catch (_: Exception) {
                        }
                        val newSessionId = packageInstaller.createSession(params)
                        try {
                            session = packageInstaller.openSession(newSessionId)
                        } catch (e: Exception) {
                            try {
                                packageInstaller.abandonSession(newSessionId)
                            } catch (_: Exception) {
                            }
                            throw e
                        }
                    }
                    filesWritten = false
                }
            }

            // ATTEMPT 2: Fallback to Monolithic APK
            if (!filesWritten) {
                Logger.d("thor", "Fallback: Treating stream as monolithic base.apk")

                bytesProcessed = 0
                lastProgressEmitted = 0

                val rawStream = context.contentResolver.openInputStream(uri)
                if (rawStream == null) {
                    session.abandon()
                    Logger.e("thor", "Could not open file stream.")
                    if (emitErrors) {
                        eventBus.emit(InstallState.Error(UiText.DynamicString("Could not open file stream.")))
                        return
                    } else throw Exception("Could not open file stream.")
                }

                val trackedStream = getTrackedStream(rawStream)

                trackedStream.use { input ->
                    val size = if (totalBytes > 0) totalBytes else -1L
                    val outStream = session.openWrite("base.apk", 0, size)
                    input.copyTo(outStream)
                    session.fsync(outStream)
                    outStream.close()
                    filesWritten = true
                }
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
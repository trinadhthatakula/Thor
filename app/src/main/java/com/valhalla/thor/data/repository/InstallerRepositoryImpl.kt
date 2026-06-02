package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import org.koin.core.annotation.Single
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
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    @Serializable
    private data class XapkManifest(
        @SerialName("split_apks") val splitApks: List<XapkSplitApk> = emptyList()
    )

    @Serializable
    private data class XapkSplitApk(
        @SerialName("file") val file: String,
        @SerialName("id") val id: String
    )

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val defaultInstaller = context.packageManager.packageInstaller

    /**
     * Returns the ordered list of APK filenames to install from an XAPK bundle.
     * Uses manifest.json split_apks if present; falls back to all .apk entries.
     */
    private fun resolveXapkApkFiles(uri: Uri): List<String>? {
        if (!isZipBundle(uri)) return null
        return try {
            var manifestBytes: ByteArray? = null
            val allApkNames = mutableListOf<String>()

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        when {
                            entry.name.equals("manifest.json", ignoreCase = true) ->
                                manifestBytes = zipStream.readBytes()
                            !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true) ->
                                allApkNames += entry.name
                            else -> zipStream.closeEntry()
                        }
                        zipStream.nextEntry.also { entry = it }
                    }
                }
            }

            manifestBytes?.let { bytes ->
                try {
                    val files = json.decodeFromString<XapkManifest>(String(bytes)).splitApks.map { it.file }
                    if (files.isEmpty()) null else files
                } catch (_: Exception) {
                    null
                }
            } ?: allApkNames.ifEmpty { null }
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
                        val privilegedInstaller = try {
                            getShizukuPackageInstaller()
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Logger.e(
                                "InstallerRepo",
                                "Failed to get Shizuku installer, will use normal installer: ${e.message}"
                            )
                            null
                        }

                        if (privilegedInstaller != null) {
                            try {
                                // Try privileged path but suppress error emission so we can fall back silently
                                performPackageInstallerInstall(
                                    uri,
                                    privilegedInstaller,
                                    canDowngrade,
                                    emitErrors = false
                                )
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e(
                                    "InstallerRepo",
                                    "Shizuku privileged install failed, falling back to normal: ${e.message}"
                                )
                                performPackageInstallerInstall(
                                    uri,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        } else {
                            // No privileged installer available, use normal installer and allow errors
                            performPackageInstallerInstall(
                                uri,
                                defaultInstaller,
                                canDowngrade,
                                emitErrors = true
                            )
                        }
                    }

                    InstallMode.DHIZUKU -> {
                        val privilegedInstaller = try {
                            getDhizukuPackageInstaller()
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            Logger.e(
                                "InstallerRepo",
                                "Failed to get Dhizuku installer, will use normal installer: ${e.message}"
                            )
                            null
                        }

                        if (privilegedInstaller != null) {
                            try {
                                // Try privileged path but suppress error emission so we can fall back silently
                                performPackageInstallerInstall(
                                    uri,
                                    privilegedInstaller,
                                    canDowngrade,
                                    emitErrors = false
                                )
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Logger.e(
                                    "InstallerRepo",
                                    "Dhizuku privileged install failed, falling back to normal: ${e.message}"
                                )
                                performPackageInstallerInstall(
                                    uri,
                                    defaultInstaller,
                                    canDowngrade,
                                    emitErrors = true
                                )
                            }
                        } else {
                            // No privileged installer available, use normal installer and allow errors
                            performPackageInstallerInstall(
                                uri,
                                defaultInstaller,
                                canDowngrade,
                                emitErrors = true
                            )
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
                eventBus.emit(InstallState.Error(e.message ?: "Unknown error during installation"))
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
                eventBus.emit(InstallState.Error("Could not open external installer: ${e.message}"))
            }
        }
    }

    private suspend fun installWithRoot(uri: Uri, canDowngrade: Boolean) {
        eventBus.emit(InstallState.Installing(0f))

        val tempDir = File(context.cacheDir, "install_root_${System.currentTimeMillis()}")
        val tempApk = File(context.cacheDir, "install_temp_${System.currentTimeMillis()}.apk")

        try {
            val apkPaths: List<String>

            val xapkApkFiles = resolveXapkApkFiles(uri)
            if (xapkApkFiles != null) {
                // XAPK/APKS: extract APKs listed in manifest (or all .apk files as fallback)
                tempDir.mkdirs()
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

                if (extracted.isEmpty()) {
                    eventBus.emit(InstallState.Error("No APK files found in bundle"))
                    return
                }

                apkPaths = extracted.map { it.absolutePath }
            } else {
                // Single APK
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempApk).use { output -> input.copyTo(output) }
                } ?: run {
                    eventBus.emit(InstallState.Error("Failed to read input file"))
                    return
                }

                apkPaths = listOf(tempApk.absolutePath)
            }

            eventBus.emit(InstallState.Installing(0.5f))

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
                    InstallState.Error(result.exceptionOrNull()?.message ?: "Root install failed")
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            eventBus.emit(InstallState.Error("Root install error: ${e.message}"))
        } finally {
            tempApk.delete()
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
                Logger.e("InstallerRepo", "Failed to setRequestDowngrade", e)
                if (emitErrors) {
                    eventBus.emit(InstallState.Error("Failed to request downgrade: ${e.message}"))
                    return
                } else throw Exception("Failed to request downgrade: ${e.message}")
            }
        }

        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (emitErrors) {
                eventBus.emit(InstallState.Error("Failed to create session: ${e.message}"))
                return
            } else throw e
        }

        var session = try {
            packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (emitErrors) {
                eventBus.emit(InstallState.Error("Failed to open session: ${e.message}"))
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
                            } catch (_: Exception) {}
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
                        eventBus.emit(InstallState.Error("Could not open file stream."))
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
                eventBus.emit(InstallState.Error(e.message ?: "Unknown installation error"))
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
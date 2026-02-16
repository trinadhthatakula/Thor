package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.receivers.InstallReceiver
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.source.local.shizuku.ShizukuPackageInstallerUtils
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.repository.InstallMode
import com.valhalla.thor.domain.repository.InstallerRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI

class InstallerRepositoryImpl(
    private val context: Context,
    private val eventBus: InstallerEventBus,
    private val rootGateway: RootSystemGateway,
    private val shizukuReflector: ShizukuReflector
) : InstallerRepository {

    private val defaultInstaller = context.packageManager.packageInstaller

    override suspend fun installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean) = withContext(Dispatchers.IO) {
        try {
            when (mode) {
                InstallMode.ROOT -> {
                    installWithRoot(uri, canDowngrade)
                }

                InstallMode.SHIZUKU -> {
                    val shizukuInstaller = try {
                        shizukuReflector.getPackageInstaller()
                    } catch (e: Exception) {
                        eventBus.emit(InstallState.Error("Failed to get Shizuku installer: ${e.message}"))
                        return@withContext
                    }
                    performPackageInstallerInstall(uri, shizukuInstaller, canDowngrade)
                }

                InstallMode.DHIZUKU -> {
                    val dhizukuInstaller = try {
                        getDhizukuPackageInstaller()
                    } catch (e: Exception) {
                        eventBus.emit(InstallState.Error("Failed to get Dhizuku installer: ${e.message}"))
                        return@withContext
                    }
                    performPackageInstallerInstall(uri, dhizukuInstaller, canDowngrade)
                }

                InstallMode.NORMAL -> {
                    performPackageInstallerInstall(uri, defaultInstaller, canDowngrade)
                }
            }
        } catch (e: Exception) {
            eventBus.emit(InstallState.Error(e.message ?: "Unknown error during installation"))
        }
    }

    private fun getDhizukuPackageInstaller(): PackageInstaller {
        val binder = SystemServiceHelper.getSystemService("package")
        val wrappedBinder = DhizukuAPI.binderWrapper(binder)
        val ipm = IPackageManager.Stub.asInterface(ShizukuBinderWrapper(wrappedBinder))
        val ipi = ipm.packageInstaller
        val privilegedIpi = android.content.pm.IPackageInstaller.Stub.asInterface(ShizukuBinderWrapper(ipi.asBinder()))
        
        return ShizukuPackageInstallerUtils.createPackageInstaller(
            privilegedIpi,
            "com.android.shell", // Typical for privileged installers
            0 // Assuming user 0 for Dhizuku
        )
    }

    private suspend fun installWithRoot(uri: Uri, canDowngrade: Boolean) {
        eventBus.emit(InstallState.Installing(0f))

        val tempFile = File(context.cacheDir, "install_temp_${System.currentTimeMillis()}.apk")

        try {
            // Copy uri to temp file
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                eventBus.emit(InstallState.Error("Failed to read input file"))
                return
            }

            eventBus.emit(InstallState.Installing(0.5f))

            // Execute root install
            val result = rootGateway.installApp(tempFile.absolutePath, canDowngrade)

            if (result.isSuccess) {
                eventBus.emit(InstallState.Installing(1.0f))
                eventBus.emit(InstallState.Success)
            } else {
                eventBus.emit(
                    InstallState.Error(
                        result.exceptionOrNull()?.message ?: "Root install failed"
                    )
                )
            }

        } catch (e: Exception) {
            eventBus.emit(InstallState.Error("Root install error: ${e.message}"))
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    private suspend fun performPackageInstallerInstall(
        uri: Uri,
        packageInstaller: PackageInstaller,
        canDowngrade: Boolean
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
                // Use reflection via HiddenApiBypass as it might be unresolved in some SDK configurations
                HiddenApiBypass.invoke(params::class.java, params, "setRequestDowngrade", true)
            } catch (e: Exception) {
                Logger.e("InstallerRepo", "Failed to setRequestDowngrade", e)
                eventBus.emit(InstallState.Error("Failed to request downgrade: ${e.message}"))
                return
            }
        }

        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (e: Exception) {
            eventBus.emit(InstallState.Error("Failed to create session: ${e.message}"))
            return
        }

        val session = try {
            packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            eventBus.emit(InstallState.Error("Failed to open session: ${e.message}"))
            return
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
            // ATTEMPT 1: Try as Bundle (XAPK/APKS)
            val bundleStream: InputStream? = context.contentResolver.openInputStream(uri)

            if (bundleStream != null) {
                try {
                    ZipInputStream(bundleStream).use { zipStream ->
                        var entry = zipStream.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                filesWritten = true
                                val size = entry.size

                                if (size == -1L) {
                                    // Unknown size in Zip: Buffer to temp
                                    val tempFile = File(
                                        context.cacheDir,
                                        "temp_${System.currentTimeMillis()}_${File(name).name}"
                                    )
                                    FileOutputStream(tempFile).use { fos -> zipStream.copyTo(fos) }

                                    val actualSize = tempFile.length()
                                    val outStream = session.openWrite(name, 0, actualSize)
                                    tempFile.inputStream().use { fis -> fis.copyTo(outStream) }
                                    session.fsync(outStream)
                                    outStream.close()
                                    tempFile.delete()
                                } else {
                                    // Known size: Stream directly
                                    val outStream = session.openWrite(name, 0, size)
                                    val buffer = ByteArray(65536)
                                    var len: Int
                                    while (zipStream.read(buffer).also { len = it } > 0) {
                                        outStream.write(buffer, 0, len)
                                    }
                                    session.fsync(outStream)
                                    outStream.close()
                                }
                            }
                            zipStream.closeEntry()
                            entry = zipStream.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("thor", "Not a valid bundle zip, trying fallback. Error: ${e.message}")
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
                    eventBus.emit(InstallState.Error("Could not open file stream."))
                    return
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
            session.abandon()
            Logger.e("thorInstaller", "Install failed", e)
            eventBus.emit(InstallState.Error(e.message ?: "Unknown installation error"))
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
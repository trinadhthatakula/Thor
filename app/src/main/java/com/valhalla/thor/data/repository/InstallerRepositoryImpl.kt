package com.valhalla.thor.data.repository

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.receivers.InstallReceiver
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.repository.InstallerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.jvm.java

class InstallerRepositoryImpl(
    private val context: Context,
    private val eventBus: InstallerEventBus
) : InstallerRepository {

    private val packageInstaller = context.packageManager.packageInstaller

    @SuppressLint("RequestInstallPackagesPolicy")
    override suspend fun installPackage(uri: Uri) = withContext(Dispatchers.IO) {
        val totalBytes = getFileSize(uri)
        var bytesProcessed = 0L
        var lastProgressEmitted = 0
        var filesWritten = false

        eventBus.emit(InstallState.Parsing)

        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )

        val sessionId = try {
            packageInstaller.createSession(params)
        } catch (e: Exception) {
            eventBus.emit(InstallState.Error("Failed to create session: ${e.message}"))
            return@withContext
        }

        val session = try {
            packageInstaller.openSession(sessionId)
        } catch (e: Exception) {
            eventBus.emit(InstallState.Error("Failed to open session: ${e.message}"))
            return@withContext
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
                        val currentProgress = ((bytesProcessed.toDouble() / totalBytes) * 100).toInt()
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
            // We assume it's a zip and look for nested .apk files
            var bundleStream: InputStream? = context.contentResolver.openInputStream(uri)

            if (bundleStream != null) {
                // We don't track progress on the Zip scan itself to avoid double counting if we fail back
                // or we can track it but reset bytesProcessed if we switch to fallback.
                // For simplicity, let's just try to read it.

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
                                    Log.w("thor", "Entry $name has unknown size. Buffering...")
                                    val tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}_$name")
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
                    // Zip processing failed, likely not a zip file or corrupt.
                    // We will fall through to check !filesWritten
                    Log.d("thor", "Not a valid bundle zip, trying fallback.")
                }
            }

            // ATTEMPT 2: Fallback to Monolithic APK
            // If the zip scan found nothing (or failed), we treat the file itself as the APK.
            if (!filesWritten) {
                Log.d("thor", "Fallback: Treating stream as monolithic base.apk")

                // Reset progress for the actual stream
                bytesProcessed = 0
                lastProgressEmitted = 0

                val rawStream = context.contentResolver.openInputStream(uri)
                if (rawStream == null) {
                    session.abandon()
                    eventBus.emit(InstallState.Error("Could not open file stream."))
                    return@withContext
                }

                // Wrap in progress tracker
                val trackedStream = getTrackedStream(rawStream)

                trackedStream.use { input ->
                    // For standard APKs, we generally know the totalBytes from the query earlier.
                    // If totalBytes is -1, session.openWrite requires a valid size or -1 if supported.
                    // Most content providers give size. If not, we might need to buffer, but let's try direct.
                    val size = if (totalBytes > 0) totalBytes else -1L

                    val outStream = session.openWrite("base.apk", 0, size)
                    input.copyTo(outStream)
                    session.fsync(outStream)
                    outStream.close()
                    filesWritten = true
                }
            }

            if (!filesWritten) {
                session.abandon()
                eventBus.emit(InstallState.Error("No valid APK files found. Is this a supported file type?"))
                return@withContext
            }

            // Finalize
            eventBus.emit(InstallState.Installing(1.0f))

            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
                setPackage(context.packageName)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()

        } catch (e: Exception) {
            session.abandon()
            Log.e("thorInstaller", "Install failed", e)
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

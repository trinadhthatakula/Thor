package com.valhalla.thor.presentation.utils

import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class CacheScanner(
    private val systemRepository: SystemRepository
) {

    /**
     * Scans cache sizes using Root.
     * @param filterPackageNames If provided, only calculates cache for these specific packages.
     * This filters out "zombie" folders from uninstalled apps.
     */
    suspend fun getCacheSize(filterPackageNames: List<String>? = null): String =
        withContext(Dispatchers.IO) {
            if (!systemRepository.isRootAvailable()) {
                return@withContext "N/A"
            }

            try {
                // Command: List summary (-s) in KB (-k) for all directories in standard cache locations.
                // 2>/dev/null suppresses "Permission denied" or "No such file" errors
                val command = "du -k -s /data/data/*/cache /sdcard/Android/data/*/cache 2>/dev/null"

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var totalSizeKb = 0L
                val filterSet = filterPackageNames?.toSet()

                var line: String? = reader.readLine()
                while (line != null) {
                    // Output format: "1234   /data/data/com.package/cache"
                    val parts = line.trim().split("\\s+".toRegex())

                    if (parts.size >= 2) {
                        val size = parts[0].toLongOrNull() ?: 0L
                        val path = parts[1]

                        if (filterSet != null) {
                            // Robustly extract package name.
                            // It's the segment immediately preceding "/cache"
                            val packageName =
                                path.substringBeforeLast("/cache").substringAfterLast("/")

                            if (packageName in filterSet) {
                                totalSizeKb += size
                            }
                        } else {
                            // No filter -> Add everything
                            totalSizeKb += size
                        }
                    }
                    line = reader.readLine()
                }
                process.waitFor()

                formatSize(totalSizeKb * 1024)
            } catch (e: Exception) {
                e.printStackTrace()
                "Error"
            }
        }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.1f %s",
            sizeBytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }
}
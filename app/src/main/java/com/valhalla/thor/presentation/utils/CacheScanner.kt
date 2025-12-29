package com.valhalla.thor.presentation.utils

import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class CacheScanner(
    private val systemRepository: SystemRepository
) {

    suspend fun getTotalCacheSize(): String = withContext(Dispatchers.IO) {
        if (!systemRepository.isRootAvailable()) {
            return@withContext "N/A"
        }

        try {
            // Command explanation:
            // du: disk usage
            // -k: block-size=1K
            // -S: separate-dirs (do not include size of subdirectories) - actually we want total, so maybe not -S.
            // -c: produce a grand total
            // We check common cache locations.
            // Note: This is an estimation.
            val command = "du -k -c /data/data/*/cache /data/user_de/0/*/cache /sdcard/Android/data/*/cache 2>/dev/null | tail -1 | awk '{print $1}'"

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()

            val sizeInKb = output?.toLongOrNull() ?: 0L
            formatSize(sizeInKb * 1024) // Convert KB to Bytes then format
        } catch (e: Exception) {
            e.printStackTrace()
            "Error"
        }
    }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(sizeBytes.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.ENGLISH,
            "%.1f %s",
            sizeBytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups]
        )
    }
}
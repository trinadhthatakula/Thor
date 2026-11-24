// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The caller supplies an I/O dispatcher and the actual base APK, including its signing block. */
internal suspend fun baseApkSha256(path: String?): String? {
    if (path.isNullOrBlank()) return null
    return try {
        File(path).inputStream().use { it.sha256Hex() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal suspend fun InputStream.sha256Hex(): String {
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        context.ensureActive()
        val count = read(buffer)
        if (count == -1) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toLowercaseHex()
}

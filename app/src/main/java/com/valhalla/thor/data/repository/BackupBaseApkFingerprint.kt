// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.PackageInfo
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

/** Resolve the embedded XAPK through the same central directory and base parser as the installer. */
internal suspend fun ArchiveSource.backupBaseApkSha256(
    header: ArchiveHeader,
    stagingDirectory: File,
    parse: (File) -> PackageInfo?,
): String? {
    val bundle = header.appBundle ?: return null
    if (bundle.fileName != THORBAK_BUNDLE_ENTRY || bundle.bytes !in 1..MAX_STAGED_BUNDLE_BYTES) return null
    return try {
        val context = currentCoroutineContext()
        val staged = File.createTempFile("backup_preview_", ".xapk", stagingDirectory)
        try {
            if (bundle.bytes > stagingDirectory.previewSpace) return null
            val input = openEntry(THORBAK_BUNDLE_ENTRY) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = input.use { source ->
                staged.outputStream().use { output ->
                    source.copyAtMostTo(output, bundle.bytes, digest) { context.ensureActive() }
                }
            }
            if (copied != bundle.bytes) return null
            val copiedDigest = digest.digest().toLowercaseHex()
            if (bundle.sha256 != null && !copiedDigest.equals(bundle.sha256, ignoreCase = true)) return null
            val plan = archiveBundlePlan(staged)
            val base = File.createTempFile("backup_base_", ".apk", stagingDirectory)
            try {
                val info = extractParsedBase(staged, base, plan, parse) ?: return null
                if (info.packageName != header.packageName || info.longVersionCode != header.versionCode) return null
                baseApkSha256(base.path)
            } finally {
                base.delete()
            }
        } finally {
            staged.delete()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

/** The preview fingerprint and package identity always come from the same parsed candidate. */
internal suspend fun extractParsedBase(
    bundle: File,
    base: File,
    plan: BundleInstallPlan,
    parse: (File) -> PackageInfo?,
): PackageInfo? {
    val context = currentCoroutineContext()
    for (candidate in plan.identityCandidates) {
        context.ensureActive()
        val budget = minOf(MAX_EXTRACTED_TOTAL_BYTES, base.parentFile?.previewSpace ?: MAX_EXTRACTED_TOTAL_BYTES)
        val extracted = BundleZip.extractEntryTo(bundle, candidate.substringAfterLast('/'), base, budget) {
            context.ensureActive()
        }
        if (!extracted) continue
        val info = parse(base)
        if (info?.applicationInfo != null) return info
    }
    return null
}

// A preview may use free cache space. It should not evict other apps' cached data to display a checksum.
@get:Suppress("UsableSpace")
private val File.previewSpace: Long get() = usableSpace

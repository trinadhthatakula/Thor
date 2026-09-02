// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.repository.ArchiveBundleVerification
import com.valhalla.thor.domain.repository.ArchiveBundleVerifier
import com.valhalla.thor.domain.repository.ParsedArchivePackageIdentity
import com.valhalla.thor.domain.repository.validateParsedArchiveBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.util.UUID

@Single(binds = [ArchiveBundleVerifier::class])
class ArchiveBundleVerifierImpl(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : ArchiveBundleVerifier {

    override suspend fun verify(
        bundle: File,
        header: ArchiveHeader,
    ): ArchiveBundleVerification = withContext(ioDispatcher) {
        val scratch = File(context.cacheDir, "archive-verify-${UUID.randomUUID()}")
        try {
            val contents = BundleZip.read(bundle, setOf("manifest.json", "info.json"))
            val manifest = contents.bytes["manifest.json"]?.let { parseXapkManifest(String(it)) }
            val apkm = contents.bytes["info.json"]?.let { parseApkmInfo(String(it)) }
            val packageHint = manifest?.packageName?.takeIf { it.isNotBlank() }
                ?: apkm?.packageName?.takeIf { it.isNotBlank() }
            val plan = resolveBundlePlan(
                contents.entryNames,
                manifest?.splitApkFiles(),
                manifest?.baseApkFile(),
                packageHint,
                requireCompleteManifest = manifest != null,
            )
            if (plan.installSet.isEmpty()) return@withContext ArchiveBundleVerification.Refused

            scratch.mkdirs()
            BundleZip.extractEntries(
                bundle,
                plan.installSet.map { it.substringAfterLast('/') },
                scratch,
            )

            // Parsing the directory invokes PackageManager's APK-cluster parser. Unlike parsing each
            // file as a monolithic APK (which necessarily rejects a split), the cluster parser reads
            // every selected member and proves one base, package/version consistency, unique splits,
            // and exact signer equality across the complete set.
            val info = context.packageManager.readArchivePackageInfo(
                scratch,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return@withContext ArchiveBundleVerification.Refused
            val parsed = ParsedArchivePackageIdentity(
                packageName = info.packageName,
                versionCode = info.longVersionCode,
                signerSha256 = info.currentSignerSha256(),
                apkCount = 1 + info.splitNames.orEmpty().size,
            )
            validateParsedArchiveBundle(header, plan.installSet, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ArchiveBundleVerification.Refused
        } finally {
            // No suspension: verification scratch is removed even when the coroutine is cancelled.
            scratch.deleteRecursively()
        }
    }
}

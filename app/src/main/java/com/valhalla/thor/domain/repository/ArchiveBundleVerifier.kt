// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ArchiveHeader
import java.io.File

/** Parsed identity of one APK selected from an authenticated archive bundle. */
data class ArchiveApkIdentity(
    val entryName: String,
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
    val isBase: Boolean,
)

/** Identity and parser-proven size of one complete extracted APK cluster. */
data class ParsedArchivePackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
    val apkCount: Int,
)

/**
 * Validate the one package identity returned after the platform parsed the complete extracted
 * cluster. A successful cluster parse has already proved one base, unique splits, uniform package
 * and version identities, and exact signer equality across every APK. Comparing its reported APK
 * count with the immutable install set proves that the parser accounted for every selected member.
 * This deliberately does not depend on archive source paths: Android 9-14 do not consistently
 * expose those paths on [android.content.pm.PackageInfo.applicationInfo].
 */
fun validateParsedArchiveBundle(
    header: ArchiveHeader,
    installSet: List<String>,
    parsed: ParsedArchivePackageIdentity,
): ArchiveBundleVerification {
    if (installSet.isEmpty() || parsed.apkCount != installSet.size) {
        return ArchiveBundleVerification.Refused
    }
    if (installSet.map { it.lowercase() }.toSet().size != installSet.size) {
        return ArchiveBundleVerification.Refused
    }
    if (parsed.packageName != header.packageName || parsed.versionCode != header.versionCode) {
        return ArchiveBundleVerification.Refused
    }
    if (parsed.signerSha256.size != 1 ||
        parsed.signerSha256.single().uppercase() != header.signerSha256.uppercase()
    ) {
        return ArchiveBundleVerification.Refused
    }
    return ArchiveBundleVerification.Verified(installSet.toList())
}

/** The immutable install set which was verified, or a detail-free refusal. */
sealed interface ArchiveBundleVerification {
    data class Verified(val installSet: List<String>) : ArchiveBundleVerification
    data object Refused : ArchiveBundleVerification
}

/** Verifies every APK which the installer will consume before installation starts. */
interface ArchiveBundleVerifier {
    suspend fun verify(bundle: File, header: ArchiveHeader): ArchiveBundleVerification
}

/** Pure fail-closed identity policy shared by the Android verifier and JVM tests. */
fun validateArchiveBundle(
    header: ArchiveHeader,
    installSet: List<String>,
    identities: List<ArchiveApkIdentity>,
): ArchiveBundleVerification {
    if (installSet.isEmpty() || identities.size != installSet.size) {
        return ArchiveBundleVerification.Refused
    }
    if (installSet.map { it.lowercase() }.toSet().size != installSet.size) {
        return ArchiveBundleVerification.Refused
    }
    if (identities.map { it.entryName.lowercase() } != installSet.map { it.lowercase() }) {
        return ArchiveBundleVerification.Refused
    }
    if (identities.count { it.isBase } != 1) return ArchiveBundleVerification.Refused

    val expectedSigner = header.signerSha256.uppercase()
    val valid = identities.all { identity ->
        identity.packageName == header.packageName &&
            identity.signerSha256.size == 1 &&
            identity.signerSha256.single().uppercase() == expectedSigner
    }
    if (!valid) return ArchiveBundleVerification.Refused

    val base = identities.single { it.isBase }
    if (base.versionCode != header.versionCode) return ArchiveBundleVerification.Refused
    return ArchiveBundleVerification.Verified(installSet.toList())
}

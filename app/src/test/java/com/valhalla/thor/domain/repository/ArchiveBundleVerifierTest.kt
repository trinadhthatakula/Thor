// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveBundleVerifierTest {

    private val header = ArchiveHeader(
        createdAt = 1L,
        thorVersionCode = 1952,
        packageName = "com.example.app",
        versionCode = 42L,
        userId = 0,
        signerSha256 = SIGNER,
        kdf = ArchiveKdf(iterations = 4, salt = "AA=="),
        verifier = "AA==",
    )
    private val plan = listOf("base.apk", "split_config.en.apk")
    private val valid = listOf(
        ArchiveApkIdentity("base.apk", "com.example.app", 42L, setOf(SIGNER), isBase = true),
        ArchiveApkIdentity(
            "split_config.en.apk",
            "com.example.app",
            42L,
            setOf(SIGNER),
            isBase = false,
        ),
    )

    @Test
    fun `every APK in one complete plan is accepted`() {
        assertEquals(
            ArchiveBundleVerification.Verified(plan),
            validateArchiveBundle(header, plan, valid),
        )
    }

    @Test
    fun `missing and stale install plans fail closed`() {
        val cases = listOf(
            emptyList<ArchiveApkIdentity>(),
            valid.dropLast(1),
            valid + valid.last().copy(entryName = "unselected.apk"),
        )

        cases.forEach { identities ->
            assertEquals(
                ArchiveBundleVerification.Refused,
                validateArchiveBundle(header, plan, identities),
            )
        }
    }

    @Test
    fun `foreign package or signer in any split fails closed`() {
        val cases = listOf(
            valid.mapIndexed { index, value ->
                if (index == 1) value.copy(packageName = "com.attacker.app") else value
            },
            valid.mapIndexed { index, value ->
                if (index == 1) value.copy(signerSha256 = setOf(OTHER_SIGNER)) else value
            },
            valid.map { it.copy(signerSha256 = emptySet()) },
        )

        cases.forEach { identities ->
            assertEquals(
                ArchiveBundleVerification.Refused,
                validateArchiveBundle(header, plan, identities),
            )
        }
    }

    @Test
    fun `multi signer APK fails closed`() {
        val identities = valid.map { it.copy(signerSha256 = setOf(SIGNER, OTHER_SIGNER)) }

        assertEquals(
            ArchiveBundleVerification.Refused,
            validateArchiveBundle(header, plan, identities),
        )
    }

    @Test
    fun `base version mismatch and missing or duplicate base fail closed`() {
        val cases = listOf(
            valid.mapIndexed { index, value -> if (index == 0) value.copy(versionCode = 43L) else value },
            valid.map { it.copy(isBase = false) },
            valid.map { it.copy(isBase = true) },
        )

        cases.forEach { identities ->
            assertEquals(
                ArchiveBundleVerification.Refused,
                validateArchiveBundle(header, plan, identities),
            )
        }
    }

    @Test
    fun `parsed package must account for the complete install plan`() {
        val parsed = ParsedArchivePackageIdentity(
            packageName = "com.example.app",
            versionCode = 42L,
            signerSha256 = setOf(SIGNER),
            apkCount = 1,
        )

        assertEquals(
            ArchiveBundleVerification.Refused,
            validateParsedArchiveBundle(header, plan, parsed),
        )
    }

    @Test
    fun `cluster identity is valid even when PackageManager does not expose archive source paths`() {
        val reordered = listOf("split_config.en.apk", "base.apk")
        val parsed = ParsedArchivePackageIdentity(
            packageName = "com.example.app",
            versionCode = 42L,
            signerSha256 = setOf(SIGNER),
            apkCount = 2,
        )

        assertEquals(
            ArchiveBundleVerification.Verified(reordered),
            validateParsedArchiveBundle(header, reordered, parsed),
        )
    }

    private companion object {
        const val SIGNER = "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB"
        const val OTHER_SIGNER = "CDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCDCD"
    }
}

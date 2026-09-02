// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.ARCHIVE_SCHEMA_VERSION
import com.valhalla.thor.domain.model.ArchiveAuthentication
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveMember
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveManifestCodecTest {

    private fun b64(size: Int, value: Byte = 1): String =
        Base64.getEncoder().encodeToString(ByteArray(size) { value })

    private fun header() = ArchiveHeader(
        createdAt = 1_770_000_000_000L,
        thorVersionCode = 1952,
        packageName = "com.example.game",
        versionCode = 42,
        versionName = "4.2",
        userId = 10,
        signerSha256 = "AB".repeat(32),
        appBundle = ArchiveBundleInfo(
            bytes = 4_096,
            sha256 = "CD".repeat(32),
            obbCapture = "present",
            obbCount = 2,
        ),
        kdf = ArchiveKdf(iterations = 1_000, salt = b64(16, 2)),
        verifier = b64(16, 3),
        authentication = ArchiveAuthentication(
            algorithm = MANIFEST_AUTH_ALGORITHM,
            mac = b64(MANIFEST_MAC_BYTES, 4),
        ),
        members = listOf(
            ArchiveMember(
                dataClass = DataClass.CE.id,
                fileName = "ce.tar.gz.enc",
                nonce = b64(MEMBER_NONCE_BYTES, 5),
                plainBytes = 123,
                cipherBytes = 143,
                chunkCount = 1,
                compression = ArchiveCompression.GZIP.id,
            ),
        ),
        skippedEntries = listOf(ArchiveSkip(DataClass.DE.id, "cache", "volatile")),
        warnings = listOf("partial tar"),
    )

    @Test
    fun `canonical manifest binds every restore semantic but excludes stored mac`() {
        val original = header()
        val canonical = ArchiveManifestCodec.canonicalBytes(original)
        val mutations = listOf(
            original.copy(schemaVersion = ARCHIVE_SCHEMA_VERSION + 1),
            original.copy(createdAt = original.createdAt + 1),
            original.copy(thorVersionCode = original.thorVersionCode + 1),
            original.copy(packageName = "com.example.other"),
            original.copy(versionCode = original.versionCode + 1),
            original.copy(versionName = null),
            original.copy(userId = original.userId + 1),
            original.copy(signerSha256 = "EF".repeat(32)),
            original.copy(appBundle = original.appBundle!!.copy(fileName = "other.xapk")),
            original.copy(appBundle = original.appBundle!!.copy(bytes = 4_097)),
            original.copy(appBundle = original.appBundle!!.copy(sha256 = "01".repeat(32))),
            original.copy(appBundle = original.appBundle!!.copy(obbCapture = "none")),
            original.copy(appBundle = original.appBundle!!.copy(obbCount = 3)),
            original.copy(kdf = original.kdf.copy(algorithm = "other")),
            original.copy(kdf = original.kdf.copy(iterations = 1_001)),
            original.copy(kdf = original.kdf.copy(salt = b64(16, 6))),
            original.copy(verifier = b64(16, 7)),
            original.copy(members = original.members.map { it.copy(dataClass = DataClass.DE.id) }),
            original.copy(members = original.members.map { it.copy(fileName = "de.tar.gz.enc") }),
            original.copy(members = original.members.map { it.copy(nonce = b64(MEMBER_NONCE_BYTES, 8)) }),
            original.copy(members = original.members.map { it.copy(plainBytes = 124) }),
            original.copy(members = original.members.map { it.copy(cipherBytes = 144) }),
            original.copy(members = original.members.map { it.copy(chunkCount = 2) }),
            original.copy(members = original.members.map { it.copy(compression = ArchiveCompression.NONE.id) }),
            original.copy(skippedEntries = listOf(ArchiveSkip(DataClass.DE.id, "code_cache", "volatile"))),
            original.copy(warnings = listOf("different warning")),
            original.copy(authentication = original.authentication!!.copy(algorithm = "other")),
        )

        mutations.forEach { mutation ->
            val mutated = runCatching { ArchiveManifestCodec.canonicalBytes(mutation) }.getOrNull()
            assertFalse(
                "mutation was neither bound nor structurally refused: $mutation",
                mutated?.contentEquals(canonical) == true,
            )
        }
        assertArrayEquals(
            canonical,
            ArchiveManifestCodec.canonicalBytes(
                original.copy(authentication = original.authentication!!.copy(mac = b64(MANIFEST_MAC_BYTES, 9)))
            ),
        )
    }

    @Test
    fun `canonical bytes ignore JSON property order and collection input order`() {
        val original = header().copy(
            members = listOf(
                header().members.single().copy(dataClass = DataClass.DE.id, fileName = "de.tar.enc"),
                header().members.single(),
            ),
            skippedEntries = listOf(
                ArchiveSkip(DataClass.CE.id, "z", "second"),
                ArchiveSkip(DataClass.CE.id, "a", "first"),
            ),
            warnings = listOf("z", "a"),
        )
        val reordered = original.copy(
            members = original.members.reversed(),
            skippedEntries = original.skippedEntries.reversed(),
            warnings = original.warnings.reversed(),
        )

        assertArrayEquals(
            ArchiveManifestCodec.canonicalBytes(original),
            ArchiveManifestCodec.canonicalBytes(ArchiveHeader.decode(reordered.encode())),
        )
    }

    @Test
    fun `manifest MAC validates only the authenticated header and key`() {
        val cipher = AppArchiveCipher()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val original = header()
        val mac = cipher.manifestMac(key, original)
        val signed = original.copy(
            authentication = original.authentication!!.copy(mac = Base64.getEncoder().encodeToString(mac))
        )

        assertTrue(cipher.verifyManifest(key, signed))
        assertFalse(cipher.verifyManifest(key, signed.copy(packageName = "com.example.other")))
        assertFalse(cipher.verifyManifest(SecretKeySpec(ByteArray(32) { 9 }, "AES"), signed))
        assertFalse(
            cipher.verifyManifest(
                key,
                signed.copy(authentication = signed.authentication!!.copy(mac = b64(MANIFEST_MAC_BYTES, 9))),
            )
        )
        assertFalse(
            cipher.verifyManifest(
                key,
                signed.copy(authentication = signed.authentication!!.copy(mac = b64(MANIFEST_MAC_BYTES - 1))),
            )
        )
        assertFalse(
            cipher.verifyManifest(
                key,
                signed.copy(authentication = signed.authentication!!.copy(algorithm = "HmacSHA1")),
            )
        )
    }

    @Test
    fun `invalid v2 structures are refused before canonical allocation`() {
        val original = header()
        val invalid = listOf(
            original.copy(schemaVersion = 1),
            original.copy(authentication = null),
            original.copy(authentication = original.authentication!!.copy(mac = null)),
            original.copy(authentication = original.authentication!!.copy(mac = b64(31))),
            original.copy(authentication = original.authentication!!.copy(algorithm = "HmacSHA1")),
            original.copy(signerSha256 = "not-a-digest"),
            original.copy(appBundle = original.appBundle!!.copy(fileName = "other.xapk")),
            original.copy(appBundle = original.appBundle!!.copy(bytes = -1)),
            original.copy(appBundle = original.appBundle!!.copy(sha256 = null)),
            original.copy(members = original.members + original.members.single().copy(fileName = "other.enc")),
            original.copy(members = original.members + original.members.single().copy(dataClass = DataClass.DE.id)),
            original.copy(members = original.members.map { it.copy(nonce = b64(MEMBER_NONCE_BYTES - 1)) }),
            original.copy(members = original.members.map { it.copy(plainBytes = -1) }),
            original.copy(members = original.members.map { it.copy(cipherBytes = -1) }),
            original.copy(members = original.members.map { it.copy(chunkCount = 0) }),
            original.copy(members = original.members.map { it.copy(compression = "brotli") }),
        )

        invalid.forEach { value ->
            assertThrows(ArchiveIntegrityException::class.java) {
                ArchiveManifestCodec.canonicalBytes(value)
            }
        }
    }
}

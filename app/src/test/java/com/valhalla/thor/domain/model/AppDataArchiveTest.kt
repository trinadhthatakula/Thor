// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The format is the one part of this feature a *future* Thor has to agree with, so the tests here
 * are about what a foreign reader sees: the schema version is on the wire even at its default, an
 * unknown field does not kill a v1 reader, and `Undetermined` is never a number.
 */
class AppDataArchiveTest {

    private fun header() = ArchiveHeader(
        createdAt = 1_770_000_000_000L,
        thorVersionCode = 1950,
        packageName = "com.example.game",
        versionCode = 12L,
        versionName = "1.2",
        userId = 0,
        signerSha256 = "AB".repeat(32),
        appBundle = ArchiveBundleInfo(
            fileName = THORBAK_BUNDLE_ENTRY,
            bytes = 4096L,
            // The lowercase ids `captureName()` produces (Task 15), matching how `DataClass.id` and
            // `ArchiveCompression.id` are spelled in this same format.
            obbCapture = "present",
            obbCount = 2,
        ),
        kdf = ArchiveKdf(iterations = 210_000, salt = "c2FsdA=="),
        verifier = "dmVyaWZpZXI=",
        members = listOf(
            ArchiveMember(
                dataClass = DataClass.CE.id,
                fileName = "ce.tar.gz.enc",
                nonce = "bm9uY2U=",
                plainBytes = 2048L,
                chunkCount = 1,
                compression = ArchiveCompression.GZIP.id,
            )
        ),
    )

    @Test
    fun `schemaVersion is written even at its default`() {
        // The one field a foreign reader must see to know how to parse the rest. BackupIndex sets
        // encodeDefaults for exactly this reason.
        assertTrue(header().encode().contains("\"schemaVersion\": $ARCHIVE_SCHEMA_VERSION"))
    }

    @Test
    fun `a v1 reader survives a v2 document carrying unknown fields`() {
        val v2 = header().encode().replaceFirst(
            "\"schemaVersion\": $ARCHIVE_SCHEMA_VERSION,",
            "\"schemaVersion\": 2,\n  \"cloudDestination\": \"s3://nope\","
        )

        val decoded = ArchiveHeader.decode(v2)

        assertEquals(2, decoded.schemaVersion)
        assertEquals("com.example.game", decoded.packageName)
    }

    @Test
    fun `a round trip preserves every field`() {
        assertEquals(header(), ArchiveHeader.decode(header().encode()))
    }

    @Test
    fun `members are looked up by the header's own file name, never by guessing`() {
        val decoded = ArchiveHeader.decode(header().encode())

        assertNotNull(decoded.member(DataClass.CE))
        assertEquals("ce.tar.gz.enc", decoded.member(DataClass.CE)!!.fileName)
        assertEquals(null, decoded.member(DataClass.EXTERNAL_MEDIA))
    }

    @Test
    fun `an uncompressed member is named for what it actually is`() {
        // The name is derived, not fixed, so a member whose gzip attempt failed is not called
        // `.tar.gz.enc`. Readers use members[].fileName; this only keeps the name honest.
        assertEquals("ce.tar.gz.enc", DataClass.CE.memberName(compressed = true))
        assertEquals("ce.tar.enc", DataClass.CE.memberName(compressed = false))
    }

    @Test
    fun `Undetermined never renders as a size`() {
        // A size we could not measure, shown as `0 B`, is how a user deselects data they actually
        // have. Same discipline as ObbProbe.Undetermined.
        assertEquals(SizeLabelKind.Unknown, DataClassSize.Undetermined.labelKind())
        assertEquals(SizeLabelKind.Empty, DataClassSize.Empty.labelKind())
        assertEquals(SizeLabelKind.Bytes(4096L), DataClassSize.Known(4096L).labelKind())
    }

    @Test
    fun `the container name identifies the app and the version it came from`() {
        assertEquals("com.example.game-12.thorbak", thorbakFileName("com.example.game", 12L))
    }

    @Test
    fun `every data class has a distinct id and a distinct member name`() {
        val ids = DataClass.entries.map { it.id }
        val names = DataClass.entries.map { it.memberName(compressed = true) }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(names.size, names.toSet().size)
    }
}

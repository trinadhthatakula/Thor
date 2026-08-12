// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.1's table, row by row. Every row gets a named test, so a change to the table is a change to a
 * test name rather than a silently relaxed check.
 *
 * **Warnings are asserted as a whole list, never with `in` or a single `assertFalse`.** The default
 * [header] holds CE *and* DE, so a test that selects CE alone gets `CE_WITHOUT_DE` whether it wants
 * it or not: `assertFalse(INSTALLED_VERSION_OLDER in warnings)` under a test called *"proceeds
 * quietly"* passed over a decision carrying a warning, and would have gone on passing if the rule
 * under test had started warning about something else entirely. Selections here are chosen so the
 * expected list is exactly what the row is about.
 */
class ArchiveRestoreGateTest {

    private val signer = "AB".repeat(32)

    private fun header(
        versionCode: Long = 100L,
        signerSha256: String = signer,
        withBundle: Boolean = true,
        classes: List<DataClass> = listOf(DataClass.CE, DataClass.DE),
        packageName: String = "com.example.app",
        userId: Int = 0,
    ) = ArchiveHeader(
        createdAt = 1_000L,
        thorVersionCode = 1950,
        packageName = packageName,
        versionCode = versionCode,
        userId = userId,
        signerSha256 = signerSha256,
        appBundle = if (withBundle) ArchiveBundleInfo(bytes = 10L, obbCapture = "none", obbCount = 0) else null,
        kdf = ArchiveKdf(iterations = 210_000, salt = "c2FsdHNhbHRzYWx0c2E="),
        verifier = "dmVyaWZpZXI=",
        members = classes.map { dataClass ->
            ArchiveMember(
                dataClass = dataClass.id,
                fileName = dataClass.memberName(compressed = true),
                nonce = "bm9uY2U=",
                plainBytes = 10L,
                chunkCount = 1,
                compression = ArchiveCompression.GZIP.id,
            )
        },
    )

    private fun installed(
        versionCode: Long = 100L,
        signerSha256: String? = signer,
    ) = InstalledAppFacts(signerSha256 = signerSha256, versionCode = versionCode, versionName = "1.0")

    @Test
    fun `an absent app with a bundle installs first, and that is not a refusal`() {
        val decision = evaluateArchiveRestoreGate(header(), installed = null, setOf(DataClass.CE, DataClass.DE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertTrue(allowed.installFirst)
    }

    @Test
    fun `an absent app with no bundle is refused, and the reason says the archive is data-only`() {
        val decision = evaluateArchiveRestoreGate(
            header(withBundle = false),
            installed = null,
            setOf(DataClass.CE, DataClass.DE),
        )

        assertEquals(
            ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `a signer mismatch is refused with no override`() {
        // The one absolute refusal. Without it, sideloading a fake `com.whatsapp` and restoring into it
        // reads out everything the real one held.
        val decision = evaluateArchiveRestoreGate(
            header(),
            installed(signerSha256 = "CD".repeat(32)),
            setOf(DataClass.CE),
        )

        assertEquals(
            ArchiveRestoreRefusal.SIGNER_MISMATCH,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `a signer that could not be read is refused, not waved through`() {
        // "I could not check" is not "it matches". This is the tri-state discipline applied to the one
        // check that exists to stop data exfiltration.
        val decision = evaluateArchiveRestoreGate(header(), installed(signerSha256 = null), setOf(DataClass.CE))

        assertEquals(
            ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `signer comparison ignores hex case`() {
        // Both sides are produced by Thor today, but a header written by a future build — or read from
        // a file a user edited — must not fail on casing and be reported as an attack.
        val decision = evaluateArchiveRestoreGate(
            header(signerSha256 = signer.lowercase()),
            installed(signerSha256 = signer),
            setOf(DataClass.CE),
        )

        assertTrue(decision.toString(), decision is ArchiveRestoreDecision.Allowed)
    }

    @Test
    fun `an installed version older than the archive warns hard but is allowed`() {
        // Newer data on older code is the classic permanent-crash-on-launch. The user is told, in those
        // words, and may proceed. Both classes selected, so the list is exactly this one row.
        val decision = evaluateArchiveRestoreGate(
            header(versionCode = 200L),
            installed(versionCode = 100L),
            setOf(DataClass.CE, DataClass.DE),
        )

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertEquals(listOf(ArchiveRestoreWarning.INSTALLED_VERSION_OLDER), allowed.warnings)
    }

    @Test
    fun `an installed version newer than the archive proceeds quietly`() {
        // Forward migration is what apps are built for. A warning here would train users to ignore
        // warnings. "Quietly" means the whole list is empty — this used to select CE alone, so the
        // decision it called quiet carried CE_WITHOUT_DE.
        val decision = evaluateArchiveRestoreGate(
            header(versionCode = 100L),
            installed(versionCode = 200L),
            setOf(DataClass.CE, DataClass.DE),
        )

        assertEquals(emptyList<ArchiveRestoreWarning>(), (decision as ArchiveRestoreDecision.Allowed).warnings)
    }

    @Test
    fun `an equal version proceeds quietly`() {
        // CE *and* DE: selecting CE alone from the default header trips CE_WITHOUT_DE, which has
        // nothing to do with the version row this test is about.
        val decision = evaluateArchiveRestoreGate(header(versionCode = 100L), installed(versionCode = 100L), setOf(DataClass.CE, DataClass.DE))

        assertEquals(emptyList<ArchiveRestoreWarning>(), (decision as ArchiveRestoreDecision.Allowed).warnings)
    }

    @Test
    fun `CE selected without DE warns, because DE carries first-run state`() {
        val decision = evaluateArchiveRestoreGate(header(), installed(), setOf(DataClass.CE))

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertEquals(listOf(ArchiveRestoreWarning.CE_WITHOUT_DE), allowed.warnings)
    }

    @Test
    fun `CE without DE does not warn when the archive holds no DE member`() {
        // The warning is about a *choice* the user made. An archive with nothing to select cannot be
        // faulted for the selection, and warning anyway is noise the user cannot act on.
        val decision = evaluateArchiveRestoreGate(
            header(classes = listOf(DataClass.CE)),
            installed(),
            setOf(DataClass.CE),
        )

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertEquals(emptyList<ArchiveRestoreWarning>(), allowed.warnings)
    }

    @Test
    fun `selecting a class the archive does not hold is refused`() {
        // Not silently dropped: the sheet built its checkboxes from `heldClasses()`, so a selection
        // outside that set means the caller and the header disagree about the file.
        val decision = evaluateArchiveRestoreGate(
            header(classes = listOf(DataClass.CE)),
            installed(),
            setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        )

        assertEquals(
            ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `selecting nothing is refused`() {
        val decision = evaluateArchiveRestoreGate(header(), installed(), emptySet())

        assertEquals(
            ArchiveRestoreRefusal.NOTHING_SELECTED,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `an archive from a newer schema is refused rather than half-read`() {
        val future = header().copy(schemaVersion = ARCHIVE_SCHEMA_VERSION + 1)

        val decision = evaluateArchiveRestoreGate(future, installed(), setOf(DataClass.CE))

        assertEquals(
            ArchiveRestoreRefusal.SCHEMA_TOO_NEW,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `an archive whose schema version is zero or negative is refused`() {
        // `schemaVersion` decides how every other field in the header is read, and it is untrusted
        // JSON. The field defaults to ARCHIVE_SCHEMA_VERSION and is encoded even at its default, so
        // no Thor ever wrote a 0 or a -1 — it is corruption or a crafted header, and "older than
        // version 1" is not a thing this reader can fall back to. Fails closed, both values.
        listOf(0, -1, Int.MIN_VALUE).forEach { version ->
            val decision = evaluateArchiveRestoreGate(
                header().copy(schemaVersion = version),
                installed(),
                setOf(DataClass.CE, DataClass.DE),
            )

            assertEquals(
                "schemaVersion=$version",
                ArchiveRestoreRefusal.INVALID_SCHEMA_VERSION,
                (decision as ArchiveRestoreDecision.Refused).reason,
            )
        }
    }

    @Test
    fun `the current schema version is read, not refused`() {
        // The other side of the two-ended range, so neither bound can be widened unnoticed.
        val decision = evaluateArchiveRestoreGate(
            header().copy(schemaVersion = ARCHIVE_SCHEMA_VERSION),
            installed(),
            setOf(DataClass.CE, DataClass.DE),
        )

        assertTrue(decision.toString(), decision is ArchiveRestoreDecision.Allowed)
    }

    @Test
    fun `an absent app is not signer-checked, because there is no signer yet`() {
        // Order matters: checking the signer first would refuse every install-then-restore, which §8.1
        // explicitly calls "not a refusal". The install path re-checks after the install lands.
        //
        // The header's signer is deliberately *not* the installed one used everywhere else in this
        // file: with a matching signer this test asserted nothing the first test in the file does not
        // already assert, and would stay green with the signer rows moved above the absent-app row.
        val decision = evaluateArchiveRestoreGate(
            header(signerSha256 = "CD".repeat(32)),
            installed = null,
            setOf(DataClass.CE, DataClass.DE),
        )

        val allowed = decision as ArchiveRestoreDecision.Allowed
        assertTrue(allowed.installFirst)
    }

    @Test
    fun `a malformed package name in the header is refused`() {
        // header.packageName is untrusted JSON and becomes a filesystem path in dataClassRoot().
        // The gate is the security boundary; it validates rather than inheriting from callers.
        val decision = evaluateArchiveRestoreGate(
            header(packageName = "not..valid"),
            installed(),
            setOf(DataClass.CE, DataClass.DE),
        )

        assertEquals(
            ArchiveRestoreRefusal.INVALID_PACKAGE_NAME,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }

    @Test
    fun `a negative user id in the header is refused`() {
        // header.userId is untrusted JSON and becomes a filesystem path component. A negative value
        // cannot appear in a valid data directory path and cannot be sanitised.
        val decision = evaluateArchiveRestoreGate(
            header(userId = -1),
            installed(),
            setOf(DataClass.CE, DataClass.DE),
        )

        assertEquals(
            ArchiveRestoreRefusal.INVALID_USER_ID,
            (decision as ArchiveRestoreDecision.Refused).reason,
        )
    }
}

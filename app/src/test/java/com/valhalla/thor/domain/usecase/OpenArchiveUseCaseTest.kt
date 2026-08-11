// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.KDF_ALGORITHM
import com.valhalla.thor.data.backup.VERIFIER_BYTES
import com.valhalla.thor.domain.model.ARCHIVE_SCHEMA_VERSION
import com.valhalla.thor.domain.model.ArchiveBundleInfo
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class OpenArchiveUseCaseTest {

    private val cipher = AppArchiveCipher()

    // `UnconfinedTestDispatcher` runs eagerly in the same thread — appropriate for a use case whose
    // I/O and KDF are synchronous blocking operations with no delay-based concurrency to observe.
    private val useCase = OpenArchiveUseCase(cipher, UnconfinedTestDispatcher())
    private val salt = ByteArray(16) { it.toByte() }

    /** In-memory [ArchiveSource]. The port is `File`/`String`-only precisely so this is four lines. */
    private class FakeSource(private val entries: Map<String, ByteArray>) : ArchiveSource {
        override val displayName = "fake.thorbak"
        override fun entryNames() = entries.keys.toList()
        override fun openEntry(name: String): InputStream? = entries[name]?.let(::ByteArrayInputStream)
        override fun close() = Unit
    }

    private fun header(iterations: Int = 4, passphrase: String = "correct horse"): ArchiveHeader {
        val key = cipher.deriveKey(passphrase.toCharArray(), salt, iterations)
        return ArchiveHeader(
            createdAt = 1_000L,
            thorVersionCode = 1950,
            packageName = "com.example.app",
            versionCode = 100L,
            userId = 0,
            signerSha256 = "AB".repeat(32),
            appBundle = ArchiveBundleInfo(bytes = 4L, obbCapture = "none", obbCount = 0),
            kdf = ArchiveKdf(iterations = iterations, salt = Base64.getEncoder().encodeToString(salt)),
            verifier = Base64.getEncoder().encodeToString(cipher.verifier(key)),
            members = emptyList(),
        )
    }

    private fun sourceFor(header: ArchiveHeader) =
        FakeSource(mapOf(THORBAK_HEADER_ENTRY to header.encode().toByteArray()))

    @Test
    fun `a well-formed container yields its header`() = runTest {
        val expected = header()

        val outcome = useCase.readHeader(sourceFor(expected))

        assertEquals(expected, (outcome as ArchiveHeaderOutcome.Read).header)
    }

    @Test
    fun `a container with no header entry is not an archive`() = runTest {
        val outcome = useCase.readHeader(FakeSource(mapOf("app.xapk" to byteArrayOf(1))))

        val reason = (outcome as ArchiveHeaderOutcome.NotAnArchive).reason
        // The message names the entry, because "not a Thor backup" on a file the user believes is one
        // is the moment they need to know what Thor looked for.
        assertTrue(reason, reason.contains(THORBAK_HEADER_ENTRY))
    }

    @Test
    fun `a header that is not valid JSON is not an archive`() = runTest {
        val outcome = useCase.readHeader(FakeSource(mapOf(THORBAK_HEADER_ENTRY to "{ nope".toByteArray())))

        assertTrue(outcome.toString(), outcome is ArchiveHeaderOutcome.NotAnArchive)
    }

    @Test
    fun `a header entry that exceeds MAX_HEADER_BYTES is refused`() = runTest {
        // A deflate-bomb thorbak.json expands to gigabytes; the ceiling turns that into a small,
        // bounded allocation that maps to NotAnArchive rather than OOM.
        val oversized = ByteArray(MAX_HEADER_BYTES + 1) { 'x'.code.toByte() }
        val outcome = useCase.readHeader(FakeSource(mapOf(THORBAK_HEADER_ENTRY to oversized)))

        val reason = (outcome as ArchiveHeaderOutcome.NotAnArchive).reason
        // The reason must mention the limit — if it were merely a parse failure (the behaviour
        // before readAtMost was introduced), the reason would mention the JSON error, not the size.
        // This distinguishes the deflate-bomb defence from ordinary corrupt-JSON handling.
        assertTrue(reason, reason.contains("exceeds") && reason.contains(MAX_HEADER_BYTES.toString()))
    }

    @Test
    fun `the header is read without a passphrase`() = runTest {
        // §8.1: the restore screen shows package, version, date and classes held *before* it asks for
        // anything. If reading the header needed the key, the gate could not run first.
        val outcome = useCase.readHeader(sourceFor(header()))

        assertEquals("com.example.app", (outcome as ArchiveHeaderOutcome.Read).header.packageName)
    }

    @Test
    fun `the right passphrase unlocks the archive`() = runTest {
        val outcome = useCase.unlock(header(), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unlocked)
    }

    @Test
    fun `a wrong passphrase is rejected after one derivation, before any member is read`() = runTest {
        val outcome = useCase.unlock(header(), "wrong horse".toCharArray())

        assertEquals(ArchiveUnlockOutcome.WrongPassphrase, outcome)
    }

    @Test
    fun `the key that comes back is the one the verifier matched`() = runTest {
        val head = header()

        val key = (useCase.unlock(head, "correct horse".toCharArray()) as ArchiveUnlockOutcome.Unlocked).key

        assertEquals(head.verifier, Base64.getEncoder().encodeToString(cipher.verifier(key)))
    }

    @Test
    fun `an absurd iteration count is refused instead of derived`() = runTest {
        // A header is attacker-controlled data. `iterations = 2_000_000_000` is a PBKDF2 call that
        // never returns; it runs on the IO dispatcher and hangs the restore worker indefinitely.
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(iterations = MAX_KDF_ITERATIONS + 1, salt = "AAAA")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a non-positive iteration count is refused`() = runTest {
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(iterations = 0, salt = "AAAA")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `an unrecognised KDF algorithm is refused as Unsupported`() = runTest {
        // An archive declaring PBKDF2WithHmacSHA512 would be silently derived with SHA-256 and the
        // correct passphrase would be reported as wrong. Detect it here and tell the user why.
        val badKdf = ArchiveKdf(
            algorithm = "PBKDF2WithHmacSHA512",
            iterations = 4,
            salt = Base64.getEncoder().encodeToString(salt),
        )

        val outcome = useCase.unlock(header().copy(kdf = badKdf), "correct horse".toCharArray())

        val unsupported = outcome as ArchiveUnlockOutcome.Unsupported
        assertTrue(unsupported.reason, unsupported.reason.contains("PBKDF2WithHmacSHA512"))
    }

    @Test
    fun `the known KDF algorithm is accepted`() = runTest {
        // Regression pin: KDF_ALGORITHM must not be reported as unsupported.
        val goodKdf = ArchiveKdf(
            algorithm = KDF_ALGORITHM,
            iterations = 4,
            salt = Base64.getEncoder().encodeToString(salt),
        )

        val outcome = useCase.unlock(header().copy(kdf = goodKdf), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unlocked)
    }

    @Test
    fun `a salt that is not base64 is refused rather than throwing`() = runTest {
        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(iterations = 4, salt = "not base64 !!")), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a salt of the wrong length is refused rather than passed to deriveKey`() = runTest {
        // `deriveKey` has a `require` on the salt length, and an IllegalArgumentException escaping a
        // worker is a crash, not a message.
        val short = Base64.getEncoder().encodeToString(ByteArray(8))

        val outcome = useCase.unlock(header().copy(kdf = ArchiveKdf(iterations = 4, salt = short)), "x".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a verifier that is not base64 is refused rather than throwing`() = runTest {
        val outcome = useCase.unlock(header().copy(verifier = "not base64 !!"), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `a verifier of the wrong length is refused as Unsupported, not WrongPassphrase`() = runTest {
        // A truncated-but-valid-Base64 verifier makes the user retry a correct passphrase forever.
        // Catch it as a corrupt header (Unsupported) rather than silently returning WrongPassphrase.
        val shortVerifier = Base64.getEncoder().encodeToString(ByteArray(VERIFIER_BYTES - 1))

        val outcome = useCase.unlock(header().copy(verifier = shortVerifier), "correct horse".toCharArray())

        assertTrue(outcome.toString(), outcome is ArchiveUnlockOutcome.Unsupported)
    }

    @Test
    fun `unlock does not clear the caller's passphrase`() = runTest {
        // The vault owns that array and may still need it to `remember` the passphrase on success.
        // `deriveKey`'s KDoc says the caller clears it; this pins that unlock did not.
        val passphrase = "correct horse".toCharArray()

        useCase.unlock(header(), passphrase)

        assertEquals("correct horse", String(passphrase))
    }

    @Test
    fun `readHeader and unlock hand their work to the injected io dispatcher`() = runTest {
        // Every other test in this file uses `UnconfinedTestDispatcher`, which runs the
        // `withContext` body inline on the calling thread — so all of them stay green if
        // `withContext(ioDispatcher)` is deleted, and even a thread-identity assertion would be
        // vacuous under it. This test counts dispatches instead: a dispatcher that is not the
        // caller's own is asked to dispatch only if the production code really moved the work onto
        // it. Delete `withContext(ioDispatcher)` from either function and its count stays at zero.
        //
        // (A second `TestCoroutineScheduler` is not an option — `runTest` rejects it outright with
        // "Detected use of different schedulers".)
        val io = CountingDispatcher()
        val dispatching = OpenArchiveUseCase(cipher, io)

        dispatching.readHeader(sourceFor(header()))
        val afterReadHeader = io.dispatches
        dispatching.unlock(header(), "correct horse".toCharArray())

        assertTrue("readHeader must dispatch onto the io dispatcher", afterReadHeader > 0)
        assertTrue("unlock must dispatch onto the io dispatcher", io.dispatches > afterReadHeader)
    }

    /**
     * Runs the block inline — the work still happens on the test thread — but records that it was
     * asked to. The recording, not the threading, is what pins `withContext(ioDispatcher)`.
     */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            block.run()
        }
    }

    @Test
    fun `the schema version travels on the header for the gate to check`() = runTest {
        // The gate (Task 11) refuses a newer schema. It can only do that if the read does not.
        val future = header().copy(schemaVersion = ARCHIVE_SCHEMA_VERSION + 1)

        val outcome = useCase.readHeader(sourceFor(future))

        assertEquals(
            ARCHIVE_SCHEMA_VERSION + 1,
            (outcome as ArchiveHeaderOutcome.Read).header.schemaVersion,
        )
    }
}

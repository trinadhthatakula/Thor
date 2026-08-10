// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CipherInputStream` returns -1 instead of throwing on `AEADBadTagException`, so a tampered archive
 * decrypts to a silently short plaintext and restore writes a partial database over a real one.
 * Every test below that expects [ArchiveIntegrityException] exists because of that: the framing is
 * only worth having if the failures are loud.
 */
class AppArchiveCipherTest {

    private val cipher = AppArchiveCipher()
    private val member = "ce.tar.gz.enc"
    private val nonce = ByteArray(MEMBER_NONCE_BYTES) { it.toByte() }

    // 1,000 rather than the production 210,000: a test suite that derives real keys spends minutes
    // in PBKDF2. `the production iteration count` below is what pins the shipped value.
    private fun key(passphrase: String = "correct horse"): SecretKey =
        cipher.deriveKey(passphrase.toCharArray(), ByteArray(KDF_SALT_BYTES) { 7 }, iterations = 1_000)

    private fun encrypt(plain: ByteArray, name: String = member, k: SecretKey = key()): Pair<ByteArray, MemberStats> {
        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember(name, ByteArrayInputStream(plain), out, k, nonce)
        return out.toByteArray() to stats
    }

    private fun decrypt(
        bytes: ByteArray,
        chunkCount: Int,
        name: String = member,
        k: SecretKey = key(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decryptMember(name, ByteArrayInputStream(bytes), out, k, nonce, chunkCount)
        return out.toByteArray()
    }

    @Test
    fun `a multi-chunk member round trips byte for byte`() {
        val plain = ByteArray(CHUNK_PLAINTEXT_BYTES * 2 + 12_345) { (it % 251).toByte() }

        val (bytes, stats) = encrypt(plain)

        assertEquals(3, stats.chunkCount)
        assertEquals(plain.size.toLong(), stats.plainBytes)
        // cipherBytes must account for every length-prefix byte and every tag byte; a missing or
        // wrong prefix (e.g. wrong endianness) would ship a header that the next reader cannot parse.
        assertEquals(bytes.size.toLong(), stats.cipherBytes)

        val out = ByteArrayOutputStream()
        val written = cipher.decryptMember(member, ByteArrayInputStream(bytes), out, key(), nonce, stats.chunkCount)
        assertArrayEquals(plain, out.toByteArray())
        assertEquals(plain.size.toLong(), written)
    }

    @Test
    fun `a payload of exactly one chunk is one chunk`() {
        val (_, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES))

        assertEquals(1, stats.chunkCount)
    }

    @Test
    fun `one byte past a chunk boundary is two chunks`() {
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES + 1) { 9 })

        assertEquals(2, stats.chunkCount)
        assertEquals(CHUNK_PLAINTEXT_BYTES + 1, decrypt(bytes, stats.chunkCount).size)
    }

    @Test
    fun `one byte short of a chunk boundary is one chunk`() {
        val (_, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES - 1))

        assertEquals(1, stats.chunkCount)
    }

    @Test
    fun `an empty member is one authenticated empty chunk, never zero chunks`() {
        // Zero chunks would make `chunkCount` unable to distinguish "nothing was written" from
        // "everything was truncated", which is the check the whole format leans on.
        val (bytes, stats) = encrypt(ByteArray(0))

        assertEquals(1, stats.chunkCount)
        assertEquals(0L, stats.plainBytes)
        assertEquals(0, decrypt(bytes, stats.chunkCount).size)
    }

    @Test
    fun `a flipped ciphertext byte is detected`() {
        val (bytes, stats) = encrypt(ByteArray(4096) { 3 })
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(bytes, stats.chunkCount) }
    }

    @Test
    fun `a stream that ends a chunk early is detected`() {
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES + 500) { 1 })

        // Arithmetic: frame0 = 4+1048576+16 = 1048596 B, frame1 = 4+500+16 = 520 B.
        // Removing exactly 520 bytes strips frame1 cleanly, leaving frame0 intact. Every intact
        // frame authenticates — only `chunkCount` detects this truncation, so `frameLength`'s
        // "ended before chunk N" branch is the one that must fire.
        val cut = bytes.copyOf(bytes.size - 520)

        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(cut, stats.chunkCount) }
        assertTrue(ex.message?.contains("ended before chunk 1") == true)
    }

    @Test
    fun `a stream truncated inside a frame is detected`() {
        val (bytes, stats) = encrypt(ByteArray(2048) { 5 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes.copyOf(bytes.size - 4), stats.chunkCount)
        }
    }

    @Test
    fun `a stream carrying more chunks than the header declares is detected`() {
        val (bytes, stats) = encrypt(ByteArray(64) { 2 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes + bytes, stats.chunkCount)
        }
    }

    @Test
    fun `a chunk replayed at the wrong index is detected`() {
        // The IV's chunk index and the AAD's chunk index both change, so a frame moved from one
        // position to another fails to authenticate at its new position.
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES * 2) { 4 })
        val frameLength = 4 + CHUNK_PLAINTEXT_BYTES + 16
        val first = bytes.copyOfRange(0, frameLength)
        val second = bytes.copyOfRange(frameLength, frameLength * 2)
        val swapped = second + first + bytes.copyOfRange(frameLength * 2, bytes.size)

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(swapped, stats.chunkCount) }
    }

    @Test
    fun `a member decrypted under another member's name is detected`() {
        // The AAD binds the entry name, so `de.tar.gz.enc` cannot be presented as `ce.tar.gz.enc` —
        // a swap that would otherwise restore device-encrypted data into the CE directory.
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, name = "de.tar.gz.enc")
        }
    }

    @Test
    fun `a wrong passphrase is detected`() {
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, k = key("wrong horse"))
        }
    }

    @Test
    fun `a declared chunk count of zero is refused`() {
        val (bytes, _) = encrypt(ByteArray(16))

        assertThrows(ArchiveIntegrityException::class.java) { decrypt(bytes, 0) }
    }

    @Test
    fun `the verifier rejects a wrong passphrase before a byte is streamed`() {
        val right = cipher.verifier(key("correct horse"))
        val wrong = cipher.verifier(key("wrong horse"))

        assertEquals(VERIFIER_BYTES, right.size)
        assertNotEquals(right.toList(), wrong.toList())
        // Stable: the same passphrase and salt must verify against an archive made yesterday.
        assertArrayEquals(right, cipher.verifier(key("correct horse")))
        // verify() uses constant-time MessageDigest.isEqual; callers must not reach for contentEquals.
        assertTrue(cipher.verify(key("correct horse"), right))
        assertFalse(cipher.verify(key("wrong horse"), right))
    }

    @Test
    fun `ArchivePassphraseException is-a ArchiveIntegrityException`() {
        // Callers that throw ArchivePassphraseException when verify() returns false stay compatible
        // with any catch-block that already handles the broader ArchiveIntegrityException.
        // assertThrows checks isInstance, which is true for subtypes.
        assertThrows(ArchiveIntegrityException::class.java) {
            throw ArchivePassphraseException("typed it wrong")
        }
    }

    @Test
    fun `the same passphrase under a different salt yields a different key`() {
        // One reused passphrase must not mean one reused key, which is the whole point of a
        // per-archive salt.
        val a = cipher.deriveKey("pass".toCharArray(), ByteArray(KDF_SALT_BYTES) { 1 }, 1_000)
        val b = cipher.deriveKey("pass".toCharArray(), ByteArray(KDF_SALT_BYTES) { 2 }, 1_000)

        assertNotEquals(a.encoded.toList(), b.encoded.toList())
    }

    @Test
    fun `every salt and nonce is fresh`() {
        assertNotEquals(cipher.newSalt().toList(), cipher.newSalt().toList())
        assertNotEquals(cipher.newNonce().toList(), cipher.newNonce().toList())
        assertEquals(KDF_SALT_BYTES, cipher.newSalt().size)
        assertEquals(MEMBER_NONCE_BYTES, cipher.newNonce().size)
    }

    @Test
    fun `the shipped parameters are the ones the spec fixed`() {
        // The tests above run at 1,000 iterations for speed; this is what pins production.
        assertEquals(210_000, KDF_ITERATIONS)
        assertEquals("PBKDF2WithHmacSHA256", KDF_ALGORITHM)
        assertEquals(16, KDF_SALT_BYTES)
        assertEquals(8, MEMBER_NONCE_BYTES)
        assertEquals(1024 * 1024, CHUNK_PLAINTEXT_BYTES)
        assertEquals(16, VERIFIER_BYTES)
        // 256-bit key.
        assertTrue(key().encoded.size == 32)
    }

    @Test
    fun `known-answer vector pins the frame layout and byte order`() {
        // Key: 32 sequential bytes (bypasses PBKDF2 — this is a layout pin, not a KDF test).
        // Nonce: [0..7], plaintext: [0..15], member: "kat", single chunk → isFinal=true.
        // AAD is "kat|0|1".toByteArray(UTF_8). IV is nonce || big-endian 0x00000000.
        //
        // Expected frame = 4-byte big-endian length (0x00000020 = 32) || ciphertext (16) || tag (16).
        // Verified by hand: first four bytes are 0x00 0x00 0x00 0x20 = big-endian 32 ✓,
        // total frame = 36 = 4 + 16 + 16 ✓.
        // Any change to endianness, AAD encoding, tag width, or IV construction breaks this.
        val katKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val katNonce = ByteArray(MEMBER_NONCE_BYTES) { it.toByte() }
        val katPlain = ByteArray(16) { it.toByte() }
        val expectedHex = "000000202905546776064d218ae4f69c629932ea3083733b5321915c935ddb74f04d89fd"

        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember("kat", ByteArrayInputStream(katPlain), out, katKey, katNonce)
        val frame = out.toByteArray()

        assertEquals("frame must be 4 + 16 + 16 = 36 bytes", 36, frame.size)
        // Prefix is big-endian 32 (= 0x20).
        assertEquals(0x00, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[1].toInt() and 0xFF)
        assertEquals(0x00, frame[2].toInt() and 0xFF)
        assertEquals(0x20, frame[3].toInt() and 0xFF)
        assertEquals(1, stats.chunkCount)
        // Full frame pin — catches any bilateral drift in layout, endianness, or AAD.
        assertEquals(expectedHex, frame.joinToString("") { "%02x".format(it) })

        // Confirm the KAT also decrypts cleanly.
        val decOut = ByteArrayOutputStream()
        val written = cipher.decryptMember("kat", ByteArrayInputStream(frame), decOut, katKey, katNonce, 1)
        assertArrayEquals(katPlain, decOut.toByteArray())
        assertEquals(katPlain.size.toLong(), written)
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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

    private fun encrypt(
        plain: ByteArray,
        name: String = member,
        dataClass: String = "ce",
        k: SecretKey = key(),
    ): Pair<ByteArray, MemberStats> {
        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember(dataClass, name, ByteArrayInputStream(plain), out, k, nonce)
        return out.toByteArray() to stats
    }

    private fun decrypt(
        bytes: ByteArray,
        chunkCount: Int,
        name: String = member,
        dataClass: String = "ce",
        k: SecretKey = key(),
        memberNonce: ByteArray = nonce,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.decryptMember(dataClass, name, ByteArrayInputStream(bytes), out, k, memberNonce, chunkCount)
        return out.toByteArray()
    }

    /**
     * The **message**, not just the type.
     *
     * Nearly everything `decryptMember` refuses is an [ArchiveIntegrityException], so the type alone
     * cannot say *which* check fired. Several of the tests below reach the same type through two
     * different guards, and it is the wording that distinguishes "this header is malformed" from
     * "this ciphertext is damaged" — and that notices when one of the two guards is deleted.
     */
    private fun assertMessageContains(ex: Throwable, needle: String) =
        assertTrue("message was \"${ex.message}\"", ex.message?.contains(needle) == true)

    /** A hand-built frame: 4-byte big-endian declared length, then whatever bytes follow it. */
    private fun frame(declaredLength: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            ((declaredLength ushr 24) and 0xFF).toByte(),
            ((declaredLength ushr 16) and 0xFF).toByte(),
            ((declaredLength ushr 8) and 0xFF).toByte(),
            (declaredLength and 0xFF).toByte(),
        ) + payload

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
        val written = cipher.decryptMember("ce", member, ByteArrayInputStream(bytes), out, key(), nonce, stats.chunkCount)
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

        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(bytes, stats.chunkCount) }

        // The GCM tag is what must refuse this. Four different faults in this class all throw
        // `ArchiveIntegrityException`, so a type-only assertion here passes just as happily when the
        // frame is refused for its declared length or for ending early — neither of which would say
        // anything about authentication.
        assertMessageContains(ex, "chunk 0 failed authentication")
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
    fun `a member truncated at a frame boundary with its chunk count rewritten to match is detected`() {
        // The attack `chunkCount` alone does **not** close, and the reason the AAD carries a
        // final-chunk bit at all. `thorbak.json` is plaintext and sits outside the AEAD envelope, so
        // the count is editable by anyone who can edit the container: cut the last frame off a member,
        // drop `chunkCount` to match, and every surviving frame still authenticates at its own index
        // under its own name. What refuses it is the bit — chunk 0 was sealed as "not the last" and is
        // being presented as the last.
        //
        // The test above removes the same 520 bytes but leaves `chunkCount` at 2, so it is refused by
        // the frame loop and says nothing about the bit. Erase the bit from `aad()` (both call sites
        // move together, which is why no round-trip test can see it) and this member decrypts and
        // authenticates perfectly as a silently short plaintext, which restore then writes over the
        // user's real data — the exact failure mode the ban on `CipherInputStream` exists to prevent,
        // reached through the unauthenticated header instead of through the stream.
        val (bytes, stats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES + 500) { 1 })
        assertEquals(2, stats.chunkCount)
        val cut = bytes.copyOf(bytes.size - 520)

        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(cut, 1) }

        // Chunk 0 itself is what fails, not a missing chunk 1: the frame is whole and present.
        assertMessageContains(ex, "chunk 0 failed authentication")
    }

    @Test
    fun `a stream truncated inside a frame is detected`() {
        val (bytes, stats) = encrypt(ByteArray(2048) { 5 })

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes.copyOf(bytes.size - 4), stats.chunkCount)
        }

        // The frame's own length prefix survives and still declares the full body, so the read of the
        // body is what runs short — a *different* branch from "ended before chunk N", which fires when
        // the four-byte prefix itself cannot be read. Naming it keeps this the test that covers it.
        assertMessageContains(ex, "chunk 0 ended early")
    }

    @Test
    fun `a stream carrying more chunks than the header declares is detected`() {
        val (bytes, stats) = encrypt(ByteArray(64) { 2 })

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes + bytes, stats.chunkCount)
        }

        // The trailing-bytes check at the end of `decryptMember`, named so this stays the test that
        // covers it: the chunk-count tests below deliberately no longer travel through it.
        assertMessageContains(ex, "carries more data")
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

        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(swapped, stats.chunkCount) }

        // Chunk 0, because the frame that was moved into position 0 is the one presented under the
        // wrong index. Both frames are whole and both declare their real length, so nothing but the
        // AEAD can object — a type-only assertion would not have shown that.
        assertMessageContains(ex, "chunk 0 failed authentication")
    }

    @Test
    fun `a member relabelled as another logical data class is detected`() {
        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember(
            dataClass = "ce",
            memberName = member,
            plaintext = ByteArrayInputStream(ByteArray(128) { 8 }),
            ciphertext = out,
            key = key(),
            nonce = nonce,
        )

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            cipher.decryptMember(
                dataClass = "ext-media",
                memberName = member,
                ciphertext = ByteArrayInputStream(out.toByteArray()),
                plaintext = ByteArrayOutputStream(),
                key = key(),
                nonce = nonce,
                chunkCount = stats.chunkCount,
            )
        }

        assertMessageContains(ex, "chunk 0 failed authentication")
    }

    @Test
    fun `a member decrypted under another member's name is detected`() {
        // The AAD binds the entry name, so `de.tar.gz.enc` cannot be presented as `ce.tar.gz.enc` —
        // a swap that would otherwise restore device-encrypted data into the CE directory.
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, name = "de.tar.gz.enc")
        }

        // The bytes are untouched and well-formed; only the AAD's name differs. Authentication is
        // therefore the only branch that can fire, and naming it is what distinguishes this test from
        // one that would pass on any malformed input at all.
        assertMessageContains(ex, "chunk 0 failed authentication")
    }

    @Test
    fun `a wrong passphrase is detected`() {
        val (bytes, stats) = encrypt(ByteArray(128) { 8 })

        assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, k = key("wrong horse"))
        }
    }

    @Test
    fun `a declared chunk count of zero is refused by the guard that names it`() {
        // Deliberately an **empty** stream. This test used to hand `decryptMember` a real one-frame
        // member, which the trailing-bytes check refuses on its own — so the `chunkCount <= 0` guard
        // the test is named for was never what fired, and the guard sat unpinned behind a green test.
        // With nothing to read, the frame loop never runs and the trailing-bytes check sees EOF, so
        // the guard is the only thing left that can refuse this.
        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(ByteArray(0), 0) }

        assertMessageContains(ex, "declares 0 chunks")
    }

    @Test
    fun `a negative chunk count is refused`() {
        // The other half of `<= 0`. `chunkCount` is a plain `Int` decoded from an unauthenticated
        // `thorbak.json`, so a negative one is a value a corrupt or hostile header really can carry.
        val ex = assertThrows(ArchiveIntegrityException::class.java) { decrypt(ByteArray(0), -1) }

        assertMessageContains(ex, "declares -1 chunks")
    }

    @Test
    fun `a nonce longer than the format's is refused, not copied past the end of the IV`() {
        // This guard is the **only** thing on the untrusted-header path. The nonce is Base64-decoded
        // straight out of `thorbak.json` by `RestoreAppArchiveUseCase`, which checks that it decodes
        // and nothing else. Remove this and `iv()`'s `nonce.copyInto(iv)` throws
        // ArrayIndexOutOfBoundsException — neither a GeneralSecurityException (so `decryptMember`'s
        // own catch misses it) nor an IOException (so both catches at the call site miss it) — and it
        // escapes `RestoreAppArchiveUseCase.invoke`, costing the caller the per-class report that
        // tells the user what was and was not restored.
        val (bytes, stats) = encrypt(ByteArray(64) { 6 })
        val overlong = ByteArray(MEMBER_NONCE_BYTES + 5) { it.toByte() }

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, memberNonce = overlong)
        }

        assertMessageContains(ex, "nonce is 13 bytes")
    }

    @Test
    fun `a nonce shorter than the format's is refused by the guard, not by a failed tag`() {
        // The wording is the whole test. A short nonce still builds a 12-byte IV — `copyInto` leaves
        // the rest zero — so without the guard decryption runs to `doFinal` and fails the tag, which
        // is an ArchiveIntegrityException too. Only the message separates "this header is malformed"
        // from "this ciphertext is damaged", and only the message notices the guard going away.
        val (bytes, stats) = encrypt(ByteArray(64) { 6 })

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(bytes, stats.chunkCount, memberNonce = ByteArray(4) { it.toByte() })
        }

        assertMessageContains(ex, "nonce is 4 bytes")
    }

    @Test
    fun `a frame declaring less than a tag's worth of bytes is refused before it is decrypted`() {
        // 15 = one below the 16-byte tag: a frame that cannot hold even its own tag. Without the
        // bound those 15 bytes are read and handed to `doFinal`, which reports them as a failed tag —
        // same exception type, wrong cause, and a bound whose deletion no test notices.
        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(frame(15, ByteArray(15)), 1)
        }

        assertMessageContains(ex, "declares 15 bytes")
    }

    @Test
    fun `a frame declaring more than a chunk plus its tag is refused before it is allocated`() {
        // Refused on the declared number, before `ByteArray(length)` is allocated from it — which is
        // what keeps a header claiming a two-gigabyte frame from being an OOM instead of a message.
        val tooLong = CHUNK_PLAINTEXT_BYTES + 16 + 1

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            decrypt(frame(tooLong, ByteArray(64)), 1)
        }

        assertMessageContains(ex, "declares $tooLong bytes")
    }

    @Test
    fun `the frame-length bound accepts both of its own limits`() {
        // The two tests above fire one byte outside each end; nothing pinned that the ends themselves
        // are *inside*. Both are lengths a member Thor writes today actually carries — an empty class
        // is a frame of exactly 16, a full chunk a frame of exactly CHUNK_PLAINTEXT_BYTES + 16 — so
        // tightening either comparison by one makes real archives unreadable.
        val (smallest, smallestStats) = encrypt(ByteArray(0))
        val (largest, largestStats) = encrypt(ByteArray(CHUNK_PLAINTEXT_BYTES))

        assertEquals(4 + 16, smallest.size)
        assertEquals(4 + CHUNK_PLAINTEXT_BYTES + 16, largest.size)
        assertEquals(0, decrypt(smallest, smallestStats.chunkCount).size)
        assertEquals(CHUNK_PLAINTEXT_BYTES, decrypt(largest, largestStats.chunkCount).size)
    }

    @Test
    fun `a stream that answers every read with zero is not read forever`() {
        // A stream that returns 0 rather than blocking or reporting EOF breaks `InputStream`'s
        // contract, and `content://` streams have been seen to do it. `fill` must treat it as the end
        // of the stream; the alternative is an unbounded spin — a **hang**, behind a foreground
        // notification, with no exception anywhere to point at it, which is worse to diagnose than a
        // crash. The call counter is what makes that a failing test rather than a build that never
        // finishes: without the guard `fill` asks again immediately and the stream raises a plain
        // IOException, which is not an ArchiveIntegrityException.
        val stuck = object : InputStream() {
            var calls = 0
                private set

            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                if (calls > 1_000) throw IOException("fill kept reading past a zero-length read")
                return 0
            }
        }

        val ex = assertThrows(ArchiveIntegrityException::class.java) {
            cipher.decryptMember("ce", member, stuck, ByteArrayOutputStream(), key(), nonce, 1)
        }

        assertMessageContains(ex, "ended before chunk 0")
        assertEquals("fill must stop at the first zero-length read", 1, stuck.calls)
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
        // AAD is length-prefixed domain, logical class and filename, then index/final marker.
        // IV is nonce || big-endian 0x00000000.
        //
        // Expected frame = 4-byte big-endian length (0x00000020 = 32) || ciphertext (16) || tag (16).
        // Verified by hand: first four bytes are 0x00 0x00 0x00 0x20 = big-endian 32 ✓,
        // total frame = 36 = 4 + 16 + 16 ✓.
        // Any change to endianness, AAD encoding, tag width, or IV construction breaks this.
        val katKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val katNonce = ByteArray(MEMBER_NONCE_BYTES) { it.toByte() }
        val katPlain = ByteArray(16) { it.toByte() }
        val expectedHex = "000000202905546776064d218ae4f69c629932eab2437cbc8b13e710d8ccba8a0f4c2660"

        val out = ByteArrayOutputStream()
        val stats = cipher.encryptMember("ce", "kat", ByteArrayInputStream(katPlain), out, katKey, katNonce)
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
        val written = cipher.decryptMember("ce", "kat", ByteArrayInputStream(frame), decOut, katKey, katNonce, 1)
        assertArrayEquals(katPlain, decOut.toByteArray())
        assertEquals(katPlain.size.toLong(), written)
    }
}

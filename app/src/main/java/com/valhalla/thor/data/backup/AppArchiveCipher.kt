// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.KDF_ITERATIONS
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.koin.core.annotation.Single

const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

private const val KDF_KEY_BITS = 256

/** Fresh per member. The GCM IV is this followed by a 4-byte big-endian chunk index. */
const val MEMBER_NONCE_BYTES = 8

const val CHUNK_PLAINTEXT_BYTES = 1024 * 1024

const val VERIFIER_BYTES = 16

private const val VERIFIER_MESSAGE = "thor-data-archive-v1"
private const val GCM_TAG_BITS = 128
private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
private const val FRAME_LENGTH_BYTES = 4

/**
 * The archive is not what it claims to be: a tag that does not verify, a stream that ends before its
 * declared chunk count, a frame that does not belong where it was found.
 *
 * An `IOException` so a caller that already handles I/O failure cannot accidentally not handle this
 * one. Note: [AppArchiveCipher.decryptMember] writes chunks as they authenticate, so chunks 0..N−1
 * may already be in the output stream when a failure on chunk N throws this. The caller must treat
 * any stream passed to `decryptMember` as a staging target and discard it on throw.
 */
open class ArchiveIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The passphrase does not match the archive's verifier.
 *
 * A subtype of [ArchiveIntegrityException] so existing catch-blocks cover it, but distinct so the
 * UI layer can show "wrong passphrase" rather than "your backup is damaged".
 *
 * **Nothing throws it today.** [AppArchiveCipher] cannot — [AppArchiveCipher.decryptMember] cannot
 * tell a wrong passphrase from a corrupt frame — and the one production caller that checks the
 * verifier, `OpenArchiveUseCase.unlock`, returns `ArchiveUnlockOutcome.WrongPassphrase` instead,
 * which reaches the same user-facing wording without an exception. This type is kept for a caller
 * that throws rather than returns after [AppArchiveCipher.verify] says false: such a caller must not
 * have to invent its own type and slip past every `catch (e: ArchiveIntegrityException)` the restore
 * path already has.
 */
class ArchivePassphraseException(message: String, cause: Throwable? = null) :
    ArchiveIntegrityException(message, cause)

/**
 * What one encrypted member turned out to be.
 *
 * [plainBytes] and [chunkCount] are the two the header records — they are `ArchiveMember`'s fields.
 * [cipherBytes] is **not** on the wire; no header field carries it. It exists for the frame
 * accounting in `AppArchiveCipherTest`, which is the only place the length prefixes and the tags are
 * checked to add up to the bytes actually written.
 */
data class MemberStats(
    val plainBytes: Long,
    val cipherBytes: Long,
    val chunkCount: Int,
)

/**
 * AES-256-GCM in 1 MiB frames.
 *
 * **`CipherInputStream` is not used and must never be.** It swallows `AEADBadTagException` and
 * returns -1, so a truncated or tampered member decrypts to a silently short plaintext — and restore
 * writes that over the user's real data. Every chunk here is its own `doFinal`, and every failure
 * throws [ArchiveIntegrityException].
 *
 * Framing: `4-byte big-endian ciphertext length ‖ ciphertext‖tag`, repeated `chunkCount` times.
 * - The IV is `nonce ‖ big-endian chunk index`, unique within a member, across members (fresh nonce)
 *   and across archives (fresh salt, so a fresh key).
 * - The AAD binds the member's entry name, the chunk index, and whether the chunk is the last one —
 *   so a frame cannot be moved, and a member cannot be presented as a different member.
 * - `chunkCount` comes from the header and closes truncation *at a chunk boundary*, which the AAD
 *   alone does not: a stream cut on a frame edge authenticates perfectly and is simply short.
 */
@Single
class AppArchiveCipher {

    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(KDF_SALT_BYTES).also(random::nextBytes)

    fun newNonce(): ByteArray = ByteArray(MEMBER_NONCE_BYTES).also(random::nextBytes)

    /**
     * Derive an archive's key from [passphrase] and that archive's own [salt].
     *
     * @param passphrase **not cleared here.** The caller owns it and may still need it — the vault
     *   remembers it after a successful unlock. Only the [PBEKeySpec]'s internal copy is cleared.
     * @param iterations **the archive's number, not Thor's.** This parameter is the format's
     *   compatibility hinge, not a test affordance: `OpenArchiveUseCase.unlock` is a production caller
     *   and it passes `header.kdf.iterations`, read straight out of an untrusted `thorbak.json`, so an
     *   archive written by a Thor whose [KDF_ITERATIONS] differs from this build's still opens. That
     *   is precisely why `MAX_KDF_ITERATIONS` exists — `unlock` refuses anything outside
     *   `0 < n <= MAX_KDF_ITERATIONS` before reaching here, because a header-supplied two billion is a
     *   derivation that never returns inside a worker. **Anything re-deriving a key for an archive it
     *   holds a header for must pass that header's count**; taking the default there produces a
     *   different key for every archive not written at today's number, and the failure surfaces as
     *   "this archive is damaged" rather than as a wrong passphrase. Tests pass it too, to avoid
     *   spending minutes in PBKDF2 — but that is the smaller of its two jobs.
     */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = KDF_ITERATIONS,
    ): SecretKey {
        require(salt.size == KDF_SALT_BYTES) { "salt must be $KDF_SALT_BYTES bytes" }
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KDF_KEY_BITS)
        try {
            val derived = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            return SecretKeySpec(derived, "AES")
        } finally {
            // The spec holds a copy of the passphrase; the caller's CharArray is the caller's to
            // clear.
            spec.clearPassword()
        }
    }

    /**
     * `HMAC-SHA256(key, "thor-data-archive-v1")` truncated to [VERIFIER_BYTES].
     *
     * Lets a wrong passphrase be rejected after one key derivation, before a byte is streamed. It
     * leaks nothing the ciphertext does not already leak to an offline attacker.
     */
    fun verifier(key: SecretKey): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        return mac.doFinal(VERIFIER_MESSAGE.toByteArray(Charsets.UTF_8)).copyOf(VERIFIER_BYTES)
    }

    /**
     * Compare [expected] (stored in the archive header) against the verifier for [key] in
     * constant time.
     *
     * Returns `false` when the passphrase is wrong; the caller should then throw
     * [ArchivePassphraseException] rather than proceeding to [decryptMember].
     */
    fun verify(key: SecretKey, expected: ByteArray): Boolean =
        MessageDigest.isEqual(verifier(key), expected)

    /** HMAC the deterministic schema-v2 manifest, excluding only its stored MAC value. */
    fun manifestMac(key: SecretKey, header: ArchiveHeader): ByteArray {
        val mac = Mac.getInstance(MANIFEST_AUTH_ALGORITHM)
        mac.init(SecretKeySpec(key.encoded, MANIFEST_AUTH_ALGORITHM))
        return mac.doFinal(ArchiveManifestCodec.canonicalBytes(header))
    }

    /** Verify schema-v2's complete manifest authentication in constant time. */
    fun verifyManifest(key: SecretKey, header: ArchiveHeader): Boolean {
        val authentication = header.authentication ?: return false
        if (authentication.algorithm != MANIFEST_AUTH_ALGORITHM) return false
        val expected = runCatching { Base64.getDecoder().decode(authentication.mac) }.getOrNull()
            ?: return false
        if (expected.size != MANIFEST_MAC_BYTES) return false
        return runCatching { MessageDigest.isEqual(manifestMac(key, header), expected) }
            .getOrDefault(false)
    }

    /**
     * Encrypt [plaintext] into [ciphertext], returning what the header must record.
     *
     * Neither stream is closed here: the output is one entry of a zip the caller keeps open for the
     * next member.
     */
    fun encryptMember(
        dataClass: String,
        memberName: String,
        plaintext: InputStream,
        ciphertext: OutputStream,
        key: SecretKey,
        nonce: ByteArray,
    ): MemberStats {
        require(nonce.size == MEMBER_NONCE_BYTES) { "nonce must be $MEMBER_NONCE_BYTES bytes" }

        // Two buffers, swapped: the final-chunk flag is part of the AAD, so a chunk cannot be
        // written until it is known whether anything follows it. A short read already proves EOF, so
        // the lookahead only happens on a full one.
        val first = ByteArray(CHUNK_PLAINTEXT_BYTES)
        val second = ByteArray(CHUNK_PLAINTEXT_BYTES)
        var current = first
        var currentLength = fill(plaintext, current)
        var index = 0
        var plainBytes = 0L
        var cipherBytes = 0L

        while (true) {
            val next = if (current === first) second else first
            val nextLength =
                if (currentLength < CHUNK_PLAINTEXT_BYTES) 0 else fill(plaintext, next)
            val isFinal = nextLength == 0

            val frame = cipherFor(Cipher.ENCRYPT_MODE, key, nonce, index).run {
                updateAAD(aad(dataClass, memberName, index, isFinal))
                doFinal(current, 0, currentLength)
            }
            writeFrame(ciphertext, frame)

            plainBytes += currentLength
            cipherBytes += FRAME_LENGTH_BYTES + frame.size
            index++
            if (isFinal) break
            current = next
            currentLength = nextLength
        }

        return MemberStats(plainBytes = plainBytes, cipherBytes = cipherBytes, chunkCount = index)
    }

    /**
     * Decrypt exactly [chunkCount] frames from [ciphertext] into [plaintext], returning the byte
     * count written.
     *
     * **[plaintext] must be a staging target** the caller discards on throw: chunks are written as
     * they authenticate, so a failure on chunk N leaves chunks 0..N−1 already in the stream.
     *
     * Throws [ArchiveIntegrityException] on anything that is not exactly that: a bad tag, a frame
     * that ends early, a declared length outside the format's bounds, or a byte after the last
     * frame. A nonce length mismatch also throws [ArchiveIntegrityException] (not
     * [IllegalArgumentException]) because the nonce comes from the archive header and a corrupt
     * header must be refused rather than crashed on.
     */
    fun decryptMember(
        dataClass: String,
        memberName: String,
        ciphertext: InputStream,
        plaintext: OutputStream,
        key: SecretKey,
        nonce: ByteArray,
        chunkCount: Int,
    ): Long {
        if (nonce.size != MEMBER_NONCE_BYTES) {
            throw ArchiveIntegrityException("$memberName nonce is ${nonce.size} bytes")
        }
        if (chunkCount <= 0) {
            throw ArchiveIntegrityException("$memberName declares $chunkCount chunks")
        }

        var written = 0L
        for (index in 0 until chunkCount) {
            val isFinal = index == chunkCount - 1
            val length = frameLength(ciphertext, memberName, index)
            if (length < GCM_TAG_BYTES || length > CHUNK_PLAINTEXT_BYTES + GCM_TAG_BYTES) {
                throw ArchiveIntegrityException("$memberName chunk $index declares $length bytes")
            }
            val frame = ByteArray(length)
            if (fill(ciphertext, frame) != length) {
                throw ArchiveIntegrityException("$memberName chunk $index ended early")
            }
            val chunk = try {
                cipherFor(Cipher.DECRYPT_MODE, key, nonce, index).run {
                    updateAAD(aad(dataClass, memberName, index, isFinal))
                    doFinal(frame)
                }
            } catch (e: GeneralSecurityException) {
                // AEADBadTagException arrives here — a wrong passphrase, a flipped byte, a frame
                // moved to another index, or a member presented under another member's name.
                throw ArchiveIntegrityException(
                    "$memberName chunk $index failed authentication",
                    e,
                )
            }
            plaintext.write(chunk)
            written += chunk.size
        }

        if (ciphertext.read() != -1) {
            throw ArchiveIntegrityException(
                "$memberName carries more data than its $chunkCount chunks"
            )
        }
        return written
    }

    private fun cipherFor(mode: Int, key: SecretKey, nonce: ByteArray, index: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(GCM_TAG_BITS, iv(nonce, index)))
        }

    private fun iv(nonce: ByteArray, index: Int): ByteArray {
        val iv = ByteArray(MEMBER_NONCE_BYTES + 4)
        nonce.copyInto(iv)
        iv[MEMBER_NONCE_BYTES] = (index ushr 24).toByte()
        iv[MEMBER_NONCE_BYTES + 1] = (index ushr 16).toByte()
        iv[MEMBER_NONCE_BYTES + 2] = (index ushr 8).toByte()
        iv[MEMBER_NONCE_BYTES + 3] = index.toByte()
        return iv
    }

    private fun aad(
        dataClass: String,
        memberName: String,
        index: Int,
        isFinal: Boolean,
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUtf8("thor-data-member-v2")
            out.writeUtf8(dataClass)
            out.writeUtf8(memberName)
            out.writeInt(index)
            out.writeBoolean(isFinal)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun writeFrame(out: OutputStream, frame: ByteArray) {
        out.write((frame.size ushr 24) and 0xFF)
        out.write((frame.size ushr 16) and 0xFF)
        out.write((frame.size ushr 8) and 0xFF)
        out.write(frame.size and 0xFF)
        out.write(frame)
    }

    private fun frameLength(input: InputStream, memberName: String, index: Int): Int {
        val header = ByteArray(FRAME_LENGTH_BYTES)
        if (fill(input, header) != FRAME_LENGTH_BYTES) {
            throw ArchiveIntegrityException("$memberName ended before chunk $index")
        }
        return ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
    }

    /**
     * Read until [into] is full or the stream ends; returns how many bytes landed.
     *
     * `read <= 0`, not `read < 0`. A zero-length read is neither EOF nor progress: `InputStream`'s
     * contract only permits it when the requested length is zero, which the loop condition already
     * rules out, so a provider that returns 0 anyway — and `content://` streams do — would spin here
     * forever. That is a **hang**, with a foreground notification already showing and no exception to
     * find in a bug report, which is strictly worse to diagnose than a crash. Treating it as the end
     * of the stream turns it into a short count, and every caller compares the count it got against
     * the count it asked for and raises [ArchiveIntegrityException].
     */
    private fun fill(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = input.read(into, total, into.size - total)
            if (read <= 0) break
            total += read
        }
        return total
    }
}

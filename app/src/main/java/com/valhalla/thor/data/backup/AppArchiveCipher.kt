// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

/** OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Pinned by a test; do not lower it for test speed. */
const val KDF_ITERATIONS = 210_000

private const val KDF_KEY_BITS = 256

/** Fresh per archive, so one reused passphrase is not one reused key. */
const val KDF_SALT_BYTES = 16

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
 * Thrown by callers when [AppArchiveCipher.verify] returns false — not by [AppArchiveCipher] itself,
 * since [AppArchiveCipher.decryptMember] cannot distinguish a wrong passphrase from a corrupt frame.
 */
class ArchivePassphraseException(message: String, cause: Throwable? = null) :
    ArchiveIntegrityException(message, cause)

/** What one encrypted member turned out to be, for the header. */
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
     * @param iterations exposed only so tests can derive keys without spending minutes in PBKDF2.
     *   Production callers pass nothing and get [KDF_ITERATIONS].
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

    /**
     * Encrypt [plaintext] into [ciphertext], returning what the header must record.
     *
     * Neither stream is closed here: the output is one entry of a zip the caller keeps open for the
     * next member.
     */
    fun encryptMember(
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
                updateAAD(aad(memberName, index, isFinal))
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
                    updateAAD(aad(memberName, index, isFinal))
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

    private fun aad(memberName: String, index: Int, isFinal: Boolean): ByteArray =
        "$memberName|$index|${if (isFinal) 1 else 0}".toByteArray(Charsets.UTF_8)

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

    /** Read until [into] is full or the stream ends; returns how many bytes landed. */
    private fun fill(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val read = input.read(into, total, into.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

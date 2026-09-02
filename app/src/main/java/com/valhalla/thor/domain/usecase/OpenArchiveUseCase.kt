// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.ArchiveManifestCodec
import com.valhalla.thor.data.backup.KDF_ALGORITHM
import com.valhalla.thor.data.backup.VERIFIER_BYTES
import com.valhalla.thor.domain.model.ARCHIVE_SCHEMA_VERSION
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.model.saltBytes
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKey

/**
 * The ceiling on a header's declared PBKDF2 iteration count.
 *
 * A header is attacker-controlled data. Two billion iterations is a derivation that never returns,
 * inside a worker, with a progress notification already showing — a hang the user can only escape by
 * force-stopping Thor. The ceiling is generous: ~20x the shipped 210,000, so an archive written by a
 * future Thor that raised its own count still opens.
 */
const val MAX_KDF_ITERATIONS = 4_000_000

/**
 * The maximum byte count that [OpenArchiveUseCase.readHeader] will read from the header entry.
 *
 * A real `thorbak.json` is a few hundred bytes to a few KB — JSON with one field per archive member,
 * and a typical backup has at most four (APK, OBB, DE, CE). The 1 MiB ceiling is conservative enough
 * that no legitimate header triggers it while still bounding a deflate-bomb `thorbak.json` to a fixed
 * allocation rather than an OOM.
 */
const val MAX_HEADER_BYTES = 1 * 1024 * 1024

sealed interface ArchiveHeaderOutcome {
    data class Read(val header: ArchiveHeader) : ArchiveHeaderOutcome

    /**
     * This file cannot be used as an archive.
     *
     * @param reason shown to the user verbatim, so it has to say *which* of four different things
     *   happened: the container holds no header entry, the header is larger than Thor will read, the
     *   header will not decode, or the container itself could not be read at all. That last one is
     *   the one that matters most to get right — a truncated or still-downloading `.thorbak` used to
     *   be reported as "this file has no `thorbak.json`, so it is not a Thor backup", which tells
     *   someone holding a real backup that it is the wrong kind of file. The four are separated in
     *   the wording rather than in the type because both consumers (`ArchiveRestoreViewModel` and
     *   `AppArchiveWorker`) render exactly this string and nothing else.
     *
     *   What is never in it: an exception message. `kotlinx.serialization`'s parser reports byte
     *   offsets, token names and a dump of the JSON it was reading — attacker-controlled detail that
     *   belongs neither on screen nor in authentication logs.
     */
    data class NotAnArchive(val reason: String) : ArchiveHeaderOutcome
}

sealed interface ArchiveUnlockOutcome {
    data class Unlocked(val key: SecretKey) : ArchiveUnlockOutcome

    data object WrongPassphrase : ArchiveUnlockOutcome

    /** The header is readable but its KDF parameters are not ones Thor will act on. */
    data class Unsupported(val reason: String) : ArchiveUnlockOutcome
}

/** Result of authenticating every byte that can influence a restore before evaluating its gate. */
sealed interface ArchiveAuthenticationOutcome {
    data class Authenticated(
        val header: ArchiveHeader,
        val key: SecretKey,
    ) : ArchiveAuthenticationOutcome

    data object WrongPassphrase : ArchiveAuthenticationOutcome

    /** Deliberately carries no attacker-controlled detail. */
    data object AuthenticationFailed : ArchiveAuthenticationOutcome
}

/**
 * Reads a container's display-only header, then authenticates every restore-relevant byte.
 *
 * The header read may populate the preview, but callers must not query installed-package facts or
 * evaluate the restore gate until [authenticate] succeeds. The worker repeats complete authentication
 * immediately before restore because a URI provider can change its bytes after user confirmation.
 */
@Factory
class OpenArchiveUseCase(
    private val cipher: AppArchiveCipher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun readHeader(source: ArchiveSource): ArchiveHeaderOutcome = withContext(ioDispatcher) {
        // Two failures, kept apart, because only one of them is about the file being the wrong kind
        // of thing: a null return is "there is no header entry in this container", while a throw is
        // "this container would not read" — truncated, damaged, or still being copied in.
        val bytes = try {
            source.openEntry(THORBAK_HEADER_ENTRY)?.use { it.readAtMost(MAX_HEADER_BYTES + 1) }
        } catch (e: CancellationException) {
            // `Exception` below would otherwise turn a cancelled read into a verdict about the file.
            throw e
        } catch (_: Exception) {
            Logger.e(TAG, "could not read archive header")
            return@withContext ArchiveHeaderOutcome.NotAnArchive(
                "this file could not be read; it may be damaged or still being copied"
            )
        } ?: return@withContext ArchiveHeaderOutcome.NotAnArchive(
            "this file has no $THORBAK_HEADER_ENTRY, so it is not a Thor backup"
        )

        // A hostile `thorbak.json` could be gigabytes after deflate; cap to avoid OOM. Its own
        // message, distinct from the "no header entry" one above: the entry is there and is absurd.
        if (bytes.size > MAX_HEADER_BYTES) {
            return@withContext ArchiveHeaderOutcome.NotAnArchive(
                "this file's $THORBAK_HEADER_ENTRY exceeds $MAX_HEADER_BYTES bytes"
            )
        }

        runCatching { ArchiveHeader.decode(bytes.decodeToString()) }
            .fold(
                onSuccess = { ArchiveHeaderOutcome.Read(it) },
                onFailure = {
                    // No parser detail is logged: malformed JSON is attacker-controlled input.
                    Logger.e(TAG, "archive header could not be decoded")
                    ArchiveHeaderOutcome.NotAnArchive(
                        "this file's $THORBAK_HEADER_ENTRY is not one this version of Thor can read"
                    )
                },
            )
    }

    /**
     * Authenticate the v2 manifest and raw installer-bundle bytes with a passphrase.
     *
     * This is the UI path. It deliberately returns one detail-free refusal for every malformed,
     * legacy, MAC-invalid, or bundle-invalid archive; only a valid structure whose fixed verifier
     * does not match can be identified as a wrong passphrase.
     */
    suspend fun authenticate(
        source: ArchiveSource,
        passphrase: CharArray,
    ): ArchiveAuthenticationOutcome = withContext(ioDispatcher) {
        authenticateCatching(source) { header ->
            when (val unlocked = unlockNow(header, passphrase)) {
                is ArchiveUnlockOutcome.Unlocked -> authenticateWithKey(source, header, unlocked.key)
                ArchiveUnlockOutcome.WrongPassphrase -> ArchiveAuthenticationOutcome.WrongPassphrase
                is ArchiveUnlockOutcome.Unsupported -> ArchiveAuthenticationOutcome.AuthenticationFailed
            }
        }
    }

    /** Repeat the complete authentication inside the worker with the in-memory derived key. */
    suspend fun authenticate(
        source: ArchiveSource,
        key: SecretKey,
    ): ArchiveAuthenticationOutcome = withContext(ioDispatcher) {
        authenticateCatching(source) { header ->
            val verifier = header.verifier.decodeBase64()
            if (verifier?.size != VERIFIER_BYTES || !cipher.verify(key, verifier)) {
                ArchiveAuthenticationOutcome.AuthenticationFailed
            } else {
                authenticateWithKey(source, header, key)
            }
        }
    }

    private inline fun authenticateCatching(
        source: ArchiveSource,
        authenticate: (ArchiveHeader) -> ArchiveAuthenticationOutcome,
    ): ArchiveAuthenticationOutcome = try {
        val names = source.entryNames()
        if (names.size != names.toSet().size || names.count { it == THORBAK_HEADER_ENTRY } != 1) {
            ArchiveAuthenticationOutcome.AuthenticationFailed
        } else {
            val bytes = source.openEntry(THORBAK_HEADER_ENTRY)
                ?.use { it.readAtMost(MAX_HEADER_BYTES + 1) }
                ?: return ArchiveAuthenticationOutcome.AuthenticationFailed
            if (bytes.size > MAX_HEADER_BYTES) {
                ArchiveAuthenticationOutcome.AuthenticationFailed
            } else {
                val header = ArchiveHeader.decode(bytes.decodeToString())
                if (header.schemaVersion != ARCHIVE_SCHEMA_VERSION) {
                    ArchiveAuthenticationOutcome.AuthenticationFailed
                } else {
                    ArchiveManifestCodec.validate(header)
                    if (header.appBundle != null &&
                        names.count { it == THORBAK_BUNDLE_ENTRY } != 1
                    ) {
                        ArchiveAuthenticationOutcome.AuthenticationFailed
                    } else {
                        authenticate(header)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ArchiveAuthenticationOutcome.AuthenticationFailed
    }

    private fun authenticateWithKey(
        source: ArchiveSource,
        header: ArchiveHeader,
        key: SecretKey,
    ): ArchiveAuthenticationOutcome {
        if (!cipher.verifyManifest(key, header) || !verifyBundle(source, header)) {
            return ArchiveAuthenticationOutcome.AuthenticationFailed
        }
        return ArchiveAuthenticationOutcome.Authenticated(header, key)
    }

    private fun verifyBundle(source: ArchiveSource, header: ArchiveHeader): Boolean {
        val bundle = header.appBundle ?: return true
        val expectedDigest = bundle.sha256?.hexBytes() ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        source.openEntry(THORBAK_BUNDLE_ENTRY)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                bytes += read
                if (bytes > bundle.bytes) return false
                digest.update(buffer, 0, read)
            }
        } ?: return false
        return bytes == bundle.bytes && MessageDigest.isEqual(expectedDigest, digest.digest())
    }

    /**
     * Derive the key and check it against the header's verifier.
     *
     * One derivation decides it, before a byte of ciphertext is touched — which matters because the
     * alternative is discovering a wrong passphrase after streaming several gigabytes.
     *
     * @param passphrase **not cleared here.** The caller owns it, and on success the vault may still
     *   need it to remember the passphrase. Same contract as `AppArchiveCipher.deriveKey`.
     */
    suspend fun unlock(header: ArchiveHeader, passphrase: CharArray): ArchiveUnlockOutcome =
        withContext(ioDispatcher) { unlockNow(header, passphrase) }

    private fun unlockNow(header: ArchiveHeader, passphrase: CharArray): ArchiveUnlockOutcome {
        val iterations = header.kdf.iterations
        if (iterations <= 0 || iterations > MAX_KDF_ITERATIONS) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive declares $iterations key-derivation rounds, which Thor will not run"
            )
        }

        if (header.kdf.algorithm != KDF_ALGORITHM) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive uses ${header.kdf.algorithm} for key derivation, which this Thor cannot read"
            )
        }

        // Decoded rather than trusted: `deriveKey` has a `require` on the salt length, and an
        // IllegalArgumentException escaping a worker is a crash, not a message.
        val salt = header.kdf.saltBytes()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's salt could not be read")
        if (salt.size != KDF_SALT_BYTES) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive's salt is ${salt.size} bytes, not $KDF_SALT_BYTES"
            )
        }
        val expected = header.verifier.decodeBase64()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's verifier could not be read")
        if (expected.size != VERIFIER_BYTES) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive's verifier is ${expected.size} bytes, not $VERIFIER_BYTES"
            )
        }

        val key = cipher.deriveKey(passphrase, salt, iterations)
        return if (cipher.verify(key, expected)) {
            ArchiveUnlockOutcome.Unlocked(key)
        } else {
            ArchiveUnlockOutcome.WrongPassphrase
        }
    }

    // `java.util.Base64`, never `android.util.Base64` — the latter throws "not mocked" under JVM
    // tests, which would take this whole class off the test classpath.
    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()

    private fun String.hexBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "OpenArchive"
    }

    /** Read up to [limit] bytes from this stream; the returned array is exactly the bytes read. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buf = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buf, total, limit - total)
            if (read < 0) break
            total += read
        }
        return if (total == limit) buf else buf.copyOf(total)
    }
}

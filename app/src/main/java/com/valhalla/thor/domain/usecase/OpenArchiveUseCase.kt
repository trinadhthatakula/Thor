// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.data.backup.KDF_ALGORITHM
import com.valhalla.thor.data.backup.VERIFIER_BYTES
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.InputStream
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

    /** @param reason shown to the user verbatim; it names what Thor looked for. */
    data class NotAnArchive(val reason: String) : ArchiveHeaderOutcome
}

sealed interface ArchiveUnlockOutcome {
    data class Unlocked(val key: SecretKey) : ArchiveUnlockOutcome

    data object WrongPassphrase : ArchiveUnlockOutcome

    /** The header is readable but its KDF parameters are not ones Thor will act on. */
    data class Unsupported(val reason: String) : ArchiveUnlockOutcome
}

/**
 * Reads a container's header, and turns a passphrase into a key.
 *
 * Two calls, not one, because §8.1 needs the header **before** it asks for anything: the restore
 * screen shows package, version, date and classes held, runs the gate, and only then prompts.
 */
@Factory
class OpenArchiveUseCase(
    private val cipher: AppArchiveCipher,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun readHeader(source: ArchiveSource): ArchiveHeaderOutcome = withContext(ioDispatcher) {
        val bytes = runCatching {
            source.openEntry(THORBAK_HEADER_ENTRY)?.use { stream ->
                val raw = stream.readAtMost(MAX_HEADER_BYTES + 1)
                // A hostile `thorbak.json` could be gigabytes after deflate; cap to avoid OOM.
                // A non-local return@withContext here surfaces the over-size outcome directly,
                // distinct from the "no header entry" message that follows.
                if (raw.size > MAX_HEADER_BYTES) {
                    return@withContext ArchiveHeaderOutcome.NotAnArchive(
                        "this file's $THORBAK_HEADER_ENTRY exceeds $MAX_HEADER_BYTES bytes"
                    )
                }
                raw
            }
        }.getOrNull()
            ?: return@withContext ArchiveHeaderOutcome.NotAnArchive(
                "this file has no $THORBAK_HEADER_ENTRY, so it is not a Thor backup"
            )

        runCatching { ArchiveHeader.decode(bytes.decodeToString()) }
            .fold(
                onSuccess = { ArchiveHeaderOutcome.Read(it) },
                onFailure = {
                    ArchiveHeaderOutcome.NotAnArchive(
                        "this file's $THORBAK_HEADER_ENTRY could not be read: ${it.message}"
                    )
                },
            )
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
        withContext(ioDispatcher) {
            val iterations = header.kdf.iterations
            if (iterations <= 0 || iterations > MAX_KDF_ITERATIONS) {
                return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive declares $iterations key-derivation rounds, which Thor will not run"
                )
            }

            if (header.kdf.algorithm != KDF_ALGORITHM) {
                return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive uses ${header.kdf.algorithm} for key derivation, which this Thor cannot read"
                )
            }

            // Decoded rather than trusted: `deriveKey` has a `require` on the salt length, and an
            // IllegalArgumentException escaping a worker is a crash, not a message.
            val salt = header.kdf.salt.decodeBase64()
                ?: return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive's salt could not be read"
                )
            if (salt.size != KDF_SALT_BYTES) {
                return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive's salt is ${salt.size} bytes, not $KDF_SALT_BYTES"
                )
            }
            val expected = header.verifier.decodeBase64()
                ?: return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive's verifier could not be read"
                )
            // A truncated-but-valid-Base64 verifier would yield WrongPassphrase forever: the user
            // retypes a correct passphrase and is refused every time. Catch it as a corrupt header.
            if (expected.size != VERIFIER_BYTES) {
                return@withContext ArchiveUnlockOutcome.Unsupported(
                    "this archive's verifier is ${expected.size} bytes, not $VERIFIER_BYTES"
                )
            }

            val key = cipher.deriveKey(passphrase, salt, iterations)
            // `cipher.verify` is constant-time (`MessageDigest.isEqual`) — the hash-then-compare
            // API was written for exactly this call site.
            if (cipher.verify(key, expected)) {
                ArchiveUnlockOutcome.Unlocked(key)
            } else {
                ArchiveUnlockOutcome.WrongPassphrase
            }
        }

    // `java.util.Base64`, never `android.util.Base64` — the latter throws "not mocked" under JVM
    // tests, which would take this whole class off the test classpath.
    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()

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

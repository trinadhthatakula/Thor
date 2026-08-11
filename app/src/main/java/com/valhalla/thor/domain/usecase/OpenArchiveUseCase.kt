// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.AppArchiveCipher
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.THORBAK_HEADER_ENTRY
import com.valhalla.thor.domain.repository.ArchiveSource
import org.koin.core.annotation.Factory
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
class OpenArchiveUseCase(private val cipher: AppArchiveCipher) {

    suspend fun readHeader(source: ArchiveSource): ArchiveHeaderOutcome {
        val bytes = runCatching { source.openEntry(THORBAK_HEADER_ENTRY)?.use { it.readBytes() } }
            .getOrNull()
            ?: return ArchiveHeaderOutcome.NotAnArchive(
                "this file has no $THORBAK_HEADER_ENTRY, so it is not a Thor backup"
            )

        return runCatching { ArchiveHeader.decode(bytes.decodeToString()) }
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
    suspend fun unlock(header: ArchiveHeader, passphrase: CharArray): ArchiveUnlockOutcome {
        val iterations = header.kdf.iterations
        if (iterations <= 0 || iterations > MAX_KDF_ITERATIONS) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive declares $iterations key-derivation rounds, which Thor will not run"
            )
        }

        // Decoded rather than trusted: `deriveKey` has a `require` on the salt length, and an
        // IllegalArgumentException escaping a worker is a crash, not a message.
        val salt = header.kdf.salt.decodeBase64()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's salt could not be read")
        if (salt.size != KDF_SALT_BYTES) {
            return ArchiveUnlockOutcome.Unsupported(
                "this archive's salt is ${salt.size} bytes, not $KDF_SALT_BYTES"
            )
        }
        val expected = header.verifier.decodeBase64()
            ?: return ArchiveUnlockOutcome.Unsupported("this archive's verifier could not be read")

        val key = cipher.deriveKey(passphrase, salt, iterations)
        return if (cipher.verifier(key).contentEquals(expected)) {
            ArchiveUnlockOutcome.Unlocked(key)
        } else {
            ArchiveUnlockOutcome.WrongPassphrase
        }
    }

    // `java.util.Base64`, never `android.util.Base64` — the latter throws "not mocked" under JVM
    // tests, which would take this whole class off the test classpath.
    private fun String.decodeBase64(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The vault is a convenience cache over a passphrase the user could always type instead. Its whole
 * contract is that losing it costs a prompt — never an archive.
 *
 * `AndroidKeystoreVaultKeyProvider` is not testable on the JVM (it needs the `AndroidKeyStore`
 * provider), which is exactly why the Keystore sits behind [VaultKeyProvider]: the contract is
 * testable with a provider that throws the way a wiped key does.
 */
class PassphraseVaultTest {

    private class FakeStore(var blob: String? = null) : PassphraseVaultStore {
        override suspend fun read(): String? = blob
        override suspend fun write(value: String?) { blob = value }
    }

    /**
     * Reversible "encryption" — the vault's logic is what is under test, not AES.
     *
     * When [alive] is false, [wrap] and [unwrap] throw [AEADBadTagException]: a permanently failing
     * JVM class (`javax.crypto`) that is the right stand-in for a wiped Keystore key in a JVM unit
     * test. `KeyPermanentlyInvalidatedException` would be more semantically precise but it is
     * Android-only and throws "not mocked" on the JVM; `AEADBadTagException` is in the permanent-
     * clear list, so the vault's selective-clearing logic is exercised correctly.
     */
    private class FakeProvider(var alive: Boolean = true) : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray =
            if (alive) plaintext.map { (it + 1).toByte() }.toByteArray()
            else throw AEADBadTagException("key gone")

        override fun unwrap(blob: ByteArray): ByteArray =
            if (alive) blob.map { (it - 1).toByte() }.toByteArray()
            else throw AEADBadTagException("key gone")
    }

    @Test
    fun `a remembered passphrase comes back`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())

        vault.remember("correct horse".toCharArray())

        assertEquals("correct horse", vault.recall()?.concatToString())
    }

    @Test
    fun `the stored blob is not the passphrase`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())

        vault.remember("correct horse".toCharArray())

        // Assert that the stored blob does not round-trip back to the plaintext passphrase.
        // Checking `store.blob.contains("correct horse")` is too weak: Base64("correct horse")
        // never contains that substring regardless of whether wrapping happened. Decoding from
        // Base64 and then comparing to the passphrase catches the case where remember() stored the
        // passphrase bytes unwrapped — FakeProvider's shift-by-one wrap ensures the round-trip
        // recovers something other than "correct horse" when wrapping is applied correctly.
        assertNotEquals(
            "correct horse",
            Base64.getDecoder().decode(store.blob!!).toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `a vault whose key was wiped yields a prompt, not a failure`() = runTest {
        // App reinstall, factory reset, biometric enrolment change. The archive is still readable;
        // the user just has to type the passphrase again. This is the single most important
        // property in the section that specified it.
        val store = FakeStore()
        val provider = FakeProvider()
        val vault = PassphraseVault(store, provider)
        vault.remember("correct horse".toCharArray())

        provider.alive = false

        assertNull(vault.recall())
    }

    @Test
    fun `an undecryptable blob is cleared rather than retried forever`() = runTest {
        val store = FakeStore()
        val provider = FakeProvider()
        val vault = PassphraseVault(store, provider)
        vault.remember("correct horse".toCharArray())

        provider.alive = false
        vault.recall()

        // Nothing can ever read it again, and leaving it there means every launch re-derives the
        // same failure and every UI keeps claiming a passphrase is stored.
        assertNull(store.blob)
    }

    @Test
    fun `an empty vault recalls nothing`() = runTest {
        assertNull(PassphraseVault(FakeStore(), FakeProvider()).recall())
    }

    @Test
    fun `forgetting removes the blob`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())
        vault.remember("correct horse".toCharArray())

        vault.forget()

        assertNull(store.blob)
        assertNull(vault.recall())
    }

    @Test
    fun `a failure to wrap does not leave a half-written vault`() = runTest {
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider(alive = false))

        vault.remember("correct horse".toCharArray())

        assertNull(store.blob)
    }

    @Test
    fun `a transient failure does not clear the blob`() = runTest {
        // Pins the non-clearing branch of the selective-clear guard. A locked device raises a
        // generic KeyStoreException — not KeyPermanentlyInvalidatedException — so setUnlockedDevice
        // Required(true) causes a transient failure that recovers once the device is unlocked.
        // Clearing the blob on that failure permanently destroys the cache for a recoverable state.
        // RuntimeException stands in for KeyStoreException here because KeyStoreException is an
        // Android class that would need mocking; the vault catches Exception broadly, so any
        // non-listed throwable exercises the no-clear path.
        val store = FakeStore()
        PassphraseVault(store, FakeProvider()).remember("correct horse".toCharArray())
        val blobBefore = checkNotNull(store.blob) { "store should have a blob after remember()" }

        val result = PassphraseVault(
            store,
            object : VaultKeyProvider {
                override fun wrap(plaintext: ByteArray) = plaintext
                override fun unwrap(blob: ByteArray): ByteArray =
                    throw RuntimeException("device temporarily locked")
            }
        ).recall()

        assertNull(result)
        assertEquals(blobBefore, store.blob) // blob must survive a transient failure
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /**
     * [isSet] is a `get()` rather than a stored flow so it reads whatever [blob] holds at
     * **property-access** time — the tests below mutate `blob` through [write] and read it back
     * directly, and a flow captured once at construction would answer for the constructor's value
     * forever.
     *
     * Property access, not collection: `flowOf(blob != null)` evaluates the condition when the getter
     * runs and then replays that captured constant however late collection happens. Every test here
     * accesses and collects in one expression (`vault.isRemembered.first()`), so the two coincide —
     * but a test that holds the flow, changes `blob`, then collects will get the stale answer.
     */
    private class FakeStore(var blob: String? = null) : PassphraseVaultStore {
        override val isSet: Flow<Boolean> get() = flowOf(blob != null)
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
    fun `isRemembered follows the store`() = runTest {
        // A regression pin, not decoration. Before `isSet` moved onto the interface this property
        // downcast to `DataStorePassphraseVaultStore` and returned a constant `false` for anything
        // else — so under any other store the UI read "no passphrase remembered" and asked for one
        // that was already there. Note it answers "is there a blob", not "will it unwrap": the wiped
        // -key test above pins the second half, where this stays true and `recall()` is null.
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())
        assertEquals(false, vault.isRemembered.first())

        vault.remember("correct horse".toCharArray())

        assertEquals(true, vault.isRemembered.first())
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

        val stored = vault.remember("correct horse".toCharArray())

        // Both halves, because they are separate failures. `store.blob` being null says the vault was
        // not half-written; `stored` being false says the caller was told so. A `remember` that wrote
        // nothing and returned true would pass the second assertion alone and put "Saved on this
        // device." on a screen where nothing was.
        assertEquals(false, stored)
        assertNull(store.blob)
    }

    @Test
    fun `a successful remember says so`() = runTest {
        // The other side of the Boolean. Without it, `remember` could `return false` unconditionally
        // and only the settings view model's tests would notice — the assertion above is satisfied by
        // a constant false, which is the shape a hurried refactor leaves behind.
        val store = FakeStore()
        val vault = PassphraseVault(store, FakeProvider())

        val stored = vault.remember("correct horse".toCharArray())

        assertEquals(true, stored)
    }

    @Test
    fun `a store that throws is reported as false, not thrown`() = runTest {
        // The guard `recall()` always had and `remember` did without. `PassphraseSettingsViewModel`
        // ran this call in a bare `viewModelScope.launch`, where an uncaught throw is a dead process
        // rather than a message — and the message this owes the user, `passphrase_error_store_failed`,
        // says a passphrase that could not be cached is survivable.
        //
        // `IllegalStateException` deliberately, not `IOException`: `DataStorePassphraseVaultStore`
        // swallows `IOException` itself, so that one never reaches this guard and a test using it
        // would pass with the guard deleted.
        val store = object : PassphraseVaultStore {
            override val isSet: Flow<Boolean> = flowOf(false)
            override suspend fun read(): String? = null
            override suspend fun write(value: String?): Unit = throw IllegalStateException("store is gone")
        }

        val stored = PassphraseVault(store, FakeProvider()).remember("correct horse".toCharArray())

        assertEquals(false, stored)
    }

    @Test
    fun `a cancellation while remembering is not reported as a store failure`() {
        // The half a broad `catch (e: Exception)` gets wrong. A cancelled coroutine must not come back
        // saying "the store refused"; it must not come back at all. Both this guard and `recall()`'s
        // rethrow `CancellationException` ahead of the broad catch.
        //
        // `runBlocking`, not `runTest`: `runTest` treats a `CancellationException` escaping the body as
        // the test coroutine being cancelled, which is not what is under test here.
        val store = object : PassphraseVaultStore {
            override val isSet: Flow<Boolean> = flowOf(false)
            override suspend fun read(): String? = null
            override suspend fun write(value: String?): Unit =
                throw CancellationException("the sheet was closed")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { PassphraseVault(store, FakeProvider()).remember("correct horse".toCharArray()) }
        }
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
                // `.copyOf()`, not the argument itself. Harmless today — this provider is only ever
                // reached through `recall()`, which never calls `wrap` — but `remember` wipes the buffer
                // it hands to `wrap` in a `finally`, and a provider that returns that same array returns
                // one the caller is about to fill with zeros. That ordering already shipped once on this
                // branch, as Base64 of NULs written to the store. The convention in every double here is
                // to copy, so that a double can never be the reason a wipe looks safe.
                override fun wrap(plaintext: ByteArray) = plaintext.copyOf()
                override fun unwrap(blob: ByteArray): ByteArray =
                    throw RuntimeException("device temporarily locked")
            }
        ).recall()

        assertNull(result)
        assertEquals(blobBefore, store.blob) // blob must survive a transient failure
    }
}

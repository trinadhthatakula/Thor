// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.data.backup.PassphraseVaultStore
import com.valhalla.thor.data.backup.VaultKeyProvider
import java.security.GeneralSecurityException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassphraseSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- doubles -------------------------------------------------------------------------------

    private class FakeVaultStore(initial: String? = null) : PassphraseVaultStore {
        private val state = MutableStateFlow(initial)

        /** What is actually stored, so a test can assert that nothing was written. */
        val blob: String? get() = state.value

        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            state.value = value
        }
    }

    /**
     * Reversible and trivial: the vault's own wrapping is Task 5's subject, not this one's.
     *
     * **The copies are load-bearing, not tidiness.** `PassphraseVault.remember` zeroes the plaintext
     * buffer it built in a `finally` that runs *before* `store.write` — so a provider that returns its
     * own argument hands the vault back the very array the vault is about to fill with zeros, and what
     * gets stored is Base64 of nothing. Each of the three tests below that recalls a passphrase then
     * reads a run of NUL characters instead. The shipped `AndroidKeystoreVaultKeyProvider` allocates
     * (`Cipher.doFinal`), so aliasing would be a property of the double and of no real provider; the
     * doubles in `AppBackupViewModelTest` and `ArchiveRestoreViewModelTest` copy for the same reason.
     */
    private class PlainKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray = plaintext.copyOf()
        override fun unwrap(blob: ByteArray): ByteArray = blob.copyOf()
    }

    /**
     * A store whose [write] parks until [release] completes, so "the save is in flight" is a state a
     * test can stand in and look at. Without it `busy` is only ever observable before and after, and
     * both of those are false.
     */
    private class GatedVaultStore : PassphraseVaultStore {
        private val state = MutableStateFlow<String?>(null)
        val release = CompletableDeferred<Unit>()

        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            release.await()
            state.value = value
        }
    }

    /**
     * A store that fails with something other than the `IOException`
     * `DataStorePassphraseVaultStore.write` swallows, so the throw escapes `PassphraseVault.remember`
     * — which wraps only the *Keystore* call in a `try` — and then escapes `save`'s `try` as well.
     */
    private class ThrowingVaultStore : PassphraseVaultStore {
        override val isSet: Flow<Boolean> = flowOf(false)
        override suspend fun read(): String? = null
        override suspend fun write(value: String?): Unit = throw IllegalStateException("store is gone")
    }

    /** A Keystore that is not there: never created, or invalidated by an enrolment change. */
    private class DeadKeyProvider : VaultKeyProvider {
        override fun wrap(plaintext: ByteArray): ByteArray =
            throw GeneralSecurityException("no key")

        override fun unwrap(blob: ByteArray): ByteArray =
            throw GeneralSecurityException("no key")
    }

    private fun pass(value: String) = value.toCharArray()

    // --- what is stored ------------------------------------------------------------------------

    @Test
    fun `an empty vault reports nothing remembered`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.remembered)
        assertEquals(false, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a saved passphrase is remembered and comes back out`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.saved)
        assertEquals(true, vm.uiState.value.remembered)
        assertNull(vm.uiState.value.error)
        // Through a second vault over the same store, because "the flag flipped" is not the claim —
        // "the passphrase can be recalled" is.
        assertEquals(
            "correct horse",
            PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString()
        )
    }

    @Test
    fun `a passphrase of exactly the minimum length is accepted`() = runTest(dispatcher) {
        // The boundary, because `>=` and `>` are one character apart and both look right.
        val typed = "a".repeat(MIN_PASSPHRASE_LENGTH)
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(pass(typed), pass(typed))
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `one character below the minimum is refused`() = runTest(dispatcher) {
        // The other side of the same boundary. Without it, `size <= MIN_PASSPHRASE_LENGTH` — an
        // off-by-one that refuses the exact-minimum case — is caught, but `size < MIN - 1` is not:
        // the accepted-boundary test above passes under it and every other refusal fixture is far
        // enough below the line to keep failing for the wrong reason.
        val typed = "a".repeat(MIN_PASSPHRASE_LENGTH - 1)
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass(typed), pass(typed))
        advanceUntilIdle()

        assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.saved)
        assertNull(store.blob)
    }

    @Test
    fun `saving a second passphrase replaces the first`() = runTest(dispatcher) {
        // §5.4: this does NOT re-encrypt anything. Every .thorbak already written still opens only
        // with the passphrase it was made with, which is why the sheet says so in words.
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()
        vm.save(pass("battery staple"), pass("battery staple"))
        advanceUntilIdle()

        assertEquals(
            "battery staple",
            PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString()
        )
    }

    @Test
    fun `forgetting clears the vault and the flag`() = runTest(dispatcher) {
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        vm.forget()
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.remembered)
        assertNull(store.blob)
    }

    // --- refusals ------------------------------------------------------------------------------

    @Test
    fun `a passphrase shorter than the minimum is refused without touching the vault`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

            vm.save(pass("short"), pass("short"))
            advanceUntilIdle()

            assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    @Test
    fun `a confirmation that does not match is refused without touching the vault`() =
        runTest(dispatcher) {
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

            vm.save(pass("correct horse"), pass("correct horss"))
            advanceUntilIdle()

            assertEquals(PassphraseError.MISMATCH, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    @Test
    fun `a refusal does not replace a passphrase that was already stored`() = runTest(dispatcher) {
        // The reason both checks run before the vault is touched. A user who opens the sheet to change
        // their passphrase, mistypes the confirmation and gives up must still be able to restore every
        // archive they made — a vault cleared or overwritten by a refused attempt takes that away, and
        // §5.4 says the passphrase is not recoverable.
        val store = FakeVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()
        val blobBefore = checkNotNull(store.blob) { "the first save should have written a blob" }

        vm.save(pass("battery staple"), pass("battery stapl3"))
        advanceUntilIdle()

        assertEquals(PassphraseError.MISMATCH, vm.uiState.value.error)
        assertEquals(blobBefore, store.blob)
        assertEquals(true, vm.uiState.value.remembered)
        assertEquals(
            "correct horse",
            PassphraseVault(store, PlainKeyProvider()).recall()?.concatToString()
        )
    }

    @Test
    fun `the length rule is reported ahead of the match rule`() = runTest(dispatcher) {
        // Both fields are wrong. "They do not match" would send the user to fix a typo and then refuse
        // them again for the length; the length is the rule they have to satisfy either way.
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(pass("abc"), pass("xyz"))
        advanceUntilIdle()

        assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
    }

    @Test
    fun `a vault that cannot store the passphrase reports a failure rather than success`() =
        runTest(dispatcher) {
            // The whole reason PassphraseVault.remember returns Boolean. A screen that says "saved"
            // when the Keystore refused sends the user away believing they need not write it down.
            val store = FakeVaultStore()
            val vm = PassphraseSettingsViewModel(PassphraseVault(store, DeadKeyProvider()))

            vm.save(pass("correct horse"), pass("correct horse"))
            advanceUntilIdle()

            assertEquals(PassphraseError.STORE_FAILED, vm.uiState.value.error)
            assertEquals(false, vm.uiState.value.saved)
            assertNull(store.blob)
        }

    // --- the arrays this class is handed ---------------------------------------------------------

    @Test
    fun `a stored passphrase is not left in the array it arrived in`() = runTest(dispatcher) {
        // Same ownership contract AppBackupViewModel.beginBackup documents: PassphraseVault.remember
        // zeroes the *byte* buffer it derives and leaves the caller's CharArray alone, so this class is
        // the last owner of it. Narrows the window rather than closing it — the sheet held the same
        // characters as a Compose String a moment earlier — which is exactly what the backup path
        // claims for itself, so the two callers of the vault behave the same way.
        val typed = pass("correct horse")
        val confirmation = pass("correct horse")
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(typed, confirmation)
        advanceUntilIdle()

        assertEquals(" ".repeat("correct horse".length), typed.concatToString())
        assertEquals(" ".repeat("correct horse".length), confirmation.concatToString())
    }

    @Test
    fun `a refused passphrase is not left in the arrays it arrived in`() = runTest(dispatcher) {
        // The path with no coroutine at all: `save` returns before it launches, so a wipe placed only
        // inside the launched block would leave the rejected passphrase live for the lifetime of the
        // sheet's recomposition.
        val typed = pass("short")
        val confirmation = pass("short")
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))

        vm.save(typed, confirmation)
        advanceUntilIdle()

        assertEquals("     ", typed.concatToString())
        assertEquals("     ", confirmation.concatToString())
    }

    // --- the flag that disables the screen -------------------------------------------------------

    @Test
    fun `busy is set while a save is in flight and cleared when it settles`() = runTest(dispatcher) {
        val store = GatedVaultStore()
        val vm = PassphraseSettingsViewModel(PassphraseVault(store, PlainKeyProvider()))

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        // Parked inside the store's write. Both text fields and the Save button are disabled while
        // this holds, which is the point of the flag: a second save cannot be started over the top of
        // this one. (The Forget button is not on screen in this scenario — nothing is stored yet.)
        assertEquals(true, vm.uiState.value.busy)
        assertEquals(false, vm.uiState.value.saved)

        store.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.busy)
        assertEquals(true, vm.uiState.value.saved)
    }

    @Test
    fun `busy is cleared when the store throws, and the throw is not swallowed`() {
        // The throw is *meant* to escape `viewModelScope`: `save` clears the flag in a `finally`, not
        // a `catch`. kotlinx-coroutines-test collects any exception that reaches the global handler
        // and `runTest` rethrows it at the end of the body, so the escape is what this test asserts
        // rather than something it works around — hence `assertThrows` *outside* `runTest` instead of
        // the usual `= runTest(dispatcher)` expression body. (Moving the throw outside a `runTest`
        // does not dodge the collector, it defers it: the exception is then reported against whichever
        // test runs next, as `UncaughtExceptionsBeforeTest`.)
        //
        // Asserting the escape is half the test, not decoration: clearing `busy` in a `catch` would
        // satisfy the first assertion while silently swallowing every failure out of the store.
        // Whether it *should* be caught is a branch-level question about `viewModelScope.launch` that
        // this task does not settle — `AppBackupViewModel.beginBackup` has the same shape — and
        // pinning it here means a later answer has to change this test deliberately.
        var busyAfterThrow: Boolean? = null
        val escaped = assertThrows(IllegalStateException::class.java) {
            runTest(dispatcher) {
                val vm = PassphraseSettingsViewModel(
                    PassphraseVault(ThrowingVaultStore(), PlainKeyProvider())
                )

                vm.save(pass("correct horse"), pass("correct horse"))
                advanceUntilIdle()

                busyAfterThrow = vm.uiState.value.busy
            }
        }

        // The state the user cannot get out of if this is wrong: `dismiss()` does not reset `busy`,
        // and this view model is activity-scoped, so a stuck flag survives closing and reopening the
        // sheet — leaving the app would be the only way back.
        assertEquals(false, busyAfterThrow)
        assertEquals("store is gone", escaped.message)
    }

    // --- the outcome is per visit --------------------------------------------------------------

    @Test
    fun `a successful save clears an earlier refusal`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))
        vm.save(pass("short"), pass("short"))
        advanceUntilIdle()

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertEquals(true, vm.uiState.value.saved)
    }

    @Test
    fun `a refusal clears an earlier success`() = runTest(dispatcher) {
        // The sheet renders "Saved on this device." and the error line independently, so a refusal that
        // left `saved` standing would draw both at once — a screen saying the passphrase was saved and
        // that it was too short, in that order.
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        vm.save(pass("short"), pass("short"))
        advanceUntilIdle()

        assertEquals(PassphraseError.TOO_SHORT, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.saved)
    }

    @Test
    fun `dismissing clears the outcome but not what is stored`() = runTest(dispatcher) {
        val vm = PassphraseSettingsViewModel(PassphraseVault(FakeVaultStore(), PlainKeyProvider()))
        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        vm.dismiss()

        assertEquals(false, vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
        // `remembered` is a fact about the device, not an outcome of this visit, so it survives.
        assertEquals(true, vm.uiState.value.remembered)
    }
}

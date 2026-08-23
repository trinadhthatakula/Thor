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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
     * `DataStorePassphraseVaultStore.write` swallows, so the throw reaches `PassphraseVault.remember`'s
     * store-write guard rather than being absorbed underneath it.
     *
     * **[isSet] reads [state] rather than answering the constant `flowOf(false)` it used to.** The
     * constant made this double lie about the interface it implements: `isSet` means "is a blob
     * stored", and a double that answers `false` no matter what was written cannot distinguish a
     * store that refused from one that accepted. `read()` now answers from the same place, so the two
     * halves of the double agree with each other.
     *
     * [write] throws **before** assigning, in that order deliberately: assigning first and then
     * throwing would flip [isSet] on a write that failed, which is the state the vault must never
     * report and therefore the state this double must never manufacture.
     *
     * **What this does not do, stated because a previous comment here claimed the opposite.** It does
     * not make `a store that throws leaves the vault reporting nothing remembered` sensitive to
     * `PassphraseVault.remember`'s implementation, and no change to this double can.
     * `PassphraseVault.isRemembered` is `get() = store.isSet`; this view model writes `remembered`
     * from exactly one place, the `init` collector; and a store whose [write] always throws before
     * assigning leaves [state] null forever. So `remembered` is false under every implementation of
     * `remember`, and that test's assertion is a statement about this double, not about the vault.
     * Measured: deleting `remember`'s store-write `try/catch` kills nothing in this file, before or
     * after this change. The guard is pinned where it lives — `PassphraseVaultTest.a store that
     * throws is reported as false, not thrown` asserts the returned `false` directly, and that one
     * does die. What the test below is still worth keeping for is the mutant its own body names:
     * `save()` writing `remembered = true` beside the collector, which nothing else here catches.
     */
    private class ThrowingVaultStore : PassphraseVaultStore {
        private val state = MutableStateFlow<String?>(null)

        override val isSet: Flow<Boolean> = state.map { it != null }
        override suspend fun read(): String? = state.value
        override suspend fun write(value: String?) {
            throw IllegalStateException("store is gone")
        }
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
    fun `busy is cleared when the store fails, and the failure is reported`() = runTest(dispatcher) {
        // This test previously asserted the opposite of its second half: that the store's throw
        // escaped `viewModelScope` uncaught, wrapped in an `assertThrows` outside `runTest`. That was
        // a correct pin on the contract of the day and is deliberately changed, not lost —
        // `PassphraseVault.remember` now catches its store write and returns `false`, the guard
        // `recall()` always had. So the failure arrives as a *result*, and there is nothing left for a
        // bare `viewModelScope.launch` to leak: an uncaught throw out of one is a dead process, which
        // is not a way to tell a user their passphrase was not cached.
        //
        // This comment used to claim the escape half was still under test here — that `runTest` would
        // collect the throw at the global handler and rethrow it, so a `remember` that let the store's
        // `IllegalStateException` out again would fail this test. It does not. `launchGuarded` catches
        // every non-cancellation `Exception` and routes it to `onFailure`, which this view model
        // defines as the same `STORE_FAILED` this test already asserts, so nothing ever reaches the
        // global handler and the two implementations are indistinguishable from here. Deleting
        // `PassphraseVault.remember`'s store-write guard leaves this test green — measured, not
        // reasoned. That guard is pinned where it lives, by `PassphraseVaultTest.a store that throws is
        // reported as false, not thrown`, which asserts the returned `false` directly.
        //
        // What this test does pin is the reporting: `launchGuarded` absorbing the failure into
        // *silence* rather than into `STORE_FAILED` fails here, and so does a `busy` left standing.
        val vm = PassphraseSettingsViewModel(
            PassphraseVault(ThrowingVaultStore(), PlainKeyProvider())
        )

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        // The state the user cannot get out of if this is wrong: `dismiss()` does not reset `busy`,
        // and this view model outlives the sheet — its owner is the `NavEntry` for
        // `ThorRoute.Settings`, the never-popped root of that back stack — so a stuck flag survives
        // closing and reopening the sheet, and leaving the app would be the only way back. It is
        // cleared in a `finally`, so it survives however the block above ends.
        assertEquals(false, vm.uiState.value.busy)
        // Reported, not swallowed. A guard that returned `false` and said nothing would satisfy the
        // `busy` assertion alone and leave the sheet claiming the passphrase was saved.
        assertEquals(PassphraseError.STORE_FAILED, vm.uiState.value.error)
        assertEquals(false, vm.uiState.value.saved)
    }

    @Test
    fun `a store that throws leaves the vault reporting nothing remembered`() = runTest(dispatcher) {
        // `remembered` is collected from the vault, so a write that never landed must not flip it.
        //
        // Read the scope of that carefully — this comment used to name the early-`true` mutant of
        // `PassphraseVault.remember` as the thing it catches, and it does not catch it. `remembered`
        // has one writer, the `init` collector over `store.isSet`, and `ThrowingVaultStore` never
        // assigns, so this assertion holds under every `remember` there is. See that double's KDoc.
        //
        // What it does catch, and what nothing else in this file catches, is a `save()` that writes
        // `remembered = true` beside the collector instead of leaving the flag to it — the second
        // writer the view model's own class KDoc forbids by name.
        val vm = PassphraseSettingsViewModel(
            PassphraseVault(ThrowingVaultStore(), PlainKeyProvider())
        )

        vm.save(pass("correct horse"), pass("correct horse"))
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.remembered)
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

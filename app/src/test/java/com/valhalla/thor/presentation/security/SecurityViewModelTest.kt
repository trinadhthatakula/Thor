// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import app.cash.turbine.test
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.FakeAuthCapability
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Behaviour tests for [SecurityViewModel] — the app lock.
 *
 * [SecurityViewModel.authState] is a four-way `combine` collapsed by a `when`, and the order of
 * that `when` *is* the security policy: it decides whether a stale error can outrank an unlocked
 * session, whether a user who turned the lock off is still asked for a fingerprint, and whether a
 * device that cannot authenticate at all is offered a way out or a prompt that can only fail. Each
 * test below fixes one of those precedence rules; none of them is expressible as a test of a single
 * method, which is why the state machine is exercised end to end.
 *
 * No dispatcher is injected here and none is needed: both `stateIn`s are `SharingStarted.Eagerly`,
 * so with a test dispatcher installed as Main the whole chain settles during construction and
 * `authState.value` is readable straight away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun locked() = FakePreferenceRepository(UserPreferences(biometricLockEnabled = true))

    @Test
    fun `with the lock switched off the app is not gated at all`() = runTest {
        val vm = SecurityViewModel(FakePreferenceRepository(), FakeAuthCapability())

        // NotRequired, not Unlocked: the UI branches on this to skip BiometricScreen entirely, so
        // "unlocked" would still cost a frame of the lock screen on every cold start.
        assertEquals(AuthState.NotRequired, vm.authState.value)
    }

    @Test
    fun `with the lock switched on the app starts locked`() = runTest {
        val vm = SecurityViewModel(locked(), FakeAuthCapability())

        // The seed value of the stateIn is Locked as well, so this is only meaningful because the
        // preference has already been read: fail *open* here and the lock never engages.
        assertEquals(AuthState.Locked, vm.authState.value)
    }

    @Test
    fun `authenticating unlocks the app`() = runTest {
        val vm = SecurityViewModel(locked(), FakeAuthCapability())

        vm.onAuthenticated()

        assertEquals(AuthState.Unlocked, vm.authState.value)
    }

    @Test
    fun `a failed attempt surfaces its own message and then retry re-arms the prompt`() = runTest {
        val vm = SecurityViewModel(locked(), FakeAuthCapability())

        vm.authState.test {
            assertEquals(AuthState.Locked, awaitItem())

            vm.onAuthError("Too many attempts")
            // The platform's own wording (lockout, no enrolled biometrics, user cancel) is the only
            // thing that tells the user why they are stuck, so it must survive to the UI verbatim.
            assertEquals(AuthState.Error("Too many attempts"), awaitItem())

            vm.onRetry()
            // Back to Locked, not straight to Unlocked: BiometricScreen re-triggers the prompt on
            // Locked, so a retry that landed anywhere else would leave the user with a dead button.
            assertEquals(AuthState.Locked, awaitItem())
        }
    }

    @Test
    fun `an error raised while the app is unlocked cannot lock it again`() = runTest {
        val vm = SecurityViewModel(locked(), FakeAuthCapability())
        vm.onAuthenticated()

        vm.onAuthError("Fingerprint operation cancelled")

        // The prompt can report a cancellation *after* it has already succeeded (the user dismisses
        // the sheet as it dissolves). Without the `!isSessionAuthenticated` guard in onAuthError,
        // that late callback would throw an authenticated user back to an error screen.
        assertEquals(AuthState.Unlocked, vm.authState.value)
    }

    @Test
    fun `an error raised while the lock is off cannot leak into a later locked session`() = runTest {
        val prefs = FakePreferenceRepository()
        val vm = SecurityViewModel(prefs, FakeAuthCapability())

        // Nothing should be listening for auth errors with the lock off, but the prompt is driven by
        // a callback the view model does not own, so it can arrive anyway.
        vm.onAuthError("No biometrics enrolled")
        assertEquals(AuthState.NotRequired, vm.authState.value)

        prefs.setBiometricLock(true)

        // Locked, not Error: an error recorded while the gate was open would otherwise re-emerge as
        // the *first* thing the user sees the moment they enable the lock, with a Retry button for
        // an attempt they never made.
        assertEquals(AuthState.Locked, vm.authState.value)
    }

    @Test
    fun `turning the lock off releases a locked session immediately`() = runTest {
        val prefs = locked()
        val vm = SecurityViewModel(prefs, FakeAuthCapability())

        vm.authState.test {
            assertEquals(AuthState.Locked, awaitItem())

            prefs.setBiometricLock(false)

            // The preference is the outermost branch of the `when`, so it wins over both the
            // session flag and the error. Otherwise a user who disabled the lock from another
            // surface would still be held at the prompt until the process restarts.
            assertEquals(AuthState.NotRequired, awaitItem())
        }
    }

    @Test
    fun `a device with nothing to authenticate with is told so instead of being held at a prompt`() =
        runTest {
            val vm = SecurityViewModel(locked(), FakeAuthCapability(capable = false))

            // This is the restore case: the lock preference comes back from Auto Backup onto a
            // device with no fingerprint and no screen lock. Locked would mean a prompt that fails
            // the instant it opens, and a Retry button that can only re-open it.
            assertEquals(AuthState.Unavailable, vm.authState.value)
        }

    @Test
    fun `setting a screen lock and coming back opens the way in`() = runTest {
        val capability = FakeAuthCapability(capable = false)
        val vm = SecurityViewModel(locked(), capability)

        vm.authState.test {
            assertEquals(AuthState.Unavailable, awaitItem())

            // The user takes the SET UP SCREEN LOCK button out to system Settings and returns.
            capability.capable = true
            vm.refreshCapability()

            // Locked, so BiometricScreen fires a prompt that can now actually succeed. Without the
            // onResume re-query this stays Unavailable until the process is killed, which turns the
            // escape hatch back into a dead end.
            assertEquals(AuthState.Locked, awaitItem())
        }
    }

    @Test
    fun `the error from the impossible prompt does not outlive the fix`() = runTest {
        val capability = FakeAuthCapability(capable = false)
        val vm = SecurityViewModel(locked(), capability)

        // The prompt still fires once before the state settles, so the platform's "no biometrics
        // enrolled" lands in _authError while the device genuinely could not authenticate.
        vm.onAuthError("No biometrics enrolled")
        assertEquals(AuthState.Unavailable, vm.authState.value)

        capability.capable = true
        vm.refreshCapability()

        // Locked, not Error: the message describes a device that no longer exists. Leaving it in
        // place greets the user with the exact complaint they just went and fixed.
        assertEquals(AuthState.Locked, vm.authState.value)
    }

    @Test
    fun `removing the screen lock mid-session does not evict an unlocked user`() = runTest {
        val capability = FakeAuthCapability()
        val vm = SecurityViewModel(locked(), capability)
        vm.onAuthenticated()

        capability.capable = false
        vm.refreshCapability()

        // Unavailable sits *below* authenticated in the `when` on purpose. This user proved who
        // they were; taking the screen lock off in system Settings is not grounds to throw them
        // out of the session they already opened.
        assertEquals(AuthState.Unlocked, vm.authState.value)
    }

    @Test
    fun `an incapable device is not gated at all while the lock is off`() = runTest {
        val vm = SecurityViewModel(FakePreferenceRepository(), FakeAuthCapability(capable = false))

        // Unavailable is a *lock* screen with an explanation on it, so showing it to someone who
        // never turned the lock on would invent a gate out of nothing.
        assertEquals(AuthState.NotRequired, vm.authState.value)
    }
}

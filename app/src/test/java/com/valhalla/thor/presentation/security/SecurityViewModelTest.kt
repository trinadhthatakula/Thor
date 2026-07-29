// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import app.cash.turbine.test
import com.valhalla.thor.domain.model.UserPreferences
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
 * [SecurityViewModel.authState] is a three-way `combine` collapsed by a `when`, and the order of
 * that `when` *is* the security policy: it decides whether a stale error can outrank an unlocked
 * session, and whether a user who turned the lock off is still asked for a fingerprint. Each test
 * below fixes one of those precedence rules; none of them is expressible as a test of a single
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
        val vm = SecurityViewModel(FakePreferenceRepository())

        // NotRequired, not Unlocked: the UI branches on this to skip BiometricScreen entirely, so
        // "unlocked" would still cost a frame of the lock screen on every cold start.
        assertEquals(AuthState.NotRequired, vm.authState.value)
    }

    @Test
    fun `with the lock switched on the app starts locked`() = runTest {
        val vm = SecurityViewModel(locked())

        // The seed value of the stateIn is Locked as well, so this is only meaningful because the
        // preference has already been read: fail *open* here and the lock never engages.
        assertEquals(AuthState.Locked, vm.authState.value)
    }

    @Test
    fun `authenticating unlocks the app`() = runTest {
        val vm = SecurityViewModel(locked())

        vm.onAuthenticated()

        assertEquals(AuthState.Unlocked, vm.authState.value)
    }

    @Test
    fun `a failed attempt surfaces its own message and then retry re-arms the prompt`() = runTest {
        val vm = SecurityViewModel(locked())

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
        val vm = SecurityViewModel(locked())
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
        val vm = SecurityViewModel(prefs)

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
        val vm = SecurityViewModel(prefs)

        vm.authState.test {
            assertEquals(AuthState.Locked, awaitItem())

            prefs.setBiometricLock(false)

            // The preference is the outermost branch of the `when`, so it wins over both the
            // session flag and the error. Otherwise a user who disabled the lock from another
            // surface would still be held at the prompt until the process restarts.
            assertEquals(AuthState.NotRequired, awaitItem())
        }
    }
}

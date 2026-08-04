// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.FakeAuthCapability
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The cold start — the one moment [SecurityViewModelTest] cannot reach.
 *
 * Every test in that file hands the view model a `FakePreferenceRepository` backed by a plain
 * `MutableStateFlow`, whose value is already there, and runs it unconfined so the collector settles
 * during construction. Production satisfies neither condition: DataStore reads from disk, and
 * `HomeActivity` composes a branch out of whatever the state is at that instant. The gap between
 * those two is where the gate stood open, and a test written against a preference that is already
 * read cannot see it however carefully it asserts.
 *
 * So this file is one setup: a preference that answers only after 50ms of virtual time, and a
 * `StandardTestDispatcher` so nothing runs until the test says so. The assertions are on the whole
 * *sequence* of states rather than a sampled value — "the gate was never open" is a claim about
 * every emission, and reading `authState.value` afterwards cannot make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelColdStartTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    /** A preference store that answers the way DataStore does: not yet. */
    private fun slowRead(lockEnabled: Boolean) = FakePreferenceRepository(
        UserPreferences(biometricLockEnabled = lockEnabled),
        firstReadDelayMs = 50
    )

    /**
     * Every state the UI would be handed, in order, from construction until the store has answered.
     *
     * The collector is a **foreground** `launch` that is cancelled once the clock has run out, not a
     * `backgroundScope.launch`. That is not a style choice: work in `backgroundScope` is not
     * guaranteed to have been dispatched by the time `advanceUntilIdle()` returns, so a collector
     * there records the state flow's initial value and then silently misses every update — this
     * asserted `[Loading]` against an `authState` whose value had already reached `Locked`. Measured
     * both ways before it was written this way round; a background collector reported `[Loading]`
     * with the dispatcher passed explicitly, without it, and with a `runCurrent()` first.
     *
     * The cancel is what makes the foreground scope safe here: `authState` is a `StateFlow` and
     * never completes, so without it `runTest` would hang waiting for a collector that has nothing
     * left to wait for.
     */
    private fun TestScope.states(vm: SecurityViewModel): List<AuthState> {
        val seen = mutableListOf<AuthState>()
        val job = launch(mainDispatcherRule.dispatcher) { vm.authState.collect { seen += it } }
        advanceUntilIdle()
        job.cancel()
        return seen
    }

    @Test
    fun `the gate is not reported open before the lock preference has been read`() = runTest {
        val vm = SecurityViewModel(slowRead(lockEnabled = true), FakeAuthCapability())

        // Everything the main thread has to run before the first frame — which on a device is all
        // that stands between onCreate and setContent picking a branch.
        runCurrent()

        // NotRequired here is the fail-open, and with the preference seeded `false` it was what
        // every cold start reported, lock on or off, for as long as the disk read took.
        assertEquals(AuthState.Loading, vm.authState.value)
    }

    @Test
    fun `a cold start with the lock on never passes through an open gate`() = runTest {
        val vm = SecurityViewModel(slowRead(lockEnabled = true), FakeAuthCapability())

        val seen = states(vm)

        // The whole history, not where it ended up. One NotRequired anywhere in this list is a frame
        // of MainScreen — the app composed behind its own lock, and the restored navigation state
        // read and dropped on the way past, so the user who then authenticates lands on the start
        // destination with their place gone.
        assertEquals(listOf(AuthState.Loading, AuthState.Locked), seen)
    }

    @Test
    fun `a cold start with the lock off never shows the lock screen`() = runTest {
        val vm = SecurityViewModel(slowRead(lockEnabled = false), FakeAuthCapability())

        val seen = states(vm)

        // The other half of the same rule, and why "not read yet" is a state of its own rather than
        // just a `Locked` seed: BiometricScreen fires the prompt from a LaunchedEffect the moment it
        // composes, so a Locked frame here is a fingerprint dialog in the face of a user who never
        // turned the lock on.
        assertEquals(listOf(AuthState.Loading, AuthState.NotRequired), seen)
    }
}

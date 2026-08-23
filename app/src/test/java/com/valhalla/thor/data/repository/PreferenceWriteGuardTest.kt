// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a caller of a `PreferenceRepository` setter sees when the store cannot be written.
 *
 * The read-side twin of [PreferenceReadGuardTest], and the more dangerous of the two while it was
 * missing: `DataStore.edit` throws `IOException` on a full disk, a read-only volume or an
 * interrupted write, and all 33 call sites sit in `viewModelScope` — a `SupervisorJob` on
 * `Dispatchers.Main.immediate` with no `CoroutineExceptionHandler`. An uncaught throw there does not
 * fail the toggle, it takes the process down. Flipping a switch with no space left on the device
 * crashed Thor.
 *
 * So the claim under test is "a write that fails returns `false` instead of throwing, is announced
 * exactly once unless its caller said it would speak for itself, and stays transparent to everything
 * that is not IO".
 *
 * Asserted against [guardedWrite] directly rather than through a setter, because a setter would test
 * one key's plumbing and the guard is what every one of them shares — and because
 * [PreferenceRepositoryImpl] needs a `Context` and two real files to exist at all.
 */
class PreferenceWriteGuardTest {

    /** The crash itself: the failure arrives as a return value, not as a throw. */
    @Test
    fun `a write that fails is reported rather than thrown`() = runTest {
        val full = FakeDataStore(failWith = { IOException("ENOSPC") })
        val latch = quiet()

        val saved = full.guardedWrite(STORE, failureLatch = latch) {
            it[Keys.BIOMETRIC_LOCK] = true
        }

        assertFalse("a dropped write must answer false", saved)
    }

    /**
     * And the user is told. Most setters return `Unit` into a fire-and-forget `launch`, so this
     * latch is the only channel they have — without it a dropped preference is indistinguishable
     * from one the user never made.
     */
    @Test
    fun `a failed write raises the store-wide notice`() = runTest {
        val full = FakeDataStore(failWith = { IOException("read-only file system") })
        val latch = quiet()

        full.guardedWrite(STORE, failureLatch = latch) { it[Keys.BIOMETRIC_LOCK] = true }

        assertTrue("the failure has to reach SecurityViewModel somehow", latch.value)
    }

    /**
     * `CorruptionException` extends `IOException`, so the narrow catch covers a store the handler
     * could not replace as well — the same case the read guard has to survive, arriving from the
     * other direction.
     */
    @Test
    fun `a corrupt store is caught by the same guard`() = runTest {
        val corrupt = FakeDataStore(failWith = { CorruptionException("unreadable proto") })

        val saved = corrupt.guardedWrite(STORE, failureLatch = quiet()) {
            it[Keys.BIOMETRIC_LOCK] = true
        }

        assertFalse(saved)
    }

    /**
     * The `announce = false` contract, which is what keeps `setBiometricLock` and `setLanguage` from
     * being talked over. Both have a caller with something better-aimed to say — which way the app
     * lock is facing, or that the language was deliberately left alone — and raising the generic
     * notice as well would report one failure twice, in two different words.
     */
    @Test
    fun `a caller that reports its own outcome does not also raise the notice`() = runTest {
        val full = FakeDataStore(failWith = { IOException("ENOSPC") })
        val latch = quiet()

        val saved = full.guardedWrite(STORE, announce = false, failureLatch = latch) {
            it[Keys.BIOMETRIC_LOCK] = true
        }

        assertFalse("the caller still learns it failed", saved)
        assertFalse("but nobody else is told", latch.value)
    }

    /** A store that takes the write is not accused of anything, and the value actually lands. */
    @Test
    fun `a healthy write applies the change and reports success`() = runTest {
        val store = FakeDataStore()
        val latch = quiet()

        val saved = store.guardedWrite(STORE, failureLatch = latch) {
            it[Keys.BIOMETRIC_LOCK] = true
        }

        assertTrue(saved)
        assertEquals(preferencesOf(Keys.BIOMETRIC_LOCK to true), store.current)
        assertFalse("and nothing is announced", latch.value)
    }

    /**
     * No retry, deliberately, and this is the assertion that pins it. `edit` already serialises
     * writes and re-reads the current value inside the transform, so a second attempt repeats the
     * same disk operation against the same full disk. The read path retries because a transient read
     * can genuinely succeed next time; there is nothing here to wait for.
     */
    @Test
    fun `a failed write is attempted once`() = runTest {
        val full = FakeDataStore(failWith = { IOException("ENOSPC") })

        full.guardedWrite(STORE, failureLatch = quiet()) { it[Keys.BIOMETRIC_LOCK] = true }

        assertEquals("one attempt, no backoff", 1, full.attempts)
    }

    /**
     * The guard must not become a blanket `catch`. A `SecurityException` from a locked user profile,
     * or a programming error inside the transform, has to stay loud — reporting it as "that setting
     * could not be saved" would hide a real fault behind a plausible sentence.
     */
    @Test
    fun `a non-IO failure is not swallowed`() = runTest {
        val broken = FakeDataStore(failWith = { IllegalStateException("not an IO problem") })
        val latch = quiet()

        val thrown = runCatching {
            broken.guardedWrite(STORE, failureLatch = latch) { it[Keys.BIOMETRIC_LOCK] = true }
        }.exceptionOrNull()

        assertTrue("expected the original failure, got $thrown", thrown is IllegalStateException)
        assertFalse("and it is not dressed up as a storage problem", latch.value)
    }

    /**
     * Cancellation above all — and the reason the catch is `IOException` rather than `Throwable`
     * with a rethrow bolted on. `CancellationException` is not an `IOException`, so structured
     * concurrency survives for free: a caller whose scope is being torn down must not be handed a
     * `false` that reads as "the disk refused", nor a `true` that reads as "it saved".
     */
    @Test
    fun `a cancellation is not mistaken for a write failure`() = runTest {
        val cancelled = FakeDataStore(failWith = { CancellationException("caller went away") })
        val latch = quiet()

        val thrown = runCatching {
            cancelled.guardedWrite(STORE, failureLatch = latch) { it[Keys.BIOMETRIC_LOCK] = true }
        }.exceptionOrNull()

        assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        assertFalse("a torn-down caller is not a storage failure", latch.value)
    }

    /** The latch as a fresh process finds it. */
    private fun quiet() = MutableStateFlow(false)

    /**
     * The smallest `DataStore` that can refuse. `edit` is an extension over `updateData`, so failing
     * there is exactly what a full disk does to every setter in the repository at once.
     */
    private class FakeDataStore(
        initial: Preferences = emptyPreferences(),
        private val failWith: (() -> Throwable)? = null
    ) : DataStore<Preferences> {

        var attempts = 0
            private set

        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        val current: Preferences get() = state.value

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            attempts++
            failWith?.let { throw it() }
            return transform(state.value).also { state.value = it }
        }
    }

    private companion object {
        const val STORE = "thor_preferences"
    }
}

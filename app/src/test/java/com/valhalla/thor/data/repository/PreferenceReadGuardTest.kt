// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.Keys
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl.LocalKeys
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a collector of `PreferenceRepository.userPreferences` sees when a store cannot be read.
 *
 * DataStore's `.data` throws `IOException` on a failed read, and its default corruption handler is
 * `NoOpCorruptionHandler`, which rethrows. Nothing rewrites the file afterwards, so an unreadable
 * `thor_preferences.preferences_pb` throws on *every* read for the life of the install — and that
 * file is the one included in cloud backup and device transfer, so a partial restore can produce
 * exactly that state on a device the user has never opened Thor on. Around twenty places collect
 * this stream, several during startup, which makes the failure an unrecoverable crash loop whose
 * only cure is clearing app data — which in turn destroys the freezer watchlist, deliberately not
 * backed up and therefore not coming back.
 *
 * So the claim under test is not "reads are correct" (that is `ToUserPreferencesTest`) but "a read
 * that fails is retried, then degrades to the defaults, says which of the two happened, and stays
 * transparent to everything that is not IO".
 *
 * `runTest` skips the retry backoff rather than waiting it out, so nothing here costs the half a
 * second the production path would spend.
 */
class PreferenceReadGuardTest {

    /** The crash loop itself, at the shape the collectors actually use. */
    @Test
    fun `an unreadable settings store yields the defaults instead of throwing`() = runTest {
        val unreadable = flow<Preferences> { throw IOException("failed to read preferences file") }

        val prefs = userPreferencesFlow(unreadable, flowOf(emptyPreferences()), intact()).first()

        assertEquals(UserPreferences(settingsLost = true), prefs)
    }

    /**
     * The restore case specifically: a truncated `.preferences_pb` surfaces as `CorruptionException`,
     * which is an `IOException` subclass — so the guard covers it even when the store's own
     * corruption handler could not replace the file (a read-only volume, a full disk), where
     * DataStore rethrows the original by design.
     */
    @Test
    fun `a corrupt preferences file yields the defaults`() = runTest {
        val corrupt = flow<Preferences> { throw CorruptionException("unreadable proto") }

        val prefs = userPreferencesFlow(corrupt, flowOf(emptyPreferences()), intact()).first()

        assertEquals(UserPreferences(settingsLost = true), prefs)
    }

    /**
     * The other half of the corruption case, and the common one: the handler *did* replace the
     * file, so every read from here on succeeds and returns an empty store. Nothing in the stream
     * can tell that apart from a user who has changed no settings, which is why the replacement is
     * recorded outside it — `biometricLockEnabled` reads `false` either way, and only one of those
     * two is a disarmed app lock.
     */
    @Test
    fun `a replaced settings file reads clean but still reports the loss`() = runTest {
        val replaced = MutableStateFlow(true)

        val prefs = userPreferencesFlow(
            flowOf(emptyPreferences()),
            flowOf(emptyPreferences()),
            replaced
        ).first()

        assertTrue("the replacement has to survive into the snapshot", prefs.settingsLost)
    }

    /** A store that reads is not accused of anything. */
    @Test
    fun `a healthy read does not claim the settings were lost`() = runTest {
        val settings = flowOf(preferencesOf(Keys.BIOMETRIC_LOCK to true))

        val prefs = userPreferencesFlow(settings, flowOf(emptyPreferences()), intact()).first()

        assertFalse(prefs.settingsLost)
        assertTrue(prefs.biometricLockEnabled)
    }

    /**
     * Each store is guarded on its own, before the combine. A single `catch` downstream of the
     * combine would end the whole stream on the first failure and hand back defaults for *both*
     * files, silently discarding a perfectly readable settings file.
     */
    @Test
    fun `a failure in one store does not discard the other`() = runTest {
        val settings = flowOf(preferencesOf(Keys.THEME_MODE to ThemeMode.DARK.name))
        val unreadableLocal = flow<Preferences> { throw IOException("local state unreadable") }

        val prefs = userPreferencesFlow(settings, unreadableLocal, intact()).first()

        assertEquals("the readable store still answers", ThemeMode.DARK, prefs.themeMode)
        assertFalse("the unreadable one falls back", prefs.hasShownDisabledAppsPrompt)
        // The per-install store holds no setting the user chose, so losing it is not the loss the
        // security path is warned about.
        assertFalse("and it is not reported as a settings loss", prefs.settingsLost)
    }

    /** And the same the other way round, since the two sides are not symmetric in what they hold. */
    @Test
    fun `an unreadable settings store leaves the per-install one readable`() = runTest {
        val unreadable = flow<Preferences> { throw IOException("settings unreadable") }
        val local = flowOf(preferencesOf(LocalKeys.HAS_SHOWN_DISABLED_APPS_PROMPT to true))

        val prefs = userPreferencesFlow(unreadable, local, intact()).first()

        assertTrue("the per-install store still answers", prefs.hasShownDisabledAppsPrompt)
        assertEquals("the unreadable one falls back", ThemeMode.SYSTEM, prefs.themeMode)
        assertTrue("and this side *is* reported", prefs.settingsLost)
    }

    // The rest assert the guard directly rather than through userPreferencesFlow. `combine`
    // collects each side in a child coroutine and conflates, so a rethrow from inside it would be
    // observed as combine's handling of a cancelled child rather than as the guard's own decision,
    // and an emission count would be observing conflation rather than the guard.

    /**
     * The reason the guard retries at all. Degrading is terminal — `catch` emits once and the
     * upstream is complete — and nothing re-subscribes: `SecurityViewModel` shares the stream with
     * `SharingStarted.Eagerly`, which does not restart a finished upstream, and the other collectors
     * are plain `collect` loops that just end. So without the retry a single transient failure (low
     * storage, an EIO, a read before credential-encrypted storage is unlocked) pins the whole
     * process to values the user never chose, with the real ones sitting intact on disk.
     */
    @Test
    fun `a read that fails once and then works delivers the real preferences`() = runTest {
        val real = preferencesOf(Keys.BIOMETRIC_LOCK to true)
        var attempts = 0
        val flaky = flow<Preferences> {
            if (attempts++ == 0) throw IOException("EIO, once")
            emit(real)
        }

        val read = flaky.guardedRead("thor_preferences").first()

        assertEquals(real, read.preferences)
        assertFalse("a recovered read is not a degraded one", read.degraded)
    }

    /** A failure that outlasts the retries still degrades rather than reaching the collectors. */
    @Test
    fun `a read that never recovers degrades after the retries`() = runTest {
        var attempts = 0
        val unreadable = flow<Preferences> {
            attempts++
            throw IOException("failed to read")
        }

        val read = unreadable.guardedRead("thor_preferences").toList()

        assertEquals(listOf(StoreRead(emptyPreferences(), degraded = true)), read)
        assertEquals("first attempt plus the retries", 4, attempts)
    }

    /**
     * A store that fails part way through keeps what it already delivered, then degrades — and the
     * retries re-deliver it, because a retry re-collects from the start. Harmless here: every
     * collector of this stream treats an emission as state to hold rather than as an event, and
     * DataStore re-emits the current snapshot to a new subscriber anyway.
     */
    @Test
    fun `a mid-stream read failure degrades rather than terminating the collector`() = runTest {
        val good = preferencesOf(Keys.THEME_MODE to ThemeMode.DARK.name)
        val failsAfterOne = flow<Preferences> {
            emit(good)
            throw IOException("the file went away")
        }

        val read = failsAfterOne.guardedRead("thor_preferences").toList()

        assertEquals(
            "the last good snapshot survives",
            good,
            read.first { !it.degraded }.preferences
        )
        assertEquals(
            "and the stream ends on the fallback rather than on the throw",
            StoreRead(emptyPreferences(), degraded = true),
            read.last()
        )
    }

    /**
     * The guard must not become a blanket `catch`. A programming error in the mapping, a
     * `SecurityException`, an `OutOfMemoryError` — turning any of those into "the user has no
     * settings" hides a real fault behind a plausible-looking default screen. It must not be
     * retried either: a non-IO failure will not read differently the second time.
     */
    @Test
    fun `a non-IO failure is not swallowed`() = runTest {
        var attempts = 0
        val broken = flow<Preferences> {
            attempts++
            throw IllegalStateException("not an IO problem")
        }

        val thrown = runCatching { broken.guardedRead("thor_preferences").toList() }
            .exceptionOrNull()

        assertTrue("expected the original failure, got $thrown", thrown is IllegalStateException)
        assertEquals("and no retry", 1, attempts)
    }

    /**
     * Cancellation above all. A collector being torn down must not be handed a fabricated defaults
     * emission on its way out — for `MainViewModel` or `PrivilegeManager` that would be a state
     * write during shutdown, and it would break structured concurrency besides.
     */
    @Test
    fun `a cancellation is not mistaken for a read failure`() = runTest {
        val cancelled = flow<Preferences> { throw CancellationException("collector went away") }

        val thrown = runCatching { cancelled.guardedRead("thor_preferences").toList() }
            .exceptionOrNull()

        assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
    }

    /** A healthy store is passed through untouched — the guard is not a filter. */
    @Test
    fun `a readable store is unaffected`() = runTest {
        val readable: Flow<Preferences> = flowOf(
            emptyPreferences(),
            preferencesOf(Keys.THEME_MODE to ThemeMode.DARK.name)
        )

        val read = readable.guardedRead("thor_preferences").toList()

        assertEquals(2, read.size)
        assertTrue("nothing was degraded", read.none { it.degraded })
    }

    /** The state of a settings file that was never replaced. */
    private fun intact() = MutableStateFlow(false)
}

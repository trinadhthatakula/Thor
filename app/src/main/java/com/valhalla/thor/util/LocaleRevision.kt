// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "The app language just changed" as one process-wide signal, for the caches that cannot see it any
 * other way.
 *
 * [LocalizedResources] fixes every string Thor resolves *on demand*, but a string already copied
 * into a database row, a launcher shortcut or a painted tile is beyond its reach. Those holders need
 * to be told, and the two mechanisms that can change the app language do not share a callback:
 * below API 33 it is [AppLocale.appliedTag], and on 33+ it is a configuration change delivered to
 * the `Application`. `ThorApplication` is the one place that already watches both, so it is the one
 * place that emits here.
 *
 * ### What this deliberately does not cover
 *
 * A change to the **system** language while an in-app override is active. `ThorApplication` compares
 * the locale of its own configuration, and under an override that value does not move when the
 * device language does — yet every third-party app label cached in Room is now stale, because a
 * label is loaded from *that* app's resources and follows the system language. `ACTION_LOCALE_CHANGED`
 * is the signal for that half, and it is a broadcast, so the consumer that cares registers for it
 * directly rather than having it relayed through here. See `AppRepositoryImpl.getAllApps`.
 *
 * ### Why `replay = 0` is safe here
 *
 * An emission with no subscriber is dropped, which for a `SharedFlow` is usually a bug. It is not
 * one here: a subscriber only exists while something is actively collecting, and every consumer of
 * this signal also reconciles from durable state when it *starts* collecting — `AppRepositoryImpl`
 * seeds its comparison from the locale key persisted alongside the rows. The flow covers the live
 * case, the persisted key covers the cold one, and neither is asked to do the other's job.
 */
object LocaleRevision {

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once per app-language change. Carries no value: the answer is always "re-read it". */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Announce that the app language changed.
     *
     * `tryEmit` rather than `emit` so this stays callable from the non-suspending framework
     * callbacks that know about the change. It cannot fail meaningfully: with a buffer of one and
     * `DROP_OLDEST`, a second change arriving before the first is collected replaces it, and one
     * "re-read everything" is worth exactly as much as two.
     */
    fun bump() {
        _changes.tryEmit(Unit)
    }
}

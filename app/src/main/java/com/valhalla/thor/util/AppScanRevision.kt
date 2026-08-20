// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.update

/**
 * Process-wide trigger to request a package scan / cache refresh in
 * [com.valhalla.thor.data.repository.AppRepositoryImpl].
 *
 * Bumped whenever an external permission grant, self-grant, or privilege elevation occurs that
 * changes package visibility, so the repository re-asks `PackageManager` instead of waiting for an
 * unrelated package broadcast.
 *
 * ### Why a counter and not a `SharedFlow<Unit>`
 *
 * A `MutableSharedFlow(replay = 0)` **silently discards** a `tryEmit` made while nobody is
 * collecting, and the one caller that matters most bumps from exactly there: the self-grant runs off
 * the privilege probe started in `ThorApplication.onCreate`, which on a fast device can finish
 * before the first ViewModel has begun collecting the repository flow. The signal that a
 * Chinese-ROM user's package list just became visible would land in an empty room and be dropped,
 * leaving the list holding only Thor with nothing scheduled to fix it.
 *
 * A monotonic revision has no such hole, because the *value* survives having no subscriber.
 *
 * ### Why [requestsAfter] and not `revision.drop(1)`
 *
 * `drop(1)` discards whatever the StateFlow replays on subscribe, which is only the right thing to
 * do if that replayed value is one the subscriber's own first scan already covers. Whether it is
 * depends on *when* the subscriber got there, and the repository cannot subscribe first: its scan
 * worker is launched onto a multi-threaded dispatcher and can be inside
 * `PackageManager.getInstalledPackages` before the watcher a few lines below it has run at all. A
 * bump landing in that window moves the value, the late `drop(1)` throws it away as "the replay",
 * and the only scan that ever ran is the one that read the package list *before* the grant took
 * effect — a truncated list with nothing scheduled to correct it.
 *
 * [snapshot] and [requestsAfter] close that window by making the baseline explicit instead of
 * positional: take the snapshot before starting the initial scan, and every bump raised after that
 * instant is delivered no matter how late the collector subscribes, because the *value* carries it.
 */
object AppScanRevision {

    private val _revision = MutableStateFlow(0)

    /**
     * Increments once per requested package scan.
     *
     * Prefer [requestsAfter] over collecting this directly — see the class KDoc. Conflating is fine
     * and deliberate: two bumps in quick succession collapsing into one emission costs nothing,
     * since the repository drains its trigger channel before scanning anyway.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Request a package scan / cache refresh. */
    fun bump() {
        _revision.update { it + 1 }
    }

    /**
     * The current revision, to be read *before* starting a scan and handed straight to
     * [requestsAfter]. Reading it any later narrows the window this pair exists to cover.
     */
    fun snapshot(): Int = _revision.value

    /**
     * Emits once per scan request raised after [baseline], and never for [baseline] itself.
     *
     * Late subscription is safe by construction: the revision only moves forward, so a collector
     * arriving after a bump reads a value that is already past [baseline] and is told to scan,
     * while one arriving with nothing bumped in between reads [baseline] and stays quiet — no
     * redundant scan on top of the one the caller runs itself.
     */
    fun requestsAfter(baseline: Int): Flow<Int> = revision.dropWhile { it == baseline }
}

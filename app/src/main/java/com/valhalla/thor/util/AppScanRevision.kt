// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide trigger to request a package scan / cache refresh in
 * [com.valhalla.thor.data.repository.AppRepositoryImpl].
 *
 * Emitted whenever an external permission grant, self-grant, or privilege elevation occurs that changes
 * package visibility so that the repository rescans PackageManager immediately.
 */
object AppScanRevision {

    private val _changes = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once per requested package scan. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /** Request a package scan / cache refresh. */
    fun bump() {
        _changes.tryEmit(Unit)
    }
}

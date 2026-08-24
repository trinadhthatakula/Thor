// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentType
import kotlinx.coroutines.flow.Flow

/**
 * Thor's record of the components it switched off.
 *
 * Bookkeeping, not enforcement — see [com.valhalla.thor.domain.model.ComponentOverride]. Every
 * method is scoped to the Android user Thor is running in; the implementation supplies that, the
 * way [SystemRepository] does for its own per-user commands, so that no caller can forget to.
 */
interface ComponentOverrideRepository {

    /** The rows for one package, as a stream so the "N restricted by Thor" header cannot lag. */
    fun observe(packageName: String): Flow<List<ComponentOverride>>

    /** Every row, for the cross-app restore. */
    suspend fun getAll(): List<ComponentOverride>

    /**
     * Record that Thor disabled [className].
     *
     * @param restoreToEnabled the component's manifest default **as read right now**, not `true`.
     */
    suspend fun record(
        packageName: String,
        className: String,
        type: ComponentType,
        restoreToEnabled: Boolean,
    )

    /** Drop one row — after a successful restore, or after the user dismisses a drifted row. */
    suspend fun forget(packageName: String, className: String)

    /** Drop every row for one package. */
    suspend fun forgetPackage(packageName: String)
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.domain.model.canForceStopApps
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import org.koin.compose.koinInject

/** Availability flags can include Root while the user has selected Dhizuku. Follow the active mode. */
@Composable
internal fun rememberCanForceStopApps(): Boolean {
    val privilege: PrivilegeStateProvider = koinInject()
    val state by privilege.state.collectAsStateWithLifecycle()
    return canForceStopApps(state)
}

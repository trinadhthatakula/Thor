// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.PrivilegeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of the shared privilege probe. The concrete `PrivilegeManager` registers
 * Shizuku binder listeners from its initializer, which is a live Android/Binder dependency;
 * consumers that only *observe* the probe take this port instead, so they stay constructible
 * off-device. Anything that needs to trigger a re-probe still depends on the manager itself.
 */
interface PrivilegeStateProvider {
    /** Latest privilege availability. Emits `isReady = true` once the first probe lands. */
    val state: StateFlow<PrivilegeState>
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.thor.domain.model.ComponentCapability
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.componentCapability
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import com.valhalla.thor.data.source.local.shizuku.Shizuku as ShizukuHelper

/**
 * "What may Thor do to an individual component on this device?", answered once per privilege state.
 *
 * Built on [DataArchiveCapabilityCache][com.valhalla.thor.data.backup.DataArchiveCapabilityCache]'s
 * shape, and for the same two reasons:
 *  - it awaits `state.first { it.isReady }` rather than reading `state.value`, because the cold-start
 *    snapshot is `isReady = false, active = NONE`, which on a rooted device would answer "no
 *    privilege" and paint a Components tab with every control greyed out until the probe lands; and
 *  - it is keyed on the whole [PrivilegeState], so `PrivilegeManager.refresh()` landing a new state
 *    *is* the invalidation. A TTL would leave a window where granting root leaves the tab dead.
 *
 * The one measurement it makes is `Shizuku.getUid()`, which is the only thing that distinguishes a
 * Shizuku that can do this from one that cannot — availability is permission-plus-`pingBinder`, and
 * that is equally true at uid 2000. It is read here, in the data layer, because `Shizuku`'s static
 * initialiser builds a Binder and cannot load in a JVM test; keeping it out of
 * [componentCapability] is what leaves the *rule* testable.
 */
@Single
class ComponentCapabilityProvider(
    private val privilegeState: PrivilegeStateProvider,
) {

    private val mutex = Mutex()

    private var cached: Pair<PrivilegeState, ComponentCapability>? = null

    suspend fun capability(): ComponentCapability {
        val state = privilegeState.state.first { it.isReady }
        mutex.withLock {
            cached?.let { (key, value) -> if (key == state) return value }
            val capability = componentCapability(
                mode = state.active,
                isReady = state.isReady,
                shizukuUid = if (state.active == PrivilegeMode.SHIZUKU) readShizukuUid() else null,
            )
            cached = state to capability
            return capability
        }
    }

    /**
     * Shizuku's own uid, or `null` when it cannot be read.
     *
     * `null` means **not capable** downstream, which is the opposite of the optimistic fold used
     * when choosing a privileged installer. The asymmetry is deliberate: guessing wrong there costs
     * one failed install with a clear message, guessing wrong here paints enabled Force Open and
     * Disable controls that throw a `SecurityException` on every press.
     */
    private fun readShizukuUid(): Int? = runCatching { ShizukuHelper.uidOrNull() }.getOrNull()
}

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.domain.model.ComponentControlBlocker
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentCapabilityProviderTest {
    private class Privilege(initial: PrivilegeState) : PrivilegeStateProvider {
        override val state = MutableStateFlow(initial)
        val root = TestRootAdmission().apply { state.value = initial.rootAvailability }
        fun publish(value: PrivilegeState) {
            root.state.value = value.rootAvailability
            state.value = value
        }
    }

    private fun provider(privilege: Privilege) = ComponentCapabilityProvider(privilege, privilege.root)

    private fun shizuku() = PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true)

    @Test fun `failed uid measurement remains unknown and can recover without a refresh`() = runTest {
        var uid: Int? = null
        var calls = 0
        val capability = provider(Privilege(shizuku())).apply {
            readShizukuUid = { calls++; uid }
        }
        assertEquals(ComponentControlBlocker.NOT_READY, capability.capability().blocker)
        uid = 0
        assertTrue(capability.capability().hasUid0)
        assertTrue(capability.capability().hasUid0)
        assertEquals(2, calls)
    }

    @Test fun `measured shell uid is cached until the observation revision changes`() = runTest {
        val privilege = Privilege(shizuku())
        var uid = 2000
        var calls = 0
        val capability = provider(privilege).apply { readShizukuUid = { calls++; uid } }
        assertEquals(ComponentControlBlocker.SHIZUKU_NOT_ROOT, capability.capability().blocker)
        uid = 0
        assertFalse(capability.capability().hasUid0)
        privilege.publish(shizuku().copy(rootAvailability = shizuku().rootAvailability.copy(revision = 2)))
        assertTrue(capability.capability().hasUid0)
        assertEquals(2, calls)
    }

    @Test fun `provider changes invalidate capabilities within the same revision`() = runTest {
        val privilege = Privilege(shizuku())
        val capability = provider(privilege).apply { readShizukuUid = { 0 } }
        assertTrue(capability.capability().hasUid0)
        privilege.publish(privilege.state.value.copy(active = PrivilegeMode.DHIZUKU))
        assertEquals(ComponentControlBlocker.DHIZUKU_UNSUPPORTED, capability.capability().blocker)
    }

    @Test fun `unresolved root refresh does not reuse granted component controls`() = runTest {
        val rooted = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)
        val privilege = Privilege(rooted)
        val capability = provider(privilege)
        assertTrue(capability.capability().hasUid0)
        privilege.publish(rooted.copy(rootAvailability = rooted.rootAvailability.copy(
            refreshStatus = RootRefreshStatus.FAILED, revision = 2,
        )))
        assertEquals(ComponentControlBlocker.NOT_READY, capability.capability().blocker)
        privilege.publish(rooted.copy(rootAvailability = rooted.rootAvailability.copy(revision = 3)))
        assertTrue(capability.capability().hasUid0)
    }
    @Test fun `direct invalidation hides cached root controls before the manager mirror catches up`() = runTest {
        val privilege = Privilege(PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true))
        val capability = provider(privilege)
        assertTrue(capability.capability().hasUid0)
        privilege.root.state.value = privilege.root.state.value.copy(
            refreshStatus = RootRefreshStatus.CHECKING, revision = 2,
        )
        assertTrue(privilege.state.value.rootAvailability.canAdmitRoot)
        assertEquals(ComponentControlBlocker.NOT_READY, capability.capability().blocker)
    }

    @Test fun `direct revision change discards a stale uid measurement without a mirror update`() = runTest {
        val privilege = Privilege(shizuku())
        var calls = 0
        val capability = provider(privilege).apply {
            readShizukuUid = {
                if (calls++ == 0) {
                    privilege.root.state.value = privilege.root.state.value.copy(revision = 2)
                    2000
                } else 0
            }
        }
        assertTrue(capability.capability().hasUid0)
        assertEquals(2, calls)
        assertEquals(0L, privilege.state.value.rootAvailability.revision)
    }

}

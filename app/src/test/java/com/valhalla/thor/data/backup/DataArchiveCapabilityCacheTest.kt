// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataArchiveCapabilityCacheTest {

    private class FakeProbe(var answer: Boolean = true) : AppDataProbe {
        var probes = 0
        override suspend fun probeDataArchiveCapability(): Boolean {
            probes++
            return answer
        }

        override suspend fun measureDataClass(packageName: String, dataClass: DataClass) =
            DataClassSize.Undetermined
    }

    private class FakePrivilege(initial: PrivilegeState) : PrivilegeStateProvider {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<PrivilegeState> get() = flow
    }

    private fun rooted() = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    @Test
    fun `the answer is probed once and reused`() = runTest {
        // The backup sheet reads this on every open, and every read is a shell round trip through
        // the gateway.
        val probe = FakeProbe()
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(rooted()))

        assertTrue(cache.isSupported())
        assertTrue(cache.isSupported())

        assertEquals(1, probe.probes)
    }

    @Test
    fun `an unsupported answer is cached too`() = runTest {
        // Otherwise the device where this feature does not work is the one that shells out most.
        val probe = FakeProbe(answer = false)
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(rooted()))

        assertFalse(cache.isSupported())
        assertFalse(cache.isSupported())

        assertEquals(1, probe.probes)
    }

    @Test
    fun `a privilege change re-probes`() = runTest {
        // Shizuku answers this differently from root, and the user can switch modes while a sheet is
        // open. The cache key is the whole PrivilegeState, so `refresh()` landing a new state is
        // enough to invalidate it — there is no second invalidation path to keep in sync.
        val probe = FakeProbe(answer = false)
        val privilege = FakePrivilege(PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true))
        val cache = DataArchiveCapabilityCache(probe, privilege)
        assertFalse(cache.isSupported())

        probe.answer = true
        privilege.flow.value = rooted()

        assertTrue(cache.isSupported())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `no privileged surface means no shell at all`() = runTest {
        // Not "probe and get false": there is nothing to probe *through*. Shelling out here would
        // spawn a `su` prompt on a device the user never granted anything on.
        val probe = FakeProbe()
        val cache = DataArchiveCapabilityCache(probe, FakePrivilege(PrivilegeState(isReady = true)))

        assertFalse(cache.isSupported())
        assertEquals(0, probe.probes)
    }

    @Test
    fun `a cold start that has not probed yet is not cached as unsupported`() = runTest {
        // `isReady = false` is "not known yet" — isSupported() suspends rather than returning false,
        // so the not-ready state is never committed to the cache as unsupported. Once the privilege
        // probe lands and the state becomes ready, the real answer comes back.
        val probe = FakeProbe()
        val privilege = FakePrivilege(PrivilegeState(isReady = false))
        val cache = DataArchiveCapabilityCache(probe, privilege)

        val deferred = async { cache.isSupported() }
        runCurrent()  // advance to the suspension point at first { it.isReady }

        privilege.flow.value = rooted()
        runCurrent()  // resume and complete

        assertTrue(deferred.await())
    }

    @Test
    fun `isSupported suspends on cold start and awaits the first ready state`() = runTest {
        // On a cold start, `privilegeState.state.value` is the default: isReady = false, active = NONE.
        // Reading `.value` directly returns false immediately — even on a rooted device — because
        // `hasAnyPrivilege` is `active != NONE`. `first { it.isReady }` suspends until the privilege
        // probe resolves and then returns the real answer. BulkFreezeRunner.kt:368 carries this fix
        // for the same snapshot-read bug; the pattern is the same.
        val probe = FakeProbe(answer = true)
        val privilege = FakePrivilege(PrivilegeState(isReady = false))
        val cache = DataArchiveCapabilityCache(probe, privilege)

        // Launch isSupported() concurrently and let it reach its suspension point before the ready
        // state arrives. With `.value`, it returns false immediately (active = NONE). With
        // `first { it.isReady }`, it suspends here and waits.
        val deferred = async { cache.isSupported() }
        runCurrent()

        // Emit the resolved state. With the fix, this wakes the suspended coroutine.
        privilege.flow.value = rooted()
        runCurrent()

        assertTrue(deferred.await())
        assertEquals(1, probe.probes)
    }
}

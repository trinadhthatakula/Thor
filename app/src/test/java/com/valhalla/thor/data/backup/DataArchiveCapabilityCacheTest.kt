// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataArchiveCapabilityCacheTest {

    private class FakeProbe(
        var answer: Boolean = true,
        var privateAnswer: Boolean = true,
    ) : AppDataProbe {
        var probes = 0
        var privateProbes = 0
        val measuredProviders = mutableListOf<PrivilegeMode>()
        var failure: Throwable? = null
        var onProbe: suspend () -> Unit = {}

        override suspend fun probePrivateDataCapability(): Boolean {
            privateProbes++
            return privateAnswer
        }

        override suspend fun probeDataArchiveCapability(): Boolean {
            probes++
            failure?.let { throw it }
            onProbe()
            return answer
        }

        override suspend fun probePrivateDataCapability(mode: PrivilegeMode): Boolean {
            measuredProviders += mode
            return probePrivateDataCapability()
        }

        override suspend fun probeDataArchiveCapability(mode: PrivilegeMode): Boolean {
            measuredProviders += mode
            return probeDataArchiveCapability()
        }

        override suspend fun measureDataClass(packageName: String, dataClass: DataClass) =
            DataClassSize.Undetermined
    }

    private class FakePrivilege(initial: PrivilegeState) : PrivilegeStateProvider {
        val flow = MutableStateFlow(initial)
        val root = TestRootAdmission().apply { state.value = initial.rootAvailability }
        override val state: StateFlow<PrivilegeState> get() = flow
        fun publish(state: PrivilegeState) {
            root.state.value = state.rootAvailability
            flow.value = state
        }
    }

    private fun newCache(probe: AppDataProbe, privilege: FakePrivilege) =
        DataArchiveCapabilityCache(probe, privilege, privilege.root)

    private fun rooted() = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

    @Test
    fun `the answer is probed once and reused`() = runTest {
        // The backup sheet reads this on every open, and every read is a shell round trip through
        // the gateway.
        val probe = FakeProbe()
        val cache = newCache(probe, FakePrivilege(rooted()))

        assertTrue(cache.isSupported())
        assertTrue(cache.isSupported())

        assertEquals(1, probe.probes)
    }

    @Test
    fun `an unsupported answer is cached too`() = runTest {
        // Otherwise the device where this feature does not work is the one that shells out most.
        val probe = FakeProbe(answer = false)
        val cache = newCache(probe, FakePrivilege(rooted()))

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
        val cache = newCache(probe, privilege)
        assertFalse(cache.isSupported())

        probe.answer = true
        privilege.publish(rooted())

        assertTrue(cache.isSupported())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `no privileged surface means no shell at all`() = runTest {
        // Not "probe and get false": there is nothing to probe *through*. Shelling out here would
        // spawn a `su` prompt on a device the user never granted anything on.
        val probe = FakeProbe()
        val cache = newCache(probe, FakePrivilege(PrivilegeState(isReady = true)))

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
        val cache = newCache(probe, privilege)

        val deferred = async { cache.isSupported() }
        runCurrent()  // advance to the suspension point at first { it.isReady }

        privilege.publish(rooted())
        runCurrent()  // resume and complete

        assertTrue(deferred.await())
    }

    @Test
    fun `isSupported suspends on cold start and awaits the first ready state`() = runTest {
        // On a cold start, `privilegeState.state.value` is the default: isReady = false, active = NONE.
        // Reading `.value` directly returns false immediately — even on a rooted device — because
        // `hasAnyPrivilege` is `active != NONE`. `first { it.isReady }` suspends until the privilege
        // probe resolves and then returns the real answer. `the legacy bulk executor.launch`'s privilege gate
        // carries this fix for the same snapshot-read bug; the pattern is the same.
        val probe = FakeProbe(answer = true)
        val privilege = FakePrivilege(PrivilegeState(isReady = false))
        val cache = newCache(probe, privilege)

        // Launch isSupported() concurrently and let it reach its suspension point before the ready
        // state arrives. With `.value`, it returns false immediately (active = NONE). With
        // `first { it.isReady }`, it suspends here and waits.
        val deferred = async { cache.isSupported() }
        runCurrent()

        // Emit the resolved state. With the fix, this wakes the suspended coroutine.
        privilege.publish(rooted())
        runCurrent()

        assertTrue(deferred.await())
        assertEquals(1, probe.probes)
    }

    @Test
    fun `non-root privilege provides external classes only`() = runTest {
        val probe = FakeProbe(answer = true, privateAnswer = false)
        val privilege = FakePrivilege(PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true))
        val cache = newCache(probe, privilege)

        assertTrue(cache.isSupported())
        assertFalse(cache.canReadPrivateData())
        assertEquals(
            setOf(DataClass.EXTERNAL_DATA, DataClass.EXTERNAL_MEDIA),
            cache.supportedClasses()
        )
    }

    @Test
    fun `root privilege provides all data classes`() = runTest {
        val probe = FakeProbe(answer = true, privateAnswer = true)
        val cache = newCache(probe, FakePrivilege(rooted()))

        assertTrue(cache.isSupported())
        assertTrue(cache.canReadPrivateData())
        assertEquals(
            DataClass.entries.toSet(),
            cache.supportedClasses()
        )
    }
    @Test
    fun `equal root refresh remeasures unsupported capability`() = runTest {
        val privilege = FakePrivilege(rooted())
        val probe = FakeProbe(answer = false)
        val cache = newCache(probe, privilege)
        assertFalse(cache.isSupported())
        probe.answer = true
        privilege.publish(rooted().copy(rootAvailability = rooted().rootAvailability.copy(revision = 2)))
        assertTrue(cache.isSupported())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `failed measurement remains unknown and the same revision can recover`() = runTest {
        val failure = IllegalStateException("measurement unavailable")
        val probe = FakeProbe().apply { this.failure = failure }
        val cache = newCache(probe, FakePrivilege(rooted()))
        assertSame(failure, runCatching { cache.isSupported() }.exceptionOrNull())
        probe.failure = null
        assertTrue(cache.isSupported())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `refresh during measurement discards obsolete completion`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val privilege = FakePrivilege(rooted())
        val probe = FakeProbe().apply {
            onProbe = { if (probes == 1) { entered.complete(Unit); release.await() } }
        }
        val cache = newCache(probe, privilege)
        val measuring = async { cache.isSupported() }
        entered.await()
        privilege.publish(rooted().copy(rootAvailability = rooted().rootAvailability.copy(revision = 2)))
        release.complete(Unit)
        assertTrue(measuring.await())
        assertEquals(2, probe.probes)
    }

    @Test
    fun `unresolved root refresh neither reuses cached capability nor starts a probe`() = runTest {
        val privilege = FakePrivilege(rooted())
        val probe = FakeProbe()
        val cache = newCache(probe, privilege)
        assertTrue(cache.isSupported())
        privilege.publish(rooted().copy(rootAvailability = rooted().rootAvailability.copy(
            refreshStatus = RootRefreshStatus.BUSY, revision = 2,
        )))
        assertTrue(runCatching { cache.isSupported() }.exceptionOrNull() is RootAdmissionUnavailable)
        assertEquals(1, probe.probes)
    }

    @Test
    fun `direct invalidation refuses cached capability before the manager mirror catches up`() = runTest {
        val privilege = FakePrivilege(rooted())
        val probe = FakeProbe()
        val cache = newCache(probe, privilege)
        assertTrue(cache.isSupported())
        privilege.root.state.value = privilege.root.state.value.copy(
            refreshStatus = RootRefreshStatus.CHECKING, revision = 2,
        )
        assertTrue(privilege.flow.value.rootAvailability.canAdmitRoot)
        assertTrue(runCatching { cache.isSupported() }.exceptionOrNull() is RootAdmissionUnavailable)
        assertEquals(1, probe.probes)
    }

    @Test
    fun `direct invalidation discards in-flight measurement while manager mirror remains old`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val privilege = FakePrivilege(rooted())
        val probe = FakeProbe().apply { onProbe = { entered.complete(Unit); release.await() } }
        val cache = newCache(probe, privilege)
        val measuring = async { runCatching { cache.isSupported() } }
        entered.await()
        privilege.root.state.value = privilege.root.state.value.copy(
            refreshStatus = RootRefreshStatus.BUSY, revision = 2,
        )
        release.complete(Unit)
        assertTrue(privilege.flow.value.rootAvailability.canAdmitRoot)
        assertTrue(measuring.await().exceptionOrNull() is RootAdmissionUnavailable)
        assertEquals(1, probe.probes)
    }

    @Test
    fun `both capability measurements use the provider captured for their cache key`() = runTest {
        val privilege = FakePrivilege(PrivilegeState(shizuku = true, active = PrivilegeMode.SHIZUKU, isReady = true))
        val probe = FakeProbe()
        assertTrue(newCache(probe, privilege).isSupported())
        assertEquals(listOf(PrivilegeMode.SHIZUKU, PrivilegeMode.SHIZUKU), probe.measuredProviders)
    }

}

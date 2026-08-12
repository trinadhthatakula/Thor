// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.DataArchiveCapabilityCache
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.AppDataProbe
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppDataProbe` fakes the per-class measurement side; `DataArchiveCapabilityCache` is constructed
 * from real objects (a `FakeProbe` + `FakePrivilege`) so the cache's `hasAnyPrivilege` short-circuit
 * is exercised rather than mocked away — that short-circuit is what prevents an `su` prompt on an
 * ungrantd-Magisk device at sheet open.
 */
private class FakeProbe(
    private val sizes: Map<DataClass, DataClassSize> = emptyMap(),
) : AppDataProbe {
    var measured = mutableListOf<DataClass>()

    // `probeDataArchiveCapability` is only called through `DataArchiveCapabilityCache.isSupported()`.
    // These fakes always answer `true`; the capability outcome is controlled by the privilege state.
    override suspend fun probeDataArchiveCapability(): Boolean = true

    override suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize {
        measured += dataClass
        return sizes[dataClass] ?: DataClassSize.Undetermined
    }
}

private class FakePrivilege(initial: PrivilegeState) : PrivilegeStateProvider {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<PrivilegeState> get() = flow
}

/** A rooted ready state: `hasAnyPrivilege = true`, so the cache calls through to the probe. */
private fun rooted() = PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)

/** No active privilege: `hasAnyPrivilege = false`, so the cache short-circuits without any probe. */
private fun noPrivilege() = PrivilegeState(isReady = true)

/** Build the use case with a real `DataArchiveCapabilityCache` backed by the given fake parts. */
private fun makeCase(
    probe: AppDataProbe,
    privilegeState: PrivilegeState = rooted(),
): MeasureAppDataUseCase {
    val cache = DataArchiveCapabilityCache(probe, FakePrivilege(privilegeState))
    return MeasureAppDataUseCase(cache, probe)
}

class MeasureAppDataUseCaseTest {

    @Test
    fun `an unsupported channel measures nothing at all`() = runTest {
        // `noPrivilege()` makes the cache short-circuit on `hasAnyPrivilege` without any shell round
        // trip. Not a cosmetic short-circuit: on a Magisk device where root is installed but not
        // granted, the uncached path reaches `isRootGranted()` and can raise an `su` prompt at sheet
        // open — a permission dialog the user never asked for.
        val probe = FakeProbe()

        val result = makeCase(probe, noPrivilege())("com.example.app")

        assertFalse(result.supported)
        assertTrue(result.sizes.isEmpty())
        assertTrue(probe.measured.toString(), probe.measured.isEmpty())
    }

    @Test
    fun `a supported channel measures every class`() = runTest {
        val probe = FakeProbe(
            sizes = mapOf(
                DataClass.CE to DataClassSize.Known(2048L),
                DataClass.DE to DataClassSize.Empty,
            ),
        )

        val result = makeCase(probe)("com.example.app")

        assertTrue(result.supported)
        assertEquals(DataClass.entries.toSet(), result.sizes.keys)
        assertEquals(DataClassSize.Known(2048L), result.sizes[DataClass.CE])
        assertEquals(DataClassSize.Empty, result.sizes[DataClass.DE])
    }

    @Test
    fun `a class that could not be measured stays Undetermined rather than becoming zero`() = runTest {
        // The whole point of the tri-state. `Known(0)` here is how a user unticks data they have.
        val probe = FakeProbe(sizes = emptyMap())

        val result = makeCase(probe)("com.example.app")

        assertEquals(DataClassSize.Undetermined, result.sizes[DataClass.EXTERNAL_MEDIA])
    }

    @Test
    fun `an unusable package name is refused without a shell round trip`() = runTest {
        val probe = FakeProbe(sizes = mapOf(DataClass.CE to DataClassSize.Known(1L)))

        val result = makeCase(probe)("com.example.app; rm -rf /")

        assertFalse(result.supported)
        assertTrue(probe.measured.isEmpty())
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.repository.AppDataProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AppDataProbe` is a two-method port precisely so this can be faked in six lines. Widening
 * `SystemRepository` instead would have made this test a 40-method stub — see deviation 7.
 */
private class FakeProbe(
    private val supported: Boolean,
    private val sizes: Map<DataClass, DataClassSize> = emptyMap(),
) : AppDataProbe {
    var measured = mutableListOf<DataClass>()

    override suspend fun probeDataArchiveCapability(): Boolean = supported

    override suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize {
        measured += dataClass
        return sizes[dataClass] ?: DataClassSize.Undetermined
    }
}

class MeasureAppDataUseCaseTest {

    @Test
    fun `an unsupported channel measures nothing at all`() = runTest {
        // Not a cosmetic short-circuit: every measurement is a shell round trip, and on a
        // shell-uid Shizuku device all four would fail slowly and render as "unknown".
        val probe = FakeProbe(supported = false)

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertFalse(result.supported)
        assertTrue(result.sizes.isEmpty())
        assertTrue(probe.measured.toString(), probe.measured.isEmpty())
    }

    @Test
    fun `a supported channel measures every class`() = runTest {
        val probe = FakeProbe(
            supported = true,
            sizes = mapOf(
                DataClass.CE to DataClassSize.Known(2048L),
                DataClass.DE to DataClassSize.Empty,
            ),
        )

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertTrue(result.supported)
        assertEquals(DataClass.entries.toSet(), result.sizes.keys)
        assertEquals(DataClassSize.Known(2048L), result.sizes[DataClass.CE])
        assertEquals(DataClassSize.Empty, result.sizes[DataClass.DE])
    }

    @Test
    fun `a class that could not be measured stays Undetermined rather than becoming zero`() = runTest {
        // The whole point of the tri-state. `Known(0)` here is how a user unticks data they have.
        val probe = FakeProbe(supported = true, sizes = emptyMap())

        val result = MeasureAppDataUseCase(probe)("com.example.app")

        assertEquals(DataClassSize.Undetermined, result.sizes[DataClass.EXTERNAL_MEDIA])
    }

    @Test
    fun `an unusable package name is refused without a shell round trip`() = runTest {
        val probe = FakeProbe(supported = true, sizes = mapOf(DataClass.CE to DataClassSize.Known(1L)))

        val result = MeasureAppDataUseCase(probe)("com.example.app; rm -rf /")

        assertFalse(result.supported)
        assertTrue(probe.measured.isEmpty())
    }
}

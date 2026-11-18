// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.kernel

import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootAvailabilityState
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.domain.repository.RootRefreshController
import com.valhalla.thor.domain.repository.RootRefreshRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KernelRootAdmissionTest {
    private val ready = RootAvailabilityState(confirmation = RootConfirmation.ROOT, hasCompletedRefresh = true)

    @Test fun `picker return waits for root observation before entering`() = runTest {
        val completed = CompletableDeferred<RootAvailabilityState>()
        val root = Fake { completed.await() }
        var executions = 0
        val work = async { withKernelRootAdmission(root, root) { executions++; 42 } }
        runCurrent()
        assertEquals(0, executions)
        completed.complete(ready)
        assertEquals(42, work.await())
        assertEquals(1, executions)
    }

    @Test fun `denied or busy root never enters package work`() = runTest {
        for (status in listOf(RootAvailabilityState(confirmation = RootConfirmation.NON_ROOT), ready.copy(refreshStatus = RootRefreshStatus.BUSY))) {
            val root = Fake { status }
            var executed = false
            try {
                withKernelRootAdmission(root, root) { executed = true }
                fail("Expected refusal")
            } catch (_: RootAdmissionUnavailable) { }
            assertFalse(executed)
        }
    }

    @Test fun `a new refresh before admission is joined once`() = runTest {
        val root = Fake { ready }.apply { raceBeforeEntry = true }
        var executions = 0
        withKernelRootAdmission(root, root) { executions++ }
        assertEquals(2, root.refreshes)
        assertEquals(1, executions)
    }

    @Test fun `exception after entry never retries package execution`() = runTest {
        val root = Fake { ready }
        var executions = 0
        try {
            withKernelRootAdmission(root, root) {
                executions++
                throw RootAdmissionUnavailable(ready.copy(refreshStatus = RootRefreshStatus.CHECKING))
            }
            fail("Expected failure")
        } catch (_: RootAdmissionUnavailable) { }
        assertEquals(1, root.refreshes)
        assertEquals(1, executions)
    }

    private class Fake(private val observation: suspend () -> RootAvailabilityState) : RootRefreshController, RootAdmissionController {
        override val state = MutableStateFlow(RootAvailabilityState())
        var refreshes = 0
        var raceBeforeEntry = false
        override suspend fun awaitInitialObservation() = state.value
        override fun requestRefresh() = RootRefreshRequest { refreshes++; observation().also { state.value = it } }
        override fun onRootWorkIdle() = Unit
        override suspend fun <T> withRootAdmission(block: suspend () -> T): T {
            if (raceBeforeEntry) {
                raceBeforeEntry = false
                throw RootAdmissionUnavailable(state.value.copy(refreshStatus = RootRefreshStatus.CHECKING))
            }
            return block()
        }
    }
}

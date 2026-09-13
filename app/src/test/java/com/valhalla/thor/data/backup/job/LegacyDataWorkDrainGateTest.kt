// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyDataWorkDrainGateTest {

    @Test
    fun `nonterminal released data work blocks until the same flow becomes terminal`() = runTest {
        val states = MutableStateFlow(listOf(WorkInfo.State.RUNNING))
        val gate = LegacyDataWorkDrainGate(states)

        val waiter = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitDrained() }
        assertFalse(waiter.isCompleted)

        states.value = listOf(WorkInfo.State.SUCCEEDED)

        assertTrue(waiter.await())
    }

    @Test
    fun `absent and already terminal legacy work open without polling`() = runTest {
        assertTrue(LegacyDataWorkDrainGate(MutableStateFlow(emptyList())).awaitDrained())
        assertTrue(
            LegacyDataWorkDrainGate(
                MutableStateFlow(
                    listOf(
                        WorkInfo.State.SUCCEEDED,
                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED,
                    )
                )
            ).awaitDrained()
        )
    }

    @Test
    fun `only the released data chain states participate in the gate`() = runTest {
        val dataChainStates = MutableStateFlow(emptyList<WorkInfo.State>())
        val gate = LegacyDataWorkDrainGate(dataChainStates)

        // Privilege work has a different chain and is deliberately absent from this gate's input.
        assertTrue(gate.awaitDrained())
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExportBottomSheetTest {

    @Test
    fun `OBB boundary maps every typed execution failure to one stable verdict`() = runTest {
        val verdicts = executionFailures().map { failure ->
            probeObbForPresentation { throw failure }
        }

        assertEquals(
            List(verdicts.size) { ObbProbe.Undetermined(PRESENTATION_OBB_FAILURE_REASON) },
            verdicts,
        )
    }

    @Test
    fun `OBB boundary rethrows cancellation unchanged`() = runTest {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("obb.probe"),
            CancellationException("cancelled"),
        )

        val caught = try {
            probeObbForPresentation { throw cancellation }
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `OBB boundary does not relabel an ordinary programming failure`() = runTest {
        val failure = IllegalStateException("ordinary failure")

        val caught = try {
            probeObbForPresentation { throw failure }
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(failure, caught)
    }

    private fun executionFailures(): List<PrivilegeExecutionException> = listOf(
        ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE),
        ShellLaneDegraded(PrivilegeExecutionLane.ARCHIVE),
        ShellTransportDied(PrivilegeExecutionLane.ARCHIVE),
        ShellCommandTimedOut(PrivilegeCommandClass("obb.probe")),
    )
}

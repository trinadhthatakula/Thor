// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import com.valhalla.thor.R
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InstallerViewModelTest {

    @Test
    fun `installer boundary maps every typed execution failure to stable user text`() = runTest {
        executionFailures().forEach { failure ->
            val bus = InstallerEventBus()

            runInstallerPresentationBoundary(bus) { throw failure }

            assertEquals(
                InstallState.Error(UiText.StringResource(R.string.unknown_error_occurred)),
                bus.latest,
            )
        }
    }

    @Test
    fun `installer boundary rethrows cancellation unchanged`() = runTest {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("installer.root"),
            CancellationException("cancelled"),
        )

        val caught = try {
            runInstallerPresentationBoundary(InstallerEventBus()) { throw cancellation }
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(cancellation, caught)
    }

    @Test
    fun `installer boundary leaves ordinary failures unchanged`() = runTest {
        val failure = IllegalStateException("ordinary failure")

        val caught = try {
            runInstallerPresentationBoundary(InstallerEventBus()) { throw failure }
            null
        } catch (actual: Throwable) {
            actual
        }

        assertSame(failure, caught)
    }

    private fun executionFailures(): List<PrivilegeExecutionException> = listOf(
        ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE),
        ShellLaneDegraded(PrivilegeExecutionLane.INTERACTIVE),
        ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE),
        ShellCommandTimedOut(PrivilegeCommandClass("installer.root")),
    )
}

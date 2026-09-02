// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionException
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellLaneDegraded
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.domain.repository.ArchiveInstallOutcome
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PrivilegeExecutionAdapterTest {

    @Test
    fun `every typed execution failure keeps its identity at every collapsing adapter`() {
        executionFailures().forEach { failure ->
            assertSame(
                failure,
                caught { obbProbeFromExecutionResult(Result.failure(failure)) },
            )
            assertSame(
                failure,
                caught {
                    Result.failure<Pair<Int, String?>>(failure)
                        .getOrNullPreservingPrivilegeExecution()
                },
            )
            assertSame(
                failure,
                caught { rootInstallState(Result.failure(failure)) },
            )
            assertSame(
                failure,
                caught { archiveInstallFailure(failure) },
            )
        }
    }

    @Test
    fun `structured cancellation keeps its identity at every collapsing adapter`() {
        val cancellation = ShellCommandCancelled(
            PrivilegeCommandClass("archive.copy"),
            CancellationException("cancelled"),
        )

        assertSame(
            cancellation,
            caught { obbProbeFromExecutionResult(Result.failure(cancellation)) },
        )
        assertSame(
            cancellation,
            caught {
                Result.failure<Pair<Int, String?>>(cancellation)
                    .getOrNullPreservingPrivilegeExecution()
            },
        )
        assertSame(
            cancellation,
            caught { rootInstallState(Result.failure(cancellation)) },
        )
        assertSame(
            cancellation,
            caught { archiveInstallFailure(cancellation) },
        )
    }

    @Test
    fun `ordinary failures retain each adapter's previous fallback`() {
        val failure = IllegalStateException("ordinary adapter failure")

        assertEquals(
            ObbProbe.Undetermined("ordinary adapter failure"),
            obbProbeFromExecutionResult(Result.failure(failure)),
        )
        assertNull(
            Result.failure<Pair<Int, String?>>(failure)
                .getOrNullPreservingPrivilegeExecution(),
        )
        assertEquals(
            InstallState.Error(UiText.DynamicString("ordinary adapter failure")),
            rootInstallState(Result.failure(failure)),
        )
        assertEquals(
            ArchiveInstallOutcome.Failed("ordinary adapter failure"),
            archiveInstallFailure(failure),
        )
    }

    private fun executionFailures(): List<PrivilegeExecutionException> = listOf(
        ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE),
        ShellLaneDegraded(PrivilegeExecutionLane.ARCHIVE),
        ShellTransportDied(PrivilegeExecutionLane.ARCHIVE),
        ShellCommandTimedOut(PrivilegeCommandClass("archive.copy")),
    )

    private fun caught(block: () -> Unit): Throwable =
        runCatching(block).exceptionOrNull() ?: error("expected an exception")
}

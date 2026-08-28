// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeExecutionTest {

    @Test
    fun `default context is interactive and has no request identity`() {
        val context = PrivilegeExecutionContext()

        assertEquals(PrivilegeExecutionLane.INTERACTIVE, context.lane)
        assertEquals(PrivilegeCommandClass("interactive.command"), context.commandClass)
        assertNull(context.packageName)
        assertNull(context.workRequestId)
        assertNull(context.sweepRequestId)
        assertNull(context.commandTimeout)
    }

    @Test
    fun `sweep context carries work request and sweep request ids`() {
        val workRequestId = UUID.randomUUID()
        val sweepRequestId = UUID.randomUUID()

        val context = PrivilegeExecutionContext(
            lane = PrivilegeExecutionLane.SWEEP,
            commandClass = PrivilegeCommandClass("sweep.clear_cache"),
            packageName = "com.example.app",
            workRequestId = workRequestId,
            sweepRequestId = sweepRequestId,
            commandTimeout = PrivilegeExecutionTimeouts.SWEEP_COMMAND,
        )

        assertEquals(PrivilegeExecutionLane.SWEEP, context.lane)
        assertEquals(workRequestId, context.workRequestId)
        assertEquals(sweepRequestId, context.sweepRequestId)
        assertTrue(context.commandTimeout == 30.seconds)
    }

    @Test
    fun `command class rejects blank values and control characters`() {
        listOf(
            "",
            "   ",
            "archive\nbackup",
            "sweep" + 0.toChar() + "command",
            "interactive" + 127.toChar() + "command",
        )
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) {
                    PrivilegeCommandClass(value)
                }
            }

        assertEquals("archive.backup", PrivilegeCommandClass("archive.backup").value)
    }

    @Test
    fun `shell command cancelled remains a CancellationException`() {
        val cause = CancellationException("worker stopped")
        val failure = ShellCommandCancelled(PrivilegeCommandClass("archive.backup"), cause)

        assertTrue(CancellationException::class.java.isInstance(failure))
        assertSame(cause, failure.cause)
        assertEquals("Root command cancelled: archive.backup", failure.message)
    }

    @Test
    fun `lane status names which background lane owns degraded MainShell`() {
        val status = RootLaneStatus(
            lane = PrivilegeExecutionLane.SWEEP,
            mode = RootLaneMode.DEGRADED,
            activeCommandClass = PrivilegeCommandClass("sweep.clear_cache"),
            fallbackOwner = PrivilegeExecutionLane.SWEEP,
        )

        assertEquals(PrivilegeExecutionLane.SWEEP, status.lane)
        assertEquals(RootLaneMode.DEGRADED, status.mode)
        assertEquals(PrivilegeExecutionLane.SWEEP, status.fallbackOwner)
        assertEquals("sweep.clear_cache", status.activeCommandClass?.value)
    }

    @Test
    fun `timeout and package owner vocabulary is stable`() {
        assertEquals(0.seconds, PrivilegeExecutionTimeouts.INTERACTIVE_ADMISSION)
        assertEquals(2.seconds, PrivilegeExecutionTimeouts.SWEEP_ADMISSION)
        assertEquals(5.seconds, PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION)
        assertEquals(30.seconds, PrivilegeExecutionTimeouts.SWEEP_COMMAND)
        assertEquals(
            listOf(
                PackageOperationOwner.ARCHIVE_BACKUP,
                PackageOperationOwner.ARCHIVE_RESTORE,
                PackageOperationOwner.FREEZE,
                PackageOperationOwner.UNFREEZE,
                PackageOperationOwner.CLEAR_CACHE,
                PackageOperationOwner.CLEAR_DATA,
                PackageOperationOwner.REINSTALL,
                PackageOperationOwner.FORCE_STOP,
                PackageOperationOwner.UNINSTALL,
                PackageOperationOwner.OTHER_MUTATION,
            ),
            PackageOperationOwner.entries,
        )
    }

    @Test
    fun `package lease result distinguishes acquired values from busy owners`() {
        val acquired: PackageLeaseResult<String> = PackageLeaseResult.Acquired("complete")
        val busy: PackageLeaseResult<Nothing> =
            PackageLeaseResult.Busy(PackageOperationOwner.ARCHIVE_RESTORE)

        assertEquals("complete", (acquired as PackageLeaseResult.Acquired).value)
        assertEquals(
            PackageOperationOwner.ARCHIVE_RESTORE,
            (busy as PackageLeaseResult.Busy).owner,
        )
    }
}

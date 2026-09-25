// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellTransportDied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpsControllerTest {
    @Test
    fun `cold start lane contention retries with fresh sessions and target lookup`() = runTest {
        val fixture = Fixture().apply {
            beforeSession = {
                if (sessions < 3) throw ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE)
            }
        }

        assertTrue(fixture.controller().getAppOps(PACKAGE).isSuccess)
        assertEquals(3, fixture.sessions)
        assertEquals(1, fixture.catalogLoads)
        assertEquals(4, fixture.targetReads) // One per attempt plus successful final validation.
        assertEquals(200L, currentTime)
    }

    @Test
    fun `lane contention during snapshot starts a new UID bracket for the current target`() = runTest {
        val fixture = Fixture().apply {
            beforeCommand = {
                if (commands.size == 2) {
                    uid++
                    throw ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE)
                }
            }
        }

        val snapshot = fixture.controller().getAppOps(PACKAGE).getOrThrow()

        assertEquals(12346, snapshot.uid)
        assertEquals(listOf(
            "appops get --user 0 12345",
            "appops get --user 0 $PACKAGE",
            "appops get --user 0 12346",
            "appops get --user 0 $PACKAGE",
            "appops get --user 0 12346",
        ), fixture.commands)
        assertEquals(2, fixture.sessions)
        assertEquals(2, fixture.catalogLoads)
        assertEquals(3, fixture.targetReads)
    }

    @Test
    fun `persistent lane contention stops after the bounded retry budget`() = runTest {
        val busy = ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE)
        val fixture = Fixture().apply { failure = busy }

        assertSame(busy, fixture.controller().getAppOps(PACKAGE).exceptionOrNull())
        assertEquals(5, fixture.sessions)
        assertEquals(5, fixture.commands.size)
        assertEquals(400L, currentTime)
    }

    @Test
    fun `timeouts transport failures malformed snapshots and cancellation are not retried`() = runTest {
        for (failure in listOf(
            ShellCommandTimedOut(PrivilegeCommandClass("app_ops.manage")),
            ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE),
            IllegalStateException("unreadable response"),
        )) {
            val fixture = Fixture().apply { this.failure = failure }
            assertSame(failure, fixture.controller().getAppOps(PACKAGE).exceptionOrNull())
            assertEquals(1, fixture.sessions)
        }
        val malformed = Fixture().apply { forcedReply = 0 to "Permission denied" }
        assertTrue(malformed.controller().getAppOps(PACKAGE).isFailure)
        assertEquals(1, malformed.sessions)

        val cancellation = CancellationException("stop")
        val cancelled = Fixture().apply { failure = cancellation }
        assertSame(cancellation, runCatching { cancelled.controller().getAppOps(PACKAGE) }.exceptionOrNull())
        assertEquals(1, cancelled.sessions)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `cancellation during lane backoff stops before opening another session`() = runTest {
        val fixture = Fixture().apply { failure = ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE) }
        var returnedResult = false
        val read = launch {
            fixture.controller().getAppOps(PACKAGE)
            returnedResult = true
        }
        runCurrent()
        assertEquals(1, fixture.sessions)

        read.cancelAndJoin()

        assertFalse(returnedResult)
        assertTrue(read.isCancelled)
        assertEquals(1, fixture.sessions)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `lane contention never retries a write or its readback`() = runTest {
        // The fourth command is the write; the fifth starts verification after the write applied.
        for (busyAt in listOf(4, 5)) {
            val busy = ShellLaneBusy(PrivilegeExecutionLane.INTERACTIVE)
            val fixture = Fixture().apply {
                beforeCommand = { if (commands.size == busyAt) throw busy }
            }
            val result = fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE)

            assertSame(busy, result.exceptionOrNull())
            assertEquals(1, fixture.sessions)
            assertEquals(1, fixture.commands.count { it.startsWith("appops set ") })
            assertEquals(busyAt, fixture.commands.size)
        }
        assertEquals(0L, currentTime)
    }

    @Test
    fun `package write is verified independently of a masking UID override`() = runTest {
        val fixture = Fixture().apply { uidMode = AppOpMode.DENY }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE).isSuccess)
        val entry = fixture.controller().getAppOps(PACKAGE).getOrThrow().entries.single()
        assertEquals(AppOpMode.IGNORE, entry.packageMode)
        assertEquals(AppOpMode.DENY, entry.displayedMode)
    }

    @Test
    fun `zero exit without changed scope is rejected`() = runTest {
        val fixture = Fixture().apply { discardWrites = true }
        val result = fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE)
        assertTrue(result.isFailure)
        assertTrue(fixture.commands.any { it.startsWith("appops set ") })
    }

    @Test
    fun `UID write cannot be verified by matching package mode`() = runTest {
        val fixture = Fixture().apply { packageMode = AppOpMode.IGNORE; discardWrites = true }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.UID, AppOpMode.IGNORE).isFailure)
    }

    @Test
    fun `reset uses platform default while literal default stays mode three`() = runTest {
        val fixture = Fixture().apply { packageMode = AppOpMode.IGNORE }
        val controller = fixture.controller()
        assertTrue(controller.setMode(PACKAGE, 29, AppOpScope.PACKAGE, null).isSuccess)
        assertTrue(fixture.commands.contains("appops set --user 0 $PACKAGE 29 allow"))
        assertTrue(controller.setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.DEFAULT).isSuccess)
        assertEquals(AppOpMode.DEFAULT, fixture.packageMode)
    }

    @Test
    fun `reset accepts absent UID entry when platform removes default override`() = runTest {
        val fixture = Fixture().apply { uidMode = AppOpMode.IGNORE }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.UID, null).isSuccess)
        assertEquals(null, fixture.uidMode)
    }

    @Test
    fun `wrong Android user is rejected before shell opens`() = runTest {
        val fixture = Fixture().apply { uid = 1012345 }
        assertTrue(fixture.controller().getAppOps(PACKAGE).isFailure)
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.UID, AppOpMode.IGNORE).isFailure)
        assertEquals(0, fixture.sessions)
    }

    @Test
    fun `secondary user commands always name the package user`() = runTest {
        val fixture = Fixture().apply { userId = 10; uid = 1012345 }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.UID, AppOpMode.IGNORE).isSuccess)
        assertTrue(fixture.commands.all { it.contains("--user 10 ") })
        assertTrue(fixture.commands.contains("appops set --user 10 1012345 29 ignore"))
    }

    @Test
    fun `unknown operation mode and nonresettable operation cannot issue writes`() = runTest {
        val fixture = Fixture()
        assertTrue(fixture.controller().setMode(PACKAGE, 999, AppOpScope.PACKAGE, AppOpMode.ALLOW).isFailure)
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.UNKNOWN).isFailure)
        fixture.definition = DEFINITION.copy(allowsReset = false)
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, null).isFailure)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `runtime permission controlled operations reject writes and resets before App Ops commands`() = runTest {
        val fixture = Fixture().apply {
            definition = DEFINITION.copy(isRuntimePermissionControlled = true)
        }
        for (scope in AppOpScope.entries) {
            for (mode in listOf(AppOpMode.ALLOW, AppOpMode.IGNORE, AppOpMode.DEFAULT, null)) {
                val result = fixture.controller().setMode(PACKAGE, 29, scope, mode)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("runtime permissions"))
            }
        }

        assertEquals(8, fixture.sessions)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `runtime permission controlled operations remain readable`() = runTest {
        val fixture = Fixture().apply {
            definition = DEFINITION.copy(isRuntimePermissionControlled = true)
            uidMode = AppOpMode.IGNORE
        }
        val snapshot = fixture.controller().getAppOps(PACKAGE).getOrThrow()

        assertTrue(snapshot.canEdit)
        assertTrue(snapshot.entries.single().definition.isRuntimePermissionControlled)
        assertEquals(AppOpMode.IGNORE, snapshot.entries.single().displayedMode)
    }

    @Test
    fun `uncertain runtime permission policy remains readable but rejects writes and resets`() = runTest {
        val fixture = Fixture().apply {
            definition = DEFINITION.copy(isRuntimePermissionControlUncertain = true)
            uidMode = AppOpMode.IGNORE
        }
        val controller = fixture.controller()
        val snapshot = controller.getAppOps(PACKAGE).getOrThrow()
        assertTrue(snapshot.entries.single().definition.isRuntimePermissionControlUncertain)
        assertEquals(AppOpMode.IGNORE, snapshot.entries.single().displayedMode)
        fixture.commands.clear()

        for (scope in AppOpScope.entries) {
            for (mode in listOf(AppOpMode.ALLOW, AppOpMode.IGNORE, AppOpMode.DEFAULT, null)) {
                val result = controller.setMode(PACKAGE, 29, scope, mode)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("could not be determined"))
            }
        }
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `malformed read or shell failure prevents a write`() = runTest {
        for (reply in listOf(0 to "Permission denied", 1 to "No operations.")) {
            val fixture = Fixture().apply { forcedReply = reply }
            assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE).isFailure)
            assertFalse(fixture.commands.any { it.startsWith("appops set ") })
        }
    }

    @Test
    fun `UID changing between dumps fails rather than inventing a package override`() = runTest {
        val fixture = Fixture().apply { changeUidDuringRead = true }
        assertTrue(fixture.controller().getAppOps(PACKAGE).isFailure)
    }

    @Test
    fun `reinstalled app with different UID cannot acknowledge a write`() = runTest {
        val fixture = Fixture().apply { replaceAfterWrite = true }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE).isFailure)
    }

    @Test
    fun `app replaced during verification cannot acknowledge the old UID write`() = runTest {
        val fixture = Fixture().apply { replaceDuringVerification = true }
        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.UID, AppOpMode.IGNORE).isFailure)
        assertTrue(fixture.commands.any { it.startsWith("appops set ") })
    }

    @Test
    fun `cancellation escapes Result and shared UID packages survive reads`() = runTest {
        val fixture = Fixture()
        val snapshot = fixture.controller().getAppOps(PACKAGE).getOrThrow()
        assertEquals(listOf(PACKAGE, "com.example.shared"), snapshot.sharedUidPackages)
        val cancellation = CancellationException("stop")
        fixture.failure = cancellation
        assertSame(cancellation, runCatching { fixture.controller().getAppOps(PACKAGE) }.exceptionOrNull())
    }

    @Test
    fun `vendor operations are reported while standard scope writes remain verified`() = runTest {
        val fixture = Fixture().apply {
            uidMode = AppOpMode.DENY
            uidVendorRecords = listOf("MIUIOP(10004): ask")
            packageVendorRecords = listOf("MIUIOP(10004): ignore", "MIUIOP(10017): ask")
        }
        val controller = fixture.controller()

        assertTrue(controller.setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE).isSuccess)
        val snapshot = controller.getAppOps(PACKAGE).getOrThrow()
        assertEquals(2, snapshot.unsupportedOperationCount)
        assertEquals(AppOpMode.IGNORE, snapshot.entries.single().packageMode)
        assertEquals(AppOpMode.DENY, snapshot.entries.single().uidMode)
        assertFalse(controller.setMode(PACKAGE, 10004, AppOpScope.PACKAGE, AppOpMode.ALLOW).isSuccess)
        assertFalse(fixture.commands.any { " 10004 " in it })
    }

    @Test
    fun `vendor UID change between bracketed reads blocks standard writes`() = runTest {
        val fixture = Fixture().apply {
            uidVendorRecords = listOf("MIUIOP(10004): ask")
            changeVendorDuringRead = true
        }

        assertTrue(fixture.controller().setMode(PACKAGE, 29, AppOpScope.PACKAGE, AppOpMode.IGNORE).isFailure)
        assertFalse(fixture.commands.any { it.startsWith("appops set ") })
    }

    private class Fixture {
        var userId = 0
        var uid = 12345
        var definition = DEFINITION
        var packageMode: AppOpMode? = null
        var uidMode: AppOpMode? = null
        var discardWrites = false
        var replaceAfterWrite = false
        var replaceDuringVerification = false
        var packageReads = 0
        var changeUidDuringRead = false
        var changeVendorDuringRead = false
        var packageVendorRecords = emptyList<String>()
        var uidVendorRecords = emptyList<String>()
        var forcedReply: Pair<Int, String?>? = null
        var failure: Exception? = null
        var sessions = 0
        var targetReads = 0
        var catalogLoads = 0
        var beforeSession: () -> Unit = {}
        var beforeCommand: () -> Unit = {}
        val commands = mutableListOf<String>()

        fun controller() = AppOpsController(
            currentUserId = { userId },
            loadTarget = {
                targetReads++
                AppOpsTarget(uid, emptySet(), listOf(PACKAGE, "com.example.shared"))
            },
            loadCatalog = { catalogLoads++; listOf(definition) },
            openSession = {
                sessions++
                beforeSession()
                AppOpsCommandSession { command ->
                    commands += command
                    beforeCommand()
                    failure?.let { throw it }
                    forcedReply ?: respond(command)
                }
            },
        )

        private fun respond(command: String): Pair<Int, String?> {
            val parts = command.split(' ')
            val target = parts[4]
            if (parts[1] == "set") {
                if (!discardWrites) {
                    val mode = AppOpMode.fromShellToken(parts[6])
                    if (target == PACKAGE) packageMode = mode else {
                        uidMode = mode.takeUnless { it == definition.platformDefault }
                    }
                }
                if (replaceAfterWrite) uid++
                return 0 to ""
            }
            val uidRecords = buildList {
                uidMode?.let { add("READ_CLIPBOARD: ${it.shellToken}") }
                addAll(uidVendorRecords)
            }
            if (target != PACKAGE) return 0 to uidRecords.joinToString("\n").ifEmpty { "No operations." }
            val output = buildList {
                addAll(uidRecords.mapIndexed { index, record -> if (index == 0) "Uid mode: $record" else record })
                packageMode?.let { add("READ_CLIPBOARD: ${it.shellToken}") }
                addAll(packageVendorRecords)
            }.joinToString("\n").ifEmpty { "No operations." }
            if (changeUidDuringRead) uidMode = AppOpMode.IGNORE
            if (changeVendorDuringRead) uidVendorRecords = listOf("MIUIOP(10004): ignore")
            packageReads++
            if (replaceDuringVerification && packageReads == 2) uid++
            return 0 to output
        }
    }

    companion object {
        private const val PACKAGE = "com.example.app"
        private val DEFINITION = AppOpDefinition(29, "READ_CLIPBOARD", "android:read_clipboard", emptyList(), emptyList(), AppOpMode.ALLOW, true)
    }
}

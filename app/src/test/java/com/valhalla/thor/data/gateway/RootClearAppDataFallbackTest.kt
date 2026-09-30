// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.ShellCommandCancelled
import com.valhalla.thor.domain.model.ShellCommandTimedOut
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.model.ShellTransportDied
import com.valhalla.thor.presentation.FakePreferenceRepository
import com.valhalla.thor.rootservice.IThorRootService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootClearAppDataFallbackTest {
    @Test
    fun `execution failures return unchanged without consulting the root daemon`() = runTest {
        val failures = listOf(
            ShellTransportDied(PrivilegeExecutionLane.INTERACTIVE),
            ShellCommandTimedOut(PrivilegeCommandClass("package.clear-data")),
            ShellLaneBusy(PrivilegeExecutionLane.ARCHIVE),
        )
        for (failure in failures) {
            val service = RecordingRootService()
            val commands = mutableListOf<RootCommand>()
            val gateway = gateway(service, commands) { throw failure }

            val result = gateway.clearAppData(PACKAGE, PrivilegeExecutionContext())

            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, commands.size)
            assertEquals(0, service.binderRequests)
            assertEquals(emptyList<Pair<String, Int>>(), service.clearCalls)
        }
    }

    @Test
    fun `ordinary nonzero shell exit still falls back to the same user daemon call`() = runTest {
        ShadowProcess.setUid(10 * 100_000 + 10_000)
        val service = RecordingRootService()
        val commands = mutableListOf<RootCommand>()
        val gateway = gateway(service, commands) { RootCommandResult(1, emptyList(), emptyList()) }

        gateway.clearAppData(PACKAGE, PrivilegeExecutionContext()).getOrThrow()

        assertEquals(10, thorUserId)
        assertEquals("pm clear --user $thorUserId '$PACKAGE'", commands.single().text)
        assertEquals(1, service.binderRequests)
        assertEquals(listOf(PACKAGE to thorUserId), service.clearCalls)
    }

    @Test
    fun `successful shell clear does not consult the root daemon`() = runTest {
        val service = RecordingRootService()
        val commands = mutableListOf<RootCommand>()
        val gateway = gateway(service, commands) { RootCommandResult(0, emptyList(), emptyList()) }

        gateway.clearAppData(PACKAGE, PrivilegeExecutionContext()).getOrThrow()

        assertEquals(1, commands.size)
        assertEquals(0, service.binderRequests)
        assertEquals(emptyList<Pair<String, Int>>(), service.clearCalls)
    }

    @Test
    fun `cancellation propagates without consulting the root daemon`() = runTest {
        val failure = ShellCommandCancelled(
            PrivilegeCommandClass("package.clear-data"),
            CancellationException("cancelled by caller"),
        )
        val service = RecordingRootService()
        val commands = mutableListOf<RootCommand>()
        val gateway = gateway(service, commands) { throw failure }

        val caught = try {
            gateway.clearAppData(PACKAGE, PrivilegeExecutionContext())
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(failure, caught)
        assertEquals(1, commands.size)
        assertEquals(0, service.binderRequests)
        assertEquals(emptyList<Pair<String, Int>>(), service.clearCalls)
    }

    private fun gateway(
        service: RecordingRootService,
        commands: MutableList<RootCommand>,
        execute: () -> RootCommandResult,
    ) = RootSystemGateway(
        context = ApplicationProvider.getApplicationContext<Application>(),
        rootCommands = object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult {
                commands += command
                return execute()
            }
        },
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
    ).also { gateway ->
        // Supply an already-bound daemon so the test observes the actual fallback call without
        // starting Odin's root process or replacing the gateway's production binding behavior.
        ReflectionHelpers.setField(gateway, "rootService", service)
        ReflectionHelpers.setField(gateway, "isDaemonReset", true)
    }

    private class RecordingRootService : IThorRootService.Default() {
        private val binder = Binder()
        var binderRequests = 0
        val clearCalls = mutableListOf<Pair<String, Int>>()

        override fun asBinder(): IBinder {
            binderRequests++
            return binder
        }

        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean {
            clearCalls += packageName to userId
            return true
        }
    }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}

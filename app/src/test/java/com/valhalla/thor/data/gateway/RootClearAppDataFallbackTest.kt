// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootServiceBinding
import com.valhalla.thor.data.gateway.root.RootServiceConnectionOwner
import com.valhalla.thor.data.gateway.root.TestRootAdmission
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

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
            val gateway = gateway(service, commands, StandardTestDispatcher(testScheduler)) { throw failure }

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
        val gateway = gateway(service, commands, StandardTestDispatcher(testScheduler)) { RootCommandResult(1, emptyList(), emptyList()) }

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
        val gateway = gateway(service, commands, StandardTestDispatcher(testScheduler)) { RootCommandResult(0, emptyList(), emptyList()) }

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
        val gateway = gateway(service, commands, StandardTestDispatcher(testScheduler)) { throw failure }

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
        mainDispatcher: CoroutineDispatcher,
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
        rootAdmission = TestRootAdmission(),
        rootServiceConnection = RootServiceConnectionOwner(
            object : RootServiceBinding {
                override fun bind(connection: ServiceConnection) {
                    connection.onServiceConnected(null, service.asBinder())
                }
                override fun unbind(connection: ServiceConnection) = Unit
            },
            mainDispatcher,
        ),
    )

    private class RecordingRootService : IThorRootService.Default() {
        private val binder = Binder().apply {
            attachInterface(this@RecordingRootService, "com.valhalla.thor.rootservice.IThorRootService")
        }
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

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.gateway.root.TestRootAdmission
import com.valhalla.thor.data.privilege.RootAvailabilityCoordinator
import com.valhalla.thor.data.privilege.RootAvailabilityProbe
import com.valhalla.thor.data.privilege.RootProbeOutcome
import com.valhalla.thor.data.privilege.RootProbeResult
import com.valhalla.thor.domain.gateway.ComponentEnabledState
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.repository.RootAdmissionController
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootMutationAdmissionTest {
    @Test
    fun `availability preserves last confirmation during uncertainty without probing the shell`() = runTest {
        val admission = TestRootAdmission()
        val gateway = gateway(admission) { error("Availability must not submit a shell command") }

        for (status in listOf(RootRefreshStatus.CHECKING, RootRefreshStatus.BUSY, RootRefreshStatus.TIMED_OUT, RootRefreshStatus.FAILED)) {
            admission.state.value = admission.state.value.copy(refreshStatus = status)
            assertTrue(gateway.isRootAvailable(PrivilegeExecutionContext()))
        }

        admission.state.value = admission.state.value.copy(confirmation = RootConfirmation.NON_ROOT)
        assertFalse(gateway.isRootAvailable(PrivilegeExecutionContext()))
    }

    @Test
    fun `every public root mutation refuses unresolved freshness before shell or Binder work`() = runTest {
        val admission = TestRootAdmission()
        val commands = mutableListOf<RootCommand>()
        val gateway = gateway(admission) { command ->
            commands += command
            RootCommandResult(0, emptyList(), emptyList())
        }
        val execution = PrivilegeExecutionContext()
        val operations: List<Pair<String, suspend () -> Result<*>>> = listOf(
            "force stop" to suspend { gateway.forceStopApp(PACKAGE, execution) },
            "root-only cache" to suspend { gateway.clearCache(PACKAGE, execution) },
            "all caches" to suspend { gateway.clearAllCaches(42, execution) },
            "clear data" to suspend { gateway.clearAppData(PACKAGE, execution) },
            "disable" to suspend { gateway.setAppDisabled(PACKAGE, true, execution) },
            "Binder suspend" to suspend { gateway.setAppSuspended(PACKAGE, true, execution) },
            "Binder unsuspend" to suspend { gateway.setAppSuspended(PACKAGE, false, execution) },
            "restrict" to suspend { gateway.setAppRestricted(PACKAGE, true, execution) },
            "reboot" to suspend { gateway.rebootDevice("", execution) },
            "uninstall" to suspend { gateway.uninstallApp(PACKAGE, execution) },
            "install" to suspend { gateway.installApp("/unused.apk", false, null, execution) },
            "split install" to suspend { gateway.installMultipleApks(listOf("/unused.apk"), false, execution = execution) },
            "reinstall" to suspend { gateway.reinstallAppWithGoogle(PACKAGE, execution) },
            "grant" to suspend { gateway.grantPermission(PACKAGE, "android.permission.CAMERA", execution) },
            "revoke" to suspend { gateway.revokePermission(PACKAGE, "android.permission.CAMERA", execution) },
            "component" to suspend { gateway.setComponentEnabled(PACKAGE, ".Main", ComponentEnabledState.ENABLED, 0, execution) },
            "launch" to suspend { gateway.forceLaunchActivity(PACKAGE, ".Main", 0, execution) },
            "stop service" to suspend { gateway.stopService(PACKAGE, ".Service", 0, execution) },
            "raw shell" to suspend { gateway.executeShellCommand("opaque", execution) },
        )

        for (status in listOf(RootRefreshStatus.CHECKING, RootRefreshStatus.BUSY, RootRefreshStatus.TIMED_OUT, RootRefreshStatus.FAILED)) {
            admission.state.value = admission.state.value.copy(refreshStatus = status)
            for ((name, operation) in operations) {
                val failure = operation().exceptionOrNull()
                assertTrue("$name must refuse during $status: $failure", failure is RootAdmissionUnavailable)
                assertEquals(status, (failure as RootAdmissionUnavailable).availability.refreshStatus)
            }
        }

        assertTrue("Neither a shell mutation nor the daemon reset/bind may start", commands.isEmpty())
    }

    @Test
    fun `root-only copy has no admission bypass`() = runTest {
        val admission = TestRootAdmission()
        admission.state.value = admission.state.value.copy(refreshStatus = RootRefreshStatus.CHECKING)
        var dispatched = false
        val gateway = gateway(admission) {
            dispatched = true
            RootCommandResult(0, emptyList(), emptyList())
        }

        val failure = try {
            gateway.copyFile("/source", "/destination", PrivilegeExecutionContext())
            null
        } catch (refused: RootAdmissionUnavailable) {
            refused
        }

        assertTrue(failure is RootAdmissionUnavailable)
        assertFalse(dispatched)
    }

    @Test
    fun `accepted multistep mutation retains admission while refresh reports busy`() = runTest {
        var probes = 0
        val admission = RootAvailabilityCoordinator(
            RootAvailabilityProbe { probes++; RootProbeResult(RootProbeOutcome.ROOT) },
            StandardTestDispatcher(testScheduler),
        )
        admission.refreshAndAwait()
        val commands = mutableListOf<RootCommand>()
        val gateway = gateway(admission) { command ->
            commands += command
            if (commands.size == 1) {
                val pending = admission.requestRefresh()
                assertEquals(RootRefreshStatus.BUSY, pending.await().refreshStatus)
                assertEquals(RootRefreshStatus.BUSY, admission.state.value.refreshStatus)
            }
            // Mirrors router admission: nested work must retain the gateway operation token.
            admission.withRootAdmission { RootCommandResult(0, emptyList(), emptyList()) }
        }

        gateway.clearAllCaches(42, PrivilegeExecutionContext()).getOrThrow()

        assertEquals(listOf("cache.trim", "cache.sweep"), commands.map { it.execution.commandClass.value })
        assertEquals("Refresh must not probe between accepted steps", 1, probes)
        admission.refreshAndAwait()
        assertEquals(2, probes)
        assertTrue(admission.state.value.canAdmitRoot)
    }

    private fun gateway(
        admission: RootAdmissionController,
        commandResult: suspend (RootCommand) -> RootCommandResult,
    ) = RootSystemGateway(
        context = FakeContext(File(".")),
        rootCommands = object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult = commandResult(command)
        },
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
        rootAdmission = admission,
    ).apply { userIdProvider = { 0 } }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}

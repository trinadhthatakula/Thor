// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.privilege

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootAdmissionUnavailable
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootRefreshStatus
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.domain.repository.RootAvailabilityProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Uses Thor's production refresh/admission path; opt in with odinRoot=true and an app root grant. */
@RunWith(AndroidJUnit4::class)
class RootRefreshAdmissionIntegrationTest {
    @Test
    fun acceptedWorkSurvivesRefreshAndNewWorkWaitsForFreshRoot() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val root = requireNotNull(koin.getOrNull<RootAvailabilityProvider>())
        val lanes = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        withTimeout(30_000) {
            lanes.statuses.first { it.values.none { lane -> lane.activeCommandClass != null } }
            assertTrue(manager.refreshAndAwait().rootAvailability.canAdmitRoot)
        }
        val confirmedRevision = root.state.value.confirmedRevision
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markers = File(context.cacheDir, "root-refresh-${UUID.randomUUID()}")
        assertTrue(markers.mkdir())
        val ready = File(markers, "ready")
        val release = File(markers, "release")
        val forbidden = File(markers, "forbidden")
        val script = "printf ready > ${ready.absolutePath.escapeForShell()}; " +
            "while [ ! -f ${release.absolutePath.escapeForShell()} ]; do sleep 0.05; done; printf survived"
        val command = "/system/bin/toybox timeout --foreground -s KILL 45 /system/bin/sh -c ${script.escapeForShell()}"
        val execution = PrivilegeExecutionContext(commandClass = PrivilegeCommandClass("test.root-refresh.held"))
        val pending = async(Dispatchers.IO) {
            withTimeout(55_000) {
                while (true) {
                    val result = gateway.executeShellCommand(command, execution)
                    // This specific refusal precedes dispatch; never retry execution failures.
                    if (result.exceptionOrNull() is ShellLaneBusy) {
                        yield()
                        continue
                    }
                    return@withTimeout result.getOrThrow()
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        }
        var completedNormally = false
        try {
            withTimeout(15_000) { while (!ready.exists() || ready.readText() != "ready") delay(10) }
            val refreshed = withTimeout(10_000) { manager.refreshAndAwait() }
            assertTrue("A busy check must retain confirmed root", refreshed.root)
            assertEquals(RootRefreshStatus.BUSY, refreshed.rootAvailability.refreshStatus)
            assertFalse("Accepted helper still owns the lane", pending.isCompleted)

            val refused = gateway.executeShellCommand("printf unexpected > ${forbidden.absolutePath.escapeForShell()}")
            assertTrue(refused.exceptionOrNull() is RootAdmissionUnavailable)
            assertFalse("New work must not dispatch", forbidden.exists())

            release.writeText("release")
            val result = withTimeout(15_000) { pending.await() }
            assertEquals(0, result.first)
            assertEquals("survived", result.second)
            completedNormally = true
            withTimeout(30_000) {
                root.state.first { it.canAdmitRoot && it.confirmedRevision > confirmedRevision }
                manager.state.first { it.rootAvailability.canAdmitRoot && it.rootAvailability.confirmedRevision > confirmedRevision }
            }
            assertEquals("ready", withTimeout(10_000) { gateway.executeShellCommand("printf ready").getOrThrow().second })
        } finally {
            withContext(NonCancellable) {
                release.writeText("release")
                // Persistent commands retain their lease until drain; leave markers if uncertain.
                withTimeout(60_000) { pending.join() }
                if (completedNormally) markers.deleteRecursively()
            }
        }
    }

    @Test
    fun refreshedRootReopensBothDedicatedLanes() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val koin = GlobalContext.get()
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val first = withTimeout(30_000) { manager.refreshAndAwait() }
        assertTrue(first.rootAvailability.canAdmitRoot)
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        suspend fun checkLanes(): Map<PrivilegeExecutionLane, String> = buildMap {
            for (lane in listOf(PrivilegeExecutionLane.ARCHIVE, PrivilegeExecutionLane.SWEEP)) {
                val result = withTimeout(15_000) {
                    gateway.executeShellCommand("id -u; echo $$", PrivilegeExecutionContext(
                        lane = lane,
                        commandClass = PrivilegeCommandClass("test.root-refresh.lane"),
                    )).getOrThrow()
                }
                assertEquals(0, result.first)
                val lines = result.second.orEmpty().lines()
                assertEquals("0", lines.first())
                assertTrue("Persistent shell PID must be readable", lines.getOrNull(1)?.toLongOrNull() != null)
                assertEquals("The test must use dedicated capacity", RootLaneMode.ISOLATED,
                    statuses.statuses.value.getValue(lane).mode)
                put(lane, lines[1])
            }
        }
        val before = checkLanes()
        assertEquals("The lanes must own separate shells", 2, before.values.toSet().size)
        val refreshed = withTimeout(30_000) { manager.refreshAndAwait() }
        assertTrue(refreshed.rootAvailability.canAdmitRoot)
        assertTrue(refreshed.rootAvailability.confirmedRevision > first.rootAvailability.confirmedRevision)
        val after = checkLanes()
        before.forEach { (lane, pid) ->
            assertTrue("Refresh must replace the $lane shell", after.getValue(lane) != pid)
        }
    }
}

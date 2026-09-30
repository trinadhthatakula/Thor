// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.repository.PreferenceRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Opt-in destructive checks limited to one disposable, debuggable fixture. Run with
 * odinRoot=true and odinClearDataTestPackage=com.valhalla.thor.audit.cleardata.
 * The normal path uses the production root executor. The fallback test substitutes one ordinary
 * nonzero shell result, then uses the real root daemon to clear the fixture's data.
 */
@RunWith(AndroidJUnit4::class)
class RootClearAppDataIntegrationTest {
    private lateinit var context: Context
    private lateinit var executor: RootCommandExecutor
    private lateinit var preferences: PreferenceRepository
    private lateinit var statuses: RootLaneStatusSource

    @Before
    fun requireDisposableFixtureAndAppRoot() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Explicit root clear-data fixture opt-in required",
            arguments.getString("odinRoot") == "true" &&
                arguments.getString("odinClearDataTestPackage") == TARGET,
        )
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertFalse(TARGET == context.packageName)
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertTrue("Fixture must be debuggable", info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse("Fixture must not be a system app", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertTrue("Fixture must be installed", info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertEquals("Fixture must belong to Thor's user", thorUserId, info.uid / 100_000)

        val koin = GlobalContext.get()
        // Complete startup's privilege probe before competing for the interactive command lane.
        withTimeout(30_000) { requireNotNull(koin.getOrNull<PrivilegeManager>()).refreshAndAwait() }
        withTimeout(10_000) {
            assertTrue("Thor itself must have a root shell", getShellAwait().isRoot)
        }
        executor = requireNotNull(koin.getOrNull<RootCommandExecutor>())
        preferences = requireNotNull(koin.getOrNull<PreferenceRepository>())
        statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
    }

    @Test
    fun normalRootClearErasesFixtureMarker() = runBlocking {
        clearFixtureAndVerify(forceShellFailure = false)
    }

    @Test
    fun ordinaryShellFailureFallsBackToRealRootDaemon() = runBlocking {
        clearFixtureAndVerify(forceShellFailure = true)
    }

    private suspend fun clearFixtureAndVerify(forceShellFailure: Boolean) {
        val expectedClear = "pm clear --user $thorUserId '$TARGET'"
        val commands = mutableListOf<RootCommand>()
        var simulatedFailures = 0
        val observedExecutor = object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult {
                commands += command
                when (command.execution.commandClass.value) {
                    "package.clear-data" -> {
                        assertEquals("Only the disposable fixture may be cleared", expectedClear, command.text)
                        if (forceShellFailure) {
                            assertEquals("The shell wipe must never be replayed", 0, simulatedFailures)
                            simulatedFailures++
                            return RootCommandResult(1, emptyList(), listOf("forced ordinary refusal"))
                        }
                    }
                    "root.service.reset" -> assertEquals(
                        "Only Thor's own daemon may be reset for the real AIDL bind",
                        "pkill -f ${context.packageName}:root",
                        command.text,
                    )
                    else -> error("Unexpected root operation in clear-data test: ${command.execution.commandClass.value}")
                }
                return executor.execute(command)
            }
        }
        val gateway = RootSystemGateway(context, observedExecutor, preferences, Dispatchers.IO)
        val marker = "thor-clear-${UUID.randomUUID()}"
        val dataDirectory = fixtureShell("pwd")
        assertTrue("run-as must reach the fixture", dataDirectory.endsWith(TARGET))
        try {
            fixtureShell("mkdir -p files")
            fixtureShell("touch files/$marker")
            assertEquals("The fixture marker must exist before the wipe", "./files/$marker", fixtureShell("find . -name $marker"))

            // Startup observers can issue commands after the privilege snapshot is published.
            // Wait before dispatching the one wipe, rather than retrying a failed mutation.
            withTimeout(30_000) {
                statuses.statuses.first { lanes ->
                    lanes.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass == null &&
                        lanes.values.none { it.fallbackOwner != null }
                }
            }
            withTimeout(45_000) {
                gateway.clearAppData(
                    TARGET,
                    PrivilegeExecutionContext(
                        commandClass = PrivilegeCommandClass("audit.clear-data"),
                        packageName = TARGET,
                    ),
                ).getOrThrow()
            }

            assertEquals("The fixture must remain accessible", dataDirectory, fixtureShell("pwd"))
            assertEquals("A successful clear must erase the marker", "", fixtureShell("find . -name $marker"))
            assertTrue(context.packageManager.getApplicationInfo(TARGET, 0)
                .flags and ApplicationInfo.FLAG_INSTALLED != 0)
            assertEquals(1, commands.count { it.text == expectedClear })
            assertEquals(if (forceShellFailure) 1 else 0, simulatedFailures)
            assertEquals(
                "Only a refused shell wipe should enter the real daemon fallback",
                if (forceShellFailure) 1 else 0,
                commands.count { it.execution.commandClass.value == "root.service.reset" },
            )
        } finally {
            // Cleanup removes only this test's marker; it never issues a second data clear.
            fixtureShell("rm -f files/$marker")
        }
    }

    private fun fixtureShell(arguments: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("/system/bin/toybox timeout 10 run-as $TARGET $arguments")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText().trim()
        }
    }

    private companion object {
        const val TARGET = "com.valhalla.thor.audit.cleardata"
    }
}

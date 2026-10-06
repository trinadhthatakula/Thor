// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.privilege

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.ShellLaneBusy
import com.valhalla.thor.presentation.appList.AppInfoDetailsViewModel
import com.valhalla.thor.presentation.settings.SettingsViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Opt in with odinRoot=true and Thor's own root grant. Exercises production Koin/view-model state
 * under an acknowledged, routed MainShell lease; this does not render or navigate either screen.
 * Only private test markers are written. The helper also has a watchdog if the test is interrupted.
 */
@RunWith(AndroidJUnit4::class)
class SharedPrivilegeContentionIntegrationTest {
    @Test
    fun detailsAndSettingsLoadWhileRoutedMainShellIsOccupied() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val privileges = requireNotNull(koin.getOrNull<PrivilegeManager>())
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val execution = PrivilegeExecutionContext(
            commandClass = PrivilegeCommandClass("test.ui.main-shell-contention"),
        )
        suspend fun awaitMainShellIdle() {
            statuses.statuses.first { lanes ->
                lanes.values.none { status ->
                    status.activeCommandClass != null &&
                        (status.lane == PrivilegeExecutionLane.INTERACTIVE || status.fallbackOwner != null)
                }
            }
        }

        // Await production startup before holding the lane. Root checks during the held lease
        // would themselves reproduce the admission failure that screen loading must now avoid.
        withTimeout(30_000) {
            privileges.state.first { it.isReady }
            awaitMainShellIdle()
            assertTrue("Thor must have an app-authorized root grant", privileges.refreshAndAwait().root)
            // The refresh already probed through the production gateway. Inspect the ready shell
            // without racing another startup observer for a second routed root-probe lease.
            assertTrue("Thor's acquired shell must be root", getShellAwait().isRoot)
            awaitMainShellIdle()
        }

        val markers = File(context.cacheDir, "privilege-contention-${UUID.randomUUID()}")
        assertTrue("Create private helper markers", markers.mkdir())
        val ready = File(markers, "ready")
        val release = File(markers, "release")
        val helper = "printf ready > ${ready.absolutePath.escapeForShell()}; " +
            "while [ ! -f ${release.absolutePath.escapeForShell()} ]; do sleep 0.05; done; " +
            "printf released"
        val command = "/system/bin/toybox timeout --foreground -s KILL 60 " +
            "/system/bin/sh -c ${helper.escapeForShell()}"
        val store = ViewModelStore()
        val helperFinishedNormally = AtomicBoolean(false)
        suspend fun executeHelperAfterAdmission(): Pair<Int, String?> {
            val admissionDeadline = SystemClock.elapsedRealtime() + 10_000L
            while (true) {
                val remaining = admissionDeadline - SystemClock.elapsedRealtime()
                assertTrue("The harmless helper must obtain its lease within ten seconds", remaining > 0)
                withTimeout(remaining) { awaitMainShellIdle() }
                val result = gateway.executeShellCommand(command, execution)
                if (result.exceptionOrNull() is ShellLaneBusy) {
                    // acquireInteractiveImmediately throws before main.execute/job creation.
                    // Only this pre-dispatch rejection is retried: an observer can win the lease
                    // after the idle snapshot. Execution/transport/cancellation failures propagate.
                    yield()
                    continue
                }
                return result.getOrThrow()
            }
        }
        val pending = async(Dispatchers.IO) {
            executeHelperAfterAdmission().also { result ->
                helperFinishedNormally.set(result.first == 0 && result.second == "released")
            }
        }

        try {
            withTimeout(15_000) {
                while (!ready.exists() || ready.readText() != "ready") delay(10)
            }
            assertEquals(
                execution.commandClass,
                statuses.statuses.value.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass,
            )
            val competing = gateway.executeShellCommand("true", execution)
            assertTrue("The routed MainShell lease must be occupied", competing.exceptionOrNull() is ShellLaneBusy)

            val (details, settings) = withContext(Dispatchers.Main) {
                val provider = ViewModelProvider(store, viewModelFactory {
                    initializer { requireNotNull(koin.getOrNull<AppInfoDetailsViewModel>()) }
                    initializer { requireNotNull(koin.getOrNull<SettingsViewModel>()) }
                })
                val details = provider[AppInfoDetailsViewModel::class.java]
                val settings = provider[SettingsViewModel::class.java]
                details.loadAppDetails(context.packageName)
                details to settings
            }
            val loaded = withTimeout(25_000) {
                details.uiState.first { !it.isLoading }
            }
            assertNull("Ordinary details must load during contention", loaded.errorMessage)
            assertNotNull(loaded.detailedInfo)
            assertEquals(context.packageName, loaded.detailedInfo?.appInfo?.packageName)
            withTimeout(10_000) {
                details.uiState.first { it.isRoot }
                // Collecting activates Settings' WhileSubscribed state pipeline.
                settings.uiState.first { it.isRootAvailable }
            }
            assertFalse("The helper must remain held until both view models have loaded", pending.isCompleted)
            assertEquals(
                execution.commandClass,
                statuses.statuses.value.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass,
            )

            release.writeText("release")
            val result = withTimeout(10_000) { pending.await() }
            assertEquals(0, result.first)
            assertEquals("released", result.second)
            assertEquals(0, gateway.executeShellCommand("true", execution).getOrThrow().first)
            assertTrue(withTimeout(30_000) { privileges.refreshAndAwait() }.root)
            assertFalse("Releasing contention must not discard loaded details", details.uiState.value.isLoading)
            assertEquals(context.packageName, details.uiState.value.detailedInfo?.appInfo?.packageName)
            withTimeout(10_000) { settings.uiState.first { it.isRootAvailable } }
        } finally {
            withContext(NonCancellable) {
                try {
                    // Release normally even after an assertion fails; persistent jobs are not
                    // terminated by coroutine cancellation. The watchdog independently bounds it.
                    release.writeText("release")
                    withTimeout(70_000) { pending.join() }
                } finally {
                    withContext(Dispatchers.Main) { store.clear() }
                    // Keep the release marker available if the helper's completion is unknown.
                    if (helperFinishedNormally.get()) markers.deleteRecursively()
                }
            }
        }
    }
}

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in live Odin job tests. No settings mutations; only a disposable stalled process. */
@RunWith(AndroidJUnit4::class)
class SettingsEditorDeadlineTest {
    @Test fun stalledHelperIsReapedBeforeRootLeaseRelease() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("settingsEditorMode")
        assumeTrue(mode == "ROOT" || mode == "ODIN")
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        // Let the application's startup privilege probe release its interactive lease first.
        requireNotNull(koin.getOrNull<PrivilegeManager>()).refreshAndAwait()
        val execution = PrivilegeExecutionContext(commandClass = PrivilegeCommandClass("settings_editor.deadline_test"))
        if (mode == "ROOT") assertTrue(gateway.isRootAvailable(execution))
        val apk = InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir
        val processName = "thor_sett_deadline_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val command = "CLASSPATH='" + apk.replace("'", "'\\''") + "' " + settingsEditorProcessDeadline(
            "/system/bin/app_process /system/bin --nice-name=$processName com.valhalla.thor.data.settingseditor.SettingsEditorStallProbe", 3
        )
        val started = SystemClock.elapsedRealtime()
        val pending = async { gateway.executeShellCommand(command, execution).getOrThrow() }
        withTimeout(10_000) {
            statuses.statuses.first { it.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass == execution.commandClass }
        }
        val competing = gateway.executeShellCommand("true", execution)
        assertTrue("Lease remains held while the helper runs", competing.exceptionOrNull() is ShellLaneBusy)
        val (code, output) = withTimeout(15_000) { pending.await() }
        assertTrue("A killed helper must fail", code != 0)
        assertTrue("Process deadline must bound the job", SystemClock.elapsedRealtime() - started < 15_000)
        val pid = output.orEmpty().lineSequence().single { it.startsWith("THOR_STALL_PID:") }.substringAfter(':').toInt()
        val probe = gateway.executeShellCommand("kill -0 $pid 2>/dev/null", execution).getOrThrow()
        assertTrue("The helper must be gone before another job is admitted", probe.first != 0)
        assertNull(statuses.statuses.value.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass)

        // Cancellation still drains the submitted Odin job. Its process watchdog supplies the bound.
        val cancelled = async { gateway.executeShellCommand(command, execution) }
        withTimeout(10_000) {
            statuses.statuses.first { it.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass == execution.commandClass }
        }
        delay(500)
        val cancellationStarted = SystemClock.elapsedRealtime()
        withTimeout(15_000) { cancelled.cancelAndJoin() }
        assertTrue("Submitted cancellation must drain until the watchdog fires", SystemClock.elapsedRealtime() - cancellationStarted >= 1_500)
        assertNull(statuses.statuses.value.getValue(PrivilegeExecutionLane.INTERACTIVE).activeCommandClass)
        assertTrue(gateway.executeShellCommand("pidof $processName", execution).getOrThrow().first != 0)
        assertEquals(0, gateway.executeShellCommand("true", execution).getOrThrow().first)
    }
}

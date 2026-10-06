// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.utils.escapeForShell
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.settingseditor.readSettingsBootId
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootExecutionObserver
import com.valhalla.thor.domain.model.RootExecutionPolicy
import com.valhalla.thor.domain.model.RootJobOutcome
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatusSource
import com.valhalla.thor.domain.model.ShellLaneBusy
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Production gateway/lanes with harmless shell state and disposable files; opt in with odinRoot. */
@RunWith(AndroidJUnit4::class)
class OdinExecutionPolicyIntegrationTest {
    @Test
    fun appBootIdentityMatchesAuthenticatedRootObservation() = runBlocking<Unit> {
        val fixture = readyFixture()
        val bootId = readSettingsBootId()
        assertNotNull("The application domain must be able to read the kernel boot identity", bootId)
        val canonical = UUID.fromString(requireNotNull(bootId)).toString()
        assertEquals("Persist only a canonical boot identity", canonical, bootId)
        val rootObservation = withTimeout(15_000) {
            fixture.gateway.executeShellCommand("cat /proc/sys/kernel/random/boot_id").getOrThrow()
        }
        assertEquals(0, rootObservation.first)
        assertEquals("The app and authenticated root must observe the same boot", bootId, rootObservation.second?.trim())
    }

    @Test
    fun explicitPolicyIsIndependentOfCommandClassAndLane() = runBlocking<Unit> {
        val fixture = readyFixture()
        val variable = "THOR_POLICY_" + UUID.randomUUID().toString().replace("-", "")
        for (lane in PrivilegeExecutionLane.entries) {
            // The old Settings Editor name convention must no longer choose isolation.
            val persistent = PrivilegeExecutionContext(
                lane = lane,
                commandClass = PrivilegeCommandClass("settings_editor.policy_test"),
            )
            try {
                assertEquals(0, fixture.gateway.executeShellCommand("export $variable=retained", persistent).getOrThrow().first)
                assertEquals("retained", fixture.gateway.executeShellCommand("printf '%s' \"\$$variable\"", persistent).getOrThrow().second)
                val recorded = CompletableDeferred<RootJobOutcome>()
                val isolated = persistent.copy(
                    commandClass = PrivilegeCommandClass("test.explicit-isolation"),
                    rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
                    rootExecutionObserver = object : RootExecutionObserver {
                        override suspend fun onOutcome(outcome: RootJobOutcome) {
                            recorded.complete(outcome)
                        }
                    },
                )
                val result = withTimeout(15_000) {
                    fixture.gateway.executeShellCommand(
                        "export $variable=changed; printf '%s\\n' \"\$$variable\"; printf diagnostic >&2; exit 7",
                        isolated,
                    ).getOrThrow()
                }
                assertEquals("An ordinary nonzero exit stays a command result", 7, result.first)
                val outcome = withTimeout(5_000) { recorded.await() }
                assertEquals(RootJobOutcomeKind.EXITED, outcome.kind)
                assertEquals(7, outcome.exitCode)
                assertEquals(listOf("changed"), outcome.stdout)
                assertEquals(listOf("diagnostic"), outcome.stderr)
                assertTrue(outcome.started)
                assertTrue(outcome.terminationConfirmed)
                assertTrue(outcome.outputDrained)
                assertTrue(outcome.shellReusable)
                assertNull(outcome.failure)
                assertEquals("retained", fixture.gateway.executeShellCommand("printf '%s' \"\$$variable\"", persistent).getOrThrow().second)
                if (lane != PrivilegeExecutionLane.INTERACTIVE) {
                    assertEquals("Exercise the owned $lane shell", RootLaneMode.ISOLATED,
                        fixture.statuses.statuses.value.getValue(lane).mode)
                }
            } finally {
                withContext(NonCancellable) {
                    withTimeout(15_000) {
                        fixture.gateway.executeShellCommand("unset $variable", persistent).getOrThrow()
                    }
                }
            }
        }
    }

    @Test
    fun interactiveCancellationRecordsAcknowledgementBeforeAdmission() =
        cancellationRecordsAcknowledgementBeforeAdmission(PrivilegeExecutionLane.INTERACTIVE)

    @Test
    fun archiveCancellationRecordsAcknowledgementBeforeAdmission() =
        cancellationRecordsAcknowledgementBeforeAdmission(PrivilegeExecutionLane.ARCHIVE)

    @Test
    fun sweepCancellationRecordsAcknowledgementBeforeAdmission() =
        cancellationRecordsAcknowledgementBeforeAdmission(PrivilegeExecutionLane.SWEEP)

    private fun cancellationRecordsAcknowledgementBeforeAdmission(lane: PrivilegeExecutionLane) = runBlocking<Unit> {
        val fixture = readyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markers = File(context.cacheDir, "execution-policy-${UUID.randomUUID()}")
        assertTrue(markers.mkdir())
        val ready = File(markers, "ready")
        val submitted = File(markers, "submitted")
        val forbidden = File(markers, "forbidden")
        val nextSubmitted = File(markers, "next")
        val acknowledgement = CompletableDeferred<RootJobOutcome>()
        val releaseRecording = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<CancellationException>()
        val lastOutcome = AtomicReference<RootJobOutcome?>()
        val leaseHeldWhileRecording = AtomicBoolean()
        val submissionCount = AtomicInteger()
        val commandClass = PrivilegeCommandClass("test.term-resistant-child")
        val persistent = PrivilegeExecutionContext(lane = lane, commandClass = PrivilegeCommandClass("test.policy.next"))
        val shellPid = withTimeout(15_000) {
            fixture.gateway.executeShellCommand("printf '%s' \"\$\$\"", persistent).getOrThrow().second
        }
        assertNotNull(shellPid?.toLongOrNull())
        if (lane != PrivilegeExecutionLane.INTERACTIVE) {
            assertEquals("Exercise the owned $lane shell", RootLaneMode.ISOLATED,
                fixture.statuses.statuses.value.getValue(lane).mode)
        }
        val execution = persistent.copy(
            commandClass = commandClass,
            rootExecutionPolicy = RootExecutionPolicy.ISOLATED,
            rootExecutionObserver = object : RootExecutionObserver {
                override suspend fun beforeSubmit() {
                    submissionCount.incrementAndGet()
                }

                override suspend fun onOutcome(outcome: RootJobOutcome) {
                    lastOutcome.set(outcome)
                    leaseHeldWhileRecording.set(
                        fixture.statuses.statuses.value.getValue(lane).activeCommandClass == commandClass,
                    )
                    acknowledgement.complete(outcome)
                    // Make outcome persistence observable while production still owns its lease.
                    withTimeout(20_000) { releaseRecording.await() }
                }
            },
        )
        // Readiness comes from the child after installing its TERM disposition. Both output
        // writes precede launch; the independent watchdog bounds a failed test's helper too.
        val child = "trap '' TERM; printf '%s:%s\\n' \"\$(id -u)\" \"\$\$\" > ${ready.absolutePath.escapeForShell()}; sleep 45"
        val command = "trap '' TERM; printf 'before\\n'; printf 'error\\n' >&2; " +
            "printf 'submitted\\n' >> ${submitted.absolutePath.escapeForShell()}; " +
            "/system/bin/toybox timeout --foreground -s KILL 45 /system/bin/sh -c ${child.escapeForShell()} & wait"
        val pending = launch(Dispatchers.IO) {
            try {
                fixture.gateway.executeShellCommand(command, execution).getOrThrow()
            } catch (cancelled: CancellationException) {
                terminal.complete(cancelled)
                throw cancelled
            }
        }
        try {
            val identity = withTimeout(15_000) {
                while (!ready.exists() || !ready.readText().trim().matches(Regex("[0-9]+:[0-9]+"))) {
                    assertFalse("The child must acknowledge before the job ends", pending.isCompleted)
                    delay(10)
                }
                ready.readText().trim().split(':')
            }
            assertEquals("The child must execute as root", "0", identity[0])
            val childPid = requireNotNull(identity[1].toLongOrNull())
            assertTrue(childPid > 1)
            val original = CancellationException("Stop acknowledged $lane test child")
            pending.cancel(original)
            val outcome = withTimeout(15_000) { acknowledgement.await() }
            assertTrue("Record completion before releasing the $lane lease", leaseHeldWhileRecording.get())
            assertFalse("Cancellation must wait for outcome recording", pending.isCompleted)
            assertEquals(commandClass, fixture.statuses.statuses.value.getValue(lane).activeCommandClass)
            assertEquals(RootJobOutcomeKind.CANCELLED, outcome.kind)
            assertTrue(outcome.started)
            assertTrue(outcome.terminationConfirmed)
            assertTrue(outcome.outputDrained)
            assertTrue(outcome.shellReusable)
            assertEquals(listOf("before"), outcome.stdout)
            assertEquals(listOf("error"), outcome.stderr)
            if (lane == PrivilegeExecutionLane.INTERACTIVE) {
                val refused = fixture.gateway.executeShellCommand(
                    "printf forbidden > ${forbidden.absolutePath.escapeForShell()}", persistent,
                )
                assertTrue("Recording must still own interactive admission", refused.exceptionOrNull() is ShellLaneBusy)
                assertFalse(forbidden.exists())
            }
            releaseRecording.complete(Unit)
            withTimeout(15_000) { pending.join() }
            val cancellation = withTimeout(5_000) { terminal.await() }
            assertTrue("Keep the caller's original cancellation cause",
                generateSequence<Throwable>(cancellation) { it.cause }.any { it === original })
            assertNull(fixture.statuses.statuses.value.getValue(lane).activeCommandClass)
            val next = withTimeout(15_000) {
                fixture.gateway.executeShellCommand(
                    "printf 'next\\n' >> ${nextSubmitted.absolutePath.escapeForShell()}; printf 'next\\n'; printf '%s\\n' \"\$\$\"",
                    persistent,
                ).getOrThrow()
            }
            assertEquals(0, next.first)
            assertEquals("No stale output and the reusable shell remains owned", listOf("next", shellPid), next.second.orEmpty().lines())
            assertEquals(listOf("submitted"), submitted.readLines())
            assertEquals(listOf("next"), nextSubmitted.readLines())
            assertEquals("Cancelled work must not replay", 1, submissionCount.get())
            val processes = withTimeout(15_000) {
                fixture.gateway.executeShellCommand("/system/bin/toybox ps -A -o PID,STAT", persistent).getOrThrow()
            }
            assertEquals(0, processes.first)
            assertFalse("The acknowledged child must no longer be live", processes.second.orEmpty().lineSequence().any { row ->
                val fields = row.trim().split(Regex("\\s+"))
                fields.size >= 2 && fields[0].toLongOrNull() == childPid && !fields[1].startsWith("Z")
            })
        } finally {
            withContext(NonCancellable) {
                releaseRecording.complete(Unit)
                pending.cancel()
                withTimeout(20_000) { pending.join() }
                // Uncertain termination must retain its resources for diagnosis/reconciliation.
                if (lastOutcome.get()?.cleanupConfirmed == true || submissionCount.get() == 0) {
                    markers.deleteRecursively()
                }
            }
        }
    }

    private suspend fun readyFixture(): Fixture {
        assumeTrue(InstrumentationRegistry.getArguments().getString("odinRoot") == "true")
        val koin = GlobalContext.get()
        val gateway = requireNotNull(koin.getOrNull<RootSystemGateway>())
        val statuses = requireNotNull(koin.getOrNull<RootLaneStatusSource>())
        val manager = requireNotNull(koin.getOrNull<PrivilegeManager>())
        withTimeout(30_000) {
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
            assertTrue(manager.refreshAndAwait().rootAvailability.canAdmitRoot)
            statuses.statuses.first { lanes -> lanes.values.none { it.activeCommandClass != null } }
        }
        return Fixture(gateway, statuses)
    }

    private data class Fixture(val gateway: RootSystemGateway, val statuses: RootLaneStatusSource)
}

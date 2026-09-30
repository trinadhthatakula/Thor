package com.valhalla.thor.data.gateway.root

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.superuser.*
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdinLifecycleIntegrationTest {
    private fun shell(): Shell {
        val root = InstrumentationRegistry.getArguments().getString("odinRoot") == "true"
        val shell = Shell.Builder.create().setTimeout(5)
            .setFlags(if (root) 0 else Shell.FLAG_NON_ROOT_SHELL).build()
        assertEquals("Test must exercise the requested authority", root, shell.isRoot)
        return shell
    }

    @Test fun cancelledPreparedJobNeverRuns() {
        shell().use { s ->
            val handle = s.prepareIsolatedJob("echo forbidden")
            assertTrue(handle.cancel())
            handle.submit()
            val outcome = handle.await(5, TimeUnit.SECONDS)
            assertEquals(JobOutcomeKind.CANCELLED, outcome.kind)
            assertFalse(outcome.started)
            assertTrue(outcome.terminationConfirmed)
            assertTrue(s.newJob().add("true").exec().isSuccess)
        }
    }

    @Test fun cancellationTerminatesGroupAndNextJobHasCleanOutput() {
        shell().use { s ->
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val marker = File(dir, "odin-ready-${System.nanoTime()}")
            try {
                val handle = s.submitIsolated("trap '' TERM", "echo before", "echo error >&2", "sleep 120 &", "echo ready > '${marker.path}'", "wait")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!marker.exists() && !handle.completion.toCompletableFuture().isDone && System.nanoTime() < deadline) Thread.sleep(20)
                assertTrue("Helper must start: ${handle.completion.toCompletableFuture().getNow(null)}", marker.exists())
                assertTrue(handle.cancel())
                assertTrue(handle.cancel())
                val outcome = handle.await(10, TimeUnit.SECONDS)
                assertEquals(outcome.toString(), JobOutcomeKind.CANCELLED, outcome.kind)
                assertTrue(outcome.terminationConfirmed)
                assertTrue(outcome.outputDrained)
                assertTrue(outcome.shellReusable)
                assertEquals(listOf("before"), outcome.stdout)
                assertEquals(listOf("error"), outcome.stderr)
                val lines = arrayListOf<String?>()
                val next = s.newJob().add("echo next").to(lines, null).exec()
                assertEquals(0, next.code)
                assertEquals(listOf("next"), lines)
            } finally { marker.delete() }
        }
    }

    @Test fun isolatedBuiltinsAndCompoundScriptKeepLegacyState() {
        shell().use { s ->
            assertEquals(0, s.newJob().add("export ODIN_LIFECYCLE_TEST=original").exec().code)
            val outcome = s.submitIsolated("cd /", "export ODIN_LIFECYCLE_TEST=changed", "if true; then echo \"\$ODIN_LIFECYCLE_TEST\"; fi", "exit 7").await(10, TimeUnit.SECONDS)
            assertEquals(outcome.toString(), JobOutcomeKind.EXITED, outcome.kind)
            assertEquals(7, outcome.exitCode)
            assertEquals(listOf("changed"), outcome.stdout)
            val lines = arrayListOf<String?>()
            s.newJob().add("echo \"\$ODIN_LIFECYCLE_TEST\"").to(lines, null).exec()
            assertEquals(listOf("original"), lines)
        }
    }

    @Test fun explicitRefreshCreatesAReadyNewGeneration() {
        val first = Shell.refreshRootAvailability()
        assertTrue(first.toString(), first.kind == RootAvailabilityKind.ROOT || first.kind == RootAvailabilityKind.NON_ROOT)
        val old = Shell.shell
        Shell.invalidateRootAvailability()
        val second = Shell.refreshRootAvailability()
        assertTrue(second.toString(), second.kind == RootAvailabilityKind.ROOT || second.kind == RootAvailabilityKind.NON_ROOT)
        assertTrue(second.generation > first.generation)
        assertFalse(old.isAlive)
        assertNotSame(old, Shell.shell)
    }
    @Test fun queuedCancellationDoesNotExecuteLater() {
        shell().use { s ->
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            s.submitTask(object : Shell.Task {
                override fun run(stdin: java.io.OutputStream, stdout: java.io.InputStream, stderr: java.io.InputStream) {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val marker = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "odin-forbidden-${System.nanoTime()}")
            try {
                val queued = s.submitIsolated("echo forbidden > '${marker.path}'")
                assertTrue(queued.cancel())
                assertFalse(queued.await(5, TimeUnit.SECONDS).started)
                release.countDown()
                assertEquals(0, s.newJob().add("true").exec().code)
                assertFalse(marker.exists())
            } finally { release.countDown(); marker.delete() }
        }
    }

    @Test fun survivingChildrenAreRetiredAfterOrdinaryExit() {
        shell().use { s ->
            val outcome = s.submitIsolated("sleep 120 &", "echo child=$!", "exit 3").await(10, TimeUnit.SECONDS)
            assertEquals(outcome.toString(), JobOutcomeKind.EXITED, outcome.kind)
            assertEquals(3, outcome.exitCode)
            assertTrue(outcome.terminationConfirmed)
            assertTrue(outcome.outputDrained)
            assertEquals(0, s.newJob().add("true").exec().code)
        }
    }

    @Test fun shellDeathNeverClaimsSuccessfulExecution() {
        shell().use { s ->
            val marker = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "odin-death-${System.nanoTime()}")
            try {
                val handle = s.submitIsolated("echo ready > '${marker.path}'", "sleep 120")
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(20)
                assertTrue(marker.exists())
                s.close()
                handle.cancel()
                val outcome = handle.await(10, TimeUnit.SECONDS)
                assertNotEquals(JobOutcomeKind.EXITED, outcome.kind)
                assertFalse(outcome.shellReusable)
            } finally { marker.delete() }
        }
    }

    @Test fun concurrentCallersShareOneInitialization() {
        val fresh = Shell.refreshRootAvailability()
        assertTrue(fresh.toString(), fresh.kind == RootAvailabilityKind.ROOT || fresh.kind == RootAvailabilityKind.NON_ROOT)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val shells = (1..8).map { pool.submit<Shell> { Shell.shell } }.map { it.get(10, TimeUnit.SECONDS) }
            shells.forEach { assertSame(shells.first(), it) }
        } finally { pool.shutdownNow() }
    }

    @Test fun initializationTimeoutCleansProcessAndAllowsLaterBuild() {
        val stalled = ProcessBuilder("/system/bin/sh", "-c", "sleep 120").start()
        try {
            try {
                Shell.Builder.create().setTimeout(1).build(stalled)
                fail("Handshake must time out")
            } catch (expected: NoShellException) {
                assertTrue("Owned startup process must die", stalled.waitFor(5, TimeUnit.SECONDS))
            }
            shell().use { assertTrue(it.isAlive) }
        } finally { stalled.destroy() }
    }

    @Test fun refreshRetiresAcceptedWorkWithoutKillingIt() {
        val initial = Shell.refreshRootAvailability()
        assertTrue(initial.toString(), initial.kind == RootAvailabilityKind.ROOT || initial.kind == RootAvailabilityKind.NON_ROOT)
        val s = Shell.shell
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        s.submitTask(object : Shell.Task {
            override fun run(stdin: java.io.OutputStream, stdout: java.io.InputStream, stderr: java.io.InputStream) {
                entered.countDown()
                release.await(15, TimeUnit.SECONDS)
                finished.countDown()
            }
        })
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val busy = Shell.refreshRootAvailability()
            assertEquals(busy.toString(), RootAvailabilityKind.BUSY, busy.kind)
            assertTrue(s.isAlive)
            assertEquals(1L, finished.count)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            val fresh = Shell.refreshRootAvailability()
            assertTrue(fresh.toString(), fresh.kind == RootAvailabilityKind.ROOT || fresh.kind == RootAvailabilityKind.NON_ROOT)
            assertFalse(s.isAlive)
        } finally { release.countDown() }
    }


}

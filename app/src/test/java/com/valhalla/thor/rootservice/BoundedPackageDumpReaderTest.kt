// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol

class BoundedPackageDumpReaderTest {
    @Test
    fun `only full exit-zero output returns and all pipes close`() {
        var command: List<String>? = null
        val process = FakeProcess("complete output\n".byteInputStream())
        val reader = BoundedPackageDumpReader(startProcess = {
            command = it
            process
        })
        assertEquals("complete output\n", reader.read(TARGET).output)
        assertEquals(listOf("dumpsys", "-t", "5", "package", TARGET), command)
        assertTrue(process.inputClosed)
        assertTrue(process.errorClosed)
        assertTrue(process.outputClosed)
    }

    @Test
    fun `output bound counts bytes and never returns a successful prefix`() {
        val exact = BoundedPackageDumpReader(
            startProcess = { FakeProcess("éé".byteInputStream()) }, maxOutputBytes = 4,
        ).read(TARGET)
        assertEquals("éé", exact.output)
        val process = FakeProcess("éé".byteInputStream())
        val exceeded = BoundedPackageDumpReader(startProcess = { process }, maxOutputBytes = 3).read(TARGET)
        assertNull(exceeded.output)
        assertEquals(Protocol.REASON_OUTPUT_LIMIT, exceeded.reason)
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `failed exit malformed encoding and dumpsys timeout marker discard all text`() {
        val fixtures = listOf(
            FakeProcess("valid prefix".byteInputStream(), exitCode = 1) to Protocol.REASON_READ_FAILED,
            FakeProcess(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) to Protocol.REASON_READ_FAILED,
            FakeProcess("prefix\n*** SERVICE DUMP TIMEOUT EXPIRED ***\n".byteInputStream()) to Protocol.REASON_TIMEOUT,
            FakeProcess("  \n".byteInputStream()) to Protocol.REASON_MALFORMED_OUTPUT,
        )
        for ((process, reason) in fixtures) {
            val result = BoundedPackageDumpReader(startProcess = { process }).read(TARGET)
            assertNull(result.output)
            assertEquals(reason, result.reason)
        }
    }

    @Test
    fun `a child that holds its output open is destroyed without waiting forever`() {
        val release = CountDownLatch(1)
        val process = FakeProcess(object : InputStream() {
            override fun read(): Int {
                release.await()
                return -1
            }
        }, onDestroy = { release.countDown() })
        val result = BoundedPackageDumpReader(startProcess = { process }, timeoutMillis = 150).read(TARGET)
        assertNull(result.output)
        assertEquals(Protocol.REASON_TIMEOUT, result.reason)
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `EOF without process exit is not success`() {
        val process = FakeProcess("output ended".byteInputStream(), exits = false)
        val result = BoundedPackageDumpReader(startProcess = { process }, timeoutMillis = 150).read(TARGET)
        assertNull(result.output)
        assertEquals(Protocol.REASON_TIMEOUT, result.reason)
    }

    @Test
    fun `missing canonical state refuses to spawn a process and preserves later admission`() {
        val starts = AtomicInteger()
        val reader = BoundedPackageDumpReader(startProcess = {
            starts.incrementAndGet()
            FakeProcess("good".byteInputStream())
        })
        for (state in listOf(null, canonical().copy(packageName = "com.other.package"), canonical().copy(userId = 10))) {
            val result = reader.read(TARGET, 0) { state }
            assertEquals(Protocol.REASON_CANONICAL_STATE_UNAVAILABLE, result.reason)
            assertNull(result.output)
        }
        assertEquals(0, starts.get())
        val result = reader.read(TARGET, 0) { canonical() }
        assertEquals("good", result.output)
        assertEquals(canonical(), result.canonicalState)
        assertEquals(1, starts.get())
    }

    @Test
    fun `a blocked Binder observation retains admission until it actually returns`() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val starts = AtomicInteger()
        val reader = BoundedPackageDumpReader(startProcess = {
            starts.incrementAndGet()
            FakeProcess("good".byteInputStream())
        }, timeoutMillis = 150)
        try {
            val result = reader.read(TARGET, 0) {
                entered.countDown()
                awaitIgnoringInterrupts(release)
                exited.countDown()
                canonical()
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(Protocol.REASON_TIMEOUT, result.reason)
            assertEquals(Protocol.REASON_BUSY, reader.read(TARGET).reason)
            assertEquals(0, starts.get())
        } finally {
            release.countDown()
        }
        assertTrue(exited.await(1, TimeUnit.SECONDS))
        // The callback latch precedes the worker's outermost finally. Repeated read-only admission
        // checks are bounded here and prove that the actual exit, not caller timeout, releases it.
        var later: PackageDumpRead
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        do {
            later = reader.read(TARGET)
            if (later.reason == Protocol.REASON_BUSY) Thread.yield()
        } while (later.reason == Protocol.REASON_BUSY && System.nanoTime() < deadline)
        assertEquals("good", later.output)
        assertEquals(1, starts.get())
    }

    @Test
    fun `late child startup after timeout is destroyed before reading and never replayed`() {
        val release = CountDownLatch(1)
        val process = FakeProcess("late".byteInputStream())
        val starts = AtomicInteger()
        val reader = BoundedPackageDumpReader(startProcess = {
            starts.incrementAndGet()
            awaitIgnoringInterrupts(release)
            process
        }, timeoutMillis = 150)
        try {
            assertEquals(Protocol.REASON_TIMEOUT, reader.read(TARGET).reason)
            assertEquals(Protocol.REASON_BUSY, reader.read(TARGET).reason)
        } finally {
            release.countDown()
        }
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS))
        assertEquals(1, starts.get())
    }

    @Test
    fun `canonical or spawn exceptions release admission and do not spawn fallback work`() {
        val starts = AtomicInteger()
        val reader = BoundedPackageDumpReader(startProcess = {
            starts.incrementAndGet()
            throw IllegalStateException("cannot start")
        })
        assertEquals(Protocol.REASON_READ_FAILED, reader.read(TARGET, 0) {
            throw IllegalStateException("cannot read framework")
        }.reason)
        assertEquals(0, starts.get())
        repeat(2) { assertEquals(Protocol.REASON_READ_FAILED, reader.read(TARGET).reason) }
        assertEquals(2, starts.get())
    }

    @Test
    fun `invalid request cannot start process or run canonical callback`() {
        val reader = BoundedPackageDumpReader(startProcess = { error("must not start") })
        assertEquals(Protocol.REASON_INVALID_ARGUMENT, reader.read("--all").reason)
        assertEquals(Protocol.REASON_INVALID_ARGUMENT, reader.read(TARGET, -1) {
            error("must not query")
        }.reason)
    }

    private fun canonical() = CanonicalSuspensionState(TARGET, 0, installed = true, suspended = false)

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Models a Binder observation that cannot be cancelled by Thread.interrupt().
            }
        }
    }

    private class FakeProcess(
        input: InputStream,
        private val exitCode: Int = 0,
        private val exits: Boolean = true,
        private val onDestroy: () -> Unit = {},
    ) : Process() {
        val destroyed = CountDownLatch(1)
        var inputClosed = false
        var errorClosed = false
        var outputClosed = false
        private val incoming = object : InputStream() {
            override fun read(): Int = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
            override fun close() {
                inputClosed = true
                input.close()
            }
        }
        private val errors = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() {
                errorClosed = true
                super.close()
            }
        }
        private val outgoing = object : ByteArrayOutputStream() {
            override fun close() {
                outputClosed = true
                super.close()
            }
        }
        override fun getOutputStream(): OutputStream = outgoing
        override fun getInputStream(): InputStream = incoming
        override fun getErrorStream(): InputStream = errors
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (exits) return true
            destroyed.await(timeout, unit)
            return false
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            onDestroy()
            destroyed.countDown()
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    companion object {
        private const val TARGET = "com.example.target"
    }
}

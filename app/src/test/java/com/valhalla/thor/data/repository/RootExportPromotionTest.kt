// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootExportPromotionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `unsupported atomic move copies all bytes and replaces the destination`() = runTest {
        val bytes = ByteArray(25_000) { (it % 251).toByte() }
        val payload = temporary.newFile("payload").apply { writeBytes(bytes) }
        val destination = temporary.newFile("destination.apk").apply {
            writeBytes(ByteArray(30_000) { 1 })
        }

        promoteRootExportPayload(payload, destination, move = ::unsupportedMove)

        assertArrayEquals(bytes, destination.readBytes())
        assertArrayEquals("The caller owns payload cleanup after promotion", bytes, payload.readBytes())
    }

    @Test
    fun `short fallback copy fails and deletes the incomplete destination`() = runTest {
        val payload = temporary.newFile("payload").apply { writeText("complete payload") }
        val destination = temporary.newFile("destination.apk").apply { writeText("old destination") }

        val failure = runCatching {
            promoteRootExportPayload(payload, destination, move = ::unsupportedMove) { _, target ->
                target.writeText("short")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(destination.exists())
        assertEquals("complete payload", payload.readText())
    }

    @Test
    fun `empty payload fallback replaces an existing destination with an empty file`() = runTest {
        val payload = temporary.newFile("payload")
        val destination = temporary.newFile("destination.apk").apply { writeText("old destination") }

        promoteRootExportPayload(payload, destination, move = ::unsupportedMove)

        assertTrue(destination.isFile)
        assertEquals(0L, destination.length())
        assertTrue(payload.isFile)
    }

    @Test
    fun `missing fallback destination is rejected even when the payload is empty`() = runTest {
        val payload = temporary.newFile("payload")
        val destination = File(temporary.root, "destination.apk")

        val failure = runCatching {
            promoteRootExportPayload(payload, destination, move = ::unsupportedMove) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(destination.exists())
        assertTrue(payload.isFile)
    }

    @Test
    fun `fallback copy failure is preserved and its partial destination is deleted`() = runTest {
        val payload = temporary.newFile("payload").apply { writeText("complete payload") }
        val destination = File(temporary.root, "destination.apk")
        val expected = IOException("copy failed after a partial write")

        val failure = runCatching {
            promoteRootExportPayload(payload, destination, move = ::unsupportedMove) { _, target ->
                target.writeText("partial")
                throw expected
            }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertFalse(destination.exists())
        assertEquals("complete payload", payload.readText())
    }

    @Test
    fun `cancellation after first copied chunk preserves cause and removes the partial file`() = runTest {
        val bytes = ByteArray(25_000) { (it % 251).toByte() }
        val payload = temporary.newFile("payload").apply { writeBytes(bytes) }
        val destination = File(temporary.root, "destination.apk")
        val cause = IOException("export owner cancelled")
        val expected = TestCancellation("stop promotion").apply { initCause(cause) }
        var failure: Throwable? = null
        var chunks = 0

        val job = launch {
            failure = runCatching {
                promoteRootExportPayload(payload, destination, move = ::unsupportedMove) { source, target ->
                    copyFileWithVerifiedProgress(source, target, VerifiedProgress { written ->
                        chunks++
                        assertEquals(written, target.length())
                        assertTrue(target.length() in 1 until source.length())
                        currentCoroutineContext().cancel(expected)
                    })
                }
            }.exceptionOrNull()
        }
        job.join()

        assertEquals(1, chunks)
        assertTrue(job.isCancelled)
        assertSame(expected, failure)
        assertSame(cause, failure?.cause)
        assertFalse(destination.exists())
        assertArrayEquals(bytes, payload.readBytes())
    }

    @Test
    fun `unrelated move failure leaves the existing destination and never copies`() = runTest {
        val payload = temporary.newFile("payload").apply { writeText("new payload") }
        val destination = temporary.newFile("destination.apk").apply { writeText("old destination") }
        val expected = IOException("move permission denied")
        var copied = false

        val failure = runCatching {
            promoteRootExportPayload(payload, destination, move = { _, _ -> throw expected }) { _, _ ->
                copied = true
            }
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertFalse(copied)
        assertEquals("old destination", destination.readText())
        assertEquals("new payload", payload.readText())
    }

    internal fun unsupportedMove(source: File, destination: File): Unit =
        throw AtomicMoveNotSupportedException(source.path, destination.path, "test filesystem")

    private class TestCancellation(message: String) : CancellationException(message) {
        // Keep coroutine stacktrace recovery from copying this exception before identity checks.
        @Suppress("unused") private val identity = Any()
    }
}

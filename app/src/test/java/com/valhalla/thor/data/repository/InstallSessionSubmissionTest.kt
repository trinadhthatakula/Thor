// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.InstallSessionUnresolved
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSessionSubmissionTest {
    @Test
    fun `close failure after commit retains submission without fallback or abandon`() = runTest {
        val closeFailure = IOException("owner binder closed")
        val calls = mutableListOf<String>()
        var diagnosed: Throwable? = null

        val submitted = trackInstallSessionSubmission(
            abandon = { calls += "abandon" },
            onSubmissionFailure = { throw AssertionError("Submission already succeeded", it) },
            onCleanupFailure = { diagnosed = it },
            releaseUnsubmitted = {},
            detach = {},
            awaitCompletion = { calls += "terminal" },
        ) { commit ->
            calls += "commit"
            commit {}
            calls += "close"
            throw closeFailure
        }
        if (!submitted) calls += "normal installer"

        assertTrue(submitted)
        assertEquals(listOf("commit", "close", "terminal"), calls)
        assertSame(closeFailure, diagnosed)
    }

    @Test
    fun `failure before commit abandons before reporting failure and permits fallback`() = runTest {
        val setupFailure = IOException("session write refused")
        val calls = mutableListOf<String>()
        var diagnosed: Throwable? = null

        val submitted = trackInstallSessionSubmission(
            abandon = { calls += "abandon" },
            onSubmissionFailure = {
                calls += "failure"
                diagnosed = it
            },
            onCleanupFailure = { throw AssertionError("Nothing was submitted", it) },
            releaseUnsubmitted = {},
            detach = {},
            awaitCompletion = { throw AssertionError("Nothing was submitted") },
        ) {
            calls += "write"
            throw setupFailure
        }
        if (!submitted) calls += "normal installer"

        assertFalse(submitted)
        assertEquals(listOf("write", "abandon", "failure", "normal installer"), calls)
        assertSame(setupFailure, diagnosed)
    }

    @Test
    fun `pre submission failure preserves original exception even if abandon fails`() = runTest {
        val writeFailure = IOException("write failed")
        var abandonCalls = 0

        val failure = runCatching {
            trackInstallSessionSubmission(
                abandon = {
                    abandonCalls++
                    throw IOException("abandon also failed")
                },
                onSubmissionFailure = { throw it },
                onCleanupFailure = { throw AssertionError("Nothing was submitted", it) },
                releaseUnsubmitted = {},
                detach = {},
                awaitCompletion = { throw AssertionError("Nothing was submitted") },
            ) { throw writeFailure }
        }.exceptionOrNull()

        assertSame(writeFailure, failure)
        assertEquals(1, abandonCalls)
    }

    @Test
    fun `cancellation propagates and abandons only an unsubmitted session`() = runTest {
        for (committed in listOf(false, true)) {
            val cancelled = CancellationException("cancelled")
            var abandonCalls = 0
            var completionWaits = 0
            val failure = runCatching {
                trackInstallSessionSubmission(
                    abandon = { abandonCalls++ },
                    onSubmissionFailure = { throw AssertionError("Cancellation must propagate", it) },
                    onCleanupFailure = { throw AssertionError("Cancellation must propagate", it) },
                    releaseUnsubmitted = {},
                    detach = {},
                    awaitCompletion = { completionWaits++ },
                ) { commit ->
                    if (committed) commit {}
                    throw cancelled
                }
            }.exceptionOrNull()

            assertSame(cancelled, failure)
            assertEquals(if (committed) 0 else 1, abandonCalls)
            assertEquals(0, completionWaits)
        }
    }

    @Test
    fun `throwing commit retains ownership without abandon or fallback`() = runTest {
        var released = false
        var detached = false
        val transportFailure = IOException("commit acknowledgement lost")
        val failure = runCatching {
            trackInstallSessionSubmission(
                abandon = { throw AssertionError("Attempted commit must not be abandoned") },
                onSubmissionFailure = { throw AssertionError("Attempted commit must not permit fallback", it) },
                onCleanupFailure = { throw AssertionError("Commit did not return", it) },
                releaseUnsubmitted = { released = true },
                detach = { detached = true },
                awaitCompletion = { throw AssertionError("Commit did not return") },
            ) { commit -> commit { throw transportFailure } }
        }.exceptionOrNull()
        assertTrue(failure is InstallSessionUnresolved)
        assertSame(transportFailure, failure?.cause)
        assertFalse(released)
        assertTrue(detached)
    }

    @Test
    fun `successful submission and close need no error handling`() = runTest {
        val submitted = trackInstallSessionSubmission(
            abandon = { throw AssertionError("Successful session must not be abandoned") },
            onSubmissionFailure = { throw AssertionError("Unexpected submission failure", it) },
            onCleanupFailure = { throw AssertionError("Unexpected cleanup failure", it) },
            releaseUnsubmitted = {},
            detach = {},
            awaitCompletion = {},
        ) { commit -> commit {} }

        assertTrue(submitted)
    }
}

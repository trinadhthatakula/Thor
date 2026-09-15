// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

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
        ) { markSubmitted ->
            calls += "commit"
            markSubmitted()
            calls += "close"
            throw closeFailure
        }
        if (!submitted) calls += "normal installer"

        assertTrue(submitted)
        assertEquals(listOf("commit", "close"), calls)
        assertSame(closeFailure, diagnosed)
    }

    @Test
    fun `failed commit abandons before reporting failure and permits fallback`() = runTest {
        val commitFailure = IOException("commit refused")
        val calls = mutableListOf<String>()
        var diagnosed: Throwable? = null

        val submitted = trackInstallSessionSubmission(
            abandon = { calls += "abandon" },
            onSubmissionFailure = {
                calls += "failure"
                diagnosed = it
            },
            onCleanupFailure = { throw AssertionError("Nothing was submitted", it) },
        ) {
            calls += "commit"
            throw commitFailure
        }
        if (!submitted) calls += "normal installer"

        assertFalse(submitted)
        assertEquals(listOf("commit", "abandon", "failure", "normal installer"), calls)
        assertSame(commitFailure, diagnosed)
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
            val failure = runCatching {
                trackInstallSessionSubmission(
                    abandon = { abandonCalls++ },
                    onSubmissionFailure = { throw AssertionError("Cancellation must propagate", it) },
                    onCleanupFailure = { throw AssertionError("Cancellation must propagate", it) },
                ) { markSubmitted ->
                    if (committed) markSubmitted()
                    throw cancelled
                }
            }.exceptionOrNull()

            assertSame(cancelled, failure)
            assertEquals(if (committed) 0 else 1, abandonCalls)
        }
    }

    @Test
    fun `successful submission and close need no error handling`() = runTest {
        val submitted = trackInstallSessionSubmission(
            abandon = { throw AssertionError("Successful session must not be abandoned") },
            onSubmissionFailure = { throw AssertionError("Unexpected submission failure", it) },
            onCleanupFailure = { throw AssertionError("Unexpected cleanup failure", it) },
        ) { markSubmitted -> markSubmitted() }

        assertTrue(submitted)
    }
}

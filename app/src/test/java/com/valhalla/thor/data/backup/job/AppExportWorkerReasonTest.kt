// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `AppExportWorker` itself needs an Android runtime; [exportFailureReason] is top-level for exactly
 * that reason, and compiles into `AppExportWorkerKt`, so naming it here does not load the worker.
 */
class AppExportWorkerReasonTest {

    @Test
    fun `the builder's space shortfall wording survives verbatim`() {
        // The one export failure a user can act on, and the only place it is phrased is inside
        // AppBundleBuilderImpl. Anything here that paraphrased, prefixed or truncated it would take
        // "about 1.4 GB more is needed" off the screen and leave "Export failed" in its place.
        val message = "not enough free space to pack this app's game data — about 1.4 GB more is needed"

        assertEquals(message, exportFailureReason(IOException(message)))
    }

    @Test
    fun `a cause with no message reports none rather than a class name`() {
        // Not `toString()`. That renders "java.lang.IllegalStateException", which is a sentence about
        // Thor's implementation shown to someone who wanted an APK.
        assertNull(exportFailureReason(IllegalStateException()))
    }

    @Test
    fun `a blank message is treated as no message`() {
        // "Export failed: " with nothing after the colon reads as a bug in Thor, which is worse than
        // the honest "unknown error" the caller substitutes.
        assertNull(exportFailureReason(IllegalStateException("   ")))
        assertNull(exportFailureReason(IllegalStateException("")))
    }

    @Test
    fun `an unbounded message is passed through, because bounding belongs at the Data boundary`() {
        // Deliberately NOT capped here. `fail` and `noteResult` both apply boundedForJobData at the
        // boundary where Data's 10 KB rule lives; a second cap here would silently shorten a message
        // that was going to be shortened correctly anyway, and would drift from the real limit.
        val long = "x".repeat(MAX_JOB_MESSAGE_CHARS * 4)

        assertEquals(long, exportFailureReason(IOException(long)))
        assertEquals(MAX_JOB_MESSAGE_CHARS, long.boundedForJobData().length)
    }
}

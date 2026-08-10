// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Progress lives here rather than in WorkManager's `Data` (§9.2): `setProgress` is an SQLite write
 * per call, throttled to roughly 1/s, so a byte-level bar routed through it is both slow and a write
 * amplifier on a job already saturating the disk.
 */
class JobRegistryTest {

    private val registry = JobRegistry()
    private val jobId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `an observer that subscribes before the job starts sees no progress`() = runTest {
        // The UI collects as soon as it enqueues, which is before the worker's first publish.
        assertNull(registry.progressOf(jobId).value)
    }

    @Test
    fun `published progress reaches an observer that subscribed first`() = runTest {
        val flow = registry.progressOf(jobId)
        val progress = ThorJobProgress(ThorJobStage.CAPTURING, "Capturing", 10, 100)

        registry.publish(jobId, progress)

        assertEquals(progress, flow.value)
    }

    @Test
    fun `one job id is one flow`() = runTest {
        // A second call handing back a different flow is the bug where the UI observes one instance
        // and the worker publishes to another — and it looks exactly like "progress never updates".
        assertSame(registry.progressOf(jobId), registry.progressOf(jobId))
    }

    @Test
    fun `jobs do not see each other's progress`() = runTest {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        registry.publish(jobId, ThorJobProgress(ThorJobStage.WRITING, "One", 1, 2))

        assertNull(registry.progressOf(other).value)
    }

    @Test
    fun `clearing a finished job drops its progress`() = runTest {
        // Otherwise every job Thor has ever run stays in memory until the process dies.
        registry.publish(jobId, ThorJobProgress(ThorJobStage.FINISHING, "Done", 2, 2))

        registry.clear(jobId)

        assertNull(registry.progressOf(jobId).value)
    }
}

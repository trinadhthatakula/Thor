// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * One id must stay one flow **even when a second caller lands between the first one's read and
     * its write** — which `one job id is one flow` above cannot show, because it calls twice in a row
     * and never lands inside anything.
     *
     * Read `JobRegistry.flow` before this: the review finding this row came from — "`getOrPut` is a
     * `get` then a `put`, so two subscribers can end up with different flows" — was **false on this
     * tree**, and the bytecode says so. `flows` is declared `ConcurrentHashMap`, so `getOrPut`
     * resolved to the `ConcurrentMap` overload, which is `putIfAbsent`-based and hands the loser the
     * winner's flow. That is why this row cannot be made to fail against the old code, and it was
     * worth finding out rather than assuming: four timing-based versions were written first — eight
     * threads released onto one absent id by a `CountDownLatch`, the same aligned by a spin on a
     * volatile, forty threads free-running over ten thousand ids, and one thread parked inside an
     * instrumented `get` while another arrived — and every one of them "passed", which looked like
     * flakiness and was in fact the correct answer.
     *
     * What is real is one step further out. Overload resolution reads the **declared type of the
     * receiver**, so widening `flows` to `MutableMap` — a tidy-up that changes no other line, warns
     * about nothing and looks like good practice — silently re-resolves the same source to
     * `Map.put` and creates the bug for real. **That widening is the mutation this row was proven
     * against**, and it is the reason both the field's type and the choice of `computeIfAbsent` are
     * spelled out in `JobRegistry`'s KDoc.
     *
     * The interleaving is forced, not waited for, and it needs no second thread: `JobRegistry`'s map
     * is replaced by a subclass whose `get` calls `progressOf` again, once, on the answer "absent" —
     * precisely the instant a real second caller can land, and before the first caller has stored
     * anything.
     *  - A `get`-then-`put` implementation calls `Map.get`, so the nested call runs, creates and
     *    stores its own flow, and the outer call then stores *over* it and hands its caller the
     *    loser. Two flows, and this fails.
     *  - `computeIfAbsent` never calls the public `get` — it works on the table directly, under the
     *    bin's lock — so nothing nests, the entry is created once, and both calls get it.
     *
     * The reflection is the price of that, and it is a fair one: the field it reaches for is named in
     * the KDoc of the method under test, and if it is ever renamed this fails loudly on the next run
     * rather than quietly passing.
     */
    @Test
    fun `a second arrival between the read and the write cannot produce a second flow`() {
        val registry = JobRegistry()
        var nested: StateFlow<ThorJobProgress?>? = null
        val instrumented = object : ConcurrentHashMap<UUID, MutableStateFlow<ThorJobProgress?>>() {
            private val armed = AtomicBoolean(true)
            override fun get(key: UUID): MutableStateFlow<ThorJobProgress?>? {
                val value = super.get(key)
                // Only the *absent* answer is instrumented: a `get` that found something is not the
                // path this bug lives on, and `armed` keeps the nesting one deep.
                if (value == null && armed.compareAndSet(true, false)) {
                    nested = registry.progressOf(key)
                }
                return value
            }
        }
        JobRegistry::class.java.getDeclaredField("flows").apply { isAccessible = true }
            .set(registry, instrumented)

        val id = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        val first = registry.progressOf(id)
        // Null under an atomic implementation, because nothing above ever fired. That is a pass and
        // not a skip — the assertion still runs, and still requires one id to be one flow.
        val second = nested ?: registry.progressOf(id)

        // Identity, not equality: two empty MutableStateFlows are not `equals`, and the bug is
        // precisely that they are two objects — the UI keeps one and the worker publishes into the
        // other, which looks exactly like "progress never updates".
        assertSame("one job id must be one flow", first, second)
    }
}

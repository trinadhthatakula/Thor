// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason this class exists rather than a `Data` entry: **WorkManager persists `Data` to SQLite**,
 * so a passphrase or derived key put there is written to disk in the clear and survives the job.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler/advanceTimeBy — the house pattern.
class ArchiveKeyHolderTest {

    // One scheduler shared by the holder's expiry timer and by `runTest`, so the tests below drive
    // the timer on virtual time. This is the whole reason the dispatcher is a constructor parameter:
    // the alternative mechanism for the same defect — observing WorkManager's terminal WorkInfo —
    // could not be tested here at all.
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val holder = ArchiveKeyHolder(dispatcher)
    private fun key(byte: Byte) = SecretKeySpec(ByteArray(32) { byte }, "AES")

    @Test
    fun `a job gets back the key it was handed`() {
        holder.put("job-1", key(1))

        assertArrayEquals(ByteArray(32) { 1 }, holder.take("job-1")?.encoded)
    }

    @Test
    fun `a key is single-use`() {
        // Taken once, at the top of the worker. Anything left behind is key material sitting in
        // process memory with no job to use it.
        holder.put("job-1", key(1))
        holder.take("job-1")

        assertNull(holder.take("job-1"))
    }

    @Test
    fun `a job whose process died finds nothing`() {
        // This is the path that forbids Result.retry(): WorkManager would re-run the worker in a
        // fresh process where the key is gone, and a retry that cannot possibly succeed burns the
        // backoff chain and reports failure much later than the truth.
        assertNull(holder.take("job-that-never-ran"))
    }

    @Test
    fun `keys do not leak between jobs`() {
        holder.put("job-1", key(1))
        holder.put("job-2", key(2))

        assertArrayEquals(ByteArray(32) { 2 }, holder.take("job-2")?.encoded)
        assertArrayEquals(ByteArray(32) { 1 }, holder.take("job-1")?.encoded)
    }

    @Test
    fun `dropping a key that was never taken clears it`() {
        // The enqueue path can fail after putting the key — a rejected work request, a cancelled
        // confirm sheet.
        holder.put("job-1", key(1))
        holder.drop("job-1")

        assertNull(holder.take("job-1"))
    }

    /**
     * The branch neither the worker's `finally` nor `ThorJobLauncher.cancel` can reach.
     *
     * `beginUniqueWork(…, APPEND_OR_REPLACE, …)` appends a second request as a **dependent**, and
     * WorkManager cancels the dependents of a prerequisite that returns `Result.failure()`. A backup
     * that fails on a wrong passphrase therefore cancels the restore queued behind it without anything
     * calling `cancel`: `doWork` never runs, so no `finally` runs, and the derived key would otherwise
     * stay in this map until the process died.
     */
    @Test
    fun `a key no job ever takes is dropped when its lifetime runs out`() = runTest(dispatcher) {
        holder.put("job-1", key(1))

        advanceTimeBy(ArchiveKeyHolder.KEY_LIFETIME_MS + 1)

        assertNull(holder.take("job-1"))
    }

    @Test
    fun `a key waiting on the chain survives until its lifetime runs out`() = runTest(dispatcher) {
        // The other half of the rule: the expiry may not shorten a legitimate wait. A job queued
        // behind a long backup must still find its key when WorkManager finally starts it.
        holder.put("job-1", key(1))

        advanceTimeBy(ArchiveKeyHolder.KEY_LIFETIME_MS - 1)

        assertNotNull(holder.take("job-1"))
    }

    @Test
    fun `re-putting an id carries the new key past the old key's timer`() = runTest(dispatcher) {
        // Each entry owns its timer, so a replaced entry has to take its timer with it. Left running,
        // the first put's timer fires on schedule and removes the *second* key — which would look
        // exactly like the bug this expiry exists to fix, in the one case where a key is legitimately
        // held.
        holder.put("job-1", key(1))
        advanceTimeBy(ArchiveKeyHolder.KEY_LIFETIME_MS / 2)
        holder.put("job-1", key(2))

        advanceTimeBy(ArchiveKeyHolder.KEY_LIFETIME_MS / 2 + 1)

        assertArrayEquals(ByteArray(32) { 2 }, holder.take("job-1")?.encoded)
    }
}

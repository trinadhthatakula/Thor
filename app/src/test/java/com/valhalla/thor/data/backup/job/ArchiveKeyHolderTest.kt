// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason this class exists rather than a `Data` entry: **WorkManager persists `Data` to SQLite**,
 * so a passphrase or derived key put there is written to disk in the clear and survives the job.
 */
class ArchiveKeyHolderTest {

    private val holder = ArchiveKeyHolder()
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
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DhizukuOwnerResultsTest {
    private val target = "com.example.target"

    @Test
    fun `clear request stays pending until its package completes`() = runTest {
        lateinit var callback: (String, Boolean) -> Unit
        val result = async { awaitPackageOperation<Boolean>(target, 1_000) { callback = it } }
        runCurrent()
        assertFalse(result.isCompleted)
        callback("com.example.other", true)
        runCurrent()
        assertFalse(result.isCompleted)
        callback(target, true)
        assertEquals(true, result.await())
    }

    @Test
    fun `denial cannot be replaced by a later success callback`() = runTest {
        assertEquals(false, awaitPackageOperation<Boolean>(target, 1_000) { completed ->
            completed(target, false)
            completed(target, true)
        })
    }

    @Test
    fun `a missing callback times out and a late callback cannot change the outcome`() = runTest {
        lateinit var callback: (String, Boolean) -> Unit
        val result = async { awaitPackageOperation<Boolean>(target, 1_000) { callback = it } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(result.await())
        callback(target, true)
        assertNull(result.await())
    }

    @Test
    fun `dispatch failure propagates instead of becoming completion`() = runTest {
        val error = SecurityException("owner authority denied")
        val result = runCatching {
            awaitPackageOperation<Boolean>(target, 1_000) { throw error }
        }
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals(error.message, result.exceptionOrNull()?.message)
    }

    @Test
    fun `caller cancellation remains cancellation`() = runTest {
        val result = async { awaitPackageOperation<Boolean>(target, 1_000) {} }
        runCurrent()
        result.cancel()
        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `uninstall success needs both terminal success and a readable absent package`() {
        assertTrue(uninstallVerified(true, Result.success(false)))
        assertFalse(uninstallVerified(false, Result.success(false)))
        assertFalse(uninstallVerified(true, Result.success(true)))
        assertFalse(uninstallVerified(true, Result.failure(SecurityException("query denied"))))
    }

    @Test
    fun `a refused permission policy cannot become success from an existing grant`() {
        assertFalse(setAndVerifyPermissionState(true, { false }, { true }, { true }))
    }

    @Test
    fun `policy readback and effective runtime grant must both match`() {
        assertTrue(setAndVerifyPermissionState(true, { true }, { true }, { true }))
        assertTrue(setAndVerifyPermissionState(false, { true }, { true }, { false }))
        assertFalse(setAndVerifyPermissionState(true, { true }, { false }, { true }))
        assertFalse(setAndVerifyPermissionState(false, { true }, { true }, { true }))
        assertFalse(setAndVerifyPermissionState(false, { true }, { true }, { null }))
    }

    @Test
    fun `normal output never hides an error or timeout`() {
        assertEquals("partial result\npermission denied", combineProcessOutput("partial result\n", "permission denied\n"))
        assertEquals("partial result\ntimeout", combineProcessOutput("partial result", "", "timeout"))
        assertEquals("permission denied", combineProcessOutput("", "permission denied"))
        assertEquals("", combineProcessOutput("", ""))
    }
}

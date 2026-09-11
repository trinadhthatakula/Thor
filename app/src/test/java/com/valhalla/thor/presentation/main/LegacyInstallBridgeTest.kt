// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyInstallBridgeTest {
    @Test
    fun `one launch claim survives recomposition and stale results cannot finish it`() = runTest {
        val bridge = LegacyInstallBridge(SavedStateHandle())
        val result = async { bridge.install("content://base.apk") }
        runCurrent()
        val id = bridge.request.value!!.id
        assertTrue(bridge.claimLaunch(id))
        assertFalse(bridge.claimLaunch(id))
        assertFalse(bridge.complete("stale", Result.success(true)))
        assertFalse(result.isCompleted)
        bridge.complete(id, Result.success(true))
        assertTrue(result.await().getOrThrow())
        assertNull(bridge.request.value)
    }

    @Test
    fun `cancelled installer returns false and clears saved request`() = runTest {
        val saved = SavedStateHandle()
        val bridge = LegacyInstallBridge(saved)
        val result = async { bridge.install("content://base.apk") }
        runCurrent()
        bridge.complete(bridge.request.value!!.id, Result.success(false))
        assertFalse(result.await().getOrThrow())
        assertTrue(saved.keys().isEmpty())
    }

    @Test
    fun `process restoration never replays old install and waits for resume to clear it`() = runTest {
        val saved = SavedStateHandle()
        val original = LegacyInstallBridge(saved)
        val result = async { original.install("content://base.apk") }
        runCurrent()
        val restored = LegacyInstallBridge(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertFalse(restored.claimLaunch(restored.request.value!!.id))
        assertTrue(restored.recoverOnResume())
        assertNull(restored.request.value)
        assertFalse(restored.recoverOnResume())
        result.cancel()
    }

    @Test
    fun `duplicate request is refused and coroutine cancellation clears pending state`() = runTest {
        val saved = SavedStateHandle()
        val bridge = LegacyInstallBridge(saved)
        val result = async { bridge.install("content://base.apk") }
        runCurrent()
        assertTrue(runCatching { bridge.install("content://other.apk") }.exceptionOrNull() is IllegalStateException)
        result.cancel()
        runCurrent()
        assertNull(bridge.request.value)
        assertTrue(saved.keys().isEmpty())
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LegacyInstallRequest(val id: String, val uri: String, val launched: Boolean = false)

internal class LegacyInstallBridge(private val savedState: SavedStateHandle) {
    private val restored = savedState.get<ArrayList<String>>(STATE_KEY)
    private var recovering = restored != null
    private val _request = MutableStateFlow(restored?.let {
        LegacyInstallRequest(it[0], it[1], launched = true)
    })
    val request = _request.asStateFlow()
    private var completion: CompletableDeferred<Result<Boolean>>? = null

    suspend fun install(uri: String): Result<Boolean> {
        check(_request.value == null) { "An installer request is already pending" }
        val pending = LegacyInstallRequest(UUID.randomUUID().toString(), uri)
        val result = CompletableDeferred<Result<Boolean>>()
        completion = result
        savedState[STATE_KEY] = arrayListOf(pending.id, pending.uri)
        _request.value = pending
        return try {
            result.await()
        } finally {
            if (_request.value?.id == pending.id) clear()
        }
    }

    fun claimLaunch(id: String): Boolean {
        val pending = _request.value ?: return false
        if (pending.id != id || pending.launched) return false
        _request.value = pending.copy(launched = true)
        return true
    }

    fun complete(id: String, result: Result<Boolean>): Boolean {
        if (_request.value?.id != id) return false
        val waiting = completion
        if (waiting != null) {
            waiting.complete(result)
            return false
        }
        return recoverOnResume()
    }

    // Process death loses the coroutine and its package lease. Never replay the installer or
    // continue the batch; wait for the host to resume, then report an interrupted operation.
    fun recoverOnResume(): Boolean {
        if (!recovering) return false
        recovering = false
        clear()
        return true
    }

    private fun clear() {
        completion = null
        _request.value = null
        savedState.remove<ArrayList<String>>(STATE_KEY)
    }

    private companion object {
        const val STATE_KEY = "legacy_fix_store_request"
    }
}

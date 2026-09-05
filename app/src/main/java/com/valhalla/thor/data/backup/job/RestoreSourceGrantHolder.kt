// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.net.Uri
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.koin.core.annotation.Single

/**
 * Process-local hand-off for a restore source selected by the foreground UI.
 *
 * Only the random token is allowed to cross the task-control boundary. The URI is consumed once by
 * source staging and is never encoded into a Room row or service intent.
 */
@Single
internal class RestoreSourceGrantHolder {
    private data class GrantKey(val taskId: UUID, val token: String)

    private val grants = ConcurrentHashMap<GrantKey, String>()
    private val currentTokens = ConcurrentHashMap<UUID, String>()

    fun register(taskId: UUID, uri: Uri): String = register(taskId, uri.toString())

    internal fun register(taskId: UUID, uriString: String): String {
        val token = UUID.randomUUID().toString()
        grants[GrantKey(taskId, token)] = uriString
        currentTokens[taskId] = token
        return token
    }

    /** Returns and removes exactly the grant registered for this task and token. */
    fun take(taskId: UUID, token: String): String? {
        val uri = grants.remove(GrantKey(taskId, token)) ?: return null
        currentTokens.remove(taskId, token)
        return uri
    }

    /** Returns only the opaque process-local capability, never the raw URI. */
    fun currentToken(taskId: UUID): String? = currentTokens[taskId]

    /** Removes every unconsumed grant owned by a cancelled or terminal task. */
    fun dropTask(taskId: UUID) {
        currentTokens.remove(taskId)
        grants.keys.removeIf { it.taskId == taskId }
    }
}

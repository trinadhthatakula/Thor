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

    private val grants = ConcurrentHashMap<GrantKey, Uri>()

    fun register(taskId: UUID, uri: Uri): String {
        val token = UUID.randomUUID().toString()
        grants[GrantKey(taskId, token)] = uri
        return token
    }

    /** Returns and removes exactly the grant registered for this task and token. */
    fun take(taskId: UUID, token: String): Uri? = grants.remove(GrantKey(taskId, token))

    /** Consumes the sole transient source owned by a freshly accepted restore task. */
    fun takeForTask(taskId: UUID): Uri? {
        val key = grants.keys.firstOrNull { it.taskId == taskId } ?: return null
        return grants.remove(key)
    }

    /** Removes every unconsumed grant owned by a cancelled or terminal task. */
    fun dropTask(taskId: UUID) {
        grants.keys.removeIf { it.taskId == taskId }
    }
}

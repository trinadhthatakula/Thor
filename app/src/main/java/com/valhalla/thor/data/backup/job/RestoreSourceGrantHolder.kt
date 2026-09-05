// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import android.net.Uri
import java.util.UUID
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

    private val lock = Any()
    private val grants = mutableMapOf<GrantKey, String>()
    private val currentTokens = mutableMapOf<UUID, String>()
    private val authorizedTokens = mutableMapOf<UUID, String>()

    fun register(taskId: UUID, uri: Uri): String = register(taskId, uri.toString())

    internal fun register(taskId: UUID, uriString: String): String = synchronized(lock) {
        val token = UUID.randomUUID().toString()
        currentTokens.remove(taskId)?.let { previous -> grants.remove(GrantKey(taskId, previous)) }
        grants[GrantKey(taskId, token)] = uriString
        currentTokens[taskId] = token
        authorizedTokens.remove(taskId)
        token
    }

    /** Registers the source already authorized by initial task acceptance. */
    fun registerAuthorized(taskId: UUID, uri: Uri): String =
        registerAuthorized(taskId, uri.toString())

    internal fun registerAuthorized(taskId: UUID, uriString: String): String {
        val token = register(taskId, uriString)
        check(authorize(taskId, token))
        return token
    }

    /** Binds a pending UI grant to the exact token whose task transition is being resumed. */
    fun authorize(taskId: UUID, token: String): Boolean = synchronized(lock) {
        if (currentTokens[taskId] != token || GrantKey(taskId, token) !in grants) return false
        authorizedTokens[taskId] = token
        true
    }

    fun revokeAuthorization(taskId: UUID, token: String) = synchronized(lock) {
        authorizedTokens.remove(taskId, token)
        Unit
    }

    /** Returns and removes exactly the authorized grant registered for this task and token. */
    fun take(taskId: UUID, token: String): String? = synchronized(lock) {
        if (authorizedTokens[taskId] != token) return null
        val uri = grants.remove(GrantKey(taskId, token)) ?: return null
        authorizedTokens.remove(taskId, token)
        currentTokens.remove(taskId, token)
        uri
    }

    /** Returns only an authorized process-local capability, never the raw URI. */
    fun currentToken(taskId: UUID): String? = synchronized(lock) {
        authorizedTokens[taskId]?.takeIf { currentTokens[taskId] == it }
    }

    /** Removes only the generation captured by a retiring claim. */
    fun drop(taskId: UUID, expectedToken: String): Boolean = synchronized(lock) {
        val removed = grants.remove(GrantKey(taskId, expectedToken)) != null
        authorizedTokens.remove(taskId, expectedToken)
        currentTokens.remove(taskId, expectedToken)
        removed
    }

    /** Removes every unconsumed grant owned by a cancelled or terminal task. */
    fun dropTask(taskId: UUID) {
        synchronized(lock) {
            currentTokens.remove(taskId)
            authorizedTokens.remove(taskId)
            grants.keys.removeAll { it.taskId == taskId }
        }
    }
}

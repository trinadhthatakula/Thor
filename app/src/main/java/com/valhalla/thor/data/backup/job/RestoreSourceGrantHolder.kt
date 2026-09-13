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
    private val authorizedTokens = mutableMapOf<UUID, String>()

    fun register(taskId: UUID, uri: Uri): String = register(taskId, uri.toString())

    internal fun register(taskId: UUID, uriString: String): String = synchronized(lock) {
        UUID.randomUUID().toString().also { token ->
            grants[GrantKey(taskId, token)] = uriString
        }
    }

    /** Registers the source already authorized by initial task acceptance. */
    fun registerAuthorized(taskId: UUID, uri: Uri): String =
        registerAuthorized(taskId, uri.toString())

    internal fun registerAuthorized(taskId: UUID, uriString: String): String {
        val token = register(taskId, uriString)
        if (!authorize(taskId, token)) {
            drop(taskId, token)
            error("Restore source already authorized for task $taskId")
        }
        return token
    }

    /** Atomically nominates the first exact pending token as this task's authorized source. */
    fun authorize(taskId: UUID, token: String): Boolean = synchronized(lock) {
        if (GrantKey(taskId, token) !in grants) return false
        when (authorizedTokens[taskId]) {
            null -> {
                authorizedTokens[taskId] = token
                true
            }

            token -> true
            else -> false
        }
    }

    /** Returns and removes exactly the authorized grant registered for this task and token. */
    fun take(taskId: UUID, token: String): String? = synchronized(lock) {
        if (authorizedTokens[taskId] != token) return null
        val uri = grants.remove(GrantKey(taskId, token)) ?: return null
        authorizedTokens.remove(taskId, token)
        uri
    }

    /** Returns only an authorized process-local capability, never the raw URI. */
    fun currentToken(taskId: UUID): String? = synchronized(lock) {
        authorizedTokens[taskId]?.takeIf { GrantKey(taskId, it) in grants }
    }

    /** Removes the exact pending grant unless a durable resume already authorized it. */
    fun dropIfUnauthorized(taskId: UUID, expectedToken: String): Boolean = synchronized(lock) {
        if (authorizedTokens[taskId] == expectedToken) return false
        dropLocked(taskId, expectedToken)
    }

    /** Removes only the generation captured by a retiring claim. */
    fun drop(taskId: UUID, expectedToken: String): Boolean = synchronized(lock) {
        dropLocked(taskId, expectedToken)
    }

    private fun dropLocked(taskId: UUID, expectedToken: String): Boolean {
        val removed = grants.remove(GrantKey(taskId, expectedToken)) != null
        authorizedTokens.remove(taskId, expectedToken)
        return removed
    }

    /** Removes every unconsumed grant owned by a cancelled or terminal task. */
    fun dropTask(taskId: UUID) {
        synchronized(lock) {
            authorizedTokens.remove(taskId)
            grants.keys.removeAll { it.taskId == taskId }
        }
    }
}

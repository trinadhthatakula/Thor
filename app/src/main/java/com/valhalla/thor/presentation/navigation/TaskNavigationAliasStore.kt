// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.annotation.SuppressLint
import android.content.Context
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** Durable aliases for caller-owned task IDs that coalesced onto an existing Room task. */
interface TaskNavigationAliasStore {
    suspend fun record(provisionalTaskId: UUID, canonicalTaskId: UUID)
    fun canonicalTaskId(provisionalTaskId: UUID): UUID?
}

@Single(binds = [TaskNavigationAliasStore::class])
internal class SharedPreferencesTaskNavigationAliasStore(
    context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : TaskNavigationAliasStore {
    private val preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    @SuppressLint("UseKtx") // commit() exposes write failure while preserving persistence-before-publication.
    override suspend fun record(provisionalTaskId: UUID, canonicalTaskId: UUID) {
        if (provisionalTaskId == canonicalTaskId) return
        withContext(ioDispatcher) {
            if (!preferences.edit()
                    .putString(key(provisionalTaskId), canonicalTaskId.toString())
                    .commit()
            ) {
                Logger.e(TAG, "failed to persist task navigation alias for $provisionalTaskId")
            }
        }
    }

    override fun canonicalTaskId(provisionalTaskId: UUID): UUID? =
        preferences.getString(key(provisionalTaskId), null)
            ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

    private fun key(taskId: UUID): String = "$KEY_PREFIX$taskId"

    private companion object {
        const val TAG = "TaskNavigationAlias"
        const val STORE_NAME = "task_navigation_aliases"
        const val KEY_PREFIX = "canonical_"
    }
}

internal class InMemoryTaskNavigationAliasStore : TaskNavigationAliasStore {
    private val aliases = mutableMapOf<UUID, UUID>()

    override suspend fun record(provisionalTaskId: UUID, canonicalTaskId: UUID) {
        if (provisionalTaskId != canonicalTaskId) aliases[provisionalTaskId] = canonicalTaskId
    }

    override fun canonicalTaskId(provisionalTaskId: UUID): UUID? = aliases[provisionalTaskId]
}

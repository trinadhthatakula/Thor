// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.ThorJobProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.annotation.Single

/**
 * Where a running job's progress lives: in memory, for the life of the process.
 *
 * **Not `setProgress`.** Every `setProgress` call is a write to WorkManager's SQLite database, so
 * WorkManager throttles observers to roughly one update a second — a backup that copies a gigabyte in
 * 1 MiB chunks would try to write a thousand rows. §9.2 puts progress here instead: the worker
 * publishes, the ViewModel collects the same `StateFlow`, and nothing touches the disk.
 *
 * The cost of that choice is that progress does not survive process death. That is acceptable because
 * a killed archive job cannot resume anyway ([ArchiveKeyHolder] holds its key in this same process),
 * so there is no state worth persisting. WorkManager's own `WorkInfo.State` — which *is* persisted —
 * remains the source of truth for "is it running, did it succeed".
 */
@Single
class JobRegistry {

    private val flows = ConcurrentHashMap<UUID, MutableStateFlow<ThorJobProgress?>>()

    /**
     * The flow for [jobId], created on first use.
     *
     * Returns the same instance every call, so a collector that subscribes *before* the worker starts
     * still sees the first published value. A new flow per call would drop everything published in
     * between.
     */
    fun progressOf(jobId: UUID): StateFlow<ThorJobProgress?> = flow(jobId)

    fun publish(jobId: UUID, progress: ThorJobProgress) {
        flow(jobId).value = progress
    }

    /** Call when a job reaches a terminal state, or every job Thor ever ran stays in memory. */
    fun clear(jobId: UUID) {
        flows.remove(jobId)
    }

    private fun flow(jobId: UUID) = flows.getOrPut(jobId) { MutableStateFlow(null) }
}

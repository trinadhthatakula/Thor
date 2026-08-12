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
 * **Not `setProgress`.** Every `setProgress` call is a write to WorkManager's SQLite database, and a
 * backup that copies a gigabyte in 1 MiB chunks would write a thousand rows — with an observer
 * emission behind each one, because WorkManager does **not** coalesce them. (An earlier version of
 * this comment claimed WorkManager throttles observers to about one update a second. It does not;
 * that guarantee was invented, and the throttling in this feature is Thor's own, in
 * `ThorJobWorker`'s notification publish.) §9.2 puts progress here instead: the worker publishes, the
 * ViewModel collects the same `StateFlow`, and nothing touches the disk.
 *
 * The cost of that choice is that progress does not survive process death. That is acceptable because
 * a killed archive job cannot resume anyway ([ArchiveKeyHolder] holds its key in this same process),
 * so there is no state worth persisting. WorkManager's own `WorkInfo.State` — which *is* persisted —
 * remains the source of truth for "is it running, did it succeed".
 */
@Single
class JobRegistry {

    /**
     * Declared as [ConcurrentHashMap], **not** as `MutableMap`, and that is not a style choice —
     * see [flow].
     */
    private val flows = ConcurrentHashMap<UUID, MutableStateFlow<ThorJobProgress?>>()

    /**
     * The flow for [jobId], created on first use.
     *
     * Returns the same instance every call, so a collector that subscribes *before* the worker starts
     * still sees the first published value. A new flow per call would drop everything published in
     * between.
     *
     * The two callers are on different threads by design — the ViewModel subscribes on the main
     * thread while the worker publishes from its own — which is the whole reason this map is
     * concurrent and the reason [flow] is written the way it is.
     */
    fun progressOf(jobId: UUID): StateFlow<ThorJobProgress?> = flow(jobId)

    fun publish(jobId: UUID, progress: ThorJobProgress) {
        flow(jobId).value = progress
    }

    /** Call when a job reaches a terminal state, or every job Thor ever ran stays in memory. */
    fun clear(jobId: UUID) {
        flows.remove(jobId)
    }

    /**
     * `computeIfAbsent`, and the declared type of [flows] above is part of the same decision.
     *
     * The obvious spelling is `flows.getOrPut(jobId) { MutableStateFlow(null) }`, and — read
     * carelessly — that looks like the classic non-atomic `get`-then-`put`. It is not, *here*.
     * Kotlin's default imports carry **two** `getOrPut`s, and overload resolution picks between them
     * on the **declared type of the receiver**:
     *  - `MutableMap<K, V>.getOrPut` is `get`, then `put`. Two callers arriving at one absent key
     *    both build a flow, the second `put` wins, and whoever holds the first is holding a flow
     *    nothing will ever emit into — a progress bar frozen at its initial value for the whole of a
     *    backup that runs correctly to completion.
     *  - `ConcurrentMap<K, V>.getOrPut` (`kotlin.collections`, JVM) is
     *    `get(key) ?: defaultValue().let { putIfAbsent(key, it) ?: it }`. The loser of the race is
     *    handed the *winner's* flow and throws its own away, which is the guarantee [progressOf]
     *    needs. Verified in the emitted bytecode, not inferred: this call site compiles to
     *    `ConcurrentMap.putIfAbsent`.
     *
     * So the field's declared type is load-bearing. Widen it to `MutableMap` — the ordinary
     * "program to the interface" tidy-up, which changes no other line and produces no warning — and
     * the same source silently recompiles to `Map.put`, turning the safe version into the bug
     * described above. `JobRegistryTest` pins exactly that, and that widening is the mutation it was
     * proven against.
     *
     * `computeIfAbsent` is preferred over relying on the resolution because it cannot be quietly
     * re-resolved: it is a member of the concurrent map, not an extension chosen by static type, so
     * the same widening becomes a compile error rather than a behaviour change. It also builds the
     * flow at most once, under the bin's lock, instead of building one per racing caller and
     * discarding the losers.
     */
    private fun flow(jobId: UUID) = flows.computeIfAbsent(jobId) { MutableStateFlow(null) }
}

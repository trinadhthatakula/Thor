// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.os.SystemClock
import android.util.Log
import com.valhalla.thor.BuildConfig
import java.util.EnumMap
import java.util.EnumSet
import java.util.UUID

/** Debug-only semantic timestamps retained temporarily for service-queue baseline comparison. */
enum class ServiceQueueOperation(val markerName: String) {
    EXPORT("export"),
    PRIVILEGE_SWEEP("privilege_sweep"),
}

/** Stable events shared by the WorkManager baseline and its service-queue comparison. */
enum class ServiceQueueEvent(val markerName: String) {
    TAP("tap"),
    LOGGER_VISIBLE("logger_visible"),
    DURABLE_ACCEPTED("durable_accepted"),
    EXECUTION_ADMITTED("execution_admitted"),
    FIRST_OPERATION("first_operation"),
}

/**
 * Emits one secret-free marker per semantic event for the active run of each operation class.
 *
 * Benchmark runs are sequential within an operation class, so replacing its in-memory token at the
 * next tap is enough to correlate UI, persistence and worker events without changing a durable job
 * payload. Calls compile to no observable output in release builds.
 */
object ServiceQueueLatencyProbe {
    private const val TAG = "ServiceQueueLatencyProbe"

    private data class ActiveRun(
        val id: String,
        val emitted: MutableSet<ServiceQueueEvent> = EnumSet.noneOf(ServiceQueueEvent::class.java),
    )

    private val activeRuns = EnumMap<ServiceQueueOperation, ActiveRun>(
        ServiceQueueOperation::class.java
    )

    fun begin(operation: ServiceQueueOperation) {
        if (!BuildConfig.DEBUG) return
        val timestamp = try {
            SystemClock.elapsedRealtimeNanos()
        } catch (_: RuntimeException) {
            // Android's local-JVM stubs throw here; evidence must not change ordinary test behavior.
            return
        }
        val run = ActiveRun(UUID.randomUUID().toString())
        synchronized(activeRuns) {
            activeRuns[operation] = run
            run.emitted.add(ServiceQueueEvent.TAP)
        }
        emit(operation, run.id, ServiceQueueEvent.TAP, timestamp)
    }

    fun mark(operation: ServiceQueueOperation, event: ServiceQueueEvent) {
        if (!BuildConfig.DEBUG || event == ServiceQueueEvent.TAP) return
        val marker = synchronized(activeRuns) {
            val run = activeRuns[operation] ?: return
            if (!run.emitted.add(event)) return
            Marker(
                operation = operation,
                runId = run.id,
                event = event,
                timestamp = SystemClock.elapsedRealtimeNanos(),
            )
        }
        emit(marker.operation, marker.runId, marker.event, marker.timestamp)
    }

    private fun emit(
        operation: ServiceQueueOperation,
        runId: String,
        event: ServiceQueueEvent,
        timestamp: Long,
    ) {
        Log.i(
            TAG,
            "operation=${operation.markerName} run_id=$runId " +
                    "event=${event.markerName} elapsed_realtime_nanos=$timestamp"
        )
    }

    private data class Marker(
        val operation: ServiceQueueOperation,
        val runId: String,
        val event: ServiceQueueEvent,
        val timestamp: Long,
    )
}

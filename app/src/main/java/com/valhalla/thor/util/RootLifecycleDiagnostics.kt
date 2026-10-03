// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.util.Log
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootConfirmation
import com.valhalla.thor.domain.model.RootJobOutcomeKind
import com.valhalla.thor.domain.model.RootRefreshStatus

/** Development diagnostics accept no commands, identifiers, output, or arbitrary failure text. */
sealed interface RootLifecycleEvent {
    data class Refresh(
        val phase: RootRefreshPhase,
        val revision: Long,
        val confirmedRevision: Long,
        val confirmation: RootConfirmation,
        val status: RootRefreshStatus,
    ) : RootLifecycleEvent

    data class Shell(
        val lane: PrivilegeExecutionLane,
        val phase: RootShellPhase,
        val generation: Long? = null,
    ) : RootLifecycleEvent

    data class Lane(val lane: PrivilegeExecutionLane, val phase: RootLanePhase) : RootLifecycleEvent

    data class IsolatedOutcome(
        val lane: PrivilegeExecutionLane,
        val kind: RootJobOutcomeKind,
        val started: Boolean,
        val terminationConfirmed: Boolean,
        val outputDrained: Boolean,
        val shellReusable: Boolean,
    ) : RootLifecycleEvent

    data class Binding(val phase: RootBindingPhase, val cached: Boolean = false) : RootLifecycleEvent
}

enum class RootRefreshPhase { STARTED, COMPLETED, DEFERRED_ACTIVE_WORK, IDLE_RETRY, CANCELLED, ADMISSION_REFUSED }
/** RETIRED means local transport close returned; it does not prove descendant termination. */
enum class RootShellPhase { OPEN_STARTED, OPENED, OPEN_CANCELLED, OPEN_FAILED, RETIRE_STARTED, RETIRED, RETIRE_FAILED }
enum class RootLanePhase { DEGRADED, RECOVERED }
/** UNBOUND means local unbind returned; pending startup and accepted IPC can still continue. */
enum class RootBindingPhase {
    STARTED, CONNECTED, CACHE_HIT, WAIT_TIMED_OUT, WAITER_CANCELLED, BIND_FAILED,
    LATE_CONNECTED, DISCONNECTED, NULL_BINDING, BINDING_DIED, UNBOUND, UNBIND_FAILED,
}

fun interface RootLifecycleDiagnostics {
    fun record(event: RootLifecycleEvent)
}

/** Uses the existing development-build gate; it adds no file retention, export, or release toggle. */
internal object DefaultRootLifecycleDiagnostics : RootLifecycleDiagnostics {
    override fun record(event: RootLifecycleEvent) {
        writeRootLifecycleEvent(BuildConfig.PRIVILEGE_TRACE, event) { line ->
            Log.d(ROOT_LIFECYCLE_LOG_TAG, line)
        }
    }
}

internal const val ROOT_LIFECYCLE_LOG_TAG = "ThorRootLifecycle"

/** Logging must never replace the operation's result or interrupt ownership cleanup. */
internal fun RootLifecycleDiagnostics.recordSafely(event: RootLifecycleEvent) {
    try {
        record(event)
    } catch (_: Exception) {
        // Diagnostics are observational, including when called from cancellation/finally paths.
    }
}

internal fun writeRootLifecycleEvent(
    enabled: Boolean,
    event: RootLifecycleEvent,
    write: (String) -> Unit,
) {
    if (enabled) write(event.toDiagnosticLine())
}

/** A versioned, bounded line composed exclusively from the closed event schema. */
internal fun RootLifecycleEvent.toDiagnosticLine(): String = "v=1 " + when (this) {
    is RootLifecycleEvent.Refresh ->
        "event=refresh phase=$phase revision=$revision confirmedRevision=$confirmedRevision " +
            "confirmation=$confirmation status=$status"
    is RootLifecycleEvent.Shell -> "event=shell lane=$lane phase=$phase generation=${generation ?: "none"}"
    is RootLifecycleEvent.Lane -> "event=lane lane=$lane phase=$phase"
    is RootLifecycleEvent.IsolatedOutcome ->
        "event=isolated_outcome lane=$lane kind=$kind started=$started " +
            "terminationConfirmed=$terminationConfirmed outputDrained=$outputDrained shellReusable=$shellReusable"
    is RootLifecycleEvent.Binding -> "event=binding phase=$phase cached=$cached"
}

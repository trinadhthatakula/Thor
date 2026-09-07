// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.domain.model.RootLaneMode
import com.valhalla.thor.domain.model.RootLaneStatus
import com.valhalla.thor.domain.model.RootLaneStatusSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Single

@Single(binds = [RootLaneStatusSource::class])
internal class DefaultRootLaneStatusSource : RootLaneStatusSource {
    private val mutableStatuses = MutableStateFlow(
        PrivilegeExecutionLane.entries.associateWith { lane ->
            RootLaneStatus(lane = lane, mode = RootLaneMode.ISOLATED)
        },
    )
    private val degradationCauses = ConcurrentHashMap<PrivilegeExecutionLane, Throwable>()

    override val statuses: StateFlow<Map<PrivilegeExecutionLane, RootLaneStatus>> =
        mutableStatuses.asStateFlow()

    fun isDegraded(lane: PrivilegeExecutionLane): Boolean =
        statuses.value.getValue(lane).mode == RootLaneMode.DEGRADED

    fun markDegraded(lane: PrivilegeExecutionLane, cause: Throwable) {
        require(lane != PrivilegeExecutionLane.INTERACTIVE) {
            "The interactive lane cannot degrade to itself"
        }
        degradationCauses.putIfAbsent(lane, cause)
        mutableStatuses.update { current ->
            current + (lane to current.getValue(lane).copy(mode = RootLaneMode.DEGRADED))
        }
    }

    fun commandStarted(
        lane: PrivilegeExecutionLane,
        commandClass: PrivilegeCommandClass,
        fallbackOwner: PrivilegeExecutionLane? = null,
    ) {
        mutableStatuses.update { current ->
            val status = current.getValue(lane)
            current + (
                lane to status.copy(
                    activeCommandClass = commandClass,
                    fallbackOwner = fallbackOwner,
                )
            )
        }
    }

    fun commandFinished(lane: PrivilegeExecutionLane) {
        mutableStatuses.update { current ->
            val status = current.getValue(lane)
            current + (
                lane to status.copy(
                    activeCommandClass = null,
                    fallbackOwner = null,
                )
            )
        }
    }

    internal fun degradationCause(lane: PrivilegeExecutionLane): Throwable? =
        degradationCauses[lane]
}

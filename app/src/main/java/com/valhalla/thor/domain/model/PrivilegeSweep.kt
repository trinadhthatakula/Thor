// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

enum class PrivilegeSweepOperation { FREEZE, UNFREEZE, CLEAR_CACHE, REINSTALL }

enum class PrivilegeSweepSource {
    MAIN,
    APP_LIST,
    FREEZER,
    PROFILE,
    QS_TILE,
    LAUNCHER,
    SETTINGS,
}

private const val PROFILE_SOURCE_PREFIX = "PROFILE:"

/** Ordinary source association plus a qualified profile token when the source is PROFILE. */
internal fun sweepSourceAssociations(
    source: PrivilegeSweepSource,
    profileId: Long?,
): Set<String> = buildSet {
    add(source.name)
    if (source == PrivilegeSweepSource.PROFILE && profileId != null) {
        add("$PROFILE_SOURCE_PREFIX$profileId")
    }
}

/** Ignores ordinary enum tokens and malformed qualified values rather than feeding them to valueOf. */
internal fun profileIdsFromSourceAssociations(associations: Collection<String>): Set<Long> =
    associations.mapNotNullTo(linkedSetOf()) { token ->
        token.takeIf { it.startsWith(PROFILE_SOURCE_PREFIX) }
            ?.removePrefix(PROFILE_SOURCE_PREFIX)
            ?.takeIf { it.isNotEmpty() && ':' !in it }
            ?.toLongOrNull()
    }

/** Returns a stable, locale-independent target order without rewriting package-name case. */
fun normalizeSweepTargets(packageNames: Collection<String>): List<String> {
    require(packageNames.none(String::isBlank)) { "Sweep package names must not be blank" }
    return packageNames.distinct().sorted()
}

data class PrivilegeSweepSpec(
    val operation: PrivilegeSweepOperation,
    val packageNames: List<String>,
    val freezerMode: FreezerMode?,
    val userId: Int,
    val source: PrivilegeSweepSource,
    val profileId: Long? = null,
) {
    init {
        require(packageNames == normalizeSweepTargets(packageNames)) {
            "Sweep targets must already be canonical"
        }
        require((operation == PrivilegeSweepOperation.FREEZE) == (freezerMode != null)) {
            "Only FREEZE requires a resolved freezer mode"
        }
        require((source == PrivilegeSweepSource.PROFILE) == (profileId != null)) {
            "PROFILE sweeps require exactly one resolved profile id"
        }
    }

    val sourceAssociations: Set<String>
        get() = sweepSourceAssociations(source, profileId)
}

enum class PrivilegeSweepPhase {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    CANCELLED,
    FAILED,
    OBSERVER_FAILURE,
}

data class PrivilegeSweepStatus(
    val requestId: UUID,
    val workId: UUID,
    val operation: PrivilegeSweepOperation,
    val source: PrivilegeSweepSource,
    val phase: PrivilegeSweepPhase,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val busy: Int,
    val unresolved: Int,
    val rootLaneDegraded: Boolean,
    val profileIds: Set<Long> = emptySet(),
)

sealed interface PrivilegeSweepLaunchResult {
    data class Accepted(
        val requestId: UUID,
        val workId: UUID,
        val coalesced: Boolean,
    ) : PrivilegeSweepLaunchResult

    data class Rejected(val reason: PrivilegeSweepLaunchRejection) : PrivilegeSweepLaunchResult
}

sealed interface PrivilegeSweepLaunchRejection {
    data object NotificationsRequired : PrivilegeSweepLaunchRejection
    data object NoPrivilege : PrivilegeSweepLaunchRejection
    data object NoTargets : PrivilegeSweepLaunchRejection
    data class EnqueueFailed(val message: String) : PrivilegeSweepLaunchRejection
}

internal val SWEEP_RESULT_RETENTION: Duration = 24.hours
internal const val SWEEP_REQUEST_ID_KEY: String = "sweep_request_id"

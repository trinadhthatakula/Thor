// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import com.valhalla.thor.data.gateway.userIdOf

/** Stable wire values. Never reuse an existing status or reason for a different meaning. */
object SuspensionReadbackProtocol {
    const val VERSION = 1
    const val STATUS_UNKNOWN = 0
    const val STATUS_NOT_SUSPENDED = 1
    const val STATUS_SUSPENDED = 2
    const val STATUS_NOT_INSTALLED = 3
    const val STATUS_REFUSED = 4

    const val MAX_OWNERS = 64
    const val MAX_PACKAGE_NAME_LENGTH = 255
    const val MAX_OUTPUT_BYTES = 1_048_576
    const val READ_TIMEOUT_MILLIS = 5_000L

    const val REASON_NONE = 0
    const val REASON_INVALID_ARGUMENT = 1
    const val REASON_USER_MISMATCH = 2
    const val REASON_READ_FAILED = 3
    const val REASON_TIMEOUT = 4
    const val REASON_OUTPUT_LIMIT = 5
    const val REASON_MALFORMED_OUTPUT = 6
    const val REASON_UNSUPPORTED_FORMAT = 7
    const val REASON_MISSING_PACKAGE = 8
    const val REASON_MISSING_USER = 9
    const val REASON_INCOMPLETE_OWNERS = 10
    const val REASON_TOO_MANY_OWNERS = 11
    const val REASON_AMBIGUOUS_DIALOG = 12
    const val REASON_PLATFORM_REFUSED = 13
    const val REASON_CANONICAL_STATE_UNAVAILABLE = 14
    const val REASON_STATE_CONTRADICTION = 15
    const val REASON_BUSY = 16

    /** Includes the historical literal `root` and framework `android` suspender identities. */
    fun isValidPackageIdentity(value: String): Boolean =
        value.length in 1..MAX_PACKAGE_NAME_LENGTH && value.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_'
        }

    fun isKnownReason(reason: Int): Boolean = reason in REASON_NONE..REASON_BUSY
}

/** Typed PackageManager evidence, read inside the same observation budget as the package dump. */
internal data class CanonicalSuspensionState(
    val packageName: String,
    val userId: Int,
    val installed: Boolean,
    val suspended: Boolean,
)

internal data class SuspensionOwnerIdentity(val packageName: String, val userId: Int)

/** UNKNOWN/REFUSED carry no actionable owners; known suspended means the list is complete. */
internal data class SuspensionSnapshot(
    val status: Int,
    val reason: Int = SuspensionReadbackProtocol.REASON_NONE,
    val owners: List<SuspensionOwnerIdentity> = emptyList(),
)

internal fun unknownSuspensionSnapshot(reason: Int) =
    SuspensionSnapshot(SuspensionReadbackProtocol.STATUS_UNKNOWN, reason)

/** Unescaped package metadata cannot be allowed to manufacture a confirmed negative state. */
internal fun verifySuspensionSnapshot(
    snapshot: SuspensionSnapshot,
    canonical: CanonicalSuspensionState?,
    packageName: String,
    userId: Int,
): SuspensionSnapshot {
    if (canonical == null || canonical.packageName != packageName || canonical.userId != userId ||
        (!canonical.installed && canonical.suspended)
    ) return unknownSuspensionSnapshot(SuspensionReadbackProtocol.REASON_CANONICAL_STATE_UNAVAILABLE)
    val expected = when {
        !canonical.installed -> SuspensionReadbackProtocol.STATUS_NOT_INSTALLED
        canonical.suspended -> SuspensionReadbackProtocol.STATUS_SUSPENDED
        else -> SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED
    }
    return if (snapshot.status in SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED..
        SuspensionReadbackProtocol.STATUS_NOT_INSTALLED && snapshot.status != expected
    ) {
        unknownSuspensionSnapshot(SuspensionReadbackProtocol.REASON_STATE_CONTRADICTION)
    } else snapshot
}

internal fun validateSuspensionReadRequest(packageName: String?, userId: Int, callerUid: Int): Int =
    when {
        packageName == null || !SuspensionReadbackProtocol.isValidPackageIdentity(packageName) ||
            userId < 0 || callerUid < 0 -> SuspensionReadbackProtocol.REASON_INVALID_ARGUMENT
        callerUid != 0 && callerUid != 1_000 && userIdOf(callerUid) != userId ->
            SuspensionReadbackProtocol.REASON_USER_MISMATCH
        else -> SuspensionReadbackProtocol.REASON_NONE
    }

internal fun SuspensionSnapshot.toReadbackResult(packageName: String, userId: Int) =
    SuspensionReadbackResult().also { result ->
        result.protocolVersion = SuspensionReadbackProtocol.VERSION
        // Even refused requests must not echo unbounded or arbitrary caller-provided text.
        result.packageName = packageName.takeIf(SuspensionReadbackProtocol::isValidPackageIdentity)
            ?: ""
        result.userId = userId
        result.status = status
        result.reason = reason
        result.owners = owners.map { owner ->
            SuspensionOwner().also {
                it.packageName = owner.packageName
                it.userId = owner.userId
            }
        }.toTypedArray()
    }

/** The old mutation API has only one user argument, so it cannot safely lift cross-user owners. */
internal fun SuspensionSnapshot.sameUserSuspendersOrNull(userId: Int): Set<String>? = when (status) {
    SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED -> emptySet()
    SuspensionReadbackProtocol.STATUS_SUSPENDED ->
        owners.takeIf { it.isNotEmpty() && it.all { owner -> owner.userId == userId } }
            ?.mapTo(linkedSetOf()) { it.packageName }
    else -> null
}

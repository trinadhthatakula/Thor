// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.os.DeadObjectException
import android.os.RemoteException
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol
import com.valhalla.thor.rootservice.SuspensionReadbackResult
import kotlinx.coroutines.CancellationException

/** An immutable, validated owner. Its Android user is part of its identity on API 35+. */
internal data class RootSuspensionOwner(val packageName: String, val userId: Int)

internal sealed interface RootSuspensionReadback {
    data object NotSuspended : RootSuspensionReadback
    data class Suspended(val owners: List<RootSuspensionOwner>) : RootSuspensionReadback
    data object NotInstalled : RootSuspensionReadback
    data class Refused(val serviceReason: Int?) : RootSuspensionReadback
    data class Unknown(
        val cause: RootSuspensionReadFailure,
        val serviceReason: Int? = null,
    ) : RootSuspensionReadback
}

/** Client observations are separate from reasons returned by a living service. */
internal enum class RootSuspensionReadFailure {
    INVALID_REQUEST,
    SERVICE_UNKNOWN,
    UNSUPPORTED_PROTOCOL,
    MALFORMED_REPLY,
    TRANSPORT_DIED,
    TRANSPORT_FAILURE,
}

/**
 * Makes one read through an already-bound service, with no bind, legacy dump, or retry fallback.
 *
 * This synchronous Binder call runs on the gateway's IO dispatcher. A coroutine timeout cannot
 * cancel a dispatched Binder transaction; the service owns the bounded dump/read deadline instead.
 * An absent or broken reply conveys no actionable ownership, including after a service death.
 */
internal class RootSuspensionReadbackClient(private val service: IThorRootService) {
    fun read(packageName: String, userId: Int): RootSuspensionReadback {
        if (!SuspensionReadbackProtocol.isValidPackageIdentity(packageName) || userId < 0) {
            return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.INVALID_REQUEST)
        }
        val reply = try {
            service.getSuspensionStateForUser(packageName, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeadObjectException) {
            return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.TRANSPORT_DIED)
        } catch (_: RemoteException) {
            return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.TRANSPORT_FAILURE)
        } catch (_: SecurityException) {
            return RootSuspensionReadback.Refused(serviceReason = null)
        } catch (_: Exception) {
            // Includes a malformed reply parcel. Never recover by reading the legacy full dump.
            return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.MALFORMED_REPLY)
        } ?: return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.UNSUPPORTED_PROTOCOL)

        return validate(reply, packageName, userId)
    }

    private fun validate(
        reply: SuspensionReadbackResult,
        packageName: String,
        userId: Int,
    ): RootSuspensionReadback {
        fun malformed() = RootSuspensionReadback.Unknown(RootSuspensionReadFailure.MALFORMED_REPLY)
        if (reply.protocolVersion != SuspensionReadbackProtocol.VERSION) {
            return RootSuspensionReadback.Unknown(RootSuspensionReadFailure.UNSUPPORTED_PROTOCOL)
        }
        if (reply.packageName != packageName || reply.userId != userId ||
            !SuspensionReadbackProtocol.isKnownReason(reply.reason)
        ) return malformed()

        val rawOwners = reply.owners ?: return malformed()
        if (rawOwners.size > SuspensionReadbackProtocol.MAX_OWNERS) return malformed()
        val owners = ArrayList<RootSuspensionOwner>(rawOwners.size)
        for (owner in rawOwners) {
            if (owner == null || owner.userId < 0 ||
                !SuspensionReadbackProtocol.isValidPackageIdentity(owner.packageName ?: "")
            ) return malformed()
            val identity = RootSuspensionOwner(owner.packageName, owner.userId)
            if (identity in owners) return malformed()
            owners += identity
        }

        val confirmed = reply.status == SuspensionReadbackProtocol.STATUS_SUSPENDED ||
            reply.status == SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED ||
            reply.status == SuspensionReadbackProtocol.STATUS_NOT_INSTALLED
        if (confirmed != (reply.reason == SuspensionReadbackProtocol.REASON_NONE)) return malformed()
        if (reply.status != SuspensionReadbackProtocol.STATUS_SUSPENDED && owners.isNotEmpty()) {
            return malformed()
        }
        return when (reply.status) {
            SuspensionReadbackProtocol.STATUS_NOT_SUSPENDED -> RootSuspensionReadback.NotSuspended
            SuspensionReadbackProtocol.STATUS_SUSPENDED -> {
                if (owners.isEmpty()) malformed() else RootSuspensionReadback.Suspended(owners.toList())
            }
            SuspensionReadbackProtocol.STATUS_NOT_INSTALLED -> RootSuspensionReadback.NotInstalled
            SuspensionReadbackProtocol.STATUS_REFUSED -> RootSuspensionReadback.Refused(reply.reason)
            SuspensionReadbackProtocol.STATUS_UNKNOWN -> RootSuspensionReadback.Unknown(
                RootSuspensionReadFailure.SERVICE_UNKNOWN,
                reply.reason,
            )
            else -> malformed()
        }
    }
}

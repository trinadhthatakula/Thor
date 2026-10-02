// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway.root

import android.content.Context
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.repository.PackageOperationBarrier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** Runs under the package lease; recovery only queries an existing operation, never submits it. */
@Single(binds = [PackageOperationBarrier::class])
internal class RootDataClearPackageBarrier(
    private val context: Context,
    private val journal: RootDataClearBarrier,
) : PackageOperationBarrier {
    internal var serviceProvider: suspend () -> com.valhalla.thor.rootservice.IThorRootService? =
        RootServiceConnectionOwner(OdinRootServiceBinding(context))::getService

    override suspend fun isBlocked(packageName: String, owner: PackageOperationOwner): Boolean = withContext(Dispatchers.IO) {
        // These operations could erase the recovery control plane, including while another
        // package begins clearing concurrently. Do not make this depend on a racy empty check.
        if (packageName in setOf(context.packageName, BuildConfig.APPLICATION_ID) && owner in SELF_DESTRUCTIVE) {
            return@withContext true
        }
        try {
            val record = journal.pending(packageName) ?: return@withContext false
            if (record.phase != RootDataClearPhase.BINDER) {
                // Exact-phase removal makes any still-live owner fail markBinder before dispatch.
                return@withContext !journal.finish(record) || owner == PackageOperationOwner.CLEAR_DATA
            }
            val service = serviceProvider() ?: return@withContext true
            val result = RootDataClearClient(service).query(record)
            if (result is RootDataClearObservation.Unknown) return@withContext true
            if (!journal.finish(record)) return@withContext true
            // A repeated clear gesture recovers the old operation; it cannot silently become
            // another wipe. A subsequent explicit gesture may start a new operation.
            owner == PackageOperationOwner.CLEAR_DATA
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            true
        }
    }

    override suspend fun isGlobalBlocked(): Boolean = try {
        journal.anyPending()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

    override suspend fun <T> withGlobalLease(block: suspend () -> T): PackageLeaseResult<T> =
        journal.withGlobalLease(block)

    private companion object {
        val SELF_DESTRUCTIVE = setOf(
            PackageOperationOwner.CLEAR_DATA, PackageOperationOwner.UNINSTALL,
            PackageOperationOwner.ARCHIVE_RESTORE,
        )
    }
}

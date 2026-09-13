// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.PackageLeaseResult
import com.valhalla.thor.domain.model.PackageOperationOwner
import com.valhalla.thor.domain.model.PrivilegeExecutionTimeouts
import com.valhalla.thor.domain.repository.PackageOperationCoordinator

/** Durable bundle reads only. Delegate-owned file cleanup completes before this lease is released. */
internal class PackageReadDataTaskRunner(
    private val delegate: DataTaskRunner,
    private val packages: PackageOperationCoordinator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DataTaskRunner {
    init {
        require(delegate.kind == DataTaskKind.APP_EXPORT || delegate.kind == DataTaskKind.SHARE_PREPARE)
    }

    override val kind get() = delegate.kind

    override suspend fun run(request: DataTaskExecutionRequest, checkpoints: DataTaskCheckpointSink): DataTaskRunOutcome =
        when (val lease = packages.withPackageLease(
            packageName = request.item.packageName,
            owner = PackageOperationOwner.BUNDLE_READ,
            admissionTimeout = PrivilegeExecutionTimeouts.ARCHIVE_ADMISSION,
        ) { delegate.run(request, checkpoints) }) {
            is PackageLeaseResult.Acquired -> lease.value
            is PackageLeaseResult.Busy -> DataTaskRunOutcome.ItemCompleted(
                DataTaskItemResult(
                    terminalState = DataTaskItemTerminalState.FAILED,
                    resultCode = DataTaskResultCode("PACKAGE_OPERATION_BUSY"),
                    warnings = emptyList(),
                    outputs = emptyList(),
                    finishedAtEpochMs = nowMs(),
                )
            )
        }
}

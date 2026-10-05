// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppExportRequest
import java.util.UUID

/**
 * Start a single-app export, and watch it.
 *
 * Separate from [ArchiveJobLauncher] rather than a third method on it, because the two share none of
 * what makes that interface awkward: an export derives no key, holds no passphrase, and hands the
 * worker nothing that has to be in memory before the worker starts. Folding it in would have given
 * every export call site a view of `ArchiveKeyHolder`'s ordering rules for no reason.
 *
 * It extends [ThorJobWatcher] for the same reason [ArchiveJobLauncher] does: a screen that starts a
 * job needs to follow it, and splitting "start" from "watch" across two injected types buys nothing
 * but a second constructor parameter.
 */
interface ExportJobLauncher : ThorJobWatcher {

    /**
     * @return the durable Room task's id, or null when acceptance failed before the task was stored.
     *   A stored task keeps its id even if Android refuses the service wake; its observed state
     *   reports that failure to the caller.
     */
    suspend fun startExport(request: AppExportRequest): UUID?

    suspend fun startExport(
        taskId: UUID,
        request: AppExportRequest,
    ): UUID? = startExport(request)
}

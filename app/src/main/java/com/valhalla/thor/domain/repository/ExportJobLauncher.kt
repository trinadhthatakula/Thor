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
     * @return the enqueued job's id, or null when the request never made it into WorkManager's
     *   database — which is a real outcome and not a theoretical one, and the caller must say so
     *   rather than showing a progress bar for a job that will never run. See `enqueueUniqueJob`.
     */
    suspend fun startExport(request: AppExportRequest): UUID?
}

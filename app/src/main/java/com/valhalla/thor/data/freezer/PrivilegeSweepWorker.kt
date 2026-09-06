// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import androidx.work.WorkerParameters
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.job.JobRegistry
import com.valhalla.thor.data.backup.job.JobSheetTarget
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.data.backup.job.ThorJobNotifications
import com.valhalla.thor.data.backup.job.ThorJobWorker
import com.valhalla.thor.domain.model.ThorJobKind
import org.koin.android.annotation.KoinWorker

/** Reconstructs released sweep WorkRequests without executing or mutating their durable requests. */
@KoinWorker
internal class PrivilegeSweepWorker(
    appContext: Context,
    params: WorkerParameters,
    notifications: ThorJobNotifications,
    registry: JobRegistry,
    sheetTargets: JobSheetTargets,
) : ThorJobWorker(
    appContext,
    params,
    notifications,
    registry,
    sheetTargets,
) {
    override val kind = ThorJobKind.PRIVILEGE_SWEEP
    override val initialLabel = appContext.getString(R.string.sweep_notification_title)
    override val runsForeground = false
    override val sheetTarget: JobSheetTarget? = null

    override suspend fun runJob(): Result =
        fail("Legacy privilege sweep WorkManager execution is retired")
}

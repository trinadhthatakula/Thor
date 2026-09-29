// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot

/** Per-app operation controls for Thor's current Android user. */
interface AppOpsRepository {
    suspend fun getAppOps(packageName: String): Result<AppOpsSnapshot>

    /** Write one validated operation and scope, then verify its mode before reporting success. */
    suspend fun setAppOpMode(
        packageName: String,
        code: Int,
        scope: AppOpScope,
        mode: AppOpMode,
    ): Result<Unit>

    /** Restore the operation's platform default, which may differ from [AppOpMode.DEFAULT]. */
    suspend fun resetAppOpMode(
        packageName: String,
        code: Int,
        scope: AppOpScope,
    ): Result<Unit>
}

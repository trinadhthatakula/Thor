// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize
import com.valhalla.thor.domain.model.isUsablePackageName
import com.valhalla.thor.domain.repository.AppDataProbe
import org.koin.core.annotation.Factory

/**
 * What the backup sheet shows before the user commits to anything.
 *
 * @param supported false when the active channel cannot read private data at all. The sheet then
 *   disables the control and states the reason — it does **not** say "requires Root", because a
 *   root-started Shizuku passes and a Dhizuku device-owner does not (§6).
 */
data class AppDataMeasurement(
    val supported: Boolean,
    val sizes: Map<DataClass, DataClassSize>,
)

@Factory
class MeasureAppDataUseCase(private val probe: AppDataProbe) {

    suspend operator fun invoke(packageName: String): AppDataMeasurement {
        // Checked here as well as inside every command builder. The builders refuse individually and
        // would each cost a round trip to say so; and a package name this shape means the caller is
        // confused, not that one class is unreadable.
        if (!isUsablePackageName(packageName)) {
            return AppDataMeasurement(supported = false, sizes = emptyMap())
        }
        if (!probe.probeDataArchiveCapability()) {
            // Deliberately no measurements: four shell round trips that will each fail, rendered as
            // four "unknown" rows, is a worse answer than one honest refusal.
            return AppDataMeasurement(supported = false, sizes = emptyMap())
        }
        // Sequential, not parallel. These are `du -s -k` walks over potentially gigabytes; four at
        // once on one privileged shell queue is slower, not faster, and the shell serialises anyway.
        val sizes = DataClass.entries.associateWith { dataClass ->
            probe.measureDataClass(packageName, dataClass)
        }
        return AppDataMeasurement(supported = true, sizes = sizes)
    }
}

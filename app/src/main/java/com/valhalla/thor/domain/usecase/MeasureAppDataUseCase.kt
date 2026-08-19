// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.data.backup.DataArchiveCapabilityCache
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
    val supportedClasses: Set<DataClass> = DataClass.entries.toSet(),
)

/**
 * `domain` → `data` import is deliberate. [DataArchiveCapabilityCache] lives in `data/backup`; this
 * use case in `domain/usecase` imports it directly, the same shape as the archive use cases importing
 * `AppArchiveCipher`. The cache holds the `hasAnyPrivilege` short-circuit (no `su` prompt on an
 * ungrantd-Magisk device at sheet open) and the per-[PrivilegeState] cache (one shell round trip, not
 * one per sheet open). Bypassing it via [AppDataProbe] directly loses both properties.
 */
@Factory
class MeasureAppDataUseCase(
    private val cache: DataArchiveCapabilityCache,
    private val probe: AppDataProbe,
) {

    suspend operator fun invoke(packageName: String): AppDataMeasurement {
        // Checked here as well as inside every command builder. The builders refuse individually and
        // would each cost a round trip to say so; and a package name this shape means the caller is
        // confused, not that one class is unreadable.
        if (!isUsablePackageName(packageName)) {
            return AppDataMeasurement(supported = false, sizes = emptyMap(), supportedClasses = emptySet())
        }
        if (!cache.isSupported()) {
            // Deliberately no measurements: four shell round trips that will each fail, rendered as
            // four "unknown" rows, is a worse answer than one honest refusal.
            return AppDataMeasurement(supported = false, sizes = emptyMap(), supportedClasses = emptySet())
        }
        val supportedClasses = cache.supportedClasses()
        // Sequential, not parallel. These are `du -s -k` walks over potentially gigabytes; four at
        // once on one privileged shell queue is slower, not faster, and the shell serialises anyway.
        val sizes = supportedClasses.associateWith { dataClass ->
            probe.measureDataClass(packageName, dataClass)
        }
        return AppDataMeasurement(
            supported = true,
            sizes = sizes,
            supportedClasses = supportedClasses,
        )
    }
}

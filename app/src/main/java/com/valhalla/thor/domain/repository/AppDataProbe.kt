// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataClassSize

/**
 * Read-only questions about another app's private data, answered through the active privilege
 * gateway.
 *
 * A narrow port over `SystemRepositoryImpl` rather than more surface on [SystemRepository]: the two
 * hand-written `RecordingSystemRepository` doubles in the test source set implement that interface in
 * full, and neither exercises backup. Same implementing object, so a capability answer from here is
 * still evidence about the surface the capture will use.
 */
interface AppDataProbe {

    /**
     * Can the active privileged surface read *another* app's private data directory?
     *
     * Deliberately a probe and not a privilege check. Root-started Shizuku can do this and plain
     * Shizuku (`shell` uid) cannot, so "requires Root" would be a lie on the first device and
     * `isRootAvailable()` would be the wrong question on both. Never throws — every failure is
     * `false`, because a maybe here has to read as "do not offer the feature".
     */
    suspend fun probeDataArchiveCapability(): Boolean

    /**
     * Apparent size of one storage class, via `du -s -k`.
     *
     * Returns `Undetermined` for anything that is not a number Thor asked for — a missing `du`, a
     * gateway failure, an unusable package name. `Empty` means the directory genuinely is not there.
     * A caller must not render `Undetermined` as `0 B`; that is the same rule `ObbProbe` and
     * `clearCache`'s nullable byte count already carry.
     */
    suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize
}

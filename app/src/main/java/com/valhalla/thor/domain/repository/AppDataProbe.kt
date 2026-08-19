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
     * True for Root or root-started Shizuku. Plain Shizuku (shell UID 2000) returns false.
     */
    suspend fun probePrivateDataCapability(): Boolean

    /**
     * Can the active privileged surface back up app data at all?
     *
     * True for Root, root-started Shizuku, or plain Shizuku with shell access.
     */
    suspend fun probeDataArchiveCapability(): Boolean

    /**
     * Apparent size of **what a backup of this class would contain**, via `du -s -k`.
     *
     * Not the size of the directory: the volatile children the archive drops (`cache`, `code_cache`,
     * `no_backup`) are measured separately and subtracted. The distinction is load-bearing rather than
     * cosmetic — this number is both displayed and the one the staging-space refusal compares against,
     * so measuring a 3 GB browser cache that is never packed refuses a backup of 20 MB of real data.
     *
     * Returns `Undetermined` for anything that is not a number Thor asked for — a missing `du`, a
     * gateway failure, an unusable package name. `Empty` means the directory genuinely is not there.
     * A caller must not render `Undetermined` as `0 B`; that is the same rule `ObbProbe` and
     * `clearCache`'s nullable byte count already carry.
     */
    suspend fun measureDataClass(packageName: String, dataClass: DataClass): DataClassSize
}

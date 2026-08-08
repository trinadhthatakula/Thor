// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Domain port for the "show Thor when opening any file" opt-in.
 *
 * The setting is not a preference. What it actually controls is whether the `AnyFileInstallerAlias`
 * component is enabled, and that state is owned by `PackageManager` — it survives reboots, is
 * cleared on uninstall, and can be changed from outside Thor (`pm enable`, some ROM app managers).
 * Storing a copy in DataStore would create a second answer that can disagree with the first, so the
 * only reader is [isEnabled] and there is no cached value anywhere.
 *
 * Exists as a port for the usual reason: the implementation needs a `Context` and a
 * `PackageManager`, so depending on the class would put [com.valhalla.thor.presentation.settings.SettingsViewModel]
 * out of reach of a JVM test.
 *
 * Both members suspend because both are binder calls.
 */
interface AnyFileOpenerController {

    /**
     * Whether Thor currently offers itself for files whose type nobody could name.
     *
     * Reflects the live component state, not what Thor last asked for — read it back after any
     * [setEnabled] rather than assuming the write took.
     */
    suspend fun isEnabled(): Boolean

    /**
     * Turn the broad filter on or off.
     *
     * Best-effort and silent: `setComponentEnabledSetting` returns nothing, so a caller that needs
     * to know the outcome has to call [isEnabled] afterwards.
     */
    suspend fun setEnabled(enabled: Boolean)
}

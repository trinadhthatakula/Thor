// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.core

import com.valhalla.superuser.Shell
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.core.ThorShellConfig.init

/**
 * Centralized configuration for the Root Shell.
 * Call [init] in your Application.onCreate().
 */
object ThorShellConfig {

    fun init() {
        // Set logging based on build type
        Shell.enableVerboseLogging = BuildConfig.DEBUG

        // Configure the default builder. Do NOT set FLAG_MOUNT_MASTER as KernelSU / APatch su
        // does not accept --mount-master and would block root acquisition.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(10)
        )
    }
}
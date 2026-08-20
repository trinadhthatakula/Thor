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

        // Do NOT set FLAG_MOUNT_MASTER — but not for the reason it is tempting to write down. The
        // flag cannot "block root acquisition" on its own: Odin's BuilderImpl.start() wraps the
        // `su --mount-master` attempt in `catch (_: NoShellException)` and falls through to plain
        // `su`, then to `sh`, so an su that rejects the argument is recoverable by construction.
        //
        // What the fallback is not is free, and that is the part that bit KernelSU/APatch users.
        // An su that rejects --mount-master by *exiting* is cheap — ShellImpl's shell check calls
        // process.exitValue() first, sees a dead process and throws immediately. An su that instead
        // sits there is not: nothing gives up until the shell check times out, and only then does
        // the plain-`su` attempt start, from zero, with its own superuser prompt. Which of the two
        // KernelSU and APatch actually do has not been confirmed here — the field symptom (root
        // never acquired within any reasonable wait) fits the second. Thor never needs the global
        // mount namespace, so there is nothing on the other side of that bet worth holding.
        //
        // setTimeout aligns the builder's shell-check budget with Odin's own root probe, which gives
        // up at RealShellRepository.SHELL_INIT_TIMEOUT_MS = 10s; the 20s BuilderImpl default would
        // leave a `su` process and a blocked executor thread alive for ten seconds after the probe
        // has already reported "no root". The budget covers the time the *user* spends answering the
        // superuser dialog, so the trade is real: a grant given between 10s and 20s now has its
        // shell destroyed instead of cached, and costs one more prompt on the next probe.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(SHELL_INIT_TIMEOUT_SECONDS)
        )
    }

    /** Matches Odin's `RealShellRepository.SHELL_INIT_TIMEOUT_MS`; see [init]. */
    private const val SHELL_INIT_TIMEOUT_SECONDS = 10L
}

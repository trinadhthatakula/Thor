// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Whether this device can authenticate the user at all.
 *
 * Extracted from `BiometricHelper` so the launch-path gate can ask the question without a
 * `Context`: `SecurityViewModel` decides whether to hold the user at the lock screen, and that
 * decision has to be unit-testable. `BiometricHelper` is a final class over `BiometricManager`
 * and `:app` carries no mocking library by design, so the seam is an interface rather than a mock.
 */
interface AuthCapability {

    /**
     * True when a prompt can actually succeed — an enrolled biometric **or** any device credential
     * (PIN/pattern/password). False means every prompt on this device fails immediately, so
     * anything that gates on authentication is unreachable rather than merely locked.
     */
    fun canAuthenticate(): Boolean

    /** True when the device has biometric hardware, whatever its enrollment state. */
    fun hasHardware(): Boolean
}

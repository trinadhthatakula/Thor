// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which authenticators Thor may ask `BiometricManager` about, per API level.
 *
 * This exists because the wrong answer here is a lockout rather than a cosmetic bug. Since the
 * launch path gates on `canAuthenticate()`, a combination the platform rejects outright returns
 * `BIOMETRIC_ERROR_UNSUPPORTED` — indistinguishable, to a `== BIOMETRIC_SUCCESS` test, from "this
 * device has nothing enrolled" — and the user is held at a screen no enrollment can clear.
 *
 * `BiometricHelper` itself is not reachable from a JVM test (it needs a `Context` and a real
 * `BiometricManager`), so the version rule is lifted out to be asserted directly.
 */
class PromptAuthenticatorsTest {

    /**
     * androidx `AuthenticatorUtils.isSupportedCombination` returns false for
     * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` when `SDK_INT` is in `[P, Q]`, i.e. exactly Android
     * 9 and 10 — and Thor's minSdk is 28, so those are shipped, supported devices.
     */
    @Test
    fun android9And10AskBiometricOnly_becauseTheCombinationIsRejectedThere() {
        assertEquals(BIOMETRIC_STRONG, promptAuthenticators(28))
        assertEquals(BIOMETRIC_STRONG, promptAuthenticators(29))
    }

    @Test
    fun api30AndUpAskForBiometricOrDeviceCredential() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, promptAuthenticators(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, promptAuthenticators(36))
    }

    /**
     * The predicate has to agree with `BiometricPromptHandler`, which calls
     * `setAllowedAuthenticators` only on API 30+ and builds a biometric-only prompt with a Cancel
     * button below that. If they diverge, the gate is answering a different question than the
     * screen behind it — the capability check says "impossible" while the prompt would have worked,
     * or the reverse.
     */
    @Test
    fun theSplitFallsOnTheSameVersionAsThePromptHandlers() {
        val firstCombined = (28..36).first { promptAuthenticators(it) != BIOMETRIC_STRONG }

        assertEquals(30, firstCombined)
    }
}

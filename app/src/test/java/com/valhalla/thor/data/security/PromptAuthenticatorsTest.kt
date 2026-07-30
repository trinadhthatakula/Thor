// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.valhalla.thor.presentation.security.promptAcceptsDeviceCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
     * Android 9's framework prompt has no device-credential path at all — `setDeviceCredentialAllowed`
     * arrives in Q — so asking about one would claim a capability the prompt cannot honour.
     */
    @Test
    fun android9AsksBiometricOnly_becauseItsPromptCannotTakeACredential() {
        assertEquals(BIOMETRIC_STRONG, promptAuthenticators(28))
        assertFalse(promptAcceptsDeviceCredential(28))
    }

    /**
     * Android 10 *can* take a credential, via the deprecated `setDeviceCredentialAllowed`, but
     * androidx `AuthenticatorUtils.isSupportedCombination` rejects `BIOMETRIC_STRONG or
     * DEVICE_CREDENTIAL` below R. `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is supported at every
     * level, so it is how the same question gets asked there.
     */
    @Test
    fun android10AsksTheOnlyCredentialCombinationItSupports() {
        assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, promptAuthenticators(29))
        assertTrue(promptAcceptsDeviceCredential(29))
    }

    @Test
    fun api30AndUpAskForBiometricOrDeviceCredential() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, promptAuthenticators(30))
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, promptAuthenticators(36))
    }

    /**
     * The predicate has to agree with `BiometricPromptHandler`, which builds a credential-capable
     * prompt from Q up and a biometric-only one on P. If they diverge, the gate is answering a
     * different question than the screen behind it — the capability check says "impossible" while
     * the prompt would have worked, or the reverse, which is the lockout this whole split exists
     * to prevent. `promptAcceptsDeviceCredential` is the same boundary in the form the unavailable
     * screen reads, and it must not drift from this one.
     */
    @Test
    fun everyLevelThatAsksAboutACredentialIsOneWhosePromptAcceptsOne() {
        val asked = (28..36).filter { promptAuthenticators(it) and DEVICE_CREDENTIAL != 0 }
        val accepted = (28..36).filter { promptAcceptsDeviceCredential(it) }

        assertEquals(accepted, asked)
        assertEquals((29..36).toList(), asked)
    }
}

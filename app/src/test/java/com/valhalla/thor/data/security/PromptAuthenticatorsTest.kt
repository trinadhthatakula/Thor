// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.security

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.valhalla.thor.R
import com.valhalla.thor.presentation.security.biometricRefusalMessage
import com.valhalla.thor.presentation.security.enrolmentCanFixLockout
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

    /**
     * The one device where a locked-out user has nothing to go and enrol.
     *
     * `SecurityViewModel` disarms the app lock on exactly this answer, so a `true` leaked in here
     * strands a user at a prompt that can never succeed, and a `false` leaked out hands Thor
     * permission to switch off a lock the user could still have opened. Both are security-relevant
     * in opposite directions, which is why it is a named predicate with its own test rather than a
     * condition inlined into the view model.
     */
    @Test
    fun onlyApi28WithNoSensorIsBeyondEnrolment() {
        assertFalse(enrolmentCanFixLockout(28, hasBiometricHardware = false))

        // A sensor with nothing enrolled is the *recoverable* case: enrolling on it is the fix.
        assertTrue(enrolmentCanFixLockout(28, hasBiometricHardware = true))

        // From Q up the prompt takes a PIN, so a device with no sensor at all is still recoverable
        // — the user sets a screen lock. Hardware stops mattering entirely.
        for (sdk in 29..36) {
            assertTrue(enrolmentCanFixLockout(sdk, hasBiometricHardware = false))
            assertTrue(enrolmentCanFixLockout(sdk, hasBiometricHardware = true))
        }
    }

    /**
     * The refusal copy follows the same two rules, because a refusal that names the wrong fix is
     * worse than a silent one: it sends the user to a Settings page that cannot help and they come
     * back to the same refusal.
     */
    @Test
    fun theRefusalNamesSomethingTheUserCanActuallyDo() {
        // Nothing will ever satisfy this prompt, so it must not send anyone to Settings.
        assertEquals(
            R.string.biometric_lock_unavailable_toast,
            biometricRefusalMessage(28, hasBiometricHardware = false)
        )
        // A sensor is present and Android 9's prompt takes nothing else, so a screen lock is not
        // the answer here even though it is the answer everywhere else.
        assertEquals(
            R.string.biometric_not_enrolled_toast_biometric_only,
            biometricRefusalMessage(28, hasBiometricHardware = true)
        )
        // Q and up: either works, hardware stops mattering, and the generic copy is true.
        for (sdk in 29..36) {
            assertEquals(
                "sdk $sdk with a sensor",
                R.string.biometric_not_enrolled_toast,
                biometricRefusalMessage(sdk, hasBiometricHardware = true)
            )
            assertEquals(
                "sdk $sdk with no sensor",
                R.string.biometric_not_enrolled_toast,
                biometricRefusalMessage(sdk, hasBiometricHardware = false)
            )
        }
    }
}

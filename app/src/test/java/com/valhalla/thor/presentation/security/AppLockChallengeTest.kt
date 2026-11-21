// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.security

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.SignatureException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockChallengeTest {
    @Test
    fun onlyAnAuthorizedSignerWithTheExpectedKeyCanCompleteTheChallenge() {
        val generator = KeyPairGenerator.getInstance("EC")
        val expected = generator.generateKeyPair()
        val other = generator.generateKeyPair()
        val challenge = AppLockChallenge(expected.public)
        val signer = Signature.getInstance("SHA256withECDSA")

        assertThrows(SignatureException::class.java) { challenge.verifyWith(signer) }
        signer.initSign(other.private)
        assertFalse(challenge.verifyWith(signer))
        signer.initSign(expected.private)
        assertTrue(challenge.verifyWith(signer))
    }
}

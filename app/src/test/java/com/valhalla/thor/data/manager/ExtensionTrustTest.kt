// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the extension-trust primitives. These are the actual security gate:
 * an extension only loads / shows as "verified" when its signer cert SHA-256 is pinned.
 */
class ExtensionTrustTest {

    private val pin = "762DC455D6F5CE05E7D1848057FDF04362D137B7AB987879AFDF370B10F9498C"

    @Test
    fun `toCertSha256Hex of empty bytes matches known digest`() {
        // SHA-256 of the empty input, the canonical test vector.
        assertEquals(
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            ByteArray(0).toCertSha256Hex(),
        )
    }

    @Test
    fun `toCertSha256Hex is uppercase hex with no separators and masks negative bytes`() {
        // Includes 0xFF which is a negative Byte — verifies no sign-extension in the hex output.
        val hex = byteArrayOf(0x00, 0x0F, 0x10, 0x7F, 0xFF.toByte()).toCertSha256Hex()
        assertTrue("expected 64 uppercase hex chars, got '$hex'", hex.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun `isPinnedSigner true for exact pin`() {
        assertTrue(isPinnedSigner(pin))
    }

    @Test
    fun `isPinnedSigner true for lowercase pin (case-insensitive)`() {
        assertTrue(isPinnedSigner(pin.lowercase()))
    }

    @Test
    fun `isPinnedSigner false for a different sha`() {
        assertFalse(isPinnedSigner("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"))
    }

    @Test
    fun `isPinnedSigner false for empty pin set`() {
        assertFalse(isPinnedSigner(pin, emptySet()))
    }

    @Test
    fun `isPinnedSigner false for empty sha`() {
        assertFalse(isPinnedSigner(""))
    }

    @Test
    fun `the dedicated Thor-Extensions pin is present in the default allowlist`() {
        assertTrue(TrustedExtensionSigners.PINS.contains(pin))
    }
}

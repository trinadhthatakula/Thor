package com.valhalla.thor.presentation.corepatch

import org.junit.Assert.assertEquals
import org.junit.Test

// Test names avoid the `>` character (the brief's `=>` is an illegal JVM method-name char).
class CorePatchConfirmStateTest {
    @Test
    fun `different signer yields sig capability`() =
        assertEquals("sig", capabilityFor("AAAA", "BBBB"))

    @Test
    fun `same signer case-insensitive yields digest capability`() =
        assertEquals("digest", capabilityFor("aaaa", "AAAA"))

    @Test
    fun `no installed signer fresh install path yields digest`() =
        assertEquals("digest", capabilityFor(null, "BBBB"))
}

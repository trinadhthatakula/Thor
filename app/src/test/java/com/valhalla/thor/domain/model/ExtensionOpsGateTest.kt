package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionOpsGateTest {

    // --- isAuthorizedExtensionCaller ---
    @Test fun `same-process (null caller) is allowed`() {
        assertTrue(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `own package is allowed`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor", "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `pinned-signer extension is allowed`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = true, isDebug = false))
    }
    @Test fun `ext-prefixed but not pinned is refused in release`() {
        assertFalse(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `ext-prefixed unpinned is allowed in debug`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = true))
    }
    @Test fun `arbitrary app is refused even in debug`() {
        assertFalse(isAuthorizedExtensionCaller("com.evil.app", "com.valhalla.thor", isPinnedSigner = false, isDebug = true))
    }

    // --- opTargets ---
    @Test fun `filters guarded and blank, dedups, preserves order`() {
        val out = opTargets(
            requested = listOf("com.a", "", "com.valhalla.thor", "com.b", "com.a"),
            guarded = setOf("com.valhalla.thor")
        )
        assertEquals(listOf("com.a", "com.b"), out)
    }
    @Test fun `empty when all guarded`() {
        assertEquals(emptyList<String>(), opTargets(listOf("com.valhalla.thor"), setOf("com.valhalla.thor")))
    }
}

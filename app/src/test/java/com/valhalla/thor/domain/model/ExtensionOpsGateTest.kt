package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionOpsGateTest {

    // --- isAuthorizedExtensionCaller ---
    @Test fun testSameProcessIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = true))
    }
    @Test fun testOwnPackageIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor", "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
    }
    @Test fun testPinnedSignerExtensionIsAllowed() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = true, isDebug = false, isSameProcess = false))
    }
    @Test fun testExtPrefixedButNotPinnedIsRefusedInRelease() {
        assertFalse(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
    }
    @Test fun testExtPrefixedUnpinnedIsAllowedInDebug() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = true, isSameProcess = false))
    }
    @Test fun testArbitraryAppIsRefusedEvenInDebug() {
        assertFalse(isAuthorizedExtensionCaller("com.evil.app", "com.valhalla.thor", isPinnedSigner = false, isDebug = true, isSameProcess = false))
    }
    @Test fun testNullCallerFromCrossProcessIsRefused() {
        assertFalse(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false, isSameProcess = false))
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

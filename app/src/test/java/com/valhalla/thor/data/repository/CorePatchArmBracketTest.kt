// CorePatchArmBracketTest.kt
package com.valhalla.thor.data.repository

import com.valhalla.thor.data.corepatch.CorePatchArmStateHolder
import com.valhalla.thor.data.corepatch.CorePatchAuditEntry
import com.valhalla.thor.data.corepatch.InMemoryCorePatchAudit
import com.valhalla.thor.domain.model.CorePatchAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CorePatchArmBracketTest {
    private val holder = CorePatchArmStateHolder()
    private val audit = InMemoryCorePatchAudit()
    private val verifierWrites = mutableListOf<Boolean>()
    private var markerCleared = false

    // Extracted free function mirroring InstallerRepositoryImpl.withCorePatchArmed, injected with fakes.
    private suspend fun <T> bracket(auth: CorePatchAuthorization, block: suspend () -> Boolean): T? {
        if (auth.disablePlayProtect) verifierWrites.add(false) // marker+flip
        return try {
            holder.arm(auth.pkg, auth.expectedNewSignerSha256, auth.capability, 30_000)
            assertTrue(holder.current().armed) // armed INSIDE
            @Suppress("UNCHECKED_CAST")
            block() as T
        } finally {
            holder.disarm()
            if (auth.disablePlayProtect) { verifierWrites.add(true); markerCleared = true }
            audit.append(CorePatchAuditEntry(0, auth.pkg, "OLD", auth.expectedNewSignerSha256, auth.capability, auth.downgrade, "DONE"))
        }
    }

    private val auth = CorePatchAuthorization("com.foo", "sig", "AABB", disablePlayProtect = true, downgrade = false)

    @Test fun `disarms and restores after normal completion`() = runBlocking {
        bracket<Boolean>(auth) { true }
        assertFalse(holder.current().armed)
        assertEquals(listOf(false, true), verifierWrites) // off then on
        assertTrue(markerCleared)
        assertEquals(1, audit.all().size)
    }

    @Test fun `disarms and restores even when install throws`() = runBlocking {
        runCatching { bracket<Boolean>(auth) { throw RuntimeException("boom") } }
        assertFalse(holder.current().armed)
        assertEquals(listOf(false, true), verifierWrites)
        assertTrue(markerCleared)
    }
}

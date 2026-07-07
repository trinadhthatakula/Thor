// CorePatchAuditTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Test

class CorePatchAuditTest {
    private fun entry(i: Int) = CorePatchAuditEntry(i.toLong(), "com.foo", "OLD", "NEW", "sig", false, "SUCCESS")

    @Test fun `append then all returns in order`() {
        val a = InMemoryCorePatchAudit()
        a.append(entry(1)); a.append(entry(2))
        assertEquals(listOf(1L, 2L), a.all().map { it.timestampMillis })
    }

    @Test fun `caps at max keeping newest`() {
        val a = InMemoryCorePatchAudit(max = 2)
        a.append(entry(1)); a.append(entry(2)); a.append(entry(3))
        assertEquals(listOf(2L, 3L), a.all().map { it.timestampMillis })
    }
}

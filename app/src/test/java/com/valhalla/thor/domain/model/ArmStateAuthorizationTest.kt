// ArmStateAuthorizationTest.kt
package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmStateAuthorizationTest {
    private val armed = ArmState(
        armed = true, pkg = "com.foo",
        signerSha256 = "AABBCC", capability = "sig", deadlineMillis = 1_000L,
    )

    @Test fun `authorizes exact match before deadline`() =
        assertTrue(armed.authorizes(900L, "com.foo", "aabbcc", "sig")) // case-insensitive signer

    @Test fun `rejects when disarmed`() =
        assertFalse(ArmState.DISARMED.authorizes(900L, "com.foo", "AABBCC", "sig"))

    @Test fun `rejects after deadline`() =
        assertFalse(armed.authorizes(1_001L, "com.foo", "AABBCC", "sig"))

    @Test fun `rejects wrong package`() =
        assertFalse(armed.authorizes(900L, "com.bar", "AABBCC", "sig"))

    @Test fun `rejects wrong signer`() =
        assertFalse(armed.authorizes(900L, "com.foo", "DDEEFF", "sig"))

    @Test fun `rejects wrong capability`() =
        assertFalse(armed.authorizes(900L, "com.foo", "AABBCC", "digest"))
}

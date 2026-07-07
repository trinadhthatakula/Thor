// SignerShaFormatTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SignerShaFormatTest {
    @Test fun `sha256 of empty cert bytes is known uppercase hex no separators`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val hex = ByteArray(0).toSignerSha256Hex()
        assertEquals("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855", hex)
        assertEquals(64, hex.length)
    }

    @Test fun `hex has no colons`() {
        assertEquals(false, byteArrayOf(1, 2, 3).toSignerSha256Hex().contains(":"))
    }
}

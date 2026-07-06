package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGateTest {
    @Test fun `allows a package that is in the freezer`() {
        assertTrue(mayRestore("com.foo", setOf("com.foo", "com.bar")))
    }
    @Test fun `blocks a package not in the freezer`() {
        assertFalse(mayRestore("com.evil", setOf("com.foo")))
    }
    @Test fun `blocks a blank package`() {
        assertFalse(mayRestore("", setOf("com.foo")))
    }
}

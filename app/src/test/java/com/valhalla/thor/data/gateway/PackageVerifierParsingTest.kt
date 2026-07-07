package com.valhalla.thor.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PackageVerifierParsingTest {
    @Test fun `zero means disabled`() = assertFalse(parsePackageVerifierValue("0"))
    @Test fun `one means enabled`() = assertTrue(parsePackageVerifierValue("1"))
    @Test fun `null string means default enabled`() = assertTrue(parsePackageVerifierValue("null"))
    @Test fun `blank means default enabled`() = assertTrue(parsePackageVerifierValue("  "))
    @Test fun `actual null means default enabled`() = assertTrue(parsePackageVerifierValue(null))
    @Test fun `garbage means enabled`() = assertTrue(parsePackageVerifierValue("banana"))
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataArchiveGatewayImplTest {

    /**
     * Pins the predicate so that a future refactor cannot accidentally make it reject names that
     * merely contain a dot. `DataClass.memberName(compress)` produces names in the form
     * `<id>.tar.enc` or `<id>.tar.gz.enc`; all four DataClass ids must pass.
     */
    @Test
    fun `ordinary dotted names are accepted — not over-rejected by the dot check`() {
        // These are the exact names DataClass.memberName() produces.
        assertTrue(isSafeStagingName("ce.tar.enc"))
        assertTrue(isSafeStagingName("ce.tar.gz.enc"))
        assertTrue(isSafeStagingName("de.tar.enc"))
        assertTrue(isSafeStagingName("de.tar.gz.enc"))
        assertTrue(isSafeStagingName("ext-data.tar.enc"))
        assertTrue(isSafeStagingName("ext-data.tar.gz.enc"))
        assertTrue(isSafeStagingName("ext-media.tar.enc"))
        assertTrue(isSafeStagingName("ext-media.tar.gz.enc"))
    }

    @Test
    fun `path-traversal components are rejected`() {
        // The entire name is the dangerous form — a component, not a file with a dot.
        assertFalse(isSafeStagingName(".."))
        assertFalse(isSafeStagingName("."))
    }

    @Test
    fun `path separators in any position are rejected`() {
        assertFalse(isSafeStagingName("foo/bar"))
        assertFalse(isSafeStagingName("../../etc/passwd"))
        assertFalse(isSafeStagingName("/absolute/path"))
        assertFalse(isSafeStagingName("ce.tar.enc/"))
    }

    @Test
    fun `blank names are rejected`() {
        assertFalse(isSafeStagingName(""))
        assertFalse(isSafeStagingName("   "))
    }
}

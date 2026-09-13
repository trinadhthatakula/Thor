// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMetadataTest {

    @Test
    fun `target SDK is unknown by default for sidecar-only metadata`() {
        val metadata = AppMetadata(
            label = "Example",
            packageName = "com.example.app",
            version = "1.0",
            versionCode = null,
            iconPath = null,
        )

        assertNull(metadata.targetSdk)
    }

    @Test
    fun `a parsed APK target SDK preserves zero as a real value`() {
        val metadata = AppMetadata(
            label = "Example",
            packageName = "com.example.app",
            version = "1.0",
            versionCode = 1L,
            iconPath = null,
            targetSdk = 0,
        )

        assertEquals(0, metadata.targetSdk)
    }
}

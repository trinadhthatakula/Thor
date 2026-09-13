// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageArchiveParsingTest {

    @Test
    fun `APK clusters use the legacy parser only where PackageManager is monolithic`() {
        assertEquals(
            ArchivePackageParsingStrategy.LEGACY_CLUSTER,
            archivePackageParsingStrategy(apiLevel = 28, isDirectory = true),
        )
        assertEquals(
            ArchivePackageParsingStrategy.LEGACY_CLUSTER,
            archivePackageParsingStrategy(apiLevel = 29, isDirectory = true),
        )
        assertEquals(
            ArchivePackageParsingStrategy.PLATFORM,
            archivePackageParsingStrategy(apiLevel = 30, isDirectory = true),
        )
        assertEquals(
            ArchivePackageParsingStrategy.PLATFORM,
            archivePackageParsingStrategy(apiLevel = 28, isDirectory = false),
        )
    }
}

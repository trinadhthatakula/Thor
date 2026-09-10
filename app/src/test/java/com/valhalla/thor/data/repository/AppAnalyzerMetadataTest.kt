// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAnalyzerMetadataTest {

    @Test
    fun `target SDK comes from the parsed APK application info`() {
        val parsedBaseApk = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply { targetSdkVersion = 23 }
        }

        assertEquals(23, parsedArchiveTargetSdk(parsedBaseApk))
    }

    @Test
    fun `selected bundle base retains its parsed target SDK`() {
        // `readMetadata` extracts the selected identity candidate to its scratch APK before it
        // reaches this helper. The sidecar plan chooses that base, but never supplies this value.
        val parsedSelectedBundleBase = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply { targetSdkVersion = 28 }
        }

        assertEquals(28, parsedArchiveTargetSdk(parsedSelectedBundleBase))
    }

    @Test
    fun `missing parsed APK application info leaves target SDK unknown`() {
        assertNull(parsedArchiveTargetSdk(PackageInfo()))
    }
}

// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.util

import com.valhalla.thor.domain.model.AppInfo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests for the bundle metadata Thor generates when exporting an installed app.
 *
 * These matter beyond the Kotlin types: the `version_code` written here is read back by other
 * installers (and by Thor's own bundle analyzer) to decide update vs. downgrade. While
 * `AppInfo.versionCode` was an `Int` fed by `packInfo.longVersionCode.toInt()`, an app declaring
 * `versionCodeMajor` had that half of its code silently dropped, so Thor emitted a version code
 * that was not the app's — turning an exported bundle into a phantom downgrade on reinstall.
 */
class ApksMetadataGeneratorTest {

    // versionCodeMajor = 1, versionCode = 5. Truncating to Int yields 5, so the two are trivially
    // distinguishable in the emitted JSON.
    private val versionCodeWithMajor = (1L shl 32) or 5L // 4294967301

    private fun app() = AppInfo(
        packageName = "com.example.app",
        appName = "Example",
        versionName = "1.0.5",
        versionCode = versionCodeWithMajor
    )

    @Test
    fun apksMetadata_writesTheFullLongVersionCode() {
        val json = ApksMetadataGenerator().generateJson(app())
        // An unquoted JSON number, so consumers parse it as an integer rather than a string.
        assertTrue(json, json.contains("\"version_code\":4294967301"))
    }

    @Test
    fun xapkManifest_writesTheFullLongVersionCode() {
        val json = ApksMetadataGenerator().generateManifestJson(app())
        // Quoted here: the XAPK schema types version_code as a string (see XapkManifestInfo,
        // which reads it back tolerantly as String?).
        assertTrue(json, json.contains("\"version_code\":\"4294967301\""))
    }
}

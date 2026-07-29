// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.util

import com.valhalla.thor.data.repository.parseXapkManifest
import com.valhalla.thor.domain.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun app(
        splits: List<String> = emptyList()
    ) = AppInfo(
        packageName = "com.example.app",
        appName = "Example",
        versionName = "1.0.5",
        versionCode = versionCodeWithMajor,
        minSdk = 28,
        targetSdk = 35,
        splitPublicSourceDirs = splits
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

    /**
     * The `.apks` manifest predates the `.xapk` writer and is read back by
     * `InstallerRepositoryImpl`; the XAPK-only fields must stay out of it rather than appear with
     * placeholder values (a `total_size: 0` there would describe a size nobody measured).
     */
    @Test
    fun apksManifest_omitsTheXapkOnlyFields() {
        val json = ApksMetadataGenerator().generateManifestJson(app())
        listOf("min_sdk_version", "target_sdk_version", "total_size", "icon").forEach { key ->
            assertFalse("$key leaked into the .apks manifest: $json", json.contains(key))
        }
    }

    @Test
    fun xapkManifest_writesTheSdkVersionsAsQuotedStrings() {
        val json = ApksMetadataGenerator().generateManifestJson(app(), totalSize = 1_234L)
        // Quoted, matching what APKPure itself emits — not an accident of typing.
        assertTrue(json, json.contains("\"min_sdk_version\":\"28\""))
        assertTrue(json, json.contains("\"target_sdk_version\":\"35\""))
    }

    @Test
    fun xapkManifest_writesTotalSizeAsANumber() {
        val json = ApksMetadataGenerator().generateManifestJson(app(), totalSize = 123_456_789L)
        assertTrue(json, json.contains("\"total_size\":123456789"))
    }

    @Test
    fun xapkManifest_namesTheIconEntryAndDeclaresTheFormatVersion() {
        val generator = ApksMetadataGenerator()

        val default = generator.generateManifestJson(app(), totalSize = 1_234L)
        assertTrue(default, default.contains("\"icon\":\"icon.png\""))
        // Present only because the .xapk is encoded with defaults on; a reader uses it to tell a
        // v1 layout from a v2 one.
        assertTrue(default, default.contains("\"xapk_version\":2"))

        val custom = generator.generateManifestJson(app(), totalSize = 1_234L, iconName = "ic.png")
        assertTrue(custom, custom.contains("\"icon\":\"ic.png\""))
    }

    /**
     * The builder passes null when it could not stage an icon. The field must then be absent, not
     * `"icon":null` and not a default name pointing at an entry the zip does not contain.
     */
    @Test
    fun xapkManifest_omitsTheIconWhenTheBuilderStagedNone() {
        val json = ApksMetadataGenerator()
            .generateManifestJson(app(), totalSize = 1_234L, iconName = null)
        assertFalse(json, json.contains("icon"))
    }

    /**
     * Copying is per-file and can fail one split at a time (no root, a protected mount), so the
     * builder passes the entries it actually staged. Declaring the app's full split list instead
     * would name an APK the container does not hold — which Thor's own analyzer detects and works
     * around, but SAI and APKPure simply trust.
     */
    @Test
    fun xapkManifest_declaresOnlyTheApksThatWereActuallyStaged() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(
                splits = listOf(
                    "/data/app/com.example.app-1/split_config.arm64_v8a.apk",
                    "/data/app/com.example.app-1/split_config.xxhdpi.apk"
                )
            ),
            totalSize = 42L,
            // arm64_v8a would not copy.
            entryNames = listOf("base.apk", "split_config.xxhdpi.apk")
        )

        val manifest = parseXapkManifest(json)
        assertNotNull(json, manifest)
        assertEquals(
            listOf("base.apk", "split_config.xxhdpi.apk"),
            manifest!!.splitApkFiles()
        )
    }

    /** No list means no claim about staging — every declared split survives. */
    @Test
    fun xapkManifest_declaresEverySplitWhenTheCallerPassesNoEntryList() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(splits = listOf("/data/app/com.example.app-1/split_config.arm64_v8a.apk")),
            totalSize = 42L,
            entryNames = null
        )

        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk"),
            parseXapkManifest(json)!!.splitApkFiles()
        )
    }

    @Test
    fun xapkManifest_roundTripsASplitAppWithAMajorVersionCode() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(splits = listOf("/data/app/com.example.app-1/split_config.arm64_v8a.apk")),
            totalSize = 42L
        )

        val manifest = parseXapkManifest(json)
        assertNotNull(json, manifest)
        assertEquals("com.example.app", manifest!!.packageName)
        assertEquals("4294967301", manifest.versionCode)
        assertEquals("icon.png", manifest.iconFile)
        assertEquals("base.apk", manifest.baseApkFile())
        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk"),
            manifest.splitApkFiles()
        )
    }
}

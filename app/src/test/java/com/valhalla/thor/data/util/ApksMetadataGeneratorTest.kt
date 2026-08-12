// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.util

import com.valhalla.thor.data.repository.parseXapkManifest
import com.valhalla.thor.domain.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val baseDir = "/data/app/com.example.app-1"

    private fun app(
        splits: List<String> = emptyList(),
        publicSourceDir: String? = "$baseDir/base.apk"
    ) = AppInfo(
        packageName = "com.example.app",
        appName = "Example",
        versionName = "1.0.5",
        versionCode = versionCodeWithMajor,
        minSdk = 28,
        targetSdk = 35,
        publicSourceDir = publicSourceDir,
        splitPublicSourceDirs = splits
    )

    /** What the builder hands over: each source path with the entry name it was written under. */
    private fun staged(vararg paths: String) =
        paths.map { ApksMetadataGenerator.StagedApk(it, it.substringAfterLast('/')) }

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
     * The manifest describes the *container*, not the app. The builder passes what it staged, in
     * the order it staged it, and `split_apks` is exactly that — which Thor's own analyzer works
     * around when it is wrong, but SAI and APKPure simply trust.
     */
    @Test
    fun xapkManifest_declaresTheApksTheBuilderActuallyStaged() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(
                splits = listOf(
                    "$baseDir/split_config.arm64_v8a.apk",
                    "$baseDir/split_config.xxhdpi.apk"
                )
            ),
            totalSize = 42L,
            staged = staged("$baseDir/base.apk", "$baseDir/split_config.xxhdpi.apk")
        )

        val manifest = parseXapkManifest(json)
        assertNotNull(json, manifest)
        assertEquals(
            listOf("base.apk", "split_config.xxhdpi.apk"),
            manifest!!.splitApkFiles()
        )
    }

    /**
     * The regression that `StagedApk` exists for.
     *
     * Two source directories each holding a `base.apk` — `publicSourceDir` and a split of the same
     * leaf name — so `stagedApkNames` writes the second as `base_2.apk`. Rebuilding the manifest
     * from the source paths named `base.apk` twice, and then filtering by the staged names kept
     * both of those and never mentioned `base_2.apk` at all: an APK present in the zip, absent from
     * the manifest, and a duplicate entry in its place.
     */
    @Test
    fun xapkManifest_namesARenamedApkByWhatTheZipCallsIt() {
        val collidingSplit = "/data/app/other/base.apk"
        val json = ApksMetadataGenerator().generateManifestJson(
            app(splits = listOf(collidingSplit)),
            totalSize = 42L,
            staged = listOf(
                ApksMetadataGenerator.StagedApk("$baseDir/base.apk", "base.apk"),
                ApksMetadataGenerator.StagedApk(collidingSplit, "base_2.apk")
            )
        )

        val manifest = parseXapkManifest(json)
        assertNotNull(json, manifest)
        // Asserted as pairs, not as two independent claims: the id still comes from the *source*
        // leaf — `base_2` is a container detail, whereas the id is what an installer matches the
        // split on — and both source leaves here are `base.apk`, so both ids are `base`. Checking
        // the file list and `"id":"base"` separately would pass on the base entry alone, leaving a
        // regression that wrote the renamed split's id as `base_2` invisible.
        assertEquals(
            json,
            listOf("base.apk" to "base", "base_2.apk" to "base"),
            manifest!!.splitApks.map { it.file to it.id }
        )
    }

    /**
     * The split id is the split's identity in the app, so it is derived from the source path even
     * when the entry name differs, and `split_` is stripped the way APKPure writes it.
     */
    @Test
    fun xapkManifest_derivesSplitIdsFromTheSourcePath() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(splits = listOf("$baseDir/split_config.arm64_v8a.apk")),
            totalSize = 42L,
            staged = staged("$baseDir/base.apk", "$baseDir/split_config.arm64_v8a.apk")
        )

        assertTrue(json, json.contains("\"id\":\"base\""))
        assertTrue(json, json.contains("\"id\":\"config.arm64_v8a\""))
    }

    /** No list means no claim about staging — every declared split survives. */
    @Test
    fun xapkManifest_declaresEverySplitWhenTheCallerPassesNoEntryList() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(splits = listOf("$baseDir/split_config.arm64_v8a.apk")),
            totalSize = 42L,
            staged = null
        )

        assertEquals(
            listOf("base.apk", "split_config.arm64_v8a.apk"),
            parseXapkManifest(json)!!.splitApkFiles()
        )
    }

    /**
     * An app with neither `publicSourceDir` nor `sourceDir` still gets a `base.apk` entry from the
     * no-list path — that branch is a claim about the app, and every app has a base.
     */
    @Test
    fun xapkManifest_fallsBackToBaseApkWhenTheAppNamesNoSourcePath() {
        val json = ApksMetadataGenerator().generateManifestJson(
            app(publicSourceDir = null),
            totalSize = 42L,
            staged = null
        )

        assertEquals(listOf("base.apk"), parseXapkManifest(json)!!.splitApkFiles())
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

    @Test
    fun xapkManifest_roundTripsExpansionsFromTheWriterIntoTheReader() {
        val json = ApksMetadataGenerator().generateManifestJson(
            appInfo = app(),
            totalSize = 123L,
            iconName = "icon.png",
            staged = staged("$baseDir/base.apk"),
            expansions = listOf(
                XapkExpansion(
                    file = "Android/obb/com.example.game/main.12.com.example.game.obb",
                    installPath = "Android/obb/com.example.game/main.12.com.example.game.obb"
                )
            )
        )

        // The wire keys are what third-party installers read; they are not ours to rename.
        assertTrue(json, json.contains("\"expansions\""))
        assertTrue(json, json.contains("\"install_path\""))
        assertTrue(json, json.contains("\"install_location\":\"EXTERNAL_STORAGE\""))

        val parsed = parseXapkManifest(json)!!
        assertEquals(1, parsed.expansions.size)
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            parsed.expansions.first().file
        )
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            parsed.expansions.first().installPath
        )
    }

    /**
     * The inverse of what this file used to assert.
     *
     * The key used to be omitted when there were no expansions, so that OBB-less bundles stayed
     * byte-identical to what shipped before the feature. `[]` is the better wire shape: it is the
     * only encoding that survives a null check, a truthiness check and a type check at once, and it
     * is what APK Extractor already emits — `null` is emitted by nobody. `encodeDefaults = true` on
     * `xapkJson` is what makes a defaulted `emptyList()` appear rather than vanish.
     */
    @Test
    fun xapkManifest_writesAnEmptyExpansionsArrayWhenThereAreNone() {
        val json = ApksMetadataGenerator().generateManifestJson(
            appInfo = app(),
            totalSize = 123L,
            iconName = "icon.png",
            staged = staged("$baseDir/base.apk")
        )

        // Guard that this is the .xapk overload and not the .apks one, which uses a different Json
        // instance: without it the assertions below pass for the wrong reason.
        assertTrue(json, json.contains("\"total_size\":123"))
        assertTrue(json, json.contains("\"expansions\":[]"))
        // Not `"expansions":null`. `explicitNulls = false` would drop that, so the two failures are
        // indistinguishable from the `contains("expansions")` this test used to do.
        assertFalse(json, json.contains("\"expansions\":null"))

        // The entry that actually decides whether a no-OBB .xapk installs. cubehouse's installer
        // throws "Cannot find base APK in manifest.json!" without it, and adhu2018's hard-subscripts
        // `manifest["split_apks"]` *inside* the no-expansions branch — so the OBB-less bundle is
        // precisely the one that reaches that code. Thor emits it, and this pins it.
        val parsed = parseXapkManifest(json)!!
        assertEquals("base.apk", parsed.baseApkFile())
        assertTrue(parsed.expansions.isEmpty())
    }

    /**
     * The base entry's id comes from *which path it is*, not from what the file happens to be called.
     *
     * Found by mutation: replacing `if (apk.sourcePath == basePath) "base"` with a plain
     * `splitIdFor(...)` killed nothing, because `splitIdFor` strips `.apk` and every fixture in this
     * file names the base APK `base.apk` — so the two agreed by coincidence on every case tested.
     * They stop agreeing the moment the base is named anything else, which happens on ROMs that
     * rename it and on any app whose base path is not `.../base.apk`. That is the case where
     * cubehouse's installer throws `Cannot find base APK in manifest.json!` and adhu2018's
     * hard-subscripts a `split_apks` list with no `"base"` in it.
     */
    @Test
    fun xapkManifest_idsTheBaseApkFromItsPathEvenWhenItIsNotNamedBaseApk() {
        val oddBase = "$baseDir/com.example.app.apk"
        val json = ApksMetadataGenerator().generateManifestJson(
            appInfo = app(
                splits = listOf("$baseDir/split_config.arm64_v8a.apk"),
                publicSourceDir = oddBase
            ),
            totalSize = 123L,
            iconName = "icon.png",
            staged = staged(oddBase, "$baseDir/split_config.arm64_v8a.apk")
        )

        val parsed = parseXapkManifest(json)!!
        // Both sides: the base is found under its own file name, and the split beside it keeps the
        // id derived from its path — a rule that identified *everything* as base would also pass a
        // baseApkFile() assertion on its own.
        assertEquals("com.example.app.apk", parsed.baseApkFile())
        assertEquals(
            listOf("com.example.app.apk", "split_config.arm64_v8a.apk"),
            parsed.splitApkFiles()
        )
        // splitIdFor strips the `split_` prefix and the `.apk` suffix and nothing else, so the id
        // is `config.arm64_v8a` rather than `arm64_v8a`. Pinned as emitted, not as guessed.
        assertTrue(json, json.contains("\"id\":\"config.arm64_v8a\""))
    }

    /**
     * The `.apks` manifest goes through the plain `Json`, where `encodeDefaults` is false, so the
     * same non-null `emptyList()` default is dropped there. Its bytes did not move.
     */
    @Test
    fun apksManifest_stillOmitsExpansionsAltogether() {
        val json = ApksMetadataGenerator().generateManifestJson(app(), staged("$baseDir/base.apk"))

        assertFalse(json, json.contains("expansions"))
        // The overload guard, inverted from the .xapk test above: total_size is XAPK-only, so its
        // absence proves this is the .apks writer and the assertion above is about the right bytes.
        assertFalse(json, json.contains("total_size"))
    }

    @Test
    fun xapkManifest_readsAMissingExpansionsKeyAsAnEmptyListRatherThanNull() {
        val parsed = parseXapkManifest(
            """{"package_name":"com.example.game","name":"Game","version_code":"12"}"""
        )!!

        assertTrue(parsed.expansions.isEmpty())
    }

    /**
     * A hostile or merely old archive must not make the manifest unreadable — losing the manifest
     * would lose the split list too, breaking an install that could have worked.
     */
    @Test
    fun xapkManifest_readsAnExpansionMissingInstallPathAsNullRatherThanFailingTheManifest() {
        val parsed = parseXapkManifest(
            """
            {"package_name":"com.example.game","name":"Game","version_code":"12",
             "expansions":[{"file":"main.obb"}]}
            """.trimIndent()
        )!!

        assertEquals(1, parsed.expansions.size)
        assertNull(parsed.expansions.first().installPath)
    }
}

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, framework-independent tests for bundle/base detection helpers.
 *
 * These cover the two install regressions without needing a real device
 * PackageManager:
 *  - GH#207: a monolithic APK that happens to bundle a nested `assets/child.apk`
 *    must be detected as monolithic (top-level AndroidManifest.xml present),
 *    NOT treated as a bundle.
 *  - GH#159: an XAPK whose `config.*.apk` splits precede the base, and whose
 *    manifest.json has a numeric `version_code`, must (a) still deserialize
 *    tolerantly and (b) resolve the correct base APK entry, not a config split.
 */
class BundleAnalysisTest {

    // --- GH#207: monolithic vs bundle detection ---

    @Test
    fun monolithicApk_withNestedApkAsset_isDetectedAsMonolithic() {
        // A normal APK is a zip with a top-level AndroidManifest.xml. Apps like
        // App Manager also bundle nested `.apk` assets, which previously tripped
        // the "first .apk entry wins" scan.
        val entries = listOf(
            "AndroidManifest.xml",
            "classes.dex",
            "resources.arsc",
            "assets/child.apk",
            "META-INF/CERT.RSA"
        )
        assertTrue(hasTopLevelAndroidManifest(entries))
        assertTrue(isMonolithicApk(entries))
        // A monolithic APK is not a bundle even though it contains an inner .apk.
        assertFalse(looksLikeBundle(entries))
    }

    @Test
    fun monolithicClassification_gatesOutBaseCandidateSelection() {
        // Invariant behind the I-1 fix: a monolithic APK (top-level
        // AndroidManifest.xml) must NEVER be routed to inner-.apk base-candidate
        // selection, even though selectBaseApkCandidates() would otherwise happily
        // surface its nested assets/child.apk. The analyzer guards this with
        // isMonolithicApk(); here we assert the two signals disagree exactly so a
        // caller that gates on isMonolithicApk() cannot leak into candidate scan.
        val entries = listOf(
            "AndroidManifest.xml",
            "classes.dex",
            "assets/child.apk"
        )
        assertTrue(isMonolithicApk(entries))
        // If a buggy caller ignored the gate, this is the wrong (nested) identity
        // it would extract — documenting exactly what the gate prevents.
        assertEquals("assets/child.apk", selectBaseApkCandidates(entries, null).first())
    }

    @Test
    fun xapkBundle_withoutTopLevelManifest_isNotMonolithic() {
        val entries = listOf(
            "manifest.json",
            "icon.png",
            "config.arm64_v8a.apk",
            "base.apk"
        )
        assertFalse(hasTopLevelAndroidManifest(entries))
        assertFalse(isMonolithicApk(entries))
        assertTrue(looksLikeBundle(entries))
    }

    // --- split detection ---

    @Test
    fun splitNamesAreRecognized() {
        assertTrue(isSplitApkName("config.arm64_v8a.apk"))
        assertTrue(isSplitApkName("split_config.en.apk"))
        assertTrue(isSplitApkName("com.example.app.config.xhdpi.apk"))
        assertFalse(isSplitApkName("base.apk"))
        assertFalse(isSplitApkName("base-master.apk"))
        assertFalse(isSplitApkName("com.example.app.apk"))
    }

    @Test
    fun packageNamedApk_containingDotConfig_isNeverClassifiedAsSplit() {
        // A package name may itself contain `.config.` (e.g. com.example.config.foo).
        // Its base entry `com.example.config.foo.apk` must NOT trip the `.config.`
        // split heuristic when the package name is known.
        val pkg = "com.example.config.foo"
        assertTrue(isSplitApkName("com.example.config.foo.apk"))            // no hint -> split
        assertFalse(isSplitApkName("com.example.config.foo.apk", pkg))      // hinted -> base
        // A genuine config split of that same package is still a split.
        assertTrue(isSplitApkName("com.example.config.foo.config.xhdpi.apk", pkg))
    }

    @Test
    fun baseSelection_picksPackageNamedBaseWhenPackageContainsDotConfig() {
        val pkg = "com.example.config.foo"
        val entries = listOf(
            "com.example.config.foo.config.xhdpi.apk",
            "com.example.config.foo.apk"
        )
        val candidates = selectBaseApkCandidates(entries, packageName = pkg)
        // The exact {packageName}.apk is chosen as the base, not the config split.
        assertEquals("com.example.config.foo.apk", candidates.first())
        assertTrue(
            candidates.indexOf("com.example.config.foo.apk") <
                candidates.indexOf("com.example.config.foo.config.xhdpi.apk")
        )
    }

    // --- GH#159: base selection ordering ---

    @Test
    fun baseSelection_skipsConfigSplitsThatPrecedeBase() {
        // config splits come FIRST in zip order; the old code grabbed the first
        // .apk which was a config split (getPackageArchiveInfo -> null -> failure).
        val entries = listOf(
            "config.arm64_v8a.apk",
            "config.en.apk",
            "split_config.xhdpi.apk",
            "base.apk"
        )
        val candidates = selectBaseApkCandidates(entries, packageName = null)
        assertNotNull(candidates.firstOrNull())
        assertEquals("base.apk", candidates.first())
        // Config splits are only tried last (fallback).
        assertTrue(candidates.indexOf("base.apk") < candidates.indexOf("config.arm64_v8a.apk"))
    }

    @Test
    fun baseSelection_prefersBaseMasterThenPackageNamedApk() {
        val entries = listOf(
            "splits/config.xhdpi.apk",
            "com.example.app.apk",
            "base-master.apk"
        )
        val candidates = selectBaseApkCandidates(entries, packageName = "com.example.app")
        assertEquals("base-master.apk", candidates.first())
        // The package-named apk should be preferred over config splits too.
        assertTrue(
            candidates.indexOf("com.example.app.apk") <
                candidates.indexOf("splits/config.xhdpi.apk")
        )
    }

    @Test
    fun baseSelection_fallsBackToConfigSplitWhenNothingElse() {
        val entries = listOf("config.arm64_v8a.apk", "config.en.apk")
        val candidates = selectBaseApkCandidates(entries, packageName = null)
        // Nothing non-split exists, so the config splits are still returned as
        // last-resort candidates rather than an empty list.
        assertEquals(entries.toSet(), candidates.toSet())
    }

    // --- GH#159: tolerant manifest parsing ---

    @Test
    fun parseXapkManifest_toleratesNumericVersionCodeAndMissingName() {
        // Real .xapk manifests use a numeric version_code and may omit `name`.
        val jsonStr = """
            {
              "package_name": "com.example.app",
              "version_code": 12345,
              "version_name": "1.2.3",
              "split_apks": [
                { "file": "config.arm64_v8a.apk", "id": "config.arm64_v8a" },
                { "file": "base.apk", "id": "base" }
              ]
            }
        """.trimIndent()

        val manifest = parseXapkManifest(jsonStr)
        assertNotNull(manifest)
        assertEquals("com.example.app", manifest!!.packageName)
        assertEquals("1.2.3", manifest.versionName)
        // Lock the GH#159 string-from-number contract: a numeric version_code is
        // coerced into the String field rather than throwing.
        assertEquals("12345", manifest.versionCode)
        assertEquals("base.apk", manifest.baseApkFile())
    }

    @Test
    fun parseXapkManifest_returnsNullForGarbage() {
        assertNull(parseXapkManifest("not json at all"))
    }

    // --- stray top-level AndroidManifest.xml in a genuine bundle (APKPure crash) ---

    @Test
    fun bundleWithStrayTopLevelManifest_isNotMonolithic() {
        // Root cause of the APKPure install failure: a genuine XAPK that ALSO ships
        // a top-level AndroidManifest.xml. The old gate ("AndroidManifest.xml ⇒
        // monolithic") routed the whole multi-APK zip into getPackageArchiveInfo,
        // which threw FileNotFoundException: AndroidManifest.xml. Bundle signals
        // (manifest.json + top-level {package}.apk/config splits) must win.
        val entries = listOf(
            "AndroidManifest.xml",
            "manifest.json",
            "com.amazon.mShop.android.shopping.apk",
            "config.arm64_v8a.apk",
            "config.xxhdpi.apk",
            "icon.png"
        )
        assertTrue(hasTopLevelAndroidManifest(entries))
        assertFalse(isMonolithicApk(entries))
        assertTrue(looksLikeBundle(entries))
    }

    @Test
    fun apkmShapedZipWithStrayManifest_isNotMonolithic() {
        // Same defect for an .apkm-shaped zip: a stray root AndroidManifest.xml plus
        // a top-level base.apk sibling and info.json must be classified as a bundle.
        val entries = listOf(
            "AndroidManifest.xml",
            "base.apk",
            "split_config.arm64_v8a.apk",
            "info.json"
        )
        assertFalse(isMonolithicApk(entries))
        assertTrue(looksLikeBundle(entries))
    }

    // --- file-extension as a secondary bundle signal ---

    @Test
    fun fileExtension_forcesBundleButNeverBreaksAPlainApk() {
        val plainApk = listOf("AndroidManifest.xml", "classes.dex", "resources.arsc")
        // No bundle signal at all → monolithic.
        assertTrue(isMonolithicApk(plainApk))
        // .apk name keeps it monolithic.
        assertTrue(isMonolithicApk(plainApk, "app.apk"))
        // A known bundle extension forces the bundle path even with a stray manifest.
        assertFalse(isMonolithicApk(plainApk, "app.xapk"))
        assertFalse(isMonolithicApk(plainApk, "App.APKM"))
        assertFalse(isMonolithicApk(plainApk, "thing.apks"))
        // .apkp is registered as an installable bundle in the manifest, so it must
        // force the bundle path here too (even with a multi-dot version string).
        assertFalse(isMonolithicApk(plainApk, "App_1.2.3.apkp"))
    }

    @Test
    fun hasBundleExtension_recognizesKnownContainers() {
        assertTrue(hasBundleExtension("foo.xapk"))
        assertTrue(hasBundleExtension("Foo.APKM"))
        assertTrue(hasBundleExtension("bar.apks"))
        assertTrue(hasBundleExtension("baz.apkp"))
        assertFalse(hasBundleExtension("bar.apk"))
        assertFalse(hasBundleExtension("noextension"))
        assertFalse(hasBundleExtension(null))
    }

    @Test
    fun hasBundleExtension_resolvesExtensionFromMultiDotNames() {
        // GH#159 reporter's exact file: substringAfterLast('.') must yield the true
        // extension for names with version dots / apkpure.com in them, so the bundle
        // path is taken regardless of how many dots precede the extension.
        assertTrue(hasBundleExtension("Amazon+Shopping_32.12.4.100_APKPure.xapk"))
        assertTrue(hasBundleExtension("Amazon Shopping_28.15.2.100_apkpure.com.xapk"))
        assertTrue(hasBundleExtension("App_1.2.3.APKP"))
        // A multi-dot plain .apk is still not a bundle container.
        assertFalse(hasBundleExtension("com.example.app_1.2.3.apk"))
    }

    // --- APKPure .xapk manifest + APKMirror .apkm info.json ---

    @Test
    fun amazonXapkManifest_picksPackageNamedBaseAndAllSplits() {
        // Real APKPure manifest.json: base is named {package}.apk (not base.apk) and
        // version_code is numeric.
        val json = """
            {
              "xapk_version": 2,
              "package_name": "com.amazon.mShop.android.shopping",
              "version_code": 1241319416,
              "version_name": "32.12.4.100",
              "split_apks": [
                { "file": "com.amazon.mShop.android.shopping.apk", "id": "base" },
                { "file": "config.arm64_v8a.apk", "id": "config.arm64_v8a" },
                { "file": "config.xxhdpi.apk", "id": "config.xxhdpi" }
              ]
            }
        """.trimIndent()
        val manifest = parseXapkManifest(json)
        assertNotNull(manifest)
        assertEquals("com.amazon.mShop.android.shopping.apk", manifest!!.baseApkFile())
        assertEquals(
            listOf(
                "com.amazon.mShop.android.shopping.apk",
                "config.arm64_v8a.apk",
                "config.xxhdpi.apk"
            ),
            manifest.splitApkFiles()
        )
    }

    @Test
    fun parseApkmInfo_readsPackageLabelAndVersion() {
        val json = """
            {
              "apkm_version": 5,
              "app_name": "Google Journal",
              "apk_title": "Google Journal 2026.02.10.867917178",
              "release_version": "2026.02.10.867917178",
              "versioncode": "30271",
              "pname": "com.google.android.apps.pixel.aurelius",
              "arches": ["arm64-v8a"]
            }
        """.trimIndent()
        val info = parseApkmInfo(json)
        assertNotNull(info)
        assertEquals("com.google.android.apps.pixel.aurelius", info!!.packageName)
        assertEquals("Google Journal", info.appName)
        assertEquals("30271", info.versionCode)
        assertNull(parseApkmInfo("definitely not json"))
    }

    @Test
    fun apkmWithoutManifest_infoHintSelectsPackageNamedBase() {
        // .apkm has no manifest.json; the base is {package}.apk. The info.json
        // package hint must surface it as the base even though a split precedes it.
        val entries = listOf(
            "split_config.arm64_v8a.apk",
            "com.google.android.apps.pixel.aurelius.apk",
            "info.json",
            "icon.png"
        )
        val set = resolveBundleInstallSet(
            entries,
            manifestSplitFiles = null,
            packageName = "com.google.android.apps.pixel.aurelius"
        )
        assertEquals("com.google.android.apps.pixel.aurelius.apk", set.first())
    }

    // --- split completeness: a subset/stale manifest must never drop a real split ---

    @Test
    fun resolveBundleInstallSet_appendsSplitsMissingFromManifest() {
        val entries = listOf(
            "base.apk",
            "config.arm64_v8a.apk",
            "config.xxhdpi.apk",
            "manifest.json",
            "icon.png"
        )
        // Manifest omits config.xxhdpi.apk (stale/subset).
        val manifestSplits = listOf("base.apk", "config.arm64_v8a.apk")
        val set = resolveBundleInstallSet(entries, manifestSplits, packageName = null)
        assertEquals("base.apk", set.first())
        assertEquals(
            setOf("base.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk"),
            set.map { it.substringAfterLast('/') }.toSet()
        )
    }

    @Test
    fun resolveBundleInstallSet_ignoresManifestReferencingMissingFiles() {
        val entries = listOf("base.apk", "split_config.arm64_v8a.apk")
        // Manifest references a file that isn't in the archive → fall back to scan.
        val manifestSplits = listOf("base.apk", "config.does_not_exist.apk")
        val set = resolveBundleInstallSet(entries, manifestSplits, packageName = null)
        assertEquals("base.apk", set.first())
        assertFalse(set.any { it.contains("does_not_exist") })
        assertEquals(
            setOf("base.apk", "split_config.arm64_v8a.apk"),
            set.map { it.substringAfterLast('/') }.toSet()
        )
    }

    @Test
    fun resolveBundleInstallSet_keepsManifestOrderWhenComplete() {
        val entries = listOf(
            "config.arm64_v8a.apk",
            "com.amazon.mShop.android.shopping.apk",
            "config.xxhdpi.apk",
            "manifest.json"
        )
        val manifestSplits = listOf(
            "com.amazon.mShop.android.shopping.apk",
            "config.arm64_v8a.apk",
            "config.xxhdpi.apk"
        )
        val set = resolveBundleInstallSet(
            entries,
            manifestSplits,
            packageName = "com.amazon.mShop.android.shopping"
        )
        // Base first, exactly the manifest order, no extras.
        assertEquals(manifestSplits, set)
    }

    @Test
    fun resolveBundleInstallSet_returnsEmptyWhenNoApkEntries() {
        val entries = listOf("manifest.json", "icon.png", "obb/main.obb")
        assertTrue(resolveBundleInstallSet(entries, null, null).isEmpty())
    }

    @Test
    fun resolveBundleInstallSet_doesNotAppendForeignStandaloneApk() {
        // A complete manifest plus a stray standalone top-level APK (e.g. a
        // `universal.apk` fallback): the union must NOT add it, or install-multiple
        // would receive two base APKs and fail. Only real config/splits are appended.
        val entries = listOf(
            "base.apk",
            "config.arm64_v8a.apk",
            "universal.apk",
            "manifest.json"
        )
        val manifestSplits = listOf("base.apk", "config.arm64_v8a.apk")
        val set = resolveBundleInstallSet(entries, manifestSplits, packageName = null)
        assertEquals(manifestSplits, set)
        assertFalse(set.any { it.contains("universal") })
    }
}

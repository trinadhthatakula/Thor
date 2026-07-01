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
}

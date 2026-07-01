package com.valhalla.thor.data.repository

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure, framework-independent helpers for classifying an installer input as a
 * monolithic APK vs. a genuine bundle (XAPK/.apks/.apkm), and for selecting the
 * correct base APK inside a bundle.
 *
 * Kept free of any Android dependency so they are unit-testable without a device
 * PackageManager. The authoritative metadata parse still runs through
 * `getPackageArchiveInfo` in the callers; these helpers only decide *which* entry
 * to hand to it.
 *
 * Related: GH#207 (monolithic APK mis-parsed as bundle), GH#159 (XAPK parse
 * failure / wrong base pick).
 */

/** Tolerant, framework-independent JSON reader for XAPK manifests. */
private val bundleJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Tolerant representation of an XAPK `manifest.json`.
 *
 * Real-world manifests vary a lot: `version_code` is often numeric, `name` is
 * sometimes absent. Everything is therefore nullable with defaults so a single
 * missing/oddly-typed field never fails the whole deserialization (which used to
 * silently skip the correct base-APK extraction path — GH#159).
 */
@Serializable
data class XapkManifestInfo(
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("version_code") val versionCode: String? = null,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("permissions") val permissions: List<String> = emptyList(),
    @SerialName("icon") val iconFile: String? = null,
    @SerialName("split_apks") val splitApks: List<XapkSplitApkInfo> = emptyList()
) {
    /** File name of the entry flagged as `id == "base"`, if any. */
    fun baseApkFile(): String? = splitApks.firstOrNull { it.id.equals("base", ignoreCase = true) }?.file

    /** All split-apk file names declared in the manifest, in declaration order. */
    fun splitApkFiles(): List<String> = splitApks.map { it.file }
}

@Serializable
data class XapkSplitApkInfo(
    @SerialName("file") val file: String,
    @SerialName("id") val id: String = ""
)

/**
 * Parse an XAPK `manifest.json` tolerantly. Returns null only when the input is
 * not valid JSON at all; missing or oddly-typed fields are tolerated.
 */
fun parseXapkManifest(jsonText: String): XapkManifestInfo? = try {
    bundleJson.decodeFromString<XapkManifestInfo>(jsonText)
} catch (_: Exception) {
    null
}

/**
 * True if the archive has a top-level (root) `AndroidManifest.xml` entry. Every
 * real APK has exactly this; bundles (XAPK/.apks/.apkm) do not have one at the
 * archive root (their manifests live inside the nested APKs).
 */
fun hasTopLevelAndroidManifest(entryNames: List<String>): Boolean =
    entryNames.any { it.equals("AndroidManifest.xml", ignoreCase = true) }

/** True if the archive contains a top-level XAPK `manifest.json`. */
fun hasXapkManifest(entryNames: List<String>): Boolean =
    entryNames.any { it.equals("manifest.json", ignoreCase = true) }

/**
 * A file is a monolithic APK (single, installable APK) when it carries its own
 * top-level `AndroidManifest.xml`. This is the primary signal used to avoid the
 * old "it's a zip containing a .apk, therefore a bundle" mistake (GH#207).
 */
fun isMonolithicApk(entryNames: List<String>): Boolean =
    hasTopLevelAndroidManifest(entryNames)

/**
 * A file looks like a genuine bundle when it does NOT have a top-level
 * `AndroidManifest.xml`. An explicit top-level `manifest.json` (XAPK) is a
 * positive bundle signal too, but the absence of the manifest is the decisive
 * one — a monolithic APK always wins.
 */
fun looksLikeBundle(entryNames: List<String>): Boolean =
    !isMonolithicApk(entryNames) && (hasXapkManifest(entryNames) || entryNames.any {
        it.endsWith(".apk", ignoreCase = true)
    })

/**
 * True for obvious split/config APK entries that are never a valid base:
 * names whose file part starts with `split_` or `config.`, or contain
 * `.config.` (e.g. `com.example.app.config.xhdpi.apk`).
 *
 * [packageName], when known, guards against a false positive where the package
 * name itself contains `.config.` (e.g. `com.example.config.foo`, base entry
 * `com.example.config.foo.apk`): an entry whose basename is exactly
 * `{packageName}.apk` is ALWAYS the base and is never classified as a split.
 */
fun isSplitApkName(name: String, packageName: String? = null): Boolean {
    val fileName = name.substringAfterLast('/').lowercase()
    if (!fileName.endsWith(".apk")) return false
    // Exact `{packageName}.apk` is the base, never a split.
    if (!packageName.isNullOrBlank() && fileName == "$packageName.apk".lowercase()) return false
    return fileName.startsWith("split_") ||
        fileName.startsWith("config.") ||
        fileName.contains(".config.")
}

/**
 * Order the `.apk` entries of a bundle into base-APK candidates, best first:
 *  1. `base.apk` / `base-master.apk`
 *  2. `{package_name}.apk` (when the package name is known)
 *  3. any other non-split `.apk` (in archive order)
 *  4. split/config `.apk` entries, as a last resort
 *
 * The caller then picks the first candidate that actually parses via
 * `getPackageArchiveInfo` as a non-split. This fixes the "first .apk in zip
 * order (often a config split) wins" bug (GH#159).
 */
fun selectBaseApkCandidates(entryNames: List<String>, packageName: String?): List<String> {
    val apks = entryNames.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return emptyList()

    fun fileNameOf(name: String) = name.substringAfterLast('/').lowercase()

    // Pass the package name so an exact `{packageName}.apk` base is never
    // mis-classified as a split, even when the package name contains `.config.`.
    val (splits, nonSplits) = apks.partition { isSplitApkName(it, packageName) }

    val preferredNames = buildList {
        add("base.apk")
        add("base-master.apk")
        packageName?.takeIf { it.isNotBlank() }?.let { add("$it.apk".lowercase()) }
    }

    val ordered = LinkedHashSet<String>()

    // 1 + 2: explicit preferred names, in preference order.
    for (preferred in preferredNames) {
        nonSplits.firstOrNull { fileNameOf(it) == preferred }?.let { ordered.add(it) }
    }
    // 3: remaining non-splits in archive order.
    ordered.addAll(nonSplits)
    // 4: splits/config as last resort.
    ordered.addAll(splits)

    return ordered.toList()
}

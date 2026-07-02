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
 * Tolerant representation of an APKMirror `.apkm` `info.json` (schema v5). Its
 * layout differs from an XAPK `manifest.json`: the package is `pname`, the label
 * `app_name`, the version `release_version`, and the version code `versioncode`.
 * It does NOT enumerate the split file names — .apkm always ships a literal
 * `base.apk` plus `split_config.*.apk` entries — so we only mine it for the
 * package-name hint + display metadata (GH#159 follow-up: base detection must not
 * depend solely on the literal `base.apk` name).
 */
@Serializable
data class ApkmInfo(
    @SerialName("pname") val packageName: String? = null,
    @SerialName("app_name") val appName: String? = null,
    @SerialName("apk_title") val title: String? = null,
    @SerialName("release_version") val versionName: String? = null,
    @SerialName("versioncode") val versionCode: String? = null
)

/**
 * Parse an APKMirror `info.json` tolerantly. Returns null only when the input is
 * not valid JSON at all; missing or oddly-typed fields are tolerated.
 */
fun parseApkmInfo(jsonText: String): ApkmInfo? = try {
    bundleJson.decodeFromString<ApkmInfo>(jsonText)
} catch (_: Exception) {
    null
}

/** File extensions that unambiguously denote a bundle container (never a single APK). */
private val BUNDLE_EXTENSIONS = setOf("xapk", "apkm", "apks")

/**
 * True when [fileName] carries a known bundle extension (`.xapk`/`.apkm`/`.apks`).
 * A cheap, reliable secondary signal so a genuine bundle that also happens to
 * carry a stray top-level `AndroidManifest.xml` is never mis-parsed as a single
 * APK (root cause of the APKPure install failure).
 */
fun hasBundleExtension(fileName: String?): Boolean =
    fileName != null && fileName.substringAfterLast('.', "").lowercase() in BUNDLE_EXTENSIONS

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

/** True if the archive contains a top-level APKMirror `info.json`. */
fun hasApkmInfoJson(entryNames: List<String>): Boolean =
    entryNames.any { it.equals("info.json", ignoreCase = true) }

/**
 * True if the archive carries bundle sidecar metadata — an XAPK `manifest.json`
 * or an APKMirror `info.json`. Either is a decisive positive bundle signal.
 */
fun hasBundleMetadata(entryNames: List<String>): Boolean =
    hasXapkManifest(entryNames) || hasApkmInfoJson(entryNames)

/**
 * True for a *top-level* `.apk` entry (archive root, no `/` in the name). A real
 * single APK never has a sibling `.apk` at its root — only bundles do — so this
 * distinguishes a genuine bundle from a monolithic APK that merely embeds a
 * nested `assets/child.apk` (GH#207), whose name contains a `/`.
 */
fun isTopLevelApkEntry(name: String): Boolean =
    !name.contains('/') && name.endsWith(".apk", ignoreCase = true)

/**
 * A file is a monolithic APK (single, installable APK) only when it carries its
 * own top-level `AndroidManifest.xml` AND shows no bundle signal. Requiring the
 * *absence* of bundle signals — not just the presence of a manifest — is the fix
 * for genuine bundles (XAPK/.apkm/.apks) that also ship a stray top-level
 * `AndroidManifest.xml`: the old "AndroidManifest.xml ⇒ monolithic" gate routed
 * the whole multi-APK zip into `getPackageArchiveInfo`, which failed with
 * `FileNotFoundException: AndroidManifest.xml`.
 *
 * Bundle signals (any ⇒ NOT monolithic):
 *  - a known bundle [fileName] extension (`.xapk`/`.apkm`/`.apks`), when known;
 *  - a top-level `manifest.json` / `info.json`;
 *  - a top-level `.apk` sibling (a real APK never has one).
 *
 * The GH#207 case is preserved: a monolithic APK with a nested `assets/child.apk`
 * has no bundle metadata and no *top-level* `.apk`, so it stays monolithic.
 */
fun isMonolithicApk(entryNames: List<String>, fileName: String? = null): Boolean {
    if (hasBundleExtension(fileName)) return false
    if (!hasTopLevelAndroidManifest(entryNames)) return false
    if (hasBundleMetadata(entryNames)) return false
    if (entryNames.any { isTopLevelApkEntry(it) }) return false
    return true
}

/**
 * A file looks like a genuine bundle when it is not monolithic and shows at least
 * one positive bundle signal: sidecar metadata, a top-level `.apk`, a nested
 * split `.apk`, or a known bundle extension.
 */
fun looksLikeBundle(entryNames: List<String>, fileName: String? = null): Boolean =
    !isMonolithicApk(entryNames, fileName) && (
        hasBundleMetadata(entryNames) ||
            hasBundleExtension(fileName) ||
            entryNames.any { it.endsWith(".apk", ignoreCase = true) }
        )

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

/**
 * Resolve the complete, base-first set of APK entry names to hand to
 * `install-multiple` for a genuine bundle.
 *
 * When a `manifest.json` split list is present AND every declared split
 * physically exists, that list is authoritative for base ordering — but we then
 * *union* it with any additional top-level `.apk` entries the manifest omitted,
 * so a stale/subset manifest can never silently drop a physically-present split
 * (which would yield a base-only/partial install). Otherwise we fall back to
 * [selectBaseApkCandidates], which orders every `.apk` base-first.
 *
 * Returns an empty list only when there are no `.apk` entries at all (caller then
 * treats the input as monolithic).
 */
fun resolveBundleInstallSet(
    entryNames: List<String>,
    manifestSplitFiles: List<String>?,
    packageName: String?
): List<String> {
    val ordered = selectBaseApkCandidates(entryNames, packageName)
    if (manifestSplitFiles.isNullOrEmpty()) return ordered

    val available = entryNames.mapTo(HashSet()) { it.substringAfterLast('/') }
    // A manifest that references files not present is stale/partial: ignore it and
    // fall back to the entry scan rather than extracting nothing.
    if (!manifestSplitFiles.all { it.substringAfterLast('/') in available }) return ordered

    val listed = manifestSplitFiles.mapTo(HashSet()) { it.substringAfterLast('/') }
    // Only append entries that are genuinely config/splits the manifest omitted —
    // never a standalone/foreign top-level APK (e.g. a `universal.apk`), which would
    // add a second base and break install-multiple.
    val extras = ordered.filter {
        it.substringAfterLast('/') !in listed && isSplitApkName(it, packageName)
    }
    return manifestSplitFiles + extras
}

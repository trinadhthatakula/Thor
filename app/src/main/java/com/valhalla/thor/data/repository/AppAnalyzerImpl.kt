// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import com.valhalla.thor.domain.model.AnalyzedPackage
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.model.StagedPackage
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.util.getDisplayName
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Sub-directory of cacheDir holding staged installer inputs.
 *
 * Not `STAGING_DIR_NAME`: `com.valhalla.thor.domain.model.STAGING_DIR_NAME` is a public top-level
 * constant naming a *different* directory (`.thorbak-staging`, inside an app's own data root, where a
 * restore extracts before the swap). A file-private declaration of that name would win over an import
 * of the public one in this file without a warning, so the two are spelled apart.
 */
private const val INSTALL_STAGING_DIR_NAME = "staged_installs"

@Single(binds = [AppAnalyzer::class])
class AppAnalyzerImpl(
    private val context: Context,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppAnalyzer {

    override suspend fun analyze(uri: Uri): Result<AnalyzedPackage> = withContext(ioDispatcher) {
        val displayName = uri.getDisplayName(context)
        // Random, unpredictable temp names (CWE-377): avoids collisions between
        // concurrent analyses and predictable cache paths.
        val token = UUID.randomUUID()
        val stagingDir = File(context.cacheDir, INSTALL_STAGING_DIR_NAME).apply { mkdirs() }
        // The staged file outlives analyze() now, so a process death between the analysis and
        // the install (or the dismissal) would strand a full copy of the input — hundreds of MB
        // for an XAPK. Nothing else ever revisits this directory, so the sweep happens on the
        // way in.
        sweepStaleStagedPackages(stagingDir, System.currentTimeMillis())
        // The whole input is copied to disk once so it can be read with ZipFile
        // (random access via the central directory). ZipInputStream cannot handle
        // APKPure's STORED-with-data-descriptor entries (zero-size local headers) and
        // mis-reads the archive; ZipFile reads the central directory like `unzip`.
        //
        // That copy is also the ONE read of the caller's URI: the install runs off this file,
        // so a hostile provider cannot serve a clean APK to the sheet the user approves and
        // spyware to the `pm install -r -g` that follows. See StagedPackage.
        val bundleFile = File(stagingDir, "staged_$token")
        val apkFile = File(context.cacheDir, "analysis_$token.apk")

        val metadata = try {
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                Result.failure(Exception("Could not open the selected file."))
            } else {
                // The extraction budget applied at the door. A content provider is not an archive
                // — there is no compression ratio to bound — but a hostile one can stream forever,
                // and this copy runs before anything has decided the input is even a zip. Bounding
                // it here is also what lets the two whole-file copies downstream (stageInstallSet's
                // monolithic branch, the session's `base.apk` stream) reason about their source:
                // it is this file, and it cannot grow after this line.
                val copied = input.use { source ->
                    FileOutputStream(bundleFile).use { output ->
                        source.copyAtMostTo(output, MAX_EXTRACTED_TOTAL_BYTES)
                    }
                }
                if (copied == null) {
                    Result.failure(
                        Exception(
                            "The selected file is larger than " +
                                "${MAX_EXTRACTED_TOTAL_BYTES / (1024 * 1024)} MB and was not read."
                        )
                    )
                } else {
                    readMetadata(bundleFile, apkFile, displayName)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            // BundleZip bounds what it reads into memory, but decoding a crafted icon or handing
            // a crafted archive to the platform parser can still exhaust the heap — and an Error
            // is not an Exception, so it would sail past every catch between here and
            // viewModelScope and kill the process while the user was only *previewing* a file.
            Result.failure(Exception("Could not read the selected file.", e))
        } finally {
            apkFile.delete()
        }

        val analyzed = metadata
            .map { AnalyzedPackage(it, StagedPackage(bundleFile, displayName)) }
            // Nobody is going to install a file we could not describe, so this is the last
            // chance to delete it; on success the caller owns it until discard().
            .onFailure { bundleFile.delete() }

        // Ownership transfers on the RETURN, and a cancelled withContext does not return — it
        // throws, so the caller never assigns the result and never calls discard(). The copy above
        // is a blocking, non-cooperative loop that runs to completion regardless, so cancelling the
        // sheet mid-parse (swiping it away is the ordinary way to leave) stranded a full-size APK
        // in cacheDir. The sweep on the next analysis reclaims it, but an hour later and only if
        // there ever is a next analysis.
        if (!isActive) bundleFile.delete()
        analyzed
    }

    override fun discard(analyzed: AnalyzedPackage?) {
        analyzed?.staged?.file?.delete()
    }

    /**
     * Identify the already-staged [bundleFile], using [apkFile] as scratch space for a base APK
     * extracted out of a bundle. Reads no URI: everything here is the one staged copy.
     */
    private fun readMetadata(
        bundleFile: File,
        apkFile: File,
        displayName: String?
    ): Result<AppMetadata> {
        // Phase 1: Enumerate entries + read sidecar metadata (XAPK manifest.json /
        // APKMirror info.json) and icon bytes via ZipFile. If the file is not a
        // readable zip, entryNames stays empty and the monolithic parse below runs.
        var entryNames: List<String> = emptyList()
        var manifestBytes: ByteArray? = null
        var infoBytes: ByteArray? = null
        var iconBytes: ByteArray? = null
        try {
            // Single ZipFile pass for entry names + all sidecar/icon bytes, instead
            // of re-opening (and re-parsing the central directory of) the archive per
            // file.
            val contents = BundleZip.read(
                bundleFile,
                setOf("manifest.json", "info.json", "icon.png", "icon.jpg", "icon.webp")
            )
            entryNames = contents.entryNames
            manifestBytes = contents.bytes["manifest.json"]
            infoBytes = contents.bytes["info.json"]
            iconBytes = contents.bytes["icon.png"]
                ?: contents.bytes["icon.jpg"]
                ?: contents.bytes["icon.webp"]
        } catch (_: Exception) {
            // Not a readable zip — fall through to the monolithic whole-file parse.
        }

        // Phase 2: Monolithic-APK gate (GH#207). A single installable APK carries
        // its own top-level AndroidManifest.xml and shows no bundle signal — parse
        // the whole file as-is and NEVER scan inner .apk assets.
        //
        // No iconBytes: this file is an APK, so the authoritative icon is the one its own
        // application info loads. A root-level `icon.png` in an APK is not a thing APKs have —
        // it is a thing an archive gets given — and BundleZip.read now only matches at the root,
        // so this changes nothing for a real APK and removes the icon from what a crafted one
        // gets to choose. Same reasoning in Phase 5.
        if (isMonolithicApk(entryNames, displayName)) {
            val archiveInfo = parseArchiveSafely(bundleFile)
                ?: return Result.failure(
                    Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
                )
            return Result.success(metadataFrom(archiveInfo, bundleFile, iconBytes = null))
        }

        // Phase 3: Sidecar-metadata hint. The XAPK manifest.json / APKMirror
        // info.json declares the package; that hint drives base selection so it no
        // longer depends on a literal `base.apk` name. Tolerant deserialization
        // (GH#159) keeps a numeric version_code / missing name from nuking it.
        val manifest = manifestBytes?.let { bytes -> parseXapkManifest(String(bytes)) }
        val apkmInfo = infoBytes?.let { bytes -> parseApkmInfo(String(bytes)) }
        val packageHint = manifest?.packageName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.packageName?.takeIf { it.isNotBlank() }

        // Phase 3.5: the install plan. Deliberately the SAME call the installer resolves its
        // install set with (resolveInstallSetFromFile), so identity can only be read out of a file
        // that install will write. Deriving the two independently is what let one archive show a
        // signed base.apk on the sheet and install an unrelated payload.apk.
        //
        // The plan also filters entry names the writers would refuse (isSafeEntryFileName), which
        // is what makes "one list" mean one list: a name like `good\base.apk` stayed in the
        // candidates below and was dropped at extraction time, so the two sides agreed on the plan
        // and still disagreed on the bytes.
        val plan = resolveBundlePlan(
            entryNames,
            manifest?.splitApkFiles(),
            manifest?.baseApkFile(),
            packageHint
        )

        // Phase 4: Base selection (GH#159) — the manifest's declared base first,
        // then generic candidates (config/splits last), all of them inside the install
        // set. Extract each from the bundle and return the first that parses as a real,
        // non-split base APK.
        for (candidate in plan.identityCandidates) {
            val base = candidate.substringAfterLast('/')
            if (!BundleZip.extractEntryTo(bundleFile, base, apkFile)) continue
            val archiveInfo = parseArchiveSafely(apkFile)
            if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                return Result.success(metadataFrom(archiveInfo, apkFile, iconBytes))
            }
        }

        // Phase 5: parse the whole file as a monolithic APK — but ONLY when the installer would
        // stream the whole file too. `resolveInstallSetFromFile` reads an empty install set as
        // "monolithic", so that is precisely the condition under which this file's own manifest
        // describes the bytes `pm` ends up with.
        //
        // Behaviour change, deliberate: an archive that is a valid APK *and* carries installable
        // `.apk` entries used to reach here and lend its own (genuine, signed) identity to an
        // install of those inner entries. It is now reported unparseable instead. Nothing legitimate
        // has that shape — GH#207's case is a nested `assets/child.apk`, whose name contains a `/`
        // and so is never an install candidate — and it is the same substitution as F5, entered
        // from the other side.
        if (plan.installSet.isEmpty()) {
            parseArchiveSafely(bundleFile)?.let {
                return Result.success(metadataFrom(it, bundleFile, iconBytes = null))
            }
        }

        // Phase 6: Sidecar-only metadata fallback (GH#159). If no bundled APK parsed but the bundle
        // declares its own identity, surface that — it lets a `.apkm` whose payload this device
        // cannot parse still show a name and an icon instead of reading as "failed to parse".
        //
        // LAST, where it used to run before the whole-file parse. Reaching it before meant a single
        // attacker-added root `manifest.json` — one decisive bundle signal — was enough to stop a
        // perfectly valid APK ever meeting the platform parser: no `.apk`-suffixed entry exists in
        // an APK, so the candidate loop above found nothing to parse, and the sheet took its label,
        // package name, version, permissions and icon from the attacker's JSON while the install
        // side installed the real thing. Below the parse, a file that parses as an APK always
        // identifies itself.
        buildMetadataFromSidecar(manifest, apkmInfo, iconBytes)?.let {
            return Result.success(it)
        }

        return Result.failure(
            Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
        )
    }

    /**
     * [parseArchive] that also swallows a thrown getPackageArchiveInfo (it can throw
     * — not just return null — on some ROMs/APIs) so callers get a clean null.
     */
    private fun parseArchiveSafely(file: File): PackageInfo? = try {
        parseArchive(file)
    } catch (_: Exception) {
        null
    }

    /** Parse an on-disk APK via getPackageArchiveInfo across API levels. */
    private fun parseArchive(tempFile: File): PackageInfo? {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                tempFile.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(tempFile.absolutePath, flags)
        }
    }

    /**
     * Build [AppMetadata] purely from bundle sidecar JSON when no bundled APK could
     * be parsed. Returns null if neither sidecar declares a package name. The icon
     * comes from the bundle's own icon bytes (there is no APK to load one from).
     *
     * The name is validated first. Every other path gets its package name from the platform
     * parser; this one gets it from a JSON file inside an archive a stranger's app handed us,
     * and it goes on to drive the installed-app lookup and the downgrade verdict. Rejecting it
     * here means the file is reported unparseable — the right answer for a bundle that will not
     * say honestly what it is.
     *
     * Known residual, deliberately left: when the archive DOES hold installable `.apk` entries and
     * none of them parsed, this still answers, and `pm install-multiple` will then run on entries
     * whose identity nothing confirmed. Exploiting it needs an APK that `getPackageArchiveInfo`
     * rejects and PackageManagerService accepts — the same parser on both sides of a binder call —
     * so the precondition is unproven, whereas refusing outright would take `.apkm` payloads this
     * device genuinely cannot read (the GH#159 case this fallback exists for) off the sheet
     * entirely. The cases that ARE reachable without a parser differential — a sidecar overriding a
     * file that parses, and a sidecar describing an install set drawn from somewhere else — are
     * closed by the phase order above and by [resolveBundlePlan] respectively.
     */
    private fun buildMetadataFromSidecar(
        manifest: XapkManifestInfo?,
        apkmInfo: ApkmInfo?,
        iconBytes: ByteArray?
    ): AppMetadata? {
        val pkg = manifest?.packageName?.takeIf { isValidSidecarPackageName(it) }
            ?: apkmInfo?.packageName?.takeIf { isValidSidecarPackageName(it) }
            ?: return null
        val label = manifest?.name?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.appName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.title?.takeIf { it.isNotBlank() }
            ?: pkg
        val versionName = manifest?.versionName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.versionName?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        // Null when neither sidecar declares a usable code. This is the ONLY path that can produce
        // an unknown version code — there is no APK to parse here — and consumers must gate on it
        // (see isVersionDowngrade): a bundle whose sidecar carries a version *name* in version_code
        // used to land here as 0 and read as a downgrade against every installed app.
        val versionCode = resolveSidecarVersionCode(manifest, apkmInfo)
        val icon = iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return AppMetadata(
            label = label,
            packageName = pkg,
            version = versionName,
            versionCode = versionCode,
            iconPath = persistIcon(icon, pkg, versionCode),
            permissions = manifest?.permissions ?: emptyList()
        )
    }

    /** Build [AppMetadata] from a parsed [PackageInfo], preferring the XAPK icon. */
    private fun metadataFrom(
        archiveInfo: PackageInfo,
        tempFile: File,
        iconBytes: ByteArray?
    ): AppMetadata {
        // Defensive validation (hardens GH#207): never build metadata with a
        // null/blank package identity or a null applicationInfo — a garbage identity
        // would drive the wrong installed-package lookup and false downgrade. Throwing
        // here routes to the caller's catch -> Result.failure -> error_parse_package.
        require(archiveInfo.applicationInfo != null) {
            "Parsed archive has no applicationInfo; not an installable APK."
        }
        require(!archiveInfo.packageName.isNullOrBlank()) {
            "Parsed archive has a null/blank package name; not an installable APK."
        }

        val pm = context.packageManager
        archiveInfo.applicationInfo?.sourceDir = tempFile.absolutePath
        archiveInfo.applicationInfo?.publicSourceDir = tempFile.absolutePath

        val iconBitmap: Bitmap? = when {
            iconBytes != null -> BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
            else -> archiveInfo.applicationInfo?.loadIcon(pm)?.toBitmap()
        }

        return AppMetadata(
            label = archiveInfo.applicationInfo?.loadLabel(pm)?.toString() ?: "Unknown",
            packageName = archiveInfo.packageName,
            version = archiveInfo.versionName ?: "Unknown",
            versionCode = archiveInfo.longVersionCode,
            iconPath = persistIcon(iconBitmap, archiveInfo.packageName, archiveInfo.longVersionCode),
            permissions = archiveInfo.requestedPermissions?.toList() ?: emptyList()
        )
    }

    /**
     * Persist a decoded icon [bitmap] to a PNG in a dedicated installer icon cache, returning its
     * absolute path (or null when there is no icon). The domain [AppMetadata] carries only this
     * path — Bitmap decoding stays in the data layer, only the destination changes.
     *
     * Keyed by [iconCacheKey], which hashes the package name rather than using it: the sidecar
     * path can reach here with whatever string a `manifest.json` declared, `java.io.File` does not
     * normalise `..` (the syscall does), and `{"package_name":"../../databases/thor_database"}`
     * would otherwise drop PNG bytes on the Room file. Hashing means no caller-supplied string
     * ever reaches File() — the sidecar name is validated too, so this is the second of two locks.
     *
     * The key still varies with [versionCode]: Coil keys its File memory cache by the path only
     * (addLastModifiedToFileCacheKey defaults false), so a fixed per-package path would serve a
     * STALE icon after a version bump — a distinct path per version busts that. A null (unknown)
     * version code gets its own `_unknown` key rather than sharing the `_0` slot with a real APK
     * that legitimately declares version code 0. Written to a unique temp file then atomically
     * renamed, so two concurrent same-(pkg,version) analyses can never expose a partial/corrupt
     * PNG to Coil (either the old complete file or the new one).
     *
     * Best-effort: any decode/IO failure yields a null path (never crashes parsing). This cache
     * file is intentionally NOT deleted in analyze()'s finally — the UI reads it after analyze()
     * returns; it lives in cacheDir so the OS can reclaim it.
     */
    private fun persistIcon(bitmap: Bitmap?, packageName: String, versionCode: Long?): String? {
        if (bitmap == null) return null
        return runCatching {
            val iconDir = File(context.cacheDir, "installer_icons").apply { mkdirs() }
            val key = iconCacheKey(packageName, versionCode)
            val dest = File(iconDir, "$key.png")
            val tmp = File(iconDir, "$key.${java.util.UUID.randomUUID()}.png.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                }
            } finally {
                // renameTo consumes tmp on success; on the copyTo fallback (or any failure
                // mid-write) delete it so we never leak a .tmp in the cache dir.
                if (tmp.exists()) tmp.delete()
            }
            dest.absolutePath
        }.getOrNull()
    }

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable) return this.bitmap

        val bitmap = createBitmap(intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}

/** How long a staged input may sit unclaimed before the next analysis reclaims its disk. */
internal const val STAGED_PACKAGE_TTL_MILLIS = 60L * 60L * 1000L

/**
 * Delete staged inputs under [dir] last touched more than [ttlMillis] before [now], returning how
 * many went. Only ever reclaims a *stranded* file: the owner deletes its own on every exit path,
 * and an hour is far longer than any live analyse-then-install, so a file this old belongs to a
 * process that no longer exists. Returns 0 for a directory that does not exist.
 */
internal fun sweepStaleStagedPackages(
    dir: File,
    now: Long,
    ttlMillis: Long = STAGED_PACKAGE_TTL_MILLIS
): Int = (dir.listFiles() ?: emptyArray())
    .count { it.isFile && now - it.lastModified() > ttlMillis && it.delete() }

/**
 * Cache-file key for an icon belonging to [packageName] at [versionCode].
 *
 * A hash, not the name: the name can come from an untrusted sidecar and this becomes a path.
 * Truncated to 128 bits, which is far more collision resistance than a per-package cache slot
 * needs, and the hex alphabet cannot express a path component.
 */
internal fun iconCacheKey(packageName: String, versionCode: Long?): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(packageName.toByteArray())
    val hex = digest.take(16).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    return "${hex}_${versionCode ?: "unknown"}"
}

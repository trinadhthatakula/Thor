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
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.AppAnalyzer
import com.valhalla.thor.util.getDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Single(binds = [AppAnalyzer::class])
class AppAnalyzerImpl(private val context: Context) : AppAnalyzer {

    override suspend fun analyze(uri: Uri): Result<AppMetadata> = withContext(Dispatchers.IO) {
        val displayName = uri.getDisplayName(context)
        // Random, unpredictable temp names (CWE-377): avoids collisions between
        // concurrent analyses and predictable cache paths.
        val token = UUID.randomUUID()
        // The whole input is copied to disk once so it can be read with ZipFile
        // (random access via the central directory). ZipInputStream cannot handle
        // APKPure's STORED-with-data-descriptor entries (zero-size local headers) and
        // mis-reads the archive; ZipFile reads the central directory like `unzip`.
        val bundleFile = File(context.cacheDir, "analysis_bundle_$token")
        val apkFile = File(context.cacheDir, "analysis_$token.apk")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(bundleFile).use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(
                Exception("Could not open the selected file.")
            )

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
            if (isMonolithicApk(entryNames, displayName)) {
                val archiveInfo = parseArchiveSafely(bundleFile)
                    ?: return@withContext Result.failure(
                        Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
                    )
                return@withContext Result.success(metadataFrom(archiveInfo, bundleFile, iconBytes))
            }

            // Phase 3: Sidecar-metadata hint. The XAPK manifest.json / APKMirror
            // info.json declares the package; that hint drives base selection so it no
            // longer depends on a literal `base.apk` name. Tolerant deserialization
            // (GH#159) keeps a numeric version_code / missing name from nuking it.
            val manifest = manifestBytes?.let { bytes -> parseXapkManifest(String(bytes)) }
            val apkmInfo = infoBytes?.let { bytes -> parseApkmInfo(String(bytes)) }
            val packageHint = manifest?.packageName?.takeIf { it.isNotBlank() }
                ?: apkmInfo?.packageName?.takeIf { it.isNotBlank() }

            // Phase 4: Base selection (GH#159) — the manifest's declared base first,
            // then generic candidates (config/splits last). Extract each from the
            // bundle and return the first that parses as a real, non-split base APK.
            val baseCandidates = buildList {
                manifest?.baseApkFile()?.let { add(it) }
                addAll(selectBaseApkCandidates(entryNames, packageHint))
            }.distinctBy { it.substringAfterLast('/').lowercase() }

            for (candidate in baseCandidates) {
                val base = candidate.substringAfterLast('/')
                if (!BundleZip.extractEntryTo(bundleFile, base, apkFile)) continue
                val archiveInfo = parseArchiveSafely(apkFile)
                if (archiveInfo != null && archiveInfo.applicationInfo != null) {
                    return@withContext Result.success(metadataFrom(archiveInfo, apkFile, iconBytes))
                }
            }

            // Phase 4.5: Sidecar-only metadata fallback (GH#159). If no bundled APK
            // parsed but the bundle declares its own identity, surface that — it is the
            // bundle's own package (never a nested APK's), so it cannot cause the
            // GH#207 wrong-identity downgrade, and it lets the (independent) installer
            // path proceed instead of the whole flow reading as "failed to parse".
            buildMetadataFromSidecar(manifest, apkmInfo, iconBytes)?.let {
                return@withContext Result.success(it)
            }

            // Phase 5: Last resort — parse the whole file as a monolithic APK (only
            // reachable when it wasn't a readable bundle at all).
            val archiveInfo = parseArchiveSafely(bundleFile)
                ?: return@withContext Result.failure(
                    Exception("Failed to parse APK manifest. The file might be corrupted or encrypted.")
                )
            Result.success(metadataFrom(archiveInfo, bundleFile, iconBytes))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            bundleFile.delete()
            apkFile.delete()
        }
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
     */
    private fun buildMetadataFromSidecar(
        manifest: XapkManifestInfo?,
        apkmInfo: ApkmInfo?,
        iconBytes: ByteArray?
    ): AppMetadata? {
        val pkg = manifest?.packageName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.packageName?.takeIf { it.isNotBlank() }
            ?: return null
        val label = manifest?.name?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.appName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.title?.takeIf { it.isNotBlank() }
            ?: pkg
        val versionName = manifest?.versionName?.takeIf { it.isNotBlank() }
            ?: apkmInfo?.versionName?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        val versionCode = (manifest?.versionCode ?: apkmInfo?.versionCode)?.toLongOrNull() ?: 0L
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
     * Keyed by packageName AND [versionCode]: Coil keys its File memory cache by the path only
     * (addLastModifiedToFileCacheKey defaults false), so a fixed per-package path would serve a
     * STALE icon after a version bump — a distinct path per version busts that. Written to a unique
     * temp file then atomically renamed, so two concurrent same-(pkg,version) analyses can never
     * expose a partial/corrupt PNG to Coil (either the old complete file or the new one).
     *
     * Best-effort: any decode/IO failure yields a null path (never crashes parsing). This cache
     * file is intentionally NOT deleted in analyze()'s finally — the UI reads it after analyze()
     * returns; it lives in cacheDir so the OS can reclaim it.
     */
    private fun persistIcon(bitmap: Bitmap?, packageName: String, versionCode: Long): String? {
        if (bitmap == null) return null
        return runCatching {
            val iconDir = File(context.cacheDir, "installer_icons").apply { mkdirs() }
            val dest = File(iconDir, "${packageName}_$versionCode.png")
            val tmp = File(iconDir, "${packageName}_$versionCode.${java.util.UUID.randomUUID()}.png.tmp")
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

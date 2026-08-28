// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Environment
import android.text.format.Formatter
import androidx.core.graphics.createBitmap
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.data.util.XapkExpansion
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ObbExportStagingDir
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeCommandClass
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.bundleFileNameFor
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable/exportable app bundle in the cache dir in the requested [BundleFormat]:
 * a monolithic `.apk` copy, or a zip of base + splits + sidecars as `.apks` (metadata.json +
 * manifest.json) or `.xapk` (the same, plus a root icon.png). Copies with a root fallback for
 * protected/system apps.
 */
@Single(binds = [AppBundleBuilder::class])
class AppBundleBuilderImpl(
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val apksMetadataGenerator: ApksMetadataGenerator,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) : AppBundleBuilder {
    override suspend fun build(
        appInfo: AppInfo,
        cacheSubDir: String,
        format: BundleFormat,
        fileName: String?,
        execution: PrivilegeExecutionContext,
    ): Result<File> = withContext(ioDispatcher) {
        // Per-package subdir. Bulk share builds each selected app sequentially into
        // the same cacheSubDir and hands all the resulting content:// URIs to
        // ACTION_SEND_MULTIPLE together AFTER the loop; wiping the whole dir on each
        // call would delete earlier apps' bundles before they are read. Distinct
        // packages (a multi-select can't pick the same app twice) never collide.
        val cacheDir = File(File(context.cacheDir, cacheSubDir), appInfo.packageName)
        // Not under cacheDir: the privileged shell that copies an expansion out of
        // Android/obb/<pkg>/ cannot write into /data/data/<thor> (0700), so the staged copies have
        // to land somewhere both parties can reach. That also puts them outside everything the
        // catch blocks below wipe, which is why this dir is deleted explicitly on all three exits —
        // a failed export of a 4 GB game would otherwise leave 4 GB in external cache until the
        // next export of the same package, which may never come.
        //
        // Declared here rather than inside the try for exactly that reason: a `val` created inside
        // the try is not in scope in the catch blocks, and a staging dir a catch cannot see is a
        // staging dir a failed export cannot free.
        //
        // The directory name is the shared constant, not a literal: `ArchiveOrphanSweeper` removes
        // this whole tree at launch, which is the only thing that cleans up after a *kill* mid-build,
        // and a second spelling would point the sweep at a directory nothing writes to.
        val obbStagingDir = context.externalCacheDir?.let {
            File(it, "${ObbExportStagingDir.NAME}/${appInfo.packageName}")
        }
        try {
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            // bundleFileNameFor owns the naming rule *and* the sanitiser — appName/versionName are
            // app-controlled and formattedAppName() only strips spaces, so a "/" or ".." could
            // escape cacheDir once copyFileSafely() falls back to a root `cp`. A caller-supplied
            // name gets the same treatment for the same reason; a batch builds its names from the
            // same function, so in practice this re-sanitises an already-safe string.
            val finalFile = File(cacheDir, sanitizedName(fileName, appInfo, format))
            if (format == BundleFormat.APK) {
                // Base only, even for a split app: that is what a monolithic .apk means, and
                // autoFor() never picks this format for one.
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                    ?: throw IllegalStateException("No source path found")
                if (!copyFileSafely(sourcePath, finalFile, execution)) {
                    throw IllegalStateException("Failed to copy base APK")
                }
            } else {
                // The zip branch serves .apks and .xapk alike. XAPK takes it even when the app
                // has no splits — a base-only .xapk is a legal artifact, and an explicit format
                // request must never silently hand back a different container.
                val tempSplitDir = File(cacheDir, "splits_staging")
                tempSplitDir.mkdirs()

                val allPaths = mutableListOf<String>()
                // publicSourceDir first, exactly as the APK branch above resolves it, so a
                // single-APK app exports the same base whichever format was asked for.
                (appInfo.publicSourceDir ?: appInfo.sourceDir)?.let { allPaths.add(it) }
                allPaths.addAll(appInfo.splitPublicSourceDirs)

                val plan = stagedApkNames(allPaths)
                if (plan.isEmpty()) {
                    throw IllegalStateException("No APK paths to copy")
                }
                // Every one, or none. A split that will not copy — no root, a protected mount —
                // used to be dropped and the export still reported as a success, handing the user
                // a bundle that installs missing its ABI or its resources, or refuses to install
                // at all. There is no partial backup of a split app: an .apks/.xapk is only
                // meaningful if it holds the whole set. Failing here reaches the catch below,
                // which wipes the staging dir with it.
                val apkFiles = plan.map { (path, name) ->
                    val destFile = File(tempSplitDir, name)
                    if (!copyFileSafely(path, destFile, execution)) {
                        throw IllegalStateException("Failed to copy APK: $name")
                    }
                    destFile
                }
                // The APKs that make it into the zip, summed before the sidecars are staged
                // because the XAPK manifest has to carry this number.
                val totalApkSize = apkFiles.sumOf { it.length() }

                // Only .xapk carries expansions, so only .xapk pays for the probe.
                val probe = if (format == BundleFormat.XAPK) {
                    systemRepository.probeObb(appInfo.packageName, execution)
                } else {
                    ObbProbe.None
                }
                // An Undetermined probe used to throw here, and the export sheet disabled the .xapk
                // chip to match: a verdict of "we could not tell" refused the whole format. That is
                // fail-closed on *unknowability*, and it turned the ordinary case into an error —
                // most apps have no expansion files at all, so most of the time the answer we could
                // not read was "there is nothing to read". Per the owner we proceed instead: an
                // .xapk built for an app that has no game data is a correct .xapk, and one built
                // for an app that does is no worse than the .apk export the user would otherwise
                // fall back to.
                //
                // The line this draws is between *unknown* and *known lost*, and only the second
                // half of it moved. `requireStagedExpansions` below still throws, because Present
                // means the probe measured real files and a copy that then fails is data we know we
                // are dropping. That is GH#164, and it stays fatal.
                val obbFiles = expansionsToPack(format, probe)

                // Every failure below throws rather than returning a Result: this whole block is
                // inside the try, so a `return@withContext Result.failure(...)` is an ordinary
                // return that skips both catch blocks — and with them the only cleanup that reaches
                // the staging dir. The surrounding code fails the same way (`IllegalStateException
                // ("Failed to copy APK: …")`) and `catch (e: Exception)` turns it into a failed
                // Result with the message intact.
                val expansionSources = if (obbFiles.isEmpty()) {
                    emptyList()
                } else {
                    val externalCache = context.externalCacheDir
                    if (externalCache == null || obbStagingDir == null) {
                        throw IOException(
                            "external storage is unavailable, so the game data cannot be staged"
                        )
                    }
                    // usableSpace is read from externalCache, not from obbStagingDir: that dir does
                    // not exist yet here, and File.usableSpace on a non-existent path returns 0,
                    // which would fail every export with a phantom shortfall.
                    val shortfall = expansionSpaceShortfall(
                        cacheDir = cacheDir,
                        externalCache = externalCache,
                        apkBytes = totalApkSize,
                        obbBytes = obbFiles.sumOf { it.sizeBytes }
                    )
                    if (shortfall > 0L) {
                        throw IOException(
                            "not enough free space to pack this app's game data — about " +
                                "${Formatter.formatShortFileSize(context, shortfall)} more is needed"
                        )
                    }
                    requireStagedExpansions(
                        requested = obbFiles,
                        staged = stageExpansions(
                            appInfo.packageName,
                            obbFiles,
                            obbStagingDir,
                            execution
                        )
                    )
                }

                // Source path *and* staged name together: stagedApkNames renames a leaf collision,
                // and a manifest that named the source leaf would then describe an entry the zip
                // does not contain. See ApksMetadataGenerator.StagedApk.
                val stagedApks = plan.map { (path, name) ->
                    ApksMetadataGenerator.StagedApk(sourcePath = path, entryName = name)
                }

                // metadata.json is written into the .xapk too. It is the SAI/.apks descriptor,
                // so SAI and APKPure ignore it as an unknown root entry, and carrying it means
                // both containers stage identically and an .apks-oriented reader can still make
                // sense of a .xapk. Thor's own installer reads manifest.json, not this file —
                // dropping it from .apks would break third-party readers, not Thor.
                val metadataFile = File(tempSplitDir, "metadata.json")
                apksMetadataGenerator.generateJson(appInfo, metadataFile)

                val iconFile = if (format == BundleFormat.XAPK) {
                    stageIcon(appInfo, tempSplitDir)
                } else {
                    null
                }

                val manifestFile = File(tempSplitDir, "manifest.json")
                if (format == BundleFormat.XAPK) {
                    // A null icon name leaves the field out altogether. A .xapk that names an
                    // icon.png it does not contain is worse than one that names nothing.
                    //
                    // total_size widens to include the staged expansions here and *only* here: it
                    // describes the archive, and the expansions are in the archive. totalApkSize
                    // itself stays APK-only because the space arithmetic above needs the two
                    // figures apart — the APKs are counted twice on internal storage, the OBB once
                    // per volume.
                    manifestFile.writeText(
                        apksMetadataGenerator.generateManifestJson(
                            appInfo,
                            totalApkSize + expansionSources.sumOf { it.file.length() },
                            iconFile?.name,
                            stagedApks,
                            expansionDescriptors(expansionSources)
                        )
                    )
                } else {
                    apksMetadataGenerator.generateManifestJson(appInfo, manifestFile, stagedApks)
                }

                val sidecars = listOfNotNull(metadataFile, manifestFile, iconFile)
                // Entry order and entry names are zipSourcesFor's decision now; see its KDoc for
                // why a .xapk leads with its sidecars and why .apks drops expansions outright.
                // expansionSources is empty for every format but .xapk, and for a .xapk whose app
                // has no OBB, so this produces exactly the entry list it did before for those.
                zipFiles(
                    zipSourcesFor(format, apkFiles, sidecars, expansionSources),
                    finalFile
                )
                tempSplitDir.deleteRecursively()
                // The zip now holds its own copy of every expansion, so the staged ones are dead
                // weight in external cache from this line on.
                obbStagingDir?.deleteRecursively()
            }
            Result.success(finalFile)
        } catch (e: CancellationException) {
            // A cancel lands mid-copy, so staging holds however much of a multi-GB app got
            // written. Nothing has been handed out yet — no content:// URI, no returned File —
            // so this copy has no reader, and here is the only chance to free it: the dir is
            // otherwise wiped by the *next* build of this same package, which may never come.
            // The staged expansions go with it, and they are the larger half — cacheDir.
            // deleteRecursively() does not reach them, because they are not under cacheDir.
            cacheDir.deleteRecursively()
            obbStagingDir?.deleteRecursively()
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            cacheDir.deleteRecursively()
            obbStagingDir?.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * The name to write, with a caller's suggestion honoured only once it is safe to use as a leaf.
     *
     * `..` and `.` are the two names that would still be path traversal after the character filter
     * — the filter maps `/` away but leaves dots alone — and a blank name is not a file at all.
     * Each of those falls back to the derived name rather than failing the export.
     */
    private fun sanitizedName(fileName: String?, appInfo: AppInfo, format: BundleFormat): String {
        val derived = bundleFileNameFor(appInfo, format)
        val requested = fileName?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.trim() ?: return derived
        return if (requested.isEmpty() || requested == "." || requested == "..") derived
        else requested
    }

    /**
     * Stages the `icon.png` root entry for a `.xapk`, preferring the full-resolution PNG
     * AppIconLoader already persisted and rendering from the PackageManager otherwise.
     * Returns null when neither works — SAI treats the icon as optional, so a missing one
     * costs the user a picture, never the export.
     */
    private fun stageIcon(appInfo: AppInfo, stagingDir: File): File? {
        return try {
            val destFile = File(stagingDir, "icon.png")
            val cachedIcon = File(File(context.filesDir, "app_icons"), "${appInfo.packageName}.png")
            // Same freshness rule AppIconFetcher applies: an app update invalidates the cached
            // PNG, so a cache written before lastUpdateTime would ship the previous release's
            // icon inside the .xapk.
            val cacheIsFresh = cachedIcon.exists() &&
                    cachedIcon.length() > 0 &&
                    cachedIcon.lastModified() >= appInfo.lastUpdateTime
            if (cacheIsFresh) {
                cachedIcon.copyTo(destFile, overwrite = true)
            } else {
                val bitmap =
                    context.packageManager.getApplicationIcon(appInfo.packageName).toIconBitmap()
                FileOutputStream(destFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            destFile.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            null
        }
    }

    /** Renders a launcher icon at its intrinsic size — adaptive icons are the common case now
     *  and are not [BitmapDrawable]s, so this draws through a canvas rather than casting. */
    private fun Drawable.toIconBitmap(): Bitmap {
        (this as? BitmapDrawable)?.bitmap?.let { return it }
        // A drawable with no intrinsic size (a plain ColorDrawable icon, say) would render 1x1
        // and put a useless entry in the zip; give it a launcher-sized square instead.
        val bitmap = createBitmap(
            intrinsicWidth.takeIf { it > 0 } ?: FALLBACK_ICON_PX,
            intrinsicHeight.takeIf { it > 0 } ?: FALLBACK_ICON_PX
        )
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bounds = oldBounds
        return bitmap
    }

    /**
     * How many bytes short the device is for staging and zipping this app's expansions, or 0 when
     * there is room.
     *
     * Split out of [build] only to keep the lint suppression on one small declaration instead of on
     * the whole builder. `File.usableSpace` is the deliberate choice, not an oversight lint caught:
     * lint's `UsableSpace` check points at `getAllocatableBytes`/`getFreeBytes`, which add back the
     * clearable cache quota and so report space that exists only if the platform evicts other apps'
     * caches at the right moment. A staging copy that has to survive until the zip is closed needs
     * the pessimistic figure. (`StorageStatsHelper` suppresses the same check for the mirror-image
     * reason: there the point is to match what `PackageManagerService.freeStorage` itself measures.)
     */
    @Suppress("UsableSpace")
    private fun expansionSpaceShortfall(
        cacheDir: File,
        externalCache: File,
        apkBytes: Long,
        obbBytes: Long
    ): Long = spaceShortfall(
        need = bundleSpaceRequirement(apkBytes = apkBytes, obbBytes = obbBytes),
        internalFree = cacheDir.usableSpace,
        externalFree = externalCache.usableSpace,
        // Same emulated volume on any phone without an SD card, in which case the two free-space
        // figures are the same bytes counted twice and the two requirements add rather than overlap.
        sameVolume = cacheDir.totalSpace == externalCache.totalSpace
    )

    /**
     * Copy each expansion into a directory Thor can read, returning one [ZipSource] per file, or
     * null if any single copy failed.
     *
     * All-or-nothing on purpose. A `.xapk` missing one of a game's expansion files installs and
     * then fails at runtime in a way the user cannot diagnose — the exact complaint in GH#164 —
     * so a partial capture is a failed export, not a degraded one.
     *
     * Unlike [copyFileSafely] this never tries a direct read first: `Android/obb/<other-pkg>/` is
     * unreadable to Thor on every Android version this app supports, so an unprivileged attempt
     * is a guaranteed exception and a wasted syscall.
     *
     * Cleanup of [stagingDir] belongs to the caller, on every one of its exits — see the comment
     * where it is declared. Deliberately no `try`/`catch` here: `return null` inside the loop is a
     * non-local return, so anything written after the loop to tidy up would not run on the paths
     * that need it most.
     */
    private suspend fun stageExpansions(
        packageName: String,
        files: List<ObbFile>,
        stagingDir: File,
        execution: PrivilegeExecutionContext,
    ): List<ZipSource>? {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return null

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
        return files.map { obb ->
            val dest = File(stagingDir, obb.name)
            val command = obbCopyCommand(
                externalStorageDir = externalRoot,
                packageName = packageName,
                leaf = obb.name,
                destPath = dest.absolutePath
            ) ?: return null

            val shellResult = systemRepository.executeShellCommand(
                command,
                execution.copy(commandClass = OBB_COPY),
            )
            shellResult.exceptionOrNull()?.rethrowIfPrivilegeExecutionFailure()
            val result = shellResult.getOrNull()
            if (result == null || result.first != 0) return null
            // The shell reported success; verify the bytes actually arrived. A `cp` that hits a
            // full volume can still exit 0 on some toybox builds, and a size that no longer
            // matches what the probe measured means the app rewrote the file underneath us —
            // either way the capture is not the one the manifest is about to describe.
            if (!dest.isFile || dest.length() != obb.sizeBytes) return null

            ZipSource(dest, expansionEntryName(packageName, obb.name))
        }
    }

    private suspend fun copyFileSafely(
        sourcePath: String,
        destFile: File,
        execution: PrivilegeExecutionContext,
    ): Boolean {
        return try {
            copyCancellable(File(sourcePath), destFile)
            true
        } catch (e: CancellationException) {
            // Ahead of the broad catch, or a cancelled copy falls through to the root fallback and
            // starts the whole multi-gigabyte copy again as a shell command — which no longer
            // observes cancellation at all.
            throw e
        } catch (_: Exception) {
            val rootCopy = systemRepository.copyFileWithRoot(
                sourcePath, destFile.absolutePath, execution,
            )
            rootCopy.exceptionOrNull()?.rethrowIfPrivilegeExecutionFailure()
            rootCopy.isSuccess
        }
    }

    /**
     * `File.copyTo` in chunks, so a cancel does not have to wait out a whole APK.
     *
     * A bulk export cancels by cancelling the coroutine, and the replacement run waits for this
     * one to unwind before it starts staging. A single uninterruptible `copyTo` of a 2 GB app
     * therefore becomes a 2 GB stall with the UI showing "0 of N" — the check per 8 KB chunk is a
     * volatile read against an IO-bound loop, which is free by comparison.
     */
    private suspend fun copyCancellable(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private suspend fun zipFiles(sources: List<ZipSource>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            // APK entries are already DEFLATEd, so re-compressing them is pure CPU for ~0%
            // saving. Measurable when a bulk share zips dozens of apps in a row.
            // This is level-0 DEFLATE, not ZipEntry.STORED as real .xapk files use: STORED
            // needs a CRC32 and size per entry up front, i.e. a second full read of every APK
            // — more IO than it saves. Every ZipFile-based reader (SAI, Thor's own BundleZip)
            // is indifferent; only a reader that fast-paths STORED loses the shortcut.
            out.setLevel(Deflater.NO_COMPRESSION)
            // 8 KB buffer — 1 KB is needlessly slow when zipping multi-MB APK splits.
            val data = ByteArray(COPY_BUFFER_BYTES)
            sources.forEach { source ->
                FileInputStream(source.file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(source.entryName)
                        out.putNextEntry(entry)
                        while (true) {
                            // Same reason as copyCancellable: zipping a split app is the other
                            // multi-gigabyte loop a cancel would otherwise have to sit through.
                            currentCoroutineContext().ensureActive()
                            val readBytes = origin.read(data)
                            if (readBytes == -1) break
                            out.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val FALLBACK_ICON_PX = 192
        const val COPY_BUFFER_BYTES = 8192
        val OBB_COPY = PrivilegeCommandClass("obb.copy")
    }
}

/**
 * Each source APK path paired with the leaf name it is staged — and zipped — under.
 *
 * Staging keyed on the source's own leaf name is right in the ordinary case: base and splits sit in
 * one directory with names the installer chose, so they are already distinct and worth preserving.
 * They are not *guaranteed* distinct, though. `publicSourceDir` repeated in `splitPublicSourceDirs`
 * is the realistic way it happens, and two directories each holding a `base.apk` is the other.
 * Either way the second copy overwrites the first, the same [File] lands in the list twice, and
 * `zipFiles` then throws `ZipException: duplicate entry` — one repeated path failing the whole
 * export of an app whose APKs were all readable.
 *
 * So an exactly-repeated path is dropped (it is the same bytes; staging it twice would double the
 * zip for nothing), and a genuine collision between two different paths is renamed rather than
 * dropped, because those *are* two different APKs and a bundle missing one will not install.
 * `_2` before the extension is enough — nothing reads a split's file name back. The installer takes
 * the split's identity from its manifest, and Thor's own sidecars are generated from these names.
 *
 * Comparison is exact, not case-insensitive: this names files in the app's own cache dir on ext4
 * and entries in a zip, both of which tell `Base.apk` and `base.apk` apart.
 */
internal fun stagedApkNames(paths: List<String>): List<Pair<String, String>> {
    val taken = HashSet<String>(paths.size)
    return paths.distinct().map { path ->
        val leaf = path.substringAfterLast('/')
        val dot = leaf.lastIndexOf('.')
        // dot > 0, not >= 0: a leading dot is a hidden file's whole name, not an extension.
        val stem = if (dot > 0) leaf.substring(0, dot) else leaf
        val extension = if (dot > 0) leaf.substring(dot) else ""
        var candidate = leaf
        var suffix = 2
        while (!taken.add(candidate)) candidate = "${stem}_${suffix++}$extension"
        path to candidate
    }
}

/**
 * A file plus the name it takes inside the zip.
 *
 * Until OBB support, every entry name was `file.name` and the archive was flat. An expansion has
 * to land at `Android/obb/<pkg>/<leaf>`, so the entry name stops being derivable from the file and
 * becomes a decision the caller makes.
 */
internal data class ZipSource(val file: File, val entryName: String)

/**
 * The entry order for one bundle.
 *
 * `.xapk` puts the sidecars first — an installer reading the archive as a stream reaches
 * `manifest.json` before anything large — and the expansions last, because they are the biggest
 * entries and nothing needs them early. `.apks` keeps its existing APKs-then-sidecars order, and
 * drops expansions entirely: that format has no expansion convention, and writing entries no
 * reader looks for would only inflate the file.
 *
 * The `.apks` order is historical rather than load-bearing — a reader working from the central
 * directory (SAI, Thor's own `BundleZip`) is indifferent to it either way — which is exactly why
 * it is preserved here instead of unified with the `.xapk` order.
 */
internal fun zipSourcesFor(
    format: BundleFormat,
    apkFiles: List<File>,
    sidecars: List<File>,
    expansions: List<ZipSource>
): List<ZipSource> {
    val apks = apkFiles.map { ZipSource(it, it.name) }
    val extras = sidecars.map { ZipSource(it, it.name) }
    return if (format == BundleFormat.XAPK) extras + apks + expansions else apks + extras
}

/**
 * `cp` one expansion out of `Android/obb/<pkg>/` into a destination the app can read, or null when
 * any of the three interpolated strings is unsafe to put in a shell command.
 *
 * The destination is always inside Thor's own `externalCacheDir`, which is the single location the
 * privileged shell and Thor can both reach: the shell uid cannot enter `/data/data/com.valhalla.thor`
 * (0700), and Thor cannot open another package's `Android/obb`. `chmod 644` follows the copy
 * because a file the shell creates is owned by the shell, and Thor has to be able to delete it
 * afterwards.
 *
 * **The source is refused if it is a symlink.** The probe already rejects one
 * ([SENTINEL_SYMLINK]), but that is a check-then-use across two shell invocations and the directory
 * belongs to the app being exported, so the copy re-tests rather than inheriting the conclusion.
 * `cp` follows links, and following one here would put the target's bytes into the user's archive
 * under a game-data name — read with the shell's privilege, not the app's.
 */
internal fun obbCopyCommand(
    externalStorageDir: String,
    packageName: String,
    leaf: String,
    destPath: String
): String? {
    if (!isUsablePackageName(packageName)) return null
    if (!isSafeObbLeafName(leaf)) return null
    if (externalStorageDir.isBlank() || !externalStorageDir.startsWith('/')) return null
    if (!destPath.startsWith('/')) return null
    // `leaf` is in this sum as well as the other two, even though isSafeObbLeafName already
    // rejects a quote. Defence in depth: this command runs as root, and the cost of the redundant
    // check is nothing next to the cost of the predicate ever being relaxed by someone who did not
    // read why it is strict.
    if ((externalStorageDir + destPath + leaf).any { it == '\'' || it == '\n' }) return null

    val sourceDir = "$externalStorageDir/${expansionDirFor(packageName)}"
    val source = "$sourceDir/$leaf"
    // Both components, because `-L` only ever tests a path's final one: a link at `<pkg>` redirects
    // the read just as effectively as a link at the leaf, and passes a test aimed at the leaf.
    return "[ ! -L '$sourceDir' ] && [ ! -L '$source' ] && " +
        "cp -f '$source' '$destPath' && chmod 644 '$destPath'"
}

/**
 * Which expansion files this bundle should try to pack — the probe verdict turned into a list.
 *
 * Three inputs, one list, and the interesting arm is [ObbProbe.Undetermined] → empty. It reads like
 * the mistake the tri-state exists to prevent, so it is worth being explicit about why it is not:
 *
 *  - [ObbProbe.Present] is the only verdict that names files, so it is the only one that can
 *    produce a non-empty list.
 *  - [ObbProbe.None] means the probe looked and found nothing.
 *  - [ObbProbe.Undetermined] means the probe could not look. Packing nothing is the *only* thing
 *    this function can do with that; what changed is that the export no longer refuses instead.
 *
 * The verdicts stay distinguishable — `Undetermined` is still not `None`, the sheet still says so,
 * and `SystemRepositoryImpl.probeObb` logs the reason — they just no longer have distinct *blocking*
 * consequences. The fatal case moved to [requireStagedExpansions], which fires when the probe did
 * name files and the copy then failed: that is loss we can prove, and it is still an error.
 *
 * [format] is read rather than assumed. `.apks` has no expansion convention, and `zipSourcesFor`
 * already drops expansions for it — this makes the same rule true one step earlier, so an `.apks`
 * export never stages a byte it will not ship.
 */
internal fun expansionsToPack(format: BundleFormat, probe: ObbProbe): List<ObbFile> =
    if (format != BundleFormat.XAPK) {
        emptyList()
    } else {
        (probe as? ObbProbe.Present)?.files.orEmpty()
    }

/**
 * The staged expansions, or a failed export — the one place where not having the game data is still
 * fatal.
 *
 * [requested] is [expansionsToPack]'s output, so a non-empty list means the probe *measured* those
 * files: it read their names and their sizes off the device. `stageExpansions` returning null after
 * that is not "there was nothing there", it is "we could not copy what we saw", and writing the
 * .xapk anyway hands the user an archive that installs a game which then cannot start. That is
 * GH#164, and it is exactly the case an Undetermined probe is *not*.
 *
 * Empty [requested] short-circuits, so the caller does not have to prove it only calls this on the
 * path where there was something to stage.
 */
internal fun requireStagedExpansions(
    requested: List<ObbFile>,
    staged: List<ZipSource>?
): List<ZipSource> {
    if (requested.isEmpty()) return emptyList()
    return staged ?: throw IOException(
        "this app's game data could not be read, so the .xapk would be incomplete"
    )
}

/**
 * Turn staged expansion sources into the manifest's `expansions` block.
 *
 * `file` and `install_path` are written equal — see [XapkExpansion] for why that is the compatible
 * choice rather than a shortcut.
 *
 * Takes no `packageName`: the entry name already carries it, and a parameter the body never reads is
 * a warning, which this build treats as an error.
 */
internal fun expansionDescriptors(
    sources: List<ZipSource>
): List<XapkExpansion> = sources.map { XapkExpansion(file = it.entryName, installPath = it.entryName) }

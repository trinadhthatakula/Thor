package com.valhalla.thor.data.repository

import java.io.File
import java.util.zip.ZipFile

/**
 * Random-access ZIP reading for installer bundles (XAPK/.apkm/.apks) and APKs.
 *
 * APKPure packages the inner APKs of an `.xapk` as **STORED** (uncompressed)
 * entries with the **data-descriptor** flag set and a **zero-size local header**
 * (the real sizes live only in the central directory / trailing descriptor).
 * `java.util.zip.ZipInputStream` reads *local* headers sequentially and cannot
 * determine where such a STORED entry ends, so it derails on the very first
 * entry — which is exactly why `manifest.json` / the base APK could not be found
 * and a nested `AndroidManifest.xml` was mis-read as a top-level entry.
 *
 * `ZipFile` reads the **central directory** (authoritative names + sizes), just
 * like the `unzip` tool, so it reads these bundles correctly. It requires an
 * on-disk file, so callers copy the input to a temp file first.
 */
object BundleZip {

    /** Top-level entry names (files only) of [zip], read from the central directory. */
    fun entryNames(zip: File): List<String> =
        ZipFile(zip).use { zf ->
            zf.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .toList()
        }

    /**
     * Bytes of the first entry whose *base name* equals [baseName] (case-insensitive),
     * or null if absent. Base-name matching mirrors how installers reference splits.
     */
    fun readEntry(zip: File, baseName: String): ByteArray? =
        ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    it.name.substringAfterLast('/').equals(baseName, ignoreCase = true)
            } ?: return null
            zf.getInputStream(entry).use { it.readBytes() }
        }

    /**
     * Extract the first entry whose base name equals [baseName] (case-insensitive)
     * into [dest]. Returns true on success, false if no such entry exists.
     */
    fun extractEntryTo(zip: File, baseName: String, dest: File): Boolean =
        ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    it.name.substringAfterLast('/').equals(baseName, ignoreCase = true)
            } ?: return false
            zf.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }

    /**
     * Extract every entry whose base name is in [wantedBaseNames] (compared
     * case-insensitively) into [outDir], one file per entry named by its base name.
     * The first match wins if two entries share a base name. Returns the extracted
     * files (archive order).
     */
    fun extractEntries(zip: File, wantedBaseNames: Set<String>, outDir: File): List<File> {
        outDir.mkdirs()
        val wanted = wantedBaseNames.mapTo(HashSet()) { it.lowercase() }
        val seen = HashSet<String>()
        val out = mutableListOf<File>()
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                val key = base.lowercase()
                if (key in wanted && seen.add(key)) {
                    val dest = File(outDir, base)
                    zf.getInputStream(entry).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.add(dest)
                }
            }
        }
        return out
    }
}

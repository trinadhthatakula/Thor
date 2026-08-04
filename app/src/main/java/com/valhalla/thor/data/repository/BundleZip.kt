// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Cap on an entry read wholly into memory (sidecar JSON, bundle icon). Those are kilobytes;
 * eight megabytes is already absurd for one. Without it a DEFLATE bomb — a few MB compressed,
 * gigabytes expanded — raises OutOfMemoryError, which is an *Error*: every `catch (Exception)`
 * up the installer's call chain misses it and the process dies while merely *previewing* a file
 * a stranger sent. The installer takes a content:// URI from any app on the device, so the
 * archive is untrusted input.
 */
internal const val MAX_METADATA_ENTRY_BYTES = 8L * 1024 * 1024

/**
 * Budget for everything one extraction writes to disk. APKs are legitimately large, so this is
 * not a per-file "sane size" so much as the point past which no install could succeed anyway —
 * it exists to stop a decompression bomb filling the data partition from cacheDir.
 */
internal const val MAX_EXTRACTED_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

/**
 * The archive was refused, not installed.
 *
 * Its own type rather than a bare IOException because the mode ladders in InstallerRepositoryImpl
 * answer a failed rung by trying the next one. That is right for "Shizuku's binder died" and wrong
 * for "this archive expands to 40 GB": the next rung reads the same bytes, reaches the same verdict,
 * and re-writes the same gigabytes on the way there. A refusal is a statement about the input, so it
 * travels past the ladder to the sheet with a message describing what was refused.
 */
internal class InstallRefusedException(message: String) : IOException(message)

/** Lowercase hex, the shape `sha256sum` prints and the shell integrity guard compares against. */
internal fun ByteArray.toLowercaseHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

/**
 * One file [BundleZip.extractEntries] wrote, paired with the SHA-256 of the bytes it wrote.
 *
 * The digest belongs to the *write*, not to [file]. On the Shizuku/Dhizuku rungs [file] lands in
 * externalCacheDir, which on API 28-29 (minSdk is 28; /Android/data was not sandboxed until 11) any
 * app holding WRITE_EXTERNAL_STORAGE can overwrite the instant the stream closes. Re-opening it to
 * hash it — what this code did before — measured whatever was there by then, so the on-device
 * `sha256sum` guard ended up comparing the attacker's file against the attacker's own hash and
 * passing. Taken in flight, the hash is of bytes no other app could ever have touched.
 */
data class ExtractedApk(val file: File, val sha256: String)

/**
 * True when [name] can be used as a leaf file name under an output directory.
 *
 * Entry base names come from the untrusted archive, and `java.io.File` does not normalise
 * `.` / `..` — it hands them to the syscall, which does. A base name is taken after the last
 * `/` so it cannot itself contain one, but `..` survives that intact, and a backslash is a
 * separator on the JVM's other host platform. Rejecting those shapes outright is cheaper than
 * reasoning about which of them the current filesystem happens to make harmless.
 */
internal fun isSafeEntryFileName(name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\')

/**
 * Read at most [limit] bytes, or null if the stream holds more.
 *
 * The size in the central directory is a *claim* by whoever built the archive; a bomb can
 * simply under-declare it. So a pre-check on [java.util.zip.ZipEntry.size] is an optimisation
 * and this is the actual bound.
 */
private fun InputStream.readAtMost(limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/**
 * Copy at most [limit] bytes into [out] — feeding each one to [digest] when given — and return the
 * number copied, or null once the source exceeds [limit] (the partial output is the caller's to
 * discard).
 *
 * Internal, not private: every path that writes bytes out of an untrusted archive needs this, and
 * the branch that introduced it applied it to two of them. The PackageInstaller session path (the
 * default rung and the last resort of both privileged ladders) and the copy of the caller's URI in
 * AppAnalyzerImpl were writing unbounded.
 *
 * [digest] is here rather than in a wrapper stream so the hash and the write see the same bytes by
 * construction — see [ExtractedApk] for what re-reading the destination instead cost.
 */
internal fun InputStream.copyAtMostTo(
    out: OutputStream,
    limit: Long,
    digest: MessageDigest? = null
): Long? {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
        digest?.update(buffer, 0, read)
    }
    return total
}

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

    /**
     * Result of a single-pass read: every entry name, plus the bytes of the requested
     * base names ([bytes] keyed by lowercased base name; only entries that existed).
     */
    data class BundleContents(
        val entryNames: List<String>,
        val bytes: Map<String, ByteArray>
    )

    /**
     * Open [zip] ONCE and return every entry name plus the bytes of any **root-level** entry named
     * in [wantedBaseNames] (case-insensitive). Avoids re-opening (and re-parsing the central
     * directory of) the archive once per metadata file.
     *
     * An entry that declares — or turns out to hold — more than [maxEntryBytes] is skipped,
     * not read: it is metadata by definition here, and no legitimate one is that large.
     *
     * Root-level, unlike the base-name matching [extractEntries] does: every caller of this wants a
     * bundle sidecar (`manifest.json`, `info.json`) or a bundle icon, and hasXapkManifest /
     * hasApkmInfoJson — the gate that decides the file even IS a bundle — already require those at
     * the archive root. Matching a base name anywhere made the two disagree: a plain APK's
     * `res/mipmap-hdpi/icon.png` became the icon on the confirmation sheet, and a file that
     * classified as monolithic could still have an `assets/manifest.json` read out of it. Split
     * APKs legitimately nest, which is why extraction still matches on the base name.
     *
     * [BundleContents.entryNames] is deliberately UNfiltered — every name, nested and unusable ones
     * included. It is the input to the classifiers (`isMonolithicApk`, `hasTopLevelAndroidManifest`,
     * `isTopLevelApkEntry`), which have to see what the archive actually holds: an entry named
     * `evil\base.apk` is still a top-level `.apk` sibling, and hiding it here would classify a
     * bundle as a monolithic APK. Deciding which names may be *acted on* is a different question
     * and is answered once, in `resolveBundleInstallSet`.
     */
    fun read(
        zip: File,
        wantedBaseNames: Set<String>,
        maxEntryBytes: Long = MAX_METADATA_ENTRY_BYTES
    ): BundleContents {
        val wanted = wantedBaseNames.mapTo(HashSet()) { it.lowercase() }
        val names = ArrayList<String>()
        val bytes = HashMap<String, ByteArray>()
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                names.add(entry.name)
                if (entry.name.contains('/')) continue
                val key = entry.name.lowercase()
                if (key in wanted && key !in bytes) {
                    if (entry.size > maxEntryBytes) continue
                    zf.getInputStream(entry).use { it.readAtMost(maxEntryBytes) }
                        ?.let { bytes[key] = it }
                }
            }
        }
        return BundleContents(names, bytes)
    }

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
     * or null if absent — or larger than [maxEntryBytes], since this reads into memory.
     * Base-name matching mirrors how installers reference splits.
     */
    fun readEntry(
        zip: File,
        baseName: String,
        maxEntryBytes: Long = MAX_METADATA_ENTRY_BYTES
    ): ByteArray? =
        ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    it.name.substringAfterLast('/').equals(baseName, ignoreCase = true)
            } ?: return null
            if (entry.size > maxEntryBytes) return null
            zf.getInputStream(entry).use { it.readAtMost(maxEntryBytes) }
        }

    /**
     * Extract the first entry whose base name equals [baseName] (case-insensitive)
     * into [dest]. Returns true on success, false if no such entry exists or it expands
     * past [maxBytes] (in which case the partial [dest] is deleted).
     *
     * A false here stays a false rather than becoming an [InstallRefusedException] like the one
     * [extractEntries] throws: the only caller is the analyzer's base-candidate loop, which is
     * choosing which entry to *read*, not what to install. "That candidate is a bomb, try the next
     * one" is the correct answer there, and every attempt is bounded by [maxBytes] on its own. No
     * digest either — [dest] is app-private scratch that is parsed and deleted, never handed to
     * `pm`.
     *
     * A [baseName] that is not a plain leaf is refused outright — the second lock behind
     * [resolveBundleInstallSet], which is where the untrusted names are actually filtered. This is
     * the read that decides the identity on the confirmation sheet, and every writer refuses such a
     * name, so an identity must not be readable from one either: that gap is exactly how the sheet
     * ends up describing a file `pm` was never given.
     */
    fun extractEntryTo(
        zip: File,
        baseName: String,
        dest: File,
        maxBytes: Long = MAX_EXTRACTED_TOTAL_BYTES
    ): Boolean {
        if (!isSafeEntryFileName(baseName)) return false
        return ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    it.name.substringAfterLast('/').equals(baseName, ignoreCase = true)
            } ?: return false
            val copied = zf.getInputStream(entry).use { input ->
                dest.outputStream().use { output -> input.copyAtMostTo(output, maxBytes) }
            }
            if (copied == null) {
                dest.delete()
                return false
            }
            true
        }
    }

    /**
     * Extract every entry whose base name is in [wantedBaseNames] (compared
     * case-insensitively) into [outDir], one file per entry named by its base name.
     * The first match wins if two entries share a base name. Returns the extracted
     * files (archive order).
     *
     * [maxTotalBytes] is a budget for the whole extraction, not per entry: a bundle is a set
     * of splits that get installed together, so what matters is what the set expands to.
     *
     * Every way of coming up short throws [InstallRefusedException] and deletes everything this
     * call wrote — including the entries that fitted. There are three, and they are one verdict:
     *
     *  - the set passes [maxTotalBytes];
     *  - a wanted name turns out not to be a plain leaf ([isSafeEntryFileName]);
     *  - a wanted name is not in the archive at all.
     *
     * Returning the short list instead (what the budget case did before, and what the leaf-name
     * case did until the rest of this comment caught up with it) refused only the all-or-nothing
     * case: a bomb placed *after* base.apk left a non-empty list, `stageInstallSet`'s
     * `.ifEmpty { null }` read that as success, and `pm install-multiple` ran on a truncated split
     * set. Half a bundle is not a smaller install, it is a different one.
     *
     * The leaf-name case is unreachable through the installer — [resolveBundleInstallSet] filters
     * those names out of the install set before any caller can want one — and is kept as a
     * fail-closed second lock, so this function's contract does not depend on its caller's.
     */
    internal fun extractEntries(
        zip: File,
        wantedBaseNames: Set<String>,
        outDir: File,
        maxTotalBytes: Long = MAX_EXTRACTED_TOTAL_BYTES
    ): List<ExtractedApk> {
        outDir.mkdirs()
        val wanted = wantedBaseNames.mapTo(HashSet()) { it.lowercase() }
        val seen = HashSet<String>()
        val out = mutableListOf<ExtractedApk>()
        var remaining = maxTotalBytes
        fun refuse(message: String): Nothing {
            out.forEach { it.file.delete() }
            throw InstallRefusedException(message)
        }
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                val key = base.lowercase()
                // Wantedness first, so an unsafe name nobody asked for is skipped in silence
                // (archives carry all sorts of junk) while an unsafe name that IS in the install
                // set is a refusal — that one cannot be dropped, because the identity was drawn
                // from the same list.
                if (key !in wanted) continue
                if (!isSafeEntryFileName(base)) {
                    refuse("\"$base\" is not a usable file name; refusing to install this archive.")
                }
                if (!seen.add(key)) continue
                val dest = File(outDir, base)
                val digest = MessageDigest.getInstance("SHA-256")
                val copied = zf.getInputStream(entry).use { input ->
                    dest.outputStream().use { output ->
                        input.copyAtMostTo(output, remaining, digest)
                    }
                }
                if (copied == null) {
                    dest.delete()
                    refuse(
                        "\"$base\" expands past the ${maxTotalBytes / (1024 * 1024)} MB " +
                            "extraction budget; refusing to install this archive."
                    )
                }
                remaining -= copied
                out.add(ExtractedApk(dest, digest.digest().toLowercaseHex()))
            }
        }
        // Compared against the lowercased set, not [wantedBaseNames]: two wanted names differing
        // only in case are one file here, and counting the caller's set would refuse a set that
        // was in fact extracted whole.
        if (seen.size != wanted.size) {
            refuse(
                "this archive does not contain ${(wanted - seen).sorted().joinToString(", ")}; " +
                    "refusing to install a partial set."
            )
        }
        return out
    }
}

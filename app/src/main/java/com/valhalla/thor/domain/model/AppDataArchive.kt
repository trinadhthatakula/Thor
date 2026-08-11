// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Schema version of `thorbak.json`. Bumped only for a change a v1 reader could misread. */
const val ARCHIVE_SCHEMA_VERSION = 1

const val THORBAK_EXTENSION = "thorbak"
const val THORBAK_MIME = "application/octet-stream"

/** The header entry, written **last** — chunk counts are unknown until members exist. */
const val THORBAK_HEADER_ENTRY = "thorbak.json"

/** The bundle entry: always an `.xapk`, even for a single-APK app, so restore has one install path. */
const val THORBAK_BUNDLE_ENTRY = "app.xapk"

/** `<pkg>-<versionCode>.thorbak`. */
fun thorbakFileName(packageName: String, versionCode: Long): String =
    "$packageName-$versionCode.$THORBAK_EXTENSION"

/**
 * The four storage classes an app owns.
 *
 * `Android/obb/<pkg>` is deliberately **not** a fifth: it rides inside [THORBAK_BUNDLE_ENTRY], so
 * there is no second OBB path to write, test and keep in sync with the one PR #376 verified on
 * hardware.
 */
enum class DataClass(val id: String) {
    /** `/data/user/<userId>/<pkg>` — credential-encrypted; the bulk of what users care about. */
    CE("ce"),

    /**
     * `/data/user_de/<userId>/<pkg>` — device-encrypted. Not exotic: PMS creates a `user_de`
     * package directory for *every* app, and that entry spent its whole life missing from
     * `PerUserCommands`' cache list.
     */
    DE("de"),

    /** `<externalStorageDir>/Android/data/<pkg>`. */
    EXTERNAL_DATA("ext-data"),

    /** `<externalStorageDir>/Android/media/<pkg>` — user-visible content. */
    EXTERNAL_MEDIA("ext-media");

    /**
     * The member's entry name inside the container.
     *
     * Derived from [compressed] rather than fixed, so a class whose `tar -czf` failed and fell back
     * to `tar -cf` is not stored under a name claiming gzip. Readers resolve members through
     * [ArchiveHeader.member], which carries the name that was actually written.
     */
    fun memberName(compressed: Boolean): String =
        if (compressed) "$id.tar.gz.enc" else "$id.tar.enc"

    /**
     * True when `cache`, `code_cache` and `no_backup` are dropped from this class.
     *
     * [EXTERNAL_MEDIA] excludes nothing: it is user-visible content, and a directory a user can see
     * in a file manager is not Thor's to decide against.
     */
    val excludesVolatileDirs: Boolean get() = this != EXTERNAL_MEDIA

    /**
     * True for the two classes under `/data`, where numeric ownership and SELinux labels are real.
     *
     * The external pair live on FUSE, which synthesizes ownership from the caller — `chown` there
     * changes nothing and `restorecon` has no label to apply. Restore uses this to decide whether
     * §8.3's steps d and e run at all.
     */
    val isInternal: Boolean get() = this == CE || this == DE
}

/**
 * How big a class is, as a **tri-state**.
 *
 * [Undetermined] exists because a measurement that failed is not a measurement of zero. Rendering it
 * as `0 B` is how a user deselects data they actually have — the same trap [ObbProbe.Undetermined]
 * exists to close.
 */
sealed interface DataClassSize {
    data class Known(val bytes: Long) : DataClassSize
    data object Empty : DataClassSize
    data object Undetermined : DataClassSize
}

/** What the UI is allowed to render for a [DataClassSize]. Never a number for `Undetermined`. */
sealed interface SizeLabelKind {
    data class Bytes(val value: Long) : SizeLabelKind
    data object Empty : SizeLabelKind
    data object Unknown : SizeLabelKind
}

fun DataClassSize.labelKind(): SizeLabelKind = when (this) {
    is DataClassSize.Known -> SizeLabelKind.Bytes(bytes)
    DataClassSize.Empty -> SizeLabelKind.Empty
    DataClassSize.Undetermined -> SizeLabelKind.Unknown
}

/** Which `tar` produced a member. Recorded because the gzip attempt is allowed to fail. */
enum class ArchiveCompression(val id: String) {
    GZIP("gzip"),
    NONE("none");

    companion object {
        fun fromId(id: String): ArchiveCompression = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * `thorbak.json`.
 *
 * Conventions are [BackupIndex]'s, and for its reasons: the reader is deliberately assumed **not**
 * to be Thor. `encodeDefaults` so [schemaVersion] is on the wire at its default,
 * `ignoreUnknownKeys` so a v1 reader survives a v2 document, `prettyPrint` because a person may
 * open it, and flat entries carrying a `dataClass` string rather than a sealed hierarchy so a
 * foreign reader need not learn Kotlin's discriminator convention.
 */
@Serializable
data class ArchiveHeader(
    val schemaVersion: Int = ARCHIVE_SCHEMA_VERSION,
    /** Epoch millis at which the run finished writing its members. */
    val createdAt: Long,
    /** The Thor build that produced this archive, for diagnosing one a later Thor rejects. */
    val thorVersionCode: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String? = null,
    /** The Android multi-user id the data was read from. Not a Linux uid — see the plan's glossary. */
    val userId: Int,
    /**
     * SHA-256 of the app's first signing certificate, uppercase hex.
     *
     * **Load-bearing.** Without it, restoring into a same-named but differently-signed package is a
     * data-exfiltration primitive: sideload a fake `com.whatsapp`, restore, read everything.
     */
    val signerSha256: String,
    val appBundle: ArchiveBundleInfo? = null,
    val kdf: ArchiveKdf,
    /** `HMAC-SHA256(key, "thor-data-archive-v1")` truncated to 16 bytes, Base64. */
    val verifier: String,
    val members: List<ArchiveMember> = emptyList(),
    val skippedEntries: List<ArchiveSkip> = emptyList(),
    /** Non-fatal notes — a `tar` exit of 1, an `externalCacheDir` fallback. */
    val warnings: List<String> = emptyList(),
) {
    fun encode(): String = json.encodeToString(this)

    fun member(dataClass: DataClass): ArchiveMember? =
        members.firstOrNull { it.dataClass == dataClass.id }

    /** The classes this archive actually holds, in [DataClass] order. */
    fun heldClasses(): List<DataClass> = DataClass.entries.filter { member(it) != null }

    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun decode(text: String): ArchiveHeader = json.decodeFromString<ArchiveHeader>(text)
    }
}

/**
 * The `.xapk` inside the container.
 *
 * [obbCapture] is [ObbProbe]'s tri-state name, not a boolean: OBB that could not be read is
 * recorded as `Undetermined`, never as "none", so restore never implies it holds game data it does
 * not. Same rule as #376.
 */
@Serializable
data class ArchiveBundleInfo(
    val fileName: String = THORBAK_BUNDLE_ENTRY,
    val bytes: Long,
    val obbCapture: String,
    val obbCount: Int,
)

@Serializable
data class ArchiveKdf(
    val algorithm: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    /** Base64, 16 bytes, generated fresh per archive so one reused passphrase is not one key. */
    val salt: String,
)

@Serializable
data class ArchiveMember(
    /** [DataClass.id]. A string, not an enum, so a v2 class name does not break a v1 reader. */
    val dataClass: String,
    val fileName: String,
    /** Base64, 8 bytes. The IV is this nonce followed by a 4-byte big-endian chunk index. */
    val nonce: String,
    val plainBytes: Long,
    /** How many chunks the reader must see. A stream that ends early is refused. */
    val chunkCount: Int,
    val compression: String,
)

/** An entry Thor refused to pack, and why. Recorded rather than silently dropped. */
@Serializable
data class ArchiveSkip(
    val dataClass: String,
    val name: String,
    val reason: String,
)

/**
 * [ObbProbe]'s answer as the string [ArchiveBundleInfo.obbCapture] records — three names for three
 * answers.
 *
 * Lowercase ids, matching how [DataClass.id] and [ArchiveCompression.id] are spelled in the same
 * format. **Never fold `Undetermined` onto `"none"`:** an archive that records "no OBB" when Thor
 * merely could not look is one a restore will happily call complete.
 */
fun ObbProbe.captureName(): String = when (this) {
    is ObbProbe.None -> "none"
    is ObbProbe.Present -> "present"
    is ObbProbe.Undetermined -> "undetermined"
}

/**
 * The one name for Thor's private archive staging directory under `cacheDir`.
 *
 * `AppDataArchiveGatewayImpl` creates files in it; `ArchiveOrphanSweeper` empties it at launch. Those
 * two live in different layers and neither may hold its own copy of the name — a sweep pointed at the
 * wrong directory either deletes nothing or deletes something else.
 */
object AppDataArchiveStagingDir {
    /**
     * Deliberately the value `AppDataArchiveGatewayImpl` already shipped with, not a fresh one: this
     * object exists to give the two layers **one** name, and renaming the directory as a side effect
     * of centralising it would strand whatever a killed job left under the old one.
     */
    const val NAME = "data_archive_staging"
}

/**
 * The one name for the `cacheDir` subtree `ArchiveBackupWorker` builds its `.xapk` into.
 *
 * Shared for the same reason as [AppDataArchiveStagingDir]: the worker passes it to
 * `AppBundleBuilder.build(cacheSubDir = …)` and `ArchiveOrphanSweeper` removes it at launch. The
 * worker deletes only the file the builder handed back, so the directory — and anything a kill left
 * half-written inside it — is the sweep's to clear.
 */
object ArchiveBundleCacheDir {
    const val NAME = "archive_bundle"
}

/**
 * The one name for the **external** cache subtree an export stages expansion (`.obb`) files into.
 *
 * Not under `cacheDir`, and that is the whole reason this constant exists. `AppBundleBuilderImpl`
 * stages expansions in `externalCacheDir/<NAME>/<pkg>` because the privileged shell that copies out of
 * `Android/obb/<pkg>/` cannot write into `/data/data/<thor>` (0700) — so nothing that empties
 * `cacheDir`, including [ArchiveBundleCacheDir]'s sweep, reaches them. For a large game they are the
 * *bigger* half of a bundle, so a process killed mid-build strands gigabytes there.
 *
 * The builder deletes its own subtree on success and on both failure paths; `ArchiveOrphanSweeper`
 * removes the whole directory at launch, which is the only thing that covers the kill. Two spellings
 * of this name would put the sweep on a directory nothing writes to — the drift `STAGING_DIR` had
 * before it was consolidated.
 */
object ObbExportStagingDir {
    const val NAME = "obb_out"
}

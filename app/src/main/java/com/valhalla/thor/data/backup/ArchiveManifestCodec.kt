// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import com.valhalla.thor.domain.model.ARCHIVE_SCHEMA_VERSION
import com.valhalla.thor.domain.model.ArchiveCompression
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveSkip
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.KDF_SALT_BYTES
import com.valhalla.thor.domain.model.THORBAK_BUNDLE_ENTRY
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

const val MANIFEST_AUTH_ALGORITHM = "HmacSHA256"
const val MANIFEST_MAC_BYTES = 32

private const val MANIFEST_DOMAIN = "thor-data-archive-manifest-v2"
private const val MAX_MANIFEST_STRING_BYTES = 16 * 1024
private const val MAX_MANIFEST_MEMBERS = 64
private const val MAX_MANIFEST_SKIPS = 4_096
private const val MAX_MANIFEST_WARNINGS = 1_024
private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024

/**
 * Stable binary representation authenticated by schema-v2's manifest MAC.
 *
 * JSON is deliberately not the authenticated representation: whitespace and property order are
 * transport details. Lists whose order has no restore meaning are sorted, while every string and
 * byte array is length-prefixed and every nullable value carries an explicit marker.
 */
object ArchiveManifestCodec {

    fun canonicalBytes(header: ArchiveHeader): ByteArray {
        validate(header)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeString(MANIFEST_DOMAIN)
            out.writeInt(header.schemaVersion)
            out.writeLong(header.createdAt)
            out.writeInt(header.thorVersionCode)
            out.writeString(header.packageName)
            out.writeLong(header.versionCode)
            out.writeNullableString(header.versionName)
            out.writeInt(header.userId)
            out.writeBytesWithLength(header.signerSha256.hexBytes())
            out.writeBoolean(header.appBundle != null)
            header.appBundle?.let { bundle ->
                out.writeString(bundle.fileName)
                out.writeLong(bundle.bytes)
                out.writeBytesWithLength(bundle.sha256!!.hexBytes())
                out.writeString(bundle.obbCapture)
                out.writeInt(bundle.obbCount)
            }
            out.writeString(header.kdf.algorithm)
            out.writeInt(header.kdf.iterations)
            out.writeBytesWithLength(header.kdf.salt.strictBase64Bytes("KDF salt"))
            out.writeBytesWithLength(header.verifier.strictBase64Bytes("passphrase verifier"))
            out.writeString(header.authentication!!.algorithm)

            val members = header.members.sortedWith(compareBy({ it.dataClass }, { it.fileName }))
            out.writeInt(members.size)
            members.forEach { member ->
                out.writeString(member.dataClass)
                out.writeString(member.fileName)
                out.writeBytesWithLength(member.nonce.strictBase64Bytes("member nonce"))
                out.writeLong(member.plainBytes)
                out.writeLong(member.cipherBytes!!)
                out.writeInt(member.chunkCount)
                out.writeString(member.compression)
            }

            val skipped = header.skippedEntries.sortedWith(
                compareBy(ArchiveSkip::dataClass, ArchiveSkip::name, ArchiveSkip::reason)
            )
            out.writeInt(skipped.size)
            skipped.forEach { skip ->
                out.writeString(skip.dataClass)
                out.writeString(skip.name)
                out.writeString(skip.reason)
            }

            val warnings = header.warnings.sorted()
            out.writeInt(warnings.size)
            warnings.forEach { warning -> out.writeString(warning) }
        }
        return output.toByteArray().also { bytes ->
            if (bytes.size > MAX_MANIFEST_BYTES) invalid("canonical manifest is too large")
        }
    }

    /** Validate all bounds and invariants before [canonicalBytes] starts allocating its buffer. */
    fun validate(header: ArchiveHeader) {
        if (header.schemaVersion != ARCHIVE_SCHEMA_VERSION) invalid("unsupported archive schema")
        requireString(header.packageName, "package name")
        if (header.createdAt < 0L || header.thorVersionCode < 0 || header.versionCode < 0L || header.userId < 0) {
            invalid("archive identity has an invalid number")
        }
        header.versionName?.let { requireString(it, "version name", allowEmpty = true) }
        requireSha256(header.signerSha256, "signer digest")

        header.appBundle?.let { bundle ->
            if (bundle.fileName != THORBAK_BUNDLE_ENTRY) invalid("bundle entry name is invalid")
            if (bundle.bytes < 0L || bundle.obbCount < 0) invalid("bundle metadata has an invalid number")
            requireSha256(bundle.sha256, "bundle digest")
            if (bundle.obbCapture !in setOf("none", "present", "undetermined")) {
                invalid("bundle OBB state is invalid")
            }
        }

        if (header.kdf.algorithm != KDF_ALGORITHM || header.kdf.iterations <= 0) {
            invalid("KDF metadata is invalid")
        }
        requireDecodedSize(header.kdf.salt, KDF_SALT_BYTES, "KDF salt")
        requireDecodedSize(header.verifier, VERIFIER_BYTES, "passphrase verifier")

        val authentication = header.authentication ?: invalid("archive authentication is missing")
        if (authentication.algorithm != MANIFEST_AUTH_ALGORITHM) {
            invalid("archive authentication algorithm is unsupported")
        }
        requireDecodedSize(authentication.mac, MANIFEST_MAC_BYTES, "manifest MAC")

        if (header.members.size > MAX_MANIFEST_MEMBERS) invalid("archive has too many members")
        val dataClasses = HashSet<String>(header.members.size)
        val fileNames = HashSet<String>(header.members.size)
        header.members.forEach { member ->
            if (member.dataClass !in DataClass.entries.map(DataClass::id)) {
                invalid("member data class is unsupported")
            }
            if (!dataClasses.add(member.dataClass)) invalid("archive repeats a data class")
            requireEntryName(member.fileName, "member filename")
            if (!fileNames.add(member.fileName)) invalid("archive repeats a member filename")
            requireDecodedSize(member.nonce, MEMBER_NONCE_BYTES, "member nonce")
            if (member.plainBytes < 0L || member.cipherBytes == null || member.cipherBytes < 0L) {
                invalid("member size is invalid")
            }
            if (member.chunkCount <= 0) invalid("member chunk count is invalid")
            if (ArchiveCompression.entries.none { it.id == member.compression }) {
                invalid("member compression is unsupported")
            }
        }

        if (header.skippedEntries.size > MAX_MANIFEST_SKIPS) invalid("archive has too many skipped entries")
        header.skippedEntries.forEach { skip ->
            requireString(skip.dataClass, "skipped data class")
            requireString(skip.name, "skipped entry name", allowEmpty = true)
            requireString(skip.reason, "skipped entry reason", allowEmpty = true)
        }
        if (header.warnings.size > MAX_MANIFEST_WARNINGS) invalid("archive has too many warnings")
        header.warnings.forEach { requireString(it, "warning", allowEmpty = true) }
    }

    private fun requireEntryName(value: String, label: String) {
        requireString(value, label)
        if (value == "." || value == ".." || '/' in value || '\\' in value) invalid("$label is invalid")
    }

    private fun requireString(value: String, label: String, allowEmpty: Boolean = false) {
        if (!allowEmpty && value.isEmpty()) invalid("$label is empty")
        if (value.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_STRING_BYTES) invalid("$label is too long")
    }

    private fun requireSha256(value: String?, label: String) {
        if (value == null || value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            invalid("$label is invalid")
        }
    }

    private fun requireDecodedSize(value: String?, size: Int, label: String) {
        if (value == null || runCatching { value.strictBase64Bytes(label).size }.getOrNull() != size) {
            invalid("$label is invalid")
        }
    }

    private fun String.strictBase64Bytes(label: String): ByteArray {
        requireString(this, label)
        val decoded = runCatching { Base64.getDecoder().decode(this) }
            .getOrElse { invalid("$label is invalid") }
        if (Base64.getEncoder().encodeToString(decoded) != this) invalid("$label is not canonical")
        return decoded
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun DataOutputStream.writeString(value: String) =
        writeBytesWithLength(value.toByteArray(Charsets.UTF_8))

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let { writeString(it) }
    }

    private fun DataOutputStream.writeBytesWithLength(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun invalid(message: String): Nothing = throw ArchiveIntegrityException(message)
}

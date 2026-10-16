// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.kernel

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class AnyKernel3Inspection(
    val sha256: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val entryCount: Int,
    val supportsSlotSelection: Boolean = false,
)

/**
 * Recognizes an AnyKernel3 archive without executing any of its contents.
 *
 * Shape, checksums and safe ZIP paths do not establish device compatibility or trust in the
 * installer: an accepted package still contains arbitrary root code. The caller must stage the
 * archive in private storage and prevent changes between inspection, confirmation and execution.
 */
object AnyKernel3Archive {
    const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 8192
    private const val MAX_INSTALLER_BYTES = 2L * 1024 * 1024
    private const val INSTALLER = "META-INF/com/google/android/update-binary"
    private val requiredFiles = setOf("anykernel.sh", INSTALLER, "tools/ak3-core.sh")

    /** Fully reads every entry, checking the actual expanded length and CRC against its headers. */
    @Throws(IOException::class)
    fun inspect(file: File): AnyKernel3Inspection {
        val entries = readDirectory(file)
        val archiveBytes = file.length()
        var expandedBytes = 0L
        val supportsSlots = ZipFile(file).use { zip ->
            valid(zip.size() == entries.size, "ZIP entry count changed")
            for (entry in entries) {
                expandedBytes += readEntry(zip, entry)
                valid(expandedBytes <= MAX_EXPANDED_BYTES, "Expanded archive exceeds 2 GiB")
            }
            fun script(name: String): String {
                val entry = zip.getEntry(name)
                if (entry.size !in 1..512L * 1024) return ""
                return zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            supportsKernelSlotSelection(script("anykernel.sh"), script("tools/ak3-core.sh"))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var hashedBytes = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hashedBytes += count
                valid(hashedBytes <= MAX_ARCHIVE_BYTES, "Archive exceeds 512 MiB")
                digest.update(buffer, 0, count)
            }
        }
        valid(hashedBytes == archiveBytes && file.length() == archiveBytes, "Archive changed during inspection")
        return AnyKernel3Inspection(
            sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            compressedBytes = archiveBytes,
            expandedBytes = expandedBytes,
            entryCount = entries.size,
            supportsSlotSelection = supportsSlots,
        )
    }

    /**
     * Extracts only the checked installer to a fresh, caller-owned destination. Call [inspect]
     * before using the archive: this method checks the ZIP structure and installer, not all bodies.
     */
    @Throws(IOException::class)
    fun extractInstaller(file: File, destination: File) {
        val installer = readDirectory(file).single { it.name == INSTALLER }
        valid(destination.createNewFile(), "Installer destination already exists")
        try {
            ZipFile(file).use { zip ->
                destination.outputStream().buffered().use { output ->
                    readEntry(zip, installer, output)
                }
            }
        } catch (failure: Exception) {
            destination.delete()
            throw failure
        }
    }

    private data class Entry(
        val name: String,
        val rawName: ByteArray,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedBytes: Long,
        val expandedBytes: Long,
        val localOffset: Long,
        val directory: Boolean,
    )

    /**
     * Native unzip may use local headers while java.util.zip uses the central directory. Require
     * both to describe exactly the same contiguous entries; otherwise a hidden local entry or a
     * different local path could escape checks performed through ZipFile alone.
     */
    private fun readDirectory(file: File): List<Entry> {
        valid(file.isFile && file.length() in 22..MAX_ARCHIVE_BYTES, "ZIP is empty or exceeds 512 MiB")
        return RandomAccessFile(file, "r").use { input ->
            val tailSize = minOf(input.length(), 65557L).toInt()
            val tailOffset = input.length() - tailSize
            input.seek(tailOffset)
            val tail = input.bytes(tailSize)
            val endIndex = (tail.size - 22 downTo 0).firstOrNull { offset ->
                tail.u32(offset) == 0x06054b50L && offset + 22 + tail.u16(offset + 20) == tail.size
            } ?: throw IOException("ZIP end record is missing or trailing data is present")
            valid(tail.u16(endIndex + 4) == 0 && tail.u16(endIndex + 6) == 0, "Multipart ZIPs are unsupported")
            val count = tail.u16(endIndex + 10)
            valid(count in 1..MAX_ENTRIES && tail.u16(endIndex + 8) == count, "ZIP entry count exceeds 8192 or is invalid")
            val directorySize = tail.u32(endIndex + 12)
            val directoryOffset = tail.u32(endIndex + 16)
            val directoryEnd = directoryOffset + directorySize
            valid(directoryOffset > 0 && directoryEnd == tailOffset + endIndex, "ZIP64, prefixed or malformed ZIP is unsupported")
            input.seek(directoryOffset)
            val entries = ArrayList<Entry>(count)
            val files = HashSet<String>()
            val directories = HashSet<String>()
            val explicitNames = HashSet<String>()
            var expandedBytes = 0L
            repeat(count) {
                valid(input.filePointer + 46 <= directoryEnd, "Truncated ZIP directory")
                val header = input.bytes(46)
                valid(header.u32(0) == 0x02014b50L, "Invalid ZIP directory record")
                valid(header.u16(6) <= 20, "Unsupported ZIP version")
                val flags = header.u16(8)
                val method = header.u16(10)
                valid(method == 0 || method == 8, "Only stored or deflated ZIP entries are supported")
                valid(flags and 0xf7f1 == 0, "Encrypted or unsupported ZIP entry")
                valid(method == 8 || flags and 6 == 0, "Invalid ZIP compression flags")
                val nameSize = header.u16(28)
                val extraSize = header.u16(30)
                val commentSize = header.u16(32)
                valid(nameSize in 1..4096, "ZIP entry path is empty or too long")
                valid(input.filePointer + nameSize + extraSize + commentSize <= directoryEnd, "Truncated ZIP entry")
                valid(header.u16(34) == 0, "Multipart ZIPs are unsupported")
                val rawName = input.bytes(nameSize)
                val name = decodeName(rawName, flags)
                val isDirectory = name.endsWith('/')
                val path = validatePath(name)
                valid(explicitNames.add(path), "Duplicate ZIP entry: $path")
                val attributes = header.u32(38)
                val unixType = ((attributes ushr 16).toInt() and 0xf000)
                valid(unixType == 0 || unixType == 0x8000 || unixType == 0x4000, "ZIP links and special files are unsupported: $path")
                valid(unixType != 0x4000 || isDirectory, "Directory metadata disagrees with ZIP path: $path")
                valid(unixType != 0x8000 || !isDirectory, "File metadata disagrees with ZIP path: $path")
                valid(attributes and 0x10 == 0L || isDirectory, "Directory metadata disagrees with ZIP path: $path")
                registerPath(path, isDirectory, files, directories)
                val entry = Entry(
                    name = name,
                    rawName = rawName,
                    flags = flags,
                    method = method,
                    crc = header.u32(16),
                    compressedBytes = header.u32(20),
                    expandedBytes = header.u32(24),
                    localOffset = header.u32(42),
                    directory = isDirectory,
                )
                valid(entry.compressedBytes <= MAX_ARCHIVE_BYTES, "ZIP entry exceeds compressed size limit")
                expandedBytes += entry.expandedBytes
                valid(expandedBytes <= MAX_EXPANDED_BYTES, "Expanded archive exceeds 2 GiB")
                valid(!isDirectory || entry.expandedBytes == 0L, "ZIP directory contains data: $path")
                valid(name != INSTALLER || entry.expandedBytes <= MAX_INSTALLER_BYTES, "Installer exceeds 2 MiB")
                validateExtra(input.bytes(extraSize))
                input.seek(input.filePointer + commentSize)
                entries.add(entry)
            }
            valid(input.filePointer == directoryEnd, "Unexpected ZIP directory data")
            valid("module.prop" !in files && "module.prop" !in directories, "Root-manager module ZIPs are unsupported")
            for (required in requiredFiles) {
                valid(entries.any { it.name == required && !it.directory && it.expandedBytes > 0 }, "AnyKernel3 file is missing or empty: $required")
            }
            var nextOffset = 0L
            for (entry in entries.sortedBy { it.localOffset }) {
                valid(entry.localOffset == nextOffset, "ZIP entries overlap, contain hidden data or have a prefix")
                nextOffset = validateLocalEntry(input, entry, directoryOffset)
            }
            valid(nextOffset == directoryOffset, "Unexpected data before ZIP directory")
            entries
        }
    }

    private fun validateLocalEntry(input: RandomAccessFile, entry: Entry, directoryOffset: Long): Long {
        valid(entry.localOffset + 30 <= directoryOffset, "Truncated local ZIP header")
        input.seek(entry.localOffset)
        val header = input.bytes(30)
        valid(header.u32(0) == 0x04034b50L && header.u16(4) <= 20, "Invalid local ZIP header")
        valid(header.u16(6) == entry.flags && header.u16(8) == entry.method, "ZIP local and central flags disagree")
        val nameSize = header.u16(26)
        val extraSize = header.u16(28)
        val dataOffset = input.filePointer + nameSize + extraSize
        val dataEnd = dataOffset + entry.compressedBytes
        valid(nameSize == entry.rawName.size && dataEnd <= directoryOffset, "Invalid local ZIP lengths")
        valid(input.bytes(nameSize).contentEquals(entry.rawName), "ZIP local and central paths disagree")
        validateExtra(input.bytes(extraSize))
        val hasDescriptor = entry.flags and 8 != 0
        val crc = header.u32(14)
        val compressed = header.u32(18)
        val expanded = header.u32(22)
        valid(crc == entry.crc || hasDescriptor && crc == 0L, "ZIP local and central CRC disagree")
        valid(compressed == entry.compressedBytes || hasDescriptor && compressed == 0L, "ZIP local and central sizes disagree")
        valid(expanded == entry.expandedBytes || hasDescriptor && expanded == 0L, "ZIP local and central sizes disagree")
        if (!hasDescriptor) return dataEnd
        valid(dataEnd + 12 <= directoryOffset, "Truncated ZIP data descriptor")
        input.seek(dataEnd)
        val first = input.bytes(4).u32(0)
        val descriptorCrc = if (first == 0x08074b50L) {
            valid(dataEnd + 16 <= directoryOffset, "Truncated ZIP data descriptor")
            input.bytes(4).u32(0)
        } else {
            first
        }
        valid(descriptorCrc == entry.crc, "ZIP descriptor CRC disagrees")
        valid(input.bytes(4).u32(0) == entry.compressedBytes, "ZIP descriptor compressed size disagrees")
        valid(input.bytes(4).u32(0) == entry.expandedBytes, "ZIP descriptor expanded size disagrees")
        return input.filePointer
    }

    private fun readEntry(zip: ZipFile, expected: Entry, output: OutputStream? = null): Long {
        val entry = zip.getEntry(expected.name) ?: throw IOException("ZIP entry disappeared: ${expected.name}")
        valid(entry.size == expected.expandedBytes && entry.compressedSize == expected.compressedBytes && entry.crc == expected.crc, "ZIP entry changed: ${expected.name}")
        var bytes = 0L
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                valid(bytes <= expected.expandedBytes, "ZIP entry expands beyond declared size: ${expected.name}")
                crc.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
        }
        valid(bytes == expected.expandedBytes && crc.value == expected.crc, "ZIP entry checksum or length is invalid: ${expected.name}")
        return bytes
    }

    private fun validatePath(name: String): String {
        valid(!name.startsWith('/') && '\\' !in name && name.none { Character.isISOControl(it) }, "Unsafe ZIP path")
        valid(!(name.length >= 2 && name[0].isLetter() && name[1] == ':'), "Absolute ZIP path is unsupported")
        val path = name.removeSuffix("/")
        valid(path.split('/').none { it.isEmpty() || it == "." || it == ".." }, "Unsafe ZIP path: $name")
        return path
    }

    private fun registerPath(path: String, directory: Boolean, files: MutableSet<String>, directories: MutableSet<String>) {
        valid(path !in files && (directory || path !in directories), "ZIP file/directory conflict: $path")
        if (directory) directories.add(path) else files.add(path)
        var slash = path.indexOf('/')
        while (slash >= 0) {
            val parent = path.substring(0, slash)
            valid(parent !in files, "ZIP file/directory conflict: $parent")
            directories.add(parent)
            slash = path.indexOf('/', slash + 1)
        }
    }

    private fun decodeName(rawName: ByteArray, flags: Int): String {
        valid(flags and 0x800 != 0 || rawName.all { it >= 0 }, "Non-UTF-8 ZIP paths are unsupported")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawName)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IOException("Invalid UTF-8 ZIP path", failure)
        }
    }

    private fun validateExtra(extra: ByteArray) {
        var offset = 0
        while (offset < extra.size) {
            valid(offset + 4 <= extra.size, "Malformed ZIP extra field")
            val type = extra.u16(offset)
            val size = extra.u16(offset + 2)
            valid(offset + 4 + size <= extra.size, "Truncated ZIP extra field")
            // These may override sizes, encryption, paths or Unix link metadata in native unzip.
            valid(type !in setOf(0x0001, 0x000d, 0x0017, 0x7075, 0x756e, 0x9901), "Unsupported ZIP extra field")
            offset += 4 + size
        }
    }

    private fun RandomAccessFile.bytes(count: Int): ByteArray = ByteArray(count).also(::readFully)
    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    private fun ByteArray.u32(offset: Int): Long =
        u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}

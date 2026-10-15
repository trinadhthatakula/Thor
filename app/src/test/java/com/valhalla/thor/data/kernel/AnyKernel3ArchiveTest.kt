// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.kernel

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnyKernel3ArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val installerPath = "META-INF/com/google/android/update-binary"
    private val installer = "#!/sbin/sh\necho 'ui_print example'\n".toByteArray()
    private val required = linkedMapOf(
        "anykernel.sh" to "#!/bin/sh\n. tools/ak3-core.sh\n".toByteArray(),
        installerPath to installer,
        "tools/ak3-core.sh" to "# AnyKernel3 core\n".toByteArray(),
    )

    @Test
    fun `valid deflated archive with data descriptors returns actual sizes and digest`() {
        val contents = linkedMapOf("tools/" to byteArrayOf()).apply { putAll(required) }
        val file = archive(contents)
        val result = AnyKernel3Archive.inspect(file)
        assertEquals(file.length(), result.compressedBytes)
        assertEquals(contents.values.sumOf { it.size.toLong() }, result.expandedBytes)
        assertEquals(contents.size, result.entryCount)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) },
            result.sha256,
        )
    }

    @Test
    fun `inspection reports standard slot support without executing scripts`() {
        val contents = LinkedHashMap(required).apply {
            put("anykernel.sh", "IS_SLOT_DEVICE=1; BLOCK=auto;".toByteArray())
            put("tools/ak3-core.sh", "${'$'}IS_SLOT_DEVICE ${'$'}SLOT_SELECT inactive)".toByteArray())
        }
        assertTrue(AnyKernel3Archive.inspect(archive(contents)).supportsSlotSelection)
        assertFalse(AnyKernel3Archive.inspect(archive()).supportsSlotSelection)
    }

    @Test
    fun `valid stored entries need no data descriptor`() {
        assertEquals(3, AnyKernel3Archive.inspect(archive(stored = true)).entryCount)
    }

    @Test
    fun `safe directory records can occur after their children`() {
        val contents = LinkedHashMap(required).apply { put("tools/", byteArrayOf()) }
        assertEquals(4, AnyKernel3Archive.inspect(archive(contents)).entryCount)
    }

    @Test
    fun `extract writes only the updater to the exact requested file`() {
        val directory = temporary.newFolder()
        val destination = File(directory, "update-binary")
        AnyKernel3Archive.extractInstaller(archive(), destination)
        assertArrayEquals(installer, destination.readBytes())
        assertEquals(listOf("update-binary"), directory.list()!!.toList())
    }

    @Test
    fun `extract refuses to overwrite an existing destination`() {
        val destination = temporary.newFile().apply { writeText("preserve this") }
        assertThrows(IOException::class.java) { AnyKernel3Archive.extractInstaller(archive(), destination) }
        assertEquals("preserve this", destination.readText())
    }

    @Test
    fun `missing or empty required files are rejected`() {
        for (path in required.keys) {
            rejected(archive(LinkedHashMap(required).apply { remove(path) }))
            rejected(archive(LinkedHashMap(required).apply { put(path, byteArrayOf()) }))
        }
    }

    @Test
    fun `directory cannot substitute for required installer`() {
        rejected(archive(LinkedHashMap(required).apply {
            remove(installerPath)
            put("$installerPath/", byteArrayOf())
        }))
    }

    @Test
    fun `root manager modules are rejected even when AK3 markers also exist`() {
        rejected(archive(LinkedHashMap(required).apply { put("module.prop", "id=example".toByteArray()) }))
    }

    @Test
    fun `traversal absolute empty component backslash and control paths are rejected`() {
        val names = listOf(
            "../outside", "tools/../../outside", "/data/outside", "./anykernel.sh",
            "tools//outside", "tools/./outside", "tools\\outside", "C:/outside", "tools/line\nfeed",
        )
        for (name in names) {
            rejected(archive(LinkedHashMap(required).apply { put(name, byteArrayOf(1)) }))
        }
    }

    @Test
    fun `duplicate names in central and local records are rejected`() {
        val file = archive(LinkedHashMap(required).apply {
            put("extraA", byteArrayOf(1))
            put("extraB", byteArrayOf(2))
        })
        mutate(file) { bytes ->
            val central = centralOffset(bytes, "extraB")
            val local = bytes.u32(central + 42).toInt()
            bytes[central + 46 + 5] = 'A'.code.toByte()
            bytes[local + 30 + 5] = 'A'.code.toByte()
        }
        rejected(file)
    }

    @Test
    fun `file and directory conflicts are rejected regardless of entry ordering`() {
        rejected(archive(LinkedHashMap(required).apply { put("tools", byteArrayOf(1)) }))
        rejected(archive(linkedMapOf("tools" to byteArrayOf(1)).apply { putAll(required) }))
        rejected(archive(LinkedHashMap(required).apply { put("anykernel.sh/child", byteArrayOf(1)) }))
    }

    @Test
    fun `symlinks and special Unix files are rejected from central attributes`() {
        for (mode in listOf(0xa1ff, 0x21ff, 0x61ff, 0x11ff)) {
            val file = archive()
            mutate(file) { bytes ->
                val offset = centralOffset(bytes, "tools/ak3-core.sh")
                bytes.put16(offset + 4, 0x0314)
                bytes.put32(offset + 38, mode.toLong() shl 16)
            }
            rejected(file)
        }
    }

    @Test
    fun `native local header path cannot differ from checked central path`() {
        val file = archive()
        mutate(file) { bytes ->
            val local = bytes.u32(centralOffset(bytes, "anykernel.sh") + 42).toInt()
            "../evilfile".toByteArray().copyInto(bytes, local + 30)
        }
        rejected(file)
    }

    @Test
    fun `payload CRC mismatch is detected by reading bodies`() {
        val file = archive(stored = true)
        corruptPayload(file, "anykernel.sh")
        rejected(file)
    }

    @Test
    fun `failed installer extraction removes partial output`() {
        val file = archive(stored = true)
        corruptPayload(file, installerPath)
        val destination = File(temporary.newFolder(), "update-binary")
        assertThrows(IOException::class.java) { AnyKernel3Archive.extractInstaller(file, destination) }
        assertFalse(destination.exists())
    }

    @Test
    fun `mismatched data descriptors are rejected`() {
        val file = archive()
        mutate(file) { bytes ->
            val central = centralOffset(bytes, "anykernel.sh")
            val local = bytes.u32(central + 42).toInt()
            val descriptor = local + 30 + bytes.u16(local + 26) + bytes.u16(local + 28) + bytes.u32(central + 20).toInt()
            assertEquals(0x08074b50L, bytes.u32(descriptor))
            bytes[descriptor + 4] = (bytes[descriptor + 4].toInt() xor 1).toByte()
        }
        rejected(file)
    }

    @Test
    fun `encrypted and unsupported compression formats are rejected`() {
        val encrypted = archive()
        mutate(encrypted) { bytes ->
            val central = centralOffset(bytes, "anykernel.sh")
            bytes.put16(central + 8, bytes.u16(central + 8) or 1)
        }
        rejected(encrypted)
        val unsupported = archive()
        mutate(unsupported) { bytes -> bytes.put16(centralOffset(bytes, "anykernel.sh") + 10, 12) }
        rejected(unsupported)
    }

    @Test
    fun `ZIP64 version and multipart records are rejected`() {
        val zip64 = archive()
        mutate(zip64) { bytes -> bytes.put16(centralOffset(bytes, "anykernel.sh") + 6, 45) }
        rejected(zip64)
        val multipart = archive()
        mutate(multipart) { bytes -> bytes.put16(bytes.size - 22 + 4, 1) }
        rejected(multipart)
    }

    @Test
    fun `extra fields that override paths or describe links are rejected`() {
        for (type in listOf(0x7075, 0x756e, 0x000d)) {
            val extra = byteArrayOf(type.toByte(), (type ushr 8).toByte(), 1, 0, 0)
            rejected(archive(extra = extra))
        }
    }

    @Test
    fun `declared expanded size above limit is rejected before decompression`() {
        val file = archive()
        mutate(file) { bytes -> bytes.put32(centralOffset(bytes, "anykernel.sh") + 24, 2L * 1024 * 1024 * 1024 + 1) }
        rejected(file)
    }

    @Test
    fun `actual entry size must match its advertised size`() {
        val file = archive()
        mutate(file) { bytes ->
            val central = centralOffset(bytes, "anykernel.sh")
            val local = bytes.u32(central + 42).toInt()
            val descriptor = local + 30 + bytes.u16(local + 26) + bytes.u16(local + 28) + bytes.u32(central + 20).toInt()
            val smaller = bytes.u32(central + 24) - 1
            bytes.put32(central + 24, smaller)
            bytes.put32(descriptor + 12, smaller)
        }
        rejected(file)
    }

    @Test
    fun `installer above two MiB is rejected`() {
        rejected(archive(LinkedHashMap(required).apply {
            put(installerPath, ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        }))
    }

    @Test
    fun `compressed archive above limit is rejected without reading contents`() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(AnyKernel3Archive.MAX_ARCHIVE_BYTES + 1) }
        rejected(file)
    }

    @Test
    fun `entry count above limit is rejected`() {
        val file = archive()
        mutate(file) { bytes ->
            bytes.put16(bytes.size - 22 + 8, 8193)
            bytes.put16(bytes.size - 22 + 10, 8193)
        }
        rejected(file)
    }

    @Test
    fun `truncated archives trailing data and hidden local entries are rejected`() {
        val truncated = archive()
        truncated.writeBytes(truncated.readBytes().dropLast(1).toByteArray())
        rejected(truncated)
        val trailing = archive()
        trailing.appendBytes(byteArrayOf(1))
        rejected(trailing)
        val hidden = archive(LinkedHashMap(required).apply { put("hidden", byteArrayOf(1)) })
        val bytes = hidden.readBytes()
        val central = centralOffset(bytes, "hidden")
        val recordLength = 46 + bytes.u16(central + 28) + bytes.u16(central + 30) + bytes.u16(central + 32)
        val shortened = bytes.copyOfRange(0, central) + bytes.copyOfRange(central + recordLength, bytes.size)
        val end = shortened.size - 22
        shortened.put16(end + 8, 3)
        shortened.put16(end + 10, 3)
        shortened.put32(end + 12, shortened.u32(end + 12) - recordLength)
        hidden.writeBytes(shortened)
        rejected(hidden)
    }

    private fun archive(
        contents: Map<String, ByteArray> = required,
        stored: Boolean = false,
        extra: ByteArray? = null,
    ): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in contents) {
                val entry = ZipEntry(name)
                entry.extra = extra
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun rejected(file: File) {
        val failure = assertThrows(IOException::class.java) { AnyKernel3Archive.inspect(file) }
        assertTrue("Invalid ZIP should explain its rejection", !failure.message.isNullOrBlank())
    }

    private fun mutate(file: File, change: (ByteArray) -> Unit) {
        file.writeBytes(file.readBytes().also(change))
    }

    private fun corruptPayload(file: File, name: String) = mutate(file) { bytes ->
        val central = centralOffset(bytes, name)
        val local = bytes.u32(central + 42).toInt()
        val payload = local + 30 + bytes.u16(local + 26) + bytes.u16(local + 28)
        bytes[payload] = (bytes[payload].toInt() xor 1).toByte()
    }

    private fun centralOffset(bytes: ByteArray, name: String): Int {
        var offset = bytes.u32(bytes.size - 22 + 16).toInt()
        while (bytes.u32(offset) == 0x02014b50L) {
            val length = bytes.u16(offset + 28)
            val actual = bytes.copyOfRange(offset + 46, offset + 46 + length).toString(Charsets.UTF_8)
            if (actual == name) return offset
            offset += 46 + length + bytes.u16(offset + 30) + bytes.u16(offset + 32)
        }
        error("Missing test ZIP entry $name")
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun ByteArray.put16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
    private fun ByteArray.put32(offset: Int, value: Long) {
        put16(offset, value.toInt())
        put16(offset + 2, (value ushr 16).toInt())
    }
}

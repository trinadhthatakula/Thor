package com.valhalla.thor.data.repository

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/**
 * Regression tests for the APKPure `.xapk` install failure.
 *
 * APKPure stores the inner APKs as STORED entries with the data-descriptor flag
 * set and ZERO sizes in the local file header (the real sizes live only in the
 * central directory / trailing descriptor). [ZipInputStream] reads local headers
 * sequentially and cannot find where such a STORED entry ends, so it derails;
 * [BundleZip] uses ZipFile (central directory) and reads it correctly — exactly
 * like the `unzip` tool.
 *
 * These tests build a zip in that precise pathological layout by hand.
 */
class BundleZipTest {

    private val temp = mutableListOf<File>()

    @After
    fun cleanup() {
        temp.forEach { it.deleteRecursively() }
    }

    private fun tempFile(name: String): File =
        File.createTempFile("bundlezip_", "_$name").also { temp.add(it) }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "bundlezip_out_${temp.size}")
            .also { it.mkdirs(); temp.add(it) }

    private fun le16(v: Int) =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun crc(data: ByteArray): Long = CRC32().apply { update(data) }.value

    /**
     * Write a zip whose entries are STORED, flag bit-3 (data descriptor) set, with
     * zero crc/csize/usize in the LOCAL header and the real values only in the data
     * descriptor + central directory — the APKPure `.xapk` layout.
     */
    private fun writeStoredDataDescriptorZip(entries: List<Pair<String, ByteArray>>): File {
        val file = tempFile("stored.zip")
        file.outputStream().use { out ->
            data class Cd(val name: ByteArray, val crc: Long, val size: Int, val offset: Int)

            val cds = mutableListOf<Cd>()
            var offset = 0
            fun write(b: ByteArray) { out.write(b); offset += b.size }

            for ((name, data) in entries) {
                val nameBytes = name.toByteArray(Charsets.US_ASCII)
                val c = crc(data)
                val start = offset
                // Local file header — zero sizes, data-descriptor flag set, STORED.
                write(le32(0x04034b50))
                write(le16(20))          // version needed
                write(le16(0x0008))      // GP flags: bit 3 (data descriptor)
                write(le16(0))           // method: STORED
                write(le16(0))           // mod time
                write(le16(0x21))        // mod date (1980-01-01)
                write(le32(0))           // crc-32 (zero in local header)
                write(le32(0))           // compressed size (zero)
                write(le32(0))           // uncompressed size (zero)
                write(le16(nameBytes.size))
                write(le16(0))           // extra len
                write(nameBytes)
                write(data)
                // Data descriptor with the real values.
                write(le32(0x08074b50))
                write(le32(c))
                write(le32(data.size.toLong()))
                write(le32(data.size.toLong()))
                cds.add(Cd(nameBytes, c, data.size, start))
            }

            val cdStart = offset
            for (cd in cds) {
                write(le32(0x02014b50))
                write(le16(20))          // version made by
                write(le16(20))          // version needed
                write(le16(0x0008))      // flags
                write(le16(0))           // method: STORED
                write(le16(0))           // mod time
                write(le16(0x21))        // mod date
                write(le32(cd.crc))      // real crc
                write(le32(cd.size.toLong())) // real compressed size
                write(le32(cd.size.toLong())) // real uncompressed size
                write(le16(cd.name.size))
                write(le16(0))           // extra len
                write(le16(0))           // comment len
                write(le16(0))           // disk number start
                write(le16(0))           // internal attrs
                write(le32(0))           // external attrs
                write(le32(cd.offset.toLong()))
                write(cd.name)
            }
            val cdSize = offset - cdStart

            // End of central directory.
            write(le32(0x06054b50))
            write(le16(0))               // disk
            write(le16(0))               // cd start disk
            write(le16(cds.size))        // entries this disk
            write(le16(cds.size))        // entries total
            write(le32(cdSize.toLong()))
            write(le32(cdStart.toLong()))
            write(le16(0))               // comment len
        }
        return file
    }

    private val amazonEntries = listOf(
        "com.amazon.mShop.android.shopping.apk" to "BASE-APK-BYTES-payload-0123456789".toByteArray(),
        "config.arm64_v8a.apk" to "ARM64-CONFIG-SPLIT-bytes".toByteArray(),
        "config.xxhdpi.apk" to "XXHDPI-CONFIG-SPLIT-bytes".toByteArray(),
        "manifest.json" to """{"package_name":"com.amazon.mShop.android.shopping"}""".toByteArray()
    )

    @Test
    fun bundleZip_readsStoredDataDescriptorEntries() {
        val zip = writeStoredDataDescriptorZip(amazonEntries)

        // Central-directory read (like unzip) finds every entry with correct content.
        assertEquals(
            amazonEntries.map { it.first }.toSet(),
            BundleZip.entryNames(zip).toSet()
        )
        assertArrayEquals(
            amazonEntries.first { it.first == "manifest.json" }.second,
            BundleZip.readEntry(zip, "manifest.json")
        )
        assertArrayEquals(
            amazonEntries.first { it.first.startsWith("com.amazon") }.second,
            BundleZip.readEntry(zip, "com.amazon.mShop.android.shopping.apk")
        )
        assertNull(BundleZip.readEntry(zip, "does-not-exist.apk"))
    }

    @Test
    fun bundleZip_extractsSelectedEntriesWithExactContent() {
        val zip = writeStoredDataDescriptorZip(amazonEntries)
        val outDir = tempDir()

        val wanted = setOf(
            "com.amazon.mShop.android.shopping.apk",
            "config.arm64_v8a.apk",
            "config.xxhdpi.apk"
        )
        val extracted = BundleZip.extractEntries(zip, wanted, outDir)

        assertEquals(wanted, extracted.map { it.name }.toSet())
        for ((name, data) in amazonEntries.filter { it.first in wanted }) {
            assertArrayEquals(data, File(outDir, name).readBytes())
        }
    }

    @Test
    fun bundleZip_read_returnsEntryNamesAndRequestedBytesInOnePass() {
        val zip = writeStoredDataDescriptorZip(amazonEntries)

        val contents = BundleZip.read(zip, setOf("manifest.json", "missing.json"))

        assertEquals(amazonEntries.map { it.first }.toSet(), contents.entryNames.toSet())
        assertArrayEquals(
            amazonEntries.first { it.first == "manifest.json" }.second,
            contents.bytes["manifest.json"]
        )
        assertNull(contents.bytes["missing.json"])          // absent entry -> not in the map
        assertNull(contents.bytes["config.arm64_v8a.apk"])  // present but not requested
    }

    @Test
    fun zipInputStream_cannotReadThisLayout_documentingWhyTheBugExisted() {
        // The whole point of BundleZip: ZipInputStream does NOT recover the real
        // manifest.json bytes from this layout (it throws or mis-reads), whereas
        // BundleZip does. We assert BundleZip is correct and ZipInputStream is not.
        val zip = writeStoredDataDescriptorZip(amazonEntries)
        val expected = amazonEntries.first { it.first == "manifest.json" }.second

        assertArrayEquals(expected, BundleZip.readEntry(zip, "manifest.json"))

        val viaZis: ByteArray? = try {
            ZipInputStream(ByteArrayInputStream(zip.readBytes())).use { zis ->
                var found: ByteArray? = null
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "manifest.json") { found = zis.readBytes(); break }
                    e = zis.nextEntry
                }
                found
            }
        } catch (_: Exception) {
            null // ZipInputStream threw on the STORED+data-descriptor entry
        }

        // Either it threw (null) or it read the wrong bytes — never the correct ones.
        assertTrue(
            "ZipInputStream unexpectedly read the entry correctly",
            viaZis == null || !viaZis.contentEquals(expected)
        )
    }

    @Test
    fun bundleZip_handlesNormalDeflatedZipToo() {
        // A conventional (DEFLATED, sizes-in-local-header) zip must also read fine.
        val file = tempFile("normal.zip")
        val payload = "hello-deflated-world".toByteArray()
        java.util.zip.ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("base.apk"))
            zos.write(payload)
            zos.closeEntry()
        }
        assertEquals(listOf("base.apk"), BundleZip.entryNames(file))
        assertArrayEquals(payload, BundleZip.readEntry(file, "base.apk"))
        assertNotEquals(0, BundleZip.readEntry(file, "base.apk")!!.size)
    }
}

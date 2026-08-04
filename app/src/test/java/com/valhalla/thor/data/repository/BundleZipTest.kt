// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
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

    // Files.createTempDirectory, not a name built from temp.size: every test method gets a fresh
    // instance, so temp.size was always the same small number and every fork of this class — and
    // every concurrent variant task, foss and store run in the same build — collided on one
    // directory. @After then deleted a directory another fork was mid-test in.
    private fun tempDir(): File =
        Files.createTempDirectory("bundlezip_out_").toFile().also { temp.add(it) }

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

    /** Raw DEFLATE stream (no zlib wrapper), as a zip entry stores it. */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    /**
     * Write a single DEFLATED entry whose central directory declares [declaredSize] as the
     * uncompressed size — which is a *claim*, and here a false one. A decompression bomb is
     * exactly this: a small entry that says it is small and is not.
     */
    private fun writeZipDeclaringSize(
        name: String,
        content: ByteArray,
        declaredSize: Long
    ): File {
        val file = tempFile("declared.zip")
        val compressed = deflate(content)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val entryCrc = crc(content)
        file.outputStream().use { out ->
            var offset = 0
            fun write(b: ByteArray) { out.write(b); offset += b.size }

            write(le32(0x04034b50))
            write(le16(20))
            write(le16(0))                       // no data descriptor
            write(le16(8))                       // method: DEFLATE
            write(le16(0))
            write(le16(0x21))
            write(le32(entryCrc))
            write(le32(compressed.size.toLong()))
            write(le32(declaredSize))            // the lie
            write(le16(nameBytes.size))
            write(le16(0))
            write(nameBytes)
            write(compressed)

            val cdStart = offset
            write(le32(0x02014b50))
            write(le16(20))
            write(le16(20))
            write(le16(0))
            write(le16(8))
            write(le16(0))
            write(le16(0x21))
            write(le32(entryCrc))
            write(le32(compressed.size.toLong()))
            write(le32(declaredSize))            // the same lie, where ZipFile reads it
            write(le16(nameBytes.size))
            write(le16(0))
            write(le16(0))
            write(le16(0))
            write(le16(0))
            write(le32(0))
            write(le32(0))                       // local header offset
            write(nameBytes)
            val cdSize = offset - cdStart

            write(le32(0x06054b50))
            write(le16(0))
            write(le16(0))
            write(le16(1))
            write(le16(1))
            write(le32(cdSize.toLong()))
            write(le32(cdStart.toLong()))
            write(le16(0))
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

        assertEquals(wanted, extracted.map { it.file.name }.toSet())
        for ((name, data) in amazonEntries.filter { it.first in wanted }) {
            assertArrayEquals(data, File(outDir, name).readBytes())
        }
    }

    @Test
    fun bundleZip_extractEntries_digestsTheBytesItWroteNotTheFileItLeftBehind() {
        // The property the privileged rungs' integrity guard rests on. Those stage into
        // externalCacheDir, which any app holding WRITE_EXTERNAL_STORAGE can rewrite on API 28-29
        // the moment the stream closes — so a digest taken by re-opening the file measures the
        // attacker's bytes and then dutifully confirms them against themselves. Overwriting the
        // file here is exactly that swap; the recorded hash must not follow it.
        val zip = writeStoredDataDescriptorZip(listOf("base.apk" to "genuine-signal-bytes".toByteArray()))
        val outDir = tempDir()

        val extracted = BundleZip.extractEntries(zip, setOf("base.apk"), outDir).single()
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("genuine-signal-bytes".toByteArray())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        assertEquals(expected, extracted.sha256)

        // The swap.
        extracted.file.writeBytes("attacker-payload".toByteArray())
        val afterSwap = MessageDigest.getInstance("SHA-256")
            .digest(extracted.file.readBytes())
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

        assertNotEquals(afterSwap, extracted.sha256)
        // Which is what makes the on-device `sha256sum "$path" != "$expected"` check fail closed.
        assertEquals(expected, extracted.sha256)
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
    fun bundleZip_read_skipsAMetadataEntryThatLiesAboutItsSize() {
        // The shape that killed the process: `manifest.json` declares 100 bytes, expands to
        // megabytes, and the old readBytes() allocated all of it. At the real scale that is an
        // OutOfMemoryError, which is an Error — every `catch (Exception)` between here and
        // viewModelScope misses it and Thor dies while merely PREVIEWING the file.
        val payload = ByteArray(4 * 1024 * 1024) // compresses to a few KB
        val zip = writeZipDeclaringSize("manifest.json", payload, declaredSize = 100L)

        // The premise: the declared size is a lie, and reading it out really does yield 4 MB.
        // If this ever fails, the pre-check alone would have been enough and the test below
        // proves nothing.
        assertEquals(
            payload.size,
            BundleZip.read(zip, setOf("manifest.json"), maxEntryBytes = 8L * 1024 * 1024)
                .bytes["manifest.json"]!!.size
        )

        // With a cap the entry cannot honour, it is dropped rather than allocated.
        val bounded = BundleZip.read(zip, setOf("manifest.json"), maxEntryBytes = 64L * 1024)
        assertNull(bounded.bytes["manifest.json"])
        // The archive is still enumerated — only the oversized entry's *bytes* are refused.
        assertEquals(listOf("manifest.json"), bounded.entryNames)
    }

    @Test
    fun bundleZip_read_skipsAnEntryThatDeclaresMoreThanTheCap() {
        // Honest declaration, still too big: refused without opening the entry at all.
        val payload = ByteArray(200_000)
        val zip = writeZipDeclaringSize("icon.png", payload, declaredSize = payload.size.toLong())

        val contents = BundleZip.read(zip, setOf("icon.png"), maxEntryBytes = 1024L)

        assertNull(contents.bytes["icon.png"])
    }

    @Test
    fun bundleZip_readEntry_refusesAnEntryLargerThanTheCap() {
        val payload = ByteArray(200_000)
        val zip = writeZipDeclaringSize("info.json", payload, declaredSize = 10L)

        assertNull(BundleZip.readEntry(zip, "info.json", maxEntryBytes = 4096L))
        assertEquals(payload.size, BundleZip.readEntry(zip, "info.json", maxEntryBytes = 1L shl 24)!!.size)
    }

    @Test
    fun bundleZip_extractEntries_refusesAtTheBudgetAndLeavesNoPartialFile() {
        // On-disk extraction has the same problem in a different currency: instead of the heap,
        // a bomb fills the data partition from cacheDir.
        val payload = ByteArray(1024 * 1024)
        val zip = writeZipDeclaringSize("base.apk", payload, declaredSize = 10L)
        val outDir = tempDir()

        val refusal = assertThrows(InstallRefusedException::class.java) {
            BundleZip.extractEntries(zip, setOf("base.apk"), outDir, maxTotalBytes = 4096L)
        }

        assertTrue(
            "the refusal has to name what was refused: ${refusal.message}",
            refusal.message!!.contains("base.apk")
        )
        assertTrue("partial output left behind", File(outDir, "base.apk").exists().not())
    }

    @Test
    fun bundleZip_extractEntries_refusesTheWholeSetWhenALaterSplitBlowsTheBudget() {
        // The half that the old "stop and return what we have" answer let through. base.apk fits,
        // the split does not, and the short list came back non-empty — which stageInstallSet's
        // `.ifEmpty { null }` reads as success, so `pm install-multiple` ran on a bundle missing
        // one of its splits. Half a bundle is not a smaller install, it is a different one.
        val zip = writeStoredDataDescriptorZip(
            listOf(
                "base.apk" to ByteArray(2048),
                "split_config.arm64_v8a.apk" to ByteArray(4096),
            )
        )
        val outDir = tempDir()

        assertThrows(InstallRefusedException::class.java) {
            BundleZip.extractEntries(
                zip,
                setOf("base.apk", "split_config.arm64_v8a.apk"),
                outDir,
                maxTotalBytes = 3000L
            )
        }

        // Including the entry that DID fit: leaving it behind would leave a lone base.apk in the
        // staging directory for a retry to find.
        assertTrue("the entry that fitted was left behind", File(outDir, "base.apk").exists().not())
        assertTrue(File(outDir, "split_config.arm64_v8a.apk").exists().not())
    }

    @Test
    fun bundleZip_extractEntries_spendsOneBudgetAcrossTheWholeSet() {
        // The complement of the test above: the budget is not per entry, so a set that fits inside
        // it whole is extracted whole. Two 2 KB entries under a 4 KB budget is the boundary case.
        val zip = writeStoredDataDescriptorZip(
            listOf(
                "base.apk" to ByteArray(2048) { 1 },
                "split_config.xxhdpi.apk" to ByteArray(2048) { 2 },
            )
        )
        val outDir = tempDir()

        val extracted = BundleZip.extractEntries(
            zip,
            setOf("base.apk", "split_config.xxhdpi.apk"),
            outDir,
            maxTotalBytes = 4096L
        )

        assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), extracted.map { it.file.name })
        assertEquals(2048, File(outDir, "base.apk").length().toInt())
        assertEquals(2048, File(outDir, "split_config.xxhdpi.apk").length().toInt())
    }

    @Test
    fun bundleZip_extractEntryTo_stopsAtTheBudgetAndDeletesThePartialFile() {
        val payload = ByteArray(1024 * 1024)
        val zip = writeZipDeclaringSize("base.apk", payload, declaredSize = 10L)
        val dest = tempFile("out.apk")

        assertTrue(BundleZip.extractEntryTo(zip, "base.apk", dest, maxBytes = 4096L).not())
        assertTrue("partial output left behind", dest.exists().not())
    }

    @Test
    fun bundleZip_extractEntries_refusesAnEntryWhoseBaseNameIsAPathComponent() {
        // `java.io.File` does not normalise `..`; the syscall does. `substringAfterLast('/')`
        // strips directories but leaves `..` intact, so "payload/.." arrives as the base name
        // "..", and File(outDir, "..") is outDir's PARENT.
        //
        // Refused, not skipped. Skipping produced the very shape the budget case two tests up
        // exists to forbid: a short list that `stageInstallSet` read as success, on a set the
        // identity was drawn from. The name is in the install set or the archive is not installed.
        val zip = writeStoredDataDescriptorZip(listOf("payload/.." to "evil".toByteArray()))
        val outDir = tempDir()

        val refusal = assertThrows(InstallRefusedException::class.java) {
            BundleZip.extractEntries(zip, setOf(".."), outDir)
        }

        assertTrue(
            "the refusal has to name what was refused: ${refusal.message}",
            refusal.message!!.contains("..")
        )
        assertEquals("nothing may be written for a refused name", 0, outDir.listFiles()!!.size)
    }

    @Test
    fun bundleZip_extractEntries_refusesAPartlyUnsafeBundleInsteadOfTruncatingIt() {
        // The regression this branch introduced and then had to close: base.apk extracts, the
        // split's leaf is not a name any writer accepts, and the old `continue` handed back a
        // one-file "bundle". Same archive, same set, one file short — which is a different app.
        val zip = writeStoredDataDescriptorZip(
            listOf(
                "base.apk" to ByteArray(64) { 1 },
                "evil\\split_config.arm64_v8a.apk" to ByteArray(64) { 2 },
            )
        )
        val outDir = tempDir()

        assertThrows(InstallRefusedException::class.java) {
            BundleZip.extractEntries(
                zip,
                setOf("base.apk", "evil\\split_config.arm64_v8a.apk"),
                outDir
            )
        }

        // Including the entry that WAS safe: leaving it behind leaves a lone base.apk for a retry.
        assertTrue("the safe entry was left behind", File(outDir, "base.apk").exists().not())
    }

    @Test
    fun bundleZip_extractEntries_refusesAWantedNameTheArchiveDoesNotHold() {
        // `pm install-multiple` on a set missing one of its splits is the same partial install the
        // budget case refuses, reached by a set that simply named a file that is not there.
        val zip = writeStoredDataDescriptorZip(listOf("base.apk" to ByteArray(64)))
        val outDir = tempDir()

        val refusal = assertThrows(InstallRefusedException::class.java) {
            BundleZip.extractEntries(zip, setOf("base.apk", "split_config.xxhdpi.apk"), outDir)
        }

        assertTrue(refusal.message!!.contains("split_config.xxhdpi.apk"))
        assertTrue(File(outDir, "base.apk").exists().not())
    }

    @Test
    fun bundleZip_extractEntries_doesNotCountTwoSpellingsOfOneNameAsTwoFiles() {
        // The completeness check counts what the archive can actually yield, which is one file per
        // *lowercased* name. Counting the caller's set instead would refuse a set that came out
        // whole, because `stageInstallSet` builds its wanted set case-sensitively.
        val zip = writeStoredDataDescriptorZip(listOf("base.apk" to ByteArray(64)))
        val outDir = tempDir()

        val extracted = BundleZip.extractEntries(zip, setOf("base.apk", "BASE.APK"), outDir)

        assertEquals(listOf("base.apk"), extracted.map { it.file.name })
    }

    @Test
    fun bundleZip_extractEntryTo_refusesABaseNameThatIsNotAPlainLeaf() {
        // The read that picks the identity on the confirmation sheet. Every writer refuses a name
        // like this, so an identity must not be readable from one either — that gap is how the
        // sheet ends up describing a file `pm` was never given. The analyzer reads false as
        // "try the next candidate", which is the right answer.
        val zip = writeStoredDataDescriptorZip(
            listOf("evil\\base.apk" to "attacker-payload".toByteArray())
        )
        val dest = tempFile("identity.apk")
        dest.delete()

        assertTrue(BundleZip.extractEntryTo(zip, "evil\\base.apk", dest).not())
        assertTrue(BundleZip.extractEntryTo(zip, "..", dest).not())
        assertTrue("nothing may be written for a refused name", dest.exists().not())
    }

    @Test
    fun isSafeEntryFileName_rejectsEveryNameThatIsNotALeaf() {
        assertTrue(isSafeEntryFileName("base.apk"))
        assertTrue(isSafeEntryFileName("split_config.arm64_v8a.apk"))
        assertTrue(isSafeEntryFileName("my app.apk")) // a space is not a traversal
        assertTrue(isSafeEntryFileName("..").not())
        assertTrue(isSafeEntryFileName(".").not())
        assertTrue(isSafeEntryFileName("").not())
        assertTrue(isSafeEntryFileName("   ").not())
        assertTrue(isSafeEntryFileName("a/b.apk").not())
        assertTrue(isSafeEntryFileName("a\\b.apk").not())
    }

    @Test
    fun bundleZip_read_ignoresANestedEntryCarryingAWantedName() {
        // read() serves the bundle *gate* (hasXapkManifest / hasApkmInfoJson), which only counts a
        // root-level sidecar. Matching a base name anywhere made the two disagree: a plain APK's
        // res/mipmap-hdpi/icon.png became the icon on the confirmation sheet — an attacker-chosen
        // picture beside a package name the user is being asked to trust — and a file that
        // classified as monolithic could still have an assets/manifest.json read out of it.
        val zip = writeStoredDataDescriptorZip(
            listOf(
                "AndroidManifest.xml" to "binary-xml".toByteArray(),
                "res/mipmap-hdpi/icon.png" to "whatsapp-icon-bytes".toByteArray(),
                "assets/manifest.json" to """{"package_name":"com.whatsapp"}""".toByteArray(),
            )
        )

        val contents = BundleZip.read(zip, setOf("icon.png", "manifest.json"))

        assertNull(contents.bytes["icon.png"])
        assertNull(contents.bytes["manifest.json"])
        // The names are still all enumerated — only the *bytes* matching is root-level, because
        // isMonolithicApk and selectBaseApkCandidates read this list.
        assertTrue(contents.entryNames.contains("res/mipmap-hdpi/icon.png"))
        assertTrue(contents.entryNames.contains("assets/manifest.json"))
    }

    @Test
    fun bundleZip_read_stillTakesARootLevelSidecarAndIcon() {
        // The other half: a real .xapk puts both at the root and must keep working.
        val zip = writeStoredDataDescriptorZip(
            listOf(
                "manifest.json" to """{"package_name":"com.amazon.mShop.android.shopping"}""".toByteArray(),
                "icon.png" to "real-bundle-icon".toByteArray(),
            )
        )

        val contents = BundleZip.read(zip, setOf("icon.png", "manifest.json"))

        assertArrayEquals("real-bundle-icon".toByteArray(), contents.bytes["icon.png"])
        assertNotEquals(null, contents.bytes["manifest.json"])
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

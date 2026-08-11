// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Write a container where every entry uses the default (DEFLATED) compression. */
    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("a.thorbak")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    /**
     * Write a container that matches the real writer's per-entry method choices:
     * - `thorbak.json` (header) uses [ZipEntry.STORED] — built in memory so size/CRC are known.
     * - All other entries use [Deflater.NO_COMPRESSION] DEFLATED — streamed with unknown sizes.
     *
     * Commit `0a32b8a3` made this the container shape on purpose; the read side must handle both.
     */
    private fun mixedMethodZip(
        headerBody: String,
        vararg dataEntries: Pair<String, String>,
    ): File {
        val file = temp.newFile("mixed.thorbak")
        ZipOutputStream(file.outputStream()).apply { setLevel(Deflater.NO_COMPRESSION) }.use { out ->
            // Data entries first (DEFLATED, level 0) — matches writer's member order.
            dataEntries.forEach { (name, body) ->
                out.putNextEntry(ZipEntry(name))
                out.write(body.toByteArray())
                out.closeEntry()
            }
            // Header last, STORED — matches BackupAppArchiveUseCase.kt:211–219.
            val headerBytes = headerBody.toByteArray()
            out.putNextEntry(
                ZipEntry("thorbak.json").apply {
                    method = ZipEntry.STORED
                    size = headerBytes.size.toLong()
                    compressedSize = headerBytes.size.toLong()
                    crc = CRC32().apply { update(headerBytes) }.value
                }
            )
            out.write(headerBytes)
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `entry names come back in the order the container stores them`() {
        // The header is written last (Task 10), so a reader that assumed "first entry" would find a
        // data member instead.
        val source = ZipArchiveSource(zip("ce.tar.gz.enc" to "x", "thorbak.json" to "{}"), "a.thorbak")

        source.use { assertEquals(listOf("ce.tar.gz.enc", "thorbak.json"), it.entryNames()) }
    }

    @Test
    fun `an entry opens by exact name`() {
        val source = ZipArchiveSource(zip("thorbak.json" to "{\"a\":1}"), "a.thorbak")

        source.use {
            assertEquals("{\"a\":1}", it.openEntry("thorbak.json")!!.readBytes().decodeToString())
        }
    }

    @Test
    fun `a name that is not in the container returns null rather than throwing`() {
        // "no DE member" is an ordinary, expected answer — a header can legitimately hold three
        // classes out of four. Throwing would make the common case an exception path.
        val source = ZipArchiveSource(zip("thorbak.json" to "{}"), "a.thorbak")

        source.use { assertNull(it.openEntry("de.tar.gz.enc")) }
    }

    @Test
    fun `lookup is by exact name, so a traversal entry name is not resolved`() {
        // Nothing in the restore path ever writes a file named after a zip entry — every destination
        // is computed from the *class*, not from the container. This test pins that: an entry called
        // `../../evil` is openable under its literal name only; its path-resolved form is null.
        val source = ZipArchiveSource(zip("../../evil" to "secret", "thorbak.json" to "{}"), "a.thorbak")

        source.use {
            // The entry IS reachable — by its exact, un-resolved name.
            assertNotNull("exact-name lookup must succeed", it.openEntry("../../evil"))
            // The path-resolved name is not in the container — zip-slip is not possible.
            assertNull("path-resolved name must return null", it.openEntry("evil"))
        }
    }

    @Test
    fun `two entries can be read in sequence from one source`() {
        // `ZipFile`, not `ZipInputStream`: the header is read first to learn the member list, then the
        // members are read. A sequential-only reader would need a second full pass over the file.
        val source = ZipArchiveSource(zip("a" to "one", "b" to "two"), "a.thorbak")

        source.use {
            assertEquals("one", it.openEntry("a")!!.readBytes().decodeToString())
            assertEquals("two", it.openEntry("b")!!.readBytes().decodeToString())
        }
    }

    @Test
    fun `close runs the caller's cleanup exactly once`() {
        // The cleanup closes the `ParcelFileDescriptor` the factory opened. Running it twice would
        // close an fd number the process may have already reused for something else.
        var closes = 0
        val source = ZipArchiveSource(zip("a" to "x"), "a.thorbak", onClose = { closes++ })

        source.close()
        source.close()

        assertEquals(1, closes)
    }

    @Test
    fun `a file that is not a zip fails at construction, not on first read`() {
        val notAZip = temp.newFile("b.thorbak").apply { writeText("this is not a zip") }

        assertThrows(IOException::class.java) { ZipArchiveSource(notAZip, "b.thorbak") }
    }

    @Test
    fun `a STORED header entry and DEFLATED data entries are both readable`() {
        // The real writer (BackupAppArchiveUseCase, commit 0a32b8a3) uses STORED for thorbak.json and
        // level-0 DEFLATED for every data member. `ZipFile.getInputStream` decodes both transparently,
        // but neither combination appeared in the original fixtures — this pins the read-side contract.
        val source = ZipArchiveSource(
            mixedMethodZip(
                headerBody = "{\"schema\":1}",
                "ce.tar.gz.enc" to "cedata",
                "de.tar.enc" to "dedata",
            ),
            "mixed.thorbak",
        )

        source.use {
            assertEquals(
                listOf("ce.tar.gz.enc", "de.tar.enc", "thorbak.json"),
                it.entryNames(),
            )
            assertEquals("{\"schema\":1}", it.openEntry("thorbak.json")!!.readBytes().decodeToString())
            assertEquals("cedata", it.openEntry("ce.tar.gz.enc")!!.readBytes().decodeToString())
            assertEquals("dedata", it.openEntry("de.tar.enc")!!.readBytes().decodeToString())
        }
    }
}

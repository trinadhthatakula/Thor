// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

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
    fun `lookup is by exact name, so a traversal entry name is unreachable`() {
        // Nothing in the restore path ever writes a file named after a zip entry — every destination
        // is computed from the *class*, not from the container. This test pins that: an entry called
        // `../../evil` is visible in the listing and openable only under its literal name, and no
        // caller asks for that name.
        val source = ZipArchiveSource(zip("../../evil" to "x", "thorbak.json" to "{}"), "a.thorbak")

        source.use {
            assertTrue(it.entryNames().contains("../../evil"))
            assertNull(it.openEntry("evil"))
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
}

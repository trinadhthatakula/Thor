// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ObbExpansionZipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("bundle-${entries.size}-${entries.hashCode()}.xapk")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an expansion is extracted to its leaf name`() {
        val zip = zipOf(
            "base.apk" to ByteArray(4) { 1 },
            "Android/obb/com.example.game/main.obb" to ByteArray(64) { 7 }
        )
        val out = temp.newFolder("out")

        val extracted = extractExpansions(
            zip,
            listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
            out
        )

        assertEquals(listOf("main.obb"), extracted.map { it.leafName })
        assertEquals(64L, extracted.single().file.length())
        // Flat in the output directory — the nesting belongs to the destination on the device,
        // not to Thor's private staging area.
        assertEquals(out, extracted.single().file.parentFile)
    }

    @Test
    fun `nothing declared extracts nothing and does not fail`() {
        val zip = zipOf("base.apk" to ByteArray(4))
        val out = temp.newFolder("out")

        assertTrue(extractExpansions(zip, emptyList(), out).isEmpty())
    }

    @Test
    fun `a declared entry that vanished between resolve and extract refuses`() {
        // resolveExpansions checked the central directory; if it disagrees now, the archive is
        // being modified underneath us. Refuse rather than place a partial set.
        val zip = zipOf("base.apk" to ByteArray(4))
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
                out
            )
        }
    }

    @Test
    fun `exceeding the total budget refuses and leaves nothing behind`() {
        val zip = zipOf(
            "Android/obb/com.example.game/a.obb" to ByteArray(64) { 1 },
            "Android/obb/com.example.game/b.obb" to ByteArray(64) { 2 }
        )
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(
                    ResolvedExpansion("Android/obb/com.example.game/a.obb", "a.obb"),
                    ResolvedExpansion("Android/obb/com.example.game/b.obb", "b.obb")
                ),
                out,
                maxTotalBytes = 100L
            )
        }

        // A refusal must not leave half a game's data in the staging directory for the next
        // install to trip over.
        assertEquals(emptyList<File>(), out.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `the budget is the whole set, not per entry`() {
        val zip = zipOf(
            "Android/obb/com.example.game/a.obb" to ByteArray(64) { 1 },
            "Android/obb/com.example.game/b.obb" to ByteArray(64) { 2 }
        )
        val out = temp.newFolder("out")

        val extracted = extractExpansions(
            zip,
            listOf(
                ResolvedExpansion("Android/obb/com.example.game/a.obb", "a.obb"),
                ResolvedExpansion("Android/obb/com.example.game/b.obb", "b.obb")
            ),
            out,
            maxTotalBytes = 128L
        )

        assertEquals(2, extracted.size)
    }

    @Test
    fun `the expansion budget is far larger than the apk budget`() {
        // A single modern game's expansion set can approach the 4 GiB the APK set is capped at.
        // Sharing that cap would refuse archives that are entirely legitimate.
        assertTrue(MAX_EXPANSION_TOTAL_BYTES > MAX_EXTRACTED_TOTAL_BYTES)
    }

    @Test
    fun `too many expansions refuses without creating the staging directory`() {
        // The byte budget does not bound the entry count: a manifest-free archive has every *.obb
        // entry treated as an expansion, and a million one-byte entries costs a million inodes and a
        // million cp invocations while spending almost none of that budget.
        val entries = (1..MAX_EXPANSION_ENTRIES + 1).map {
            "Android/obb/com.example.game/p$it.obb" to ByteArray(1)
        }
        val zip = zipOf(*entries.toTypedArray())
        val out = File(temp.root, "out-never-created")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                entries.map { (name, _) -> ResolvedExpansion(name, name.substringAfterLast('/')) },
                out
            )
        }
        assertFalse(out.exists())
    }

    @Test
    fun `the same leaf twice refuses rather than overwriting`() {
        // Unreachable through resolveExpansions, which drops repeats. Reaching it means a caller
        // built the list some other way, and overwriting would return two entries pointing at one
        // file and charge the budget twice. Case-insensitive: the staging volume usually is.
        val zip = zipOf(
            "Android/obb/com.example.game/main.obb" to ByteArray(8) { 1 },
            "Android/obb/com.example.game/other.obb" to ByteArray(8) { 2 }
        )
        val out = temp.newFolder("dup-out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(
                    ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb"),
                    ResolvedExpansion("Android/obb/com.example.game/other.obb", "MAIN.OBB")
                ),
                out
            )
        }
        assertEquals(emptyList<File>(), out.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `an unsafe leaf refuses even if it somehow reached this far`() {
        // Defence in depth: resolveExpansions is the gate, but this function writes files and
        // must not depend on having been called correctly.
        val zip = zipOf("Android/obb/com.example.game/main.obb" to ByteArray(4))
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "../evil.obb")),
                out
            )
        }
        assertFalse(File(out.parentFile, "evil.obb").exists())
    }
}

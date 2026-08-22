// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.ObbPlacement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `ObbInstaller`'s two hoisted halves — the streaming placement loop through [ObbStreamStep], and
 * [readDeclaredExpansions] — so neither needs a `Context`.
 *
 * What matters in the first half is disk: §8.4 exists because the existing `place` extracts every
 * expansion before placing any, so a 4 GB game costs 8 GB. Those tests assert the peak, not just the
 * outcome. What matters in the second is that "this archive declares no expansions" and "this
 * archive could not be read" stay two answers.
 */
class ObbInstallerStreamingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun step(
        placed: MutableList<String> = mutableListOf(),
        failOn: String? = null,
        peaks: MutableList<Long> = mutableListOf(),
    ) = object : ObbStreamStep {
        override suspend fun extract(leafName: String, into: File): File? {
            val file = File(into, leafName).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1024)) }
            peaks += into.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            return file
        }

        override suspend fun place(source: File, leafName: String): Boolean {
            if (leafName == failOn) return false
            placed += leafName
            return true
        }
    }

    @Test
    fun `each expansion is extracted, placed, then deleted before the next is extracted`() = runTest {
        // The invariant §8.4 asks for, expressed as a measurement: the staging directory never holds
        // two files at once. A `finally`-less delete, or a delete moved after the loop, breaks this
        // and nothing else.
        val peaks = mutableListOf<Long>()
        val staging = temp.newFolder("staging")

        val result = streamObbEntries(listOf("main.obb", "patch.obb", "extra.obb"), staging, step(peaks = peaks))

        assertEquals(3, (result as ObbPlacement.Placed).count)
        assertEquals(listOf(1024L, 1024L, 1024L), peaks)
    }

    @Test
    fun `the staging directory is empty when the loop finishes`() = runTest {
        val staging = temp.newFolder("staging")

        streamObbEntries(listOf("main.obb", "patch.obb"), staging, step())

        assertEquals(emptyList<File>(), staging.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `a placement failure names the file and stops the loop`() = runTest {
        val placed = mutableListOf<String>()
        val staging = temp.newFolder("staging")

        val result = streamObbEntries(
            listOf("main.obb", "patch.obb", "extra.obb"),
            staging,
            step(placed = placed, failOn = "patch.obb"),
        )

        val reason = (result as ObbPlacement.Failed).reason
        assertTrue(reason, reason.contains("patch.obb"))
        // Stops rather than carrying on: a game missing one expansion is broken, and continuing would
        // spend the remaining minutes and disk producing the same broken outcome.
        assertEquals(listOf("main.obb"), placed)
    }

    @Test
    fun `a failed placement still clears the staging directory`() = runTest {
        // Otherwise a full volume plus a failed placement leaves the partial bytes behind, and the
        // *next* attempt fails for lack of space with a message about game data.
        val staging = temp.newFolder("staging")

        streamObbEntries(listOf("main.obb", "patch.obb"), staging, step(failOn = "patch.obb"))

        assertEquals(emptyList<File>(), staging.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `an extraction that produces nothing is a failure naming the file`() = runTest {
        val staging = temp.newFolder("staging")
        val brokenStep = object : ObbStreamStep {
            override suspend fun extract(leafName: String, into: File): File? = null
            override suspend fun place(source: File, leafName: String) = true
        }

        val result = streamObbEntries(listOf("main.obb"), staging, brokenStep)

        assertTrue(result.toString(), (result as ObbPlacement.Failed).reason.contains("main.obb"))
    }

    @Test
    fun `no expansions is not needed rather than a placement of zero`() = runTest {
        // `Placed(0)` would render as "0 game data files placed", which reads as a failure for an app
        // that simply has no expansions.
        val result = streamObbEntries(emptyList(), temp.newFolder("staging"), step())

        assertEquals(ObbPlacement.NotNeeded, result)
    }

    @Test
    fun `progress reports each file with its position in the set`() = runTest {
        val seen = mutableListOf<Triple<String, Int, Int>>()

        streamObbEntries(listOf("main.obb", "patch.obb"), temp.newFolder("staging"), step()) { name, i, total ->
            seen += Triple(name, i, total)
        }

        // 1-based: "1 of 2", not "0 of 2". A progress line that starts at zero reads as not started.
        assertEquals(listOf(Triple("main.obb", 1, 2), Triple("patch.obb", 2, 2)), seen)
    }

    // --- readDeclaredExpansions: "nothing declared" and "could not read" are different answers ---

    private var zipCount = 0

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("declared-${entries.size}-${zipCount++}.xapk")
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
    fun `an archive that cannot be read is null, not an empty list`() {
        // The whole reason this function exists. `placeStreaming` is the terminal operation on the
        // restore path — there is no install ahead of it to fail with a better message — so a
        // truncated `.thorbak` that read as "no expansions" would end the restore in "restored" and
        // leave a game that launches and immediately crashes.
        val notAZip = temp.newFile("truncated.xapk").apply { writeBytes(ByteArray(64) { 0x7F }) }

        assertNull(readDeclaredExpansions(notAZip, "com.example.game"))
    }

    @Test
    fun `a plain apk declares nothing rather than failing`() {
        // The other half of the same distinction: a monolithic APK is a perfectly readable zip with
        // no `manifest.json` and no `Android/obb/…` entries. It must stay a `NotNeeded`, so this has
        // to be an empty list and not a null.
        val plain = zipOf(
            "AndroidManifest.xml" to ByteArray(8),
            "classes.dex" to ByteArray(8),
        )

        assertEquals(emptyList<ResolvedExpansion>(), readDeclaredExpansions(plain, "com.example.game"))
    }

    @Test
    fun `a declared expansion is read out of the manifest and matched to its entry`() {
        // The entry deliberately sits at the archive root while its `install_path` is the OBB
        // directory — the shape a real `.xapk` uses, and the one the manifest-free fallback rule
        // cannot find. A wiring that dropped the parsed manifest would resolve nothing here.
        val zip = zipOf(
            "manifest.json" to """
                {"package_name":"com.example.game","expansions":[
                  {"file":"main.1.obb","install_path":"Android/obb/com.example.game/main.1.obb"}
                ]}
            """.trimIndent().toByteArray(),
            "base.apk" to ByteArray(8),
            "main.1.obb" to ByteArray(16),
        )

        assertEquals(
            listOf(ResolvedExpansion("main.1.obb", "main.1.obb")),
            readDeclaredExpansions(zip, "com.example.game"),
        )
    }

    @Test
    fun `a manifest that is not json at all still reads the archive`() {
        // `parseXapkManifest` returns null for unparseable JSON rather than throwing, so this is
        // *not* an unreadable archive — the zip opened fine. It falls through to the manifest-free
        // rule, which takes the `Android/obb/<pkg>/` entries the archive actually carries.
        val zip = zipOf(
            "manifest.json" to "not json {{{".toByteArray(),
            "Android/obb/com.example.game/main.1.obb" to ByteArray(16),
        )

        assertEquals(listOf("main.1.obb"), readDeclaredExpansions(zip, "com.example.game")?.map { it.leafName })
    }
}

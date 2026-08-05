// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The pure parts of "read the picked file once, install exactly those bytes": which archive
 * entries reach an install session, whether the staged bytes are still the staged bytes by the
 * time `pm` reads them, and who cleans up a copy nobody claimed.
 */
class StagedInstallTest {

    private val temp = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("staged_install_").toFile().also { temp.add(it) }

    @After
    fun tearDown() {
        temp.forEach { it.deleteRecursively() }
    }

    // ---- sweepStaleStagedPackages --------------------------------------------------------

    @Test
    fun `a staged copy left behind by a dead process is reclaimed`() {
        // The analyzer no longer deletes its copy — the ViewModel does, on teardown. Kill the
        // process before that runs (the OS killing an installer sheet in the background is the
        // normal case, not the exotic one) and the copy is a full-size APK nothing will ever
        // claim. This sweep is the only thing that gets it back.
        val dir = tempDir()
        val now = System.currentTimeMillis()
        val stranded = File(dir, "staged_dead").apply { writeText("x") }
        stranded.setLastModified(now - STAGED_PACKAGE_TTL_MILLIS - 1)

        assertEquals(1, sweepStaleStagedPackages(dir, now))
        assertFalse(stranded.exists())
    }

    @Test
    fun `the copy the user is installing right now survives the sweep`() {
        // The sweep runs at the START of an analysis, while an earlier sheet may still be sitting
        // on its own staged file waiting for the user to press Install. Deleting that one turns
        // the fix into a bug.
        val dir = tempDir()
        val now = System.currentTimeMillis()
        val live = File(dir, "staged_live").apply { writeText("x") }
        live.setLastModified(now - 1000L)
        // Exactly at the TTL is still kept; only strictly older goes.
        val edge = File(dir, "staged_edge").apply { writeText("x") }
        edge.setLastModified(now - STAGED_PACKAGE_TTL_MILLIS)

        assertEquals(0, sweepStaleStagedPackages(dir, now))
        assertTrue(live.exists())
        assertTrue(edge.exists())
    }

    @Test
    fun `sweeping a staging directory that was never created is not an error`() {
        // analyze() sweeps before it stages, so on a first run the directory does not exist yet.
        // listFiles() answers null there, and null is not an empty array.
        val missing = File(tempDir(), "never_created")

        assertEquals(0, sweepStaleStagedPackages(missing, System.currentTimeMillis()))
    }

    @Test
    fun `the sweep only takes files, never a directory`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        val subDir = File(dir, "install_shizuku_1").apply { mkdirs() }
        subDir.setLastModified(now - STAGED_PACKAGE_TTL_MILLIS - 1)

        assertEquals(0, sweepStaleStagedPackages(dir, now))
        assertTrue(subDir.isDirectory)
    }

    // ---- the digest taken during the copy ------------------------------------------------

    private fun digestOfCopy(source: ByteArray, limit: Long = Long.MAX_VALUE): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = ByteArrayOutputStream()
        ByteArrayInputStream(source).use { it.copyAtMostTo(sink, limit, digest) } ?: return null
        return digest.digest().toLowercaseHex()
    }

    @Test
    fun `the digest taken during the copy matches the published SHA-256 vectors`() {
        // Pinned against the standard vectors rather than against another call to the same code,
        // because the shell compares this hex to `sha256sum` output on the device. It is taken on
        // the way through the copy now — the expected hash has to describe the bytes Thor wrote,
        // not whatever is in the file by the time anyone gets round to reading it back.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digestOfCopy(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digestOfCopy("abc".toByteArray())
        )
    }

    @Test
    fun `a stream far larger than one read buffer still hashes as one stream`() {
        // APKs are tens of megabytes; the buffered loop is the whole point, and an off-by-one in
        // it would produce a hash that no device could ever match — every privileged install
        // would fail the guard.
        val bytes = ByteArray(100_000) { i -> (i * 31 % 251).toByte() }

        val oneShot = MessageDigest.getInstance("SHA-256").digest(bytes).toLowercaseHex()

        assertEquals(oneShot, digestOfCopy(bytes))
    }

    @Test
    fun `a single flipped byte changes the digest`() {
        // The property the guard rests on: an attacker who swaps base.apk in shared storage
        // cannot leave the hash where it was.
        val before = digestOfCopy(ByteArray(4096) { 7 })

        assertNotEquals(before, digestOfCopy(ByteArray(4096) { i -> if (i == 2048) 8 else 7 }))
    }

    @Test
    fun `the copy writes exactly the source bytes it hashed`() {
        // The digest is only worth anything if it describes what landed on disk, so the two are
        // asserted against the same call rather than against each other in separate ones.
        val bytes = ByteArray(70_000) { i -> (i % 256).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = ByteArrayOutputStream()

        val copied = ByteArrayInputStream(bytes).use { it.copyAtMostTo(sink, 1L shl 24, digest) }

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, sink.toByteArray())
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(sink.toByteArray()).toLowercaseHex(),
            digest.digest().toLowercaseHex()
        )
    }

    @Test
    fun `a source past the limit yields no byte count, so no caller can mistake it for a copy`() {
        assertNull(digestOfCopy(ByteArray(5000), limit = 4096L))
    }

    // ---- writeEntriesWithinBudget (the PackageInstaller session path) ---------------------

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = File(tempDir(), "bundle.zip")
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    /** A stand-in for `session.openWrite`, recording the name and declared length it was given. */
    private class RecordingSession {
        val sinks = LinkedHashMap<String, ByteArrayOutputStream>()
        val declaredLengths = mutableListOf<Pair<String, Long>>()

        fun openSink(name: String, declaredLength: Long): ByteArrayOutputStream {
            declaredLengths.add(name to declaredLength)
            return sinks.getOrPut(name) { ByteArrayOutputStream() }
        }
    }

    @Test
    fun `every selected entry reaches the session with its exact bytes`() {
        val base = ByteArray(3000) { 1 }
        val split = ByteArray(1500) { 2 }
        val zip = zipOf("base.apk" to base, "split_config.arm64_v8a.apk" to split)
        val session = RecordingSession()

        val written = ZipFile(zip).use { zf ->
            writeEntriesWithinBudget(
                zip = zf,
                entries = selectEntriesToWrite(
                    zf.entries().asSequence(),
                    setOf("base.apk", "split_config.arm64_v8a.apk")
                ),
                budget = 1L shl 20,
                openSink = session::openSink
            )
        }

        assertEquals(4500L, written)
        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk"), session.sinks.keys.toList())
        assertArrayEquals(base, session.sinks["base.apk"]!!.toByteArray())
        assertArrayEquals(split, session.sinks["split_config.arm64_v8a.apk"]!!.toByteArray())
    }

    @Test
    fun `an entry that expands past the budget refuses the install instead of finishing it`() {
        // The default rung, InstallMode.NORMAL: no root, no Shizuku, so this is where most installs
        // land — and it copied each entry with a bare copyTo. A single-layer DEFLATE bomb goes to
        // ~1032:1, so a 100 MB pick wrote ~100 GB into /data/app/vmdl<id>.tmp until write() hit
        // ENOSPC, taking every other app's and system_server's writes down with it.
        val zip = zipOf("base.apk" to ByteArray(1024 * 1024))
        val session = RecordingSession()

        val refusal = assertThrows(InstallRefusedException::class.java) {
            ZipFile(zip).use { zf ->
                writeEntriesWithinBudget(
                    zip = zf,
                    entries = selectEntriesToWrite(zf.entries().asSequence(), setOf("base.apk")),
                    budget = 4096L,
                    openSink = session::openSink
                )
            }
        }

        assertTrue(
            "the refusal has to name what was refused: ${refusal.message}",
            refusal.message!!.contains("base.apk")
        )
        // Refused, not truncated: the copy stops at the budget instead of running to the entry's
        // full expanded size, and the caller abandons the session rather than committing what fit.
        assertTrue(session.sinks["base.apk"]!!.size() < 1024 * 1024)
    }

    @Test
    fun `the budget is spent across the whole set, not granted afresh per entry`() {
        // A bundle installs as a set, so a bomb split behind an innocent base has to stop the whole
        // thing — and the budget the first entry consumed has to still be gone when the second is
        // opened, or an archive gets one budget per entry just by adding entries.
        val zip = zipOf("base.apk" to ByteArray(3000), "split_config.xxhdpi.apk" to ByteArray(3000))
        val session = RecordingSession()

        assertThrows(InstallRefusedException::class.java) {
            ZipFile(zip).use { zf ->
                writeEntriesWithinBudget(
                    zip = zf,
                    entries = selectEntriesToWrite(
                        zf.entries().asSequence(),
                        setOf("base.apk", "split_config.xxhdpi.apk")
                    ),
                    budget = 4096L,
                    openSink = session::openSink
                )
            }
        }

        // base.apk fitted, which is exactly why the second entry must not get 4096 bytes of its own.
        assertEquals(3000, session.sinks["base.apk"]!!.size())
        assertTrue(session.sinks.containsKey("split_config.xxhdpi.apk"))
    }

    @Test
    fun `an entry declaring more than may ever be written gets no preallocation hint`() {
        // openWrite's length argument comes from the archive's central directory — a claim by
        // whoever built it — and the platform preallocates against it. Handing on a size Thor has
        // already decided it will not write would fail the install on an allocation instead of on
        // the budget; -1 is what openWrite documents as "unknown".
        val zip = zipOf("base.apk" to ByteArray(64), "split_config.xxhdpi.apk" to ByteArray(1024 * 1024))
        val session = RecordingSession()

        assertThrows(InstallRefusedException::class.java) {
            ZipFile(zip).use { zf ->
                writeEntriesWithinBudget(
                    zip = zf,
                    entries = selectEntriesToWrite(
                        zf.entries().asSequence(),
                        setOf("base.apk", "split_config.xxhdpi.apk")
                    ),
                    budget = 8192L,
                    openSink = session::openSink
                )
            }
        }

        // The honest, in-budget entry keeps its real declared size; the oversized one is opened
        // with -1 rather than with a megabyte the budget was never going to allow.
        assertEquals(64L, session.declaredLengths.first { it.first == "base.apk" }.second)
        assertEquals(
            -1L,
            session.declaredLengths.first { it.first == "split_config.xxhdpi.apk" }.second
        )
    }

    @Test
    fun `an empty entry list writes nothing and refuses nothing`() {
        // A property of the copier alone: handed nothing, it writes nothing rather than treating
        // an empty budget spend as an error. Deciding whether an empty selection is *allowed* is
        // not its job — that is selectEntriesToWriteOrRefuse's, three tests down, and the answer
        // there is no.
        val zip = zipOf("readme.txt" to ByteArray(16))
        val session = RecordingSession()

        val written = ZipFile(zip).use { zf ->
            writeEntriesWithinBudget(
                zip = zf,
                entries = emptyList(),
                budget = 4096L,
                openSink = session::openSink
            )
        }

        assertEquals(0L, written)
        assertTrue(session.sinks.isEmpty())
    }

    // ---- selectEntriesToWriteOrRefuse ----------------------------------------------------

    @Test
    fun `a resolved install set that yields no writable entry refuses instead of falling through`() {
        // The regression: `filesWritten = toWrite.isNotEmpty()` conflated "this input is
        // monolithic" with "a bundle resolved and every entry was dropped". The second fell into
        // the monolithic branch and streamed the CONTAINER archive as base.apk — a file that by
        // construction is not the one the sheet's identity was read from, which is the substitution
        // the whole plan exists to close. The archive named files it cannot supply; that is a
        // verdict about the archive, not a licence to install something else.
        val zip = zipOf("evil\\base.apk" to ByteArray(16), "manifest.json" to ByteArray(8))

        val refusal = assertThrows(InstallRefusedException::class.java) {
            ZipFile(zip).use { zf ->
                selectEntriesToWriteOrRefuse(zf.entries().asSequence(), setOf("evil\\base.apk"))
            }
        }

        assertTrue(
            "the refusal has to name what was refused: ${refusal.message}",
            refusal.message!!.contains("evil\\base.apk")
        )
    }

    @Test
    fun `a bundle missing one of its splits refuses rather than installing the rest`() {
        // Same rule as BundleZip.extractEntries applies to the privileged rungs. Half a bundle is
        // not a smaller install, it is a different one — and this is the rung most users land on.
        val zip = zipOf("base.apk" to ByteArray(16))

        val refusal = assertThrows(InstallRefusedException::class.java) {
            ZipFile(zip).use { zf ->
                selectEntriesToWriteOrRefuse(
                    zf.entries().asSequence(),
                    setOf("base.apk", "split_config.xxhdpi.apk")
                )
            }
        }

        assertTrue(refusal.message!!.contains("split_config.xxhdpi.apk"))
        // Only the missing name is named; the one that was there is not part of the complaint.
        assertFalse(refusal.message!!.contains("base.apk"))
    }

    @Test
    fun `a complete selection passes through untouched`() {
        // The check must not cost the ordinary case anything: same list, same order.
        val zip = zipOf(
            "icon.png" to ByteArray(4),
            "base.apk" to ByteArray(16),
            "split_config.arm64_v8a.apk" to ByteArray(16),
        )

        val selected = ZipFile(zip).use { zf ->
            selectEntriesToWriteOrRefuse(
                zf.entries().asSequence(),
                setOf("base.apk", "split_config.arm64_v8a.apk")
            ).map { it.name }
        }

        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk"), selected)
    }

    // ---- integrityGuardedInstall ---------------------------------------------------------

    @Test
    fun `the hash check runs before pm install, not after`() {
        val expected = "aa".repeat(32)
        val script = integrityGuardedInstall(
            listOf("/sdcard/Android/data/x/base.apk" to expected),
            "pm install -r -g '/sdcard/Android/data/x/base.apk'"
        )

        val guardAt = script.indexOf("sha256sum")
        val installAt = script.indexOf("pm install")
        assertTrue(guardAt >= 0)
        assertTrue(installAt >= 0)
        assertTrue("the guard must precede the install", guardAt < installAt)
        assertTrue(script.contains(expected))
        assertTrue(script.contains("exit $INTEGRITY_CHECK_EXIT_CODE"))
    }

    @Test
    fun `every staged split is checked, not just the first`() {
        // install-multiple takes the whole set; guarding only base.apk would leave the splits —
        // which carry code too — swappable.
        val digests = listOf(
            "/sdcard/x/base.apk" to "11".repeat(32),
            "/sdcard/x/split_a.apk" to "22".repeat(32),
            "/sdcard/x/split_b.apk" to "33".repeat(32),
        )

        val script = integrityGuardedInstall(digests, "pm install-multiple -r -g /sdcard/x/base.apk")

        assertEquals(3, Regex("sha256sum").findAll(script).count())
        digests.forEach { (path, expected) ->
            assertTrue(script.contains(expected))
            assertTrue(script.contains("'$path'"))
        }
        // One abort per check: a mismatch on the third split has to stop the script before the
        // install command, exactly as a mismatch on the first does.
        assertEquals(3, Regex("exit $INTEGRITY_CHECK_EXIT_CODE").findAll(script).count())
    }

    @Test
    fun `a path holding a quote cannot end the guard's own argument`() {
        // The staging dir name is ours, but the file names inside come from the picked archive.
        val script = integrityGuardedInstall(
            listOf("/sdcard/x/it's;rm -rf /.apk" to "ff".repeat(32)),
            "pm install -r -g x"
        )

        assertTrue(script.contains("""'/sdcard/x/it'\''s;rm -rf /.apk'"""))
        // The raw path never appears: if it did, the apostrophe would have closed the guard's
        // quoting and left `;rm -rf /` as a command of its own.
        assertFalse(script.contains("/sdcard/x/it's;rm"))
    }

    @Test
    fun `an empty digest list is refused rather than silently unguarded`() {
        // A future caller that forgets to hash what it staged must not get a bare `pm install`
        // back; the throw is caught upstream and drops the rung.
        assertThrows(IllegalArgumentException::class.java) {
            integrityGuardedInstall(emptyList(), "pm install -r -g /sdcard/x/base.apk")
        }
    }

    // ---- selectEntriesToWrite ------------------------------------------------------------

    @Test
    fun `only the wanted entries are written, in archive order`() {
        val entries = sequenceOf(
            ZipEntry("icon.png"),
            ZipEntry("base.apk"),
            ZipEntry("manifest.json"),
            ZipEntry("split_config.arm64_v8a.apk"),
        )

        val selected = selectEntriesToWrite(entries, setOf("base.apk", "split_config.arm64_v8a.apk"))

        assertEquals(listOf("base.apk", "split_config.arm64_v8a.apk"), selected.map { it.name })
    }

    @Test
    fun `a wanted name repeated in the archive is written once`() {
        // Two entries claiming the same session file name make openWrite overwrite the first with
        // the second — the install would ship whichever the attacker put last.
        val entries = sequenceOf(
            ZipEntry("base.apk"),
            ZipEntry("BASE.APK"),
            ZipEntry("nested/base.apk"),
        )

        val selected = selectEntriesToWrite(entries, setOf("base.apk"))

        assertEquals(listOf("base.apk"), selected.map { it.name })
    }

    @Test
    fun `a directory entry is never written`() {
        val entries = sequenceOf(ZipEntry("base.apk/"), ZipEntry("base.apk"))

        val selected = selectEntriesToWrite(entries, setOf("base.apk"))

        assertEquals(1, selected.size)
        assertFalse(selected.single().isDirectory)
    }

    @Test
    fun `an entry whose leaf name is a traversal is dropped, not handed to openWrite`() {
        // openWrite("..") throws IllegalArgumentException, which fails the entire install rather
        // than the one malicious entry — so the archive gets to choose whether Thor can install
        // anything at all.
        val entries = sequenceOf(
            ZipEntry("payload/.."),
            ZipEntry("base.apk"),
        )

        val selected = selectEntriesToWrite(entries, setOf("base.apk", ".."))

        assertEquals(listOf("base.apk"), selected.map { it.name })
    }

    @Test
    fun `an entry nested in a directory is matched by its leaf name`() {
        // Real bundles from APKPure and SAI put the APKs at the root, but some nest them; the
        // pre-existing matching is on the base name and this pins it.
        val entries = sequenceOf(ZipEntry("apks/base.apk"))

        val selected = selectEntriesToWrite(entries, setOf("base.apk"))

        assertEquals(listOf("apks/base.apk"), selected.map { it.name })
    }

    @Test
    fun `an archive with none of the wanted names selects nothing`() {
        val entries = sequenceOf(ZipEntry("readme.txt"), ZipEntry("icon.png"))

        assertTrue(selectEntriesToWrite(entries, setOf("base.apk")).isEmpty())
    }
}

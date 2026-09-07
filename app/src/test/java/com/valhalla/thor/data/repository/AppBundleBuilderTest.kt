// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What goes into the zip, in what order, and under what name.
 *
 * `zipFiles` derived every entry name from `file.name`, which is right for the flat APK/sidecar
 * layout and wrong for `Android/obb/<pkg>/main.obb`. [zipSourcesFor] is the seam that separates the
 * two decisions, and it is pure, so the ordering rule is checkable without a device.
 *
 * `stagedApkNames`'s coverage stays in `StagedApkNamesTest`; this class is about names the file
 * cannot supply.
 */
class AppBundleBuilderTest {

    @Test
    fun `xapk puts the sidecars first and the expansions last`() {
        // Sidecar-first is not cosmetic: an installer that streams the archive reads manifest.json
        // before it has to decide what to do with anything else. Expansions go last because they
        // are the largest entries and the least urgent to reach.
        val sources = zipSourcesFor(
            format = BundleFormat.XAPK,
            apkFiles = listOf(File("/tmp/base.apk"), File("/tmp/split_a.apk")),
            sidecars = listOf(File("/tmp/manifest.json"), File("/tmp/icon.png")),
            expansions = listOf(
                ZipSource(File("/tmp/staged/main.obb"), "Android/obb/com.example.game/main.obb")
            )
        )

        assertEquals(
            listOf(
                "manifest.json",
                "icon.png",
                "base.apk",
                "split_a.apk",
                "Android/obb/com.example.game/main.obb"
            ),
            sources.map { it.entryName }
        )
    }

    @Test
    fun `apks keeps apks first and carries no expansions`() {
        // .apks is SAI's format and has no expansion convention. Passing some in is a caller bug,
        // and dropping them beats writing entries no reader will look for.
        val sources = zipSourcesFor(
            format = BundleFormat.APKS,
            apkFiles = listOf(File("/tmp/base.apk")),
            sidecars = listOf(File("/tmp/meta.sai_v2.json")),
            expansions = listOf(
                ZipSource(File("/tmp/staged/main.obb"), "Android/obb/com.example.game/main.obb")
            )
        )

        assertEquals(listOf("base.apk", "meta.sai_v2.json"), sources.map { it.entryName })
    }

    @Test
    fun `a plain file keeps its own name as the entry name`() {
        val sources = zipSourcesFor(
            BundleFormat.XAPK,
            apkFiles = listOf(File("/tmp/staging/base.apk")),
            sidecars = emptyList(),
            expansions = emptyList()
        )

        assertEquals(listOf("base.apk"), sources.map { it.entryName })
        assertEquals(listOf(File("/tmp/staging/base.apk")), sources.map { it.file })
    }

    @Test
    fun `the copy command quotes both paths and refuses a hostile leaf`() {
        val command = obbCopyCommand(
            externalStorageDir = "/storage/emulated/0",
            packageName = "com.example.game",
            leaf = "main.12.com.example.game.obb",
            destPath = "/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb/main.obb"
        )!!

        assertTrue(
            command,
            command.contains("'/storage/emulated/0/Android/obb/com.example.game/main.12.com.example.game.obb'")
        )
        assertTrue(
            command,
            command.contains("'/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb/main.obb'")
        )

        assertNull(
            obbCopyCommand("/storage/emulated/0", "com.example.game", "../../evil.obb", "/tmp/x")
        )
        assertNull(
            obbCopyCommand("/storage/emulated/0", "com.example.game", "main.obb", "/tmp/it's")
        )
        assertNull(
            obbCopyCommand("/storage/emulated/0", "bad;name", "main.obb", "/tmp/x")
        )
    }

    @Test
    fun `the copy refuses a source that is a symlink`() {
        val command = obbCopyCommand(
            externalStorageDir = "/storage/emulated/0",
            packageName = "com.example.game",
            leaf = "main.obb",
            destPath = "/tmp/x/main.obb"
        )!!

        // The probe rejects a symlinked expansion too, but that is a check-then-use across two shell
        // invocations into a directory the exported app owns. `cp` follows links, and following one
        // here reads a file with the shell's privilege and writes its bytes into the user's archive
        // labelled as game data.
        // Both components: `-L` tests only a path's final one, so a link at `<pkg>` redirects the
        // read exactly as well as a link at the leaf while passing a leaf-aimed test.
        val dir = "/storage/emulated/0/Android/obb/com.example.game"
        assertTrue(
            command,
            command.startsWith("[ ! -L '$dir' ] && [ ! -L '$dir/main.obb' ] && cp -f ")
        )
    }

    @Test
    fun `an unreadable probe packs nothing instead of refusing the export`() {
        // The owner's regression test. `build` used to throw IOException on Undetermined and the
        // export sheet disabled the .xapk chip to match, so "Thor could not read Android/obb" —
        // which for ~70% of apps means "there was nothing there to read" — came back as a failed
        // export. Undetermined now packs nothing and the export proceeds.
        assertEquals(
            emptyList<ObbFile>(),
            expansionsToPack(BundleFormat.XAPK, ObbProbe.Undetermined("no privileged shell"))
        )

        // The other side of the same boundary, in the same test: if this arm ever also returned
        // empty, the assertion above would still pass while the feature packed no expansions at
        // all. Present is the only verdict that can name files, and it must still name them.
        val files = listOf(ObbFile("main.1.com.example.game.obb", 4L), ObbFile("patch.1.obb", 8L))
        assertEquals(
            files,
            expansionsToPack(BundleFormat.XAPK, ObbProbe.Present(files, otherEntryCount = 0))
        )
    }

    @Test
    fun `nothing is packed for a probe that found nothing or a format that carries nothing`() {
        // None is a real measurement, and it means the same list as Undetermined by a different
        // route. Pinned separately so that collapsing the two arms is a visible edit.
        assertEquals(emptyList<ObbFile>(), expansionsToPack(BundleFormat.XAPK, ObbProbe.None))

        // .apks has no expansion convention and zipSourcesFor already drops them; this makes the
        // rule true a step earlier, so an .apks export never stages bytes it will not ship. Present
        // deliberately, because APKS + None would pass on the wrong arm.
        val files = listOf(ObbFile("main.1.com.example.game.obb", 4L))
        assertEquals(
            emptyList<ObbFile>(),
            expansionsToPack(BundleFormat.APKS, ObbProbe.Present(files, otherEntryCount = 0))
        )
    }

    @Test
    fun `a copy that fails after the probe measured files is still fatal`() {
        // This is GH#164 and it did NOT get relaxed. Present means the probe read those names and
        // sizes off the device, so a null from stageExpansions is data we know we are dropping —
        // as opposed to Undetermined, which is data we cannot prove exists.
        val requested = listOf(ObbFile("main.1.com.example.game.obb", 4L))

        val thrown = assertThrows(IOException::class.java) {
            requireStagedExpansions(requested = requested, staged = null)
        }
        // The message, not just the type: an IOException from somewhere else in this call would
        // satisfy a type-only assertion while this guard was gone.
        assertEquals(
            "this app's game data could not be read, so the .xapk would be incomplete",
            thrown.message
        )
    }

    @Test
    fun `staged expansions pass through, and nothing requested needs nothing staged`() {
        // The passing side of the guard above. Without it, `throw` unconditionally would be green.
        val staged =
            listOf(ZipSource(File("/tmp/s/main.obb"), "Android/obb/com.example.game/main.obb"))
        assertEquals(
            staged,
            requireStagedExpansions(listOf(ObbFile("main.obb", 4L)), staged)
        )

        // And the short-circuit: an app with no expansions reaches this with null from a staging
        // step that was never run. Throwing there would fail every ordinary .xapk export.
        assertEquals(emptyList<ZipSource>(), requireStagedExpansions(emptyList(), null))
    }

    @Test
    fun `expansions are declared with the entry name as the install path`() {
        // What a third-party installer reads. file == install_path is the shape the reference
        // installers assume, and it also means a manifest-blind installer that scans for *.obb
        // entries lands them in the right place by accident.
        val declared = expansionDescriptors(
            listOf(
                ZipSource(File("/tmp/a"), "Android/obb/com.example.game/main.obb"),
                ZipSource(File("/tmp/b"), "Android/obb/com.example.game/patch.obb")
            )
        )

        assertEquals(
            listOf(
                "Android/obb/com.example.game/main.obb",
                "Android/obb/com.example.game/patch.obb"
            ),
            declared.map { it.file }
        )
        assertEquals(declared.map { it.file }, declared.map { it.installPath })
        assertTrue(declared.all { it.installLocation == "EXTERNAL_STORAGE" })
    }

    @Test
    fun `direct copy reports verified bytes only after they are written`() = runTest {
        val root = Files.createTempDirectory("bundle_copy_progress_").toFile()
        try {
            val source = File(root, "source.apk").apply {
                writeBytes(ByteArray(20_000) { (it % 251).toByte() })
            }
            val destination = File(root, "destination.apk")
            var reported = 0L

            copyFileWithVerifiedProgress(
                source,
                destination,
                VerifiedProgress { bytes ->
                    reported += bytes
                    assertTrue(destination.length() >= reported)
                },
            )

            assertEquals(source.length(), reported)
            assertEquals(source.readBytes().toList(), destination.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip reports every source byte after writing it`() = runTest {
        val root = Files.createTempDirectory("bundle_zip_progress_").toFile()
        try {
            val first = File(root, "base.apk").apply { writeBytes(ByteArray(12_000) { 1 }) }
            val second = File(root, "split.apk").apply { writeBytes(ByteArray(7_000) { 2 }) }
            val output = File(root, "bundle.apks")
            var reported = 0L

            zipFilesWithVerifiedProgress(
                sources = listOf(ZipSource(first, first.name), ZipSource(second, second.name)),
                zipFile = output,
                progress = VerifiedProgress { bytes ->
                    assertTrue(output.length() > 0L)
                    reported += bytes
                },
            )

            assertEquals(first.length() + second.length(), reported)
            assertTrue(output.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `root export work receives a deadline below the ten minute lease`() {
        assertEquals(
            EXPORT_ROOT_COMMAND_TIMEOUT,
            PrivilegeExecutionContext().withExportRootDeadline().commandTimeout,
        )
        assertTrue(EXPORT_ROOT_COMMAND_TIMEOUT < 10.minutes)
        assertEquals(
            1.minutes,
            PrivilegeExecutionContext(commandTimeout = 1.minutes)
                .withExportRootDeadline()
                .commandTimeout,
        )
    }

    @Test
    fun `successful OBB probe checkpoints before the first copy starts`() = runTest {
        val events = mutableListOf<String>()
        val probe = probeObbWithVerifiedBoundary(
            operation = {
                events += "probe"
                ObbProbe.Present(listOf(ObbFile("main.obb", 4L)), otherEntryCount = 0)
            },
            boundary = VerifiedOperationBoundary { events += "checkpoint" },
        )

        events += "copy"

        assertTrue(probe is ObbProbe.Present)
        assertEquals(listOf("probe", "checkpoint", "copy"), events)
    }

    @Test
    fun `each successful root copy checkpoints before the next file side effect`() = runTest {
        val events = mutableListOf<String>()
        val boundary = VerifiedOperationBoundary { events += "checkpoint" }

        val first = executeRootCommandWithVerifiedBoundary(
            operation = {
                events += "obb-copy-1"
                Result.success(0 to null)
            },
            boundary = boundary,
        )
        events += "verify-1"
        val second = executeRootCommandWithVerifiedBoundary(
            operation = {
                events += "obb-copy-2"
                Result.success(0 to null)
            },
            boundary = boundary,
        )
        events += "verify-2"
        val fallback = executeRootCopyWithVerifiedBoundary(
            operation = {
                events += "apk-copy"
                Result.success(Unit)
            },
            boundary = boundary,
        )
        events += "verify-apk"

        assertEquals(0, first?.first)
        assertEquals(0, second?.first)
        assertTrue(fallback)
        assertEquals(
            listOf(
                "obb-copy-1", "checkpoint", "verify-1",
                "obb-copy-2", "checkpoint", "verify-2",
                "apk-copy", "checkpoint", "verify-apk",
            ),
            events,
        )
    }

    @Test
    fun `failed root operations do not checkpoint successful boundaries`() = runTest {
        var boundaries = 0
        val boundary = VerifiedOperationBoundary { boundaries++ }

        val failedCommand = executeRootCommandWithVerifiedBoundary(
            operation = { Result.success(1 to "copy failed") },
            boundary = boundary,
        )
        val failedCopy = executeRootCopyWithVerifiedBoundary(
            operation = { Result.failure(IOException("no root")) },
            boundary = boundary,
        )
        val undeterminedProbe = probeObbWithVerifiedBoundary(
            operation = { ObbProbe.Undetermined("no root") },
            boundary = boundary,
        )

        assertEquals(1, failedCommand?.first)
        assertFalse(failedCopy)
        assertTrue(undeterminedProbe is ObbProbe.Undetermined)
        assertEquals(0, boundaries)
    }

    @Test
    fun `root operation cancellation remains cancellation without a boundary`() {
        val cancellation = CancellationException("stop root copy")
        var boundaries = 0

        val thrown = assertThrows(CancellationException::class.java) {
            runTest {
                executeRootCopyWithVerifiedBoundary(
                    operation = { Result.failure(cancellation) },
                    boundary = VerifiedOperationBoundary { boundaries++ },
                )
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(0, boundaries)
    }

    @Test
    fun `root copy progress is reported only for the expected complete byte count`() {
        val root = Files.createTempDirectory("bundle_root_progress_").toFile()
        try {
            val destination = File(root, "copied.obb").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

            assertEquals(4L, verifiedRootCopyByteCount(destination, expectedBytes = 4L))
            assertNull(verifiedRootCopyByteCount(destination, expectedBytes = 5L))
            assertNull(verifiedRootCopyByteCount(destination, expectedBytes = 0L))
        } finally {
            root.deleteRecursively()
        }
    }
}

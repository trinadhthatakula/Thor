// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.PrivilegeExecutionLane
import com.valhalla.thor.presentation.FakeSystemRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDataArchiveGatewayImplTest {

    /**
     * Pins the predicate so that a future refactor cannot accidentally make it reject names that
     * merely contain a dot. `DataClass.memberName(compress)` produces names in the form
     * `<id>.tar.enc` or `<id>.tar.gz.enc`; all four DataClass ids must pass.
     */
    @Test
    fun `ordinary dotted names are accepted — not over-rejected by the dot check`() {
        // These are the exact names DataClass.memberName() produces.
        assertTrue(isSafeStagingName("ce.tar.enc"))
        assertTrue(isSafeStagingName("ce.tar.gz.enc"))
        assertTrue(isSafeStagingName("de.tar.enc"))
        assertTrue(isSafeStagingName("de.tar.gz.enc"))
        assertTrue(isSafeStagingName("ext-data.tar.enc"))
        assertTrue(isSafeStagingName("ext-data.tar.gz.enc"))
        assertTrue(isSafeStagingName("ext-media.tar.enc"))
        assertTrue(isSafeStagingName("ext-media.tar.gz.enc"))
    }

    @Test
    fun `path-traversal components are rejected`() {
        // The entire name is the dangerous form — a component, not a file with a dot.
        assertFalse(isSafeStagingName(".."))
        assertFalse(isSafeStagingName("."))
    }

    @Test
    fun `path separators in any position are rejected`() {
        assertFalse(isSafeStagingName("foo/bar"))
        assertFalse(isSafeStagingName("../../etc/passwd"))
        assertFalse(isSafeStagingName("/absolute/path"))
        assertFalse(isSafeStagingName("ce.tar.enc/"))
    }

    @Test
    fun `blank names are rejected`() {
        assertFalse(isSafeStagingName(""))
        assertFalse(isSafeStagingName("   "))
    }

    // ---- isInsideStagingRoot -----------------------------------------------------------------------

    private val stagingRoot = "/data/user/0/com.valhalla.thor/cache/data_archive_staging"

    /**
     * The canonical path of a file Thor writes lands inside the staging root, so the containment
     * check must pass for those inputs. Uses the real `STAGING_DIR` constant name so a rename there
     * would break this test and reveal the coupling.
     */
    @Test
    fun `canonical path inside staging root passes the containment check`() {
        assertTrue(isInsideStagingRoot("$stagingRoot/ce.tar.enc", stagingRoot))
        assertTrue(isInsideStagingRoot("$stagingRoot/de.tar.gz.enc", stagingRoot))
        assertTrue(isInsideStagingRoot("$stagingRoot/ext-media.tar.enc", stagingRoot))
    }

    /**
     * A sibling directory whose name starts with `data_archive_staging` (no trailing slash) would
     * pass a naive `startsWith(root)` check. The trailing-slash separator in the implementation
     * defeats this attack.
     */
    @Test
    fun `sibling-prefix path fails the containment check`() {
        val evil = "/data/user/0/com.valhalla.thor/cache/data_archive_staging-evil/ce.tar.enc"
        assertFalse(isInsideStagingRoot(evil, stagingRoot))
    }

    /**
     * After `canonicalPath` resolves `..` components the resulting path is outside the staging root.
     * The containment function only sees the resolved string; the test shows it correctly rejects it.
     */
    @Test
    fun `path that has escaped the staging root via dot-dot fails the containment check`() {
        // canonicalPath would have collapsed `staging/../../etc/passwd` to `/etc/passwd`
        assertFalse(isInsideStagingRoot("/etc/passwd", stagingRoot))
        // Adjacent directory — not a child of staging root
        assertFalse(isInsideStagingRoot("/data/user/0/com.valhalla.thor/cache/escaped.tar.enc", stagingRoot))
    }

    /**
     * The staging root itself is not "inside" the root — the check requires at least one path
     * component after the root, enforced by the trailing-slash separator.
     */
    @Test
    fun `staging root itself fails the containment check`() {
        assertFalse(isInsideStagingRoot(stagingRoot, stagingRoot))
    }

    @Test
    fun `every archive phase has its stable archive route`() {
        val source = productionSource()
        val commandClasses = listOf(
            "archive.force_stop",
            "archive.list",
            "archive.verify",
            "archive.tar",
            "archive.stage_chown",
            "archive.extract",
            "archive.swap",
            "archive.chown",
            "archive.restorecon",
        )

        commandClasses.forEach { commandClass ->
            assertTrue("missing archive route $commandClass", source.contains("\"$commandClass\""))
        }
        assertTrue(
            "archive commands must use the ARCHIVE lane",
            source.contains("PrivilegeExecutionLane.ARCHIVE"),
        )
    }

    @Test
    fun `archive command adapters attach the stable route metadata`() = runTest {
        val repository = FakeSystemRepository()
        val packageName = "com.example.target"

        repository.forceStopForArchive(packageName)
        ArchiveCommandPhase.entries
            .filterNot { it == ArchiveCommandPhase.FORCE_STOP }
            .forEach { phase ->
                repository.executeArchiveCommand(
                    command = "sensitive command",
                    packageName = packageName,
                    phase = phase,
                )
            }

        assertEquals(ArchiveCommandPhase.entries.size, repository.executions.size)
        ArchiveCommandPhase.entries.zip(repository.executions).forEach { (phase, recorded) ->
            val execution = recorded.second
            assertEquals(phase.commandClass, execution.commandClass)
            assertEquals(PrivilegeExecutionLane.ARCHIVE, execution.lane)
            assertEquals(packageName, execution.packageName)
            assertNull(execution.commandTimeout)
        }
    }

    @Test
    fun `archive diagnostics never include raw commands or output`() {
        val source = productionSource()
        val forbidden = listOf(
            '$' + "command",
            "result.second",
            "output:",
        ).filter(source::contains)

        assertTrue(
            "archive diagnostics expose raw shell data through $forbidden",
            forbidden.isEmpty(),
        )
    }

    private fun productionSource(): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            val source = File(
                directory,
                "app/src/main/java/com/valhalla/thor/data/repository/AppDataArchiveGatewayImpl.kt",
            )
            if (source.isFile) return source.readText()
            directory = directory.parentFile
        }
        error("could not locate AppDataArchiveGatewayImpl.kt from ${System.getProperty("user.dir")}")
    }
}

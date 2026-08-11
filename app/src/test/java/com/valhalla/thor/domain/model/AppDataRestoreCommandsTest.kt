// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destructive half. Every one of these commands runs as root against a real app's data directory,
 * so the same rule as `ObbPlacementTest` applies: an input that cannot be quoted produces **no
 * command**, not a quoted-and-hoped-for-the-best one.
 */
class AppDataRestoreCommandsTest {

    private val root = "/data/user/0/com.example.app"

    @Test
    fun `the staging directory is a hidden child of the class root`() {
        assertEquals("$root/.thorbak-staging", stagingDirPath(root))
    }

    @Test
    fun `extraction creates the staging directory and extracts into it`() {
        val command = extractCommand(root, "/data/data/com.valhalla.thor/cache/x/ce.tar", compressed = true)!!

        assertTrue(command, command.contains("mkdir -p '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("-C '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("'/data/data/com.valhalla.thor/cache/x/ce.tar'"))
    }

    @Test
    fun `extraction uses the flags matching how the member was written`() {
        assertTrue(extractCommand(root, "/tmp/a.tar", compressed = true)!!.contains("-xzf"))
        assertTrue(extractCommand(root, "/tmp/a.tar", compressed = false)!!.contains("-xf"))
    }

    @Test
    fun `extraction refuses a staging directory that is a symlink`() {
        // `mkdir -p` succeeds silently on a symlink to a directory, and the extraction would then land
        // wherever it points — with root's privilege, from a path the target app owns.
        val command = extractCommand(root, "/tmp/a.tar", compressed = true)!!

        assertTrue(command, command.contains("[ ! -L '$root/.thorbak-staging' ]"))
    }

    @Test
    fun `the swap deletes every entry in the class root except the staging directory`() {
        // The one thing a naive `rm -rf <root>/*` destroys is the staging directory holding the data
        // being restored — mid-restore, after the original is already gone.
        val command = swapStagedEntriesCommand(root)!!

        assertTrue(command, command.contains("! -name '.thorbak-staging'"))
        assertTrue(command, command.contains("-mindepth 1"))
        assertTrue(command, command.contains("-maxdepth 1"))
    }

    @Test
    fun `the name the swap protects is exactly the name the extraction creates`() {
        // Two string literals that must agree. If they drift, the swap deletes the staged data and the
        // restore reports success over an empty directory.
        val extract = extractCommand(root, "/tmp/a.tar", compressed = true)!!
        val swap = swapStagedEntriesCommand(root)!!

        assertTrue(extract.contains("'$root/$STAGING_DIR_NAME'"))
        assertTrue(swap.contains("! -name '$STAGING_DIR_NAME'"))
    }

    @Test
    fun `the swap moves entries with find rather than a glob`() {
        // A shell glob does not match dotfiles, and app data is full of them — `.config`, `.cache`,
        // per-library dot directories. `mv <staging>/* <root>/` silently leaves every one behind.
        val command = swapStagedEntriesCommand(root)!!

        assertTrue(command, command.contains("find '$root/.thorbak-staging'"))
        assertTrue(command, command.contains("mv"))
    }

    @Test
    fun `the swap removes the staging directory when it is done`() {
        assertTrue(swapStagedEntriesCommand(root)!!.contains("rmdir '$root/.thorbak-staging'"))
    }

    @Test
    fun `chown is recursive and applies one id to both owner and group`() {
        assertEquals("chown -R 10123:10123 '$root'", chownRecursiveCommand(root, 10123))
    }

    @Test
    fun `restorecon is recursive and forced`() {
        // -F, not just -R: without the force, an already-labelled file keeps whatever context it was
        // extracted with, and the app still cannot read it. Omitting this is the most common reason a
        // restore "succeeds" and the app crashes on launch.
        assertEquals("restorecon -RF '$root'", restoreconCommand(root))
    }

    @Test
    fun `every restore command refuses a root that is not a quotable absolute path`() {
        val hostile = listOf("/data/user/0/it's", "relative/path", "/data\n/user", "")

        hostile.forEach { bad ->
            assertNull(bad, stagingDirPath(bad))
            assertNull(bad, extractCommand(bad, "/tmp/a.tar", compressed = true))
            assertNull(bad, swapStagedEntriesCommand(bad))
            assertNull(bad, chownRecursiveCommand(bad, 10123))
            assertNull(bad, restoreconCommand(bad))
        }
    }

    @Test
    fun `extraction refuses a tar path that is not a quotable absolute path`() {
        assertNull(extractCommand(root, "cache/a.tar", compressed = true))
        assertNull(extractCommand(root, "/tmp/it's.tar", compressed = true))
        assertNull(extractCommand(root, "", compressed = true))
    }

    @Test
    fun `chown refuses a negative uid`() {
        // `appUid` returns null for a missing package; a caller that turned that into -1 would emit
        // `chown -R -1:-1`, which some toybox builds accept as an option.
        assertNull(chownRecursiveCommand(root, -1))
    }
}

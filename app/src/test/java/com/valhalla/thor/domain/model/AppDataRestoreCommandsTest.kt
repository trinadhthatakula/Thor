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
    fun `extract command for a compressed member is exactly right`() {
        // Exact equality pins the ordering (mkdir, symlink guard, member-name check, then extract)
        // and the member-name grep pattern. The pattern refuses absolute paths (^/) and any member
        // whose name contains ".." as a path component, making extraction unable to write outside
        // the staging directory regardless of which tar implementation the ROM supplies.
        val tarPath = "/data/data/com.valhalla.thor/cache/x/ce.tar"
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "mkdir -p '$staging' && [ ! -L '$staging' ] && " +
                "! tar -tf '$tarPath' | grep -qE '^/|^\\.\\./|/\\.\\./|/\\.\\.\$' && " +
                "tar -xzf '$tarPath' -C '$staging'",
            extractCommand(root, tarPath, compressed = true),
        )
    }

    @Test
    fun `extract command for an uncompressed member is exactly right`() {
        val tarPath = "/tmp/a.tar"
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "mkdir -p '$staging' && [ ! -L '$staging' ] && " +
                "! tar -tf '$tarPath' | grep -qE '^/|^\\.\\./|/\\.\\./|/\\.\\.\$' && " +
                "tar -xf '$tarPath' -C '$staging'",
            extractCommand(root, tarPath, compressed = false),
        )
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
    fun `swap command is exactly right`() {
        // Exact equality pins the ordering — non-empty guard, then delete, then move, then rmdir.
        // Reversing the rm and mv stages destroys the data the restoration just extracted. A contains
        // check passes even with the stages reversed, because the depth flags appear in both find
        // invocations and the rm/mv substrings float freely.
        val staging = "$root/.thorbak-staging"
        assertEquals(
            "[ -n \"\$(ls -A '$staging')\" ] && " +
                "find '$root' -mindepth 1 -maxdepth 1 ! -name '.thorbak-staging' -exec rm -rf {} + && " +
                "find '$staging' -mindepth 1 -maxdepth 1 -exec mv -f {} '$root/' \\; && " +
                "rmdir '$staging'",
            swapStagedEntriesCommand(root),
        )
    }

    @Test
    fun `chown is recursive, does not follow symlinks, and applies one id to both owner and group`() {
        // -h: without it, toybox's chown follows a symlink planted in the restored tree and hands
        // another app's data directory to the restored uid.
        assertEquals("chown -Rh 10123:10123 '$root'", chownRecursiveCommand(root, 10123))
    }

    @Test
    fun `restorecon is recursive and forced`() {
        // -F, not just -R: without the force, an already-labelled file keeps whatever context it was
        // extracted with, and the app still cannot read it. Omitting this is the most common reason a
        // restore "succeeds" and the app crashes on launch.
        assertEquals("restorecon -RF '$root'", restoreconCommand(root))
    }

    @Test
    fun `every restore command refuses a root that is not quotable or is not normalised`() {
        // The last four inputs pass isQuotableAbsolutePath but are rejected by isNormalisedRoot:
        // "/" and a single-segment path have too few segments; the "../" path climbs out of the
        // data directory via a ".." component.
        val hostile = listOf(
            "/data/user/0/it's",
            "relative/path",
            "/data\n/user",
            "",
            "/",
            "/data",
            "/data/user/0/com.example.app/../../../system",
        )

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
        // `chown -Rh -1:-1`, which some toybox builds accept as an option.
        assertNull(chownRecursiveCommand(root, -1))
    }
}

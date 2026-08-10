// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The install side's shell surface, kept pure so it can be checked without a device.
 *
 * `ObbInstaller` itself is not JVM-testable — it needs a `Context` and
 * `Environment.getExternalStorageDirectory()` — so the decisions worth locking down are hoisted out
 * of it into these two builders, the same shape [obbCopyCommand] takes on the export side. That
 * matters most for the package name: it reaches a root shell here as a *directory being created*,
 * and the review of Task 7 asked for that to be validated at the site rather than inherited from
 * `resolveExpansions` having refused it earlier in the call chain.
 */
class ObbPlacementTest {

    private val root = "/storage/emulated/0"
    private val pkg = "com.example.game"

    @Test
    fun `the destination is the platform's own OBB directory for the installed package`() {
        assertEquals("$root/Android/obb/$pkg", obbDestinationDir(root, pkg))
    }

    @Test
    fun `the mkdir command quotes the destination and refuses a symlinked directory`() {
        val command = obbMkdirCommand(root, pkg)!!

        val dir = "/storage/emulated/0/Android/obb/com.example.game"
        // `mkdir -p` succeeds silently when the path is a symlink to a directory, and every
        // placement after it would then land wherever that link points — from a path the target app
        // owns, written with the shell's privilege.
        assertEquals("mkdir -p '$dir' && [ ! -L '$dir' ]", command)
    }

    @Test
    fun `the copy unlinks the destination before writing it`() {
        val command = obbPlaceCommand(root, pkg, "main.obb", "/tmp/main.obb", 1L)!!
        val dest = "/storage/emulated/0/Android/obb/com.example.game/main.obb"

        // `cp -f` unlinks only when the *open* fails, so an existing symlink at the destination is
        // followed — an arbitrary root write, plus an arbitrary chmod, into whatever it names. `rm`
        // does not follow links, so it removes the link rather than the target.
        assertTrue(command, command.startsWith("rm -f '$dest' && cp -f "))
    }

    @Test
    fun `the copy command quotes both paths, fixes the mode and verifies the size`() {
        val command = obbPlaceCommand(
            externalStorageDir = root,
            packageName = pkg,
            leaf = "main.12.com.example.game.obb",
            sourcePath = "/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb_in/x/main.12.com.example.game.obb",
            expectedBytes = 1048576L
        )!!

        assertTrue(
            command,
            command.contains(
                "'/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb_in/x/main.12.com.example.game.obb'"
            )
        )
        assertTrue(
            command,
            command.contains("'/storage/emulated/0/Android/obb/com.example.game/main.12.com.example.game.obb'")
        )
        // The shell creates the file, so it is the shell's uid that owns it; the game reads it as
        // its own uid.
        assertTrue(command, command.contains("chmod 644"))
        // `cp` can exit 0 having written short on a full volume, and Thor cannot stat the
        // destination itself from API 30 on — so the size is checked inside the same invocation.
        assertTrue(command, command.contains("1048576"))
    }

    @Test
    fun `a copy whose size cannot be measured on this device is still accepted`() {
        // The size check must not become a new hard dependency on `stat`: a device whose shell has
        // no usable `stat -c` would otherwise report every placement as failed while the bytes were
        // in fact in place, and "the game data could not be placed" is the one message this feature
        // must not produce falsely.
        val command = obbPlaceCommand(root, pkg, "main.obb", "/tmp/main.obb", 64L)!!

        assertTrue(command, command.contains("2>/dev/null"))
        assertTrue(command, command.contains("-z"))
    }

    @Test
    fun `an unusable package name yields no command at all`() {
        // Validated here, not inherited. Both of these reach a root shell as part of `mkdir -p`.
        assertNull(obbMkdirCommand(root, "com.example.game; rm -rf /"))
        assertNull(obbMkdirCommand(root, "../../data/local/tmp"))
        assertNull(obbMkdirCommand(root, ""))
        assertNull(obbPlaceCommand(root, "com.example.game; rm -rf /", "main.obb", "/tmp/a", 1L))
        assertNull(obbDestinationDir(root, "com..example"))
    }

    @Test
    fun `an external storage root that is not a quotable absolute path yields no command`() {
        assertNull(obbMkdirCommand("/storage/emu'lated/0", pkg))
        assertNull(obbMkdirCommand("/storage/emulated\n/0", pkg))
        assertNull(obbMkdirCommand("storage/emulated/0", pkg))
        assertNull(obbMkdirCommand("", pkg))
        assertNull(obbPlaceCommand("/storage/emu'lated/0", pkg, "main.obb", "/tmp/a", 1L))
    }

    @Test
    fun `a leaf Thor will not create yields no copy command`() {
        assertNull(obbPlaceCommand(root, pkg, "../../evil.obb", "/tmp/a", 1L))
        assertNull(obbPlaceCommand(root, pkg, "main.txt", "/tmp/a", 1L))
        assertNull(obbPlaceCommand(root, pkg, "it's.obb", "/tmp/a", 1L))
        assertNull(obbPlaceCommand(root, pkg, "", "/tmp/a", 1L))
    }

    @Test
    fun `a source path that is not a quotable absolute path yields no copy command`() {
        assertNull(obbPlaceCommand(root, pkg, "main.obb", "relative/main.obb", 1L))
        assertNull(obbPlaceCommand(root, pkg, "main.obb", "/tmp/it's/main.obb", 1L))
        assertNull(obbPlaceCommand(root, pkg, "main.obb", "/tmp/a\nb", 1L))
    }

    @Test
    fun `a negative expected size yields no copy command`() {
        // The only way to reach this is a File.length() of -1 on a file that vanished mid-install;
        // interpolating it would compare the copied size against the string "-1" and always fail.
        assertNull(obbPlaceCommand(root, pkg, "main.obb", "/tmp/main.obb", -1L))
    }

    @Test
    fun `the copy lands inside the directory the mkdir creates`() {
        val dir = obbDestinationDir(root, pkg)!!

        assertTrue(obbMkdirCommand(root, pkg)!!.contains("'$dir'"))
        assertTrue(
            obbPlaceCommand(root, pkg, "main.obb", "/tmp/main.obb", 1L)!!.contains("'$dir/main.obb'")
        )
    }
}

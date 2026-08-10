// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObbExpansionsTest {

    private val pkg = "com.example.game"

    private fun declared(vararg paths: String) =
        paths.map { XapkExpansionInfo(file = it, installPath = it) }

    @Test
    fun `the entry name is the install path, and both are the canonical OBB location`() {
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            expansionEntryName(pkg, "main.12.com.example.game.obb")
        )
    }

    @Test
    fun `a well-formed manifest resolves`() {
        val resolved = resolveExpansions(
            packageName = pkg,
            declared = declared("Android/obb/com.example.game/main.obb"),
            entryNames = listOf("manifest.json", "base.apk", "Android/obb/com.example.game/main.obb")
        )

        assertEquals(
            listOf(
                ResolvedExpansion(
                    entryName = "Android/obb/com.example.game/main.obb",
                    leafName = "main.obb"
                )
            ),
            resolved
        )
    }

    @Test
    fun `a traversal in install_path is dropped`() {
        // The first code in Thor that writes to a path taken from an archive. Everything else in
        // the installer is flat by construction (isSafeEntryFileName refuses separators outright),
        // so zip-slip stops being theoretical here.
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo(
                    file = "Android/obb/com.example.game/main.obb",
                    installPath = "Android/obb/com.example.game/../../../data/local/tmp/evil.obb"
                )
            ),
            listOf("Android/obb/com.example.game/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an install_path for a different package is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.other.app/main.obb"),
            listOf("Android/obb/com.other.app/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an absolute install_path is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("/sdcard/Android/obb/com.example.game/main.obb"),
            listOf("/sdcard/Android/obb/com.example.game/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a non-obb extension is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/payload.so"),
            listOf("Android/obb/com.example.game/payload.so")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a nested install_path below the package dir is dropped`() {
        // The platform's own OBB layout is flat. Allowing depth here would mean creating
        // directories from archive-controlled names for no gain.
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/sub/main.obb"),
            listOf("Android/obb/com.example.game/sub/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a declared expansion with no matching zip entry is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/main.obb"),
            listOf("manifest.json", "base.apk")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `two expansions landing on the same leaf keep only the first`() {
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo("a/main.obb", "Android/obb/com.example.game/main.obb"),
                XapkExpansionInfo("b/main.obb", "Android/obb/com.example.game/main.obb")
            ),
            listOf("a/main.obb", "b/main.obb")
        )

        assertEquals(listOf("a/main.obb"), resolved.map { it.entryName })
    }

    @Test
    fun `an entry name may differ from the install path, because they are separate wire fields`() {
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo(
                    file = "obb/main.obb",
                    installPath = "Android/obb/com.example.game/main.obb"
                )
            ),
            listOf("obb/main.obb")
        )

        assertEquals(
            listOf(ResolvedExpansion("obb/main.obb", "main.obb")),
            resolved
        )
    }

    @Test
    fun `a manifest-free archive falls back to any depth-correct obb entry`() {
        // The reference installer does this, and APKPure archives in the wild omit the expansions
        // block while still carrying the files. Declaring nothing must not mean losing the data.
        val resolved = resolveExpansions(
            pkg,
            declared = emptyList(),
            entryNames = listOf(
                "manifest.json",
                "base.apk",
                "Android/obb/com.example.game/main.obb",
                "Android/obb/com.other.app/main.obb"
            )
        )

        assertEquals(
            listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
            resolved
        )
    }

    @Test
    fun `an unusable package name resolves nothing at all`() {
        assertTrue(
            resolveExpansions(
                "com.example.game; rm -rf /",
                declared("Android/obb/com.example.game/main.obb"),
                listOf("Android/obb/com.example.game/main.obb")
            ).isEmpty()
        )
    }

    @Test
    fun `leaf names that are not plain file names are refused`() {
        assertTrue(isSafeObbLeafName("main.12.com.example.game.obb"))
        assertFalse(isSafeObbLeafName(""))
        assertFalse(isSafeObbLeafName("."))
        assertFalse(isSafeObbLeafName(".."))
        assertFalse(isSafeObbLeafName("a/b.obb"))
        assertFalse(isSafeObbLeafName("a\\b.obb"))
        assertFalse(isSafeObbLeafName("main.obb "))
        assertFalse(isSafeObbLeafName("main.obb\n"))
        assertFalse(isSafeObbLeafName("main.txt"))
    }

    @Test
    fun `packing needs two copies of the apks but only one of the obb`() {
        // The APKs are copied out of /data/app into the staging dir and then deflated into the
        // final zip, so both exist at once. The OBB is streamed from external storage straight
        // into the zip, so only the zip's copy lands on internal storage... except that Thor
        // cannot read another package's OBB directly, so it stages there too.
        assertEquals(
            BundleSpace(internalBytes = 2 * 100L + 50L, externalBytes = 50L),
            bundleSpaceRequirement(apkBytes = 100L, obbBytes = 50L)
        )
    }

    @Test
    fun `no obb means no external requirement`() {
        assertEquals(
            BundleSpace(internalBytes = 200L, externalBytes = 0L),
            bundleSpaceRequirement(apkBytes = 100L, obbBytes = 0L)
        )
    }

    @Test
    fun `shortfall is zero when both volumes have room`() {
        val need = bundleSpaceRequirement(100L, 50L)

        assertEquals(0L, spaceShortfall(need, internalFree = 1000L, externalFree = 1000L, sameVolume = false))
    }

    @Test
    fun `shortfall reports the larger gap when both volumes are short`() {
        val need = BundleSpace(internalBytes = 1000L, externalBytes = 500L)

        assertEquals(
            900L,
            spaceShortfall(need, internalFree = 100L, externalFree = 400L, sameVolume = false)
        )
    }

    @Test
    fun `on a single-volume device the two requirements are summed, not maxed`() {
        // Most phones emulate external storage on the data partition, so "internal free" and
        // "external free" are the same bytes reported twice. Checking them independently there
        // passes a device that then runs out mid-copy.
        val need = BundleSpace(internalBytes = 600L, externalBytes = 600L)

        assertEquals(0L, spaceShortfall(need, 1000L, 1000L, sameVolume = false))
        assertEquals(200L, spaceShortfall(need, 1000L, 1000L, sameVolume = true))
    }

    // ---------------------------------------------------------------------------------------------
    // Past the plan's table. Each of the seven below is an input shape the drafted rules left open;
    // two of them the drafted implementation did not survive.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a leaf carrying a single quote is refused, because both directions shell-interpolate it`() {
        // Not a path-shape rule but a shell-injection rule, and it belongs here because this is the
        // one definition both directions share.
        //
        // Pack side: the copy command is `cp -f '<externalStorageDir>/Android/obb/<pkg>/<leaf>' ...`
        // and the guard beside it covers the storage dir and the destination — the leaf goes in
        // unchecked. Install side: the destination is `'<destDir>/<leaf>'`. Either way a leaf holding
        // a `'` closes the quote and the remainder of the name runs as a command, under root.
        //
        // The leaf is untrusted in *both* directions. Packing takes it from `stat` on the target
        // app's own `Android/obb/<pkg>/`, which that app may write to with no permission at all — so
        // any installed app can choose bytes Thor hands to a root shell. Installing takes it from a
        // downloaded archive.
        assertFalse(isSafeObbLeafName("main'.obb"))
        assertFalse(isSafeObbLeafName("main'; id > /sdcard/pwned; echo '.obb"))

        assertTrue(
            resolveExpansions(
                pkg,
                declared("Android/obb/com.example.game/main'; id > /sdcard/pwned; echo '.obb"),
                listOf("Android/obb/com.example.game/main'; id > /sdcard/pwned; echo '.obb")
            ).isEmpty()
        )
    }

    @Test
    fun `a control character inside the leaf is refused, not only a trailing one`() {
        // A NUL truncates the name at the syscall boundary, so what the filesystem creates stops
        // matching what was validated. The plan's table covered only a *trailing* newline, which the
        // `.obb`-suffix rule catches on its own — an interior one it never reaches.
        assertFalse(isSafeObbLeafName("main${Char(0)}.obb"))
        assertFalse(isSafeObbLeafName("ma\tin.obb"))
        assertFalse(isSafeObbLeafName("ma\rin.obb"))

        // An interior *space*, though, is a legal file name and has to stay legal: the probe splits
        // on the first space only so that `main 1.obb` survives (see ObbProbeParserTest).
        assertTrue(isSafeObbLeafName("main 1.obb"))
    }

    @Test
    fun `a backslash traversal in install_path is dropped`() {
        // Mixed separators: the prefix matches with forward slashes and the escape is attempted with
        // backslashes, on the bet that only one of the two is checked.
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/..\\..\\evil.obb"),
            listOf("Android/obb/com.example.game/..\\..\\evil.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `two expansions differing only in leaf case keep only the first`() {
        // Rule 5 exists so a later declaration cannot overwrite an earlier extraction. The volumes
        // this feature writes to are case-insensitive — emulated external storage, and any FAT or
        // exFAT card — so a case-only difference names the same file there, and a case-sensitive
        // dedup waves the second one through to overwrite the first anyway.
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo("a/main.obb", "Android/obb/com.example.game/main.obb"),
                XapkExpansionInfo("b/main.obb", "Android/obb/com.example.game/MAIN.OBB")
            ),
            listOf("a/main.obb", "b/main.obb")
        )

        assertEquals(listOf(ResolvedExpansion("a/main.obb", "main.obb")), resolved)
    }

    @Test
    fun `a declaration without an install_path is dropped rather than falling back to file`() {
        // `installPath` is nullable because the wire input is hostile. Guessing `file` in its place
        // would accept an entry path as a destination, and the two are separate fields precisely
        // because an archive may set them differently.
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo(
                    file = "Android/obb/com.example.game/main.obb",
                    installPath = null
                )
            ),
            listOf("Android/obb/com.example.game/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a manifest that declares only unusable expansions does not re-enable the scan`() {
        // The fallback is for archives that declare *nothing*. A manifest that declares something is
        // authoritative, so an archive must not be able to buy a permissive scan by declaring one
        // rejected entry alongside the files it wants picked up regardless.
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.other.app/main.obb"),
            listOf(
                "Android/obb/com.other.app/main.obb",
                "Android/obb/com.example.game/main.obb"
            )
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an install_path naming the package directory itself is dropped`() {
        // Empty leaf — and, in the fallback, a plain directory entry. `File(outDir, "")` is `outDir`,
        // so an unguarded extraction would try to write over the staging directory.
        assertTrue(
            resolveExpansions(
                pkg,
                declared("Android/obb/com.example.game/"),
                listOf("Android/obb/com.example.game/")
            ).isEmpty()
        )
        assertTrue(
            resolveExpansions(
                pkg,
                declared = emptyList(),
                entryNames = listOf("Android/obb/com.example.game/")
            ).isEmpty()
        )
    }
}

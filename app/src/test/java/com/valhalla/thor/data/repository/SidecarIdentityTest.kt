// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The package name a *sidecar* declares is the one string in the analyzer that nothing has
 * validated: `manifest.json` inside a `.xapk` handed to an exported Activity by whichever app
 * fired the ACTION_VIEW intent. It flows into the installed-app lookup, the downgrade verdict,
 * and — before this — straight into a `File()` path.
 *
 * Two independent locks, tested independently: the name is rejected, and even a name that got
 * through could not name a file.
 */
class SidecarIdentityTest {

    private val temp = mutableListOf<File>()

    private fun tempDir(): File =
        Files.createTempDirectory("sidecar_icons_").toFile().also { temp.add(it) }

    @After
    fun tearDown() {
        temp.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a real package name is accepted`() {
        assertTrue(isValidSidecarPackageName("com.amazon.mShop.android.shopping"))
        assertTrue(isValidSidecarPackageName("com.valhalla.thor"))
        assertTrue(isValidSidecarPackageName("a"))
        assertTrue(isValidSidecarPackageName("com.example.app2"))
    }

    @Test
    fun `a traversal dressed as a package name is rejected`() {
        // The reported payload: PNG bytes land in /data/user/0/com.valhalla.thor/databases/,
        // and overwriting the Room file yields SQLiteDatabaseCorruptException on next open.
        assertFalse(isValidSidecarPackageName("../../databases/thor_database"))
        assertFalse(isValidSidecarPackageName("../files/secret"))
        assertFalse(isValidSidecarPackageName(".."))
        assertFalse(isValidSidecarPackageName("."))
    }

    @Test
    fun `a name carrying a path separator is rejected`() {
        assertFalse(isValidSidecarPackageName("com.example/app"))
        assertFalse(isValidSidecarPackageName("com.example\\app"))
        assertFalse(isValidSidecarPackageName("/etc/passwd"))
    }

    @Test
    fun `an empty or blank name is rejected`() {
        assertFalse(isValidSidecarPackageName(null))
        assertFalse(isValidSidecarPackageName(""))
        assertFalse(isValidSidecarPackageName("   "))
    }

    @Test
    fun `a name carrying shell or glob metacharacters is rejected`() {
        // The same string reaches the privilege gateways as a `pm` argument.
        assertFalse(isValidSidecarPackageName("com.example;rm -rf /"))
        assertFalse(isValidSidecarPackageName("com.example\$(id)"))
        assertFalse(isValidSidecarPackageName("com.example*"))
        assertFalse(isValidSidecarPackageName("com.example\nnext"))
    }

    @Test
    fun `the icon cache key never carries the package name into the path`() {
        // Second lock: even granting a caller-supplied name past validation, what reaches
        // File() is hex. java.io.File does not normalise `..` — the syscall does — so the only
        // safe answer is for the untrusted string not to be in the name at all.
        val key = iconCacheKey("../../databases/thor_database", 5L)

        assertFalse(key.contains("/"))
        assertFalse(key.contains(".."))
        assertTrue(key.matches(Regex("^[0-9a-f]{32}_5$")))

        val dir = tempDir()
        val dest = File(dir, "$key.png")
        assertEquals(dir, dest.parentFile)
        assertEquals(dir.canonicalFile, dest.canonicalFile.parentFile)
    }

    @Test
    fun `the icon cache key still busts Coil's per-path cache on a version bump`() {
        // The property the old `${packageName}_${versionCode}` key existed for: Coil keys its
        // File cache by path only, so one path per package would serve a stale icon forever.
        val v1 = iconCacheKey("com.example.app", 1L)
        val v2 = iconCacheKey("com.example.app", 2L)
        assertNotEquals(v1, v2)

        // Same package + same version is the same slot (that is what makes it a cache).
        assertEquals(v1, iconCacheKey("com.example.app", 1L))

        // Different packages do not share a slot.
        assertNotEquals(v1, iconCacheKey("com.example.other", 1L))

        // An unknown version code keeps its own slot rather than colliding with a genuine 0.
        assertNotEquals(
            iconCacheKey("com.example.app", null),
            iconCacheKey("com.example.app", 0L)
        )
    }

    // ---- resolveBundlePlan: the identity and the install set agree ------------------------

    @Test
    fun `the app that is described is drawn from the files that get installed`() {
        // The split that made this necessary: the analyzer chose an identity from every `.apk` in
        // the archive, the installer chose an install set from the manifest's split list — which
        // deliberately drops standalone APKs it did not name. An archive holding a real, correctly
        // signed base.apk beside a manifest naming only payload.apk got one answer from each side.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "icon.png", "base.apk", "payload.apk"),
            manifestSplitFiles = listOf("payload.apk"),
            manifestBaseFile = null,
            packageName = "com.example.app"
        )

        assertEquals(listOf("payload.apk"), plan.installSet)
        // base.apk is the better-looking base by every naming heuristic, and it is still not
        // offered: nothing installs it, so nothing may be identified by it.
        assertEquals(listOf("payload.apk"), plan.identityCandidates)
    }

    @Test
    fun `a manifest cannot flag a base it then leaves out of the install set`() {
        // The same attack with the manifest being explicit rather than merely selective. An
        // `id == "base"` pointing at a file that survives no install is describing something
        // nobody runs, and that discrepancy is the payload, not a hint worth following.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "base.apk", "payload.apk"),
            manifestSplitFiles = listOf("payload.apk"),
            manifestBaseFile = "base.apk",
            packageName = "com.example.app"
        )

        assertEquals(listOf("payload.apk"), plan.identityCandidates)
    }

    @Test
    fun `a normal XAPK still leads with the base its manifest declares`() {
        // The common case must not change shape: APKPure writes `{package}.apk` plus config
        // splits and flags the base explicitly, and that flag is still the first thing tried.
        val plan = resolveBundlePlan(
            entryNames = listOf(
                "manifest.json",
                "icon.png",
                "com.example.app.apk",
                "config.arm64_v8a.apk",
                "config.xxhdpi.apk",
            ),
            manifestSplitFiles = listOf(
                "com.example.app.apk",
                "config.arm64_v8a.apk",
                "config.xxhdpi.apk",
            ),
            manifestBaseFile = "com.example.app.apk",
            packageName = "com.example.app"
        )

        assertEquals(
            listOf("com.example.app.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk"),
            plan.installSet
        )
        assertEquals("com.example.app.apk", plan.identityCandidates.first())
        // Offered once, not twice, even though the manifest flag and the name heuristic agree.
        assertEquals(plan.identityCandidates.distinct(), plan.identityCandidates)
    }

    @Test
    fun `the install set keeps the manifest's order while the identity list is base-first`() {
        // Two different jobs off one list: `install-multiple` gets the order the producer wrote
        // (a config split may legitimately come first), while the parse-until-it-works loop has
        // to start at the entry most likely to BE the app.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "config.arm64_v8a.apk", "base.apk"),
            manifestSplitFiles = listOf("config.arm64_v8a.apk", "base.apk"),
            manifestBaseFile = null,
            packageName = null
        )

        assertEquals(listOf("config.arm64_v8a.apk", "base.apk"), plan.installSet)
        assertEquals(listOf("base.apk", "config.arm64_v8a.apk"), plan.identityCandidates)
    }

    @Test
    fun `no identity candidate ever names a file outside the install set`() {
        // The invariant the whole type exists to hold, asserted on an archive built to break it:
        // a foreign universal.apk, a split the manifest forgot, and a manifest base that is not
        // in its own split list.
        val plan = resolveBundlePlan(
            entryNames = listOf(
                "manifest.json",
                "universal.apk",
                "base.apk",
                "split_config.xxhdpi.apk",
                "split_config.en.apk",
            ),
            manifestSplitFiles = listOf("base.apk", "split_config.xxhdpi.apk"),
            manifestBaseFile = "universal.apk",
            packageName = "com.example.app"
        )

        val installable = plan.installSet.map { it.substringAfterLast('/').lowercase() }.toSet()
        plan.identityCandidates.forEach {
            assertTrue(
                "$it is offered as the app's identity but is never installed",
                it.substringAfterLast('/').lowercase() in installable
            )
        }
        // The omitted *split* is picked up (dropping it would install a broken app); the foreign
        // standalone APK is not (it would be a second base and fail install-multiple outright).
        assertTrue(plan.installSet.contains("split_config.en.apk"))
        assertFalse(plan.installSet.contains("universal.apk"))
    }

    @Test
    fun `an archive with no APK entries plans nothing, which is how both sides call it monolithic`() {
        // The reported attack on the analyzer: append a manifest.json and an icon.png to somebody
        // else's genuine, signed APK. The sidecar used to win outright, so the sheet showed the
        // attacker's label, package name, version and permissions while the install shipped the
        // real thing. An empty plan is what routes both sides back to the platform parser on the
        // whole file — the one reader an attacker's JSON cannot talk to.
        val plan = resolveBundlePlan(
            entryNames = listOf(
                "AndroidManifest.xml",
                "classes.dex",
                "resources.arsc",
                "manifest.json",
                "icon.png",
            ),
            manifestSplitFiles = emptyList(),
            manifestBaseFile = null,
            packageName = "com.attacker.claim"
        )

        assertTrue(plan.installSet.isEmpty())
        assertTrue(plan.identityCandidates.isEmpty())
    }

    @Test
    fun `an entry whose leaf no writer would accept is in neither list`() {
        // The door this branch left open after closing the first one. Both sides read one plan,
        // but the plan still carried names only one side could act on: `good\base.apk` stayed in
        // the identity candidates — where extractEntryTo happily read it, because a backslash is
        // nothing special on Linux — and was dropped by every writer, which refuses anything that
        // is not a plain leaf. Same archive, two answers again, through a different door.
        //
        // Dropped from BOTH lists, so `payload.apk` is what the sheet describes and what `pm` gets.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "good\\base.apk", "payload.apk"),
            manifestSplitFiles = null,
            manifestBaseFile = null,
            packageName = "com.example.app"
        )

        assertEquals(listOf("payload.apk"), plan.installSet)
        assertEquals(listOf("payload.apk"), plan.identityCandidates)
    }

    @Test
    fun `no plan of any shape offers a name a writer would refuse`() {
        // Every route a name can take into the plan, in one archive: the entry scan, the manifest's
        // split list, and the manifest's explicit `id == "base"` flag. `..` is the traversal case
        // (java.io.File does not normalise it; the syscall does) and the backslash is the
        // separator-on-the-other-host-platform case.
        val plan = resolveBundlePlan(
            entryNames = listOf(
                "manifest.json",
                "base.apk",
                "evil\\split_config.arm64_v8a.apk",
                "nested/..",
                "split_config.xxhdpi.apk",
            ),
            manifestSplitFiles = listOf("base.apk", "split_config.xxhdpi.apk"),
            manifestBaseFile = "evil\\split_config.arm64_v8a.apk",
            packageName = "com.example.app"
        )

        (plan.installSet + plan.identityCandidates).forEach {
            assertTrue(
                "$it reached the plan but no writer would accept it",
                isSafeEntryFileName(it.substringAfterLast('/'))
            )
        }
        assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), plan.installSet)
        assertEquals(listOf("base.apk", "split_config.xxhdpi.apk"), plan.identityCandidates)
    }

    @Test
    fun `a manifest split list naming an unusable entry is discarded, never shortened`() {
        // The manifest's own strings are untrusted too, and resolveBundleInstallSet returns them
        // verbatim. They inherit the leaf guarantee from the `available` check — a name that was
        // filtered out of the entries is not available, so the split list reads as stale and is
        // dropped whole. Silently shortening it would be the truncation the budget case refuses.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "base.apk", "evil\\payload.apk"),
            manifestSplitFiles = listOf("base.apk", "evil\\payload.apk"),
            manifestBaseFile = "base.apk",
            packageName = "com.example.app"
        )

        assertEquals(listOf("base.apk"), plan.installSet)
        assertEquals(listOf("base.apk"), plan.identityCandidates)
    }

    @Test
    fun `an archive whose only APK entries are unusable plans nothing, as monolithic does`() {
        // The empty plan is not a dead end, it is the agreed signal: AppAnalyzerImpl parses the
        // whole file only when `plan.installSet.isEmpty()`, and resolveInstallSetFromFile answers
        // null on the same condition and streams the whole file. Both sides land on the same bytes.
        val plan = resolveBundlePlan(
            entryNames = listOf("manifest.json", "icon.png", "evil\\base.apk"),
            manifestSplitFiles = null,
            manifestBaseFile = null,
            packageName = "com.example.app"
        )

        assertTrue(plan.installSet.isEmpty())
        assertTrue(plan.identityCandidates.isEmpty())
    }

    @Test
    fun `a sidecar naming splits the archive does not contain cannot invent an install set`() {
        // Same archive, but the manifest now declares splits to look more like a bundle. They do
        // not exist, so the split list is discarded as stale and the entry scan finds no `.apk`
        // either — the plan stays empty rather than resolving to names nothing can extract.
        val plan = resolveBundlePlan(
            entryNames = listOf("AndroidManifest.xml", "classes.dex", "manifest.json"),
            manifestSplitFiles = listOf("base.apk", "split_config.arm64_v8a.apk"),
            manifestBaseFile = "base.apk",
            packageName = "com.attacker.claim"
        )

        assertTrue(plan.installSet.isEmpty())
        assertTrue(plan.identityCandidates.isEmpty())
    }
}

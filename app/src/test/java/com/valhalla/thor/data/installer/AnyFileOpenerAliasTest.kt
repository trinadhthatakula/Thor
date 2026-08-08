// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the two hand-maintained halves of one component name to each other.
 *
 * `AnyFileOpenerManager` computes the alias's class name in Kotlin; `AndroidManifest.xml` declares
 * it in XML. Nothing links them. If they drift, the failure is silent until a user opens Settings
 * and flips the switch, at which point `setComponentEnabledSetting` throws `IllegalArgumentException`
 * on a component that exists in no build — and the only symptom is a file manager that still refuses
 * to offer Thor, which is the bug the switch exists to fix.
 *
 * The drift is not hypothetical. The first version of the manager built the class name by appending
 * to `context.packageName`, which is wrong on every debug build: the `debug` build type sets
 * `applicationIdSuffix = ".debug"`, so `packageName` is `com.valhalla.thor.debug` while a relative
 * `android:name` resolves against the **namespace**, which no suffix touches. That produced
 * `com.valhalla.thor.debug.presentation.installer.AnyFileInstallerAlias` — a name that appears in no
 * manifest anywhere. This test is the thing that would have caught it.
 *
 * ### What it proves, and what it does not
 *
 * It proves the two spellings agree and that the alias ships disabled. It cannot prove the alias
 * resolves on a device, that the filter matches anything, or that flipping it changes what a file
 * manager offers — all three need hardware. Those belong to the on-device diagnostic that GH#161
 * still wants.
 *
 * ### Anti-vacuity
 *
 * A manifest sweep that reads nothing passes green, so [the sweep read a real manifest] asserts the
 * file was found and contains the unrelated landmarks it should, and the extraction below throws
 * rather than returning a default when the attribute is missing.
 */
class AnyFileOpenerAliasTest {

    @Test
    fun `the sweep read a real manifest`() {
        val xml = manifestText
        assertTrue("manifest is implausibly short: ${xml.length} chars", xml.length > 2000)
        // Landmarks unrelated to the alias — if these are gone this is not Thor's manifest and
        // every other assertion here is meaningless rather than passing.
        assertTrue(
            "manifest does not declare PortableInstallerActivity",
            xml.contains(".presentation.installer.PortableInstallerActivity")
        )
        assertTrue(
            "manifest does not declare the application element",
            xml.contains("<application")
        )
    }

    @Test
    fun `manifest alias name matches the name the manager will ask PackageManager for`() {
        val declared = aliasAttribute("android:name")
        // The manifest writes it relative (leading dot); the manager resolves it against the
        // namespace. Compare on the resolved form, which is what ComponentName actually carries.
        val resolved =
            if (declared.startsWith(".")) NAMESPACE + declared else declared
        assertEquals(
            "AndroidManifest.xml and AnyFileOpenerManager.ALIAS_CLASS_NAME disagree",
            resolved,
            AnyFileOpenerManager.ALIAS_CLASS_NAME
        )
    }

    @Test
    fun `the alias ships disabled`() {
        assertEquals(
            "the any-file filter must ship off — it is opt-in by decision, see " +
                "docs/follow-ups/161-apks-not-openable-from-file-managers.md",
            "false",
            aliasAttribute("android:enabled")
        )
    }

    @Test
    fun `the alias points at the installer activity`() {
        val target = aliasAttribute("android:targetActivity")
        val resolved = if (target.startsWith(".")) NAMESPACE + target else target
        assertEquals(
            "$NAMESPACE.presentation.installer.PortableInstallerActivity",
            resolved
        )
    }

    @Test
    fun `the typeless filter lives only on the alias, never on the activity`() {
        // The whole point of the alias is that this filter is not reachable while it is off. If it
        // were also left on the always-enabled activity, the switch would be decorative.
        val body = manifestText.substringAfter("<activity-alias").substringBefore("</activity-alias>")
        assertTrue("alias lost its content:// scheme", body.contains("""android:scheme="content""""))
        assertTrue("alias lost its file:// scheme", body.contains("""android:scheme="file""""))

        val beforeAlias = manifestText.substringBefore("<activity-alias")
        // Every <data> on the activity must carry a mimeType or a path matcher. A bare scheme-only
        // <data> there is the typeless filter escaping back onto the always-on component.
        val activityFilters = Regex("""<intent-filter>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(beforeAlias)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("found no intent-filters on the activity — extraction is broken", activityFilters.isNotEmpty())
        activityFilters.forEach { filter ->
            assertTrue(
                "an intent-filter on the activity has neither a mimeType nor a path matcher, " +
                    "which makes it a second always-on typeless filter:\n$filter",
                filter.contains("android:mimeType") ||
                    filter.contains("android:path") ||
                    // The launcher/MAIN-style filters carry no <data> at all and are not at issue.
                    !filter.contains("<data")
            )
        }
    }

    // -- machinery ----------------------------------------------------------------------------

    /**
     * Reads the attribute off the single `<activity-alias>` block, throwing rather than defaulting.
     *
     * A missing attribute returning `""` would let [the alias ships disabled] pass on a manifest
     * where the alias had been deleted outright, which is the worst possible outcome for this file.
     */
    private fun aliasAttribute(name: String): String {
        val block = manifestText.substringAfter("<activity-alias", missingDelimiterValue = "")
        assertTrue("AndroidManifest.xml declares no <activity-alias>", block.isNotEmpty())
        val header = block.substringBefore(">")
        val match = Regex("""\Q$name\E\s*=\s*"([^"]*)"""").find(header)
            ?: throw AssertionError("<activity-alias> has no $name; header was:\n$header")
        return match.groupValues[1]
    }

    /**
     * `<module>/src/main/AndroidManifest.xml`, found by walking up from wherever the runner started
     * — the same approach, and for the same reason, as `ObserverCallSitesTest.mainSourceRoot`:
     * Gradle sets the working directory to `<repo>/app`, but Android Studio and `--tests` runs have
     * both been seen starting a level up. Throws with everything it tried rather than returning a
     * plausible missing path, which would turn this whole class green and silent.
     */
    private val manifestText: String by lazy {
        val marker = "src/main/AndroidManifest.xml"
        val tried = mutableListOf<String>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        var hops = 0
        while (hops < 8) {
            val here = dir ?: break
            for (candidate in listOf(File(here, marker), File(here, "app/$marker"))) {
                if (candidate.isFile) return@lazy candidate.readText()
                tried += candidate.path
            }
            dir = here.parentFile
            hops++
        }

        throw AssertionError(
            "could not locate $marker from ${System.getProperty("user.dir")}; tried:\n" +
                tried.joinToString("\n")
        )
    }

    private companion object {
        /** `android.namespace` in app/build.gradle.kts — what a relative android:name resolves against. */
        const val NAMESPACE = "com.valhalla.thor"
    }
}

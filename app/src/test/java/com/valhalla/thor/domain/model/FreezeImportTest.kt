// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Freezer's "import disabled apps" candidate rule. Pure list logic, no Android deps.
 *
 * This filter had no test while it lived inline in `FreezerScreen`, which is exactly why a clause
 * that skipped every system app survived for a month without anyone noticing.
 */
class FreezeImportTest {

    /**
     * `enabled` and `isInstalled` are not independent on a real device: `AppInfoMapper` and
     * `AppRepositoryImpl` both compute `enabled` as `ApplicationInfo.enabled && FLAG_INSTALLED`,
     * so a package that is not installed for this user can never read as enabled. This helper
     * enforces that here so no test can assert against a state the mapper cannot produce.
     */
    private fun app(
        packageName: String,
        isSystem: Boolean = false,
        disabled: Boolean = true,
        installedForUser: Boolean = true,
        bloatRecommendation: String? = null,
        uadLoadFailed: Boolean = false,
    ) = AppInfo(
        packageName = packageName,
        isSystem = isSystem,
        enabled = !disabled && installedForUser,
        isInstalled = installedForUser,
        bloatRecommendation = bloatRecommendation,
        isUadLoadFailed = uadLoadFailed,
    )

    @Test
    fun `a disabled user app that is not tracked is offered`() {
        val apps = listOf(app("com.user.frozen"))
        assertEquals(
            listOf("com.user.frozen"),
            importableDisabledApps(apps, emptySet()).map { it.packageName }
        )
    }

    @Test
    fun `an enabled app is never offered`() {
        val apps = listOf(app("com.user.running", disabled = false))
        assertEquals(emptyList<String>(), importableDisabledApps(apps, emptySet()).map { it.packageName })
    }

    @Test
    fun `an app already on the watchlist is not offered`() {
        val apps = listOf(app("com.user.frozen"))
        assertEquals(
            emptyList<String>(),
            importableDisabledApps(apps, setOf("com.user.frozen")).map { it.packageName }
        )
    }

    @Test
    fun `a disabled system app is offered`() {
        // The regression this filter was rewritten for. Freezing a system app now leaves it
        // installed-but-disabled wherever the platform allows disabling, and the old `!isSystem`
        // clause dropped precisely that app from the import prompt.
        val apps = listOf(app("com.system.frozen", isSystem = true))
        assertEquals(
            listOf("com.system.frozen"),
            importableDisabledApps(apps, emptySet()).map { it.packageName }
        )
    }

    @Test
    fun `an unsafe system app is not offered`() {
        // Importing a BLOCKED app would be a one-way door: Unfreeze-all enables it, and
        // freezableCandidates then refuses to ever freeze it again.
        val apps = listOf(
            app("com.system.unsafe", isSystem = true, bloatRecommendation = "Unsafe")
        )
        assertEquals(emptyList<String>(), importableDisabledApps(apps, emptySet()).map { it.packageName })
    }

    @Test
    fun `the unsafe recommendation is matched regardless of case`() {
        // uad_lists.json capitalises the recommendation; freezeTierOf lowercases before comparing.
        val apps = listOf(
            app("com.system.unsafe", isSystem = true, bloatRecommendation = "UNSAFE")
        )
        assertEquals(emptyList<String>(), importableDisabledApps(apps, emptySet()).map { it.packageName })
    }

    @Test
    fun `an expert-tier system app is still offered`() {
        // EXPERT warns, it does not block — and this list only records an already-completed
        // freeze, so there is nothing left to warn about by the time we get here.
        val apps = listOf(
            app("com.system.expert", isSystem = true, bloatRecommendation = "Expert")
        )
        assertEquals(
            listOf("com.system.expert"),
            importableDisabledApps(apps, emptySet()).map { it.packageName }
        )
    }

    @Test
    fun `an unreadable UAD list withholds every system app but keeps user apps`() {
        // Fail closed, and land on exactly the behaviour the old blanket clause had: with no
        // classification available, no system app is offered.
        val apps = listOf(
            app("com.system.one", isSystem = true, uadLoadFailed = true),
            app("com.system.two", isSystem = true, bloatRecommendation = "Recommended", uadLoadFailed = true),
            app("com.user.frozen", uadLoadFailed = true),
        )
        assertEquals(
            listOf("com.user.frozen"),
            importableDisabledApps(apps, emptySet()).map { it.packageName }
        )
    }

    @Test
    fun `a system app uninstalled for this user is not offered`() {
        // The destructive mechanic's output, and indistinguishable from a vendor-removed package
        // or another debloater's work. Offering it means Unfreeze-all would run
        // `pm install-existing` on something Thor may never have removed.
        val apps = listOf(app("com.system.removed", isSystem = true, installedForUser = false))
        assertEquals(emptyList<String>(), importableDisabledApps(apps, emptySet()).map { it.packageName })
    }

    @Test
    fun `a user app uninstalled with data retained is not offered`() {
        // Thor cannot unfreeze this at all — there is no APK to enable — so a watchlist row for it
        // would only fail every unfreeze it took part in.
        val apps = listOf(app("com.user.gone", installedForUser = false))
        assertEquals(emptyList<String>(), importableDisabledApps(apps, emptySet()).map { it.packageName })
    }

    @Test
    fun `scan order is preserved`() {
        // The prompt shows a count and the confirm button imports the whole list, so order is only
        // cosmetic — but a filter is the wrong place to reorder anything, and asserting it here
        // stops a future rewrite from quietly sorting.
        val apps = listOf(
            app("com.b"),
            app("com.a"),
            app("com.c", disabled = false),
            app("com.d", isSystem = true),
        )
        assertEquals(
            listOf("com.b", "com.a", "com.d"),
            importableDisabledApps(apps, emptySet()).map { it.packageName }
        )
    }

    @Test
    fun `an empty install list yields no candidates`() {
        assertEquals(
            emptyList<String>(),
            importableDisabledApps(emptyList(), setOf("com.x")).map { it.packageName }
        )
    }
}

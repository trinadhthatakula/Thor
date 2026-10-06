// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RootManagerShortcutsTest {
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        packageManager = ApplicationProvider.getApplicationContext<Application>().packageManager
    }

    @Test
    fun `an explicitly selected randomized package resolves without manager name inference`() {
        val packageName = "zaqxsw.edcrfv.tgbyhn"
        install(packageName, "Tools")

        assertEquals(
            RootManagerShortcut(packageName, "Tools"),
            RootManagerShortcuts.resolve(packageManager, packageName),
        )
        assertTrue(PrivilegeManagerApp.findInstalledManagers { it == packageName }.isEmpty())
    }

    @Test
    fun `empty absent disabled and unlaunchable selections do not resolve`() {
        install("test.disabled", "Disabled", enabled = false)
        install("test.unlaunchable", "Background service", launchers = 0)
        install("test.private", "Private activity", exported = false)

        for (packageName in listOf(null, "", " ", "test.absent", "test.disabled", "test.unlaunchable", "test.private")) {
            assertNull(packageName, RootManagerShortcuts.resolve(packageManager, packageName))
        }
    }

    @Test
    fun `a removed app stops resolving`() {
        install("test.removed", "Manager")
        assertEquals("test.removed", RootManagerShortcuts.resolve(packageManager, "test.removed")?.packageName)

        shadowOf(packageManager).removePackage("test.removed")

        assertNull(RootManagerShortcuts.resolve(packageManager, "test.removed"))
    }

    @Test
    fun `a package without its installed flag does not resolve`() {
        install("test.retained", "Retained data")
        requireNotNull(
            shadowOf(packageManager).getInternalMutablePackageInfo("test.retained").applicationInfo,
        ).flags = 0

        assertNull(RootManagerShortcuts.resolve(packageManager, "test.retained"))
    }

    @Test
    fun `candidates deduplicate launchers and sort labels with a package tie break`() {
        install("test.zulu", "Zulu")
        install("test.same.b", "Same", launchers = 2)
        install("test.alpha", "alpha")
        install("test.same.a", "Same")
        install("test.disabled", "Disabled", enabled = false)
        install("test.background", "Background", launchers = 0)

        val candidates = RootManagerShortcuts.candidates(packageManager)
            .filter { it.packageName.startsWith("test.") }

        assertEquals(
            listOf(
                RootManagerShortcut("test.alpha", "alpha"),
                RootManagerShortcut("test.same.a", "Same"),
                RootManagerShortcut("test.same.b", "Same"),
                RootManagerShortcut("test.zulu", "Zulu"),
            ),
            candidates,
        )
    }

    @Test
    fun `renamed forks are discovered through exact known launcher classes`() {
        install(
            "zaqxsw.edcrfv.tgbyhn",
            "Tools",
            launcherClassNames = listOf("com.resukisu.resukisu.ui.MainActivity"),
        )
        install(
            "qwerty.asdfgh.zxcvbn",
            "Settings",
            launcherClassNames = listOf("com.sukisu.ultra.ui.MainActivity"),
        )

        assertEquals(
            listOf(
                InstalledManagerInfo(PrivilegeManagerApp.RE_SUKI_SU, "zaqxsw.edcrfv.tgbyhn"),
                InstalledManagerInfo(PrivilegeManagerApp.SUKI_SU_ULTRA, "qwerty.asdfgh.zxcvbn"),
            ),
            PrivilegeManagerApp.findInstalledManagers(packageManager),
        )
    }

    @Test
    fun `alternate launcher aliases are recognized without duplicate managers`() {
        install(
            "test.renamed",
            "Tools",
            launcherClassNames = listOf(
                "com.resukisu.resukisu.ui.MainActivityAlias",
                "com.resukisu.resukisu.ui.MainActivity",
            ),
        )

        assertEquals(
            listOf(InstalledManagerInfo(PrivilegeManagerApp.RE_SUKI_SU, "test.renamed")),
            PrivilegeManagerApp.findInstalledManagers(packageManager),
        )
    }

    @Test
    fun `a usable canonical package takes precedence over a renamed copy`() {
        install("com.resukisu.resukisu", "ReSukiSU")
        install(
            "test.renamed",
            "Tools",
            launcherClassNames = listOf("com.resukisu.resukisu.ui.MainActivity"),
        )

        assertEquals(
            listOf(InstalledManagerInfo(PrivilegeManagerApp.RE_SUKI_SU, "com.resukisu.resukisu")),
            PrivilegeManagerApp.findInstalledManagers(packageManager),
        )
    }

    @Test
    fun `manager labels and similar launcher names do not identify a known manager`() {
        install("test.same.label", "ReSukiSU")
        install(
            "test.similar.launcher",
            "ReSukiSU",
            launcherClassNames = listOf("com.resukisu.resukisu.ui.MainActivityClone"),
        )

        assertTrue(PrivilegeManagerApp.findInstalledManagers(packageManager).isEmpty())
        assertEquals(2, RootManagerShortcuts.candidates(packageManager).count { it.packageName.startsWith("test.") })
    }

    @Test
    fun `disabled known packages and renamed launchers are not offered`() {
        install("com.resukisu.resukisu", "ReSukiSU", enabled = false)
        install(
            "test.renamed",
            "Tools",
            enabled = false,
            launcherClassNames = listOf("com.resukisu.resukisu.ui.MainActivity"),
        )

        assertTrue(PrivilegeManagerApp.findInstalledManagers(packageManager).isEmpty())
    }

    private fun install(
        packageName: String,
        label: String,
        enabled: Boolean = true,
        launchers: Int = 1,
        launcherClassNames: List<String>? = null,
        exported: Boolean = true,
    ) {
        val shadow = shadowOf(packageManager)
        shadow.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.nonLocalizedLabel = label
                this.enabled = enabled
                this.flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        val names = launcherClassNames ?: List(launchers) { "$packageName.Launcher$it" }
        for (name in names) {
            val component = ComponentName(packageName, name)
            shadow.addActivityIfNotPresent(component).apply {
                this.enabled = enabled
                this.exported = exported
            }
            shadow.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }
}

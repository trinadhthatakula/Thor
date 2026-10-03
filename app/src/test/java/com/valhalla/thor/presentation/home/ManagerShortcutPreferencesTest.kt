// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import app.cash.turbine.test
import com.valhalla.thor.domain.model.RootManagerShortcut
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagerShortcutPreferencesTest {
    @Test
    fun `unrelated preferences still emit without rediscovering managers`() = runTest {
        val preferences = MutableStateFlow(UserPreferences(selectedRootManagerPackage = "hidden.manager"))
        val revision = MutableStateFlow(0L)
        val discoveries = mutableListOf<String?>()
        val managers = shortcuts("hidden.manager")

        preferencesWithManagerShortcuts(preferences, revision) { packageName ->
            discoveries += packageName
            managers
        }.test {
            assertEquals(preferences.value to managers, awaitItem())

            preferences.value = preferences.value.copy(
                showInstallerTile = false,
                themeMode = ThemeMode.DARK,
            )

            assertEquals(preferences.value to managers, awaitItem())
            assertEquals(listOf("hidden.manager"), discoveries)
        }
    }

    @Test
    fun `selection changes and clearing pair current preferences with freshly resolved shortcuts`() = runTest {
        val preferences = MutableStateFlow(UserPreferences())
        val revision = MutableStateFlow(0L)
        val discoveries = mutableListOf<String?>()

        preferencesWithManagerShortcuts(preferences, revision) { packageName ->
            discoveries += packageName
            shortcuts(packageName)
        }.test {
            assertEquals(preferences.value to shortcuts(null), awaitItem())

            for (packageName in listOf("manager.a", "manager.b", "manager.a", null)) {
                val current = preferences.value.copy(selectedRootManagerPackage = packageName)
                preferences.value = current

                assertEquals(current to shortcuts(packageName), awaitItem())
            }

            assertEquals(listOf(null, "manager.a", "manager.b", "manager.a", null), discoveries)
        }
    }

    @Test
    fun `an unavailable selected manager remains cached until an explicit refresh`() = runTest {
        val preferences = MutableStateFlow(UserPreferences(selectedRootManagerPackage = "hidden.manager"))
        val revision = MutableStateFlow(0L)
        var available = false
        var discoveries = 0

        preferencesWithManagerShortcuts(preferences, revision) { packageName ->
            discoveries++
            shortcuts(packageName.takeIf { available })
        }.test {
            assertNull(awaitItem().second.selected)

            available = true
            preferences.value = preferences.value.copy(showExtensionsTile = false)
            val unchanged = awaitItem()
            assertEquals(preferences.value, unchanged.first)
            assertNull(unchanged.second.selected)
            assertEquals(1, discoveries)

            revision.value++
            assertEquals(preferences.value to shortcuts("hidden.manager"), awaitItem())
            assertEquals(2, discoveries)

            available = false
            revision.value++
            assertNull(awaitItem().second.selected)
            assertEquals(3, discoveries)
        }
    }

    @Test
    fun `each collection discovers afresh and cannot replace another collectors cache`() = runTest {
        val preferences = MutableStateFlow(UserPreferences(selectedRootManagerPackage = "hidden.manager"))
        val revision = MutableStateFlow(0L)
        var discoveries = 0
        val snapshots = preferencesWithManagerShortcuts(preferences, revision) { packageName ->
            discoveries++
            shortcuts(packageName, label = "Discovery $discoveries")
        }

        snapshots.test {
            val original = awaitItem().second
            assertEquals("Discovery 1", original.selected?.label)

            snapshots.test {
                assertEquals("Discovery 2", awaitItem().second.selected?.label)
            }

            preferences.value = preferences.value.copy(showInstallerTile = false)
            assertEquals(preferences.value to original, awaitItem())
            assertEquals(2, discoveries)
        }

        assertEquals("Discovery 3", snapshots.first().second.selected?.label)
        assertEquals(3, discoveries)
    }

    private fun shortcuts(packageName: String?, label: String = "Manager") = ManagerShortcuts(
        installed = emptyList(),
        selected = packageName?.let { RootManagerShortcut(it, label) },
    )
}

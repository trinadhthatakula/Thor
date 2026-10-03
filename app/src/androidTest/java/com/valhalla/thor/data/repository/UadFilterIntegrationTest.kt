// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.source.local.UadHelper
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.FilterType
import com.valhalla.thor.domain.model.UadRecommendation
import com.valhalla.thor.domain.model.filterApps
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Read-only package checks against the installed device and its real bundled/extension UAD map. */
@RunWith(AndroidJUnit4::class)
class UadFilterIntegrationTest {
    @Test
    fun installedSystemAppsMatchUadTagsAndDetails() = runBlocking<Unit> {
        val koin = GlobalContext.get()
        // Instrumentation's compiler graph is separate from ThorApplication's loaded modules.
        // Require the runtime binding so this exercises production repositories.
        val repository = requireNotNull(koin.getOrNull<AppRepository>())
        val apps = withTimeout(60_000) {
            repository.getAllApps().first { apps ->
                apps.any { it.isSystem } && apps.filter { it.isSystem }.all { it.isUadLoaded }
            }
        }
        val systems = apps.filter { it.isSystem }
        val users = apps.filterNot { it.isSystem }
        assertFalse("Bundled UAD lookup must succeed on this target", systems.any { it.isUadLoadFailed })
        val snapshot = withContext(Dispatchers.IO) { requireNotNull(koin.getOrNull<UadHelper>()).snapshot() }
        assertFalse(snapshot.loadFailed)
        val recognized = setOf("recommended", "advanced", "expert", "unsafe")
        val results = UadRecommendation.entries.associateWith { tag ->
            val expected = systems.filter { app ->
                val raw = snapshot.recommendationFor(app.packageName)?.lowercase()
                if (tag == UadRecommendation.UNKNOWN) raw !in recognized
                else raw == tag.persistedValue.lowercase()
            }.map { it.packageName }.toSet()
            val actual = filterApps(systems, FilterType.Uad, tag.persistedValue)
            assertEquals(tag.persistedValue, expected, actual.map { it.packageName }.toSet())
            assertTrue(filterApps(users, FilterType.Uad, tag.persistedValue).isEmpty())
            actual
        }
        assertEquals(systems.size, results.values.sumOf { it.size })
        assertEquals(systems, filterApps(systems, FilterType.Uad, "All"))
        assertTrue("This device should exercise recognized UAD recommendations",
            results.getValue(UadRecommendation.RECOMMENDED).isNotEmpty())
        for (app in results.values.mapNotNull { it.firstOrNull() }) {
            val details = requireNotNull(repository.getAppDetails(app.packageName))
            assertTrue(details.isUadLoaded)
            assertFalse(details.isUadLoadFailed)
            assertEquals(app.bloatRecommendation, details.bloatRecommendation)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("uad_filter_counts", results.entries.joinToString { "${it.key.persistedValue}=${it.value.size}" })
            putInt("system_apps", systems.size)
            putInt("user_apps", users.size)
        })
    }

    @Test
    fun separateFilterProfilesSurviveRepositoryRecreation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = requireNotNull(GlobalContext.get().getOrNull<PreferenceRepository>())
        val original = repository.userPreferences.first()
        try {
            repository.updateAppFilter(AppListType.USER, FilterType.State, "Frozen")
            repository.updateAppFilter(AppListType.SYSTEM, FilterType.Uad, "Recommended")
            val recreated = PreferenceRepositoryImpl(context)
            val saved = recreated.userPreferences.first()
            assertEquals(FilterType.State, saved.userAppFilter.filterType)
            assertEquals("Frozen", saved.userAppFilter.selectedFilter)
            assertEquals(FilterType.Uad, saved.systemAppFilter.filterType)
            assertEquals("Recommended", saved.systemAppFilter.selectedFilter)
            recreated.updateAppFilter(AppListType.USER, FilterType.Permission, "All")
            assertEquals(saved.systemAppFilter, repository.userPreferences.first().systemAppFilter)
        } finally {
            repository.updateAppFilter(AppListType.USER, original.userAppFilter.filterType,
                original.userAppFilter.selectedFilter)
            repository.updateAppFilter(AppListType.SYSTEM, original.systemAppFilter.filterType,
                original.systemAppFilter.selectedFilter)
        }
        val restored = repository.userPreferences.first()
        assertEquals(original.userAppFilter, restored.userAppFilter)
        assertEquals(original.systemAppFilter, restored.systemAppFilter)
    }
}

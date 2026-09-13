// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityPreferencesTest {
    @Test
    fun `cold entry waits for the saved font and default tab instead of emitting defaults`() = runTest {
        val saved = UserPreferences(fontPreset = FontPreset.SYSTEM, defaultTab = DefaultTab.FREEZER)
        val repository = FakePreferenceRepository(saved, firstReadDelayMs = 100)
        val state = repository.userPreferences.asActivityPreferences(backgroundScope)

        runCurrent()
        assertNull(state.value)
        advanceTimeBy(99)
        runCurrent()
        assertNull(state.value)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(saved, state.value?.preferences)
        assertEquals(DefaultTab.FREEZER, state.value?.initialDefaultTab)
    }

    @Test
    fun `appearance updates stay live while the activity start tab remains pinned`() = runTest {
        val repository = FakePreferenceRepository(UserPreferences(defaultTab = DefaultTab.FREEZER))
        val state = repository.userPreferences.asActivityPreferences(backgroundScope)
        runCurrent()

        repository.setDefaultTab(DefaultTab.HOME)
        repository.setFontPreset(FontPreset.SYSTEM)
        runCurrent()

        assertEquals(FontPreset.SYSTEM, state.value?.preferences?.fontPreset)
        assertEquals(DefaultTab.HOME, state.value?.preferences?.defaultTab)
        assertEquals(DefaultTab.FREEZER, state.value?.initialDefaultTab)

        repository.setFontPreset(FontPreset.ASGARD)
        runCurrent()

        assertEquals(FontPreset.ASGARD, state.value?.preferences?.fontPreset)
        assertEquals(DefaultTab.FREEZER, state.value?.initialDefaultTab)
    }
}

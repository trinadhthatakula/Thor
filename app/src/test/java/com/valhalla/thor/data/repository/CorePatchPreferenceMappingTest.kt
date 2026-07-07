package com.valhalla.thor.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchPreferenceMappingTest {
    @Test fun `defaults corePatchEnabled to false`() =
        assertFalse(emptyPreferences().toUserPreferences().corePatchEnabled)

    @Test fun `reads corePatchEnabled true`() {
        val prefs = mutablePreferencesOf(
            PreferenceRepositoryImpl.Keys.CORE_PATCH_ENABLED to true,
        )
        assertTrue(prefs.toUserPreferences().corePatchEnabled)
    }
}

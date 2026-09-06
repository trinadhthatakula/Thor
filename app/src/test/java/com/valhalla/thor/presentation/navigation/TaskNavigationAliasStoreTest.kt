// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TaskNavigationAliasStoreTest {

    @Test
    fun `canonical alias survives construction of a replacement process store`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provisionalId = UUID.randomUUID()
        val canonicalId = UUID.randomUUID()

        SharedPreferencesTaskNavigationAliasStore(context, Dispatchers.IO)
            .record(provisionalId, canonicalId)

        assertEquals(
            canonicalId,
            SharedPreferencesTaskNavigationAliasStore(context, Dispatchers.IO)
                .canonicalTaskId(provisionalId),
        )
    }
}

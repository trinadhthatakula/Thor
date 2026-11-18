// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.source.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuspensionMarkersTest {
    @Test fun `nonce survives instances and is scoped to package and Android user`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markers = SuspensionMarkers(context)
        val first = markers.extras("com.example.one", 0).getString(SuspensionMarkers.KEY)
        assertNotNull(first)
        assertEquals(first, SuspensionMarkers(context).extras("com.example.one", 0).getString(SuspensionMarkers.KEY))
        assertNotEquals(first, markers.extras("com.example.two", 0).getString(SuspensionMarkers.KEY))
        assertNotEquals(first, markers.extras("com.example.one", 10).getString(SuspensionMarkers.KEY))
    }
}

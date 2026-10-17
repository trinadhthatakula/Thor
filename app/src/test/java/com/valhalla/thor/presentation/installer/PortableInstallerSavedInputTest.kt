// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.core.os.BundleCompat
import androidx.core.net.toUri
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.presentation.FakePreferenceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 36])
class PortableInstallerSavedInputTest {
    @Before fun setUp() {
        startKoin { modules(module { single<PreferenceRepository> { FakePreferenceRepository() } }) }
    }

    @After fun tearDown() { stopKoin() }

    @Test fun queuedNewIntentSurvivesRecreationWithOriginalLaunchIntent() {
        val original = Intent(Intent.ACTION_VIEW, "content://packages/original.apk".toUri())
        val queuedUri = "content://packages/queued.zip".toUri()
        val queued = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, queuedUri)
            clipData = ClipData.newRawUri("Kernel", queuedUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val first = Robolectric.buildActivity(PortableInstallerActivity::class.java, original).create()
        val saved = Bundle()
        try {
            first.newIntent(queued).saveInstanceState(saved)
        } finally { first.destroy() }

        // Recreate from the system's original launch Intent, with a real parcelled saved Bundle.
        val recreated = Robolectric.buildActivity(PortableInstallerActivity::class.java, original)
            .create(roundTrip(saved))
        val restored = Bundle()
        try { recreated.saveInstanceState(restored) } finally { recreated.destroy() }
        val input = requireNotNull(BundleCompat.getParcelable(restored, "pending_intent", Intent::class.java))
        assertEquals(queuedUri, portableInstallerUri(input))
        assertEquals(Intent.ACTION_SEND, input.action)
        assertEquals("application/zip", input.type)
        assertEquals(queuedUri, input.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, input.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(restored.getBoolean("pending_new_intent"))
    }

    @Test fun missingSavedIntentFallsBackToLaunchIntentAndPreservesFlag() {
        for (newIntent in listOf(false, true)) {
            val original = Intent(Intent.ACTION_VIEW, "content://packages/original.apk".toUri())
            val saved = Bundle().apply { putBoolean("pending_new_intent", newIntent) }
            val controller = Robolectric.buildActivity(PortableInstallerActivity::class.java, original).create(saved)
            val restored = Bundle()
            try { controller.saveInstanceState(restored) } finally { controller.destroy() }
            val input = requireNotNull(BundleCompat.getParcelable(restored, "pending_intent", Intent::class.java))
            assertEquals(original.data, portableInstallerUri(input))
            assertEquals(newIntent, restored.getBoolean("pending_new_intent"))
        }
    }

    @Test fun firstLaunchKeepsOriginalInputWithoutNewIntentFlag() {
        val original = Intent(Intent.ACTION_VIEW, "content://packages/original.apk".toUri())
        val controller = Robolectric.buildActivity(PortableInstallerActivity::class.java, original).create()
        val saved = Bundle()
        try { controller.saveInstanceState(saved) } finally { controller.destroy() }
        assertEquals(original.data, portableInstallerUri(requireNotNull(
            BundleCompat.getParcelable(saved, "pending_intent", Intent::class.java),
        )))
        assertFalse(saved.getBoolean("pending_new_intent"))
    }

    private fun roundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
    }
}

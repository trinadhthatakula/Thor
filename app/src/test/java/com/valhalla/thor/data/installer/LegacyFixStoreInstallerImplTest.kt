// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.installer

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LegacyFixStoreInstallerImplTest {
    @Test
    fun `fresh split names or split paths refuse before requesting any installer`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (splitNamesOnly in listOf(true, false)) {
            val info = PackageInfo().apply {
                packageName = "example.split"
                splitNames = if (splitNamesOnly) arrayOf("config.en") else emptyArray()
                applicationInfo = ApplicationInfo().apply {
                    packageName = "example.split"
                    flags = ApplicationInfo.FLAG_INSTALLED
                    sourceDir = "/base.apk"
                    splitSourceDirs = if (splitNamesOnly) emptyArray() else arrayOf("/split.apk")
                }
            }
            shadowOf(context.packageManager).installPackage(info)
            var requested = false
            val result = LegacyFixStoreInstallerImpl(context, Dispatchers.Unconfined)
                .reinstall(info.packageName) {
                    requested = true
                    Result.success(true)
                }
            assertFalse(requested)
            assertEquals(
                UiText.StringResource(R.string.legacy_fix_store_split_unsupported),
                (result.exceptionOrNull() as? UiTextException)?.uiText,
            )
        }
    }
}

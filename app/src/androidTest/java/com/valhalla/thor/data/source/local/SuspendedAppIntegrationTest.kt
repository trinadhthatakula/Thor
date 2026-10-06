// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.domain.gateway.SystemGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/** Explicit opt-in; suspends only an initially active fixture and restores it in finally. */
@RunWith(AndroidJUnit4::class)
class SuspendedAppIntegrationTest {
    @Test
    fun suspendedAppCanBeResumedFromTheSystemDialog() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("suspendDialogMode")
        assumeTrue(mode in setOf("shizuku", "root", "dhizuku"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val target = args.getString("suspendDialogTarget") ?: "com.google.android.calendar"
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(target))
        fun suspended(): Boolean = context.packageManager.getApplicationInfo(target, 0)
            .flags and ApplicationInfo.FLAG_SUSPENDED != 0
        assertFalse("Fixture must start unsuspended", suspended())
        val gateway: SystemGateway = when (mode) {
            "root" -> requireNotNull(GlobalContext.get().getOrNull<RootSystemGateway>())
            "dhizuku" -> requireNotNull(GlobalContext.get().getOrNull<DhizukuSystemGateway>())
            else -> requireNotNull(GlobalContext.get().getOrNull<ShizukuSystemGateway>())
        }
        try {
            gateway.setAppSuspended(target, true).getOrThrow()
            assertTrue("Gateway must actually suspend", suspended())
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val automation = instrumentation.uiAutomation
            withTimeout(15_000) {
                while (automation.rootInActiveWindow?.packageName?.toString() !in
                    setOf("android", "com.android.settings")) delay(100)
            }
            if (mode != "dhizuku" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                withTimeout(10_000) {
                    while (automation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("Unpause app").isNullOrEmpty()) delay(100)
                }
            }
            if (mode == "dhizuku") {
                assertTrue("Expected device-owner policy notice", automation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText("admin").isNotEmpty())
            }
            automation.takeScreenshot()?.let { screenshot ->
                File(context.filesDir, "suspend-dialog-$mode.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            if (mode != "dhizuku" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val buttons = automation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText("Unpause app")
                assertTrue("Native Unpause button missing", buttons.isNotEmpty())
                assertTrue(buttons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(10_000) { while (suspended()) delay(100) }
                assertFalse("Unpause must remove the actual suspension", suspended())
                withTimeout(10_000) {
                    while (automation.rootInActiveWindow?.packageName?.toString() != target) delay(100)
                }
            } else {
                // Device-owner policy controls this dialog. The in-app Unsuspend action must work.
                gateway.setAppSuspended(target, false).getOrThrow()
                assertFalse(suspended())
            }
        } finally {
            gateway.setAppSuspended(target, false).getOrThrow()
            assertFalse("Restore fixture", suspended())
        }
    }
}

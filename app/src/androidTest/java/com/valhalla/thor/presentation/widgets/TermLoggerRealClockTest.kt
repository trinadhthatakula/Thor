// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.presentation.theme.ThorTheme
import com.valhalla.thor.util.UiText
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Direct ActivityScenario composition uses the real window frame clock, never a Compose test clock. */
@RunWith(AndroidJUnit4::class)
class TermLoggerRealClockTest {
    @Test
    fun liveWindowAnimatesStopsAndRestartsAcrossThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val output = File(context.filesDir, "rearranging-boxes-parity/real-clock").apply { check(isDirectory || mkdirs()) }
        val initialRefresh = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        File(output, "metadata.txt").writeText("system_animation_scale=$scale\ninitial_display_refresh_hz=$initialRefresh\nclock=real window Choreographer\n")
        File(output, "result.txt").writeText("INCOMPLETE: real-clock verification has started but has not passed.\n")
        if (scale <= 0f) File(output, "result.txt").writeText("SKIPPED: system animations are disabled; playback was not verified and no setting was changed.\n")
        assumeTrue("Real playback requires enabled system animations; see real-clock/result.txt", scale > 0f)
        val status = mutableStateOf(TermLoggerStatus.ACTIVE)
        val dark = mutableStateOf(true)
        val bounds = AtomicReference<Rect>()
        val records = mutableListOf("phase,frame,elapsed_realtime_ms,display_refresh_hz,width,height,pixel_sha256")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    ThorTheme(darkTheme = dark.value) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                                TermLoggerContent(
                                    title = UiText.DynamicString("Live animation preview"),
                                    logs = listOf(UiText.DynamicString("Sample task ready"), UiText.DynamicString("Preview running")),
                                    status = status.value,
                                    modifier = Modifier.height(260.dp).onGloballyPositioned {
                                        val rect = it.boundsInWindow()
                                        bounds.set(Rect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt()))
                                    },
                                )
                            }
                        }
                    }
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (bounds.get()?.isEmpty != false && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(bounds.get()?.isEmpty == false) { "Terminal did not reach the visible window" }
            SystemClock.sleep(200)
            // Observe longer than two nominal 1616 ms loops at the device's motion scale.
            val interval = (3_400 * scale / 20).toLong().coerceAtLeast(20)
            fun sample(phase: String, count: Int, wait: Long): List<String> = (0 until count).map { index ->
                SystemClock.sleep(wait)
                val rect = checkNotNull(bounds.get())
                val bitmap = createBitmap(rect.width(), rect.height())
                try {
                    val ready = CountDownLatch(1)
                    var result = PixelCopy.ERROR_UNKNOWN
                    var refreshRate = Float.NaN
                    scenario.onActivity { activity ->
                        refreshRate = activity.window.decorView.display?.refreshRate ?: Float.NaN
                        PixelCopy.request(activity.window, rect, bitmap, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
                    }
                    check(ready.await(5, TimeUnit.SECONDS)) { "PixelCopy timed out" }
                    assertEquals("PixelCopy must capture the actual window surface", PixelCopy.SUCCESS, result)
                    val buffer = ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer)
                    val hash = MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
                    File(output, "$phase-${index.toString().padStart(2, '0')}.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                    records += "$phase,$index,${SystemClock.elapsedRealtime()},$refreshRate,${bitmap.width},${bitmap.height},$hash"
                    File(output, "frames.csv").writeText(records.joinToString("\n", postfix = "\n"))
                    hash
                } finally { bitmap.recycle() }
            }
            val active = sample("active-dark", 20, interval)
            assertTrue("Dark ACTIVE must move on real display frames", active.distinct().size >= 3)
            assertTrue("Dark ACTIVE must keep moving at the end of the observation", active.takeLast(8).distinct().size >= 3)
            scenario.onActivity { status.value = TermLoggerStatus.SUCCESS }
            SystemClock.sleep(250)
            val success = sample("success-dark", 3, 150)
            assertEquals("SUCCESS must stop visible motion", 1, success.distinct().size)
            scenario.onActivity { dark.value = false; status.value = TermLoggerStatus.ACTIVE }
            SystemClock.sleep(250)
            val resumed = sample("active-light", 20, interval)
            assertTrue("Light ACTIVE must restart on real display frames", resumed.distinct().size >= 3)
            assertTrue("Light ACTIVE must keep moving at the end of the observation", resumed.takeLast(8).distinct().size >= 3)
            assertTrue("The real surface must reflect the changed theme", resumed.none { it in active })
            File(output, "result.txt").writeText(
                "PASS: real window frame clock and PixelCopy; system animation scale=$scale (unchanged).\n" +
                    "Dark ACTIVE unique=${active.distinct().size}; SUCCESS unique=${success.distinct().size}; " +
                    "light ACTIVE unique=${resumed.distinct().size}. Sustained playback across each observation window.\n",
            )
        }
    }
}

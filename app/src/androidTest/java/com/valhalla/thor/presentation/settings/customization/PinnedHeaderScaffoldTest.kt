// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one invariant [PinnedHeaderScaffold] exists to hold: the body is never measured to nothing.
 *
 * The customization screen pins its hint, reset button and live preview above a `LazyColumn` that
 * takes the remaining space with `weight(1f)`. A `Column` measures an *unweighted* child against
 * everything left over, so a header that outgrows the viewport — landscape on a short screen, or a
 * display/font scale that wraps the hint and stretches the preview chips — is handed the whole
 * height and the weighted list is measured at zero. The list is the entire point of the screen, and
 * because nothing scrolls in that state there is no gesture that recovers it: the user sees a
 * preview and no actions.
 *
 * So the header is capped and scrolls inside the cap, and a header that fits is left exactly as it
 * was.
 */
@RunWith(AndroidJUnit4::class)
class PinnedHeaderScaffoldTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val bodyTag = "body"

    /**
     * Hosts the scaffold in a viewport of a known size, independent of the device the test runs on.
     */
    private fun setScaffold(
        width: Dp,
        height: Dp,
        fontScale: Float = 1f,
        header: @Composable () -> Unit
    ) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale)
        ) {
            Box(modifier = Modifier.requiredSize(width, height)) {
                PinnedHeaderScaffold(
                    modifier = Modifier.fillMaxSize(),
                    header = header
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(bodyTag))
                }
            }
        }
    }

    private fun bodyHeight(): Dp = rule.onNodeWithTag(bodyTag).getUnclippedBoundsInRoot().height

    @Test
    fun headerThatFitsLeavesTheBodyTheRest() {
        setScaffold(width = 360.dp, height = 400.dp) {
            Box(modifier = Modifier.fillMaxWidth().height(120.dp))
        }

        // Untouched by the cap: 400 - 120. Tolerance covers dp/px rounding at any density.
        val body = bodyHeight()
        assertTrue("body was $body, expected ~280dp", body.value in 279f..281f)
    }

    @Test
    fun headerTallerThanTheViewportStillLeavesHalfForTheBody() {
        setScaffold(width = 360.dp, height = 400.dp) {
            Box(modifier = Modifier.fillMaxWidth().height(4_000.dp))
        }

        // Without the cap this is 0dp and the action list is unreachable.
        rule.onNodeWithTag(bodyTag).assertHeightIsAtLeast(199.dp)
    }

    @Test
    fun largeFontScaleStillLeavesTheBodyOnScreen() {
        setScaffold(width = 360.dp, height = 400.dp, fontScale = 2f) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Drag to reorder actions, or use the arrows. " +
                        "Toggle a switch to hide an action from the app info sheet."
                )
            }
        }

        rule.onNodeWithTag(bodyTag).assertHeightIsAtLeast(199.dp)
    }

    @Test
    fun landscapeShortViewportStillLeavesTheBodyOnScreen() {
        // A phone in landscape below the system bars: wide, and short enough that the real header
        // (hint row + preview card, roughly 150dp) is a large fraction of what is left.
        setScaffold(width = 800.dp, height = 300.dp) {
            Box(modifier = Modifier.fillMaxWidth().height(280.dp))
        }

        rule.onNodeWithTag(bodyTag).assertHeightIsAtLeast(149.dp)
    }
}

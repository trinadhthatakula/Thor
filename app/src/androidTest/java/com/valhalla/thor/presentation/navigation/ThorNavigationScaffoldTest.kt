// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.navigation

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.asgard.navigation.AsgardNavigationBar
import com.valhalla.thor.domain.model.NavigationBarPreferences
import com.valhalla.thor.domain.model.NavigationPlacement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThorNavigationScaffoldTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var floating by mutableStateOf(true)
    private var visible by mutableStateOf(true)
    private var margin by mutableIntStateOf(12)
    private var clearance = 0.dp
    private var navigationInsetPx = 0
    private var surfaceColor = Color.Unspecified

    /** Builds a scrollable host with measured action clearance and optional persistent export progress. */
    private fun show(export: Boolean = false) {
        rule.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                val inset = WindowInsets.navigationBars.getBottom(density)
                val color = MaterialTheme.colorScheme.surfaceContainer
                SideEffect { navigationInsetPx = inset; surfaceColor = color }
                ThorNavigationScaffold(
                    floatingNavigation = floating,
                    navigationBar = {
                        if (visible) {
                            val bar: @Composable () -> Unit = {
                                AsgardNavigationBar(
                                    items = thorNavigationItems(),
                                    selectedIndex = 0,
                                    onSelect = {},
                                    configuration = navigationConfiguration(
                                        NavigationBarPreferences(
                                            placement = if (floating) NavigationPlacement.FLOATING else NavigationPlacement.DOCKED,
                                            floatingMargin = margin,
                                        ),
                                        scaffoldFloating = floating,
                                    ),
                                    modifier = Modifier.testTag("navigation"),
                                )
                            }
                            if (floating) bar() else DockedNavigationBar(Modifier.testTag("docked"), bar)
                        }
                    },
                    bottomBar = { if (export) Box(Modifier.fillMaxWidth().height(48.dp).testTag("export")) },
                ) { padding ->
                    val overlap = LocalFloatingNavigationClearance.current
                    SideEffect { clearance = overlap }
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().testTag("viewport")) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("list"),
                            contentPadding = PaddingValues(bottom = overlap + 72.dp),
                        ) {
                            items(50) { Box(Modifier.fillMaxWidth().height(48.dp).testTag("row-$it")) }
                        }
                        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = overlap + 16.dp)
                            .size(48.dp).testTag("action"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun floatingNavigationOverlaysTheViewportAndKeepsActionsAboveIt() {
        show()
        assertTrue(bounds("viewport").bottom > bounds("navigation").top)
        assertTrue(bounds("action").bottom <= bounds("navigation").top)
        rule.runOnIdle { assertTrue(clearance > 0.dp) }
    }

    @Test fun dockedNavigationReservesBottomSpaceAndNeedsNoOverlayClearance() {
        floating = false
        show()
        assertTrue(bounds("viewport").bottom <= bounds("navigation").top)
        rule.runOnIdle { assertEquals(0.dp, clearance) }
    }

    @Test fun dockedBackgroundCoversTheSystemNavigationInset() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        floating = false
        show()
        assumeTrue("Device has no bottom navigation inset", navigationInsetPx > 0)
        val image = rule.onNodeWithTag("docked").captureToImage().toPixelMap()
        assertEquals(surfaceColor, image[image.width / 10, image.height - 2])
    }

    @Test fun switchingToFloatingRestoresThePreviousSystemBarStyle() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        @Suppress("DEPRECATION")
        val previousColor = rule.activity.window.navigationBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= 29) {
            rule.activity.window.isNavigationBarContrastEnforced
        } else null
        floating = false
        show()
        rule.runOnIdle {
            @Suppress("DEPRECATION")
            assertEquals(android.graphics.Color.TRANSPARENT, rule.activity.window.navigationBarColor)
            if (Build.VERSION.SDK_INT >= 29) assertFalse(rule.activity.window.isNavigationBarContrastEnforced)
            floating = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            @Suppress("DEPRECATION")
            assertEquals(previousColor, rule.activity.window.navigationBarColor)
            if (Build.VERSION.SDK_INT >= 29) assertEquals(previousContrast, rule.activity.window.isNavigationBarContrastEnforced)
        }
    }

    @Test fun floatingMarginIsNotStackedOnAnAdditionalFabGap() {
        show()
        assertEquals(bounds("viewport").bottom, bounds("navigation").bottom, 1f)
        rule.runOnIdle { margin = 32 }
        rule.waitForIdle()
        assertEquals(bounds("viewport").bottom, bounds("navigation").bottom, 1f)
        assertTrue(bounds("action").bottom <= bounds("navigation").top)
    }

    @Test fun switchingPlacementAndHidingNavigationRemoveOverlayClearance() {
        show()
        rule.runOnIdle { floating = false }
        rule.waitForIdle()
        assertTrue(bounds("viewport").bottom <= bounds("navigation").top)
        rule.runOnIdle { assertEquals(0.dp, clearance); floating = true }
        rule.waitForIdle()
        rule.runOnIdle { visible = false }
        rule.onNodeWithTag("navigation").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0.dp, clearance) }
    }

    @Test fun finalListItemCanScrollAboveFloatingNavigationAndActions() {
        show()
        rule.onNodeWithTag("list").performScrollToNode(hasTestTag("row-49"))
        // ScrollToNode exposes the item; an additional scroll reaches the content-padding tail.
        rule.onNodeWithTag("list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithTag("row-49").assertIsDisplayed()
        assertTrue(bounds("row-49").bottom <= bounds("action").top)
    }

    @Test fun exportProgressRetainsItsOwnBottomSlotBelowFloatingNavigation() {
        show(export = true)
        assertTrue(bounds("navigation").bottom <= bounds("export").top)
        assertTrue(bounds("viewport").bottom <= bounds("export").top)
        assertTrue(bounds("viewport").bottom > bounds("navigation").top)
    }
}

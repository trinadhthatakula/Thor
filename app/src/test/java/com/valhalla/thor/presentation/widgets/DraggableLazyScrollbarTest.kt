// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real lazy containers, including their visible range and adjustable semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraggableLazyScrollbarTest {
    @get:Rule val rule = createComposeRule()

    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private val scrollbarLabel get() = application.getString(R.string.scrollbar_scroll_apps)

    @Test
    fun shortListDoesNotExposeAnUnusableScrollbar() {
        rule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        items(3) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        scrollbar().assertDoesNotExist()
    }

    @Test
    fun compactViewportWithBottomInsetLeavesRowsTouchableAndScrollable() {
        lateinit var state: LazyListState
        var clickedIndex = -1
        rule.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 240.dp).testTag(VIEWPORT_TAG)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        items(80) { index ->
                            Text(
                                "App $index",
                                Modifier.fillMaxWidth().height(56.dp).clickable { clickedIndex = index },
                            )
                        }
                    }
                    DraggableLazyScrollbar(
                        state,
                        Modifier.align(Alignment.CenterEnd).padding(bottom = 160.dp),
                    )
                }
            }
        }

        rule.runOnIdle { assertTrue(state.canScrollForward) }
        scrollbar().assertDoesNotExist()
        rule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            click(Offset(center.x * 1.89f, center.y * 0.24f))
        }
        rule.runOnIdle { assertEquals(0, clickedIndex) }

        rule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            down(Offset(center.x, center.y * 1.5f))
            moveTo(Offset(center.x, center.y * 0.4f))
            up()
        }
        rule.runOnIdle { assertTrue("The list should still scroll", state.firstVisibleItemIndex > 0) }
    }

    @Test
    fun paddingOnlyOverflowDoesNotCoverVisibleAppRows() {
        lateinit var state: LazyListState
        var clickedIndex = -1
        rule.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 480.dp).testTag(VIEWPORT_TAG)) {
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    ) {
                        items(7) { index ->
                            Text(
                                "App $index",
                                Modifier.fillMaxWidth().height(56.dp).clickable { clickedIndex = index },
                            )
                        }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        rule.runOnIdle {
            assertTrue("The trailing content padding should be scrollable", state.canScrollForward)
            assertEquals(7, state.layoutInfo.visibleItemsInfo.size)
        }
        scrollbar().assertDoesNotExist()
        rule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            click(Offset(center.x * 1.89f, center.y * 1.4f))
        }
        rule.runOnIdle { assertTrue("A visible app row should receive the trailing-edge tap", clickedIndex >= 0) }
    }

    @Test
    fun paddingOnlyOverflowDoesNotExposeGridScrollbar() {
        lateinit var state: LazyGridState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyGridState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = state,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    ) {
                        items(21) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        rule.runOnIdle {
            assertTrue("The trailing grid padding should be scrollable", state.canScrollForward)
            assertEquals(21, state.layoutInfo.visibleItemsInfo.size)
        }
        scrollbar().assertDoesNotExist()
    }

    @Test
    fun gridGutterKeepsTrailingCardTouchableInLtr() {
        assertTrailingGridCardOutsideScrollbar(LayoutDirection.Ltr)
    }

    @Test
    fun gridGutterKeepsTrailingCardTouchableInRtl() {
        assertTrailingGridCardOutsideScrollbar(LayoutDirection.Rtl)
    }

    @Test
    fun listGutterKeepsTrailingRowTouchableInLtr() {
        assertTrailingListRowOutsideScrollbar(LayoutDirection.Ltr)
    }

    @Test
    fun listGutterKeepsTrailingRowTouchableInRtl() {
        assertTrailingListRowOutsideScrollbar(LayoutDirection.Rtl)
    }

    @Test
    fun partiallyOffscreenLastListRowKeepsScrollbar() {
        lateinit var state: LazyListState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    ) {
                        items(9) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        rule.runOnIdle {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertEquals(8, last.index)
            assertTrue("The final row must cross the visible edge", last.offset + last.size > info.viewportEndOffset)
        }
        scrollbar().assertExists()
    }

    @Test
    fun partiallyOffscreenLastGridRowKeepsScrollbar() {
        lateinit var state: LazyGridState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyGridState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = state,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    ) {
                        items(27) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        rule.runOnIdle {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.last()
            assertEquals(26, last.index)
            assertTrue(
                "The final grid row must cross the visible edge",
                last.offset.y + last.size.height > info.viewportEndOffset,
            )
        }
        scrollbar().assertExists()
    }

    @Test
    fun listScrollbarSeeksToEndAndReportsTheNewVisibleRange() {
        lateinit var state: LazyListState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        items(80) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        val control = scrollbar().assertExists()
        val firstRange = control.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        control.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(1f))
        }

        var expectedLastRange = ""
        rule.runOnIdle {
            assertTrue(state.firstVisibleItemIndex > 40)
            assertEquals(79, state.layoutInfo.visibleItemsInfo.last().index)
            val visible = state.layoutInfo.visibleItemsInfo
            expectedLastRange = application.getString(
                R.string.scrollbar_visible_range,
                visible.first().index + 1,
                visible.last().index + 1,
                80,
            )
        }
        val lastRange = scrollbar().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(firstRange, lastRange)
        assertEquals(expectedLastRange, lastRange)
    }

    @Test
    fun gridScrollbarSeeksAcrossRowsToTheLastApp() {
        lateinit var state: LazyGridState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyGridState()
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = state,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    ) {
                        items(90) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        scrollbar().assertExists()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(1f))
            }

        var expectedLastRange = ""
        rule.runOnIdle {
            assertTrue(state.firstVisibleItemIndex > 45)
            assertEquals(89, state.layoutInfo.visibleItemsInfo.last().index)
            val visible = state.layoutInfo.visibleItemsInfo
            expectedLastRange = application.getString(
                R.string.scrollbar_visible_range,
                visible.first().index + 1,
                visible.last().index + 1,
                90,
            )
        }
        assertEquals(expectedLastRange, scrollbar().fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    fun draggingListThumbExpandsItsTargetAndSeeksThroughTheList() {
        lateinit var state: LazyListState
        rule.setContent {
            MaterialTheme {
                state = rememberLazyListState()
                var dragging by remember { mutableStateOf(false) }
                val gutterWidth = if (dragging) ScrollbarDraggingGutterWidth else ScrollbarRestingGutterWidth
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LazyColumn(
                        state = state,
                        contentPadding = PaddingValues(end = gutterWidth),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    ) {
                        items(120) { index -> AppRow(index) }
                    }
                    DraggableLazyScrollbar(
                        state,
                        Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        onDraggingChange = { dragging = it }
                    )
                }
            }
        }

        val initialBounds = scrollbar().assertExists().getUnclippedBoundsInRoot()
        assertEquals(ScrollbarRestingGutterWidth, initialBounds.width)
        val initialHeight = initialBounds.height
        scrollbar().performTouchInput { down(center) }
        assertEquals(ScrollbarDraggingGutterWidth, scrollbar().getUnclippedBoundsInRoot().width)
        scrollbar().performTouchInput {
            moveTo(Offset(center.x, center.y + center.y * 8f))
            up()
        }

        rule.runOnIdle {
            assertTrue("A partial drag should move into the list", state.firstVisibleItemIndex > 10)
            assertTrue("A partial drag should not jump to the end", state.canScrollForward)
        }
        val partialProgress = scrollbar().fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("Partial drag progress: $partialProgress", partialProgress in 0.2f..0.8f)

        scrollbar().performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + center.y * 20f))
            up()
        }
        rule.runOnIdle {
            assertEquals(119, state.layoutInfo.visibleItemsInfo.last().index)
            assertTrue("The end drag should reach the bottom", !state.canScrollForward)
        }
        assertEquals(initialHeight, scrollbar().getUnclippedBoundsInRoot().height)
        assertEquals(ScrollbarRestingGutterWidth, scrollbar().getUnclippedBoundsInRoot().width)
    }

    @Test
    fun rtlScrollbarStaysOnTheTrailingEdgeAndCanSeek() {
        lateinit var state: LazyListState
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    state = rememberLazyListState()
                    var dragging by remember { mutableStateOf(false) }
                    val gutterWidth = if (dragging) ScrollbarDraggingGutterWidth else ScrollbarRestingGutterWidth
                    Box(Modifier.size(width = 320.dp, height = 480.dp).testTag(VIEWPORT_TAG)) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(end = gutterWidth),
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        ) {
                            items(80) { index -> AppRow(index) }
                        }
                        DraggableLazyScrollbar(
                            state,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            onDraggingChange = { dragging = it }
                        )
                    }
                }
            }
        }

        val viewport = rule.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val thumb = scrollbar().assertExists().getUnclippedBoundsInRoot()
        assertEquals("RTL trailing edge should be the left side", viewport.left, thumb.left)

        scrollbar().performTouchInput {
            down(center)
            moveTo(Offset(center.x, center.y + center.y * 20f))
            up()
        }
        rule.runOnIdle { assertEquals(79, state.layoutInfo.visibleItemsInfo.last().index) }
    }

    @Test
    fun tappingTheTrackDoesNotStealAnAppRowTap() {
        var clickedIndex = -1
        rule.setContent {
            MaterialTheme {
                val state = rememberLazyListState()
                Box(Modifier.size(width = 320.dp, height = 480.dp).testTag(VIEWPORT_TAG)) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                        items(80) { index ->
                            Text(
                                "App $index",
                                Modifier.fillMaxWidth().height(56.dp).clickable { clickedIndex = index },
                            )
                        }
                    }
                    DraggableLazyScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }

        rule.onNodeWithTag(VIEWPORT_TAG).performTouchInput {
            click(Offset(center.x * 1.95f, center.y * 1.4f))
        }
        rule.runOnIdle { assertTrue("A track tap should reach an app row", clickedIndex >= 0) }
    }

    private fun assertTrailingListRowOutsideScrollbar(layoutDirection: LayoutDirection) {
        var clickedIndex = -1
        lateinit var state: LazyListState
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MaterialTheme {
                    state = rememberLazyListState()
                    var dragging by remember { mutableStateOf(false) }
                    val gutterWidth = if (dragging) ScrollbarDraggingGutterWidth else ScrollbarRestingGutterWidth
                    Box(Modifier.size(width = 320.dp, height = 480.dp).testTag(VIEWPORT_TAG)) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp, end = gutterWidth),
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        ) {
                            items(90) { index ->
                                Text(
                                    "App $index",
                                    Modifier.fillMaxWidth().height(56.dp)
                                        .clickable { clickedIndex = index }
                                        .testTag("list-row-$index")
                                )
                            }
                        }
                        DraggableLazyScrollbar(
                            state,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            onDraggingChange = { dragging = it }
                        )
                    }
                }
            }
        }

        fun assertRowOutsideThumb(expectedWidth: Dp) {
            val viewport = rule.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
            val target = scrollbar().assertExists().getUnclippedBoundsInRoot()
            val row = rule.onNodeWithTag("list-row-0").assertExists().getUnclippedBoundsInRoot()
            assertEquals(expectedWidth, target.width)
            assertTrue("The first row should align vertically with the thumb", row.top < target.bottom && target.top < row.bottom)
            if (layoutDirection == LayoutDirection.Rtl) {
                assertEquals("RTL scrollbar should stay on the left", viewport.left, target.left)
                assertTrue("RTL trailing row overlaps the drag target: $row, $target", row.left >= target.right)
            } else {
                assertEquals("LTR scrollbar should stay on the right", viewport.right, target.right)
                assertTrue("LTR trailing row overlaps the drag target: $row, $target", row.right <= target.left)
            }
        }

        assertRowOutsideThumb(ScrollbarRestingGutterWidth)
        val rowNode = rule.onNodeWithTag("list-row-0").assertExists()
        val rowBounds = rowNode.fetchSemanticsNode().boundsInRoot
        rowNode.performTouchInput {
            val trailingEdgeX = if (layoutDirection == LayoutDirection.Rtl) 1f else rowBounds.right - rowBounds.left - 1f
            click(Offset(trailingEdgeX, center.y))
        }
        rule.runOnIdle { assertEquals(0, clickedIndex) }

        scrollbar().performTouchInput { down(center) }
        assertRowOutsideThumb(ScrollbarDraggingGutterWidth)
        scrollbar().performTouchInput {
            moveTo(Offset(center.x, center.y + center.y * 8f))
            up()
        }
        rule.runOnIdle { assertTrue("The expanded list thumb must keep the gesture", state.firstVisibleItemIndex > 0) }
        assertEquals(ScrollbarRestingGutterWidth, scrollbar().getUnclippedBoundsInRoot().width)
    }

    private fun assertTrailingGridCardOutsideScrollbar(layoutDirection: LayoutDirection) {
        var clickedIndex = -1
        lateinit var state: LazyGridState
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                MaterialTheme {
                    state = rememberLazyGridState()
                    var dragging by remember { mutableStateOf(false) }
                    val gutterWidth = if (dragging) ScrollbarDraggingGutterWidth else ScrollbarRestingGutterWidth
                    Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = state,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp, end = gutterWidth),
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        ) {
                            items(90) { index ->
                                Text(
                                    "App $index",
                                    Modifier.fillMaxWidth().height(56.dp)
                                        .clickable { clickedIndex = index }
                                        .testTag("grid-card-$index"),
                                )
                            }
                        }
                        DraggableLazyScrollbar(
                            state,
                            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            onDraggingChange = { dragging = it }
                        )
                    }
                }
            }
        }

        fun assertCardOutsideThumb(expectedWidth: Dp) {
            val target = scrollbar().assertExists().getUnclippedBoundsInRoot()
            val card = rule.onNodeWithTag("grid-card-2").assertExists().getUnclippedBoundsInRoot()
            assertEquals(expectedWidth, target.width)
            assertTrue("The trailing card should align vertically with the thumb", card.top < target.bottom && target.top < card.bottom)
            if (layoutDirection == LayoutDirection.Rtl) {
                assertTrue("RTL trailing card overlaps the drag target: $card, $target", card.left >= target.right)
            } else {
                assertTrue("LTR trailing card overlaps the drag target: $card, $target", card.right <= target.left)
            }
        }

        assertCardOutsideThumb(ScrollbarRestingGutterWidth)
        val cardNode = rule.onNodeWithTag("grid-card-2").assertExists()
        val cardBoundsPx = cardNode.fetchSemanticsNode().boundsInRoot
        cardNode.performTouchInput {
            val trailingEdgeX = if (layoutDirection == LayoutDirection.Rtl) 1f else cardBoundsPx.right - cardBoundsPx.left - 1f
            click(Offset(trailingEdgeX, center.y))
        }
        rule.runOnIdle { assertEquals(2, clickedIndex) }

        scrollbar().performTouchInput { down(center) }
        assertCardOutsideThumb(ScrollbarDraggingGutterWidth)
        rule.runOnIdle { assertEquals("A drag should retain the grid column count", 3, state.layoutInfo.visibleItemsInfo.count { it.row == 0 }) }
        scrollbar().performTouchInput {
            moveTo(Offset(center.x, center.y + center.y * 8f))
            up()
        }
        rule.runOnIdle { assertTrue("The expanded thumb must keep the gesture", state.firstVisibleItemIndex > 0) }
        assertEquals(ScrollbarRestingGutterWidth, scrollbar().getUnclippedBoundsInRoot().width)
    }

    private fun scrollbar() = rule.onNodeWithContentDescription(scrollbarLabel)

    @androidx.compose.runtime.Composable
    private fun AppRow(index: Int) {
        Text("App $index", Modifier.fillMaxWidth().height(56.dp))
    }

    private companion object {
        const val VIEWPORT_TAG = "scrollbar-test-viewport"
    }
}

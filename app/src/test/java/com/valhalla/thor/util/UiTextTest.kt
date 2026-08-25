// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import com.valhalla.thor.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The two decisions [UiText] makes that do not need a `Context`.
 *
 * Same split as `LocalePolicyTest`, and for the same reason: there is no Robolectric here, so
 * `asString(Context)` and the `@Composable` overload cannot be called at all from a JVM test. What
 * they delegate to can be, so the delegation targets are what get asserted — [resolvedWith] for the
 * format-argument walk and [asUiText] for the unwrap.
 *
 * What that leaves unpinned, deliberately and on the record: that `asString` actually *calls*
 * `resolved`. Only the compiler checks that.
 */
class UiTextTest {

    @Test
    fun `an argument list with nothing nested is handed back untouched`() {
        val args = arrayOf<Any>("disk is full", 3)

        // Same instance, not merely an equal one: the no-nesting case is every existing caller, and
        // it should not pay an allocation to find that out.
        assertSame(args, args.resolvedWith { error("must not render anything") })
    }

    @Test
    fun `a nested UiText is rendered and everything around it keeps its place`() {
        val args = arrayOf<Any>(1, UiText.DynamicString("Operation not permitted"), "tail")

        val resolved = args.resolvedWith { (it as UiText.DynamicString).value }

        // The middle slot is the whole point: left to String.format it arrives as
        // "DynamicString(value=Operation not permitted)", because DynamicString is a data class.
        assertArrayEquals(arrayOf<Any>(1, "Operation not permitted", "tail"), resolved)
    }

    @Test
    fun `every nested UiText is rendered, not just the first`() {
        val args = arrayOf<Any>(UiText.DynamicString("a"), UiText.DynamicString("b"))

        val resolved = args.resolvedWith { (it as UiText.DynamicString).value + "!" }

        assertArrayEquals(arrayOf<Any>("a!", "b!"), resolved)
    }

    /**
     * The unwrap that gave this helper its name: a [UiTextException] carries its message in
     * [UiTextException.uiText] and leaves `message` null, so formatting one into `error_format`
     * renders a bare "Error: " and tells the user nothing.
     */
    @Test
    fun `a UiTextException reports the text it carries, not an empty error`() {
        val carried = UiText.StringResource(R.string.error_self_skipped)

        assertEquals(carried, UiTextException(carried).asUiText())
    }

    @Test
    fun `any other throwable reports its message through error_format`() {
        assertEquals(
            UiText.StringResource(R.string.error_format, "disk is full"),
            IllegalStateException("disk is full").asUiText()
        )
    }

    @Test
    fun `a throwable with no message still produces a well-formed UiText`() {
        assertEquals(
            UiText.StringResource(R.string.error_format, ""),
            IllegalStateException().asUiText()
        )
    }
}

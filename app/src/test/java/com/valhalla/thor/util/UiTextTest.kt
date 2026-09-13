// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import com.valhalla.thor.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // ---------------------------------------------------------------------------------------------
    // equals / hashCode.
    //
    // Hand-written, because `vararg val args: Any` gives a data class array identity semantics and
    // two separately-built StringResources for the same message would compare unequal. Which makes
    // these the load-bearing pieces they are: several hundred assertions across this suite are
    // `assertEquals(MainSideEffect.Message(UiText.StringResource(res, name)), effect)`, and every one
    // of them is really a test of the code below. Nothing covered them until now — a regression here
    // would not fail loudly, it would make a large number of unrelated assertions stop discriminating.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `two separately built StringResources with the same content are equal`() {
        val a = UiText.StringResource(R.string.error_format, "disk is full", 3)
        val b = UiText.StringResource(R.string.error_format, "disk is full", 3)

        // Distinct `args` arrays: the whole reason equals is written out by hand.
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `StringResources differing only in an argument are not equal`() {
        assertNotEquals(
            UiText.StringResource(R.string.frozen_success, "App A"),
            UiText.StringResource(R.string.frozen_success, "App B")
        )
    }

    @Test
    fun `StringResources differing only in resId are not equal`() {
        assertNotEquals(
            UiText.StringResource(R.string.frozen_success, "App A"),
            UiText.StringResource(R.string.unfrozen_success, "App A")
        )
    }

    /**
     * The failure this would let through is the quiet one: a toast asserted as "frozen" while the
     * code emits "unfrozen" is exactly the freeze/unfreeze confusion several of these tests exist to
     * catch, and it only stays caught while a same-arity, same-argument pair of different resources
     * compares unequal.
     */
    @Test
    fun `a StringResource never equals a PluralsResource or a DynamicString`() {
        val single = UiText.StringResource(R.string.frozen_success, "App A")

        assertNotEquals(single, UiText.PluralsResource(R.plurals.component_restricted_count, 1))
        assertNotEquals(single, UiText.DynamicString("App A"))
        assertNotEquals(single, "App A")
    }

    @Test
    fun `PluralsResources are compared on quantity as well as resId`() {
        val one = UiText.PluralsResource(R.plurals.component_restricted_count, 1)
        val two = UiText.PluralsResource(R.plurals.component_restricted_count, 2)

        assertNotEquals(one, two)
        assertEquals(one, UiText.PluralsResource(R.plurals.component_restricted_count, 1))
        assertEquals(
            one.hashCode(),
            UiText.PluralsResource(R.plurals.component_restricted_count, 1).hashCode()
        )
    }

    /**
     * ⚠️ A trap worth pinning rather than fixing.
     *
     * `formatArgs` substitutes `quantity` as the sole format argument when `args` is empty, so these
     * two render the *same* sentence — but `equals` compares `args`, which differ, so they are not
     * equal. A test that writes the explicit form while the code emits the implicit one therefore
     * fails with two identical-looking values in the diff.
     *
     * Left as-is deliberately: making them equal would mean `equals` comparing `formatArgs`, and then
     * a genuine `PluralsResource(res, 2, 5)` — "2" selecting the plural form, "5" filling `%d` —
     * would collide with nothing meaningful. The `toString` added alongside this test is what makes
     * the diff readable, since it prints `quantity` and `args` separately.
     */
    @Test
    fun `an implicit quantity argument and an explicit one are not interchangeable`() {
        val implicit = UiText.PluralsResource(R.plurals.component_restricted_count, 5)
        val explicit = UiText.PluralsResource(R.plurals.component_restricted_count, 5, 5)

        assertNotEquals(implicit, explicit)
        // …and the diff a failing assertion prints distinguishes them, which is the point.
        assertNotEquals(implicit.toString(), explicit.toString())
    }

    // ---------------------------------------------------------------------------------------------
    // toString
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a StringResource names its resource rather than its identity`() {
        val rendered = UiText.StringResource(R.string.frozen_success, "App A").toString()

        // The defect this replaces printed "com.valhalla.thor.util.UiText$StringResource@4f2a1c",
        // obfuscated further in release. An unresolved resId is not a sentence, but it is findable.
        assertEquals(
            "UiText.StringResource(resId=${R.string.frozen_success}, args=[App A])",
            rendered
        )
        assertEquals(
            "UiText.StringResource(resId=${R.string.error_self_skipped})",
            UiText.StringResource(R.string.error_self_skipped).toString()
        )
    }

    /**
     * `message` must stay null, and this is the test that says so.
     *
     * A dozen handlers still render a failure as `error_format` over `e.message ?: ""`. Giving this
     * exception a message would put `UiText.StringResource(resId=…)` into a user-facing toast at
     * every one of them — so the diagnostic belongs in `toString`, which only logs and stack traces
     * read. Someone "completing" this class by passing a message to `Exception(…)` would be making
     * twelve toasts worse, silently, and no other test in the suite would notice.
     */
    @Test
    fun `UiTextException keeps a null message and puts its diagnostic in toString`() {
        val e = UiTextException(UiText.StringResource(R.string.error_unsafe_skipped))

        assertNull(e.message)
        assertTrue(
            "toString should name the carried UiText, was: $e",
            e.toString() == "UiTextException(UiText.StringResource(resId=${R.string.error_unsafe_skipped}))"
        )
    }
}

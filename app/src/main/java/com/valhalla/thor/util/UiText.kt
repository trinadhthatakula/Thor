// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.valhalla.thor.R

sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    class StringResource(
        @param:StringRes val resId: Int,
        vararg val args: Any
    ) : UiText() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StringResource) return false
            if (resId != other.resId) return false
            if (!args.contentEquals(other.args)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = resId
            result = 31 * result + args.contentHashCode()
            return result
        }

        /**
         * Names the resource rather than the object.
         *
         * The absence of this is what made the nested-format-argument bug *invisible* rather than
         * merely wrong: a `UiText` that reached `String.format` printed as
         * `com.valhalla.thor.util.UiText$StringResource@4f2a1c`, which reads like a crash artefact
         * instead of "someone passed the wrong type here", and minification shortens it further.
         * [Array.resolvedWith] now stops such an argument reaching `String.format` at all; this
         * makes the *next* leak — into a log line, a string template, an assertion diff — legible.
         *
         * [resId] stays an unresolved int, because resolving it to a name needs a `Context` this
         * object does not have. It still identifies the string: the same int appears in the
         * generated `R` class and in the expected value of a failing assertion.
         */
        override fun toString(): String =
            if (args.isEmpty()) "UiText.StringResource(resId=$resId)"
            else "UiText.StringResource(resId=$resId, args=${args.contentToString()})"
    }

    /**
     * A quantity string ([`<plurals>`][android.content.res.Resources.getQuantityString]).
     *
     * When [args] is empty, [quantity] is used as the sole format argument — the common case
     * where the count is both the plural selector and the `%d` placeholder.
     */
    class PluralsResource(
        @param:PluralsRes val resId: Int,
        val quantity: Int,
        vararg val args: Any
    ) : UiText() {
        private val formatArgs: Array<out Any>
            get() = if (args.isEmpty()) arrayOf(quantity) else args

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PluralsResource) return false
            if (resId != other.resId) return false
            if (quantity != other.quantity) return false
            if (!args.contentEquals(other.args)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = resId
            result = 31 * result + quantity
            result = 31 * result + args.contentHashCode()
            return result
        }

        /**
         * Same reasoning as [StringResource.toString], plus [quantity] — which [equals] compares
         * but [formatArgs] can silently stand in for, so a diff that shows only `args` would leave
         * out the half that actually differs.
         */
        override fun toString(): String =
            "UiText.PluralsResource(resId=$resId, quantity=$quantity, args=${args.contentToString()})"

        @Composable
        fun resolve(): String =
            if (formatArgs.any { it is UiText }) resolve(LocalContext.current)
            else pluralStringResource(resId, quantity, *formatArgs)

        fun resolve(context: Context): String =
            context.resources.getQuantityString(resId, quantity, *formatArgs.resolved(context))
    }

    @Composable
    fun asString(): String {
        return when (this) {
            is DynamicString -> value
            // Delegating rather than mapping the args in place: resolving a nested [UiText] means
            // calling the @Composable overload from inside a `map` lambda, which is not composable.
            is StringResource ->
                if (args.any { it is UiText }) asString(LocalContext.current)
                else stringResource(resId, *args)
            is PluralsResource -> resolve()
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args.resolved(context))
            is PluralsResource -> resolve(context)
        }
    }
}

/**
 * Renders any [UiText] sitting in a format-argument list before it reaches `String.format`.
 *
 * `error_format` and `log_failed` are both `%1$s`, and a view model composing one of them has no
 * `Context` with which to render an inner [UiText.StringResource] — so the inner message has to
 * travel *as* a `UiText` and can only be resolved at the point of display, which is here.
 *
 * Without this, `String.format` falls back to `toString()` on the argument.
 * [UiText.DynamicString] is a data class, so the user reads `Error: DynamicString(value=…)`;
 * [UiText.StringResource] declares none, so they read
 * `Error: com.valhalla.thor.util.UiText$StringResource@…`, which minification shortens without
 * making it any more meaningful. Both were reachable from the Apps tab's quick actions.
 *
 * Returns `this` untouched in the overwhelmingly common no-nesting case, so the allocation only
 * happens where it is needed.
 */
private fun Array<out Any>.resolved(context: Context): Array<out Any> =
    resolvedWith { it.asString(context) }

/**
 * [resolved] with the rendering step handed in, which is the whole of it that a unit test can reach.
 *
 * `asString(Context)` cannot be called from a JVM unit test — this module has no Robolectric, so
 * every `android.content.Context` member throws "not mocked" — and the mapping is the part that can
 * silently regress to `toString()`. Splitting it out means the array walk is pinned by a test even
 * though its one production caller is not.
 */
internal fun Array<out Any>.resolvedWith(render: (UiText) -> String): Array<out Any> =
    if (none { it is UiText }) this
    else map { if (it is UiText) render(it) else it }.toTypedArray()

class UiTextException(val uiText: UiText) : Exception() {
    /**
     * A diagnostic in [toString], deliberately **not** in `message`.
     *
     * `message` has to stay null. A dozen handlers still render a failure as
     * `StringResource(error_format, e.message ?: "")`, so giving this exception a message would put
     * `UiText.StringResource(resId=…)` on screen in a toast — trading an empty error for a worse
     * one. Those handlers are correct as they stand, because this type is only ever *returned* in a
     * `Result.failure` by the freeze gates, and every site that can receive one already calls
     * [asUiText]; a `message` would change what the other twelve print without fixing anything.
     *
     * `toString` reaches `Logger`, `printStackTrace` and debugger views, and none of those read
     * `message`. So the diagnostic goes where it costs nothing user-facing — until now a swallowed
     * `UiTextException` logged as a bare `UiTextException` with no stated reason at all.
     */
    override fun toString(): String = "UiTextException($uiText)"
}

/**
 * The message to show for a throw, whichever kind of throw it is.
 *
 * Exists because [UiTextException] carries its rendered message in [UiTextException.uiText] and
 * leaves `message` null, so `error_format` applied to one renders a bare "Error: " — a toast that
 * tells the user something failed and nothing else. Every handler that formats an exception therefore
 * has to ask, and the ones that forgot were not distinguishable by reading them: the omission only
 * surfaces at runtime, on a refusal, as an empty error.
 *
 * A function rather than the same `if` repeated at each catch, because the repetition is what let the
 * sites drift apart in the first place — the freezer surfaces had it in three places and not in six,
 * and which behaviour you got depended on which screen you tapped.
 *
 * Covers both ways a refusal reaches a handler, since a surface can see either. A tier refusal is
 * *returned* — `FreezeAppUseCase` wraps it in a failed `Result` rather than throwing it — and reaches
 * `Result.onFailure`; anything else that goes wrong in the same block is *thrown* and reaches the
 * `launchGuarded` catch instead. Two sites unwrapping the same type by hand is what produced the
 * empty-toast asymmetry, so both go through here.
 */
fun Throwable.asUiText(): UiText =
    if (this is UiTextException) uiText
    else UiText.StringResource(R.string.error_format, message ?: "")

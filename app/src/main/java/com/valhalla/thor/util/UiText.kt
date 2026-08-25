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

class UiTextException(val uiText: UiText) : Exception()

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

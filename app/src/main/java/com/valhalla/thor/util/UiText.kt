// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

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
        fun resolve(): String = pluralStringResource(resId, quantity, *formatArgs)

        fun resolve(context: Context): String =
            context.resources.getQuantityString(resId, quantity, *formatArgs)
    }

    @Composable
    fun asString(): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> stringResource(resId, *args)
            is PluralsResource -> resolve()
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args)
            is PluralsResource -> resolve(context)
        }
    }
}

class UiTextException(val uiText: UiText) : Exception()

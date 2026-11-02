// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.annotation.SuppressLint
import android.os.Build
import com.valhalla.bypass.Bypass
import com.valhalla.thor.util.Logger

/** The system renders and handles Unpause, including its localized default button label. */
@SuppressLint("PrivateApi")
internal fun buildSuspendDialogInfo(message: String, title: String): Any? = runCatching {
    val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
    val builder = Bypass.newInstance<Any>(builderClass)
    Bypass.invoke<Any?>(builderClass, builder, "setMessage", arrayOf(String::class.java), message)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val dialogClass = Class.forName("android.content.pm.SuspendDialogInfo")
        val action = dialogClass.getField("BUTTON_ACTION_UNSUSPEND").getInt(null)
        Bypass.invoke<Any?>(
            builderClass, builder, "setNeutralButtonAction",
            arrayOf(Int::class.javaPrimitiveType!!), action,
        )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Bypass.invoke<Any?>(builderClass, builder, "setTitle", arrayOf(String::class.java), title)
    }
    Bypass.invoke<Any>(builderClass, builder, "build")
}.getOrElse { error ->
    // Customization is optional; a ROM that rejects it must still be able to suspend the app.
    Logger.w("SuspendDialog", "Custom suspension dialog unavailable: $error")
    null
}

/** Complete fixed resource strings, including dialogs created before a language switch. */
internal fun knownThorSuspendDialogs(context: android.content.Context): Set<String> =
    listOf("en", "ar", "es", "fr", "ja", "pl", "pt", "pt-BR", "zh-CN").mapNotNull { tag ->
        val configuration = android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(tag))
        }
        val resources = context.createConfigurationContext(configuration).resources
        buildSuspendDialogInfo(
            resources.getString(com.valhalla.thor.R.string.suspended_app_dialog_message),
            resources.getString(com.valhalla.thor.R.string.suspended_app_dialog_title),
        )?.toString()
    }.toSet()

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.bypass.Bypass
import com.valhalla.thor.R
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol
import com.valhalla.thor.rootservice.parseSuspensionDump
import com.valhalla.thor.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SuspendDialogLocaleTest {
    @Test
    fun previousLanguageDialogsRemainTrustedOnlyForTrustedOwners() {
        val sdk = Build.VERSION.SDK_INT
        assumeTrue(sdk in 29..37)
        Bypass.prepareThor()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val english = context.withLocale("en")
        val russian = context.withLocale("ru")
        assertNotEquals(
            english.getString(R.string.suspended_app_dialog_message),
            russian.getString(R.string.suspended_app_dialog_message),
        )

        // The current app language is English; dialogs recorded in any earlier language must
        // remain recognizable without making arbitrary owners or messages trustworthy.
        val trusted = knownThorSuspendDialogs(english)
        for (language in AppLanguage.entries) {
            val tag = language.tag ?: continue
            val localized = context.withLocale(tag)
            val dialog = requireNotNull(buildSuspendDialogInfo(
                localized.getString(R.string.suspended_app_dialog_message),
                localized.getString(R.string.suspended_app_dialog_title),
            )) { "Could not build the $tag suspension dialog" }.toString()
            assertTrue("Missing trusted dialog for $tag", dialog in trusted)
        }

        val dialog = requireNotNull(buildSuspendDialogInfo(
            russian.getString(R.string.suspended_app_dialog_message),
            russian.getString(R.string.suspended_app_dialog_title),
        )).toString()
        val owner = context.packageName
        val target = "com.example.target"
        fun read(suspender: String, value: String) = parseSuspensionDump(
            output = dump(target, suspender, value, sdk),
            packageName = target,
            userId = 0,
            sdkInt = sdk,
            trustedStringOwners = setOf(owner),
            trustedDialogs = trusted,
        )
        assertEquals(SuspensionReadbackProtocol.STATUS_SUSPENDED, read(owner, dialog).status)
        assertEquals(SuspensionReadbackProtocol.REASON_AMBIGUOUS_DIALOG,
            read("com.example.other", dialog).reason)
        assertEquals(SuspensionReadbackProtocol.REASON_AMBIGUOUS_DIALOG,
            read(owner, dialog.replace("Thor", "other")).reason)
    }

    private fun Context.withLocale(tag: String): Context = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
    )

    private fun dump(target: String, owner: String, dialog: String, sdk: Int): String {
        val ownerKey = if (sdk >= 35) "<0>$owner" else owner
        val user = if (sdk < 30) {
            "    User 0: installed=true suspended=true suspendingPackage=$ownerKey dialogInfo=$dialog"
        } else {
            "    User 0: installed=true suspended=true\n" +
                "    Suspend params:\n      suspendingPackage=$ownerKey dialogInfo=$dialog"
        }
        return "Packages:\n  Package [$target] (a1):\n$user\nQueries:\n"
    }
}

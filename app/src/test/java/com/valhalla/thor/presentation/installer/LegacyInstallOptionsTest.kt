// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.installer

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppMetadata
import com.valhalla.thor.domain.repository.InstallMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class LegacyInstallOptionsTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun legacyRootInstallShowsWarningWhenAutomaticApprovalIsOff() {
        setOptions()

        rule.onNodeWithText(description()).assertIsDisplayed()
        rule.onNodeWithText(allowedBySetting()).assertDoesNotExist()
    }

    @Test
    fun savedAutomaticApprovalIsExplainedForSupportedInstaller() {
        setOptions(automaticallyAllowed = true)

        rule.onNodeWithText(description()).assertIsDisplayed()
        rule.onNodeWithText(allowedBySetting()).assertIsDisplayed()
    }

    @Test
    fun confirmationDialogCallsConfirmForThisPackage() {
        var confirmations = 0
        var dismissals = 0
        setConfirmationDialog(
            onConfirm = { confirmations++ },
            onDismiss = { dismissals++ },
        )

        rule.onNodeWithText(confirmTitle()).assertIsDisplayed()
        rule.onNodeWithText(confirmMessage()).assertIsDisplayed()
        rule.onNodeWithText(allowOnce()).performClick()

        rule.runOnIdle {
            assertEquals(1, confirmations)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun confirmationDialogCancelCallsDismissWithoutConfirming() {
        var confirmations = 0
        var dismissals = 0
        setConfirmationDialog(
            onConfirm = { confirmations++ },
            onDismiss = { dismissals++ },
        )

        rule.onNodeWithText(context().getString(R.string.cancel)).performClick()

        rule.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun everyUnsupportedInstallerModeExplainsWhyItCannotBypass() {
        val selectedMode = mutableStateOf(InstallMode.NORMAL)
        rule.setContent {
            MaterialTheme {
                LegacyInstallOptions(
                    meta = legacyMeta(),
                    mode = selectedMode.value,
                    automaticallyAllowed = true,
                    deviceSdk = DEVICE_SDK,
                )
            }
        }

        for (mode in listOf(InstallMode.NORMAL, InstallMode.DHIZUKU, InstallMode.EXTERNAL)) {
            rule.runOnIdle { selectedMode.value = mode }
            rule.onNodeWithText(description()).assertIsDisplayed()
            rule.onNodeWithText(unsupported()).assertIsDisplayed()
            rule.onNodeWithText(allowedBySetting()).assertDoesNotExist()
        }
    }

    @Test
    fun warningIsHiddenBeforeAndroid14ForSafeAndUnknownTargets() {
        rule.setContent {
            MaterialTheme {
                LegacyInstallOptions(legacyMeta(), InstallMode.ROOT, false, deviceSdk = 33)
                LegacyInstallOptions(legacyMeta(targetSdk = 23), InstallMode.ROOT, false, deviceSdk = DEVICE_SDK)
                LegacyInstallOptions(legacyMeta(targetSdk = null), InstallMode.ROOT, false, deviceSdk = DEVICE_SDK)
            }
        }

        rule.onNodeWithText(description()).assertDoesNotExist()
        rule.onNodeWithText(unsupported()).assertDoesNotExist()
    }

    @Test
    fun warningAndAutomaticApprovalExplanationRemainVisibleOnNarrowLargeTextSurface() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                MaterialTheme {
                    Surface(Modifier.width(280.dp)) {
                        LegacyInstallOptions(legacyMeta(), InstallMode.ROOT, true, deviceSdk = DEVICE_SDK)
                    }
                }
            }
        }

        rule.onNodeWithText(description()).assertIsDisplayed()
        rule.onNodeWithText(allowedBySetting()).assertIsDisplayed()
    }

    private fun setOptions(
        mode: InstallMode = InstallMode.ROOT,
        automaticallyAllowed: Boolean = false,
    ) {
        rule.setContent {
            MaterialTheme {
                LegacyInstallOptions(legacyMeta(), mode, automaticallyAllowed, DEVICE_SDK)
            }
        }
    }

    private fun setConfirmationDialog(
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        rule.setContent {
            MaterialTheme {
                LegacyInstallConfirmationDialog(
                    meta = legacyMeta(),
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    deviceSdk = DEVICE_SDK,
                )
            }
        }
    }

    private fun legacyMeta(
        label: String = "Example",
        packageName: String = "com.example.legacy",
        targetSdk: Int? = 22,
    ) = AppMetadata(
        label = label,
        packageName = packageName,
        version = "1.0",
        versionCode = 1L,
        iconPath = null,
        targetSdk = targetSdk,
    )

    private fun context() = ApplicationProvider.getApplicationContext<Application>()

    private fun allowOnce() = context().getString(R.string.legacy_install_allow_once)
    private fun confirmTitle() = context().getString(R.string.legacy_install_confirm_title)
    private fun unsupported() = context().getString(R.string.legacy_install_unsupported)
    private fun allowedBySetting() = context().getString(R.string.legacy_install_allowed_by_setting)
    private fun description() = context().getString(
        R.string.legacy_install_description, "com.example.legacy", 22, 23,
    )
    private fun confirmMessage() = context().getString(
        R.string.legacy_install_confirm_message, "Example", "com.example.legacy", 22, 23,
    )

    private companion object {
        const val DEVICE_SDK = 34
    }
}

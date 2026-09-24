// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppOpsCommandsTest {
    @Test
    fun scopesEveryReadToTheChosenAndroidUser() {
        assertEquals("appops get --user 10 com.example.app", AppOpsCommands.getPackage("com.example.app", 10))
        assertEquals("appops get --user 10 1010218", AppOpsCommands.getUid(1010218, 10))
        assertEquals("appops get --user 0 android 26", AppOpsCommands.getPackage("android", 0, 26))
    }

    @Test
    fun uidWriteUsesNumericUidForApi28Compatibility() {
        assertEquals(
            "appops set --user 10 1010218 26 ignore",
            AppOpsCommands.setMode("com.example.app", 1010218, 10, 26, AppOpScope.UID, AppOpMode.IGNORE),
        )
    }

    @Test
    fun platformResetAllowDoesNotSendLiteralDefault() {
        assertEquals(
            "appops set --user 0 com.example.app 26 allow",
            AppOpsCommands.setMode("com.example.app", 10218, 0, 26, AppOpScope.PACKAGE, AppOpMode.ALLOW),
        )
        assertEquals(
            "appops set --user 0 com.example.app 26 default",
            AppOpsCommands.setMode("com.example.app", 10218, 0, 26, AppOpScope.PACKAGE, AppOpMode.DEFAULT),
        )
    }

    @Test
    fun rejectsWrongUserBeforeBuildingAnyWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.setMode("com.example.app", 1010218, 0, 26, AppOpScope.PACKAGE, AppOpMode.ALLOW)
        }
        assertThrows(IllegalArgumentException::class.java) { AppOpsCommands.getUid(10218, 10) }
        assertThrows(IllegalArgumentException::class.java) { AppOpsCommands.getPackage("com.example.app", -1) }
    }

    @Test
    fun rejectsShellSyntaxAndUidLikePackageTokens() {
        listOf("10218", "u0a218", "u10s1000", "u0a123suffix", "root", "shell", "com.example; id", "com.example\nwhoami", "--user", "com.example/app").forEach { invalid ->
            assertThrows("Accepted unsafe target $invalid", IllegalArgumentException::class.java) {
                AppOpsCommands.getPackage(invalid, 0)
            }
        }
    }

    @Test
    fun rejectsUnknownModesAndNegativeOperationCodes() {
        assertThrows(IllegalArgumentException::class.java) {
            AppOpsCommands.setMode("com.example.app", 10218, 0, 26, AppOpScope.PACKAGE, AppOpMode.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) { AppOpsCommands.getPackage("com.example.app", 0, -1) }
    }
}

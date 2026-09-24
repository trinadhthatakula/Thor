// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.permission

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.valhalla.thor.domain.model.AppPermission
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class PermissionRowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `normal and signature permissions show status without a grant switch`() {
        var toggles = 0
        rule.setContent {
            MaterialTheme {
                Surface {
                    Column {
                        listOf("android.permission.INTERNET", "android.permission.BIND_DEVICE_ADMIN").forEach { name ->
                            PermissionRow(permission(name, isRuntime = false), isPrivilegeMode = true) {
                                toggles++
                            }
                        }
                    }
                }
            }
        }

        rule.onAllNodes(switchMatcher).assertCountEquals(0)
        rule.onAllNodesWithText("Denied").assertCountEquals(2)
        assertEquals(0, toggles)
    }

    @Test
    fun `runtime permission retains its switch when privileged`() {
        val toggles = mutableListOf<Boolean>()
        rule.setContent {
            MaterialTheme {
                Surface {
                    PermissionRow(permission("android.permission.CAMERA", isRuntime = true), true) {
                        toggles += it
                    }
                }
            }
        }

        rule.onAllNodes(switchMatcher).assertCountEquals(1)
        rule.onAllNodes(switchMatcher)[0].performClick()
        assertEquals(listOf(true), toggles)
    }

    private fun permission(name: String, isRuntime: Boolean) = AppPermission(
        name = name,
        label = name.substringAfterLast('.'),
        description = "",
        group = null,
        isGranted = false,
        isRuntime = isRuntime,
        protectionLevel = 0,
    )

    private companion object {
        val switchMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    }
}

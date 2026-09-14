// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.source.local.isEffectivelyEnabled
import com.valhalla.thor.data.source.local.isHiddenForUser
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class HiddenAppRestoreTest {

    @Test
    fun `Root unhides before enabling a hidden disabled system app for the same user`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = installHidden(context, system = true, enabled = false)
        val commands = mutableListOf<RootCommand>()
        val gateway = rootGateway(context) { command ->
            commands += command
            when {
                command.text.startsWith("pm unhide ") -> ReflectionHelpers.setField(info, "privateFlags", 0)
                command.text.startsWith("pm enable ") -> info.enabled = true
            }
            RootCommandResult(0, emptyList(), emptyList())
        }

        gateway.setAppDisabled(PACKAGE, false, PrivilegeExecutionContext()).getOrThrow()

        assertEquals(
            listOf("pm unhide --user 10 '$PACKAGE'", "pm enable --user 10 '$PACKAGE'"),
            commands.map { it.text },
        )
        assertTrue(info.isEffectivelyEnabled)
    }

    @Test
    fun `Root refuses a successful unhide command that leaves either app kind hidden`() = runTest {
        for (system in listOf(false, true)) {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val info = installHidden(context, system)
            val commands = mutableListOf<RootCommand>()
            val gateway = rootGateway(context) { command ->
                commands += command
                RootCommandResult(0, emptyList(), emptyList())
            }

            val result = gateway.setAppDisabled(PACKAGE, false, PrivilegeExecutionContext())

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("still hidden"))
            assertEquals(listOf("pm unhide --user 10 '$PACKAGE'"), commands.map { it.text })
            assertTrue(info.isHiddenForUser)
        }
    }

    @Test
    fun `Shizuku reports device-policy recovery modes when shell cannot unhide`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = installHidden(context, system = false)
        val commands = mutableListOf<String>()
        val gateway = shizukuGateway(context).apply {
            unhideCommandExecutor = { command ->
                commands += command
                255 to "Requires MANAGE_USERS"
            }
        }

        val result = gateway.setAppDisabled(PACKAGE, false, PrivilegeExecutionContext())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Dhizuku or Root"))
        assertEquals(listOf("pm unhide --user $thorUserId '$PACKAGE'"), commands)
        assertTrue(info.isHiddenForUser)
    }

    @Test
    fun `Root-backed Shizuku accepts a verified unhide even when the command reports failure`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = installHidden(context, system = true)
        val gateway = shizukuGateway(context).apply {
            unhideCommandExecutor = {
                ReflectionHelpers.setField(info, "privateFlags", 0)
                1 to "late command failure"
            }
        }

        gateway.setAppDisabled(PACKAGE, false, PrivilegeExecutionContext()).getOrThrow()

        assertFalse(info.isHiddenForUser)
        assertTrue(info.isEffectivelyEnabled)
    }

    private fun installHidden(
        context: Application,
        system: Boolean,
        enabled: Boolean = true,
    ): ApplicationInfo {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = PACKAGE
                this.enabled = enabled
                flags = ApplicationInfo.FLAG_INSTALLED or (if (system) ApplicationInfo.FLAG_SYSTEM else 0)
                ReflectionHelpers.setField(this, "privateFlags", 1)
            }
        })
        return requireNotNull(
            shadowOf(context.packageManager).getInternalMutablePackageInfo(PACKAGE).applicationInfo,
        )
    }

    private fun rootGateway(
        context: Application,
        commandResult: (RootCommand) -> RootCommandResult,
    ) = RootSystemGateway(
        context = context,
        rootCommands = object : RootCommandExecutor {
            override suspend fun execute(command: RootCommand): RootCommandResult = commandResult(command)
        },
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
    ).apply { userIdProvider = { 10 } }

    private fun shizukuGateway(context: Application) = ShizukuSystemGateway(
        context = context,
        reflector = ShizukuReflector(context),
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private companion object {
        const val PACKAGE = "com.example.hidden"
    }
}

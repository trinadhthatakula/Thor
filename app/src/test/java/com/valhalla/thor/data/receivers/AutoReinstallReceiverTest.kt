// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.app.Application
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.valhalla.thor.data.repository.installerPackageNameOf
import com.valhalla.thor.data.repository.readInstallerOfRecord
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class AutoReinstallReceiverTest {
    @Test
    fun `Dhizuku and no privilege skip installer queries and commands`() = runTest {
        for (mode in listOf(PrivilegeMode.DHIZUKU, PrivilegeMode.NONE)) {
            assertFalse(
                preserveGooglePlayInstaller(
                    PACKAGE, provider(mode),
                    currentInstaller = { error("Unsupported mode must not inspect packages") },
                    execute = { error("Unsupported mode must not attempt set-installer") },
                )
            )
        }
    }

    @Test
    fun `cold start waits for the real privilege result`() = runTest {
        val privilege = FakePrivilegeStateProvider()
        var reads = 0
        val result = async {
            preserveGooglePlayInstaller(
                PACKAGE, privilege,
                currentInstaller = { reads++; PLAY },
                execute = { error("Already attributed packages need no command") },
            )
        }
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(0, reads)
        privilege.emit(PrivilegeState(active = PrivilegeMode.ROOT, isReady = true))
        assertTrue(result.await())
        assertEquals(1, reads)
    }

    @Test
    fun `Root and Shizuku verify the updated installer`() = runTest {
        for (mode in listOf(PrivilegeMode.ROOT, PrivilegeMode.SHIZUKU)) {
            var installer: String? = null
            var commands = 0
            assertTrue(
                preserveGooglePlayInstaller(
                    PACKAGE, provider(mode),
                    currentInstaller = { installer },
                    execute = {
                        commands++
                        installer = PLAY
                        Result.success(0 to "")
                    },
                )
            )
            assertEquals(1, commands)
        }
    }

    @Test
    fun `zero command exit without installer readback is not success`() = runTest {
        var reads = 0
        assertFalse(
            preserveGooglePlayInstaller(
                PACKAGE, provider(PrivilegeMode.ROOT),
                currentInstaller = { reads++; "com.rosan.dhizuku" },
                execute = { Result.success(0 to "Success") },
            )
        )
        assertEquals(2, reads)
    }

    @Test
    fun `historical Play initiation does not satisfy installer attribution`() = runTest {
        val manager = ApplicationProvider.getApplicationContext<Application>().packageManager
        val packages = shadowOf(manager)
        packages.installPackage(PackageInfo().apply { packageName = PACKAGE })
        packages.setInstallSourceInfo(PACKAGE, PLAY, null)
        assertNull(manager.readInstallerOfRecord(PACKAGE))
        assertEquals("Display fallback stays unchanged", PLAY, manager.installerPackageNameOf(PACKAGE))

        var commands = 0
        assertFalse(
            preserveGooglePlayInstaller(
                PACKAGE, provider(PrivilegeMode.ROOT),
                currentInstaller = { manager.readInstallerOfRecord(PACKAGE) },
                execute = { commands++; Result.success(0 to "") },
            )
        )
        assertEquals("Historical initiation must not skip the setter", 1, commands)

        assertTrue(
            preserveGooglePlayInstaller(
                PACKAGE, provider(PrivilegeMode.ROOT),
                currentInstaller = { manager.readInstallerOfRecord(PACKAGE) },
                execute = {
                    packages.setInstallSourceInfo(PACKAGE, PLAY, PLAY)
                    Result.success(0 to "")
                },
            )
        )
        assertEquals(PLAY, manager.readInstallerOfRecord(PACKAGE))
    }

    @Test
    fun `command failure cannot report successful attribution`() = runTest {
        var reads = 0
        assertFalse(
            preserveGooglePlayInstaller(
                PACKAGE, provider(PrivilegeMode.SHIZUKU),
                currentInstaller = { reads++; null },
                execute = { Result.success(1 to "Denied") },
            )
        )
        assertEquals(1, reads)
    }

    @Test
    fun `cancelled command retains cancellation identity`() = runTest {
        val cancellation = CancellationException("cancelled")
        val outcome = runCatching {
            preserveGooglePlayInstaller(
                PACKAGE, provider(PrivilegeMode.ROOT),
                currentInstaller = { null },
                execute = { Result.failure(cancellation) },
            )
        }
        assertSame(cancellation, outcome.exceptionOrNull())
    }

    private fun provider(mode: PrivilegeMode) =
        FakePrivilegeStateProvider(PrivilegeState(active = mode, isReady = true))

    private companion object {
        const val PACKAGE = "com.example.app"
        const val PLAY = "com.android.vending"
    }
}

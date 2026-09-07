// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.gateway.root.RootCommand
import com.valhalla.thor.data.gateway.root.RootCommandExecutor
import com.valhalla.thor.data.gateway.root.RootCommandResult
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.data.source.local.shizuku.ShizukuReflector
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.ReinstallPostconditionFailed as DomainReinstallPostconditionFailed
import com.valhalla.thor.presentation.FakeContext
import com.valhalla.thor.presentation.FakePreferenceRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReinstallPostconditionTest {

    @Test
    fun `reinstall success requires package installed for requested user`() = runTest {
        val reader = RecordingReinstallStateReader(
            ReinstallFinalState(
                installedForThorUser = false,
                installerPackageName = GOOGLE_PLAY,
            )
        )
        val verifier = ReinstallPostconditionVerifier(reader)

        val result = verifier.verify(PACKAGE, USER_ID)

        assertTrue(result.exceptionOrNull() is DomainReinstallPostconditionFailed)
        assertEquals(listOf(PACKAGE to USER_ID), reader.reads)
    }

    @Test
    fun `reinstall success requires Google Play install source`() = runTest {
        val verifier = ReinstallPostconditionVerifier(
            RecordingReinstallStateReader(
                ReinstallFinalState(
                    installedForThorUser = true,
                    installerPackageName = "com.example.other.store",
                )
            )
        )

        val result = verifier.verify(PACKAGE, USER_ID)

        assertTrue(result.exceptionOrNull() is DomainReinstallPostconditionFailed)
    }

    @Test
    fun `exit zero with failed postcondition is failure`() = runTest {
        val commands = SuccessfulReinstallCommandExecutor()
        val verifier = ReinstallPostconditionVerifier(
            RecordingReinstallStateReader(
                ReinstallFinalState(
                    installedForThorUser = true,
                    installerPackageName = null,
                )
            )
        )

        val result = gateway(commands, verifier).reinstallAppWithGoogle(
            PACKAGE,
            PrivilegeExecutionContext(),
        )

        assertTrue(result.exceptionOrNull() is DomainReinstallPostconditionFailed)
        assertEquals(2, commands.commands.size)
    }

    @Test
    fun `repeating a verified reinstall remains success`() = runTest {
        val commands = SuccessfulReinstallCommandExecutor()
        val reader = RecordingReinstallStateReader(
            ReinstallFinalState(
                installedForThorUser = true,
                installerPackageName = GOOGLE_PLAY,
            )
        )
        val gateway = gateway(commands, ReinstallPostconditionVerifier(reader))

        val first = gateway.reinstallAppWithGoogle(PACKAGE, PrivilegeExecutionContext())
        val replay = gateway.reinstallAppWithGoogle(PACKAGE, PrivilegeExecutionContext())

        assertTrue(first.isSuccess)
        assertTrue(replay.isSuccess)
        assertEquals(4, commands.commands.size)
        assertEquals(listOf(PACKAGE to USER_ID, PACKAGE to USER_ID), reader.reads)
    }

    @Test
    fun `Thor package is rejected before command execution`() = runTest {
        val commands = SuccessfulReinstallCommandExecutor()
        val reader = RecordingReinstallStateReader(
            ReinstallFinalState(
                installedForThorUser = true,
                installerPackageName = GOOGLE_PLAY,
            )
        )

        val result = gateway(commands, ReinstallPostconditionVerifier(reader))
            .reinstallAppWithGoogle(BuildConfig.APPLICATION_ID, PrivilegeExecutionContext())

        assertTrue(result.isFailure)
        assertTrue(commands.commands.isEmpty())
        assertTrue(reader.reads.isEmpty())
    }

    @Test
    fun `Shizuku path lookup failure does not attempt reinstall`() = runTest {
        val commands = mutableListOf<String>()
        val reader = RecordingReinstallStateReader(VERIFIED_STATE)
        val context = FakeContext(File("."))
        val gateway = ShizukuSystemGateway(
            context = context,
            reflector = ShizukuReflector(context),
            preferenceRepository = FakePreferenceRepository(),
            ioDispatcher = Dispatchers.Unconfined,
            reinstallPostconditionVerifier = ReinstallPostconditionVerifier(reader),
        ).also { gateway ->
            gateway.reinstallUserIdProvider = { USER_ID }
            gateway.reinstallCommandExecutor = { command ->
                commands += command
                -1 to TRANSPORT_FAILURE
            }
        }

        val result = gateway.reinstallAppWithGoogle(PACKAGE, PrivilegeExecutionContext())

        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("pm path --user"))
        assertTrue(commands.none { it.startsWith("pm install") })
        assertEquals(
            "Shizuku package path lookup failed with exit code -1: $TRANSPORT_FAILURE",
            result.exceptionOrNull()?.message,
        )
        assertTrue(reader.reads.isEmpty())
    }

    @Test
    fun `Dhizuku path lookup failure does not attempt reinstall`() = runTest {
        val commands = mutableListOf<String>()
        val reader = RecordingReinstallStateReader(VERIFIED_STATE)
        val context = FakeContext(File("."))
        val gateway = DhizukuSystemGateway(
            context = context,
            reflector = DhizukuReflector(context),
            preferenceRepository = FakePreferenceRepository(),
            ioDispatcher = Dispatchers.Unconfined,
            reinstallPostconditionVerifier = ReinstallPostconditionVerifier(reader),
        ).also { gateway ->
            gateway.reinstallUserIdProvider = { USER_ID }
            gateway.reinstallCommandExecutor = { command ->
                commands += command
                -1 to TRANSPORT_FAILURE
            }
        }

        val result = gateway.reinstallAppWithGoogle(PACKAGE, PrivilegeExecutionContext())

        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("pm path --user"))
        assertTrue(commands.none { it.startsWith("pm install") })
        assertEquals(
            "Dhizuku package path lookup failed with exit code -1: $TRANSPORT_FAILURE",
            result.exceptionOrNull()?.message,
        )
        assertTrue(reader.reads.isEmpty())
    }

    private fun gateway(
        commands: RootCommandExecutor,
        verifier: ReinstallPostconditionVerifier,
    ) = RootSystemGateway(
        context = FakeContext(File(".")),
        rootCommands = commands,
        preferenceRepository = FakePreferenceRepository(),
        ioDispatcher = Dispatchers.Unconfined,
        reinstallPostconditionVerifier = verifier,
    ).also { gateway ->
        gateway.userIdProvider = { USER_ID }
    }

    private class RecordingReinstallStateReader(
        private val state: ReinstallFinalState,
    ) : ReinstallStateReader {
        val reads = mutableListOf<Pair<String, Int>>()

        override suspend fun read(packageName: String, userId: Int): ReinstallFinalState {
            reads += packageName to userId
            return state
        }
    }

    private class SuccessfulReinstallCommandExecutor : RootCommandExecutor {
        val commands = mutableListOf<RootCommand>()

        override suspend fun execute(command: RootCommand): RootCommandResult {
            commands += command
            return if (commands.size % 2 == 1) {
                RootCommandResult(
                    exitCode = 0,
                    stdout = listOf("package:/data/app/example/base.apk"),
                    stderr = emptyList(),
                )
            } else {
                RootCommandResult(exitCode = 0, stdout = emptyList(), stderr = emptyList())
            }
        }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val GOOGLE_PLAY = "com.android.vending"
        const val USER_ID = 10
        const val TRANSPORT_FAILURE = "transport unavailable"
        val VERIFIED_STATE = ReinstallFinalState(
            installedForThorUser = true,
            installerPackageName = GOOGLE_PLAY,
        )
    }
}

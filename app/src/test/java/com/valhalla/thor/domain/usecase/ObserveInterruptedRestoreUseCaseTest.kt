// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.repository.ArchiveBreadcrumb
import com.valhalla.thor.domain.repository.ArchiveBreadcrumbStore
import com.valhalla.thor.domain.repository.ArchiveJobLauncher
import com.valhalla.thor.domain.repository.ThorJobStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * §8.5's notice as a surface outside the restore screen sees it.
 *
 * The whole point of this class is a *negative*: a breadcrumb is written at the start of the destructive
 * phase, so the raw store answers "a restore began and has not finished" while one is running, and the
 * copy that answer drives says "did not finish — restoring it again is the fix". Every test below is
 * either that suppression or the thing the suppression must not break.
 */
// `UnconfinedTestDispatcher` so a collector is attached before the write/clear it is watching. Class
// level, as in FileArchiveBreadcrumbStoreTest and AppBackupViewModelTest.
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveInterruptedRestoreUseCaseTest {

    private val crumb = ArchiveBreadcrumb("com.example.game", "Game", startedAt = 1L)
    private val jobId: UUID = UUID.fromString("00000000-0000-0000-0000-0000deadbeef")

    @Test
    fun `a breadcrumb with no live restore for that app is the notice`() = runTest {
        val breadcrumbs = FakeBreadcrumbs(crumb)
        val useCase = ObserveInterruptedRestoreUseCase(breadcrumbs, FakeLauncher())
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(crumb, seen.last())
    }

    @Test
    fun `nothing is reported while a restore for that app is live`() = runTest {
        // N1. The user starts a restore in the sheet, the worker writes the breadcrumb on its way into
        // the destructive phase, and the Settings section composed underneath it must not announce that
        // the restore "did not finish" beneath a progress bar reporting normal progress.
        val breadcrumbs = FakeBreadcrumbs(crumb)
        val launcher = FakeLauncher(running = mapOf(crumb.packageName to MutableStateFlow(jobId)))
        val useCase = ObserveInterruptedRestoreUseCase(breadcrumbs, launcher)
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()

        assertNull(seen.last())
        // Asked about the app the breadcrumb names, not about some other target: a suppression keyed on
        // the wrong package is a banner that hides for the wrong restore and shows during the right one.
        assertEquals(
            listOf(ThorJobKind.ARCHIVE_RESTORE to crumb.packageName),
            launcher.asked,
        )
    }

    @Test
    fun `the notice appears when the live restore ends without clearing the breadcrumb`() = runTest {
        // A restore that failed mid-swap: the worker never reached the success path that deletes the
        // breadcrumb, and the job leaves RUNNING. That is the case §8.5 exists for, and the suppression
        // has to lift the moment the job is gone rather than waiting for the next entry into Settings.
        val live = MutableStateFlow<UUID?>(jobId)
        val useCase = ObserveInterruptedRestoreUseCase(
            FakeBreadcrumbs(crumb),
            FakeLauncher(running = mapOf(crumb.packageName to live)),
        )
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()
        assertNull(seen.last())

        live.value = null
        testScheduler.advanceUntilIdle()

        assertEquals(crumb, seen.last())
    }

    @Test
    fun `a live restore of a different app does not suppress the notice`() = runTest {
        // Two apps, one queue. An interrupted restore of A is still worth reporting while B is being
        // restored — and it is the only notice the user will get about A.
        val breadcrumbs = FakeBreadcrumbs(crumb)
        val launcher = FakeLauncher(
            running = mapOf(
                crumb.packageName to MutableStateFlow(null),
                "com.example.other" to MutableStateFlow(jobId),
            )
        )
        val useCase = ObserveInterruptedRestoreUseCase(breadcrumbs, launcher)
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(crumb, seen.last())
    }

    @Test
    fun `Got it in the restore sheet takes the notice off the section behind it`() = runTest {
        // I2, at this seam, and it must not regress: the restore sheet is hosted above the section, so
        // the surface that clears the breadcrumb and the row that shows the notice are composed together.
        val breadcrumbs = FakeBreadcrumbs(crumb)
        val useCase = ObserveInterruptedRestoreUseCase(breadcrumbs, FakeLauncher())
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()
        assertEquals(crumb, seen.first())

        breadcrumbs.clear()
        testScheduler.advanceUntilIdle()

        assertNull(seen.last())
    }

    @Test
    fun `a breadcrumb written and then finished cleanly is never reported`() = runTest {
        // The ordinary successful restore, start to end, with a collector attached the whole way: write
        // while the job is live, then clear. Nothing on this flow is ever non-null, which is what makes
        // the banner's absence a property rather than a matter of timing.
        val breadcrumbs = FakeBreadcrumbs(null)
        val live = MutableStateFlow<UUID?>(jobId)
        val useCase = ObserveInterruptedRestoreUseCase(
            breadcrumbs,
            FakeLauncher(running = mapOf(crumb.packageName to live)),
        )
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        breadcrumbs.write(crumb.packageName, crumb.appLabel)
        testScheduler.advanceUntilIdle()
        breadcrumbs.clear()
        live.value = null
        testScheduler.advanceUntilIdle()

        assertTrue("reported an interruption that never happened: $seen", seen.all { it == null })
    }

    @Test
    fun `no breadcrumb asks the launcher nothing`() = runTest {
        // There is no package to ask about, and the null branch has to emit rather than wait: a flow
        // that never produced a value would strand a collector on its initial value, which is how a
        // dismissed notice stays on screen.
        val launcher = FakeLauncher()
        val useCase = ObserveInterruptedRestoreUseCase(FakeBreadcrumbs(null), launcher)
        val seen = mutableListOf<ArchiveBreadcrumb?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            useCase().toList(seen)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<ArchiveBreadcrumb?>(null), seen)
        assertEquals(emptyList<Pair<ThorJobKind, String>>(), launcher.asked)
    }

    /** A hot store: `observe()` is the flow the fake's own `write`/`clear` push into. */
    private class FakeBreadcrumbs(initial: ArchiveBreadcrumb?) : ArchiveBreadcrumbStore {
        private val state = MutableStateFlow(initial)

        override suspend fun write(packageName: String, appLabel: String): Boolean {
            state.value = ArchiveBreadcrumb(packageName, appLabel, startedAt = 1L)
            return true
        }

        override suspend fun read(): ArchiveBreadcrumb? = state.value
        override fun observe(): Flow<ArchiveBreadcrumb?> = state
        override suspend fun clear() {
            state.value = null
        }
    }

    /**
     * [running] maps a target to its live-job flow. An unmapped target answers null — the ordinary
     * "nothing running for this app" — and [asked] records what was queried, so a use case that asks
     * about the wrong package cannot pass by accident.
     */
    private class FakeLauncher(
        private val running: Map<String, MutableStateFlow<UUID?>> = emptyMap(),
    ) : ArchiveJobLauncher {
        val asked = mutableListOf<Pair<ThorJobKind, String>>()

        override suspend fun startBackup(request: ArchiveBackupRequest, passphrase: CharArray): UUID? = null

        override suspend fun startRestore(
            request: ArchiveRestoreRequest,
            passphrase: CharArray,
            salt: ByteArray,
            iterations: Int,
        ): UUID? = null

        override fun status(jobId: UUID): Flow<ThorJobStatus> = MutableStateFlow(ThorJobStatus.Gone)

        override fun runningJobFor(kind: ThorJobKind, target: String): Flow<UUID?> {
            asked += kind to target
            return running[target] ?: MutableStateFlow(null)
        }
    }
}

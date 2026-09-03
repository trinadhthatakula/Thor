// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.job

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ArchiveBackupOutcome
import com.valhalla.thor.domain.model.ArchiveBackupRequest
import com.valhalla.thor.domain.model.ArchiveHeader
import com.valhalla.thor.domain.model.ArchiveKdf
import com.valhalla.thor.domain.model.ArchiveRestoreDecision
import com.valhalla.thor.domain.model.ArchiveRestoreRequest
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataClass
import com.valhalla.thor.domain.model.DataTaskCheckpoint
import com.valhalla.thor.domain.model.DataTaskRunOutcome
import com.valhalla.thor.domain.model.DataTaskStage
import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.model.ObbProbe
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.RestoreMutationBreadcrumb
import com.valhalla.thor.domain.model.StoredDataDestination
import com.valhalla.thor.domain.repository.ArchiveOpenOutcome
import com.valhalla.thor.domain.repository.ArchiveSource
import com.valhalla.thor.domain.usecase.ArchiveAuthenticationOutcome
import com.valhalla.thor.domain.usecase.ArchiveRestoreOutcome
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArchiveTaskRunnerTest {

    @Test
    fun `backup probes OBB only when the request includes a bundle`() = runTest {
        val operations = FakeBackupOperations()
        val outcome = runArchiveBackupTask(
            request = backupExecutionRequest(includeBundle = false),
            operations = operations,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            checkpoints = appliedCheckpoints(),
            nowMs = { 10L },
        )

        assertTrue(outcome is DataTaskRunOutcome.ItemCompleted)
        assertEquals(0, operations.obbProbeCount)
        assertEquals(0, operations.bundleBuildCount)
        assertEquals(1, operations.backupCount)
    }

    @Test
    fun `backup always deletes the temporary bundle when cancellation escapes`() {
        val bundle = File.createTempFile("thor-task-runner", ".xapk")
        val operations = FakeBackupOperations(
            bundle = bundle,
            backupResult = { throw CancellationException("cancel backup") },
        )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                runArchiveBackupTask(
                    request = backupExecutionRequest(includeBundle = true),
                    operations = operations,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    checkpoints = appliedCheckpoints(),
                    nowMs = { 10L },
                )
            }
        }

        assertFalse("temporary XAPK survived cancellation", bundle.exists())
    }

    @Test
    fun `restore authenticates before package facts and recomputes installFirst`() = runTest {
        val events = mutableListOf<String>()
        val source = FakeArchiveSource()
        val operations = object : ArchiveRestoreTaskOperations {
            override suspend fun open(uriString: String): ArchiveOpenOutcome {
                events += "open"
                return ArchiveOpenOutcome.Opened(source)
            }

            override suspend fun authenticate(
                source: ArchiveSource,
                key: SecretKey,
            ): ArchiveAuthenticationOutcome {
                events += "authenticate"
                return ArchiveAuthenticationOutcome.Authenticated(
                    header = archiveHeader(PACKAGE_NAME),
                    key = key,
                )
            }

            override suspend fun readPackageFacts(packageName: String): ArchiveRestorePackageFacts {
                events += "facts"
                return ArchiveRestorePackageFacts(installed = null, appLabel = "Example")
            }

            override fun evaluateGate(
                header: ArchiveHeader,
                installed: InstalledAppFacts?,
                classes: Set<DataClass>,
            ): ArchiveRestoreDecision {
                events += "gate"
                return ArchiveRestoreDecision.Allowed(installFirst = true, warnings = emptyList())
            }

            override suspend fun restore(
                source: ArchiveSource,
                header: ArchiveHeader,
                key: SecretKey,
                classes: List<DataClass>,
                installFirst: Boolean,
                restoreObb: Boolean,
                execution: PrivilegeExecutionContext,
                appLabel: String,
                onProgress: (com.valhalla.thor.domain.model.ThorJobProgress) -> Unit,
            ): ArchiveRestoreOutcome {
                events += "restore:$installFirst"
                return ArchiveRestoreOutcome.Completed(classes, emptyList(), obb = null)
            }
        }

        val outcome = runArchiveRestoreTask(
            request = restoreExecutionRequest(),
            operations = operations,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            checkpoints = appliedCheckpoints(),
            nowMs = { 20L },
        )

        assertTrue(outcome is DataTaskRunOutcome.ItemCompleted)
        assertEquals(
            listOf("open", "authenticate", "facts", "gate", "restore:true"),
            events,
        )
        assertTrue(source.closed)
    }

    @Test
    fun `restore never reopens a task whose destructive checkpoint requires review`() = runTest {
        var opened = false
        val operations = object : ArchiveRestoreTaskOperations by UnusedRestoreOperations() {
            override suspend fun open(uriString: String): ArchiveOpenOutcome {
                opened = true
                return ArchiveOpenOutcome.Unreadable
            }
        }
        val breadcrumb = RestoreMutationBreadcrumb(PACKAGE_NAME, "Example", 12L)
        val request = restoreExecutionRequest(
            resumedFrom = DataTaskCheckpoint(
                stage = DataTaskStage.RESTORING,
                completed = 0L,
                total = 1L,
                activeItemOrdinal = 0,
                activeItemLabel = "Example",
                destructiveStarted = true,
                restoreMutationBreadcrumb = breadcrumb,
                recordedAtEpochMs = 13L,
            ),
        )

        val outcome = runArchiveRestoreTask(
            request = request,
            operations = operations,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            checkpoints = appliedCheckpoints(),
            nowMs = { 20L },
        )

        assertEquals(
            DataTaskRunOutcome.InterruptedReview(
                com.valhalla.thor.domain.model.DataTaskResultCode("ARCHIVE_RESTORE_INTERRUPTED"),
                breadcrumb,
            ),
            outcome,
        )
        assertFalse(opened)
    }

    @Test
    fun `checkpoint ownership loss stops backup before package work`() = runTest {
        val operations = FakeBackupOperations()

        val outcome = runArchiveBackupTask(
            request = backupExecutionRequest(includeBundle = false),
            operations = operations,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            checkpoints = DataTaskCheckpointSink { DataTaskSinkWrite.OWNERSHIP_LOST },
            nowMs = { 10L },
        )

        assertSame(DataTaskRunOutcome.OwnershipLost, outcome)
        assertEquals(0, operations.appLookupCount)
        assertEquals(0, operations.backupCount)
    }

    private fun backupExecutionRequest(includeBundle: Boolean) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.ArchiveBackup(
            request = ArchiveBackupRequest(
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.CE),
                includeBundle = includeBundle,
                salt = ByteArray(16),
            ),
            key = KEY,
            destination = StoredDataDestination.ArchiveStore,
        ),
        item = item(),
        taskAttemptCount = 1,
        resumedFrom = null,
    )

    private fun restoreExecutionRequest(
        resumedFrom: DataTaskCheckpoint? = null,
    ) = DataTaskExecutionRequest(
        taskId = TASK_ID,
        payload = DataTaskExecutionPayload.ArchiveRestore(
            request = ArchiveRestoreRequest(
                uriString = "content://provider/archive",
                packageName = PACKAGE_NAME,
                classes = setOf(DataClass.DE, DataClass.CE),
                restoreObb = true,
            ),
            key = KEY,
        ),
        item = item(),
        taskAttemptCount = 2,
        resumedFrom = resumedFrom,
    )

    private fun item() = DataTaskExecutionItem(
        ordinal = 0,
        packageName = PACKAGE_NAME,
        displayLabel = "Example",
        deterministicStagingIdentity = TASK_ID.toString(),
        attemptCount = 1,
    )

    private fun appliedCheckpoints() = DataTaskCheckpointSink { DataTaskSinkWrite.APPLIED }

    private fun archiveHeader(packageName: String) = ArchiveHeader(
        createdAt = 1L,
        thorVersionCode = 1952,
        packageName = packageName,
        versionCode = 1L,
        userId = 0,
        signerSha256 = "ab".repeat(32),
        kdf = ArchiveKdf(iterations = 4, salt = "AA=="),
        verifier = "AA==",
    )

    private class FakeBackupOperations(
        private val bundle: File? = null,
        private val backupResult: suspend () -> ArchiveBackupOutcome = {
            ArchiveBackupOutcome.Completed(
                fileName = "Example-1.thorbak",
                header = archiveHeaderStatic(PACKAGE_NAME),
                destinationLabel = "Backups",
            )
        },
    ) : ArchiveBackupTaskOperations {
        var appLookupCount = 0
        var obbProbeCount = 0
        var bundleBuildCount = 0
        var backupCount = 0

        override suspend fun loadApp(packageName: String): AppInfo {
            appLookupCount++
            return AppInfo(appName = "Example", packageName = packageName, versionCode = 1L)
        }

        override suspend fun probeObb(
            packageName: String,
            execution: PrivilegeExecutionContext,
        ): ObbProbe {
            obbProbeCount++
            return ObbProbe.None
        }

        override suspend fun buildBundle(
            appInfo: AppInfo,
            cacheSubDir: String,
            format: BundleFormat,
            execution: PrivilegeExecutionContext,
        ): Result<File> {
            bundleBuildCount++
            return Result.success(checkNotNull(bundle))
        }

        override suspend fun usableStagingBytes(): Long = 1_000_000L

        override suspend fun backup(
            request: ArchiveBackupRequest,
            key: SecretKey,
            bundle: File?,
            bundleObbCapture: String,
            bundleObbCount: Int,
            versionCode: Long,
            versionName: String?,
            usableStagingBytes: Long,
            appLabel: String,
            onProgress: (com.valhalla.thor.domain.model.ThorJobProgress) -> Unit,
        ): ArchiveBackupOutcome {
            backupCount++
            return backupResult()
        }
    }

    private open class UnusedRestoreOperations : ArchiveRestoreTaskOperations {
        override suspend fun open(uriString: String): ArchiveOpenOutcome = error("not used")
        override suspend fun authenticate(
            source: ArchiveSource,
            key: SecretKey,
        ): ArchiveAuthenticationOutcome = error("not used")

        override suspend fun readPackageFacts(packageName: String): ArchiveRestorePackageFacts =
            error("not used")

        override fun evaluateGate(
            header: ArchiveHeader,
            installed: InstalledAppFacts?,
            classes: Set<DataClass>,
        ): ArchiveRestoreDecision = error("not used")

        override suspend fun restore(
            source: ArchiveSource,
            header: ArchiveHeader,
            key: SecretKey,
            classes: List<DataClass>,
            installFirst: Boolean,
            restoreObb: Boolean,
            execution: PrivilegeExecutionContext,
            appLabel: String,
            onProgress: (com.valhalla.thor.domain.model.ThorJobProgress) -> Unit,
        ): ArchiveRestoreOutcome = error("not used")
    }

    private class FakeArchiveSource : ArchiveSource {
        var closed = false
        override val displayName: String = "archive.thorbak"
        override fun entryNames(): List<String> = emptyList()
        override fun openEntry(name: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val TASK_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val KEY: SecretKey = SecretKeySpec(ByteArray(32), "AES")

        fun archiveHeaderStatic(packageName: String) = ArchiveHeader(
            createdAt = 1L,
            thorVersionCode = 1952,
            packageName = packageName,
            versionCode = 1L,
            userId = 0,
            signerSha256 = "ab".repeat(32),
            kdf = ArchiveKdf(iterations = 4, salt = "AA=="),
            verifier = "AA==",
        )
    }
}

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.backup.job

import com.valhalla.thor.data.privilege.DefaultPackageOperationCoordinator
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.domain.repository.*
import com.valhalla.thor.domain.usecase.ExportSession
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacyExportPackageAdmissionTest {
    @Test fun `legacy adapter waits for mutation before lookup and copy`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, Duration.ZERO) { release.await() }
        }
        runCurrent()
        val events = mutableListOf<String>()
        val operations = Operations(events)
        val export = async { runAdapter(operations, StandardTestDispatcher(testScheduler), events, packages) }
        runCurrent()
        try {
            assertTrue("lookup and copy must wait for package mutation", events.isEmpty())
            assertFalse(export.isCompleted)
        } finally { release.complete(Unit); owner.join() }
        export.await()
        assertEquals(listOf("checkpoint", "lookup", "checkpoint", "copy", "result"), events)
    }

    @Test fun `legacy adapter excludes same package while unrelated mutation progresses`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val export = launch { runAdapter(Operations(events) { entered.complete(Unit); release.await() }, StandardTestDispatcher(testScheduler), events, packages) }
        entered.await()
        val mutation = async {
            packages.withPackageLease(PACKAGE, PackageOperationOwner.UNINSTALL, 5.seconds) { events += "mutation" }
        }
        runCurrent()
        try {
            assertFalse(mutation.isCompleted)
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.BUNDLE_READ),
                packages.withPackageLease(PACKAGE, PackageOperationOwner.UNINSTALL, Duration.ZERO) { "P" })
            assertEquals(PackageLeaseResult.Acquired("Q"),
                packages.withPackageLease("com.example.other", PackageOperationOwner.UNINSTALL, Duration.ZERO) { "Q" })
        } finally { release.complete(Unit); export.join() }
        mutation.await()
        assertEquals(1, events.count { it == "result" })
        assertTrue(events.indexOf("mutation") > events.indexOf("copy"))
    }

    @Test fun `legacy cancellation holds ownership until suspended cleanup completes`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val export = launch {
            runAdapter(Operations(events) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.complete(Unit); releaseCleanup.await(); events += "cleaned" } }
            }, StandardTestDispatcher(testScheduler), events, packages)
        }
        entered.await(); export.cancel(); cleaning.await()
        val mutation = async {
            packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, 5.seconds) { events += "mutation" }
        }
        runCurrent()
        try {
            assertFalse(mutation.isCompleted)
            assertEquals(PackageLeaseResult.Busy(PackageOperationOwner.BUNDLE_READ),
                packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, Duration.ZERO) { true })
            assertFalse(events.contains("result"))
        } finally { releaseCleanup.complete(Unit); export.join() }
        assertEquals(PackageLeaseResult.Acquired(true),
            packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, Duration.ZERO) { true })
        mutation.await()
        assertEquals(listOf("cleaned", "mutation"), events.takeLast(2))
        assertFalse(events.contains("result"))
    }

    @Test fun `cancelled legacy admission never performs package lookup or reports a result`() = runTest {
        val packages = DefaultPackageOperationCoordinator()
        val owner = launch {
            packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, Duration.ZERO) { awaitCancellation() }
        }
        runCurrent()
        val events = mutableListOf<String>()
        val waiting = launch { runAdapter(Operations(events), StandardTestDispatcher(testScheduler), events, packages) }
        runCurrent()
        waiting.cancelAndJoin()
        owner.cancelAndJoin()
        assertTrue(events.isEmpty())
        assertEquals(PackageLeaseResult.Acquired(true), packages.withPackageLease(PACKAGE, PackageOperationOwner.REINSTALL, Duration.ZERO) { true })
    }

    private suspend fun runAdapter(operations: Operations, dispatcher: CoroutineDispatcher, events: MutableList<String>, packages: PackageOperationCoordinator) =
        runLegacyAppExportTask(
            taskId = UUID.fromString("99999999-9999-9999-9999-999999999999"),
            decodedRequest = decodeLegacyAppExportRequest(AppExportRequest(PACKAGE, BundleFormat.APK, "Example").toMap()),
            runAttemptCount = 1,
            invalidRequestReason = "invalid",
            runner = AppExportTaskRunner(operations, dispatcher),
            packages = packages,
            checkpoints = DataTaskCheckpointSink { events += "checkpoint"; DataTaskSinkWrite.APPLIED },
            results = DataTaskResultSink { events += "result"; it },
        )

    private class Operations(val events: MutableList<String>, val copying: suspend () -> Unit = {}) : AppExportTaskOperations {
        override suspend fun awaitLaunchSweep() = true
        override suspend fun loadApp(packageName: String): AppInfo {
            events += "lookup"
            return AppInfo(packageName = packageName, appName = "Example", publicSourceDir = "/app/base.apk")
        }
        override suspend fun isTreeWritable(treeUri: String) = true
        override suspend fun reconcilePublication(target: ExportTargetChoice, identity: AppExportPublicationIdentity): AppExportPublicationReconciliation = error("legacy must not reconcile")
        override suspend fun exportInto(
            appInfo: AppInfo, format: BundleFormat, session: ExportSession,
            publicationIdentity: AppExportPublicationIdentity?, execution: PrivilegeExecutionContext,
            captureProgress: VerifiedProgress, captureBoundary: VerifiedOperationBoundary, publicationProgress: VerifiedProgress,
            publicationStart: suspend () -> Unit,
        ): Result<AppExportPublication> {
            assertNull(publicationIdentity)
            assertEquals("export_temp", session.stagingSubDir)
            events += "copy"; copying()
            return Result.success(AppExportPublication("Downloads/Thor", AppExportPublicationStatus.PUBLISHED))
        }
    }
    private companion object { const val PACKAGE = "com.example.app" }
}

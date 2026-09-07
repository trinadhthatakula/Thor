// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.repository.AppBundleBuilder
import com.valhalla.thor.domain.repository.AppBundleFileStore
import com.valhalla.thor.domain.repository.AppExportPublication
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationReconciliation
import com.valhalla.thor.domain.repository.AppExportPublicationStatus
import com.valhalla.thor.domain.repository.VerifiedOperationBoundary
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAppUseCaseDurableTest {

    @Test
    fun `replay after visible publication reuses the task identity without rebuilding or writing`() =
        runTest {
            val root = Files.createTempDirectory("durable_export_").toFile()
            try {
                val identity = AppExportPublicationIdentity(
                    "Thor-task-11111111-1111-1111-1111-111111111111-0.apk"
                )
                val builder = RecordingBuilder(root)
                val store = RecordingFileStore()
                val session = ExportSession(ExportTargetChoice.Downloads, "item-1")
                var operationBoundaries = 0
                val captureBoundary = VerifiedOperationBoundary { operationBoundaries++ }

                val first = exportDurableBundle(
                    bundleBuilder = builder,
                    fileStore = store,
                    appInfo = appInfo(label = "Foo", version = "1.0"),
                    format = BundleFormat.APK,
                    session = session,
                    publicationIdentity = identity,
                    execution = PrivilegeExecutionContext(),
                    captureProgress = VerifiedProgress.NONE,
                    captureBoundary = captureBoundary,
                    publicationProgress = VerifiedProgress.NONE,
                    publicationStart = {},
                ).getOrThrow()
                val replay = exportDurableBundle(
                    bundleBuilder = builder,
                    fileStore = store,
                    appInfo = appInfo(label = "Renamed", version = "9.9"),
                    format = BundleFormat.APK,
                    session = session,
                    publicationIdentity = identity,
                    execution = PrivilegeExecutionContext(),
                    captureProgress = VerifiedProgress.NONE,
                    captureBoundary = captureBoundary,
                    publicationProgress = VerifiedProgress.NONE,
                    publicationStart = {},
                ).getOrThrow()

                assertEquals(AppExportPublicationStatus.PUBLISHED, first.status)
                assertEquals(AppExportPublicationStatus.RECONCILED, replay.status)
                assertEquals(1, builder.buildCount)
                assertEquals(1, store.publishCount)
                assertEquals(1, operationBoundaries)
                assertEquals(listOf(identity.fileName), builder.requestedNames)
                assertEquals(listOf(identity, identity), store.reconciledIdentities)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `runner persists publishing before durable use case creates any public document`() = runTest {
        val root = Files.createTempDirectory("publication_boundary_").toFile()
        try {
            var stage: com.valhalla.thor.domain.model.DataTaskStage? = null
            val builder = RecordingBuilder(root)
            val store = RecordingFileStore {
                assertEquals(com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING, stage)
            }
            val operations = object : com.valhalla.thor.data.backup.job.AppExportTaskOperations {
                override suspend fun awaitLaunchSweep() = true
                override suspend fun loadApp(packageName: String) = appInfo("Foo", "1.0")
                override suspend fun isTreeWritable(treeUri: String) = true
                override suspend fun reconcilePublication(target: ExportTargetChoice, identity: AppExportPublicationIdentity) =
                    store.reconcilePublicExport(target, identity)
                override suspend fun exportInto(
                    appInfo: AppInfo, format: BundleFormat, session: ExportSession,
                    publicationIdentity: AppExportPublicationIdentity?, execution: PrivilegeExecutionContext,
                    captureProgress: VerifiedProgress, captureBoundary: VerifiedOperationBoundary,
                    publicationProgress: VerifiedProgress,
                    publicationStart: suspend () -> Unit,
                ) = exportDurableBundle(builder, store, appInfo, format, session, requireNotNull(publicationIdentity),
                    execution, captureProgress, captureBoundary, publicationProgress, publicationStart)
            }
            val id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")
            val request = com.valhalla.thor.data.backup.job.DataTaskExecutionRequest(
                taskId = id,
                payload = com.valhalla.thor.data.backup.job.DataTaskExecutionPayload.AppExport(
                    com.valhalla.thor.domain.model.AppExportRequest("com.example.app", BundleFormat.APK, "Foo", "content://provider/tree/root"),
                    com.valhalla.thor.domain.model.DataTaskPublicationPolicy.PUBLIC_DOCUMENT),
                item = com.valhalla.thor.data.backup.job.DataTaskExecutionItem(0, "com.example.app", "Foo", "item-$id-0", 1),
                taskAttemptCount = 1, resumedFrom = null,
            )
            val publicationRequested = CompletableDeferred<Unit>()
            val commitPublication = CompletableDeferred<Unit>()
            val running = async {
                com.valhalla.thor.data.backup.job.AppExportTaskRunner(operations, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
                    .run(request, com.valhalla.thor.data.backup.job.DataTaskCheckpointSink {
                        if (it.stage == com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING) {
                            publicationRequested.complete(Unit)
                            commitPublication.await()
                        }
                        stage = it.stage; com.valhalla.thor.data.backup.job.DataTaskSinkWrite.APPLIED
                    })
            }
            publicationRequested.await()
            assertEquals(1, builder.buildCount)
            assertEquals(0, store.publishCount)
            commitPublication.complete(Unit)
            running.await()
            assertEquals(1, store.publishCount)
        } finally { root.deleteRecursively() }
    }

    private fun appInfo(label: String, version: String) = AppInfo(
        packageName = "com.example.app",
        appName = label,
        versionName = version,
        versionCode = 1L,
        publicSourceDir = "/apps/com.example.app/base.apk",
    )

    private class RecordingBuilder(private val root: File) : AppBundleBuilder {
        var buildCount = 0
        val requestedNames = mutableListOf<String>()

        override suspend fun build(
            appInfo: AppInfo,
            cacheSubDir: String,
            format: BundleFormat,
            fileName: String?,
            execution: PrivilegeExecutionContext,
        ): Result<File> = error("durable export must use the progress-aware builder contract")

        override suspend fun buildWithProgress(
            appInfo: AppInfo,
            cacheSubDir: String,
            format: BundleFormat,
            fileName: String?,
            execution: PrivilegeExecutionContext,
            progress: VerifiedProgress,
            operationBoundary: VerifiedOperationBoundary,
        ): Result<File> {
            buildCount++
            requestedNames += requireNotNull(fileName)
            operationBoundary.onOperationCompleted()
            return Result.success(File(root, fileName).apply { writeText("payload") })
        }
    }

    private class RecordingFileStore(val beforePublish: () -> Unit = {}) : AppBundleFileStore {
        var publishedIdentity: AppExportPublicationIdentity? = null
        var publishCount = 0
        val reconciledIdentities = mutableListOf<AppExportPublicationIdentity>()

        override suspend fun reconcilePublicExport(
            target: ExportTargetChoice,
            identity: AppExportPublicationIdentity,
        ): AppExportPublicationReconciliation {
            reconciledIdentities += identity
            return if (publishedIdentity == identity) {
                AppExportPublicationReconciliation.Complete(
                    AppExportPublication(
                        destinationLabel = "Downloads/Thor",
                        status = AppExportPublicationStatus.RECONCILED,
                    )
                )
            } else {
                AppExportPublicationReconciliation.Absent
            }
        }

        override suspend fun publishPublicExport(
            file: File,
            target: ExportTargetChoice,
            mime: String,
            identity: AppExportPublicationIdentity,
            progress: VerifiedProgress,
        ): AppExportPublication {
            assertEquals(identity.fileName, file.name)
            assertTrue(identity.fileName != "Foo_1.0.apk")
            beforePublish()
            publishCount++
            publishedIdentity = identity
            return AppExportPublication(
                destinationLabel = "Downloads/Thor",
                status = AppExportPublicationStatus.PUBLISHED,
            )
        }

        override suspend fun writeToDownloads(file: File, mime: String): String =
            error("durable publication must use the operation-aware contract")

        override suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String =
            error("durable publication must use the operation-aware contract")

        override suspend fun isTreeWritable(treeUriStr: String?): Boolean = true

        override suspend fun currentTargetLabel(savedTreeUriStr: String?): String = "Downloads/Thor"

        override fun shareUri(file: File): String = error("not used")

        override suspend fun stageText(fileName: String, content: String): File = error("not used")
    }
}

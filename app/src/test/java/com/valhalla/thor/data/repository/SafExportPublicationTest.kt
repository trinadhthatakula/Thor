// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.repository

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationReconciliation
import com.valhalla.thor.domain.repository.VerifiedProgress
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SafExportPublicationTest {
    private lateinit var provider: ExportDocumentsProvider
    private lateinit var store: AppBundleFileStoreImpl
    private lateinit var source: File
    private val identity = AppExportPublicationIdentity("Thor-task-77777777-7777-7777-7777-777777777777-0.apk")
    private val target = ExportTargetChoice.Custom("content://export.test/tree/root")

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        provider = Robolectric.buildContentProvider(ExportDocumentsProvider::class.java).create(
            ProviderInfo().apply {
                authority = "export.test"
                exported = true
                grantUriPermissions = true
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            }
        ).get()
        store = AppBundleFileStoreImpl(context, Dispatchers.Unconfined)
        source = File(context.cacheDir, identity.fileName).apply { writeText("complete package bytes") }
    }

    @Test fun `normalized partial is rejected before opening bytes and only created identity is cleaned`() = runBlocking {
        provider.partialName = "normalized.apk.part"
        assertFailsPublication()
        assertEquals(0, provider.opens)
        assertEquals(listOf("created"), provider.deleted)
    }

    @Test fun `missing metadata name is rejected before opening bytes`() = runBlocking {
        provider.hideNames = true
        assertFailsPublication()
        assertEquals(0, provider.opens)
        assertEquals(listOf("created"), provider.deleted)
    }

    @Test fun `missing final metadata is rejected and cleaned by returned rename identity`() = runBlocking {
        provider.hideFinalName = true
        assertFailsPublication()
        assertEquals(listOf("renamed"), provider.deleted)
    }

    @Test fun `collision suffixed final is rejected and changed rename URI is the cleanup identity`() = runBlocking {
        provider.finalName = "collision (1).apk"
        assertFailsPublication()
        assertEquals(listOf("renamed"), provider.deleted)
        assertTrue(provider.documents.isEmpty())
    }

    @Test fun `failed cleanup is not success and never deletes a foreign final`() = runBlocking {
        provider.finalName = "collision (1).apk"
        provider.failDelete = true
        provider.afterCreate = { provider.documents["foreign"] = identity.fileName }
        assertFailsPublication()
        assertEquals(listOf("created"), provider.deleted)
        assertFalse(provider.deleted.contains("foreign"))
        assertEquals(identity.fileName, provider.documents["foreign"])
    }

    @Test fun `existing foreign final is never deleted or overwritten`() = runBlocking {
        provider.documents["foreign"] = identity.fileName
        assertFailsPublication()
        assertFalse(provider.deleted.contains("foreign"))
        assertEquals(identity.fileName, provider.documents["foreign"])
    }

    @Test fun `exact publication follows returned rename URI and is reusable`() = runBlocking {
        store.publishPublicExport(source, target, "application/vnd.android.package-archive", identity, VerifiedProgress.NONE)
        assertEquals(identity.fileName, provider.documents["renamed"])
        assertTrue(provider.deleted.isEmpty())
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ApplicationProvider.getApplicationContext(), target.treeUri.toUri())!!
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(target.treeUri.toUri(), "root")
        ApplicationProvider.getApplicationContext<Application>().contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { assertEquals(1, it.count) }
        assertEquals(listOf(identity.fileName), tree.listFiles().map { it.name })
        assertTrue("exact document must be a file", tree.listFiles().single().isFile)
        assertTrue(store.reconcilePublicExport(target, identity) is AppExportPublicationReconciliation.Complete)
        assertEquals(1, provider.opens)
    }

    @Test fun `legacy writer retains provider normalized publication behavior`() = runBlocking {
        provider.partialName = "normalized.part"
        provider.finalName = "normalized.apk"
        store.writeToTree(source, target.treeUri, "application/vnd.android.package-archive")
        assertEquals("normalized.apk", provider.documents["renamed"])
    }

    @Test fun `fresh SAF task does not adopt a foreign exact final as its own success`() = runBlocking {
        provider.documents["foreign"] = identity.fileName
        val lifecycle = Lifecycle()
        val outcome = lifecycle.run() as com.valhalla.thor.domain.model.DataTaskRunOutcome.ItemCompleted
        assertEquals(com.valhalla.thor.domain.model.DataTaskItemTerminalState.FAILED, outcome.result.terminalState)
        assertEquals(0, lifecycle.builds)
        assertEquals(0, provider.creates)
        assertTrue(provider.deleted.isEmpty())
        assertEquals(identity.fileName, provider.documents["foreign"])
    }

    @Test fun `kill after create fences unknown partial and never rebuilds`() = runBlocking {
        provider.partialName = "provider-normalized.part"
        interruptedAttempt { provider.afterCreate = { kill() } }
    }

    @Test fun `kill after create normalized directly to final does not adopt empty output`() = runBlocking {
        provider.partialName = identity.fileName
        interruptedAttempt { provider.afterCreate = { kill() } }
    }

    @Test fun `kill during write fences exact partial and never republishes`() = runBlocking {
        source.writeBytes(ByteArray(20_000) { 7 })
        interruptedAttempt { it.killDuringWrite = true }
        assertEquals(8192L, File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "provider-created").length())
    }

    @Test fun `kill after normalized rename fences unknown final and never republishes`() = runBlocking {
        provider.finalName = "provider-normalized.apk"
        interruptedAttempt { provider.afterRename = { kill() } }
    }

    @Test fun `kill after exact rename reuses complete publication without rebuilding`() = runBlocking {
        interruptedAttempt(expectComplete = true) { provider.afterRename = { kill() } }
    }

    @Test fun `kill after verified publication before settlement reuses complete output`() = runBlocking {
        val lifecycle = Lifecycle()
        assertTrue(lifecycle.run() is com.valhalla.thor.domain.model.DataTaskRunOutcome.ItemCompleted)
        assertEquals(com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING, lifecycle.checkpoint?.stage)
        val replay = lifecycle.run(resuming = true) as com.valhalla.thor.domain.model.DataTaskRunOutcome.ItemCompleted
        assertEquals("APP_EXPORT_COMPLETED", replay.result.resultCode.value)
        assertEquals(1, lifecycle.builds)
        assertEquals(1, provider.creates)
    }

    @Test fun `repeated interruption of reconciliation preserves publishing checkpoint`() = runBlocking {
        val lifecycle = Lifecycle()
        provider.partialName = "unknown.part"
        provider.afterCreate = { kill() }
        try { lifecycle.run(); fail("expected simulated process death") } catch (_: SimulatedProcessDeath) { }
        provider.dead = false
        repeat(2) {
            provider.dieOnQuery = true
            try { lifecycle.run(resuming = true); fail("expected interrupted reconciliation") } catch (_: SimulatedProcessDeath) { }
            provider.dead = false
            assertEquals(com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING, lifecycle.checkpoint?.stage)
        }
        val replay = lifecycle.run(resuming = true) as com.valhalla.thor.domain.model.DataTaskRunOutcome.ItemCompleted
        assertEquals("APP_EXPORT_PUBLICATION_UNCERTAIN", replay.result.resultCode.value)
        assertEquals(1, lifecycle.builds)
        assertEquals(1, provider.creates)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun `rejected renamed document with failed cleanup is not reported as success`() = runBlocking {
        provider.finalName = "normalized.apk"
        provider.failDelete = true
        assertFailsPublication()
        assertEquals(listOf("renamed"), provider.deleted)
        assertEquals("normalized.apk", provider.documents["renamed"])
    }

    private fun kill(): Nothing {
        provider.dead = true
        throw SimulatedProcessDeath()
    }

    private suspend fun interruptedAttempt(expectComplete: Boolean = false, schedule: (Lifecycle) -> Unit) {
        val lifecycle = Lifecycle()
        schedule(lifecycle)
        try { lifecycle.run(); fail("expected simulated process death") } catch (_: SimulatedProcessDeath) { }
        assertEquals(com.valhalla.thor.domain.model.DataTaskStage.PUBLISHING, lifecycle.checkpoint?.stage)
        provider.dead = false
        val replay = lifecycle.run(resuming = true) as com.valhalla.thor.domain.model.DataTaskRunOutcome.ItemCompleted
        assertEquals(if (expectComplete) "APP_EXPORT_COMPLETED" else "APP_EXPORT_PUBLICATION_UNCERTAIN", replay.result.resultCode.value)
        assertEquals(1, lifecycle.builds)
        assertEquals(1, provider.creates)
        assertEquals(1, lifecycle.lookups)
        assertTrue(provider.deleted.isEmpty())
    }

    private inner class Lifecycle {
        var checkpoint: com.valhalla.thor.domain.model.DataTaskCheckpoint? = null
        var builds = 0
        var lookups = 0
        var killDuringWrite = false
        private val builder = object : com.valhalla.thor.domain.repository.AppBundleBuilder {
            override suspend fun build(appInfo: com.valhalla.thor.domain.model.AppInfo, cacheSubDir: String,
                format: com.valhalla.thor.domain.model.BundleFormat, fileName: String?, execution: com.valhalla.thor.domain.model.PrivilegeExecutionContext): Result<File> = error("use progress builder")
            override suspend fun buildWithProgress(appInfo: com.valhalla.thor.domain.model.AppInfo, cacheSubDir: String,
                format: com.valhalla.thor.domain.model.BundleFormat, fileName: String?, execution: com.valhalla.thor.domain.model.PrivilegeExecutionContext,
                progress: VerifiedProgress, operationBoundary: com.valhalla.thor.domain.repository.VerifiedOperationBoundary): Result<File> {
                builds++
                assertEquals(identity.fileName, fileName)
                return Result.success(source)
            }
        }
        private val operations = object : com.valhalla.thor.data.backup.job.AppExportTaskOperations {
            override suspend fun awaitLaunchSweep() = true
            override suspend fun loadApp(packageName: String): com.valhalla.thor.domain.model.AppInfo {
                lookups++
                return com.valhalla.thor.domain.model.AppInfo(packageName = packageName, appName = "Example", publicSourceDir = "/app/base.apk")
            }
            override suspend fun isTreeWritable(treeUri: String) = true
            override suspend fun reconcilePublication(target: ExportTargetChoice, identity: AppExportPublicationIdentity) = store.reconcilePublicExport(target, identity)
            override suspend fun exportInto(appInfo: com.valhalla.thor.domain.model.AppInfo, format: com.valhalla.thor.domain.model.BundleFormat,
                session: com.valhalla.thor.domain.usecase.ExportSession, publicationIdentity: AppExportPublicationIdentity?,
                execution: com.valhalla.thor.domain.model.PrivilegeExecutionContext, captureProgress: VerifiedProgress,
                captureBoundary: com.valhalla.thor.domain.repository.VerifiedOperationBoundary, publicationProgress: VerifiedProgress,
                publicationStart: suspend () -> Unit) = com.valhalla.thor.domain.usecase.exportDurableBundle(
                    builder, store, appInfo, format, session, requireNotNull(publicationIdentity), execution,
                    captureProgress, captureBoundary, VerifiedProgress { bytes ->
                        if (killDuringWrite) kill()
                        publicationProgress.onBytesWritten(bytes)
                    }, publicationStart)
        }
        suspend fun run(resuming: Boolean = false): com.valhalla.thor.domain.model.DataTaskRunOutcome {
            val id = java.util.UUID.fromString("77777777-7777-7777-7777-777777777777")
            return com.valhalla.thor.data.backup.job.AppExportTaskRunner(operations, Dispatchers.Unconfined).run(
                com.valhalla.thor.data.backup.job.DataTaskExecutionRequest(id,
                    com.valhalla.thor.data.backup.job.DataTaskExecutionPayload.AppExport(
                        com.valhalla.thor.domain.model.AppExportRequest("com.example.app", com.valhalla.thor.domain.model.BundleFormat.APK, "Example", target.treeUri),
                        com.valhalla.thor.domain.model.DataTaskPublicationPolicy.PUBLIC_DOCUMENT),
                    com.valhalla.thor.data.backup.job.DataTaskExecutionItem(0, "com.example.app", "Example", "item-$id-0", 1),
                    1, if (resuming) checkpoint else null),
                com.valhalla.thor.data.backup.job.DataTaskCheckpointSink { checkpoint = it; com.valhalla.thor.data.backup.job.DataTaskSinkWrite.APPLIED })
        }
    }

    private suspend fun assertFailsPublication() {
        try {
            store.publishPublicExport(source, target, "application/vnd.android.package-archive", identity, VerifiedProgress.NONE)
            fail("unproven publication must be refused")
        } catch (_: IOException) { }
    }
}

private class SimulatedProcessDeath : Error("simulated process death")

/** A provider double, not a second writer: the real store calls DocumentsContract and ContentResolver. */
class ExportDocumentsProvider : android.content.ContentProvider() {
    val documents = linkedMapOf<String, String?>()
    val deleted = mutableListOf<String>()
    private val sizes = mutableMapOf<String, Long>()
    var partialName: String? = null
    var finalName: String? = null
    var hideNames = false
    var hideFinalName = false
    var failDelete = false
    var opens = 0
    var creates = 0
    var dead = false
    var dieOnQuery = false
    var afterCreate: () -> Unit = {}
    var afterRename: () -> Unit = {}
    override fun onCreate() = true
    override fun getType(uri: android.net.Uri): String = "application/vnd.android.package-archive"
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = error("not used")
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("not used")
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("not used")
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (dieOnQuery) { dieOnQuery = false; dead = true }
        if (dead) throw SimulatedProcessDeath()
        return rows(projection, if (uri.lastPathSegment == "children") documents.keys.toList() else listOf(DocumentsContract.getDocumentId(uri)))
    }
    @Suppress("DEPRECATION")
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
        val input = requireNotNull(extras)
        val uri = requireNotNull(input.getParcelable<android.net.Uri>("uri"))
        return android.os.Bundle().apply {
            when (method) {
                "android:createDocument" -> putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri,
                    createDocument(DocumentsContract.getDocumentId(uri), input.getString("mime_type")!!, input.getString("_display_name")!!)))
                "android:renameDocument" -> putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri,
                    renameDocument(DocumentsContract.getDocumentId(uri), input.getString("_display_name")!!)))
                "android:deleteDocument" -> deleteDocument(DocumentsContract.getDocumentId(uri))
                else -> error("unexpected contract call $method")
            }
        }
    }
    private fun rows(projection: Array<out String>?, ids: List<String>): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply {
            ids.forEach { id -> addRow(columns.map<String, Any?> { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> if (hideNames || (hideFinalName && id == "renamed")) null else if (id == "root") "Exports" else documents[id]
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/vnd.android.package-archive"
                DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                DocumentsContract.Document.COLUMN_SIZE -> if (id == "created") File(requireNotNull(context).cacheDir, "provider-created").length() else sizes[id] ?: 22L
                else -> null
            } }.toTypedArray()) }
        }
    }
    private fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        creates++
        File(requireNotNull(context).cacheDir, "provider-created").writeBytes(byteArrayOf())
        documents["created"] = partialName ?: displayName
        afterCreate()
        return "created"
    }
    private fun renameDocument(documentId: String, displayName: String): String {
        sizes["renamed"] = File(requireNotNull(context).cacheDir, "provider-$documentId").length()
        documents.remove(documentId)
        documents["renamed"] = finalName ?: if (documents.values.contains(displayName)) "$displayName (1)" else displayName
        afterRename()
        return "renamed"
    }
    private fun deleteDocument(documentId: String) {
        if (dead) throw SimulatedProcessDeath()
        deleted += documentId
        if (failDelete) throw java.io.FileNotFoundException("cleanup refused")
        documents.remove(documentId)
    }
    override fun openFile(uri: android.net.Uri, mode: String): ParcelFileDescriptor {
        val documentId = DocumentsContract.getDocumentId(uri)
        opens++
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, "provider-$documentId"),
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
}

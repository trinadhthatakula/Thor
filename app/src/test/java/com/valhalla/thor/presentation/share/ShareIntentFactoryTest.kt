// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.data.source.local.room.DataTaskDao
import com.valhalla.thor.data.source.local.room.NewDataTaskItem
import com.valhalla.thor.data.source.local.room.NewDataTaskRow
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.DataTaskItemResult
import com.valhalla.thor.domain.model.DataTaskItemTerminalState
import com.valhalla.thor.domain.model.DataTaskKind
import com.valhalla.thor.domain.model.DataTaskOutputState
import com.valhalla.thor.domain.model.DataTaskPublicationPolicy
import com.valhalla.thor.domain.model.DataTaskResultCode
import com.valhalla.thor.domain.model.DataTaskState
import com.valhalla.thor.domain.model.NewDataTaskOutput
import com.valhalla.thor.domain.model.StoredDataTaskDetail
import com.valhalla.thor.domain.repository.AppBundleFileStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@Suppress("DEPRECATION")
class ShareIntentFactoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: AppDatabase
    private lateinit var dao: DataTaskDao
    private lateinit var fileStore: RecordingFileStore
    private lateinit var factory: ShareIntentFactory
    private var nextTask = 1L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.dataTaskDao()
        fileStore = RecordingFileStore()
        factory = ShareIntentFactory(context, dao, fileStore)
        shareRoot().deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        shareRoot().deleteRecursively()
        File(context.cacheDir, "share-handoff-outside").deleteRecursively()
    }

    @Test
    fun `one validated output creates ACTION_SEND with matching stream and clip data`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "alpha.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)

        val intent = requireNotNull(factory.create(taskId, NOW_MS))
        val stream = requireNotNull(
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        )

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(BundleFormat.APK.mime, intent.type)
        assertEquals(fileStore.uris.single(), stream)
        assertEquals(stream, requireNotNull(intent.clipData).getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals("content", stream.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.provider", stream.authority)
    }

    @Test
    fun `multiple outputs preserve DAO order in ACTION_SEND_MULTIPLE and clip data`() = runTest {
        val taskId = newTaskId()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "first.apk"),
            output(taskId, ordinal = 1, displayName = "second.apk"),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach { writeOutput(it) }

        val intent = requireNotNull(factory.create(taskId, NOW_MS))
        val streams = requireNotNull(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        val clipUris = requireNotNull(intent.clipData).let { clip ->
            (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        }

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals(fileStore.uris, streams)
        assertEquals(streams, clipUris)
        assertEquals(
            outputs.map { File(context.cacheDir, it.relativePath).canonicalFile },
            fileStore.files,
        )
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun `ready partial phase shares its ready output`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "partial.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_tasks SET state = 'READY_PARTIAL' WHERE task_id = ?",
            arrayOf(taskId.toString()),
        )

        assertEquals(Intent.ACTION_SEND, requireNotNull(factory.create(taskId, NOW_MS)).action)
        assertEquals(1, fileStore.files.size)
    }

    @Test
    fun `one invalid output refuses all outputs before creating any URI`() = runTest {
        val taskId = newTaskId()
        val outputs = listOf(
            output(taskId, ordinal = 0, displayName = "first.apk"),
            output(
                taskId,
                ordinal = 1,
                displayName = "second.apk",
                mimeType = BundleFormat.APKS.mime,
            ),
        )
        persistReadyTask(taskId, outputs)
        outputs.forEach { writeOutput(it) }

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `expiry at the captured wall time is refused`() = runTest {
        val taskId = newTaskId()
        val output = output(
            taskId,
            ordinal = 0,
            displayName = "expired.apk",
            expiresAtEpochMs = NOW_MS,
        )
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `non ready Room output is refused even while the task row still says ready`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "published.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_task_outputs SET state = 'PUBLISHED' WHERE task_id = ?",
            arrayOf(taskId.toString()),
        )

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `traversing persisted path is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "escape.apk")
        persistReadyTask(taskId, listOf(output))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_task_outputs SET private_relative_path = ? WHERE task_id = ?",
            arrayOf(
                "share_ready/item-$taskId-0/com.example.alpha/../escape.apk",
                taskId.toString()
            ),
        )

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `symbolic link output is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "linked.apk")
        persistReadyTask(taskId, listOf(output))
        val outside = File(context.cacheDir, "share-handoff-outside").apply {
            parentFile?.mkdirs()
            writeText("payload")
        }
        File(context.cacheDir, output.relativePath).also { link ->
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        }

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `wrong deterministic item identity is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "wrong-identity.apk")
        persistReadyTask(taskId, listOf(output), firstItemIdentity = "item-$taskId-9")
        writeOutput(output)

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `byte size mismatch is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "changed.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output, "longer-than-recorded".toByteArray())

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `missing output is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "missing.apk")
        persistReadyTask(taskId, listOf(output))

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `wrong publication policy is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "public.apk")
        persistReadyTask(
            taskId,
            listOf(output),
            publicationPolicy = DataTaskPublicationPolicy.PUBLIC_DOCUMENT,
        )
        writeOutput(output)

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `stale task phase is refused before URI creation`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "stale.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE data_tasks SET state = 'SUCCEEDED' WHERE task_id = ?",
            arrayOf(taskId.toString()),
        )

        assertNull(factory.create(taskId, NOW_MS))
        assertTrue(fileStore.files.isEmpty())
    }

    @Test
    fun `non provider URI refuses the complete handoff`() = runTest {
        val taskId = newTaskId()
        val output = output(taskId, ordinal = 0, displayName = "wrong-provider.apk")
        persistReadyTask(taskId, listOf(output))
        writeOutput(output)
        fileStore.authority = "example.invalid"

        assertNull(factory.create(taskId, NOW_MS))
        assertEquals(1, fileStore.files.size)
    }

    private suspend fun persistReadyTask(
        taskId: UUID,
        outputs: List<OutputSpec>,
        firstItemIdentity: String = "item-$taskId-0",
        publicationPolicy: DataTaskPublicationPolicy =
            DataTaskPublicationPolicy.PRIVATE_SHARE_WITH_24_HOUR_EXPIRY,
    ) {
        val ordered = outputs.sortedBy(OutputSpec::ordinal)
        dao.insertTask(
            NewDataTaskRow(
                taskId = taskId.toString(),
                payloadSchemaVersion = 1,
                kind = DataTaskKind.SHARE_PREPARE,
                targetKey = "share:$taskId",
                initialState = DataTaskState.QUEUED,
                detail = StoredDataTaskDetail.SharePrepare(
                    requestedFormat = BundleFormat.APK,
                    publicationPolicy = publicationPolicy,
                    deterministicStagingIdentity = "stage-$taskId",
                ),
                items = ordered.map { spec ->
                    NewDataTaskItem(
                        ordinal = spec.ordinal,
                        packageName = spec.packageName,
                        displayLabel = spec.packageName,
                        deterministicStagingIdentity = if (spec.ordinal == 0) {
                            firstItemIdentity
                        } else {
                            "item-$taskId-${spec.ordinal}"
                        },
                    )
                },
                createdAtEpochMs = 1_000L,
            )
        )
        val taskClaim = "task-claim-$taskId"
        val first = requireNotNull(
            dao.claimOldestRunnableWork(
                sessionToken = "session",
                taskClaimToken = taskClaim,
                itemClaimToken = "item-claim-0",
                nowMs = 1_100L,
                leaseUntilMs = NOW_MS + 10_000L,
            )
        )
        ordered.forEachIndexed { index, spec ->
            val claimed = if (index == 0) {
                first.item
            } else {
                requireNotNull(
                    dao.claimNextPendingItem(
                        taskId = taskId.toString(),
                        taskClaimToken = taskClaim,
                        itemClaimToken = "item-claim-$index",
                        nowMs = 1_200L + index,
                        leaseUntilMs = NOW_MS + 10_000L,
                    )
                )
            }
            assertEquals(spec.ordinal, claimed.ordinal)
            assertTrue(
                dao.completeClaimedItem(
                    taskId = taskId.toString(),
                    ordinal = spec.ordinal,
                    taskClaimToken = taskClaim,
                    itemClaimToken = claimed.claimToken,
                    result = DataTaskItemResult(
                        terminalState = DataTaskItemTerminalState.SUCCEEDED,
                        resultCode = DataTaskResultCode("TEST_COMPLETE"),
                        warnings = emptyList(),
                        outputs = listOf(
                            NewDataTaskOutput(
                                outputId = UUID.nameUUIDFromBytes(
                                    "output:$taskId:${spec.ordinal}".toByteArray()
                                ),
                                privateRelativePath = spec.relativePath,
                                displayName = spec.displayName,
                                mimeType = spec.mimeType,
                                byteSize = spec.expectedBytes.size.toLong(),
                                state = DataTaskOutputState.READY,
                                expiresAtEpochMs = spec.expiresAtEpochMs,
                            )
                        ),
                        finishedAtEpochMs = 1_500L + index,
                    ),
                )
            )
        }
        assertTrue(dao.finishClaimedTaskIfDrained(taskId.toString(), taskClaim, 2_000L))
    }

    private fun output(
        taskId: UUID,
        ordinal: Int,
        displayName: String,
        packageName: String = "com.example.${if (ordinal == 0) "alpha" else "beta"}",
        mimeType: String = BundleFormat.APK.mime,
        expiresAtEpochMs: Long = NOW_MS + 1_000L,
        expectedBytes: ByteArray = "payload".toByteArray(),
    ): OutputSpec = OutputSpec(
        ordinal = ordinal,
        packageName = packageName,
        displayName = displayName,
        mimeType = mimeType,
        expiresAtEpochMs = expiresAtEpochMs,
        expectedBytes = expectedBytes,
        relativePath = "share_ready/item-$taskId-$ordinal/$packageName/$displayName",
    )

    private fun writeOutput(
        output: OutputSpec,
        content: ByteArray = output.expectedBytes,
    ) {
        File(context.cacheDir, output.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(content)
        }
    }

    private fun shareRoot(): File = File(context.cacheDir, "share_ready")

    private fun newTaskId(): UUID = UUID(0L, nextTask++)

    private class OutputSpec(
        val ordinal: Int,
        val packageName: String,
        val displayName: String,
        val mimeType: String,
        val expiresAtEpochMs: Long,
        val expectedBytes: ByteArray,
        val relativePath: String,
    )

    private class RecordingFileStore : AppBundleFileStore {
        val files = mutableListOf<File>()
        val uris = mutableListOf<Uri>()
        var authority: String = "${BuildConfig.APPLICATION_ID}.provider"

        override suspend fun writeToDownloads(file: File, mime: String): String = error("unused")

        override suspend fun writeToTree(
            file: File,
            treeUriStr: String,
            mime: String,
        ): String = error("unused")

        override suspend fun isTreeWritable(treeUriStr: String?): Boolean = false

        override suspend fun currentTargetLabel(savedTreeUriStr: String?): String = error("unused")

        override fun shareUri(file: File): String {
            files += file
            return Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(file.name)
                .build()
                .also(uris::add)
                .toString()
        }

        override suspend fun stageText(fileName: String, content: String): File = error("unused")
    }

    private companion object {
        const val NOW_MS = 10_000L
    }
}

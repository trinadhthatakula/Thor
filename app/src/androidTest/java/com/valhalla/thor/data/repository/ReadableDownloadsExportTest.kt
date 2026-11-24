// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ExportPublicationReceipt
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.repository.AppExportPublicationIdentity
import com.valhalla.thor.domain.repository.AppExportPublicationReconciliation
import com.valhalla.thor.domain.repository.VerifiedProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class ReadableDownloadsExportTest {
    @Test
    fun recoveryRemovesOnlyTheInterruptedTasksPendingRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AppBundleFileStoreImpl(context, Dispatchers.IO)
        val resolver = context.contentResolver
        val names = List(2) { "Thor-task-${UUID.randomUUID()}-0.apk" }
        val rows = mutableListOf<Uri>()
        try {
            names.forEach { name ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, BundleFormat.APK.mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Thor/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                rows += requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            }
            val identity = AppExportPublicationIdentity(names.first())
            assertEquals(AppExportPublicationReconciliation.Absent, store.reconcilePublicExport(ExportTargetChoice.Downloads, identity))
            assertEquals(null, store.displayNameOf(resolver, rows.first()))
            assertEquals(names.last(), store.displayNameOf(resolver, rows.last()))
        } finally {
            rows.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun mediaStoreKeepsBothExportsAndRecoversEachByRowId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AppBundleFileStoreImpl(context, Dispatchers.IO)
        val resolver = context.contentResolver
        val target = ExportTargetChoice.Downloads
        val label = "Test_${UUID.randomUUID()}_1.0"
        val rows = mutableListOf<Uri>()
        try {
            for (format in BundleFormat.entries) {
                val preferred = "$label.${format.extension}"
                repeat(2) { index ->
                    val identity = AppExportPublicationIdentity("Thor-task-${UUID.randomUUID()}-0.${format.extension}")
                    val file = File(context.cacheDir, identity.fileName)
                    try {
                        file.writeText("package $index")
                        var receipt: ExportPublicationReceipt? = null
                        store.publishPublicExport(file, target, format.mime, identity, VerifiedProgress.NONE, preferred) {
                            receipt = it
                            rows += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, requireNotNull(it.mediaStoreId))
                        }
                        val uri = rows.last()
                        val name = store.displayNameOf(resolver, uri)
                        if (index == 0) assertEquals(preferred, name)
                        else assertEquals("$label (1).${format.extension}", name)
                        val bytes = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                        assertEquals("package $index", bytes)
                        assertTrue(store.reconcilePublicExport(target, identity.copy(receipt = requireNotNull(receipt)))
                                is AppExportPublicationReconciliation.Complete)
                    } finally {
                        file.delete()
                    }
                }
            }
        } finally {
            rows.forEach { resolver.delete(it, null, null) }
        }
    }
}

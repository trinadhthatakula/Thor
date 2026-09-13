// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.ExportTargetChoice
import java.io.File

/** Receives byte counts only after those bytes have reached the next durable staging boundary. */
fun interface VerifiedProgress {
    suspend fun onBytesWritten(bytes: Long)

    companion object {
        val NONE = VerifiedProgress {}
    }
}

/** Stable, operation-owned name used to reconcile a durable public export after process death. */
data class AppExportPublicationIdentity(val fileName: String) {
    init {
        require(fileName.isNotBlank() && fileName != "." && fileName != "..")
        require('/' !in fileName && '\\' !in fileName)
    }
}

enum class AppExportPublicationStatus {
    PUBLISHED,
    RECONCILED,
}

data class AppExportPublication(
    val destinationLabel: String,
    val status: AppExportPublicationStatus,
)

sealed interface AppExportPublicationReconciliation {
    data object Absent : AppExportPublicationReconciliation

    data class Complete(val publication: AppExportPublication) :
        AppExportPublicationReconciliation
}

/**
 * Domain port for persisting/sharing an already-built app bundle. Keeps the
 * export/share use cases free of Android file-I/O concerns (MediaStore, SAF,
 * FileProvider, ContentResolver, Environment) — the concrete impl lives in the
 * data layer. Signatures use only [File]/String/primitives, no Android types.
 */
interface AppBundleFileStore {
    /**
     * Reconcile only [identity]'s exact final and incomplete publication at [target].
     * A completed publication is reusable; incomplete state is removed before replay.
     */
    suspend fun reconcilePublicExport(
        target: ExportTargetChoice,
        identity: AppExportPublicationIdentity,
    ): AppExportPublicationReconciliation = AppExportPublicationReconciliation.Absent

    /** Publish [file] under [identity], reporting only bytes already written. */
    suspend fun publishPublicExport(
        file: File,
        target: ExportTargetChoice,
        mime: String,
        identity: AppExportPublicationIdentity,
        progress: VerifiedProgress,
    ): AppExportPublication {
        require(file.name == identity.fileName)
        val label = when (target) {
            is ExportTargetChoice.Custom -> writeToTree(file, target.treeUri, mime)
            ExportTargetChoice.Downloads -> writeToDownloads(file, mime)
        }
        return AppExportPublication(label, AppExportPublicationStatus.PUBLISHED)
    }

    /** Write [file] to public Downloads/Thor; returns a human-readable location label. */
    suspend fun writeToDownloads(file: File, mime: String): String

    suspend fun writeToDownloads(
        file: File,
        mime: String,
        progress: VerifiedProgress,
    ): String = writeToDownloads(file, mime)

    /** Write [file] into the user-picked SAF tree [treeUriStr]; returns a location label. */
    suspend fun writeToTree(file: File, treeUriStr: String, mime: String): String

    suspend fun writeToTree(
        file: File,
        treeUriStr: String,
        mime: String,
        progress: VerifiedProgress,
    ): String = writeToTree(file, treeUriStr, mime)

    /** True when [treeUriStr] resolves to a currently writable SAF tree. */
    suspend fun isTreeWritable(treeUriStr: String?): Boolean

    /** Label to show for the current export target given the saved SAF tree URI. */
    suspend fun currentTargetLabel(savedTreeUriStr: String?): String

    /** Content-uri (as String) to share [file] via FileProvider. */
    fun shareUri(file: File): String

    /**
     * Stage [content] as [fileName] in a shareable cache directory, replacing the previous staging.
     *
     * For the small text artefacts a caller builds in memory rather than from an installed package
     * — the app-list CSV — which the bundle builder has no way to produce.
     *
     * The directory is wiped on entry rather than the file being deleted when the caller finishes,
     * because a staged file that has been shared must outlive the call that made it: the receiving
     * app opens the `content://` URI whenever it gets round to it. Clearing on the *next* export is
     * the only point at which nobody can still be reading the last one.
     */
    suspend fun stageText(fileName: String, content: String): File
}

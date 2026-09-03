// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID

enum class DataTaskKind { ARCHIVE_BACKUP, ARCHIVE_RESTORE, APP_EXPORT, SHARE_PREPARE }

enum class DataTaskState {
    QUEUED,
    STAGING_SOURCE,
    RUNNING,
    CANCEL_REQUESTED,
    WAITING_FOR_AUTH,
    WAITING_FOR_SOURCE,
    INTERRUPTED_REVIEW,
    READY,
    READY_PARTIAL,
    START_BLOCKED,
    START_BLOCKED_NOTIFICATION,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
}

enum class DataTaskItemState { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class DataTaskItemTerminalState { SUCCEEDED, FAILED, CANCELLED }

enum class DataTaskOutputState { STAGING, READY, PUBLISHED, SHARED, DISMISSED, EXPIRED }

enum class DataTaskStage {
    PREPARING,
    STAGING_SOURCE,
    MEASURING,
    CAPTURING,
    WRITING,
    INSTALLING,
    RESTORING,
    PUBLISHING,
    FINISHING,
}

enum class DataTaskInterruption {
    NONE,
    AUTHENTICATION_REQUIRED,
    SOURCE_REQUIRED,
    DESTRUCTIVE_RESTORE_REVIEW,
}

@JvmInline
value class DataTaskResultCode(val value: String) {
    init {
        require(value.matches(Regex("[A-Z0-9_]{1,64}")))
    }
}

data class DataTaskMessage(
    val code: DataTaskResultCode,
    val arguments: List<String> = emptyList(),
)

data class RestoreMutationBreadcrumb(
    val packageName: String,
    val appLabel: String,
    val startedAtEpochMs: Long,
)

sealed interface StoredDataDestination {
    data object ArchiveStore : StoredDataDestination

    data object Downloads : StoredDataDestination

    data object TaskPrivateStorage : StoredDataDestination

    data class PersistedTreeGrant(val grantIdentity: String) : StoredDataDestination
}

sealed interface StoredRestoreSource {
    data object AwaitingTransientGrant : StoredRestoreSource

    data class PersistedGrant(val grantIdentity: String) : StoredRestoreSource

    data class PrivateCopy(val privateRelativePath: String) : StoredRestoreSource
}

enum class DataTaskPublicationPolicy { PUBLIC_DOCUMENT, PRIVATE_SHARE_WITH_24_HOUR_EXPIRY }

sealed interface StoredDataTaskDetail {
    val deterministicStagingIdentity: String

    data class ArchiveBackup(
        val packageName: String,
        val dataClassIds: List<String>,
        val includeBundle: Boolean,
        val kdfSaltBase64: String,
        val destination: StoredDataDestination,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail

    data class ArchiveRestore(
        val expectedPackageName: String,
        val dataClassIds: List<String>,
        val restoreObb: Boolean,
        val source: StoredRestoreSource,
        val mutationBreadcrumb: RestoreMutationBreadcrumb?,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail

    data class AppExport(
        val requestedFormat: BundleFormat,
        val destination: StoredDataDestination,
        val namingLabel: String,
        val publicationPolicy: DataTaskPublicationPolicy,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail

    data class SharePrepare(
        val requestedFormat: BundleFormat,
        val publicationPolicy: DataTaskPublicationPolicy,
        override val deterministicStagingIdentity: String,
    ) : StoredDataTaskDetail
}

data class DataTaskCheckpoint(
    val stage: DataTaskStage,
    val completed: Long,
    val total: Long,
    val activeItemOrdinal: Int?,
    val activeItemLabel: String?,
    val destructiveStarted: Boolean,
    val restoreMutationBreadcrumb: RestoreMutationBreadcrumb?,
    val recordedAtEpochMs: Long,
)

data class NewDataTaskOutput(
    val outputId: UUID,
    val privateRelativePath: String?,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val state: DataTaskOutputState,
    val expiresAtEpochMs: Long?,
)

data class DataTaskItemResult(
    val terminalState: DataTaskItemTerminalState,
    val resultCode: DataTaskResultCode,
    val warnings: List<DataTaskMessage>,
    val outputs: List<NewDataTaskOutput>,
    val finishedAtEpochMs: Long,
)

sealed interface DataTaskRunOutcome {
    data class ItemCompleted(val result: DataTaskItemResult) : DataTaskRunOutcome

    data class WaitingForAuthentication(val resultCode: DataTaskResultCode) : DataTaskRunOutcome

    data class WaitingForSource(val resultCode: DataTaskResultCode) : DataTaskRunOutcome

    data class InterruptedReview(
        val resultCode: DataTaskResultCode,
        val breadcrumb: RestoreMutationBreadcrumb,
    ) : DataTaskRunOutcome

    data class TaskFailed(
        val resultCode: DataTaskResultCode,
        val arguments: List<String> = emptyList(),
    ) : DataTaskRunOutcome

    data object Cancelled : DataTaskRunOutcome

    data object OwnershipLost : DataTaskRunOutcome
}

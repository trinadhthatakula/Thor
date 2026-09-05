// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import java.util.UUID

internal const val MAX_TASK_PRESENTATION_ARGUMENTS = 8
internal const val MAX_TASK_PRESENTATION_ARGUMENT_CHARS = 512
internal const val MAX_TASK_WARNING_COUNT = 4
private const val MAX_TASK_GRANT_IDENTITY_CHARS = 128
private val TASK_GRANT_IDENTITY = Regex("[A-Za-z0-9_-]{1,$MAX_TASK_GRANT_IDENTITY_CHARS}")
private val TASK_STABLE_CODE = Regex("[A-Za-z0-9_]{1,64}")
private val TASK_URI = Regex(
    "(?i)(?:[a-z][a-z0-9+.-]{1,31}://|(?:content|file|android\\.resource):/)",
)
private val TASK_RAW_DIAGNOSTIC = Regex(
    "(?i)(?:^|\\s)(?:caused by:|suppressed:|at\\s+\\S+\\([^)]*(?::\\d+)?\\)|" +
            "[\\w.$]+(?:exception|error)(?::|\\s|$))",
)
private val TASK_ABSOLUTE_PATH = Regex(
    "(?i)(?:(?<![\\p{L}\\p{N}_])/(?=\\S|$)|" +
            "(?<![\\p{L}\\p{N}_])[a-z]:[/\\\\]|" +
            "(?<![\\p{L}\\p{N}_])\\\\(?=\\S|$))",
)
private val TASK_SHELL_TEXT = Regex(
    "^\\s*(?:(?:pm|am|appops|dpm|cmd(?:\\s+package)?|sh|su)\\s*:|" +
            "pm\\s+(?:install(?:-[a-z-]+)?|uninstall|clear|enable|disable(?:-user)?|" +
            "suspend|unsuspend|hide|unhide|grant|revoke|list|path|dump)\\b|" +
            "am\\s+(?:force-stop|kill|start|broadcast)\\b|" +
            "appops\\s+(?:set|get|reset)\\b|cmd\\s+package\\s+\\S+|dpm\\s+\\S+)",
)

internal fun requireTaskPresentationArguments(arguments: List<String>, fieldName: String) {
    require(arguments.size <= MAX_TASK_PRESENTATION_ARGUMENTS) {
        "$fieldName contains too many presentation arguments"
    }
    arguments.forEach { argument ->
        require(argument.length <= MAX_TASK_PRESENTATION_ARGUMENT_CHARS) {
            "$fieldName contains an oversized presentation argument"
        }
        require(argument.none(Char::isISOControl)) {
            "$fieldName contains a control character"
        }
        require(!TASK_URI.containsMatchIn(argument)) {
            "$fieldName contains URI text"
        }
        require(!looksLikeAbsoluteOrTraversingPath(argument)) {
            "$fieldName contains path text"
        }
        require(!TASK_SHELL_TEXT.containsMatchIn(argument)) {
            "$fieldName contains raw shell text"
        }
        require(!TASK_RAW_DIAGNOSTIC.containsMatchIn(argument)) {
            "$fieldName contains raw diagnostic text"
        }
    }
}

internal fun requireTaskStableCode(value: String, fieldName: String) {
    require(TASK_STABLE_CODE.matches(value)) { "$fieldName must be a stable code" }
}

internal fun requireTaskProgress(completed: Long, total: Long) {
    require(completed >= 0) { "completed must be non-negative" }
    require(total >= 0) { "total must be non-negative" }
    require(total == 0L || completed <= total) {
        "completed must not exceed a positive total"
    }
}

private fun requireOpaqueGrantIdentity(value: String) {
    require(TASK_GRANT_IDENTITY.matches(value)) {
        "grantIdentity must be a bounded opaque identifier"
    }
}

private fun requireTaskRelativePath(value: String) {
    require(value.isNotEmpty()) { "privateRelativePath must not be empty" }
    require(value.none(Char::isISOControl)) {
        "privateRelativePath must not contain control characters"
    }
    require(!TASK_URI.containsMatchIn(value)) {
        "privateRelativePath must not contain a URI"
    }
    require('\\' !in value) {
        "privateRelativePath must use normalized separators"
    }
    require(!looksLikeAbsoluteOrTraversingPath(value)) {
        "privateRelativePath must be normalized and relative"
    }
    require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "privateRelativePath must be normalized and relative"
    }
}

private fun looksLikeAbsoluteOrTraversingPath(value: String): Boolean =
    TASK_ABSOLUTE_PATH.containsMatchIn(value) ||
            value.split('/', '\\').any { it == "." || it == ".." }

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
    SERVICE_TIMEOUT,
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

internal fun DataTaskResultCode.toUserFacingJobMessage(): String = when (value) {
    "ARCHIVE_BACKUP_APP_NOT_INSTALLED" -> "the app is not installed"
    "ARCHIVE_BACKUP_BUNDLE_FAILED" -> "the app's installer bundle could not be built"
    "ARCHIVE_BACKUP_DESTINATION_REQUIRED" -> "choose a folder for Thor's backups first"
    "ARCHIVE_RESTORE_NOT_AN_ARCHIVE" -> "that file is not a Thor backup"
    "ARCHIVE_RESTORE_SOURCE_UNREADABLE", "SOURCE_REQUIRED" ->
        "Thor could not read that backup file"

    "ARCHIVE_RESTORE_AUTHENTICATION_FAILED" ->
        "this backup could not be authenticated and was not restored"

    "ARCHIVE_AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED" ->
        "this archive's key is no longer in memory — start it again"

    "ARCHIVE_RESTORE_INTERRUPTED", "DESTRUCTIVE_RESTORE_REVIEW" ->
        "this restore stopped after it began changing the app; review it before trying again"

    else -> "the archive job could not be completed"
}

data class DataTaskMessage(
    val code: DataTaskResultCode,
    val arguments: List<String> = emptyList(),
) {
    init {
        requireTaskPresentationArguments(arguments, "arguments")
    }
}

data class RestoreMutationBreadcrumb(
    val packageName: String,
    val appLabel: String,
    val startedAtEpochMs: Long,
)

sealed interface StoredDataDestination {
    data object ArchiveStore : StoredDataDestination

    data object Downloads : StoredDataDestination

    data object TaskPrivateStorage : StoredDataDestination

    data class PersistedTreeGrant(val grantIdentity: String) : StoredDataDestination {
        init {
            requireOpaqueGrantIdentity(grantIdentity)
        }
    }
}

sealed interface StoredRestoreSource {
    data object AwaitingTransientGrant : StoredRestoreSource

    data class PersistedGrant(val grantIdentity: String) : StoredRestoreSource {
        init {
            requireOpaqueGrantIdentity(grantIdentity)
        }
    }

    data class PrivateCopy(val privateRelativePath: String) : StoredRestoreSource {
        init {
            requireTaskRelativePath(privateRelativePath)
        }
    }
}

internal fun privateRestoreSourceRelativePath(taskId: UUID): String =
    "data_tasks/$taskId/restore-source.thor"

internal fun isPrivateRestoreSourceRelativePath(taskId: UUID, relativePath: String): Boolean =
    relativePath == privateRestoreSourceRelativePath(taskId)

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
) {
    init {
        requireTaskProgress(completed, total)
        require(activeItemOrdinal == null || activeItemOrdinal >= 0) {
            "activeItemOrdinal must be non-negative"
        }
    }
}

data class NewDataTaskOutput(
    val outputId: UUID,
    val privateRelativePath: String?,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val state: DataTaskOutputState,
    val expiresAtEpochMs: Long?,
) {
    init {
        privateRelativePath?.let(::requireTaskRelativePath)
        require(byteSize >= 0) { "byteSize must be non-negative" }
    }
}

data class DataTaskItemResult(
    val terminalState: DataTaskItemTerminalState,
    val resultCode: DataTaskResultCode,
    val warnings: List<DataTaskMessage>,
    val outputs: List<NewDataTaskOutput>,
    val finishedAtEpochMs: Long,
) {
    init {
        require(warnings.size <= MAX_TASK_WARNING_COUNT) {
            "warnings contains too many entries"
        }
    }
}

sealed interface DataTaskRunOutcome {
    data class ItemCompleted(val result: DataTaskItemResult) : DataTaskRunOutcome

    data class WaitingForAuthentication(val resultCode: DataTaskResultCode) : DataTaskRunOutcome

    data class WaitingForSource(val resultCode: DataTaskResultCode) : DataTaskRunOutcome

    data class InterruptedReview(
        val resultCode: DataTaskResultCode,
        val breadcrumb: RestoreMutationBreadcrumb?,
    ) : DataTaskRunOutcome

    data class TaskFailed(
        val resultCode: DataTaskResultCode,
        val arguments: List<String> = emptyList(),
    ) : DataTaskRunOutcome {
        init {
            requireTaskPresentationArguments(arguments, "arguments")
        }
    }

    data object Cancelled : DataTaskRunOutcome

    data object OwnershipLost : DataTaskRunOutcome
}

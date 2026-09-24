// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.data.appops.AppOpsCommands
import com.valhalla.thor.data.appops.AppOpsParseResult
import com.valhalla.thor.data.appops.AppOpsParser
import com.valhalla.thor.data.gateway.userIdOf
import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.domain.model.AppOpScope
import com.valhalla.thor.domain.model.AppOpsSnapshot
import com.valhalla.thor.domain.model.ShellLaneBusy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AppOpsTarget(
    val uid: Int,
    val requestedPermissions: Set<String>,
    val sharedUidPackages: List<String>,
)

/** A single selected privileged transport for the whole read/write/readback operation. */
internal fun interface AppOpsCommandSession {
    suspend fun execute(command: String): Pair<Int, String?>
}

/** Testable orchestration; Android package lookup and privileged transport live in the adapter. */
internal class AppOpsController(
    private val currentUserId: () -> Int,
    private val loadTarget: (String) -> AppOpsTarget,
    private val loadCatalog: suspend (AppOpsCommandSession) -> List<AppOpDefinition>,
    private val openSession: suspend (String) -> AppOpsCommandSession,
) {
    private val mutex = Mutex()

    suspend fun getAppOps(packageName: String): Result<AppOpsSnapshot> = guarded {
        // A cold-start root probe can briefly occupy the immediate-admission interactive lane.
        // Retry only read-only admission failures, rebuilding the whole snapshot each time.
        repeat(READ_ATTEMPTS - 1) {
            try {
                return@guarded readSnapshot(packageName)
            } catch (_: ShellLaneBusy) {
                delay(READ_RETRY_DELAY_MS)
            }
        }
        readSnapshot(packageName)
    }

    private suspend fun readSnapshot(packageName: String): AppOpsSnapshot {
        val target = checkedTarget(packageName)
        val session = openSession(packageName)
        val definitions = loadCatalog(session)
        val parsed = readEntries(session, packageName, target, definitions)
        check(checkedTarget(packageName).uid == target.uid) { "The app changed while reading App Ops. Refresh and try again." }
        return AppOpsSnapshot(
            userId = userIdOf(target.uid),
            uid = target.uid,
            entries = parsed.entries,
            canEdit = true,
            sharedUidPackages = target.sharedUidPackages,
            unsupportedOperationCount = parsed.unsupportedOperationCount,
        )
    }

    suspend fun setMode(
        packageName: String,
        code: Int,
        scope: AppOpScope,
        mode: AppOpMode?,
    ): Result<Unit> = guarded {
        val target = checkedTarget(packageName)
        val session = openSession(packageName)
        val definitions = loadCatalog(session)
        val definition = definitions.singleOrNull { it.code == code }
            ?: error("This operation is not available on this device.")
        check(!definition.isRuntimePermissionControlled) {
            "Android controls this App Ops mode through runtime permissions. Use the Permissions tab."
        }
        val expected = mode ?: definition.platformDefault.also {
            check(definition.allowsReset) { "This operation cannot be reset to its platform default." }
        }
        require(expected != AppOpMode.UNKNOWN) { "Unknown App Ops mode cannot be written." }
        // Refuse to mutate if this platform's output cannot first be read unambiguously.
        readEntries(session, packageName, target, definitions)
        check(checkedTarget(packageName).uid == target.uid) { "The app changed before updating App Ops. Refresh and try again." }
        val output = execute(
            session,
            AppOpsCommands.setMode(packageName, target.uid, userIdOf(target.uid), code, scope, expected),
        )
        check(output.isBlank()) { "Android returned an unexpected response while updating App Ops." }
        check(checkedTarget(packageName).uid == target.uid) { "The app changed while updating App Ops. Refresh and try again." }
        val entry = readEntries(session, packageName, target, definitions).entries.single { it.definition.code == code }
        check(checkedTarget(packageName).uid == target.uid) { "The app changed while verifying App Ops. Refresh and try again." }
        // Verify the requested scope, not the displayed/effective mode: a UID override can mask
        // a perfectly valid package write, and a package value cannot prove a UID write worked.
        val recorded = when (scope) {
            AppOpScope.PACKAGE -> entry.packageMode
            AppOpScope.UID -> entry.uidMode
        } ?: definition.platformDefault
        check(recorded == expected) { "Android did not apply the requested App Ops mode. Refresh and try again." }
    }

    private fun checkedTarget(packageName: String): AppOpsTarget {
        val userId = currentUserId()
        // Validate the name even if a test or a future package source does not use PackageManager.
        AppOpsCommands.getPackage(packageName, userId)
        return loadTarget(packageName).also {
            require(it.uid >= 0 && userIdOf(it.uid) == userId) { "The app does not belong to this Android user." }
        }
    }

    private suspend fun readEntries(
        session: AppOpsCommandSession,
        packageName: String,
        target: AppOpsTarget,
        definitions: List<AppOpDefinition>,
    ): AppOpsParseResult {
        val userId = userIdOf(target.uid)
        val uidOutput = execute(session, AppOpsCommands.getUid(target.uid, userId))
        val packageOutput = execute(session, AppOpsCommands.getPackage(packageName, userId))
        val uidAfter = execute(session, AppOpsCommands.getUid(target.uid, userId))
        check(uidOutput.trim() == uidAfter.trim()) { "App Ops changed while reading. Refresh and try again." }
        return AppOpsParser.parseSnapshot(packageOutput, uidOutput, definitions, target.requestedPermissions)
    }

    private suspend fun execute(session: AppOpsCommandSession, command: String): String {
        val (exitCode, output) = session.execute(command)
        check(exitCode == 0) { "Android could not read or update App Ops (exit $exitCode)." }
        return output.orEmpty()
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        resultPreservingCancellation { mutex.withLock { Result.success(block()) } }

    private companion object {
        const val READ_ATTEMPTS = 5
        const val READ_RETRY_DELAY_MS = 100L
    }
}

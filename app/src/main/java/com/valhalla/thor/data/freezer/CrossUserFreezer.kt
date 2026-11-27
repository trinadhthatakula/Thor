// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.valhalla.thor.data.gateway.RootSystemGateway
import com.valhalla.thor.data.gateway.root.RootSuspensionReadback
import com.valhalla.thor.data.gateway.root.RootSuspensionReadbackClient
import com.valhalla.thor.data.source.local.SuspensionMarkers
import com.valhalla.thor.data.source.local.UadHelper
import com.valhalla.thor.data.source.local.room.CrossUserFreezerDao
import com.valhalla.thor.data.source.local.room.ScopedFreezerAppEntity
import com.valhalla.thor.data.source.local.room.ScopedFreezerDisableEntity
import com.valhalla.thor.data.source.local.room.ScopedFreezerProfileEntity
import com.valhalla.thor.data.source.local.shizuku.Shizuku
import com.valhalla.thor.data.source.local.knownThorSuspendDialogs
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.data.source.local.toFreezerUser
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeProfileIcon
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.FreezerUser
import com.valhalla.thor.domain.model.OwnedSuspensionSnapshot
import com.valhalla.thor.domain.model.PrivilegeExecutionContext
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.ScopedFreezerApp
import com.valhalla.thor.domain.model.SuspensionOwner
import com.valhalla.thor.domain.model.changeOwnedSuspension
import com.valhalla.thor.domain.model.freezeTierOf
import com.valhalla.thor.domain.repository.PrivilegeStateProvider
import com.valhalla.thor.domain.repository.StoredPrivilegeSweep
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol
import com.valhalla.thor.rootservice.parseSuspensionDump
import com.valhalla.thor.rootservice.IThorRootService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private val packagePattern = Regex("[a-zA-Z0-9._]+")
private const val SHELL_ACTOR = "com.android.shell"

@Single
internal class CrossUserFreezer(
    private val context: Context,
    private val root: RootSystemGateway,
    private val privilege: PrivilegeStateProvider,
    private val dao: CrossUserFreezerDao,
    private val uad: UadHelper,
    @Named("io") private val io: CoroutineDispatcher,
) {
    val privilegeState get() = privilege.state

    suspend fun users(): List<FreezerUser> = withContext(io) {
        transport()?.users().orEmpty()
    }

    suspend fun apps(user: FreezerUser): List<ScopedFreezerApp> = withContext(io) {
        val gateway = requireTransport(user)
        val names = gateway.command("cmd package list packages --user ${user.id}")
            .lineSequence().mapNotNull { line -> line.takeIf { it.startsWith("package:") }?.removePrefix("package:") }
            .filter(packagePattern::matches).distinct().toList()
        val ownedDisables = dao.ownedDisables(user.serial).toSet()
        val uadSnapshot = uad.snapshot()
        val list = names.filter { it != context.packageName }.mapNotNull { name ->
            gateway.info(name, user.id)?.let { info ->
                ScopedFreezerApp(
                    packageName = name,
                    label = appLabel(info),
                    isSystem = info.isSystem,
                    isDisabled = !info.enabled,
                    isSuspended = info.isSuspended,
                    canFreeze = freezeTierOf(info.isSystem, uadSnapshot.recommendationFor(name),
                        uadSnapshot.loadFailed) == FreezeTier.NORMAL,
                    canRestore = name in ownedDisables,
                )
            }
        }
        gateway.verify(user)
        list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    suspend fun watchlist(user: FreezerUser): Set<String> = dao.watchlist(user.serial).toSet()

    suspend fun profiles(user: FreezerUser): List<FreezeProfile> {
        requireTransport(user)
        return dao.profiles(user.serial).map { row ->
            FreezeProfile(row.id, row.name, Json.decodeFromString<List<String>>(row.packagesJson),
                FreezeProfileIcon.fromKey(row.icon) ?: FreezeProfileIcon.FREEZER)
        }
    }

    suspend fun saveProfile(user: FreezerUser, profile: FreezeProfile) = withContext(io) {
        val gateway = requireTransport(user)
        val packages = profile.packageNames.sorted().distinct()
        require(packages.all { it.matches(packagePattern) && gateway.info(it, user.id) != null })
        val name = profile.name.trim()
        require(name.isNotBlank() && packages.isNotEmpty())
        val json = Json.encodeToString(packages)
        if (profile.id == 0L) {
            dao.createProfile(ScopedFreezerProfileEntity(userSerial = user.serial, name = name,
                packagesJson = json, icon = profile.icon.key))
        } else {
            check(dao.updateProfile(user.serial, profile.id, name, json, profile.icon.key) == 1)
        }
    }

    suspend fun deleteProfile(user: FreezerUser, id: Long) {
        requireTransport(user)
        dao.deleteProfile(user.serial, id)
    }

    suspend fun removeFromWatchlist(user: FreezerUser, app: ScopedFreezerApp) {
        val gateway = requireTransport(user)
        val current = withContext(io) { gateway.info(app.packageName, user.id) }
        check(current == null || (current.enabled && !current.isSuspended))
        dao.removeFromWatchlist(user.serial, app.packageName)
    }

    suspend fun execute(
        request: StoredPrivilegeSweep,
        packageName: String,
        execution: PrivilegeExecutionContext,
    ): Boolean = withContext(io) {
        val user = request.targetUser() ?: return@withContext false
        val gateway = requireTransport(user, execution)
        require(packageName != context.packageName && packagePattern.matches(packageName))
        val info = checkNotNull(gateway.info(packageName, user.id))
        val op = request.operation
        if (op == PrivilegeSweepOperation.FREEZE || op == PrivilegeSweepOperation.SUSPEND) {
            val snapshot = uad.snapshot()
            // Expert packages require an explicit risk acknowledgement; the cross-user surface
            // offers reversible operations only for packages that do not need that prompt.
            check(freezeTierOf(info.isSystem, snapshot.recommendationFor(packageName),
                snapshot.loadFailed) == FreezeTier.NORMAL)
        }
        val suspendedRemains = when (op) {
            PrivilegeSweepOperation.FREEZE -> if (request.freezerMode == FreezerMode.SUSPEND) {
                changeSuspension(gateway, user, packageName, true)
            } else {
                disable(gateway, user, packageName, info)
                false
            }
            PrivilegeSweepOperation.UNFREEZE -> {
                restore(gateway, user, packageName)
                changeSuspension(gateway, user, packageName, false)
            }
            PrivilegeSweepOperation.SUSPEND -> changeSuspension(gateway, user, packageName, true)
            PrivilegeSweepOperation.UNSUSPEND -> changeSuspension(gateway, user, packageName, false)
            else -> error("Unsupported cross-user operation")
        }
        gateway.verify(user)
        if (request.addToFreezer) dao.addToWatchlist(ScopedFreezerAppEntity(user.serial, packageName))
        suspendedRemains
    }

    private suspend fun requireTransport(
        user: FreezerUser,
        execution: PrivilegeExecutionContext = PrivilegeExecutionContext(),
    ): Transport = withContext(io) {
        checkNotNull(transport(execution)).also { it.verify(user) }
    }

    private suspend fun transport(execution: PrivilegeExecutionContext = PrivilegeExecutionContext()): Transport? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (privilege.state.value.active) {
            PrivilegeMode.ROOT -> root.getRootService()?.let { Transport(it, execution) }
            PrivilegeMode.SHIZUKU -> if (Shizuku.uidOrNull() in setOf(0, 2000)) Transport(null, execution) else null
            else -> null
        }
    }

    private fun appLabel(info: ApplicationInfo): String {
        // ApplicationInfo is read for the target user. Loading its label reads only APK resources.
        val label = runCatching { info.loadLabel(context.packageManager).toString() }.getOrNull()
        return label?.takeIf { it.isNotBlank() } ?: info.packageName
    }

    private suspend fun disable(gateway: Transport, user: FreezerUser, packageName: String, info: ApplicationInfo) {
        val previous = dao.previousSetting(user.serial, packageName)
        if (!info.enabled) {
            check(previous != null &&
                gateway.setting(packageName, user.id) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            return
        }
        val setting = gateway.setting(packageName, user.id)
        check(setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        if (previous != null && previous != setting) dao.forgetDisable(user.serial, packageName)
        dao.rememberDisable(ScopedFreezerDisableEntity(user.serial, packageName, setting))
        gateway.verify(user)
        gateway.command("cmd package disable-user --user ${user.id} $packageName")
        check(gateway.info(packageName, user.id)?.enabled == false &&
            gateway.setting(packageName, user.id) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
    }

    private suspend fun restore(gateway: Transport, user: FreezerUser, packageName: String) {
        val previous = dao.previousSetting(user.serial, packageName) ?: return
        val current = gateway.setting(packageName, user.id)
        if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            val command = if (previous == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"
            gateway.verify(user)
            gateway.command("cmd package $command --user ${user.id} $packageName")
            check(gateway.setting(packageName, user.id) == previous &&
                gateway.info(packageName, user.id)?.enabled == true)
        }
        check(gateway.info(packageName, user.id)?.enabled == true)
        dao.forgetDisable(user.serial, packageName)
    }

    private suspend fun changeSuspension(
        gateway: Transport,
        user: FreezerUser,
        packageName: String,
        desired: Boolean,
    ): Boolean {
        val rootBacked = gateway.daemon != null || Shizuku.isRoot
        val actor = if (rootBacked) SuspensionOwner(context.packageName, user.id)
            else SuspensionOwner(SHELL_ACTOR, if (Build.VERSION.SDK_INT >= 35) 0 else user.id)
        if (desired && !rootBacked) Shizuku.freezerSuspensionMarker(packageName, user.id)
        val result = changeOwnedSuspension(
            desired, Build.VERSION.SDK_INT, actor,
            read = { gateway.readSuspension(packageName, user, actor) },
            write = { owner, suspended ->
                gateway.verify(user)
                if (gateway.daemon != null) {
                    // The legacy setter cannot report a cross-user co-owner. The scoped read
                    // performed by changeOwnedSuspension verifies the complete owner set.
                    gateway.daemon.setAppSuspendedAsForUser(packageName, suspended, owner.packageName, user.id)
                } else Shizuku.setFreezerSuspension(context, packageName, user.id, owner, suspended)
            },
            canWrite = { owner -> owner.packageName == actor.packageName &&
                (owner.userId == actor.userId || owner.userId == user.id) },
        )
        return !desired && result.suspensionRemains
    }

    private inner class Transport(
        val daemon: IThorRootService?,
        private val execution: PrivilegeExecutionContext,
    ) {
        fun users(): List<FreezerUser> = (if (daemon != null) daemon.listFreezerUsers() else Shizuku.listFreezerUsers())
            .orEmpty().mapNotNull { it.toFreezerUser() }.filter { it.id != thorUserId }.sortedBy { it.id }

        fun verify(user: FreezerUser) {
            check(users().any { it.id == user.id && it.serial == user.serial })
        }

        fun info(name: String, userId: Int): ApplicationInfo? =
            if (daemon != null) daemon.getFreezerApplicationInfo(name, userId)
            else Shizuku.getFreezerApplicationInfo(name, userId)

        fun setting(name: String, userId: Int): Int =
            if (daemon != null) daemon.getFreezerEnabledSetting(name, userId)
            else Shizuku.getFreezerEnabledSetting(name, userId)

        suspend fun command(command: String): String {
            val (exitCode, output) = if (daemon != null) root.executeShellCommand(command, execution).getOrThrow()
                else Shizuku.executeCombined(command)
            check(exitCode == 0) { "The target user's package manager refused an operation" }
            return output.orEmpty()
        }

        suspend fun readSuspension(
            name: String,
            user: FreezerUser,
            actor: SuspensionOwner,
        ): OwnedSuspensionSnapshot? {
            val userId = user.id
            val info = info(name, userId) ?: return null
            if (!info.isSuspended) return OwnedSuspensionSnapshot(emptySet(), emptySet())
            val reportedOwners = if (daemon != null) {
                val readback = RootSuspensionReadbackClient { pkg, id ->
                    daemon.getScopedFreezerSuspension(pkg, id, user.serial)
                }.read(name, userId)
                if (readback !is RootSuspensionReadback.Suspended) return null
                readback.owners.map { SuspensionOwner(it.packageName, it.userId) }.toSet()
            } else {
                val parsed = parseSuspensionDump(
                    command("dumpsys package $name"), name, userId, Build.VERSION.SDK_INT,
                    trustedStringOwners = setOf(SHELL_ACTOR, context.packageName),
                    trustedDialogs = knownThorSuspendDialogs(context),
                )
                if (parsed.status != SuspensionReadbackProtocol.STATUS_SUSPENDED) return null
                parsed.owners.map { SuspensionOwner(it.packageName, it.userId) }.toSet()
            }
            val owners = if (actor.packageName == SHELL_ACTOR)
                normalizeFreezerShellOwner(reportedOwners, actor, userId) ?: return null
            else reportedOwners
            val token = SuspensionMarkers(context).token(name, userId)
            val ours = owners.filterTo(mutableSetOf()) { owner ->
                (owner.packageName == context.packageName && owner.userId == userId) ||
                    (daemon == null && !Shizuku.isRoot && owner.packageName == SHELL_ACTOR &&
                        token != null && Shizuku.freezerSuspensionMarker(name, userId) == token)
            }
            return OwnedSuspensionSnapshot(owners, ours)
        }
    }
}

/** Android releases with the cross-user suspension flag off report shell under the target user. */
internal fun normalizeFreezerShellOwner(
    owners: Set<SuspensionOwner>,
    actor: SuspensionOwner,
    targetUserId: Int,
): Set<SuspensionOwner>? {
    val aliases = owners.filter { it.packageName == actor.packageName &&
        (it.userId == actor.userId || it.userId == targetUserId) }
    if (aliases.size > 1) return null
    return aliases.singleOrNull()?.let { owners - it + actor } ?: owners
}

internal fun StoredPrivilegeSweep.targetUser(): FreezerUser? = targetUserSerial?.let {
    FreezerUser(userId, it, targetUserName.orEmpty(), false)
}

internal val ApplicationInfo.isSystem: Boolean get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
internal val ApplicationInfo.isSuspended: Boolean get() = (flags and ApplicationInfo.FLAG_SUSPENDED) != 0

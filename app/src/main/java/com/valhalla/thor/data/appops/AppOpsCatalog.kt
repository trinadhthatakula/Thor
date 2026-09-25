// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.valhalla.bypass.Bypass
import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpMode
import com.valhalla.thor.util.Logger

/** The running Android build, including OEM additions, owns the operation catalog. */
internal object AppOpsCatalog {
    private val cachedOperations: List<CatalogOperation> by lazy {
        // getNumOps/opToPublicName are not available on all supported releases. getOpStrs is
        // available on API 28 and its indices are the numeric operation codes, even for null names.
        val names = Bypass.invoke<Array<String?>>(
            AppOpsManager::class.java, null, "getOpStrs",
        )
        names.mapIndexed { code, publicName ->
            CatalogOperation(
                code = code,
                switchCode = invokeForCode("opToSwitch", code),
                debugName = invokeForCode("opToName", code),
                publicName = publicName,
                permission = invokeForCode("opToPermission", code),
                defaultMode = AppOpMode.fromPlatformValue(invokeForCode("opToDefaultMode", code)),
                allowsReset = invokeForCode("opAllowsReset", code),
            )
        }
    }

    /** Call off the main thread: first access resolves hidden framework metadata. */
    fun load(packageManager: PackageManager, mappingEnabled: Boolean?): List<AppOpDefinition> = fromOperations(
        withRuntimePermissionPolicy(
            operations = cachedOperations,
            mappingEnabled = mappingEnabled,
            runtimePermissionOpCode = { permission ->
                try {
                    @Suppress("DEPRECATION")
                    val info = packageManager.getPermissionInfo(permission, 0)
                    if (info.protection == PermissionInfo.PROTECTION_DANGEROUS) {
                        Bypass.invoke<Int>(
                            AppOpsManager::class.java, null, "permissionToOpCode",
                            arrayOf(String::class.java), permission,
                        )
                    } else {
                        null
                    }
                } catch (failure: PackageManager.NameNotFoundException) {
                    // A vendor catalog may retain a permission removed from its package table.
                    // Its runtime policy is unconfirmed; scoped write verification still applies.
                    Logger.e("AppOpsCatalog", "Could not classify App Ops permission $permission", failure)
                    null
                }
            },
        ),
    )

    /**
     * Mirror AppOpService's mapping, not SDK level or whether the selected app requested a
     * permission. Android may derive an ignored mode even for apps that never requested it.
     */
    internal fun withRuntimePermissionPolicy(
        operations: List<CatalogOperation>,
        mappingEnabled: Boolean?,
        runtimePermissionOpCode: (String) -> Int?,
    ): List<CatalogOperation> = operations.map { operation ->
        val runtimeMapped = mappingEnabled != false && operation.permission?.let {
            runtimePermissionOpCode(it) == operation.code
        } == true
        operation.copy(
            isRuntimePermissionControlled = mappingEnabled == true && runtimeMapped,
            isRuntimePermissionControlUncertain = mappingEnabled == null && runtimeMapped,
        )
    }

    // Intentional hidden-API probe through Bypass; unavailable APIs use the privileged policy read.
    @SuppressLint("PrivateApi")
    fun reflectedRuntimePermissionMappingEnabled(): Boolean? = try {
        Bypass.invoke(
            Class.forName("android.permission.flags.Flags"), null,
            "runtimePermissionAppopsMappingEnabled",
        )
    } catch (_: ClassNotFoundException) {
        // Some devices expose this class only to system_server, even when the policy is active.
        null
    } catch (failure: Exception) {
        Logger.e("AppOpsCatalog", "Could not determine runtime permission App Ops policy", failure)
        null
    }

    internal fun fromOperations(operations: List<CatalogOperation>): List<AppOpDefinition> {
        require(operations.isNotEmpty()) { "Android returned an empty App Ops catalog" }
        require(operations.map(CatalogOperation::code).distinct().size == operations.size) {
            "Duplicate App Ops operation code"
        }
        // Android 36 retains a removed operation's array slot (96) as OP_NONE with empty names.
        // It has no control to expose. Keep the remaining original codes: compacting these array
        // indices would silently retarget every later command to a different operation.
        val activeOperations = operations.filterNot { operation ->
            operation.code >= 0 && operation.switchCode == -1 && operation.debugName.isBlank() &&
                operation.publicName.isNullOrBlank() && operation.permission == null
        }
        require(activeOperations.isNotEmpty()) { "Android returned no active App Ops operations" }
        val byCode = activeOperations.associateBy(CatalogOperation::code)
        activeOperations.forEach { operation ->
            require(operation.code >= 0 && operation.debugName.isNotBlank()) {
                "Invalid App Ops metadata for operation ${operation.code}"
            }
            val controller = requireNotNull(byCode[operation.switchCode]) {
                "App Ops switch is missing from the device catalog"
            }
            require(controller.switchCode == controller.code) { "Invalid App Ops switch chain" }
        }
        return activeOperations.groupBy(CatalogOperation::switchCode).map { (code, aliases) ->
            val controller = byCode.getValue(code)
            AppOpDefinition(
                code = code,
                debugName = controller.debugName,
                publicName = controller.publicName,
                aliases = aliases.flatMap { listOfNotNull(it.debugName, it.publicName) }
                    .filter { it != controller.debugName && it != controller.publicName }
                    .distinct(),
                relatedPermissions = aliases.mapNotNull(CatalogOperation::permission).distinct(),
                platformDefault = controller.defaultMode,
                allowsReset = controller.allowsReset && controller.defaultMode != AppOpMode.UNKNOWN,
                aliasCodes = aliases.map(CatalogOperation::code).filter { it != code },
                // AppOpsService resolves opToSwitch before attempting either scoped write.
                isRuntimePermissionControlled = controller.isRuntimePermissionControlled,
                isRuntimePermissionControlUncertain = controller.isRuntimePermissionControlUncertain,
            )
        }.sortedBy(AppOpDefinition::code)
    }

    private fun <T> invokeForCode(methodName: String, code: Int): T = Bypass.invoke(
        AppOpsManager::class.java, null, methodName, arrayOf(Int::class.javaPrimitiveType!!), code,
    )
}

internal data class CatalogOperation(
    val code: Int,
    val switchCode: Int,
    val debugName: String,
    val publicName: String?,
    val permission: String?,
    val defaultMode: AppOpMode,
    val allowsReset: Boolean,
    val isRuntimePermissionControlled: Boolean = false,
    val isRuntimePermissionControlUncertain: Boolean = false,
)

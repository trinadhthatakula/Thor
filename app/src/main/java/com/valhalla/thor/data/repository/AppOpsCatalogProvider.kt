// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.AppOpDefinition

/** Resolve the boot-fixed policy through the same privileged session used for App Ops. */
internal class AppOpsCatalogProvider(
    private val reflectedPolicy: () -> Boolean?,
    private val loadDefinitions: (Boolean?) -> List<AppOpDefinition>,
) {
    // Access is serialized by AppOpsController. Unknown policy must not become a cached false.
    private var confirmedCatalog: List<AppOpDefinition>? = null

    suspend fun load(session: AppOpsCommandSession): List<AppOpDefinition> {
        confirmedCatalog?.let { return it }
        val policy = reflectedPolicy() ?: readPolicy(session)
        return loadDefinitions(policy).also { definitions ->
            if (policy != null) confirmedCatalog = definitions
        }
    }

    private suspend fun readPolicy(session: AppOpsCommandSession): Boolean? {
        // aflags reports the active boot value, including fixed read-only flags whose framework
        // Flags class is absent from the app process. Shell identity cannot use it on some ROMs.
        // Filter a static key to keep the privileged command output small; parsing remains exact.
        val (flagsExit, flagsOutput) = session.execute(AFLAGS_COMMAND)
        if (flagsExit == 0) parseAflagsPolicy(flagsOutput.orEmpty())?.let { return it }

        // Older platforms and Shizuku can expose the exact full key through DeviceConfig instead.
        // Transport, busy-lane and cancellation exceptions deliberately propagate to the caller.
        val (configExit, configOutput) = session.execute(DEVICE_CONFIG_COMMAND)
        return if (configExit == 0) configOutput?.trim()?.toBooleanStrictOrNull() else null
    }

    internal companion object {
        const val POLICY_KEY = "android.permission.flags.runtime_permission_appops_mapping_enabled"
        const val AFLAGS_COMMAND = "aflags list --container system | grep -F '$POLICY_KEY'"
        const val DEVICE_CONFIG_COMMAND = "device_config get permissions $POLICY_KEY"

        fun parseAflagsPolicy(output: String): Boolean? {
            val matching = output.lineSequence().map { it.trim().split(Regex("\\s+")) }
                .filter { it.firstOrNull() == POLICY_KEY }.toList()
            val row = matching.singleOrNull() ?: return null
            // Active value, staged value, provenance, permission and container. Do not mistake a
            // staged setting, duplicate or a partially returned record for confirmed boot state.
            if (row.size != 6 || row[2] != "-" || row[3] !in setOf("default", "server", "local") ||
                row[4] != "read-only" || row[5] != "system"
            ) return null
            return when (row[1]) {
                "enabled" -> true
                "disabled" -> false
                else -> null
            }
        }
    }
}

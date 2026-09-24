// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.appops

import com.valhalla.thor.domain.model.AppOpDefinition
import com.valhalla.thor.domain.model.AppOpEntry
import com.valhalla.thor.domain.model.AppOpMode
import java.util.Locale

/** Parses only complete, recognized shell responses; absence must never disguise a read error. */
internal object AppOpsParser {
    private val operationLine = Regex("^([A-Za-z_][A-Za-z0-9_:]*|[0-9]+|MIUIOP\\([0-9]+\\)):\\s*([a-z]+)(.*)$")
    private val miuiOperation = Regex("^miuiop\\(([0-9]+)\\)$")
    private val duration = "[+-]?(?:0|(?:[0-9]+(?:ms|d|h|m|s))+)"
    private val telemetry = Regex(
        "^(?:; (?:time|rejectTime)=$duration ago|; duration=$duration| \\(running\\))*$",
    )

    /**
     * On newer Android releases packageOutput starts with all UID overrides, but only the first
     * line has a `Uid mode:` prefix. uidOutput establishes the boundary before package records.
     * The caller must bracket the package query with equal UID reads to detect concurrent edits.
     */
    fun parseSnapshot(
        packageOutput: String,
        uidOutput: String,
        definitions: List<AppOpDefinition>,
        requestedPermissions: Set<String>,
    ): AppOpsParseResult {
        val packageDump = parseDump(packageOutput)
        val uidDump = parseDump(uidOutput)
        val packageRecords = if (packageDump.hasUidPrefix) {
            require(uidDump.entries.isNotEmpty()) { "App Ops UID modes changed during the read" }
            require(packageDump.entries.take(uidDump.entries.size) == uidDump.entries) {
                "App Ops UID modes changed during the read"
            }
            packageDump.entries.drop(uidDump.entries.size)
        } else {
            // A prefixed UID response identifies the newer format. Without the same prefix the
            // package query was taken before/after a concurrent change, not a reliable snapshot.
            require(!uidDump.hasUidPrefix) { "App Ops UID modes changed during the read" }
            packageDump.entries
        }
        val names = definitionNames(definitions)
        val packages = resolve(packageRecords, names)
        val uids = resolve(uidDump.entries, names)
        val entries = definitions.map { definition ->
            AppOpEntry(
                definition = definition,
                packageMode = packages.modes[definition.code],
                uidMode = uids.modes[definition.code],
                observed = definition.code in packages.observed || definition.code in uids.observed,
                permissionRequested = definition.relatedPermissions.any(requestedPermissions::contains),
            )
        }
        return AppOpsParseResult(
            entries = entries,
            unsupportedOperationCount = (packages.unsupportedCodes + uids.unsupportedCodes).size,
        )
    }

    private fun parseDump(output: String): Dump {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty()) { "Empty App Ops response" }
        if (lines.first() == "No operations.") {
            require(lines.size == 1 || (lines.size == 2 &&
                lines[1].startsWith("Default mode: ") &&
                AppOpMode.fromShellToken(lines[1].removePrefix("Default mode: ")) != AppOpMode.UNKNOWN)) {
                "Unexpected App Ops response after No operations"
            }
            return Dump(emptyList(), hasUidPrefix = false)
        }
        val hasUidPrefix = lines.first().startsWith("Uid mode: ")
        val entries = lines.mapIndexed { index, line ->
            val value = if (index == 0 && hasUidPrefix) line.removePrefix("Uid mode: ") else line
            val match = requireNotNull(operationLine.matchEntire(value)) { "Unrecognized App Ops response" }
            require(telemetry.matches(match.groupValues[3])) { "Unrecognized App Ops history" }
            // Keep raw modes (including Xiaomi's `ask`) until after UID/package separation.
            // Normalizing unsupported modes would conceal concurrent vendor-mode changes.
            Record(name = match.groupValues[1].lowercase(Locale.ROOT), modeToken = match.groupValues[2])
        }
        return Dump(entries, hasUidPrefix)
    }

    private fun definitionNames(definitions: List<AppOpDefinition>): Map<String, Name> = buildMap {
        definitions.forEach { definition ->
            val canonical = listOfNotNull(definition.debugName, definition.publicName, definition.code.toString())
            (canonical + definition.aliases + definition.aliasCodes.map(Int::toString)).forEach { name ->
                val key = name.lowercase(Locale.ROOT)
                val mapped = Name(definition.code, canonical.any { it.equals(name, ignoreCase = true) })
                val previous = put(key, mapped)
                require(previous == null || previous == mapped) { "Ambiguous App Ops catalog name" }
            }
        }
    }

    private fun resolve(records: List<Record>, names: Map<String, Name>): Resolved {
        val seenNames = mutableSetOf<String>()
        val modes = mutableMapOf<Int, AppOpMode>()
        val observed = mutableSetOf<Int>()
        val unsupportedCodes = mutableSetOf<Int>()
        records.forEach { record ->
            require(seenNames.add(record.name)) { "Duplicate App Ops record" }
            val vendorCode = miuiOperation.matchEntire(record.name)?.groupValues?.get(1)?.let { value ->
                requireNotNull(value.toIntOrNull()) { "Invalid Xiaomi App Ops code" }
            }
            if (vendorCode != null) {
                // HyperOS appends its own MIUIOP records, which are outside AppOpsManager's
                // catalog and can use vendor-only modes. Never infer their defaults or edit
                // them, and never discard a record that could refer to a catalogued operation.
                require(vendorCode >= 10_000 && record.name !in names && vendorCode.toString() !in names) {
                    "Xiaomi App Ops code conflicts with the Android catalog"
                }
                require(unsupportedCodes.add(vendorCode)) { "Duplicate Xiaomi App Ops record" }
                return@forEach
            }
            val mode = AppOpMode.fromShellToken(record.modeToken)
            require(mode != AppOpMode.UNKNOWN) { "Unrecognized App Ops mode" }
            val name = requireNotNull(names[record.name]) { "Operation missing from the App Ops catalog" }
            observed += name.code
            // A history entry for GPS/FINE_LOCATION, for example, is evidence of use. Its stored
            // mode does not replace the controlling COARSE_LOCATION operation's configured mode.
            if (name.isController) {
                require(modes.put(name.code, mode) == null) { "Duplicate App Ops controlling mode" }
            }
        }
        return Resolved(modes, observed, unsupportedCodes)
    }

    private data class Name(val code: Int, val isController: Boolean)
    private data class Record(val name: String, val modeToken: String)
    private data class Dump(val entries: List<Record>, val hasUidPrefix: Boolean)
    private data class Resolved(
        val modes: Map<Int, AppOpMode>,
        val observed: Set<Int>,
        val unsupportedCodes: Set<Int>,
    )
}

internal data class AppOpsParseResult(
    val entries: List<AppOpEntry>,
    val unsupportedOperationCount: Int,
)

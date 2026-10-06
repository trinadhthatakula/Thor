// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol

private val PACKAGE_BLOCK = Regex("^Package \\[([A-Za-z0-9._]+)] \\([0-9a-fA-F]+\\):$")
private val USER_BLOCK = Regex("^User (\\d+):(?: (.*))?$")
private val PREFIXED_OWNER = Regex("^<(\\d+)>([A-Za-z0-9._]+)$")
private val FIELD = Regex("^([A-Za-z][A-Za-z0-9]*)=([^\\s=]+)$")
private val INLINE_OWNER = Regex("(?:^| )suspendingPackage=(?:<\\d+>)?([A-Za-z0-9._]+)(?: |$)")
private val RESOURCE_DIALOG = Regex(
    "SuspendDialogInfo: \\{" +
        "(?:mIconId = (?:0x[0-9a-fA-F]+|0) )?" +
        "(?:mTitleResId = (?:0x[0-9a-fA-F]+|0) )?" +
        "(?:mNeutralButtonTextResId = (?:0x[0-9a-fA-F]+|0) )?" +
        "(?:mDialogMessageResId = (?:0x[0-9a-fA-F]+|0) )?" +
        "(?:mNeutralButtonAction = [01])?\\}",
)
private val THOR_DIALOGS = setOf(
    "SuspendDialogInfo: {mDialogMessage = \"This app has been suspended by Thor.\" }",
    "SuspendDialogInfo: {mDialogMessage = \"This app has been suspended by Thor.\" mNeutralButtonAction = 1}",
    "SuspendDialogInfo: {mTitle = \"Thor\"mDialogMessage = \"This app has been suspended by Thor.\" mNeutralButtonAction = 1}",
)

/**
 * Strictly parses the active package and one complete Android-user section from a completed dump.
 * The caller must also establish EOF, successful producer exit, and the byte/time bounds.
 *
 * API 28/29 inline owners, API 30-34 owner blocks and API 35-37 UserPackage keys are supported.
 * Unlike the historical Set parser, absent flags, a missing user or an incomplete block are unknown.
 * A top-level section or following user must close the requested user's record; EOF in that record
 * is conservatively unknown. The hidden system-package copy is never used as live state.
 *
 * SuspendDialogInfo renders app-supplied strings without escaping newlines. Accept only null,
 * resource-only dialogs and Thor's exact fixed strings from explicitly trusted owners, checking
 * every dialog row before processing any structural lines. Trusting those strings for an arbitrary
 * owner would let its custom message forge the same strings followed by another owner/user block.
 * API 28's raw dialogMessage has no framing at all, so even its literal null is accepted only for
 * those trusted owners. Other custom and OEM formats remain unknown in this protocol version.
 */
internal fun parseSuspensionDump(
    output: String,
    packageName: String,
    userId: Int,
    sdkInt: Int,
    trustedStringOwners: Set<String> = emptySet(),
): SuspensionSnapshot {
    if (!Protocol.isValidPackageIdentity(packageName) || userId < 0) {
        return unknownSuspensionSnapshot(Protocol.REASON_INVALID_ARGUMENT)
    }
    if (sdkInt !in 28..37) return unknownSuspensionSnapshot(Protocol.REASON_UNSUPPORTED_FORMAT)
    if (output.length > Protocol.MAX_OUTPUT_BYTES ||
        output.toByteArray(Charsets.UTF_8).size > Protocol.MAX_OUTPUT_BYTES
    ) return unknownSuspensionSnapshot(Protocol.REASON_OUTPUT_LIMIT)
    if ('\u0000' in output) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)

    // Complete this pass before trusting even the first package/user header. An unsafe dialog in
    // another user's section can otherwise inject the requested user's header later in the dump.
    val lines = ArrayList<Pair<Int, String>>()
    for (raw in output.lineSequence()) {
        val trimmed = raw.trim()
        if (isDumpTimeoutLine(trimmed)) return unknownSuspensionSnapshot(Protocol.REASON_TIMEOUT)
        if (trimmed.startsWith("Permission Denial:")) {
            return SuspensionSnapshot(Protocol.STATUS_REFUSED, Protocol.REASON_PLATFORM_REFUSED)
        }
        val safe = withoutTrustedDialog(trimmed, trustedStringOwners)
            ?: return unknownSuspensionSnapshot(Protocol.REASON_AMBIGUOUS_DIALOG)
        lines += (raw.length - raw.trimStart().length) to safe
    }

    var inPackages = false
    var inTarget = false
    var packageIndent = -1
    var targetSeen = false
    var currentUser: Int? = null
    var targetFields: Map<String, String>? = null
    var targetComplete = false
    var inOwnerBlock = false
    var ownerBlockSeen = false
    val owners = linkedSetOf<SuspensionOwnerIdentity>()

    for ((indent, line) in lines) {
        if (line.isEmpty()) continue
        if (indent == 0) {
            if (inTarget && currentUser == userId) targetComplete = true
            inPackages = line == "Packages:"
            inTarget = false
            currentUser = null
            inOwnerBlock = false
            continue
        }
        if (!inPackages) continue

        if (line.startsWith("Package [")) {
            val header = PACKAGE_BLOCK.matchEntire(line)
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            if (inTarget && currentUser == userId) targetComplete = true
            inTarget = header.groupValues[1] == packageName
            currentUser = null
            inOwnerBlock = false
            if (inTarget) {
                if (targetSeen) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
                targetSeen = true
                packageIndent = indent
            }
            continue
        }
        if (!inTarget) continue

        if (line.startsWith("User ")) {
            if (indent != packageIndent + 2) {
                return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            }
            val header = USER_BLOCK.matchEntire(line)
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            val nextUser = header.groupValues[1].toIntOrNull()
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            if (currentUser == userId) targetComplete = true
            currentUser = nextUser
            inOwnerBlock = false
            if (nextUser != userId) continue
            if (targetFields != null) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            val fields = parseFields(header.groupValues[2])
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            val installed = fields["installed"]?.toBooleanStrictOrNull()
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            val suspended = fields["suspended"]?.toBooleanStrictOrNull()
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            if (!installed && suspended) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            targetFields = fields
            fields["suspendingPackage"]?.let { key ->
                if (sdkInt >= 30 || !installed || !suspended) {
                    return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
                }
                val owner = parseOwner(key, userId, sdkInt)
                    ?: return unknownSuspensionSnapshot(Protocol.REASON_INCOMPLETE_OWNERS)
                owners += owner
            }
            continue
        }
        if (currentUser != userId) continue

        if (line == "Suspend params:") {
            if (sdkInt < 30 || indent != packageIndent + 2 || ownerBlockSeen ||
                targetFields?.get("suspended") != "true"
            ) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            ownerBlockSeen = true
            inOwnerBlock = true
            continue
        }
        if (line.startsWith("suspendingPackage=")) {
            if (!inOwnerBlock || indent != packageIndent + 4) {
                return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            }
            val fields = parseFields(line)
                ?: return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            if (fields.keys.any { it != "suspendingPackage" && it != "quarantined" } ||
                (fields.containsKey("quarantined") && fields["quarantined"]?.toBooleanStrictOrNull() == null)
            ) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            val owner = parseOwner(fields["suspendingPackage"].orEmpty(), userId, sdkInt)
                ?: return unknownSuspensionSnapshot(Protocol.REASON_INCOMPLETE_OWNERS)
            if (!owners.add(owner)) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
            if (owners.size > Protocol.MAX_OWNERS) {
                return unknownSuspensionSnapshot(Protocol.REASON_TOO_MANY_OWNERS)
            }
            continue
        }
        inOwnerBlock = false
    }

    if (!targetSeen) return unknownSuspensionSnapshot(Protocol.REASON_MISSING_PACKAGE)
    val fields = targetFields ?: return unknownSuspensionSnapshot(Protocol.REASON_MISSING_USER)
    if (!targetComplete) return unknownSuspensionSnapshot(Protocol.REASON_MALFORMED_OUTPUT)
    if (fields["installed"] == "false") return SuspensionSnapshot(Protocol.STATUS_NOT_INSTALLED)
    if (fields["suspended"] == "false") return SuspensionSnapshot(Protocol.STATUS_NOT_SUSPENDED)
    if (owners.isEmpty() || (sdkInt >= 30 && !ownerBlockSeen)) {
        return unknownSuspensionSnapshot(Protocol.REASON_INCOMPLETE_OWNERS)
    }
    return SuspensionSnapshot(Protocol.STATUS_SUSPENDED, owners = owners.toList())
}

internal fun isDumpTimeoutLine(line: String): Boolean =
    line.contains("DUMP TIMEOUT") || line.contains("SERVICE TIMEOUT") ||
        line.startsWith("Error dumping service info")

private fun parseFields(text: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    for (token in text.splitToSequence(' ', '\t').filter(String::isNotEmpty)) {
        val field = FIELD.matchEntire(token) ?: return null
        if (result.put(field.groupValues[1], field.groupValues[2]) != null) return null
    }
    return result
}

private fun parseOwner(key: String, targetUser: Int, sdkInt: Int): SuspensionOwnerIdentity? {
    if (sdkInt < 35) {
        return key.takeIf { it != "null" && Protocol.isValidPackageIdentity(it) }
            ?.let { SuspensionOwnerIdentity(it, targetUser) }
    }
    val match = PREFIXED_OWNER.matchEntire(key) ?: return null
    val ownerUser = match.groupValues[1].toIntOrNull() ?: return null
    val name = match.groupValues[2]
    return name.takeIf { it != "null" && Protocol.isValidPackageIdentity(it) }
        ?.let { SuspensionOwnerIdentity(it, ownerUser) }
}

private fun withoutTrustedDialog(line: String, trustedStringOwners: Set<String>): String? {
    val infoIndex = line.indexOf(" dialogInfo=")
    val messageIndex = line.indexOf(" dialogMessage=")
    if (infoIndex < 0 && messageIndex < 0) return line
    if (infoIndex >= 0 && messageIndex >= 0) return null
    val index = maxOf(infoIndex, messageIndex)
    val owner = INLINE_OWNER.find(line.substring(0, index))?.groupValues?.get(1)
    if (messageIndex >= 0 && owner !in trustedStringOwners) return null
    val key = if (infoIndex >= 0) " dialogInfo=" else " dialogMessage="
    val value = line.substring(index + key.length)
    val valueLength = when {
        value == "null" || value.startsWith("null ") -> 4
        infoIndex >= 0 && value.startsWith("SuspendDialogInfo: {") -> {
            val end = value.indexOf('}')
            if (end < 0) return null
            val dialog = value.substring(0, end + 1)
            if (!RESOURCE_DIALOG.matches(dialog)) {
                if (dialog !in THOR_DIALOGS || owner !in trustedStringOwners) return null
            }
            end + 1
        }
        else -> return null
    }
    val tail = value.substring(valueLength)
    if (tail.isNotEmpty() && !tail.startsWith(' ')) return null
    return line.substring(0, index) + tail
}

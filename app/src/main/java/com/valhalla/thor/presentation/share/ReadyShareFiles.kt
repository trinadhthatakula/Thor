// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.share

import com.valhalla.thor.data.source.local.room.DataTaskItemSnapshot
import com.valhalla.thor.data.source.local.room.DataTaskOutputSnapshot
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** The same ownership proof is required before sharing or deleting a private ready leaf. */
internal fun readyShareOwnedFile(
    cacheDirectory: File,
    taskId: UUID,
    item: DataTaskItemSnapshot,
    output: DataTaskOutputSnapshot,
): File? {
    if (item.ordinal != output.itemOrdinal) return null
    val identity = "item-$taskId-${item.ordinal}"
    if (item.deterministicStagingIdentity != identity) return null
    if (!item.packageName.isSafeShareComponent() || !output.displayName.isSafeShareComponent()) return null
    val components = listOf("share_ready", identity, item.packageName, output.displayName)
    if (output.privateRelativePath != components.joinToString("/")) return null
    val root = cacheDirectory.canonicalFile
    var file = root
    for (component in components) {
        file = File(file, component)
        if (Files.isSymbolicLink(file.toPath())) return null
    }
    if (file.canonicalFile.toPath() != file.absoluteFile.toPath().normalize() ||
        !file.canonicalFile.toPath().startsWith(root.toPath())) return null
    return file
}

private fun String.isSafeShareComponent(): Boolean =
    isNotBlank() && this != "." && this != ".." &&
        none { it == '/' || it == '\\' || it.isISOControl() }

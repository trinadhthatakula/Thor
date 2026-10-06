// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.util

import java.io.File
import java.util.UUID

/** Read-only kernel identity; editable Android settings are not proof of a new boot. */
internal fun readKernelBootId(): String? = try {
    File("/proc/sys/kernel/random/boot_id").readText().trim().let { value ->
        value.takeIf { UUID.fromString(it).toString() == it }
    }
} catch (_: Exception) { null }

// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import androidx.test.platform.app.InstrumentationRegistry

/**
 * A standalone app_process does not inherit instrumentation's target-app class loader.
 * AGP omits app-provided dependencies (including Kotlin's runtime) from the test APK, so both
 * APKs must be on the helper's classpath. The child writer inherits this same environment.
 */
internal fun settingsEditorProbeClasspath(): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return instrumentation.context.applicationInfo.sourceDir + ":" + instrumentation.targetContext.applicationInfo.sourceDir
}

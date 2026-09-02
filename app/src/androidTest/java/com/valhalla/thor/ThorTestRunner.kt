// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Uses an isolated application runtime for tests that replace Room and WorkManager themselves. */
class ThorTestRunner : AndroidJUnitRunner() {

    internal var isolateApplicationRuntime = false
        private set

    override fun onCreate(arguments: Bundle) {
        isolateApplicationRuntime = arguments.getString(CLASS_ARGUMENT)
            ?.split(',')
            ?.map { selector -> selector.substringBefore('#') }
            ?.contains(PRIVILEGE_SWEEP_WORKER_TEST) == true
        super.onCreate(arguments)
    }

    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(
        classLoader,
        ThorTestApplication::class.java.name,
        context,
    )

    private companion object {
        const val CLASS_ARGUMENT = "class"
        const val PRIVILEGE_SWEEP_WORKER_TEST =
            "com.valhalla.thor.data.freezer.PrivilegeSweepWorkerIntegrationTest"
    }
}

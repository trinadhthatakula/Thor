// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor

import androidx.test.platform.app.InstrumentationRegistry

/** Keeps production startup for ordinary tests and suppresses it for isolated infrastructure tests. */
class ThorTestApplication : ThorApplication() {
    override fun shouldStartApplicationRuntime(): Boolean =
        !(InstrumentationRegistry.getInstrumentation() as ThorTestRunner).isolateApplicationRuntime
}

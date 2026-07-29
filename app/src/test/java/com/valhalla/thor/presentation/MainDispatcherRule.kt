// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Installs a [TestDispatcher] as `Dispatchers.Main` for one test.
 *
 * `viewModelScope` resolves to `Dispatchers.Main.immediate`, which has no implementation on a plain
 * JVM. lifecycle 2.11 falls back to an empty context rather than throwing, so without this rule the
 * view model still runs — on whatever thread happens to pick it up, at which point nothing the test
 * asserts is ordered against it. Setting Main here also hands `runTest` this dispatcher's scheduler
 * (`TestScope` adopts the Main scheduler whenever Main is a `TestDispatcher`), so `viewModelScope`
 * work and the test body share one virtual clock instead of racing on two.
 *
 * Unconfined by default: none of the view models under test schedule anything on a delay, so eager
 * execution is what makes an action's effects observable by the time the call returns. Swap in a
 * `StandardTestDispatcher` for a test that needs to observe an intermediate state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

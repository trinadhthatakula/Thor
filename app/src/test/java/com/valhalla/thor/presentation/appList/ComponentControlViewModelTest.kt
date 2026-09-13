// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.data.source.local.ComponentCapabilityProvider
import com.valhalla.thor.domain.model.ComponentDetail
import com.valhalla.thor.domain.model.ComponentOverride
import com.valhalla.thor.domain.model.ComponentSnapshot
import com.valhalla.thor.domain.model.ComponentType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeState
import com.valhalla.thor.domain.repository.ComponentOverrideRepository
import com.valhalla.thor.domain.usecase.ComponentControlUseCase
import com.valhalla.thor.presentation.FakeAppRepository
import com.valhalla.thor.presentation.FakePrivilegeStateProvider
import com.valhalla.thor.presentation.FakeSystemRepository
import com.valhalla.thor.presentation.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The disclaimer gate on [ComponentControlViewModel].
 *
 * Worth its own suite because the gate guards the one action in Thor that can break an app while
 * leaving every visible sign of health intact — the component stays installed, the app stays enabled
 * and launchable, and some part of it simply stops working. The warning is the only thing standing
 * between a mis-tap and that outcome, so *when it is skipped* is a behaviour, not a detail.
 *
 * The suite is possible at all because the gate stopped being a persisted preference. When the
 * answer lived in DataStore the only way to drive it was a real preference flow, and the unread
 * window in between — which defaulted to *accepted*, and so skipped the disclaimer outright — was
 * exactly the state a test could not pin down. A plain in-memory flag has no such window: it starts
 * at "ask", and every move away from that is an explicit user action, which is what is asserted
 * here.
 *
 * Both halves are checked each time, because each alone can pass while the gate is broken:
 * `pendingConsent` says the disclaimer was raised, and the calls reaching [FakeSystemRepository] say
 * whether the disable itself went through. A test that only looked for a null `pendingConsent`
 * would be equally happy with a `requestDisable` that quietly did nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComponentControlViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `the disclaimer is raised for a disable in a fresh session`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))

        assertNotNull(
            "a fresh session must ask before disabling anything",
            fixture.viewModel.uiState.value.pendingConsent,
        )
        assertEquals("the component was disabled before the user answered", emptyList<String>(), fixture.disables)
    }

    @Test
    fun `confirming runs the disable that was parked`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))
        fixture.viewModel.onDisclaimerConfirmed(dontAskAgain = false)

        assertNull(fixture.viewModel.uiState.value.pendingConsent)
        assertEquals(listOf("setComponentEnabled:$PKG:Sync:DISABLED"), fixture.disables)
    }

    /**
     * The default, and the reason the checkbox exists rather than the confirm button implying it.
     *
     * Confirming *is* consent for the component in front of the user; it is not consent for every
     * component after it. Treating one tap as a standing answer is what the persisted flag did, and
     * it did it permanently.
     */
    @Test
    fun `confirming without ticking the box asks again next time`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))
        fixture.viewModel.onDisclaimerConfirmed(dontAskAgain = false)
        fixture.viewModel.requestDisable(ComponentType.RECEIVER, component("Boot"))

        assertNotNull(
            "an unticked confirm silenced the disclaimer anyway",
            fixture.viewModel.uiState.value.pendingConsent,
        )
        assertEquals(
            "the second component went off without being asked about",
            listOf("setComponentEnabled:$PKG:Sync:DISABLED"),
            fixture.disables,
        )
    }

    @Test
    fun `ticking the box silences the disclaimer for the rest of the session`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))
        fixture.viewModel.onDisclaimerConfirmed(dontAskAgain = true)
        fixture.viewModel.requestDisable(ComponentType.RECEIVER, component("Boot"))

        assertNull(
            "the box was ticked, so the second disable must not stop to ask",
            fixture.viewModel.uiState.value.pendingConsent,
        )
        assertEquals(
            listOf("setComponentEnabled:$PKG:Sync:DISABLED", "setComponentEnabled:$PKG:Boot:DISABLED"),
            fixture.disables,
        )
    }

    /**
     * The silence is *session*-scoped, and the session outlives the ViewModel on purpose.
     *
     * [ComponentControlViewModel] is scoped per package by its Koin key, so a flag held on the
     * ViewModel would re-ask for every app the user opened — the annoyance the checkbox is there to
     * prevent. Sharing the one session instance is what makes a single tick cover a whole sitting.
     */
    @Test
    fun `the silence spans the packages of one session`() = runTest {
        val session = ComponentConsentSession()

        Fixture(session).viewModel.also {
            it.requestDisable(ComponentType.SERVICE, component("Sync"))
            it.onDisclaimerConfirmed(dontAskAgain = true)
        }
        // A second view model over a second package, as opening another app's sheet builds.
        val next = Fixture(session, packageName = OTHER_PKG)
        next.viewModel.requestDisable(ComponentType.RECEIVER, component("Boot"))

        assertNull(
            "the tick did not carry to the next package",
            next.viewModel.uiState.value.pendingConsent,
        )
        assertEquals(listOf("setComponentEnabled:$OTHER_PKG:Boot:DISABLED"), next.disables)
    }

    /**
     * And it does not outlive the process, which is the whole difference from the persisted flag.
     *
     * A fresh [ComponentConsentSession] is what a relaunch produces, since nothing reads the answer
     * back from disk.
     */
    @Test
    fun `the silence does not survive a new session`() = runTest {
        Fixture().viewModel.also {
            it.requestDisable(ComponentType.SERVICE, component("Sync"))
            it.onDisclaimerConfirmed(dontAskAgain = true)
        }

        val relaunched = Fixture()
        relaunched.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))

        assertNotNull(
            "a silenced session leaked across a restart",
            relaunched.viewModel.uiState.value.pendingConsent,
        )
        assertEquals(emptyList<String>(), relaunched.disables)
    }

    /** Dismissing drops the parked request; it must not fall through to the disable. */
    @Test
    fun `dismissing the disclaimer clears the parked request without disabling`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))
        fixture.viewModel.onDisclaimerDismissed()

        assertNull(fixture.viewModel.uiState.value.pendingConsent)
        assertEquals("a dismissal disabled the component anyway", emptyList<String>(), fixture.disables)

        fixture.viewModel.requestDisable(ComponentType.SERVICE, component("Sync"))
        assertNotNull(
            "a dismissal must not count as an answer",
            fixture.viewModel.uiState.value.pendingConsent,
        )
    }

    /** Confirming with nothing parked is a no-op, not a disable of whatever was last seen. */
    @Test
    fun `confirming with no parked request does nothing`() = runTest {
        val fixture = Fixture()

        fixture.viewModel.onDisclaimerConfirmed(dontAskAgain = true)

        assertNull(fixture.viewModel.uiState.value.pendingConsent)
        assertEquals(emptyList<String>(), fixture.disables)
    }

    /**
     * A bound view model over a stubbed package.
     *
     * `load()` is called because [ComponentControlViewModel.performDisable] needs a package name to
     * act on, and without one every disable would return early — leaving the "went through" half of
     * each assertion unable to fail. The privilege state is a ready **root** one specifically: a
     * ready *Shizuku* state would send [ComponentCapabilityProvider] to read `Shizuku.getUid()`,
     * whose static initialiser builds a Binder and cannot load in a JVM test.
     */
    private class Fixture(
        session: ComponentConsentSession = ComponentConsentSession(),
        val packageName: String = PKG,
    ) {
        val system = FakeSystemRepository()

        val viewModel = ComponentControlViewModel(
            appRepository = FakeAppRepository().apply {
                componentSnapshots[packageName] = SNAPSHOT
            },
            componentControl = ComponentControlUseCase(system, FakeLedger()),
            capabilityProvider = ComponentCapabilityProvider(
                FakePrivilegeStateProvider(
                    PrivilegeState(root = true, active = PrivilegeMode.ROOT, isReady = true)
                )
            ),
            consentSession = session,
            ioDispatcher = UnconfinedTestDispatcher(),
        ).apply { load(packageName, SNAPSHOT) }

        /** The component writes that actually reached the privilege layer, in order. */
        val disables: List<String>
            get() = system.calls.filter { it.startsWith("setComponentEnabled:") }
    }

    /** Enough of a ledger to run the use case; the rows themselves are asserted in its own suite. */
    private class FakeLedger : ComponentOverrideRepository {

        private val rows = mutableListOf<ComponentOverride>()
        private val revision = MutableStateFlow(0)

        override fun observe(packageName: String): Flow<List<ComponentOverride>> =
            revision.map { rows.filter { row -> row.packageName == packageName } }

        override suspend fun getAll(): List<ComponentOverride> = rows.toList()

        override suspend fun record(
            packageName: String,
            className: String,
            type: ComponentType,
            restoreToEnabled: Boolean,
        ) {
            rows += ComponentOverride(packageName, className, type, restoreToEnabled, disabledAt = 0L)
            revision.value++
        }

        override suspend fun forget(packageName: String, className: String) {
            rows.removeAll { it.packageName == packageName && it.className == className }
            revision.value++
        }

        override suspend fun forgetPackage(packageName: String) {
            rows.removeAll { it.packageName == packageName }
            revision.value++
        }
    }

    private companion object {
        const val PKG = "com.example.app"
        const val OTHER_PKG = "com.example.other"

        val SNAPSHOT = ComponentSnapshot(
            services = listOf(component("Sync")),
            receivers = listOf(component("Boot")),
        )

        fun component(className: String) = ComponentDetail(
            className = className,
            exported = true,
            enabled = true,
            manifestDefaultEnabled = true,
        )
    }
}

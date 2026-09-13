// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Which of the four manifest component kinds a [ComponentDetail] describes.
 *
 * Carried on the ledger row as well as on the live component, because a row has to keep rendering
 * after the component it names has vanished from an app update — at which point the only thing left
 * that can say which section it belongs under is the recorded type.
 */
@Serializable
enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/**
 * One declared component, with the three facts that decide what Thor may do to it.
 *
 * Replaces the bare class-name string the Components tab used to render. The name alone cannot
 * answer either question the tab now asks — "can this be opened?" and "is this switched off?" — and
 * both answers are already sitting in the `PackageInfo` the detail loader fetches; they were being
 * mapped away one line after they arrived.
 *
 * @param enabled the **effective** state right now: the runtime override if one has been set, and
 * the manifest default otherwise. Not readable from `ComponentInfo.enabled`, which only ever reports
 * the manifest attribute — see `AppRepositoryImpl.componentDetailsOf` for how it is derived.
 * @param manifestDefaultEnabled `android:enabled` as the developer shipped it. Kept beside [enabled]
 * so the UI can tell "somebody turned this off" apart from "this ships off", and so a restore puts
 * the component back the way the developer shipped it rather than merely switching it on.
 * @param hasExplicitState whether an explicit override exists at all, i.e. whether
 * `getComponentEnabledSetting` answered something other than `COMPONENT_ENABLED_STATE_DEFAULT`.
 * Not the same as `enabled != manifestDefaultEnabled`: a component the manifest already enables can
 * carry an explicit `ENABLED` override, which looks identical from the outside but is a real row in
 * `package-restrictions.xml` that "Reset to default" should be offered for and that survives an app
 * update the way the developer's own default does not.
 * @param permission `android:permission` — the permission a caller must hold to enter this
 * component, or `null` when entry is ungated.
 */
@Serializable
@Immutable
data class ComponentDetail(
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,
    val manifestDefaultEnabled: Boolean,
    val hasExplicitState: Boolean = false,
    val permission: String? = null,
) {
    /**
     * Whether launching this activity needs uid 0.
     *
     * **Not simply `!exported`.** An *exported* activity guarded by an `android:permission` Thor
     * does not hold fails in exactly the same way as an unexported one:
     * `ActivityStarter.executeRequest` runs the export check and the permission check side by side,
     * and `ActivityManager.canAccessUnexportedComponents` — the only waiver — is granted to
     * `ROOT_UID` and `SYSTEM_UID` alone.
     *
     * Thor could in principle hold the guarding permission itself, which would make some of these
     * launchable unprivileged. It is not checked: `checkSelfPermission` would have to run per row on
     * a list that can be several hundred long, and being wrong in this direction costs only an
     * unnecessary Force Open label on a row that will then succeed. Being wrong in the other
     * direction costs a plain Open button that throws.
     */
    val launchRequiresRoot: Boolean get() = !exported || permission != null

    /**
     * Whether an explicit override is in force — i.e. whether *anything* (Thor, another tool, the
     * app itself, the OEM) has written a state for this component.
     *
     * This is what "Reset to default" acts on, and it is deliberately blind to who wrote the
     * override: `getComponentEnabledSetting` records a state, not an author. Thor's own ledger
     * (`component_overrides`) is the only record of authorship, and it only knows about Thor.
     */
    val isOverridden: Boolean get() = hasExplicitState

    /**
     * The class name without its package prefix, for a Toast.
     *
     * `com.foo.bar.baz.ui.settings.SettingsActivity` in a two-line Toast is a package path with the
     * interesting word off the end; `SettingsActivity` is the word. Substring rather than
     * `substringAfterLast('.')` on the *package* — a component's class is not always under the app's
     * own package (a library's `androidx.work.impl.SystemJobService` is declared by the app that
     * bundles it), so the last dot is the only reliable split.
     *
     * Never empty: a class name that ends in a dot, or has none, falls back to the whole string.
     */
    val shortName: String
        get() = className.substringAfterLast('.').ifEmpty { className }
}

/**
 * The four component lists of one package, as [ComponentDetail]s.
 *
 * A type rather than four parameters because every consumer wants all four together and because
 * [of] lets the tab loop over [ComponentType.entries] instead of repeating itself four times — which
 * is what kept the old four-`List<String>` shape from ever gaining a per-row affordance without a
 * fourfold edit.
 */
@Serializable
@Immutable
data class ComponentSnapshot(
    val activities: List<ComponentDetail> = emptyList(),
    val services: List<ComponentDetail> = emptyList(),
    val receivers: List<ComponentDetail> = emptyList(),
    val providers: List<ComponentDetail> = emptyList(),
) {
    fun of(type: ComponentType): List<ComponentDetail> = when (type) {
        ComponentType.ACTIVITY -> activities
        ComponentType.SERVICE -> services
        ComponentType.RECEIVER -> receivers
        ComponentType.PROVIDER -> providers
    }

    /** Every component of every type, tagged with its section — for counting and reconciliation. */
    fun all(): List<Pair<ComponentType, ComponentDetail>> =
        ComponentType.entries.flatMap { type -> of(type).map { type to it } }

    val isEmpty: Boolean
        get() = activities.isEmpty() && services.isEmpty() &&
                receivers.isEmpty() && providers.isEmpty()
}

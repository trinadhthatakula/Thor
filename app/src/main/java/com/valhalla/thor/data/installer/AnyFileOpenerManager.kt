// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.installer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.valhalla.thor.domain.repository.AnyFileOpenerController
import com.valhalla.thor.presentation.installer.PortableInstallerActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * Flips the `AnyFileInstallerAlias` component, which carries the typeless content://+file:// VIEW
 * filter and ships disabled. See the manifest comment above the alias for why it can only be all or
 * nothing.
 */
@Single(binds = [AnyFileOpenerController::class])
class AnyFileOpenerManager(
    private val context: Context,
    private val packageManager: PackageManager,
    @Named("io") private val ioDispatcher: CoroutineDispatcher,
) : AnyFileOpenerController {

    /**
     * The alias component: applicationId as the package, namespace-relative FQCN as the class.
     *
     * Those two are *not* the same string. The `debug` build type sets
     * `applicationIdSuffix = ".debug"`, so `context.packageName` is `com.valhalla.thor.debug` while
     * the merged manifest still names the component `com.valhalla.thor.presentation.installer.…` —
     * a relative `android:name` resolves against the namespace, which no suffix touches. Building
     * the class name by appending to `packageName` therefore yields a component that exists in no
     * build, and `setComponentEnabledSetting` throws `IllegalArgumentException` on it.
     *
     * So the class name is anchored to [PortableInstallerActivity] instead, which the manifest
     * declares the alias next to and in the same package: moving that class moves this with it, and
     * the compiler catches a rename that a string literal would not.
     */
    private val alias: ComponentName
        get() = ComponentName(context.packageName, ALIAS_CLASS_NAME)

    override suspend fun isEnabled(): Boolean = withContext(ioDispatcher) {
        // COMPONENT_ENABLED_STATE_DEFAULT means "whatever the manifest said", and the manifest says
        // android:enabled="false" — so only an explicit ENABLED counts as on. Comparing against
        // DISABLED instead would read the untouched default as enabled.
        packageManager.getComponentEnabledSetting(alias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(ioDispatcher) {
        packageManager.setComponentEnabledSetting(
            alias,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            // DONT_KILL_APP is not optional here. Without it the platform kills the process that
            // owns the component it just changed — which is Thor's own, from the Settings screen the
            // user is standing in. The flag costs nothing: the alias has no running instance to
            // leave in a stale state, since it is a launch target and not a live component.
            PackageManager.DONT_KILL_APP
        )
    }

    companion object {
        /** Simple name of the alias — mirrors `android:name` in AndroidManifest.xml. */
        const val ALIAS_SIMPLE_NAME = "AnyFileInstallerAlias"

        /**
         * Fully-qualified alias class name, in [PortableInstallerActivity]'s package because that is
         * where the manifest declares it.
         *
         * Internal rather than private so `AnyFileOpenerAliasNameTest` can assert it against the
         * manifest itself — the derivation and the XML are two hand-maintained halves of one name,
         * and nothing else would notice them drifting apart until a user flipped the switch.
         */
        val ALIAS_CLASS_NAME: String =
            PortableInstallerActivity::class.java.name.substringBeforeLast('.') +
                ".$ALIAS_SIMPLE_NAME"
    }
}

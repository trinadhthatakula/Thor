// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.privileged

import android.annotation.SuppressLint
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import com.valhalla.bypass.Bypass
import com.valhalla.thor.domain.repository.InstallMode
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The uid an ADB-started Shizuku runs as. Not a magic number: `com.android.shell` owns it, and it is
 * the identity the `getMySessions` owner check resolves [SHELL_INSTALLER_PACKAGE_NAME] against.
 */
internal const val SHELL_UID = 2000

/**
 * The package that owns [SHELL_UID].
 *
 * Deliberately its own constant rather than a reuse of
 * [com.valhalla.thor.domain.model.SHELL_SUSPENDER_IDENTITY], which happens to hold the same string
 * for a different question — who `pm suspend` acts *as*, parsed back out of a `dumpsys` dump. Same
 * package, two independent facts; the shared string is a coincidence of Android's naming, not a
 * coupling worth creating between the domain layer's readback parser and the installer.
 */
internal const val SHELL_INSTALLER_PACKAGE_NAME = "com.android.shell"

/**
 * Which binder wrapper a privileged `PackageInstaller` — and every session opened on it — has to
 * transact through.
 *
 * A deliberately dumb enum with no member bodies, for two reasons. It has to be loadable from a
 * plain JVM test (`rikka.shizuku.Shizuku`'s static initialiser builds a Binder and throws "not
 * mocked", and an enum constant with a body is a subclass whose verification can drag its
 * dependencies in), and the one line that differs between the two modes belongs in
 * [PrivilegedPackageInstallers.wrap] where it can be read next to everything that uses it.
 */
internal enum class PrivilegedInstallerTransport { SHIZUKU, DHIZUKU }

/**
 * The transport an [InstallMode]'s privileged session rung belongs to, or `null` for the modes that
 * have no privileged `PackageInstaller` at all.
 *
 * **This function is the fix for a live bug, not plumbing.** `getDhizukuPackageInstaller()` used to
 * call `ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()`, so the Dhizuku session rung
 * ran on `ShizukuBinderWrapper` — which on a Dhizuku-only device is a wrapper around a service that
 * is not installed. Its comment even claimed to be "using Dhizuku's binder wrapper". Choosing the
 * transport from the mode, by name, in a `when` with no `else`, is what stops that from being
 * expressible: a mode added later fails to compile here instead of silently inheriting Shizuku's.
 *
 * `ROOT` installs through the Odin shell, `NORMAL` uses the in-process unprivileged installer whose
 * sessions already belong to Thor's own uid, and `EXTERNAL` hands the whole job to another app.
 * None of the three has a binder to wrap, and `null` is the honest answer rather than a default.
 */
internal fun transportFor(mode: InstallMode): PrivilegedInstallerTransport? = when (mode) {
    InstallMode.SHIZUKU -> PrivilegedInstallerTransport.SHIZUKU
    InstallMode.DHIZUKU -> PrivilegedInstallerTransport.DHIZUKU
    InstallMode.ROOT, InstallMode.NORMAL, InstallMode.EXTERNAL -> null
}

/**
 * The name a session created on [transport] records as its installer package.
 *
 * Pure, and lifted out of the two factory functions it used to be written into so the two privilege
 * modes cannot answer it differently by accident — which is how the Dhizuku factory ended up on the
 * Shizuku transport in the first place.
 *
 * - **Shizuku at [SHELL_UID]** — the ordinary ADB setup — names [SHELL_INSTALLER_PACKAGE_NAME],
 *   because `PackageInstallerService.getMySessions` filters on the installer package's *owner* and
 *   the session was created by uid 2000. An unreadable uid arrives here as `-1` and takes the same
 *   branch as a root Shizuku: Thor's own name. Both are preserved verbatim from
 *   `getShizukuPackageInstaller()`.
 * - **Dhizuku** names the *device owner's* package ([dhizukuOwnerPackageName]) and never shell, and
 *   never Thor. This one is a behaviour change, and a necessary one — see below.
 *
 * On any transport, the name has to belong to the uid that calls `createSession`.
 * `PackageInstallerService.createSessionInternal` takes the `isRootOrShell(callingUid)` branch for
 * Shizuku (uid 2000 or 0, both exempt), but a Dhizuku transact is re-issued from the Dhizuku app
 * process, so system_server sees an ordinary app uid and the `else` branch runs
 * `mAppOps.checkPackage(callingUid, installerPackageName)` — which throws
 * `SecurityException("Package com.valhalla.thor does not belong to <dhizuku uid>")`. Naming Thor on
 * this transport does not merely mislabel the session, it refuses to create one. Thor's own package
 * was what the old `getDhizukuPackageInstaller()` passed, but it never mattered: that factory built
 * its installer on `ShizukuBinderWrapper`, so on a Dhizuku-only device the call died before reaching
 * system_server at all. Fixing the transport is what makes this name load-bearing for the first time.
 *
 * The owner package is also what earns the *silent* install:
 * `PackageInstallerSession.isInstallerDeviceOwnerOrAffiliatedProfileOwner()` calls
 * `canSilentlyInstallPackage(mInstallSource.mInstallerPackageName, mInstallerUid)`, passing the
 * name as well as the uid, so a session that does not name the device owner still ends at the
 * confirmation dialog even though the uid is right.
 *
 * [dhizukuOwnerPackageName] is nullable because `Dhizuku.getOwnerPackageName()` throws until the
 * owner component has been received. `null` falls back to [thorPackageName]: no better name exists
 * at that point, and it leaves the rung exactly where it already was — refused, then handed on to
 * the next rung — rather than passing a `null` the platform would fail on differently.
 *
 * Note the name is **not** what decides session *ownership*. `PackageInstallerSession.mInstallerUid`
 * is the calling uid of `createSession` and nothing in `SessionParams` can move it, which is why
 * wrapping the session binder ([PrivilegedPackageInstallers.openSession]) is a separate fix that no
 * choice of installer name could have substituted for.
 */
internal fun sessionInstallerPackageName(
    transport: PrivilegedInstallerTransport,
    shizukuUid: Int,
    thorPackageName: String,
    dhizukuOwnerPackageName: String?,
): String = when (transport) {
    PrivilegedInstallerTransport.SHIZUKU ->
        if (shizukuUid == SHELL_UID) SHELL_INSTALLER_PACKAGE_NAME else thorPackageName

    // Shizuku's uid is irrelevant on this transport and has to stay irrelevant — reading it here is
    // what would couple the two modes again.
    PrivilegedInstallerTransport.DHIZUKU -> dhizukuOwnerPackageName ?: thorPackageName
}

/**
 * A `PackageInstaller` together with the only correct way to open sessions on it.
 *
 * The pair exists because `PackageInstaller.openSession(int)` is
 * `new Session(mInstaller.openSession(sessionId))` — it hands the returned
 * `IPackageInstallerSession` to `Session` **unwrapped**. On a privileged installer that is the whole
 * bug: `createSession` runs through the wrapper and records `mInstallerUid` as the *transport's* uid,
 * then every `openWrite`/`fsync`/`commit` on the raw session transacts from Thor's own app uid and
 * meets `assertCallerIsOwnerOrRoot()` → `SecurityException("Session does not belong to uid N")`.
 *
 * There is no way to fix that on the `PackageInstaller` object, so the installer never travels
 * without its opener. [unprivileged] is the one case that genuinely wants the platform's own
 * `openSession`, and it has to be asked for by name.
 */
internal class InstallerHandle(
    // Private, so "the installer never travels without its opener" is enforced by the compiler
    // rather than only asserted above: a caller that could reach this could call the platform's
    // unwrapped openSession on it and rebuild the bug.
    private val installer: PackageInstaller,
    private val sessionOpener: (Int) -> PackageInstaller.Session,
) {
    fun createSession(params: PackageInstaller.SessionParams): Int = installer.createSession(params)

    fun openSession(sessionId: Int): PackageInstaller.Session = sessionOpener(sessionId)

    fun abandonSession(sessionId: Int) = installer.abandonSession(sessionId)

    companion object {
        /**
         * The in-process installer from `context.packageManager.packageInstaller`.
         *
         * Its sessions are created by Thor's own uid, so `mInstallerUid` already matches the process
         * that writes to them and the platform's `openSession` is correct. This is `InstallMode.NORMAL`
         * — the rung that ends in the system confirmation dialog.
         */
        fun unprivileged(installer: PackageInstaller): InstallerHandle =
            InstallerHandle(installer) { installer.openSession(it) }
    }
}

/**
 * Thor's privileged `PackageInstaller` plumbing, parameterised by [PrivilegedInstallerTransport].
 *
 * Derived from
 * [FDroid Priv](https://github.com/depau/fdroid_shizuku_privileged_extension/blob/main/app/src/main/java/org/fdroid/fdroid/privileged/ShizukuPackageInstallerUtils.kt)
 * and Rikka's
 * [PackageInstallerUtils](https://github.com/RikkaApps/Shizuku-API/blob/01e08879d58a5cb11a333535c6ddce9f7b7c88ff/demo/src/main/java/rikka/shizuku/demo/util/PackageInstallerUtils.java)
 * — including the [openSession] half of `DemoActivity.java:247` that Thor had not copied, which is
 * the defect this file exists to close.
 *
 * This replaces `ShizukuPackageInstallerUtils`, which was Shizuku-only in its name and in its one
 * wrapper call while both privilege modes used it. Two copies of the same reflection is how they
 * drifted; one copy that has to be told which transport it is on is how they stop.
 *
 * Every public function names a transport. There is deliberately no overload that does not, and a
 * unit test asserts it: the regression being prevented is a Dhizuku call site quietly reusing a
 * Shizuku helper.
 *
 * Hidden framework types are resolved by `Class.forName` string and held as `android.os.IInterface`,
 * never as a bundled compile-time shadow. R8 renames a shadow and rewrites `::class` references to
 * it, but at runtime the real bootclasspath class wins parent-first, so a shadow reference reflects
 * against a class that exists in no process. `Bypass.prepareThor()` exempts `Landroid/content/pm`,
 * which is what makes the hidden members below reachable at all.
 */
@SuppressLint("PrivateApi")
internal object PrivilegedPackageInstallers {

    /**
     * Puts [transport]'s wrapper around [binder] so the transact leaves as the privileged identity
     * instead of as Thor.
     *
     * Single-wrapped, on purpose. `DhizukuHelper.asInterface` puts `ShizukuBinderWrapper` on top of
     * Dhizuku's own wrapper, and that file documents at length why the resulting rungs are dead on a
     * Dhizuku-only device. Nothing here repeats it.
     */
    fun wrap(transport: PrivilegedInstallerTransport, binder: IBinder): IBinder = when (transport) {
        PrivilegedInstallerTransport.SHIZUKU -> ShizukuBinderWrapper(binder)
        PrivilegedInstallerTransport.DHIZUKU -> DhizukuAPI.binderWrapper(binder)
    }

    /** `<className>$Stub.asInterface(wrap(transport, binder))`, reflectively. */
    private fun asInterface(
        className: String,
        binder: IBinder,
        transport: PrivilegedInstallerTransport,
    ): IInterface {
        val stub = Class.forName("$className\$Stub")
        return Bypass.invoke(
            stub,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            wrap(transport, binder)
        )
    }

    /**
     * The system `IPackageInstaller`, reached through [transport] — returned as an opaque
     * [IInterface] because nothing calls the hidden interface directly; the handle is only ever
     * passed back into reflective calls in this file.
     */
    fun privilegedPackageInstaller(transport: PrivilegedInstallerTransport): IInterface {
        val packageManagerBinder = SystemServiceHelper.getSystemService("package")
        val packageManager =
            asInterface("android.content.pm.IPackageManager", packageManagerBinder, transport)

        // `packageManager.javaClass` rather than the interface, carried over verbatim from the
        // ShizukuPackageInstallerUtils this file replaces. That line is proven in production — the
        // comment there recorded a NoSuchMethodError on some ROMs when this was reached differently —
        // so it is preserved rather than tidied. Bypass.getDeclaredMethod walks superclasses.
        val installerProxy = Bypass.invoke<Any>(
            packageManager.javaClass,
            packageManager,
            "getPackageInstaller"
        )

        val installerBinder = (installerProxy as IInterface).asBinder()
        return asInterface("android.content.pm.IPackageInstaller", installerBinder, transport)
    }

    /**
     * The public `PackageInstaller` facade around an already-wrapped [installer].
     *
     * **A bare facade is only safe for the sessionless operations** — `uninstall`,
     * `installExistingPackage` — because those transact through [installer] itself. Anything that
     * opens a session must go through [handleFor]; see [InstallerHandle] for what
     * `PackageInstaller.openSession` does with the binder it gets back.
     */
    fun packageInstaller(
        transport: PrivilegedInstallerTransport,
        installerPackageName: String?,
        userId: Int,
    ): PackageInstaller {
        val installer = privilegedPackageInstaller(transport)
        return packageInstallerFacade(installer, installerPackageName, userId)
    }

    /**
     * A privileged installer **and** the wrapped session opener that belongs with it — the only way
     * a session install should obtain either.
     */
    fun handleFor(
        transport: PrivilegedInstallerTransport,
        installerPackageName: String?,
        userId: Int,
    ): InstallerHandle {
        val installer = privilegedPackageInstaller(transport)
        val facade = packageInstallerFacade(installer, installerPackageName, userId)
        return InstallerHandle(facade) { sessionId -> openSession(installer, sessionId, transport) }
    }

    /**
     * Opens session [sessionId] on [installer] and wraps the returned session binder in
     * [transport]'s wrapper before building `PackageInstaller.Session` around it.
     *
     * This is the half of the reference implementation Thor was missing. The platform's own
     * `PackageInstaller.openSession` is `new Session(mInstaller.openSession(sessionId))`, and the
     * `IPackageInstallerSession` it passes along is raw — so the outer `openSession` transact goes
     * through the privileged wrapper (and is allowed: `assertCallerIsOwnerOrRootOrSystem` sees the
     * transport's uid, which created the session) while every subsequent call on the returned
     * `Session` leaves as Thor's app uid and is refused with
     * `SecurityException("Session does not belong to uid N")`.
     *
     * `mInstallerUid` is fixed at `createSession` time from `Binder.getCallingUid()` and no
     * `SessionParams` field moves it, so wrapping the session is the only place this can be fixed.
     */
    fun openSession(
        installer: IInterface,
        sessionId: Int,
        transport: PrivilegedInstallerTransport,
    ): PackageInstaller.Session {
        // Reflect against the *interface*, not installer.javaClass. What asInterface hands back is
        // `IPackageInstaller$Stub$Proxy`, a private nested class; looking the method up on the public
        // interface that declares it sidesteps the accessibility question entirely and dispatches
        // virtually to the proxy anyway. Resolved by name for the reason ShizukuReflector's
        // installExistingPackage gives: R8 renames a bundled shadow `::class.java`, while at runtime
        // the genuine bootclasspath class wins parent-first, so the two would not be the same class.
        val rawSession = Bypass.invoke<Any>(
            Class.forName("android.content.pm.IPackageInstaller"),
            installer,
            "openSession",
            arrayOf(Int::class.javaPrimitiveType!!),
            sessionId
        )
        val sessionBinder = (rawSession as IInterface).asBinder()
        val wrappedSession =
            asInterface("android.content.pm.IPackageInstallerSession", sessionBinder, transport)

        return Bypass.newInstance(
            PackageInstaller.Session::class.java,
            arrayOf(Class.forName("android.content.pm.IPackageInstallerSession")),
            wrappedSession
        )
    }

    /**
     * `new PackageInstaller(iPackageInstaller, installerPackageName, [attributionTag,] userId)`.
     *
     * Transport-free on purpose — [installer] is already wrapped by the time it gets here, and this
     * only picks the constructor the platform actually has. API 31 inserted `attributionTag` between
     * the installer package name and the user id.
     */
    private fun packageInstallerFacade(
        installer: IInterface,
        installerPackageName: String?,
        userId: Int,
    ): PackageInstaller {
        val iPackageInstallerClass = Class.forName("android.content.pm.IPackageInstaller")
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Bypass.newInstance(
                PackageInstaller::class.java,
                arrayOf(
                    iPackageInstallerClass,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                ),
                installer, installerPackageName, null, userId
            )
        } else {
            Bypass.newInstance(
                PackageInstaller::class.java,
                arrayOf(iPackageInstallerClass, String::class.java, Int::class.javaPrimitiveType!!),
                installer, installerPackageName, userId
            )
        }
    }
}

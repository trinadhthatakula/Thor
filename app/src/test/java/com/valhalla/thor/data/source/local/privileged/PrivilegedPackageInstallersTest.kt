// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.privileged

import com.valhalla.thor.domain.repository.InstallMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The second half of the silent-install bug: **who does the transact, once a session exists.**
 *
 * `InstallerRepositoryImpl` reached `PackageInstaller.openSession(id)` on a privileged installer it
 * had built reflectively. That call is
 * `new Session(mInstaller.openSession(sessionId))` — the returned `IPackageInstallerSession` is
 * handed to `Session` **raw**. Everything after it (`openWrite`, `fsync`, `commit`) therefore
 * transacts straight from Thor's own app uid, while the session's `mInstallerUid` is the uid that
 * *created* it (2000 under an ordinary Shizuku). `PackageInstallerSession.assertCallerIsOwnerOrRoot`
 * answers that with `SecurityException("Session does not belong to uid N")`, the rung reports
 * failure, and `installPackage` walks on to the unprivileged installer — which is the system
 * confirmation dialog the reporter saw.
 *
 * Rikka's reference does the missing half: `DemoActivity.java:247` wraps the *session* binder before
 * building `Session`, and `PackageInstallerUtils.java:28-31` is the single-argument
 * `PackageInstaller.Session(IPackageInstallerSession)` helper. Thor copied
 * `createPackageInstaller` and stopped there.
 *
 * The third defect is the transport itself. `getDhizukuPackageInstaller()` called
 * `ShizukuPackageInstallerUtils.getPrivilegedPackageInstaller()`, so **the Dhizuku rung ran on the
 * Shizuku binder wrapper** — dead on a Dhizuku-only device, where there is no Shizuku service to
 * transact through. This file's [transportFor] tests are what make that unrepeatable: the transport
 * is chosen from the [InstallMode], by name, in a `when` with no `else`.
 *
 * Fixing that transport exposed a second half to the same defect. Once a Dhizuku session really does
 * reach system_server, the installer package name it carries stops being cosmetic: the transact
 * arrives as the device owner's app uid, and `createSessionInternal` runs
 * `mAppOps.checkPackage(callingUid, installerPackageName)` on everything that is not root, shell or
 * system. Thor's own name — what the old factory passed, harmlessly, because the call never landed —
 * is refused there. [sessionInstallerPackageName]'s Dhizuku tests pin the owner package.
 *
 * ### What a JVM test can and cannot say here
 *
 * Nothing on the far side of a binder is reachable from this source set — no mockk, no Robolectric,
 * and `rikka.shizuku.Shizuku`'s static initialiser builds a Binder and throws "not mocked". So the
 * two things that *are* checkable are checked: the pure transport/identity decisions, and the
 * **shape** of the privileged API — that no caller can open a privileged session, or build a
 * privileged installer, without naming which transport it belongs to. The shape assertions are the
 * only defence available against the exact regression this fixes, which was a Dhizuku call site
 * quietly reusing a Shizuku helper.
 */
class PrivilegedPackageInstallersTest {

    // ---------------------------------------------------------------------------------------------
    // transportFor: which wrapper a mode's privileged session belongs to
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `each privileged mode maps to its own transport`() {
        assertEquals(
            PrivilegedInstallerTransport.SHIZUKU,
            transportFor(InstallMode.SHIZUKU)
        )
        assertEquals(
            PrivilegedInstallerTransport.DHIZUKU,
            transportFor(InstallMode.DHIZUKU)
        )
    }

    /** The regression this fixes: Dhizuku ran on the Shizuku wrapper, which is nothing on a Dhizuku-only device. */
    @Test
    fun `dhizuku does not borrow the shizuku transport`() {
        assertFalse(transportFor(InstallMode.DHIZUKU) == PrivilegedInstallerTransport.SHIZUKU)
        assertFalse(transportFor(InstallMode.SHIZUKU) == PrivilegedInstallerTransport.DHIZUKU)
    }

    @Test
    fun `the modes with no privileged PackageInstaller answer null`() {
        // ROOT installs through the Odin shell, NORMAL is the unprivileged in-process installer, and
        // EXTERNAL hands the job to another app entirely. None of the three has a binder to wrap.
        assertNull(transportFor(InstallMode.ROOT))
        assertNull(transportFor(InstallMode.NORMAL))
        assertNull(transportFor(InstallMode.EXTERNAL))
    }

    @Test
    fun `every install mode is answered rather than thrown at`() {
        // A `when` with no `else` is what makes a newly added InstallMode a compile error instead of
        // a runtime one; this asserts the whole enum is actually covered today.
        InstallMode.entries.forEach { mode ->
            transportFor(mode) // must not throw
        }
        assertEquals(
            listOf(InstallMode.SHIZUKU, InstallMode.DHIZUKU),
            InstallMode.entries.filter { transportFor(it) != null }
        )
    }

    @Test
    fun `there is exactly one transport per privileged mode and no more`() {
        assertEquals(
            listOf("SHIZUKU", "DHIZUKU"),
            PrivilegedInstallerTransport.entries.map { it.name }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // sessionInstallerPackageName: the name the session records as its installer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an adb shizuku names the shell package and anything else names thor`() {
        // Behaviour preserved verbatim from getShizukuPackageInstaller(): uid 2000 is the ordinary
        // ADB-started Shizuku, and `getMySessions` checks the installer package's owner. The Dhizuku
        // owner name is passed on every call to prove this branch ignores it.
        assertEquals(
            "com.android.shell",
            sessionInstallerPackageName(
                PrivilegedInstallerTransport.SHIZUKU,
                shizukuUid = 2000,
                thorPackageName = "com.valhalla.thor",
                dhizukuOwnerPackageName = "com.rosan.dhizuku",
            )
        )
        assertEquals(
            "com.valhalla.thor",
            sessionInstallerPackageName(
                PrivilegedInstallerTransport.SHIZUKU,
                shizukuUid = 0,
                thorPackageName = "com.valhalla.thor",
                dhizukuOwnerPackageName = "com.rosan.dhizuku",
            )
        )
        // -1 is the "could not read Shizuku's uid" sentinel getShizukuPackageInstaller() already used.
        assertEquals(
            "com.valhalla.thor",
            sessionInstallerPackageName(
                PrivilegedInstallerTransport.SHIZUKU,
                shizukuUid = -1,
                thorPackageName = "com.valhalla.thor",
                dhizukuOwnerPackageName = "com.rosan.dhizuku",
            )
        )
    }

    @Test
    fun `a dhizuku session names the device owner and never thor`() {
        // `createSessionInternal` runs `mAppOps.checkPackage(callingUid, installerPackageName)` for
        // any caller that is not root, shell or system. A Dhizuku transact arrives as the device
        // owner's app uid, which owns neither "com.android.shell" nor Thor — so naming Thor here does
        // not mislabel the session, it throws and drops the install to the confirmation dialog.
        // Shizuku's uid is irrelevant on this transport and must stay irrelevant.
        listOf(2000, 0, -1, 10231).forEach { uid ->
            assertEquals(
                "naming Thor on the Dhizuku transport is refused by appops, not merely cosmetic",
                "com.rosan.dhizuku",
                sessionInstallerPackageName(
                    PrivilegedInstallerTransport.DHIZUKU,
                    shizukuUid = uid,
                    thorPackageName = "com.valhalla.thor",
                    dhizukuOwnerPackageName = "com.rosan.dhizuku",
                )
            )
        }
    }

    @Test
    fun `a dhizuku session with no known owner falls back to thor`() {
        // Dhizuku.getOwnerPackageName() throws until the owner component has been received, and the
        // call site turns that into null. There is no better name at that point; Thor's own is where
        // this rung already was before the transport was fixed, so the fallback changes nothing
        // rather than handing the platform a null it fails on differently.
        assertEquals(
            "com.valhalla.thor",
            sessionInstallerPackageName(
                PrivilegedInstallerTransport.DHIZUKU,
                shizukuUid = -1,
                thorPackageName = "com.valhalla.thor",
                dhizukuOwnerPackageName = null,
            )
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Shape: a privileged session cannot be opened without naming its transport
    // ---------------------------------------------------------------------------------------------

    private val installers: Class<*> = PrivilegedPackageInstallers::class.java

    private fun publicMethodsNamed(name: String) =
        installers.declaredMethods.filter { it.name == name && Modifier.isPublic(it.modifiers) }

    @Test
    fun `openSession takes a transport`() {
        val candidates = publicMethodsNamed("openSession")
        assertTrue("PrivilegedPackageInstallers.openSession is missing", candidates.isNotEmpty())
        candidates.forEach { method ->
            assertTrue(
                "openSession(${method.parameterTypes.joinToString { it.simpleName }}) does not name a transport",
                method.parameterTypes.any { it == PrivilegedInstallerTransport::class.java }
            )
        }
    }

    @Test
    fun `the privileged IPackageInstaller lookup takes a transport`() {
        val candidates = publicMethodsNamed("privilegedPackageInstaller")
        assertTrue(
            "PrivilegedPackageInstallers.privilegedPackageInstaller is missing",
            candidates.isNotEmpty()
        )
        candidates.forEach { method ->
            assertTrue(
                "privilegedPackageInstaller has an overload that does not name a transport",
                method.parameterTypes.any { it == PrivilegedInstallerTransport::class.java }
            )
        }
    }

    @Test
    fun `every public entry point on the object names a transport`() {
        // The whole failure mode was a Dhizuku call site reusing a Shizuku helper. If a public
        // function here can be called without saying which transport it is for, that is available
        // again. `wrap` included — it is the one line that differs between the two modes.
        val exempt = setOf("toString", "hashCode", "equals")
        val swept = installers.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.name !in exempt }

        // Without this, an empty `swept` would make the loop below assert nothing and report a pass.
        assertTrue("the sweep found no public entry points to check", swept.isNotEmpty())

        swept.forEach { method ->
            assertTrue(
                "${method.name}(${method.parameterTypes.joinToString { it.simpleName }}) " +
                    "can be called without naming a transport",
                method.parameterTypes.any { it == PrivilegedInstallerTransport::class.java }
            )
        }
    }

    @Test
    fun `handleFor is the way a privileged installer is obtained`() {
        val candidates = publicMethodsNamed("handleFor")
        assertTrue("PrivilegedPackageInstallers.handleFor is missing", candidates.isNotEmpty())
        assertTrue(
            "handleFor must hand back an InstallerHandle, not a bare PackageInstaller",
            candidates.all { it.returnType == InstallerHandle::class.java }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Shape: an InstallerHandle always carries the opener that goes with its installer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an InstallerHandle cannot be built without a session opener`() {
        // A default-argument or single-argument constructor here would let a caller reconstruct the
        // bug: an installer whose sessions are opened by the unwrapped `PackageInstaller.openSession`.
        val ctors = InstallerHandle::class.java.declaredConstructors
        ctors.forEach { ctor ->
            assertTrue(
                "InstallerHandle(${ctor.parameterTypes.joinToString { it.simpleName }}) omits the opener",
                ctor.parameterCount >= 2
            )
        }

        // The arity check above cannot see the regression its comment names. Giving `sessionOpener` a
        // default makes Kotlin emit a *second*, synthetic constructor
        // `(PackageInstaller, Function1, int, DefaultConstructorMarker)` — arity 4, so `>= 2` still
        // passes while `InstallerHandle(installer)` becomes callable again. Counting the constructors
        // is what actually catches it: a defaulted parameter takes this from one to two.
        assertEquals(
            "InstallerHandle should have exactly one constructor; a second means a parameter grew a " +
                "default and the opener can be omitted again: " +
                ctors.joinToString { c -> "(${c.parameterTypes.joinToString { it.simpleName }})" },
            1,
            ctors.size
        )
    }

    // ---------------------------------------------------------------------------------------------
    // The two fixes themselves, read out of the compiled constant pool
    // ---------------------------------------------------------------------------------------------
    //
    // Neither fix can be *executed* here: wrap() constructs a ShizukuBinderWrapper or a
    // DhizukuBinderWrapper, both of which touch android.os.Binder and die with "not mocked", and
    // openSession() needs a live system_server on the far side. Everything above therefore tests the
    // plumbing around the two decisions without ever testing the decisions.
    //
    // What is available without a device is the class file. A constant pool records every type the
    // method bodies reference and every string literal they load, so "does wrap() still reach for
    // Dhizuku's wrapper" and "does openSession() still name IPackageInstallerSession" are answerable
    // from bytes on the test classpath. These are the only assertions in this file that fail if the
    // two fixes are reverted while their call sites are left intact.

    /** The compiled bytes of [clazz] as they sit on the test classpath. */
    private fun classFileBytes(clazz: Class<*>): String {
        val fileName = clazz.name.substringAfterLast('.') + ".class"
        val stream = requireNotNull(clazz.getResourceAsStream(fileName)) {
            "could not read $fileName from the test classpath"
        }
        // ISO-8859-1 maps each byte to the char of the same value, so a substring search over this
        // decoding is a byte search — which is what a constant-pool scan for ASCII needs to be.
        return stream.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun `wrap reaches for each transport's own wrapper`() {
        val compiled = classFileBytes(PrivilegedPackageInstallers::class.java)

        // Fix 3. Routing DHIZUKU back onto ShizukuBinderWrapper — which is what
        // getDhizukuPackageInstaller() did, via ShizukuPackageInstallerUtils — drops this reference
        // and fails here. On a Dhizuku-only device that revert is a dead rung: there is no Shizuku
        // service to transact through, so the install falls all the way to the dialog.
        assertTrue(
            "PrivilegedPackageInstallers no longer references Dhizuku's binder wrapper — the Dhizuku " +
                "transport has been routed onto Shizuku's, which is the bug this file fixed",
            compiled.contains("com/rosan/dhizuku/api/Dhizuku")
        )

        // And the mirror image, so the two transports cannot be collapsed in either direction.
        assertTrue(
            "PrivilegedPackageInstallers no longer references ShizukuBinderWrapper",
            compiled.contains("rikka/shizuku/ShizukuBinderWrapper")
        )
    }

    @Test
    fun `openSession still wraps the session binder`() {
        val compiled = classFileBytes(PrivilegedPackageInstallers::class.java)

        // Fix 2. This string is loaded only by openSession — once to build the wrapped
        // IPackageInstallerSession and once to name the PackageInstaller.Session constructor's
        // parameter type. Going back to the platform's own PackageInstaller.openSession(id), which
        // hands Session an unwrapped binder, removes both and fails here.
        assertTrue(
            "PrivilegedPackageInstallers no longer names IPackageInstallerSession — the session binder " +
                "is not being wrapped, so every write on the session transacts as Thor and the " +
                "privileged rung dies with \"Session does not belong to uid N\"",
            compiled.contains("android.content.pm.IPackageInstallerSession")
        )

        // The session is opened on the hidden interface, not on the public PackageInstaller facade;
        // the facade is what cannot wrap.
        assertTrue(
            "PrivilegedPackageInstallers no longer names IPackageInstaller",
            compiled.contains("android.content.pm.IPackageInstaller")
        )
    }

    /** Guards the guards: a typo in [classFileBytes] would make both tests above vacuous. */
    @Test
    fun `the constant pool scan can actually fail`() {
        val compiled = classFileBytes(PrivilegedPackageInstallers::class.java)
        assertTrue("read no bytes for PrivilegedPackageInstallers", compiled.length > 512)
        assertFalse(
            "the scan matches a string that is not in this class, so it proves nothing",
            compiled.contains("android.content.pm.INotAThingThatExists")
        )
    }

    @Test
    fun `the unprivileged handle is named rather than defaulted`() {
        // InstallMode.NORMAL genuinely does want `PackageInstaller.openSession` — its installer came
        // from `context.packageManager`, so the session already belongs to Thor's own uid. That case
        // has to be spelled out at the call site, not arrived at by leaving an argument off.
        val factory = InstallerHandle.Companion::class.java.declaredMethods
            .firstOrNull { it.name == "unprivileged" }
        assertNotNull("InstallerHandle.unprivileged is missing", factory)
        assertEquals(InstallerHandle::class.java, factory!!.returnType)
    }
}

package com.valhalla.thor.rootservice;

/**
 * The privileged surface the :root daemon exposes to the app process.
 *
 * <p><b>Only ever append to this interface, and never change an existing method's arity.</b> AIDL
 * derives each transaction code from declaration order (FIRST_CALL_TRANSACTION + index), and a root
 * daemon can outlive the app that started it -- it is a separate app_process, so an app update
 * leaves the old one running and bound. Inserting a method renumbers everything below it, and the
 * stale daemon keeps the old numbering: a clearAppData() call would be dispatched to whatever now
 * sits at that index, wiping the wrong thing. Appending is safe because an unknown transaction code
 * falls through to Binder's default onTransact, which returns false, which the generated proxy reads
 * back as an empty reply parcel -- false for a boolean, null for a String. A stale daemon therefore
 * degrades into an honest failure rather than a mis-dispatch.
 *
 * <p>That is why setAppSuspended below keeps its two-argument shape even though setAppSuspendedAs
 * supersedes it.
 */
interface IThorRootService {
    boolean setAppSuspended(String packageName, boolean suspended);
    boolean clearAppData(String packageName);

    /**
     * Sets {@code packageName}'s suspended state while recording {@code suspendingPackage} as the
     * suspending identity, and reports whether the platform's own record agrees afterwards.
     *
     * <p>Android keys a suspension on the suspending package name captured at suspend time, and from
     * API 30 a caller may only lift its own entry (PackageSettingBase.removeSuspension(callingPackage),
     * android-11.0.0_r1 PackageSettingBase.java:443-452). Naming a suspender you do not own is not an
     * error either: it leaves oldSuspendParams == null == newSuspendParams, so changed == false, so
     * the package is left *out* of the returned failure array and the call looks like a success. The
     * identity therefore has to be passed in from a readback rather than guessed, which is what this
     * overload exists for.
     *
     * <p>Root, and only root, may name an arbitrary identity:
     * PackageManagerService.enforceCanSetPackagesSuspendedAsUser unconditionally early-returns for
     * Process.ROOT_UID before any suspender-name validation (android-17.0.0_r1
     * PackageManagerService.java:3354-3358), unchanged from API 28 to main. Shell is not exempt, so
     * this rescue path exists in the root daemon and nowhere else.
     *
     * @param suspendingPackage the identity to act as. When null the daemon falls back to its own
     *   historical behaviour: for a suspend, try com.valhalla.thor then com.android.shell then
     *   android; for an unsuspend, clear every identity the readback reports.
     * @return true only when a re-read of the platform's record confirms the requested state -- never
     *   merely because the reflective call returned an empty failure array.
     */
    boolean setAppSuspendedAs(String packageName, boolean suspended, in @nullable String suspendingPackage);

    /**
     * Raw {@code dumpsys package <packageName>} output, or null when it could not be read.
     *
     * <p>Exposed so the gateway can discover who actually owns a suspension through the already-bound
     * root process instead of spawning a second shell. It has to happen on this side of the Binder:
     * PackageManagerService.dump gates on android.permission.DUMP via
     * DumpUtils.checkDumpAndUsageStatsPermission (android-16 PackageManagerService.java:6689), which
     * the app process does not hold.
     *
     * <p>Feed the result to {@code parseSuspendingPackages}, and treat null -- and an empty parse --
     * as "unknown", never as "not suspended".
     */
    @nullable String dumpPackage(String packageName);
}

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
 * <p>That is why setAppSuspended below keeps its two-argument shape, and setAppSuspendedAs its
 * three-argument one, even though setAppSuspendedAsForUser supersedes both.
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

    /**
     * {@link #clearAppData} for a named Android user, which is the only shape of it that is safe to
     * call from a secondary user.
     *
     * <p>The daemon cannot work the user out for itself. It runs as uid 0 in user 0, so
     * {@code Process.myUserHandle()} answers 0 there no matter which user the app that bound it
     * belongs to -- and IPackageManager.clearApplicationUserData takes the user id as an argument,
     * so the one-argument {@link #clearAppData} above could only ever pass 0. For Thor in a work
     * profile or a Xiaomi Second Space that wipes the *primary* user's copy of the package, which is
     * irreversible and reported as a success.
     *
     * <p>Appended rather than added as a third argument to {@link #clearAppData}, per the rule at the
     * top of this file: a daemon left over from an older build has no transaction code for this and
     * returns false, so the caller reports a failure instead of destroying the wrong user's data.
     * {@link #clearAppData} therefore stays, and stays user-0, for that stale-daemon case alone.
     */
    boolean clearAppDataForUser(String packageName, int userId);

    /**
     * {@link #setAppSuspendedAs} for a named Android user -- the only shape of it whose outcome the
     * app process can actually judge.
     *
     * <p>One operation used to name three different users. The daemon's reflection wrote user 0,
     * because it cannot work the user out for itself: it runs as uid 0 in user 0, so
     * {@code Process.myUserHandle()} answers 0 there whichever user the app that bound it belongs to
     * -- the same blindness {@link #clearAppDataForUser} exists for. The dumpsys readback that
     * decides this method's return value parsed user 0 to match. But the gateway's own judge of
     * success is {@code ApplicationInfo.FLAG_SUSPENDED}, read in-process, and that can only ever
     * answer for *Thor's* user.
     *
     * <p>With Thor in a work profile or a Xiaomi Second Space, those two numbers differ and the
     * mismatch is a false success in both directions. A suspend pauses the personal profile's copy
     * of an app the user never selected, verifies it against a user-0 dump, and reports success. The
     * unsuspend that should undo it reads FLAG_SUSPENDED for Thor's user, finds it false because
     * nothing was ever suspended there, and returns success having run no rung at all -- so the
     * suspension it created cannot be lifted from inside Thor.
     *
     * <p>{@code userId} is therefore both the user written and the user parsed back:
     * setPackagesSuspendedAsUser's suspendingUserId and targetUserId, and the {@code User N:} section
     * parseSuspendingPackages is asked to read. Root may name a user it does not itself belong to for
     * the same reason it may name an arbitrary suspending package --
     * PackageManagerService.enforceCanSetPackagesSuspendedAsUser early-returns for Process.ROOT_UID
     * before either check (android-17.0.0_r1 PackageManagerService.java:3354-3358).
     *
     * <p>Appended rather than added as a fourth argument to {@link #setAppSuspendedAs}, per the rule
     * at the top of this file: a daemon left over from an older build has no transaction code for
     * this and returns false, so the caller reports a failure instead of pausing another user's app
     * behind a dialog only this daemon can lift. {@link #setAppSuspended} and
     * {@link #setAppSuspendedAs} therefore stay, and stay user-0, for that stale-daemon case alone.
     *
     * @param suspendingPackage as on {@link #setAppSuspendedAs}: the identity to act as, or null to
     *   let the daemon apply its own fallback order.
     */
    boolean setAppSuspendedAsForUser(String packageName, boolean suspended, in @nullable String suspendingPackage, int userId);
}

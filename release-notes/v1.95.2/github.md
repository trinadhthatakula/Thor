# Thor v1.95.2 Release Notes

A dev build on top of the **v1.95.1** stable release. Its headline is **per-component control** —
the Components tab in App Info stops being a list of class names and becomes something you can act
on — and alongside it, the silent-install path that was quietly falling back to the system dialog on
both Shizuku and Dhizuku now works.

---

## ✨ Highlights

* 🧩 **Per-component control** — open an activity, force-open the ones an ordinary launch cannot reach, stop a running service, or switch off an individual component, straight from **App Info → Components**.
* 📒 **A ledger, not a policy** — Thor records only what it disabled itself, so **Restore all** puts those components back the way they shipped and leaves everything else alone. Nothing is re-applied at boot; `PackageManager` stays the source of truth.
* 🔒 **Honest about privilege** — component writes need uid 0, so the screen says *why* it cannot act (shell-mode Shizuku, Dhizuku) instead of drawing buttons that throw on every press.
* 🔧 **Silent install actually silent** — the Shizuku and Dhizuku rungs no longer fall through to the system installer confirmation dialog. Three separate defects, one per rung.
* ❄️ **The Freezer can no longer take the process down** — a watchlist write that fails is reported, not thrown into an app with no exception handler.
* 💬 **Readable failures** — an error toast shows the reason rather than `DynamicString(value=…)` or an R8-shortened class name.
* 🌍 **Locale-correct backup dates** — the archive date follows the in-app language picker, matching the size beside it.

---

## What's Changed

### 🧩 Per-Component Control in App Info (#435, #436, #439, #440, #442)

The Components tab listed four columns of class names and did nothing else. It now offers what
Activity Launcher and the root component managers give you:

* **Open / Force open** — an exported, unguarded activity launches with a plain `startActivity` and no privilege at all. Everything else routes through `am start --user N -n`. The predicate is `!exported || permission != null`, not just `!exported`: an exported activity sitting behind an `android:permission` fails exactly the way an unexported one does.
* **Stop now** — stops a running service.
* **Disable / Enable / Reset to default** — switching a component off asks for consent once, persisted for the session; the read-only verbs never consult it. Re-enabling prefers `default-state` over `enable`, because `enable` writes an override that outlives an app update a real default would not.
* **Copy class name** — for the rows you want to take elsewhere.
* **Badges that name the state**: *Restricted by Thor*, *Changed elsewhere*, *Disabled*, *Not exported*, *Off by default*.

**What a privilege mode can actually do.** Every privileged verb here collapses onto one fact — does
the transport execute at uid 0 — so capability is keyed on the **effective uid**, not on the
privilege mode:

* `setComponentEnabled` is uid 0 only. `PackageManagerService.setEnabledSetting` carves out `Process.SHELL_UID` on the explicit condition that `className == null`; with a class name present it throws, and reaching `IPackageManager` by reflection lands on the same check with the same calling uid.
* Force-launch is uid 0 only. `ActivityManager.canAccessUnexportedComponents` waives the export and permission checks for `ROOT_UID` and `SYSTEM_UID` alone, and `START_ANY_ACTIVITY` is `signature`-level — the Shell package does not declare it in any release from 9 to 16.
* So **Root** works, **Shizuku** works only when Shizuku itself was started as root, and **Dhizuku** is an empty case rather than a partial one — and each says so in its own words. An unreadable Shizuku uid fails closed, because a wrong guess here paints a screen of controls that throw.

**Restore all.** One button puts every component Thor disabled back the way it shipped —
*in this app and in every other*, for the current user. `restoreToEnabled` stores the manifest
default as read at the time, never `true`, so a component that ships disabled is restored to
disabled rather than to a state the app never had. Two orderings are load-bearing and pinned by
tests: the ledger row is written only *after* the platform call succeeds, and dropped only *after*
the restoring call succeeds. A partial run therefore reports **"Restored X of Y — try again to
finish the rest"**: a row can survive either because the component is still off, or because the
platform call succeeded and only Thor's record of it failed to clear.

**Two traps in the shell layer**, both closed here:

* A component spec is `pkg/cls`, and `PackageManager` reports an inner class as `com.foo.Widget$Receiver`. Interpolated unquoted, `$Receiver` expands to nothing and the command lands on `com.foo.Widget` — in many apps a different, real component. Every spec is single-quoted through `escapeForShell()`.
* The exit code cannot answer whether a launch worked, in either direction. `am start` prints `Security exception:` and still exits 0 on most releases, while Android 14 folds the same refusal into `START_CLASS_NOT_FOUND`. The verdict is read from the output, with the code as a backstop — and `Warning: Activity not started, its current task has been brought to the front` is deliberately not a failure, since that is the single most common repeat press in the feature.

Privileged actions are serialised per screen, because two concurrent `pm` writes to
`package-restrictions.xml` can lose one. Room migrates 6 → 7 by auto-migration for the new table.

### 🔧 Silent Install on Shizuku and Dhizuku (#434)

Silent install fell through to the system confirmation dialog on both privileged modes while root
and normal install worked. Three independent defects, one per rung:

* **Shell `pm`, both modes.** The multi-APK path invoked `pm install-multiple`, a verb that does not exist outside `adb`'s own client, and the single-APK path handed `pm install` a path inside Thor's `Android/data`, which a non-root shell cannot read from API 30 on. Both now use the streaming session shape the root gateway already used — `install-create` → `cat <apk> | install-write -S <size> $SID <name> -` → `install-commit` — so the bytes travel over stdin and no privileged reader has to reach into app-private storage.
* **Privileged `PackageInstaller`, both modes.** The session install passed the raw `IPackageInstallerSession` along, so every following `openWrite`/`fsync`/`commit` transacted from Thor's own app uid and met *"Session does not belong to uid N"*. The session binder is now wrapped before `PackageInstaller.Session` is built around it.
* **Dhizuku.** `getDhizukuPackageInstaller()` obtained its `IPackageInstaller` through the Shizuku utils, so the Dhizuku rung transacted over a wrapper around a service that is not installed on a Dhizuku-only device. The transport is now chosen from the `InstallMode` in a `when` with no `else`, and each transport applies exactly one wrapper: its own. The installer package name also stops being cosmetic once a Dhizuku session really reaches `system_server`, so it is set correctly.

### ❄️ Freezer Bookkeeping Hardening (#437, #438)

Room reports a failed write by throwing, `FreezerRepositoryImpl` is a bare pass-through, and `:app`
installs no `CoroutineExceptionHandler` — so every watchlist call sitting in a bare
`viewModelScope.launch` was one full disk away from process death. **16 of 21 call sites**, across
four view models, and ten of those threw *after* an irreversible privileged call had already
succeeded.

* **Every watchlist call site now runs under `launchGuarded`**, and the handlers distinguish the two sides of the irreversible step: a throw *before* it means the tap did nothing, a throw *after* it means the app really is frozen or thawed and only Thor's record is missing — reported as two messages, because either alone is a half-truth.
* **The port, not just its callers.** `FreezerShortcutManager.pinAppShortcut`, `refreshAppShortcut` and `syncDynamicShortcuts` are fire-and-forget: they return once the body is merely *scheduled*, so no caller-side guard could ever have caught them. They now route through `launchSafely`.
* **Ordering.** Removing an app from the Freezer greys the launcher shortcut *before* dropping the row, at all four surfaces. A pinned shortcut can only be disabled, never deleted, so the row is the only end of the pair Thor can take back — greying first means a launcher that refuses stops the delete and leaves a state a retry can act on.
* **A post-merge review of #437** turned up five more: an app-info sheet drawing the `frozen` chip over an app its own toast had just called unfrozen, and the ordering rationale itself — which was backwards at five code sites and on the public site. A per-app shortcut carries `ACTION_LAUNCH`, so a stale live shortcut *thaws* an app; it cannot drive a freeze from the launcher. Greying first is still right, for retryability, and that is the reason now given.

### 💬 Legible Errors (#438, #439)

* **A `UiText` passed as a format argument** reached `String.format`, which has no idea what one is and fell back to `toString()`. Users read `Error: DynamicString(value=no privileged gateway)`, or `Error: …UiText$StringResource@4f2a1c` — which minification only shortens. Nested `UiText` is resolved before formatting now. Two tests had *asserted* the garbled shape, one of them named *"is shown as that message, not as a bare error"*; both now assert what actually reaches the user.
* **`toString()` on `StringResource` and `PluralsResource`**, so the next leak reads as "wrong type here" rather than as a crash artefact. `UiTextException` gets one too — deliberately as `toString`, not as a `message`, because about a dozen handlers render `e.message` into a toast and giving this type a message would trade an empty error for a worse one.
* **The obfuscated success toast is gone.** `getSuccessMessage`'s `else` branch filled a translated sentence with `action.javaClass.simpleName` — untranslated in all eight locales, and R8 has no keep rule for `AppClickAction`, so the toast read *"b completed: Instagram"*. The `when` is exhaustive now, making a newly routed action a compile error rather than a mystery toast.

### 🌍 Locale-Correct Archive Dates (#434)

`DateFormat.getDateInstance(MEDIUM)` reads `Locale.getDefault(FORMAT)`, which below API 33 is the
*device's* language whatever the in-app picker chose — so a backup card showed a localised size
beside an English date. The date now resolves through `AppLocale.localeOf(context)`, off the same
Configuration the composable resolves its strings with.

---

## 🧰 Project & Internal

* **Component-control documentation** (#439, #440) — the feature is written up on the site, and the design spec corrected: **Restore all** is *cross-app but single-user*, not device-wide, and the KDoc now states that the set it leaves behind is wider than "the components still disabled".
* **`SyntheticAccessor` pinned in `:app`** (#441, #443) — 14 production declarations widened to `internal`, and the follow-up report corrected to name the exact lint id and every source set the change touched, including the test one that neither `lintStoreRelease` nor a failing build reports.
* **Shizu Store listing** (#428, converged from `master`) — the live schema had mutated in place under the same `schema_version: 1`, making the manifest a hard rejection that the store ignores silently; re-vendored, with the developer profile slots filled.
* **Web layer converged** (#428) — IndexNow submission, the downloads hero, `llms.txt`/`robots.txt`, and three documentation corrections, all already live on `master`, now back on `dev`.
* **Dependencies** (#426, #427, #431) — Gradle wrapper 9.7.0 → 9.7.1, `github/codeql-action`, and three web packages.
* **Store changelog sync** (#433) — the Shizu manifest brought up to v1.95.1.

---

## 🛠 Reliability & Verification

* `./gradlew :app:testFossDebugUnitTest --rerun-tasks` — **129 suites, 1776 tests, 0 failures** (up from 1634 at v1.95.1; 51 of the new ones cover component control, 8 the `UiText` argument walk).
* `./gradlew lintFossDebug lintStoreRelease` — clean; no `MissingTranslation` across any flavour, and the eight in-app locales all carry the reworded partial-restore string.
* Room 6 → 7 auto-migration for `component_overrides`, with the exported schema checked in.

---

## 🛠 Commits Log

**Per-component control**
* `f8408b48` docs(components): design spec for per-component control in App Info
* `3104018e` feat(components): open, force-open, stop and disable individual components
* `cf19a134` fix(components): read stopservice's verdict from stderr, not its exit code
* `06781eac` fix(components): rank failure markers by usefulness, not by line order
* `e7090e42` fix(components): a dead transport is not a stopped service
* `6ed95a38` fix(components): answer the three review findings on the disable path
* `037aaa3c` fix(components): the session-consent box says whether it is ticked
* `60cc4daf` fix(components): give the consent row back the 48dp the null handler gave up
* `84422d9d` docs(components): document per-component control, and correct the spec's scope
* `73c81259` docs(components): Restore all is cross-app, not device-wide
* `8b6382b0` docs(components): say which rows a partial Restore all leaves behind
* `6517660d` fix(strings): a partial Restore All must not claim the rest are off

**Installer & backup**
* `0bd1369e` fix(installer): make the Shizuku and Dhizuku silent-install rungs actually work
* `583198af` fix(backup): format the archive date in the app locale
* `4d038c1a` docs(installer): correct two overstated claims and one overstated test
* `535e7c9f` docs(installer): say what actually exempts the shell from Android/data

**Freezer & errors**
* `e0ed3f76` fix(freezer): a watchlist write that fails must not take the process
* `b8acbf0a` fix(freezer): the review follow-ups PR #437 left behind
* `ab029f94` fix(uitext): make a leaked UiText legible and kill the obfuscated toast

**Project**
* `de4119e3` chore(lint): pin SyntheticAccessor in :app, and correct what it is worth
* `dfd1a8b0` docs(follow-ups): exact lint id, and every source set the :app fix touched
* `09153c8e` chore: reconverge master's web-layer commits into dev
* `61ab1ec6` chore(shizu): sync store changelog to v1.95.1
* `0541d937` chore(deps): bump gradle-wrapper from 9.7.0 to 9.7.1 in the maven group
* `91f5f784` chore(deps): bump github/codeql-action in the actions group
* `71b33dd9` chore(deps): bump the web group in /web with 3 updates

**Full Changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.95.1...v1.95.2

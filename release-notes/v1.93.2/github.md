# Thor v1.93.2 Release Notes

A **data-safety and correctness** release on top of the v1.93.1 pre-release. The headline is that
**freezing a preinstalled app no longer destroys its data** — under any privilege mode — and that
the paths which used to report a success they never checked now read the platform back and tell you
what actually happened.

> **Pre-release.** v1.93.2 is a dev build, like v1.93.1 was. The current **stable** release is still
> **v1.93.0**.

*100 commits and 28 merged pull requests since `v1.93.1-dev-103`. Roughly two thirds of them are the
new marketing site, documentation and CI; the app changes are below.*

## ✨ Highlights

* 🧊 **A system-app freeze keeps the app's data** — disable first, and a `-k` removal only where the
  platform actually refused (#314, #332, for GH#316).
* ⚠️ **A freeze Thor cannot perform now fails and says why**, instead of quietly uninstalling the app
  for your user (#314, #332).
* 📱 **The app list no longer collapses to a single entry** on Chinese-market ROMs (#329).
* ❄️ **Removing an app from the Freezer really unfreezes it**, from every screen that can remove it
  (#308, #331, for GH#310).
* ⏸️ **Unsuspend stops reporting a success it never earned**, and names the privilege still holding
  the app (#330).
* 🤖 **Dhizuku is usable on a device**, and the Privilege Check dialog's Refresh actually re-probes
  privileges (#333).
* 💜 **GitHub Sponsors** in the Support sheet (#311).

## What's Changed

### 🧊 System-app freeze: disable first, `-k` second (#314, #332 — for GH#316)

* Every build before this one ran `pm uninstall --user N` with **no `-k`** as the first and only
  mechanic, on every privilege mode. The package was removed for the current user and `installd`
  destroyed `/data/user/N/<pkg>`, so unfreezing brought the app back factory-fresh — logins,
  settings and local content gone — while the UI reported an ordinary, reversible freeze
  (`b59c1f64`).
* **Rung 1 is now a disable.** Root runs `pm disable --user N`; Shizuku tries
  `IPackageManager.setApplicationEnabledSetting` through `:bypass`, then `pm disable-user --user N`,
  then the unprivileged `PackageManager`. Every rung is judged by re-reading `ApplicationInfo`, not
  by a shell exit code — `pm` prints `Success` for a no-op (`b59c1f64`).
* **Rung 2 keeps the data.** The uninstall fallback now carries `-k` (`DELETE_KEEP_DATA`). Measured
  on the HyperOS device this fallback exists for: `pm uninstall -k --user 0` then
  `pm install-existing --user 0` returned **unchanged** `ceDataInode` and `deDataInode` values, so
  the CE and DE data directories were never recreated — which is exactly what `DELETE_KEEP_DATA`
  promises. The bytes inside them were not compared; the inodes were (`cf739fc0`). A later pass
  measured that runtime permission grants survive the round trip too, at
  uid 2000 on a stock API 36 emulator — one permission, one build, recorded for the scope it has
  (`4524387d`). What the rung still costs unconditionally is `FLAG_INSTALLED`, which is why every
  query on the freeze path passes `MATCH_UNINSTALLED_PACKAGES`.
* **Rung 2 is gated on a refusal, not on an Android version.** The first cut of the gate was
  `sdkInt >= 36`; measurement killed it. `pm disable-user --user 0` at uid 2000 succeeds on stock
  API 36 *and* API 37, AOSP's shell guard in `PackageManagerService.setEnabledSettings` is
  byte-identical across android14- through android16-qpr2-release, and the refusal users actually
  hit is Xiaomi's vendor `PackageManagerServiceImpl.shouldRestrictEnabledSettingsChange` — first
  reported on HyperOS running **Android 14**, a year before Android 16 shipped. Reproduced on
  `25053PC47G`, HyperOS OS3.0, build `BP2A.250605.031.A3` (`6ebdaa17`).
* ⚠️ **Behaviour change: a freeze can now fail where it used to "work".** `uninstallFreezeFallbackAllowed`
  returns `false` for `PrivilegeMode.ROOT`, so a root user on a ROM that protects the package now
  sees *"Root freeze of X failed"* where the old build "succeeded" by removing the app for the user.
  A non-zero exit, a binder timeout or an unreadable read never counts as a refusal — only a real
  `SecurityException` does (`6ebdaa17`).
* **The fallback does not exist at shell uid on Android 17.** The identical command that succeeds on
  API 36 answers `Failure [only root can delete system app for a particular user]` on API 37
  (`CP31.260623.005`). The package is left untouched, so the chain ends in an honest failure, and
  Shizuku users get a new dialog (`freeze_system_app_requires_root`) naming Thor's Root mode instead
  of the old, wrong *"reflection is blocked or shell lacks permissions"* (`4524387d`, `af37e1f4`).
* **Dhizuku joined the chain.** #314 deliberately left the Dhizuku gateway on the old unconditional
  uninstall; #332 converted it. Dhizuku now tries `setApplicationEnabledSetting` through the device
  owner, then `pm disable-user`, then `pm uninstall -k` — and hardware testing found that PMS
  **refuses rung 1 for the device-owner identity** where the shell uid succeeds on the same device
  (`af37e1f4`, `c997124e`).
* **Unfreeze learned both shapes.** Every system app frozen by v1.93.1 or earlier is
  uninstalled-for-user, and a package can be disabled *and* uninstalled at once. Unfreeze now runs
  `install-existing` → `enable` in that order and verifies the end state is installed **and**
  enabled; the old root path ran `pm enable` first and skipped it entirely for an uninstalled
  package, so such an app stayed disabled after "unfreeze" (`b59c1f64`, `af37e1f4`).
* **Extensions no longer bypass the freeze-tier gate.** `ExtensionOpsProvider` handed each
  extension-supplied package to the raw `ManageAppUseCase.setAppDisabled`, whose own KDoc says it
  carries no `FreezeTier` check — while `FreezeAppUseCase`'s KDoc already named "an extension
  trigger" as the caller it exists to backstop. Signature attestation does not close this: a
  legitimately pinned extension still supplies an arbitrary package list, and freezing a
  UAD-"Unsafe" system app can leave the device unable to boot. Unfreeze deliberately stays on
  `forceUnfreeze` — a block there would trap the app it protects (`f48c2874`).
* **Freezing acted on the wrong user in a work profile or Second Space.** `am get-current-user`
  returns the *foreground* user, not the user Thor runs as, so rung 1 disabled the app for the
  profile while the `-k` fallback removed it for the parent. Both paths now read
  `Process.myUserHandle()` in process — `thorUserId`: no shell, no permission, no binder
  (`86dd7afa`, `0bbf9e96`).
* **Two visible UI changes you will notice on first launch.** The Freezer's *"you have disabled apps
  that aren't in the Freezer — import them?"* prompt lost its blanket `!isSystem` filter
  (`disabledAppsNotInFreezer`), so system apps Thor itself froze are now offered — BLOCKED-tier
  packages excluded. And the app list/grid badge is unified: a system app frozen by removal used to
  draw a red "Uninstalled" danger icon and now shows the same Frozen snowflake as any other frozen
  app (the `cd_uninstalled` string was removed from all five locales) (`b59c1f64`).

### 📱 The app list collapsing to one entry (#329)

* **Cause:** `com.android.permission.GET_INSTALLED_APPS`, a non-AOSP runtime permission from
  T/TAF 108-2022 that MIUI/HyperOS, ColorOS, OriginOS and MagicOS implement as three-state
  (allow / while-in-use / deny) **in addition to** `QUERY_ALL_PACKAGES`. Granted *"while in use"* —
  which is what those ROMs default to — `getInstalledPackages()` empties the moment Thor is
  backgrounded, and the cache prune then deleted the Room rows **and the cached icon PNGs** under
  `filesDir/app_icons/` for nearly every app on the device (`6c010067`).
* **New permission declared in the manifest:** `com.android.permission.GET_INSTALLED_APPS`.
  `QUERY_ALL_PACKAGES` is untouched and still required. On AOSP the declaration is a silent no-op —
  the permission is not defined there.
* `scanVerdict()` in `domain/model/InstalledAppsVisibility.kt` refuses to prune against a scan it
  cannot believe — empty, missing the platform package `android`, more than half the cache lost, or
  a permission gate that explains the loss — and `AppRepositoryImpl` re-emits the cached rows so the
  UI still shows them. Only with no permission gate to explain it does `SUSPECT_SCAN_TOLERANCE = 2`
  apply.
* A **Grant** banner appears on the Apps tab while the permission reads `Denied`, and only on a
  device that actually defines it — probed with `getPermissionInfo`, not with
  `shouldShowRequestPermissionRationale`, which returns a hard `false` for a ROM-undefined
  permission and would have nagged every Pixel forever. A transient probe failure is no longer
  cached as "unsupported" (`1128ed84`).

### ⏸️ Unsuspend and suspension ownership (#330)

* Android records *who* suspended a package, and from API 30 only that identity may lift it. The
  platform API returns an **empty failure array** when it removes nothing — which every caller read
  as total success. Thor's suspender identity is not stable across modes: root records
  `com.valhalla.thor`, Shizuku/Dhizuku record `com.android.shell`, a device owner records `android`,
  and pre-GH#239 builds recorded the literal string `root` (`3124b93b`).
* `parseSuspendingPackages()` reads the owner set out of `dumpsys package <pkg>`, handling all four
  line shapes including API 35+'s `<0>com.android.shell` prefix. An unreadable dump **fails closed**
  — never as "nothing to do". 17 parser unit tests.
* Root issues one removal per recorded owner, under that owner's name, and reads the record back —
  `PackageManagerService.enforceCanSetPackagesSuspendedAsUser` unconditionally early-returns for
  `Process.ROOT_UID` before any suspender-name validation, unchanged from API 28 to main. Shell is
  not exempt, so this rescue path exists in the root daemon and nowhere else. A shell-uid Shizuku
  facing a root-recorded suspension on API 30+ now says so and names the owner instead of returning
  success.
* Two methods were **appended** — never inserted — to `IThorRootService` (`setAppSuspendedAs`,
  `dumpPackage`), with the interface's new class KDoc explaining why: AIDL derives transaction codes
  from declaration order, and a root daemon outlives the app update that started it. A stale daemon
  reads back an empty parcel, which fails closed.
* ⚠️ Implemented and unit-tested, but **not yet verified on a device** — see
  `docs/follow-ups/cross-privilege-suspend-ownership.md`.

### ❄️ Freezer removal (#308, #331 — for GH#310)

* All three surfaces that can drop an app from the watchlist — the Freezer screen, the Apps tab and
  the app-info sheet — now **restore first and delete the row only on success**. Previously only
  `FreezerViewModel` restored at all, and even it discarded the `Result` and reported success
  unconditionally; from the Apps tab and the sheet the row was deleted while the app stayed disabled
  or suspended, which stranded it out of reach of Unfreeze-all (`9d5eb970`, `1a5e5f13`, `445b63a3`).
* **Bulk removal counts what actually left.** A single failure shows the gateway's own words — which
  after #330 name the privilege still holding the app — and a partial run gets a new
  `removed_from_freezer_partial_failure` string, translated into all five locales. Failed apps keep
  their watchlist rows and stay selected, so a retry is one more tap (`445b63a3`).
* **The manage-sheet switch stopped planning from a stale snapshot.** It restored using flags from
  `allInstalledApps`, which the suspend-freeze path never patches, so switching an app on and
  straight back off planned nothing, made zero privileged calls, deleted the row and left the app
  suspended. Both watchlist-removal paths now call `forceUnfreeze` unconditionally (`445b63a3`).
* **Crash fix:** a throw out of the Room delete or `ShortcutManagerCompat` took the process — `:app`
  installs no `CoroutineExceptionHandler`, so an escaping throw was not a toast. Both branches of
  `toggleManaged` are now guarded, with `CancellationException` rethrown first (`41eda1d9`).

### 🤖 Privilege UX (#333)

* **Refresh never re-probed privileges.** `PrivilegeManager.refresh()` had **zero call sites**; the
  Privilege Check dialog's confirm button called `loadDashboardData()`, which reloads the app list
  and nothing else. It looked Dhizuku-only because `PrivilegeManager` owns Shizuku's binder and
  permission listeners and self-heals — root and Dhizuku publish no such callback, so a grant made
  while Thor was running stayed invisible until the process was killed. When one privilege mode
  alone looks broken, the other modes may be hiding a shared defect (`86dd7afa`).
* **Dhizuku re-binds from the probe.** `DhizukuHelper` latched the `Dhizuku.init` result from
  `ThorApplication.onCreate` — on a first run, before the user had authorised anything — so every
  later probe asked an unbound client whether it had permission. Dhizuku 2.6.0 publishes no
  connection callback, so the retry is *pulled* from `probeDhizuku`, which keeps a successful bind
  and re-reads the grant each time so a revocation shows up at once (`86dd7afa`, 7 new tests).
* `am get-current-user` is denied to the device-owner identity (no `INTERACT_ACROSS_USERS`), which
  killed every `--user`-scoped Dhizuku command before it ran. Replaced with the in-process
  `thorUserId`, device-proven to resolve to 0 (`86dd7afa`); moved to
  `data/source/local/ThorUser.kt` so the Dhizuku package does not depend on the Shizuku one
  (`0bbf9e96`).
* The Privilege Check dialog now **names Dhizuku**, in all five locales — `privilege_check_desc` said
  "Root or Shizuku" while the warning string beside it already listed three (`86dd7afa`).
* Verified on `emulator-5558` (Android 17, Dhizuku device owner). Rung 2's hardware answer, which
  #332 had left open: `pm uninstall -k --user 0 com.android.egg` →
  `Failure [only root can delete system app for a particular user]`, package untouched
  (`b7eda9c0`).

### 💜 Support (#311)

* GitHub Sponsors is offered in the Support Developer sheet, on both the FOSS and store flavours
  (`b150c151`, `4b197ced`).

## 🌐 Project: the site, the stores, the build

* **Thor has a website** — <https://thor.trinadhthatakula.com>, an Astro static site under `web/`
  (#313, #319). Deployment went through three iterations before it worked: a GitHub Actions job via
  the Vercel CLI (#321), a correction pass on those instructions (#322), then the discovery that the
  project had been imported from the Vercel portal and therefore deploys from **Vercel's own Git
  integration** — blocked all along by `git.deploymentEnabled: false` committed in both
  `vercel.json` files (#327, #328). Branch model: **`master` publishes, `dev` stages** (#323).
* **Play uploads moved up one track** — the `master` lane now goes to Closed testing and the
  `production` lane to Open testing, with a hardened track guard (#320, `977bb95a`, `53752885`).
* **F-Droid preparation** (#309): a fresh clone with no signing keys can build a release APK again,
  an F-Droid submission guide was added, and the changelog directory gets a "What's New" entry for
  the first time since v1.60.0 (`9a9e4426`, `6035f373`). Thor is **not** on F-Droid yet and cannot be
  submitted until a stable release lands.
* **`:vm-runtime` aligned to Java 21** (#307), which stops the IDE rewriting `.idea/compiler.xml` on
  every sync — one project gets one language level, so the lowest module wins.
* Documentation: the cold-start contention measurements (#305, #306), a stale freeze-mechanic
  comment corrected (#318), root freeze comments (#315), and `docs/site-content/` excluded from git
  (#312).
* Dependabot: GitHub Actions and npm bumps (#304, #317, #324, #325).
* **Unit tests: 376 → 497**, 0 failures. Lint stays an enforced gate.

## 🛠 Commits Log (`v1.93.1-dev-103...HEAD`)

**Merged pull requests**

* `38f6effa` — #333 Dhizuku user id, re-init and Refresh
* `c997124e` — #332 Dhizuku freeze rungs
* `a78b2255` — #331 freezer bulk removal failures
* `9f487d3a` — #330 suspend ownership readback
* `2aaa45f3` — #329 installed-apps visibility on Chinese-market ROMs
* `8035db3e` — #328 web: system-environment behaviour confirmed
* `17008c7c` — #327 Vercel Git auto-deploy
* `42630d54` — #326 dev/master sync
* `61ca876b` — #325 Dependabot: GitHub Actions
* `ff7681e3` — #324 Dependabot: npm (web)
* `0f255e21` — #323 web on master
* `7dca86f3` — #322 web deploy corrections
* `23bad00a` — #321 web deploy from Actions
* `7adb5955` — #320 Play tracks moved up one
* `1f418e95` — #319 the landing page
* `015f0831` — #318 stale freeze-mechanic doc
* `7bd109e9` — #317 Dependabot: GitHub Actions
* `99eb889b` — #315 root freeze comments
* `d8137191` — #314 system-app disable chain
* `bfa6df75` — #313 landing page design spec
* `ddebf315` — #312 ignore site-content drafts
* `4b197ced` — #311 GitHub Sponsors in-app
* `daaea68e` — #309 F-Droid preparation
* `c8c0502a` — #308 freezer removal restores the app
* `8c34824e` — #307 `:vm-runtime` JVM target 21
* `ded1039a` — #306 cold-start contention
* `1b60fbeb` — #305 cold-start config 1 measured
* `ec49853e` — #304 Dependabot: GitHub Actions

**Key commits**

* `b59c1f64` fix(freezer): freeze system apps by disabling them, not by uninstalling
* `cf739fc0` fix(freezer): keep app data on the uninstall fallback with `pm uninstall -k`
* `6ebdaa17` fix(freezer): gate the destructive freeze on refusal, not on Android 16
* `4524387d` fix(freezer): correct the `-k` permission claim and record the API 37 limit
* `f48c2874` fix(extensions): route extension freezes through the FreezeTier gate
* `af37e1f4` fix(freezer): freeze preinstalled apps by disabling, not by uninstalling (Dhizuku)
* `86dd7afa` / `0bbf9e96` fix(privilege): make Dhizuku usable on device — user id, re-init, Refresh
* `3124b93b` fix(suspend): stop reporting success when an unsuspend removed nothing
* `9d5eb970` / `1a5e5f13` fix(freezer): removing an app from the watchlist always restores it
* `445b63a3` fix(freezer): stop bulk removal reporting a success it never checked
* `41eda1d9` fix(freezer): guard `toggleManaged`'s durable steps against a throw
* `6c010067` / `1128ed84` fix(apps): keep the app list intact when a ROM revokes package visibility
* `b150c151` feat(support): offer GitHub Sponsors alongside Patreon and PayPal
* `9a9e4426` chore(build): let a keystore-less clone build a release, and add the F-Droid guide
* `977bb95a` / `53752885` chore(ci): move both Play uploads up one track

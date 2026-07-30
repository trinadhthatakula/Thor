# Thor v1.93.1 Release Notes

A **feature and reliability** release on top of v1.93.0. Four things Thor has never had before —
**freeze profiles**, **bulk export**, **`.xapk` export** and **filter-by-permission** — plus the
freezer's Quick Settings tile, pinned shortcuts and bulk engine substantially reworked, the app-info
sheet unified into a single surface, and a batch of security and correctness fixes that includes an
**escape hatch for an app lock that could previously lock you out permanently**.

> **Not** in this release, despite what an earlier draft of these notes said: **app backup**.
> Exporting installable bundles is not a backup — no app *data* is captured, and there is no restore
> path. #51 stays open, and the word is deliberately absent below.

> 215 commits and 21 merged pull requests since **v1.93.0**.

---

## ✨ Highlights

* 🧊 **Freeze Profiles** — named groups of apps you can freeze or unfreeze in one tap (#295, closes #55a).
* 💾 **Bulk export** — save a whole selection of apps to a folder in one run (#293).
* 📦 **`.xapk` export** — the third bundle format, with a picker (#293, completes #164).
* 🔎 **Filter the app list by permission** (#294, closes #285).
* 📱 **One unified app-info sheet** — the tabbed details moved inside it (#288).
* 🧊 **Freezer tile & shortcut rework** — real state, honest results, no stuck spinner (#284, #286, #287).
* 🔒 **App lock can no longer lock you out**, and won't arm where it could never work (#292).
* 🔐 **Cloud backup and device transfer now exclude Thor's cache and internal state** (#290, #299).

---

## What's Changed

### 🧊 Freeze Profiles (#295 — closes #55a)
Named groups of apps, frozen or unfrozen as a set, saved in two new Room tables via
`@AutoMigration(5→6)` (new tables only, schema export committed).
* **The feature**: `freeze_profiles` + `freeze_profile_apps`, a `FreezeProfileRepository`, a profiles
  sheet with per-row freeze / unfreeze / edit / delete, an editor reusing the watchlist's app picker,
  and a *"save this selection as a profile"* entry in the multi-select toolbox (`f2a19d87`).
* **Profiles route through `BulkFreezeRunner`, deliberately** — that is what earns them the
  `FreezeTier.BLOCKED` gate for free, rather than reimplementing a freeze loop that could bypass it.
  The editor also warns at *add* time, because a profile is a standing instruction later runs act on
  with no UI in front of them.
* **The runner's coalescing key had to be fixed first**: keyed on `BulkOp` alone, *"freeze profile A"*
  then *"freeze profile B"* returned the first `Deferred` and silently never froze B. The key is now
  `BulkRequest(op, scope)` — same op / different scope serializes, a different op still replaces
  (`81599c46`, `a8cf5ab7`).
* **`FreezerBridgeProvider` now checks the watchlist ∪ every profile's membership**, so an app frozen
  only by a profile isn't a dead launcher tap.
* **Every in-flight request is published, not just the newest** — a watchlist freeze with a profile
  freeze queued behind it used to make the QS tile paint idle mid-freeze (`96129019`).
* **A failed bulk run now says so** instead of reporting "nothing to do" (`a9ef0976`), and the
  profiles flow recovers from a transient Room failure instead of staying empty (`8d9813ec`).

### 💾 Bulk Export (#293)
Export stops being one-app-at-a-time.
* Multi-select → export **every selected app** to your chosen folder in a single run, with a
  cancellable progress bar, a process-lifetime runner that survives leaving the screen, and a
  written index of what was produced (`0ef42564`). The index carries a `schemaVersion` with a pinned
  forward-compatibility test, so a later format can add fields without invalidating a folder written
  today.
* **A bundle missing an APK is never reported as a successful export** (`1cbbc923`) — in a feature
  whose whole job is producing files you'll rely on later, the worst failure is the one that claims
  success.
* Names, destination and staging are decided **once per run** rather than per app (`ebe53367`), a
  bundle is named after the app rather than after the word `"null"` (`c79bcbda`), and the index name
  is stamped to the millisecond so two runs in the same second can't collide (`8aae224a`).
* ⚠️ **This is not a backup, and is deliberately not described as one.** It captures installable
  bundles, **not app data**, and there is **no restore path** — `RestoreRequest` exists as a domain
  model but is wired to no UI. #51 ("App + data backup") stays **open**; the data half needs root and
  a restore flow, and neither is in this release.

### 📦 `.xapk` Export (#293 — completes #164)
* `.xapk` joins `.apk` and `.apks` as an export format, with a format picker in the export sheet
  (`77bfc027`). `ApksMetadataGenerator` already produced the XAPK `manifest.json` for the share path,
  so this was the last missing writer.
* **A split renamed to avoid a filename collision keeps its real split id** — the entry name comes
  from the staged leaf, the id from the source leaf, so a second `base.apk` staged as `base_2.apk`
  still declares `id: "base"` and installs correctly.
* ⚠️ **#164 should be closed *with its scope stated***: three of its four outputs shipped; the fourth,
  a raw split *folder*, is **declined, not built**. Closing it silently would read as shipped.

### 🔎 Filter the App List by Permission (#294 — closes #285)
* Filter chips for runtime permission **groups** — Camera, Microphone, Location and the rest — built
  from a single `getInstalledPackages(GET_PERMISSIONS)` sweep held in memory while the filter is
  selected (`81f4215d`). **No Room schema change**: the index is invalidated by any install,
  uninstall or update, so persisting it would buy a migration and a staleness bug for nothing.
* **Chip labels come from the platform** via `PermissionGroupInfo.loadLabel`, so they are already
  translated into every locale Android supports and read exactly like the permission dialogs users
  have seen — instead of Thor shipping its own five translations of "Camera".
* **The permission → group table had to ship inside Thor** (`c78b2a92`, `5c57ad6b`). Since Android 10
  the framework manifest declares every dangerous platform permission with
  `permissionGroup="UNDEFINED"` and the real mapping lives inside PermissionController — verified on
  an API 37 emulator, where `pm list permissions -g -d` shows CAMERA, LOCATION and CONTACTS all
  empty. Reading `PermissionInfo.group` — the obvious implementation, and the one this feature was
  first built with — produces **no Camera chip, no Microphone chip and no Location chip** on any
  modern device. Thor asks the device first and falls back to its own table, so custom permissions
  declared by apps still resolve through `PermissionInfo.group`, where that field is still honest.
* The chips no longer blame the device for a slow first load (`21b403a9`).

### 📱 Unified App-Info Sheet (#288)
* The tabbed details **now live inside the info sheet** (`5ce7896d`), the detailed-view switch is
  retired (`c60d1f5e`), and the sheet can open at the partial detent (`1105c91f`).
* **The sheet owns its own dismissal** (`1bc7bc46`, `5272094c`) — hosts must not clear the selection
  in `onAppAction`, which is what used to close it out from under an in-flight action.
* Shared plumbing: one action row for both the sheet and the details screen (`9d0e9988`), the details
  screen split into two reusable pieces (`9046740e`), and **one** risk dialog keyed off `FreezeTier`
  instead of three near-copies (`2f41f9aa`).
* Watchlist adds are gated on `FreezeTier` **on every surface** (`79cccaa8`) — the gate applies to
  *"put this on the watchlist"*, and deliberately **not** to *"it's already frozen, track it?"*.

### 🧊 Freezer Tile, Shortcuts & Bulk Engine Rework (#284, #286, #287)
The largest single body of work in this release: the QS tile, the pinned launcher shortcuts and the
bulk freeze engine were rebuilt around one runner and one state reader.
* **New core**: `BulkFreezeRunner` (`bb7c66d0`) and `AppFreezeStateReader` (`4c8db239`), plus a pure
  tile state machine (`172bf778`) and pure bulk-freeze models and candidate filter (`33a0a870`) —
  extracted precisely so they could be tested.
* **The tile tells the truth**: state derives from real per-app state rather than a cached guess
  (`15365111`), the published state is freeze-specific (`3f95fa9d`), a stale freeze result is cleared
  when an unfreeze completes (`5f1bc052`), and the result re-trigger loop and cold-tap drop are gone
  (`9e252b0d`). A tap is handed to the runner **before** awaiting the privilege probe, so a cold tile
  no longer does nothing (`e48125be`, `5cefb34d`).
* **Bulk correctness**: conflicting ops serialize instead of racing (`f2e2a40b`, `3ccd6d3d`), results
  are tagged with the op that produced them and worker handoff is bounded (`e2927727`), the post-run
  sweep is bounded and the shortcut icon refresh deduped (`96c27488`), and completion no longer
  depends on the notifier having fired (`8b797a72`).
* **Blocked apps stay blocked**: the tile and Freeze-all no longer bypass the unsafe-app block
  (`58cd5315`), and an app whose `FreezeTier` cannot be resolved now **fails closed** (`21643400`).
* **Frozen system apps are visible again**: a system app frozen via `pm uninstall --user` needs
  `MATCH_UNINSTALLED_PACKAGES` to be readable at all (`c4dedf04`), and pinned icons are rebuilt from
  the runner rather than from one caller (`06c0315d`).
* Bulk shortcuts route through the runner (`15d0a59d`) and the shortcut trampoline reports the run's
  outcome (`0f7e85bc`); results are delivered as a notification (`057211de`, `0824f142`) because a
  `TileService` cannot render a Toast.

### 🔒 App Lock — an Escape Hatch, and a Refusal That Tells the Truth (#292)
* **The lockout was permanent.** Enough failed biometric attempts hard-locks the sensor at the OS
  level; with Thor's app lock on and no fallback, the app became unopenable until the device was
  restarted or the biometric re-enrolled. There is now a way back in (`99a33f22`), true on **Android
  9 and 10** as well as newer releases (`972dec25`).
* **Thor won't arm a lock this device can never open** (`4669b7c0`). On a device with no enrollable
  biometric the setting is refused with a toast rather than switched on, and if a restored backup
  brings the setting back, Thor turns it off itself.
* **The refusal names a fix that actually works** (`4a57fe4f`) — three distinct messages, because a
  refusal that sends you to a Settings page which cannot help is worse than no message.

### 🔐 Android Cloud Backup & Privacy (#290, #299)
*This is Android's own Auto Backup / device transfer, not a Thor feature.*
* **Cloud backup and device transfer no longer carry Thor's internals** (`fdbeff69`). The AGP template
  rules were unedited, so the Room metadata cache and the icon cache were going up with everything
  else. Now only the user's settings are backed up — verified on-device over a local transport, and
  confirmed present in the shipped release APK.
* **The "disabled apps" prompt flag is excluded from backup** (`4ee45bf2`, #299). Restoring a backup
  used to carry `has_shown_disabled_apps_prompt = true` to the new device, suppressing the watchlist
  recovery prompt on a device that had never shown it. It now lives in a second, un-backed-up
  DataStore (`thor_local_state`) — both backup rulesets allowlist a *filename*, not the `datastore/`
  directory, so this needed **no ruleset change and no `BackupAgent`**.

### 🛡️ Privilege & Gateway Fixes (#290)
* **`pm grant` targeted the wrong Android user.** All three gateways defaulted to user 0, so on
  work-profile and multi-user devices the permission was granted to the wrong user. Each gateway now
  derives the id from the package's own uid (`ee50867e`). Owner-verified on device.
* **Single-app freezes are gated on the tier, not on a hidden button** (`53737ff4`) — previously the
  only thing stopping a `BLOCKED` app being frozen was a dialog that rendered no confirm button.
  Owner-verified on device.
* **Extension `toggle` saw only half a freeze** (`5f260e98`): `anyFrozen` missed the
  `pm uninstall --user` half, so an all-system-app target read as *not frozen* and toggle re-froze it
  instead of thawing.
* **One root probe per cold start** (`c86aa565`), plus debug-only instrumentation to measure it.

### 📥 Installer & App List (#276, #277, #278)
* **Downgrade detection is gated on a *known* version code** (`ee8c40bb`, `6e974ca2`, `9f215a92`) —
  an unknown code is now `null` rather than `0`, so an app whose version couldn't be read is no
  longer mistaken for a downgrade.
* **Huge version codes are no longer truncated** (`7f1e5e18`): `longVersionCode` was being narrowed to
  `Int`. Rows already corrupted in the Room cache are repaired on upgrade (`feb2bc90`).
* **Pull-to-refresh** stays readable (`abe174ca`), its settle delay scales with the system Animation
  Intensity setting (`6fc3b768`), and the package scan honours cancellation (`4c164d6e`).

### 🔔 Notifications (#284, #290)
* `POST_NOTIFICATIONS` can be requested from the Settings permissions section (`14cf72d0`), the row
  is no longer a dead end once permanently denied (`7919e370`), and it renders correctly below
  API 33 (`82c245b3`).

### 🏬 Shizu Store Listing (#280, #282, #283)
* The store listing is translated into **Arabic, Spanish, French, Hindi and Chinese** (`e7f08593`).
* The weekly audit no longer fails when the schema host blocks the runner (`6487d83d`), image URLs
  are retried once before being called dead (`68604813`), and the false *"100% Kotlin"* claim was
  dropped from every listing (`09f26ead`).
* Publishing from `master` now happens **only when the version code actually moved** (`651669fc`).

### 🧪 Tests & Tooling (#290, #296, #291, #298)
* **The unit suite went 104 → 209 tests.** `kotlinx-coroutines-test` and turbine were added, and
  `AppListViewModel` got 8 behaviour tests on virtual time — which cost four narrow domain ports
  (`PrivilegeStateProvider`, `StorageStatsProvider`, `UsageAccessGate`, `AppShortcutController`),
  because the view model was not *constructible* in a JVM test, never mind untested (`028e2eab`).
* **Android Lint is now an enforced CI gate** — `continue-on-error` is gone, `warningsAsErrors` is
  on, and `app/lint.xml` records the three deliberate exemptions. Qodana was dropped (`53c908fd`).
* **An upstream AGP alpha can no longer redden every open PR at once** (`e139b654`, `d2ad9a1c`):
  lint's `AndroidGradlePluginVersion` check ships at severity `ERROR`, so a newly published alpha
  failed `lintStoreRelease` on a line no branch had touched. Both halves landed — take the newest
  alpha *and* override the severity, since the version alone would break again on the next one.
* **Bundler is tracked by Dependabot** and two gem advisories closed (`18d4efb3`).

---

## 🛠 Build & Dependencies
* **Version**: bumped to **1.93.1 (1931)** in `gradle.properties`.
* **Room**: schema **5 → 6** via `@AutoMigration` (new tables only, nothing back-filled); schema
  export committed.
* **Security**: `faraday` → 1.10.6 and `excon` → 1.6.0 via `fastlane` 2.236.1 → 2.237.0, closing
  CVE-2026-54297 (HIGH) and CVE-2026-54171 (MEDIUM). Both are release-tooling dependencies; neither
  ships inside the APK.
* **Dependency updates** (Dependabot): Material3, koin-plugin 1.1.0, navigation3 1.1.5, and AGP
  9.4.0-alpha07 (`0b047fcd`, `e10aebef`).

---

## 🛠 Commits Log (`v1.93.0...HEAD`)

**Merged pull requests**
* `2a503959` — #295 freeze profiles (#55a)
* `4e35e0b4` — #294 filter the app list by permission (#285)
* `1f35400b` — #299 keep the watchlist prompt flag off the backup
* `5eb19981` — #300 shortcut match flags for frozen system apps
* `e7748643` — #293 `.xapk` export (#164) + bulk multi-app export
* `8680325b` — #292 biometric hard-lockout escape hatch
* `55ea76b3` — #298 AGP version lint gate
* `0b047fcd` — #297 Dependabot maven group
* `1f99692a` — #291 track bundler with Dependabot; close two gem advisories
* `3d0c70a1` — #290 tier-0 backlog batch (8 deferred items)
* `d5bc0596` — #296 `AppListViewModel` behaviour tests + four domain ports
* `cf58be11` — #289 roadmap & docs refresh
* `ff81bd98` — #288 unified app-info sheet
* `bec4d5ce` — #287 bulk freeze blocked-tier gate
* `bfa9174f` — #286 tile pinned-icon refresh
* `8a36ae45` — #284 freezer tile rework
* `e8d6ced4` — #280 Shizu CoreFetch store manifest
* `1fe56bf9` — #278 app-list refresh timing
* `5799964b` — #277 `longVersionCode` truncation fix
* `67228429` — #276 installer downgrade detection
* `e10aebef` — #275 Dependabot maven group

**Key commits**
* `f2a19d87` feat(freezer): named freeze profiles (#55a)
* `81f4215d` feat(app-list): filter the app list by permission (#285)
* `0ef42564` feat: export a whole selection of apps in one run, with a written index
* `77bfc027` feat(export): offer `.xapk` as an export format (#164)
* `5ce7896d` / `c60d1f5e` / `1105c91f` feat(app-info): tabbed details in the sheet; retire the switch
* `bb7c66d0` feat(freezer): add `BulkFreezeRunner` · `4c8db239` add `AppFreezeStateReader`
* `172bf778` feat(tile): pure QS tile state machine · `33a0a870` pure bulk-freeze models
* `99a33f22` / `972dec25` / `4669b7c0` / `4a57fe4f` fix(security): app-lock escape hatch + honest refusal
* `fdbeff69` fix(backup): back up the user's settings, and nothing else
* `4ee45bf2` fix(prefs): keep the watchlist prompt flag off the backup
* `75647098` / `855b8d26` fix(shortcuts): match flags for frozen system apps
* `53737ff4` feat(freezer): gate single-app freezes on the tier, not on a hidden button
* `ee50867e` fix(gateway): target the package's own Android user when granting permissions
* `5f260e98` fix(extensions): see both halves of a freeze when reporting frozen state
* `c78b2a92` / `5c57ad6b` fix(permissions): ask the device before the table
* `1cbbc923` fix(export): never report a bundle that is missing an APK as a successful export
* `a9ef0976` fix(freezer): stop reporting a failed bulk run as "nothing to do"
* `81599c46` / `a8cf5ab7` / `96129019` fix(freezer): scoped coalescing + publish every in-flight request
* `58cd5315` / `21643400` fix(freezer): tier gate on every bulk path; fail closed when unresolvable
* `c4dedf04` fix(freezer): read frozen system apps that were uninstalled for the user
* `7f1e5e18` / `feb2bc90` fix(apps): stop truncating `longVersionCode`; repair the cache
* `ee8c40bb` fix(installer): gate downgrade detection on a known versionCode
* `6fc3b768` / `abe174ca` / `4c164d6e` fix(appList): refresh timing, indicator floor, cancellation
* `028e2eab` refactor(app-list): domain ports for the Android-bound collaborators
* `53c908fd` build: enforce lint, unblock coroutine tests, drop Qodana
* `e139b654` / `d2ad9a1c` build: stop an upstream AGP alpha from failing every open PR
* `18d4efb3` build(ci): close both open gem advisories and track bundler with Dependabot
* `c86aa565` perf(privilege): one root probe per cold start, and a way to measure it
* `e7f08593` feat(store): translate the listing into ar, es, fr, hi and zh
* `651669fc` ci: publish from master only when the version code actually moved

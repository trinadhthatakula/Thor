# Thor v1.94.0 Release Notes

The first stable since **v1.93.0**, consolidating everything that shipped through the
`v1.93.1`, `v1.93.2` and `v1.93.3` pre-release builds — **67 pull requests**.

Two threads run through it. The first is **new ground**: freeze profiles, bulk backup and
`.xapk` export, permission filtering, and a support catalogue that comes from Play instead of
from the binary. The second is quieter and, for anyone who relies on Thor to actually do what
it says, more important: **a long sweep making privileged actions report the truth.** `pm` and
`am` return exit code 0 for work they did not do. Thor used to believe them. It no longer does
— it re-reads the state it asked for, and tells you when the answer is no.

The same sweep found the sharpest bug in the release: **freezing a preinstalled app was
uninstalling it, and taking its data with it.**

---

## ✨ Highlights

* 🧊 **Freeze Profiles** — save named groups of apps and freeze or unfreeze the whole set in
  one tap.
* 💾 **Bulk backup and export** to a folder you choose, now including **`.xapk`** alongside
  `.apk` and `.apks`.
* 🔎 **Filter the app list by permission** — Camera, Microphone, Location and more.
* 🧊 **Freezing a preinstalled app no longer wipes its data.** Thor disables it; where it must
  fall back to removal, it keeps the data.
* ✅ **Freeze, restrict, force-stop and clear-data are judged by a readback**, not by an exit
  code that never meant success.
* 🔒 **App lock covers cold start**, and the app list no longer leaks into the Recents preview.
* 📱 **One app-info sheet**, with the full details built in — the detailed-view switch is gone.
* 🏠 **The home shortcuts pack themselves to the space they have**, and the Installer and
  Extensions tiles can be switched off in Settings.
* 💖 **Support tiers are read from Play**, so a new tier appears without an app update.

---

## What's Changed

### 🧊 Freeze Profiles (#295 — the profiles half of GH#55)

Named, saved groups of apps. Build a profile once, then freeze or unfreeze all of it from a
single control. The editor takes a bulk selection, and profiles persist in Room alongside the
watchlist.

### 💾 Bulk backup and `.xapk` export (#293 — GH#51 phase 1, and GH#164)

Export several apps in one run to a folder you pick, and export as `.xapk` alongside `.apk`
and `.apks`. Backups carry a `manifest.json` with a `schemaVersion`, so a later release can add
per-app data without invalidating a folder written by this one.

Scope, stated plainly: this is the **APK** half. Backing up an app's *data* is root-only and has
not been built. Exported bundles go through the normal share sheet, so Quick Share already moves
them phone-to-phone — GH#51 was closed by its reporter on that basis, not because the data half
landed.

### 🔎 Filter the app list by permission (#294 — for GH#285)

Filter by what apps can actually reach: Camera, Microphone, Location and the rest.

### 📱 Unified app-info sheet (#288)

App info and app details are one sheet. The "detailed view" toggle it replaces is retired
rather than hidden.

### 🏠 An adaptive home grid, with tiles you can hide (#357 — for GH#344)

The home shortcuts pack to the room they have instead of to a fixed 2×2. An odd number of tiles
leads with a full-width one rather than leaving a hole, and in the navigation rail — where a
tile has no width for its label — each takes its own row and explains itself on a long press.

**Installer** and **Extensions** each got a switch in **Settings → General**. Hiding a tile
takes away the shortcut, never the feature: the installer still handles APK intents, and
Extensions keeps its own Settings entry. The two preferences stack with the existing eligibility
rules rather than overriding them, so asking for the Extensions tile without a privilege mode
still shows nothing — and hiding both with no privilege legitimately empties the grid, which
disappears rather than leaving a gap where it used to be.

### 🧊 System-app freeze: disable first, remove second (#314, #332 — for GH#316)

The important fix in this release. Freezing a preinstalled app went down an uninstall path that
destroyed its data.

* Thor now **disables** a system app to freeze it (#314).
* Where a rung must fall back to removal, it uses `pm uninstall -k` to **keep data and cache**.
* The destructive rung is gated on an actual refusal, not on an Android version guess.
* The same defect existed on the **Dhizuku** path and was fixed there too (#332).
* Extension-initiated freezes are routed through the same `FreezeTier` gate, so an extension
  cannot take the destructive path around it.

### ❄️ Freezer reliability — tile, shortcuts, watchlist, bulk runs (#284, #286, #287, #300, #308, #331)

* The **QS tile** derives its state from real per-app freeze state instead of a cached guess
  (#284).
* **Pinned shortcut icons** are rebuilt by the runner rather than by whichever caller happened
  to trigger it (#286).
* The tile and **Freeze-all** no longer bypass the unsafe-app block (#287).
* Shortcut lookups use the flags a **frozen system app** needs, or the package reads as absent
  (#300).
* **Removing an app from the watchlist always restores it** (#308 — for GH#310), and says so
  when it cannot.
* **Bulk removal stops reporting a success it never checked** (#331).

### ✅ Privileged actions judged by a readback (#346, #347, #348)

* Actions are verified by **re-reading the state**, not by trusting an exit code (#347).
* **Clear-data waits for the observer** instead of reporting a wipe that was merely dispatched
  (#348).
* The **app-ops fallback** stops reporting a restriction it never applied.
* **Every privileged command names the user**, per that command's own AOSP default (#346) —
  work profiles and secondary users were being hit by accident.
* One unresponsive ROM no longer costs a batch **~15 seconds per app**.

### 🤖 Dhizuku, on real hardware (#333)

Device testing turned up three defects, all fixed:

* `am get-current-user` is **denied** to the Dhizuku uid, so the user id is resolved once and
  centrally instead of being asked for per command.
* The privilege dialog **names Dhizuku** in all five locales.
* **Refresh never re-probed privileges at all.** It looked Dhizuku-specific only because
  Shizuku's listeners self-heal and mask it — root was affected too.

### ⏸️ Suspend ownership (#330)

An unsuspend you don't own returns an **empty failure array**, which every caller read as
success. Thor now reads the recorded suspender and reports honestly when it cannot lift a
suspension another privilege mode applied.

### 🔒 App lock, privacy and stability (#292, #299, #339, #340)

* **App lock covers cold start**, and the app list no longer appears in the Recents preview
  (#340).
* The lock has **a way out when no prompt can succeed** (#292) — a restore onto a device with
  no enrolled biometric used to lock the user out of Settings entirely.
* The "already prompted" flag is **kept off cloud backup** (#299), so a reinstall does not
  pre-suppress the watchlist recovery prompt.
* An **unreadable settings file no longer crash-loops** the app (#339).

### 📱 App list, installer and locale (#276, #277, #278, #329, #343, #345)

* The app list **survives a ROM revoking package visibility** in the background (#329) — it was
  collapsing to a single entry on some Chinese-market ROMs.
* The installer makes **the confirmed identity and the installed bytes one set** (#343), and
  cancels a superseded parse so it reclaims its own staged copy.
* Downgrade detection is gated on a **known** `versionCode`, and the codes are surfaced (#276).
* `longVersionCode` is **no longer truncated to `Int`** (#277).
* Pull-to-refresh is no longer charged the transition settle delay (#278).
* The in-app **language picker works below API 33** (#345), and a language change reaches the
  caches that hold copies.

### 💖 Support (#311, #342, #351, #356)

* **GitHub Sponsors** alongside Patreon and PayPal (#311).
* **Ko-fi and Buy Me a Coffee are now in the app** (#356). The website and `FUNDING.yml` have
  advertised five funding routes for a while; the in-app sheet only ever offered three. All five
  now agree.
* **Support tiers are probed rather than hardcoded** (#351). Play exposes no catalogue-
  enumeration API, so Thor queries a candidate ID set and renders whatever Play answers with —
  a tier added in Play Console shows up without an app release. Prices and their ordering come
  from Play's own localized figures, so the amount shown is the amount in the buyer's currency.
* A **resume rebuilds a billing connection** the retry ladder had given up on (#342).

---

## 🌐 Project: the site, the stores, the build

* **The Thor landing page shipped** (#313, #319, #321, #322, #327, #328) — an Astro site with a
  claims gate that reads the built HTML rather than the source, so a claim cannot pass by being
  written differently than it renders. It deploys from Vercel's Git integration; the
  Actions-based path is documented and deferred.
* Both Play uploads moved **up one track** (#320): internal→alpha, alpha→beta.
* A **keystore-less clone can build a release**, with an F-Droid guide (#309).
* Website copy drafts are kept out of the repo (#312).

## 🔧 Internal

* **R8 runs on every PR** (#338), so the first minified build is not the one that ships.
* The **Tier-0/1 deferred-work batch** (#290) closed ten tracked items at once (GH#5, #11, #14,
  #16, #17, #18, #20, #21, #22, #28), with 209 tests and lint enforced.
* Named **dispatchers are injected** rather than hardcoded (#350), so the classes that use them
  are testable.
* Four build warnings fixed **without deleting the runtime guards they pointed at** (#354) —
  the elvis branches survive R8 into the shipped DEX, so removing them would have swapped a
  guard for a crash on non-compliant ROMs.
* Behaviour tests for app-list load/refresh timing, and the seam they required (#296).
* `:vm-runtime` aligned to Java 21 so the IDE stops rewriting `.idea` (#307).
* An upstream AGP alpha release no longer fails every open PR (#298); both open gem advisories
  closed and bundler tracked by Dependabot (#291).
* Documentation corrections that contradicted the shipped `-k` freeze behaviour (#315, #318),
  the follow-up index resweep (#336), the DataStore write path filed for later (#341), and the
  billing reconnection backstop named accurately (#349).
* Cold-start measurement work concluding that the slow `root=` mode is **CPU contention, not
  emulation** (#305, #306).
* Dependency and action bumps: #275, #297, #304, #317, #324, #325.

---

## 🛠 Commits Log (`v1.93.0...v1.94.0`)

* `59236b5e` — #357 adaptive home grid, and hiding the Installer and Extensions tiles
* `fbf2d54e` — #356 Ko-fi and Buy Me a Coffee in the app
* `0ea6f93b` — #354 lint warning sweep, guards preserved
* `1f31a36f` — #352 release 1.93.3
* `172583f7` — #351 probe support tiers instead of hardcoding them
* `2f46f7e5` — #350 inject named dispatchers
* `ad1a9e65` — #349 name the real billing reconnection backstop
* `585e82f3` — #348 clear-data waits for the observer
* `d7eec303` — #347 judge privileged actions by a readback
* `53a8631a` — #346 name the user on every privileged command
* `998a6c99` — #345 language picker below API 33
* `9d5b677f` — #343 confirmed identity and installed bytes as one set
* `0a8b5971` — #342 a resume rebuilds the billing connection
* `3031498f` — #341 file the DataStore write path
* `535311a9` — #340 app lock cold start, Recents leak
* `cb177c78` — #339 unreadable settings file no longer crash-loops
* `ec2066bd` — #338 R8 on every PR
* `7adbc2fb` — #336 follow-ups resweep
* `e1ab6323` — #334 release 1.93.2
* `38f6effa` — #333 Dhizuku user id, re-init, Refresh
* `c997124e` — #332 freeze preinstalled apps by disabling (Dhizuku)
* `a78b2255` — #331 bulk removal reports what it checked
* `9f487d3a` — #330 unsuspend reports honestly
* `2aaa45f3` — #329 app list survives revoked package visibility
* `8035db3e` — #328 screenshot gate confirmed enforced
* `17008c7c` — #327 let Vercel's Git integration deploy
* `61ca876b` — #325 CodeQL action bump
* `ff7681e3` — #324 `@astrojs/compiler` bump
* `7dca86f3` — #322 publish from master, stage from dev
* `23bad00a` — #321 site deploy from Actions
* `7adb5955` — #320 Play uploads up one track
* `1f418e95` — #319 the Thor landing page
* `015f0831` — #318 correct two `-k` permission-grant claims
* `7bd109e9` — #317 `actions/setup-java` bump
* `99eb889b` — #315 correct comments contradicting the `-k` fallback
* `d8137191` — #314 freeze system apps by disabling, not uninstalling
* `bfa6df75` — #313 landing page design spec
* `ddebf315` — #312 keep website copy drafts out of the repo
* `4b197ced` — #311 GitHub Sponsors in-app
* `daaea68e` — #309 F-Droid preparation, keystore-less release build
* `c8c0502a` — #308 watchlist removal always restores
* `8c34824e` — #307 `:vm-runtime` JVM target 21
* `ded1039a` — #306 cold-start: contention, not emulation
* `1b60fbeb` — #305 cold-start config 1 measured on v1.93.1
* `ec49853e` — #304 Dependabot: GitHub Actions
* `dea40e45` — #302 correct three factual errors in the v1.93.1 notes
* `b1f270e8` — #301 release 1.93.1
* `5eb19981` — #300 shortcut lookup flags for a frozen system app
* `1f35400b` — #299 watchlist prompt flag off the backup
* `55ea76b3` — #298 AGP alpha no longer fails every open PR
* `0b047fcd` — #297 maven group bump
* `d5bc0596` — #296 app-list behaviour tests and their seam
* `2a503959` — #295 named freeze profiles
* `4e35e0b4` — #294 filter the app list by permission
* `e7748643` — #293 `.xapk` export and bulk app backup
* `8680325b` — #292 app lock escape hatch
* `1f99692a` — #291 gem advisories, bundler tracking
* `3d0c70a1` — #290 Tier-0/1 deferred-work batch
* `cf58be11` — #289 re-sync roadmap and project docs
* `ff81bd98` — #288 unified app-info sheet
* `bec4d5ce` — #287 tile and Freeze-all respect the unsafe-app block
* `bfa9174f` — #286 rebuild pinned shortcut icons from the runner
* `8a36ae45` — #284 QS tile from real per-app freeze state
* `1fe56bf9` — #278 pull-to-refresh settle delay
* `5799964b` — #277 stop truncating `longVersionCode`
* `67228429` — #276 downgrade detection on a known `versionCode`
* `e10aebef` — #275 maven group bump

**Full changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.93.0...v1.94.0

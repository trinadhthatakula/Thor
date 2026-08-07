# Thor — Feature Request Roadmap

**Date:** 2026-07-02 · **Last reviewed:** 2026-08-07 (user feedback folded in; #178 and #55b reassessed)

> **The ranked build order now lives in [`follow-ups/README.md`](follow-ups/README.md).** That file
> ranks *everything* open — issue-backed or not — by impact × ease, which is what insight 5 below
> says a roadmap tracking only issues will always fail to do. This document remains the per-issue
> analysis: why each estimate is what it is.
**Purpose:** The per-issue analysis behind each estimate — what infrastructure a request can reuse, what it must build, and what its privilege tier costs it. It covers the **issue tracker only**; the ranked build order across *everything* open lives in [`follow-ups/README.md`](follow-ups/README.md), for the reason insight 5 gives.
**Method:** Each request was analyzed against the actual Thor codebase (existing infra it can reuse, layers it touches, root/Shizuku/Dhizuku feasibility), so the estimates reflect *what already exists vs. what must be built*, not guesses.

## How to read this

- **Impact** 1–5 (niche → transformative). **Effort** = complexity 1–5 + a rough solo-dev day estimate.
- **Tier / colour:** 🟢 do-first quick wins (high value, low risk) · 🟡 solid bets (scope carefully) · 🔴 defer (high effort or low demand).
- **Build strategy:** one issue per branch. Several requests are "a cheap MVP wearing an expensive costume" — ship the small high-value slice, defer/decline the rest. Which one to pick up first is answered in [`follow-ups/README.md`](follow-ups/README.md), not here.

## Status snapshot

| Issue | Feature | Status |
|-------|---------|--------|
| **#57** | Sort by size | ✅ **Done** — built as *total install size* (metric upgraded from APK size at maintainer's call); merged to `dev` (`3e8de3e`). ⚠️ the GitHub issue is still **open** — close it |
| **#164** | Export bundles | ✅ **Done as scoped** — `.xapk` shipped alongside the existing `.apk` / `.apks` writers, with a format picker in the export sheet (`BundleFormat`, `ExportBottomSheet`). ⚠️ **close it with the scope stated**: the issue's fourth output, a raw split *folder*, is declined, not built — closing silently would read as shipped. The in-thread `.xapk` promise is kept |
| **#210** | Keep-in-launcher | ✅ **Achievable slice done** — the Freeze\|Suspend mode shipped via #239 (PR #241). The accessibility-based auto-refreeze remains declined, as planned |
| **#55a** | Freeze profiles | ✅ **Shipped** — PR #295 merged 2026-07-30, released in v1.93.1. All three recon risks closed: the tier gate, the runner's coalescing key and the launcher restore gate (see below) |
| #51 | App + data backup | 🟨 **Phase 1 done** — multi-select → bulk APK/bundle backup to the export target with a `thor-backup-<stamp>.json` manifest, a cancellable progress bar and a process-lifetime runner. **Phase 2 (root data tar) not started**, and that is the half `README.md` has promised for a year |
| **#285** | Filter by permission | ✅ **Done** — merged to `dev` (#294). Scope question settled: one `getInstalledPackages` sweep, no Room change. ⚠️ the GitHub issue is still **open** — close it |
| #161 | `.apks` won't open from Samsung My Files | 🐞 **bug, not a feature** — unanswered since 2026-07-18 |
| _all others_ | | ⬜ not started |

**Shipped since this document was written but not tracked by any issue:** the Freezer QS tile
rework (PRs #284 / #286 / #287), the Shizu CoreFetch store manifest (#280), the app-list refresh
timing fix (#278), the `longVersionCode` truncation fix (#277), installer downgrade detection
(#276), the portable-installer theme fix (#273), Odin Phase 3 (#266), and the release-only suspend
R8 fix (#265). That is where the last four weeks went — the feature backlog below barely moved
because the work was elsewhere.

**In review, not yet merged:** nothing. Freeze profiles (#295/#55a) merged 2026-07-30. The permission filter
(#294/#285), the watchlist prompt flag (#299), the shortcut match flags (#300), `.xapk` export +
backup phase 1 (#293), the biometric hard-lockout escape hatch (#292), the Dependabot Bundler
ecosystem (#291), the unified app-info sheet (#288) and the previous refresh (#289) have all since
merged. While several branches were open at once they all edited this file, so this paragraph and the
ranking table were kept *byte-identical* across them — same text merges with no resolution at all,
and the alternative was re-resolving the same conflict once per merge. Worth redoing the moment a
second branch opens.

---

## Master ranking

| # | Issue | Feature | Impact | Est. time | Complexity | Tier / verdict |
|---|-------|---------|:------:|:---------:|:----------:|----------------|
| — | **#57** | Sort by (install) size | 3–4 | done | 2 | ✅ **Merged** (#230) — close the issue |
| — | **#210** | Freezer "keep-in-launcher" | 3 | done | 4 | ✅ **Slice merged** (#241) — suspend mode; rest declined |
| — | **#164b** | `.xapk` export (the remainder of #164) | 2 | done | 2 | ✅ **Merged** (#293) — the format picker ships with it; close #164 on merge |
| — | **#51 ph.1** | Bulk APK/bundle backup + manifest | **4** | done | 3 | ✅ **Merged** (#293) — phase 2 (root data) is what remains |
| — | **#55a** | Freeze **profiles** (named groups) | 3 | done | 4 | ✅ **Shipped** in v1.93.1 (#295, merged 2026-07-30) — reused Room + `BulkFreezeRunner` as predicted; the runner needed a scoped coalescing key first *(split from #55)* |
| — | **#285** | Filter app list by permission | 2–3 | done | 2 | ✅ **Merged** (#294) — mostly UI as predicted, but the group table had to be shipped by Thor (see below); close the issue |
| 1 | **#161** | `.apks` won't open from Samsung My Files | 2 | **1–2 d** | 2 | 🟢 a real bug with a named reporter and a working comparison app — cheap goodwill |
| 2 | **#51 ph.2** | App **data** backup / transfer | **4** | root-data **5–8 d** · P2P 12–20 d | 5 | 🟡 highest remaining impact, hard-gated on root — phase 3 (P2P) stays declined |
| 3 | **#130** | InstallWithOptions attribution + drill-down | 2 | **1–2 d** (label ≈0.25 d) | 2 | 🟡 label = trivial; attribution unreliable |
| 4 | **#58** | **App lock** (root/Shizuku) | 3 | **8–15 d** | 5 | 🔴 differentiator but heavy build + ongoing tax |
| 5 | **#178** | App **tagging** (+ per-app notes) | 3 | **3–5 d** | 3 | 🟡 low-risk; **demand is no longer zero** (2026-08-07) — defer on cost if at all, not on demand |
| 6 | **#209** | **VirusTotal** scanner | 2 | **4–7 d** | 3 | 🔴 network stack + user API key + privacy |
| — | **#55b** | Process manager (RAM/CPU) | 3 | 4–7 d full · **flag: unsized** | 4 | 🟡/🔴 **split again** — the flag is what was asked for and is *smaller*, but not yet costed: it still needs a privileged `ps`/`dumpsys` read, so name the API and the fallback before ranking it. The RAM/CPU stats keep every original objection *(split from #55)* |

---

## Sequencing

**🟢 Do first (~1 week):** ~~**#164b** (`.xapk` writer)~~ **merged (#293)** → ~~**#285** (permission
filter)~~ **merged (#294)** → ~~**#55a** (freeze profiles)~~ **merged (#295), v1.93.1** →
**#161** (Samsung My Files). All lean on existing infra and are low-risk — with one caveat:
~~**#285's estimate is pending scope validation.**~~ **Settled, and it was the cheap answer** — see
the **#285** row below. One `getInstalledPackages(GET_PERMISSIONS)` sweep, held in memory while the
filter is selected. **No Room schema change.** The expensive surprise was elsewhere: since API 29 the
platform no longer tells you which group a permission belongs to, so Thor ships the table itself.

**🟡 High-value bets (scope carefully):**
- **#51 backup, phase 2** — phase 1 (bundles only — `.apk`, `.apks` or `.xapk`, whichever the app's shape and the picker call for; **no app data**) shipped with `.xapk`, in the same session, exactly as this document proposed. What is left is the root-only data tar, a separate 5–8 d effort behind an explicit "requires root" state; bespoke phone-to-phone transport stays skipped (the exported file already rides the share sheet). The README's "BackUp App Data" promise is only half-kept until phase 2 lands.
- **#130** — do the *achievable slice* (the friendly installer label) and explicitly decline the parts Android won't allow.

**✅ Closed out since the last review:** #57 (shipped) and #210 (achievable slice shipped as the Freeze|Suspend mode). Neither issue has been closed on GitHub yet.

**🔴 Defer:** #58 (biggest build + robustness tax), #209 (FOSS API-key + privacy, low demand), #55b's
RAM/CPU half (fragile, niche). ~~#178 (zero demand)~~ — **reassessed 2026-08-07**: still deferrable
on its 3–5 d cost, no longer on demand.

---

## Per-feature detail

### #164 — Export to folder (APK / XAPK / APKS / split) · ✅ shipped · impact 3
- **Shipped:** the SAF `ACTION_OPEN_DOCUMENT_TREE` picker, a remembered destination (with a `Downloads/Thor` default), `DocumentFile` write plumbing, progress/failure states, and all three writers — `.apk`, `.apks`, `.xapk`. See `BundleFormat`, `ExportBottomSheet`, `AppBundleFileStoreImpl`, `AppBundleBuilderImpl`, `BundleZip`.
- **Shipped with it (#164b):** `.xapk` output, and the format picker the third option finally justified. The sheet offers two chips — the app's native container plus `.xapk` — because the third is always the wrong offer: `.apks` around a single base APK is a zip that buys nothing, and a monolithic `.apk` of a split app installs something that will not run. `autoFor()` never returns XAPK, so an export nobody touches produces what it always did.
- **Still unbuilt (deliberately):** the raw split-folder output. Thor chooses the default format from the app's shape, which is the better default.
- **Risks:** none hard. System/protected apps degrade without root (consistent with the app). OBB export stays out of scope.
- **Verify with more than a round trip:** installability is necessary but not sufficient. Export → reinstall through Thor's own installer *and* a third-party one, then also check the two failure shapes a successful install would hide: a bundle whose OBB assets are silently absent, and split contents/metadata a different reader rejects. See `docs/follow-ups/app-data-backup-and-xapk-export.md`.

### #55a — Freeze profiles · ✅ built · impact 3
- **What:** named groups of apps you can freeze/unfreeze on demand.
- **Reuses:** `FreezerRepository` + Room `freezer_apps` table, `FreezerViewModel` multi-select + batch `MultiAppAction.Freeze/UnFreeze`, `AutoMigration`. New profile tables + a small UI.
- **Shipped:** `freeze_profiles` + `freeze_profile_apps` (Room **auto-migration 5→6**, new tables only, schema export committed), `FreezeProfileRepository`, a profiles sheet with per-row freeze/unfreeze/edit/delete, an editor sheet reusing the watchlist's app picker, and a "save this selection as a profile" entry in the multi-select toolbox. 17 unit tests cover the name rule and the request identity.
- **All three recon risks are closed, and each one is why a piece looks the way it does:**
  1. **Tier gate.** Profiles route through `BulkFreezeRunner`, so `targetsFor` → `freezableCandidates(...)` applies the list-level `FreezeTier.BLOCKED` filter for free — routing *through the runner* is what earns the gate, which is the reason a profile run is not a direct freeze loop. The editor additionally warns at *add* time, because a profile is a standing instruction that later runs act on with no UI.
  2. **Coalescing key.** The job slot keyed on `BulkOp` alone would have made "freeze profile A" then "freeze profile B" return the first `Deferred` and silently never freeze B. The key is now `BulkRequest(op, scope)`: same op / different scope serializes (`join()`), different op still replaces (`cancelAndJoin()`).
  3. **Launcher restore.** `FreezerBridgeProvider` now checks the watchlist **∪** every profile's membership, so an app frozen only by a profile is not a dead launcher tap.
- **Left for the owner:** on-device verification of the two new sheets, and — as with every freezer surface — a run on a real privilege backend.

### #161 — `.apks` won't open from Samsung My Files · 🟢 · 1–2 d · impact 2 *(bug)*
- **What:** on a Galaxy S25 Ultra / One UI 8.5, tapping a `.apks` file in Samsung's stock My Files does not offer Thor, while InstallerX Revived and Universal Installer both appear. Thor *does* appear from other file managers.
- **Where to start:** `AndroidManifest.xml` (the `PortableInstallerActivity` intent-filters) — diff Thor's filters against the two apps that work. One UI's My Files is stricter about MIME type ↔ extension pairing than AOSP's picker, so the likely gap is a missing `mimeType`/`pathPattern` combination rather than anything deep.
- **Risks:** over-broad filters make Thor pop up for every zip or `application/octet-stream`, which is worse than the bug. Narrow the fix to the extensions Thor actually installs.
- **Why do it:** a named reporter with device details and a screenshot, unanswered since 2026-07-18, and a working comparison app to diff against. Cheap goodwill.

### #285 — Filter the app list by permission · ✅ built · impact 2–3
- **What:** filter or group the app list by a permission — "which apps can use the camera".
- **Reuses:** `FilterType` is an extensible sealed interface and the filter chip row already renders dynamically, so the UI is additive. Permissions are already parsed per app for the info sheet's Permissions tab.
- ~~**The one real question:** where the data comes from … this likely wants a permission index in the Room cache, populated on scan — which turns a UI feature into a schema change.~~
  **Answered: no schema change.** The fear was per-app parsing at list time. It isn't needed —
  `PackageManager.getInstalledPackages(GET_PERMISSIONS)` returns every package's declared
  permissions in a *single* call, so the whole device is indexed in one sweep. `PermissionRepository.buildPermissionIndex()`
  runs that while the filter is selected and keeps `group -> packages` in memory on the ViewModel,
  rebuilding when the app list itself changes. Caching it in Room would have been actively worse:
  the index is invalidated by any install, uninstall or update, so a persisted copy buys a migration
  and a staleness bug in exchange for nothing.
- **Three judgement calls made while building, flagged for review:**
  1. **Runtime permissions only, grouped.** Chips are permission *groups* (Camera, Microphone,
     Location…), not individual permissions. `INTERNET` matching 400 apps is not a filter, and the
     question users actually ask is about the capabilities the system itself gates behind a prompt.
     Non-dangerous and ungrouped permissions are left out of the index entirely.
  2. **Chip labels come from the platform,** via `PermissionGroupInfo.loadLabel` — so they are
     already translated into every locale Android supports and read identically to the permission
     dialogs the user has seen, instead of Thor shipping its own five translations of "Camera".
  3. **The permission → group table is hardcoded in Thor** (`PlatformPermissionGroups`), because the
     device will not answer the question. Since Android 10 the framework manifest declares *every*
     dangerous platform permission with `permissionGroup="android.permission-group.UNDEFINED"` and
     the real mapping lives inside PermissionController; verified on an API 37 emulator, where
     `pm list permissions -g -d` shows CAMERA, LOCATION, CONTACTS and the rest all **empty**. Reading
     `PermissionInfo.group` — the obvious implementation, and the one this feature shipped with
     before review — produces no Camera chip, no Microphone chip and no Location chip on any modern
     device. The *group* names are still real, so the localised labels in (2) still work. Custom
     permissions declared by apps still go through `PermissionInfo.group`, which is where that field
     is still honest.
- **Status:** opened 2026-07-28, labelled `enhancement` + `needs triage`, zero comments.

### #51 — App + data backup / transfer · 🟨 phase 1 shipped · impact 4
- **What:** back up an app's APK(s) + private data to a file; transfer app+data between phones.
- **Standing promise:** `README.md` has listed "BackUp App Data" under *Upcoming Features* for about a year. Phase 1 keeps the APK half of it; the *data* half is still outstanding.
- **Shipped (phase 1):** multi-select → **Backup** runs the whole selection through `ExportAppUseCase` (bounded to two apps staging at once) and drops a `thor-backup-<yyyyMMdd-HHmmss>.json` manifest beside the bundles, listing every app attempted, failures included. `BackupRunner` is a `@Single` on a process-lifetime scope, so a 200-app run outlives the sheet, the view model and the Activity **without a foreground service** — the same shape `BulkFreezeRunner` uses. A non-modal progress bar in the bottom bar reports it and can cancel it; a cancelled run still writes its manifest, so the folder is never undescribed. See `BackupAppsUseCase`, `BackupRunner`, `BackupIndex`, `ExportProgressBar`.
  **What "process-lifetime" does not mean:** the run survives *Thor's UI* going away, not Thor's *process*. Once the last Activity is gone the process is a background process and the OS may kill it under memory pressure or after the user swipes the app away — the run then stops mid-batch with no message. What is on disk stays on disk and there is no resume, so the recovery story is the manifest: every app the run got to is described, the rest simply are not listed, and re-running the same selection re-exports them. Surviving process death would mean a foreground service (a new permission, a mandatory FSU type on API 34+, a time cap on 35+) or `WorkManager` with a serialisable work unit; neither is in phase 1, and the confirm dialog's "keep Thor open" copy is the honest version of that limit rather than a suggestion.
- **HARD BLOCKER (data half only):** private-data backup requires **root** — Shizuku's shell uid cannot read `/data/data`, Dhizuku has no file access, and `adb backup` is dead on modern Android. Restore additionally needs correct uid/gid plus an SELinux relabel, which is where the real effort sits.
- **Phasing:** (1) ✅ bundles-only backup (no app data) to the export target; (2) root-only data tar backup/restore, gated behind an explicit "requires root" state — the manifest's `schemaVersion` and its nullable fields exist so a v2 index can name a data file without breaking a v1 reader; (3) **defer** live P2P transport (the exported file already rides the share sheet).
- **Pairs with:** `docs/follow-ups/app-data-backup-and-xapk-export.md`.

### #210 — Freezer "keep-in-launcher" · ✅ slice shipped · impact 3
- **What:** frozen apps stay visible in the launcher; tap surfaces them; auto-refreeze on dismiss.
- **Shipped:** the pragmatic suspend mode, as a Freeze\|Suspend choice in the Freezer settings sheet (#239, PR #241). `setAppSuspended` is implemented in all three gateways with `SuspendDialogInfo`, and a suspended app stays in the launcher behind the system's "app paused" dialog — which is the whole of what Android will allow.
- **Declined, as planned:** there is no public API to detect removal-from-recents (it needs Accessibility or UsageStats polling, at a battery and Play-policy cost) and no single-tap launcher intercept. The accessibility-based auto-refreeze stays out.
- **Open sub-item:** cross-privilege suspend ownership — an app suspended under root cannot be unsuspended under Shizuku and vice versa, because Android only lets the recorded suspending package lift a suspension. See `docs/follow-ups/cross-privilege-suspend-ownership.md`. That is the one thing standing between "shipped" and "closed".

### #130 — InstallWithOptions attribution + apps-per-installer · 🟡 · 1–2 d · impact 2
- **What:** (1) attribute installs from zacharee/InstallWithOptions; (2) tap an installer name → list apps it installed.
- **Reuses:** attribution + source-filtering already exist (`getInstallSourceInfo`, `FilterType.Source`, the distribution chart). Mostly a friendly-label add + tap-to-navigate wiring.
- **Caveat:** shell-based installs often record `com.android.shell`/null, so part (1) is inherently unreliable regardless of effort. The label is a ~0.25 d quick win; the drill-down nav is the bulk.

### #58 — App lock (root/Shizuku) · 🔴 · 8–15 d · impact 3
- **What:** lock apps behind biometric/credential auth, battery-friendly (event-driven).
- **Reuses:** only `BiometricPromptHandler` + the Room pattern. The whole detection + overlay pipeline is net-new.
- **Risks:** needs a Shizuku UserService bound to ActivityTaskManager (or root `am monitor`), SYSTEM_ALERT_WINDOW overlay + foreground service, boot persistence; overlay races; OEM quirks; Play-policy risk; Dhizuku can't do per-launch gating. Big build + ongoing maintenance.

### #178 — App tagging · 🟡 *(was 🔴)* · 3–5 d · impact 3
- **What:** user-defined tags to group/browse the app list. **Scope grew 2026-08-07:** a second user asked for per-app **notes**, which is the same table, the same CRUD and the same assignment UI. Build both or neither.
- **Reuses:** `FreezerEntity`/`FreezerRepository` is a near-template; `FilterType` is an extensible sealed interface; the chip row already renders dynamically. Needs a Room migration (the DB is at **v6** now, not 4) + tag CRUD/assignment UI.
- ~~**Risks:** none hard, but **zero demand**. Bundle with other app-list UX work if/when demand appears.~~
  **Re-verdicted 2026-08-07.** Two independent requests in one thread. The demand argument is spent and the instruction it carried — *bundle with app-list UX work if demand appears* — is now live rather than conditional. Nothing else about the estimate changes: still 3–5 days, still a migration, still no hard risk. **If it is deferred it must be deferred on cost**, and saying so is the point of this correction — a stale "nobody wants it" is how a cheap feature stays unbuilt for reasons that stopped being true.

### #209 — VirusTotal scanner · 🔴 · 4–7 d · impact 2
- **What:** scan APKs pre-install + installed apps via VirusTotal.
- **New:** an entire network stack the app lacks (HTTP client, `INTERNET` permission, VT v3 models, settings for the key).
- **Risks:** FOSS blocker — a bundled key is extractable/rate-banned, so users must supply their own (friction). Free-tier limits (4/min, 500/day, 32 MB cap) make full-device scans impractical → hash-lookup-only. Third-party upload privacy → must be opt-in. Low demand.

### #55b — Process manager (RAM/CPU) · 🔴 · 4–7 d · impact 3 (niche)
- **What:** live process list with RAM/CPU to spot heavy apps.
- **Risks:** Shizuku/root-only (post-Android-8 `getRunningAppProcesses` returns only self → must parse `dumpsys meminfo`/`top`/`ps`), brittle across OEM/OS versions, CPU% needs sampled polling, **Dhizuku has no shell** → dead-end. Overlaps with existing task managers. Split from #55 and deferred; the profiles half (#55a) should ship without it.
- **Split again, 2026-08-07 — and this is the useful part.** A user asked for *"options for know app running in background"*. That is a **running/not-running flag**, and every objection above is an objection to the *statistics*: `top`/`meminfo` parsing, sampled polling for CPU%, OEM drift. A coarse "is this package currently running" needs none of them. **The niche verdict was earned by the expensive half and then applied to the whole feature** — which is insight 1 (ship the MVP, not the costume) going unapplied to the very issue that first prompted it.
- ⚠️ **Smaller is not the same as costed, and this is not yet costed.** The bullet above originally called the flag *affordable* and said Dhizuku's missing shell only degrades it. Neither claim is supported: `getRunningAppProcesses` returns only the caller's own process on everything Thor targets, so even the coarse answer goes through a privileged `ps`/`dumpsys` — which puts **Dhizuku in the same dead-end it is in for the stats**. What it needs before it can be ranked: a named API, a stated privilege path, and a decided behaviour for the modes that cannot answer. It sits in band D of [`follow-ups/README.md`](follow-ups/README.md) — unrankable, not scheduled — and the stats stay deferred.
- **Verified 2026-08-07:** nothing in the tree queries running processes today. Every `isRunning` in `app/src/main/java` refers to a bulk *operation* in flight, not to a process — so there is no partial seam to build on, in either direction.

---

## Cross-cutting insights

1. **Ship the MVP, not the costume.** #51, #55, #210, #130 each hide a small high-value slice behind an ambitious full ask. The leverage is in the slice.
2. **Privilege tier gates real value.** #51 (data), #55b (process stats), #58 (launch monitoring) are root/Shizuku-only with **Dhizuku dead-ends** — each needs an explicit "requires root/Shizuku" gate; none should assume Dhizuku parity.
3. **Demand is uneven — and "zero demand" has a shelf life.** Most requests have low 👍/comment signal; strategic fit (storage/freeze management) matters more than raw demand for a power-user tool. But a deferral written as *zero demand* is a claim about the world on the day it was written, and unlike an effort estimate it can be falsified by a single thread — as #178's was on 2026-08-07. **Date a demand argument, or re-check it before you lean on it.** The issue tracker is also the wrong instrument for measuring this: four users produced twenty-three asks in one Reddit thread, more feedback than the tracker had collected in months, and none of it would ever have appeared as a 👍.
4. **Finish the last 20%.** #164 is the cautionary tale: the hard 80% (SAF plumbing, writers, progress states) shipped, and the feature still cannot be closed because a half-day writer is missing — one that was promised in the thread. The same shape threatens #210, which is functionally shipped but held open by cross-privilege suspend ownership. Closing these is cheaper than starting anything new, and it is the difference between a roadmap that reads as delivered and one that reads as stalled.
5. **Keep this table honest.** Between 2026-07-02 and 2026-07-29 nothing on this list moved, yet nine PRs shipped — the QS tile rework, the store manifest, four installer/app-list fixes, Odin Phase 3, and the app-info unification. A roadmap that only tracks issue-backed work will keep reporting "no progress" while the project moves. Re-check it at each release, not when it feels stale.

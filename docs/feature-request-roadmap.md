# Thor — Feature Request Roadmap

**Date:** 2026-07-02 · **Last reviewed:** 2026-07-30 (statuses re-checked against `origin/dev` and the live issue tracker)
**Partially re-checked:** 2026-08-07 — **only** the four rows that user feedback or the `feat/band-a`
build actually moved (161, 178, 55b, and 55a, which this file still had *in review* eight days after
it merged — PR #295 merged 2026-07-30). Every other row still carries its 2026-07-30 status and has
**not** been re-verified against the issue tracker since. Corrections are struck through in place
rather than overwritten, per this file's own convention — the superseded wording is quoted from
`docs/follow-ups/`, so deleting it would orphan those quotes.
**Purpose:** A prioritized, codebase-grounded triage of the open feature requests in the issue tracker — ranked by value-to-effort with implementation estimates and impact, to drive the "greens first" build order.
**Method:** Each request was analyzed against the actual Thor codebase (existing infra it can reuse, layers it touches, root/Shizuku/Dhizuku feasibility), so the estimates reflect *what already exists vs. what must be built*, not guesses.

## How to read this

- **Impact** 1–5 (niche → transformative). **Effort** = complexity 1–5 + a rough solo-dev day estimate.
- **Tier / colour:** 🟢 do-first quick wins (high value, low risk) · 🟡 solid bets (scope carefully) · 🔴 defer (high effort or low demand).
- **Build strategy:** one issue per branch, **greens first**. Several requests are "a cheap MVP wearing an expensive costume" — ship the small high-value slice, defer/decline the rest.

## Status snapshot

| Issue | Feature | Status |
|-------|---------|--------|
| **#57** | Sort by size | ✅ **Done** — built as *total install size* (metric upgraded from APK size at maintainer's call); merged to `dev` (`3e8de3e`). ⚠️ the GitHub issue is still **open** — close it |
| **#164** | Export bundles | ✅ **Done as scoped** — `.xapk` shipped alongside the existing `.apk` / `.apks` writers, with a format picker in the export sheet (`BundleFormat`, `ExportBottomSheet`). ⚠️ **close it with the scope stated**: the issue's fourth output, a raw split *folder*, is declined, not built — closing silently would read as shipped. The in-thread `.xapk` promise is kept |
| **#210** | Keep-in-launcher | ✅ **Achievable slice done** — the Freeze\|Suspend mode shipped via #239 (PR #241). The accessibility-based auto-refreeze remains declined, as planned |
| **#55a** | Freeze profiles | ✅ **Shipped** — ~~in review (#295)~~ merged 2026-07-30 (`2a503959`) and released in v1.93.1. All three recon risks closed: the tier gate, the runner's coalescing key and the launcher restore gate (see below). ⚠️ **It is also undiscoverable**, which r/howtomen surfaced as a feature request for something already built; band A #5 added a labelled entry point |
| #51 | App + data backup | 🟨 **Phase 1 done** — multi-select → bulk APK/bundle backup to the export target with a `thor-backup-<stamp>.json` manifest, a cancellable progress bar and a process-lifetime runner. **Phase 2 (root data tar) not started**, and that is the half `README.md` has promised for a year |
| **#285** | Filter by permission | ✅ **Done** — merged to `dev` (#294). Scope question settled: one `getInstalledPackages` sweep, no Room change. ⚠️ the GitHub issue is still **open** — close it |
| #161 | `.apks` won't open from Samsung My Files | 🐞 **bug, not a feature** — ~~unanswered since 2026-07-18~~ diagnosed 2026-07-30, answered on the issue 2026-08-03, **half fixed 2026-08-07** on `feat/band-a`. Stays open: what fixes the other half is a device diagnostic nobody has run |
| _all others_ | | ⬜ not started |

**Shipped since this document was written but not tracked by any issue:** the Freezer QS tile
rework (PRs #284 / #286 / #287), the Shizu CoreFetch store manifest (#280), the app-list refresh
timing fix (#278), the `longVersionCode` truncation fix (#277), installer downgrade detection
(#276), the portable-installer theme fix (#273), Odin Phase 3 (#266), and the release-only suspend
R8 fix (#265). That is where the last four weeks went — the feature backlog below barely moved
because the work was elsewhere.

**In review, not yet merged:** ~~freeze profiles (#295/#55a), and nothing else~~ — **nothing**. #295
merged 2026-07-30, which is what makes this paragraph the file's own worked example of insight 5
below: a status line is only true on the day it is written. The permission filter
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
| — | **#55a** | Freeze **profiles** (named groups) | 3 | done | 4 | ✅ **Merged** (#295), shipped in v1.93.1 — reused Room + `BulkFreezeRunner` as predicted; the runner needed a scoped coalescing key first *(split from #55)* |
| — | **#285** | Filter app list by permission | 2–3 | done | 2 | ✅ **Merged** (#294) — mostly UI as predicted, but the group table had to be shipped by Thor (see below); close the issue |
| 1 | **#161** | `.apks` won't open from Samsung My Files | 2 | ~~**1–2 d**~~ **half done; the rest is one diagnostic** | 2 | 🟢 **half fixed 2026-08-07** — the typeless filter's host gate is gone; whether that is enough turns on what Samsung's provider reports |
| 2 | **#51 ph.2** | App **data** backup / transfer | **4** | root-data **5–8 d** · P2P 12–20 d | 5 | 🟡 highest remaining impact, hard-gated on root — phase 3 (P2P) stays declined |
| — | **#130** | InstallWithOptions attribution + drill-down | 2 | ~~**1–2 d**~~ **label + drill-down done** | 2 | 🟢 **both achievable halves shipped 2026-08-08 (band B #21)** — the drill-down was not the bulk; attribution stays declined |
| 4 | **#58** | **App lock** (root/Shizuku) | 3 | **8–15 d** | 5 | 🔴 differentiator but heavy build + ongoing tax |
| 5 | **#178** | App **tagging** | 3 | **3–5 d** | 3 | 🔴 low-risk but ~~**zero demand**~~ — **demand is no longer zero** (2026-08-07). Defer on cost, or not at all |
| 6 | **#209** | **VirusTotal** scanner | 2 | **4–7 d** | 3 | 🔴 network stack + user API key + privacy |
| — | **#55b** | Process manager (RAM/CPU) | 3 | 4–7 d | 4 | 🔴 fragile shell parsing, Shizuku/root-only, Dhizuku dead-end *(split from #55)* |

---

## Sequencing

**🟢 Do first (~1 week):** ~~**#164b** (`.xapk` writer)~~ **merged (#293)** → ~~**#285** (permission
filter)~~ **merged (#294)** → ~~**#55a** (freeze profiles)~~ **merged (#295)** →
~~**#161** (Samsung My Files)~~ **half fixed 2026-08-07 (band A #10)** — the remaining half is a
device diagnostic, not a build. All lean on existing infra and are low-risk — with one caveat:
~~**#285's estimate is pending scope validation.**~~ **Settled, and it was the cheap answer** — see
the **#285** row below. One `getInstalledPackages(GET_PERMISSIONS)` sweep, held in memory while the
filter is selected. **No Room schema change.** The expensive surprise was elsewhere: since API 29 the
platform no longer tells you which group a permission belongs to, so Thor ships the table itself.

**🟡 High-value bets (scope carefully):**
- **#51 backup, phase 2** — phase 1 (bundles only — `.apk`, `.apks` or `.xapk`, whichever the app's shape and the picker call for; **no app data**) shipped with `.xapk`, in the same session, exactly as this document proposed. What is left is the root-only data tar, a separate 5–8 d effort behind an explicit "requires root" state; bespoke phone-to-phone transport stays skipped (the exported file already rides the share sheet). The README's "BackUp App Data" promise is only half-kept until phase 2 lands.
- ~~**#130** — do the *achievable slice* (the friendly installer label) and explicitly decline the parts Android won't allow.~~ **Done 2026-08-08 (band B #21)**, and the achievable slice was bigger than "the label": the drill-down went with it, because everything it needed already existed. Attribution remains declined.

**✅ Closed out since the last review:** #57 (shipped) and #210 (achievable slice shipped as the Freeze|Suspend mode). Neither issue has been closed on GitHub yet.

**🔴 Defer:** #58 (biggest build + robustness tax), #209 (FOSS API-key + privacy, low demand), #178
(~~zero demand~~ **cost** — the demand objection is spent, see below), #55b (fragile, niche).

---

## Per-feature detail

### #164 — Export to folder (APK / XAPK / APKS / split) · ✅ shipped · impact 3
- **Shipped:** the SAF `ACTION_OPEN_DOCUMENT_TREE` picker, a remembered destination (with a `Downloads/Thor` default), `DocumentFile` write plumbing, progress/failure states, and all three writers — `.apk`, `.apks`, `.xapk`. See `BundleFormat`, `ExportBottomSheet`, `AppBundleFileStoreImpl`, `AppBundleBuilderImpl`, `BundleZip`.
- **Shipped with it (#164b):** `.xapk` output, and the format picker the third option finally justified. The sheet offers two chips — the app's native container plus `.xapk` — because the third is always the wrong offer: `.apks` around a single base APK is a zip that buys nothing, and a monolithic `.apk` of a split app installs something that will not run. `autoFor()` never returns XAPK, so an export nobody touches produces what it always did.
- **Still unbuilt (deliberately):** the raw split-folder output. Thor chooses the default format from the app's shape, which is the better default.
- **Risks:** none hard. System/protected apps degrade without root (consistent with the app). OBB export stays out of scope.
- **Verify with more than a round trip:** installability is necessary but not sufficient. Export → reinstall through Thor's own installer *and* a third-party one, then also check the two failure shapes a successful install would hide: a bundle whose OBB assets are silently absent, and split contents/metadata a different reader rejects. See `docs/follow-ups/app-data-backup-and-xapk-export.md`.

### #55a — Freeze profiles · ✅ shipped (v1.93.1) · impact 3
- **What:** named groups of apps you can freeze/unfreeze on demand.
- **Reuses:** `FreezerRepository` + Room `freezer_apps` table, `FreezerViewModel` multi-select + batch `MultiAppAction.Freeze/UnFreeze`, `AutoMigration`. New profile tables + a small UI.
- **Shipped:** `freeze_profiles` + `freeze_profile_apps` (Room **auto-migration 5→6**, new tables only, schema export committed), `FreezeProfileRepository`, a profiles sheet with per-row freeze/unfreeze/edit/delete, an editor sheet reusing the watchlist's app picker, and a "save this selection as a profile" entry in the multi-select toolbox. 17 unit tests cover the name rule and the request identity.
- **All three recon risks are closed, and each one is why a piece looks the way it does:**
  1. **Tier gate.** Profiles route through `BulkFreezeRunner`, so `targetsFor` → `freezableCandidates(...)` applies the list-level `FreezeTier.BLOCKED` filter for free — routing *through the runner* is what earns the gate, which is the reason a profile run is not a direct freeze loop. The editor additionally warns at *add* time, because a profile is a standing instruction that later runs act on with no UI.
  2. **Coalescing key.** The job slot keyed on `BulkOp` alone would have made "freeze profile A" then "freeze profile B" return the first `Deferred` and silently never freeze B. The key is now `BulkRequest(op, scope)`: same op / different scope serializes (`join()`), different op still replaces (`cancelAndJoin()`).
  3. **Launcher restore.** `FreezerBridgeProvider` now checks the watchlist **∪** every profile's membership, so an app frozen only by a profile is not a dead launcher tap.
- **Left for the owner:** on-device verification of the two new sheets, and — as with every freezer surface — a run on a real privilege backend.
- **What shipping it did not buy: anybody finding it.** A user who read the Freezer screen closely enough to ask for per-group actions did not see that named groups already existed, because the only entry point was an unlabelled toolbar icon. Band A #5 added a labelled button and empty-state copy. Per-group *kill* and *suspend* are the genuine remainder and are still open.

### #161 — `.apks` won't open from Samsung My Files · 🟢 · half fixed · impact 2 *(bug)*
- **What:** on a Galaxy S25 Ultra / One UI 8.5, tapping a `.apks` file in Samsung's stock My Files does not offer Thor, while InstallerX Revived and Universal Installer both appear. Thor *does* appear from other file managers.
- ~~**Where to start:** … One UI's My Files is stricter about MIME type ↔ extension pairing than AOSP's picker, so the likely gap is a missing `mimeType`/`pathPattern` combination rather than anything deep.~~
  **Diagnosed 2026-07-30, and it is the opposite of that guess.** More patterns cannot help: `android:host="*"` on Thor's two wildcard filters made their path matchers a *mandatory* gate, because `IntentFilter.matchData` reads the path list only inside the `authorities != null` branch. **Two counts appear below and they measure different things:** each of the two filters carries **35 `pathPattern`s**, and the typeless one carried **45 path matchers** in total — those 35 plus the 10 `android:pathSuffix` entries beside them. Neither number is stale; `pathSuffix` is API 31+ and `pathPattern` is the 28–30 fallback, so a matcher count that omits one is only ever half the gate — and Samsung's MediaStore URI carries a row id, with the filename nowhere in the path. SAI ships ~10× Thor's pattern coverage with the same host and is absent from the same chooser; the three apps that do appear declare no host. **Pattern count correlates with nothing; the host gate correlates perfectly.**
- **Shipped 2026-08-07 (band A #10) — the narrow half only.** The *typeless* filter dropped its host and the 45 path matchers that gate had made unreachable: a filter that exists for URIs nobody can type was excluding precisely the opaque provider URI that case arrives as. The `*/*` filter keeps its host **deliberately** — removing it makes Thor a candidate for every typed file on the device, which is worse than the bug.
- **Still open, and it is a diagnostic rather than a build:** what does Samsung's provider return from `getType()`? The half that shipped only helps if the answer is *no type*. A stable type outside the 14 already declared is one `<data android:mimeType>` line; a type that varies by device needs a product call. Commands in `docs/follow-ups/161-apks-not-openable-from-file-managers.md`. ⚠️ **That doc's four-filter table predates the manifest** — read the manifest comment on the typeless filter as the current shape.
- **Risks:** over-broad filters make Thor pop up for every zip or `application/octet-stream`, which is worse than the bug. Narrow the fix to the extensions Thor actually installs. The opt-in *"show Thor when opening any file"* toggle that would license the broad half was descoped with band A #10 and is unbuilt.
- **Why do it:** a named reporter with device details and a screenshot, unanswered from 2026-07-18 until the diagnosis was posted 2026-08-03, and a working comparison app to diff against. Cheap goodwill.

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

### #130 — InstallWithOptions attribution + apps-per-installer · 🟡 · ~~1–2 d~~ · impact 2
- **What:** (1) attribute installs from zacharee/InstallWithOptions; (2) tap an installer name → list apps it installed.
- **Reuses:** attribution + source-filtering already exist (`getInstallSourceInfo`, `FilterType.Source`, the distribution chart). Mostly a friendly-label add + tap-to-navigate wiring.
- **Caveat:** shell-based installs often record `com.android.shell`/null, so part (1) is inherently unreliable regardless of effort. The label is a ~0.25 d quick win; ~~the drill-down nav is the bulk~~.
- ✅ **Part 2 shipped as band B #21, 2026-08-08, and it was not the bulk.** Everything the drill-down needed existed — `FilterType.Source`, the chip row, the persisted selection, the Home→Apps switch — so it came to a click handler on the legend row, carrying the chart's User/System selection across so the tap cannot land on a list that hides its own target. The friendly label shipped with it, resolving **curated → `PackageManager` label → raw package id**; the curated tier stays at exactly the entries that already existed, because letting the package manager answer everything else is what makes Aurora, Obtainium and the next one work with no code change.
- ⚠️ **The row missed a live defect in the thing it was about.** `AppDistributionChart` keyed its buckets on `substringAfterLast(".").uppercase()`, so `com.aurora.store` was drawn as "STORE" and **any two installers sharing a last segment were silently added into one bar** — a wrong number, not just a bad label. Buckets now key on the package id.
- **Part 1 (attribution) stays declined**, on the caveat above, unchanged. Evidence: `docs/follow-ups/reddit-howtomen-feedback.md`.

### #58 — App lock (root/Shizuku) · 🔴 · 8–15 d · impact 3
- **What:** lock apps behind biometric/credential auth, battery-friendly (event-driven).
- **Reuses:** only `BiometricPromptHandler` + the Room pattern. The whole detection + overlay pipeline is net-new.
- **Risks:** needs a Shizuku UserService bound to ActivityTaskManager (or root `am monitor`), SYSTEM_ALERT_WINDOW overlay + foreground service, boot persistence; overlay races; OEM quirks; Play-policy risk; Dhizuku can't do per-launch gating. Big build + ongoing maintenance.

### #178 — App tagging · 🔴 · 3–5 d · impact 3
- **What:** user-defined tags to group/browse the app list.
- **Reuses:** `FreezerEntity`/`FreezerRepository` is a near-template; `FilterType` is an extensible sealed interface; the chip row already renders dynamically. Needs a Room 4→5 migration + tag CRUD/assignment UI.
- ~~**Risks:** none hard, but **zero demand**. Bundle with other app-list UX work if/when demand appears.~~
  **Risks: none hard, and the demand objection is spent.** On 2026-08-07 two users on one r/howtomen thread asked for it unprompted — one for tags, one for per-app **notes**, which is the same Room migration and the same assignment UI. *"Bundle with other app-list UX work if/when demand appears"* is discharged: demand appeared, and the app-list UX work it was meant to ride along with shipped as band A. What survives is the **cost** — 3–5 days and a schema migration — so this may still be deferred, but it has to be deferred on that and not on demand. Evidence: `docs/follow-ups/reddit-howtomen-feedback.md`.
- **Build them together or neither.** Tags and notes are one migration; doing them separately buys two migrations for one feature's worth of value.

### #209 — VirusTotal scanner · 🔴 · 4–7 d · impact 2
- **What:** scan APKs pre-install + installed apps via VirusTotal.
- **New:** an entire network stack the app lacks (HTTP client, `INTERNET` permission, VT v3 models, settings for the key).
- **Risks:** FOSS blocker — a bundled key is extractable/rate-banned, so users must supply their own (friction). Free-tier limits (4/min, 500/day, 32 MB cap) make full-device scans impractical → hash-lookup-only. Third-party upload privacy → must be opt-in. Low demand.

### #55b — Process manager (RAM/CPU) · 🔴 · 4–7 d · impact 3 (niche)
- **What:** live process list with RAM/CPU to spot heavy apps.
- **Risks:** Shizuku/root-only (~~post-Android-8 `getRunningAppProcesses` returns only self~~ — see the correction below — → must parse `dumpsys meminfo`/`top`/`ps`), brittle across OEM/OS versions, CPU% needs sampled polling, **Dhizuku has no shell** → dead-end. Overlaps with existing task managers. Split from #55 and deferred; the profiles half (#55a) shipped without it.
- **Correction, 2026-08-07 — right conclusion, wrong API and wrong version.** *"Caller-only since Android 8"* is `getRunningServices`. `getRunningAppProcesses` is a different method with a different history: it has been progressively restricted **since Android 5.1** and is privilege-filtered, so an ordinary app does not get other packages back from it. Nothing downstream moves — a privileged `ps`/`dumpsys` is still required and Dhizuku is still a dead-end — but the sentence as written would send someone to check the wrong API against the wrong release.
- **Demand is corroborated, and the ask is smaller than the row.** A user asked for *"options for know app running in background"* — a running/not-running **flag**, not RAM or CPU figures. That drops the `top`/`meminfo` parsing, the sampled polling and most of the OEM drift, which are objections to the *stats*, not to the flag. ⚠️ **Smaller is not costed:** the flag still needs a named API, a privilege path and a decided fallback for the modes that cannot answer, because the privilege filtering above applies to it too. Until those three exist it stays unsized rather than cheap.

---

## Cross-cutting insights

1. **Ship the MVP, not the costume.** #51, #55, #210, #130 each hide a small high-value slice behind an ambitious full ask. The leverage is in the slice.
2. **Privilege tier gates real value.** #51 (data), #55b (process stats), #58 (launch monitoring) are root/Shizuku-only with **Dhizuku dead-ends** — each needs an explicit "requires root/Shizuku" gate; none should assume Dhizuku parity.
3. **Demand is uneven — and "zero demand" has a shelf life.** Most requests have low 👍/comment signal; strategic fit (storage/freeze management) matters more than raw demand for a power-user tool. ~~Zero-demand items (#178, #209) should wait.~~ On 2026-08-07 a single Reddit thread produced demand for **#178** (twice) and **#55b**, none of which the issue tracker had shown, because the people asking never opened an issue. A tracker measures who files, not who wants. Re-read a deferral whose only stated reason is demand before quoting it; **#209** still has none.
4. **Finish the last 20%.** #164 is the cautionary tale: the hard 80% (SAF plumbing, writers, progress states) shipped, and the feature still cannot be closed because a half-day writer is missing — one that was promised in the thread. The same shape threatens #210, which is functionally shipped but held open by cross-privilege suspend ownership. Closing these is cheaper than starting anything new, and it is the difference between a roadmap that reads as delivered and one that reads as stalled.
5. **Keep this table honest.** Between 2026-07-02 and 2026-07-29 nothing on this list moved, yet nine PRs shipped — the QS tile rework, the store manifest, four installer/app-list fixes, Odin Phase 3, and the app-info unification. A roadmap that only tracks issue-backed work will keep reporting "no progress" while the project moves. Re-check it at each release, not when it feels stale.

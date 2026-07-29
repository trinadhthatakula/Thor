# Thor — Feature Request Roadmap

**Date:** 2026-07-02 · **Last reviewed:** 2026-07-29 (statuses re-checked against `origin/dev` and the live issue tracker)
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
| **#164** | Export bundles | 🟨 **Mostly done** — SAF picker, remembered destination, and `.apk` / `.apks` writers all shipped (`ExportBottomSheet`, `AppBundleFileStoreImpl`, 11 `export_*` strings). **`.xapk` export is the only part left**, and it was promised in-thread |
| **#210** | Keep-in-launcher | ✅ **Achievable slice done** — the Freeze\|Suspend mode shipped via #239 (PR #241). The accessibility-based auto-refreeze remains declined, as planned |
| #55a | Freeze profiles | ⬜ **next green** — now the top unstarted item |
| #51 | App + data backup | ⬜ not started — but phase 1 got much cheaper once #164's SAF export landed. Also the oldest promise in `README.md` ("Upcoming Features → BackUp App Data") |
| #285 | Filter by permission | 🆕 **new, untriaged** — opened 2026-07-28, postdates this document |
| #161 | `.apks` won't open from Samsung My Files | 🐞 **bug, not a feature** — unanswered since 2026-07-18 |
| _all others_ | | ⬜ not started |

**Shipped since this document was written but not tracked by any issue:** the Freezer QS tile
rework (PRs #284 / #286 / #287), the Shizu CoreFetch store manifest (#280), the app-list refresh
timing fix (#278), the `longVersionCode` truncation fix (#277), installer downgrade detection
(#276), the portable-installer theme fix (#273), Odin Phase 3 (#266), and the release-only suspend
R8 fix (#265). That is where the last four weeks went — the feature backlog below barely moved
because the work was elsewhere.

**In review, not yet merged:** the unified app-info sheet (#288) and this refresh (#289).

---

## Master ranking

| # | Issue | Feature | Impact | Est. time | Complexity | Tier / verdict |
|---|-------|---------|:------:|:---------:|:----------:|----------------|
| — | **#57** | Sort by (install) size | 3–4 | done | 2 | ✅ **Merged** (#230) — close the issue |
| — | **#210** | Freezer "keep-in-launcher" | 3 | done | 4 | ✅ **Slice merged** (#241) — suspend mode; rest declined |
| 1 | **#164b** | `.xapk` export (the remainder of #164) | 2 | **0.5–1 d** | 2 | 🟢 **do first** — everything but the writer exists, and it was publicly promised |
| 2 | **#55a** | Freeze **profiles** (named groups) | 3 | **2–3 d** | 4 | 🟢 quick win — reuses Room + batch-freeze *(split from #55)* |
| 3 | **#161** | `.apks` won't open from Samsung My Files | 2 | **1–2 d** | 2 | 🟢 a real bug with a named reporter and a working comparison app — cheap goodwill |
| 4 | **#285** | Filter app list by permission | 2–3 | **1–2 d** | 2 | 🟢 `FilterType` is an extensible sealed interface and permissions are already parsed for the info sheet — mostly UI |
| 5 | **#51** | App **+ data backup** / transfer | **4** | APK-only **≈1 d now** · root-data **5–8 d** · P2P 12–20 d | 5 | 🟡 highest impact, root-gated — phase it; phase 1 got cheap once #164 landed |
| 6 | **#130** | InstallWithOptions attribution + drill-down | 2 | **1–2 d** (label ≈0.25 d) | 2 | 🟡 label = trivial; attribution unreliable |
| 7 | **#58** | **App lock** (root/Shizuku) | 3 | **8–15 d** | 5 | 🔴 differentiator but heavy build + ongoing tax |
| 8 | **#178** | App **tagging** | 3 | **3–5 d** | 3 | 🔴 low-risk but **zero demand** |
| 9 | **#209** | **VirusTotal** scanner | 2 | **4–7 d** | 3 | 🔴 network stack + user API key + privacy |
| — | **#55b** | Process manager (RAM/CPU) | 3 | 4–7 d | 4 | 🔴 fragile shell parsing, Shizuku/root-only, Dhizuku dead-end *(split from #55)* |

---

## Sequencing

**🟢 Do first (~1 week):** **#164b** (`.xapk` writer — half a day, closes a public promise and lets #164 be closed) → **#55a** (freeze profiles) → **#161** (Samsung My Files) → **#285** (permission filter). All lean on existing infra and are low-risk — with one caveat:
**#285's estimate is pending scope validation.** It is only a UI change if the permission data it
filters on is already available where the list is built; if it has to be read per app at list time,
or cached, that is a Room schema change and a different size of job. Settle that question before
committing to the 1–2 d figure.

**🟡 High-value bets (scope carefully):**
- **#51 backup** — highest *impact* (4), a genuine differentiator, and the **oldest unkept promise in the README**. Ship **phased**: phase 1 (APK-only backup to a SAF location) is now roughly a day, because #164's picker, remembered destination and writers already exist; root data backup is a separate 5–8 d effort; skip bespoke phone-to-phone transport (the exported file already rides the share sheet). Pair phase 1 with `.xapk` export — same code, same session.
- **#130** — do the *achievable slice* (the friendly installer label) and explicitly decline the parts Android won't allow.

**✅ Closed out since the last review:** #57 (shipped) and #210 (achievable slice shipped as the Freeze|Suspend mode). Neither issue has been closed on GitHub yet.

**🔴 Defer:** #58 (biggest build + robustness tax), #209 (FOSS API-key + privacy, low demand), #178 (zero demand), #55b (fragile, niche).

---

## Per-feature detail

### #164 — Export to folder (APK / XAPK / APKS / split) · 🟨 mostly shipped · impact 3
- **Shipped:** the SAF `ACTION_OPEN_DOCUMENT_TREE` picker, a remembered destination (with a `Downloads/Thor` default), `DocumentFile` write plumbing, progress/failure states, and both writers Thor picks between automatically — `.apk` for a single-APK app, `.apks` for a split app. See `ExportBottomSheet`, `AppBundleFileStoreImpl`, `AppBundleBuilderImpl`, `BundleZip`, and the 11 `export_*` strings.
- **Left (#164b):** `.xapk` output. `ApksMetadataGenerator` already produces the XAPK `manifest.json` for the *share* path, and Thor can already *install* an APKPure `.xapk` (`15f57d6d`), so the writer is the only missing piece — call it half a day. It was promised explicitly in-thread ("`.xapk` export specifically is planned"), so leaving it undone is a visible broken promise rather than a silent gap.
- **Also unbuilt (deliberately):** the raw split-folder output and a user-facing format picker. Thor chooses the format from the app's shape, which is the better default; a picker is only worth adding once `.xapk` gives it a third option.
- **Risks:** none hard. System/protected apps degrade without root (consistent with the app). OBB export stays out of scope.
- **Verify with more than a round trip:** installability is necessary but not sufficient. Export → reinstall through Thor's own installer *and* a third-party one, then also check the two failure shapes a successful install would hide: a bundle whose OBB assets are silently absent, and split contents/metadata a different reader rejects. See `docs/follow-ups/app-data-backup-and-xapk-export.md`.

### #55a — Freeze profiles · 🟢 · 2–3 d · impact 3
- **What:** named groups of apps you can freeze/unfreeze on demand.
- **Reuses:** `FreezerRepository` + Room `freezer_apps` table, `FreezerViewModel` multi-select + batch `MultiAppAction.Freeze/UnFreeze`, `AutoMigration`. New profile tables + a small UI.
- **Risks:** the Room migration is on a shipped database, so it needs a real `AutoMigration` and a schema-export diff — not `fallbackToDestructiveMigration`. Beyond that: a profile-triggered bulk freeze would be a **fourth** surface reaching `BulkFreezeRunner` (after the Freezer screen, the launcher shortcuts and the QS tile), so it must route through the runner rather than freezing directly, and it must respect the freeze tier gate, which now lives in `FreezeAppUseCase.kt:35-48` (the follow-up
  doc this used to link shipped and was deleted in `412f655e`). Two further risks the recon pass
  found: `BulkFreezeRunner`'s job slot coalesces on the `BulkOp` alone, so "freeze profile A" then
  "freeze profile B" would return the first `Deferred` and silently never freeze B; and
  `FreezerBridgeProvider` refuses to restore anything absent from the watchlist, so apps that live
  only in a profile would be un-unfreezable from the launcher.
- Otherwise on-brand for the freezer.

### #161 — `.apks` won't open from Samsung My Files · 🟢 · 1–2 d · impact 2 *(bug)*
- **What:** on a Galaxy S25 Ultra / One UI 8.5, tapping a `.apks` file in Samsung's stock My Files does not offer Thor, while InstallerX Revived and Universal Installer both appear. Thor *does* appear from other file managers.
- **Where to start:** `AndroidManifest.xml` (the `PortableInstallerActivity` intent-filters) — diff Thor's filters against the two apps that work. One UI's My Files is stricter about MIME type ↔ extension pairing than AOSP's picker, so the likely gap is a missing `mimeType`/`pathPattern` combination rather than anything deep.
- **Risks:** over-broad filters make Thor pop up for every zip or `application/octet-stream`, which is worse than the bug. Narrow the fix to the extensions Thor actually installs.
- **Why do it:** a named reporter with device details and a screenshot, unanswered since 2026-07-18, and a working comparison app to diff against. Cheap goodwill.

### #285 — Filter the app list by permission · 🟢 · 1–2 d · impact 2–3 *(new)*
- **What:** filter or group the app list by a permission — "which apps can use the camera".
- **Reuses:** `FilterType` is an extensible sealed interface and the filter chip row already renders dynamically, so the UI is additive. Permissions are already parsed per app for the info sheet's Permissions tab.
- **The one real question:** where the data comes from. Parsing manifests for the whole device on demand is too slow for a filter, so this likely wants a permission index in the Room cache, populated on scan — which turns a UI feature into a schema change. Scope that before estimating harder.
- **Status:** opened 2026-07-28, labelled `enhancement` + `needs triage`, zero comments. It postdates this document's original ranking.

### #51 — App + data backup / transfer · 🟡 · phased · impact 4
- **What:** back up an app's APK(s) + private data to a file; transfer app+data between phones.
- **Standing promise:** `README.md` has listed "BackUp App Data" under *Upcoming Features* for about a year. Of everything in this document, this is the item users have been told to expect for longest.
- **Reuses:** the APK half is now essentially built. `ShareAppUseCase`, `BundleZip`, the multi-APK install pipeline, *and* #164's SAF picker + remembered destination + `AppBundleFileStoreImpl` all exist, so phase 1 is wiring rather than construction.
- **HARD BLOCKER (data half only):** private-data backup requires **root** — Shizuku's shell uid cannot read `/data/data`, Dhizuku has no file access, and `adb backup` is dead on modern Android. Restore additionally needs correct uid/gid plus an SELinux relabel, which is where the real effort sits.
- **Phasing:** (1) APK-only backup to a SAF location — ~1 day atop #164, and the natural companion to `.xapk` export; (2) root-only data tar backup/restore, gated behind an explicit "requires root" state; (3) **defer** live P2P transport (the exported file already rides the share sheet).
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

### #178 — App tagging · 🔴 · 3–5 d · impact 3
- **What:** user-defined tags to group/browse the app list.
- **Reuses:** `FreezerEntity`/`FreezerRepository` is a near-template; `FilterType` is an extensible sealed interface; the chip row already renders dynamically. Needs a Room 4→5 migration + tag CRUD/assignment UI.
- **Risks:** none hard, but **zero demand**. Bundle with other app-list UX work if/when demand appears.

### #209 — VirusTotal scanner · 🔴 · 4–7 d · impact 2
- **What:** scan APKs pre-install + installed apps via VirusTotal.
- **New:** an entire network stack the app lacks (HTTP client, `INTERNET` permission, VT v3 models, settings for the key).
- **Risks:** FOSS blocker — a bundled key is extractable/rate-banned, so users must supply their own (friction). Free-tier limits (4/min, 500/day, 32 MB cap) make full-device scans impractical → hash-lookup-only. Third-party upload privacy → must be opt-in. Low demand.

### #55b — Process manager (RAM/CPU) · 🔴 · 4–7 d · impact 3 (niche)
- **What:** live process list with RAM/CPU to spot heavy apps.
- **Risks:** Shizuku/root-only (post-Android-8 `getRunningAppProcesses` returns only self → must parse `dumpsys meminfo`/`top`/`ps`), brittle across OEM/OS versions, CPU% needs sampled polling, **Dhizuku has no shell** → dead-end. Overlaps with existing task managers. Split from #55 and deferred; the profiles half (#55a) should ship without it.

---

## Cross-cutting insights

1. **Ship the MVP, not the costume.** #51, #55, #210, #130 each hide a small high-value slice behind an ambitious full ask. The leverage is in the slice.
2. **Privilege tier gates real value.** #51 (data), #55b (process stats), #58 (launch monitoring) are root/Shizuku-only with **Dhizuku dead-ends** — each needs an explicit "requires root/Shizuku" gate; none should assume Dhizuku parity.
3. **Demand is uneven.** Most requests have low 👍/comment signal; strategic fit (storage/freeze management) matters more than raw demand for a power-user tool — but zero-demand items (#178, #209) should wait.
4. **Finish the last 20%.** #164 is the cautionary tale: the hard 80% (SAF plumbing, writers, progress states) shipped, and the feature still cannot be closed because a half-day writer is missing — one that was promised in the thread. The same shape threatens #210, which is functionally shipped but held open by cross-privilege suspend ownership. Closing these is cheaper than starting anything new, and it is the difference between a roadmap that reads as delivered and one that reads as stalled.
5. **Keep this table honest.** Between 2026-07-02 and 2026-07-29 nothing on this list moved, yet nine PRs shipped — the QS tile rework, the store manifest, four installer/app-list fixes, Odin Phase 3, and the app-info unification. A roadmap that only tracks issue-backed work will keep reporting "no progress" while the project moves. Re-check it at each release, not when it feels stale.

# Thor — Feature Request Roadmap

**Date:** 2026-07-02
**Purpose:** A prioritized, codebase-grounded triage of the open feature requests in the issue tracker — ranked by value-to-effort with implementation estimates and impact, to drive the "greens first" build order.
**Method:** Each request was analyzed against the actual Thor codebase (existing infra it can reuse, layers it touches, root/Shizuku/Dhizuku feasibility), so the estimates reflect *what already exists vs. what must be built*, not guesses.

## How to read this

- **Impact** 1–5 (niche → transformative). **Effort** = complexity 1–5 + a rough solo-dev day estimate.
- **Tier / colour:** 🟢 do-first quick wins (high value, low risk) · 🟡 solid bets (scope carefully) · 🔴 defer (high effort or low demand).
- **Build strategy:** one issue per branch, **greens first**. Several requests are "a cheap MVP wearing an expensive costume" — ship the small high-value slice, defer/decline the rest.

## Status snapshot

| Issue | Feature | Status |
|-------|---------|--------|
| **#57** | Sort by size | ✅ **Done** — built as *total install size* (metric upgraded from APK size at maintainer's call); PR #230 → `dev`, awaiting CI merge |
| #164 | Export bundles | ⬜ next green |
| #55a | Freeze profiles | ⬜ next green |
| _all others_ | | ⬜ not started |

---

## Master ranking

| # | Issue | Feature | Impact | Est. time | Complexity | Tier / verdict |
|---|-------|---------|:------:|:---------:|:----------:|----------------|
| 1 | **#57** | Sort by (install) size | 3–4 | done | 2 | 🟢 **Done** (PR #230) |
| 2 | **#164** | Export to folder (APK/XAPK/APKS/split) | 3 | **3–5 d** | 3 | 🟢 quick win — ~70–80% reuses `ShareAppUseCase` |
| 3 | **#55a** | Freeze **profiles** (named groups) | 3 | **2–3 d** | 4 | 🟢 quick win — reuses Room + batch-freeze *(split from #55)* |
| 4 | **#51** | App **+ data backup** / transfer | **4** | APK-only ≈free · root-data **5–8 d** · P2P 12–20 d | 5 | 🟡 highest impact, root-gated — phase it |
| 5 | **#210** | Freezer **"keep-in-launcher"** | 3 | **3–6 d** | 4 | 🟡 suspend exists; decline the platform-limited parts |
| 6 | **#130** | InstallWithOptions attribution + drill-down | 2 | **1–2 d** (label ≈0.25 d) | 2 | 🟡 label = trivial; attribution unreliable |
| 7 | **#58** | **App lock** (root/Shizuku) | 3 | **8–15 d** | 5 | 🔴 differentiator but heavy build + ongoing tax |
| 8 | **#178** | App **tagging** | 3 | **3–5 d** | 3 | 🔴 low-risk but **zero demand** |
| 9 | **#209** | **VirusTotal** scanner | 2 | **4–7 d** | 3 | 🔴 network stack + user API key + privacy |
| — | **#55b** | Process manager (RAM/CPU) | 3 | 4–7 d | 4 | 🔴 fragile shell parsing, Shizuku/root-only, Dhizuku dead-end *(split from #55)* |

---

## Sequencing

**🟢 Do first — quick, high-fit wins (~1 week):** #57 (done) → **#164** → **#55a** (freeze profiles). All lean on existing infra, touch core "manage / free up storage / freeze" value, and carry no feasibility risk.

**🟡 High-value bets (scope carefully):**
- **#51 backup** — highest *impact* (4) and a genuine differentiator — but ship **phased**: APK-only backup is nearly free once #164's SAF export exists; root data backup is a separate 5–8 d effort; skip bespoke phone-to-phone transport (the exported file already rides the share sheet).
- **#210 / #130** — do the *achievable slice* (suspend-based freeze for #210; the friendly label for #130) and explicitly decline the parts Android won't allow.

**🔴 Defer:** #58 (biggest build + robustness tax), #209 (FOSS API-key + privacy, low demand), #178 (zero demand), #55b (fragile, niche).

---

## Per-feature detail

### #164 — Export to folder (APK / XAPK / APKS / split) · 🟢 · 3–5 d · impact 3
- **What:** export installed apps to a user-chosen folder (SAF picker), in a selectable format — not just Share.
- **Reuses:** `ShareAppUseCase` already gathers base + splits with a copy-then-root-fallback, generates APKS `metadata.json` + XAPK `manifest.json` (`ApksMetadataGenerator`), and zips via `BundleZip`. ~70–80% of the pipeline exists.
- **New:** a SAF `ACTION_OPEN_DOCUMENT_TREE`/`CreateDocument` picker + `DocumentFile` write plumbing, a format-selector dialog, and two extra output builders (real `.xapk`, raw split folder).
- **Risks:** none hard. System/protected apps degrade without root (consistent with the app). Scope OBB export out of v1.

### #55a — Freeze profiles · 🟢 · 2–3 d · impact 3
- **What:** named groups of apps you can freeze/unfreeze on demand.
- **Reuses:** `FreezerRepository` + Room `freezer_apps` table, `FreezerViewModel` multi-select + batch `MultiAppAction.Freeze/UnFreeze`, `AutoMigration`. New profile tables + a small UI.
- **Risks:** none. On-brand for the freezer.

### #51 — App + data backup / transfer · 🟡 · phased · impact 4
- **What:** back up an app's APK(s) + private data to a file; transfer app+data between phones.
- **Reuses:** APK half is strong (`ShareAppUseCase`, `BundleZip`, the multi-APK install pipeline).
- **HARD BLOCKER:** private-data backup requires **root** (Shizuku=shell uid can't read `/data/data`; Dhizuku has no file access; `adb backup` is dead on modern Android). Restore needs correct uid/gid + SELinux relabel.
- **Phasing:** (1) APK-only backup to a SAF location (near-free atop #164); (2) root-only data tar backup/restore; (3) **defer** live P2P transport (the file already rides the share sheet).

### #210 — Freezer "keep-in-launcher" · 🟡 · 3–6 d · impact 3
- **What:** frozen apps stay visible in the launcher; tap surfaces them; auto-refreeze on dismiss.
- **Reuses:** `setAppSuspended` is already implemented in all 3 gateways with `SuspendDialogInfo`; `AutoFreezeManager` already re-freezes on screen-off. The "keep visible" mechanism is basically suspend.
- **Platform limits (decline):** no public API to detect removal-from-recents (needs Accessibility/UsageStats polling — battery/Play-policy cost); no single-tap launcher-intercept (only the 2-tap "app paused" dialog). Ship the pragmatic suspend mode; decline the accessibility-based refreeze.

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

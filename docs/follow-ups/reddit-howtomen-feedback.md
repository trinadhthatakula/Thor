# r/howtomen feedback — verified against the code

**Filed:** 2026-08-07 (UTC) · **Source:** four comment threads on the r/howtomen Thor post
**Status:** triaged. Every claim below was checked against `origin/dev` before it was sized.

Twenty-three distinct asks from four users. This document is the evidence layer: what each person
reported, what the code actually does, and which existing row it lands on. The *ranking* lives in
[`README.md`](README.md) — do not duplicate it here, or the two will drift.

**Why this file exists rather than 23 rows:** most of these are small, and several are the same
underlying gap seen from different angles. Sizing them one at a time produces a backlog that hides
the three findings that actually matter (below). The retention rule's exception 2 applies — this is
the evidence under those conclusions.

---

## The three findings that changed something

### 1. A frozen system app can be *removed for the user*, and that is the likely account-loss mechanism

One reporter: freezing Google Play Services removed their Google accounts, and re-adding them was
required after unfreezing. They contrasted Hail, whose Disable preserves accounts but produces
notification spam from dependent apps — where Thor produces none.

Thor freezes with one of two mechanics ([`FreezePolicy.kt:106`](../../app/src/main/java/com/valhalla/thor/domain/model/FreezePolicy.kt)):

| Mechanic | Command | Effect |
|---|---|---|
| `DISABLE` | `pm disable-user --user N` | package stays installed, keeps data, `pm enable` restores it exactly |
| `UNINSTALL` | `pm uninstall -k --user N` | clears `FLAG_INSTALLED` for that user; `-k` keeps the data dirs |

`DISABLE` is preferred everywhere it works, and `uninstallFreezeFallbackAllowed` confines the
escalation to the case where the platform actually *refused* to disable — not to any failure. That
gate is deliberate and correct, and it is not what is being questioned here.

**But on the OEM builds where that refusal is real, freezing GMS runs `pm uninstall -k --user N`.**
That clears `FLAG_INSTALLED`, so GMS stops resolving for the user entirely.

This explains *both* halves of the report, which is what makes it convincing:

- **Accounts disappear.** An account is owned by its authenticator package. With GMS no longer
  installed for the user, the accounts it authenticates have no authenticator.
- **No notification spam.** Dependent apps cannot resolve GMS at all, so they have nothing to retry
  against. Hail's `DISABLE` leaves the package installed-but-disabled, which is exactly the state
  that makes dependents retry and complain.

⚠️ **The mechanism is inferred, not measured.** The commands and the gate are read from the code;
that `AccountManagerService` drops accounts on this specific path is the leading explanation for the
reported symptom, not something this repo has measured. **Reproduce before fixing** — and reproduce
it on a device where disable is refused, since on stock Android the escalation never fires and the
bug is invisible.

**The gap is not the escalation, it is that it is silent.** A user who asks to *freeze* is not told
that on their device it will be carried out by removing the package for them. The fix is a choice
between: (a) tell them at the point of freeze, (b) let them forbid escalation and take a visible
failure instead, or (c) both. (b) is the honest default for the user who wrote in — they would rather
GMS refuse to freeze than lose their accounts.

### 2. The debloat descriptions are already in the app, parsed, and then discarded

Both label-related asks — show the Recommended/Expert label in the *list*, and show a short
explanation of what an app is and why it is safe to remove — are **rendering work only**. No new
data source, no network call, no scraping.

`app/src/main/assets/uad_lists.json` already ships a per-package `description`, and
[`UadHelper.kt:11`](../../app/src/main/java/com/valhalla/thor/data/source/local/UadHelper.kt)
already parses it into `UadEntry(list, description, removal)` — assembled at `:125`, and again at
`:104` for entries contributed by extensions.

The data then stops at the API boundary: `UadSnapshot` exposes only

```kotlin
fun recommendationFor(packageName: String): String? = entries[packageName]?.removal
```

so `description` is read off disk on every load and thrown away. Exposing it is one accessor.

The label is likewise a map lookup against an already-loaded in-memory map — **not** a per-row query,
which is what would have made rendering it in a list expensive. The reporter's request to see the
tier while multi-selecting is therefore cheap, and it is the one that improves the *safety* of a bulk
debloat rather than just its convenience.

### 3. The usage-access plumbing for "abandoned apps" is already built and shipping

The abandoned-app request was expected to be the most expensive item in the thread, on the grounds
that it needed a new special-access permission. It does not, and the reason is better than "the
permission happens to be declared".

`PACKAGE_USAGE_STATS` is in the manifest at
[`AndroidManifest.xml:42`](../../app/src/main/AndroidManifest.xml), and **it is load-bearing today**:
it is what makes the `GET_USAGE_STATS` app-op grantable, and that op is what lets
`StorageStatsManager.queryStatsForPackage` answer for *other* packages — i.e. it is why Thor can show
you an app's size at all
([`StorageStatsHelper.kt:47`](../../app/src/main/java/com/valhalla/thor/data/manager/StorageStatsHelper.kt)).

More than the permission is already done.
[`UsageAccessManager`](../../app/src/main/java/com/valhalla/thor/data/manager/UsageAccessManager.kt)
is a complete gate around this op:

| Piece | Where |
|---|---|
| Read the op for Thor's own uid, per SDK level | `isGranted()`, `:37` |
| Silent grant through the active privilege gateway, **then re-verify** | `tryGrantViaPrivilege()`, `:48` |
| Once-per-process auto-grant that latches only on *success* | `maybeAutoGrant()`, `:69` — called from `AppListViewModel.kt:375` |
| `ACTION_USAGE_ACCESS_SETTINGS` deep link for the manual fallback | `usageAccessIntent()`, `:75` — wired into `AppListScreen.kt:372` and `SettingsScreen.kt:424` |

So the permission, the privileged grant path, the re-verification, the user-facing fallback and two
entry points into Settings all exist. What abandoned-app detection still needs is
`UsageStatsManager.queryUsageStats` — a **different class** from the `StorageStatsManager` above, and
the one thing here that genuinely appears nowhere in `app/src/main/java` — plus a schedule and a
notification.

That is still real work, and the recurring schedule is a maintenance cost the estimate should keep.
But it is ordinary work on top of a built foundation, not "add a scary permission".

> **Correction.** This section first claimed the opposite — that the permission was vestigial and
> should probably be deleted — on the strength of `UsageStatsManager` matching nothing in the tree.
> It matches nothing because it is the wrong symbol: the consumer is `StorageStatsManager`.
> Deleting that line would have broken app-size reporting. A search that returns nothing is not
> proof of absence unless you searched for the right name; CodeRabbit caught this on the PR.

---

## What was checked, and what it found

Everything in this table was read in `origin/dev`. "Already there" means the seam exists and the ask
is wiring, not building.

| Ask | Verdict | Evidence |
|---|---|---|
| Adding an app to the Freezer freezes it immediately, closing it and losing unsaved work | **Working as intended** — confirmed by the maintainer. Adding to the watchlist *is* a freeze. The gap is that nothing says so before it happens | — |
| Reinstall All offers no selection | **Confirmed** — the target set is *computed*, never chosen: every user app whose installer is neither Play nor the system installer | `MainViewModel.kt:459-471` |
| Reinstall All cannot be cancelled once started | **Partly** — it dispatches to `MultiAppAction.ReInstall` and the entry point offers no cancel. The bulk runners elsewhere do support cancellation, so this is a wiring gap, not a missing capability | `MainViewModel.kt:470` |
| "Forces the installer record" is unclear, and it rewrote a sideloaded app's installer from InstallerX to Play | **Confirmed, and the copy is accurate but jargon** — that is precisely what the operation does | `strings.xml:138` |
| Freezing Play Services removed Google accounts | **Symptom credible, mechanism inferred** — see finding 1 | `FreezePolicy.kt:106` |
| No in-app explanation of Force Stop vs Suspend vs Freeze | **Confirmed** — the three actions are offered with labels and icons only | `AppActionRow.kt` |
| Wants a Hail-style "Hide mode" needing no Shizuku | **Already partly served** — `DhizukuSystemGateway` is the Device Owner path and needs no Shizuku. What Hail calls Hide is closest to Thor's suspend | `DhizukuSystemGateway.kt:93` |
| Wants custom groups in the Freezer with per-group freeze/suspend/kill | **Already shipped as Freeze Profiles** (#55a, PR #295) — named groups with per-row freeze/unfreeze. This is a **discoverability** failure, not a feature gap. Per-group *kill* and *suspend* are the genuine remainder | `FreezeProfilesSheet.kt` |
| Debloat labels only visible in the detail page | **Confirmed, and cheap to fix** — see finding 2 | `UadHelper.kt:34` |
| Wants Canta-style short descriptions | **The data already ships** — see finding 2 | `uad_lists.json` |
| Wants to disable the per-freeze confirmation dialog | **No such preference exists.** ⚠️ Not all of these dialogs are the same: the `BLOCKED`-tier refusal is a safety gate and must stay unbypassable | `FreezePolicy.kt:69` (`isBlockedFromFreeze`) |
| Wants smaller icons / more column options | **Fixed at `GridCells.Adaptive(minSize = 100.dp)`** in four places, with no preference behind it | `AppList.kt:543` and 3 others |
| Wants App Ops-style permission grant/revoke | **Genuinely new.** Thor filters *by* permission (#285) and grants via `pm grant`, but has no revoke/appops surface | — |
| Wants change history (installed/uninstalled/froze last week) | **Genuinely new** — Room is at v6 with `apps`, `freezer_apps`, `extension_data`, `freeze_profiles`. **No event table, no timestamps of actions** | `AppDatabase.kt:21` |
| Wants update history (previous version of an app) | **Genuinely new, and blocked by the same gap** — `AppEntity.versionCode`/`versionName` are *overwritten* on each scan, so no prior version is retained anywhere | `AppEntity.kt` |
| Wants "abandoned app" notifications (unused 6 months) | **Cheaper than expected** — the permission, the privileged grant, the re-verification and the Settings deep link all ship today. See finding 3 | `UsageAccessManager.kt` |
| Wants CSV/MD export of the installed-app list | **Mostly already there** — the SAF picker, remembered destination and `DocumentFile` write plumbing all shipped with #164/#51 phase 1 | `AppBundleFileStoreImpl` |
| Wants a Portuguese translation | **480 strings.** Thor ships `en` + `ar`, `es`, `fr`, `zh-rCN`, all at 480 | `res/values*/strings.xml` |
| Wants a default-tab setting | **Hardcoded** `mutableStateOf(AppDestinations.HOME)` | `MainScreen.kt:119` |
| Wants an SD Maid-style quick scrollbar with sort-aware snapping | **Buildable, and the hard part is done** — `SortBy` has 8 modes already grouped into 4 families by `isNameBased()` / `isDateBased()` / `isVersionBased()` / `isSdkBased()`, which is exactly the switch the snap targets need | `SortBy.kt` |
| Wants tap/hold to copy the package name | **Nearly free** — `LocalClipboard` + `ClipEntry` are already used twice in the detail screen | `AppInfoDetailsScreen.kt:856` |
| Wants per-app notes | **Genuinely new**, but shares its storage work with tags below | — |
| Wants per-app custom tags | **Genuinely new** — and see the roadmap correction below | — |
| Wants to see which apps are running in the background | **Genuinely new** — nothing queries running processes. Every `isRunning` in the tree refers to a bulk *operation* in flight, not a process | — |

---

## What this changes about the existing backlog

**One roadmap justification is falsified outright.** `#178 — app tagging` *was* deferred in
[`../feature-request-roadmap.md`](../feature-request-roadmap.md) on the stated grounds of **"zero
demand"**, and in `README.md` as *"low-risk build, **zero demand**"*. Both of those wordings are
historical: this sweep replaced them, so the quotes above describe the state before it, not after.
A user asked for it unprompted,
and a second asked for per-app **notes**, which is the same storage work. That specific reason no
longer holds. The roadmap's *other* grounds — 3–5 days, needs a Room migration — are untouched and
may still justify deferring it; but it now has to be deferred on cost, not on demand.

**One deferral is corroborated rather than falsified.** `#55b — process manager` was deferred for
fragile `dumpsys`/`top` parsing and a Dhizuku dead-end. A user asked for it, so demand is now
non-zero — but every engineering objection stands. What the request reveals is only that the
expensive part is not what was asked for: *"options for know app running in background"* is answered
by a running/not-running flag, without live RAM or CPU figures and without sampled polling.

⚠️ **That is a smaller feature, not a solved one.** `getRunningAppProcesses` has returned only the
caller's own process since Android 8, so even a coarse flag needs a privileged `ps`/`dumpsys` read —
which means **Dhizuku's lack of a shell is still a dead-end for it**, exactly as it is for per-process
stats. Before this is ranked as affordable it needs a named API, a stated privilege path, and a
decided fallback for the modes that cannot answer. Until then the honest position is *"cheaper than
#55b as filed, and still unsized"*.

**One item is a documentation problem wearing a feature request.** Freeze Profiles shipped in
v1.93.1 and does most of what the "custom tabs/groups" request asks for. A user who reads the
Freezer screen carefully enough to request per-group actions did not find it.

**No new defect was found.** The one report that read as a bug — losing unsaved work on adding an app
to the Freezer — is intended behaviour. It stays on the backlog as an expectation gap, which
[`freezer-membership-toggle-semantics.md`](freezer-membership-toggle-semantics.md) had already filed
from the opposite direction: that row observed the control means two different things on two screens,
and this report is a user hitting exactly that.

**Nothing here displaces the engineering follow-ups.** The unguarded DataStore writes, the biometric
capability check and cross-privilege suspend ownership are all still ahead of every item in this
thread on risk, whatever their demand.


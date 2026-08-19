# r/howtomen feedback — verified against the code

**Filed:** 2026-08-07 (UTC) · **Source:** four comment threads on the r/howtomen Thor post
**Status:** triaged, **bands A and B are built** — eleven of band A's twelve rows shipped on
`feat/band-a`, band B followed on 2026-08-08 across three PRs, Band C #29 (Portuguese & Polish) shipped
2026-08-16, and App Info actions customization shipped 2026-08-19 (PR #410). Every claim
below was checked against `origin/dev` before it was sized; the ones the implementation then
falsified are corrected in place and labelled, not rewritten away.

Twenty-three distinct asks from four users. This document is the evidence layer: what each person
reported, what the code actually does, and which existing row it lands on. The *ranking* lives in
[`README.md`](README.md) — do not duplicate it here, or the two will drift.

**Why this file exists rather than 23 rows:** most of these are small, and several are the same
underlying gap seen from different angles. Sizing them one at a time produces a backlog that hides
the three findings that actually matter (below). The retention rule's exception 2 applies — this is
the evidence under those conclusions.

---

## The three findings that changed something

### 1. A frozen system app ~~can be~~ **could be** *removed for the user*, and that is the likely account-loss mechanism

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

> **Resolved 2026-08-07 — by *removing* the escalation, not by disclosing it.** The paragraph above
> framed this as a disclosure problem and offered a toggle as the strongest option. What shipped is
> (b) with no toggle at all: `uninstallFreezeFallbackAllowed` answers `false` under **every**
> privilege mode, refused or not, and both `ShizukuSystemGateway.freezeSystemApp` and
> `DhizukuSystemGateway.freezeSystemApp` now end at that gate the way `RootSystemGateway` already
> did — `Result.failure`, package left installed, and on a refusal the user is told so via
> `R.string.freeze_system_app_disable_refused`. Nothing warns you before an escalation any more,
> because there is no longer an escalation to warn about.
>
> Two consequences the paragraph above does not carry. **This is a capability removal**, not only a
> safety fix: a Shizuku or Dhizuku user on a build that refuses `pm disable-user` can no longer
> freeze system apps at all, and that needs a release-notes line. And **the escalation code is still
> in the tree**, unreachable but statically referenced, because the "remove it for this user anyway"
> consent path was deferred to its own work —
> [`freeze-refusal-remove-for-user-consent.md`](freeze-refusal-remove-for-user-consent.md).
>
> The ⚠️ above still stands in full. Nothing here was reproduced on a device that refuses
> `pm disable-user`; the fix was made because the escalation is indefensible when silent, not
> because the account-loss mechanism was confirmed.

### 2. The debloat descriptions are already in the app, parsed, and ~~then discarded~~ carried all the way to the UI unrendered

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

> **Correction.** *"The data then stops at the API boundary … exposing it is one accessor"* was
> checked at the wrong boundary, and building it proved so. `UadSnapshot` does expose only `removal`
> — that half is accurate — but **the app list never goes through `UadSnapshot`.**
> `AppRepositoryImpl` holds the raw `Map<String, UadEntry>` and already copied *both* fields onto the
> domain model at three sites (`:205-206`, `:215-216`, `:378-379`), so `AppInfo.bloatRecommendation`
> **and** `AppInfo.bloatDescription` existed and were populated on `origin/dev` before any of this
> work started. `description` was not thrown away; it was carried all the way to the presentation
> layer and then never rendered.
>
> The conclusion — *rendering work only, no new data source* — survives, and was in fact
> **understated**: it needed no accessor, no new field and no plumbing through `AppList` either. Two
> greps (`AppInfo` for the field, `presentation/` for a use of it) would have said so; the section
> instead reasoned forward from the one accessor it had found and stopped there. A boundary that
> exists is not evidence that the data crosses it there.

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

Everything in this table was read in `origin/dev` **as of 2026-08-07, before band A was built**. It
is a snapshot of what the code did at triage time and is deliberately left that way; what has since
shipped is listed in the section after it. "Already there" means the seam exists and the ask is
wiring, not building.

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

> **Correction — the one figure in this table that was measured wrong.** *"**480 strings** … all at
> 480"* is a **line** count wearing a name count. All five locales pack `animation_intensity_high`
> and `suspended_app_dialog_title` onto one physical line, so the triage-time figure was **481**
> unique `<string>` names plus 10 `<plurals>` — and the part the row got right, that all five sat at
> the same number, held exactly. Band A took every file to **508** names and the same 10 plurals,
> checked by diffing sorted `name=` attributes across all 25 pairs rather than by counting anything.
>
> The packed line is still on disk: band A inserted *around* it, not through it. So the trap is live
> for the next person, and it is not a one-off — a line count is short by one today and by an unknown
> amount the moment a second pair gets packed. **Diff sorted `name=` attributes.** The size of the
> Portuguese ask is unaffected in substance: it was never 480 strings and is now 508.

---

## What shipped, 2026-08-07 (`feat/band-a`)

Eleven of band A's twelve rows. The ranking and the row numbers live in [`README.md`](README.md),
under *Band A — shipped*; this section records only what the *evidence above* got wrong or
understated, because that is what this file is for.

| Ask from the table above | Shipped as |
|---|---|
| Freezing GMS removed accounts | The escalation is gone under every privilege mode — see the resolution block in finding 1 |
| Debloat labels only in the detail page | Tier badge in the app list (chip in list mode, coloured dot in grid mode) |
| Canta-style short descriptions | A "why this is flagged" card on the General tab, dropped when null *or* blank |
| No explanation of Force Stop / Suspend / Freeze | Long-press any of those three tiles for an explain-only sheet |
| Adding to the Freezer freezes immediately | The behaviour is unchanged and intended; every add now confirms first |
| Freeze Profiles undiscoverable | A labelled button under the Freezer's search bar, plus empty-state copy |
| Default-tab setting | A four-way picker in Settings → General, read before the first frame |
| Tap/hold to copy the package name | Three surfaces: both app-info headers and a General-tab card |
| Smaller icons / more columns | A three-step density preference driving all four `GridCells.Adaptive` grids |
| Sort tab is English in every locale | `SortBy.asGeneralName()` now returns a `@StringRes Int` |

**Not shipped: the `.apks`-from-My-Files fix (band A #10) is only half done** — the typeless intent
filter's `android:host="*"` gate and its 45 now-unreachable path matchers are gone, but the device
diagnostic that decides the rest is still unrun. See
[`161-apks-not-openable-from-file-managers.md`](161-apks-not-openable-from-file-managers.md).

> **Correction — "smaller icons" was priced as one number, and it is not one number.** The table row
> above is right that `minSize = 100.dp` is hardcoded in four places with no preference behind it,
> and the ranked row read that as "put a preference behind the number". Building it showed that a
> lone `minSize` preference **ships a rendering bug the moment it goes below 100**: `Modifier.size`
> is declared `enforceIncoming = true`, so an icon whose cell can no longer hold it is silently
> coerced smaller *while its corner radius stays put*, and the tile renders as a pill. 100.dp is the
> tight bound today — `56 + 2×16 + 2×6` — not a round number someone picked. Density therefore had
> to ship as a coordinated bundle (cell, icon, padding, corner radius, label gap, badge), with an
> invariant that each row's cell can actually hold its icon.
>
> A second premise, which circulated during implementation, is **false**: small icons do **not** clip
> the label. Both grid labels already ellipsize, and they did before this work. The thing that
> degrades below 100.dp is the *icon*, not the text — so anyone verifying this on a device should
> look at the tile shape, not at the caption.

---

## What shipped, 2026-08-08 (`feat/band-b-freezer`)

Band B's Freezer half. Two of these asks are from the table above; the rest of the branch is backlog
rows this file never raised. Same rule as the band A section — only what the *evidence above* got
wrong or understated is recorded here.

| Ask from the table above | Shipped as |
|---|---|
| Custom groups with per-group freeze / suspend / kill | The remaining two verbs, in the profile row's overflow menu. Nothing is persisted on the profile — the entity stays verb-agnostic, and the verb is chosen per tap |
| Too many confirmations when debloating | A Settings → Freezer switch, reaching `FreezeTier.NORMAL` system apps only |

> **Correction — "per-group kill" is not a bulk *op*, and reading it as one would have been the
> expensive mistake.** The ask names freeze, suspend and kill in one breath, and the table above
> repeats that framing. Suspend genuinely is a third `BulkAction` — `bulkActionFor` already had the
> branch. Kill is not: it does not change an app's frozen state, so putting it in `BulkOp` forces an
> answer to *"does a kill cancel an in-flight freeze of the same profile?"* when the honest answer is
> that they do not interact at all. It routes through the existing `MultiAppAction.Kill` instead,
> which already has both a confirmation and a progress log.
>
> **The suppression ask is narrower than it sounds, too.** "Stop asking me" reads as one setting, but
> the dialog it names has three tiers behind it. Only `NORMAL` is about tedium. `EXPERT` is a verdict
> about a *specific* package with no backstop underneath — `FreezeAppUseCase` refuses `BLOCKED` and
> nothing else — and `BLOCKED`'s missing confirm button *is* the refusal, so suppressing that dialog
> would remove the safety gate rather than a prompt. A setting that took the ask literally would have
> shipped as a way to turn off the tier system.

---

## What shipped, 2026-08-08 (`feat/band-b-applist`)

Band B's Apps-tab half, and the last of this thread's asks. Same rule again — only what the
*evidence above* got wrong or understated.

| Ask from the table above | Shipped as |
|---|---|
| Fix Store is opaque, all-or-nothing and uncancellable | A picker listing every candidate with the installer Android currently records for it, all pre-ticked, the count in the confirm button; a stop that lands between apps; plainer confirm copy |
| Quick scrollbar with sort-aware snapping | The position **indicator** on both long lists. The sort-aware scrubber is deferred |
| Export the app list to CSV/MD | CSV of `displayedApps` verbatim, saved to the existing export target or shared straight out. MD dropped |

> **Correction — "the target set is computed, never chosen" understates it: the set was also
> *wrong*, in two places that had drifted apart.** The evidence above named the real complaint
> correctly (a user had every sideloaded app's installer rewritten and was never shown what would be
> touched) and stopped at the missing picker. Reading the predicate to build the picker showed it
> excluded Google's package installer but not **AOSP's** `com.android.packageinstaller`, which
> `AppListViewModel` already classifies as Sideloaded alongside it — so on a de-Googled ROM every
> normally-sideloaded app was a Fix Store candidate. The Home card's badge computed the same rule
> from its own copy, drifted further still. A picker over the wrong set would have made the wrong
> set visible, not right.
>
> **"The label is the whole win" was wrong about #130, and the chart had a defect nobody had
> filed.** The drill-down half was priced at most of 1–2 days on the roadmap; the filter type, the
> chip row, the persisted selection and the Home→Apps switch all already existed, so it came to a
> click handler. And the chart was not merely unlabelled — it derived its bucket key from the
> installer id's last segment, uppercased, so `com.aurora.store` drew as "STORE" and **any two
> installers sharing a last segment were summed into one bar**. That is a wrong number on a chart,
> which nothing in this thread reported and nobody would have noticed.
>
> **The scrollbar's cost was in the half that did not ship.** The row was sized medium on the
> strength of sort-aware snapping, and band A #7 deliberately kept four `SortBy` predicates alive as
> its seam. The *indicator* needs none of them: it is two lines per screen. Those predicates are
> still there and still unspent, and the scrubber that will use them carries a constraint worth
> recording now — A-Z buckets must be folded out of the list's actual order, never out of a human
> alphabet, because switching the NAME comparator to a locale `Collator` silently reorders every
> existing user's app list in every locale.
>
> **"The SAF plumbing already shipped" was half true.** It was, for saving. Sharing a list needed a
> port method whose contract is the *opposite* of the bundle export's: `ExportAppUseCase` deletes
> its staged copy in a `finally`, which is exactly wrong for a file handed to another app as a
> `content://` URI, since the receiver opens it long after the call returns. `stageText` wipes its
> directory on **entry** instead — the one moment nobody can still be reading the last one.

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

⚠️ **That is a smaller feature, not a solved one.** `getRunningAppProcesses` has been progressively
restricted since **Android 5.1** and is privilege-filtered for an ordinary app, so even a coarse flag
needs a privileged `ps`/`dumpsys` read — which means **Dhizuku's lack of a shell is still a dead-end
for it**, exactly as it is for per-process stats. Before this can be ranked as affordable it needs a
named API, a stated privilege path, and a decided fallback for the modes that cannot answer. Until
then the honest position is *"smaller than #55b as filed, and still unsized"*.

**One item is a documentation problem wearing a feature request.** Freeze Profiles shipped in
v1.93.1 and does most of what the "custom tabs/groups" request asks for. A user who reads the
Freezer screen carefully enough to request per-group actions did not find it.

**No new defect was found.** The one report that read as a bug — losing unsaved work on adding an app
to the Freezer — is intended behaviour. It stays on the backlog as an expectation gap, which
[`freezer-membership-toggle-semantics.md`](freezer-membership-toggle-semantics.md) had already filed
from the opposite direction: that row observed the control means two different things on two screens,
and this report is a user hitting exactly that. Band A #4 closed the *surprise* — every add now
confirms first — and left that row's actual subject, the two meanings, untouched.

**Nothing here displaces the engineering follow-ups.** The unguarded DataStore writes, the biometric
capability check and cross-privilege suspend ownership are all still ahead of every item in this
thread on risk, whatever their demand.

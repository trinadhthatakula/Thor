# Thor's deferred work, in one table

Everything Thor has put off, in one place: the write-ups in this directory, the open feature requests
in [`../feature-request-roadmap.md`](../feature-request-roadmap.md), and the standing promises in the
project `README.md`. **One line per item** — the detail lives in the linked doc, and the linked doc is
the thing to update. A row without a link is an item whose whole content is the line you are reading.

**Tiers.** `0/1` being built right now · `2` approved, not scheduled · `3` filed, decision still open ·
*declined* ruled out, do not re-raise. Where a row also carries a roadmap colour (🟢 do-first ·
🟡 scope carefully · 🔴 defer), that colour is the roadmap's own verdict, not a second opinion.

**Last swept:** 2026-08-07, twice — once to rank the backlog, once after band A was built against the
ranking. The Kotlin sources still contain **no `TODO`/`FIXME`/`HACK`/`XXX` markers at all** —
re-checked this sweep, zero matches across `app/src/main/java` and `bypass`. So nothing below was
found by grepping the code; every row came from a doc, the roadmap, the project `README.md`, or —
new this sweep — **users**.

Dates here are **UTC**, matching the GitHub timestamps they can be checked against. A sweep run late
in the evening IST lands on the previous UTC day; write the day GitHub will show, not the day your
clock shows, or the index becomes uncheckable in exactly the way this sweep was fixing.

When you file a new follow-up, add a row here. When one ships, delete the row *and* the doc — with
**three exceptions**, all deliberate, none of which the next sweep should "fix":

1. **An open row cites the doc as a worked example.** `viewmodel-behavior-tests.md` sits on disk with
   no row of its own because the `BulkFreezeRunner` row leans on it for the mutation-checking bar.
2. **The doc is the evidence under a conclusion.** `privilege-manager-cold-start.md` is what #22's
   verdict rests on; deleting it would leave the conclusion with nothing beneath it.
3. **A shipped row whose job is "do not start this again."** The whole Tier 0/1 section is kept for
   that reason — seven closed rows whose remaining value is stopping the work being redone.

Deleting something an open row, a live conclusion or a future contributor leans on trades a tidy
index for lost context, which is the wrong trade.

<details>
<summary>What this sweep changed (2026-08-03)</summary>

The index had drifted from its own linked docs — the failure mode this file exists to prevent.

* **#22 was recorded as *"2 of 8 configurations, and the numbers are bad"*** while its own doc had
  said `✅ CLOSED, all 8 measured` since 2026-08-01. Both rows corrected; the interim verdict is
  refuted, not merely superseded.
* **#55a read *"scoped, unstarted"*** — PR #295 merged the same day it was scoped.
* **#285 read *"in review, close the issue on merge"*** — PR #294 merged, issue closed.
* **#164's action item** (*"can be closed with its scope stated"*) was discharged on 2026-08-03.
* **`telegram-caption-length-guard.md` had no row at all**, filed 2026-08-03 and never indexed.
* **Two finished docs deleted**, their surviving decisions folded into the rows that still need them.

GitHub issues were reconciled in the same pass: **#310, #164 and #210 closed**, each with the scope
of what shipped stated rather than implied; **#55, #51 and #161** got status comments and stay open.

</details>

<details>
<summary>What this sweep changed (2026-08-06)</summary>

Two follow-up docs deleted; their subjects are now shipped code:

* **`two-branches-one-play-version-code.md`** — the collision it documented cannot occur with one
  uploader. The digit-gate routing it recorded was the old rule; the three-rung ladder supersedes
  it, and `release-notes/README.md` now documents the routing directly. Both workflows that cited
  it by path (`dev-check.yml`, `production-deploy.yml`) are themselves deleted.
* **`telegram-caption-length-guard.md`** — implemented as `.github/scripts/check-notes-budget.sh`
  (2026-08-06). The pre-flight check is part of the release runbook in `release-notes/README.md`.

Inbound links checked before both deletions per the retention rule.

</details>

<details>
<summary>What this sweep changed (2026-08-07, first pass — the ranking)</summary>

**First sweep driven by user feedback rather than by the code.** Twenty-three asks from four users on
an r/howtomen post, each checked against `origin/dev` before being sized — see
[`reddit-howtomen-feedback.md`](reddit-howtomen-feedback.md) for the evidence.

* **A ranked ordering was added** (below). The tier tables answer *"is this approved?"*; they have
  never answered *"what should I build on Tuesday?"* Both files now had ~45 open items and no
  agreed order between them.
* **#178's deferral reason is falsified.** It was deferred as *"zero demand"*. Two users asked for it
  (tagging, and notes over the same storage). The cost objection survives; the demand one does not.
* **#55b is corroborated but its scope shrinks — and the smaller half is still not costed.** Someone
  did ask, and asked for *"which apps are running"*, not for RAM/CPU figures. The cheap slice had
  never been separated from the expensive one. It is separated now and sits in band D, unranked:
  `getRunningAppProcesses` has been progressively restricted since Android 5.1 and is
  privilege-filtered for an ordinary app, so even the coarse flag needs a privileged `ps`/`dumpsys`
  and Dhizuku is a dead-end for it too.
* **One request turned out to be already shipped.** Freeze Profiles (#55a, v1.93.1) is most of the
  "custom groups in the Freezer" ask. Filed as discoverability.
* **No new defect.** The one report that read as a bug — an app freezing the moment it is added to the
  Freezer, closing it and losing unsaved work — is **intended behaviour**, confirmed by the
  maintainer. It joins [`freezer-membership-toggle-semantics.md`](freezer-membership-toggle-semantics.md),
  which had already filed the same control from the opposite direction.

</details>

<details>
<summary>What this sweep changed (2026-08-07, second pass — band A built)</summary>

**Band A was built the same day it was ranked**, on `feat/band-a`: eleven of its twelve rows shipped
and the twelfth shipped a half. The rows are **retired in place, not deleted** — retention exception
3 — and nothing was renumbered, because `band A #1` and `band A #10` are cited from other docs and a
renumber breaks those silently with nothing to catch it.

* **Two ranked premises were falsified by building them.** Both are corrected in
  [`reddit-howtomen-feedback.md`](reddit-howtomen-feedback.md) rather than quietly rewritten. Row 6
  needed **no accessor at all**: `AppInfo.bloatDescription` already existed and was already populated
  on `origin/dev`, so the "the data stops at the API boundary" reasoning was checked at the wrong
  boundary. And row 11 is **not** a lone `minSize` preference — `Modifier.size` is declared
  `enforceIncoming = true`, so an icon whose cell can no longer hold it is silently coerced smaller
  while its corner radius stays put and the tile renders as a pill. The related claim that small
  icons clip the *label* is false; both grid labels already ellipsize.
* **Row 1 shipped the stronger option, not the ranked one.** The row asked for disclosure. What
  shipped is *removal* of the escalation under every privilege mode — which is also a **capability
  removal** on the OEM builds where it used to fire, and needs a release-notes line.
* **Three pieces were descoped by the owner and are open work, not done work**: the "remove it for
  this user anyway" consent path (new doc, Tier 3 below), row 4's *don't ask again* preference
  (folded into band B #18), and row 10's opt-in *"show Thor when opening any file"* toggle (stays
  with #161).
* **One doc deleted:** `sort-labels-are-hardcoded-english.md`, whose work shipped as band A #7.
  Inbound links were checked first per the retention rule; the only one was its own Tier 3 row, which
  goes with it.
* **A recount that was never run past lint was reverted.** Mid-wave, `:bypass`'s `SyntheticAccessor`
  count was *derived* down from 18 findings across 6 methods to 9 across 4, by disassembling the
  detector. It was then re-measured — six methods back to `private`, `:bypass:lintDebug` — and the
  answer is **18 from 6**. A derived count and a measured count are different kinds of claim, and
  this index printed the derived one before the measurement caught it.
* **Band A #1 falsified the published website, and no gate will say so.** Five live pages under
  `web/src/pages/` — six passages — and the four `docs/site-content/` specs behind them all tell the
  user that on a device which refuses to disable a preinstalled app, Thor removes it for their
  Android user instead. As of band A #1 it does not. The claims gate
  (`web/src/content/claims.mjs`, rule `C1`) cannot catch this: it is a **forbid** list pointed at the
  *older* wrong claim that freezing loses your data, so copy that is merely out of date and
  reassuring passes it clean. New row in band B and in Tier 2. ⚠️ **`master` is the branch that
  publishes**, so this is due when band A gets there, not later.
* **The roadmap was brought in line, in four places, by strikethrough rather than overwrite.**
  [`../feature-request-roadmap.md`](../feature-request-roadmap.md) had not been re-read since
  2026-07-30. It still deferred #178 on *"zero demand"* — wording this index had already replaced,
  and which [`reddit-howtomen-feedback.md`](reddit-howtomen-feedback.md) claimed *"this sweep
  replaced"* while it was still sitting there in four places; it still sent the next reader hunting
  for a missing `mimeType`/`pathPattern` on #161, which is the guess the diagnosis refuted; it still
  attributed `getRunningAppProcesses`'s restriction to Android 8 (that is `getRunningServices` — this
  one has been privilege-filtered since **5.1**, and the conclusion it supports is unchanged); and it
  still had #55a *in review* three weeks after PR #295 merged. The superseded wording is struck
  through, not deleted, because other docs quote it verbatim.
* **Nothing in band A has run on a device.** It inherits band D's standing "awaiting a device"
  caveat in full, band A #1 most of all — that one only behaves differently on hardware which
  actually refuses `pm disable-user`. It *has* been through the toolchain: `assembleFossDebug`
  exit 0, **738 unit tests / 0 failures across 63 classes** read off
  `app/build/test-results/**/*.xml` rather than off Gradle's log, and `lint` exit 0 with zero
  `MissingTranslation` and zero `SyntheticAccessor`. A green build is not a device.

</details>

---

## Do next — every open item, ranked

Ranked by **impact × ease**: what buys the most for the least. This is the answer to *"what should I
pick up?"*; the tier tables below are the answer to *"has this been approved, and what is the
detail?"* Every row appears in both — this section adds an order, not new work.

**Read the bands, not the exact numbers.** The gap between 3 and 5 is noise; the gap between band A
and band C is not.

**Band A is built — start at the unnumbered row at the head of band B, then #13.** Band A's numbers
are frozen where they are; see the sweep note above for why they are not being reclaimed, and why the
one item band A itself created carries no number.

### Band A — shipped 2026-08-07 (`feat/band-a`)

Eleven of twelve, in one wave. Kept per retention exception 3: the remaining value of these rows is
stopping the work being redone. **None of it has run on a device.**

| # | Item | Kind | Effort | Shipped as |
|:-:|---|---|---|---|
| 1 | [Disclose that a freeze may *remove* a system app for the user](reddit-howtomen-feedback.md) | risk | small–medium | ✅ **by a stronger option than the row asked for** — not disclosure but removal. `uninstallFreezeFallbackAllowed` answers `false` under **every** privilege mode, refused or not, and the Shizuku and Dhizuku gateways now end at that gate the way root already did: `Result.failure`, package left installed, the refusal named to the user. ⚠️ **This is a capability removal** — on a build that refuses `pm disable-user`, those two modes can no longer freeze a system app at all. **Needs a release-notes line, and it also falsified nine files of website copy** — both are the unnumbered row at the head of band B. The path it displaces is [its own row](freeze-refusal-remove-for-user-consent.md) below |
| 2 | [Show the UAD tier in the app **list**](reddit-howtomen-feedback.md) | safety/ux | small | ✅ one `UadTierBadge` with two shapes — the `StatusChip` the detail screen already draws, in list mode; a coloured dot in grid mode, because a word does not fit a 100 dp cell. The gate and the colour live in the one composable so the two cannot fork. Gated `isSystem && !isUadLoadFailed`, matching `AppRiskDialog`. The Freezer's rows get it for free, since they are the same two composables |
| 3 | [Explain Force Stop vs Suspend vs Freeze in-app](reddit-howtomen-feedback.md) | docs/ux | small | ✅ long-press any of those three tiles for an **explain-only** sheet (`InfoBottomSheet` gained a no-confirm mode). Deliberately not the Home bento's explain-then-do: all three verbs are destructive, and a confirm button reached by the hesitation gesture is a second trigger |
| 4 | [Warn before adding to the Freezer freezes a running app](freezer-membership-toggle-semantics.md) | ux | small | ✅ every add from the Manage sheet confirms first; the behaviour is unchanged and intended. Removal is still never gated. **No suppression preference shipped** — that is band B #18, which must not be allowed to silence *this* dialog |
| 5 | [Surface Freeze Profiles from the Freezer screen](reddit-howtomen-feedback.md) | discoverability | small | ✅ a labelled button under the search bar, plus `no_profiles_yet` onboarding copy gated on the profile list actually being empty. The unlabelled toolbar icon stays — removing a shipped affordance buys nothing |
| 6 | [Show the UAD `description` too](reddit-howtomen-feedback.md) | ux | small | ✅ a "why this is flagged" card on the General tab, dropped when the description is null *or* blank — 64 UAD entries ship an empty one. ⚠️ **Needed no accessor and no new field**; see the correction in the linked doc |
| 7 | Sort labels were hardcoded English | i18n | small | ✅ `SortBy.asGeneralName()` returns a `@StringRes Int`, shaped exactly like `FilterType`'s, with four dead `SortOrder` members deleted alongside it. **Its doc is deleted and this row is the record** |
| 8 | [Default-tab setting](reddit-howtomen-feedback.md) | ux | small | ✅ a four-way picker in Settings → General. The substance was the cold-start race, and it is handled in `HomeActivity`, not in Compose: the preference is read *before* `setContent` and the splash is held until it lands. Both in-Compose fixes are wrong — an unkeyed `rememberSaveable` captures HOME forever, a keyed one replays the tab transition on every cold start |
| 9 | [Tap/hold to copy a package name](reddit-howtomen-feedback.md) | ux | trivial | ✅ three surfaces — both app-info headers and a General-tab card — reusing the existing `LocalClipboard` + `toast_copy_saved` pattern verbatim. Not attached to the list row, where a child handler would swallow the tap that opens the sheet |
| 10 | [#161 — `.apks` won't open from Samsung My Files](161-apks-not-openable-from-file-managers.md) | bug | small | ⚠️ **half shipped, and it may not fix the issue.** The *typeless* filter lost its `android:host="*"` and the 45 path matchers that gate made unreachable — there the host provably excluded the opaque-provider case the filter exists for. The `*/*` filter is untouched, because dropping its host claims every typed file on the device. Whether the half is enough turns on what Samsung's provider returns from `getType()`, and **the diagnostic is still unrun**. See the Tier 2 row |
| 11 | [Icon size / column count preference](reddit-howtomen-feedback.md) | ux | small | ✅ a three-step density driving all four `GridCells.Adaptive` grids — but as a **coordinated bundle** (cell, icon, padding, corner radius, label gap, badge) carrying the invariant that each row's cell can actually hold its icon, not the lone `minSize` the row imagined. DEFAULT reproduces today's rendering to the dp, so a user who never opens the setting sees no change |
| 12 | [`SyntheticAccessor` in `:bypass`](static-analysis-switched-off-by-default.md) | tech-debt | small | ✅ the `:bypass` half only — `enable += "SyntheticAccessor"` plus the six `private`→`internal` widenings **in the same change**, because the module's `warningsAsErrors` makes enabling it on its own an instantly red build. **18 findings from six methods**; a mid-wave recount to 9 from 4 was derived rather than measured and has been reverted. `:app`'s 43 are untouched and are their own piece of work |

### Band B — worth scheduling

| # | Item | Kind | Effort | Why here |
|:-:|---|---|---|---|
| — | **Outward-facing copy that band A #1 made false** — the website, and the release note that does not exist yet | docs | small | **Unnumbered deliberately**: rows 1–12 and 13–22 are cited by band and number from other docs, and renumbering to slot this in breaks those citations silently, with nothing to catch it. Read it as *"do it with #13"*. Six passages across five live pages (`index.astro`, `faq.mdx`, `features.mdx` ×2, `download.mdx`, `privacy.mdx`) and their four `docs/site-content/` specs still promise the removal fallback band A #1 deleted. ⚠️ **Two corrections, not one** — the same passages also say *"Dhizuku has not been converted yet and still removes without keeping data"*, which has been false since PR #332: `Dhizuku.freezeSystemAppForUser` passes `keepData = true`. The second one is **not** band A's doing and predates it. `master` publishes the site, so this is due on that merge |
| 13 | [Guard the DataStore **write** path](datastore-writes-are-unguarded.md) | risk | small–medium | 25 unguarded `edit { }` blocks; a full disk crashes Thor from a settings toggle, and one of them strands a user outside their own app lock. Held only by a product call on retry semantics. **Now the top of the queue.** Band A added three more `edit { }` blocks to the pile (default tab, grid density) without guarding any of them |
| 14 | [Fix Store: selection + cancel + plainer copy](reddit-howtomen-feedback.md) | ux | medium | Confirmed: the target set is computed, never chosen (`MainViewModel.kt:459`). A user ran it and had every sideloaded app's installer rewritten |
| 15 | [Freezer removal has no escape hatch](freezer-removal-has-no-escape-hatch.md) | ux | small once decided | An app that refuses to thaw is a row the user cannot delete. Option 3 (prune uninstalled packages) is independent and safe to land alone |
| 16 | [Profile editor dismisses before its save lands](profile-editor-dismisses-before-the-save-lands.md) | ux | small–medium | Worst for the user who just ticked forty apps and hits a name collision |
| 17 | [Per-group *kill* and *suspend* in Freeze Profiles](reddit-howtomen-feedback.md) | feature | small–medium | The genuine remainder of the "groups" ask, now that band A #5 has surfaced what exists |
| 18 | [Optional per-freeze confirmation](reddit-howtomen-feedback.md) | ux | small | ⚠️ **Scope grew, in both directions.** The `BLOCKED`-tier refusal (`FreezePolicy.kt:69`) is a **safety gate**, not a confirmation, and must stay unbypassable — and band A #4 added a second dialog this must not silence either, since suppressing the data-loss warning is the outcome #4 exists to prevent. This row is also where #4's descoped *"don't ask again"* lands |
| 19 | [Quick scrollbar with sort-aware snapping](reddit-howtomen-feedback.md) | ux | medium | The hard part is done: `SortBy`'s 4 families already have predicates, which is exactly the switch the snap targets need. Band A #7 deleted four dead `SortOrder` members but **deliberately left those four predicates**, which are this row's seam |
| 20 | [Export the app list to CSV/MD](reddit-howtomen-feedback.md) | feature | small–medium | SAF picker, remembered destination and write plumbing all shipped with #164/#51 |
| 21 | [#130 — friendly installer label](../feature-request-roadmap.md) | feature | ≈0.25 d for the label | The label is the whole win; the attribution half stays declined |
| 22 | [`lastResult` has no expiry](freezer-bulk-run-deferred-review-findings.md) (§1) | bug | medium | Wants the runner's tests first |

### Band C — real, and expensive

| # | Item | Kind | Effort | Why here |
|:-:|---|---|---|---|
| 23 | [#51 phase 2 — app **data** backup](app-data-backup-and-xapk-export.md) | feature | 5–8 d | Highest-impact item left (4/5), and `README.md` has promised it for a year. Root-only, hard-gated |
| 24 | [#178 — app tagging + per-app notes](../feature-request-roadmap.md) | feature | 3–5 d | **Demand is no longer zero** — two users, one thread. Notes and tags are one Room migration, so build them together or neither |
| 25 | Change history + update history | feature | medium–large | Room has no event table and `AppEntity` overwrites the version on every scan, so *both* need the same new table. Do them as one piece of work |
| 26 | [`BulkFreezeRunner` concurrency tests](bulk-freeze-runner-concurrency-tests.md) | tests | medium | Still blocked: 3 of 4 collaborators need a seam before the runner can be built in a JVM test |
| 27 | [Abandoned-app notifications](reddit-howtomen-feedback.md) | feature | medium | **Re-priced downward.** `PACKAGE_USAGE_STATS`, the privileged silent grant, the re-verification and the Settings deep link all ship today in `UsageAccessManager` — what is missing is `UsageStatsManager.queryUsageStats`, a schedule and a notification. Here rather than in band B because the recurring schedule is a permanent maintenance cost |
| 28 | App Ops–style permission grant/revoke | feature | large | Genuinely new surface. Thor filters by permission and grants; it cannot revoke |
| 29 | Portuguese translation | i18n | 508 strings + 10 plurals | Mechanical but not small, and it is a whole locale to keep in step forever after. Band A moved every one of the five files from **481** unique `<string>` names to **508** in a single pass, which is the recurring cost this row is really about. ⚠️ The *"480"* this was first sized at was a line count, not a name count — the base file packs two strings onto one physical line, in all five locales. **Diff sorted `name=` attributes; never count lines** |
| 30 | #58 — app lock · #209 — VirusTotal | feature | 4–15 d each | Both carry a policy or maintenance tax out of proportion to demand. #209 also puts a third-party API key in a FOSS build |

### Band D — not ready, or not ours

| # | Item | Why it is not rankable |
|:-:|---|---|
| — | [biometric capability check](biometric-lock-restores-without-a-capability-check.md) · [watchlist recovery flag](restored-prompt-flag-suppresses-watchlist-recovery.md) · [cross-privilege suspend](cross-privilege-suspend-ownership.md) · [subscription downgrade](subscription-downgrade-replacement-mode.md) | **Fixed or shipped, awaiting a device.** These need verification, not development — and they are ahead of everything above on *risk*, whatever their rank on effort. **All twelve band A rows joined them on 2026-08-07**, band A #1 most of all: it only behaves differently on hardware that actually refuses `pm disable-user` |
| — | [the "remove it for this user anyway" consent path](freeze-refusal-remove-for-user-consent.md) | **Opened by band A #1, deliberately.** Four product questions have to be answered before any of it can be built, and the first one — what the watchlist shows for a removed-but-not-frozen app — has no obviously right answer |
| — | [release builds emit no Thor logcat](release-builds-emit-no-thor-logcat.md) | Possibly deliberate — logcat is world-readable and Thor's logs carry package lists and shell commands. Needs a ruling, not a build |
| — | [Which apps are running](reddit-howtomen-feedback.md) — the cheap half of **#55b** | **Unsized, and it must stay unsized until an API is named.** A running/not-running flag drops RAM, CPU and sampled polling, but `getRunningAppProcesses` has been progressively restricted since **Android 5.1** and is privilege-filtered for an ordinary app, so even the coarse answer needs a privileged `ps`/`dumpsys` — meaning **Dhizuku's missing shell is a dead-end here too**. Needs a named API, a privilege path and a decided fallback before it can be ranked |
| — | [odin root availability cache](odin-root-availability-cache.md) · residual `MainShell` hang | Upstream in Odin |
| — | [on-device Perfetto pass](perfetto-trace-pass.md) | Explicitly last, by design |
| — | [vercel-actions-deploy](vercel-actions-deploy.md) | Deferred, nothing broken |
| — | Editing `packages.xml` · Batch install · Authenticated extension trigger | Unsized `README.md` promises with no issue, design or doc |

---

## Tier 0/1 — landed in the `chore/tier0-batch-1` batch

Numbers are the 2026-07-29 deferred-items sweep. All eight are **done in code**, and as of
2026-08-01 **all eight are closed** — #22 was the last one holding out. **The owner's device pass ran
2026-07-30: #17, #18 and #20 all verified.** Do not start any of these again.

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **#14 — `ExtensionOpsProvider` match flags** — scoped out of the shortcut match-flags work (doc deleted 2026-08-03, work shipped) | `anyFrozen` saw only the `pm disable` half of a freeze, so an all-system-app target read as *not* frozen and extension `toggle` re-froze instead of thawing | 0/1 | small | ✅ done — `MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS`, covered by `ExtensionOpsGateTest` |
| **#16 — unit-test dependencies** | add `kotlinx-coroutines-test` + turbine; three test follow-ups are blocked on nothing else | 0/1 | small | ✅ done — and used: the suite went 104 → 209 tests |
| **#17 — `grantPermission` omits `--user`** | `pm grant` defaults to user 0 in all three gateways, so it targets the wrong user on work-profile / multi-user devices | 0/1 | small | ✅ **done & closed** — all three gateways derive the id from the package's own uid (`userIdOf(uid)`), not `myUserId()`; **owner-verified on device 2026-07-30**, doc deleted. The reasoning survives in each gateway's KDoc at the call site |
| **#18 — single-app freeze tier gate** | the three single-app freeze paths check nothing; what stops a `BLOCKED` app being frozen is a dialog that renders no confirm button | 0/1 | small | ✅ **done & closed** — `FreezeAppUseCase` + 16 tests; **owner-verified on device 2026-07-30**: a BLOCKED system app is refused with the blocked dialog rather than frozen. Doc deleted |
| **#20 — backup rules are still the AGP template** | `allowBackup="true"` with unedited `backup_rules.xml` / `data_extraction_rules.xml`, so the Room cache and DataStore prefs go to cloud backup and device transfer unfiltered | 0/1 | small | ✅ done **and device-verified 2026-07-30** — local transport, uninstall + reinstall, prefs file back byte-identical before first launch, Room DB and icon cache correctly absent; also confirmed present and intact in the **shipped release APK**, which `shrinkResources` cuts 2041 → 786 entries. ⚠️ **Do not check that with `unzip -l \| grep backup_rules`** — AGP's `optimizeReleaseResources` path-shortens the files, so a release APK has no `res/xml/` directory at all (they land as `res/Qq.xml` / `res/4j.xml`, names regenerated per build) and the grep reads as a false negative. Use `aapt2 dump resources <apk> \| grep -A1 'xml/backup_rules'`. It surfaced two *restore-only* defects, both filed in Tier 3 below and neither caused by this change |
| **#21 — make Android Lint a required CI step** | `.github/workflows/pr-ci.yml:38-41` runs `lintFossDebug` with `continue-on-error: true`; its own comment says promote it once lint is clean | 0/1 | small | ✅ done — `continue-on-error` gone, `warningsAsErrors` on, `app/lint.xml` records the three deliberate exemptions |
| **[#22 — measure the privilege cold-start cost](privilege-manager-cold-start.md)** | `PrivilegeManager` was pulled into the startup graph and nobody measured whether that costs anything; filed as "measure this", not "fix this" | 0/1 | medium — measurement first | ✅ **closed 2026-08-01 (PR #306)** — all **8 of 8** configurations measured on release-shaped builds, zero open risks. The 2026-07-30 interim verdict of *bad* is **refuted, not superseded**: the bimodal slow `root=` mode is **CPU contention, not an emulator artifact**, reproducible on demand by saturating a physical device's cores (idle 45–60 ms unimodal → loaded 71–91 / 157–205 ms). The probe-vs-first-frame race is **self-balancing** — contention slows the first frame too, so the spinner window compresses but never inverts, worst case **−1 ms** at 2× oversubscription — and the projected "~579 ms of visible spinner" never existed. The doc is **retained as the measurement record**, not as open work |
| **#28 — drop Qodana** | delete `qodana.yaml` and its follow-up doc; see *Declined* for the reasoning | 0/1 | small | ✅ done — both deleted |

---

## Tier 2 — approved, not yet scheduled

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[app-**data** backup (#51 phase 2)](app-data-backup-and-xapk-export.md)** | **narrowed 2026-07-30.** `.xapk` export (#164b) and backup **phase 1** shipped together in one branch, as the owner asked. **#164 was closed 2026-08-03 with its scope stated** — three of its four outputs shipped and the fourth (a raw split folder) is declined, so the closing comment said that rather than implying it all shipped. What is left of #51 is the root-only data half, plus two device checks a desk cannot do (an export of an app that *has* an OBB, and Thor's `manifest.json` read by SAI/APKPure rather than by Thor). The manifest already carries a `schemaVersion` and a pinned forward-compatibility test, so phase 2 can add a per-entry data file without breaking a phase-1 folder. **Carried over from the deleted export/share doc:** the OBB row in app details can never render before API 29, and the decision was **option 2 — the field stays for phase 2**; option 1's stated payoff turned out not to exist | 2 | ph.2 (root data) 5–8 d · the two verifications ≈1 h each | approved, unscheduled — the README's "BackUp App Data" promise stays half-kept until this lands. **#51 stays open tracking the data half only**, per the status comment posted 2026-08-03 |
| **[on-device trace pass](perfetto-trace-pass.md)** | one Perfetto + LeakCanary session over cold start, list scroll/refresh and a bulk freeze run | 2 | small to capture, open-ended to act on | approved, **explicitly last** — after every other change has landed. No longer carries #22's measurement work, which closed on its own |
| **[#161 — `.apks` won't open from Samsung My Files](161-apks-not-openable-from-file-managers.md)** | **diagnosed 2026-07-30**: `android:host="*"` on Thor's two wildcard filters makes their 35 pathPatterns a *mandatory* gate, and Samsung's MediaStore URI has no filename in the path — so adding extensions cannot fix it. SAI is absent from the same chooser for the same reason; the apps that do appear declare no host | 2 · 🟢 | small — run the diagnostic, then likely one MIME string | **half fixed 2026-08-07 (band A #10), still open.** The *typeless* filter's host and its 45 now-unreachable path matchers are gone — there the gate was self-defeating, since a filter that exists for URIs nobody can type was excluding exactly the opaque provider URI that case takes. The `*/*` filter keeps its host deliberately: removing it makes Thor a candidate for every typed file on the device, which the doc rightly calls user-hostile. **The `pm query-activities` diagnostic is still unrun and still decides the rest** — one added MIME type, or a `*/*` filter behind the opt-in toggle that was descoped with it. ⚠️ **The linked doc's four-filter table now predates the manifest**; read the manifest comment on the typeless filter as the current shape. Separately, Thor declares no `ACTION_SEND` at all, so the share route is a second gap and band A did not touch it. Diagnosis posted to the issue 2026-08-03 so the reporter is not waiting on silence |
| **the website still promises the escalation band A #1 removed** *(no separate doc — this row is the whole item, per the "a row without a link" rule at the top)* | Six passages describe a Shizuku freeze falling back to removing a system app for the user: `web/src/pages/index.astro:61`, `faq.mdx:130`, `features.mdx:223` and `:337`, `download.mdx:95`, `privacy.mdx:194-200`, plus the four specs they were written from (`docs/site-content/index.md:51`, `faq.md:112`, `features.md:268`, `download.md:66`). All of it was accurate when written and none of it is now. **Two separate corrections live in the same sentences**: (1) the fallback is gone under every privilege mode, and (2) *"Dhizuku … still removes without keeping data"* (`features.md:271`, `features.mdx:340`, `privacy.mdx:207`) has been false since PR #332 — `Dhizuku.freezeSystemAppForUser` passes `keepData = true` — so it is **not** band A's doing and would need fixing anyway. The behaviour change also needs a **release-notes line**, which nothing tracks yet; cf. `release-notes/v1.93.2/github.md:58`, where the last behaviour change in this same area was flagged | 2 | small — it is prose, in nine files | **filed 2026-08-07, open, and dated by a merge rather than by a decision.** ⚠️ **No gate catches it.** `web/src/content/claims.mjs` rule `C1` forbids the *opposite* error (that freezing loses your data) and carries a `NEGATED` escape, so a sentence that is out of date but reassuring is exactly the shape that passes. `web-ci.yml` proves the claims that are checked, not the claims that are true. ⚠️ **Correct the `docs/site-content/` spec and the `web/src/pages/` copy together** — the specs are what the pages were written from, and fixing only the visible half puts the next author back on the old text |
| **[deploy the site from Actions instead of Vercel's Git integration](vercel-actions-deploy.md)** | `web-deploy.yml` is a complete CLI-driven deploy pipeline that has **never run** — it finds no secrets, prints a notice and skips, so every green `Web Deploy` tick is that skip. The site went live 2026-08-02 from a portal-imported Vercel project instead, and its Git integration is now the deployer. Moving back would put the four invisible dashboard settings under review; what blunts that is `web-ci.yml`, which already runs the same gate chain from a file in git on every web push and PR | 2 | small — the workflow exists; the work is 5 owner actions and one interlock | **deferred, nothing broken.** Reactivating REQUIRES disconnecting Vercel's Git integration in the same change: both production branches are `master`, so leaving both on races two production deploys at one alias with no error anywhere. Root Directory inverts too — `web` for the Git integration, **unset** for Actions, and neither wrong value errors clearly |

---

## Tier 3 — filed and deferred, decision still open

Rows tagged 🔴 carry the roadmap's own "defer" verdict; the rest have had no owner ruling at all.

### Engineering follow-ups

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[biometric lock restores without a capability check](biometric-lock-restores-without-a-capability-check.md)** | **Major.** `biometric_lock=true` restored onto a device that cannot show a prompt hard-locked the user out — `SecurityViewModel` derived `authState` from the preference alone, so Lock ↔ Error cycled and Settings was never reachable. Most likely during setup-wizard restore, before a screen lock exists | 3 | small — one capability check | **fixed.** API 29+: `AuthState.Unavailable` + a deep link to security settings + an `onResume` re-query, so setting a lock or enrolling a biometric gets the user in. API 28 with no sensor — the one case no enrolment could fix — is the owner's 2026-07-30 call, shipped **asymmetrically**: refusing to *arm* the lock (with a toast) applies to any incapable device, disarming one that arrived anyway is confined to the unrecoverable case, because silently dropping a lock the user can still open is a security downgrade. Restore path still needs a device |
| **[a restored "already prompted" flag suppresses watchlist recovery](restored-prompt-flag-suppresses-watchlist-recovery.md)** | `has_shown_disabled_apps_prompt` was backed up; the Room watchlist it describes is deliberately not. Reinstall Thor on a device with frozen apps and the import prompt — the one recovery path — was silently pre-suppressed | 3 | small | **fixed**, and by the option the doc had written off. Both rulesets allowlist one *filename*, not the `datastore/` folder, so a second Preferences store is excluded with no ruleset change — the flag moved to `thor_local_state`. No `BackupAgent`, no nag-scope decision (behaviour is unchanged; "once per install" is merely true now). The old value is deliberately **not** migrated forward, since carrying it over would reproduce the bug for exactly the users it exists for. Restore path still needs a device |
| **[the DataStore *write* path has no guard](datastore-writes-are-unguarded.md)** | PR #339 made an unreadable settings file survivable and deliberately stopped at the read. All **25** `edit { }` blocks in `PreferenceRepositoryImpl` are still bare, every caller is `viewModelScope.launch { … }` with no `try`, and `viewModelScope` installs no `CoroutineExceptionHandler` — so an `IOException` on a full disk or a read-only filesystem **crashes Thor from a settings toggle**. Two writes fail worse than the rest: `SecurityViewModel`'s self-heal throws from inside a `collect { }`, which kills the collector for the process and strands a device that cannot open its own app lock; and `setLanguage` is one `edit { }` followed by a locale application the *platform* persists, so a throw between them leaves Thor rendering one language and reading back another | 3 | small–medium | **decision open** — filed rather than fixed because "retry the write" is a product call, not a mechanical one. Which setters are worth retrying, what the user is told, and whether the guard lives in a `guardedWrite` beside `guardedRead` (25 edits, precise) or in one `CoroutineExceptionHandler` (1 edit, silences unrelated bugs) are all open. Less urgent than #339 was: a write failure is one tap and one crash, not a loop that runs before any UI |
| **[release builds emit no Thor logcat](release-builds-emit-no-thor-logcat.md)** | `Logger` gates all five levels — **including `e`** — on `isDebug`, which is `BuildConfig.DEBUG`, and Thor makes zero direct `android.util.Log` calls. So non-fatal failures in the field are silent. Crashes are **not** affected (no custom handler, so the platform still prints them) | 3 | small | **decision open**, and possibly deliberate — logcat is world-readable and Thor's logs carry package lists and shell commands. Option 4 shares an implementation with the release-shaped `benchmark` build type that #22 added |
| **["0 errors, 0 warnings" is bounded by config, not by code](static-analysis-switched-off-by-default.md)** | `:app` is clean on all five variants and enforces it with `warningsAsErrors` — and that headline was **bounded by what lint was pointed at**. Measured during the v1.93.3 sweep: **292 findings switched off** (273 `:app` on `storeDebug`, 18 `:bypass`, 1 `:vm-runtime` javac). Two closed in that sweep — `checkTestSources` is now on (61 unit-test files and the whole `androidTest` tree had been analysed by *nothing*) and `:bypass` got a `lint {}` block pinning the clean state it was already in. What is left is one check worth enabling (`SyntheticAccessor`, 43+18, method count against the 64K limit), two cosmetic ones that dominate the count (`TypographyQuotes` 127, `DuplicateStrings` 82), and one that is a false positive by construction | 3 | small — what is left is `:app`'s 43 | **`:bypass` closed 2026-08-07 (band A #12); the rest is still open.** That module now enables `SyntheticAccessor` by id and carries the six `private`→`internal` widenings in the same change, taking the switched-off total from 292 to **274**. ⚠️ **18 findings from six methods is the measured number.** A mid-wave recount derived 9 from 4 by disassembling the detector, and it was wrong: the companion bail-out is `getContainingUClass() == "Companion"`, which a companion `val` initializer does **not** satisfy, because Kotlin hosts that field on the outer class. Re-measured by reverting all six to `private`. A derived count is not a measured count. ⚠️ The counts are **not additive** — `checkAllWarnings` alone 253, `checkTestSources` alone 9, both together **266**, not 262 — so any repro must name its variant *and* flag set. Do **not** flip `checkAllWarnings` globally: under the existing `warningsAsErrors` it turns 253 findings into an instantly red build, and only 4 of the registry's 39 disabled checks fire here at all. Enable `SyntheticAccessor` by id in `lint.xml` instead. The Kotlin half has no gate at all: `allWarningsAsErrors` is **blocked** by the Koin plugin's version-mismatch warning (trivial in itself — owner's call 2026-08-05 — but it would fail every build), so "zero compiler warnings", true as of v1.93.3, is a state nothing enforces. Filed 2026-08-05 |
| **[cross-privilege suspend ownership](cross-privilege-suspend-ownership.md)** | an app suspended under Root could not be unsuspended under Shizuku, and vice versa — **and Thor reported every such unsuspend as a success**, because lifting a suspension you do not own returns an *empty* failure array | 3 | shipped | ✅ **implemented in PR #330**, shipped in v1.93.2 — none of the three filed options; a fourth, *read* the recorded suspender via `dumpsys` instead of inferring it. Also corrected the doc's premise: "only the recorder can lift it" is **version-dependent** (false below API 30, true from 30). ⚠️ **Row stays open: zero device testing.** Highest-risk unverified branches are the API 35+ `<0>`-keyed dump shape and the sub-30 path, where a wrong *refusal* is as much a bug as a wrong success |
| **[changing to a cheaper support tier uses an upgrade-shaped replacement mode](subscription-downgrade-replacement-mode.md)** | `launchBillingFlow` hardcodes `CHARGE_PRORATED_PRICE` for every plan change in **either** direction, comparing nothing. **Not a regression from #351** — verified: `dev` already shipped four tiers (5/10/25/50), so a `_50` subscriber could already tap `_5`; the dynamic catalogue only widens which pairs are reachable by adding `_1`/`_2`/`_3` below the old floor. An adversarial review raised it against #351 and refuted it *as a finding against that diff*, correctly — which is not the same as fine | 3 | small once the mode is chosen | **decision open, and needs a device before a fix.** ⚠️ The mode set is `javap`-verified from `billing-9.1.0.aar`; that Play **rejects** a downgrade under mode 2 is **not** — it is Google's documented reading, untested here, and the `.aar` carries no doc text. It may instead succeed with an unexpected proration, which is the worse outcome. `DEFERRED` vs `WITH_TIME_PRORATION` is a product call. Filed 2026-08-05 |
| **["remove it for this user anyway" — the consent path band A #1 left behind](freeze-refusal-remove-for-user-consent.md)** | band A #1 removed the freeze → uninstall escalation, which also removed a capability some users want: *"remove this system app for my user, keeping its data"*. They objected to it happening silently under a button labelled **Freeze**, not to it existing. The escalation code is still in the tree, unreachable but statically referenced, precisely so this path has something to call | 3 | small to build, **blocked on four product calls** | **filed 2026-08-07, open, and a product decision before it is a build.** ⚠️ It cannot reuse `SystemGateway.uninstallApp` — that is a plain `pm uninstall` with **no `-k`**, so wiring consent to it would turn "remove it for me" into "delete my data on every user", which is worse than what was just removed. The four open questions are what the watchlist shows for a removed-but-not-frozen app, whether unfreeze restores it, what the five headless surfaces do (they cannot ask), and whether a device reproduction is required first. **Do not delete the escalation code to tidy up** — if this is ever declined, the deletion has to take `freeze_system_app_requires_root` and `freeze_system_app_removal_failed` with it, since `UnusedResources` is fatal in `:app` |
| **[freezer membership toggle semantics](freezer-membership-toggle-semantics.md)** | one snowflake control, two meanings — the Apps tab removes from the watchlist, the Freezer tab removes **and thaws** | 3 | small once the semantics are chosen | product decision, open. **Band A #4 closed the *surprise*, not this row** — every add from the Manage sheet now confirms before it freezes, which is what the r/howtomen reporter hit. The two meanings are untouched, and the add-side asymmetry is now more visible rather than less: a user who learns "adding freezes it" from that dialog will expect the Apps tab to do the same, and it does not |
| **[freezer removal has no escape hatch](freezer-removal-has-no-escape-hatch.md)** | GH#310's fix makes "removing from the watchlist always restores" a real invariant, so an app that persistently refuses to thaw is now a row the user cannot delete | 3 | small once the semantics are chosen | product decision, open — **deliberately not taken inside the #310 fix**, since "remove anyway" is a new destructive user-facing action and reintroduces the orphan state #310 exists to prevent. Option 3 (prune rows whose package is no longer installed) is independent of the others and safe to land alone. GH#310 itself is **closed 2026-08-03**; this row is what it left behind, and nothing on GitHub tracks it |
| **[`lastResult` has no expiry or invalidation](freezer-bulk-run-deferred-review-findings.md)** (§1) | a "Froze N apps" result survives for the process lifetime and is not cleared by the unfreeze paths that skip the runner | 3 | medium | wants the runner's tests first |
| **[odin root availability cache](odin-root-availability-cache.md)** | root revoked mid-session still reads as available until restart; the real fix is in Odin, not Thor | 3 | small in Thor, medium in Odin | upstream |
| **[the freeze-profile editor dismisses before its save lands](profile-editor-dismisses-before-the-save-lands.md)** | the editor closes in the same frame it dispatches the write, so a `UNIQUE` name collision or a Room error toasts *after* the draft is gone — worst for the user who ticked forty apps | 3 | small to medium | filed from #295's review; the shape of the fix is a UX call (dismiss-on-success vs optimistic-with-restore) |
| **[`BulkFreezeRunner` concurrency tests](bulk-freeze-runner-concurrency-tests.md)** | the only stateful concurrent class has zero tests, and both defects it shipped with were `runTest`-shaped | 3 | medium | **still blocked, for a different reason than the doc used to give** — #16 landed, but four collaborators are final concrete classes over `Context`/`PackageManager`, so the runner cannot be built in a JVM test. Needs a seam in main source first. **One of the four is already solved**: the runner's only use of `PrivilegeManager` is `state`, and `PrivilegeStateProvider` — shipped with the ViewModel behaviour tests — is exactly that port, taking it to three. `AppFreezeStateReader`, `UadHelper` and `BulkResultNotifier` still need one. **[`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md) is kept on disk as this row's worked example** even though that work shipped; it sets the mutation-checking bar this row has to meet, and `app/build.gradle.kts:317` cites it too |

### Feature requests and standing promises

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **#130 — installer attribution + drill-down** | the friendly installer label is the achievable slice; the drill-down nav is the bulk | 3 · 🟡 | 1–2 d (label ≈0.25 d) | slice worth doing, unscheduled |
| **#58 — app lock** | the whole launch-detection + overlay pipeline is net-new, plus a Play-policy risk and an ongoing maintenance tax | 3 · 🔴 | 8–15 d | roadmap says defer |
| **#178 — app tagging**, and per-app **notes** with it | low-risk build, one Room migration, and the two asks share all of it | 3 · 🔴 | 3–5 d for both | ⚠️ **the deferral reason is spent.** It was deferred as *"zero demand"*; 2026-08-07 brought two independent requests (tags, and notes over the same storage). The **cost** objection stands and may still justify deferring — but it now has to be deferred on cost. `bundle with app-list UX work if demand appears` has been discharged: demand appeared |
| **#209 — VirusTotal scanner** | an entire network stack, a user-supplied API key, and third-party upload privacy | 3 · 🔴 | 4–7 d | roadmap says defer |
| **#55b — process manager (RAM/CPU)** | fragile `dumpsys`/`top` parsing, root/Shizuku-only, Dhizuku dead-end | 3 · 🔴 | 4–7 d full · **flag: unsized** | **corroborated, and narrower than it was filed.** A user asked for it 2026-08-07 — but asked *"options for know app running in background"*, not for RAM/CPU. The `top`/`meminfo` parsing, the sampled CPU polling and the OEM drift are objections to the **stats**, so split it: the flag is band D and the full manager stays deferred. ⚠️ **Smaller is not costed** — `getRunningAppProcesses` is privilege-filtered (restricted since Android 5.1; an unprivileged caller does not get other packages), so the flag also needs a privileged `ps`/`dumpsys`, and **Dhizuku is a dead-end for it too**. Name the API and the fallback before ranking it. **#55a — freeze profiles — shipped in v1.93.1 (PR #295, merged 2026-07-30)**, which is why #55 stays open on this half alone; status comment posted to the issue 2026-08-03 |

### User-reported, 2026-08-07 (r/howtomen)

All twenty-three asks and their verification are in
[`reddit-howtomen-feedback.md`](reddit-howtomen-feedback.md); the ones that are not already covered
by a row above are listed here. **Nothing in this block was a defect** — the one report that looked
like one is intended behaviour. Eleven of the twelve items band A drew from this block **shipped the
same day**, on `feat/band-a`; the status column says which, and the band A table above is where the
detail lives.

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[a freeze that silently *removes* the app for the user](reddit-howtomen-feedback.md)** | on OEM builds that refuse `pm disable-user`, freezing escalated to `pm uninstall -k --user N`, which clears `FLAG_INSTALLED`. A user reported losing their **Google accounts** to it when freezing Play Services — and, consistently, seeing none of the notification spam Hail's disable produces | 3 | small–medium | ✅ **shipped as band A #1 — and not the way this row proposed.** It said *"the fix is disclosure plus an opt-out, not removing the fallback"*; what shipped **is** removing the fallback, under every privilege mode, because an escalation nobody can see is not made safe by describing it. ⚠️ **The mechanism is still inferred, not measured** — nothing was reproduced on a device that actually refuses to disable, so the reason to change it was the silence, not a confirmed account-loss path. What that leaves behind has [its own row](freeze-refusal-remove-for-user-consent.md) |
| **[UAD tier + description in the list](reddit-howtomen-feedback.md)** | the safety label appeared only in an app's detail page, so it was invisible during exactly the bulk debloat where it matters | 3 | small | ✅ **shipped as band A #2 and #6.** ⚠️ **This row's premise was wrong in a way worth keeping**: it said `UadSnapshot` exposes only `removal`, *"so the data is read and discarded"*. `UadSnapshot` does — but the app list never goes through it. `AppInfo.bloatDescription` was already populated on `origin/dev`, so this needed no accessor, no new field and no plumbing. Cheaper than "rendering work only" implied |
| **[no in-app explanation of Force Stop / Suspend / Freeze](reddit-howtomen-feedback.md)** | three destructive-looking verbs offered with labels and icons and nothing else | 3 | small | ✅ shipped as band A #3 — long-press for an explain-only sheet, scoped to those three tiles because those are the three strings that exist |
| **[Freeze Profiles are undiscoverable](reddit-howtomen-feedback.md)** | "custom tabs/groups in the Freezer with group actions" — most of which shipped in v1.93.1 as #55a, requested anyway by a user who had read the screen carefully | 3 | small | ✅ shipped as band A #5. Per-group *kill*/*suspend* is the genuine remainder and stays open at band B #17 |
| **[Fix Store is opaque, all-or-nothing and uncancellable](reddit-howtomen-feedback.md)** | *"forces the installer record"* is accurate but jargon; a user ran it and had every sideloaded app's installer rewritten from InstallerX to Play. **Confirmed: the target set is computed, never chosen** (`MainViewModel.kt:459-471`) | 3 | medium | open — band B #14, the largest untouched item from this thread |
| **app-list UX cluster** | default tab · [copy package name](reddit-howtomen-feedback.md) · icon size / columns · quick scrollbar with sort-aware snapping · optional per-freeze confirmation | 3 | trivial→medium each | **three of five shipped** — default tab (band A #8), copy package name (#9) and grid density (#11). The scrollbar (band B #19) and the per-freeze confirmation (band B #18) stay open, and #18 grew a second dialog it must not silence. Band A #7 kept `SortBy`'s four family predicates alive precisely because they are the scrollbar's seam |
| **[change history and update history](reddit-howtomen-feedback.md)** | "last week you installed X, froze Z", and "what version was this before the update that broke it" | 3 | medium–large | band C #25. **One piece of work, not two** — Room is at v6 with no event table, and `AppEntity` overwrites `versionCode`/`versionName` on every scan, so neither question has anywhere to be answered from today |
| **[export the app list to CSV/MD](reddit-howtomen-feedback.md)** | package names, versions, frozen status | 3 | small–medium | band B #20 — the SAF picker and write plumbing already shipped with #164/#51 |
| **[App Ops–style permission grant/revoke](reddit-howtomen-feedback.md)** | grant or revoke permissions the system greys out | 3 | large | band C #28 — genuinely new. Thor filters *by* permission (#285) and grants via `pm grant`; it cannot revoke |
| **[abandoned-app notifications](reddit-howtomen-feedback.md)** | "unused for 6 months — uninstall or freeze?" | 3 | medium | band C #27 — **re-priced while sizing it.** `UsageAccessManager` already holds the permission, the privileged silent grant, the re-verification and the `ACTION_USAGE_ACCESS_SETTINGS` fallback, wired into two screens. What is missing is `UsageStatsManager.queryUsageStats`, a schedule and a notification |
| **[Portuguese translation](reddit-howtomen-feedback.md)** | Thor ships `en` + `ar`, `es`, `fr`, `zh-rCN` | 3 | 508 strings | band C #29 — mechanical, not small, and it is a whole locale per translator forever after. Band A took all five files from 481 to 508 names in one pass, which is the shape of the recurring cost. A community PR is the natural route |
| **Editing `packages.xml`** | listed under *Upcoming Features* in the project `README.md`. No issue, no design, no doc | 3 | unsized | no decision |
| **Batch install** | listed under *Upcoming Features* in the project `README.md`. No issue, no design, no doc | 3 | unsized | no decision |
| **Authenticated extension trigger** | *Upcoming Features*: replace the removed public `thor://extension/trigger` deep link with an explicit-component intent or a nonce-signed token | 3 | unsized | no decision — but the insecure version is already gone, so this is an addition, not a fix |

---

## Explicitly declined / closed

Settled. If one of these comes back, it needs new evidence, not a new opinion.

| Item | Why it was rejected |
|---|---|
| **Qodana, in both linters** | `qodana-jvm-community` ships no AGP, so `:app` syncs with empty source roots and the scan analyses **zero `.kt` files** while still exiting 0 — a silently vacuous check. It also rewrites `.idea/` JDK/bytecode defaults on every run. `qodana-jvm-android` has no native mode and is Docker-only. Declined 2026-07-29 (#28); `./gradlew lint` plus forced-recompile Kotlin compiler warnings cover the gap |
| **Lowering Asgard's `minSdk` below 28** | Owner's call — Asgard stays as it is for now. It is a separate repo (`com.trinadhthatakula:asgard`), so nothing in Thor changes either way |
| **Accessibility-based auto-refreeze (#210)** | No public API detects removal-from-recents; it needs Accessibility or UsageStats polling, at a battery and Play-policy cost. The achievable slice — Freeze\|Suspend mode, plus the launcher bridge that unfreezes on tap — shipped instead. **#210 closed 2026-08-03**, with the closing comment splitting the request in two so the shipped half is not mistaken for the declined one |
| **Bespoke phone-to-phone transfer (#51 phase 3)** | 12–20 days to rebuild what Nearby Share already does; the exported file rides the share sheet today |
| **Raw split-folder export (#164)** | Thor picks the bundle format from the app's shape, which is the better default. The *picker* half of this row is no longer declined — `.xapk` did turn it into a genuine choice, so the sheet now offers the native container plus `.xapk`. The raw split folder stays out. **#164 closed 2026-08-03** with exactly this scope stated |
| **`InstallWithOptions` attribution (#130 part 1)** | Shell-based installs record `com.android.shell` or null, so attribution is unreliable no matter how much effort goes in. The friendly label ships; the attribution claim does not |

---

## Tracked elsewhere

Two items are real but cannot be actioned from this repo:

- **The residual `MainShell` shell-init hang** — fixed user-visibly, not at thread level. Its Thor
  follow-up was deleted when Odin Phase 3 removed `:suCore`; it lives on as Step F of Odin's
  shell-modernization plan.
- **The root-availability cache** above — Thor can only add the re-probe call; the cache
  invalidation has to happen in Odin.

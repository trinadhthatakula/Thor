# Thor's deferred work, in one table

Everything Thor has put off, in one place: the write-ups in this directory, the open feature requests
in [`../feature-request-roadmap.md`](../feature-request-roadmap.md), and the standing promises in the
project `README.md`. **One line per item** — the detail lives in the linked doc, and the linked doc is
the thing to update. A row without a link is an item whose whole content is the line you are reading.

**Tiers.** `0/1` being built right now · `2` approved, not scheduled · `3` filed, decision still open ·
*declined* ruled out, do not re-raise. Where a row also carries a roadmap colour (🟢 do-first ·
🟡 scope carefully · 🔴 defer), that colour is the roadmap's own verdict, not a second opinion.

**Last swept:** 2026-08-03. The Kotlin sources still contain **no `TODO`/`FIXME`/`HACK`/`XXX` markers
at all** — re-checked this sweep, zero matches across `app/src/main/java` and `bypass`. So nothing
below was found by grepping the code; every row came from a doc, the roadmap, or the project
`README.md`.

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
| **[#161 — `.apks` won't open from Samsung My Files](161-apks-not-openable-from-file-managers.md)** | **diagnosed 2026-07-30**: `android:host="*"` on Thor's two wildcard filters makes their 35 pathPatterns a *mandatory* gate, and Samsung's MediaStore URI has no filename in the path — so adding extensions cannot fix it. SAI is absent from the same chooser for the same reason; the apps that do appear declare no host | 2 · 🟢 | small — run the diagnostic, then likely one MIME string | **ready to fix**, but run the `pm query-activities` diagnostic first: it decides between one added MIME type and a `*/*` filter that needs an opt-in toggle. Separately, Thor declares no `ACTION_SEND` at all (confirmed: zero matches in the manifest), so the share route is a second gap. Diagnosis posted to the issue 2026-08-03 so the reporter is not waiting on silence |
| **[deploy the site from Actions instead of Vercel's Git integration](vercel-actions-deploy.md)** | `web-deploy.yml` is a complete CLI-driven deploy pipeline that has **never run** — it finds no secrets, prints a notice and skips, so every green `Web Deploy` tick is that skip. The site went live 2026-08-02 from a portal-imported Vercel project instead, and its Git integration is now the deployer. Moving back would put the four invisible dashboard settings under review; what blunts that is `web-ci.yml`, which already runs the same gate chain from a file in git on every web push and PR | 2 | small — the workflow exists; the work is 5 owner actions and one interlock | **deferred, nothing broken.** Reactivating REQUIRES disconnecting Vercel's Git integration in the same change: both production branches are `master`, so leaving both on races two production deploys at one alias with no error anywhere. Root Directory inverts too — `web` for the Git integration, **unset** for Actions, and neither wrong value errors clearly |

---

## Tier 3 — filed and deferred, decision still open

Rows tagged 🔴 carry the roadmap's own "defer" verdict; the rest have had no owner ruling at all.

### Engineering follow-ups

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[biometric lock restores without a capability check](biometric-lock-restores-without-a-capability-check.md)** | **Major.** `biometric_lock=true` restored onto a device that cannot show a prompt hard-locked the user out — `SecurityViewModel` derived `authState` from the preference alone, so Lock ↔ Error cycled and Settings was never reachable. Most likely during setup-wizard restore, before a screen lock exists | 3 | small — one capability check | **fixed.** API 29+: `AuthState.Unavailable` + a deep link to security settings + an `onResume` re-query, so setting a lock or enrolling a biometric gets the user in. API 28 with no sensor — the one case no enrolment could fix — is the owner's 2026-07-30 call, shipped **asymmetrically**: refusing to *arm* the lock (with a toast) applies to any incapable device, disarming one that arrived anyway is confined to the unrecoverable case, because silently dropping a lock the user can still open is a security downgrade. Restore path still needs a device |
| **[a restored "already prompted" flag suppresses watchlist recovery](restored-prompt-flag-suppresses-watchlist-recovery.md)** | `has_shown_disabled_apps_prompt` was backed up; the Room watchlist it describes is deliberately not. Reinstall Thor on a device with frozen apps and the import prompt — the one recovery path — was silently pre-suppressed | 3 | small | **fixed**, and by the option the doc had written off. Both rulesets allowlist one *filename*, not the `datastore/` folder, so a second Preferences store is excluded with no ruleset change — the flag moved to `thor_local_state`. No `BackupAgent`, no nag-scope decision (behaviour is unchanged; "once per install" is merely true now). The old value is deliberately **not** migrated forward, since carrying it over would reproduce the bug for exactly the users it exists for. Restore path still needs a device |
| **[the DataStore *write* path has no guard](datastore-writes-are-unguarded.md)** | PR #339 made an unreadable settings file survivable and deliberately stopped at the read. All **25** `edit { }` blocks in `PreferenceRepositoryImpl` are still bare, every caller is `viewModelScope.launch { … }` with no `try`, and `viewModelScope` installs no `CoroutineExceptionHandler` — so an `IOException` on a full disk or a read-only filesystem **crashes Thor from a settings toggle**. Two writes fail worse than the rest: `SecurityViewModel`'s self-heal throws from inside a `collect { }`, which kills the collector for the process and strands a device that cannot open its own app lock; and `setLanguage` is two writes that must both land | 3 | small–medium | **decision open** — filed rather than fixed because "retry the write" is a product call, not a mechanical one. Which setters are worth retrying, what the user is told, and whether the guard lives in a `guardedWrite` beside `guardedRead` (25 edits, precise) or in one `CoroutineExceptionHandler` (1 edit, silences unrelated bugs) are all open. Less urgent than #339 was: a write failure is one tap and one crash, not a loop that runs before any UI |
| **[release builds emit no Thor logcat](release-builds-emit-no-thor-logcat.md)** | `Logger` gates all five levels — **including `e`** — on `isDebug`, which is `BuildConfig.DEBUG`, and Thor makes zero direct `android.util.Log` calls. So non-fatal failures in the field are silent. Crashes are **not** affected (no custom handler, so the platform still prints them) | 3 | small | **decision open**, and possibly deliberate — logcat is world-readable and Thor's logs carry package lists and shell commands. Option 4 shares an implementation with the release-shaped `benchmark` build type that #22 added |
| **[two branches, one Play version code](two-branches-one-play-version-code.md)** | `master` and `production` both upload the same `chore(release)` code to Play, which allows a code exactly once app-wide, so one of the two runs must fail | 3 | small once the flow is chosen | **decision open** — surfaced by #5's fix, which covers every *non*-release push but cannot cover this. Option 1 (promote `alpha` → `beta`) is the only one keeping one artifact across both tracks |
| **[Telegram caption length is unchecked and fails silently](telegram-caption-length-guard.md)** | release notes ride a `sendDocument` **caption**. Telegram rejects anything over **1024 UTF-16 units** rather than truncating, and the `curl` has no `--fail` with output discarded — so an oversized caption posts **nothing** and the step still goes green | 3 | small | filed 2026-08-03, **never indexed here until this sweep**. v1.93.2 shipped at **977 of 1024**; a one-line accuracy fix in review took it to **1006** mid-cycle and only a hand-run `python3` count caught it. The guard belongs in a **pre-flight** step, not next to the `curl`: in `dev-check.yml` the Telegram step runs *after* the Play upload and *before* the GitHub release, so a guard bolted on there fails a half-published release. The same step should check `playstore.txt` < 500 chars, which CI also never checks |
| **[cross-privilege suspend ownership](cross-privilege-suspend-ownership.md)** | an app suspended under Root could not be unsuspended under Shizuku, and vice versa — **and Thor reported every such unsuspend as a success**, because lifting a suspension you do not own returns an *empty* failure array | 3 | shipped | ✅ **implemented in PR #330**, shipped in v1.93.2 — none of the three filed options; a fourth, *read* the recorded suspender via `dumpsys` instead of inferring it. Also corrected the doc's premise: "only the recorder can lift it" is **version-dependent** (false below API 30, true from 30). ⚠️ **Row stays open: zero device testing.** Highest-risk unverified branches are the API 35+ `<0>`-keyed dump shape and the sub-30 path, where a wrong *refusal* is as much a bug as a wrong success |
| **[freezer membership toggle semantics](freezer-membership-toggle-semantics.md)** | one snowflake control, two meanings — the Apps tab removes from the watchlist, the Freezer tab removes **and thaws** | 3 | small once the semantics are chosen | product decision, open |
| **[freezer removal has no escape hatch](freezer-removal-has-no-escape-hatch.md)** | GH#310's fix makes "removing from the watchlist always restores" a real invariant, so an app that persistently refuses to thaw is now a row the user cannot delete | 3 | small once the semantics are chosen | product decision, open — **deliberately not taken inside the #310 fix**, since "remove anyway" is a new destructive user-facing action and reintroduces the orphan state #310 exists to prevent. Option 3 (prune rows whose package is no longer installed) is independent of the others and safe to land alone. GH#310 itself is **closed 2026-08-03**; this row is what it left behind, and nothing on GitHub tracks it |
| **[`lastResult` has no expiry or invalidation](freezer-bulk-run-deferred-review-findings.md)** (§1) | a "Froze N apps" result survives for the process lifetime and is not cleared by the unfreeze paths that skip the runner | 3 | medium | wants the runner's tests first |
| **[odin root availability cache](odin-root-availability-cache.md)** | root revoked mid-session still reads as available until restart; the real fix is in Odin, not Thor | 3 | small in Thor, medium in Odin | upstream |
| **[sort labels are hardcoded English](sort-labels-are-hardcoded-english.md)** | `SortBy.asGeneralName()` returns string literals and `AppList` renders them with no `stringResource`, so the filter sheet's Sort tab is English in every locale. The identical bug in `FilterType.asGeneralName()` was fixed in #285 because that PR had to touch the function anyway; this one it does not | 3 | small — 8 strings × 5 locales, plus one dead function to delete | **unblocked and mechanical**; `FilterType.kt` is the worked example. Deferred only to keep 40 unreviewed translation entries out of a PR about permissions |
| **[the freeze-profile editor dismisses before its save lands](profile-editor-dismisses-before-the-save-lands.md)** | the editor closes in the same frame it dispatches the write, so a `UNIQUE` name collision or a Room error toasts *after* the draft is gone — worst for the user who ticked forty apps | 3 | small to medium | filed from #295's review; the shape of the fix is a UX call (dismiss-on-success vs optimistic-with-restore) |
| **[`BulkFreezeRunner` concurrency tests](bulk-freeze-runner-concurrency-tests.md)** | the only stateful concurrent class has zero tests, and both defects it shipped with were `runTest`-shaped | 3 | medium | **still blocked, for a different reason than the doc used to give** — #16 landed, but four collaborators are final concrete classes over `Context`/`PackageManager`, so the runner cannot be built in a JVM test. Needs a seam in main source first. **One of the four is already solved**: the runner's only use of `PrivilegeManager` is `state`, and `PrivilegeStateProvider` — shipped with the ViewModel behaviour tests — is exactly that port, taking it to three. `AppFreezeStateReader`, `UadHelper` and `BulkResultNotifier` still need one. **[`viewmodel-behavior-tests.md`](viewmodel-behavior-tests.md) is kept on disk as this row's worked example** even though that work shipped; it sets the mutation-checking bar this row has to meet, and `app/build.gradle.kts:317` cites it too |

### Feature requests and standing promises

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **#130 — installer attribution + drill-down** | the friendly installer label is the achievable slice; the drill-down nav is the bulk | 3 · 🟡 | 1–2 d (label ≈0.25 d) | slice worth doing, unscheduled |
| **#58 — app lock** | the whole launch-detection + overlay pipeline is net-new, plus a Play-policy risk and an ongoing maintenance tax | 3 · 🔴 | 8–15 d | roadmap says defer |
| **#178 — app tagging** | low-risk build, **zero demand** | 3 · 🔴 | 3–5 d | roadmap says defer; bundle with app-list UX work if demand appears |
| **#209 — VirusTotal scanner** | an entire network stack, a user-supplied API key, and third-party upload privacy | 3 · 🔴 | 4–7 d | roadmap says defer |
| **#55b — process manager (RAM/CPU)** | fragile `dumpsys`/`top` parsing, root/Shizuku-only, Dhizuku dead-end | 3 · 🔴 | 4–7 d | roadmap says defer. **#55a — freeze profiles — shipped in v1.93.1 (PR #295)**, which is why #55 stays open on this half alone; status comment posted to the issue 2026-08-03 |
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

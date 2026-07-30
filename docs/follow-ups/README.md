# Thor's deferred work, in one table

Everything Thor has put off, in one place: the write-ups in this directory, the open feature requests
in [`../feature-request-roadmap.md`](../feature-request-roadmap.md), and the standing promises in the
project `README.md`. **One line per item** — the detail lives in the linked doc, and the linked doc is
the thing to update. A row without a link is an item whose whole content is the line you are reading.

**Tiers.** `0/1` being built right now · `2` approved, not scheduled · `3` filed, decision still open ·
*declined* ruled out, do not re-raise. Where a row also carries a roadmap colour (🟢 do-first ·
🟡 scope carefully · 🔴 defer), that colour is the roadmap's own verdict, not a second opinion.

**Last swept:** 2026-07-30. The Kotlin sources contain **no `TODO`/`FIXME`/`HACK`/`XXX` markers at
all**, and the one deferral marker that did exist anywhere in the tree — the AGP template `TODO` in
`res/xml/data_extraction_rules.xml` — was item #20, now fixed. So nothing below was found by
grepping the code; every row came from a doc, the roadmap, or the project `README.md`. When you file
a new follow-up, add a row here; when one ships, delete the row *and* the doc.

---

## Tier 0/1 — landed in the `chore/tier0-batch-1` batch

Numbers are the 2026-07-29 deferred-items sweep. All eight are **done in code**; do not start them
again. **The owner's device pass ran 2026-07-30: #17, #18 and #20 all verified.** The one residue
left is #22, whose instrumentation shipped but whose measurement is only 2 configurations deep —
that row says so, and the numbers so far are bad.

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **#14 — `ExtensionOpsProvider` match flags** — scoped out of [shortcut-match-flags-system-apps.md](shortcut-match-flags-system-apps.md) | `anyFrozen` saw only the `pm disable` half of a freeze, so an all-system-app target read as *not* frozen and extension `toggle` re-froze instead of thawing | 0/1 | small | ✅ done — `MATCH_UNINSTALLED_PACKAGES or MATCH_DISABLED_COMPONENTS`, covered by `ExtensionOpsGateTest` |
| **#16 — unit-test dependencies** | add `kotlinx-coroutines-test` + turbine; three test follow-ups are blocked on nothing else | 0/1 | small | ✅ done — and used: the suite went 104 → 209 tests |
| **#17 — `grantPermission` omits `--user`** | `pm grant` defaults to user 0 in all three gateways, so it targets the wrong user on work-profile / multi-user devices | 0/1 | small | ✅ **done & closed** — all three gateways derive the id from the package's own uid (`userIdOf(uid)`), not `myUserId()`; **owner-verified on device 2026-07-30**, doc deleted. The reasoning survives in each gateway's KDoc at the call site |
| **#18 — single-app freeze tier gate** | the three single-app freeze paths check nothing; what stops a `BLOCKED` app being frozen is a dialog that renders no confirm button | 0/1 | small | ✅ **done & closed** — `FreezeAppUseCase` + 16 tests; **owner-verified on device 2026-07-30**: a BLOCKED system app is refused with the blocked dialog rather than frozen. Doc deleted |
| **#20 — backup rules are still the AGP template** | `allowBackup="true"` with unedited `backup_rules.xml` / `data_extraction_rules.xml`, so the Room cache and DataStore prefs go to cloud backup and device transfer unfiltered | 0/1 | small | ✅ done **and device-verified 2026-07-30** — local transport, uninstall + reinstall, prefs file back byte-identical before first launch, Room DB and icon cache correctly absent; also confirmed present and intact in the **shipped release APK**, which `shrinkResources` cuts 2041 → 786 entries. ⚠️ **Do not check that with `unzip -l \| grep backup_rules`** — AGP's `optimizeReleaseResources` path-shortens the files, so a release APK has no `res/xml/` directory at all (they land as `res/Qq.xml` / `res/4j.xml`, names regenerated per build) and the grep reads as a false negative. Use `aapt2 dump resources <apk> \| grep -A1 'xml/backup_rules'`. It surfaced two *restore-only* defects, both filed in Tier 3 below and neither caused by this change |
| **#21 — make Android Lint a required CI step** | `.github/workflows/pr-ci.yml:38-41` runs `lintFossDebug` with `continue-on-error: true`; its own comment says promote it once lint is clean | 0/1 | small | ✅ done — `continue-on-error` gone, `warningsAsErrors` on, `app/lint.xml` records the three deliberate exemptions |
| **[#22 — measure the privilege cold-start cost](privilege-manager-cold-start.md)** | `PrivilegeManager` was pulled into the startup graph and nobody measured whether that costs anything; filed as "measure this", not "fix this" | 0/1 | medium — measurement first | ✅ instrumentation done; **measurement started — 2 of 8 configurations, and root-granted comes back _bad_**: the root probe takes 627–789 ms in 6 of 10 cold starts, bimodal, ~99% of `probe total`. Confound 7 (two callers racing Odin's synchronized shell) **is now removed** — `HomeActivity` reads `PrivilegeManager`'s result instead of probing independently — and a store-only `benchmark` build type carries the trace into a release-shaped build. Both invalidate the config-1 numbers for comparison: **re-run config 1 first**, expecting unimodal-slow, then six configurations still need a device |
| **#28 — drop Qodana** | delete `qodana.yaml` and its follow-up doc; see *Declined* for the reasoning | 0/1 | small | ✅ done — both deleted |

---

## Tier 2 — approved, not yet scheduled

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[`.xapk` export + app-data backup](app-data-backup-and-xapk-export.md)** — paired on purpose | roadmap #164b + #51. Owner's call: these ship **together**, one branch — backup phase 1 is a batch wrapper over the same bundle builder the `.xapk` writer touches. #51 is also the README's oldest promise ("BackUp App Data", ~1 year under *Upcoming Features*) | 2 · 🟢 | `.xapk` ≈0.5 d · backup ph.1 ≈1 d · ph.2 (root data) 5–8 d | approved, unscheduled |
| **[on-device trace pass](perfetto-trace-pass.md)** | one Perfetto + LeakCanary session over cold start, list scroll/refresh and a bulk freeze run | 2 | small to capture, open-ended to act on | approved, **explicitly last** — after every other change has landed |
| **[`PrivilegeManager` cold-start measurement](privilege-manager-cold-start.md)** | #22's other half: the debug-only `PrivilegeProbeTrace` instrumentation shipped, and the first numbers are now in — **root-granted is over budget on three of five signals** | 2 | ~30 min per privilege configuration | **2 of 8 configurations done**, and both code blockers are cleared (duplicate probe deleted, `benchmark` build type added). Next, in order: re-run config 1 with `am start -W -n` and check the *shape* — unimodal-slow confirms the race was the cause; then the same run on `assembleStoreBenchmark` to read the release spinner window directly instead of projecting it; then config 8 (the floor). Run inside the trace pass above |
| **#55a — freeze profiles** | named groups of apps you can freeze/unfreeze on demand; two new tables + a join (not a `profileId` on `freezer_apps`, which would make membership exclusive), `@AutoMigration(5→6)` is legitimate **provided nothing back-fills a default profile** | 2 · 🟢 | **3.5–4 d**, not 2–3 | scoped 2026-07-30, unstarted. Three risks the roadmap missed: a profile freeze that bypasses `targetsFor` loses the `FreezeTier.BLOCKED` gate (the bug the QS tile already shipped once); `BulkFreezeRunner`'s job slot coalesces on `BulkOp` alone, so freezing profile B right after A silently no-ops; and `FreezerBridgeProvider` won't restore apps absent from the watchlist, stranding profile-only apps. Good moment to take the four-interface seam from the row below |
| **[#161 — `.apks` won't open from Samsung My Files](161-apks-not-openable-from-file-managers.md)** | **diagnosed 2026-07-30**: `android:host="*"` on Thor's two wildcard filters makes their 35 pathPatterns a *mandatory* gate, and Samsung's MediaStore URI has no filename in the path — so adding extensions cannot fix it. SAI is absent from the same chooser for the same reason; the apps that do appear declare no host | 2 · 🟢 | small — run the diagnostic, then likely one MIME string | **ready to fix**, but run the `pm query-activities` diagnostic first: it decides between one added MIME type and a `*/*` filter that needs an opt-in toggle. Separately, Thor declares no `ACTION_SEND` at all, so the share route is a second gap |
| **#285 — filter the app list by permission** | additive UI *if* permission data is already available where the list is built; otherwise it is a Room schema change | 2 · 🟢 | 1–2 d, **estimate pending scope validation** | ranked 🟢, GitHub issue still untriaged |

---

## Tier 3 — filed and deferred, decision still open

Rows tagged 🔴 carry the roadmap's own "defer" verdict; the rest have had no owner ruling at all.

### Engineering follow-ups

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **[biometric lock restores without a capability check](biometric-lock-restores-without-a-capability-check.md)** | **Major.** `biometric_lock=true` restored onto a device that cannot show a prompt hard-locked the user out — `SecurityViewModel` derived `authState` from the preference alone, so Lock ↔ Error cycled and Settings was never reachable. Most likely during setup-wizard restore, before a screen lock exists | 3 | small — one capability check | **fixed on API 29+** — `AuthState.Unavailable` + a deep link to security settings + an `onResume` re-query, so setting a lock or enrolling a biometric now gets the user in. **API 28 with no enrollable biometric is still a lockout**: the prompt takes no device credential there, so the screen is honest but the user is stuck. Restore path still needs a device. The *security* call — fail open, self-heal, in-app disable, or confirm-credential on 28 — is untouched and **still the owner's** |
| **[a restored "already prompted" flag suppresses watchlist recovery](restored-prompt-flag-suppresses-watchlist-recovery.md)** | `has_shown_disabled_apps_prompt` is backed up; the Room watchlist it describes is deliberately not. Reinstall Thor on a device with frozen apps and the import prompt — the one recovery path — is silently pre-suppressed | 3 | small | **decision open**; option 3 (derive the condition, drop the flag) removes the class. "Do nothing" is a defensible answer here |
| **[three defects in the existing export/share path](export-share-defects-found-during-30-recon.md)** | pre-existing, found while scoping #30 and verified in shipped code: single-app share hardcodes `application/vnd.android.package-archive` even for a `.apks` **zip**; the OBB row in app details can never render (no storage permission past API 28); and nothing deletes a staged bundle after a successful export | 3 | small ×2, medium ×1 | **fold into the #30 branch** — same files. The staging one is a *blocker* for #30 phase 1, the other two are independent |
| **[release builds emit no Thor logcat](release-builds-emit-no-thor-logcat.md)** | `Logger` gates all five levels — **including `e`** — on `isDebug`, which is `BuildConfig.DEBUG`, and Thor makes zero direct `android.util.Log` calls. So non-fatal failures in the field are silent. Crashes are **not** affected (no custom handler, so the platform still prints them) | 3 | small | **decision open**, and possibly deliberate — logcat is world-readable and Thor's logs carry package lists and shell commands. Option 4 shares an implementation with #22's release-shaped measurement build |
| **[two branches, one Play version code](two-branches-one-play-version-code.md)** | `master` and `production` both upload the same `chore(release)` code to Play, which allows a code exactly once app-wide, so one of the two runs must fail | 3 | small once the flow is chosen | **decision open** — surfaced by #5's fix, which covers every *non*-release push but cannot cover this. Option 1 (promote `internal` → `alpha`) is the only one keeping one artifact across both tracks |
| **[cross-privilege suspend ownership](cross-privilege-suspend-ownership.md)** | an app suspended under Root cannot be unsuspended under Shizuku, and vice versa — Android only lets the recorded suspender lift a suspension | 3 | unsized; three candidate fixes, (1) recommended | decision open; it is the one thing keeping #210 from being closed |
| **[freezer membership toggle semantics](freezer-membership-toggle-semantics.md)** | one snowflake control, two meanings — the Apps tab removes from the watchlist, the Freezer tab removes **and thaws** | 3 | small once the semantics are chosen | product decision, open |
| **[`lastResult` has no expiry or invalidation](freezer-bulk-run-deferred-review-findings.md)** (§1) | a "Froze N apps" result survives for the process lifetime and is not cleared by the unfreeze paths that skip the runner | 3 | medium | wants the runner's tests first |
| **[shortcut match flags for frozen system apps](shortcut-match-flags-system-apps.md)** | `appLabel`/`appIcon` drop frozen system apps; latent while pins stay user-apps-only, and the two sites must be fixed together or the icon becomes a white square | 3 | small | do it *before* lifting the "v1: user apps only" pin gate |
| **[odin root availability cache](odin-root-availability-cache.md)** | root revoked mid-session still reads as available until restart; the real fix is in Odin, not Thor | 3 | small in Thor, medium in Odin | upstream |
| **[ViewModel behavior tests](viewmodel-behavior-tests.md)** | `AppListViewModel`'s refresh/settle timing is still pinned only by constant tests. `MainViewModel` and `SecurityViewModel` got real behaviour tests in the #16 batch; this one did not | 3 | small | **unblocked** — the harness (`MainDispatcherRule`, `ViewModelTestDoubles`) exists; the tests are just unwritten |
| **[`BulkFreezeRunner` concurrency tests](bulk-freeze-runner-concurrency-tests.md)** | the only stateful concurrent class has zero tests, and both defects it shipped with were `runTest`-shaped | 3 | medium | **still blocked, for a different reason than the doc used to give** — #16 landed, but four collaborators are final concrete classes over `Context`/`PackageManager`, so the runner cannot be built in a JVM test. Needs a seam in main source first |

### Feature requests and standing promises

| Item | What | Tier | Effort | Status |
|---|---|:---:|---|---|
| **#130 — installer attribution + drill-down** | the friendly installer label is the achievable slice; the drill-down nav is the bulk | 3 · 🟡 | 1–2 d (label ≈0.25 d) | slice worth doing, unscheduled |
| **#58 — app lock** | the whole launch-detection + overlay pipeline is net-new, plus a Play-policy risk and an ongoing maintenance tax | 3 · 🔴 | 8–15 d | roadmap says defer |
| **#178 — app tagging** | low-risk build, **zero demand** | 3 · 🔴 | 3–5 d | roadmap says defer; bundle with app-list UX work if demand appears |
| **#209 — VirusTotal scanner** | an entire network stack, a user-supplied API key, and third-party upload privacy | 3 · 🔴 | 4–7 d | roadmap says defer |
| **#55b — process manager (RAM/CPU)** | fragile `dumpsys`/`top` parsing, root/Shizuku-only, Dhizuku dead-end | 3 · 🔴 | 4–7 d | roadmap says defer; split out of #55 so #55a can ship alone |
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
| **Accessibility-based auto-refreeze (#210)** | No public API detects removal-from-recents; it needs Accessibility or UsageStats polling, at a battery and Play-policy cost. The achievable slice — Freeze\|Suspend mode — shipped instead |
| **Bespoke phone-to-phone transfer (#51 phase 3)** | 12–20 days to rebuild what Nearby Share already does; the exported file rides the share sheet today |
| **Raw split-folder export + a user-facing format picker (#164)** | Thor picks the bundle format from the app's shape, which is the better default. Revisit the picker only if `.xapk` turns it into a genuine three-way choice |
| **`InstallWithOptions` attribution (#130 part 1)** | Shell-based installs record `com.android.shell` or null, so attribution is unreliable no matter how much effort goes in. The friendly label ships; the attribution claim does not |

---

## Tracked elsewhere

Two items are real but cannot be actioned from this repo:

- **The residual `MainShell` shell-init hang** — fixed user-visibly, not at thread level. Its Thor
  follow-up was deleted when Odin Phase 3 removed `:suCore`; it lives on as Step F of Odin's
  shell-modernization plan.
- **The root-availability cache** above — Thor can only add the re-probe call; the cache
  invalidation has to happen in Odin.

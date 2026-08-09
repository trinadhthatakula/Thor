# Thor v1.94.1 Release Notes

Twelve merges since **v1.94.0**, and the first release cut on the three-rung ladder — `dev`
uploads to Play's closed track, `master` promotes it to open testing, `production` promotes it
to stable. Nothing is rebuilt on the way up.

Two things dominate. The first is a **fix for a feature that shipped broken four days ago**:
Clear All Cache freed nothing, on every device it was tried on, under both root and Shizuku. The
second is the **backlog wave** — bands A and B, twenty-two ranked rows drawn from the
r/howtomen feedback thread and Thor's own issue tracker, built over four PRs.

Read the correction below before the rest: one line of the v1.94.0 notes is no longer true, and
this release is what made it untrue.

---

## ⚠️ Correction to the v1.94.0 release notes

v1.94.0 said:

> Where a rung must fall back to removal, it uses `pm uninstall -k` to **keep data and cache**.

**That fallback is gone.** It kept an app's data, but nothing kept `FLAG_INSTALLED`, so the
package disappeared from every query that does not ask for uninstalled packages — and a user
reported losing the Google accounts the removed package had authenticated. The escalation was
never disclosed and never consented to, so it has been removed rather than documented (#366).

**This is a capability removal, stated plainly:** on an OEM build that refuses `pm disable-user`
for a given system app, **Shizuku and Dhizuku can no longer freeze that app at all.** They now
end where root already did — the freeze fails, the package stays installed, and the refusal is
named on screen. Root is unaffected; it never took the uninstall path.

An explicit *"remove it for this user anyway"* action — asked for by the user, having been told
what it costs — is a separate, deferred decision. It is not in this release.

---

## ✨ Highlights

* 🧹 **Clear All Cache actually clears.** It reported *"there was no cache left to clear"* on
  every device and freed nothing. Both defects behind that are fixed.
* 🧊 **A refused system-app freeze fails instead of removing the app** — see the correction
  above.
* 📋 **Select many apps at once** in the Apps list, with the same actions the single-app sheet
  has.
* 🔎 **Search the Freezer**, and prune watchlist rows whose package is no longer installed.
* ❄️ **Suspend or force-stop a whole Freeze Profile**, chosen per tap.
* 🛠 **Fix Store lists what it will touch** before it runs, and can be stopped mid-run.
* 📤 **Export the app list as CSV**, or share it straight out.
* ⚙️ **Sort, filter, grid density and your starting tab survive a cold start.**
* ⚠️ **A risk dialog** before the actions that can leave a device unbootable, naming the package
  and what the platform does next.
* 🔒 **An unwritable settings store no longer kills the process.**

---

## What's Changed

### 🧹 Clear All Cache freed nothing (#373, #374)

v1.94.0's cache rework shipped with the tile reporting success over a no-op. Four devices —
stock Android 16 and 17, HyperOS 3, Samsung 16 — answered *"there was no cache left to clear"*,
under root and under Shizuku alike. One root cause chain explains all of it, which is why the
symptom was identical everywhere:

* **`pm trim-caches N` means "ensure N bytes are *usable*", not "free N bytes of cache".** The
  first line of AOSP's `freeStorage` is `if (file.getUsableSpace() >= bytes) return;`, with no
  reserve added to the comparison. Thor was passing `StorageStatsManager.getFreeBytes()`, which
  on any real phone is *exactly* the volume's usable space — the cache term it adds back is
  clamped to zero by a reserve of ~10% of total storage. The target was therefore always already
  satisfied, and the trim returned on its first line. It is now the volume's usable space **plus**
  the cache actually measured, so the target has to be met by reclaiming.
* **`pm trim-caches` exits 0 whether it freed gigabytes or bailed out immediately** —
  `runTrimCaches` waits on its observer and then returns 0 unconditionally. Root used to
  `return` on that exit code, which made its own `rm -rf` sweep of the cache directories
  unreachable. The verdict is now logged and dropped; root always sweeps (#374).

Also in the pair, from #373:

* **Per-app Clear Cache is root-only, and says so.** It was reporting success to Shizuku and
  Dhizuku, neither of which holds the signature-level permission the API needs.
* **A measured zero is reported as a measured zero**, not as a missing permission — and an
  *unmeasured* clear no longer tells the user to grant a permission that would not have helped.
* The tile confirms before it runs and reports how much it freed when it finishes.

⚠️ Known gap, filed rather than fixed: `StorageStats.cacheBytes` counts `code_cache`, while
Thor's per-app root sweep does not clear it. The two figures can therefore disagree by the size
of a package's compiled-code cache.

### 🧊 System-app freeze: a refusal is now the end of the line (#366, #368, #369)

Covered in the correction above. Alongside the behaviour change, the copy that described the old
escalation was retracted everywhere it appeared: **six passages across five live pages** of the
website (`index`, `faq`, `features` ×2, `download`, `privacy`), with a new claims rule so it
cannot be reintroduced from an older draft, and the in-app strings in all five locales. A
separate line claiming Dhizuku *"still removes without keeping data"* — false since v1.94.0, not
band A's doing — went with it (#368), along with a correction in the other direction: the API 37
guard is uid-0-only, so Dhizuku's device-owner uid is refused exactly as Shizuku's shell uid is.

### 📋 Apps tab: bulk selection, export, and a Fix Store you can watch (#366, #371)

* **Bulk select**, with the same action set the single-app path already had — a ten-app freeze is
  one confirm rather than ten.
* **Fix Store hands over to a picker**: every candidate listed with the installer Android
  currently records for it, everything pre-ticked, the count on the confirm button. The run can
  be stopped — between apps, never during one, because killing a `pm install` halfway is how a
  package ends up half-written. Building it turned up that the candidate rule excluded Google's
  package installer but **not AOSP's**, so on a de-Googled ROM every sideloaded app was a
  candidate; and that Thor's self-exclusion was spelled as a literal that the debug build's
  `applicationIdSuffix` made wrong, so Thor cleared its own cache mid-run.
* **Export the app list as CSV**, or share it straight out. What gets written is the list on
  screen — tab, search, filter and sort already applied.
* **A scroll position indicator** on the Apps tab and the Freezer's main list, drawn without
  adding a drag target.
* **The installer distribution chart names its installers** and is tappable through to a filtered
  list. It used to key its buckets on the last segment of the package id, so `com.aurora.store`
  was drawn as "STORE" and any two installers sharing a last segment were silently summed into
  one bar.
* **The UAD tier is shown in the app list** — a chip in list mode, a coloured dot in grid mode —
  and the UAD description now renders as a "why this is flagged" card in the details screen.

### ❄️ Freezer: search, pruning, per-profile verbs, fewer prompts (#366, #370)

* **Search the watchlist.** Past a screenful it was scrolling only.
* **Uninstalled packages are pruned from the watchlist**, keyed off the cache's own scan verdict
  rather than the app list — that verdict is the only thing that can tell *"the package is gone"*
  from *"the OS refused to answer"*.
* **Per-profile suspend and force stop**, chosen per tap from the row's overflow menu. Nothing is
  persisted on the profile; the entity stays verb-agnostic.
* **The routine freeze confirmation can be switched off** in Settings → Freezer. It reaches
  ordinary system apps only — the expert-tier warning is a verdict about a specific package, and
  a preference about tedium must not quietly become one about risk.
* **The profile editor closes when the save lands**, not when the button is tapped. A refused save
  leaves the sheet up with the draft intact.
* **A parked bulk result expires** after five minutes instead of being replayed as current, and
  Unfreeze-all goes through the same path as everything else.
* **Freeze Profiles are reachable from the Freezer screen** with a labelled button, not only an
  unlabelled toolbar icon.
* **Long-press Force Stop, Suspend or Freeze for an explain-only sheet.** All three are
  destructive, so the sheet explains and does not offer to act.

### ⚙️ Settings that stay set, and copy in your own language (#366)

* **Sort and filter state survives process death** instead of resetting on every cold start.
* **A default-tab picker.** The substance was the cold-start race: the preference is read before
  `setContent` and the splash is held until it lands, so the app does not open on Home and jump.
* **Sort labels come from resources.** They were hardcoded English inside an enum, so four of five
  locales read the app in their own language and the sort menu in someone else's.
* **Grid density**, as a coordinated bundle — cell, icon, padding, corner radius, label gap and
  badge move together, because an icon whose cell can no longer hold it is coerced smaller while
  its corner radius stays put and the tile renders as a pill. The default reproduces today's
  rendering to the dp.
* **Tap or hold to copy a package name**, on three surfaces.
* **Open in Play Store** from the details screen.

### 🔒 Reliability (#369)

* **An unwritable settings store no longer kills the process.** All twenty-nine DataStore write
  blocks go through one guarded helper — there were zero `try` blocks among them.
* **Thor says so when the app lock could not be saved**, rather than leaving the switch showing a
  state that was never persisted.

### 📦 Installer (#366)

* The **typeless open-with filter is opt-in**, behind a switch, rather than claiming every
  untyped URI on the device by default.
* That filter lost an `android:host="*"` that made **45 path matchers unreachable** — the host
  provably excluded the opaque-provider case the filter exists for. Whether this is enough to fix
  `.apks` opening from Samsung My Files turns on what that provider returns from `getType()`, and
  the diagnostic is still unrun.

---

## 🌐 Project: the release ladder

* **Three branches, three rungs, one Play upload** (#362). `dev` → Play `alpha` and a GitHub
  pre-release; `master` promotes `alpha` → `beta`; `production` promotes `beta` → `production`
  and cuts the Latest release. Only the bottom rung uploads an artifact — Play allows one upload
  per version code per app, and a second uploader is what produced
  `Version code NNNN has already been used`. There is **no hotfix bypass**: a fix re-enters at
  the bottom with a new version code, which is exactly what this release is.
* Release tags are **pinned to the commit that was built**, the AAB is no longer published as a
  GitHub asset, and the `-foss` `versionName` suffix is gone.
* A **pre-flight release-notes size gate** measures the Telegram caption in UTF-16 units,
  including the wrapper the workflow adds — measured per run rather than assumed, because the
  wrapper contains the branch name. An oversized caption is rejected by Telegram outright, which
  used to be discovered mid-release.
* The **Shizu store manifest derives its version from `origin/production`**, not the working
  tree, and warns on PRs while failing hard in the weekly audit — so one production promotion no
  longer reddens every open PR for authors who cannot fix it from their branch.
* **actionlint, shellcheck and a shell test runner** in CI, with the release rung hardened
  against the failures that stay quiet.
* The **hi-IN changelog translations were reverted** (#363) — English is the correct placeholder
  until a translation is signed off, and supply blanks a locale that has no file for the current
  version code.
* **The whole backlog was ranked against what users actually asked for** (#364), producing the
  bands this release is built from.

## 🔧 Internal

* **Every PR is checked**, not only the ones aimed at a long-lived branch, and `androidTest`
  sources are compiled — nothing in the repo did that before (#367).
* `SyntheticAccessor` is enabled in `:bypass` (#366), with the six `private` → `internal`
  widenings in the same change, because that module's `warningsAsErrors` makes enabling it alone
  an instantly red build.
* The trim-target arithmetic was extracted as a pure function so the failure that shipped is
  covered by a JVM test (#374).
* Dependency bumps: KSP 2.3.10 → 2.3.11 (#365), the GitHub Actions group (#372).

---

## 🛠 Commits Log (`v1.94.0...v1.94.1`)

* `339ff68c` — #374 clear-all freed nothing; the trim target was the one number PMS refuses
* `e32096ca` — #373 per-app clear is root-only, and the tile trims the whole device
* `6b4890f7` — #372 GitHub Actions group bump
* `8217d4da` — #371 band B's Apps-tab cluster — Fix Store picker, list export, installer names
* `0b142916` — #370 band B's Freezer cluster — prune, save ordering, per-profile verbs
* `59fcc5ef` — #369 guard the DataStore write path, and retire the freeze-becomes-a-removal copy
* `3c893861` — #366 band A — stop escalating a refused system-app freeze, plus 11 backlog rows
* `80f35a5e` — #367 check every PR, and compile androidTest sources
* `7e1c873e` — #365 KSP 2.3.10 → 2.3.11
* `75e62aa7` — #364 rank the whole backlog against what users asked for
* `96b5b85a` — #363 drop the hi-IN changelog translations
* `3c67b398` — #362 the three-rung release ladder

#368 is not listed separately: it targeted `master` (the site deploys from there), and the same
commit reached `dev` inside #369.

**Full changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.94.0...v1.94.1

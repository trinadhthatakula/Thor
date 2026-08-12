# Thor v1.94.2 Release Notes

Seven merges since **v1.94.1**, and two of them are features large enough that this release is
really about them: **app backup and restore** (#51 phase 2) and **game data inside a `.xapk`**
(#164). A third, the Settings rewrite, is the first structural change that screen has had.

Read the next section before installing. One of those two features ships deliberately unfinished,
and the notes say so rather than letting the first person to try it find out.

---

## ⚠️ Backup & restore is in its first test phase

**Back up an app you can afford to lose, and check the restore, before you trust it with one you
cannot.**

This is not a hedge added to the copy at the end. It is what the work actually looks like:

* Nothing that replaces an app's data has ever run inside a test. That region is a WorkManager job
  driving privileged shell commands against another app's data directory, and there is no JVM seam
  anywhere in it. The 1,523 unit tests in this release cover the container format, the key
  derivation, the gates, the sizing arithmetic and the view models — **none of them writes to a real
  app's data directory, because none of them can.**
* Both Critical defects the review found were the same shape — *the backup cannot be restored* —
  and both existed because, until late in the branch, **nothing wrote an archive with the real
  backup path and read it back with the real restore path.** The commit that finally did
  (`0e9fef25`) found them immediately. That is a strong hint about what else on-device use will
  turn up.
* Backup itself only reads. A failed backup cannot damage the app it was reading — Thor says so
  explicitly in the failure copy. **Restore is the destructive half**, and it is the half to be
  careful with.

Restore is already gated hard: a signer mismatch is refused outright, an unverifiable signature is
refused, an archive from a newer Thor is refused, and the replace itself sits behind an explicit
"I understand this replaces the app's current data" confirmation. Those gates are tested. What is
untested is what happens *after* you pass them, on your device, on your ROM.

---

## ✨ Highlights

* 💾 **Back up an app and its data** into a single encrypted `.thorbak` — app data, startup data,
  files and media on shared storage, and the app's own installer, each chosen separately.
* ♻️ **Restore one later**, with every check shown before anything is replaced, and honest copy
  about what was touched when something stops part-way.
* 🔑 **A passphrase Thor can remember on the device, or forget when you ask.** Thor cannot recover
  it, and says so at the point you choose it.
* 🔔 **Backups and restores run in the background** with a progress notification, and tapping that
  notification reopens that job's own sheet.
* 🎮 **Games with expansion files export and install as one `.xapk`**, game data included, with the
  App Info screen reporting how much of it there is.
* 🐛 **The `.xapk` chip stopped refusing apps it should have accepted** — a fix for two stacked
  defects found on hardware two days after the feature merged.
* ⚙️ **Settings now has eight sections and a search box**, and opens a second pane on a tablet or
  an unfolded phone.
* 👂 **Every settings switch is readable by a screen reader.** All twelve announced as a button
  with no on/off state before this release.

---

## What's Changed

### 💾 App backup and restore (#379, #381)

**Where it is.** *Back up* is in an app's action row. *Restore a backup* is in
Settings → Backup & restore, alongside the passphrase.

**What goes in the file.** One `.thorbak` container, encrypted with AES-256-GCM in framed chunks so
a corrupted archive fails loudly at the damaged chunk rather than decrypting into rubbish. Four
classes of data are offered, each ticked or not on its own:

| Shown as | What it is |
|---|---|
| App data | the app's private data directory |
| Startup data | the device-encrypted data an app reads before first unlock |
| Files on shared storage | `Android/data` |
| Media on shared storage | `Android/media` |

The app's installer can be included too, which is what lets an archive restore an app that is no
longer on the device. On its own it is refused — an installer with no data is just an APK, and Thor
says that rather than writing a file that cannot restore anything.

**The passphrase.** Chosen per backup, confirmed twice, and **not recoverable** — the warning sits
next to the field, not in a FAQ. Thor will remember it on the device if you ask, and forget it on
request from Settings. Changing the remembered passphrase does not re-encrypt anything already
written: every archive still opens only with the passphrase it was made with, which the Settings
copy states outright.

**What it needs.** Root, or Shizuku started from root. Anything less cannot read another app's
private data, so Thor refuses with that sentence rather than offering a button that fails — and the
same refusal names what is planned instead: partial backups without root, in Thor 2.0.

**Running.** Jobs go through WorkManager as a foreground service, so closing Thor does not stop a
backup. The notification carries progress, and tapping it reopens the sheet belonging to *that*
job. Only one archive job runs at a time; a queued one says so.

**Restoring.** Every gate is shown before anything destructive happens:

* **Refused** — the backup was made from an app signed by a different developer; the installed
  app's signature cannot be read; the app is absent and the archive holds no installer; the archive
  was written by a newer Thor; the archive does not say what format it is in.
* **Warned** — the installed app is *older* than the backup (apps can crash on data from a newer
  version); app data is selected without the startup data some apps need alongside it.

Then the confirmation, then the replace. If it stops part-way, Thor says the app's data may be
incomplete and that restoring again is the fix — and if it never started, it says that instead, so
"failed" and "failed after changing your device" are never the same message.

**What the review found.** Recorded here because the accountability record is the point of this
file:

* **Restore derived its key with Thor's current KDF rounds rather than the rounds recorded in the
  archive** (`279d1582`). Any archive written before a rounds change would never open again.
* **A replaced archive could leave the previous app's signer behind** (`09994a89`), so the signer
  check — the one gate standing between an archive and another app's data — could be checking the
  wrong app.
* The `lib` symlink was being packed into the archive (`dd14a7e2`); the archive was written
  somewhere other than the folder the sheet's own caption named (`4d399329`); a failed publish
  stranded the partial file and could hand out a name already taken (`5aa575d5`); a killed write
  could erase the ledger, and the copies a restore strands were not swept (`6acb4814`).
* Several rounds went the other way — untested cipher guards deleted rather than documented
  (`311cad28`), and four test claims retracted for naming a guard they did not actually cover
  (`79c2b96e`).

**The sheets** (#381). Both halves became sheets rather than screens: a running backup collapses to
a bar with a way out and closes itself on success; restore reopens from its own notification. The
review of that change found four ways the reopen handoff could open the wrong thing (`87c3d5fb`),
and two overlapping reads that could split the sheet's identity in half (`3cbb47a8`, `d9603327`).

### 🎮 Game data in `.xapk` (#376, #378)

Expansion files (OBB) now travel with the app. Export packs them into the `.xapk`; installing a
`.xapk` puts them back under `Android/obb`. The App Info screen reports how much game data an app
has, or says plainly that it cannot check right now.

The probe reads another app's storage on Thor's privileges, and an expansion **filename is
controlled by the app being read**, so the guards are the substance of this work: a leaf name that
tries to escape its directory is refused, symlinks are not followed on any privileged path, the
package directory is checked and not merely the leaf, the entry count is capped, a repeated leaf is
refused, and a `stat` that cannot measure a file **fails closed** rather than guessing at a size.

**#378 is the hardware fix**, and it is worth reading as a pair of lessons:

* **The probe killed the root shell.** Its script ended with a top-level `exit`. Odin's root
  channel is *one long-lived `su` session*, so that `exit` did not end a script — it ended the
  session, and everything Thor asked for afterwards came back with no result code. Shizuku spawns a
  fresh shell per command and was completely unaffected, so **the same code failed on root and
  passed on Shizuku**, which is exactly how it reached a device.
* **A tri-state was collapsed to a boolean.** The probe answers *present*, *absent* or *cannot
  tell*. The export gate read *cannot tell* as *no game data* and disabled the `.xapk` chip — for
  the roughly 70% of apps that have no expansion files at all, which is to say most of them.
* A `.xapk` built from an app with no game data now writes `"expansions": []` rather than omitting
  the key, so a reader can tell "none" from "this file predates the field".

### ⚙️ Settings: eight doors, and a second pane (#383)

One long scrolling panel became an index of eight categories — Appearance, Home screen, Freezer,
Installing & sharing, Security, Backup & restore, Extensions, About & support — at most two levels
deep, with a **search box** that finds a setting by name, opens its category and highlights the row
on arrival. On a tablet or an unfolded phone the category opens *beside* the index instead of on
top of it.

Every row's description is **two lines** now. The old switch row was one line with an opt-in
marquee that started on a tap *on the subtitle* — a nested tap target inside a row whose whole
surface already toggles the setting, so the gesture that revealed the description was
indistinguishable from the one that changed it. Two lines fit every description Thor ships, in all
five locales, so the scrolling text had nothing left to reveal.

Three defects the review caught, all of which had shipped in the first push:

* **The back stack leaked.** Picking a category while the Extension Manager was open pushed onto a
  stack that never unwound, because the guard assumed only a category could sit on top — and the
  Extension Manager is itself a detail pane, so the index stays visible beside it and you can pick
  a category from there. Reproduced on hardware and confirmed fixed there.
* **Usage Access and Notification Access were dead once granted.** Both guarded on the permission
  still being *missing*, while the row reports the value the user is *asking for* — so switching
  either off called the handler, the handler returned, and the switch snapped back with nothing
  opened.
* **No settings switch was readable by a screen reader.** The row was `clickable` (a button, with
  no state) while the `Switch` beside it had its semantics cleared to stop the setting being
  offered twice — so between them, nothing carried on or off, across all twelve toggles.

Pinned by nine JVM tests over the catalogue (the exhaustive `when` proves every row reaches a
branch, and says nothing about whether every category reaches a row) and seven instrumented tests
over the switch row. Verified by hand on a Pixel 10 Pro Fold at 851 dp unfolded and 411 dp folded.

---

## 🔧 Internal

* **WorkManager 2.11.2** and `koin-androidx-workmanager` join the build — Thor had no long-running
  job seam before this release, and now has a reusable one (`c3956c6a`).
* **AGP 9.4.0-alpha07 → alpha08**, **Gradle 9.6.1 → 9.7.0**, and four bumps in the maven group
  (#377).
* The `#51` plan documents still described a **WorkManager throttle the source had already
  retracted** (#380) — the code was corrected in-branch and the docs were not, which is its own
  small lesson about where a retraction has to be swept.
* Localisation: the OBB export copy was retracted and rewritten across all five locales
  (`25e6b10e`), the Arabic app name is kept as "Thor" rather than transliterated (`e9095478`), and
  the Chinese export explainer no longer opens a sentence with a bare file extension (`6948d0c0`).

⚠️ **Tag naming, for anyone following a compare link:** only a *production* release mints a plain
`v<version>` tag. `v1.94.1` never reached production, so that tag does not exist and the compare
link in the v1.94.1 notes 404s; the pre-release tag `v1.94.1-dev-12` is the one that resolves. The
link below uses it.

---

## 🛠 Commits Log (`v1.94.1-dev-12...dev`)

* `e210768f` — #383 Settings: eight doors, and a second pane on wide windows
* `4c5edad9` — #381 both archive surfaces are sheets, and a job notification reopens its own
* `d3315c3f` — #380 finish the throttle retraction the source got and the docs did not
* `940480ef` — #379 app data backup and restore (#51 phase 2)
* `0fd72541` — #378 stop `.xapk` export refusing every app whose game data it cannot read
* `73b47e5e` — #377 bump the maven group with 4 updates
* `91100e58` — #376 OBB support in `.xapk` export and install (#164)

**Full changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.94.1-dev-12...dev

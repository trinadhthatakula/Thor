# Thor v1.94.3 Release Notes

**This release is v1.94.2 plus one feature, because v1.94.2 never reached anybody.** Its dev rung
failed at the Play upload — `You must let us know whether your app uses any Foreground Service
permissions` — and because that rejection happens inside `commit_current_edit!`, the Play edit was
refused atomically. No tag was cut, no GitHub release was published, nothing was broadcast. Version
code 1942 was then spent on a hand-uploaded internal build, so it cannot be reused; 1943 is the code
that carries this work.

Everything v1.94.2 described is therefore still new to everyone reading this, and the sections below
are carried forward unchanged. The one addition is the reason 1943 exists as a separate build:
**exporting an app now runs in the background.**

Read the backup warning below before installing. One of these features ships deliberately unfinished,
and the notes say so rather than letting the first person to try it find out.

---

## ⚠️ Backup & restore is in its first test phase

**Back up an app you can afford to lose, and check the restore, before you trust it with one you
cannot.**

This is not a hedge added to the copy at the end. It is what the work actually looks like:

* Nothing that replaces an app's data has ever run inside a test. That region is a WorkManager job
  driving privileged shell commands against another app's data directory, and there is no JVM seam
  anywhere in it. The unit tests in this release cover the container format, the key derivation, the
  gates, the sizing arithmetic and the view models — **none of them writes to a real app's data
  directory, because none of them can.**
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

* 📤 **Exporting an app runs in the background.** Start an export, leave Thor, and it carries on —
  with a progress notification, and a second notification naming the file when it lands.
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
* 🧊 **Unfreeze now clears both halves of frozen.** An app frozen by being *paused* came back
  still paused, while the confirmation counted it as unfrozen.
* 🔢 **A batch reports what actually happened.** Bulk unfreeze said "Unfrozen 12 apps" whether
  twelve, one or none of them came back — and a run that reached the end could still sign off with
  a "Stopped" line.
* 🛑 **Thor skips itself in a Kill or Uninstall batch.** "Select all → Uninstall" reached Thor
  itself, and with root or Shizuku it succeeded.
* ⏹ **Bulk share can be stopped**, and hands over whatever it had already prepared.
* 💳 **A subscription gets confirmed on paths where Thor never asked.** The backstop for a purchase
  whose callback never arrives was being skipped in precisely the state it exists for.
* 📥 **A failed export no longer costs you the file it was replacing.**
* 🔐 **A privileged Thor grants its own permissions** instead of asking you to approve something it
  can already do.

---

## What's Changed

### 📤 Exporting an app runs in the background — the one thing 1943 adds

Exporting a `.apk`, `.apks` or `.xapk` used to hold the sheet open. The button showed a spinner, the
whole form sat there greyed out, and a toast reported the result — which meant the report only
arrived if Thor was still in the foreground when the packaging finished. For a 4 GB game it very
often was not, so the one message that mattered was the one most likely to be missing.

An export is now a WorkManager job behind the same `dataSync` foreground service the archive jobs
use. What that changes for you:

* **You can leave.** The running frame has a **Background** button; dismissing the sheet drops the
  screen's watcher and the export carries on. Reopening the sheet finds the same job still running
  rather than a fresh form.
* **It reports either way.** Progress is in the notification, and the outcome is a second
  notification — *"Saved Clash of Clans to Downloads"*, or the actual reason it failed, with the
  builder's own wording kept intact so *"about 1.4 GB more is needed"* survives to the shade instead
  of being flattened to "Export failed".
* **A second tap cannot eat the first export.** Both runs stage through the same folder, which the
  builder wipes on entry, so a duplicate would have deleted the other's half-written copy and
  finished a truncated zip "successfully". The sheet reattaches to the live job instead of offering
  the form again.
* **The form disappears while a job runs** rather than being disabled. Two chips, a destination row
  and two dead buttons are something you can read, cannot use, and have to scroll past to reach the
  only thing that is moving.

Three notes stated rather than glossed:

* **Multi-app export is *not* on this.** "Backup all" is still a plain coroutine on a
  process-lifetime scope, and its non-adoption is argued in its own source rather than pending. This
  release moves the single-app path only.
* **A stopped export reports nothing from the worker.** WorkManager stops workers for its own
  reasons and then re-runs them, so a shade row saying "nothing was saved" would be a lie told at the
  exact moment the export was about to succeed. A genuine cancel is worded by the screen.
* **This is also the feature the blocked `store` release was waiting for.** Play requires a video
  declaration for `FOREGROUND_SERVICE_DATA_SYNC`, and a reviewer can only verify what they can
  reproduce on a stock device. Backup and restore need root or Shizuku on every path. Export needs
  neither, which is why it is the surface the declaration will show. It does not lift the block by
  itself — the video still has to be recorded on a stock device and the declaration submitted.

Under the hood this is the third job on the seam (`3fa28f75`, `193d893e`, `b56143c6`), and it is the
first that genuinely survives a process death: the archive jobs hold their key in memory and refuse a
re-run, while an export's whole input is four strings that are still true tomorrow. That exposed a
race nothing had hit before — the launch-time cleanup deletes the staging tree wholesale, on the
reasoning that anything using it "died with the process", which stops being true the moment
WorkManager re-runs a worker *in* that process. A one-shot startup barrier now holds an export back
until the sweep is over, and refuses to stage rather than proceeding if it times out.

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

### 🔢 The batch actions stop claiming work they did not do (#385)

This began as groundwork for moving the bulk actions onto the job seam, and turned into a set of
corrections that had to land first. Moving a batch that lies onto a background worker only makes the
lie harder to see.

**Unfreeze was clearing half of frozen.** An app can be frozen two ways — disabled, or *suspended*
(what the Freezer's suspend mode, the quick-settings tile and extensions use). Both bulk unfreeze
paths only re-enabled. So an app frozen by being paused came back still paused, still unusable, and
its row was redrawn as unfrozen anyway. The Freezer's group unfreeze was worse than a partial fix:
it planned the work from the app's *last known* flags, and for a paused app that plan came out empty
— **it made no privileged call at all and returned success.** Both paths now always ask the system to
unpause before re-enabling, instead of deciding from stale state.

**The counts were unconditional.** Bulk unfreeze reported `Unfrozen %d apps` over the size of the
selection, and marked every selected row as enabled, *whatever the system had actually answered* —
so a run in which every app failed (privilege lost mid-batch, a ROM that refuses) was reported to
the user as twelve successes. It now reports `Unfroze 3/12 apps (9 failed)`, and only the apps that
really came back are redrawn.

**A finished batch could describe itself as interrupted.** Tapping Stop while the last app was being
processed produced "Stopped — 20 of 20 apps were processed." The gate tested whether a stop had been
*requested* rather than whether anything was actually left undone. A completed destructive batch
reporting itself as stopped is an invitation to run it again, so this touches all six batches that
share the progress log — force-stop, clear cache, uninstall, reinstall, pause, unpause — plus bulk
share.

**Thor no longer force-stops or uninstalls itself.** Thor is in its own app list, so "select all →
Kill" was two taps from killing the process running the batch: the remaining apps were abandoned
silently and the progress log vanished with no report of where it had got to. "Select all →
Uninstall" reached Thor too, and with root or Shizuku it *worked* — you could uninstall the app you
were using, from inside it. Both now skip Thor and say so in the log.

**Bulk share got a Stop button.** It is the slowest batch in the app — it builds an installable
bundle per app — and it was the only one without a way out, so a 50-app share had to be waited out
or force-stopped. Stopping now still hands the share sheet everything already prepared.

**A second tap no longer eats the first run's file.** Export list and Share list both stage through
one folder that is wiped when a run starts, so tapping again while a run was live could delete the
file the first run had just handed to the share sheet. The second tap is now ignored.

**The background-work notification switch was renamed** from "Backup and restore" to "Background
jobs", because bulk actions are going to share it and nobody should end up silencing more than they
agreed to. The channel id is unchanged, so this renames the existing switch rather than adding a
second one — and, disclosed rather than glossed: **that rename is English-only for now.** The
running notification also stops using the snowflake, which is Thor's *frozen app* icon, so every
backup in progress had been advertising itself as a freeze.

### 💳 Subscriptions: the sweep that was skipped exactly when it was needed (#386)

Supporters' subscriptions were being refunded again after the v1.94.0 fix. First, what did *not*
happen: nothing regressed. The acknowledgement path is byte-identical on `production`, `master` and
`dev`, and last changed at versionCode 1933 — before v1.94.0 shipped. These are gaps that fix did
not reach.

Google refunds a purchase Thor fails to acknowledge within three days. The backstop for that is a
sweep that asks Play for every subscription it knows about and confirms whatever is still
unconfirmed — and it opened with `if (!isConnected) return`. That guard was pure loss. Verified
against the artifact Gradle actually resolves (billing 9.1.0), a query on a disconnected client
does not fail; it **rebinds**:

```text
BillingClientImpl.queryPurchasesAsync -> submits Callable zzbp
zzbp.call()      -> BillingClientImpl.zzay(this, zzdq.zzb())   // synthetic accessor
zzay(impl, long) -> impl.zzbx(long)
zzbx(long)       -> zzby() ; zzaI(int).get(timeout) ; Math.pow + Thread.sleep
```

`zzbx` is the same helper already sitting at the head of the acknowledgement call — which is exactly
why acknowledging was deliberately left unguarded. The sweep belonged in that set and had been left
out of it. Asking a disconnected client rebuilds the binding; returning early guarantees nothing is
swept.

Resuming the app compounded it, because the sweep ran only inside the connected branch. Past the
reconnect ladder's five attempts a resume answers *exhausted*, and any resume inside the 30-second
cooldown answers *too soon* — in both cases the reconnect was a no-op, so **a resume with a dropped
binding swept nothing for the remaining life of the process.** The sweep now runs on every resume
regardless.

Three more on the same path:

* **Response code 7 now triggers a sweep instead of a toast.** Play answers `ITEM_ALREADY_OWNED`
  when a purchase exists and is not being handed over — which is what it does when an earlier
  acknowledgement never landed, making it the single code most likely to mean *you owe Play a
  confirmation right now*. The purchase list is null on that path, so asking is the only route to
  the token. The toast went too: "Billing error: 7" blamed the buyer for something they did not do.
* **A cancelled attempt releases its purchase.** The token is claimed before the coroutine starts,
  and two paths ended it without reaching either exit — so a stranded token made every later sweep
  skip that purchase for the life of the process, which is the refund this code exists to prevent.
* **Retries 4 → 6.** Four attempts spent the entire budget in about seven seconds. The failure they
  exist for is a flaky network in the seconds after you return from the Play sheet — a lift, a
  tunnel, a Wi-Fi handover — and seven seconds outlasts none of them.

**Stated plainly, because this is the second attempt at the same bug:** this narrows the window, it
does not close it. A buyer whose process dies mid-confirmation and who never reopens Thor inside
Play's three days is still refunded, because the sweep only runs while the app is open. Closing that
needs acknowledgement from a server Thor does not have, and is filed rather than claimed. This file
is also **on no test classpath by construction** — the billing library is a Play-flavour-only
dependency and the unit tests run against the FOSS flavour — so it is verified against bytecode and
by hand, not by a test and not yet on a device.

### 🔐 A privileged Thor stops asking, and an export stops deleting (#387)

Three unrelated corrections that were finished in time to ride this release.

**Thor grants its own permissions when it can.** Once it has root, Shizuku or Dhizuku, asking you to
approve a runtime permission is asking permission to do something it can already do to itself, so it
now grants what it has declared. Notification state is read through the same question the notifier
itself asks rather than a permission check, because those two answers diverge the moment someone
turns notifications off in system settings without revoking the permission. Nine JVM tests over the
model.

**A job that starts without notification access now asks for it**, at the moment the job starts,
from the sheet that started it. A backup or restore running without it produces no progress
notification at all, which is indistinguishable from nothing having happened. As of this release the
export sheet asks the same question, for the same reason.

**An export no longer deletes the old file before the new one exists.** Exporting over an existing
file removed it first, so an export that failed or was interrupted left you with neither. Each
backend now stages its bytes and settles once: the document picker writes a `.part` and renames over
the replaced file at the end; the media store resolves the replaced row **before** inserting,
because it de-duplicates a colliding display name at insert time rather than replacing it; the
legacy Downloads path renames within one volume. The accepted cost, stated rather than hidden: an
export killed mid-write leaves a `.part` file in the destination folder instead of a truncated one
under the real name, and nothing sweeps it.

**Verification, across everything in this release:** #385 and #387 are covered by unit tests
wherever a JVM seam exists, #386 has none available to it at all, and the export job is covered at
the seams a JVM test can reach — the request's `Data` round trip, the status reduction the screen
renders, the startup barrier, and the failure wording. **None of it has been on a device.** The parts
that most want a device are exactly the parts no JVM test can stand in for — the privileged
gateways, the platform file APIs, the foreground service, and a real Play billing connection.

---

## 🔧 Internal

* **WorkManager 2.11.2** and `koin-androidx-workmanager` join the build — Thor had no long-running
  job seam before v1.94.2, and now has a reusable one carrying three job kinds (`c3956c6a`).
* **AGP 9.4.0-alpha07 → alpha08**, **Gradle 9.6.1 → 9.7.0**, and four bumps in the maven group
  (#377).
* The `#51` plan documents still described a **WorkManager throttle the source had already
  retracted** (#380) — the code was corrected in-branch and the docs were not, which is its own
  small lesson about where a retraction has to be swept.
* Localisation: the OBB export copy was retracted and rewritten across all five locales
  (`25e6b10e`), the Arabic app name is kept as "Thor" rather than transliterated (`e9095478`), and
  the Chinese export explainer no longer opens a sentence with a bare file extension (`6948d0c0`).
  The export job's own copy — the queued line, the Background button, and the sentence the outcome
  notification carries — was written into all five locales with it (`1a806e4e`), and the now-unused
  in-progress button label was removed from every one of them. The one new string in #385 — the line
  saying Thor skipped itself — **was translated into the other four locales without a native
  reader**, and is the weakest copy in this release. Corrections welcome.
* **The job seam was generalised so something that is not an archive can use it** (#385): the
  encryption-key handling moved out of the shared base class into the two archive jobs, job-watching
  became its own interface, and enqueue-and-confirm became one shared function. v1.94.2 listed the
  second queue and the after-the-fact completion notification as **built-ahead with no producer**.
  The completion notification now has one — the export's outcome row is exactly that mechanism in
  use. The second queue still has none.
* **The dev rung now puts its one upload on two Play tracks** (#388): `dev` uploads to `alpha` and
  then *assigns that same release* to `internal`, which is not a second upload and is non-fatal if
  it fails. Play allows one upload per version code per app, so a second uploader would be an error
  rather than a shortcut.
* `docs/workers/README.md` records which operations actually run on WorkManager — **three** as of
  this release, not two — and what the rest are, because the naming misleads in both directions. It
  states its gaps rather than hiding them, including the one this release created: `LaunchSweepBarrier`
  is opt-in, and a future job that stages into the swept tree and forgets it loses the same race
  silently.
* A retraction that had already been made once got copied forward anyway. Four comments in the tree
  claim the job launcher does not await its enqueue; it has awaited it since that function grew an
  awaited `Operation`, and two *new* files picked the claim up from the nearest example to hand
  before it was caught (`f43b5f29`). The four originals are now listed by file and line so the next
  sweep has somewhere to start.

⚠️ **Tag naming, for anyone following a compare link:** only a *production* release mints a plain
`v<version>` tag. Neither `v1.94.1` nor `v1.94.2` reached production, and v1.94.2 never published at
all, so neither of those tags exists. The pre-release tag `v1.94.1-dev-12` is the one that resolves,
and the link below uses it.

---

## 🛠 Commits Log (`v1.94.1-dev-12...dev`)

* `f43b5f29` — record the third job on the seam, and retract a comment that had just been spread
* `b56143c6` — hand a single-app export to the worker, and let the user walk away
* `193d893e` — run a single-app export on a worker that always says how it went
* `3fa28f75` — give export a job kind and a contract that survives a re-run
* `1a806e4e` — the sentences an export says when nobody is watching, in five locales
* `9ed08d06` — #384 release 1942 — backup and restore, in its first test phase (never published)
* `82d08433` — #388 put the dev rung's build on internal as well as alpha
* `7b930c58` — #387 grant our own permissions, ask for notifications when a job starts, and stop
  deleting an export before its replacement exists
* `0ef6732d` — #386 stop the guard that skipped the sweep Play refunds you for missing
* `bf4dff26` — #385 make the batch actions tell the truth, and generalise the job seam
* `e210768f` — #383 Settings: eight doors, and a second pane on wide windows
* `4c5edad9` — #381 both archive surfaces are sheets, and a job notification reopens its own
* `d3315c3f` — #380 finish the throttle retraction the source got and the docs did not
* `940480ef` — #379 app data backup and restore (#51 phase 2)
* `0fd72541` — #378 stop `.xapk` export refusing every app whose game data it cannot read
* `73b47e5e` — #377 bump the maven group with 4 updates
* `91100e58` — #376 OBB support in `.xapk` export and install (#164)

**Full changelog**: https://github.com/trinadhthatakula/Thor/compare/v1.94.1-dev-12...dev

# Follow-up: app-**data** backup (#51 phase 2), and what phase 1 + `.xapk` left to verify

**Status:** OPEN — narrowed. `.xapk` export (#164b) and backup **phase 1** shipped together as
planned; what remains is the root-only **data** half of #51 plus two device checks the desk cannot
do.
**Severity:** Not a defect. One public promise now kept (#164), one still half-kept — `README.md`
promises "BackUp App Data", and only the APK half of that exists.
**Effort:** Backup phase 2 (root data) 5–8 days. The two open verifications: under an hour each, on
a device.
**Raised by:** the deferred-items audit (2026-07-29); the owner asked for both to be filed and built
in the same session. Narrowed 2026-07-30 when phase 1 landed.

Files:
`app/src/main/java/com/valhalla/thor/domain/model/BackupIndex.kt`,
`app/src/main/java/com/valhalla/thor/domain/usecase/BackupAppsUseCase.kt`,
`app/src/main/java/com/valhalla/thor/data/backup/BackupRunner.kt`,
`app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`,
`README.md:76-81` (Upcoming Features), `docs/feature-request-roadmap.md` (#164, #51)

---

## What shipped (for the record)

**`.xapk` export (#164b).** `BundleFormat` (`APK`/`APKS`/`XAPK`) owns the format policy that used to
be a `splitPublicSourceDirs.isEmpty()` branch in the builder, and carries the MIME that decides
whether a receiver treats the file as installable. The export sheet offers two chips — the app's
native container plus `.xapk` — never three, because the third is always the wrong offer. `autoFor()`
never returns XAPK, so an export nobody touches produces exactly what it produced before.

**Backup phase 1 (#51).** Multi-select → **Backup** runs the selection through the *existing*
`ExportAppUseCase` — the only thing that deletes its staged copy after a successful write, which is
the single property keeping `cacheDir` bounded across a 200-app run — and writes a
`thor-backup-<yyyyMMdd-HHmmss-SSS>.json` manifest beside the bundles. Two apps stage at once, gated
by a semaphore owned by the runner rather than the run, so a cancel-and-replace cannot double the
cache peak. `BackupRunner` is a `@Single` on a process-lifetime scope: the run outlives the sheet,
the view model and the Activity **without** a foreground service.

**What that guarantee is not.** "Process-lifetime" means the run survives Thor's *UI* going away,
not Thor's *process*. With the last Activity gone, Thor is a background process: the OS may kill it
under memory pressure, and a swipe-away from Recents kills it outright. The run then stops
mid-batch, silently — there is no notification to update and no message to deliver, because the
thing that would deliver it is gone.

Recovery is the manifest, and it is deliberately the *only* recovery. Whatever landed stays on disk
and is described; the apps the run never reached are simply absent from the folder and from the
manifest; re-running the same selection exports them. There is no resume token and no partial-state
file, because a resume would have to survive the same kill that stopped the run, which is the
problem it is trying to solve.

Making a run survive process death means one of two things, both out of phase 1's scope: a
foreground service (a new permission, a mandatory `foregroundServiceType` on API 34+, a time cap on
35+, and a class of start-not-allowed crashes) or `WorkManager` with a serialisable work unit (which
would mean the selection, the destination `Uri` grant and the format all had to round-trip through a
`Data` bundle). The confirm dialog's "keep Thor open" copy is the honest statement of this limit
rather than a suggestion, and it is worth keeping worded that way.

Three design decisions worth not re-litigating:

- **One manifest per run, timestamped.** Both file-store paths write by name and delete a collision
  first, so the fixed `thor-backup.json` the original plan called for would have let Tuesday's
  export silently replace Monday's manifest while leaving Monday's bundles undescribed. A reader
  that wants the whole folder globs `thor-backup-*.json` and merges.
- **The manifest records failures.** An entry with `error` set and `fileName`/`sizeBytes` null is
  the same flat shape as a success — no polymorphic discriminator, so a `jq` one-liner or a restore
  tool written years from now against schema v1 does not have to learn Kotlin's conventions.
- **Cancelling still writes the manifest.** A cancelled run leaves real files in the folder, and an
  undescribed folder is the same lie a success-only index would be.

## Still to verify, on a device

Neither is desk-testable, and a green install hides both:

1. **Missing OBB.** A real XAPK carries `Android/obb/<pkg>/*.obb` and Thor exports none. Export an
   app that *has* an OBB directory, install the result, and launch it: it will install cleanly and
   fail at play time. Assert the absence deliberately rather than letting a green install imply the
   assets travelled. The explain string already says so up front.
2. **Metadata a different reader rejects.** Thor writes `manifest.json` for its own consumption, so
   nothing has validated it against a third-party parser. Check the split names, `total_size` and
   the split list against what SAI and APKPure actually read — a field Thor emits but nobody else
   accepts fails only on someone else's device. Validate against a real APKPure `.xapk` in both
   directions.

Also worth one negative test: export a split app, delete one split from the zip, and confirm the
installer reports a real error rather than installing a partial app.

---

## Phase 2 — Root-only data backup (5–8 days)

**Hard blocker, unchanged:** private-data backup requires **root**.
- Shizuku runs as the shell uid and cannot read `/data/data/<pkg>`.
- Dhizuku has no file access at all.
- `adb backup` is dead on modern Android.

Restore is the harder half: files must land with the correct uid/gid *and* the right SELinux context
(`restorecon` after extraction), or the app crashes on next launch in a way the user will blame on
Thor. Plan for:
- `tar` inside the root shell rather than pulling bytes across the Binder — Odin's `exec()` returns a
  real exit code now, so failures are detectable.
- An explicit privilege gate in the UI. This must be visibly root-only, not a feature that silently
  degrades: a backup that appears to succeed and restores nothing is worse than a disabled button.
- Excluding caches (`cache/`, `code_cache/`, `no_backup/`) — they bloat the archive and restore
  nothing of value.
- Force-stopping the app before reading, and after restoring, so nothing is written underneath.

**The manifest is already shaped for it.** `BackupIndex.schemaVersion` is written even when it
equals its default, and `BackupIndexTest` pins that a v1 reader survives a v2 document carrying an
extra `dataFileName` — so phase 2 can name a data file per entry without breaking anything that
already reads a phase-1 folder. Bump `SCHEMA_VERSION` when it does.

### Phase 3 — declined

Bespoke phone-to-phone transport. The exported file already rides the share sheet; a custom P2P
transport is 12–20 days for something Nearby Share does.

---

## Sequencing

Phase 2 is its own branch behind a root gate and should not block a release.

**#164 can be closed now** — everything in that issue is either shipped or deliberately declined
(the raw split-folder output). **#51 stays open** on phase 2.

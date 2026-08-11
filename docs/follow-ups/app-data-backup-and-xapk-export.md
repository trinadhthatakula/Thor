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

1. ~~**Missing OBB.**~~ ✅ **Fixed, both directions** — GH#164's OBB gap, shipped on
   `feat/xapk-obb-support` (design `docs/superpowers/specs/2026-08-10-xapk-obb-support-design.md`,
   plan `docs/superpowers/plans/2026-08-10-xapk-obb-support-implementation.md`). Export probes
   `Android/obb/<pkg>` through the privileged shell, stages each expansion via `externalCacheDir` —
   the only location Thor's uid and the shell's uid can both reach — writes them at
   `Android/obb/<pkg>/<leaf>.obb` and declares them in the manifest's `expansions` block. Install
   validates a bundled block against the package being installed and places the files, refusing
   **before** installing anything when placement is impossible. **Not root-only:**
   `executeShellCommand` is routed through `runGatewayAction`, so Shizuku works too; only Dhizuku's
   device-owner process cannot see another package's external directories, and there the `.xapk`
   chip is disabled with the reason shown. The explain string that *asserted* the gap has been
   retracted in all five locales — it claimed Android 11 stops any app reading another app's OBB
   folder, which is true of an ordinary app and false of Thor. The device checks this row asked for
   have moved into the plan's Task 12 Step 6 (nine of them, root and Shizuku both) and are still
   **unrun** — treat the feature as desk-verified only: 927 unit tests cover every pure decision and
   none of the privileged paths.
2. **Metadata a different reader rejects.** Thor writes `manifest.json` for its own consumption, so
   nothing has validated it against a third-party parser. Check the split names, `total_size` and
   the split list against what SAI and APKPure actually read — a field Thor emits but nobody else
   accepts fails only on someone else's device. Validate against a real APKPure `.xapk` in both
   directions.

Also worth one negative test: export a split app, delete one split from the zip, and confirm the
installer reports a real error rather than installing a partial app.

### Phase 2's own checks — **21 items, every one unrun**

Kept separate from the two above on purpose: a device check that passed for phase 1 and the `.xapk`
work says nothing about phase 2. Nothing below has been executed — the whole feature is
desk-verified, every task's gate was a build plus JVM unit tests, and no task in the plan had a
device step.

**Cryptography and storage — the parts JVM tests fake**

- [ ] **A non-ASCII passphrase, end to end.** Write a backup with one (CJK, emoji, combining marks),
      then restore it. The encode path goes through `CharBuffer` and UTF-8 rather than `String`, so a
      surrogate pair split across a buffer boundary is a real failure mode no ASCII fixture reaches.
- [ ] **The real `AndroidKeyStore` and DataStore path.** Every JVM test fakes both — `VaultKeyProvider`
      stands in for the Keystore, `PassphraseVaultStore` for DataStore, which is why those seams
      exist. Untested on hardware: key generation under `setUnlockedDeviceRequired(true)`, the
      invalidation-on-biometric-re-enrolment path, and the DataStore corruption handler.
- [ ] **`STORE_FAILED` actually renders.** Producing it needs a device whose Keystore key is gone or
      uncreatable. The sheet must show the refusal and must **not** show "Saved on this device."
- [ ] **Save / replace / forget round trip in the Settings sheet**, and that "Forget it" makes the
      next backup prompt for a passphrase rather than using a stale one.
- [ ] **The SELinux label of the staged tar.** A file written by a root shell into Thor's staging
      directory can carry a label the app process cannot read back.

**The privileged shells**

- [ ] **Shizuku's 300 s `EXECUTE_TIMEOUT_MS` against one `tar -xzf` per data class on a multi-GB
      app.** Four classes, one invocation each; a large app can exceed the timeout on a single class,
      and the failure then arrives as a timeout rather than as a tar error.
- [ ] **A root-rung OBB failure yields `InstalledWithoutGameData` *every* time**, not sometimes a
      generic failure. That outcome is the difference between "your save data is missing" and "the
      restore failed", and the user acts differently on each.
- [ ] **The `ObbInstaller` port convergence row** — root and Shizuku reach the same outcome for the
      same input rather than diverging on one rung.

**Zip and the archive**

- [ ] **`ZipFile` against `/proc/self/fd/N`** — how the archive is read from a SAF file descriptor. It
      depends on the platform allowing a `ZipFile` over that path.
- [ ] **Android libcore's duplicate-zip-entry resolution.** Which of two same-named members wins is
      libcore's choice, not the JDK's, and the archive's integrity story assumes one of them.

**The job seam**

- [ ] **Both `internal` `@KoinWorker` classes resolve out of the Koin worker factory.** `internal`
      visibility plus a generated factory is precisely where a binding compiles and then fails to
      resolve at runtime.
- [ ] **Foreground promotion happens inside the platform's window.** Miss it and Android 14 throws
      rather than warns.
- [ ] **`APPEND_OR_REPLACE` with a backup and a restore enqueued back to back.** The chain semantics
      are what make a queued job's cancellation meaningful.
- [ ] **SAF `discardOrphans` finds a real `.part` document.** The sweeper has only run against a fake
      provider.

**The backup sheet**

- [ ] **A provider refusing a persistable grant reaches the toast, not a crash.** The
      `SecurityException` is thrown inside a result callback, where an uncaught one takes the process.
- [ ] **`Pending` is observable long enough for the queued row to earn its space.** If the state is
      never seen, the row is dead UI.
- [ ] **The enqueue-vs-first-status executor ordering behind the `seenLive` guard.**

**Restore**

- [ ] **Opening a `.thorbak` from a file manager on API 31+ *and separately* on API 28–30.** Two
      devices, not one: the `pathPattern` rung is the only one below 31, so a pass on either alone
      proves nothing about the other.
- [ ] **A queued restore whose prerequisite fails** — the only path that renders "Nothing was
      changed", and the only one entitled to say it.
- [ ] **The interruption banner on an unfolded / tablet window.**
- [ ] **Start a restore on an expanded window while watching the Settings section.** No banner while
      it runs; a banner if the job ends badly. This is the defect `ObserveInterruptedRestoreUseCase`
      exists to prevent, and the one item here with no test at all.

---

## Phase 2 — built 2026-08-10 (desk-verified only)

Built across 18 tasks on `feat/app-backup-restore`, to the spec at
`docs/superpowers/specs/2026-08-10-app-backup-and-restore-design.md` and the plan beside it. Every
task's gate was a build plus JVM unit tests; **no part of this has run on a device.** The checks that
have to run are in the section above.

`.thorbak` is a plain zip named `<pkg>-<versionCode>.thorbak`, holding `thorbak.json`, `app.xapk`,
and one AES-256-GCM member per selected storage class, written on a reusable WorkManager
foreground-job seam. The privilege gate is a **probe**, not a root check: it runs through
`executeShellCommand`, which `runGatewayAction` routes to whichever gateway is active, so a
root-started Shizuku shell passes it — "requires Root" would have been a lie on that device.

### Release checklist — obligations, not advice

- File the Play Console **Foreground Service declaration** *before* the first `store` upload carrying
  `FOREGROUND_SERVICE_DATA_SYNC`. It is not a post-release fix: the upload is rejected.
- Declare the type as **`dataSync`**, matching what `SystemForegroundService` and the workers'
  `ForegroundInfo` already use. A manifest that disagrees with the `ForegroundInfo` is a crash on
  Android 14, not a warning — fix whichever is wrong, never suppress the lint check.
- Record the demonstration video on **bulk APK export on an unrooted device**. Not on a backup: a
  data backup needs root, Play will not review on a rooted device, so the backup path is not one a
  reviewer can run. Bulk export uses the same foreground service for the same reason.
- The release notes must state that `.thorbak` is **encrypted** and that the passphrase is **not
  recoverable**. Both are properties of the format; a user who learns them after losing a passphrase
  has already lost the archive.

### Known limitations — triage a bug report against this list first

- A document provider that hands back a **pipe** forces a copy to `cacheDir`. Peak disk is the
  archive size, once. Not a leak, and not avoidable through SAF.
- The `.thorbak` VIEW filter matches on **`Uri.getPath()`**, so it misses providers using opaque
  document ids. Those users reach the file through the in-app picker instead.
- A VIEW-intent grant lives as long as the **task**. A restore whose task is swiped away cannot
  reopen the file.
- **Progress does not survive process death, and neither does a job** — the derived key is
  process-scoped by design. A killed job is reported through the breadcrumb, not resumed.
- `strings_backup.xml` is **English-only**, behind a file-level `tools:ignore="MissingTranslation"`.

### Deferred, each with why

| Follow-up | Why not now |
|---|---|
| Migrate APK/XAPK export onto the job seam | The seam was built to carry it, but moving a shipped export path inside a branch that cannot be device-tested puts a regression on working code |
| Move bulk actions onto the seam as `shortService` | Same seam, different foreground-service type; wants its own device pass |
| Same for clear-all-cache | #373/#374 make this the path most recently broken and repaired; it should move on its own commit |
| Multi-app batch backup | Needs a queue UI and a per-app failure model the single-app sheet does not have |
| Streaming bundle build (`OutputStream` + `DEFLATED` at level 0) | Drops the staged `.xapk` copy and its peak disk cost; a format-adjacent change best made once the format has run on hardware |
| **Translate `strings_backup.xml`** into `values-ar`, `values-es`, `values-fr`, `values-zh-rCN` | ~50 strings. This is the debt the file-level `tools:ignore="MissingTranslation"` holds open, and the reason that suppression must not be copied into any other file |

**The manifest was already shaped for it.** `BackupIndex.schemaVersion` is written even when it
equals its default, and `BackupIndexTest` pins that a v1 reader survives a v2 document carrying an
extra `dataFileName`.

### Phase 3 — declined

Bespoke phone-to-phone transport. The exported file already rides the share sheet; a custom P2P
transport is 12–20 days for something Nearby Share does.

---

## Sequencing

Phase 2 is its own branch behind a privilege gate and should not block a release. It is built, on
`feat/app-backup-restore`.

**#164 can be closed now** — everything in that issue is either shipped or deliberately declined
(the raw split-folder output). **#51 stays open** on phase 2: the code exists, but *"has run on a
device"* is the bar for closing it, and 21 checks say it has not. **Do not close #51 on the merge.**

**Amended 2026-08-10.** #164 *was* closed on 2026-08-03, and reopened: on 2026-08-06 `playagain96`
posted a screen recording of an export whose `.xapk` carried no OBB *"even tho app has it visible and
accessible"* — which is the row above, reported by the person the row was written for. A limitation
stated in an explain string is still a missing feature. It is now fixed and #164 closes again on the
merge of `feat/xapk-obb-support`, this time with nothing about it deferred. The sequencing note still
holds for phase 2: nothing in the OBB work touches `/data/data`, so the root gate is still ahead of
that work, not behind it.

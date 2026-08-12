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

   ⚠️ **Amended 2026-08-12 — those nine unrun checks cost exactly what they were there to catch.**
   The owner device-tested after the merge and found `.xapk` export failing for apps with no OBB
   *under root and Shizuku both*. Two stacked bugs, fixed on `fix/obb-probe-session-kill`
   (**PR #378**, into `dev`):

   - **`ObbProbeParser.obbProbeCommand` emitted six bare top-level `exit 0`s.** Odin's root channel
     is one long-lived `su` session, so a top-level `exit` kills the **session**, not the command;
     libsu's end marker never runs, `StreamGobbler.OUT` returns its `NO_RESULT_CODE`, and the caller
     reads a synthetic non-zero exit. The probe's *first* early exit fires on the commonest input —
     an app with no OBB directory. Worse, **it left the root shell dead for everything after it**, so
     any freeze / force-stop / clear-cache taken right after opening an export sheet failed with
     "Root shell unavailable", surfacing as an unrelated bug. Now wrapped in `( … )`. Shizuku was
     never affected — `ShizukuHelper.execute` spawns a fresh `sh` per command.
   - **`Undetermined` was treated as fatal.** Independently of the above: the builder threw and the
     sheet **disabled the `.xapk` chip**. Wrong on the merits even with a healthy shell — most Play
     titles ship no OBB, and Dhizuku's device-owner process structurally cannot see another package's
     external directories, so it can only ever answer `Undetermined`. It now means *proceed without
     game data*. A copy that fails *after* the probe reported data present is still fatal, and
     deliberately so.

   Sweeping the class found a second violator: `InstallerRepositoryImpl.integrityGuardedInstall` was
   also unwrapped, latent only because both of its callers spawn a fresh process. The rule now lives
   in `SystemRepository.executeShellCommand`'s KDoc — the seam every one of these builders crosses,
   and previously the one place that said nothing about it.

   **The lesson is the sequencing, not the bug.** Nine device checks were deferred on a feature whose
   entire surface is privileged shell behaviour, which is the one thing a JVM test cannot reach. The
   fix carries six device checks of its own and they are listed in PR #378 rather than deferred again.
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

## The whole-branch review — 2026-08-12

Six reviewers over disjoint slices of `feat/app-backup-restore`, then six fixers in sequence, then a
five-lens re-review of the fix wave with an adversarial verifier on every finding. **1362 → 1467
tests** across 16 commits; lint held at 0 Error / 0 Warning / 6 Hint throughout.

The review found **2 Criticals**, and they are worth recording because they had one root cause: both
were independent ways to produce an **unrestorable backup**, found by two reviewers who could not see
each other's slices — because *nothing on the branch wrote an archive and then restored it*. Every
test on either side used a fixture the other side never produced. `ArchiveRoundTripTest` now closes
that seam as far as the JVM allows.

The re-review returned **0 blockers**, and its refutations are the more useful half: of eleven
"the fix wave broke this" attributions, **six did not survive verification**. Reviewer attribution on
this branch should not be trusted without a `merge-base --is-ancestor` check.

### The automated review pass on PR #379, and what it was worth

CodeRabbit posted **13 inline findings**, one rated 🔴 Critical. Each was verified against the code
and the survivors were then handed to an adversarial checker whose job was to refute them. **Nine did
not survive**, including the Critical and including the one finding that would have blocked the
merge — "secrets are written to WorkManager `Data`". Both were mechanism claims that were true in the
abstract and unreachable in this code.

Four were fixed, and the useful pattern is that **three of the four were fixed differently from the
way they were reported**:

- **The checkbox rows had no accessible name** (`CheckRow`, seven call sites including the
  confirm-replace acknowledgement). The row now carries `toggleable(role = Role.Checkbox)` and the
  checkbox takes `onCheckedChange = null`, matching `FixStoreSheet`. ⚠️ **The posted fix would have
  introduced a second defect**: `Checkbox` applies `minimumInteractiveComponentSize()` *only while it
  owns the click*, so handing the click to the row gives up the 48dp target and `toggleable` enforces
  no minimum of its own — the three rows with no `detail` would have shrunk to ~28dp. Hence the
  `heightIn(min = 48.dp)`. The finding's "the touch target is the checkbox alone" clause was false,
  and it was false in the direction that damaged the remedy.
- **A failed open could not be retried on the same file.** `open()` stored the URI before reading the
  header, so every later pick of that file hit the same-file guard. Released now by
  `forgetUriIfStill`, on the two `FILE_UNREADABLE` paths only — the ones whose own advice is "try
  that one again". ⚠️ The posted fix nulled the field unconditionally, which is unsafe: `open(B)` does
  not cancel `open(A)`, so a late A-failure would clear a URI B had already stored. Both arms are now
  pinned by tests.
- **The passphrase field survived picking a second archive**, pre-filled and with Unlock enabled.
  Cleared at the picker callback. The finding filed this under Security & Privacy, which is wrong and
  the screen's own comment says why — a Kotlin `String` cannot be zeroed, so clearing the field drops
  a reference and nothing more. It is a UI-consistency defect: a full 210,000-iteration derivation
  spent to answer "wrong passphrase" for text the user never typed for this file.
- **An unbounded string could overflow WorkManager's 10 KB `Data`.** `Data.Builder.build()` throws
  rather than truncating, and the site that matters is the *success* result: an overflow there is
  caught as a failure, so **a restore that had already completed would be reported as failed** — which
  sends the user to run it again over data that is already correct. The finding named two write sites
  and missed the busiest one (`fail()`, which every failure in both workers routes through) and the
  actual root cause: `isSafeObbLeafName` had **no length bound**, and that leaf is quoted into a
  placement warning from a manifest the doc's own threat model calls attacker-controlled. Fixed at the
  source (`NAME_MAX`, 255) *and* at the boundary (`boundedForJobData`, top-level so a JVM test can
  reach it).

The residue of the Critical was real and small: two comments promised `onFailure` covered "either
collector" while the guard reached only one of them. Closed by running `block` through
`coroutineScope`, which makes the comments true rather than narrowing them — one word, no observable
behaviour change today, and the next `launch` added inside a guarded block does not have to know any
of this.

### Still open — small, and none of them user-facing

- [ ] **`ArchiveRoundTripTest`'s gateway still discards the `compressed` flag.** The restore-side
      decision is now pinned in `RestoreAppArchiveUseCaseTest` (a mixed GZIP/NONE archive, both
      directions asserted), but nothing proves an `ArchiveCompression.NONE` member survives the
      container end to end. Narrow: toybox, GNU and busybox `tar` all sniff gzip magic on read, so
      only an archive whose `tar -czf` fell back — taken on a device with no gzip — is exposed.
- [ ] **`ThorJobLauncher.cancel` has zero call sites** and is not on the injected interface. Either
      wire it to the notification's cancel action or delete it; a launcher method nothing can reach
      reads as a capability the feature has.
- [ ] **`AppDataArchiveGatewayImpl` has no JVM seam.** The class that actually replaces a user's app
      data is reachable by no test in this repository. This is the largest hole in the feature and it
      is structural, not an oversight — see the blind spot below.
- [ ] **`obbOffered` has no true-side assertion** anywhere in `app/src/test` or `app/src/androidTest`,
      so collapsing the field to a constant `false` would go unobserved. Pre-existing, one line to
      close: `assertEquals(true, withObb.uiState.value.obbOffered)` beside the existing `restoreObb`
      assertion.
- [ ] **Five checkbox/switch rows outside this branch have the accessible-name defect `CheckRow`
      just lost.** Found while adjudicating that finding, and it is the reason its severity came down:
      this branch was matching the app-wide status quo, not regressing against a convention.
      `SettingsScreen.kt:1197`, `FreezerSettingsSheet.kt:306`, `PermissionManagerScreen.kt:508`,
      `FreezerScreen.kt:242`, `AppList.kt:530` — each a live `onCheckedChange` beside a sibling text
      `Column`, so a screen reader gets an unnamed control announced *before* the words that name it.
      `FixStoreSheet` and now `CheckRow` are the two that do it correctly. Mechanical, but check each
      for the `minimumInteractiveComponentSize()` trap before copying the shape across.
- [ ] **A repo-wide sweep for line-number citations in committed comments.** Two were found and fixed
      on this branch (`ObbInstaller.kt`, `AppArchiveInstallerImpl.kt`); nobody has checked the rest of
      the tree. A **commit SHA** in a comment is fine and must be left alone — a SHA does not rot. It
      is `File.kt:313` that goes stale silently.

### Do NOT file these as defects — they are decisions, and a future reviewer will re-raise them

Each was raised, verified, and deliberately kept. Filing them would invite a regression.

- **`BaseDestination.publish()` sets `settled = true` before `output.close()`.** Deliberate.
- **`publish()` discards the container on a non-true publish.** Refuted as a data-loss regression on
  platform sources: MediaStore does no rename at all, API 28's `renameTo` cannot throw after
  committing, and `ExternalStorageProvider` mints a *new* doc id, so the pre-rename delete hits a
  dead path and is swallowed. Tests pin both arms.
- **`launchGuarded` has no log line.** The implied remedy emits **nothing** in a build a user runs —
  `Logger` gates every level on `isDebug`, false in release. Already covered generally by
  [`release-builds-emit-no-thor-logcat.md`](release-builds-emit-no-thor-logcat.md).
- **`ArchiveHeaderOutcome` has no `Unreadable` arm.** The `when` is exhaustive with **no `else`**, so
  adding the arm is a compile error that forces a human to write the user-facing sentence. An `else`
  would trade that forcing function for silence. Add the arm only *with* the sentence.
- **`ArchiveRestoreGate` is the authority on what is restorable**, and backup must not offer what
  restore refuses. Not to be weakened to admit a bundle-only archive — `NOTHING_SELECTED` is returned
  *before* the bundle is consulted, so admitting one is a reordering that changes the meaning of every
  existing archive.
- **No global `CoroutineExceptionHandler`** — it would silence reporting this feature is already thin
  on. Still the decision after the #379 review: a child coroutine's failure now reaches `onFailure`
  because `launchGuarded` runs `block` through `coroutineScope`, which *reports* it at the call site.
  A scope-level handler would have swallowed it instead, which is the opposite trade.

### Two durable traps this branch paid for

- **A test that reads back what it wrote using a different reader than production proves nothing.**
  `BackupAppArchiveUseCaseTest` reads with `ZipInputStream` (needs no central directory); restore uses
  `ZipFile` (needs one). Removing `zip.finish()` left two green halves and one unreadable archive.
- **Type-only exception assertions can cover the wrong mechanism.** Deleting two real guards killed
  0 of 91 tests, because the failure threw the same type by another path. Assert the guard's own
  message.

### The blind spot, stated plainly

`ThorJobLauncher` → WorkManager → worker → privileged shell — the region that actually replaces a
user's app data — **is exercised by no test in this repository and by no device run in this review.**
It cannot be closed on the JVM: it needs `Context`, and this module has no Robolectric, no
`koin-test` and no `work-testing`. There are also **zero Compose tests** over `ArchiveRestoreScreen`,
`AppBackupSheet` or the passphrase sheet. Compounding it, a shipped Thor emits nothing to logcat
under any tag it owns, so a failure in that region is both untested and undiagnosable from a user bug
report.

**Merge the branch on the review; do not ship it to users until someone has taken a backup and
restored it on hardware.** That is one check, and it is worth more than everything above.

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

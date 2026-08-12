# App backup and restore — design

**Date:** 2026-08-10
**Status:** **BUILT** — merged to `dev` 2026-08-12 as PR #379 (`940480ef`). Desk-verified only: the
21 device checks in the tracker are unrun, so this is on `dev` but must not ship to users yet.
**Scope:** row 23 of `docs/follow-ups/README.md` — GH#51 phase 2, "app **data** backup". Phase 1
(APK/`.apks`/`.xapk` export) already shipped; #164's OBB half merged as PR #376 (`91100e58`), with
its hardware follow-up in PR #378 (`0fd72541`).
**Tracker:** `docs/follow-ups/app-data-backup-and-xapk-export.md`

---

## 1. Problem

Thor can export an app's code and, as of #376, its expansion files. It cannot export the app's
*data*, which is the half users actually mean when they say "back up my apps" — the save file, the
login, the settings, the year of history. `README.md` has promised this for a year.

The tracker doc states the constraint that shapes everything below:

> a backup that appears to succeed and restores nothing is worse than a disabled button.

That is the failure mode this design spends most of its complexity avoiding. Data backup has no
casual verification: an archive that is subtly wrong looks identical to one that is right until the
day someone needs it, and a restore that is subtly wrong fails when the target app launches, long
after Thor has reported success.

---

## 2. Scope

**In scope**

- Back up one app at a time: its bundle (`.xapk`) plus its private data.
- Restore that archive, including installing the app first when it is absent.
- Mandatory encryption with a single user passphrase.
- A shared long-running-job seam with a foreground service, with backup and restore as its first
  two callers.

**Out of scope for this spec** — each is a follow-up row, not a silent omission:

- Migrating the existing APK/XAPK export batch (`BackupRunner`) onto the job seam.
- Putting bulk actions (freeze/unfreeze/uninstall sweeps) on the job seam. Their foreground-service
  *type* is a separate question — see §9.4.
- Clear-all-cache. #373 shipped broken and #374 fixed it on 2026-08-09; it is not getting touched
  again this week.
- Multi-app batch backup. One app at a time first; batching is a loop over a working single-app
  path, and building it before the single path is proven inverts the risk.
- Scheduled/automatic backups.
- Cloud destinations.

**Naming.** `RestoreRequest.kt` already owns the word *restore* in this codebase, where it means
"unfreeze via the Stormbringer launcher hook" (GH#239). Nothing in this feature may be named bare
`Restore*`. Types here are `AppArchive*`, `AppData*`, or carry an explicit `Archive` qualifier.

---

## 3. Decisions

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | Backup **and** restore, one app at a time | A tar of `/data/user/0/<pkg>` has no consumer at all. Unlike an `.apk`, which any file manager installs, a data archive without a restore path is a file the user cannot use — shipping it alone would repeat the criticism the owner made of phase 1 on the #51 thread. |
| 2 | A sibling file with its own action, never on the share sheet | `.xapk` rides the share sheet; `/data/user/N/<pkg>` is credentials. Also, appending to an `.apk` breaks its v2/v3 signature, so "inside the bundle" could only ever have worked for one of three formats. |
| 3 | Mandatory encryption, one passphrase reused across archives, cached encrypted in DataStore | Owner's call. Simpler to remember than per-archive passphrases. Consequences are handled in §5, chiefly: a fresh salt per archive, and the cache is never the source of truth. |
| 4 | Capability decided by **probing the channel that does the work** | Root passes; shell-uid Shizuku fails; root-started Shizuku passes; Dhizuku fails — all without naming a privilege mode. Measuring capability on the channel that performs the operation is the same discipline `ObbProbe` follows. |
| 5 | Payload is everything the app owns, with **per-class user selection** | See §4.2. Selection is not a convenience checkbox — it is what bounds peak disk. |
| 6 | The `.xapk` goes **inside** the archive | Makes the archive self-sufficient: absent app → install from the archive → restore data, Swift Backup's model. Reuses `AppBundleBuilderImpl` wholesale and removes the need for a separate OBB data class. |
| 7 | Foreground service in **all** flavours, WorkManager-hosted | Owner's call, made with the Play declaration risk stated. See §9.3 and §13. |

### 3.1 The finding that shaped the format

Odin's `Shell.Job` exposes `to(MutableList<String?>)` and
`to(MutableList<String?>, MutableList<String?>)` for output — **line-based strings only**. libsu's
`to(OutputStream)` is not in the fork. `add(InputStream)` / `Shell.cmd(in: InputStream)` exists but
serves the stream as *shell commands*, not as data on a command's stdin.

**There is no binary pipe in either direction.** Every byte that crosses between Thor and the
privileged shell must go through a file. That is the same rendezvous the OBB work proved twice last
week, and it means a monolithic archive would cost 2× the entire payload in peak disk — unaffordable
for a media-heavy app. Hence one member per storage class (§4).

---

## 4. Archive format

### 4.1 Container

A plain zip, built with the existing `BundleZip` writer, named `<pkg>-<versionCode>.thorbak`:

```
thorbak.json           ← plaintext header, STORED
app.xapk               ← plaintext, STORED — splits + OBB + manifest.json + icon
ce.tar.gz.enc          ← present only if selected and non-empty
de.tar.gz.enc
ext-data.tar.gz.enc
ext-media.tar.gz.enc
```

**Encrypted members are STORED, not DEFLATED.** They are already ciphertext; compressing them is
CPU that occasionally grows the file. Compression happens *inside* the member (`tar -czf`), because
shrinking the staged tar shrinks peak disk — the constraint that drove the whole layout.

**The `.xapk` is plaintext.** It is public app code, and the header already names the package, so
encrypting it leaks nothing extra while costing a full pass over hundreds of megabytes. It buys a
real property: lose the passphrase and you still have a working, installable app. Thor already
exports APKs in the clear, so this is self-consistent.

**Always an `.xapk`,** even for a single-APK app with no expansions. Uniform beats a byte saved, and
restore then has exactly one install path.

**`thorbak.json` is written last, not first.** Chunk counts are not known until each member exists,
and knowing them up front would require tarring every class before encrypting any — peak disk back
to the sum. Zip has a central directory and `ZipFile` seeks. This does not contradict `.xapk`'s
sidecars-first rule: that one serves streaming third-party installers, and Thor is the only reader
here.

### 4.2 Storage classes

`DataClass` has four members. `Android/obb/<pkg>` is deliberately **not** a fifth — it rides inside
the `.xapk`, so there is no second OBB path to write, test, and keep in sync with the one just
verified on hardware.

| Class | Path | Notes |
|-------|------|-------|
| `CE` | `/data/user/<u>/<pkg>` | Credential-encrypted. The bulk of what users care about. |
| `DE` | `/data/user_de/<u>/<pkg>` | Device-encrypted. **Not exotic** — PMS creates a `user_de` package directory for *every* app, and that entry spent its whole life missing from `PerUserCommands`' cache list. |
| `EXTERNAL_DATA` | `/storage/emulated/<u>/Android/data/<pkg>` | |
| `EXTERNAL_MEDIA` | `/storage/emulated/<u>/Android/media/<pkg>` | User-visible content; the class most worth declining on restore. |

`cache`, `code_cache` and `no_backup` are excluded from `CE` and `DE`.

The user selects classes, and the `.xapk` itself, with checkboxes. All default on. Unchecking the
bundle produces a data-only archive; the restore screen reads that from the header and says up front
that it needs the app already installed.

Selection is load-bearing, not cosmetic: with one member per class, peak staging is the **largest
single class**, not the sum. A user whose `Android/media/com.whatsapp` is 20 GB can uncheck it and
pay nothing for it.

### 4.3 Header

`thorbak.json` follows `BackupIndex`'s conventions exactly — `encodeDefaults = true` so
`schemaVersion` is written even at its default, `ignoreUnknownKeys = true` so a v1 reader survives a
v2 document, `prettyPrint = true`, and flat entries carrying a `dataClass` string rather than a
sealed hierarchy so a foreign reader need not learn Kotlin's discriminator convention.

Fields:

- `schemaVersion`, `createdAt`, `thorVersionCode`
- `packageName`, `versionCode`, `versionName`, `userId`
- `signerSha256` — SHA-256 of the app's first signing certificate. **Load-bearing:** without it,
  restoring into a same-named but differently-signed package is a data-exfiltration primitive
  (sideload a fake `com.whatsapp`, restore, read everything).
- `appBundle { fileName, bytes, obbCapture, obbCount }` where `obbCapture` is `ObbProbe`'s
  tri-state. Same rule as #376: OBB that could not be read is recorded **Undetermined**, never
  "none", so restore never implies it holds game data it does not.
- `kdf { algorithm, iterations, salt }`
- `verifier`
- `members[] { dataClass, fileName, nonce, plainBytes, chunkCount, compression }`
- `skippedEntries[] { dataClass, name, reason }` — see §7.2.
- `warnings[]` — e.g. a `tar` exit of 1.

---

## 5. Cryptography

None exists in the tree today: zero hits for `Cipher`, `SecretKey`, `PBKDF2`, `EncryptedFile`,
`MasterKey` or `security-crypto`. This uses `javax.crypto` and `AndroidKeyStore` directly and
**explicitly not** `androidx.security:security-crypto`, which is unmaintained.

### 5.1 Key derivation

PBKDF2WithHmacSHA256, 210,000 iterations, 256-bit key, **16-byte salt generated fresh per archive**.
One reused passphrase must not mean one reused key: a per-archive salt keeps cross-archive keys
distinct even though the passphrase is shared.

Ruled out at the start: a device-bound Keystore key with no passphrase. It dies on exactly the two
events a backup exists for — device loss and factory reset.

### 5.2 Chunk framing, and the trap that forces it

`CipherInputStream` **silently swallows `AEADBadTagException`**. It returns `-1` instead of throwing,
so a truncated or tampered archive decrypts to a silently short plaintext and restore writes a
partial database over the user's real one. It cannot be used for GCM.

Members are therefore chunk-framed:

- AES-256-GCM, 1 MiB plaintext chunks, each its own `doFinal`.
- IV = `8-byte random per-member nonce ‖ 4-byte big-endian chunk index`. Unique within a member,
  across members (distinct nonce), and across archives (distinct key).
- AAD per chunk binds the member file name, the chunk index, and a final-chunk flag.
- The header records `chunkCount`; a stream that ends early is refused.

AAD alone does not close truncation at a chunk boundary — the `chunkCount` check does. Together they
mean a corrupt archive fails on the first bad megabyte rather than after writing 20 GB.

### 5.3 Verifier

`HMAC-SHA256(key, "thor-data-archive-v1")` truncated to 16 bytes, stored in the header. A wrong
passphrase is rejected after one key derivation, before a byte is streamed. It leaks nothing the
ciphertext does not already leak to an offline attacker.

### 5.4 Passphrase vault

The passphrase is AES-GCM-encrypted under an `AndroidKeyStore` key with
`setUnlockedDeviceRequired(true)` (API 28, which is minSdk), and the ciphertext lives in DataStore.

**It is a cache, never the source of truth.** If the Keystore key is gone or invalidated — app
reinstall, factory reset, biometric enrolment change — the vault throws, Thor re-prompts, and the
archive is still readable. *The failure mode of the convenience layer is a prompt, not data loss.*
This is the single most important property in this section and it must have its own test.

Two consequences that reach the UI:

- Changing the passphrase does **not** re-encrypt existing archives; Thor says so rather than
  implying otherwise.
- On restore, a stored passphrase that fails the verifier causes a prompt, not a "corrupt archive"
  verdict.

---

## 6. Capability gate

One probe, run through the active gateway's `executeShellCommand` and gated on exit code:

```
ls -1 /data/user/<userId>/<thor pkg> >/dev/null 2>&1
```

**Notation, because two different numbers are called "uid" in this feature and confusing them is a
bug:** `<userId>` is the Android multi-user id (0, 10, …) that appears in a path. `<uid>` in §8.3 is
the app's Linux uid from `ApplicationInfo.uid`, which appears only in `chown`. They are never the
same value and are never interchangeable.

Run against Thor's own data directory, cached per privilege generation, invalidated on
`PrivilegeManager.refresh()`.

**Gate shape:** visible, disabled, with the reason shown. Precedent is band A #1 and the `.xapk`
chip — a control that vanishes hides its own explanation.

**The reason string does not name a privilege mode.** Root-started Shizuku passes this probe, so
"requires Root" would be a lie on that device. The primary line describes the capability; a
secondary line names what typically provides it.

**Restore degrades rather than refusing.** The install half is gateway-routed and works under
Shizuku; only the data half is root-gated. A device that fails the probe is offered *"install the app
only"* from the bundle, stated plainly, rather than having the file rejected.

---

## 7. Backup flow

### 7.1 Staging location

The shell writes into Thor's **internal** `cacheDir` and `chown`s the file to Thor's uid.

This improves on the OBB precedent deliberately. OBB staged in `externalCacheDir` because Thor's own
uid had to *write* files the shell would read. Here Thor only needs to *read*, and root can write
anywhere — so there is no reason to leave a multi-gigabyte plaintext tar of someone's credentials on
shared storage where any all-files-access app can read it.

**No `externalCacheDir` fallback ships.** An earlier draft of this section allowed one where the
privileged shell could not write internally. Implementation retired it on two grounds. First, it is a
security downgrade for exactly the payload that least tolerates one: a multi-gigabyte plaintext tar
on shared storage, readable by any all-files-access app, is the thing the paragraph above exists to
prevent — so "only as a fallback" still means "on the devices that take the fallback, always".
Second, the trigger condition is unreachable: §6's capability probe already excludes the channels
that cannot write internally (a shell-uid Shizuku fails the probe and never reaches staging), so no
device loses the feature by the fallback's absence. Staging is internal or the job fails.

### 7.2 Sequence

1. Capability probe (§6).
2. Resolve `thorUserId`. There is one: `am get-current-user` is denied without
   `INTERACT_ACROSS_USERS`.
3. `du -s -k` per class for the sizing UI. **POSIX `-k`, not `-b`** — `-b` is not safe to assume on
   toybox.
4. `am force-stop` **once**, before the first class. Not per class.
5. Open `<name>.thorbak.partial` at the destination and hold the zip stream open for steps 5–7 —
   every member is appended to this one stream, which is what keeps peak disk at one class.
6. Build the `.xapk` via `AppBundleBuilderImpl` and add it to the container.
7. Per selected class:
   a. `ls -A` the class root; filter `cache` / `code_cache` / `no_backup` **in Kotlin**; pass the
      survivors as quoted operands. Deliberately not `tar --exclude`, which bets on toybox's option
      surface — enumerate-then-list is a pure `List<String> → String?` function, testable the way
      `obbPlaceCommand` is. An empty class root produces no member at all rather than an empty tar.
   b. An entry whose name is not quotable (a newline in a filename, say) is refused and **recorded
      in `skippedEntries`**, not silently dropped.
   c. `tar -czf` into internal cache; on any nonzero exit, retry `tar -cf` and record which one
      worked in the member's `compression`.
   d. Thor chunk-encrypts the staged tar directly into the container.
   e. Delete the staged tar before starting the next class.
8. Write `thorbak.json` and close the stream.
9. Rename `<name>.thorbak.partial` → `<name>.thorbak`.

The container is written incrementally to the destination as `.partial` and renamed on completion. A
crash therefore leaves a file that explains itself rather than a plausible-looking bad backup.

**Destination** reuses `resolveExportTarget`: the saved SAF tree when valid, otherwise
`Downloads/Thor`. Once the payload is ciphertext, forcing a picker buys nothing.

### 7.3 tar exit 1

A `tar` exit of 1 with a non-empty archive is recorded as a warning in the header, **not** treated as
failure. GNU-family tar returns 1 for "a file changed while being read", which is routine on live app
data even after a force-stop.

### 7.4 Pre-flight space

Follows `BackupAppsUseCase.checkStagingSpace`'s existing rule and **fails open** when the partition
reports nothing usable — refusing on an unmeasurable partition would block working devices. The
safety net is elsewhere: a `tar` that runs out of space exits nonzero, and the staged file is deleted
on any failure. Peak is bounded per-class regardless.

---

## 8. Restore flow

Restore starts from the file, not from an installed app, so it is not on `AppInfoSheet`.

### 8.1 Gates

Read the header, show package / archive version / date / classes held, then:

| Condition | Outcome |
|-----------|---------|
| App not installed | Install from `app.xapk`, then restore data. Not a refusal. |
| Signer mismatch on an installed app | **Refuse.** No override. |
| Installed version **older** than the archive | Warn hard; allow on explicit confirm. Newer data on older code is the classic permanent-crash-on-launch. |
| Installed version newer | Proceed quietly. Forward migration is what apps are built for. |
| `CE` selected without `DE` | Warn. `DE` carries first-run state. |
| Archive has no bundle and app is absent | Refuse, and say the archive is data-only. |

### 8.2 Install path

The existing session installer in `InstallerRepositoryImpl`, splits and all — gateway-routed, so it
works under Shizuku. Two ordering constraints, neither optional:

- **The uid does not exist until the install lands.** `chown` must read
  `PackageManager.getApplicationInfo(pkg, 0).uid` *after* install, never from the archive.
- **`session.commit()` is fire-and-forget.** `isInstalled()` immediately afterwards returns false.
  Restore waits on the install result callback; it does not poll. This has bitten this codebase
  before.

### 8.3 Sequence

1. Passphrase → verifier.
2. `am force-stop`.
3. Write the interruption breadcrumb (§8.5).
4. Per selected class:
   a. Decrypt the member to a staged tar in internal cache.
   b. Extract into `<class root>/.thorbak-staging/`.
   c. Delete the class root's existing entries — **every entry except `.thorbak-staging` itself**,
      which is the one thing a naive `rm -rf <class root>/*` would destroy mid-restore — then rename
      the staged entries up one level and remove the now-empty staging directory.
   d. **`chown -R <uid>:<uid>`** using the live uid. A reinstalled app has a *new* uid, so the
      archive's numeric owners are always wrong. **CE and DE only** — `Android/data` on FUSE has
      synthesized ownership and `chown` there is meaningless.
   e. **`restorecon -RF <class root>`**. Extracted files carry no SELinux context and default to
      something the app cannot read. Omitting this is the most common reason a restore "succeeds"
      and the app still crashes.
5. `am force-stop` again — a broadcast can wake the app mid-restore.
6. Delete the breadcrumb.

Extract-then-rename (4b/4c) rather than wipe-then-extract shrinks the destructive window from a
multi-gigabyte extraction to a short series of same-filesystem renames.

Restore **replaces** a class wholesale; it does not merge. Stale files from the current install would
otherwise survive. The confirmation says so in those words.

### 8.4 OBB on an already-installed app

Skipping the bundle when the app is installed would leave a game whose OBB was wiped with no way to
get it back. So restore offers OBB as a restorable item sourced from the bundle: scan the inner
`.xapk` sequentially for `Android/obb/**`, extract one entry at a time to cache, place it with
`obbPlaceCommand`, delete, repeat. Peak is one OBB file and the bundle is never fully extracted.

### 8.5 Interruption breadcrumb

Written before the destructive phase, deleted on success. A breadcrumb surviving to the next launch
means Thor says *"the restore of X was interrupted and its data may be incomplete"* rather than
letting the user discover it when the app crashes.

### 8.6 Completion message

The data is in place and the app should be launched to check. Thor cannot verify from outside that
an app accepts its own data, and claiming otherwise is exactly the "appears to succeed" failure the
tracker singles out.

---

## 9. Execution model

### 9.1 The job seam

A shared base for long-running work, designed against **two** callers on paper even though only one
set migrates in this work — a job abstraction written against exactly one consumer is usually the
wrong abstraction:

- `ThorJobWorker`, a `CoroutineWorker` base holding foreground promotion, the notification, progress
  reporting and cancellation.
- `@Single JobRegistry`, holding `StateFlow`s keyed by job id, so the UI observes progress without
  routing it through WorkManager's `Data`.
- A process-scoped payload holder for values that must never be persisted — chiefly the derived key.

The spec sketches how the APK/XAPK export batch would sit on this seam; it does not move here.

### 9.2 Why progress does not go through `Data`

WorkManager's `Data` is written to its own SQLite database. Putting the passphrase or the derived
key there writes the secret to disk in the clear — disqualifying. And `setProgress` is an SQLite
write per call, so even non-secret progress is worth coarsening — **which Thor does itself**, to
roughly 1/s on the notification and to one update per storage class on the progress callback.
WorkManager promises no throttling of its own; it coalesces nothing.

### 9.3 What WorkManager is and is not doing here

Retry is **off** (`Result.failure()`, never `Result.retry()`): WorkManager restarts a worker from
scratch, and silently restarting a 20 GB operation whose key died with the process is worse than a
clear failure. Deferral is off — the work is expedited and user-initiated only; a backup that starts
at 3 a.m. and fails is not a feature. Boot persistence is meaningless for the same reason.

This also resolves a collision that would otherwise have shipped: §5.4's
`setUnlockedDeviceRequired(true)` makes the vault unusable while the device is locked, which a
deferred background run would hit every time. Deriving the key in the foreground the moment the user
taps *Back up* — device unlocked by definition — keeps the hardening and removes the collision.

Unique work name with `APPEND_OR_REPLACE` so runs serialise and peak disk stays one class at a time.
Queued items whose key died with the process fail cleanly rather than half-running.

### 9.4 Foreground service and its type

`androidx.work:work-runtime`'s own manifest declares the base `FOREGROUND_SERVICE` permission and
`androidx.work.impl.foreground.SystemForegroundService` — but **no `android:foregroundServiceType`**.
WorkManager leaves the type to the app. On targetSdk 34+ (Thor is 37) `setForeground()` against a
typeless service throws `MissingForegroundServiceTypeException`, so `app/src/main/AndroidManifest.xml`
must add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

There is no configuration in which WorkManager provides long-running execution without Thor
declaring the type itself. A worker that never goes foreground is capped by JobScheduler's ~10-minute
window and is Doze-subject, which a multi-gigabyte backup blows straight through.

This is **Thor's first foreground service**; the only `<service>` elements today are
`ThorRootService` and `FreezerTileService`, and `storeRelease`'s merged manifest has no
`FOREGROUND_SERVICE` at all. `POST_NOTIFICATIONS` is already declared, so progress costs no new
runtime grant.

**Type scope.** `dataSync` honestly describes anything that moves bytes — backup, restore, APK and
XAPK export. It does **not** describe a bulk freeze or uninstall sweep, and a type that does not
match the use is what Play's declaration review rejects. Bulk actions likely want `shortService`
(no type permission, no declaration, ~3-minute cap, API 34+, and below 34 there is no type
requirement at all). That belongs to the bulk-actions follow-up, not here.

### 9.5 Flavours

Both. The owner's call, made with the Play declaration risk stated (§13). The generalised seam is
part of the argument: the declaration's demo can show **bulk APK export on an unrooted device**, so
the reviewer can reproduce the foreground service without the root-gated feature.

---

## 10. UI

**`AppInfoSheet`** gains a *Back up app* action opening `AppBackupSheet`.

**`AppBackupSheet`:** the bundle checkbox plus four class checkboxes, sizes resolving asynchronously.
`DataClassSize` is an explicit tri-state — `Known(bytes)` / `Empty` / `Undetermined` — rendered as a
size, "empty", or "size unknown". **Never `0 B` for Undetermined:** a size we could not measure
rendered as zero is how a user deselects data they actually have. Same discipline as `ObbProbe`.
Destination label from the existing `currentTargetLabel()`. Passphrase field on first use only
(set + confirm); afterwards the vault supplies it, with a "use a different passphrase" affordance.

**Restore** is its own entry point — from Settings, or from opening a `.thorbak`. It shows the
header's contents and every gate outcome from §8.1 before anything destructive happens.

**Settings** gains passphrase management, carrying the §5.4 warning that existing archives keep
needing the passphrase they were made with.

**Orphan sweep at launch:** staged tars in `cacheDir` (the Odin shell dies with the process and
leaves partials) and Thor's own `.partial` containers — matched by exact name, never a wildcard
delete in the user's folder.

---

## 11. File layout

Domain — no Android types, JVM-testable:

- `domain/model/AppDataArchive.kt` — `DataClass`, `ArchiveHeader`, `ArchiveMember`, `DataClassSize`.
- `domain/model/AppDataCommands.kt` — every shell string, pure, each returning `String?` where null
  means *refused*. Same shape as `PerUserCommands.kt` and `obbPlaceCommand`. Path quoting, symlink
  guards and package-name validation live here.
- `domain/model/ArchiveRestoreGate.kt` — §8.1 as a pure function.
- `domain/usecase/BackupAppArchiveUseCase.kt`, `RestoreAppArchiveUseCase.kt`,
  `MeasureAppDataUseCase.kt`.
- `domain/repository/AppArchiveStore.kt` — `File`/String terms only; the use cases never touch SAF.

Data:

- `data/backup/AppArchiveCipher.kt` — derivation, chunk framing, verifier. Streams and bytes in and
  out, no Thor types.
- `data/backup/PassphraseVault.kt` — the DataStore + Keystore cache and its cache-not-truth contract.
- `data/backup/job/ThorJobWorker.kt`, `data/backup/job/JobRegistry.kt`.
- `data/repository/AppArchiveStoreImpl.kt` — container assembly over the existing `BundleZip`
  machinery, not a second zip writer.
- `SystemRepositoryImpl` gains the capability probe and the class-size query, root-gated with an
  explicit reason string, following `clearCache`'s precedent verbatim.

Presentation:

- `presentation/backup/AppBackupSheet.kt`, `AppBackupViewModel.kt`
- `presentation/backup/ArchiveRestoreScreen.kt`, `ArchiveRestoreViewModel.kt`

`BackupRunner` is unchanged. Its "deliberately not a foreground service" choice was correct for a
fast APK batch and does not bind a multi-gigabyte operation.

No Room involvement: no migration, no `schemas/7.json`.

---

## 12. Testing

The crypto, the shell strings and the gate logic are three separate pure units precisely so the bulk
of this feature is JVM-testable.

**JVM**

- `AppDataCommands`: hostile inputs, quoting, the enumerate-and-filter function, and the reflective
  *"every builder names its user"* assertion borrowed from `PerUserCommandsTest`.
- `AppArchiveCipher`: round trip; chunk boundaries at exactly 1 MiB, ±1 byte, and zero; wrong
  passphrase rejected by the verifier before streaming; **a tampered chunk detected**; **a truncated
  stream detected** via `chunkCount`. The last two are the tests that would catch the
  `CipherInputStream` trap; they are non-negotiable.
- `PassphraseVault`: a vault whose Keystore key is gone yields a re-prompt, never a failure to
  decrypt an existing archive.
- Header: a v1 reader survives a v2 document with unknown fields, mirroring `BackupIndexTest`;
  `schemaVersion` present at its default.
- `ArchiveRestoreGate`: every row of §8.1.
- `DataClassSize`: `Undetermined` never renders as `0 B`.

Note that `rikka.shizuku.Shizuku`'s static initialiser builds a Binder and throws "not mocked" under
JVM tests, so nothing in these units may touch it — another reason the shell strings are pure.

**Device** — stated plainly because the decision brief flagged restore as *"not desk-testable, not
emulator-testable on a Play image, fails silently until the target app launches"*:

1. Round trip on a small app with only CE data.
2. Round trip on a game with splits and OBB.
3. Restore onto a **fresh install** — proves the `chown`.
4. Restore onto a differently-signed package — must refuse.
5. Restore with the app absent — installs from the bundle, then restores data.
6. Restore with only some classes selected.
7. Wrong passphrase — rejected before streaming.
8. Corrupted / truncated archive — rejected, nothing written.
9. Interrupted restore — breadcrumb present, warning on next launch.
10. Shizuku-only device — gate disables backup; restore offers install-only.
11. `ls -Z` after restore shows app contexts, and the app launches with its data.
12. Large payload — peak disk stays at the largest single class, not the sum.
13. Backup survives leaving the app (the foreground service actually works).

**Build gates.** Lint is fatal (`warningsAsErrors` + `checkTestSources`). Watch `ForegroundServiceType`
and the `UsableSpace` hint — the latter recommends the API that reproduced the cache-clear bug in
#373, so it is suppressed with a reason, not obeyed.

---

## 13. Release checklist

A non-code deliverable, easy to forget until a store upload bounces:

- File the **Play Console Foreground Service declaration** before the first `store` upload carrying
  `FOREGROUND_SERVICE_DATA_SYNC`. Declare `dataSync`, describe the use as long-running
  user-initiated archive operations, and record the demo on **bulk APK export on an unrooted
  device** — reproducible by the reviewer, unlike the root-gated half.
- Release notes must say the backup format is encrypted and that the passphrase is not recoverable.

---

## 14. Known limitations

- **Process death ends a run.** The key cannot be persisted, so a killed process means "re-run",
  not "resume". Stated in the UI rather than hidden.
- **Staging is internal-only, with no fallback.** Where the privileged shell cannot write to Thor's
  internal `cacheDir`, the job fails rather than staging the plaintext tar on shared storage. See
  §7.1: the `externalCacheDir` fallback an earlier draft allowed was retired during implementation,
  because it traded the exposure this design exists to avoid for a case §6's probe already excludes.
- **The bundle is staged.** `AppBundleBuilderImpl` writes to a `File`, so APK bytes stage where they
  could have streamed from `/data/app`. Typically tens to a few hundred MB; the OBB portion was
  already in the staging budget. Eliminating it means teaching the builder to write to an
  `OutputStream` and nesting it as `DEFLATED` at level 0 (which permits streaming unknown-length
  content where `STORED` demands the CRC up front) — not worth un-verifying PR #376's code path for.
- **Extract-then-rename shrinks the destructive window; it does not close it.**
- Android 15 caps `dataSync` at six hours per 24. Not reachable in practice, but real.

---

## 15. To verify at plan time

Not assumptions this design rests on — items to check before writing code:

- Koin's **compiler plugin** with `strictSafety` needs a worker factory for constructor injection.
  `koin-androidx-workmanager` and `@KoinWorker` should cover it, but that combination is not in the
  tree today and is not asserted to work until checked.
- Toybox's actual behaviour for `tar -czf` (and the `-cf` fallback), `ls -A`, `du -s -k`,
  `restorecon -RF` and `chown -R` on the target devices.
- Whether `AppBundleBuilderImpl` can be called with a caller-chosen output name without disturbing
  the export path under test in PR #376.

---

## 16. Follow-ups this spec creates

| Item | Why deferred |
|------|--------------|
| Migrate APK/XAPK export onto the job seam | `BackupRunner` is shipped and working; its `StagingGate`, `NonCancellable` handoff and write-index-on-cancel behaviour are what a migration quietly breaks. |
| Bulk actions on the job seam | Needs its own foreground-service-type answer (§9.4), probably `shortService`. |
| Clear-all-cache on the job seam | #373 shipped broken, #374 fixed it 2026-08-09. Not this week. |
| Multi-app batch backup | A loop over a proven single-app path. |
| Streaming bundle build (`OutputStream` + `DEFLATED` level 0) | Only worth it if the app half turns out to be the peak. |

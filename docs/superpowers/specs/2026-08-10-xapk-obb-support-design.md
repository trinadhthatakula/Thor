# OBB support in `.xapk` export and install

**Issue:** GH#164 — *"xapk files are ignoring obb files"*
**Date:** 2026-08-10
**Status:** Implemented on `feat/xapk-obb-support` (PR #376) — **device verification pending**, nine
checks listed in `docs/superpowers/plans/2026-08-10-xapk-obb-support-implementation.md` Task 12 Step 6
**Branch:** `feat/xapk-obb-support`

Supersedes the *"Missing OBB"* device-verification item in
`docs/follow-ups/app-data-backup-and-xapk-export.md`, and falsifies that doc's closing claim that
*"#164 can be closed now"*.

---

## 1. The problem

Three defects, not one.

### 1.1 Export drops OBB silently

`AppBundleBuilderImpl.build()` stages the base APK and its splits and never looks at
`Android/obb/<pkg>`. A packed game installs cleanly and fails at play time — usually with the app's
own "downloading additional data" screen, which then fails because the CDN entry is gone or the app
expects the file to be present already.

This is the asymmetry the reporter hit. The builder has an explicit *"every one, or none"* doctrine
for splits — `stagedApkNames` refuses to drop one — while OBB is never even looked for.

### 1.2 Install ignores OBB

`BundleZip` matches archive entries by **base name**, and `isSafeEntryFileName`
(`BundleZip.kt:67`) refuses anything that is not a plain leaf. That is deliberate: it makes zip-slip
unrepresentable in the reader. The side effect is that a genuine APKPure `.xapk`, whose OBB entries
live at `Android/obb/<pkg>/*.obb`, has those entries silently dropped. Thor installs the APKs and
the game does not run.

So Thor both fails to produce OBB and fails to consume it.

### 1.3 Thor states a reason that is not true

`app/src/main/res/values/strings.xml:198`:

> `export_explain_xapk` — "Thor packs this app's parts into a single .xapk in the folder below, a
> format other installers understand too. **Game data (OBB files) is left out because Android 11 and
> later stops any app from reading another app's OBB folder;** a large game will install from this
> file but may not run."

The first clause is true of an ordinary app and false of Thor. Verified:

- `ACTION_OPEN_DOCUMENT` and `ACTION_OPEN_DOCUMENT_TREE` cannot reach `Android/data/` or
  `Android/obb/` or their subdirectories. The SAF route really is closed.
- Thor holds no broad storage permission on Android 11+: `WRITE_EXTERNAL_STORAGE` carries
  `android:maxSdkVersion="28"` and `MANAGE_EXTERNAL_STORAGE` appears nowhere in the repo.

But Thor is a root/Shizuku app whose bundle builder *already* reads protected APK paths through a
privileged fallback (`copyFileSafely` → `copyFileWithRoot`). OBB is missing because Thor never asked
for it, not because Android forbids it. The string is translated into all five locales
(`values-ar:535`, `values-es:501`, and the fr / zh-rCN equivalents) and must be **retracted**, not
patched.

---

## 2. Wire format

Pinned by three independent implementations, none of them guessing from each other's source:

| Source | Language | Evidence |
|---|---|---|
| `cade335/xapk-installer` | Kotlin | `XApkExpansion.kt`, `XApkManifest.kt` |
| `dqh147258/XApkInstaller` | Kotlin | `bean/XApkExpansion.kt`, `bean/XApkManifest.kt`, `XApkInstaller.kt` |
| `litefeel/adbtool` | Python | `adbtool/subcommands/apkinstall.py` |

`manifest.json` gains one array:

```json
{
  "xapk_version": 2,
  "package_name": "com.example.game",
  "expansions": [
    {
      "file": "Android/obb/com.example.game/main.12.com.example.game.obb",
      "install_location": "EXTERNAL_STORAGE",
      "install_path": "Android/obb/com.example.game/main.12.com.example.game.obb"
    }
  ]
}
```

- `file` — the entry path **inside** the archive.
- `install_path` — the destination, **relative to the external-storage root**.
- `install_location` — decorative. The reference installer parses it and never branches on it; it
  uses only `file` and `install_path`. Thor emits `"EXTERNAL_STORAGE"` for interoperability and
  ignores it on read.

Two further facts from the reference implementations:

- `dqh147258/XApkInstaller` has a **manifest-free fallback**: with no `expansions`, it treats any
  archive entry ending `.obb` as an expansion and places it at its own entry path. Thor's reader
  should tolerate the same shape, subject to §5's validation.
- `litefeel/adbtool` validates that `install_path` starts with `Android/obb/<package_name>/` and
  rejects duplicate `file` or duplicate `install_path`. Thor adopts both checks.

**SAI is not an authority here.** Its `XapkAppMetaExtractor` reads only `package_name`, `name`,
`version_name`, `version_code` and `icon.png`; there is no expansion handling anywhere in its xapk
resolver. Do not look to SAI to validate this half of the format.

---

## 3. Capability model

### 3.1 The finding that sets the scope

`SystemRepositoryImpl` routes `executeShellCommand` through `runGatewayAction` — the ordinary
privilege-routing helper — and all three gateways implement it:

| Gateway | `executeShellCommand` runs as |
|---|---|
| `RootSystemGateway` | the Odin root shell, real exit codes |
| `ShizukuSystemGateway` | `ShizukuHelper.execute` — Shizuku's privileged process, **shell uid** |
| `DhizukuSystemGateway` | `DhizukuHelper.execute` — `DhizukuAPI.newProcess`, a device-owner **app** process |

This is unlike `copyFileWithRoot` (`SystemRepositoryImpl.kt:238`), which is hard root-gated and
returns `Result.failure(Exception("Root required for privileged copy"))` otherwise — as is
`getAppPaths`.

So OBB work does **not** have to be root-only. One privileged surface covers probe, read and
placement for root and Shizuku alike.

### 3.2 Resulting matrix

| Mode | Probe `Android/obb` | Stage out (export) | Place (install) |
|---|:---:|:---:|:---:|
| Root | ✅ | ✅ | ✅ |
| Shizuku | ✅ shell uid reads `/sdcard` freely | ✅ | ✅ |
| Dhizuku | ❌ DO app process, no cross-package external access | ❌ | ❌ |

Dhizuku degrades visibly (§6), it does not fail silently.

### 3.3 What we are not doing

`MANAGE_EXTERNAL_STORAGE` stays out of the manifest. The `store` flavour ships to Play; Thor already
carries `QUERY_ALL_PACKAGES` and `REQUEST_INSTALL_PACKAGES` with `tools:ignore` policy suppressions,
and a third all-files-access declaration is a fight with no upside. The docs are also ambiguous about
whether it covers `Android/obb` at all — the exclusion list names `/Android/data/` and justifies
itself by app-specific directories, and `Android/obb` is not named either way.

### 3.4 The one filesystem both sides can see

Under Shizuku the shell uid **cannot** write into `/data/data/com.valhalla.thor/` (0700, owned by
the app uid), and Thor **cannot** read `/sdcard/Android/obb/<other-pkg>/`. The only location both
parties can read and write is Thor's own external cache:

```
context.externalCacheDir  →  /sdcard/Android/data/com.valhalla.thor/cache
```

Shell uid can write there; Thor owns it and needs no permission. **Both directions stage through
`externalCacheDir`.** Export copies OBB in via a privileged `cp`, then zips from there. Install
extracts OBB out to there, then copies into place via a privileged `cp`. Under root this is
unnecessary but harmless, and one path is better than two.

If `externalCacheDir` is null (no external volume mounted), **fail** — `AppBundleBuilderImpl` throws
rather than falling back.

> **Amended as built.** This section originally called for a fallback to `copyFileWithRoot` into
> internal cache, restricted to root. It was dropped, not forgotten: `externalCacheDir` is null
> exactly when the primary external volume is unavailable, and `Android/obb/<pkg>` lives on that same
> volume. So there is nothing to stage — the probe cannot read the OBB either, and the `.xapk` chip is
> already disabled. A fallback for that state would be code that can only run when its input does not
> exist.

---

## 4. The probe

### 4.1 Why a path test is not enough

`AppInfoMapper.kt:36-45` computes `obbFilePath` by `File(...).exists()`. On Android 11+ that returns
false for another package's OBB directory **whether or not one exists**. Any probe shaped like a
path test inherits the flaw: under Dhizuku, *"I cannot see it"* and *"there is none"* are the same
answer — and under the chosen failure policy that answer produces exactly the silently-incomplete
bundle we are trying to prevent.

The probe must therefore **assert its own privilege positively** before interpreting absence.

### 4.2 Shape

```kotlin
sealed interface ObbProbe {
    /** Privileged read succeeded and the app has no OBB. `.xapk` is offered normally. */
    data object None : ObbProbe

    /**
     * Privileged read succeeded and found these. [otherEntryCount] counts everything in the
     * directory that is not a depth-1 `*.obb` and therefore will not be packed (§4.4).
     */
    data class Present(val files: List<ObbFile>, val otherEntryCount: Int) : ObbProbe

    /** The active privilege cannot see Android/obb. Never treat as None. */
    data class Undetermined(val reason: String) : ObbProbe
}

data class ObbFile(val name: String, val sizeBytes: Long)
```

Exposed on `SystemRepository` as `suspend fun probeObb(packageName: String): ObbProbe`, implemented
over `executeShellCommand`.

### 4.3 Command

```sh
o=<externalStorageDir>/Android/obb; p=$o/<pkg>
ls -1 "$o" >/dev/null 2>&1 || { echo THOR_NOPRIV; exit 0; }
[ -d "$p" ] || { echo THOR_NODIR; exit 0; }
for f in "$p"/*.obb; do [ -f "$f" ] && stat -c '%s %n' "$f"; done
echo THOR_END
```

Design points, each load-bearing:

- **Listing the parent is the capability assertion.** Root and shell succeed; the Dhizuku DO process
  does not. This is what separates `None` from `Undetermined`.
- **`THOR_END` proves the command ran to completion.** Output without it — truncated, or a shell
  that died mid-script — is `Undetermined`, never `None`. A missing sentinel must not read as
  "no OBB".
- **`stat -c '%s %n'` puts size first**, so a filename containing spaces parses by splitting on the
  first space only. Do not split on whitespace generally.
- **`<externalStorageDir>` comes from `Environment.getExternalStorageDirectory().absolutePath`**,
  shell-quoted — not a hardcoded `/storage/emulated/0`. Per the existing single-`thorUserId`
  constraint (`am get-current-user` is denied without `INTERACT_ACROSS_USERS`), there is no
  multi-user path to juggle.
- **`<pkg>` is shell-quoted too**, and validated against the ordinary package-name character set
  before interpolation. It arrives from `PackageManager` rather than from user input, but this
  string is assembled into a shell command and should not rely on that.
- **A `Result.failure` from the gateway is `Undetermined`**, with the throwable's message as the
  reason.

> **Amended as built.** The command above fails **open** in two ways, both found in review after the
> implementation was complete, and both of them reproduce GH#164 from inside the fix for GH#164 — the
> `.xapk` chip is gated on `Undetermined`, not on `None`, so any corrupt reply that reads as `None`
> offers a bundle and packs no game data. The shipped command therefore differs:
>
> 1. **`stat`'s exit code is checked** (`|| { echo THOR_STATFAIL; exit 0; }`). As written above,
>    `stat` writes its complaint to stderr, the loop carries on, `THOR_END` still prints and the exit
>    code is still 0 — so an expansion file Thor could not measure arrives as an empty directory.
>    Whether `stat -c` is available across ROMs is what §8.3 item 4 exists to establish, so it is the
>    one failure that may not be silent.
> 2. **A `*.obb` name containing CR or LF is refused before `stat` runs** (`THOR_BADNAME`). `%n`
>    prints the name raw, and the name is the only field in this output Thor does not author, so
>    `main.obb<LF>THOR_NODIR` splits into a well-formed record plus a line the parser would read as
>    the script's own "no OBB directory" verdict. The target app writes `Android/obb/<pkg>` with no
>    permission at all — that made the directory-is-empty verdict a switch the app itself could flip.
>    The parser also now disbelieves any `THOR_NODIR` accompanied by listing output, since the genuine
>    branch `exit 0`s before the loop can print anything.
>
> 3. **A `*.obb` that is a symlink is refused** (`THOR_SYMLINK`), and the `-L` test runs *before*
>    `[ -f ]` because `[ -f ]`, `stat` and `cp` all follow links. Following one would have the probe
>    report the target's size and the export's privileged `cp` write the target's bytes into the
>    archive under a game-data name — a read performed with the shell's privilege, not the app's, and
>    `Android/obb/<pkg>` is the app's own directory. Primary external storage is FUSE-backed and
>    creating a link there is expected to fail, so this is hardening rather than a demonstrated
>    exploit; but a privileged shell frequently sees the lower ext4 at `/data/media/0`, where links
>    are ordinary, and a link left behind by a "move OBB to SD" script needs no attacker at all.
>
> The loop consequently iterates `"$p"/*` rather than `"$p"/*.obb`, dispatching on a `case` — which it
> had to anyway, to count `otherEntryCount` (§4.4).
>
> The same reasoning applies twice more outside the probe, and both are fixed with it — a check in the
> probe is a check-then-use across two shell invocations, so neither site inherits its conclusion:
>
> - **§5.2 export copy.** `obbCopyCommand` is now `[ ! -L '<src>' ] && cp -f …`.
> - **§6.3 install placement**, which is the worse direction, because there the app-owned path is the
>   **destination**. `cp -f` unlinks only when the *open* fails, so an existing `<leaf>` that is a
>   symlink is followed — making a root `cp` into an arbitrary write and the `chmod 644` after it an
>   arbitrary chmod. `obbPlaceCommand` now starts `rm -f '<dest>' &&`; `rm` does not follow links.
>   `obbMkdirCommand` gains `&& [ ! -L '<dir>' ]` for the same reason one level up: `mkdir -p`
>   succeeds silently on a symlink to a directory.

### 4.4 Documented limitation

Only `*.obb` files at depth 1 are captured. Subdirectories and non-`.obb` files inside
`Android/obb/<pkg>/` are outside the XAPK convention and are not packed. The probe counts them into
`Present.otherEntryCount`; the export sheet notes that some files will not be included. It does not
block the export — the XAPK format has no way to carry them, so refusing would deny the user a
bundle that is complete by the format's own definition.

---

## 5. Pack side

### 5.1 `zipFiles` gains an entry-name model

`AppBundleBuilderImpl.kt:277` is today:

```kotlin
private suspend fun zipFiles(files: List<File>, zipFile: File) { … ZipEntry(file.name) … }
```

Flat leaf names, which cannot express `Android/obb/<pkg>/x.obb`. It becomes:

```kotlin
private data class ZipSource(val file: File, val entryName: String)
private suspend fun zipFiles(sources: List<ZipSource>, zipFile: File)
```

Existing call sites pass `file.name` and are unchanged in behaviour. This also makes entry naming
testable in isolation, the way `stagedApkNames` already is.

Everything else about `zipFiles` stays: `Deflater.NO_COMPRESSION` (APK and OBB payloads are already
compressed), the 8 KB buffer, and `ensureActive()` per chunk so a multi-gigabyte zip stays
cancellable — which matters far more now that entries can be gigabytes each.

### 5.2 Staging

OBB files are copied into `externalCacheDir` with a privileged `cp` through `executeShellCommand`,
per §3.4, then added to the archive as `Android/obb/<pkg>/<leaf>`.

Streaming instead of staging is not available: Odin's `exec` buffers stdout as `List<String>`, which
is useless for binary payloads. Staging is the cost of admission.

### 5.3 Manifest

`ApksMetadataGenerator.XapkManifest` gains:

```kotlin
@SerialName("expansions") val expansions: List<XapkExpansion> = emptyList()
```

with

```kotlin
@Serializable
data class XapkExpansion(
    @SerialName("file") val file: String,
    @SerialName("install_location") val installLocation: String = "EXTERNAL_STORAGE",
    @SerialName("install_path") val installPath: String,
)
```

`total_size` includes OBB bytes. The existing `xapkJson = Json { encodeDefaults = true;
explicitNulls = false }` already does the right thing with a defaulted list.

Note the existing doc comment on that class: the SDK-level fields are `String` for wire-format
fidelity and must not be "fixed" to `Int`. `expansions` follows the same rule — match the format,
not our taste.

### 5.4 Free space is a precondition, not a hope

Peak usage during a bundle build is roughly:

```
internal (cacheDir):        apkBytes  (splits_staging)  +  apkBytes + obbBytes  (final .xapk)
external (externalCacheDir):                               obbBytes             (OBB staging)
```

On most devices `/data` and `/sdcard` are the same emulated volume, so the practical requirement is
about **2 × (apkBytes + obbBytes)**. A 4 GB game needs roughly 8 GB free.

Check `usableSpace` on both target directories before starting and refuse with the actual numbers
rather than dying part-way through a multi-gigabyte zip. This is the same lesson as the
`pm trim-caches` fix: a target that looks satisfied is not evidence, and a silent no-op is worse
than a stated refusal.

Writing the final archive straight to the user-chosen destination would halve the peak, but that is
a rework of the export flow and is **out of scope** here.

### 5.5 Zip64

A `.xapk` for a large game will exceed 4 GiB total. `java.util.zip.ZipOutputStream` and `ZipFile`
both handle Zip64 transparently, and Play caps an individual OBB at 2 GiB so no single entry
overflows. Flagged as a device-verification item (§8), not a design change.

---

## 6. Install side

### 6.1 A separate, path-aware reader

The existing reader's base-name-only contract is a security property, not an accident. It stays
intact. Expansions get their own function alongside `extractEntries`, with its own validation:

- `install_path` must equal `Android/obb/<pkg>/<leaf>.obb`, where `<pkg>` matches **both** the
  manifest's `package_name` **and** the package actually being installed.
- `<leaf>` must pass `isSafeEntryFileName`.
- Reject `..` segments, absolute paths, and backslashes, after normalisation.
- Reject a duplicate `file` or a duplicate `install_path` within one manifest.
- The `file` entry must exist in the archive and end in `.obb`.
- Manifest-free fallback (§2): entries ending `.obb` are accepted only if their entry path already
  satisfies the `Android/obb/<pkg>/<leaf>.obb` rule. No inference, no rewriting.

### 6.2 Byte budget

`MAX_EXTRACTED_TOTAL_BYTES` is 4 GiB (`BundleZip.kt:29`) and governs the APK set. A large game's OBB
alone can reach that, so **expansions need their own budget constant** rather than sharing the APK
one. Reusing it would refuse legitimate archives.

### 6.3 Placement

Extract to `externalCacheDir` (§3.4 — under Shizuku the shell cannot read Thor's internal cache),
then `mkdir -p` the destination and `cp` into `Android/obb/<pkg>/` through `executeShellCommand`.

Ownership on emulated storage is synthesised from the path by FUSE/sdcardfs, so a file written there
by root or shell should land with the uid the app expects. Device-verification item (§8); if it does
not hold, an explicit `chmod` follows the copy.

**Ordering.** APKs install first, expansions are placed after a successful install. Placing first
would litter `Android/obb` for an install that then fails.

**Do not clear the destination directory.** Place the declared files, overwriting by name, and leave
anything else alone. Wiping a game's existing OBB directory on reinstall is a data-loss decision
that this feature has no mandate to make.

**Partial failure.** If placement fails after the APK install succeeded, report it plainly — app
installed, game data not placed — and leave the install alone. Rolling back would uninstall an app
the user may already have had.

### 6.4 Refusing an archive we cannot honour

If an archive declares expansions and the active privilege cannot place them, Thor **refuses before
installing anything**, via `InstallRefusedException` with the reason.

This is consistent on both counts. It matches the export policy — do not produce or accept a bundle
we know is incomplete — and it matches the reader's existing doctrine in
`selectEntriesToWriteOrRefuse`, which already throws *"refusing to install a partial set"*.

The cost is honest and worth stating: a Dhizuku user who could previously install an OBB `.xapk` now
gets nothing. What they got before was a game that silently did not run, which is what #164 is about.

The capability check runs before staging, in `installPackage`, since the probe is a suspend shell
call and `resolveInstallSetFromFile` is not.

---

## 7. UI and strings

### 7.1 The export chip

`ExportBottomSheet.kt:86` builds `listOf(BundleFormat.autoFor(appInfo), BundleFormat.XAPK)`. Both
chips remain. The `.xapk` chip becomes **disabled with a visible reason** when — and only when — the
probe returns `Undetermined`.

`Present` never disables the chip. The probe and the copy use the same privileged surface, so a
successful read is itself proof the files can be captured; there is no "found it but cannot copy it"
state to represent. This is why the tri-state matters: the whole decision hangs on
`Undetermined` being distinguishable from `None`.

Disabled-with-a-reason, not absent. A chip that simply vanishes leaves the user with no way to learn
why, which was the one weakness in the chosen failure policy; this is the compensation. The reason
text renders where the format explanation already renders (`ExportBottomSheet.kt:258-260`).

Insufficient free space (§5.4) is a **build-time** refusal, not a chip state. It depends on the
chosen format and on space that can change between opening the sheet and confirming, so it is
checked when the build starts and reported as a failure with the actual numbers.

The size estimate shown to the user includes OBB bytes.

### 7.2 Strings

- `export_explain_xapk` rewritten in **all five locales** (en, ar, es, fr, zh-rCN) to stop attributing
  the omission to Android.
- New strings for the disabled-chip reasons, again ×5. Lint is fatal here — `MissingTranslation`,
  `ExtraTranslation` and `UnusedResources` all bite, and `warningsAsErrors` is on.

### 7.3 Adjacent fix in the same class of bug

`AppInfoDetailsScreen.kt:740-742` renders an OBB-directory card fed by the same unreliable
`exists()` gate (`AppInfoMapper.kt:36-45`), so on Android 11+ it is near-permanently blank. Feed it
from the probe. Same bug, same fix, one line of consumer change — including it here rather than
leaving a known-wrong card on screen.

---

## 8. Testing

### 8.1 Unit (JVM)

Pure helpers, so the logic is testable without a device:

- **Probe output parser** — `THOR_NOPRIV`, `THOR_NODIR`, a normal listing, a listing with no
  `THOR_END` (must be `Undetermined`), filenames containing spaces, garbage lines, empty output,
  non-zero exit. Plus the two fail-open shapes from the §4.3 amendment, each of which must be
  `Undetermined` rather than `None`: `THOR_STATFAIL`, `THOR_BADNAME`, `THOR_SYMLINK`, and a
  `THOR_NODIR` planted in a listing by a filename. The command builder is asserted on too — the `stat`
  guard is present, the CR/LF `case` arm precedes the `*.obb` arm (`case` takes the first match, so
  the order *is* the guard), and `[ -L ]` precedes `[ -f ]`.
- **The privileged copies** — the export copy refuses a symlinked source, the placement `rm -f`s its
  destination before writing, and the `mkdir` refuses a symlinked directory.
- **Export plan builder** — probe output → `expansions` entries + archive entry names; leaf
  collisions; a package name needing no escaping vs one that does.
- **Install-side validation** — a hostile table: `../../etc/passwd`, absolute `/data/...`,
  backslash separators, a `<pkg>` that disagrees with the manifest, a `<pkg>` that disagrees with the
  install target, duplicate `file`, duplicate `install_path`, non-`.obb` extension, an
  `install_path` naming an entry absent from the archive.
- **Round trip** — build an `.xapk` carrying expansions, parse it back, assert field-for-field.
- **Budget** — an expansion set over the APK budget must be accepted; one over the expansion budget
  must be refused.

Note the standing trap: run tests with `--rerun-tasks`, delete `build/test-results/` first, and take
counts from the XML rather than the log line.

### 8.2 Device

The real gate. Export a genuine OBB game under root, then under Shizuku; install each on a clean
device; **launch the game**. Installing cleanly is not the test — #164 is a bug where installing
cleanly was exactly the symptom.

Also verify against a real APKPure `.xapk` in the other direction, which closes the second
outstanding item in `app-data-backup-and-xapk-export.md`.

### 8.3 Open device-verification items

1. Files written by the shell uid into `externalCacheDir` are readable and deletable by Thor.
2. Files copied into `Android/obb/<pkg>/` by root or shell get usable synthesised ownership.
3. Zip64 round-trips for an archive over 4 GiB (§5.5).
4. `stat -c '%s %n'` behaves as expected on the toybox builds Thor targets.
5. Whether `File.exists()` on another package's OBB directory really returns false on current
   Android — asserted here from the permission model and not yet observed. It is the premise for
   §4.1 and §7.3.

---

## 9. Scope boundary

Out of scope, deliberately:

- **App data backup** (#51 phase 2, band C row 23) — the next branch, not this one.
- **Dhizuku OBB support.** No route exists short of a new permission.
- **Any new manifest permission**, `MANAGE_EXTERNAL_STORAGE` above all.
- **OBB in `.apks`.** That format has no expansion convention; OBB is XAPK-only.
- **Writing the archive directly to the export destination** (§5.4), which would halve peak disk use
  but reworks the export flow.
- **Non-`.obb` content** in the OBB directory (§4.4).

---

## 10. Decisions worth not re-litigating

1. **Root + Shizuku, not root-only.** Justified by `executeShellCommand` being gateway-routed while
   `copyFileWithRoot` is not.
2. **`externalCacheDir` is the staging point in both directions**, because it is the only filesystem
   location Thor and the shell uid can both read and write.
3. **The probe asserts privilege before interpreting absence.** `None` and `Undetermined` are
   different answers and must never collapse.
4. **Refuse rather than half-install.** Consistent with the export policy and with
   `selectEntriesToWriteOrRefuse`.
5. **Disabled chip with a reason, never a vanished chip.**
6. **Do not clear the destination OBB directory on install.**

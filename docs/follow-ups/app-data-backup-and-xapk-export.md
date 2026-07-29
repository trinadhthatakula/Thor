# Follow-up: `.xapk` export, and the app-data backup the README has promised for a year

**Status:** OPEN — planned together, because the cheap half unblocks nothing on its own and the
expensive half needs the cheap half's plumbing.
**Severity:** Not a defect. One public promise unkept in an issue thread (#164) and one unkept in
`README.md` (#51).
**Effort:** `.xapk` export ≈ half a day. Backup phase 1 ≈ a day. Backup phase 2 (root data) 5–8 days.
**Raised by:** the deferred-items audit (2026-07-29); the owner asked for both to be filed and built
in the same session.

Files:
`app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`,
`app/src/main/java/com/valhalla/thor/domain/repository/AppBundleBuilder.kt`,
`app/src/main/java/com/valhalla/thor/domain/repository/AppBundleFileStore.kt`,
`app/src/main/java/com/valhalla/thor/data/repository/AppBundleFileStoreImpl.kt`,
`app/src/main/java/com/valhalla/thor/data/repository/BundleZip.kt`,
`README.md:76-81` (Upcoming Features), `docs/feature-request-roadmap.md` (#164, #51)

---

## Part 1 — `.xapk` export (the remainder of #164)

### Why it is nearly free

`AppBundleBuilderImpl.build` already writes **both** manifests into every split bundle it produces:

```text
:80  val metadataFile = File(tempSplitDir, "metadata.json")
:81  apksMetadataGenerator.generateJson(appInfo, metadataFile)
:84  val manifestFile = File(tempSplitDir, "manifest.json")
:85  apksMetadataGenerator.generateManifestJson(appInfo, manifestFile)
```

`metadata.json` is the SAI/`.apks` descriptor; `manifest.json` is the **XAPK** descriptor. So the
zip Thor writes today is already structurally an XAPK — it is only ever *named* `.apks` (`:64`). The
missing work is a choice and a filename, not a format.

### What to build

1. A format parameter on `AppBundleBuilder.build` (or a `BundleFormat` enum in `domain/model/`) —
   `APK` / `APKS` / `XAPK`. Keep the current auto-selection as the default: single-APK apps have no
   meaningful choice, so only offer the picker when the app actually has splits.
2. `.xapk` naming plus the right MIME on the way out. `AppBundleFileStore.writeToDownloads` /
   `writeToTree` both take a `mime` — `.xapk` has no registered type, so
   `application/octet-stream` is the honest answer; do **not** claim
   `application/vnd.android.package-archive` for a zip, or file managers will hand it to the
   platform installer, which cannot read it.
3. A third option in `ExportBottomSheet`, and a matching `export_explain_xapk` string. Remember to
   add it to all five locales (`values`, `values-ar`, `values-es`, `values-fr`, `values-zh-rCN`).

### Scope out

**OBB assets.** A real XAPK carries `Android/obb/<pkg>/*.obb`, and Thor exports none. Say so in the
explain string rather than shipping a bundle that silently loses game data — an XAPK missing its OBB
looks valid and fails at play time, which is the worst failure shape available here.

### How to verify

The round trip is the first check, not the only one. Export → reinstall through Thor's own
installer, and then through a third-party one (APKPure's, or SAI). Thor can already *install* an
APKPure `.xapk` (`15f57d6d`, ZipFile-based to survive STORED + data-descriptor entries), so that
much is testable end to end today.

But a successful install hides the two failures that actually matter here, because both produce a
file that installs fine and is still wrong:

1. **Missing OBB.** Export an app that *has* `Android/obb/<pkg>/`, install the result, and launch
   it. It will install cleanly and then fail at play time — which is why the explain string has to
   say so up front (see *Scope out* above). Assert the absence deliberately; do not let a green
   install imply the assets travelled.
2. **Metadata a different reader rejects.** Thor writes `manifest.json` for its own consumption
   today, so nothing has ever validated it against a third-party parser. Check the split names,
   `total_size`, and the `split_configs` list against what SAI/APKPure actually read — a field Thor
   emits but nobody else accepts fails only on someone else's device.

Also worth one negative test: export a split app, delete one split from the zip, and confirm the
installer reports a real error rather than installing a partial app.

---

## Part 2 — App + data backup (#51)

### The standing promise

`README.md:78` has listed **"BackUp App Data"** under *Upcoming Features* for roughly a year. It is
the oldest unkept commitment in the project, and #51 is the highest-impact item on the roadmap
(impact 4).

### Phase 1 — APK-only backup (≈1 day)

Now cheap, because #164 built the plumbing: the SAF `ACTION_OPEN_DOCUMENT_TREE` picker, the
remembered destination with a `Downloads/Thor` default, `AppBundleFileStoreImpl`, progress and
failure states. Phase 1 is a **multi-app** wrapper over the existing single-app export plus a
manifest of what was backed up. Do this in the same session as `.xapk` export — same files, same
tests, one review.

Design notes:
- Batch it through the existing bundle builder per app; do not invent a second path.
- Write one index file (`thor-backup.json`: package, label, versionCode, versionName, format,
  filename, timestamp) so a restore does not have to guess from filenames.
- Bound concurrency. Building bundles is I/O- and CPU-heavy and a 200-app backup must not spawn 200
  jobs; reuse the `BulkFreezeRunner` lesson — one owner, a semaphore, a cancellable scope.

### Phase 2 — Root-only data backup (5–8 days)

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

### Phase 3 — declined

Bespoke phone-to-phone transport. The exported file already rides the share sheet; a custom P2P
transport is 12–20 days for something Nearby Share does.

---

## Sequencing

`.xapk` export and backup phase 1 land together — one branch, one PR, because phase 1 is a batch
wrapper over the same builder the `.xapk` work touches. Phase 2 is its own branch behind a root
gate, and should not block a release.

Once `.xapk` ships, **#164 can be closed** — everything else in that issue is either shipped or
deliberately declined.

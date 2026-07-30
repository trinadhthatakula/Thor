# Follow-up: three defects in the existing export/share path

**Status:** (1) and (3) **FIXED** on the #30 branch, as intended below. (2) **decided, not built** —
the field stays for phase 2; see its section.
**Severity:** one moderate, one cosmetic, one latent-until-#30.
**Effort:** small, small, medium.
**Raised by:** the #30 reconnaissance pass (2026-07-30).
**Resolved:** 2026-07-31, in the #30 branch (PR #293).

These are **not** part of #30's feature work. They are pre-existing, and they sit in exactly the code
#30 touches, so the cheapest moment to fix them is that branch. Filed separately so #30's scope stays
honest and so they do not quietly disappear if #30 slips.

Both fixes landed as a *consequence* of the feature work rather than as separate commits, which is
what "same files" meant. Recorded here per defect so the closure is checkable against the code.

---

## 1. Single-app share declares the wrong MIME type for split apps — moderate — **FIXED**

`MainSideEffect.ShareApp` now carries `mime`, `MainScreen` sets `type = effect.mime`, and the value
comes from `MainViewModel.mimeForBundle(uri)` — the bundle's own extension looked up in
[`BundleFormat`](../../app/src/main/java/com/valhalla/thor/domain/model/BundleFormat.kt), which is
the single place that says only a monolithic `.apk` is a package-archive. `.apks` and `.xapk` are
`application/octet-stream`, so a receiver no longer offers to install a zip.

The MIME lives on the enum rather than in a second `mimeFor`-shaped helper because #164 added a third
format; two copies of "which of these is installable" would already have disagreed. Unknown
extensions fall back to `APKS.mime`, i.e. the non-installable answer — the safe direction, since
mistyping an installable file as opaque costs a chooser entry while the reverse costs a failed
install.

The original report, for the record:

---

`app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt:246`

```kotlin
is MainSideEffect.ShareApp -> {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
```

The type is hardcoded regardless of what was actually built. `AppBundleBuilderImpl` emits a plain
`.apk` for a single-source app but a **`.apks` zip** for any app with splits
(`AppBundleBuilderImpl.kt:56-65`), so sharing a split app announces a zip archive as an installable
package. Receivers that trust the declared type — package installers in particular — will accept the
share and then fail on the contents.

The app already knows better one layer down: `ExportAppUseCase.mimeFor()`
(`ExportAppUseCase.kt:58-60`) returns `application/vnd.android.package-archive` only for `.apk` and
`application/octet-stream` otherwise. The bulk path is also already correct — `ShareApps` uses
`*/*` (`MainScreen.kt:255`). It is only the single-app `ACTION_SEND` that disagrees with both.

**Fix:** carry the MIME type on `MainSideEffect.ShareApp` (computed by the same `mimeFor` logic) and
use it, instead of a literal at the call site. One field plus one line.

**Verify:** share a split app (any large Play-installed app) and confirm the chooser no longer offers
package installers; share a single-APK app and confirm it still does.

---

## 2. The OBB row in the app details screen can never render — cosmetic — **option 2, deliberately**

`app/src/main/java/com/valhalla/thor/data/repository/AppInfoMapper.kt:36-42`,
`app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt:674`

```kotlin
val obbFilePath = if (!isLightweight) {
    val obbFile = File(Environment.getExternalStorageDirectory(), "Android/obb/${appInfo.packageName}")
    if (obbFile.exists()) obbFile.absolutePath else null
}
```

Thor cannot see that path. `AndroidManifest.xml:30-32` declares `WRITE_EXTERNAL_STORAGE` with
`android:maxSdkVersion="28"` and nothing else — no `READ_EXTERNAL_STORAGE`, no
`MANAGE_EXTERNAL_STORAGE`. On API 29+ scoped storage denies the traversal outright, and from Android
11 `Android/obb/<other package>` is blocked even *with* `MANAGE_EXTERNAL_STORAGE`. With `minSdk = 28`
the only devices where this could ever have returned non-null are API 28, and only if a runtime grant
Thor does not appear to request had been given.

So `obbFilePath` is null in practice on the entire install base, the Room column
(`AppEntity.kt:33`) stores nothing, and `AppInfoDetailsScreen.kt:674`'s `?.let` block is dead UI.
No crash, no wrong data shown — just a feature that silently is not there.

**Fix — pick one, they are opposite directions:**
1. **Delete it.** The mapper block, the `AppInfo`/`AppEntity` field (needs a Room migration) and the
   details row. Honest, and removes a `File.exists()` off the app-list mapping path.
2. **Make it work for the one case that matters.** OBB data is only reachable via the root gateway,
   so it belongs to #30 phase 2 (root data backup), not to app-list metadata. If #30 phase 2 is
   going ahead, keep the field and populate it from `RootSystemGateway` instead.

Do **not** do both by accident: option 2 means this row stays dead until phase 2 lands, which is fine
but should be a decision, not a leftover.

### Decision: option 2. Recorded, not a leftover.

Three reasons, one of which corrects the case made for option 1 above:

- **Option 1's incentive does not exist.** "Removes a `File.exists()` off the app-list mapping path"
  is wrong: the whole `!isLightweight` block, OBB probe included, is skipped for the app list —
  `AppRepositoryImpl.kt:146` maps with `isLightweight = true`. The only non-lightweight callers are
  `getAppDetails` and the APK-file inspector, both one app at a time. So deleting it saves one stat
  per details-screen open, not one per installed app. There is no hot path here to clean up.
- **The field is phase 2's, and phase 2 is the plan of record.** Root data backup needs the OBB
  path; deleting the column now means adding it back with a second migration later.
- **A migration on this branch is the expensive part, and it collides.** Room is at version 5 on
  `dev` and `feat/freeze-profiles` (#295) already takes it to 6. A third schema change in flight for
  a cosmetic removal is the worst-value migration available.

**What this costs:** the details row stays dead until phase 2 populates the field from
`RootSystemGateway`. That is invisible — a `?.let` over a null — not a wrong value on screen. If
phase 2 is ever dropped, option 1 becomes right again and this decision should be reopened rather
than inherited.

---

## 3. Nothing deletes a staged bundle after a successful export — latent, blocks #30 phase 1 — **FIXED**

Fixed as prescribed: export and share now take different shapes, because they want opposite things
from the staged file.

`ExportAppUseCase.exportInto` writes the bundle to the destination and deletes its staged copy in a
`finally`, so a batch frees each app before starting the next and `cacheDir` is bounded by
concurrency rather than by batch size. `BackupAppsUseCase` is a wrapper over that call rather than a
reimplementation of it precisely so it cannot drift from that one property; its KDoc says so.

Two things the fix needed beyond "delete after write":

- **A staging scope per run** (`export_batch_<nanoTime>`), taken whole in the use case's `finally`.
  The builder wipes its per-package directory on entry, so a single export of an app a batch is also
  exporting used to delete that batch's work mid-copy. A run-owned directory means a cancelled run
  cleaning up cannot reach its replacement's files.
- **A pre-flight**, because deleting as you go bounds the *steady state* and not the *peak*.
  `checkStagingSpace` refuses a batch whose largest app cannot be staged `concurrency` times over,
  using `StorageManager.getAllocatableBytes` rather than `File.usableSpace` — the latter ignores the
  clearable cache the platform would evict, so it refuses batches the device could run. It fails
  open when it cannot measure.

Share is unchanged and still keeps its file alive for the receiver, which was always correct.

The original report:

---

`app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt:45-46`, `:89`

There are exactly two cleanup calls in the builder:

| Line | What it wipes | When |
|---|---|---|
| `:46` | this package's own subdir | at the **start** of building that same package |
| `:89` | `splits_staging` inside the bundle | after zipping |

Nothing wipes a finished bundle after it has been exported or shared. The current per-package
scoping is deliberate and correct for what exists today — the comment at `:41-45` explains it: bulk
share builds sequentially into one `cacheSubDir` and hands every `content://` URI to
`ACTION_SEND_MULTIPLE` *after* the loop, so wiping per call would delete earlier bundles before the
receiver reads them.

That trade stops working at #30's scale. A batch backup stages **every** app's full bundle in
`cacheDir` and frees none of it until the same package is exported again. This is cache, so Android
will evict it under pressure rather than leak permanently, but an export run can still fill the
partition mid-run and fail — and it fails late, after doing most of the work.

**Fix:** give the batch path an explicit lifecycle — build → write to the destination → delete that
one bundle → next. That is incompatible with the current "collect URIs, send at the end" shape, so
it wants a different flow for *export* than for *share*, which is the right split anyway: a share
must keep the file alive for the receiver, an export must not.

**This is the one to resolve before #30 phase 1 ships**, not after.

---

## Acceptance

- **(1) DONE in a unit test, OUTSTANDING on device.** `BundleFormatTest.only a monolithic apk is
  typed as a package archive` pins the mapping the fix reads from. The device check in the section
  above — share a split app, confirm the chooser stops offering package installers — is still worth
  doing once, because the chooser's behaviour is the actual claim and no test reaches it.
- **(2) N/A.** Nothing to verify; the decision is that the row stays dead. It gets an acceptance
  when phase 2 populates the field.
- **(3) OUTSTANDING (needs a device).** A 50+ app export run with
  `adb shell du -sh /data/data/com.valhalla.thor/cache` sampled during and after, showing cache
  returns to its pre-run size. `BackupAppsUseCaseTest.the whole run stages under one scope of its
  own, and it is gone afterwards` covers the lifecycle against a fake, which is the logic; only a
  device shows the bytes.

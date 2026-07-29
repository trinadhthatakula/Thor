# Follow-up: three defects in the existing export/share path

**Status:** OPEN, unfixed. All three found while scoping #30 (`.xapk` export + app-data backup) and
verified against shipped code, not inferred from the plan.
**Severity:** one moderate, one cosmetic, one latent-until-#30.
**Effort:** small, small, medium.
**Raised by:** the #30 reconnaissance pass (2026-07-30).

These are **not** part of #30's feature work. They are pre-existing, and they sit in exactly the code
#30 touches, so the cheapest moment to fix them is that branch. Filed separately so #30's scope stays
honest and so they do not quietly disappear if #30 slips.

---

## 1. Single-app share declares the wrong MIME type for split apps — moderate

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

## 2. The OBB row in the app details screen can never render — cosmetic

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

---

## 3. Nothing deletes a staged bundle after a successful export — latent, blocks #30 phase 1

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

- (1) and (2) each carry a one-line verification in their sections; do those on device.
- (3) needs a 50+ app export run with `adb shell du -sh /data/data/com.valhalla.thor/cache` sampled
  during and after, showing cache returns to its pre-run size.

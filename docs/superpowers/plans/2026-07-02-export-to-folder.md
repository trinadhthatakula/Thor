# Export App to Folder (#164) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export an installed app to a user-visible folder (default `Downloads/Thor`, or a remembered SAF folder) via a simple bottom sheet, reusing Share's bundle logic.

**Architecture:** Extract Share's cache-bundle builder into a shared `AppBundleBuilder`. A pure `resolveExportTarget()` decides SAF-vs-Downloads; `ExportAppUseCase` builds the bundle, resolves the target, and writes via MediaStore (Downloads/Thor) or SAF `DocumentFile`. A self-contained `ExportBottomSheet` (target + Change + Export) is hosted locally in the App Info screen.

**Tech Stack:** Kotlin, Coroutines, Koin annotations, Room-free, `MediaStore`, `DocumentFile` (SAF), Jetpack Compose, DataStore, JUnit4.

## Global Constraints
- Build/verify `fossDebug`: `./gradlew :app:testFossDebugUnitTest` (compiles + unit tests). **In this environment `./gradlew` via the Bash tool is redirected** — run it through the context-mode executor instead.
- Target SDK 37 / minSdk 28, **scoped storage**: **never** use `MANAGE_EXTERNAL_STORAGE`. Downloads write = `MediaStore.Downloads` (API 29+) / `WRITE_EXTERNAL_STORAGE maxSdkVersion="28"` legacy.
- Format mirrors Share: no splits → `.apk`; splits → `.apks` (base + splits + `metadata.json` + `manifest.json`).
- `@Single`/`@Factory`/`@KoinViewModel` auto-register via `@ComponentScan("com.valhalla.thor")` — no `di/Modules.kt` edits.
- Bundle-building must stay behavior-identical to today's Share output.

---

### Task 1: Extract `AppBundleBuilder` (behaviour-preserving refactor of Share)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/AppBundleBuilder.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/usecase/ShareAppUseCase.kt`

**Interfaces:**
- Produces: `@Single class AppBundleBuilder` with `suspend fun build(appInfo: AppInfo): Result<File>` — a cache file (`.apk` no-split, `.apks` split).

- [ ] **Step 1: Create `AppBundleBuilder`** — move the cache-build logic verbatim.

Create `AppBundleBuilder.kt`:

```kotlin
package com.valhalla.thor.domain.usecase

import com.valhalla.thor.BuildConfig
import android.content.Context
import com.valhalla.thor.data.util.ApksMetadataGenerator
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.formattedAppName
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable/exportable app bundle in the cache dir: a single `.apk` for
 * apps with no splits, an `.apks` (base + splits + metadata.json + manifest.json)
 * for split apps. Copies with a root fallback for protected/system apps.
 */
@Single
class AppBundleBuilder(
    private val context: Context,
    private val systemRepository: SystemRepository,
    private val apksMetadataGenerator: ApksMetadataGenerator
) {
    suspend fun build(appInfo: AppInfo): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "share_temp")
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            cacheDir.mkdirs()

            val finalFile: File
            if (appInfo.splitPublicSourceDirs.isEmpty()) {
                val sourcePath = appInfo.publicSourceDir ?: appInfo.sourceDir
                    ?: return@withContext Result.failure(Exception("No source path found"))
                finalFile = File(cacheDir, "${appInfo.formattedAppName()}_${appInfo.versionName}.apk")
                if (!copyFileSafely(sourcePath, finalFile)) {
                    return@withContext Result.failure(Exception("Failed to copy base APK"))
                }
            } else {
                finalFile = File(cacheDir, "${appInfo.formattedAppName()}_${appInfo.versionName}.apks")
                val tempSplitDir = File(cacheDir, "splits_staging")
                tempSplitDir.mkdirs()

                val allPaths = mutableListOf<String>()
                appInfo.sourceDir?.let { allPaths.add(it) }
                allPaths.addAll(appInfo.splitPublicSourceDirs)

                val filesToZip = allPaths.mapNotNull { path ->
                    val destFile = File(tempSplitDir, path.substringAfterLast("/"))
                    if (copyFileSafely(path, destFile)) destFile else null
                }.toMutableList()
                if (filesToZip.isEmpty()) {
                    return@withContext Result.failure(Exception("Failed to copy any APK files"))
                }

                val metadataFile = File(tempSplitDir, "metadata.json")
                apksMetadataGenerator.generateJson(appInfo, metadataFile)
                filesToZip.add(metadataFile)

                val manifestFile = File(tempSplitDir, "manifest.json")
                apksMetadataGenerator.generateManifestJson(appInfo, manifestFile)
                filesToZip.add(manifestFile)

                zipFiles(filesToZip, finalFile)
                tempSplitDir.deleteRecursively()
            }
            Result.success(finalFile)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun copyFileSafely(sourcePath: String, destFile: File): Boolean {
        return try {
            File(sourcePath).copyTo(destFile, overwrite = true)
            true
        } catch (_: Exception) {
            systemRepository.copyFileWithRoot(sourcePath, destFile.absolutePath).isSuccess
        }
    }

    private fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            val data = ByteArray(1024)
            files.forEach { file ->
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        while (true) {
                            val readBytes = origin.read(data)
                            if (readBytes == -1) break
                            out.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite `ShareAppUseCase` to delegate to the builder.**

Replace the entire contents of `ShareAppUseCase.kt` with:

```kotlin
package com.valhalla.thor.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import org.koin.core.annotation.Factory

@Factory
class ShareAppUseCase(
    private val context: Context,
    private val bundleBuilder: AppBundleBuilder
) {
    suspend operator fun invoke(appInfo: AppInfo): Result<Uri> =
        bundleBuilder.build(appInfo).map { file ->
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", file)
        }
}
```

- [ ] **Step 3: Compile — Share still builds.**

Run (via context-mode ctx_execute): `./gradlew :app:compileFossDebugKotlin 2>&1 | tail -25`
Expected: BUILD SUCCESSFUL. (`AppBundleBuilder` auto-registers via `@Single`; `ShareAppUseCase` now takes only `Context` + `AppBundleBuilder`.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/usecase/AppBundleBuilder.kt \
        app/src/main/java/com/valhalla/thor/domain/usecase/ShareAppUseCase.kt
git commit -m "refactor(share): extract AppBundleBuilder for reuse by export (#164)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: DataStore — `exportDirUri`

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt`
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt`

**Interfaces:**
- Produces: `UserPreferences.exportDirUri: String?`; `PreferenceRepository.setExportDirUri(uri: String?)`.

- [ ] **Step 1: Add the field to `UserPreferences`** — after the last property (before the closing `)`):

```kotlin
    // Export destination (persisted SAF tree URI; null = default Downloads/Thor)
    val exportDirUri: String? = null
```

- [ ] **Step 2: Add the interface method** — in `PreferenceRepository.kt`, next to `setLanguage`:

```kotlin
    suspend fun setExportDirUri(uri: String?)
```

- [ ] **Step 3: Implement it** — mirror the existing nullable `language` pref exactly.

In `PreferenceRepositoryImpl.kt`, in the `Keys` object (next to `LANGUAGE`):
```kotlin
        val EXPORT_DIR_URI = stringPreferencesKey("export_dir_uri")
```
In the `userPreferences` flow's `UserPreferences(...)` construction (next to `language = prefs[Keys.LANGUAGE],`):
```kotlin
            exportDirUri = prefs[Keys.EXPORT_DIR_URI],
```
And the setter (next to `setLanguage`):
```kotlin
    override suspend fun setExportDirUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.EXPORT_DIR_URI)
            else it[Keys.EXPORT_DIR_URI] = uri
        }
    }
```
(Match the surrounding `context.dataStore.edit { ... }` form used by `setLanguage`.)

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileFossDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt \
        app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt
git commit -m "feat(prefs): persist export destination (exportDirUri) (#164)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `resolveExportTarget()` — pure target resolver (TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ExportTarget.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ExportTargetTest.kt`

**Interfaces:**
- Produces: `sealed interface ExportTargetChoice { object Downloads; data class Custom(treeUri: String) }`; `data class ExportTargetResolution(choice, clearSavedDir: Boolean)`; `fun resolveExportTarget(savedUri: String?, isSavedValid: Boolean): ExportTargetResolution`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/domain/model/ExportTargetTest.kt`:

```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTargetTest {

    @Test
    fun noSavedDir_usesDownloads_noClear() {
        val r = resolveExportTarget(savedUri = null, isSavedValid = false)
        assertEquals(ExportTargetChoice.Downloads, r.choice)
        assertFalse(r.clearSavedDir)
    }

    @Test
    fun savedValid_usesCustom_noClear() {
        val r = resolveExportTarget(savedUri = "content://tree/abc", isSavedValid = true)
        assertEquals(ExportTargetChoice.Custom("content://tree/abc"), r.choice)
        assertFalse(r.clearSavedDir)
    }

    @Test
    fun savedInvalid_fallsBackToDownloads_andClears() {
        val r = resolveExportTarget(savedUri = "content://tree/gone", isSavedValid = false)
        assertEquals(ExportTargetChoice.Downloads, r.choice)
        assertTrue(r.clearSavedDir)
    }
}
```

- [ ] **Step 2: Run — verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ExportTargetTest" 2>&1 | tail -20`
Expected: FAIL — unresolved `resolveExportTarget` / `ExportTargetChoice`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/valhalla/thor/domain/model/ExportTarget.kt`:

```kotlin
package com.valhalla.thor.domain.model

/** Where an export should be written. */
sealed interface ExportTargetChoice {
    /** Public Downloads/Thor (MediaStore / legacy). */
    data object Downloads : ExportTargetChoice
    /** A user-picked SAF tree (persisted URI string). */
    data class Custom(val treeUri: String) : ExportTargetChoice
}

/**
 * @param clearSavedDir true when a saved-but-invalid dir should be cleared from prefs.
 */
data class ExportTargetResolution(
    val choice: ExportTargetChoice,
    val clearSavedDir: Boolean
)

/**
 * Pure resolver: use the saved SAF dir only if it is still valid; otherwise fall
 * back to Downloads (and signal a clear when a stale dir was saved).
 */
fun resolveExportTarget(savedUri: String?, isSavedValid: Boolean): ExportTargetResolution =
    when {
        savedUri != null && isSavedValid ->
            ExportTargetResolution(ExportTargetChoice.Custom(savedUri), clearSavedDir = false)
        savedUri != null ->
            ExportTargetResolution(ExportTargetChoice.Downloads, clearSavedDir = true)
        else ->
            ExportTargetResolution(ExportTargetChoice.Downloads, clearSavedDir = false)
    }
```

- [ ] **Step 4: Run — verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ExportTargetTest" 2>&1 | tail -20`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ExportTarget.kt \
        app/src/test/java/com/valhalla/thor/domain/model/ExportTargetTest.kt
git commit -m "feat(export): pure resolveExportTarget() + tests (#164)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `ExportAppUseCase` — build, resolve, write

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/valhalla/thor/domain/usecase/ExportAppUseCase.kt`
- Possibly modify: `app/build.gradle.kts` (add `androidx.documentfile` if unresolved)

**Interfaces:**
- Consumes: `AppBundleBuilder.build` (T1), `PreferenceRepository.exportDirUri`/`setExportDirUri` (T2), `resolveExportTarget`/`ExportTargetChoice` (T3).
- Produces: `@Factory class ExportAppUseCase` with `suspend operator fun invoke(appInfo): Result<String>` (returns a human location label) and `suspend fun currentTargetLabel(): String`.

- [ ] **Step 1: Declare the legacy permission** — in `AndroidManifest.xml` after the `RECEIVE_BOOT_COMPLETED` line:

```xml
    <uses-permission
        android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
```

- [ ] **Step 2: Create `ExportAppUseCase`**

Create `ExportAppUseCase.kt`:

```kotlin
package com.valhalla.thor.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.ExportTargetChoice
import com.valhalla.thor.domain.model.resolveExportTarget
import com.valhalla.thor.domain.repository.PreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import java.io.File
import java.io.IOException

@Factory
class ExportAppUseCase(
    private val context: Context,
    private val bundleBuilder: AppBundleBuilder,
    private val preferenceRepository: PreferenceRepository
) {
    /** Build the bundle and write it to the resolved target. Returns a location label. */
    suspend operator fun invoke(appInfo: AppInfo): Result<String> = withContext(Dispatchers.IO) {
        try {
            val file = bundleBuilder.build(appInfo).getOrElse { return@withContext Result.failure(it) }
            val mime = mimeFor(file)

            val savedUri = preferenceRepository.userPreferences.first().exportDirUri
            val resolution = resolveExportTarget(savedUri, isTreeWritable(savedUri))
            if (resolution.clearSavedDir) preferenceRepository.setExportDirUri(null)

            val location = when (val choice = resolution.choice) {
                is ExportTargetChoice.Custom -> writeToTree(file, choice.treeUri.toUri(), mime)
                ExportTargetChoice.Downloads ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writeToDownloads(file, mime)
                    else writeToDownloadsLegacy(file)
            }
            Result.success(location)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) e.printStackTrace()
            Result.failure(e)
        }
    }

    /** The label shown in the export sheet ("Downloads/Thor" or the saved folder name). */
    suspend fun currentTargetLabel(): String {
        val savedUri = preferenceRepository.userPreferences.first().exportDirUri
        return if (savedUri != null && isTreeWritable(savedUri)) {
            DocumentFile.fromTreeUri(context, savedUri.toUri())?.name ?: "Selected folder"
        } else "Downloads/Thor"
    }

    private fun mimeFor(file: File) =
        if (file.name.endsWith(".apk")) "application/vnd.android.package-archive"
        else "application/octet-stream"

    private fun isTreeWritable(uriStr: String?): Boolean {
        if (uriStr == null) return false
        return try {
            val doc = DocumentFile.fromTreeUri(context, uriStr.toUri())
            doc != null && doc.exists() && doc.canWrite()
        } catch (_: Exception) { false }
    }

    private fun writeToDownloads(source: File, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Thor")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // don't leave a dangling pending entry
            throw e
        }
        return "Downloads/Thor"
    }

    private fun writeToDownloadsLegacy(source: File): String {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Thor"
        )
        if (!dir.exists()) dir.mkdirs()
        source.copyTo(File(dir, source.name), overwrite = true)
        return "Downloads/Thor"
    }

    private fun writeToTree(source: File, treeUri: Uri, mime: String): String {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Invalid folder")
        tree.findFile(source.name)?.delete() // overwrite
        val doc = tree.createFile(mime, source.name) ?: throw IOException("Could not create file")
        context.contentResolver.openOutputStream(doc.uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("openOutputStream failed")
        return tree.name ?: "Selected folder"
    }
}
```

- [ ] **Step 3: Compile (add the DocumentFile dep only if unresolved)**

Run: `./gradlew :app:compileFossDebugKotlin 2>&1 | tail -25`
- If it fails with `unresolved reference: documentfile` / `DocumentFile`, add to `app/build.gradle.kts` dependencies: `implementation("androidx.documentfile:documentfile:1.0.1")`, then re-run.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/valhalla/thor/domain/usecase/ExportAppUseCase.kt \
        app/build.gradle.kts
git commit -m "feat(export): ExportAppUseCase — MediaStore/SAF writers + target resolve (#164)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Export bottom sheet + App Info action

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt`
- Add strings: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `ExportAppUseCase` (T4), `PreferenceRepository.setExportDirUri` (T2).

- [ ] **Step 1: Create the self-contained `ExportBottomSheet`**

Create `ExportBottomSheet.kt`. It shows the current target, a "Change" row that launches the SAF folder picker (persisting the grant + saving to prefs), and an Export button.

```kotlin
package com.valhalla.thor.presentation.appList

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(appInfo: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportUseCase = koinInject<ExportAppUseCase>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val scope = rememberCoroutineScope()

    var targetLabel by remember { mutableStateOf("Downloads/Thor") }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { targetLabel = exportUseCase.currentTargetLabel() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch {
                preferenceRepository.setExportDirUri(uri.toString())
                targetLabel = exportUseCase.currentTargetLabel()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Export ${appInfo.appName ?: appInfo.packageName}")
            Text("Destination: $targetLabel")
            TextButton(onClick = { picker.launch(null) }) { Text("Change folder") }
            Button(
                enabled = !exporting,
                onClick = {
                    exporting = true
                    scope.launch {
                        val result = exportUseCase(appInfo)
                        exporting = false
                        result
                            .onSuccess {
                                Toast.makeText(context, "Saved to $it", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    "Export failed: ${it.message ?: "unknown error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
            ) { Text(if (exporting) "Exporting…" else "Export") }
        }
    }
}
```

- [ ] **Step 2: Add an "Export" action + host the sheet in `AppInfoDetailsScreen`**

Read `AppInfoDetailsScreen.kt`. The actions row composable (the one with the `onShare: () -> Unit` param around line 669, rendered near line 230 with `onShare = { onAppAction(AppClickAction.Share(details.appInfo)) }`) exposes typed callbacks. Do three things, matching the existing style:
1. Add an `onExport: () -> Unit` parameter to that actions composable and an action button beside Share — reuse the existing action-button pattern used for `onShare`; pick an existing export/save drawable (`grep -n 'R.drawable' AppInfoDetailsScreen.kt` for a suitable one, e.g. a download/share icon).
2. At the call site, add a local state near the screen's other state: `var showExportSheet by remember { mutableStateOf(false) }`, and pass `onExport = { showExportSheet = true }` (handled **locally** — do NOT bubble it through `onAppAction`).
3. Where the screen renders its other overlays, add:
```kotlin
if (showExportSheet) {
    ExportBottomSheet(appInfo = details.appInfo, onDismiss = { showExportSheet = false })
}
```
Add imports: `androidx.compose.runtime.getValue/setValue/mutableStateOf/remember` (if not already present).

- [ ] **Step 3: Add the action label string** — in `strings.xml` near the other action labels:
```xml
    <string name="action_export">Export</string>
```
Use `stringResource(R.string.action_export)` for the button's label/content-description (match how the Share button labels itself).

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileFossDebugKotlin 2>&1 | tail -25`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt \
        app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(export): App Info Export action + destination bottom sheet (#164)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Full verification, manual test, push & PR

**Files:** none.

- [ ] **Step 1: Full unit tests + assemble**

Run: `./gradlew :app:testFossDebugUnitTest 2>&1 | tail -12` then `./gradlew assembleFossDebug 2>&1 | tail -12`
Expected: BUILD SUCCESSFUL; `ExportTargetTest` (3) + existing tests pass; APK assembles (Koin resolves `AppBundleBuilder`/`ExportAppUseCase`).

- [ ] **Step 2: Manual verification (device)**

- App Info → **Export**: a single-APK app writes `<name>.apk`, a split app writes `<name>.apks`, both under **Downloads/Thor** (visible in a file manager) with a "Saved to Downloads/Thor" toast.
- **Change folder** → pick a folder → export → file lands there; the sheet now shows that folder's name; relaunch and it's remembered.
- Delete the picked folder → export again → falls back to Downloads/Thor and the label reverts.
- Rooted device: a system/protected app exports (root copy fallback).

- [ ] **Step 3: Push + open PR into `dev`**

```bash
git push -u origin feat/export-to-folder
gh pr create --base dev --head feat/export-to-folder \
  --title "feat: export app to folder (Downloads/Thor or SAF) (#164)" \
  --body "Implements #164 (single-app export). See docs/superpowers/specs/2026-07-02-export-to-folder-design.md.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

---

## Notes for the implementer
- The pure `resolveExportTarget()` is the only unit-tested piece; MediaStore/SAF/DocumentFile writes are device-dependent (manual).
- Export is handled **locally** in the App Info screen (self-contained sheet) — no `MainViewModel`/`AppClickAction` global routing, unlike Share.
- Do not add `MANAGE_EXTERNAL_STORAGE`. The `WRITE_EXTERNAL_STORAGE maxSdkVersion="28"` is only for the API-28 legacy Downloads write.

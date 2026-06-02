# Freezer Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the existing filtered-list Freezer with a user-curated app manager backed by its own Room table, a Manage sheet, a Settings sheet, and a Quick Settings tile.

**Architecture:** New `FreezerRepository` (interface in `domain/`, impl in `data/`). `FreezerViewModel` combines the freezer DB flow with the installed-apps flow via `combine`. All system operations (freeze/unfreeze) go through the existing `ManageAppUseCase`. `AppItemGrid` is made `internal` so `FreezerScreen` can reuse it directly.

**Tech Stack:** Room (KSP), Koin 4.x (`@Single`, `@KoinViewModel`, component scan), Jetpack Compose Material3 (`ModalBottomSheet`, `LazyVerticalGrid`, `SnackbarHost`), Android `TileService`.

---

## File Map

| Action | Path |
|--------|------|
| New | `app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerEntity.kt` |
| New | `app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerDao.kt` |
| Modified | `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt` |
| New | `app/src/main/java/com/valhalla/thor/domain/repository/FreezerRepository.kt` |
| New | `app/src/main/java/com/valhalla/thor/data/repository/FreezerRepositoryImpl.kt` |
| Modified | `app/src/main/java/com/valhalla/thor/di/Modules.kt` |
| Modified | `app/src/main/java/com/valhalla/thor/presentation/widgets/AppList.kt` |
| Replaced | `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt` |
| New | `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerSettingsSheet.kt` |
| New | `app/src/main/java/com/valhalla/thor/presentation/freezer/ManageFreezerSheet.kt` |
| Replaced | `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt` |
| Modified | `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt` |
| Modified | `app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt` |
| New | `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt` |
| Modified | `app/src/main/AndroidManifest.xml` |

---

## Task 1: FreezerEntity + FreezerDao + AppDatabase v3

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerEntity.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerDao.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt`

- [ ] **Step 1: Create FreezerEntity**

```kotlin
// FreezerEntity.kt
package com.valhalla.thor.data.source.local.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "freezer_apps")
data class FreezerEntity(
    @PrimaryKey val packageName: String
)
```

- [ ] **Step 2: Create FreezerDao**

```kotlin
// FreezerDao.kt
package com.valhalla.thor.data.source.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FreezerDao {
    @Query("SELECT * FROM freezer_apps")
    fun getAll(): Flow<List<FreezerEntity>>

    @Query("SELECT packageName FROM freezer_apps")
    suspend fun getAllPackageNames(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM freezer_apps WHERE packageName = :packageName)")
    suspend fun contains(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FreezerEntity)

    @Query("DELETE FROM freezer_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
```

- [ ] **Step 3: Bump AppDatabase to version 3**

Open `AppDatabase.kt`. Replace:
```kotlin
@Database(entities = [AppEntity::class], version = 2, exportSchema = true)
```
with:
```kotlin
@Database(entities = [AppEntity::class, FreezerEntity::class], version = 3, exportSchema = true)
```
Add the abstract DAO accessor below `fun appDao()`:
```kotlin
abstract fun freezerDao(): FreezerDao
```

- [ ] **Step 4: Build to verify KSP generates without errors**

```bash
./gradlew :app:kspFossDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If Room complains about a missing migration, confirm `fallbackToDestructiveMigration(dropAllTables = true)` is still present in `Modules.kt`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerEntity.kt \
        app/src/main/java/com/valhalla/thor/data/source/local/room/FreezerDao.kt \
        app/src/main/java/com/valhalla/thor/data/source/local/room/AppDatabase.kt
git commit -m "feat(freezer): add FreezerEntity, FreezerDao, bump DB to v3"
```

---

## Task 2: FreezerRepository interface + FreezerRepositoryImpl

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/repository/FreezerRepository.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/FreezerRepositoryImpl.kt`

- [ ] **Step 1: Create the domain interface**

```kotlin
// FreezerRepository.kt
package com.valhalla.thor.domain.repository

import kotlinx.coroutines.flow.Flow

interface FreezerRepository {
    fun getAll(): Flow<List<String>>
    suspend fun getAllPackageNames(): List<String>
    suspend fun add(packageName: String)
    suspend fun remove(packageName: String)
    suspend fun contains(packageName: String): Boolean
}
```

- [ ] **Step 2: Create the data implementation**

```kotlin
// FreezerRepositoryImpl.kt
package com.valhalla.thor.data.repository

import com.valhalla.thor.data.source.local.room.FreezerDao
import com.valhalla.thor.data.source.local.room.FreezerEntity
import com.valhalla.thor.domain.repository.FreezerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [FreezerRepository::class])
class FreezerRepositoryImpl(
    private val freezerDao: FreezerDao
) : FreezerRepository {

    override fun getAll(): Flow<List<String>> =
        freezerDao.getAll().map { list -> list.map { it.packageName } }

    override suspend fun getAllPackageNames(): List<String> =
        freezerDao.getAllPackageNames()

    override suspend fun add(packageName: String) {
        freezerDao.insert(FreezerEntity(packageName))
    }

    override suspend fun remove(packageName: String) {
        freezerDao.delete(packageName)
    }

    override suspend fun contains(packageName: String): Boolean =
        freezerDao.contains(packageName)
}
```

- [ ] **Step 3: Compile check**

```bash
./gradlew :app:compileFossDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/FreezerRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/FreezerRepositoryImpl.kt
git commit -m "feat(freezer): add FreezerRepository interface and impl"
```

---

## Task 3: DI — wire FreezerDao into Koin

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt`

- [ ] **Step 1: Add FreezerDao binding**

Open `Modules.kt`. Inside `AppModule`, add after the existing `fun appDao(...)` binding:

```kotlin
@Single
fun freezerDao(appDatabase: AppDatabase): FreezerDao = appDatabase.freezerDao()
```

`FreezerRepositoryImpl` is auto-discovered by `@ComponentScan("com.valhalla.thor")` — no further change needed.

- [ ] **Step 2: Build to verify Koin graph compiles**

```bash
./gradlew :app:assembleFossDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/di/Modules.kt
git commit -m "feat(freezer): wire FreezerDao into Koin"
```

---

## Task 4: FreezerViewModel (full replacement)

**Files:**
- Replace: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt`

- [ ] **Step 1: Replace the entire file**

```kotlin
package com.valhalla.thor.presentation.freezer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.GetInstalledAppsUseCase
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinViewModel

// packageName + appName of an app frozen outside the freezer list — drives the "Add to Freezer" snackbar
data class FreezerPrompt(val packageName: String, val appName: String?)

data class FreezerUiState(
    val isLoading: Boolean = true,
    val isRoot: Boolean = false,
    val isShizuku: Boolean = false,
    val freezerApps: List<AppInfo> = emptyList(),
    val freezerPackageNames: Set<String> = emptySet(),
    val allInstalledApps: List<AppInfo> = emptyList(),
    val multiSelection: Set<String> = emptySet(),
    val manageSheetSearchQuery: String = "",
    val actionMessage: String? = null,
    val freezerPrompt: FreezerPrompt? = null
)

@KoinViewModel
class FreezerViewModel(
    private val freezerRepository: FreezerRepository,
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val manageAppUseCase: ManageAppUseCase,
    private val systemRepository: SystemRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FreezerUiState())
    val uiState: StateFlow<FreezerUiState> = _uiState.asStateFlow()

    init {
        observeApps()
        loadPrivileges()
    }

    private fun observeApps() {
        viewModelScope.launch {
            combine(
                freezerRepository.getAll(),
                getInstalledAppsUseCase()
            ) { freezerPkgs, (userApps, _) ->
                val pkgSet = freezerPkgs.toSet()
                Triple(pkgSet, userApps.filter { it.packageName in pkgSet }, userApps)
            }
            .flowOn(Dispatchers.Default)
            .collect { (pkgSet, freezerApps, allApps) ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        freezerPackageNames = pkgSet,
                        freezerApps = freezerApps,
                        allInstalledApps = allApps
                    )
                }
            }
        }
    }

    private fun loadPrivileges() {
        viewModelScope.launch(Dispatchers.IO) {
            val hasRoot = systemRepository.isRootAvailable()
            val hasShizuku = systemRepository.isShizukuAvailable()
            _uiState.update { it.copy(isRoot = hasRoot, isShizuku = hasShizuku) }
        }
    }

    // --- Freeze All / Unfreeze All ---

    fun freezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames
            pkgs.forEach { manageAppUseCase.setAppDisabled(it, true) }
            _uiState.update { it.copy(actionMessage = "Froze ${pkgs.size} apps") }
        }
    }

    fun unfreezeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val pkgs = _uiState.value.freezerPackageNames
            pkgs.forEach { manageAppUseCase.setAppDisabled(it, false) }
            _uiState.update { it.copy(actionMessage = "Unfroze ${pkgs.size} apps") }
        }
    }

    // --- Multi-select removal ---

    fun toggleSelection(packageName: String) {
        _uiState.update {
            val sel = it.multiSelection.toMutableSet()
            if (packageName in sel) sel.remove(packageName) else sel.add(packageName)
            it.copy(multiSelection = sel)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelection = emptySet()) }
    }

    fun selectAll() {
        _uiState.update { it.copy(multiSelection = it.freezerPackageNames.toSet()) }
    }

    fun removeFromFreezer(packageNames: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            packageNames.forEach { pkg ->
                freezerRepository.remove(pkg)
                manageAppUseCase.setAppDisabled(pkg, false)
            }
            _uiState.update { it.copy(multiSelection = emptySet(), actionMessage = "Removed ${packageNames.size} app(s) from Freezer") }
        }
    }

    // --- Manage Sheet ---

    fun toggleManaged(packageName: String, add: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (add) {
                freezerRepository.add(packageName)
                manageAppUseCase.setAppDisabled(packageName, true)
            } else {
                freezerRepository.remove(packageName)
                manageAppUseCase.setAppDisabled(packageName, false)
            }
        }
    }

    fun updateManageSheetSearch(query: String) {
        _uiState.update { it.copy(manageSheetSearchQuery = query) }
    }

    // --- Snackbar from AppInfoDialog (app frozen outside freezer) ---

    fun addToFreezer(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            freezerRepository.add(packageName)
            _uiState.update { it.copy(freezerPrompt = null, actionMessage = "Added to Freezer") }
        }
    }

    fun showFreezerPrompt(packageName: String, appName: String?) {
        _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
    }

    fun dismissFreezerPrompt() {
        _uiState.update { it.copy(freezerPrompt = null) }
    }

    // --- Single-app freeze/unfreeze (called from AppInfoDialog in FreezerScreen) ---

    fun freezeSingleApp(packageName: String, appName: String?, inFreezer: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            manageAppUseCase.setAppDisabled(packageName, true)
            if (!inFreezer) {
                _uiState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
            } else {
                _uiState.update { it.copy(actionMessage = "Frozen ${appName ?: packageName}") }
            }
        }
    }

    fun unfreezeSingleApp(packageName: String, appName: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            manageAppUseCase.setAppDisabled(packageName, false)
            _uiState.update { it.copy(actionMessage = "Unfrozen ${appName ?: packageName}") }
        }
    }

    // --- Feedback ---

    fun dismissMessage() {
        _uiState.update { it.copy(actionMessage = null) }
    }
}
```

- [ ] **Step 2: Compile check**

```bash
./gradlew :app:compileFossDebugKotlin
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt
git commit -m "feat(freezer): replace FreezerViewModel with new curated-list implementation"
```

---

## Task 5: FreezerSettingsSheet

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerSettingsSheet.kt`

- [ ] **Step 1: Create the settings bottom sheet**

```kotlin
package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerSettingsSheet(
    onDismiss: () -> Unit,
    onUnfreezeAll: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Freezer Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    onUnfreezeAll()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Unfreeze All")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "AUTO FREEZE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Coming soon — automatically freeze apps on a schedule or trigger.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerSettingsSheet.kt
git commit -m "feat(freezer): add FreezerSettingsSheet"
```

---

## Task 6: ManageFreezerSheet

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/freezer/ManageFreezerSheet.kt`

- [ ] **Step 1: Create the manage sheet**

This sheet shows all installed user apps in a grid. Apps already in the freezer are highlighted with a check overlay. Toggling fires `onToggle(packageName, add)`.

```kotlin
package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.widgets.AppIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFreezerSheet(
    allApps: List<AppInfo>,
    freezerPackageNames: Set<String>,
    searchQuery: String,
    imageLoader: ImageLoader,
    onSearchChange: (String) -> Unit,
    onToggle: (packageName: String, add: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = remember(allApps, searchQuery) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.appName?.contains(searchQuery, ignoreCase = true) == true ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Manage Freezer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Spacer(Modifier.height(16.dp))

            // Search bar
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    if (searchQuery.isEmpty()) {
                        Text(
                            "Search apps…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 32.dp
            )
        ) {
            items(filtered, key = { it.packageName }) { app ->
                val inFreezer = app.packageName in freezerPackageNames
                FreezerManageItem(
                    app = app,
                    inFreezer = inFreezer,
                    imageLoader = imageLoader,
                    onClick = { onToggle(app.packageName, !inFreezer) }
                )
            }
        }
    }
}

@Composable
private fun FreezerManageItem(
    app: AppInfo,
    inFreezer: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(
                if (inFreezer) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp)
    ) {
        Box {
            AppIcon(app.packageName, app.enabled, app.isSuspended, 56.dp, imageLoader)
            if (inFreezer) {
                Icon(
                    painter = painterResource(R.drawable.check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.appName ?: app.packageName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
```

> `AppIcon` is already an internal composable in `AppList.kt`. If the compiler can't resolve it, check its visibility and make it `internal` (same step as Task 7 Step 1 — run Task 7 Step 1 first if needed).

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/freezer/ManageFreezerSheet.kt
git commit -m "feat(freezer): add ManageFreezerSheet"
```

---

## Task 7: FreezerScreen + expose AppItemGrid/AppIcon as internal

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/AppList.kt`
- Replace: `app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt`

- [ ] **Step 1: Make AppItemGrid and AppIcon internal in AppList.kt**

In `AppList.kt`, find `private fun AppItemGrid(` and change `private` to `internal`:
```kotlin
internal fun AppItemGrid(
```
Find `private fun AppIcon(` (or however it is declared) and make it `internal`:
```kotlin
internal fun AppIcon(
```
These are in the same module so `internal` is sufficient for cross-file access within `:app`.

- [ ] **Step 2: Replace FreezerScreen.kt entirely**

```kotlin
package com.valhalla.thor.presentation.freezer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.rememberAsyncImagePainter
import coil3.request.crossfade
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppClickAction
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.presentation.utils.AppIconFetcher
import com.valhalla.thor.presentation.utils.AppIconKeyer
import com.valhalla.thor.presentation.utils.getAppIcon
import com.valhalla.thor.presentation.widgets.AppInfoDialog
import com.valhalla.thor.presentation.widgets.AppItemGrid
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezerScreen(
    modifier: Modifier = Modifier,
    viewModel: FreezerViewModel = koinViewModel(),
    onAppAction: (AppClickAction) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedAppInfo by remember { mutableStateOf<AppInfo?>(null) }
    var showManageSheet by rememberSaveable { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(context))
            }
            .crossfade(true)
            .build()
    }

    // Toast for simple action feedback
    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.dismissMessage()
        }
    }

    // Snackbar for "Add to Freezer" prompt (fired from AppInfoDialog when app not in freezer)
    LaunchedEffect(state.freezerPrompt) {
        state.freezerPrompt?.let { prompt ->
            val result = snackbarHostState.showSnackbar(
                message = "Frozen",
                actionLabel = "Add to Freezer",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.addToFreezer(prompt.packageName)
            }
            viewModel.dismissFreezerPrompt()
        }
    }

    // Exit multi-select on back press
    BackHandler(state.multiSelection.isNotEmpty()) {
        viewModel.clearSelection()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // --- Header ---
            if (state.multiSelection.isNotEmpty()) {
                // Multi-select header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.multiSelection.size == state.freezerApps.size,
                        onCheckedChange = { checked ->
                            if (checked) viewModel.selectAll() else viewModel.clearSelection()
                        }
                    )
                    Text(
                        text = "${state.multiSelection.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Button(
                        onClick = { viewModel.removeFromFreezer(state.multiSelection) },
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Remove")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(painterResource(R.drawable.round_close), "Close")
                    }
                }
            } else {
                // Normal header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.frozen),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.freezer),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = (-1).sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.freezeAll() },
                            shape = RoundedCornerShape(12.dp),
                            enabled = state.freezerApps.isNotEmpty()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.frozen),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Freeze All")
                        }
                        FilledTonalIconButton(onClick = { showSettingsSheet = true }) {
                            Icon(painterResource(R.drawable.round_settings), "Settings")
                        }
                    }
                }
            }

            // --- App Grid ---
            if (state.freezerApps.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.frozen),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            "No apps in Freezer",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Tap ＋ to add apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.freezerApps, key = { it.packageName }) { app ->
                        AppItemGrid(
                            app = app,
                            isSelected = app.packageName in state.multiSelection,
                            imageLoader = imageLoader,
                            onClick = { selectedAppInfo = app },
                            onLongClick = { viewModel.toggleSelection(app.packageName) }
                        )
                    }
                }
            }
        }

        // FAB — ＋ opens ManageFreezerSheet
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(end = 16.dp, bottom = 88.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            androidx.compose.material3.FloatingActionButton(
                onClick = { showManageSheet = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_add),
                    contentDescription = "Manage Freezer"
                )
            }
        }
    }

    // AppInfoDialog
    selectedAppInfo?.let { app ->
        AppInfoDialog(
            appInfo = app,
            isRoot = state.isRoot,
            isShizuku = state.isShizuku,
            onDismiss = { selectedAppInfo = null },
            onAppAction = { action ->
                when (action) {
                    is AppClickAction.Freeze -> {
                        viewModel.freezeSingleApp(
                            app.packageName,
                            app.appName,
                            inFreezer = app.packageName in state.freezerPackageNames
                        )
                        selectedAppInfo = null
                    }
                    is AppClickAction.UnFreeze -> {
                        viewModel.unfreezeSingleApp(app.packageName, app.appName)
                        selectedAppInfo = null
                    }
                    else -> {
                        onAppAction(action)
                        selectedAppInfo = null
                    }
                }
            }
        )
    }

    // Manage Sheet
    if (showManageSheet) {
        ManageFreezerSheet(
            allApps = state.allInstalledApps,
            freezerPackageNames = state.freezerPackageNames,
            searchQuery = state.manageSheetSearchQuery,
            imageLoader = imageLoader,
            onSearchChange = viewModel::updateManageSheetSearch,
            onToggle = { pkg, add -> viewModel.toggleManaged(pkg, add) },
            onDismiss = { showManageSheet = false }
        )
    }

    // Settings Sheet
    if (showSettingsSheet) {
        FreezerSettingsSheet(
            onDismiss = { showSettingsSheet = false },
            onUnfreezeAll = viewModel::unfreezeAll
        )
    }
}
```


- [ ] **Step 3: Add `round_add` and `round_settings` drawable references**

Check that `R.drawable.round_add` and `R.drawable.round_settings` exist:
```bash
find app/src/main/res -name "round_add*" -o -name "round_settings*"
```
If either is missing, use an existing equivalent drawable from the project (e.g. `R.drawable.add` or `R.drawable.settings`) and update the references in `FreezerScreen.kt`.

- [ ] **Step 4: Build and fix any import/visibility errors**

```bash
./gradlew :app:assembleFossDebug 2>&1 | grep -E "error:|warning:" | head -30
```
Fix any unresolved references (`AppIcon`, `AppItemGrid` visibility, missing drawables).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/widgets/AppList.kt \
        app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerScreen.kt \
        app/src/main/java/com/valhalla/thor/presentation/freezer/FreezerViewModel.kt
git commit -m "feat(freezer): rewrite FreezerScreen with grid, FAB, multi-select"
```

---

## Task 8: AppListViewModel + AppListScreen — "Add to Freezer" snackbar

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt`

- [ ] **Step 1: Inject FreezerRepository into AppListViewModel**

In `AppListViewModel.kt`, add the import and constructor parameter:
```kotlin
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.presentation.freezer.FreezerPrompt
```
Add to the constructor (Koin picks it up automatically via component scan):
```kotlin
@KoinViewModel
class AppListViewModel(
    private val getInstalledAppsUseCase: GetInstalledAppsUseCase,
    private val getAppDetailsUseCase: GetAppDetailsUseCase,
    private val systemRepository: SystemRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val freezerRepository: FreezerRepository   // NEW
) : ViewModel() {
```

Add `freezerPrompt` to `AppListUiState`:
```kotlin
val freezerPrompt: FreezerPrompt? = null   // add at end of AppListUiState
```

- [ ] **Step 2: Update freezeApp to emit the prompt when app is not in freezer**

Find the existing `fun freezeApp(packageName: String, appName: String?, freeze: Boolean)` method. Replace its success branch:

```kotlin
fun freezeApp(packageName: String, appName: String?, freeze: Boolean) {
    viewModelScope.launch {
        val result = manageAppUseCase.setAppDisabled(packageName, freeze)
        result.onSuccess {
            if (freeze) {
                val inFreezer = withContext(Dispatchers.IO) {
                    freezerRepository.contains(packageName)
                }
                if (!inFreezer) {
                    _rawState.update { it.copy(freezerPrompt = FreezerPrompt(packageName, appName)) }
                } else {
                    _rawState.update { it.copy(actionMessage = "Frozen ${appName ?: packageName}") }
                }
            } else {
                _rawState.update { it.copy(actionMessage = "Unfrozen ${appName ?: packageName}") }
            }
        }.onFailure { e ->
            _rawState.update { it.copy(actionMessage = "Error: ${e.message}") }
        }
    }
}
```

Add dismiss + add-to-freezer functions:
```kotlin
fun dismissFreezerPrompt() {
    _rawState.update { it.copy(freezerPrompt = null) }
}

fun addToFreezer(packageName: String) {
    viewModelScope.launch(Dispatchers.IO) {
        freezerRepository.add(packageName)
        _rawState.update { it.copy(freezerPrompt = null, actionMessage = "Added to Freezer") }
    }
}
```

- [ ] **Step 3: Add snackbar to AppListScreen**

In `AppListScreen.kt`, add near the top of the composable:
```kotlin
val snackbarHostState = remember { SnackbarHostState() }
```

Add a `LaunchedEffect` for the freezer prompt (after existing `LaunchedEffect` blocks):
```kotlin
LaunchedEffect(state.freezerPrompt) {
    state.freezerPrompt?.let { prompt ->
        val result = snackbarHostState.showSnackbar(
            message = "Frozen",
            actionLabel = "Add to Freezer",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.addToFreezer(prompt.packageName)
        }
        viewModel.dismissFreezerPrompt()
    }
}
```

Wrap the root `Column` in a `Box` and add `SnackbarHost` at the bottom:
```kotlin
Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ... existing content unchanged ...
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 80.dp)
    ) { data ->
        Snackbar(snackbarData = data)
    }
}
```

- [ ] **Step 4: Build and verify**

```bash
./gradlew :app:assembleFossDebug 2>&1 | grep -E "error:" | head -20
```
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppListViewModel.kt \
        app/src/main/java/com/valhalla/thor/presentation/appList/AppListScreen.kt
git commit -m "feat(freezer): show 'Add to Freezer' snackbar from AppList after freeze"
```

---

## Task 9: FreezerTileService + AndroidManifest

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create FreezerTileService**

```kotlin
package com.valhalla.thor.presentation.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.valhalla.thor.R
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FreezerTileService : TileService() {

    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val systemRepository: SystemRepository by inject()

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope?.launch { refreshTile() }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        scope?.launch {
            val hasPrivilege = withContext(Dispatchers.IO) {
                systemRepository.isRootAvailable() ||
                        systemRepository.isShizukuAvailable() ||
                        systemRepository.isDhizukuAvailable()
            }
            if (!hasPrivilege) {
                Toast.makeText(
                    applicationContext,
                    "Grant Root / Shizuku / Dhizuku first",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
            if (pkgs.isEmpty()) return@launch
            pkgs.forEach { pkg ->
                withContext(Dispatchers.IO) { manageAppUseCase.setAppDisabled(pkg, true) }
            }
            refreshTile()
        }
    }

    private suspend fun refreshTile() {
        val tile = qsTile ?: return
        val hasPrivilege = withContext(Dispatchers.IO) {
            systemRepository.isRootAvailable() ||
                    systemRepository.isShizukuAvailable() ||
                    systemRepository.isDhizukuAvailable()
        }
        if (!hasPrivilege) {
            tile.state = Tile.STATE_UNAVAILABLE
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                tile.subtitle = "No privilege granted"
            }
            tile.updateTile()
            return
        }
        val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
        tile.state = if (pkgs.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = if (pkgs.isEmpty()) "No apps" else "${pkgs.size} apps · tap to freeze"
        }
        tile.updateTile()
    }
}
```

- [ ] **Step 2: Register in AndroidManifest.xml**

Inside the `<application>` block, add before the closing `</application>` tag:

```xml
<service
    android:name=".presentation.tile.FreezerTileService"
    android:exported="true"
    android:icon="@drawable/frozen"
    android:label="@string/freezer"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

- [ ] **Step 3: Final build**

```bash
./gradlew assembleFossDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Verify Koin inject() works in TileService**

`TileService` is not a Koin-managed component. The `by inject()` delegates use `KoinAndroidExtensions` which resolve from the global Koin context (started by `KoinApplication` in `Application`). This works out of the box with `koin-android` — no extra setup needed.

If the app crashes at tile tap with `KoinException: No definition found`, add `KoinAndroidContext` override:
```kotlin
// At class level in FreezerTileService
override fun getKoin(): Koin = (application as KoinComponent).getKoin()
```
Or implement `AndroidKoinComponent`:
```kotlin
class FreezerTileService : TileService(), AndroidKoinComponent { ... }
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/tile/FreezerTileService.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(freezer): add FreezerTileService Quick Settings tile"
```

---

## Done

After all tasks, install the debug APK, verify:
1. Freezer screen shows empty state with ＋ FAB
2. Tapping ＋ opens Manage sheet — checking an app freezes it and adds it to the grid
3. Unchecking removes it from the grid and unfreezes it
4. "Freeze All" header button freezes all grid apps
5. Long-press enters multi-select → "Remove" removes and unfreezes
6. ⚙ sheet shows "Unfreeze All" and "Auto Freeze (coming soon)"
7. AppList screen: freezing an app not in the freezer shows "Frozen / Add to Freezer" snackbar
8. Quick Settings tile appears in tile picker, freezes all on tap, shows "No privilege granted" when none granted

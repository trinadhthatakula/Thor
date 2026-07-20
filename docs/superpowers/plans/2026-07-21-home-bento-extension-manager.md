# Home Bento Actions + Extension Manager Entry — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface an Extension Manager entry on Home by restructuring Home's stacked action cards into an adaptive 2×2 "bento" grid, with the Extension tile gated on an active privilege.

**Architecture:** A pure `homeActionRows(...)` function computes which action tiles sit in which row (unit-tested against the visibility matrix). A new size-adaptive `BentoTile` replaces the old full-width `ActionCard`. `HomeActionsBento` renders the rows (pair → weighted; solo → full-width) and is used by both HomeScreen layout branches. Navigation reuses the existing shared `ExtensionManager` destination, pushed onto the active tab's back stack.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `androidx.compose.material3.adaptive` (`currentWindowAdaptiveInfoV2` + `WindowSizeClass`), Koin, JUnit4 (unit test).

## Global Constraints

- minSdk 28; JDK 21 (Zulu); Compose Material 3.
- No ViewModel/data-layer changes — every value already exists in `HomeUiState`.
- Existing actions (reinstall / install / clear-cache) must stay behavior-identical.
- Extension tile visible iff `state.activePrivilegeMode != null`; Clear-cache tile iff `== PrivilegeMode.ROOT`.
- Adaptive across COMPACT/MEDIUM/EXPANDED width classes; tiles purely weight-based; EXPANDED caps Home content to a centered `1200.dp` max-width.
- Both `assembleFossDebug` AND `assembleStoreDebug` must build green.
- Extension icon = `R.drawable.round_extension` (same as the Settings row).
- New user-facing strings must eventually be translated into `ar/es/fr/zh-rCN` (maintainer i18n pass) to keep lint clean.

---

### Task 1: Pure bento-row logic (+ unit test)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/home/components/HomeActions.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/home/HomeActionsTest.kt`

**Interfaces:**
- Produces: `enum class HomeAction { REINSTALL, INSTALL, CLEAR_CACHE, EXTENSIONS }` and `fun homeActionRows(reinstallVisible: Boolean, isRoot: Boolean, hasPrivilege: Boolean): List<List<HomeAction>>` — a list of rows, each row 1–2 actions; empty rows dropped.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.valhalla.thor.presentation.home

import com.valhalla.thor.presentation.home.components.HomeAction.CLEAR_CACHE
import com.valhalla.thor.presentation.home.components.HomeAction.EXTENSIONS
import com.valhalla.thor.presentation.home.components.HomeAction.INSTALL
import com.valhalla.thor.presentation.home.components.HomeAction.REINSTALL
import com.valhalla.thor.presentation.home.components.homeActionRows
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActionsTest {
    @Test fun noPrivilege_onlyInstallFullWidth() {
        assertEquals(listOf(listOf(INSTALL)), homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = false))
    }

    @Test fun shizukuOrDhizuku_withReinstall_extensionsFullWidth() {
        assertEquals(
            listOf(listOf(REINSTALL, INSTALL), listOf(EXTENSIONS)),
            homeActionRows(reinstallVisible = true, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun shizukuOrDhizuku_noReinstall() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = false, hasPrivilege = true)
        )
    }

    @Test fun root_withReinstall_fullGrid() {
        assertEquals(
            listOf(listOf(REINSTALL, INSTALL), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = true, isRoot = true, hasPrivilege = true)
        )
    }

    @Test fun root_noReinstall() {
        assertEquals(
            listOf(listOf(INSTALL), listOf(CLEAR_CACHE, EXTENSIONS)),
            homeActionRows(reinstallVisible = false, isRoot = true, hasPrivilege = true)
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.home.HomeActionsTest"`
Expected: FAIL — unresolved reference `homeActionRows` / `HomeAction`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.valhalla.thor.presentation.home.components

/** The four Home action tiles, in bento order. */
enum class HomeAction { REINSTALL, INSTALL, CLEAR_CACHE, EXTENSIONS }

/**
 * Computes the Home bento rows from the current privilege/state flags.
 * Row 1 = [Reinstall?] + Install (Install is always present).
 * Row 2 = [Clear cache (root)?] + [Extensions (privilege)?].
 * A row that ends up with a single tile renders full-width; empty rows are dropped.
 */
fun homeActionRows(
    reinstallVisible: Boolean,
    isRoot: Boolean,
    hasPrivilege: Boolean,
): List<List<HomeAction>> {
    val row1 = listOfNotNull(
        HomeAction.REINSTALL.takeIf { reinstallVisible },
        HomeAction.INSTALL,
    )
    val row2 = listOfNotNull(
        HomeAction.CLEAR_CACHE.takeIf { isRoot },
        HomeAction.EXTENSIONS.takeIf { hasPrivilege },
    )
    return listOf(row1, row2).filter { it.isNotEmpty() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.home.HomeActionsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/home/components/HomeActions.kt \
        app/src/test/java/com/valhalla/thor/presentation/home/HomeActionsTest.kt
git commit -m "feat(home): pure bento-row logic for Home actions (+unit test)"
```

---

### Task 2: `BentoTile` composable

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/home/components/BentoTile.kt`

**Interfaces:**
- Produces: `@Composable fun BentoTile(title: String, subtitle: String, icon: Int, modifier: Modifier = Modifier, isPrimary: Boolean = false, isWarning: Boolean = false, onClose: (() -> Unit)? = null, onClick: () -> Unit)` — the caller supplies width via `modifier` (`Modifier.weight(1f).fillMaxHeight()` for a pair, `Modifier.fillMaxWidth()` for solo).

- [ ] **Step 1: Write the composable**

```kotlin
package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * Compact, size-adaptive action tile for the Home bento grid. Renders correctly both at
 * Modifier.weight(1f) (half-width, paired) and fillMaxWidth() (full-width, solo). Icon chip on
 * top, then title + subtitle. Uniform min height so paired tiles align. Mirrors the color logic
 * of the former ActionCard (isPrimary/isWarning/neutral).
 */
@Composable
fun BentoTile(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isWarning: Boolean = false,
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isPrimary -> MaterialTheme.colorScheme.primaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isPrimary -> MaterialTheme.colorScheme.onPrimaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 112.dp)
            .padding(18.dp)
    ) {
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_close),
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                    .padding(12.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isPrimary) MaterialTheme.colorScheme.primaryContainer else contentColor
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPrimary) contentColor.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL (no unresolved refs; all imports used).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/home/components/BentoTile.kt
git commit -m "feat(home): add size-adaptive BentoTile component"
```

---

### Task 3: `HomeActionsBento` composable

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/home/components/HomeActionsBento.kt`

**Interfaces:**
- Consumes: `homeActionRows(...)`, `HomeAction`, `BentoTile(...)`.
- Produces: `@Composable fun HomeActionsBento(reinstallVisible: Boolean, isRoot: Boolean, hasPrivilege: Boolean, unknownInstallerCount: Int, selectedTypeName: String, onReinstall: () -> Unit, onDismissReinstall: () -> Unit, onInstall: () -> Unit, onClearCache: () -> Unit, onNavigateToExtensionManager: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the composable**

```kotlin
package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * The Home actions bento grid. Renders the rows from [homeActionRows]: a two-tile row is a
 * weighted pair (equal height via IntrinsicSize.Min); a one-tile row spans full width.
 * Shared by both HomeScreen layout branches. animateContentSize smooths reflow when a tile
 * appears/disappears (privilege resolves, reinstall dismissed).
 */
@Composable
fun HomeActionsBento(
    reinstallVisible: Boolean,
    isRoot: Boolean,
    hasPrivilege: Boolean,
    unknownInstallerCount: Int,
    selectedTypeName: String,
    onReinstall: () -> Unit,
    onDismissReinstall: () -> Unit,
    onInstall: () -> Unit,
    onClearCache: () -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = homeActionRows(reinstallVisible, isRoot, hasPrivilege)

    @Composable
    fun Tile(action: HomeAction, tileModifier: Modifier) {
        when (action) {
            HomeAction.REINSTALL -> BentoTile(
                title = stringResource(R.string.reinstall_all),
                subtitle = stringResource(R.string.reinstall_all_subtitle, unknownInstallerCount, selectedTypeName),
                icon = R.drawable.apk_install,
                isWarning = true,
                onClose = onDismissReinstall,
                onClick = onReinstall,
                modifier = tileModifier,
            )
            HomeAction.INSTALL -> BentoTile(
                title = stringResource(R.string.install_from_file),
                subtitle = stringResource(R.string.install_from_file_subtitle),
                icon = R.drawable.apk_install,
                isPrimary = true,
                onClick = onInstall,
                modifier = tileModifier,
            )
            HomeAction.CLEAR_CACHE -> BentoTile(
                title = stringResource(R.string.clear_all_cache),
                subtitle = stringResource(R.string.clear_all_cache_subtitle),
                icon = R.drawable.clear_all,
                onClick = onClearCache,
                modifier = tileModifier,
            )
            HomeAction.EXTENSIONS -> BentoTile(
                title = stringResource(R.string.home_extensions_title),
                subtitle = stringResource(R.string.home_extensions_subtitle),
                icon = R.drawable.round_extension,
                onClick = onNavigateToExtensionManager,
                modifier = tileModifier,
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            if (row.size == 1) {
                Tile(row[0], Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { action -> Tile(action, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/home/components/HomeActionsBento.kt
git commit -m "feat(home): add HomeActionsBento adaptive grid"
```

---

### Task 4: Integrate the bento into HomeScreen + remove ActionCard + expanded cap

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/home/HomeScreen.kt`

**Interfaces:**
- Consumes: `HomeActionsBento(...)`.
- Produces: updated `HomeScreen(onNavigateToApps, onNavigateToFreezer, onReinstallAll, onClearAllCache, onNavigateToExtensionManager, viewModel, installerViewModel)` — new `onNavigateToExtensionManager: () -> Unit` param.

- [ ] **Step 1: Add the new callback param**

In the `HomeScreen(...)` signature (HomeScreen.kt:60-67), add after `onClearAllCache`:

```kotlin
    onClearAllCache: (AppListType) -> Unit,
    onNavigateToExtensionManager: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
```

- [ ] **Step 2: Add derived flags + expanded breakpoint**

Right after the existing `isWideScreen` line (HomeScreen.kt:86), add:

```kotlin
    val isExpanded = adaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val hasPrivilege = state.activePrivilegeMode != null
    val isRoot = state.activePrivilegeMode == PrivilegeMode.ROOT
    val reinstallVisible = state.activePrivilegeMode != null &&
        state.unknownInstallerCount > 0 && state.showReinstallCard
```

- [ ] **Step 3: Wrap content in a centered, expanded-capped container**

Replace the root `Column(...)` opener (HomeScreen.kt:92-97) with a `Box` that scrolls, holding a width-capped centered `Column`:

```kotlin
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .then(if (isExpanded) Modifier.widthIn(max = 1200.dp) else Modifier)
        ) {
```

Add the matching closing `}` for the new `Box` right after the existing `Spacer(Modifier.height(32.dp))` that ends the content Column (HomeScreen.kt:326-327). Add imports: `androidx.compose.foundation.layout.widthIn` (Alignment is already imported).

- [ ] **Step 4: Replace the PHONE-branch action cards with the bento**

Delete the three action blocks in the `else` (phone) branch — the reinstall `AnimatedVisibility` (HomeScreen.kt:237-254), the install `ActionCard` (256-265), and the clear-cache `AnimatedVisibility` (267-278) — and replace with:

```kotlin
            HomeActionsBento(
                reinstallVisible = reinstallVisible,
                isRoot = isRoot,
                hasPrivilege = hasPrivilege,
                unknownInstallerCount = state.unknownInstallerCount,
                selectedTypeName = state.selectedType.name.lowercase(),
                onReinstall = onReinstallAll,
                onDismissReinstall = { viewModel.dismissReinstallCard() },
                onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                onClearCache = { showCacheDialog = true },
                onNavigateToExtensionManager = onNavigateToExtensionManager,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
```

- [ ] **Step 5: Replace the WIDE-branch action cards with the bento**

In the wide branch left column, delete the reinstall `AnimatedVisibility` (HomeScreen.kt:137-153), the install `ActionCard` (155-163), and the clear-cache `AnimatedVisibility` (165-175), and replace with the same call but no horizontal padding (the parent Row already pads 24dp):

```kotlin
                    HomeActionsBento(
                        reinstallVisible = reinstallVisible,
                        isRoot = isRoot,
                        hasPrivilege = hasPrivilege,
                        unknownInstallerCount = state.unknownInstallerCount,
                        selectedTypeName = state.selectedType.name.lowercase(),
                        onReinstall = onReinstallAll,
                        onDismissReinstall = { viewModel.dismissReinstallCard() },
                        onInstall = { filePickerLauncher.launch(arrayOf("*/*")) },
                        onClearCache = { showCacheDialog = true },
                        onNavigateToExtensionManager = onNavigateToExtensionManager,
                    )
```

Add the import: `com.valhalla.thor.presentation.home.components.HomeActionsBento`.

- [ ] **Step 6: Delete the now-orphaned `ActionCard`**

Delete the entire `private fun ActionCard(...)` composable (HomeScreen.kt:409-504) and any now-unused imports it alone used (check: `Brush`, `Color`, `IconButton` — remove only if no other usage remains in the file after Steps 4–5).

- [ ] **Step 7: Verify it compiles**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL (no unresolved refs, no unused-import errors).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/home/HomeScreen.kt
git commit -m "feat(home): render actions as adaptive bento; add Extension Manager tile; drop ActionCard"
```

---

### Task 5: Wire navigation in MainScreen + add strings

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt:319-337, 458-483`
- Modify: `app/src/main/res/values/strings.xml:69`

**Interfaces:**
- Consumes: `HomeScreen(..., onNavigateToExtensionManager)`, `ThorRoute.ExtensionManager`, `currentBackStack` (MainScreen.kt:138).

- [ ] **Step 1: Pass the Home → Extension Manager callback**

In the `entry<ThorRoute.Home> { HomeScreen(...) }` block (MainScreen.kt:319-337), add after `onClearAllCache = ...` (line 335):

```kotlin
                        onClearAllCache = { type -> mainViewModel.clearAllCache(type) },
                        onNavigateToExtensionManager = {
                            homeBackStack.add(ThorRoute.ExtensionManager)
                        }
```

- [ ] **Step 2: Make the shared ExtensionManager entry pop/push the ACTIVE stack**

In `entry<ThorRoute.ExtensionManager>` (MainScreen.kt:461-470), replace both `settingsBackStack` references with `currentBackStack` so back-nav works whether it was opened from Home or Settings:

```kotlin
                    ExtensionManagerScreen(
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        },
                        onBrowse = {
                            currentBackStack.add(ThorRoute.ExtensionBrowse)
                        }
                    )
```

- [ ] **Step 3: Same fix for the ExtensionBrowse entry**

In `entry<ThorRoute.ExtensionBrowse>` (MainScreen.kt:476-482), replace `settingsBackStack` with `currentBackStack`:

```kotlin
                    ExtensionBrowseScreen(
                        onBack = {
                            if (currentBackStack.size > 1) {
                                currentBackStack.removeLastOrNull()
                            }
                        }
                    )
```

- [ ] **Step 4: Add the two strings**

In `app/src/main/res/values/strings.xml`, after `install_from_file_subtitle` (line 69), add:

```xml
    <string name="home_extensions_title">Extensions</string>
    <string name="home_extensions_subtitle">Manage &amp; open</string>
```

> i18n note: these two keys will show as `MissingTranslation` in `ar/es/fr/zh-rCN` until translated — add them in the locale `strings.xml` files (maintainer i18n pass) before release to keep lint clean. English text: "Extensions" / "Manage & open".

- [ ] **Step 5: Verify both flavors compile**

Run: `./gradlew :app:compileFossDebugKotlin :app:compileStoreDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/main/MainScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(home): open Extension Manager from Home; active-stack back handling; strings"
```

---

### Task 6: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Unit tests**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.home.HomeActionsTest"`
Expected: PASS (5).

- [ ] **Step 2: Build both flavors**

Run: `./gradlew assembleFossDebug assembleStoreDebug`
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 3: Lint (expect only the 2 new-string translations pending)**

Run: `./gradlew :app:lintFossDebug`
Expected: the only *new* findings are `MissingTranslation` for `home_extensions_title` / `home_extensions_subtitle` (until the maintainer translates them). No new code warnings/errors.

- [ ] **Step 4: Manual device/emulator matrix**

Verify against the spec's visibility matrix and adaptivity:
- COMPACT phone (portrait + landscape): bento renders; privilege states NONE / Shizuku / Dhizuku / ROOT × (with/without unknown installers) match the matrix; reinstall dismiss collapses row 1 to full-width Install.
- MEDIUM/EXPANDED tablet: two-pane; bento in the left pane; EXPANDED content is centered/capped (no edge-to-edge sprawl).
- Resize a freeform/ChromeOS/DeX window and fold/unfold a foldable → layout reflows live.
- Tap **Extensions** on Home → opens Extension Manager (consent sheet on first run); **back** returns to Home (not Settings). Settings → Extension Manager still works and its back returns to Settings.

- [ ] **Step 5: Commit any fixups** (only if the manual pass required changes)

```bash
git add -A && git commit -m "fix(home): address bento verification findings"
```

---

## Self-Review

- **Spec coverage:** visibility gate (T1/T3/T4), bento layout + pairing/fallback (T1/T3), adaptive BentoTile (T2), width classes + EXPANDED cap (T4), nav reuse + active-stack back fix (T5), Settings entry retained (unchanged), strings + i18n note (T5), testing matrix (T6). All covered.
- **Placeholders:** none — every code step has complete code; the only "later" item is the maintainer i18n pass, explicitly flagged with the exact keys/text.
- **Type consistency:** `homeActionRows(reinstallVisible, isRoot, hasPrivilege)` and `HomeAction` names are identical across T1/T3; `HomeActionsBento(...)` param list identical in T3 (definition) and T4 (both call sites); `onNavigateToExtensionManager` name consistent T4/T5; icon `R.drawable.round_extension` consistent.

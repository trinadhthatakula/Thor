# Home Bento Actions + Extension Manager Entry — Design

**Date:** 2026-07-21
**Branch:** `feat/home-bento-extension-manager`
**Status:** Design — approved in brainstorming, pending spec review

## Problem / Goal

Reaching an extension today (e.g. Stormbringer) takes four steps: **Settings → scroll → Extension Manager → open extension**. We want a first-class **Extension Manager** entry on the **Home** screen. While adding it, restructure Home's stacked action cards into an **adaptive 2×2 "bento" grid** that stays elegant across phones, tablets, foldables, and resizable desktop/ChromeOS/browser windows.

## Decisions (from brainstorming)

1. **Visibility of the Extension Manager tile:** show iff `state.activePrivilegeMode != null` (a privilege is *actively resolved* — Root/Shizuku/Dhizuku). This is the exact predicate Home already uses to gate the reinstall card.
2. **Layout:** an adaptive **2×2 bento** replacing the vertical `ActionCard` stack:
   - **Row 1** = `[Reinstall All?] · [Install from file]`
   - **Row 2** = `[Clear all cache (ROOT)?] · [Extension Manager (≠NONE)?]`
   - When a row's *conditional* (left) tile is hidden, the surviving tile **spans the full row**.
3. **Tile style:** a new compact, size-adaptive **`BentoTile`** (icon → title → one-line subtitle, stacked; uniform height). It **replaces** the existing `ActionCard`, which becomes orphaned and is deleted.
4. **Adaptive behavior:** drive layout off the three width classes (`COMPACT / MEDIUM / EXPANDED`), weight-based tiles, reactive to window/fold changes. On **EXPANDED**, keep the 2×2 and **cap the Home content to a centered max-width (~1100–1200dp)** so tiles/text don't sprawl.
5. **Retained defaults:** the Settings → Extension Manager entry **stays** (Home is an added shortcut). **Install from file stays the visually primary tile.** Reinstall keeps its warning tint + dismiss ✕. Paired tiles share a **uniform height**.

## Architecture / Components

All UI-layer; **no ViewModel/data changes** (every value already lives in `HomeUiState`).

### `BentoTile` (new)
Compact, width-agnostic card. Suggested signature:
```
@Composable private fun BentoTile(
    title: String, subtitle: String, icon: Int,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false, isWarning: Boolean = false,
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
)
```
- Vertical layout: icon chip (top) → title → subtitle. `maxLines` + ellipsis on both texts so nothing breaks at half-width.
- Uniform `minHeight` (~96dp) so paired tiles align; renders correctly at both `Modifier.weight(1f)` (half) and `fillMaxWidth()` (full).
- Carries over the existing color logic (`isPrimary` → primaryContainer, `isWarning` → tertiary tint, else surfaceContainerHigh) and the optional close button.

### `HomeActionsBento` (new)
Encapsulates the grid so it's used by **both** layout branches (removing today's duplicated `ActionCard` blocks). Takes the relevant state (`activePrivilegeMode`, `unknownInstallerCount`, `showReinstallCard`, `selectedType`) + callbacks (`onReinstallAll`, `onDismissReinstall`, `onInstallFromFile`, `onClearCache`, `onNavigateToExtensionManager`) + a `modifier`.
- Builds the two rows with `listOfNotNull`:
  - `row1 = listOfNotNull(reinstallTile.takeIf { reinstallVisible }, installTile)`
  - `row2 = listOfNotNull(clearCacheTile.takeIf { isRoot }, extensionTile.takeIf { hasPrivilege })`
- A `BentoRow(tiles)` helper: **2 tiles** → `Row(height = IntrinsicSize.Min)` with each `weight(1f)`, 12dp gap; **1 tile** → full width; **0** → render nothing.
- `Modifier.animateContentSize()` on the container so dismiss / privilege-resolution reflows smoothly.

### Page layout (width-class driven)
- **COMPACT** (phone): single column — `DashboardHeader`, `SummaryStatRow`, `HomeActionsBento` (full width, 24dp h-padding), distribution chart, support.
- **MEDIUM / EXPANDED** (tablet/foldable/split window): two-pane `Row` — **left** = `SummaryStatRow` + `HomeActionsBento`; **right** = distribution + support (today's structure).
- **EXPANDED**: wrap the whole content in a **centered, max-width-capped** container (~1100–1200dp).
- Width classes via `currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND / WIDTH_DP_EXPANDED_LOWER_BOUND)` (already a dependency). Recomputes on window/config/fold changes → live reflow on ChromeOS/DeX/browser resize and foldable posture changes.

### Navigation
Add `onNavigateToExtensionManager: () -> Unit` to `HomeScreen`. In `MainScreen`'s `entry<ThorRoute.Home>`, wire `onNavigateToExtensionManager = { homeBackStack.add(ThorRoute.ExtensionManager) }` — reuses the **shared** `ExtensionManager` destination (no new route/entry). The in-screen consent sheet (`extensionConsentAccepted`) still fires on first open regardless of entry point.

## Visibility matrix

| Privilege | unknown installers >0 & not dismissed | Row 1 | Row 2 |
|---|---|---|---|
| NONE | — | `[Install (full)]` | *(none)* |
| Shizuku/Dhizuku | yes | `[Reinstall][Install]` | `[Extensions (full)]` |
| Shizuku/Dhizuku | no | `[Install (full)]` | `[Extensions (full)]` |
| ROOT | yes | `[Reinstall][Install]` | `[ClearCache][Extensions]` |
| ROOT | no | `[Install (full)]` | `[ClearCache][Extensions]` |

## Strings
- `home_extensions_title` = "Extensions"
- `home_extensions_subtitle` = "Manage & open"
- Icon: reuse the extension icon used by the Settings "Extension Manager" row / `ExtensionManagerScreen`.

## Edge cases
- **Privilege resolves late** (`isPrivilegeReady` flips): tiles appear when `activePrivilegeMode` resolves; `animateContentSize` smooths the reflow.
- **Reinstall dismissed:** Row 1 collapses `[Reinstall][Install]` → `[Install (full)]`.
- **First-ever open:** consent sheet appears inside `ExtensionManagerScreen` (unchanged behavior).
- **Very narrow left pane** (medium two-pane): tiles are weight-based, so they shrink gracefully; texts ellipsize.

## Out of scope (YAGNI)
- Extension count / badge on the tile (would need new IPC/repo wiring into `HomeViewModel`).
- Opening a *specific* extension directly from Home (the tile opens the manager list).
- 3+ column bento on expanded (we cap width instead).

## Testing / verification
- Build `assembleFossDebug` + `assembleStoreDebug` green.
- Manual device/emulator matrix:
  - **Compact** phone portrait + landscape.
  - **Medium/Expanded** tablet; **resize** a freeform/ChromeOS/DeX window and confirm live reflow; **foldable** fold/unfold.
  - Each privilege state (NONE / Shizuku / Dhizuku / ROOT) × (with / without unknown installers) matches the visibility matrix.
  - Tap **Extensions** → opens Extension Manager (consent sheet on first run); Settings entry still works.

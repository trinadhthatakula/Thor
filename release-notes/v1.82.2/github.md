# Thor v1.82.2 Release Notes

This major production release consolidates all changes from the **v1.82.x** cycle (v1.82.0, v1.82.1, and v1.82.2). It delivers a completely overhauled Freezer interface, a safer system app debloating/disabling strategy with Universal Android Debloater (UAD) integration, safety gating for system app freezing, a redesigned Portable Installer UI with split APK MIME associations, adaptive layout support, multi-language localization, and critical crash fixes.

## What's Changed

### ❄️ Freezer UI Overhaul & Safety Gating
*   **Horizontal Floating Toolbar**: Replaced the legacy Floating Action Button and top "Freeze All" option with a modern bottom horizontal floating toolbar containing **Freeze**, **Add**, and **Unfreeze** actions.
*   **App Filter Button Group**: Integrated a button group filter at the top of the Freezer and App List screens to instantly toggle between User and System apps.
*   **Unsafe Freeze Prevention**: Blocks freezing system packages classified as **Unsafe** by Universal Android Debloater (UAD) or if safety metadata fails to load.
*   **Expert Freeze Warning**: Displays a confirmation warning dialog before freezing system apps classified as **Expert**.
*   **Batch & Auto-Freeze Filtering**: Excludes unsafe system packages from batch freeze and auto-freeze processes (including `AutoFreezeManager`).

### 🛡️ Safer System App Disabling & UAD Integration
*   **User-Level Uninstallation**: Disabling/freezing system apps now uninstalls them for the current user (`pm uninstall --user current`) instead of using standard package disabling, making it fully compatible with modern Android versions across all privilege engines (Root, Shizuku, and Dhizuku).
*   **User-Level Restoration**: Enabling/unfreezing uninstalled system apps reinstalls them from the system partition (`pm install-existing`).
*   **Multi-User & Work Profile Support**: Dynamically retrieves the active user ID using `am get-current-user` to run commands under the correct active profile.
*   **UAD Safety Chips**: Shows recommendation badges (Recommended, Advanced, Expert, Unsafe) in headers, app details, and uninstall dialogs.
*   **Unsafe Package Blocker**: Uninstallation of system packages marked as **Unsafe** is strictly blocked (warning screen replaces confirmation with a close button) to prevent bootloops.
*   **Expert Package Warning**: Shows warning confirmation dialogs for **Expert** packages before allowing uninstallation.
*   **Local App Icon Caching**: Caches system app icons locally before uninstallation to keep them visible in list, grid, and details views.
*   **Danger Badge Indicators**: Displays a red danger icon badge over uninstalled system apps in lists and grids.

### 📦 Portable Installer Redesign & MIME Associations
*   **Redesigned UI States**: Overhauled the installation interface with clear, user-friendly states: **Installing** (pulsing icon, progress percentage), **UserConfirmationRequired** (loader & action prompt), **Success** (Done/Open actions), and **Error** (detailed error card).
*   **Metadata Caching**: Preserves application labels and icons across all installer state transitions.
*   **MIME Associations**: Associated `.apkm`, `.apks`, and `.xapk` (and wildcards) formats with the Thor installer.
*   **Polished Banners**: Improved readability of update/downgrade warning banners and badges.

### 📐 Adaptive Layouts & Navigation Polish
*   **Navigation Rail**: Implemented a vertical side navigation rail (`ThorNavigationRail`) for tablets and foldables.
*   **Independent Multi-Backstack**: Added independent backstack navigation for each top-level tab (Home, Apps, Freezer, Settings) with correct state preservation and custom back press handling.
*   **Viewport Optimization**: Hides navigation bars when navigating to detailed sub-screens and implements a split-details layout strictly for landscape phones.
*   **Backstack Safety**: Guarded all backstack pop operations with `size > 1` checks to prevent `NavDisplay` empty backstack crashes.
*   **Import Fix**: Updated Freezer's disabled apps import feature to completely skip all system apps.

### 🌐 UI Persistence & Localization
*   **Grid/List Layout Persistence**: Persists user layout mode preferences for App List and Freezer screens across app launches.
*   **Localization Support**: Added full localization support for new strings in Arabic, Spanish, French, and Chinese.

### 🐛 Bug Fixes & Stability Improvements
*   **Privileged Gateway Error Handling**: Prevented crashes when no privilege mode (Root/Shizuku/Dhizuku) is active by returning `Result` instead of throwing `IllegalStateException`.
*   **Button Group Crash Fix**: Resolved layout constraint `IllegalArgumentException` by removing `animateWidth` modifier from `ConnectedButtonGroup`'s toggle buttons.
*   **Explicit Broadcasts**: Made the Shizuku uninstall result broadcast intent explicit for improved security.
*   **Performance Improvements**: Optimized process stream handling and bypassed redundant cache operations.
*   **CI Upgrades**: Upgraded Ruby to 3.3 and migrated fastlane to Bundler.

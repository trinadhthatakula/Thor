# Thor v1.82.1 Release Notes

This release delivers safety gating for system app freezing, an overhauled Portable Installer UI with split APK MIME associations, adaptive layout support for tablets/foldables, multi-backstack navigation polish, and grid/list view mode preference persistence.

## What's Changed

### 🛡️ System App Freezing Safety Gating
*   **Unsafe Freeze Prevention**: Blocks freezing system packages classified as **Unsafe** by Universal Android Debloater (UAD) or if safety metadata fails to load.
*   **Expert Freeze Warning**: Displays a warning confirmation dialog before freezing system apps classified as **Expert**.
*   **Batch & Auto-Freeze Filtering**: Excludes unsafe system packages from batch freeze and auto-freeze processes (including `AutoFreezeManager`).
*   **Multi-language Warnings**: Translated safety warnings for English, Arabic, Spanish, French, and Chinese.

### 📦 Portable Installer overhaul & MIME associations
*   **Overhauled UI States**: Redesigned screens for **Installing** (pulsing icon, progress percentage), **UserConfirmationRequired** (tertiary alert icon, loader), **Success** (checkmark, Done/Open actions), and **Error** (danger badge, detailed error card).
*   **State Caching**: Preserves application metadata (labels and icons) across install state transitions.
*   **MIME Registration**: Associated `.apkm`, `.apks`, and `.xapk` (`vnd.apkm`, `vnd.apks`, `vnd.xapk`) package formats and wildcards with the Thor installer.
*   **Polish**: Improved readability of downgrade/update warning banners and high-contrast chips.

### 📐 Adaptive Layouts & Navigation Polish
*   **Multi-Backstack Navigation**: Added independent multi-backstack navigation for each top-level tab (Home, Apps, Freezer, Settings) with correct state preservation and custom back press handling.
*   **Navigation Rail**: Implemented a vertically-oriented custom side navigation rail (`ThorNavigationRail`) for tablets and large screen viewports.
*   **Viewport Optimization**: Hides navigation bars when navigating to detailed sub-screens and implements a split-details layout strictly for landscape phones.
*   **Import Fix**: Updated Freezer's disabled apps import feature to completely skip all system apps.

### 🎨 Grid/List Layout Persistence
*   **Persistent Preferences**: Remembers the user's grid/list layout view mode preference for both the **App List** and **Freezer** screens across app launches.

### 🐛 Build & Tooling
*   **CI Upgrades**: Upgraded Ruby to 3.3 and migrated fastlane to Bundler.

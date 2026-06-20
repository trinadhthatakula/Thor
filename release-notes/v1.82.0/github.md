# Thor v1.82.0 Release Notes

This major release delivers a completely overhauled Freezer interface, a safer system app debloating/disabling strategy for all privilege modes (Root, Shizuku, and Dhizuku), icon preservation for uninstalled system packages, and integration of the Universal Android Debloater (UAD) safety recommendations.

## What's Changed

### ❄️ Freezer UI Overhaul
*   **Horizontal Floating Toolbar**: Replaced the old Floating Action Button and top "Freeze All" button with a modern bottom horizontal floating toolbar containing **Freeze**, **Add**, and **Unfreeze** actions.
*   **App Filter Button Group**: Integrated a button group filter at the top of the Freezer and App List screens to instantly toggle between User and System apps.

### 🛡️ Safer System App Disabling (Root, Shizuku & Dhizuku)
*   **User-Level Uninstallation**: Disabling/freezing system apps now uninstalls them for the current user (`pm uninstall --user current`) instead of using standard disabling, making it fully compatible with modern Android versions.
*   **User-Level Restoration**: Enabling/unfreezing uninstalled system apps reinstalls them from the system partition (`pm install-existing`).
*   **Multi-User & Work Profile Support**: Dynamically retrieves the active user ID using `am get-current-user` to run commands under the correct user.
*   **Auto-Freezer Addition**: System apps uninstalled via Thor are automatically added to the Freezer database to be tracked as frozen.

### 🎨 UAD Safety Guidance & Enforcement
*   **UAD Safety Chips**: Shows recommendation badges (Recommended, Advanced, Expert, Unsafe) in headers, app details, and uninstall dialogs.
*   **Unsafe Package Blocker**: Uninstallation of system packages marked as **Unsafe** is strictly blocked (warning screen replaces confirmation with a close button) to prevent bootloops.
*   **Expert Package Warning**: Shows warning confirmation dialogs for **Expert** packages before allowing uninstallation.
*   **Danger Badge Indicators**: Displays a red danger icon badge over uninstalled system apps in lists and grids.
*   **Clean Details View**: Removed lengthy UAD description texts to keep the user interface clean and minimal.

### 📁 Icon Caching & UI Fixes
*   **Saved System Icons**: Locally caches icons of system apps before uninstallation to keep them visible in list, grid, and details views.
*   **AsyncImage Migration**: Migrated the bottom sheet dialog to Coil `AsyncImage` and `AppIconModel` to ensure uninstalled system app icons render correctly.

### 🐛 Bug Fixes & Refinements
*   **Explicit Broadcasts**: Made the Shizuku uninstall result broadcast intent explicit for improved security.
*   **Performance Improvements**: Bypassed redundant cache operations and optimized process stream handling.

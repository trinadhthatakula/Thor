# Thor v1.81.3 Release Notes

This release brings visual polish with Shared Element Transitions and Material Expressive Motion, optimizes memory scoping by upgrading to Lifecycle 2.11+, and includes dependency updates.

## What's Changed

### 🚀 Features & UX Enhancements
*   **Shared Element Transitions**: Added smooth Jetpack Compose shared element transitions for app icons (shared elements) and names (shared bounds) when navigating from lists to details and permission screens.
*   **Material Expressive Motion**: Migrated legacy animations across counters, distribution charts, snackbars, and navigation transitions to use physics-based tokens from the theme's `MaterialTheme.motionScheme`.
*   **Freezer Load UX**: Fixed privilege loading delay so the Floating Action Button (FAB) appears immediately on the Freezer screen.

### 🛠️ Architecture & Lifecycle Scoping
*   **Lifecycle 2.11+ Upgrade**: Upgraded the Android Jetpack Lifecycle library to `2.11.0-rc01`.
*   **Destination-Scoped ViewModels**: Integrated the new `androidx.lifecycle:lifecycle-viewmodel-navigation3` package. Sub-route ViewModels (`AppInfoDetailsViewModel` and `PermissionManagerViewModel`) are now scoped to their navigation back stack entries and automatically cleared when popped.
*   **Stable Tab Scoping**: Retained core tab ViewModels at the parent level to prevent unnecessary disposal and reload cycles when switching tabs.

### ⚙️ Build & Dependencies
*   **Compiler Warning Cleanup**: Removed the redundant `-Xexplicit-backing-fields` Kotlin compiler argument.
*   **Dependencies**: Bumped various Gradle plugins and dependency coordinates in the version catalog.

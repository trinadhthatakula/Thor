# Thor v1.81.2 Release Notes

This release brings stability fixes, compatibility improvements for future Android versions, and UI enhancements.

## What's Changed

### 🚀 Features & Compatibility
*   **Android 16.1+ Compatibility**: Implemented a dynamic shell-first execution fallback flow to ensure seamless operations on Android 16.1+.
*   **Modern APIs**: Migrated from the deprecated `LocalClipboardManager` to the modern `LocalClipboard`.

### 🐛 Bug Fixes
*   **App List Search**: Fixed the cursor jumping issue in the search bar by managing the local query state synchronously.
*   **UI Color Fix**: Resolved contrast issues on `StatusChips` in `AppInfoDetailsScreen` by setting explicit text colors.
*   **API Warnings**: Resolved deprecated permission API warnings in repository classes.

### ⚙️ Build & Dependencies
*   **Kotlin Downgrade**: Reverted Kotlin to `2.3.21` to fix a compiler crash (`ClassCastException`) caused by Koin compiler plugin incompatibility.
*   **Dependencies**: Bumped various Maven group dependencies to their latest stable versions.

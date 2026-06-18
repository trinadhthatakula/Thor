# Thor v1.81.6 Release Notes

This release introduces major enhancements to app management, sharing, and project support. It features advanced Freezer filtering, direct APK/APKs sharing, centralized cache clearing options for root users, flavor-separated support developer channels, a redesigned dashboard community section, and critical bug fixes.

## What's Changed

### 💖 Support Developer & Community
*   **Support Developer Bottom Sheet**: Added a modal bottom sheet to easily support the developer, triggered automatically exactly once after successfully completing any action (freeze, unfreeze, reinstall, etc.), or manually from the Settings screen.
*   **Support & Community Dashboard Card**: Replaced the low-prominence social links row with a beautifully styled "Support & Community" card below the App Distribution Chart, featuring a primary button to support the developer and redirect actions to open the Play Store listing (enabling easy rating), the GitHub repository, and the Telegram channel.
*   **Improved Action Flow**: Defer showing the automatic Support Developer prompt until the `TermLoggerDialog` is dismissed, ensuring a seamless user experience.
*   **Google Play Billing 9.0.0**: Integrated native subscriptions ($5, $10, $25, $50) for the Google Play Store variant.
*   **Patreon & PayPal Fallback**: FOSS build remains completely free of proprietary Billing SDKs. It links directly to Patreon and PayPal. The Store variant also falls back to these links plus Rate App/Play Store dev page if billing is offline/unavailable.
*   **Isolated Permissions**: `INTERNET` and `com.android.vending.BILLING` permissions are strictly isolated to the Google Play Store build manifest.

### 🚀 Features & UX Enhancements
*   **User/System App Filter**: Added a User/System app toggle inside the Freezer Settings sheet using the custom `ConnectedButtonGroup` component to easily partition apps.
*   **Automatic Disabled Apps Import**: Automatically detects disabled apps outside the Freezer and prompts the user on first launch to import them. The prompt state is robustly marked as shown only when the user explicitly interacts (confirms, cancels, or dismisses) with the dialog.
*   **Settings Auto-Hide**: The "Import disabled apps" button in the Freezer settings sheet automatically hides if there are no disabled apps outside the freezer left to import.
*   **App Filtering & Selection Improvements**: Multi-select actions (like Select All and selection counts) on the Freezer screen now operate exclusively on the currently visible filtered apps.
*   **Direct APK & APKS Sharing**: Upgraded sharing behavior to share the actual base APK (or a packaged `.apks` zip with split APKs and standard `manifest.json` metadata) instead of a simple market link.
*   **Batch Sharing**: Added a Share option to the multi-select toolbar to share multiple app packages at once.

### 🧹 Cache Management (Root Users)
*   **Clear Cache Dashboard Option**: Added a clear cache option on the dashboard for root users, supporting custom selection between user and system app cache clearing with confirmation safeguards.

### 🛠️ Bug Fixes & Under-The-Hood
*   **App List Filtering**: Fixed the AppList screen filters where choosing an activity state (like frozen) could result in an empty list due to stale raw cache states.
*   **Dependencies**: Centralized build versioning (`compileSdk`, `targetSdk`, and `minSdk`) in `libs.versions.toml` and upgraded Maven dependency packages.

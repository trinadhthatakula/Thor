# Thor v1.81.9 Release Notes

This release introduces new user personalization settings to control screen redirection behavior, navigation transitions, as well as critical improvements to app installation and privilege gateway robustness. It features a new Detailed View setting, a Details redirect option in the app info dialog, a 3-level Animation Intensity configuration, batch reinstall support for Shizuku/Dhizuku, and complete security and stability refinements across Root, Shizuku, and Dhizuku gateways.

## What's Changed

### 🚀 Features & UX Enhancements
*   **Detailed View Settings**: Added a new Detailed View toggle under the general settings. When enabled, clicking an app on the App List screen navigates directly to the dedicated Details screen instead of opening the dialog.
*   **App Info Dialog Redirection**: Added a new "Details" action button to the bottom sheet `AppInfoDialog` (accessed via either the App List or Freezer screen) to quickly jump to the full details view.
*   **Shizuku & Dhizuku Batch Reinstall**: Enabled the batch reinstall ("Fix Store") action in the multi-select toolbar for Shizuku and Dhizuku privilege modes.
*   **Segmented Controls in Settings**: Integrated the new options directly into the settings layout using the custom `ConnectedButtonGroup` component.

### 🎬 Performance & Animations
*   **Animation Intensity Control**: Added a 3-level animation intensity preference under appearance settings (Low, Medium, High).
    *   **Low**: Disables standard sliding/fading navigation transitions (uses instant snap navigation) and shared transitions. Bypasses expensive Compose dual-pass lookahead layouts for optimal performance and battery saving.
    *   **Medium**: Retains standard screen navigation animations (fades/slides) but disables shared transitions to reduce layout overhead on mid-range devices.
    *   **High**: Enables all animations and shared element transitions for a premium, expressive motion feel.

### 🔒 Gateway & Security Refinements (Root, Shizuku, Dhizuku)
*   **Thread Safety**: Wrapped all privileged operations in `withContext(Dispatchers.IO)` to prevent main thread blocking and ANR issues.
*   **Process Buffer Deadlock Protection**: Reworked shell command executors in Shizuku and Dhizuku to drain process streams concurrently in background threads, avoiding pipe deadlocks.
*   **Input Sanitization**: Implemented shell escaping using `ShellUtils.escapedString` and validated package names and user IDs via strict regex checks to prevent command injection.
*   **Custom Logger Integration**: Switched print statements and stack traces to use the custom `com.valhalla.thor.util.Logger`.

### 🌐 Localization
*   **Full Multi-Language Localization**: Localized all new settings titles, descriptions, action labels, and suspended app dialog strings in English, Arabic, Spanish, French, and Chinese (Simplified).

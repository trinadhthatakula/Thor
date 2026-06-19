# Thor v1.81.9 Release Notes

This release introduces new user personalization settings to control screen redirection behavior and navigation transitions. It features a new Detailed View setting, a Details redirect option in the app info dialog, and a 3-level Animation Intensity configuration to optimize performance and feel on all devices.

## What's Changed

### 🚀 Features & UX Enhancements
*   **Detailed View Settings**: Added a new Detailed View toggle under the general settings. When enabled, clicking an app on the App List screen navigates directly to the dedicated Details screen instead of opening the dialog.
*   **App Info Dialog Redirection**: Added a new "Details" action button to the bottom sheet `AppInfoDialog` (accessed via either the App List or Freezer screen) to quickly jump to the full details view.
*   **Segmented Controls in Settings**: Integrated the new options directly into the settings layout using the custom `ConnectedButtonGroup` component.

### 🎬 Performance & Animations
*   **Animation Intensity Control**: Added a 3-level animation intensity preference under appearance settings (Low, Medium, High).
    *   **Low**: Disables standard sliding/fading navigation transitions (uses instant snap navigation) and shared transitions. Bypasses expensive Compose dual-pass lookahead layouts for optimal performance and battery saving.
    *   **Medium**: Retains standard screen navigation animations (fades/slides) but disables shared transitions to reduce layout overhead on mid-range devices.
    *   **High**: Enables all animations and shared element transitions for a premium, expressive motion feel.

### 🌐 Localization
*   **Full Multi-Language Localization**: Localized all new settings titles, descriptions, and action labels in English, Arabic, Spanish, French, and Chinese (Simplified).

# Thor v1.90.4 Release Notes

**Add Freezer to launcher (#210)** — launch your frozen apps straight from the home screen, and keep Freeze-all / Unfreeze-all one tap away.

## What's Changed

### 🧊 Freezer shortcuts (#210)
A new opt-in **Settings → Freezer → "Add Freezer to launcher"** unlocks launcher shortcuts for your apps:

*   **Per-app home-screen shortcuts.** Pin any user app as a shortcut. Tapping it **enables the app in the background and launches it** (or launches it directly if it's already enabled) — so a frozen app is one tap away, and re-freezes on screen-off if Auto-Freeze is on.
*   **State-following icons.** A shortcut's icon is **grayscale while the app is frozen** and **full-colour while it's enabled**, updating as the app's state changes (launching, screen-off auto-freeze, manual freeze/unfreeze).
*   **Freeze all / Unfreeze all shortcuts.** Pin them to the home screen **and** reach them from a long-press on Thor's icon.
*   **Pin from anywhere.** An **"Add to Home screen"** action in the app-info dialog (from any screen) and on the app-details page, plus a dedicated **"Shortcuts"** section in Freezer settings.
*   **Pin confirmation.** A toast confirms when a shortcut is actually added to the home screen.

### ❄️ Freeze → "Add to Freezer?" prompt
*   Freezing an app from the **app-info dialog** or the **app-details page** now shows the same **"Frozen — Add to Freezer?"** prompt used elsewhere, instead of silently just-disabling (dialog) or auto-adding (details). You decide whether it joins the Freezer.

### 🛠 Polish
*   Launcher shortcuts open cleanly even when Thor is already in the recents stack — the launch trampoline now runs in its own task instead of surfacing Thor.
*   Action rows in the app-info dialog and app-details page are top-aligned, so labels that wrap to two lines no longer knock the icons out of alignment.

### ✅ Issues Resolved
*   **#210** — show frozen apps in the launcher and launch them from the home screen.

## 📦 Build
*   Version bumped to **1.90.4 (1904)**.

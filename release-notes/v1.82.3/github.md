# Thor v1.82.3 Release Notes

This minor patch release resolves a backward-compatibility regression where system apps frozen/disabled by older versions of Thor could not be unfrozen.

## What's Changed

### 🐛 Bug Fixes & Stability Improvements
*   **System App Unfreezing Compat**: Fixes an issue where system apps disabled by older versions of Thor remained disabled when using the new unfreeze method (`pm install-existing`). The unfreezing logic now checks if the system app is in a disabled state (installed but not enabled) and enables it first (`pm enable` / `setAppEnabled`) before running the reinstall/unfreeze action. This fix is implemented across all privilege engines (Root, Shizuku, and Dhizuku).

⚡️ **Thor v1.82.0 is out!**

This major update introduces a beautiful Freezer UI overhaul, safer system app debloating, and Universal Android Debloater safety guidance.

**What's New:**
• **Freezer UI Overhaul**: New bottom horizontal floating toolbar for quick Freeze, Add, and Unfreeze actions, with a button group filter for User/System apps.
• **Modern System App Disabling**: Both disable/enable actions now perform user-level uninstall/restore (`pm uninstall` and `pm install-existing`) to support latest Android versions under Root, Shizuku, and Dhizuku.
• **UAD Safety Integration**: Display safety recommendations (Recommended, Advanced, Expert, Unsafe) inside the app details and uninstall dialogs.
• **Uninstall Protection**: Uninstallation of system packages classified as **Unsafe** is strictly blocked to prevent bootloops.
• **Danger Badges**: Uninstalled system apps now display a red danger status badge in lists/grids.
• **Saved App Icons**: Icons of uninstalled system apps are cached locally so they continue displaying in the app.
• **Auto-Freezer Tracking**: System apps uninstalled via Thor are automatically tracked in the Freezer database.
• **Bug Fixes**: Refined Shizuku uninstall intent broadcast, fixed icon load issues in the app info dialog, and improved stability.

🧩 **Thor v1.95.2 — Per-Component Control**

Dev build — individual components are now yours to open, stop and switch off.

**✨ New:**

• 🧩 **Per-component control** — open an activity, force-open the ones a normal launch cannot reach, stop a running service, or switch off a component you do not want. App Info → Components.

• ↩️ **Restore all** — puts every component Thor disabled back the way it shipped. Thor only ever touches what it changed itself.

• 🔒 Needs **root** or a **root-mode Shizuku**. Shell-mode Shizuku and Dhizuku cannot write component state, and the screen says so.

**🛠 Fixed:** silent install falling through to the system dialog on Shizuku and Dhizuku; a failed Freezer watchlist write taking the app down; error toasts printing a class name instead of the reason.

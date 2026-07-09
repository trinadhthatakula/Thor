# Thor v1.91.2 Release Notes

**Extension updates** — the in-app store now knows when an installed extension has a newer version and offers a one-tap **Update**, instead of just showing *Installed*.

## What's Changed

### 🧩 Extensions store — update detection
*   The **Settings → Extensions → Browse** store now compares each installed extension against the catalog and shows an **Update** button when a newer version is published. Previously it only ever said *Installed*, with no way to know an update existed.
*   Tapping **Update** runs the exact same verified path as a fresh install — **download → SHA-256 → signature check → in-place upgrade** — and the card flips back to *Installed* the moment it finishes.
*   A source-only or not-yet-published entry never shows a dead Update button; it falls back to the plain *Installed* / *build it yourself* state.

### 🔐 Verified extensions hardened (updates waiting in the store)
The two verified extensions got a security + reliability pass and are ready to update from the store:
*   **Strombringer 1.00.1** — its config store now rejects **any** cross-process write, so nothing but Strombringer's own screen can flip the CorePatch (signature-bypass) switch; config writes moved off the UI thread.
*   **Thor Cluster Automator 1.00.3** — its config provider now allowlists callers, refuses to clobber your saved clusters on a malformed write, and reads them off the UI thread.

## 📦 Build
*   Version bumped to **1.91.2 (1912)**.
*   The catalog schema gains a `versionCode` field (published by CI) so the store can compare versions numerically.
*   The base app still carries **no Xposed / signature-bypass code** — those capabilities live entirely inside extensions, so both the FOSS and Play builds stay clean.

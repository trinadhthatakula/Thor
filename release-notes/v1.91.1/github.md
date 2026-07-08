# Thor v1.91.1 Release Notes

**Extensions, for real** — browse and install verified add-ons from an in-app store, each with its own configuration screen. Power-user only, and gated behind a clear, one-time consent.

## What's Changed

### 🧩 Extensions — an in-app store (browse → verify → install)
Thor's Extensions feature graduates from a hidden toggle to a proper, trust-anchored experience:

*   **Browse store.** A new **Settings → Extensions → Browse** screen fetches the official catalog and lets you install verified extensions right from Thor — it **downloads the APK, verifies its SHA-256 and signature, then installs** it. Extensions already on your device show as *Installed*; source-only ones are marked *build it yourself*.
*   **Each extension configures itself.** Extensions now render their **own** settings UI in their **own** process (Thor just launches it), so an extension's screen can't destabilise Thor — and it's themed to match Thor (light / dark / system / AMOLED / dynamic colour) automatically.
*   **A real trust anchor.** Release Thor loads **only** extensions signed by the dedicated *Thor Extensions* key (pinned in an allowlist); every store download is integrity-checked (SHA-256) **and** signer-verified before it can install. Fail-closed at every step.

### 🔐 Deliberately power-user
*   The **Extensions** section in Settings now appears **only when you have an active privilege** (Root / Shizuku / Dhizuku), so it stays out of the way for everyone else.
*   Opening the Extension Manager shows a **one-time consent**: a plain-language disclaimer (extensions aren't always ours; we verify but it's powerful, risky territory; Thor isn't liable for damage) plus a quick confirmation you have to complete to continue.

### 📦 Two extensions available now
*   **Strombringer** — auto-unfreeze a suspended app when you tap its launcher icon *(requires LSPosed)*.
*   **Thor Cluster Automator** — group apps into clusters and freeze / unfreeze them on a schedule or from a home-screen shortcut.

### 🥚 Misc
*   The home-title tap easter egg no longer unlocks Extensions (they're privilege-gated now). It just counts up — and if you *really* keep going past 100, it tips its hat and tells you to stay tuned.

## 📦 Build
*   Version bumped to **1.91.1 (1911)**.
*   The base app carries **no Xposed / signature-bypass code** — those capabilities live entirely inside extensions, so both the FOSS and Play builds stay clean.
*   Builds against the Compose-free **thor-extension-api 3.0.0**.

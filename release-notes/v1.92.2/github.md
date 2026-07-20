# Thor v1.92.2 Release Notes

A polish-and-hardening release. The Home screen gets a redesigned, fully-adaptive **bento** action grid with one-tap access to the **Extension Manager**; landscape, tablet and foldable layouts are fixed across the app; and — under the hood — a **66-finding full-codebase audit** was remediated (crashes, memory/binder leaks, background ANRs, and a Clean-Architecture domain-purity pass). Plus a freezer fix and translations into four new languages.

---

## What's Changed

### 🎨 Redesigned Home — Bento Actions & Extension Manager (#260)
* **Adaptive bento grid**: Home's stacked action cards are now a responsive 2×2 "bento" grid (`BentoTile` + `HomeActionsBento`), where a row collapses to full-width when a tile isn't applicable (`3323f10`, `bf69d7d`, `e55af89`, `93c9242`).
* **Extension Manager on Home**: a new **Extensions** tile opens the Extension Manager straight from Home (previously buried under Settings), shown only when a privilege (Root/Shizuku/Dhizuku) is active. Back-navigation now correctly targets the active tab's back stack (`1829ec5`).
* **Truly adaptive**: the layout adjusts across compact / medium / expanded window sizes and caps its content width on large screens so it never sprawls (`1ab4a9f`).

### 📱 Landscape, Tablet & Foldable Fixes (#260, #261)
* **Scrollable bottom sheets**: the app-info, export, freezer-settings and installer sheets now scroll, so their actions are never cut off in landscape on phones (`b72500a`); the installer also resets its scroll position on each install-state change (`cdb1659`).
* **Support & Community card**: its margins now align with the distribution chart, and its Play Store / GitHub / Telegram buttons stack neatly instead of truncating on narrow panes (`0c1ca05`, `34aeb12`).

### 🧊 Freezer Fix (#259)
* Freezing one of **your own** apps no longer shows the alarming "Freeze System App?" warning — that safety prompt is now correctly limited to actual system apps (`48a9539`).

### 🌍 Localization (#259)
* **68 strings** translated into **Arabic, Spanish, French and Chinese (Simplified)** (`5780379`), plus lint cleanup and removal of verified-unused resources (`8ccf405`, `8ed10db`, `f73d202`, `feaf201`).

### 🛡️ Stability, Leaks & Architecture — Codebase-Audit Remediation (#256, #257, #258)
A full-codebase audit surfaced 66 issues; this release resolves them:
* **Crashes & robustness**: fixed an app-list rotation crash, a root-bind deadlock, and an extensions-screen crash; hardened the privilege gateways and freezer tile service (`9e8eb39`).
* **Memory / binder leaks**: fixed installer event-bus bitmap retention, per-tick coroutine-scope churn, and abandoned PackageInstaller sessions (`9e8eb39`, `d024694`).
* **No more background ANRs / jank**: one-off UI events moved to a lifecycle-safe buffered channel, all blocking I/O moved onto injected IO dispatchers, and framework types pulled out of ViewModels (`b5056de`, `7711f3e`, `6c84683`).
* **Installer hardening**: avoids `TransactionTooLargeException` when listing extensions (`537b37a`) and batch-1 medium fixes across install/enumeration paths (`60d10f7`).
* **Clean-Architecture domain purity**: the domain layer is now free of Android framework types — icons load via a cache path + Coil, install intents ride a data-layer holder, dead models removed, and a single source of truth for preferences (`96bd218`, `eba85d8`, `aa9239c`, `b67aea8`, `0893980`, `3a798c7`).
* Plus numerous review-driven fixes across multiple gemini-code-assist rounds and holistic self-reviews.

---

## 🛠 Commits Log (`v1.92.1...HEAD`)

**Merged pull requests**
* `3f68fcb` — #261 make bottom sheets vertically scrollable in landscape
* `afcd353` — #260 adaptive Home bento actions grid + Extension Manager entry
* `55fb583` — #259 freezer freeze-dialog fix + lint cleanup + 68 translations
* `c24d991` — #258 A3 domain-layer purity refactor
* `68230ff` — #257 audit A-series (events/dispatchers/VM framework-types) + P2 tail
* `b9269a1` — #256 audit P0/P1 + leaks + robustness + batch-1 mediums

**Key commits**
* `93c9242` feat(home): pure bento-row logic (+unit test)
* `e55af89` feat(home): size-adaptive BentoTile component
* `bf69d7d` feat(home): HomeActionsBento adaptive grid
* `3323f10` feat(home): render actions as bento; add Extension Manager tile; drop ActionCard
* `1829ec5` feat(home): open Extension Manager from Home; active-stack back handling
* `1ab4a9f` fix(home): EXPANDED width cap applied correctly
* `0c1ca05` / `34aeb12` fix(home): Support & Community card alignment + button stacking
* `b72500a` fix(ui): scrollable bottom sheets; `cdb1659` fix(installer): reset sheet scroll on state change
* `48a9539` fix(freezer): don't show "Freeze System App?" for user apps
* `5780379` i18n: translate 68 strings into ar/es/fr/zh-rCN
* `96bd218`…`3a798c7` refactor(audit): A3 domain purity (stages 1–6)
* `b5056de` / `7711f3e` / `6c84683` refactor(audit): A-series + P2 tail
* `60d10f7` fix(audit): batch-1 medium findings; `9e8eb39` fix(audit): P0/P1 findings

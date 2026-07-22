# Thor v1.92.3 Release Notes

A lean-and-reliable release. The direct-download APK is **nearly half the size**, the **app-suspend feature is fixed on release builds**, and Thor's in-house root engine — **Odin**, our `libsu` fork — is now a published open-source library with a **hang-proof root probe** and honest shell exit codes.

---

## What's Changed

### 📦 Much Smaller Download — foss APK 6.23 MB → 3.24 MB (−48%)
* The direct-download APK now **compresses its `classes.dex`** (`useLegacyPackaging`), the **foss build is obfuscated** (matching the store build), and foss ships **only its bundled locales** — together nearly halving the foss-release APK (`38d875b`).
* The bundled fonts were also subset, trimming ~180 KB with no glyph regressions (`38d875b`).
* **Note:** Google Play App Bundles are delivered per-device and are, by design, largely unaffected by the dex-packaging lever — this size win is biggest for the GitHub / direct-download APK.

### 🐛 App Suspend Fixed on Release Builds (#265)
* Suspending / unsuspending apps silently failed on **release / Play** builds: R8 renamed bundled framework shadow classes, so the runtime calls hit the real framework classes and threw. The shadows are removed and the root-service daemon is kept intact, so **suspend works again on shipped builds** (`77ec023`).

### 🔧 Odin — Root Engine Extracted & Hardened (#264, #266)
* Thor's in-house `libsu` fork is now the standalone, Apache-2.0 **Odin** library, published to Maven Central as **`com.trinadhthatakula:odin:1.0.0`**. The `:suCore` module is gone and Thor consumes the published dependency (`aacc18e`).
* **Hang-proof root probe**: root detection can no longer wedge the app — a failed shell init is signalled via `onShellDied` and the `isRootGranted` probe is time-bounded, and it correctly rethrows cancellation (`0560e93`, `ec75323`).
* **Honest exit codes**: root shell commands now return the **real** process exit code — matching the Shizuku/Dhizuku gateways — instead of a hard-coded success (`aacc18e`).
* **Tighter root IPC**: added a reusable `RootService.enforceCaller()` so only Thor can bind the privileged service (`c77c668`).

### 🧹 Polish — Lint, Qodana & Leaner Vectors (#268, #269)
* Resolved the outstanding **Android lint + Qodana** findings: correct `<plurals>` handling, unused-resource cleanup, `mipmap-anydpi` rename, and coroutines bumped to 1.11.0 (`979f937`).
* **Losslessly compacted** the two largest vector drawables (`thor_mono`, `thor_drawn_foreground`) — **−6.8 KB** with **pixel-identical** rendering, verified by render-diff (`AE == 0`) at multiple sizes (`7055d50`, `7903a07`).

---

## 🛠 Commits Log (`v1.92.2...HEAD`)

**Merged pull requests**
* `9b1e1f3` — #269 losslessly compact the long `VectorPath` drawables
* `fbc8a8e` — #268 resolve Android lint + Qodana findings
* `11188cd` — #266 Odin Phase 3: consume published `com.trinadhthatakula:odin:1.0.0`
* `47e51b4` — #265 fix release-only app suspend broken by R8 (RootService daemon)
* `f7cc74d` — #264 Odin Phase 1: MainShell hang fix + move `ThorRootService` into `:app`

**Key commits**
* `38d875b` perf(app): shrink foss-release APK 6.23 MB → 3.24 MB (−48%) — dex packaging + foss obfuscation + locale/font trim
* `aacc18e` feat(odin): consume published Odin 1.0.0; delete `:suCore`; real shell exit codes
* `77ec023` fix(app): fix release-only app suspend broken by R8
* `c77c668` refactor(sucore): move `ThorRootService` into `:app`; add `RootService.enforceCaller()`
* `0560e93` fix(sucore): signal shell-init failure via `onShellDied` + bound `isRootGranted` so it can't hang
* `ec75323` fix(sucore): rethrow `CancellationException` in the `isRootGranted` probe
* `e58a5f1` chore(sucore): declare kotlinx-coroutines dep + relicense Odin to Apache-2.0
* `979f937` chore(lint): resolve Android lint + Qodana findings (plurals, resources, coroutines 1.11.0)
* `7055d50` / `7903a07` perf(res): losslessly compact `thor_mono` / `thor_drawn_foreground` pathData

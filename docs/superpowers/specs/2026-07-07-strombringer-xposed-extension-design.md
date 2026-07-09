# Strombringer — Thor's Xposed Extension — Design Spec

**Date:** 2026-07-07
**Status:** Design (pending user review)
**Related:** Spec B — [Thor-Extensions distribution](2026-07-07-thor-extensions-distribution-design.md); builds on [Freezer suspend mode (#239)](2026-07-06-freezer-suspend-mode-design.md).

## 1. Goal

Ship **Strombringer**, an optional, LSPosed-activated **Xposed module** that adds three power-user capabilities on top of Thor, while keeping the **base Thor app (both `foss` and `store` flavors) 100% free of Xposed code** so the Play build carries zero policy risk:

1. **Auto-unfreeze suspended apps** — tapping a suspended app's real launcher icon unsuspends and opens it (no system "paused" dialog).
2. **Signature-bypass + downgrade** — install a mismatched-signature update or an older version without uninstalling (data-loss-free). Delivered by a **fork of CorePatch** (GPLv3).
3. **Installer spoof** — make a chosen app report `getInstallerPackageName() == com.android.vending`, without reinstalling.

Strombringer is **headless**: no launcher activity, no standalone UI. It is discovered, configured, and controlled **only through Thor**.

## 2. Non-negotiable constraints

- **Base Thor stays Xposed-free.** No `xposed_init`, no `xposedmodule` meta-data, no Xposed API dependency, no danger-zone UI in `:app`. The `store` APK is byte-for-byte unaffected by this feature except the small, store-safe `ExtensionManager` trust change (a signature check). The `INTERNET` permission moves to the shared `app/src/main/AndroidManifest.xml` for both flavors (§4.2) — net-new for `foss`, but the `store` manifest already declared it, so the store APK is unchanged.
- **GPLv3** — Thor and all extensions are GPLv3; the CorePatch fork inherits it. No license conflict.
- **No `extension-api` changes** for v1 (verified against the published contract).

## 3. Architecture — the dual-role APK

Strombringer is one installed APK that plays two independent roles, loaded by two different loaders into different processes:

| Role | Loaded by | Runs in | Entry point |
|---|---|---|---|
| **Thor extension** (config surface) | Thor `PathClassLoader` | Thor's process | class named in `metaData["thor.extension.class"]`, implements `AutomationExtension` |
| **LSPosed module** (the hooks) | LSPosed | launcher / `system_server` / target apps | `assets/xposed_init` → `IXposedHookLoadPackage` |

The two roles share **the extension's own config store** (a `ContentProvider` + prefs Strombringer owns). Thor's config UI writes it; the hooks read it via `XSharedPreferences`. Thor's loader ignores the Xposed manifest bits; LSPosed ignores the Thor meta-data.

```
User taps a suspended app
  → Strombringer launcher-hook (in launcher proc) sees a suspended target
  → calls Thor's unsuspend ContentProvider (Thor holds root/Shizuku/Dhizuku privilege)
  → Thor unsuspends via its gateway → launch proceeds to the now-active app
```

## 4. Base-Thor changes (in `:app`, both flavors, store-safe)

### 4.1 `ExtensionManager` trust: same-signature → pinned allowlist
**Problem:** Thor ships under two signatures (Play App Signing key vs. FOSS release key), so the current `isSignatureVerified()` (compare extension cert to *Thor's own* cert, `ExtensionManager.kt:50-74`) can never hold for both. **Fix:** trust extensions signed by a **pinned, dedicated "Thor Extensions" signing certificate**, independent of Thor's own signature.

- Add a constant allowlist of trusted signer SHA-256 cert hashes (the dedicated extension key's cert — see Spec B §2), baked into both flavors (e.g. `res/values/` string-array or a `BuildConfig` field).
- Rewrite `isSignatureVerified(packageName)` to: read the extension's signing cert (`GET_SIGNING_CERTIFICATES`), SHA-256 it, return `hash in ALLOWLIST`.
- **Dev ergonomics:** in `BuildConfig.DEBUG`, *also* accept a cert equal to Thor's own (so debug-key local extensions still load without the release extension key).
- Everything else (`thor.extension.class` discovery, `PathClassLoader` load) is unchanged.

### 4.2 foss gets `INTERNET`
Move `<uses-permission android:name="android.permission.INTERNET"/>` into `app/src/main/AndroidManifest.xml` (both flavors) and drop the now-redundant one in `app/src/store/AndroidManifest.xml`. Needed for the in-app extension browser (Spec B). *(Store already declared it; net effect on store = none.)*

### 4.3 The `unsuspend` `ContentProvider`
A base-app provider — **no Xposed code** — that Strombringer's launcher hook calls to unsuspend an app (Thor is where the privilege lives).

- Authority e.g. `com.valhalla.thor.freezer.bridge`; a `call("restore", pkg)` method → `manageAppUseCase.restoreApp(pkg, …)` (reuses the #239 gateway).
- **Caller-identity reality:** the hook runs in the *launcher's* process, so `Binder.getCallingUid()` is the launcher, not Strombringer — we cannot cryptographically prove "the trusted extension called us." We bound the risk instead:
  1. The provider **only restores packages currently in the user's freezer** (it can't touch arbitrary app state).
  2. Restrict callers to system/home-app UIDs.
  3. Unsuspending a frozen app is inherently low-impact (worst case: an app you froze gets unfrozen).
- `exported=true` with a custom `android:permission` is *not* used for trust (signature perms require same-signer, which the extension isn't) — gating is the freezer-scope + caller checks above.

## 5. Strombringer module (`verified/strombringer/`, new)

### 5.1 Manifest (dual-role, headless)
- Thor-extension meta-data: `<meta-data android:name="thor.extension.class" android:value="…StrombringerExtension"/>`.
- Xposed meta-data: `xposedmodule=true`, `xposeddescription`, `xposedminversion`, `xposedscope` (launchers + `android` + user-selected).
- `assets/xposed_init` → the `IXposedHookLoadPackage` FQN.
- **No launcher `<activity>`** with `MAIN/LAUNCHER` → nothing to open standalone.

### 5.2 Config store
- A `ContentProvider` Strombringer owns (`call()` get/set) persisting to prefs; hooks read via `XSharedPreferences`.
- Keys: `autoUnfreeze: Boolean`, `sigBypass: Boolean` (default false), `downgrade: Boolean`, `spoofInstaller: Set<pkg>`.
- A **self-hook** flips a static "active" flag Thor reads (so Thor shows "installed & LSPosed-active").

### 5.3 Thor-side config UI
`StrombringerExtension : AutomationExtension` — `ConfigurationScreen(shellExecutor, dataStore, onBack)` renders **inside Thor** (existing framework), reads/writes the config store via `LocalContext.current.contentResolver.call(strombringerAuthority, …)`. This is the danger-zone screen; it lives in the extension APK (outside Play), not in `:app`.

### 5.4 Hooks
| Hook | LSPosed scope | Does |
|---|---|---|
| **Auto-unfreeze** | launchers | hook `Activity.startActivityForResult` / `ContextWrapper.startActivity` (Hail-proven, *public* API — low fragility); if target `isPackageSuspended` and `autoUnfreeze` on → call Thor's `restore` provider, then let the launch proceed |
| **Sig-bypass + downgrade** | `android` (system_server) | **forked CorePatch** hooks on the signature-verify / downgrade paths, gated on `sigBypass`/`downgrade` prefs (default off) |
| **Installer spoof** | user-selected packages | hook `PackageManager.getInstallerPackageName` / `getInstallSourceInfo` → return `com.android.vending` for `spoofInstaller` packages |

The **auto-unfreeze** hook is the *tractable* one (public API, no system_server internals); the sig-bypass is the fragile forked-CorePatch surface.

## 6. Config bridge (Thor UI ↔ hooks)
`ConfigurationScreen` runs as Thor's UID, so `ExtensionDataStore` (Thor's storage) can't reach the hooks. Instead the UI calls **Strombringer's own `ContentProvider`** (cross-process to Strombringer), which persists to Strombringer's prefs; hooks read via `XSharedPreferences`. No `extension-api` change; no shared secret.

## 7. Security & safety
- **Sig-bypass / downgrade default OFF**, behind a confirm dialog + "Thor is not responsible" warning (per product decision); each is an explicit manual toggle.
- Installer spoof is per-package opt-in.
- The base-app `restore` provider is freezer-scoped + caller-restricted (§4.3).
- Strombringer is Thor-Extensions-key-signed → passes the §4.1 allowlist; a repackaged/untrusted build won't load in Thor.

## 8. Build order (proves the risky parts last)
1. **Auto-unfreeze slice** — base-app `restore` provider + `ExtensionManager` trust change + foss `INTERNET` + a minimal Strombringer with the launcher hook + config store + `ConfigurationScreen`. End-to-end proof of the dual-role + IPC + trust wiring.
2. **Installer spoof** hook + its toggle.
3. **Forked CorePatch** sig-bypass/downgrade (the fragile part) + danger-zone toggles/warnings.

## 9. Testing
- **Base Thor:** unit-test the SHA-256 allowlist match in `isSignatureVerified` (pure); on-device: verified extension loads under both a release-key Thor and a debug-key Thor; an untrusted-signed extension is rejected; the `restore` provider only touches freezer packages.
- **Strombringer:** device matrix — tap a suspended app → unsuspends + opens; installer spoof flips `getInstallerPackageName` for a chosen app; sig-bypass installs a mismatched-signature update (danger-zone) on the target Android version.
- **Store build regression:** confirm the `store` APK gains nothing but the signature-check change (no Xposed refs, no danger-zone UI).

## 10. Out of scope (v1)
- Force-grant restricted permissions (root already covers it).
- The third-party *untrusted*-extension opt-in trust flow (danger-zone "trust this cert") — future.
- Multi-user / work-profile suspension nuances beyond current freezer behavior.

## 11. Open risks (honest)
- **Forked CorePatch maintenance** — re-sync per Android version; this is the accepted cost of the "integrate, don't build" decision.
- **system_server hook fragility** — sig-bypass may break on new Android releases until re-synced; auto-unfreeze (public-API launcher hook) is far more stable.
- **`restore` provider exposure** — mitigated (freezer-scoped, low-impact), not cryptographically caller-verified; documented.

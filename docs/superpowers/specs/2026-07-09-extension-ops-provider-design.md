# Extension Ops ContentProvider — retire in-host `onTrigger` for a privileged-ops handoff

**Date:** 2026-07-09
**Status:** Approved (design)
**Repos:** `Thor` (provider + retirement), `Thor-Extensions` (automation extension)
**Central releases consumed:** 0 (no `thor-extension-api` change)

## 1. Problem

The "Thor Cluster Automator" extension's freeze does nothing. Root-cause investigation
(systematic-debugging) found a 4-layer cascade, all device-verified:

1. **Signature permission** — the extension is signed with the dedicated "Thor Extensions" key, not
   Thor's app key, so it could never hold Thor's `signature`-level `TRIGGER_EXTENSION` permission →
   its trigger broadcast to `ExtensionTriggerReceiver` was dropped.
2. **Thor missing `<uses-permission>`** — Thor *defined* `TRIGGER_EXTENSION` but never *held* it, so
   even the `sendBroadcast(intent, TRIGGER_EXTENSION)` `receiverPermission` check failed.
3. **MIUI/HyperOS autostart** — with Thor's process dead, the OS refuses to cold-start it to deliver
   a manifest broadcast (`BroadcastQueueInjector: process is not permitted to auto start`).
4. **`AbstractMethodError` / `NoSuchMethodError` — the real crash.** When `onTrigger` *is* reached,
   it throws. `onTrigger` is a `suspend fun`, so its compiled signature ends in a
   `kotlin.coroutines.Continuation`. The extension bundles + R8-minifies kotlin-stdlib, renaming
   `Continuation` (dexdump: `Lqz;`). Thor invokes `onTrigger(…, kotlin.coroutines.Continuation)`
   (its own, kept name). Across the classloader boundary those are different types →
   `AbstractMethodError`. Keeping the class name un-renamed only exposed the next mole
   (`Continuation.getContext()` renamed to `i()` → `NoSuchMethodError`) — the exact keep-rule
   whack-a-mole that killed the in-host `@Composable ConfigurationScreen`.

The coupling is pervasive: **every** cross-boundary api method is `suspend` (`onTrigger`,
`ShellExecutor.execute`, all of `ExtensionDataStore`), so any suspend call across two
independently-minified kotlin runtimes breaks.

## 2. Decision — Path B (privileged-ops ContentProvider)

The durable fix is to **stop running the extension's code inside Thor's process**, the same move that
retired `ConfigurationScreen`. Thor exposes a caller-verified `ContentProvider` for privileged
package operations; the extension *calls* it with a package list, and **Thor** does the work with its
own runtime. Rejected alternative: making the whole api non-suspend/blocking (A′) — a breaking
`thor-extension-api` major, keeps the fragile "run foreign code in our process" model, and doesn't fix
the MIUI autostart problem.

Path B fixes all four layers at once: no cross-classloader code (kills #4), a provider access reliably
cold-starts Thor (kills #3), and `getCallingPackage()` + pinned-signer verification replaces the
signature-level permission (kills #1/#2).

## 3. Architecture

```
Extension ConfigActivity "Freeze/Unfreeze"  ─┐
Extension AlarmReceiver (9 PM schedule)      ─┤─► resolve cluster → package list
                                              └─► contentResolver.call(
                                                    content://<thorPkg>.extensionops,
                                                    method = "freeze" | "unfreeze" | "toggle",
                                                    extras = { "packages": String[] })
                                                      │ (provider access cold-starts Thor's process —
                                                      │  fixes the MIUI dead-process case)
                                                      ▼
   Thor ExtensionOpsProvider.call():
     1. verify caller: getCallingPackage() ∈ com.valhalla.thor.ext.* AND
        ExtensionManager.isSignatureVerified(caller)  (relaxed in debug)  → else { ok=false }
     2. Binder.clearCallingIdentity()  → run under Thor's own identity
     3. drop guarded packages (Thor itself, the caller), then run the op via Thor's own
        ManageAppUseCase (its own coroutines, no cross-classloader)
     4. return { "ok": true, "count": N }
```

**Transport:** the extension sends the **actual package list** (not a cluster reference), so Thor
never reaches back into the extension's config store and the provider is a general-purpose privileged
package-ops surface. Both extension entry points (`ConfigActivity`, `AlarmReceiver`) run in the
extension's process and already own the cluster→packages mapping.

## 4. `ExtensionOpsProvider` (Thor)

- **Authority:** `${applicationId}.extensionops` (`com.valhalla.thor.extensionops`, `…debug.extensionops`).
  Exported; no `query`/`insert`/etc — only `call`.
- **Methods** (`extras = { "packages": String[] }`, returns `{ "ok": Boolean, "count": Int }`):
  - `freeze` — freeze each package.
  - `unfreeze` — `ManageAppUseCase.forceUnfreeze` each (unsuspend **and** enable).
  - `toggle` — determine the cluster's frozen state, then freeze-all or unfreeze-all.
- **Mode-aware freeze:** honors Thor's global `freezerMode` (via `PreferenceRepository`), matching
  manual freezing and the launcher shortcuts: `freeze` = `setAppSuspended(pkg, true)` in SUSPEND mode
  else `setAppDisabled(pkg, true)`. (Behaviour change vs the old `pm disable-user`, which ignored
  Suspend mode.) `toggle` uses the `isFrozen(enabled, isSuspended)` predicate from `FreezerMode.kt`.
- **Caller verification:** `getCallingPackage()` must start with `com.valhalla.thor.ext.` **and** pass
  `ExtensionManager.isSignatureVerified` (pinned-cert). `BuildConfig.DEBUG` relaxes the signature check
  (self-built extensions). A null/own-package caller is allowed. Unauthorized → `{ ok=false }`, no work.
- **Guardrail (minimal):** refuse to operate on Thor's own package (`com.valhalla.thor[.debug]`) and
  the calling extension's package. No broader denylist (Thor's own freezer already lets users freeze
  system apps; the extension is trusted + the packages are user-configured).
- **Privilege / threading:** `Binder.clearCallingIdentity()` around the work; the `call()` runs on a
  Binder thread and drives the ops via `runBlocking { … }` over Thor's own `ManageAppUseCase`
  (its own coroutines — nothing crosses a classloader). Synchronous so the caller gets a real result.
  Clusters are small, so Binder-call duration is a non-issue.
- **Purity for tests:** extract the authorization decision (`isAuthorizedCaller(pkg, verified, debug)`)
  and the mode-aware freeze decision as pure functions (mirrors `RestoreGate`/`FreezerMode`).

## 5. Extension changes (`Thor-Extensions/verified/thor-automation-extension`)

- **`AutomationCluster`** drops `AutomationExtension`/`onTrigger`/`readSavedClusters` and becomes a
  plain `ThorExtension` (metadata only, like `StrombringerExtension`). It remains the
  `thor.extension.class` for discovery + the Configure button.
- **New `ThorOps` client** (local to the extension — plain strings, no api dependency): resolves the
  live Thor package (tries `com.valhalla.thor`, then `…debug`, like `Config.THOR_PACKAGES`) and issues
  `contentResolver.call("content://<pkg>.extensionops", action, null, { packages })`, off the UI thread.
- **`ConfigActivity`** — the Freeze/Unfreeze actions resolve the cluster's packages and call `ThorOps`
  instead of `triggerThor(broadcast)`.
- **`AlarmReceiver`** — reads the cluster's packages from the extension's own prefs (`Config.PREFS` →
  `saved_clusters` JSON → find by name) and calls `ThorOps` directly.
- **Removed:** the `triggerThor` broadcast, the extension manifest's
  `<uses-permission TRIGGER_EXTENSION>` (calling an exported provider needs no permission; the
  extension already has `QUERY_ALL_PACKAGES` to resolve it).
- **versionCode/versionName bump** → re-released via **Thor-Extensions GitHub CI** (not Maven Central).

## 6. Thor retirement + no api change

- **Add** `ExtensionOpsProvider` + its manifest `<provider>` entry.
- **Retire the broadcast path:** delete `ExtensionTriggerReceiver` (+ manifest entry) and the
  `TRIGGER_EXTENSION` `<permission>` (and its receiver `android:permission`). Remove
  `ThorShellExecutor` / `RoomExtensionDataStore` **iff** the trigger receiver was their only consumer
  (verify at implementation time).
- **`thor-extension-api`: no change, no Central release.** The ops contract is plain strings shared by
  Thor + the extension and documented in `Thor-Extensions/CONTRIBUTING.md` for third-party authors.
  `AutomationExtension`/`ShellExecutor`/`ExtensionDataStore` stay in api 3.0.0 (unused-but-present);
  `@Deprecated` markers are deferred to the next *natural* api publish. **Central budget preserved
  (5/7 used this month).**
- **Thor versionCode bump** → normal Thor release.

## 7. Testing

- **Unit (Thor):** `isAuthorizedCaller` (ext-prefix + pinned + debug-bypass matrix) and the mode-aware
  freeze/toggle decision (pure functions).
- **Device (the real proof):** create a cluster → **Freeze** suspends or disables per the active mode;
  **Unfreeze** restores; **toggle** flips; the **9 PM alarm** path; and the critical one —
  **freeze works with Thor's process killed** (the provider cold-starts it), which the broadcast never
  could on MIUI. Guardrail: a cluster containing `com.valhalla.thor` is a no-op for that package.

## 8. Scope / notes

- **Strombringer unaffected** — it's a plain `ThorExtension` (metadata only), never used `onTrigger`.
- **Reverts** the uncommitted `knownSigner`/`uses-permission` work (branch deleted) — superseded.
- **Device cleanup** during verification: unfreeze the `AutoTest`/AClock test artifact; the device's
  Thor 1913 (permission-fix build) is replaced by the new Thor release.
- **Out of scope:** a broader op set (suspend/unsuspend/clear-cache) — add later if an extension needs
  it; the api deprecation cleanup; migrating the template/sample away from `onTrigger`.
